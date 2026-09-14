// pvr_codec.cpp - PVR v3 完整编解码（ASTC LDR/HDR + 未压缩格式）
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <vector>
#include <memory>

#define LOG_TAG "PvrCodec"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// PVR v3 头（52 字节）
struct PVRHeader {
    uint32_t version;       // 0x03525650
    uint32_t flags;
    uint32_t pixelFormatLo; // 压缩枚举或通道顺序低32
    uint32_t pixelFormatHi; // 位深/每通道字节数
    uint32_t colorSpace;
    uint32_t channelType;
    uint32_t height;
    uint32_t width;
    uint32_t depth;
    uint32_t numSurfaces;
    uint32_t numFaces;
    uint32_t mipMapCount;
    uint32_t metaDataSize;
};

// ASTC block 尺寸枚举（WoT 自有表，与 PVR 官方标准不同！）
// 实测确认：27=4x4, 29=5x5, 30=6x5, 31=6x6, 33=8x6, 35=10x5, 38=10x10, 40=12x12
// 策略：27-45 范围内一律接受（初始猜测 6x6），真实块尺寸由 refineBlockSize
// 用 mip 链数据量反推修正——枚举值猜错也不影响正确解码（一次性根治）
static bool getAstcBlockSize(uint32_t pf, int& bw, int& bh) {
    if (pf >= 27 && pf <= 45) {
        // 已实测的枚举给精确初始值（refine 仍会校验）
        switch (pf) {
            case 27: bw = 4;  bh = 4;  break;
            case 29: bw = 5;  bh = 5;  break;
            case 30: bw = 6;  bh = 5;  break;
            case 31: bw = 6;  bh = 6;  break;
            case 33: bw = 8;  bh = 6;  break;
            case 35: bw = 10; bh = 5;  break;
            case 38: bw = 10; bh = 10; break;
            case 40: bw = 12; bh = 12; break;
            default: bw = 6;  bh = 6;  break; // 未实测枚举：默认猜测，交 refine 修正
        }
        return true;
    }
    return false;
}

// 反向：block size 到 WoT 枚举（实测表，仅编码支持的尺寸）
static uint32_t astcBlockToEnum(int bw, int bh) {
    if (bw == 4 && bh == 4) return 27;
    if (bw == 5 && bh == 5) return 29;
    if (bw == 6 && bh == 5) return 30;
    if (bw == 6 && bh == 6) return 31;
    if (bw == 8 && bh == 6) return 33;
    if (bw == 10 && bh == 5) return 35;
    if (bw == 10 && bh == 10) return 38;
    if (bw == 12 && bh == 12) return 40;
    return 31; // 默认 6x6
}

// 计算 ASTC 全 mip 链数据大小
static size_t astcMipChainSize(uint32_t w, uint32_t h, uint32_t mips, int bw, int bh) {
    size_t total = 0;
    uint32_t cw = w, ch = h;
    for (uint32_t m = 0; m < mips; m++) {
        total += (size_t)((cw + bw - 1) / bw) * ((ch + bh - 1) / bh) * 16;
        cw = (cw > 1) ? (cw + 1) / 2 : 1;
        ch = (ch > 1) ? (ch + 1) / 2 : 1;
    }
    return total;
}

// 数据量自适应校验：用实际数据大小反推正确 block size
static bool refineBlockSize(uint32_t w, uint32_t h, uint32_t mips, size_t dataSize, int& bw, int& bh) {
    static const int sizes[][2] = {{4,4},{5,4},{5,5},{6,5},{6,6},{8,5},{8,6},{8,8},{10,5},{10,6},{10,8},{10,10},{12,10},{12,12}};
    for (auto& s : sizes) {
        if (astcMipChainSize(w, h, mips, s[0], s[1]) == dataSize) {
            bw = s[0]; bh = s[1];
            return true;
        }
    }
    return false;
}


// ============ 统一 bpp 计算 ============
// pfHi 各字节 = 每通道位数（实测验证：Object268 pfHi=8→1B/px；标准 RGBA8888 pfHi=0x08080808→4B/px）
static int bppFromPfHi(uint32_t pfHi) {
    if (pfHi == 0) return 4; // 约定：WoT RGBA8888 变体（pfLo=0&&pfHi=0）
    uint32_t bits = (pfHi & 0xFF) + ((pfHi >> 8) & 0xFF) + ((pfHi >> 16) & 0xFF) + ((pfHi >> 24) & 0xFF);
    return (int)(bits / 8);
}

