package com.dvpl.modhelper.codec

import kotlin.math.abs

/**
 * SCG（WoT Blitz 场景几何容器）解析与 OBJ 导出。
 *
 * 格式要点（逆向自原版坦克模型，已在 Tiger I / IS-3 / T-34-85 / XM551 四辆原版车上验证）：
 * - 文件头: "SCPG" + u32 ver + u32 recordCount ×2
 * - 记录流: "KA\x01\x00" + u32 pairCount + N 个属性对
 *   属性对 = 名称(type4: [4][u32 len][utf8]) + 值(type2: [2][u32] / type4: [4][u32 len][utf8] / type6: [6][u32 len][bytes])
 * - 每辆车 = 若干 PolygonGroup：整车全部可研发配件、全部 LOD、挂点标记都在同一个文件里
 * - 顶点: 位置恒为 float3 @ +0/+4/+8（米制，Z 向上），法线 @ +12..+20；步长由 vertexFormat 决定：
 *   3=16B(简化) 395=56B 399=60B 411=64B(蒙皮)；未知格式回退 stride = size / vertexCount
 * - 索引: u16 (indexFormat=0) / u32，三角列表
 *
 * 配件名（材质名）来自同名 .sc2 场景文件：
 * - 字符串表: 首个 "KA\x02\x00" 记录 = [u32 count] + N × [u16 len][utf8]
 * - 引用模板: [u32 0xBDD9B834][0x0A][u64 组ID][u32 0xCD75CF90][0x0A][u64 字符串索引]
 *
 * LOD 分级：同一包围盒（量化 0.1m）内按顶点数降序——顶点最多 = LOD0（最高细节）；
 * 相同顶点数 = 同级不同配件（例如 IS-3 的 4 门炮各 337 顶点并排）。
 * 挂点标记 = 仅 3 顶点的小三角（HP_* 火点/挂点），不是实际几何。
 */
object ScgConverter {

    /** 已知顶点格式的步长（字节） */
    private val STRIDES = mapOf(
        3 to 16, 11 to 32, 17 to 20, 27 to 40, 31 to 44,
        395 to 56, 399 to 60, 411 to 64, 415 to 68, 923 to 68,
        507 to 80, 511 to 84, 907 to 60, 911 to 64, 1023 to 88,
        49547 to 88, 49551 to 92, 49563 to 96, 49567 to 100, 49595 to 104, 49663 to 116
    )

    /**
     * UV 通道位置: vertexFormat -> (顶点内偏移, 是否半精度float2)。
     * 实测采样验证（采样值域+变化数判定）:
     * - 布局A: pos@+0(12B) normal@+12(12B) uv float2@+24 —— 11/395/399/411/507/907
     *   (411 为修正: 原 half@24 推断有误, 实测 float@24, 另有 uv1@28/uv2@32 附加通道)
     * - 布局B: pos@+0(12B) normal@+12(12B) tangent@+24(4B打包) uv float2@+28 —— 911/511/1023/495xx
     * - 3 = 16B: pos@+0 + uv half2@+12（布局推断）。
     * 注意: 皮肤件 UV 常平铺到 0..2 (u 最大到 1.995)，属正常现象。
     */
    private val UV_LAYOUT = mapOf(
        3 to Pair(12, true),
        11 to Pair(24, false),
        395 to Pair(24, false),
        399 to Pair(24, false),
        411 to Pair(24, false),   // 修正: 实测 float@24（原推断 half@24 错误）
        507 to Pair(24, false),
        511 to Pair(28, false),
        907 to Pair(24, false),   // 395 + 0x200 (蒙皮权重附加通道), stride 60, UV 同 395 @+24 float
        911 to Pair(28, false),   // 399 + 0x200, tangent 打包 @+24, UV @+28 float
        923 to Pair(28, false),   // 411 + 0x200, 多用于炮塔皮肤件
        415 to Pair(28, false),   // 411 + 0x4 (tangent), Type5 Exp 等皮肤件实测
        17 to Pair(12, false),    // pos(12)+uv(8) 布局, 实测
        27 to Pair(28, false),    // 实测(另有 uv1@32)
        31 to Pair(28, false),    // 实测(另有 uv1@32)
        1023 to Pair(28, false),
        49547 to Pair(24, false),
        49551 to Pair(28, false),
        49563 to Pair(24, false),
        49567 to Pair(24, false),
        49595 to Pair(24, false),
        49663 to Pair(28, false)
    )

