package com.dvpl.modhelper

import com.dvpl.modhelper.codec.ScgConverter
import org.junit.Test
import java.io.File

/** 诊断: 哪些 vertexFormat 被缓存为「探测过但无 UV」, 它们的网格到底有没有 UV 数据 */
class ScgUvDiagTest {
    private val root = File("D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks")

    @Test
    fun diag() {
        val groups = ArrayList<ScgConverter.ScgGroup>()
        groups.addAll(ScgConverter.parseScg(
            ScgConverter.unwrapDvpl(File(root, "USSR/R132_VNII_100LT.scg.dvpl").readBytes())
        ))
        val cust = File(root, "Customization").listFiles()!!.filter {
            val n = it.name.lowercase().replace(Regex("[^a-z0-9]"), "")
            n.contains("r132vnii100lt") || n.contains("r132vniit100lt")
        }
        for (sf in cust) {
            val sg = try { ScgConverter.parseScg(ScgConverter.unwrapDvpl(sf.readBytes())) }
            catch (e: Exception) { continue }
            var piece = 0
            for (g in sg) {
                g.name = sf.name.removeSuffix(".dvpl").removeSuffix(".scg") +
                    (if (sg.size > 1) "_#" + ++piece else "")
            }
            groups.addAll(sg)
        }
        ScgConverter.assignLod(groups)
        ScgConverter.assignNames(groups, null)
        val sel = groups.map { it.id }.toSet()
        ScgConverter.writeObj(groups, sel)   // 触发探测 + 缓存

        // 反射读缓存: NO_UV 哨兵 = 该格式「无 UV」, Pair = 找到的偏移
        val f = ScgConverter::class.java.getDeclaredField("uvAutoDetected")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = f.get(ScgConverter) as Map<Any, Any>
        println("==== 缓存内容 (vfmt -> 结果) ====")
        for (e in cache.entries) {
            val v = e.value
            val desc = if (v.javaClass.simpleName == "NO_UV") "无UV" else "offset=" + (v as Pair<Int, Boolean>).first + " half=" + (v as Pair<Int, Boolean>).second
            println("  vfmt=" + e.key + " -> " + desc)
        }

        for (g in groups) {
            val res = cache[g.vertexFormat] ?: continue
            if (res.javaClass.simpleName != "NO_UV") continue
            println("---- 无UV网格: " + g.name + " id=" + g.id + " vfmt=" + g.vertexFormat + " stride=" + g.stride + " vc=" + g.vertexCount + " ic=" + g.indexCount + " ----")
            analyze(g)
        }
    }

