package com.dvpl.modhelper.codec

import android.graphics.Bitmap

/**
 * DDS 纹理转换器
 * 支持：BC1(DXT1/DX10)、BC2(DXT3)、BC3(DXT5)、BC4、BC5、BC7（DX10）
 */
object DdsConverter {

    init {
        System.loadLibrary("pvr-codec")
    }

    /** DDS 格式常量（与 nativeDecodeDds 返回一致） */
    const val FORMAT_BC1 = 1
    const val FORMAT_BC2 = 2
    const val FORMAT_BC3 = 3
    const val FORMAT_BC4 = 4
    const val FORMAT_BC5 = 5
    const val FORMAT_BC6H = 6
    const val FORMAT_BC7 = 7
    const val FORMAT_UNCOMPRESSED = 8

    fun formatName(format: Int): String = when (format) {
        FORMAT_BC1 -> "BC1 (DXT1)"
        FORMAT_BC2 -> "BC2 (DXT3)"
        FORMAT_BC3 -> "BC3 (DXT5)"
        FORMAT_BC4 -> "BC4 (单通道)"
        FORMAT_BC5 -> "BC5 (双通道)"
        FORMAT_BC6H -> "BC6H (HDR)"
        FORMAT_BC7 -> "BC7"
        FORMAT_UNCOMPRESSED -> "未压缩"
        else -> "未知($format)"
    }

    private external fun nativeDecodeDds(ddsData: ByteArray): IntArray?

    /**
     * DDS 解码 mip0 → Bitmap
     */
    /** DVPL 包裹检测：.dds.dvpl 自动解包 */
    private fun unwrap(ddsData: ByteArray): ByteArray =
        if (ddsData.size >= 24 && DvplCodec.isDvplFile(ddsData))
            try { DvplCodec.decode(ddsData) } catch (e: Exception) { ddsData }
        else ddsData

    fun decodeToBitmap(ddsData: ByteArray): Pair<Bitmap, Int>? {
        val data = unwrap(ddsData)
        val result = nativeDecodeDds(data) ?: return null
        if (result.size < 4) return null
        val w = result[0]; val h = result[1]; val format = result[2]
        val pixels = result.copyOfRange(3, result.size)
        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        return Pair(bitmap, format)
    }

    /**
     * 检查是否为 DDS 文件
     */
    fun isDdsFile(data: ByteArray): Boolean {
        if (DvplCodec.isDvplFile(data)) return true  // .dds.dvpl 预览入口兼容
        if (data.size < 4) return false
        return data[0] == 0x44.toByte() && data[1] == 0x44.toByte() &&
               data[2] == 0x53.toByte() && data[3] == 0x20.toByte()
    }

    /** DDS 输出格式 */
    enum class DdsFormat(val label: String) {
        BC3("BC3 / DXT5（标准，支持透明）"),
        BC5("BC5（法线图专用）"),
        BC4("BC4（单通道灰度）")
    }