    /**
     * 未知格式的 UV 自动探测结果缓存（vfmt -> 偏移+精度）。
     * 注意：ConcurrentHashMap 不允许 null 值（put 直接 NPE），
     * "探测过但无 UV" 用 NO_UV 哨兵表示（T-100 LT 皮肤件曾在此崩溃）。
     */
    private object NO_UV
    private val uvAutoDetected = java.util.concurrent.ConcurrentHashMap<Int, Any>()

    /** UV 布局查询: 已知格式用表, 未知格式运行时探测并缓存（一劳永逸应对新格式） */
    private fun uvLayoutFor(g: ScgGroup): Pair<Int, Boolean>? {
        UV_LAYOUT[g.vertexFormat]?.let { return it }
        val cached = uvAutoDetected[g.vertexFormat]
        if (cached != null) return if (cached === NO_UV) null else cached as Pair<Int, Boolean>
        val r = detectUvLayout(g)
        uvAutoDetected[g.vertexFormat] = r ?: NO_UV
        return r
    }

    /**
     * 自动探测 UV 通道: 按偏移从小到大, float/half 两种精度采样验证。
     * 判定标准(经 20+ 种格式实测校准): ≥95% 采样对落在 [-0.05,2.05](容平铺),
     * 且取值有变化(≥15 种不同对, 排除全零的 tangent/填充位)。
     */
    private fun detectUvLayout(g: ScgGroup): Pair<Int, Boolean>? {
        val stride = g.stride
        if (stride < 16 || g.vertexCount < 16) return null
        val n = minOf(g.vertexCount, 256)
        val v = g.vertices
        // 扫描到 stride-4：末尾 half2 UV 通道也要测到（vfmt=515 stride=28 实测发现原盲区）。
        // f32 对必须 off+8<=stride，防止读到下一个顶点的字节产生假样本。
        // 第一遍: 双轴都变化的真实 UV（最典型形态）
        for (off in 12 until stride - 3 step 2) {
            if (off + 8 <= stride)
                uvStats(v, stride, off, n, false)?.takeIf { it.both }?.let { return Pair(off, false) }
            uvStats(v, stride, off, n, true)?.takeIf { it.both }?.let { return Pair(off, true) }
        }
        // 第二遍: 单轴 UV（如蒙版件整列映射到纹理一行, 另一轴恒定）
        for (off in 12 until stride - 3 step 2) {
            if (off + 8 <= stride)
                uvStats(v, stride, off, n, false)?.takeIf { it.single }?.let { return Pair(off, false) }
            uvStats(v, stride, off, n, true)?.takeIf { it.single }?.let { return Pair(off, true) }
        }
        return null
    }

    /** UV 采样统计: 达标比例 + 两轴各自的变化数 */
    private class UvStats(val inRange: Int, val n: Int, val dU: Int, val dV: Int) {
        val both = inRange >= n * 0.95f && dU >= 8 && dV >= 8
        val single = inRange >= n * 0.95f && maxOf(dU, dV) >= 16
    }

    private fun uvStats(v: ByteArray, stride: Int, off: Int, n: Int, isHalf: Boolean): UvStats? {
        var inRange = 0
        val distinctU = HashSet<Long>()
        val distinctV = HashSet<Long>()
        val need = if (isHalf) 4 else 8
        for (i in 0 until n) {
            val base = i * stride + off
            if (base + need > v.size) return null
            val u = if (isHalf) half(v, base) else f32(v, base)
            val w = if (isHalf) half(v, base + 2) else f32(v, base + 4)
            if (!u.isFinite() || !w.isFinite()) continue
            if (u >= -0.05f && u <= 2.05f && w >= -0.05f && w <= 2.05f) {
                inRange++
                distinctU.add(Math.round(u * 100.0))
                distinctV.add(Math.round(w * 100.0))
                if (distinctU.size > 4096 || distinctV.size > 4096) break
            }
        }
        return UvStats(inRange, n, distinctU.size, distinctV.size)
    }

    /** IEEE 754 half-float -> float（UV 用） */
    private fun half(b: ByteArray, off: Int): Float {
        val h = u16(b, off)
        val e = (h and 0x7C00) shr 10
        val f = h and 0x03FF
        val m = if (e == 0) f / 1024f * (1f / 16384f) else (1 + f / 1024f) * (1 shl (e - 15)).toFloat()
        return if (h and 0x8000 != 0) -m else m
    }