    private fun analyze(g: ScgConverter.ScgGroup) {
        val stride = g.stride
        val vc = g.vertexCount
        if (stride < 16 || vc < 16) {
            println("   [提前返回] stride<16 或 vc<16, 未做探测")
            return
        }
        val n = minOf(vc, 256)
        val v = g.vertices
        val rows = ArrayList<String>()
        // 全偏移扫描（1 字节步进，half 每偶数偏移，f32 每 4 对齐）—— 检验探测是否漏掉末尾 half 通道
        for (off in 12 until stride) {
            for (isHalf in listOf(false, true)) {
                if (!isHalf && off % 4 != 0) continue
                val st = uvStats(v, stride, off, n, isHalf) ?: continue
                val ratio = st[0].toFloat() / n
                if (ratio >= 0.2f) rows.add("   off=" + off + (if (isHalf) " half" else " f32") +
                    " inRange=" + ratio.toString().take(5) + " dU=" + st[1] + " dV=" + st[2] +
                    " minU=" + st[3] + " maxU=" + st[4] + " minV=" + st[5] + " maxV=" + st[6])
            }
        }
        rows.sortByDescending { it.substringAfter("inRange=").substringBefore(" dU").toFloatOrNull() ?: 0f }
        if (rows.isEmpty()) println("   所有偏移样本几乎全在 [-0.05,2.05] 之外 -> 真的不是 UV")
        else { rows.take(14).forEach { println(it) }; if (rows.size > 14) println("   ...其余 " + (rows.size - 14) + " 行省略") }
        // u8x4 解读（顶点色假设验证）: 每个候选 4 字节通道按无符号字节看分布
        for (off in intArrayOf(12, 16, 20, 24)) {
            if (off + 4 > stride) continue
            val b0 = HashSet<Int>(); val b1 = HashSet<Int>(); val b2 = HashSet<Int>(); val b3 = HashSet<Int>()
            val m0 = IntArray(2); val m1 = IntArray(2); val m2 = IntArray(2); val m3 = IntArray(2)
            var cnt = 0
            for (i in 0 until minOf(vc, 256)) {
                val base = i * stride + off
                if (base + 4 > v.size) break
                val x0 = v[base].toInt() and 0xFF; val x1 = v[base + 1].toInt() and 0xFF
                val x2 = v[base + 2].toInt() and 0xFF; val x3 = v[base + 3].toInt() and 0xFF
                b0.add(x0); b1.add(x1); b2.add(x2); b3.add(x3)
                if (cnt == 0) { m0[0]=x0; m0[1]=x0; m1[0]=x1; m1[1]=x1; m2[0]=x2; m2[1]=x2; m3[0]=x3; m3[1]=x3 }
                else { if(x0<m0[0])m0[0]=x0; if(x0>m0[1])m0[1]=x0; if(x1<m1[0])m1[0]=x1; if(x1>m1[1])m1[1]=x1; if(x2<m2[0])m2[0]=x2; if(x2>m2[1])m2[1]=x2; if(x3<m3[0])m3[0]=x3; if(x3>m3[1])m3[1]=x3 }
                cnt++
            }
            println("   u8x4@" + off + ": b0[" + m0[0] + ".." + m0[1] + " d=" + b0.size +
                " b1[" + m1[0] + ".." + m1[1] + " d=" + b1.size +
                " b2[" + m2[0] + ".." + m2[1] + " d=" + b2.size +
                " b3[" + m3[0] + ".." + m3[1] + " d=" + b3.size)
        }
    }

    // 返回 [inRange, dU, dV, minU, maxU, minV, maxV]; 越界返回 null
    private fun uvStats(v: ByteArray, stride: Int, off: Int, n: Int, isHalf: Boolean): FloatArray? {
        var inRange = 0
        val du = HashSet<Long>(); val dv = HashSet<Long>()
        val need = if (isHalf) 4 else 8
        var minU = 1e9f; var maxU = -1e9f; var minV = 1e9f; var maxV = -1e9f
        for (i in 0 until n) {
            val base = i * stride + off
            if (base + need > v.size) return null
            val u = if (isHalf) half(v, base) else f32(v, base)
            val w = if (isHalf) half(v, base + 2) else f32(v, base + 4)
            if (!u.isFinite() || !w.isFinite()) continue
            if (u >= -0.05f && u <= 2.05f && w >= -0.05f && w <= 2.05f) {
                inRange++
                du.add(Math.round(u * 100.0)); dv.add(Math.round(w * 100.0))
                if (u < minU) minU = u; if (u > maxU) maxU = u
                if (w < minV) minV = w; if (w > maxV) maxV = w
            }
        }
        return floatArrayOf(inRange.toFloat(), du.size.toFloat(), dv.size.toFloat(),
            minU, maxU, minV, maxV)
    }

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or (b[o + 1].toInt() and 0xFF shl 8)

    private fun u32(b: ByteArray, o: Int): Long {
        var x = 0L; for (i in 3 downTo 0) x = (x shl 8) or (b[o + i].toLong() and 0xFF); return x
    }

    private fun f32(b: ByteArray, o: Int): Float =
        java.lang.Float.intBitsToFloat(u32(b, o).toInt())

    private fun half(b: ByteArray, o: Int): Float {
        val h = u16(b, o)
        val e = (h and 0x7C00) shr 10
        val f = h and 0x03FF
        val sg = if ((h and 0x8000) != 0) -1f else 1f
        return when (e) {
            0 -> sg * f * 5.9604645E-8f
            31 -> if (f == 0) sg * 0f else Float.NaN
            else -> sg * Math.pow(2.0, (e - 15).toDouble()).toFloat() * (1f + f / 1024f)
        }
    }
}
