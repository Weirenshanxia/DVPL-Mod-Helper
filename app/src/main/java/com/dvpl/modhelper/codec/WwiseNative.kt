package com.dvpl.modhelper.codec

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Wwise 原生转换（ww2ogg 0.24 by hcs，BSD 许可，源码零改动移植）
 * WEM(Wwise Vorbis) → 标准 Ogg Vorbis；码书 aoTuV 603 打包在 assets。
 */
object WwiseNative {
    private var loaded = false
    private var pcbFile: File? = null

    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("wwise")
            loaded = true
        }
    }

    /** 释放外部码书到 filesDir（首次调用拷贝，之后复用） */
    @Synchronized
    fun codebookFile(context: Context): File {
        pcbFile?.takeIf { it.exists() && it.length() == 74387L }?.let { return it }
        val f = File(context.filesDir, "packed_codebooks_aoTuV_603.bin")
        if (!(f.exists() && f.length() == 74387L)) {
            context.assets.open("packed_codebooks_aoTuV_603.bin").use { input ->
                FileOutputStream(f).use { output -> input.copyTo(output) }
            }
        }
        pcbFile = f
        return f
    }

    /**
     * WEM → OGG。返回 null 成功；否则返回错误信息（中文）。
     * wemFile/oggFile 均为本地文件路径（SAF Uri 需先拷到 cacheDir）。
     */
    @Synchronized
    fun wemToOgg(wemFile: File, oggFile: File, codebook: File): String? {
        ensureLoaded()
        return wwemToOgg(wemFile.absolutePath, oggFile.absolutePath, codebook.absolutePath)
    }

    private external fun wwemToOgg(wemPath: String, oggPath: String, pcbPath: String): String?

    /**
     * 一体化转换：wem 字节 → 修复过 granule 的 ogg 字节（自动处理临时文件与 revorb 等效修复）。
     * PCM 编码的 wem 不适用（直接当 wav 用，调用方自行判断）。
     */
    fun convertWemToOgg(context: Context, wemBytes: ByteArray): ByteArray {
        val codebook = codebookFile(context)
        val wemF = File.createTempFile("w2o", ".wem", context.cacheDir)
        val oggF = File.createTempFile("w2o", ".ogg", context.cacheDir)
        try {
            wemF.writeBytes(wemBytes)
            val err = wemToOgg(wemF, oggF, codebook)
            if (err != null) throw IllegalStateException(err)
            var ogg = oggF.readBytes()
            val samples = WwiseConverter.readWemSampleCount(wemBytes)
            // 原生层已写入精确 granule（末页 = 总采样数）时不再用插值覆盖；
            // 只有旧路径（全部为 0）才回退到线性插值修复
            if (samples > 0 && WwiseConverter.readOggTotalSamples(ogg) <= 0L)
                ogg = WwiseConverter.fixOggGranules(ogg, samples)
            return ogg
        } finally {
            wemF.delete()
            oggF.delete()
        }
    }
}