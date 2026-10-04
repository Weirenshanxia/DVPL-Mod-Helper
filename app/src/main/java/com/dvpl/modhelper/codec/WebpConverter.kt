package com.dvpl.modhelper.codec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

/** WebP 压缩策略: AUTO=无损与有损都编一次取更小者(仅 API 30+), LOSSY=有损, LOSSLESS=无损 */
enum class WebpStrategy { AUTO, LOSSY, LOSSLESS }

/**
 * 通用图像 → 标准 WebP 转换（纯平台 API，零外部库）。
 * 输入支持: DVPL 包裹(自动剥壳)、PVR(ASTC/未压缩)、DDS(BC1-7, 含 DXT5nm 自动还原)、
 * 以及 BitmapFactory 全家桶(PNG/JPEG/BMP/GIF/WEBP/HEIF 等)。
 */
object WebpConverter {

    // ---------- 静态图 ----------

    /** 位图 → WebP 字节。AUTO 策略在 API 26-29 上自动降级为有损（平台无无损编码器）。
     *  AUTO 的无损/有损双编码并行跑（读同一位图，Skia compress 只读不改），墙钟时间减半 */
    fun encodeImage(bitmap: Bitmap, strategy: WebpStrategy, quality: Int): ByteArray {
        val q = quality.coerceIn(1, 100)
        return when (strategy) {
            WebpStrategy.LOSSY -> compress(bitmap, false, q)
            WebpStrategy.LOSSLESS -> compress(bitmap, true, q)
            WebpStrategy.AUTO -> if (Build.VERSION.SDK_INT >= 30) {
                // 双编码并行: 副线程压位图副本, 杜绝两线程并发读同一位图的隐患（副本是纯 memcpy, 开销小）
                val copy = try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Exception) { null }
                val lossyBox = arrayOfNulls<ByteArray>(1)
                val t = Thread { lossyBox[0] = compress(copy ?: bitmap, false, q) }
                t.isDaemon = true
                t.start()
                val lossless = compress(bitmap, true, q)
                t.join()
                copy?.recycle()
                val lossy = lossyBox[0] ?: compress(bitmap, false, q)
                if (lossless.size < lossy.size) lossless else lossy
            } else {
                compress(bitmap, false, q)
            }
        }
    }

    /** 视频动画帧编码（恒有损，动画体积优先） */
    fun encodeFrame(bitmap: Bitmap, quality: Int): ByteArray =
        compress(bitmap, false, quality.coerceIn(1, 100))

    private fun compress(bitmap: Bitmap, lossless: Boolean, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(maxOf(4096, bitmap.width * bitmap.height / 2))
        val fmt = if (Build.VERSION.SDK_INT >= 30) {
            if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        if (!bitmap.compress(fmt, quality, out)) throw IllegalStateException("webp encode failed")
        return out.toByteArray()
    }

    // ---------- 解码 ----------

    /** 任意图像格式 → 位图。返回 null = 解码失败（不支持的格式或损坏）。 */
    fun decodeToBitmap(data: ByteArray, fileName: String): Bitmap? {
        var payload = data
        if (DvplCodec.isDvplFile(data)) payload = DvplCodec.decode(data)
        if (PvrConverter.isPvrFile(payload)) {
            return PvrConverter.decodeToBitmap(payload)
        }
        if (payload.size >= 4 && payload[0] == 'D'.code.toByte() && payload[1] == 'D'.code.toByte() &&
            payload[2] == 'S'.code.toByte() && payload[3] == ' '.code.toByte()) {
            return DdsConverter.decodeToBitmap(payload, fileName)?.first
        }
        // inPremultiplied=false: 半透明 PNG→WebP 保持直通 alpha, 不被预乘压暗
        return BitmapFactory.decodeByteArray(payload, 0, payload.size,
            BitmapFactory.Options().apply { inPremultiplied = false })
    }

    /** 估算解码内存（宽×高×4），供并发限流申请 permits；不解码位图本体。 */
    fun estimateDecodeBytes(data: ByteArray): Long {
        var payload = data
        if (DvplCodec.isDvplFile(data)) payload = DvplCodec.decode(data)  // LZ4 解压便宜
        if (PvrConverter.isPvrFile(payload)) {
            val info = PvrConverter.parse(payload)
            if (info != null) return info.width.toLong() * info.height * 4
        }
        if (payload.size >= 20 && payload[0] == 'D'.code.toByte() && payload[3] == ' '.code.toByte()) {
            // DDS 头: width@0x10, height@0x0C
            val w = leU16(payload, 16); val h = leU16(payload, 12)
            if (w > 0 && h > 0) return w.toLong() * h * 4
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) return opts.outWidth.toLong() * opts.outHeight * 4
        return 4096L * 4096 * 4
    }

    private fun leU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    // ---------- 视频抽帧（MediaCodec 硬解快路径） ----------

    /**
     * MediaCodec 顺序解码抽帧（优先硬件解码器），供视频 → 动画 WebP。
     * 对比 MediaMetadataRetriever.getFrameAtTime（每帧 seek 从关键帧重解一遍）典型快 3~10 倍。
     * onFrame 在本函数内同步回调：位图已缩放、已按轨道 rotation 旋转，调用方负责 recycle；
     * 返回 false = 预算已满，停止抽帧（已回调的帧保留）。
     * 函数返回 false = 该路径不适用（无视频轨/不支持图像输出等），调用方回退 retriever 慢路径。
     * onFrame 抛出的异常（含协程取消）原样向上传播。
     */
    fun extractVideoFramesFast(
        context: Context, uri: Uri, targetFps: Int, maxSide: Int, maxFrames: Int,
        onFrame: (Bitmap) -> Boolean
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            // MediaExtractor 无 (Context, Uri) 重载，走 FileDescriptor
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
            extractor.setDataSource(pfd.fileDescriptor)
            var trackIdx = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("video/")) { trackIdx = i; trackFormat = f; break }
            }
            val format = trackFormat ?: return false
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            extractor.selectTrack(trackIdx)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            // KEY_FRAME_RATE 部分设备缺失或存成 String，兜底 30
            val srcFps = try {
                format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 240)
            } catch (e: Exception) { 30 }
            val step = maxOf(1, Math.round(srcFps.toFloat() / targetFps))
            val rotation = try {
                if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            } catch (e: Exception) { 0 }
            val info = MediaCodec.BufferInfo()
            var inEos = false
            var outEos = false
            var stopRequested = false   // 调用方预算满 → 停
            var decoded = 0L
            var kept = 0
            while (!outEos && !stopRequested && kept < maxFrames) {
                if (!inEos) {
                    val idx = codec.dequeueInputBuffer(10_000L)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                    else -> if (outIdx >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                        val img = codec.getOutputImage(outIdx)
                        if (img != null) {
                            if (decoded % step == 0L) {
                                val bmp = imageToBitmap(img, maxSide, rotation)
                                if (bmp != null) {
                                    val keepGoing = onFrame(bmp)
                                    kept++
                                    if (!keepGoing) stopRequested = true
                                }
                            }
                            img.close()
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        decoded++
                    }
                }
            }
            return kept > 0
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WebpConvert", "MediaCodec fast path fallback: " + e)
            return false
        } finally {
            try { codec?.stop() } catch (e: Exception) {}
            try { codec?.release() } catch (e: Exception) {}
            extractor.release()
            try { pfd?.close() } catch (e: Exception) {}
        }
    }

    /** YUV_420_888 → Bitmap（BT.601 limited，行/像素步长通用处理），按需旋转、缩放。返回 null = 不支持。 */
    private fun imageToBitmap(img: Image, maxSide: Int, rotation: Int): Bitmap? {
        if (img.format != ImageFormat.YUV_420_888) return null
        val w = img.width
        val h = img.height
        val planes = img.planes
        val yB = planes[0].buffer; val uB = planes[1].buffer; val vB = planes[2].buffer
        val yRow = planes[0].rowStride; val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride; val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride; val vPix = planes[2].pixelStride
        val px = IntArray(w * h)
        var i = 0
        for (row in 0 until h) {
            val yBase = row * yRow
            val uBase = (row shr 1) * uRow
            val vBase = (row shr 1) * vRow
            val rowEnd = yBase + w * yPix
            var yc = yBase
            var col = 0
            while (yc < rowEnd) {
                val y = (yB.get(yc).toInt() and 0xFF) - 16
                val u = (uB.get(uBase + (col shr 1) * uPix).toInt() and 0xFF) - 128
                val v = (vB.get(vBase + (col shr 1) * vPix).toInt() and 0xFF) - 128
                val r = ((1192 * y + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((1192 * y - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((1192 * y + 2066 * u) shr 10).coerceIn(0, 255)
                px[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                yc += yPix
                col++
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        // 硬解不处理 rotation 元数据，手动转正
        val upright = if (rotation == 90 || rotation == 270) {
            val m = Matrix()
            m.postRotate(rotation.toFloat())
            val r2 = Bitmap.createBitmap(bmp, 0, 0, w, h, m, true)
            if (r2 !== bmp) bmp.recycle()
            r2
        } else bmp
        val longSide = maxOf(upright.width, upright.height)
        return if (maxSide in 1 until longSide) {
            val scale = maxSide.toFloat() / longSide
            val s = Bitmap.createScaledBitmap(upright,
                maxOf(1, (upright.width * scale).toInt()),
                maxOf(1, (upright.height * scale).toInt()), true)
            if (s !== upright) upright.recycle()
            s
        } else upright
    }
}
