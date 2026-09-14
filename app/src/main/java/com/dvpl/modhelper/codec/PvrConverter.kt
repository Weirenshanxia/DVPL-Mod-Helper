package com.dvpl.modhelper.codec

import android.graphics.Bitmap

/**
 * PVR 纹理转换器
 * 支持：ASTC 4x4~12x12（LDR+HDR）+ 未压缩 RGBA8888/R8
 */
object PvrConverter {

    init {
        System.loadLibrary("pvr-codec")
    }

    /** PVR 解析结果 */
    data class PvrInfo(
        val width: Int,
        val height: Int,
        val mips: Int,
        val blockW: Int,
        val blockH: Int,
        val isAstc: Boolean,
        val bpp: Int,
        val dataOffset: Int
    )

    /** ASTC 质量档位 */
    enum class AstcQuality(val blockW: Int, val blockH: Int, val label: String) {
        ASTC_4x4(4, 4, "ASTC 4x4（最高质量）"),
        ASTC_5x5(5, 5, "ASTC 5x5（高质量）"),
        ASTC_6x6(6, 6, "ASTC 6x6（推荐/游戏默认）"),
        ASTC_8x6(8, 6, "ASTC 8x6（中质量）"),
        ASTC_10x5(10, 5, "ASTC 10x5（小体积）"),
        RGBA_8888(0, 0, "不压缩 RGBA8888（无损）"),
        RGBA_4444_PC(0, 0, "RGBA4444（PC 端 DX11 格式）")
    }

    // ===== JNI 声明 =====
    private external fun nativeParsePvr(pvrData: ByteArray): IntArray?
    private external fun nativeDecodePvrMip(pvrData: ByteArray, mip: Int): IntArray?
    private external fun nativeAstcDecode(
        data: ByteArray, w: Int, h: Int, bw: Int, bh: Int, toneMap: Boolean
    ): IntArray?
    external fun nativeAstcEncode(
        pixels: IntArray, w: Int, h: Int, bw: Int, bh: Int, quality: Int
    ): ByteArray?

    /**
     * 解析 PVR 头
     */
    fun parse(pvrData: ByteArray): PvrInfo? {
        val data = unwrap(pvrData)
        val r = nativeParsePvr(data) ?: return null
        if (r.size < 8) return null
        return PvrInfo(r[0], r[1], r[2], r[3], r[4], r[5] == 1, r[6], r[7])
    }

    /**
     * 解码指定 mip → Bitmap
     */
    /** DVPL 包裹检测：.pvr.dvpl 自动解包 */
    private fun unwrap(pvrData: ByteArray): ByteArray =
        if (pvrData.size >= 24 && DvplCodec.isDvplFile(pvrData))
            try { DvplCodec.decode(pvrData) } catch (e: Exception) { pvrData }
        else pvrData

    fun decodeToBitmap(pvrData: ByteArray, mip: Int = 0): Bitmap? {
        val data = unwrap(pvrData)
        val result = nativeDecodePvrMip(data, mip) ?: return null
        if (result.size < 4) return null
        val w = result[0]; val h = result[1]
        val pixels = result.copyOfRange(3, result.size)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Bitmap → PVR 文件字节（ASTC 或 RGBA8888，含 mip 链）
     */
    fun encodeToPvr(bitmap: Bitmap, quality: AstcQuality): ByteArray {
        val w = bitmap.width
        val h = bitmap.height

        // 计算 mip 级数（到 1x1）
        var mips = 1
        var mw = w; var mh = h
        while (mw > 1 || mh > 1) {
            mw = maxOf(1, (mw + 1) / 2); mh = maxOf(1, (mh + 1) / 2)
            mips++
        }

        val isAstc = quality != AstcQuality.RGBA_8888 && quality != AstcQuality.RGBA_4444_PC
        val bw = if (isAstc) quality.blockW else 0
        val bh = if (isAstc) quality.blockH else 0
        val is4444 = quality == AstcQuality.RGBA_4444_PC

        // 生成每一级 mip 的位图并压缩
        val out = java.io.ByteArrayOutputStream()
        var curW = w; var curH = h;
        var curBitmap = bitmap;
        var isFirst = true;
        
        repeat(mips) {
            val pixels = IntArray(curW * curH)
            curBitmap.getPixels(pixels, 0, curW, 0, 0, curW, curH)
            
            if (is4444) {
                // RGBA4444（PC DX11 PVR）：ARGB int → [R<<4|G, B<<4|A] 字节对
                val px = ByteArray(curW * curH * 2)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val r4 = ((p shr 16) and 0xF0) shr 4
                    val g4 = (p shr 12) and 0xF
                    val b4 = (p shr 4) and 0xF
                    val a4 = p shr 28
                    px[i*2] = ((r4 shl 4) or g4).toByte()
                    px[i*2+1] = ((b4 shl 4) or a4).toByte()
                }
                out.write(px)
            } else if (isAstc) {
                val compressed = nativeAstcEncode(pixels, curW, curH, bw, bh, 0)
                    ?: throw IllegalStateException("ASTC 编码失败（${curW}x${curH}）")
                out.write(compressed)
            } else {
                // RGBA8888: ARGB int → RGBA 字节
                val rgba = ByteArray(curW * curH * 4)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    rgba[i*4] = ((p shr 16) and 0xFF).toByte()
                    rgba[i*4+1] = ((p shr 8) and 0xFF).toByte()
                    rgba[i*4+2] = (p and 0xFF).toByte()
                    rgba[i*4+3] = ((p shr 24) and 0xFF).toByte()
                }
                out.write(rgba)
            }
            
            // 下一级 mip
            if (it < mips - 1) {
                val nextW = maxOf(1, (curW + 1) / 2)
                val nextH = maxOf(1, (curH + 1) / 2)
                val scaled = Bitmap.createScaledBitmap(curBitmap, nextW, nextH, true)
                if (!isFirst) curBitmap.recycle()
                curBitmap = scaled
                isFirst = false;
                curW = nextW; curH = nextH;
            }
        }
        if (!isFirst) curBitmap.recycle()

