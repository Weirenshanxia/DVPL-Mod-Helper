package com.dvpl.modhelper.codec

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PBR 贴图像素运算（纯函数，输入输出均为 ARGB_8888 IntArray）
 *
 * 实测游戏贴图通道布局（ASTC 解码验证）：
 * - RM：RGB=粗糙度（灰度 R==G==B）、A=金属度（BPC_01 金属车体 A=171，
 *        Hololive 涂装 A≈0.4，建筑 A≈2-10）
 * - BC：正常彩色 sRGB、A=不透明度
 * - MISC：灰度 RGB + A 常数
 */
object PbrOps {

    // ===== 1. RM 光泽/金属度 =====
    // gloss：正值更光滑（粗糙度降低），-100..100
    // metal：正值更金属（金属度升高），-100..100
    fun adjustRm(pixels: IntArray, gloss: Int, metal: Int): IntArray {
        val roughDelta = -gloss * 255 / 100
        val metalDelta = metal * 255 / 100
        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            val a = (((p ushr 24) and 0xFF) + metalDelta).coerceIn(0, 255)
            val r = (((p shr 16) and 0xFF) + roughDelta).coerceIn(0, 255)
            // 保持灰度：R==G==B
            (a shl 24) or (r shl 16) or (r shl 8) or r
        }
    }

    // ===== 2. 法线贴图生成（Sobel） =====
    // 从亮度图生成切线空间法线：RGB = (nx,ny,nz)*0.5+0.5，A=255
    // strength：强度 10..200（100=基准）；invert：反转凹凸
    fun generateNormal(pixels: IntArray, w: Int, h: Int, strength: Int, invert: Boolean): IntArray {
        // 亮度（Rec.601）
        val lum = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            lum[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
        }
        val s = strength / 100f * (1f / 8f)   // Sobel 梯度归一化系数
        val sign = if (invert) 1f else -1f
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val ym = if (y > 0) y - 1 else 0
            val yp = if (y < h - 1) y + 1 else h - 1
            for (x in 0 until w) {
                val xm = if (x > 0) x - 1 else 0
                val xp = if (x < w - 1) x + 1 else w - 1
                // Sobel X
                val gx = (lum[ym * w + xp] + 2f * lum[y * w + xp] + lum[yp * w + xp]
                        - lum[ym * w + xm] - 2f * lum[y * w + xm] - lum[yp * w + xm]) * s
                // Sobel Y
                val gy = (lum[yp * w + xm] + 2f * lum[yp * w + x] + lum[yp * w + xp]
                        - lum[ym * w + xm] - 2f * lum[ym * w + x] - lum[ym * w + xp]) * s
                val nx = sign * gx
                val ny = sign * gy
                val invLen = 1f / sqrt(nx * nx + ny * ny + 1f)
                val r = ((nx * invLen) * 0.5f + 0.5f) * 255f + 0.5f
                val g = ((ny * invLen) * 0.5f + 0.5f) * 255f + 0.5f
                val b = (invLen * 0.5f + 0.5f) * 255f + 0.5f
                out[y * w + x] = (0xFF shl 24) or
                        (r.toInt().coerceIn(0, 255) shl 16) or
                        (g.toInt().coerceIn(0, 255) shl 8) or
                        b.toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    // ===== 3. 通道工具 =====
    // 顺序：交换 → 反转 → 提取（提取后 RGB=该通道灰度、A=255）
    // swap：如 Pair('R','G') 交换两通道；null 不交换
    // invertSet：要反转的通道集合；extract：'R'/'G'/'B'/'A' 或 null
    fun channelOps(
        pixels: IntArray,
        swap: Pair<Char, Char>?,
        invertSet: Set<Char>,
        extract: Char?
    ): IntArray {
        return IntArray(pixels.size) { i ->
            var r = (pixels[i] shr 16) and 0xFF
            var g = (pixels[i] shr 8) and 0xFF
            var b = pixels[i] and 0xFF
            var a = (pixels[i] ushr 24) and 0xFF

            // 交换
            if (swap != null) {
                val (c1, c2) = swap
                val v1 = getCh(r, g, b, a, c1)
                val v2 = getCh(r, g, b, a, c2)
                when (c1) { 'R' -> r = v2; 'G' -> g = v2; 'B' -> b = v2; else -> a = v2 }
                when (c2) { 'R' -> r = v1; 'G' -> g = v1; 'B' -> b = v1; else -> a = v1 }
            }
            // 反转
            if ('R' in invertSet) r = 255 - r
            if ('G' in invertSet) g = 255 - g
            if ('B' in invertSet) b = 255 - b
            if ('A' in invertSet) a = 255 - a
            // 提取为灰度
            if (extract != null) {
                val v = getCh(r, g, b, a, extract)
                r = v; g = v; b = v; a = 255
            }
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun getCh(r: Int, g: Int, b: Int, a: Int, c: Char): Int = when (c) {
        'R' -> r; 'G' -> g; 'B' -> b; else -> a
    }

    // ===== 4. 颜色调色（色相/饱和度/明度） =====
    // hue：-180..180 度；sat：0..200（100=不变）；bright：-100..100。A 通道不受影响
    // 矩阵为 4x5 行主序（行=输出 RGBA，列=输入 r,g,b,a，末列为偏移）
    fun adjustColor(pixels: IntArray, hue: Int, sat: Int, bright: Int): IntArray {
        var m = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        if (hue != 0) {
            // 标准 SVG feColorMatrix hueRotate（保亮度）
            val rad = Math.toRadians(hue.toDouble())
            val c = cos(rad).toFloat()
            val sn = sin(rad).toFloat()
            val lumR = 0.213f; val lumG = 0.715f; val lumB = 0.072f
            m = concatM(m, floatArrayOf(
                lumR + c * (1 - lumR) + sn * (-lumR), lumG + c * (-lumG) + sn * (-lumG), lumB + c * (-lumB) + sn * (1 - lumB), 0f, 0f,
                lumR + c * (-lumR) + sn * 0.143f, lumG + c * (1 - lumG) + sn * 0.140f, lumB + c * (-lumB) + sn * (-0.283f), 0f, 0f,
                lumR + c * (-lumR) + sn * (-(1 - lumR)), lumG + c * (-lumG) + sn * lumG, lumB + c * (1 - lumB) + sn * lumB, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        if (sat != 100) {
            // AOSP ColorMatrix.setSaturation 同式
            val s = sat / 100f
            val inv = 1f - s
            val r = 0.213f * inv; val g = 0.715f * inv; val b = 0.072f * inv
            m = concatM(m, floatArrayOf(
                r + s, g,     b,     0f, 0f,
                r,     g + s, b,     0f, 0f,
                r,     g,     b + s, 0f, 0f,
                0f,    0f,    0f,    1f, 0f
            ))
        }
        if (bright != 0) {
            // 明度：线性缩放 + 少量偏移（避免暗部全黑）
            val bf = bright / 100f
            val scale = 1f + 0.8f * bf
            val offset = 20f * bf
            m = concatM(m, floatArrayOf(
                scale, 0f, 0f, 0f, offset,
                0f, scale, 0f, 0f, offset,
                0f, 0f, scale, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        return applyMatrix(pixels, m)
    }

    /** 4x5 仿射矩阵复合：a ∘ b（先 b 后 a） */
    private fun concatM(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(20)
        for (i in 0..3) {
            val a0 = a[i * 5]; val a1 = a[i * 5 + 1]; val a2 = a[i * 5 + 2]; val a3 = a[i * 5 + 3]
            for (j in 0..3) {
                r[i * 5 + j] = a0 * b[j] + a1 * b[5 + j] + a2 * b[10 + j] + a3 * b[15 + j]
            }
            r[i * 5 + 4] = a[i * 5 + 4] + a0 * b[4] + a1 * b[9] + a2 * b[14] + a3 * b[19]
        }
        return r
    }

    /** 把 4x5 矩阵应用到像素数组（A 行固定不变） */
    private fun applyMatrix(pixels: IntArray, m: FloatArray): IntArray {
        val a00 = m[0]; val a01 = m[1]; val a02 = m[2]; val a04 = m[4]
        val a10 = m[5]; val a11 = m[6]; val a12 = m[7]; val a14 = m[9]
        val a20 = m[10]; val a21 = m[11]; val a22 = m[12]; val a24 = m[14]
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val a = (p ushr 24) and 0xFF
            val nr = (a00 * r + a01 * g + a02 * b + a04).toInt().coerceIn(0, 255)
            val ng = (a10 * r + a11 * g + a12 * b + a14).toInt().coerceIn(0, 255)
            val nb = (a20 * r + a21 * g + a22 * b + a24).toInt().coerceIn(0, 255)
            out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        return out
    }
}