// 尺寸与 mip 数上限（防伪造头 OOM DoS）
static const uint32_t MAX_DIM = 16384;
static const uint32_t MAX_MIPS = 20;

// ============ JNI 接口 ============

// 声明 astc_wrapper 中的解码函数（无 jobject 参数的内部签名）
extern jintArray astcDecodeToArgb(JNIEnv* env, jbyteArray astcData, jint width, jint height,
                                  jint blockW, jint blockH, jboolean toneMap);
// 内部指针版（免 jbyteArray 中转，P8 性能优化）
extern jintArray astcDecodeToArgbRaw(JNIEnv* env, const uint8_t* data, size_t dataLen,
                                     jint width, jint height, jint blockW, jint blockH, jboolean toneMap);

/**
 * 解析 PVR 文件头
 * 返回 int[]: [w, h, mips, blockW, blockH, isAstc, bpp, dataOffset]
 */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_dvpl_modhelper_codec_PvrConverter_nativeParsePvr(
    JNIEnv* env, jobject, jbyteArray pvrData) {
    
    jsize dataLen = env->GetArrayLength(pvrData);
    if (dataLen < 52) { LOGE("PVR too small"); return nullptr; }
    jbyte* dataPtr = env->GetByteArrayElements(pvrData, nullptr);
    if (!dataPtr) return nullptr;
    const uint8_t* data = (const uint8_t*)dataPtr;
    
    const PVRHeader* hdr = (const PVRHeader*)data;
    if (hdr->version != 0x03525650) {
        env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
        LOGE("Not PVR v3"); return nullptr;
    }
    
    // B1/B3 修复：metaDataSize 校验 + 尺寸上限
    if (52 + (size_t)hdr->metaDataSize > (size_t)dataLen) {
        env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
        LOGE("PVR metaDataSize overflow"); return nullptr;
    }
    uint32_t w = hdr->width, h = hdr->height;
    uint32_t mips = hdr->mipMapCount ? hdr->mipMapCount : 1;
    if (w == 0 || h == 0 || w > MAX_DIM || h > MAX_DIM || mips > MAX_MIPS) {
        env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
        LOGE("PVR bad dims %ux%u mips=%u", w, h, mips); return nullptr;
    }
    
    uint32_t pfLo = hdr->pixelFormatLo, pfHi = hdr->pixelFormatHi;
    size_t dataOffset = 52 + hdr->metaDataSize;
    size_t dataSize = dataLen - dataOffset;
    
    int info[8] = {0};
    info[0] = (int)w; info[1] = (int)h; info[2] = (int)mips;
    
    int bw = 0, bh = 0;
    if (getAstcBlockSize(pfLo, bw, bh)) {
        // ASTC：数据量自适应校验（修正可能的枚举偏差）
        int rbw = bw, rbh = bh;
        if (refineBlockSize(w, h, mips, dataSize, rbw, rbh)) {
            bw = rbw; bh = rbh;
        }
        info[3] = bw; info[4] = bh; info[5] = 1; // isAstc
        info[6] = 0; info[7] = (int)dataOffset;
        LOGI("PVR ASTC %dx%d %ux%u mips=%u", bw, bh, w, h, mips);
    } else if (pfHi == 0 && pfLo == 0) {
        // WoT 变体：RGBA8888 未压缩
        info[3] = 0; info[4] = 0; info[5] = 0;
        info[6] = 4; // 4 字节/像素
        info[7] = (int)dataOffset;
    } else if (pfHi != 0) {
        // 标准 PVR v3 未压缩：pfLo = 通道序字符，pfHi = 每通道位数
        info[3] = 0; info[4] = 0; info[5] = 0;
        info[6] = bppFromPfHi(pfHi); // 统一 bpp 计算（A6 修复）
        info[7] = (int)dataOffset;
        LOGI("PVR uncompressed pfLo=0x%x pfHi=0x%x bpp=%d", pfLo, pfHi, info[6]);
    } else {
        env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
        LOGE("Unsupported PVR format pfLo=%u(0x%x) pfHi=0x%x", pfLo, pfLo, pfHi);
        return nullptr;
    }
    
    jintArray result = env->NewIntArray(8);
    if (!result) {
        env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
        return nullptr;
    }
    env->SetIntArrayRegion(result, 0, 8, info);
    env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
    return result;
}