    /** sc2 引用模板里的属性标识（引擎属性字典 ID，逆向确认跨车一致） */
    private const val HASH_GEO_REF = 0xBDD9B834L   // PolygonGroup 引用
    private const val HASH_NAME_REF = 0xCD75CF90L  // 材质名引用

    class ScgGroup(
        var id: Long,
        val vertexCount: Int,
        val indexCount: Int,
        val indexFormat: Int,
        val vertexFormat: Int,
        val vertices: ByteArray,
        val indices: ByteArray
    ) {
        /** sc2 解析出的材质名（原始映射，未经几何校验，不可直接显示） */
        var sc2Name: String? = null

        /** 最终显示/导出名（assignNames() 决定：几何分类 > 皮肤/装饰名 > 白名单名） */
        var name: String? = null

        /** LOD 等级：0 = 最高细节 */
        var lod = 0

        /** 包围盒 [mnX,mnY,mnZ,mxX,mxY,mxZ]，assignLod() 时计算 */
        var bbox: FloatArray? = null

        /** 顶点步长（字节） */
        val stride: Int
            get() = STRIDES[vertexFormat] ?: if (vertexCount > 0) vertices.size / vertexCount else 0
    }

    // ---------- 底层读取 ----------

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or (b[off + 1].toInt() and 0xFF shl 8)

    private fun u32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun f32(b: ByteArray, off: Int): Float =
        java.lang.Float.intBitsToFloat(u32(b, off).toInt())

    private fun matches(b: ByteArray, off: Int, magic: String): Boolean {
        if (off + magic.length > b.size) return false
        for (i in magic.indices) if (b[off + i] != magic[i].code.toByte()) return false
        return true
    }

    // ---------- SCG 解析 ----------

    /** 解析 SCG 字节流（已解 DVPL）为 PolygonGroup 列表；非 SCG 文件抛 IllegalArgumentException */
    fun parseScg(data: ByteArray): List<ScgGroup> {
        require(data.size > 16 && matches(data, 0, "SCPG")) { "not SCPG" }
        val groups = ArrayList<ScgGroup>()
        var pos = 0x10
        while (pos + 8 <= data.size) {
            if (!matches(data, pos, "KA\u0001")) { pos++; continue }
            val pairCount = u32(data, pos + 4).toInt()
            var p = pos + 8
            var id = -1L
            var vc = 0; var ic = 0; var ifmt = 0; var vfmt = 0
            var verts: ByteArray? = null; var idx: ByteArray? = null
            var pairsOk = true
            for (i in 0 until pairCount) {
                if (p + 5 > data.size) { pairsOk = false; break }
                // 属性名: [04][u32 len][utf8]
                if (data[p] != 4.toByte()) { pairsOk = false; break }
                val nLen = u32(data, p + 1).toInt()
                if (nLen > 64 || p + 5 + nLen > data.size) { pairsOk = false; break }
                val name = String(data, p + 5, nLen, Charsets.UTF_8)
                p += 5 + nLen
                if (p >= data.size) { pairsOk = false; break }
                // 值: 类型 2 = u32；类型 4 = 长度前缀字符串；其余(6 等) = 长度前缀二进制
                // 注意 #id 是 8 字节 u64 blob（type 6），需在 blob 分支取低 32 位
                val vt = data[p].toInt() and 0xFF
                if (vt == 2) {
                    val v = u32(data, p + 1).toInt()
                    when (name) {
                        "vertexCount" -> vc = v
                        "indexCount" -> ic = v
                        "indexFormat" -> ifmt = v
                        "vertexFormat" -> vfmt = v
                    }
                    p += 5
                } else {
                    val len = u32(data, p + 1).toInt()
                    if (len < 0 || len > data.size - (p + 5)) { pairsOk = false; break }
                    if (vt == 4 && len > 4096) { pairsOk = false; break }
                    if (vt != 4) {
                        when (name) {
                            "#id" -> if (len >= 4) id = u32(data, p + 5)
                            "vertices" -> verts = data.copyOfRange(p + 5, p + 5 + len)
                            "indices" -> idx = data.copyOfRange(p + 5, p + 5 + len)
                        }
                    }
                    p += 5 + len
                }
            }
            if (pairsOk && verts != null && idx != null && id >= 0 && vc > 0) {
                groups.add(ScgGroup(id, vc, ic, ifmt, vfmt, verts, idx))
            }
            if (!pairsOk) { pos++; continue } // 未对齐：滑动窗口重新找记录头
            pos = p
        }
        return groups
    }

