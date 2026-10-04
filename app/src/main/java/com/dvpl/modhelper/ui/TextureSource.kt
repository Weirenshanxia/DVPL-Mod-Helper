package com.dvpl.modhelper.ui

import android.graphics.Bitmap

sealed class TextureSource {
    data class AstcCompressed(
        val width: Int,
        val height: Int,
        val blockW: Int,
        val blockH: Int,
        val isHdr: Boolean,
        val mips: List<MipData>,
        val srgb: Boolean = false   // 已废弃: 游戏法线实测为线性编码(2026-10 重验), 恒传 false
    ) : TextureSource() {
        data class MipData(val data: ByteArray, val width: Int, val height: Int)
    }

    data class DecodedBitmaps(
        val mips: List<Bitmap>
    ) : TextureSource()
}

// ASTC GL 压缩内部格式（Khronos 规范完整 14 档）。srgb=true 用 GL_COMPRESSED_SRGB8_ALPHA8_ASTC_*(0x93D0 系)，
// 采样时硬件执行 sRGB→线性解码——仅当数据确为 sRGB 编码时使用；现行游戏法线(彩色/灰度)均为线性，勿开
fun astcGlFormat(bw: Int, bh: Int, srgb: Boolean = false): Int {
    val base = if (srgb) 0x93D0 else 0x93B0
    return when {
        bw == 4 && bh == 4 -> base
        bw == 5 && bh == 4 -> base + 1
        bw == 5 && bh == 5 -> base + 2
        bw == 6 && bh == 5 -> base + 3
        bw == 6 && bh == 6 -> base + 4
        bw == 8 && bh == 5 -> base + 5
        bw == 8 && bh == 6 -> base + 6
        bw == 8 && bh == 8 -> base + 7
        bw == 10 && bh == 5 -> base + 8
        bw == 10 && bh == 6 -> base + 9
        bw == 10 && bh == 8 -> base + 10
        bw == 10 && bh == 10 -> base + 11
        bw == 12 && bh == 10 -> base + 12
        bw == 12 && bh == 12 -> base + 13
        else -> base + 13
    }
}