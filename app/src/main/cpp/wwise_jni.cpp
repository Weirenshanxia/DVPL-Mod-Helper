#include <jni.h>
#include <string>
#include <sstream>
#include <fstream>
#include <android/log.h>
#include "wwriff.h"

#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, "WwiseOgg", __VA_ARGS__)

// 用指定参数运行一次 wem→ogg 解码；成功返回空串，失败返回错误信息
static std::string tryWemToOgg(const std::string &wemPath, const std::string &oggPath,
                               const std::string &pcbPath, bool inlineCodebooks, bool fullSetup) {
    try {
        Wwise_RIFF_Vorbis ww(wemPath, pcbPath, inlineCodebooks, fullSetup, kNoForcePacketFormat);
        std::ofstream of(oggPath, std::ios::binary);
        if (!of) {
            std::ostringstream os;
            os << "无法写出临时文件: " << oggPath;
            return os.str();
        }
        ww.generate_ogg(of);
        return std::string();
    } catch (const File_open_error &fe) {
        std::ostringstream os; os << fe; return os.str();
    } catch (const Parse_error &pe) {
        std::ostringstream os; os << pe; return os.str();
    } catch (const Argument_error &ae) {
        std::ostringstream os; os << ae; return os.str();
    } catch (const std::exception &e) {
        return std::string("转换异常: ") + e.what();
    } catch (...) {
        return std::string("未知转换错误");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dvpl_modhelper_codec_WwiseNative_wwemToOgg(
        JNIEnv *env, jclass /*clazz*/,
        jstring jWemPath, jstring jOggPath, jstring jPcbPath) {
    const char *wemPath = env->GetStringUTFChars(jWemPath, nullptr);
    const char *oggPath = env->GetStringUTFChars(jOggPath, nullptr);
    const char *pcbPath = env->GetStringUTFChars(jPcbPath, nullptr);

    // 诊断：打印输入文件大小与关键头部字节，便于定位解析问题
    {
        std::ifstream f(wemPath, std::ios::binary);
        if (f) {
            f.seekg(0, std::ios::end);
            long sz = f.tellg();
            f.seekg(0);
            char hdr[20] = {0};
            f.read(hdr, 20);
            ALOGW("wemToOgg: file=%s size=%ld head=%02x%02x%02x%02x riffsz=%02x%02x%02x%02x b12=%02x%02x%02x%02x",
                  wemPath, sz,
                  (unsigned char)hdr[0], (unsigned char)hdr[1], (unsigned char)hdr[2], (unsigned char)hdr[3],
                  (unsigned char)hdr[8], (unsigned char)hdr[9], (unsigned char)hdr[10], (unsigned char)hdr[11],
                  (unsigned char)hdr[12], (unsigned char)hdr[13], (unsigned char)hdr[14], (unsigned char)hdr[15]);
        } else {
            ALOGW("wemToOgg: cannot open %s", wemPath);
        }
    }

    // 尝试顺序：
    // 1) external codebooks, standard packets  （游戏原生 wem，码书 ID 查外部表）
    // 2) inline codebooks, stripped setup       （Wwise 压缩 setup，内联码书）
    // 3) inline codebooks, full setup           （本应用 OGG→WEM 生成的内联码书 wem）
    std::string result = tryWemToOgg(wemPath, oggPath, pcbPath, false, false);
    if (!result.empty()) ALOGW("attempt1 (external) failed: %s", result.c_str());
    if (!result.empty()) {
        std::string r2 = tryWemToOgg(wemPath, oggPath, pcbPath, true, false);
        if (r2.empty()) {
            ALOGW("attempt2 (inline-stripped) OK");
            result.clear();
        } else {
            ALOGW("attempt2 (inline-stripped) failed: %s", r2.c_str());
            std::string r3 = tryWemToOgg(wemPath, oggPath, pcbPath, true, true);
            if (r3.empty()) {
                ALOGW("attempt3 (inline-full) OK");
                result.clear();
            } else {
                ALOGW("attempt3 (inline-full) failed: %s", r3.c_str());
            }
        }
    }

    env->ReleaseStringUTFChars(jWemPath, wemPath);
    env->ReleaseStringUTFChars(jOggPath, oggPath);
    env->ReleaseStringUTFChars(jPcbPath, pcbPath);
    if (result.empty()) return nullptr;
    return env->NewStringUTF(result.c_str());
}