    /**
     * Bitmap → DDS（BC3/BC5/BC4 编码 + mip 链）
     * BC 编码在 Kotlin 层实现（块压缩算法简单，性能足够）
     *
     * 内存策略（4096x4096 防闪退）：输出精确预分配（头+DX10+全部 mip 块），
     * 全程只做一次 toByteArray 复制（旧实现纹理 + 组装结果各复制一次，峰值翻倍）
     */
    fun encodeToDds(bitmap: Bitmap, format: DdsFormat): ByteArray {
        val w = bitmap.width
        val h = bitmap.height

        // mip 链（到 1x1，与 PvrConverter 一致的数法）
        var mips = 1
        var mw = w; var mh = h
        while (mw > 1 || mh > 1) {
            mw = maxOf(1, (mw + 1) / 2); mh = maxOf(1, (mh + 1) / 2)
            mips++
        }

        // 精确总容量：128B 头（+20B DX10）+ 全部 mip 块字节
        val hasDx10 = format != DdsFormat.BC3
        val blockBytes = when (format) {
            DdsFormat.BC3 -> 16L; DdsFormat.BC4 -> 8L; DdsFormat.BC5 -> 16L
        }
        var exactTotal = 0L
        var ew = w; var eh = h
        repeat(mips) {
            exactTotal += ((ew + 3) / 4).toLong() * ((eh + 3) / 4) * blockBytes
            ew = maxOf(1, (ew + 1) / 2); eh = maxOf(1, (eh + 1) / 2)
        }
        val out = java.io.ByteArrayOutputStream((128 + (if (hasDx10) 20 else 0) + exactTotal).toInt())

        // DDS 头（128B）直写流
        val buf = java.nio.ByteBuffer.allocate(128).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x20534444)  // "DDS "
        buf.putInt(124)         // header size
        buf.putInt(0xA1007)     // flags: CAPS|HEIGHT|WIDTH|PIXELFORMAT|MIPMAPCOUNT(0x20000)|LINEARSIZE
        buf.putInt(h)
        buf.putInt(w)
        // LINEARSIZE = mip0 的块数据大小（非整条 mip 链）
        val mip0Size = when (format) {
            DdsFormat.BC3 -> ((w + 3) / 4) * ((h + 3) / 4) * 16
            DdsFormat.BC4 -> ((w + 3) / 4) * ((h + 3) / 4) * 8
            DdsFormat.BC5 -> ((w + 3) / 4) * ((h + 3) / 4) * 16
        }
        buf.putInt(mip0Size)
        buf.putInt(0)           // depth
        buf.putInt(mips)
        repeat(11) { buf.putInt(0) } // reserved
        // pixel format
        buf.putInt(32)          // pf size
        buf.putInt(0x4)         // DDPF_FOURCC
        val fourCC = when (format) {
            DdsFormat.BC3 -> 0x35545844  // "DXT5"
            DdsFormat.BC4 -> 0x30315844  // "DX10"（BC4 需 DX10 头）
            DdsFormat.BC5 -> 0x30315844  // "DX10"
        }
        buf.putInt(fourCC)
        buf.putInt(0); buf.putInt(0); buf.putInt(0); buf.putInt(0)
        buf.putInt(0); buf.putInt(0) // masks
        buf.putInt(0x401008)   // caps: COMPLEX|MIPMAP|TEXTURE
        buf.putInt(0); buf.putInt(0); buf.putInt(0)
        out.write(buf.array())

        // BC4/BC5 需要 DX10 头
        if (hasDx10) {
            val dx10 = java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            dx10.putInt(if (format == DdsFormat.BC4) 80 else 83) // DXGI BC4_UNORM=80, BC5_UNORM=83
            dx10.putInt(3)  // DIMENSION_TEXTURE2D
            dx10.putInt(0); dx10.putInt(1); dx10.putInt(0)
            out.write(dx10.array())
        }

        var curW = w; var curH = h
        var curBitmap = bitmap
        var isFirst = true

        val maxPixels = IntArray(w * h)
        repeat(mips) {
            val pixels = maxPixels
            curBitmap.getPixels(pixels, 0, curW, 0, 0, curW, curH)
            encodeBlocksToStream(pixels, curW, curH, format, out)
            if (it < mips - 1) {
                val nw = maxOf(1, (curW + 1) / 2); val nh = maxOf(1, (curH + 1) / 2)
                val scaled = Bitmap.createScaledBitmap(curBitmap, nw, nh, true)
                if (!isFirst) curBitmap.recycle()
                curBitmap = scaled
                isFirst = false
                curW = nw; curH = nh
            }
        }
        if (!isFirst) curBitmap.recycle()

