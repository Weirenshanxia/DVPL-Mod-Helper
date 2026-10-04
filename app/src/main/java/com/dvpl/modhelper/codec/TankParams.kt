package com.dvpl.modhelper.codec

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 已安装游戏的官方车辆参数（Parameters YAML + 车辆 XML）。
 *
 * 运行时通过 PackageManager 从已安装游戏包的 assets 读取，不往 APK 内打包任何游戏数据，
 * 未安装游戏 / 找不到对应车辆时返回 null，由几何命名兜底。
 */
object TankParams {

    /** 单个零件的碰撞盒（车体坐标系） */
    class Part(val min: FloatArray, val max: FloatArray)

    /** 一辆车的参数：零件碰撞盒 + 炮塔枢轴位置（来自车辆 XML turretPositions） */
    class Tank(
        val parts: HashMap<String, Part>,
        val turretPos: List<FloatArray>,
        val gunPos: List<FloatArray> = emptyList()
    )

    // 国服（网易）/ 国际服各发行包（internal: Wwise 音频包提取共用）
    internal val GAME_PKGS = arrayOf(
        "com.netease.wotb",
        "com.wargaming.wotblitz",
        "com.wargaming.wotblitz.eu",
        "com.wargaming.wotblitz.na",
        "com.wargaming.wotblitz.asia",
        "com.blitz470.priv"
    )

    // 车辆键(blitzModelPath 文件名) -> (包名, yaml asset 路径)
    private var tankIndex: HashMap<String, Pair<String, String>>? = null
    // yaml 文件基名 -> (包名, xml asset 路径)
    private var xmlIndex: HashMap<String, Pair<String, String>>? = null

    private val cache = ConcurrentHashMap<String, Tank>()
    // 已知无参数的车辆键（负缓存）
    private val negatives = ConcurrentHashMap.newKeySet<String>()

    /** 从 scg/sc2 文件名提取车辆键：Object268.scg.dvpl -> Object268 */
    fun keyOf(fileName: String): String {
        var k = fileName
        while (true) {
            val lower = k.lowercase()
            val e = listOf(".dvpl", ".scg", ".sc2").firstOrNull { lower.endsWith(it) }
            if (e == null) break
            k = k.substring(0, k.length - e.length)
        }
        return k
    }

    /** 按文件名查参数；未安装游戏/无该车辆返回 null。必须在 IO 线程调用。 */
    fun forTank(context: Context, fileName: String): Tank? {
        val key = keyOf(fileName)
        cache[key]?.let { return it }
        if (key in negatives) return null
        val hit = index(context)[key] ?: run { negatives.add(key); return null }
        val tank = try {
            parseTank(context, hit.first, hit.second)
        } catch (e: Exception) { null }
        if (tank != null) cache[key] = tank else negatives.add(key)
        return tank
    }

    /** 启动预热（后台调用）：建索引并返回收录车辆数，用于自检 */
    fun warmUp(context: Context): Int = index(context).size

    // ---------- 索引构建（一次性） ----------

    private fun index(context: Context): HashMap<String, Pair<String, String>> {
        tankIndex?.let { return it }
        synchronized(this) {
            tankIndex?.let { return it }
            val map = HashMap<String, Pair<String, String>>()
            val xmls = HashMap<String, Pair<String, String>>()
            val pm = context.packageManager
            for (pkg in GAME_PKGS) {
                try {
                    val am = pm.getResourcesForApplication(pkg).assets
                    // Parameters: Data/3d/Tanks/Parameters/<n>/*.yaml 与 Data/3d/Parameters/<n>/*.yaml
                    for (root in listOf("Data/3d/Tanks/Parameters", "Data/3d/Parameters")) {
                        for (nat in am.list(root) ?: continue) {
                            val dir = "$root/$nat"
                            for (f in am.list(dir) ?: continue) {
                                val isYaml = f.endsWith(".yaml") || f.endsWith(".yaml.dvpl")
                                if (!isYaml) continue
                                val path = "$dir/$f"
                                try {
                                    val head = am.open(path).use { s ->
                                        val buf = ByteArray(1024)
                                        String(buf, 0, s.read(buf), Charsets.UTF_8)
                                    }
                                    val m = Regex("blitzModelPath:\\s*\"([^\"]+)\"").find(head)
                                        ?: continue
                                    val key = m.groupValues[1].substringAfterLast('/')
                                        .removeSuffix(".sc2")
                                    if (key.isNotEmpty() && !map.containsKey(key)) map[key] = pkg to path
                                } catch (e: Exception) { /* 单个文件失败忽略 */ }
                            }
                        }
                    }
                    // 车辆 XML: Data/XML/item_defs/vehicles/<n>/<base>.xml
                    val vr = "Data/XML/item_defs/vehicles"
                    for (nat in am.list(vr) ?: continue) {
                        val dir = "$vr/$nat"
                        for (f in am.list(dir) ?: continue) {
                            if (f.endsWith(".xml")) xmls[f.removeSuffix(".xml")] = pkg to "$dir/$f"
                        }
                    }
                } catch (e: Exception) { /* 包不存在或结构不同，跳过 */ }
            }
            xmlIndex = xmls
            tankIndex = map
            return map
        }
    }