        // 写 PVR v3 头（52 字节）+ 16 字节 CRC metadata
        val textureData = out.toByteArray()
        val header = java.nio.ByteBuffer.allocate(52).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x03525650)   // version "PVR\x03"
        header.putInt(0)            // flags
        if (isAstc) {
            val pfEnum = astcBlockToEnum(bw, bh)
            header.putInt(pfEnum)   // pixelFormat 低32
            header.putInt(0)        // 高32 = 0
        } else if (is4444) {
            // PC DX11 PVR：pfLo="rgba"、pfHi=[4,4,4,4]（实测游戏文件确认）
            header.putInt(0x61626772)
            header.putInt(0x04040404)
        } else {
            // RGBA8888: 低32 = "rgba" 0x61626772, 高32 = [1,1,1,1] 每通道1字节
            header.putInt(0x61626772)
            header.putInt(0x01010101)
        }
        header.putInt(0)            // colorSpace lRGB
        header.putInt(if (is4444) 0 else 4)  // channelType（PC 4444 文件实测为 0）
        header.putInt(h)            // height
        header.putInt(w)            // width
        header.putInt(1)            // depth
        header.putInt(1)            // numSurfaces
        header.putInt(1)            // numFaces
        header.putInt(mips)         // mipMapCount
        val metaSize = if (is4444) 31 else 16
        header.putInt(metaSize)     // metaDataSize
        
        val crc = java.util.zip.CRC32()
        crc.update(textureData)
        val meta = if (is4444) {
            // PC 4444 文件实测 31 字节结构：
            // [PVR\u0003][3,3,0,0 块 16B][PVR\u0003][CRC_][4][CRC32]
            val m = java.nio.ByteBuffer.allocate(31).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            m.putInt(0x03525650)     // "PVR\u0003"
            m.putInt(3)              // blockSize=3（实测）
            m.putInt(3)
            m.putInt(0)
            m.putInt(0)
            m.putInt(0x03525650)     // "PVR\u0003"
            m.putInt(0x5F435243)     // "CRC_"
            m.putInt(4)              // dataSize
            m.putInt(crc.value.toInt())
            m
        } else {
            // 安卓 ASTC 文件实测 16 字节结构（K-91_skin_NM.astc.pvr）
            val m = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            m.putInt(0x03525650)     // "PVR\u0003"
            m.putInt(0x5F435243)     // "CRC_"
            m.putInt(4)              // dataSize
            m.putInt(crc.value.toInt())
            m
        }
        
        val result = java.io.ByteArrayOutputStream()
        result.write(header.array())
        result.write(meta.array())
        result.write(textureData)
        return result.toByteArray()
    }

    /**
     * 检查是否为 PVR 文件
     */
    fun isPvrFile(data: ByteArray): Boolean {
        if (DvplCodec.isDvplFile(data)) return true  // .pvr.dvpl 预览入口兼容
        if (data.size < 4) return false
        return data[0] == 0x50.toByte() && data[1] == 0x56.toByte() &&
               data[2] == 0x52.toByte() && data[3] == 0x03.toByte()
    }

    private fun astcBlockToEnum(bw: Int, bh: Int): Int = when {
        bw == 4 && bh == 4 -> 27
        bw == 5 && bh == 5 -> 29
        bw == 6 && bh == 6 -> 31
        bw == 8 && bh == 6 -> 33
        bw == 10 && bh == 5 -> 35
        bw == 8 && bh == 8 -> 37
        bw == 10 && bh == 10 -> 39
        bw == 12 && bh == 12 -> 41
        bw == 8 && bh == 5 -> 43
        bw == 8 && bh == 10 -> 45
        else -> 31
    }
}