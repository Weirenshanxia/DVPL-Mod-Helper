package com.dvpl.modhelper

import com.dvpl.modhelper.codec.ScgConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * sc2 家族/命名/LOD 回归测试（本地游戏文件；缺文件自动跳过）。
 *
 * 背景: sc2 尾区组件名流按字母序、网格流按场景序，导出器按位置 zip——周期名与网格
 * 完全错位（炮塔网格挂轮名、炮管挂履带名，hull/turret/gun 真名不在周期流里）。
 * 因此: LOD = 周期内数据源(同部件)按 vc 降序；部件名 = 家族几何分类 + 字符串表
 * 词汇表计数匹配。侧族按 UV 平铺 + 网格复杂度区分（履带纹理沿长度平铺 >= 2.5 格；
 * 坠毁履带 = 断成碎块的网格，同尺寸下顶点数远多于正常履带——Object777 用户实测确认）。
 * 以下断言值均来自实测（T-55A/Type63/B-1bis/A-20/Werewolf/BZ_75/BDR_G1B/M4A3E8_BP/
 * Object_777）。
 */
class Sc2FamilyNamingTest {

    private fun load(scgPath: String, sc2Path: String): List<ScgConverter.ScgGroup> {
        org.junit.Assume.assumeTrue("样本不存在: " + scgPath, File(scgPath).isFile && File(sc2Path).isFile)
        val groups = ScgConverter.parseScg(ScgConverter.unwrapDvpl(File(scgPath).readBytes()))
        val sc2 = ScgConverter.unwrapDvpl(File(sc2Path).readBytes())
        val info = ScgConverter.parseSc2Info(sc2, groups.map { it.id }.toSet())
        for (g in groups) {
            g.family = info.familyOf(g.id)
            g.sc2Name = info.familyName[g.family]
        }
        ScgConverter.assignLod(groups)
        ScgConverter.assignNames(groups, null, info.vocab)
        return groups
    }

    private fun wheelNames(g: List<ScgConverter.ScgGroup>, side: String): List<String> =
        g.filter { it.name?.startsWith("chassis_wheel_" + side) == true }
            .sortedBy { it.name }
            .map { it.name!! }

    private fun wheelIds(side: String, n: Int): List<String> =
        (1..n).map { "chassis_wheel_" + side + "_%02d".format(it) }

    @Test
    fun t55a() {
        val g = load("D:/Download/dsh_workplace/3d/Tanks/Other/Oth11_T-55A.scg.dvpl",
            "D:/Download/dsh_workplace/3d/Tanks/Other/Oth11_T-55A.sc2.dvpl")
        assertEquals(65, g.size)
        // 车体 = 最大居中族（vc3429），真名无 ?
        assertEquals("hull", g.first { it.vertexCount == 3429 }.name)
        // 炮塔 3 LOD（同周期 gid 229/230/231, vc 1232/673/151 → lod 0/1/2）
        val turret = g.filter { it.id in setOf(229L, 230L, 231L) }
        assertEquals(3, turret.size)
        assertTrue(turret.all { it.name == "turret_01" })
        assertEquals(0, turret.first { it.vertexCount == 1232 }.lod)
        assertEquals(1, turret.first { it.vertexCount == 673 }.lod)
        assertEquals(2, turret.first { it.vertexCount == 151 }.lod)
        // 炮管 / 履带 / 坠毁履带 / 侧板: 履带与坠毁均平铺 11 格，坠毁 gid193 是碎裂网格
        // （261 顶点/143 连通碎块 vs 正常 gid199 168 顶点/43 碎块）→ 坠毁 = 顶点最多者;
        // gid241 侧板 UV 仅 1 格 → chassis_chassis（旧 vc 降序曾把 396 顶点侧板顶成 track）
        assertEquals("gun_01", g.first { it.id == 220L }.name)
        assertEquals("chassis_track_crash_L", g.first { it.id == 193L }.name)
        assertEquals("chassis_track_L", g.first { it.id == 199L }.name)
        assertEquals("chassis_chassis_L", g.first { it.id == 241L }.name)
        assertEquals("chassis_track_crash_R", g.first { it.id == 190L }.name)
        assertEquals("chassis_track_R", g.first { it.id == 196L }.name)
        assertEquals("chassis_chassis_R", g.first { it.id == 244L }.name)
        // 轮 7+7 全部拿到词汇真名（无 ?）
        assertEquals(wheelIds("L", 7), wheelNames(g, "L"))
        assertEquals(wheelIds("R", 7), wheelNames(g, "R"))
        // 回归: 周期名错位不得再出现——大网格不得挂轮名（旧 bug: 炮塔挂 chassis_wheel_L_01）
        assertTrue(g.none { it.name?.startsWith("chassis_wheel") == true && it.vertexCount > 800 })
    }