    private fun readAsset(am: android.content.res.AssetManager, path: String): ByteArray =
        am.open(path).use { s -> ScgConverter.unwrapDvpl(s.readBytes()) }

    // ---------- 解析 ----------

    private fun parseTank(context: Context, pkg: String, yamlPath: String): Tank? {
        val am = context.packageManager.getResourcesForApplication(pkg).assets
        val text = String(readAsset(am, yamlPath), Charsets.UTF_8)

        // collision: 各零件碰撞盒（4 空格缩进 = 零件，12 空格 min/max = 数值）
        val parts = HashMap<String, Part>()
        val ci = text.indexOf("collision:")
        if (ci >= 0) {
            var cur: String? = null
            var mn: FloatArray? = null
            var mx: FloatArray? = null
            fun flush() {
                val n = cur
                val a = mn; val b = mx
                // 防御: 解析出的盒必须各含 3 个数值, 否则跳过该零件
                if (n != null && a != null && b != null && a.size == 3 && b.size == 3) parts[n] = Part(a, b)
                cur = null; mn = null; mx = null
            }
            for (raw in text.substring(ci).split('\n')) {
                val line = raw.trimEnd('\r')
                when {
                    line.startsWith("    ") && !line.startsWith("     ") && line.endsWith(":") ->
                        { flush(); cur = line.trim().removeSuffix(":") }
                    line.startsWith("            min: [") ->
                        mn = floats(line.substringAfter('[').substringBefore(']'))
                    line.startsWith("            max: [") ->
                        mx = floats(line.substringAfter('[').substringBefore(']'))
                }
            }
            flush()
        }
        if (parts.isEmpty()) return null

        // 车辆 XML: 炮塔枢轴位置 + 炮枢轴（gunPosition，炮盾定位用）
        val turretPos = ArrayList<FloatArray>()
        val gunPos = ArrayList<FloatArray>()
        val base = yamlPath.substringAfterLast('/').removeSuffix(".yaml.dvpl").removeSuffix(".yaml")
        val xmlHit = xmlIndex?.get(base)
        if (xmlHit != null) {
            try {
                val xt = String(
                    readAsset(context.packageManager.getResourcesForApplication(xmlHit.first).assets, xmlHit.second),
                    Charsets.UTF_8
                )
                val a = xt.indexOf("<turretPositions>")
                val b = xt.indexOf("</turretPositions>")
                if (a >= 0 && b > a) {
                    for (m in Regex("<turret>([^<]+)</turret>").findAll(xt.substring(a, b))) {
                        val v = floats(m.groupValues[1].replace(',', ' '))
                        // XML 是 (x, 高度, 前后)，模型空间是 (x, 前后, 高度)
                        if (v.size == 3) turretPos.add(floatArrayOf(v[0], v[2], v[1]))
                    }
                }
                // 炮枢轴（每炮塔一个，炮塔坐标系）
                for (m in Regex("<gunPosition>([^<]+)</gunPosition>").findAll(xt)) {
                    val v = floats(m.groupValues[1].replace(',', ' '))
                    if (v.size == 3) gunPos.add(floatArrayOf(v[0], v[2], v[1]))
                }
            } catch (e: Exception) { /* 缺失不致命 */ }
        }
        return Tank(parts, turretPos, gunPos)
    }

    private fun floats(s: String): FloatArray =
        // 兼容 "0 0 0" 与 "0, 0, 0" 两种写法（私服/不同版本 YAML 用逗号分隔）
        s.trim().replace(',', ' ').split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toFloatOrNull() }.toFloatArray()
}