/**
 * 解码 PVR 指定 mip → ARGB 像素
 * 返回 int[]: [w, h, isHDR] + ARGB 像素
 */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_dvpl_modhelper_codec_PvrConverter_nativeDecodePvrMip(
    JNIEnv* env, jobject, jbyteArray pvrData, jint mipLevel) {
    
    jsize dataLen = env->GetArrayLength(pvrData);
    if (dataLen < 52) return nullptr;
    jbyte* dataPtr = env->GetByteArrayElements(pvrData, nullptr);
    if (!dataPtr) return nullptr;
    const uint8_t* data = (const uint8_t*)dataPtr;
    const PVRHeader* hdr = (const PVRHeader*)data;
    
    if (hdr->version != 0x03525650) {
        env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
        return nullptr;
    }
    // B1/B3：metaDataSize + 尺寸校验
    if (52 + (size_t)hdr->metaDataSize > (size_t)dataLen) {
        env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
        return nullptr;
    }
    uint32_t w = hdr->width, h = hdr->height;
    uint32_t mips = hdr->mipMapCount ? hdr->mipMapCount : 1;
    if (w == 0 || h == 0 || w > MAX_DIM || h > MAX_DIM || mips > MAX_MIPS) {
        env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
        return nullptr;
    }
    if (mipLevel < 0 || mipLevel >= (int)mips) {
        env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
        return nullptr;
    }
    
    int bw = 0, bh = 0;
    bool isAstc = getAstcBlockSize(hdr->pixelFormatLo, bw, bh);
    size_t dataOffset = 52 + hdr->metaDataSize;
    size_t dataSize = dataLen - dataOffset;
    if (isAstc) {
        int rbw = bw, rbh = bh;
        if (refineBlockSize(w, h, mips, dataSize, rbw, rbh)) { bw = rbw; bh = rbh; }
    }
    
    // 逐 mip 推进偏移（A6：统一 bppFromPfHi）
    size_t offset = dataOffset;
    uint32_t mw = w, mh = h;
    for (int m = 0; m < mipLevel; m++) {
        if (isAstc) offset += (size_t)((mw + bw - 1) / bw) * ((mh + bh - 1) / bh) * 16;
        else offset += (size_t)mw * mh * bppFromPfHi(hdr->pixelFormatHi);
        mw = (mw > 1) ? (mw + 1) / 2 : 1;
        mh = (mh > 1) ? (mh + 1) / 2 : 1;
    }
    
    if (isAstc) {
        // ASTC：B1 校验 + 内部指针直传（P8 免拷贝）
        size_t mipDataSize = (size_t)((mw + bw - 1) / bw) * ((mh + bh - 1) / bh) * 16;
        if (offset + mipDataSize > (size_t)dataLen) {
            env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
            LOGE("PVR ASTC mip data truncated");
            return nullptr;
        }
        jintArray r = astcDecodeToArgbRaw(env, data + offset, mipDataSize,
                                          (jint)mw, (jint)mh, bw, bh, JNI_TRUE);
        env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
        return r;
    } else {
        // 未压缩：B1 校验 + 解码
        int bpp = bppFromPfHi(hdr->pixelFormatHi);
        size_t mipDataSize = (size_t)mw * mh * bpp;
        if (offset + mipDataSize > (size_t)dataLen) {
            env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
            LOGE("PVR uncompressed mip data truncated");
            return nullptr;
        }
        
        const size_t pixelCount = (size_t)mw * mh;
        auto pixels = std::make_unique<uint32_t[]>(pixelCount);
        const uint8_t* src = data + offset;
        
        if (hdr->pixelFormatHi == 0 && hdr->pixelFormatLo == 0) {
            // WoT 变体：RGBA8888（字节序 R,G,B,A）
            for (size_t i = 0; i < pixelCount; i++) {
                uint8_t r = src[i*4], g = src[i*4+1], b = src[i*4+2], a = src[i*4+3];
                pixels[i] = ((uint32_t)a << 24) | ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
            }
        } else if (bpp == 1) {
            // 单通道 1B/px（Object268 实测：pfHi=8 → 8bit 单通道）→ 灰度
            for (size_t i = 0; i < pixelCount; i++) {
                uint8_t v = src[i];
                pixels[i] = 0xFF000000 | ((uint32_t)v << 16) | ((uint32_t)v << 8) | v;
            }
        } else if (bpp == 4) {
            // 标准未压缩 RGBA：按 pfLo 通道序解包（pfLo 4 字节为通道字符，如 'r','g','b','a'）
            uint8_t chOrder[4] = { (uint8_t)(hdr->pixelFormatLo & 0xFF), (uint8_t)((hdr->pixelFormatLo >> 8) & 0xFF),
                                  (uint8_t)((hdr->pixelFormatLo >> 16) & 0xFF), (uint8_t)((hdr->pixelFormatLo >> 24) & 0xFF) };
            for (size_t i = 0; i < pixelCount; i++) {
                uint8_t comps[4] = {0, 0, 0, 255};
                for (int chIdx = 0; chIdx < 4; chIdx++) {
                    uint8_t ch = chOrder[chIdx];
                    if (ch == 'r') comps[0] = src[i*4 + chIdx];
                    else if (ch == 'g') comps[1] = src[i*4 + chIdx];
                    else if (ch == 'b') comps[2] = src[i*4 + chIdx];
                    else if (ch == 'a') comps[3] = src[i*4 + chIdx];
                }
                pixels[i] = ((uint32_t)comps[3] << 24) | ((uint32_t)comps[0] << 16) | ((uint32_t)comps[1] << 8) | comps[2];
            }
        } else if (bpp == 2) {
            // RGBA4444（PC DX11 PVR：pfLo="rgba" pfHi=[4,4,4,4]，实测确认）
            // 每像素 2 字节 = 两个 4bit 半字节。字节序（小端 u16）：高字节 = 第一半像素
            // 按标准 PVR v3 4444 打包：byte0 = R0<<4|G0, byte1 = B0<<4|A0
            uint8_t chOrder[4] = { (uint8_t)(hdr->pixelFormatLo & 0xFF), (uint8_t)((hdr->pixelFormatLo >> 8) & 0xFF),
                                  (uint8_t)((hdr->pixelFormatLo >> 16) & 0xFF), (uint8_t)((hdr->pixelFormatLo >> 24) & 0xFF) };
            for (size_t i = 0; i < pixelCount; i++) {
                uint8_t hi = src[i*2], lo = src[i*2+1];
                uint8_t nib[4] = { (uint8_t)(hi >> 4), (uint8_t)(hi & 0xF), (uint8_t)(lo >> 4), (uint8_t)(lo & 0xF) };
                // nib 展开到 8bit（高 4bit 复制到低 4bit）
                uint8_t ex[4] = { (uint8_t)((nib[0] << 4) | nib[0]), (uint8_t)((nib[1] << 4) | nib[1]),
                                  (uint8_t)((nib[2] << 4) | nib[2]), (uint8_t)((nib[3] << 4) | nib[3]) };
                // 按 pfLo 通道序：chOrder[k] 是第 k 个半字节对应的通道
                uint8_t comps[4] = {0, 0, 0, 255}; // r,g,b,a 默认
                for (int k = 0; k < 4; k++) {
                    uint8_t ch = chOrder[k];
                    if (ch == 'r') comps[0] = ex[k];
                    else if (ch == 'g') comps[1] = ex[k];
                    else if (ch == 'b') comps[2] = ex[k];
                    else if (ch == 'a') comps[3] = ex[k];
                }
                pixels[i] = ((uint32_t)comps[3] << 24) | ((uint32_t)comps[0] << 16) | ((uint32_t)comps[1] << 8) | comps[2];
            }
        } else {
            env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
            LOGE("Unsupported uncompressed pfLo=0x%x pfHi=0x%x bpp=%d (magic=%02x %02x %02x %02x)",
                 hdr->pixelFormatLo, hdr->pixelFormatHi, bpp,
                 data[0], data[1], data[2], data[3]);
            return nullptr;
        }
        
        env->ReleaseByteArrayElements(pvrData, dataPtr, JNI_ABORT);
        jintArray result = env->NewIntArray((jsize)(3 + pixelCount));
        if (!result) return nullptr;
        jint header[3] = { (jint)mw, (jint)mh, 0 };
        env->SetIntArrayRegion(result, 0, 3, header);
        env->SetIntArrayRegion(result, 3, (jsize)pixelCount, (const jint*)pixels.get());
        return result;
    }
}