    // ---------- SC2 配件名 ----------

    /** 在 sc2 字节流中找字符串表（首个 KA\x02\x00 记录） */
    private fun parseStringTable(sc2: ByteArray): List<String>? {
        for (i in 0x10 until minOf(0x1000, sc2.size - 8)) {
            if (!matches(sc2, i, "KA\u0002\u0000")) continue
            val cnt = u32(sc2, i + 4).toInt()
            if (cnt <= 0 || cnt >= 5000) continue
            var p = i + 8
            val out = ArrayList<String>(cnt)
            var ok = true
            for (k in 0 until cnt) {
                if (p + 2 > sc2.size) { ok = false; break }
                val len = u16(sc2, p); p += 2
                if (len > 200 || p + len > sc2.size) { ok = false; break }
                out.add(String(sc2, p, len, Charsets.UTF_8))
                p += len
            }
            if (ok) return out
        }
        return null
    }

    /**
     * 解析 sc2，返回 组ID -> 材质名（配件名）。
     * 引用模板: [0xBDD9B834][0x0A][u64 gid][0xCD75CF90][0x0A][u64 字符串索引]
     */
    fun parseSc2Names(sc2: ByteArray, groupIds: Set<Long>): Map<Long, String> {
        val strings = parseStringTable(sc2) ?: return emptyMap()
        val map = HashMap<Long, String>()
        var i = 4
        val n = sc2.size - 20
        while (i < n) {
            if (sc2[i] == 0x0A.toByte() &&
                u32(sc2, i + 5) == 0L &&
                u32(sc2, i + 9) == HASH_NAME_REF &&
                sc2[i + 13] == 0x0A.toByte() &&
                u32(sc2, i + 18) == 0L &&
                u32(sc2, i - 4) == HASH_GEO_REF
            ) {
                val gid = u32(sc2, i + 1)
                val strIdx = u32(sc2, i + 14).toInt()
                if (gid in groupIds && strIdx in strings.indices && gid !in map) {
                    map[gid] = strings[strIdx]
                }
            }
            i++
        }
        return map
    }

    // ---------- LOD 分级 ----------

    /**
     * 按包围盒聚类并分级：同一（量化 0.1m）包围盒内，顶点数降序 → LOD 0,1,2...
     * 相同顶点数 = 同级（不同配件并排，例如 4 门炮）。
     */
    fun assignLod(groups: List<ScgGroup>) {
        val clusters = HashMap<String, MutableList<ScgGroup>>()
        for (g in groups) {
            g.bbox = computeBbox(g)
            val key = quantKey(g.bbox!!)
            clusters.getOrPut(key) { ArrayList() }.add(g)
        }
        for (list in clusters.values) {
            val counts = list.map { it.vertexCount }.distinct().sortedDescending()
            for (g in list) g.lod = counts.indexOf(g.vertexCount)
        }
    }

    private fun computeBbox(g: ScgGroup): FloatArray {
        val stride = g.stride
        val out = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        if (stride < 12) return out
        val v = g.vertices
        for (i in 0 until g.vertexCount) {
            val o = i * stride
            for (a in 0..2) {
                val f = f32(v, o + a * 4)
                if (f < out[a]) out[a] = f
                if (f > out[a + 3]) out[a + 3] = f
            }
        }
        return out
    }

    private fun quantKey(b: FloatArray): String {
        val sb = StringBuilder(48)
        for (i in 0..2) {
            if (i > 0) sb.append(';')
            sb.append("%.1f".format(b[i])).append(',').append("%.1f".format(b[i + 3]))
        }
        return sb.toString()
    }

    /** 真实配件名白名单（chassis_/gun_/hull/炮塔/履带/车轮/皮肤/活动装饰/挂点…） */
    private val GOOD_NAME = Regex(
        "^(chassis[_.-]?|gun[_.-]?|turret|hull|track|wheel|skin|decal|ny\\d|hw\\d|pumpkin|ladder|" +
        "cinema|blitz_logo|slottype|hp_|g0?\\d_|.*_mtr$|.*_mask$)"
    )

