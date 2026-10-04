package com.dvpl.modhelper.codec

/**
 * 动画 WebP 混流器 —— 纯 Kotlin、零外部依赖（容器格式 RFC 9649），JVM 可单测。
 *
 * 每帧先用 Bitmap.compress 压成独立单帧 WebP 文件，再剥 RIFF 外壳取出图像
 * chunk（ALPH?/VP8/VP8L），按标准拼装：
 *   RIFF[WEBP] VP8X(ANIM[|ALPHA]) ANIM(背景色+循环) ANMF(x,y,w,h,时长,flags)+帧chunk...
 *
 * 关键字段（24bit 小端）与标志位：
 *   VP8X.flags: ANIMATION=0x02, ALPHA=0x10（帧带 ALPH 时自动置位）
 *   ANMF.flags: NO_BLEND=0x02（整帧替换，不混合）；DISPOSE=0（保留画布）
 *   ANIM: 背景色 uint32 + 循环次数 uint16（0=无限）
 */
object WebpAnimMuxer {

    /** durationMs: 帧显示时长（24bit 上限） */
    class Frame(val webp: ByteArray, val durationMs: Int) {
        init { require(durationMs in 1..0xFFFFFF) { "ANMF duration overflow: " + durationMs } }
    }

    /** 合成完整动画 WebP 字节流 */
    fun mux(frames: List<Frame>, canvasW: Int, canvasH: Int, loopCount: Int = 0): ByteArray {
        require(frames.isNotEmpty()) { "no frames" }
        require(canvasW in 1..(1 shl 24) && canvasH in 1..(1 shl 24)) { "canvas overflow: " + canvasW + "x" + canvasH }
        require(loopCount in 0..0xFFFF) { "loop count overflow" }
        var hasAlpha = false
        val anmfs = frames.map { f ->
            val chunks = extractImageChunks(f.webp)
                ?: throw IllegalArgumentException("frame is not a valid WebP file")
            if (chunks.any { it.size >= 8 && it[0] == 'A'.code.toByte() && it[1] == 'L'.code.toByte() &&
                    it[2] == 'P'.code.toByte() && it[3] == 'H'.code.toByte() }) hasAlpha = true
            val head = ByteArray(16)
            put24(head, 0, 0)                    // frame x
            put24(head, 3, 0)                    // frame y
            put24(head, 6, canvasW - 1)          // frame w - 1
            put24(head, 9, canvasH - 1)          // frame h - 1
            put24(head, 12, f.durationMs)       // duration ms
            head[15] = 0x02                      // B=1 不混合(no blend); D=0 保留画布
            chunk("ANMF", head + concat(chunks))
        }
        val vp8x = chunk("VP8X", ByteArray(10).also {
            it[0] = if (hasAlpha) 0x12 else 0x02 // ANIMATION | ALPHA?
            put24(it, 4, canvasW - 1)             // canvas w - 1
            put24(it, 7, canvasH - 1)             // canvas h - 1
        })
        val anim = chunk("ANIM", ByteArray(6).also {
            put32(it, 0, 0x00000000)              // 背景色: 透明黑
            it[4] = (loopCount and 0xFF).toByte() // 循环次数 uint16 LE（0=无限）
            it[5] = ((loopCount shr 8) and 0xFF).toByte()
        })
        return riff(listOf(vp8x, anim) + anmfs)
    }

    /** 从单帧 WebP 文件剥出图像 chunk 列表（原始字节，含偶数 padding；剥 VP8X/ANIM/ANMF 壳）。
     *  返回 null = 输入不是合法 WebP。 */
    fun extractImageChunks(webp: ByteArray): List<ByteArray>? {
        if (webp.size < 12 || !isRiff(webp)) return null
        var off = 12
        val parts = ArrayList<ByteArray>()
        while (off + 8 <= webp.size) {
            val size = leInt(webp, off + 4)
            if (size < 0 || off + 8 + size > webp.size) return null   // 截断/损坏
            val fourcc = fourcc(webp, off)
            // 白名单: ANMF 帧数据只允许 ALPH/VP8/VP8L。Android 14+ 的 Bitmap.compress 可能
            // 嵌 ICCP(EXIF/XMP 同理)，黑名单会把它们塞进 ANMF —— 规范违规，Chromium 判文件损坏
            if (fourcc == "ALPH" || fourcc == "VP8 " || fourcc == "VP8L") {
                parts.add(webp.copyOfRange(off, off + 8 + size + (size and 1)))
            }
            off += 8 + size + (size and 1)
        }
        return if (parts.isEmpty()) null else parts
    }

    /** 解析一个 WebP 文件的 chunk 目录（fourcc -> payloadSize），单测/诊断用 */
    fun listChunks(webp: ByteArray): List<Pair<String, Int>> {
        if (webp.size < 12 || !isRiff(webp)) return emptyList()
        var off = 12
        val out = ArrayList<Pair<String, Int>>()
        while (off + 8 <= webp.size) {
            val size = leInt(webp, off + 4)
            if (size < 0 || off + 8 + size > webp.size) break
            out.add(Pair(fourcc(webp, off), size))
            off += 8 + size + (size and 1)
        }
        return out
    }

    // ---------- 低层装配 ----------

    private fun isRiff(b: ByteArray): Boolean =
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
        b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
        b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
        b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    private fun fourcc(b: ByteArray, off: Int): String =
        String(b, off, 4, Charsets.US_ASCII)

    /** RIFF[WEBP] + chunks 总装 */
    private fun riff(chunks: List<ByteArray>): ByteArray {
        var total = 4  // "WEBP"
        for (c in chunks) total += c.size
        val out = ByteArray(8 + total)
        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        put32(out, 4, total)
        out[8] = 'W'.code.toByte(); out[9] = 'E'.code.toByte()
        out[10] = 'B'.code.toByte(); out[11] = 'P'.code.toByte()
        var off = 12
        for (c in chunks) {
            c.copyInto(out, off)
            off += c.size
        }
        return out
    }

    /** 单个 chunk: FourCC + Size(LE) + payload + 奇数补零 */
    private fun chunk(name: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size + (payload.size and 1))
        for (i in 0..3) out[i] = name[i].code.toByte()
        put32(out, 4, payload.size)
        payload.copyInto(out, 8)
        return out
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var off = 0
        for (p in parts) { p.copyInto(out, off); off += p.size }
        return out
    }

    private fun put24(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
