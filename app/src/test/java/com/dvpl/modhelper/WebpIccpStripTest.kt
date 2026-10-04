package com.dvpl.modhelper

import com.dvpl.modhelper.codec.WebpAnimMuxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Android 14+ Bitmap.compress 可能嵌 ICCP/EXIF/XMP —— 帧数据必须白名单只留 ALPH/VP8/VP8L */
class WebpIccpStripTest {

    private fun chunk(name: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size + (payload.size and 1))
        for (i in 0..3) out[i] = name[i].code.toByte()
        val sz = payload.size
        out[4] = (sz and 0xFF).toByte()
        out[5] = ((sz shr 8) and 0xFF).toByte()
        out[6] = ((sz shr 16) and 0xFF).toByte()
        out[7] = ((sz shr 24) and 0xFF).toByte()
        payload.copyInto(out, 8)
        return out
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        var total = 4
        chunks.forEach { total += it.size }
        val out = ByteArray(8 + total)
        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        out[4] = (total and 0xFF).toByte(); out[5] = ((total shr 8) and 0xFF).toByte()
        out[6] = ((total shr 16) and 0xFF).toByte(); out[7] = ((total shr 24) and 0xFF).toByte()
        out[8] = 'W'.code.toByte(); out[9] = 'E'.code.toByte()
        out[10] = 'B'.code.toByte(); out[11] = 'P'.code.toByte()
        var off = 12
        chunks.forEach { it.copyInto(out, off); off += it.size }
        return out
    }

    private val vp8 = chunk("VP8 ", ByteArray(10) { it.toByte() })
    private val alph = chunk("ALPH", ByteArray(6) { (it + 3).toByte() })
    private val iccp = chunk("ICCP", ByteArray(20) { (it * 7).toByte() })
    private val exif = chunk("EXIF", ByteArray(8) { it.toByte() })

    @Test
    fun stripsIccpExifKeepsImageChunks() {
        // Android 14 形态: VP8X + ICCP + ALPH + VP8
        val frame = riff(
            chunk("VP8X", ByteArray(10)),
            iccp, alph, vp8
        )
        val parts = WebpAnimMuxer.extractImageChunks(frame)!!
        assertEquals(2, parts.size)
        assertEquals("ALPH", String(parts[0], 0, 4, Charsets.US_ASCII))
        assertEquals("VP8 ", String(parts[1], 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun stripsExifFromSimpleFrame() {
        // VP8X + EXIF + VP8（无 alpha）
        val frame = riff(chunk("VP8X", ByteArray(10)), exif, vp8)
        val parts = WebpAnimMuxer.extractImageChunks(frame)!!
        assertEquals(1, parts.size)
        assertEquals("VP8 ", String(parts[0], 0, 4, Charsets.US_ASCII))
    }

    @Test
    fun noImageChunksReturnsNull() {
        // 只有 VP8X + ICCP → 无有效图像 chunk → null
        val frame = riff(chunk("VP8X", ByteArray(10)), iccp)
        assertNull(WebpAnimMuxer.extractImageChunks(frame))
    }

    @Test
    fun muxWithIccpFramesProducesValidChunks() {
        val frames = listOf(
            WebpAnimMuxer.Frame(riff(chunk("VP8X", ByteArray(10)), iccp, vp8), 100),
            WebpAnimMuxer.Frame(riff(chunk("VP8X", ByteArray(10)), exif, alph, vp8), 100)
        )
        val out = WebpAnimMuxer.mux(frames, 8, 8)
        val names = WebpAnimMuxer.listChunks(out).map { it.first }
        assertEquals(listOf("VP8X", "ANIM", "ANMF", "ANMF"), names)
        // ANMF payload 里不应有任何 ICCP/EXIF 字样
        val s = String(out, Charsets.ISO_8859_1)
        assertEquals(-1, s.indexOf("ICCP", 12))
        assertEquals(-1, s.indexOf("EXIF", 12))
    }
}