    /**
     * 统一配件命名（assignLod 之后调用，依赖 bbox）。
     *
     * sc2 名字映射实测不可靠：cd75 指向的字符串有约一半是引擎字典词/自动名，
     * 且真名与网格的对应关系存在错位（gun_05 实为车体上装甲、轮子挂 turret 名）。
     * 因此命名优先级：
     *   1. 几何强特征（车轮/炮管/阴影体/侧裙/车体/上层结构/炮塔）——唯一可信的真相；
     *      其中车轮若 sc2 名含 "wheel" 则沿用（Tiger 实测正确，268 全是 Instance 垃圾则自动编号）；
     *   2. 皮肤/装饰类 sc2 名（skin/ny/hw/decal/cinema…）——装饰件无歧义，直接信；
     *   3. 白名单 sc2 名（chassis_/gun_/hull/HP_ 等）；
     *   4. 都没有 = null（UI 显示 #ID）。
     * 不做同名簇继承——它会放大错乱（轮子继承炮塔名正是用户报告的 bug）。
     *
     * 名字后缀 "?" = 纯几何猜测（wheel_L1?/hull?/side_L?/gun_mask?…）；不带 "?" = 可信
     * 来源（游戏 sc2 原名 / 官方碰撞盒匹配出的 gun_XX、turret_XX / 盒包含判定的 hull、chassis）。
     *
     * 官方参数强化（tank != null 时）：用游戏自带的零件碰撞盒（Parameters YAML，运行时
     * 从已安装游戏读取）+ XML gunPosition 把 gun_barrel?/turret? 升级为该车真实零件名
     * （gun_01/turret_01…），无名网格按盒包含兜底（hull/chassis/gun_XX/gun_mask?）。
     */
    fun assignNames(groups: List<ScgGroup>, tank: TankParams.Tank? = null) {
        if (groups.isEmpty()) return
        var halfW = 0f; var len = 0f
        for (g in groups) {
            val b = g.bbox ?: continue
            halfW = maxOf(halfW, kotlin.math.abs(b[0]), kotlin.math.abs(b[3]))
            len = maxOf(len, b[4] - b[1])
        }
        val wheels = ArrayList<ScgGroup>()
        for (g in groups) {
            // 垃圾名过滤：纹理路径/配置引用/## 字段一律不用（实测 cd75 会给出这类值）
            val sc2Raw = g.sc2Name
            val sc2 = if (sc2Raw == null || sc2Raw.contains('/') || sc2Raw.contains(".tex") ||
                sc2Raw.contains(".yaml") || sc2Raw.startsWith("#")) null else sc2Raw
            val sc2l = (sc2 ?: "").lowercase()
            // 3 顶点挂点标记：HP_* 等白名单名保留，其余留空
            if (g.vertexCount <= 3) {
                if (sc2 != null && GOOD_NAME.containsMatchIn(sc2l)) g.name = sc2
                continue
            }
            val b = g.bbox ?: continue
            if (halfW <= 0f || len <= 0f) {
                if (sc2 != null && GOOD_NAME.containsMatchIn(sc2l)) g.name = sc2
                continue
            }
            val sx = b[3] - b[0]; val sy = b[4] - b[1]; val sz = b[5] - b[2]
            val cx = (b[0] + b[3]) / 2; val cy = (b[1] + b[4]) / 2; val cz = (b[2] + b[5]) / 2
            val cosmetic = sc2 != null && (sc2l.contains("skin") || sc2l.contains("decal") ||
                sc2l.contains("pumpkin") || sc2l.contains("ladder") || sc2l.contains("cinema") ||
                sc2l.contains("_ny") || sc2l.startsWith("ny") ||
                sc2l.contains("_hw") || sc2l.startsWith("hw"))
            when {
                // 车轮: 小盒贴车侧（放宽到 1.2m 容纳大负重轮）
                maxOf(sx, sy, sz) <= 1.2f && sz <= 1.4f && kotlin.math.abs(cx) > halfW * 0.35f -> {
                    if (sc2 != null && sc2l.contains("wheel")) g.name = sc2 else wheels.add(g)
                }
                // 细长 + 中线 + 炮口探出车头 = 炮管（中线/探出条件排除侧裙杆、尾部格栅）。
                // 必须先于皮肤名判定：实测 268 三根炮管 LOD 的 sc2 名是 Skin_02_hull，
                // 走皮肤分支会全叫成 _hull
                sz <= 0.6f && sy >= 2f && kotlin.math.abs(cx) <= maxOf(0.6f, halfW * 0.4f) &&
                    b[4] >= maxOf(2.2f, len * 0.35f) -> g.name = "gun_barrel?"
                cosmetic -> g.name = sc2                                   // 皮肤/装饰件
                // 挡泥板: 小盒 + 车头/车尾极端位置 + 低位（此前这类网格全落空成 #ID）
                maxOf(sx, sy, sz) <= 2.2f && kotlin.math.abs(cy) >= len * 0.35f && cz <= 1.4f ->
                    g.name = if (cx < 0) "fender_L?" else "fender_R?"
                b[2] < -1.2f -> g.name = "shadow?"                         // 延伸到地下 = 阴影体
                sx <= halfW * 0.9f && sy >= len * 0.5f && kotlin.math.abs(cx) > halfW * 0.35f ->
                    g.name = if (cx < 0) "side_L?" else "side_R?"           // 薄长贴侧 = 侧裙/履带
                sy >= len * 0.7f && sz >= 1.5f && cz < 1.7f -> g.name = "hull?"      // 覆盖整车大盒
                sx >= 2f && sz >= 1.4f && maxOf(sx, sy) <= 5f && minOf(sx, sy) >= 2f && cz < 1.7f ->
                    g.name = "hull_top?"                                  // 方形大中盒 = 上层结构
                // 高处中型盒；sc2 名是垃圾时放宽高度上限（KV-5 这类高盒炮塔 sz 可达 2.6）
                cz >= 1.7f && sy in 1.5f..4f && (sz <= 1.5f || (sc2 == null && sz <= 2.6f)) ->
                    g.name = "turret?"
                else -> if (sc2 != null && GOOD_NAME.containsMatchIn(sc2l)) g.name = sc2
            }
        }
        // 车轮自动编号：按侧分组，前后方向排序（几何猜测，带 ? 标记）
        fun num(list: List<ScgGroup>, tag: String) {
            list.sortedBy { (it.bbox!![1] + it.bbox!![4]) / 2 }.forEachIndexed { i, g ->
                g.name = "wheel_" + tag + (i + 1) + "?"
            }
        }
        num(wheels.filter { (it.bbox!![0] + it.bbox!![3]) / 2 < 0 }, "L")
        num(wheels.filter { (it.bbox!![0] + it.bbox!![3]) / 2 >= 0 }, "R")

        // 官方参数区域匹配（存在时）
        if (tank != null) officialNames(groups, tank)
    }

