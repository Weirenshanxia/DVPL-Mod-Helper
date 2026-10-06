package com.dvpl.modhelper

import com.dvpl.modhelper.codec.ScgConverter
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Werewolf vfmt=3 组字节级解剖: stride=16 假设是否成立 */
class WerewolfDiag2Test {
    private val zip = File("D:/Download/dsh_workplace/DVPLModHelper.zip")

    @Test
    fun diag2() {
        // 样本 zip 为本机测试资产, 不随仓库分发: 不存在时跳过而非报错
        org.junit.Assume.assumeTrue("样本 zip 不存在（跳过）: " + zip, zip.isFile)
        val zf = ZipFile(zip)
        var sc2: ByteArray? = null
        var scg: ByteArray? = null
        for (entry in zf.entries()) {
            if (entry.name.contains("Werewolf") && entry.name.endsWith(".sc2.dvpl")) sc2 = ScgConverter.unwrapDvpl(zf.getInputStream(entry).readBytes())
            if (entry.name.contains("Werewolf") && entry.name.endsWith(".scg.dvpl")) scg = ScgConverter.unwrapDvpl(zf.getInputStream(entry).readBytes())
        }
        val groups = ScgConverter.parseScg(scg!!)
        ScgConverter.assignLod(groups)
        val ids = groups.map { it.id }.toSet()
        val info = ScgConverter.parseSc2Info(sc2!!, ids)
        for (g in groups) { g.family = info.familyOf(g.id); g.sc2Name = info.familyName[g.family] }

        val v3 = groups.filter { it.vertexFormat == 3 }
        println("vfmt=3 组数: " + v3.size)
        for (g in v3) {
            println("---- #" + g.id + " name=" + g.sc2Name + " vc=" + g.vertexCount + " ic=" + g.indexCount +
                " vlen=" + g.vertices.size + " vlen/vc=" + g.vertices.size / g.vertexCount +
                " 余=" + g.vertices.size % g.vertexCount + " ilen=" + g.indices.size +
                " ic*2=" + g.indexCount * 2 + " bbox=" + g.bbox!!.joinToString { "%.2f".format(it) })
            dumpVerts(g.vertices, 16, 5)
        }

        // 参照: 一个 vfmt=395 正常组前2顶点
        val ref = groups.first { it.vertexFormat == 395 && it.vertexCount > 100 }
        println("---- 参照 vfmt=395 #" + ref.id + " name=" + ref.sc2Name + " stride=" + ref.stride +
            " vlen/vc=" + ref.vertices.size / ref.vertexCount)
        dumpVerts(ref.vertices, ref.stride, 2)

        // T-55A 有没有 vfmt=3? (全列表)
        for (entry in zf.entries()) {
            if (entry.name.contains("T-55A") && entry.name.endsWith(".scg.dvpl")) {
                val t = ScgConverter.parseScg(ScgConverter.unwrapDvpl(zf.getInputStream(entry).readBytes()))
                println("T-55A vfmt 分布: " + t.groupBy { it.vertexFormat }.entries.joinToString { it.key.toString() + "×" + it.value.size })
            }
        }
        zf.close()
    }

    /** 按每 4 字节打 f32 视图, 再打 half 视图 */
    private fun dumpVerts(v: ByteArray, stride: Int, n: Int) {
        for (i in 0 until n) {
            val o = i * stride
            val ws = ArrayList<String>()
            for (w in 0 until stride / 4) {
                val f = java.lang.Float.intBitsToFloat(u32(v, o + w * 4).toInt())
                ws.add(w.toString() + ":" + (if (f.isNaN()) "NaN" else "%.3f".format(f)))
            }
            val hs = ArrayList<String>()
            for (w in 0 until stride / 2) {
                hs.add(halfStr(u16(v, o + w * 2)))
            }
            println("   v" + i + " f32[" + ws.joinToString(" ") + "] half[" + hs.joinToString(" ") + "]")
        }
    }

    private fun halfStr(h: Int): String {
        val e = (h and 0x7C00) shr 10
        val f = h and 0x03FF
        val m = if (e == 0) f / 1024f * (1f / 16384f) else (1 + f / 1024f) * (1 shl (e - 15)).toFloat()
        return (if (h and 0x8000 != 0) "-" else "") + "%.3f".format(m)
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or (b[off + 1].toInt() and 0xFF shl 8)

    private fun u32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}
