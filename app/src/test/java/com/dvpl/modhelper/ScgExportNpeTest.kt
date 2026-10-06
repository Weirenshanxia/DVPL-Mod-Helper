package com.dvpl.modhelper

import com.dvpl.modhelper.codec.ScgConverter
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * PC 端 Steam 真实数据完整复刻导出管线，复现真机 NullPointerException。
 */
class ScgExportNpeTest {
    private val root = File("D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks")

    // 1:1 复刻 MainActivity.isTankSkinFile
    private fun isTankSkinFile(fileName: String, tankKeyNorm: String): Boolean {
        val n = fileName.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (!n.endsWith("scg")) return false
        val mSkin = Regex("^mskin").find(n) ?: Regex("^skin[0-9]*").find(n) ?: return false
        val rest = n.substring(mSkin.value.length)
        val ki = rest.indexOf(tankKeyNorm)
        if (ki < 0) return false
        var tail = rest.substring(ki + tankKeyNorm.length)
        tail = Regex("^skin[0-9]*").replace(tail, "")
        if (tail.startsWith("style")) tail = tail.removePrefix("style")
        return tail.startsWith("hull") || tail.startsWith("turret") ||
            tail.startsWith("gun") || tail.startsWith("mask")
    }

    @Test
    fun reproExportNpe() {
        val tanks = listOf(
            "USSR/Object268", "USSR/T-54", "USSR/KV-5",
            "German/E-75", "German/JagdTiger",
            "German/PzVI_Tiger_I", "German/PzVIB_Tiger_II",
            "USSR/T100LT"
        )
        var anyFail = false
        for (t in tanks) {
            try {
                exportTank(t)
            } catch (e: Throwable) {
                anyFail = true
                println("==== FAILED: " + t + " ====")
                e.printStackTrace()
            }
        }
        if (anyFail) fail("export pipeline threw, see stdout")
    }

    private fun exportTank(rel: String) {
        // T100LT = 用户实际崩溃的车：主 scg + 同名 sc2 + Customization 下全部皮肤件（多选手动附加 = extraScgs 路径）
        if (rel.endsWith("T100LT")) {
            val groups = ScgConverter.parseScg(
                ScgConverter.unwrapDvpl(File(root, "USSR/R132_VNII_100LT.scg.dvpl").readBytes())
            ).toMutableList()
            val info = ScgConverter.parseSc2Info(
                ScgConverter.unwrapDvpl(File(root, "USSR/R132_VNII_100LT.sc2.dvpl").readBytes()),
                groups.map { it.id }.toSet()
            )
            for (g in groups) { g.family = info.familyOf(g.id); g.sc2Name = info.familyName[g.family] }
            ScgConverter.assignLod(groups)
            ScgConverter.assignNames(groups, null, info.vocab)
            // 多选附加件（extraScgs）：id 2_000_000 起，lod=0，name=文件名去后缀
            var idOff = 2_000_000L
            val cust = File(root, "Customization").listFiles()
                ?.filter { it.name.lowercase().replace(Regex("[^a-z0-9]"), "")
                    .let { n -> n.contains("r132vnii100lt") || n.contains("r132vniit100lt") } } ?: emptyList()
            for (sf in cust) {
                val sg = try {
                    ScgConverter.parseScg(ScgConverter.unwrapDvpl(sf.readBytes()))
                } catch (e: Exception) { continue }
                if (sg.isEmpty()) continue
                val tag = sf.name.removeSuffix(".dvpl").removeSuffix(".scg")
                var piece = 0
                for (g in sg) {
                    g.id = idOff++
                    g.lod = 0
                    g.name = tag + (if (sg.size > 1) "_#" + ++piece else "")
                }
                groups.addAll(sg)
            }
            val sel = groups.map { it.id }.toSet()
            val res = ScgConverter.writeObj(groups, sel)
            println("OK T100LT: " + res.keptGroups + "/" + res.totalGroups +
                " groups, " + res.keptVertices + " verts, extras=" + cust.size +
                ", obj=" + res.objText.length + " chars")
            return
        }
        val nationDir = File(root, rel.substringBefore('/'))
        val tankName = rel.substringAfter('/')
        val scgFile = File(nationDir, tankName + ".scg.dvpl")
        val sc2File = File(nationDir, tankName + ".sc2.dvpl")

        val groups = ScgConverter.parseScg(
            ScgConverter.unwrapDvpl(scgFile.readBytes())
        ).toMutableList()
        check(groups.isNotEmpty()) { "no groups: " + tankName }

        val info = ScgConverter.parseSc2Info(
            ScgConverter.unwrapDvpl(sc2File.readBytes()),
            groups.map { it.id }.toSet()
        )
        for (g in groups) { g.family = info.familyOf(g.id); g.sc2Name = info.familyName[g.family] }

        ScgConverter.assignLod(groups)
        ScgConverter.assignNames(groups, null, info.vocab)

        val key = tankName.lowercase().replace(Regex("[^a-z0-9]"), "")
        var skinMerged = 0
        if (key.length >= 3) {
            val skins = File(root, "Customization").listFiles()
                ?.filter { isTankSkinFile(it.name, key) } ?: emptyList()
            var idOff = 1_000_000L
            for (sf in skins) {
                val sg = try {
                    ScgConverter.parseScg(ScgConverter.unwrapDvpl(sf.readBytes()))
                } catch (e: Exception) { continue }
                if (sg.isEmpty()) continue
                val tag = sf.name.removeSuffix(".dvpl").removeSuffix(".scg")
                var piece = 0
                for (g in sg) {
                    g.id = idOff++
                    g.lod = 0
                    g.name = tag + (if (sg.size > 1) "_#" + ++piece else "")
                }
                groups.addAll(sg)
                skinMerged += sg.size
            }
        }

        val sel = groups.map { it.id }.toSet()
        val res = ScgConverter.writeObj(groups, sel)
        println("OK " + tankName + ": " + res.keptGroups + "/" + res.totalGroups +
            " groups, " + res.keptVertices + " verts, merged=" + skinMerged +
            ", obj=" + res.objText.length + " chars")
    }
}