    @Test
    fun type63() {
        val g = load("D:/Download/dsh_workplace/japan_tmp/J36_Type_63_HT.scg.dvpl",
            "D:/Download/dsh_workplace/japan_tmp/J36_Type_63_HT.sc2.dvpl")
        assertEquals(76, g.size)
        // 车体 = vc3393 大盒（不是旧 bug 的 gid174 炮盾小盒）
        assertEquals("hull", g.first { it.vertexCount == 3393 }.name)
        assertEquals("turret_01", g.first { it.vertexCount == 798 }.name)
        // 轮 13+13 全词汇名（旧 bug: 左侧末尾轮子挂 comp.typename/count 等字典词）
        assertEquals(wheelIds("L", 13), wheelNames(g, "L"))
        assertEquals(wheelIds("R", 13), wheelNames(g, "R"))
        assertTrue(g.none { it.name?.startsWith("chassis_wheel") == true && it.vertexCount > 600 })
    }

    @Test
    fun b1bis() {
        val g = load("D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/German/B-1bis_captured.scg.dvpl",
            "D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/German/B-1bis_captured.sc2.dvpl")
        assertEquals(84, g.size)
        assertEquals("hull", g.first { it.vertexCount == 2581 }.name)
        assertEquals("turret_01", g.first { it.vertexCount == 497 }.name)
        // B-1bis 小轮 0.34m: meta 判定不得误杀（旧阈值 mid<0.35 全灭成挂点）
        assertEquals(wheelIds("L", 18), wheelNames(g, "L"))
        assertEquals(wheelIds("R", 18), wheelNames(g, "R"))
        // 侧族（全无 UV 平铺 → 纯网格复杂度序）: 坠毁 = 0.39m 窄带碎裂网格 461/459 顶点,
        // 正常履带 = 1.56m 高环绕带（Char B1 全高履带跑）324 顶点, 侧板 = 0.83m 宽悬舱 348
        assertEquals("chassis_track_crash_L", g.first { it.id == 38L }.name)
        assertEquals("chassis_track_crash_R", g.first { it.id == 81L }.name)
        assertEquals("chassis_track_L", g.first { it.id == 3L }.name)
        assertEquals("chassis_track_R", g.first { it.id == 11L }.name)
        assertEquals("chassis_chassis_L", g.first { it.id == 32L }.name)
    }

    @Test
    fun a20() {
        val g = load("D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/USSR/A-20.scg.dvpl",
            "D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/USSR/A-20.sc2.dvpl")
        assertEquals(100, g.size)
        assertEquals("hull", g.first { it.vertexCount == 2417 }.name)
        assertEquals("turret_01", g.first { it.vertexCount == 690 }.name)
        assertEquals("turret_02", g.first { it.vertexCount == 689 }.name)
        assertEquals("gun_01", g.first { it.vertexCount == 357 }.name)
        // 侧族全无 UV → 纯网格复杂度序: 坠毁 534/516 碎裂网格、正常履带 252、侧板 445/444
        assertEquals("chassis_track_crash_L", g.first { it.id == 153L }.name)
        assertEquals("chassis_track_crash_R", g.first { it.id == 145L }.name)
        assertEquals("chassis_track_L", g.first { it.id == 81L }.name)
        assertEquals("chassis_track_R", g.first { it.id == 75L }.name)
        assertEquals("chassis_chassis_L", g.first { it.id == 158L }.name)
        assertEquals("chassis_chassis_R", g.first { it.id == 110L }.name)
        assertEquals(wheelIds("L", 6), wheelNames(g, "L"))
        assertEquals(wheelIds("R", 6), wheelNames(g, "R"))
    }

    @Test
    fun werewolf() {
        // unpacked 变体（仍是 dvpl 容器；字符串表为压缩形态但词汇表可读）
        val g = load("D:/Download/dsh_workplace/werewolf/Oth23_Werewolf.scg",
            "D:/Download/dsh_workplace/werewolf/Oth23_Werewolf.sc2")
        assertEquals(66, g.size)
        assertEquals("hull", g.first { it.vertexCount == 2781 }.name)
        assertEquals("turret_01", g.first { it.vertexCount == 966 }.name)
        assertEquals("gun_01", g.first { it.vertexCount == 297 }.name)
        // 侧族: 坠毁 = 池中顶点最多的碎裂网格 408/384（281/257 连通碎块），
        // 正常履带 = 平铺 11 格的 214/215（90 碎块，zMin -0.12 触地垂坠），窄侧板 286/320
        assertEquals("chassis_track_crash_L", g.first { it.id == 122L }.name)
        assertEquals("chassis_track_crash_R", g.first { it.id == 114L }.name)
        assertEquals("chassis_track_L", g.first { it.id == 108L }.name)
        assertEquals("chassis_track_R", g.first { it.id == 100L }.name)
        assertEquals("chassis_chassis_L", g.first { it.id == 134L }.name)
        assertEquals("chassis_chassis_R", g.first { it.id == 130L }.name)
        assertEquals(wheelIds("L", 8), wheelNames(g, "L"))
        assertEquals(wheelIds("R", 8), wheelNames(g, "R"))
    }

