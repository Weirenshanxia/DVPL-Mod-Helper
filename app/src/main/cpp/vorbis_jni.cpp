#include <jni.h>
#include <vorbis/vorbisenc.h>
#include <ogg/ogg.h>
#include <string.h>
#include <stdlib.h>
#include <vector>

/**
 * PCM (16-bit LE, interleaved) → Ogg Vorbis bytes
 * quality: -0.1 (lowest) … 1.0 (highest); 0.3 ≈ ~112 kbps stereo
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_dvpl_modhelper_codec_WwiseNative_wpcmToOgg(
        JNIEnv *env, jclass /*clazz*/,
        jbyteArray jPcm, jint sampleRate, jint channels, jfloat quality)
{
    jsize pcmLen = env->GetArrayLength(jPcm);
    jbyte *pcmData = env->GetByteArrayElements(jPcm, nullptr);

    int numSamples = (int)(pcmLen / 2 / channels); // 16-bit samples per channel

    ogg_stream_state os;
    ogg_page         og;
    ogg_packet       op;
    vorbis_info      vi;
    vorbis_comment   vc;
    vorbis_dsp_state vd;
    vorbis_block     vb;

    // --- init encoder ---
    vorbis_info_init(&vi);
    int ret = vorbis_encode_init_vbr(&vi, channels, sampleRate, quality);
    if (ret != 0) {
        env->ReleaseByteArrayElements(jPcm, pcmData, JNI_ABORT);
        return nullptr;
    }

    vorbis_comment_init(&vc);
    vorbis_comment_add_tag(&vc, "ENCODER", "DvplModHelper");

    vorbis_analysis_init(&vd, &vi);
    vorbis_block_init(&vd, &vb);

    ogg_stream_init(&os, 1); // serial = 1

    // --- write headers ---
    ogg_packet header, header_comm, header_code;
    vorbis_analysis_headerout(&vd, &vc, &header, &header_comm, &header_code);
    ogg_stream_packetin(&os, &header);
    ogg_stream_packetin(&os, &header_comm);
    ogg_stream_packetin(&os, &header_code);

    std::vector<unsigned char> outBuf;
    outBuf.reserve(pcmLen / 2 + 4096);

    // flush header pages
    while (ogg_stream_flush(&os, &og)) {
        outBuf.insert(outBuf.end(), og.header, og.header + og.header_len);
        outBuf.insert(outBuf.end(), og.body, og.body + og.body_len);
    }

    // --- encode PCM ---
    const int BLOCK = 1024;
    int pos = 0;
    const short *pcm16 = reinterpret_cast<const short*>(pcmData);

    while (pos <= numSamples) {
        int todo = (pos == numSamples) ? 0 : (numSamples - pos < BLOCK ? numSamples - pos : BLOCK);

        if (todo == 0) {
            vorbis_analysis_wrote(&vd, 0); // EOS
        } else {
            float **buf = vorbis_analysis_buffer(&vd, todo);
            for (int ch = 0; ch < channels; ch++) {
                for (int i = 0; i < todo; i++) {
                    buf[ch][i] = pcm16[(pos + i) * channels + ch] / 32768.0f;
                }
            }
            vorbis_analysis_wrote(&vd, todo);
            pos += todo;
        }

        while (vorbis_analysis_blockout(&vd, &vb) == 1) {
            vorbis_analysis(&vb, nullptr);
            vorbis_bitrate_addblock(&vb);
            while (vorbis_bitrate_flushpacket(&vd, &op)) {
                ogg_stream_packetin(&os, &op);
                while (ogg_stream_pageout(&os, &og)) {
                    outBuf.insert(outBuf.end(), og.header, og.header + og.header_len);
                    outBuf.insert(outBuf.end(), og.body, og.body + og.body_len);
                }
                if (op.e_o_s) {
                    // flush last page
                    while (ogg_stream_flush(&os, &og)) {
                        outBuf.insert(outBuf.end(), og.header, og.header + og.header_len);
                        outBuf.insert(outBuf.end(), og.body, og.body + og.body_len);
                    }
                    goto done;
                }
            }
        }

        if (todo == 0) break;
    }
done:
    ogg_stream_clear(&os);
    vorbis_block_clear(&vb);
    vorbis_dsp_clear(&vd);
    vorbis_comment_clear(&vc);
    vorbis_info_clear(&vi);

    env->ReleaseByteArrayElements(jPcm, pcmData, JNI_ABORT);

    if (outBuf.empty()) return nullptr;
    jbyteArray result = env->NewByteArray((jsize)outBuf.size());
    env->SetByteArrayRegion(result, 0, (jsize)outBuf.size(),
                            reinterpret_cast<const jbyte*>(outBuf.data()));
    return result;
}
