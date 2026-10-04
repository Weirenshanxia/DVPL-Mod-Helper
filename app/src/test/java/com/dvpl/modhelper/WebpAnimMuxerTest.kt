package com.dvpl.modhelper

import com.dvpl.modhelper.codec.WebpAnimMuxer
import com.dvpl.modhelper.codec.WebpAnimMuxer.Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动画 WebP 混流器容器结构测试（RFC 9649）——纯字节操作，JVM 可跑。
 */
class WebpAnimMuxerTest {

    // ---------- 测试用工具 ----------

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun le24(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16)

    private fun chunkBytes(name: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size + (payload.size and 1))
        for (i in 0..3) out[i] = name[i].code.toByte()
        for (i in 0..3) out[4 + i] = ((payload.size shr (i * 8)) and 0xFF).toByte()
        payload.copyInto(out, 8)
        return out
    }

    /** 造一个"单帧 webp 文件"：RIFF[WEBP] + 任意 chunk（偶数尺寸 payload） */
    private fun fakeWebp(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = chunks.map { chunkBytes(it.first, it.second) }
        var total = 4
        for (c in body) total += c.size
        val out = ByteArray(12 + total - 4)
        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        for (i in 0..3) out[4 + i] = ((total shr (i * 8)) and 0xFF).toByte()
        out[8] = 'W'.code.toByte(); out[9] = 'E'.code.toByte()
        out[10] = 'B'.code.toByte(); out[11] = 'P'.code.toByte()
        var off = 12
        for (c in body) { c.copyInto(out, off); off += c.size }
        return out
    }

    /** 遍历 chunk: 返回 (fourcc, payloadOff, size) */
    private fun walk(webp: ByteArray): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var off = 12
        while (off + 8 <= webp.size) {
            val size = leInt(webp, off + 4)
            val tag = String(webp, off, 4, Charsets.US_ASCII)
            out.add(Triple(tag, off + 8, size))
            off += 8 + size + (size and 1)
        }
        return out
    }

    // ---------- 用例 ----------

    @Test fun basicMuxStructure() {
        val f1 = fakeWebp("VP8 " to ByteArray(16) { (it + 1).toByte() })
        val f2 = fakeWebp("VP8 " to ByteArray(17) { (it * 3).toByte() })  // 奇数 payload
        val out = WebpAnimMuxer.mux(listOf(Frame(f1, 100), Frame(f2, 200)), 64, 48)

        // RIFF 头 + 尺寸字段 = 文件大小 - 8
        assertEquals("RIFF", String(out, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(out, 8, 4, Charsets.US_ASCII))
        assertEquals(out.size - 8, leInt(out, 4))

        // chunk 目录顺序
        val tags = walk(out).map { it.first }
        assertEquals(listOf("VP8X", "ANIM", "ANMF", "ANMF"), tags)

        // VP8X: flags=ANIM, canvas 63/47
        val vp8x = walk(out)[0]
        assertEquals(10, vp8x.third)
        val vp = vp8x.second
        assertEquals(0x02, out[vp].toInt() and 0xFF)
        assertEquals(63, le24(out, vp + 4))
        assertEquals(47, le24(out, vp + 7))

        // ANIM: 6 字节，背景色 0，循环 0
        val anim = walk(out)[1]
        assertEquals(6, anim.third)
        assertEquals(0, leInt(out, anim.second))

        // ANMF: 16 字节头 + 帧图像 chunk；x=y=0, 画布 63/47, 时长, flags=0x02
        val anmf1 = walk(out)[2]
        val h1 = anmf1.second
        assertEquals(0, le24(out, h1))          // x
        assertEquals(0, le24(out, h1 + 3))     // y
        assertEquals(63, le24(out, h1 + 6))    // w-1
        assertEquals(47, le24(out, h1 + 9))    // h-1
        assertEquals(100, le24(out, h1 + 12))  // duration
        assertEquals(0x02, out[h1 + 15].toInt() and 0xFF)
        // 帧负载 = 原始 VP8 chunk 字节（偶数尺寸时无 padding）
        val expect1 = chunkBytes("VP8 ", ByteArray(16) { (it + 1).toByte() })
        assertTrue(out.copyOfRange(h1 + 16, h1 + 16 + 24).contentEquals(expect1))
        // 第二帧奇数 payload: chunk 总长 8+17+1=26
        assertEquals(8 + 17 + 1, walk(out)[3].third - 16)
    }

    @Test fun alphaFlagWhenFrameHasAlph() {
        val f1 = fakeWebp("ALPH" to ByteArray(8), "VP8 " to ByteArray(16))
        val out = WebpAnimMuxer.mux(listOf(Frame(f1, 80)), 32, 32)
        val vp8x = walk(out)[0]
        assertEquals(0x12, out[vp8x.second].toInt() and 0xFF)  // ANIMATION | ALPHA
        // ANMF 负载按序包含 ALPH + VP8 两个 chunk
        val anmf = walk(out)[2]
        val payload = out.copyOfRange(anmf.second + 16, anmf.second + anmf.third)
        val inner = ArrayList<String>()
        var off = 0
        while (off + 8 <= payload.size) {
            inner.add(String(payload, off, 4, Charsets.US_ASCII))
            off += 8 + leInt(payload, off + 4) + (leInt(payload, off + 4) and 1)
        }
        assertEquals(listOf("ALPH", "VP8 "), inner)
    }

    @Test fun stripsExtendedFormatShell() {
        // 扩展格式静态帧（Bitmap.compress 带 alpha 时的真实形态: VP8X+ALPH+VP8）→ 剥壳只留图像 chunk
        val f = fakeWebp("VP8X" to ByteArray(10), "ALPH" to ByteArray(8), "VP8 " to ByteArray(20))
        val out = WebpAnimMuxer.mux(listOf(Frame(f, 50)), 16, 16)
        val anmf = walk(out)[2]
        // 负载 = ALPH + VP8（壳被剥掉）
        val payload = out.copyOfRange(anmf.second + 16, anmf.second + anmf.third)
        assertEquals(8 + 8 + 8 + 20, payload.size)
        assertEquals("ALPH", String(payload, 0, 4, Charsets.US_ASCII))
        assertEquals("VP8 ", String(payload, 16, 4, Charsets.US_ASCII))
        assertEquals(20, leInt(payload, 20))
    }

    @Test fun rejectsInvalidFrames() {
        assertNull(WebpAnimMuxer.extractImageChunks(ByteArray(50)))
        assertNull(WebpAnimMuxer.extractImageChunks(fakeWebp()))
        var thrown = false
        try { WebpAnimMuxer.mux(emptyList(), 10, 10) } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
        thrown = false
        try { WebpAnimMuxer.mux(listOf(Frame(ByteArray(50), 100)), 10, 10) } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
        thrown = false
        try { WebpAnimMuxer.mux(listOf(Frame(fakeWebp("VP8 " to ByteArray(8)), 0)), 10, 10) }
        catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)   // duration 0 非法
    }

    @Test fun truncatedFrameReturnsNull() {
        val f = fakeWebp("VP8 " to ByteArray(20))
        val cut = f.copyOfRange(0, f.size - 5)
        assertNull(WebpAnimMuxer.extractImageChunks(cut))
    }

    @Test fun loopCountWritten() {
        val f = fakeWebp("VP8 " to ByteArray(8))
        val out = WebpAnimMuxer.mux(listOf(Frame(f, 100)), 8, 8, 0xFFFF)
        val anim = walk(out)[1]
        assertEquals(0xFFFF, (out[anim.second + 4].toInt() and 0xFF) or
            ((out[anim.second + 5].toInt() and 0xFF) shl 8))
    }
}
