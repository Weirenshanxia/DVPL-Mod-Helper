package com.dvpl.modhelper

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dvpl.modhelper.codec.DvplCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DvplCodecTest {
    
    @Test
    fun testEncodeDecodeRoundTrip() {
        val original = "Hello DVPL World! 测试数据".toByteArray()
        
        val encoded = DvplCodec.encode(original, DvplCodec.CompressionType.LZ4)
        assertNotNull(encoded)
        
        val decoded = DvplCodec.decode(encoded)
        assertNotNull(decoded)
        assertArrayEquals(original, decoded)
    }
    
    @Test
    fun testSmallDataNoCompression() {
        val original = "Hi".toByteArray()
        val encoded = DvplCodec.encode(original, DvplCodec.CompressionType.LZ4)
        
        // 验证小数据不压缩（类型为 0）
        val type = DvplCodec.getCompressionType(encoded)
        assertEquals(0, type)
    }
    
    @Test
    fun testLargeData() {
        val original = ByteArray(1024 * 1024) { (it % 256).toByte() }
        
        val encoded = DvplCodec.encode(original, DvplCodec.CompressionType.LZ4_HC)
        val decoded = DvplCodec.decode(encoded)
        
        assertArrayEquals(original, decoded)
        assertTrue(encoded.size < original.size) // 验证压缩有效
    }
    
    @Test
    fun testAllCompressionTypes() {
        val original = ByteArray(10000) { (it % 256).toByte() }
        
        listOf(
            DvplCodec.CompressionType.NONE,
            DvplCodec.CompressionType.LZ4,
            DvplCodec.CompressionType.LZ4_HC,
            DvplCodec.CompressionType.DEFLATE
        ).forEach { type ->
            val encoded = DvplCodec.encode(original, type)
            val decoded = DvplCodec.decode(encoded)
            assertArrayEquals("Failed for $type", original, decoded)
        }
    }

@Test
    fun testTruncatedFile() {
        val original = "Hello DVPL World!".toByteArray()
        val encoded = DvplCodec.encode(original, DvplCodec.CompressionType.LZ4)
        try {
            DvplCodec.decode(encoded.copyOf(encoded.size - 5)) // 截断
            // 截断后 magic 不在末尾，应抛异常
            fail("应抛出异常")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }
    
    @Test
    fun testBadMagic() {
        val data = ByteArray(64) { 0xAA }
        try {
            DvplCodec.decode(data)
            fail("应抛出异常")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }
    
    @Test
    fun testCorruptedCrc() {
        val original = ByteArray(5000) { (it % 251).toByte() }
        val encoded = DvplCodec.encode(original, DvplCodec.CompressionType.LZ4_HC)
        // 破坏数据区一个字节（CRC 将失配）
        encoded[10] = (encoded[10].toInt() xor 0xFF).toByte()
        try {
            DvplCodec.decode(encoded)
            fail("CRC 损坏应抛出异常")
        } catch (e: IllegalArgumentException) {
            // 预期（F1 修复后不再静默）
        }
    }
}
