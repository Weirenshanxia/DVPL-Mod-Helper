package com.dvpl.modhelper.ui

import android.graphics.Bitmap

sealed class TextureSource {
    data class AstcCompressed(
        val width: Int,
        val height: Int,
        val blockW: Int,
        val blockH: Int,
        val isHdr: Boolean,
        val mips: List<MipData>
    ) : TextureSource() {
        data class MipData(val data: ByteArray, val width: Int, val height: Int)
    }

    data class DecodedBitmaps(
        val mips: List<Bitmap>
    ) : TextureSource()
}

// ASTC GL 压缩内部格式（Khronos 规范 GL_COMPRESSED_RGBA_ASTC_*_KHR 完整 14 档）
fun astcGlFormat(bw: Int, bh: Int): Int = when {
    bw == 4 && bh == 4 -> 0x93B0
    bw == 5 && bh == 4 -> 0x93B1
    bw == 5 && bh == 5 -> 0x93B2
    bw == 6 && bh == 5 -> 0x93B3
    bw == 6 && bh == 6 -> 0x93B4
    bw == 8 && bh == 5 -> 0x93B5
    bw == 8 && bh == 6 -> 0x93B6
    bw == 8 && bh == 8 -> 0x93B7
    bw == 10 && bh == 5 -> 0x93B8
    bw == 10 && bh == 6 -> 0x93B9
    bw == 10 && bh == 8 -> 0x93BA
    bw == 10 && bh == 10 -> 0x93BB
    bw == 12 && bh == 10 -> 0x93BC
    bw == 12 && bh == 12 -> 0x93BD
    else -> 0x93BD
}