package com.dvpl.modhelper.codec

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * DVPL 编解码器
 * 支持 World of Tanks Blitz 等游戏的 DVPL 格式文件
 */
object DvplCodec {
    
    // 压缩类型常量
    const val COMPRESSION_NONE = 0
    const val COMPRESSION_LZ4 = 1
    const val COMPRESSION_LZ4_HC = 2
    const val COMPRESSION_DEFLATE = 3
    
    // 魔数 "DVPL"
    private val MAGIC_BYTES = byteArrayOf(0x44, 0x56, 0x50, 0x4C) // "DVPL"
    
    init {
        try {
            System.loadLibrary("dvpl-codec")
        } catch (e: UnsatisfiedLinkError) {
            // Native 库加载失败，使用纯 Kotlin 实现
        }
    }
    
    
    /**
     * 解码 DVPL 字节数组
     */
    fun decode(input: ByteArray): ByteArray {
        if (input.size < 20) {
            throw IllegalArgumentException("文件太小，不是有效的 DVPL 文件")
        }
        
        // 检查魔数
        val magicStart = input.size - 4
        if (!input.sliceArray(magicStart until input.size).contentEquals(MAGIC_BYTES)) {
            throw IllegalArgumentException("不是有效的 DVPL 文件（魔数不匹配）")
        }
        
        // 解析尾部 20 字节
        val footer = input.sliceArray(input.size - 20 until input.size)
        val buffer = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN)
        
        val originalSize = buffer.int
        val compressedSize = buffer.int
        val crc32Value = buffer.int
        val compressionType = buffer.int
        // B2 双保险：footer 字段范围校验（native 层已校验，这里提前拦截省一次 JNI）
        if (originalSize < 0 || originalSize > 512 * 1024 * 1024) {
            throw IllegalArgumentException("originalSize 非法：$originalSize")
        }
        if (compressedSize < 0 || compressedSize > input.size) {
            throw IllegalArgumentException("compressedSize 非法：$compressedSize")
        }
        
        // 提取数据部分
        val dataPart = input.sliceArray(0 until input.size - 20)
        
        if (dataPart.size != compressedSize) {
            throw IllegalArgumentException("数据大小不匹配（期望 $compressedSize，实际 ${dataPart.size}）")
        }
        
        // 验证 CRC32
        val crc32 = CRC32()
        crc32.update(dataPart)
        if (crc32.value.toInt() != crc32Value) {
            // F1 修复：损坏数据不应静默流入游戏，直接报错
            throw IllegalArgumentException("CRC32 校验失败，文件数据已损坏")
        }
        
