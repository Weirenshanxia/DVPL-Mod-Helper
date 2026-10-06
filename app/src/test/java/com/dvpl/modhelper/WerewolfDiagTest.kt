package com.dvpl.modhelper

import com.dvpl.modhelper.codec.ScgConverter
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** 诊断: Werewolf 部件在 3D 预览/OBJ 导出中变一坨——对比 T-55A 逐组对比解析质量 */
class WerewolfDiagTest {
    private val zip = File("D:/Download/dsh_workplace/DVPLModHelper.zip")

    @Test
    fun diag() {
        // 样本 zip 为本机测试资产, 不随仓库分发: 不存在时跳过而非报错
        org.junit.Assume.assumeTrue("样本 zip 不存在（跳过）: " + zip, zip.isFile)
        val stridesField = ScgConverter::class.java.getDeclaredField("STRIDES")
        stridesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val knownStrides = stridesField.get(ScgConverter) as Map<Int, Int>

        val zf = ZipFile(zip)
        for (entry in zf.entries()) {
            if (!entry.name.endsWith(".scg.dvpl")) continue
            val tank = entry.name.substringBefore(".scg.dvpl")
            val raw = zf.getInputStream(entry).readBytes()
            val unwrapped = try { ScgConverter.unwrapDvpl(raw) }
            catch (e: Exception) { println("==== " + tank + " DVPL 解包失败: " + e.message); continue }
            println("==== " + tank + " 解包 " + unwrapped.size + "B 头=" +
                unwrapped.take(8).joinToString(" ") { String.format("x", it) })
            val groups = try { ScgConverter.parseScg(unwrapped) }
            catch (e: Exception) { println("  parseScg 抛异常: " + e.message); continue }
            ScgConverter.assignLod(groups)
            println("  groups=" + groups.size)
            val vfmts = HashMap<Int, Int>()
            for (g in groups) vfmts[g.vertexFormat] = (vfmts[g.vertexFormat] ?: 0) + 1
            println("  vertexFormat 分布: " + vfmts.entries.sortedBy { it.key }
                .joinToString { it.key.toString() + "×" + it.value +
                    " stride=" + g1(knownStrides, it.key, groups) })
            val ifmts = groups.map { it.indexFormat }.distinct().sorted()
            println("  indexFormat 分布: " + ifmts)
            for (g in groups) {
                val s = g.stride
                val known = knownStrides.containsKey(g.vertexFormat)
                val pad = if (g.vertexCount > 0) g.vertices.size % g.vertexCount else -1
                var nan = 0; var zero = 0; var big = 0f
                for (i in 0 until g.vertexCount) {
                    val o = i * s
                    for (a in 0..2) {
                        val f = floatBits(g.vertices, o + a * 4)
                        if (f.isNaN() || f.isInfinite()) nan++
                        else if (a == 0 && f == 0f && floatBits(g.vertices, o + 4) == 0f &&
                            floatBits(g.vertices, o + 8) == 0f) { zero++; break }
                        else if (Math.abs(f) > big) big = Math.abs(f)
                    }
                }
                // 索引越界检查
                var maxIdx = -1L
                if (g.indexFormat == 0) {
                    for (i in 0 until g.indexCount) {
                        val v = u16(g.indices, i * 2).toLong(); if (v > maxIdx) maxIdx = v }
                } else {
                    for (i in 0 until g.indexCount) {
                        val v = u32(g.indices, i * 4); if (v > maxIdx) maxIdx = v }
                }
                val badIdx = if (maxIdx >= g.vertexCount) "IDX 越界 " + maxIdx + ">=" + g.vertexCount + " " else ""
                val badStride = if (!known) "未知vfmt! " else ""
                val badPad = if (pad != 0) "顶点区不对齐余" + pad + " " else ""
                val badNan = if (nan > 0) "NaN/Inf×" + nan + " " else ""
                val badZero = if (g.vertexCount > 4 && zero * 100 > g.vertexCount * 60) "零点比例" + zero + " " else ""
                val badBig = if (big > 100f) "坐标幅度" + big + " " else ""
                val flags = badStride + badPad + badNan + badZero + badBig + badIdx
                if (flags.isNotEmpty())
                    println("  [!!] " + tank + " #" + g.id + " vfmt=" + g.vertexFormat + " stride=" + s +
                        " vc=" + g.vertexCount + " ic=" + g.indexCount + " vlen=" + g.vertices.size +
                        " :: " + flags)
            }
        }
        zf.close()
    }

    private fun g1(m: Map<Int, Int>, k: Int, groups: List<ScgConverter.ScgGroup>): String {
        // 该 vfmt 实际 stride（含回退值）
        val g = groups.firstOrNull { it.vertexFormat == k } ?: return "?"
        return g.stride.toString() + (if (m.containsKey(k)) "(表)" else "(回退!)")
    }

    private fun floatBits(b: ByteArray, off: Int): Float =
        java.lang.Float.intBitsToFloat(u32(b, off).toInt())

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or (b[off + 1].toInt() and 0xFF shl 8)

    private fun u32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}