    @Test
    fun bz75() {
        // 词汇表 15 轮/侧、实际只有 14 个独立轮 → 第 15 轮并进横跨中线的后端轮簇
        // （3.02m 宽 × 0.63m 短 × 0.66m 低）。高 vc 轮组吸收成轮类名，不得再挤掉履带名额
        val g = load("D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/China/Ch48_BZ_75.scg.dvpl",
            "D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/China/Ch48_BZ_75.sc2.dvpl")
        // 656 顶点/529 碎块 = 坠毁碎裂网格；180 顶点/55 碎块 = 正常履带
        assertEquals("chassis_track_crash_L", g.first { it.id == 260L }.name)
        assertEquals("chassis_track_crash_R", g.first { it.id == 263L }.name)
        assertEquals("chassis_track_L", g.first { it.id == 212L }.name)
        assertEquals("chassis_track_R", g.first { it.id == 227L }.name)
        assertEquals("chassis_chassis_L", g.first { it.id == 202L }.name)
        assertEquals("chassis_chassis_R", g.first { it.id == 204L }.name)
        assertEquals("chassis_wheel_15?", g.first { it.id == 234L }.name)
        assertEquals("chassis_wheel_15?", g.first { it.id == 249L }.name)
        assertEquals(wheelIds("L", 14), wheelNames(g, "L"))
        assertEquals(wheelIds("R", 14), wheelNames(g, "R"))
    }

    @Test
    fun bdr() {
        // BDR: 1.78m 高环绕履带超过旧 1.75 硬门限（旧版全灭成 null），凭 UV 平铺 11-12 格进侧族
        val g = load("D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/France/BDR_G1B.scg.dvpl",
            "D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/France/BDR_G1B.sc2.dvpl")
        assertEquals("chassis_track_crash_L", g.first { it.id == 41L }.name)
        assertEquals("chassis_track_crash_R", g.first { it.id == 22L }.name)
        assertEquals("chassis_track_L", g.first { it.id == 66L }.name)
        assertEquals("chassis_track_R", g.first { it.id == 6L }.name)
        assertEquals("chassis_chassis_L", g.first { it.id == 27L }.name)
        assertEquals("chassis_chassis_R", g.first { it.id == 38L }.name)
    }

    @Test
    fun m4a3e8bp() {
        // 冬季特效坦克: 275m 雪花粒子盒 + 50m 阴影盒被离群防护剔除，不再毒化
        // halfW/len（旧版 hull 被挤成 turret_01、侧族全变 turret_XX?）
        val g = load("D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/USA/M4A3E8_BP.scg.dvpl",
            "D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/USA/M4A3E8_BP.sc2.dvpl")
        assertEquals("hull", g.first { it.id == 43L }.name)
        assertEquals("turret_01", g.first { it.id == 33L }.name)
        assertEquals("chassis_track_crash_L", g.first { it.id == 122L }.name)
        assertEquals("chassis_track_crash_R", g.first { it.id == 121L }.name)
        assertEquals("chassis_track_L", g.first { it.id == 62L }.name)
        assertEquals("chassis_track_R", g.first { it.id == 139L }.name)
        assertEquals("chassis_chassis_L", g.first { it.id == 148L }.name)
        assertEquals("chassis_chassis_R", g.first { it.id == 155L }.name)
    }

    @Test
    fun obj777() {
        // 用户实测地面真值: 136 顶点的平铺族是正常履带, 294/298 顶点的是坠毁履带
        // （碎裂网格: 167/171 连通碎块 vs 正常 10/11——同尺寸同 UV 下碎块数天差地别）
        val g = load("D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/USSR/R119_Object_777.scg.dvpl",
            "D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/USSR/R119_Object_777.sc2.dvpl")
        assertEquals(136, g.filter { it.name == "chassis_track_L" }.maxOf { it.vertexCount })
        assertEquals(136, g.filter { it.name == "chassis_track_R" }.maxOf { it.vertexCount })
        assertEquals(294, g.filter { it.name == "chassis_track_crash_L" }.maxOf { it.vertexCount })
        assertEquals(298, g.filter { it.name == "chassis_track_crash_R" }.maxOf { it.vertexCount })
        assertEquals(372, g.filter { it.name == "chassis_chassis_L" }.maxOf { it.vertexCount })
        assertEquals(374, g.filter { it.name == "chassis_chassis_R" }.maxOf { it.vertexCount })
        assertEquals(wheelIds("L", 9), wheelNames(g, "L"))
        assertEquals(wheelIds("R", 9), wheelNames(g, "R"))
    }

    @Test
    fun skinLod() {
        // 传涂/外挂件内部自带多级 LOD（AABB 相似簇分级）——旧版一律 lod=0 会把
        // 高 LOD 网格与 lod0 一起被全量选中导出
        val f = "D:/steam/steamapps/common/World of Tanks Blitz/Data/3d/Tanks/Customization/Achilles_FR_hull_mskin.scg.dvpl"
        org.junit.Assume.assumeTrue("样本不存在: " + f, File(f).isFile)
        val g = ScgConverter.parseScg(ScgConverter.unwrapDvpl(File(f).readBytes()))
        ScgConverter.assignLod(g)
        assertEquals(5, g.size)
        assertEquals(0, g.first { it.vertexCount == 655 }.lod)
        assertEquals(1, g.first { it.vertexCount == 499 }.lod)
    }
}
