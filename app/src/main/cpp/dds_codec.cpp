// dds_codec.cpp - DDS 解码器（基于 bcdec v0.985，BC1~BC7 全格式）
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <memory>
#include "bcdec.h"

#define LOG_TAG "DdsCodec"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct DDSHeader {
    uint32_t size;         // 124
    uint32_t flags;
    uint32_t height;
    uint32_t width;
    uint32_t pitchOrLinearSize;
    uint32_t depth;
    uint32_t mipMapCount;
    uint32_t reserved1[11];
    uint32_t pfSize;       // 32
    uint32_t pfFlags;
    uint32_t fourCC;
    uint32_t rgbBitCount;
    uint32_t rBitMask, gBitMask, bBitMask, aBitMask;
    uint32_t caps1, caps2, caps3, caps4;
    uint32_t reserved2;
};

struct DX10Header {
    uint32_t dxgiFormat;
    uint32_t resourceDimension;
    uint32_t miscFlag;
    uint32_t arraySize;
    uint32_t miscFlags2;
};

// f16 -> f32
static inline float halfToFloat(uint16_t h) {
    uint32_t sign = (h >> 15) & 1;
    uint32_t exp = (h >> 10) & 0x1F;
    uint32_t mant = h & 0x3FF;
    uint32_t bits;
    if (exp == 0) {
        if (mant == 0) bits = sign << 31;
        else {
            exp = 127 - 14;
            while (!(mant & 0x400)) { mant <<= 1; exp--; }
            mant &= 0x3FF;
            bits = (sign << 31) | (exp << 23) | (mant << 13);
        }
    } else if (exp == 0x1F) {
        bits = (sign << 31) | 0x7F800000 | (mant << 13);
    } else {
        bits = (sign << 31) | ((exp - 15 + 127) << 23) | (mant << 13);
    }
    float f;
    memcpy(&f, &bits, 4);
    return f;
}

static inline uint8_t toneMapChannel(float v) {
    if (v > 1.0f) v = v / (1.0f + v) * 2.0f;  // Reinhard
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    return (uint8_t)(v * 255.0f + 0.5f);
}

static inline uint32_t packARGB(uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    return ((uint32_t)a << 24) | ((uint32_t)r << 16) | ((uint32_t)g << 8) | b;
}

