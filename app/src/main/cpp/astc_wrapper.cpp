// astc_wrapper.cpp - astcenc JNI 封装（编码/解码 ASTC LDR+HDR）
#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <memory>

// astcenc 公共头
#include "astcenc.h"

#define LOG_TAG "AstcWrapper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// astcenc 需要的符号导出声明（在 astcenc_entry.cpp 中定义）

// ============ astcenc 上下文缓存（P2 性能优化）============
// thread_local：每个工作线程独立缓存，并行批量编码天然安全，无需锁
struct CtxKey { int bw; int bh; float effort; bool decodeOnly; };
static thread_local astcenc_context* t_cachedCtx = nullptr;
static thread_local CtxKey t_cachedKey { 0, 0, 0.0f, false };
static thread_local bool t_ctxValid = false;

static astcenc_context* getOrCreateContext(const astcenc_config& config, CtxKey key) {
    if (t_ctxValid && t_cachedCtx &&
        t_cachedKey.bw == key.bw && t_cachedKey.bh == key.bh &&
        t_cachedKey.effort == key.effort && t_cachedKey.decodeOnly == key.decodeOnly) {
        return t_cachedCtx; // 命中缓存，复用（省去重建分区表的开销）
    }
    if (t_cachedCtx) astcenc_context_free(t_cachedCtx);
    astcenc_context* ctx = nullptr;
    astcenc_error allocErr = astcenc_context_alloc(&config, 1, &ctx, nullptr);
    if (allocErr != ASTCENC_SUCCESS) {
        LOGE("ctx_alloc failed: %s (bw=%d bh=%d effort=%f decode=%d)",
             astcenc_get_error_string(allocErr), key.bw, key.bh, key.effort, (int)key.decodeOnly);
        return nullptr;
    }
    t_cachedCtx = ctx;
    t_cachedKey = key;
    t_ctxValid = true;
    return ctx;
}
// f16 → f32 转换（提前定义）
static inline float f16tof32_impl(uint16_t h) {
    uint32_t sign = (h >> 15) & 1;
    uint32_t exp = (h >> 10) & 0x1F;
    uint32_t mant = h & 0x3FF;
    uint32_t bits;
    if (exp == 0) {
        if (mant == 0) bits = sign << 31;
        else { // 非规格化
            exp = 127 - 15 + 1;
            while (!(mant & 0x400)) { mant <<= 1; exp--; }
            mant &= 0x3FF;
            bits = (sign << 31) | (exp << 23) | (mant << 13);
        }
    } else if (exp == 0x1F) {
        bits = (sign << 31) | 0x7F800000 | (mant << 13); // Inf/NaN
    } else {
        bits = (sign << 31) | ((exp - 15 + 127) << 23) | (mant << 13);
    }
    float f;
    memcpy(&f, &bits, 4);
    return f;
}

/**
 * 解码 ASTC 压缩数据 → RGBA8888（HDR 块自动 tone map）
 * 返回: int 数组 [w, h, isHDR] + 后跟 ARGB_8888 像素（tone map 后）
 * JNI: (byte[] astcData, int width, int height, int blockW, int blockH, boolean toneMap) -> int[]
 */
