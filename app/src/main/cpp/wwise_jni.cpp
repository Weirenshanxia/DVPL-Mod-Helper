#include <jni.h>
#include <string>
#include <sstream>
#include <fstream>
#include "wwriff.h"

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

    // 先按游戏原生参数解码
    std::string result = tryWemToOgg(wemPath, oggPath, pcbPath, false, false);
    // 解析失败时用 inline_codebooks + full_setup 重试（覆盖本应用 OGG→WEM 生成的内联码书 wem）
    if (!result.empty()) {
        std::string retry = tryWemToOgg(wemPath, oggPath, pcbPath, true, true);
        if (retry.empty()) result.clear();
    }

    env->ReleaseStringUTFChars(jWemPath, wemPath);
    env->ReleaseStringUTFChars(jOggPath, oggPath);
    env->ReleaseStringUTFChars(jPcbPath, pcbPath);
    if (result.empty()) return nullptr;
    return env->NewStringUTF(result.c_str());
}