// bcdec 输出 0xAABBGGRR（b 在高位、r 在低位），Android ARGB_8888 需要 0xAARRGGBB → 交换 R/B
static inline uint32_t bcdecColorToARGB(uint32_t c) {
    return (c & 0xFF00FF00u) | ((c >> 16) & 0x000000FFu) | ((c & 0x000000FFu) << 16);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_dvpl_modhelper_codec_DdsConverter_nativeDecodeDds(
    JNIEnv* env, jobject, jbyteArray ddsData) {

    jsize dataLen = env->GetArrayLength(ddsData);
    if (dataLen < 128) { LOGE("DDS too small"); return nullptr; }
    jbyte* dataPtr = env->GetByteArrayElements(ddsData, nullptr);
    if (!dataPtr) return nullptr;
    const uint8_t* data = (const uint8_t*)dataPtr;

    if (*(const uint32_t*)data != 0x20534444) {
        env->ReleaseByteArrayElements(ddsData, dataPtr, JNI_ABORT);
        LOGE("Not DDS"); return nullptr;
    }

    const DDSHeader* hdr = (const DDSHeader*)(data + 4);
    uint32_t width = hdr->width;
    uint32_t height = hdr->height;
    if (width == 0 || height == 0 || width > 16384 || height > 16384) {
        env->ReleaseByteArrayElements(ddsData, dataPtr, JNI_ABORT);
        LOGE("Bad dims %ux%u", width, height); return nullptr;
    }

    int format = 0;         // 1=BC1 2=BC2 3=BC3 4=BC4 5=BC5 6=BC6H 7=BC7 8=未压缩
    size_t headerSize = 128;
    uint32_t fourCC = hdr->fourCC;
    bool isSigned = false;
    bool rgbIsBgrOrder = false;   // true = 内存序 B,G,R,(A)
    uint32_t bpp = 4;       // 未压缩

    if (fourCC == 0x31545844) format = 1;        // "DXT1"
    else if (fourCC == 0x33545844) format = 2;   // "DXT3"
    else if (fourCC == 0x35545844) format = 3;   // "DXT5"
    else if (fourCC == 0x30315844) {             // "DX10"
        if ((size_t)dataLen < 148) {
            env->ReleaseByteArrayElements(ddsData, dataPtr, JNI_ABORT);
            return nullptr;
        }
        const DX10Header* dx10 = (const DX10Header*)(data + 128);
        headerSize = 148;
        switch (dx10->dxgiFormat) {
            case 71: case 72: format = 1; break;  // BC1 / BC1_SRGB
            case 74: case 75: format = 2; break;  // BC2
            case 77: case 78: format = 3; break;  // BC3
            case 80: format = 4; break;           // BC4_UNORM
            case 81: format = 4; isSigned = true; break; // BC4_SNORM
            case 83: format = 5; break;           // BC5_UNORM
            case 84: format = 5; isSigned = true; break; // BC5_SNORM
            case 95: format = 6; break;           // BC6H_UF16
            case 96: format = 6; isSigned = true; break; // BC6H_SF16
            case 98: case 99: format = 7; break;  // BC7 / BC7_SRGB
            case 2:  format = 8; bpp = 4; break;  // R8G8B8A8_UNORM
            case 87: format = 9; bpp = 2; break;  // B5G5R5A1_UNORM
            default: format = 0;
        }
    }
    else if (hdr->pfFlags & 0x40) {              // DDPF_RGB 未压缩
        format = 8;
        bpp = hdr->rgbBitCount / 8;
        // 按 R 掩码判断内存字节序：0x00FF0000=RGB 序，0x000000FF=BGR 序（常规）
        if (hdr->rBitMask == 0x000000FF && bpp == 4) rgbIsBgrOrder = true;
    }

    if (format == 0) {
        env->ReleaseByteArrayElements(ddsData, dataPtr, JNI_ABORT);
        LOGE("Unsupported DDS fourCC=0x%x", fourCC);
        return nullptr;
    }

    const uint8_t* imageData = data + headerSize;
    const size_t pixelCount = (size_t)width * height;

    // B1 修复：解码前校验 mip0 所需数据不超过实际长度（防截断文件越界崩溃）
    {
        size_t required = 0;
        switch (format) {
            case 1: required = (size_t)(((width + 3) / 4)) * (((height + 3) / 4)) * 8; break;
            case 2: case 3: case 7: required = (size_t)(((width + 3) / 4)) * (((height + 3) / 4)) * 16; break;
            case 4: required = (size_t)(((width + 3) / 4)) * (((height + 3) / 4)) * 8; break;
            case 5: required = (size_t)(((width + 3) / 4)) * (((height + 3) / 4)) * 16; break;
            case 6: required = (size_t)(((width + 3) / 4)) * (((height + 3) / 4)) * 16; break;
            case 8: case 9: required = (size_t)pixelCount * bpp; break;
        }
        if (headerSize + required > (size_t)dataLen) {
            env->ReleaseByteArrayElements(ddsData, dataPtr, JNI_ABORT);
            LOGE("DDS data truncated: need %zu, have %zu", headerSize + required, (size_t)dataLen);
            return nullptr;
        }
    }
    auto pixels = std::make_unique<uint32_t[]>(pixelCount);
    const size_t blocksW = (width + 3) / 4;
    const size_t blocksH = (height + 3) / 4;
    bool ok = true;

    switch (format) {
        case 1: { // BC1 8B/块
            uint32_t block[16];
            for (size_t by = 0; by < blocksH; by++)
                for (size_t bx = 0; bx < blocksW; bx++) {
                    bcdec_bc1(imageData + (by * blocksW + bx) * 8, block, 16);
                    for (int py = 0; py < 4; py++) for (int px = 0; px < 4; px++) {
                        uint32_t x = (uint32_t)(bx*4+px), y = (uint32_t)(by*4+py);
                        if (x < width && y < height) pixels[y*width+x] = bcdecColorToARGB(block[py*4+px]);
                    }
                }
            break;
        }
        case 2: case 3: { // BC2/BC3 16B/块
            uint32_t block[16];
            for (size_t by = 0; by < blocksH; by++)
                for (size_t bx = 0; bx < blocksW; bx++) {
                    if (format == 3) bcdec_bc3(imageData + (by * blocksW + bx) * 16, block, 16);
                    else bcdec_bc2(imageData + (by * blocksW + bx) * 16, block, 16);
                    for (int py = 0; py < 4; py++) for (int px = 0; px < 4; px++) {
                        uint32_t x = (uint32_t)(bx*4+px), y = (uint32_t)(by*4+py);
                        if (x < width && y < height) pixels[y*width+x] = bcdecColorToARGB(block[py*4+px]);
                    }
                }
            break;
        }
        case 4: { // BC4 单通道 → 灰度
            uint8_t block[16];
            for (size_t by = 0; by < blocksH; by++)
                for (size_t bx = 0; bx < blocksW; bx++) {
#ifdef BCDEC_BC4BC5_PRECISE
                    bcdec_bc4(imageData + (by * blocksW + bx) * 8, block, 4, isSigned ? 1 : 0);
#else
                    bcdec_bc4(imageData + (by * blocksW + bx) * 8, block, 4);
#endif
                    for (int py = 0; py < 4; py++) for (int px = 0; px < 4; px++) {
                        uint32_t x = (uint32_t)(bx*4+px), y = (uint32_t)(by*4+py);
                        if (x < width && y < height) {
                            uint32_t v = block[py*4+px];
                            pixels[y*width+x] = 0xFF000000 | (v << 16) | (v << 8) | v;
                        }
                    }
                }
            break;
        }
        case 5: { // BC5 双通道 RG
            uint16_t block[16];
            for (size_t by = 0; by < blocksH; by++)
                for (size_t bx = 0; bx < blocksW; bx++) {
#ifdef BCDEC_BC4BC5_PRECISE
                    bcdec_bc5(imageData + (by * blocksW + bx) * 16, block, 8, isSigned ? 1 : 0);
#else
                    bcdec_bc5(imageData + (by * blocksW + bx) * 16, block, 8);
#endif
                    for (int py = 0; py < 4; py++) for (int px = 0; px < 4; px++) {
                        uint32_t x = (uint32_t)(bx*4+px), y = (uint32_t)(by*4+py);
                        if (x < width && y < height) {
                            uint32_t r = block[py*4+px] & 0xFF;
                            uint32_t g = (block[py*4+px] >> 8) & 0xFF;
                            pixels[y*width+x] = 0xFF000000 | (r << 16) | (g << 8) | 0;
                        }
                    }
                }
            break;
        }
        case 6: { // BC6H HDR → tone map
            uint16_t block[16 * 3];  // 每像素 3 个 half
            for (size_t by = 0; by < blocksH; by++)
                for (size_t bx = 0; bx < blocksW; bx++) {
                    bcdec_bc6h_half(imageData + (by * blocksW + bx) * 16, block, 4 * 3, isSigned ? 1 : 0);
                    for (int py = 0; py < 4; py++) for (int px = 0; px < 4; px++) {
                        uint32_t x = (uint32_t)(bx*4+px), y = (uint32_t)(by*4+py);
                        if (x < width && y < height) {
                            float r = halfToFloat(block[(py*4+px)*3+0]);
                            float g = halfToFloat(block[(py*4+px)*3+1]);
                            float b = halfToFloat(block[(py*4+px)*3+2]);
                            pixels[y*width+x] = packARGB(toneMapChannel(r), toneMapChannel(g), toneMapChannel(b), 255);
                        }
                    }
                }
            break;
        }
        case 7: { // BC7 16B/块
            uint32_t block[16];
            for (size_t by = 0; by < blocksH; by++)
                for (size_t bx = 0; bx < blocksW; bx++) {
                    bcdec_bc7(imageData + (by * blocksW + bx) * 16, block, 16);
                    for (int py = 0; py < 4; py++) for (int px = 0; px < 4; px++) {
                        uint32_t x = (uint32_t)(bx*4+px), y = (uint32_t)(by*4+py);
                        if (x < width && y < height) pixels[y*width+x] = bcdecColorToARGB(block[py*4+px]);
                    }
                }
            break;
        }
        case 8: case 9: { // 未压缩
            if (bpp == 4) {
                for (size_t i = 0; i < pixelCount; i++) {
                    uint8_t r, g, b, a;
                    if (rgbIsBgrOrder) { // BGR 序（常见）
                        b = imageData[i*4]; g = imageData[i*4+1]; r = imageData[i*4+2]; a = imageData[i*4+3];
                    } else {            // RGB 序（DX10 R8G8B8A8 等）
                        r = imageData[i*4]; g = imageData[i*4+1]; b = imageData[i*4+2]; a = imageData[i*4+3];
                    }
                    pixels[i] = packARGB(r, g, b, a);
                }
            } else if (bpp == 2) { // B5G5R5A1（DX10 dxgi 87）
                for (size_t i = 0; i < pixelCount; i++) {
                    uint16_t p = imageData[i*2] | (imageData[i*2+1] << 8);
                    uint8_t r = ((p >> 11) & 0x1F) << 3, g = ((p >> 6) & 0x1F) << 3;
                    uint8_t b = ((p >> 1) & 0x1F) << 3;
                    uint8_t a = (p & 1) * 255;
                    pixels[i] = packARGB(r, g, b, a);
                }
            } else {
                ok = false;
            }
            break;
        }
        default: ok = false;
    }

    env->ReleaseByteArrayElements(ddsData, dataPtr, JNI_ABORT);
    if (!ok) { LOGE("decode failed format=%d", format); return nullptr; }

    jintArray result = env->NewIntArray((jsize)(3 + pixelCount));
    if (!result) return nullptr;
    jint header[3] = { (jint)width, (jint)height, (jint)format };
    env->SetIntArrayRegion(result, 0, 3, header);
    env->SetIntArrayRegion(result, 3, (jsize)pixelCount, (const jint*)pixels.get());
    return result;
}

// BC6H signed 版本支持（dxgiFormat 96 = BC6H_SF16）
// 注：需要在 case 95/96 区分，此处 95=UF16 unsigned