    /** 点是否在零件盒内（带容差；y/z 加枢轴偏移，x 不加——塔位 x 恒为 0） */
    private fun insideBox(p: TankParams.Part, o: FloatArray, m: Float, x: Float, y: Float, z: Float): Boolean =
        x >= p.min[0] - m && x <= p.max[0] + m &&
        y >= p.min[1] - m + o[1] && y <= p.max[1] + m + o[1] &&
        z >= p.min[2] - m + o[2] && z <= p.max[2] + m + o[2]

    /** 网格整盒落在零件盒内（带容差）——实测最稳的炮匹配一级判据 */
    private fun boxInBox(p: TankParams.Part, o: FloatArray, b: FloatArray, m: Float): Boolean =
        b[0] >= p.min[0] - m && b[3] <= p.max[0] + m &&
        b[1] >= p.min[1] - m + o[1] && b[4] <= p.max[1] + m + o[1] &&
        b[2] >= p.min[2] - m + o[2] && b[5] <= p.max[2] + m + o[2]

    /**
     * 官方参数命名（5 车实测验证版：Object268/T-54/KV-5/E-75/JagdTiger）：
     *  - 炮匹配两级：一级整盒包含（碰撞盒套住网格）——最强判据；二级炮口最近——
     *    炮的碰撞盒比可见炮管短（薄细部件不做命中体），长炮管须放行。
     *  - 炮盾(mask)：小盒位于炮枢轴（炮塔位 + XML gunPosition）前方 0~1.8m。
     *  - 无名网格兜底顺序：炮盒包含 -> 炮塔盒 -> 车体盒 -> 底盘盒 -> 炮盾。
     *  - 挂点标记(<=3 顶点)不参与；匹配成功 = 官方名（不带 ?），失败保留几何名（带 ?）。
     */
    private fun officialNames(groups: List<ScgGroup>, tank: TankParams.Tank) {
        val guns = tank.parts.keys.filter { it.startsWith("gun") && !it.contains("mask") }
            .mapNotNull { k -> tank.parts[k]?.let { k to it } }
        val turrets = tank.parts.keys.filter { it.startsWith("turret") }
            .mapNotNull { k -> tank.parts[k]?.let { k to it } }
        val origins = if (tank.turretPos.isEmpty()) listOf(FloatArray(3)) else tank.turretPos
        val gPos = if (tank.gunPos.isEmpty()) listOf(FloatArray(3)) else tank.gunPos
        val hull = tank.parts["hull"]
        val chassis = tank.parts["chassis"]

        /** 炮匹配：一级整盒包含；二级（allowStage2，供几何炮管）炮口最近 */
        fun gunMatch(b: FloatArray, allowStage2: Boolean): String? {
            var best: String? = null; var bestTip = Float.MAX_VALUE
            for ((name, part) in guns) for (o in origins) {
                if (!boxInBox(part, o, b, 0.35f)) continue
                val tip = abs(part.max[1] + o[1] - b[4])
                if (tip < bestTip) { bestTip = tip; best = name }
            }
            if (best != null || !allowStage2) return best
            for ((name, part) in guns) for (o in origins) {
                if (b[1] < part.min[1] + o[1] - 0.5f || b[1] > part.max[1] + o[1] + 0.5f) continue
                val cx = (b[0] + b[3]) / 2; val cz = (b[2] + b[5]) / 2
                if (abs(cx - o[0]) > 0.6f) continue
                if (cz < part.min[2] + o[2] - 0.9f || cz > part.max[2] + o[2] + 0.9f) continue
                val tip = abs(part.max[1] + o[1] - b[4])
                if (tip < bestTip) { bestTip = tip; best = name }
            }
            return best
        }

        /** 小盒形件位于炮枢轴前方 = 炮盾(mask) */
        fun gunMask(b: FloatArray): Boolean {
            val cx = (b[0] + b[3]) / 2; val cy = (b[1] + b[4]) / 2; val cz = (b[2] + b[5]) / 2
            if (b[5] - b[2] > 1.0f || b[4] - b[1] > 1.8f) return false
            for (gp in gPos) for (o in origins) {
                val px = o[0] + gp[0]; val py = o[1] + gp[1]; val pz = o[2] + gp[2]
                if (abs(cx - px) > 0.7f) continue
                if (cy < py - 0.5f || cy > py + 1.8f) continue
                if (abs(cz - pz) > 0.9f) continue
                return true
            }
            return false
        }

        /** 炮塔匹配：中心入盒；退化盒（无 z 范围）按离塔位距离 */
        fun turretMatch(b: FloatArray): String? {
            val cx = (b[0] + b[3]) / 2; val cy = (b[1] + b[4]) / 2; val cz = (b[2] + b[5]) / 2
            for ((name, part) in turrets) {
                if (part.max[0] - part.min[0] <= 0f && part.max[2] - part.min[2] <= 0f) continue
                if (insideBox(part, FloatArray(3), 0.3f, cx, cy, cz)) return name
            }
            for ((name, part) in turrets) for (o in origins) {
                val d = maxOf(abs(cx - o[0]), abs(cy - o[1]), abs(cz - o[2]))
                if (d < 1.6f && part.max[2] - part.min[2] <= 0f) return name
            }
            return null
        }

        for (g in groups) {
            if (g.vertexCount <= 3) continue            // 挂点标记不动
            val b = g.bbox ?: continue
            when (g.name) {
                "gun_barrel?" -> g.name = gunMatch(b, true) ?: "gun_barrel?"
                "turret?" -> g.name = turretMatch(b) ?: "turret?"
                null -> {
                    // 严格优先: 炮盒包含 -> 炮塔盒 -> 车体盒 -> 底盘盒 -> 炮位(炮盾)
                    val cx = (b[0] + b[3]) / 2; val cy = (b[1] + b[4]) / 2; val cz = (b[2] + b[5]) / 2
                    val gun = gunMatch(b, false)
                    if (gun != null) { g.name = gun; continue }
                    val t = turretMatch(b)
                    if (t != null) { g.name = t; continue }
                    if (hull != null && insideBox(hull, FloatArray(3), 0.3f, cx, cy, cz)) {
                        g.name = "hull"; continue
                    }
                    if (chassis != null && insideBox(chassis, FloatArray(3), 0.3f, cx, cy, cz)) {
                        g.name = "chassis"; continue
                    }
                    if (gunMask(b)) g.name = "gun_mask?"
                }
            }
        }
    }

