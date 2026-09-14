#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <cstring>
#include <vector>
#include <zlib.h>

// LZ4 库（Android NDK 自带）
#include <lz4.h>
#include <lz4hc.h>

#define LOG_TAG "DvplCodec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============= LZ4 压缩/解压 =============

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_dvpl_modhelper_codec_DvplCodec_nativeCompressLZ4(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray data,
    jboolean use_hc
) {
    jsize data_len = env->GetArrayLength(data);
    jbyte* data_ptr = env->GetByteArrayElements(data, nullptr);
    if (!data_ptr) return nullptr;
    
    // 分配输出缓冲区
    int max_compressed_size = LZ4_compressBound(data_len);
    std::vector<char> compressed(max_compressed_size);
    
    int compressed_size;
    if (use_hc) {
        // 使用 LZ4_HC 高压缩比
        compressed_size = LZ4_compress_HC(
            reinterpret_cast<const char*>(data_ptr),
            compressed.data(),
            data_len,
            max_compressed_size,
            LZ4HC_CLEVEL_MAX // 最高压缩等级
        );
    } else {
        // 使用标准 LZ4
        compressed_size = LZ4_compress_default(
            reinterpret_cast<const char*>(data_ptr),
            compressed.data(),
            data_len,
            max_compressed_size
        );
    }
    
    env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
    
    if (compressed_size <= 0) {
        LOGE("LZ4 压缩失败");
        return nullptr;
    }
    
    // 创建 Java 字节数组
    jbyteArray result = env->NewByteArray(compressed_size);
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, compressed_size, 
                           reinterpret_cast<jbyte*>(compressed.data()));
    
    return result;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_dvpl_modhelper_codec_DvplCodec_nativeDecompressLZ4(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray compressed,
    jint original_size
) {
    jsize compressed_len = env->GetArrayLength(compressed);
    jbyte* compressed_ptr = env->GetByteArrayElements(compressed, nullptr);
    if (!compressed_ptr) return nullptr;
    
    // B2 修复：original_size 范围校验（防恶意 footer 的 length_error/bad_alloc 崩溃）
    if (original_size < 0 || original_size > 512 * 1024 * 1024) {
        env->ReleaseByteArrayElements(compressed, compressed_ptr, JNI_ABORT);
        LOGE("original_size out of range: %d", original_size);
        return nullptr;
    }
    
    // 分配输出缓冲区
    std::vector<char> decompressed;
    try {
        decompressed.resize((size_t)original_size);
    } catch (const std::exception&) {
        env->ReleaseByteArrayElements(compressed, compressed_ptr, JNI_ABORT);
        LOGE("alloc %d bytes failed", original_size);
        return nullptr;
    }
    
    int decompressed_size = LZ4_decompress_safe(
        reinterpret_cast<const char*>(compressed_ptr),
        decompressed.data(),
        compressed_len,
        original_size
    );
    
    env->ReleaseByteArrayElements(compressed, compressed_ptr, JNI_ABORT);
    
    if (decompressed_size != original_size) {
        LOGE("LZ4 解压失败: 期望 %d 字节，实际 %d 字节", original_size, decompressed_size);
        return nullptr;
    }
    
    // 创建 Java 字节数组
    jbyteArray result = env->NewByteArray(original_size);
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, original_size, 
                           reinterpret_cast<jbyte*>(decompressed.data()));
    
    return result;
}

// ============= DEFLATE 压缩/解压 =============

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_dvpl_modhelper_codec_DvplCodec_nativeCompressDeflate(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray data
) {
    jsize data_len = env->GetArrayLength(data);
    jbyte* data_ptr = env->GetByteArrayElements(data, nullptr);
    
    // 初始化 zlib
    z_stream stream;
    stream.zalloc = Z_NULL;
    stream.zfree = Z_NULL;
    stream.opaque = Z_NULL;
    
    // -15 = raw DEFLATE (without zlib header)
    if (deflateInit2(&stream, Z_BEST_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY) != Z_OK) {
        env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
        LOGE("deflateInit2 失败");
        return nullptr;
    }
    
    stream.avail_in = data_len;
    stream.next_in = reinterpret_cast<Bytef*>(data_ptr);
    
    // 分配输出缓冲区
    uLong max_compressed_size = deflateBound(&stream, data_len);
    std::vector<uint8_t> compressed(max_compressed_size);
    
    stream.avail_out = max_compressed_size;
    stream.next_out = compressed.data();
    
    int ret = deflate(&stream, Z_FINISH);
    uLong compressed_size = stream.total_out;  // deflateEnd 前保存（end 后读 stream 是未定义行为）
    deflateEnd(&stream);
    
    env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
    
    if (ret != Z_STREAM_END) {
        LOGE("deflate 失败: %d", ret);
        return nullptr;
    }
    
    // 创建 Java 字节数组
    jbyteArray result = env->NewByteArray(compressed_size);
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, compressed_size, 
                           reinterpret_cast<jbyte*>(compressed.data()));
    
    return result;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_dvpl_modhelper_codec_DvplCodec_nativeDecompressDeflate(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray compressed,
    jint original_size
) {
    jsize compressed_len = env->GetArrayLength(compressed);
    jbyte* compressed_ptr = env->GetByteArrayElements(compressed, nullptr);
    if (!compressed_ptr) return nullptr;
    if (original_size < 0 || original_size > 512 * 1024 * 1024) {
        env->ReleaseByteArrayElements(compressed, compressed_ptr, JNI_ABORT);
        LOGE("original_size out of range: %d", original_size);
        return nullptr;
    }
    
    // 初始化 zlib
    z_stream stream;
    stream.zalloc = Z_NULL;
    stream.zfree = Z_NULL;
    stream.opaque = Z_NULL;
    
    // -15 = raw DEFLATE (without zlib header)
    if (inflateInit2(&stream, -15) != Z_OK) {
        env->ReleaseByteArrayElements(compressed, compressed_ptr, JNI_ABORT);
        LOGE("inflateInit2 失败");
        return nullptr;
    }
    
    stream.avail_in = compressed_len;
    stream.next_in = reinterpret_cast<Bytef*>(compressed_ptr);
    
    std::vector<uint8_t> decompressed;
    try {
        decompressed.resize((size_t)original_size);
    } catch (const std::exception&) {
        env->ReleaseByteArrayElements(compressed, compressed_ptr, JNI_ABORT);
        LOGE("alloc %d bytes failed", original_size);
        return nullptr;
    }
    
    stream.avail_out = original_size;
    stream.next_out = decompressed.data();
    
    int ret = inflate(&stream, Z_FINISH);
    inflateEnd(&stream);
    
    env->ReleaseByteArrayElements(compressed, compressed_ptr, JNI_ABORT);
    
    if (ret != Z_STREAM_END) {
        LOGE("inflate 失败: %d", ret);
        return nullptr;
    }
    
    // 创建 Java 字节数组
    jbyteArray result = env->NewByteArray(original_size);
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, original_size, 
                           reinterpret_cast<jbyte*>(decompressed.data()));
    
    return result;
}

// ============= PVR 编解码 =============

// PVR 文件头结构