        // 唯一一次整体复制
        return out.toByteArray()
    }

    /** 块编码：BC3（DXT5 颜色+alpha）/ BC4（R）/ BC5（R+G） */
    private fun encodeBlocksToStream(pixels: IntArray, w: Int, h: Int,
                                     format: DdsFormat, out: java.io.ByteArrayOutputStream) {
        val blocksW = (w + 3) / 4
        val blocksH = (h + 3) / 4
        for (by in 0 until blocksH) {
            for (bx in 0 until blocksW) {
                // 提取 4x4 块（边界 clamp）
                val blockR = IntArray(16); val blockG = IntArray(16)
                val blockB = IntArray(16); val blockA = IntArray(16)
                for (py in 0 until 4) {
                    for (px in 0 until 4) {
                        val x = minOf(bx * 4 + px, w - 1)
                        val y = minOf(by * 4 + py, h - 1)
                        val p = pixels[y * w + x]
                        val i = py * 4 + px
                        blockR[i] = (p shr 16) and 0xFF
                        blockG[i] = (p shr 8) and 0xFF
                        blockB[i] = p and 0xFF
                        blockA[i] = (p shr 24) and 0xFF
                    }
                }
                when (format) {
                    DdsFormat.BC3 -> { encodeBC3Block(blockR, blockG, blockB, blockA, out) }
                    DdsFormat.BC4 -> { encodeBC4Block(blockR, out) }
                    DdsFormat.BC5 -> { encodeBC4Block(blockR, out); encodeBC4Block(blockG, out) }
                }
            }
        }
    }

    /** BC3 块编码：8字节 alpha + 8字节颜色 */
    private fun encodeBC3Block(r: IntArray, g: IntArray, b: IntArray, a: IntArray,
                               out: java.io.ByteArrayOutputStream) {
        // --- alpha（BC3 8 端点插值） ---
        var a0 = a[0]; var a1 = a[1]
        for (v in a) { if (v < a0) a0 = v; if (v > a1) a1 = v }
        out.write(a0); out.write(a1)
        // 3bit 索引（选最近端点）
        var bitAcc = 0; var bitCnt = 0
        val palette = IntArray(8)
        palette[0] = a0; palette[1] = a1
        if (a0 > a1) { for (i in 0 until 6) palette[2 + i] = ((6 - i) * a0 + (i + 1) * a1) / 7 }
        else { for (i in 0 until 4) palette[2 + i] = ((4 - i) * a0 + (i + 1) * a1) / 5; palette[6] = 0; palette[7] = 255 }
        for (v in a) {
            var best = 0; var bestDist = Int.MAX_VALUE
            for (i in 0 until 8) {
                val d = kotlin.math.abs(palette[i] - v)
                if (d < bestDist) { bestDist = d; best = i }
            }
            bitAcc = bitAcc or (best shl bitCnt)
            bitCnt += 3
            if (bitCnt >= 8) { out.write(bitAcc and 0xFF); bitAcc = bitAcc shr 8; bitCnt -= 8 }
        }
        while (bitCnt > 0) { out.write(bitAcc and 0xFF); bitAcc = bitAcc shr 8; bitCnt -= 8 }

        // --- 颜色（BC1 式 2 端点 RGB565） ---
        encodeBC1ColorBlock(r, g, b, out)
    }

    /** BC1 颜色编码（忽略 alpha，全不透明处理） */
    private fun encodeBC1ColorBlock(r: IntArray, g: IntArray, b: IntArray,
                                    out: java.io.ByteArrayOutputStream) {
        // 找 min/max 端点（亮度）
        var minLum = Int.MAX_VALUE; var maxLum = Int.MIN_VALUE
        var minIdx = 0; var maxIdx = 0
        for (i in 0 until 16) {
            val lum = (r[i] * 30 + g[i] * 59 + b[i] * 11) / 100
            if (lum < minLum) { minLum = lum; minIdx = i }
            if (lum > maxLum) { maxLum = lum; maxIdx = i }
        }
        fun rgb565(ri: Int, gi: Int, bi: Int): Int =
            ((ri shr 3) shl 11) or ((gi shr 2) shl 5) or (bi shr 3)
        var c0 = rgb565(r[maxIdx], g[maxIdx], b[maxIdx])
        var c1 = rgb565(r[minIdx], g[minIdx], b[minIdx])
        // BC3 内嵌颜色块按规范恒为 4 色模式：必须保证 c0 > c1（量化后可能反转，需交换）
        if (c0 <= c1) {
            val t = c0; c0 = c1; c1 = t
        }
        // 展开 RGB565 → 888
        fun expand(c: Int): Triple<Int, Int, Int> = Triple(
            (((c shr 11) and 0x1F) shl 3) or ((c shr 13) and 0x7),
            (((c shr 5) and 0x3F) shl 2) or ((c shr 12) and 0x3),
            ((c and 0x1F) shl 3) or ((c shr 2) and 0x7)
        )
        val (er0, eg0, eb0) = expand(c0)
        val (er1, eg1, eb1) = expand(c1)
        // 调色板（保证 c0 > c1）
        val c0v = c0; val c1v = c1  // 已保证 c0 > c1
        val palR = intArrayOf(er0, er1, (er0 * 2 + er1) / 3, (er1 * 2 + er0) / 3)
        val palG = intArrayOf(eg0, eg1, (eg0 * 2 + eg1) / 3, (eg1 * 2 + eg0) / 3)
        val palB = intArrayOf(eb0, eb1, (eb0 * 2 + eb1) / 3, (eb1 * 2 + eb0) / 3)
        out.write(c0v and 0xFF); out.write((c0v shr 8) and 0xFF)
        out.write(c1v and 0xFF); out.write((c1v shr 8) and 0xFF)
        // 2bit 索引
        var idxAcc = 0
        for (i in 0 until 16) {
            var best = 0; var bestDist = Int.MAX_VALUE
            for (p in 0 until 4) {
                val dr = palR[p] - r[i]; val dg = palG[p] - g[i]; val db = palB[p] - b[i]
                val d = dr * dr + dg * dg + db * db
                if (d < bestDist) { bestDist = d; best = p }
            }
            idxAcc = idxAcc or (best shl (i * 2))
        }
        out.write(idxAcc and 0xFF); out.write((idxAcc shr 8) and 0xFF)
        out.write((idxAcc shr 16) and 0xFF); out.write((idxAcc shr 24) and 0xFF)
    }

    /** BC4 单通道块编码（8字节） */
    private fun encodeBC4Block(channel: IntArray, out: java.io.ByteArrayOutputStream) {
        var a0 = channel[0]; var a1 = channel[1]
        for (v in channel) { if (v < a0) a0 = v; if (v > a1) a1 = v }
        out.write(a0); out.write(a1)
        val palette = IntArray(8)
        palette[0] = a0; palette[1] = a1
        if (a0 > a1) { for (i in 0 until 6) palette[2 + i] = ((6 - i) * a0 + (i + 1) * a1) / 7 }
        else { for (i in 0 until 4) palette[2 + i] = ((4 - i) * a0 + (i + 1) * a1) / 5; palette[6] = 0; palette[7] = 255 }
        var bitAcc = 0; var bitCnt = 0
        for (v in channel) {
            var best = 0; var bestDist = Int.MAX_VALUE
            for (i in 0 until 8) {
                val d = kotlin.math.abs(palette[i] - v)
                if (d < bestDist) { bestDist = d; best = i }
            }
            bitAcc = bitAcc or (best shl bitCnt)
            bitCnt += 3
            if (bitCnt >= 8) { out.write(bitAcc and 0xFF); bitAcc = bitAcc shr 8; bitCnt -= 8 }
        }
        while (bitCnt > 0) { out.write(bitAcc and 0xFF); bitAcc = bitAcc shr 8; bitCnt -= 8 }
    }
}