    // ---------- OBJ 导出 ----------

    /**
     * 导出 OBJ 文本。
     * @param selectedIds 选中的组 ID 集合（部件选择在 UI 层完成，与 LOD 预设解耦）
     * @return OBJ 文本 + 统计
     */
    fun writeObj(groups: List<ScgGroup>, selectedIds: Set<Long>): Result {
        val kept = groups.filter { it.id in selectedIds && it.stride >= 12 }
        val sb = StringBuilder(1 shl 20)
        sb.append("# WoT Blitz SCG -> OBJ (DvplModHelper)\n")
        sb.append("# groups: ").append(groups.size).append(" -> kept ").append(kept.size).append(" (selected)\n")
        var vOff = 1
        val seenNames = HashSet<String>()
        for (g in kept) {
            var nm = ((g.name ?: ("g" + g.id)).replace(Regex("[^A-Za-z0-9_.?]"), "_")) + "_lod" + g.lod
            if (!seenNames.add(nm)) {          // 同名去重: hull_lod0 出现多次时加序号
                var k = 2
                while (!seenNames.add(nm.substringBeforeLast('_') + "_" + k + "_lod" + g.lod)) k++
                nm = nm.substringBeforeLast('_') + "_" + k + "_lod" + g.lod
            }
            sb.append("o ").append(nm).append('\n')
            val stride = g.stride
            val v = g.vertices
            for (i in 0 until g.vertexCount) {
                val o = i * stride
                // 坐标系: 游戏 Z 朝上(Y=车长, Z=高度) -> OBJ Y 朝上: (x,y,z) -> (x,z,-y)
                sb.append("v ").append("%.5f".format(f32(v, o)))
                    .append(' ').append("%.5f".format(f32(v, o + 8)))
                    .append(' ').append("%.5f".format(-f32(v, o + 4)))
                    .append('\n')
            }
            // 贴图坐标（textureCoordCount=1，每个顶点一份）；未知格式自动探测
            val uv = uvLayoutFor(g)
            if (uv != null) {
                for (i in 0 until g.vertexCount) {
                    val o = i * stride + uv.first
                    val u = if (uv.second) half(v, o) else f32(v, o)
                    // 游戏 V 轴向下(原点左上), OBJ 惯例向上(左下): 翻转 V
                    val w = if (uv.second) 1.0f - half(v, o + 2) else 1.0f - f32(v, o + 4)
                    sb.append("vt ").append("%.5f".format(u)).append(' ').append("%.5f".format(w)).append('\n')
                }
            }
            val idx = g.indices
            if (g.indexFormat == 0) {
                for (t in 0 until g.indexCount / 3) {
                    val o = t * 6
                    sb.append("f ").append(faceCorner(u16(idx, o), vOff, uv))
                        .append(' ').append(faceCorner(u16(idx, o + 2), vOff, uv))
                        .append(' ').append(faceCorner(u16(idx, o + 4), vOff, uv))
                        .append('\n')
                }
            } else {
                for (t in 0 until g.indexCount / 3) {
                    val o = t * 12
                    sb.append("f ").append(faceCorner(u32(idx, o).toInt(), vOff, uv))
                        .append(' ').append(faceCorner(u32(idx, o + 4).toInt(), vOff, uv))
                        .append(' ').append(faceCorner(u32(idx, o + 8).toInt(), vOff, uv))
                        .append('\n')
                }
            }
            vOff += g.vertexCount
        }
        return Result(sb.toString(), groups.size, kept.size, kept.sumOf { it.vertexCount.toLong() })
    }

    /** 面角点: 有 UV 时输出 "v/vt"（两者都逐顶点 1:1，索引相同），否则仅 "v" */
    private fun faceCorner(vertexIdx: Int, vOff: Int, uv: Pair<Int, Boolean>?): String =
        if (uv != null) (vertexIdx + vOff).toString() + "/" + (vertexIdx + vOff) else (vertexIdx + vOff).toString()

    class Result(val objText: String, val totalGroups: Int, val keptGroups: Int, val keptVertices: Long)

    // ---------- 输入预处理 ----------

    /** 若字节流是 DVPL 容器则解包，否则原样返回 */
    fun unwrapDvpl(data: ByteArray): ByteArray {
        if (data.size >= 20) {
            val foot = data.copyOfRange(data.size - 20, data.size)
            if (String(foot, 16, 4, Charsets.US_ASCII) == "DVPL") {
                return DvplCodec.decode(data)
            }
        }
        return data
    }
}
