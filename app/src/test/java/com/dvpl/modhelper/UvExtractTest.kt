package com.dvpl.modhelper

import com.dvpl.modhelper.codec.ScgConverter
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.zip.ZipFile

/** extractUvParts 提取测试（Werewolf 样例: 含 10 个 vfmt=3/24B 无UV 组） */
class UvExtractTest {

    private val zip = "D:/Download/dsh_workplace/DVPLModHelper.zip"

    private fun loadTank(entry: String): List<ScgConverter.ScgGroup> {
        // 样本 zip 为本机测试资产, 不随仓库分发: 不存在时跳过而非报错
        org.junit.Assume.assumeTrue("样本 zip 不存在（跳过）: " + zip, java.io.File(zip).isFile)
        ZipFile(zip).use { z ->
            val e = z.getEntry(entry) ?: error("missing entry " + entry)
            val raw = z.getInputStream(e).readBytes()
            return ScgConverter.parseScg(ScgConverter.unwrapDvpl(raw))
        }
    }

    @Test
    fun werewolfUvParts() {
        val groups = loadTank("Oth23_Werewolf.scg.dvpl")
        val parts = ScgConverter.extractUvParts(groups)
        assertEquals(groups.size, parts.size)
        // 无UV: 10 个 vfmt=3/24B 组 + 13 个 vc<=3 挂点标记组 = 23
        val noUv = parts.filter { !it.hasUv }
        assertEquals(23, noUv.size)
        for (p in noUv) assertEquals(0, p.uv.size)
        // 其余组带 UV；履带/皮肤等平铺件值域可到 ±6，垃圾误读是 ±1e38 级（量级断言区分）
        val withUv = parts.filter { it.hasUv }
        assertEquals(groups.size - 23, withUv.size)
        for (p in withUv) {
            assertTrue(p.uv.isNotEmpty())
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (f in p.uv) { if (f < mn) mn = f; if (f > mx) mx = f }
            assertTrue("uv range " + mn + ".." + mx, mn >= -32f && mx <= 32f)
        }
        // bbox 与汤一致
        for (p in withUv) {
            val bb = p.bbox
            assertTrue(bb[2] >= bb[0] && bb[3] >= bb[1])
        }
    }

    @Test
    fun t55aAllHaveUv() {
        // 对照组: T-55A 常规组（vc>3，挂点标记除外）全部带 UV
        val groups = loadTank("Oth11_T-55A.scg.dvpl")
        val parts = ScgConverter.extractUvParts(groups)
        assertTrue(parts.isNotEmpty())
        for (i in parts.indices) {
            if (groups[i].vertexCount > 3)
                assertTrue("T-55A part #$i 应带UV", parts[i].hasUv)
        }
    }

    @Test
    fun vFlipMatchesWriteObj() {
        // 屏幕空间 v = 1 - gameV：与 writeObj 输出一致
        val groups = loadTank("Oth11_T-55A.scg.dvpl")
        val g = groups.first { it.vertexFormat == 395 }
        val parts = ScgConverter.extractUvParts(listOf(g))
        val p = parts[0]
        assertTrue(p.hasUv)
        // 手工读第一个顶点的 uv 校验翻转
        val stride = g.stride
        val off = 24   // 395 布局 UV @+24 float
        val v = g.vertices
        val rawU = java.lang.Float.intBitsToFloat(
            ((v[off].toInt() and 0xFF) or ((v[off+1].toInt() and 0xFF) shl 8) or
             ((v[off+2].toInt() and 0xFF) shl 16) or ((v[off+3].toInt() and 0xFF) shl 24)))
        val rawV = java.lang.Float.intBitsToFloat(
            ((v[off+4].toInt() and 0xFF) or ((v[off+5].toInt() and 0xFF) shl 8) or
             ((v[off+6].toInt() and 0xFF) shl 16) or ((v[off+7].toInt() and 0xFF) shl 24)))
        // 汤中某顶点必为 (rawU, 1-rawV)（三角形汤由索引展开，取值集合等价）
        val expectU = rawU; val expectV = 1f - rawV
        var found = false
        var i = 0
        while (i + 1 < p.uv.size) {
            if (kotlin.math.abs(p.uv[i] - expectU) < 1e-6f &&
                kotlin.math.abs(p.uv[i + 1] - expectV) < 1e-6f) { found = true; break }
            i += 2
        }
        assertTrue("翻转后 (u,1-v) 应出现在汤中", found)
    }
}