        // 根据压缩类型解压
        return when (compressionType) {
            COMPRESSION_NONE -> {
                dataPart
            }
            COMPRESSION_LZ4, COMPRESSION_LZ4_HC -> {
                decompressLZ4Native(dataPart, originalSize)
            }
            COMPRESSION_DEFLATE -> {
                decompressDeflate(dataPart, originalSize)
            }
            else -> {
                throw IllegalArgumentException("不支持的压缩类型: $compressionType")
            }
        }
    }
    
    /**
     * 编码为 DVPL 字节数组
     */
    fun encode(input: ByteArray, compressionType: Int = COMPRESSION_LZ4_HC): ByteArray {
        val originalSize = input.size
        
        // 压缩数据
        val (compressed, actualCompressionType) = when (compressionType) {
            COMPRESSION_NONE -> {
                input to COMPRESSION_NONE
            }
            COMPRESSION_LZ4, COMPRESSION_LZ4_HC -> {
                val compressed = compressLZ4Native(input, compressionType)
                // 如果压缩后更大，则不压缩
                if (compressed.size >= input.size) {
                    input to COMPRESSION_NONE
                } else {
                    compressed to compressionType
                }
            }
            COMPRESSION_DEFLATE -> {
                val compressed = compressDeflate(input)
                if (compressed.size >= input.size) {
                    input to COMPRESSION_NONE
                } else {
                    compressed to COMPRESSION_DEFLATE
                }
            }
            else -> {
                throw IllegalArgumentException("不支持的压缩类型: $compressionType")
            }
        }
        
        val compressedSize = compressed.size
        
        // 计算 CRC32
        val crc32 = CRC32()
        crc32.update(compressed)
        val crc32Value = crc32.value.toInt()
        
        // 构建输出
        val output = ByteArrayOutputStream()
        output.write(compressed)
        
        // 写入尾部 20 字节
        val footer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        footer.putInt(originalSize)
        footer.putInt(compressedSize)
        footer.putInt(crc32Value)
        footer.putInt(actualCompressionType)
        footer.put(MAGIC_BYTES)
        
        output.write(footer.array())
        
        return output.toByteArray()
    }
    
    /**
     * 使用 Native 实现的 LZ4 解压
     */
    private fun decompressLZ4Native(compressed: ByteArray, originalSize: Int): ByteArray {
        return try {
            nativeDecompressLZ4(compressed, originalSize)
        } catch (e: UnsatisfiedLinkError) {
            // Native 实现不可用，使用纯 Kotlin 实现
            decompressLZ4Kotlin(compressed, originalSize)
        }
    }
    
    /**
     * 使用 Native 实现的 LZ4 压缩
     */
    private fun compressLZ4Native(data: ByteArray, compressionType: Int): ByteArray {
        return try {
            nativeCompressLZ4(data, compressionType == COMPRESSION_LZ4_HC)
        } catch (e: UnsatisfiedLinkError) {
            // Native 实现不可用，使用纯 Kotlin 实现
            compressLZ4Kotlin(data)
        }
    }
    
    // Native 方法声明
    private external fun nativeDecompressLZ4(compressed: ByteArray, originalSize: Int): ByteArray
    private external fun nativeCompressLZ4(data: ByteArray, useHC: Boolean): ByteArray
    private external fun nativeDecompressDeflate(compressed: ByteArray, originalSize: Int): ByteArray
    private external fun nativeCompressDeflate(data: ByteArray): ByteArray
    
    /**
     * 纯 Kotlin 实现的简单 LZ4 解压（备用）
     */
    private fun decompressLZ4Kotlin(compressed: ByteArray, originalSize: Int): ByteArray {
        // 简化的 LZ4 解压实现
        val output = ByteArray(originalSize)
        var inPos = 0
        var outPos = 0
        
        while (inPos < compressed.size && outPos < originalSize) {
            val token = compressed[inPos++].toInt() and 0xFF
            
            // 字面量长度
            var literalLength = token shr 4
            if (literalLength == 15) {
                while (inPos < compressed.size) {
                    val b = compressed[inPos++].toInt() and 0xFF
                    literalLength += b
                    if (b != 255) break
                }
            }
            
            // 复制字面量
            if (literalLength > 0) {
                System.arraycopy(compressed, inPos, output, outPos, literalLength)
                inPos += literalLength
                outPos += literalLength
            }
            
            if (inPos >= compressed.size) break
            
            // 匹配偏移
            val offset = (compressed[inPos++].toInt() and 0xFF) or
                        ((compressed[inPos++].toInt() and 0xFF) shl 8)
            
            // 匹配长度
            var matchLength = (token and 0x0F) + 4
            if (matchLength == 19) {
                while (inPos < compressed.size) {
                    val b = compressed[inPos++].toInt() and 0xFF
                    matchLength += b
                    if (b != 255) break
                }
            }
            
            // 复制匹配
            var matchPos = outPos - offset
            repeat(matchLength) {
                output[outPos++] = output[matchPos++]
            }
        }
        
        return output
    }
    
    /**
     * 纯 Kotlin 实现的简单 LZ4 压缩（备用）
     */
    private fun compressLZ4Kotlin(data: ByteArray): ByteArray {
        // 简化实现：不压缩，直接返回
        // 完整的 LZ4 压缩实现比较复杂，这里作为备用方案
        return data
    }
    
    /**
     * DEFLATE 解压
     */
    private fun decompressDeflate(compressed: ByteArray, originalSize: Int): ByteArray {
        return try {
            nativeDecompressDeflate(compressed, originalSize)
        } catch (e: UnsatisfiedLinkError) {
            // 使用 Java 内置的 Inflater
            val inflater = java.util.zip.Inflater(true) // true = nowrap (raw DEFLATE)
            inflater.setInput(compressed)
            val output = ByteArray(originalSize)
            val resultLength = inflater.inflate(output)
            inflater.end()
            if (resultLength != originalSize) {
                output.copyOf(resultLength)
            } else {
                output
            }
        }
    }
    
    /**
     * DEFLATE 压缩
     */
    private fun compressDeflate(data: ByteArray): ByteArray {
        return try {
            nativeCompressDeflate(data)
        } catch (e: UnsatisfiedLinkError) {
            // 使用 Java 内置的 Deflater
            val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            deflater.end()
            output.toByteArray()
        }
    }
    
    /**
     * 压缩类型枚举
     */
    enum class CompressionType(val value: Int) {
        NONE(COMPRESSION_NONE),
        LZ4(COMPRESSION_LZ4),
        LZ4_HC(COMPRESSION_LZ4_HC),
        DEFLATE(COMPRESSION_DEFLATE);
    }
    
    /**
     * 获取 DVPL 文件的压缩类型
     */
    fun getCompressionType(input: ByteArray): Int {
        if (input.size < 20) return -1
        val buffer = ByteBuffer.wrap(input, input.size - 20, 20).order(ByteOrder.LITTLE_ENDIAN)
        buffer.int // originalSize
        buffer.int // compressedSize
        buffer.int // crc32
        return buffer.int // compressionType
    }
    
    /**
     * 检查文件是否为 DVPL 格式
     */
    fun isDvplFile(data: ByteArray): Boolean {
        if (data.size < 20) return false
        val magicStart = data.size - 4
        return data.sliceArray(magicStart until data.size).contentEquals(MAGIC_BYTES)
    }
}