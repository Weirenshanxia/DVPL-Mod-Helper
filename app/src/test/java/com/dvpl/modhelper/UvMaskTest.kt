package com.dvpl.modhelper

import com.dvpl.modhelper.ui.rasterizeMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UV 覆盖掩码光栅化（部件贴图原位导出的核心）。
 * 锁两件最容易写反的事: V 轴方向（UV V 向上, 位图行 0 在上）与
 * 平铺件 UV 折回主 [0,1] 块（REPEAT 语义）。
 */
class UvMaskTest {

    private fun covered(mask: ByteArray, w: Int, x: Int, y: Int): Boolean =
        mask[y * w + x] != 0.toByte()

    /** 掩码覆盖纹素数（膨胀前的裸光栅化结果） */
    private fun count(mask: ByteArray): Int = mask.count { it != 0.toByte() }

    @Test
    fun vAxisPointsUp() {
        // UV 上半（v 0.5..1）的三角形 → 位图上半（y 小）
        val uv = floatArrayOf(
            0f, 1f, 1f, 1f, 0f, 0.5f,
            1f, 1f, 1f, 0.5f, 0f, 0.5f
        )
        val mask = rasterizeMask(uv, 64, 64)
        assertTrue(covered(mask, 64, 32, 4))      // 顶部覆盖
        assertTrue(!covered(mask, 64, 32, 60))    // 底部不覆盖
    }

    @Test
    fun tiledUvFoldsBackToBaseTile() {
        // 平铺件（履带）: u 落在第 3 块 2.0..2.5 → 折回 0..0.5
        val uv = floatArrayOf(
            2f, 0f, 2.5f, 0f, 2f, 1f,
            2.5f, 0f, 2.5f, 1f, 2f, 1f
        )
        val mask = rasterizeMask(uv, 64, 64)
        assertTrue(covered(mask, 64, 8, 32))      // 左半覆盖
        assertTrue(!covered(mask, 64, 56, 32))    // 右半不覆盖
    }

    @Test
    fun uncoveredStaysZeroAndAreaMatches() {
        // 左下 1/4 的矩形（两个三角形）: 面积 ≈ 1/4 纹素数
        val uv = floatArrayOf(
            0f, 0f, 0.5f, 0f, 0f, 0.5f,
            0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f
        )
        val mask = rasterizeMask(uv, 64, 64)
        assertEquals(32 * 32, count(mask))
        assertTrue(covered(mask, 64, 4, 60))      // 左下角（v 小 → y 大）
        assertTrue(!covered(mask, 64, 4, 4))      // 左上角空
    }

    @Test
    fun degenerateTrianglesAreSkipped() {
        // 退化三角形（三点共线）不应污染掩码
        val uv = floatArrayOf(0f, 0f, 0.5f, 0.5f, 1f, 1f)
        assertEquals(0, count(rasterizeMask(uv, 32, 32)))
    }
}