jintArray astcDecodeToArgbRaw(
    JNIEnv* env, const uint8_t* dataPtr, size_t dataLen, jint width, jint height,
    jint blockW, jint blockH, jboolean toneMap) {

    // 分配输出图像（float16 中转）
    const size_t pixelCount = (size_t)width * height;
    auto* image = new astcenc_image;
    image->dim_x = (unsigned int)width;
    image->dim_y = (unsigned int)height;
    image->dim_z = 1;
    image->data_type = ASTCENC_TYPE_F16;
    image->data = new void*[1];
    // F16 每像素 4 通道 = 8 字节
    auto* pixelBuf = new uint16_t[pixelCount * 4]();
    image->data[0] = pixelBuf;

    astcenc_config config = {};
    // 用解码专用 profile（自动识别 LDR/HDR 块）
    astcenc_profile profile = ASTCENC_PRF_LDR_SRGB;
    astcenc_config_init(profile, (unsigned int)blockW, (unsigned int)blockH, 1,
                        10.0f, ASTCENC_FLG_DECOMPRESS_ONLY, &config);

    astcenc_context* context = getOrCreateContext(config, { blockW, blockH, 10.0f, true });
    if (!context) {
        LOGE("astcenc_context_alloc failed (decode)");
        delete[] pixelBuf;
        delete[] image->data;
        delete image;
        return nullptr;
    }

    // 解码 mip0
    astcenc_swizzle swizzle { ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A };
    astcenc_error err = astcenc_decompress_image(context, (const uint8_t*)dataPtr, (size_t)dataLen,
                                   image, &swizzle, 0);

    if (err != ASTCENC_SUCCESS) {
        LOGE("astcenc_decompress_image failed: %s", astcenc_get_error_string(err));
        delete[] pixelBuf;
        delete[] image->data;
        delete image;
        return nullptr;
    }

    // F16 → ARGB8888（检测 HDR：值 > 1.0）
    const uint16_t* f16 = pixelBuf;
    bool isHDR = false;
    auto outPixels = std::make_unique<uint32_t[]>(pixelCount);
    for (size_t i = 0; i < pixelCount; i++) {
        float r = f16tof32_impl(f16[i*4+0]);
        float g = f16tof32_impl(f16[i*4+1]);
        float b = f16tof32_impl(f16[i*4+2]);
        float a = f16tof32_impl(f16[i*4+3]);
        if (r > 1.0f || g > 1.0f || b > 1.0f) isHDR = true;
        uint32_t rr, gg, bb, aa;
        if (toneMap && (r > 1.0f || g > 1.0f || b > 1.0f)) {
            // Reinhard tone map
            r = r / (1.0f + r) * 2.0f;
            g = g / (1.0f + g) * 2.0f;
            b = b / (1.0f + b) * 2.0f;
        }
        rr = (uint32_t)(fminf(fmaxf(r, 0.0f), 1.0f) * 255.0f + 0.5f);
        gg = (uint32_t)(fminf(fmaxf(g, 0.0f), 1.0f) * 255.0f + 0.5f);
        bb = (uint32_t)(fminf(fmaxf(b, 0.0f), 1.0f) * 255.0f + 0.5f);
        aa = (uint32_t)(fminf(fmaxf(a, 0.0f), 1.0f) * 255.0f + 0.5f);
        outPixels[i] = (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }

    delete[] pixelBuf;
    delete[] image->data;
    delete image;

    // 输出: [0]=w [1]=h [2]=isHDR 后跟像素
    jintArray result = env->NewIntArray((jsize)(3 + pixelCount));
    if (!result) return nullptr;
    std::vector<jint> header = { width, height, isHDR ? 1 : 0 };
    env->SetIntArrayRegion(result, 0, 3, header.data());
    env->SetIntArrayRegion(result, 3, (jsize)pixelCount, (const jint*)outPixels.get());
    return result;
}

/**
 * RGBA8888 → ASTC 编码
 * JNI: (int[] argbPixels, int width, int height, int blockW, int blockH, int quality) -> byte[]
 */
jbyteArray astcEncodeFromArgb(
    JNIEnv* env, jintArray argbPixels, jint width, jint height,
    jint blockW, jint blockH, jint quality) {

    if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return nullptr;
    jsize pixelCount = env->GetArrayLength(argbPixels);
    // B5 修复：数组至少要有 w*h 个元素（防 astcenc 越界读）；允许多余长度供 mip 复用缓冲
    const jsize need = (jsize)((int64_t)width * height);
    if (need > pixelCount) return nullptr;
    jint* inPixels = env->GetIntArrayElements(argbPixels, nullptr);
    if (!inPixels) return nullptr;

    // ARGB int → RGBA 字节（只转换 w*h 个，多余部分是 mip 复用缓冲的残留）
    const size_t dataSize = (size_t)need * 4;
    auto rgba = std::make_unique<uint8_t[]>(dataSize);
    for (jsize i = 0; i < need; i++) {
        uint32_t p = (uint32_t)inPixels[i];
        rgba[i*4+0] = (p >> 16) & 0xFF; // R
        rgba[i*4+1] = (p >> 8) & 0xFF;  // G
        rgba[i*4+2] = p & 0xFF;         // B
        rgba[i*4+3] = (p >> 24) & 0xFF; // A
    }
    env->ReleaseIntArrayElements(argbPixels, inPixels, JNI_ABORT);

    // 构建输入图像
    astcenc_image image {};
    image.dim_x = (unsigned int)width;
    image.dim_y = (unsigned int)height;
    image.dim_z = 1;
    image.data_type = ASTCENC_TYPE_U8;
    image.data = new void*[1];
    image.data[0] = rgba.get();

    astcenc_config config {};
    astcenc_profile profile = ASTCENC_PRF_LDR_SRGB;
    // quality: 0=快速 1=中 2=高 → astcenc effort (0-100)
    float effort = quality >= 2 ? 98.0f : quality == 1 ? 60.0f : 10.0f;
    astcenc_config_init(profile, (unsigned int)blockW, (unsigned int)blockH, 1,
                        effort, 0, &config);

    astcenc_context* context = getOrCreateContext(config, { blockW, blockH, effort, false });
    if (!context) {
        LOGE("context_alloc failed (encode)");
        delete[] image.data;
        return nullptr;
    }

    // 输出缓冲: ceil(w/bw)*ceil(h/bh)*16
    size_t blocksW = (width + blockW - 1) / blockW;
    size_t blocksH = (height + blockH - 1) / blockH;
    size_t outSize = blocksW * blocksH * 16;
    auto outData = std::make_unique<uint8_t[]>(outSize);

    static const astcenc_swizzle swizzle { ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A };
    astcenc_error err = astcenc_compress_image(context, &image, &swizzle, outData.get(), outSize, 0);
    delete[] image.data;

    if (err != ASTCENC_SUCCESS) {
        LOGE("compress failed: %s", astcenc_get_error_string(err));
        return nullptr;
    }

    jbyteArray result = env->NewByteArray((jsize)outSize);
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, (jsize)outSize, (const jbyte*)outData.get());
    return result;
}
// ================= jbyteArray 包装版（供 Kotlin 直接调用） =================
jintArray astcDecodeToArgb(
    JNIEnv* env, jbyteArray astcData, jint width, jint height,
    jint blockW, jint blockH, jboolean toneMap) {
    jsize dataLen = env->GetArrayLength(astcData);
    jbyte* dataPtr = env->GetByteArrayElements(astcData, nullptr);
    if (!dataPtr) return nullptr;
    jintArray result = astcDecodeToArgbRaw(env, (const uint8_t*)dataPtr, (size_t)dataLen,
                                           width, height, blockW, blockH, toneMap);
    env->ReleaseByteArrayElements(astcData, dataPtr, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_dvpl_modhelper_codec_PvrConverter_nativeAstcDecode(
    JNIEnv* env, jobject, jbyteArray data, jint w, jint h,
    jint bw, jint bh, jboolean toneMap) {
    return astcDecodeToArgb(env, data, w, h, bw, bh, toneMap);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_dvpl_modhelper_codec_PvrConverter_nativeAstcEncode(
    JNIEnv* env, jobject thiz, jintArray pixels, jint w, jint h,
    jint bw, jint bh, jint quality) {
    return astcEncodeFromArgb(env, pixels, w, h, bw, bh, quality);
}