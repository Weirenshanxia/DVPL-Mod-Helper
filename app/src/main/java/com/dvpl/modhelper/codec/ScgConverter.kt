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
 * - 顶点: 位置恒为 float3 @ +0/+4/+8（米制，Z 向上），法线 @ +12..+20；步长由 vertexFormat 决定。
 *   未知格式回退 stride = size / vertexCount
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
     * - 395/411/507: uv0@+24; 399/415: uv0@+28（+24 是 4B 顶点色槽, 误读会得到假 UV）
     * - 实测采样验证（采样值域+变化数判定）:
     * - 布局A: pos@+0(12B) normal@+12(12B) uv float2@+24 —— 11/395/411/507/907
     * - 布局B: pos@+0(12B) normal@+12(12B) uv float2@+28 —— 399/415/911/511/1023/495xx
     *   (399 修正: 原 @24 是 RGBA8 标记槽 99% 全 0, 真 uv0 在 @28——31 车逐顶点 cross/tex 相关性实测)
     * - 3 = 16B: pos@+0 + f32@+12（标记组, 无 UV）。
     * 注意: 皮肤件 UV 常平铺到 0..2 (u 最大到 1.995)，属正常现象。
     */
    private val UV_LAYOUT = mapOf(
        11 to Pair(24, false),
        395 to Pair(24, false),
        399 to Pair(28, false),   // uv0 @28
        411 to Pair(24, false),   // uv1@32 (tex=2)
        507 to Pair(24, false),   // uv1@32 uv2@40 uv3@48 (tex=4)
        511 to Pair(28, false),
        907 to Pair(24, false),   // 395 + 0x200 (硬蒙皮关节槽 4B), stride 60, UV 同 395 @+24 float
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
        // vfmt=3 的 24B 形态: pos(12) + normal f32×3(12), 无 UV 字节（实测尾三字恒为单位向量）
        if (g.vertexFormat == 3 && g.stride == 24) return null
        // 挂点标记（3 顶点小三角, 同 scgCategory 判定）无 UV 通道, 数据处是杂散字节
        if (g.vertexCount <= 3) return null
        // 元数据记录（贴图路径/节点名）顶点位置是 ASCII 字节误读, 量级 ±1e38, 过滤掉
        if (!posSane(g)) return null
        val tab = UV_LAYOUT[g.vertexFormat]
        if (tab != null) return if (uvSane(g, tab)) tab else detectUvLayout(g)
        val cached = uvAutoDetected[g.vertexFormat]
        if (cached != null) {
            if (cached === NO_UV) return null
            val c = cached as Pair<Int, Boolean>
            return if (uvSane(g, c)) c else detectUvLayout(g)
        }
        val r = detectUvLayout(g)
        uvAutoDetected[g.vertexFormat] = r ?: NO_UV
        return r
    }

    /**
     * 表/缓存布局逐组垃圾校验: >=95% 采样 UV 有限且 |u|,|v| <= 32。
     * 判"垃圾"用量级而非 0..2 值域: 正常 UV 通道可能合法平铺超出 2（皮肤件）,
     * 而打包 tangent 等字节误读成 float 是 ±1e38 量级, 量级过滤可精确区分两者。
     * 校验不过 → 逐组探测兜底（探测按 0..2 值域找真 UV 通道）。
     */
    private fun uvSane(g: ScgGroup, uv: Pair<Int, Boolean>): Boolean {
        val stride = g.stride
        if (stride < 16 || g.vertexCount < 4) return true
        val v = g.vertices
        val need = if (uv.second) 4 else 8
        if (uv.first + need > stride) return false
        val n = minOf(g.vertexCount, 64)
        var ok = 0
        for (i in 0 until n) {
            val base = i * stride + uv.first
            if (base + need > v.size) return false
            val u = if (uv.second) half(v, base) else f32(v, base)
            val w = if (uv.second) half(v, base + 2) else f32(v, base + 4)
            if (u.isFinite() && w.isFinite() && kotlin.math.abs(u) <= 32f &&
                kotlin.math.abs(w) <= 32f) ok++
        }
        return ok >= n * 0.95f
    }    /**
     * 检查采样 UV 是否是 ±1e20 级垃圾（贴图路径/节点名字符串误读成 float）。
     * 仅在 UV_LAYOUT 有条目时调用。真实几何的 UV 极少超出 ±10000，垃圾值是 ±1e38。
     */
    private fun uvGarbage(g: ScgGroup, uv: Pair<Int, Boolean>): Boolean {
        val stride = g.stride
        val need = if (uv.second) 4 else 8
        if (uv.first + need > stride || g.vertexCount < 4 || stride < 16) return false
        val v = g.vertices
        val n = minOf(g.vertexCount, 64)
        var bad = 0
        for (i in 0 until n) {
            val base = i * stride + uv.first
            if (base + need > v.size) return false
            val u = if (uv.second) half(v, base) else f32(v, base)
            val w = if (uv.second) half(v, base + 2) else f32(v, base + 4)
            if (!u.isFinite() || !w.isFinite() || kotlin.math.abs(u) > 1e20f || kotlin.math.abs(w) > 1e20f) bad++
        }
        return bad >= n / 2
    }

    /**
     * 该 record 是否为场景元数据（贴图路径/节点名等字节被误当几何体）而非真实几何。
     * 判据: 格式表声明应有 UV 却采样到 ±1e20 级垃圾 UV。
     */
    internal fun isMetaRecord(g: ScgGroup): Boolean {
        if (g.vertexCount <= 3) return false
        val tab = UV_LAYOUT[g.vertexFormat] ?: return false
        if (!posSane(g)) return true
        return uvGarbage(g, tab)
    }



    /**
     * 顶点位置合理性校验: 坐克范围 ±1e5m（游戏世界尺度）。
     * SCG 里混有场景元数据记录（贴图路径、节点名等），其"顶点"字节是 ASCII 字符串误读，
     * 读成 float 是 ±1e38 量级，与真实坐标 ±100m 相差 6 个数量级，一次采样就能区分。
     */
    private fun posSane(g: ScgGroup): Boolean {
        val stride = g.stride
        if (stride < 12 || g.vertexCount < 1) return true
        val v = g.vertices
        val n = minOf(g.vertexCount, 8)
        for (i in 0 until n) {
            val base = i * stride
            if (base + 12 > v.size) return false
            val x = f32(v, base); val y = f32(v, base + 4); val z = f32(v, base + 8)
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() ||
                kotlin.math.abs(x) > 1e5f || kotlin.math.abs(y) > 1e5f || kotlin.math.abs(z) > 1e5f)
                return false
        }
        return true
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

    /** sc2 数据源属性标识（引擎属性字典 ID，逆向确认跨车一致；值 = SCG gid） */
    private const val HASH_GEO_REF = 0xBDD9B834L

    class ScgGroup(
        var id: Long,
        val vertexCount: Int,
        val indexCount: Int,
        val indexFormat: Int,
        val vertexFormat: Int,
        val vertices: ByteArray,
        val indices: ByteArray
    ) {
        // 注: vfmt=3 实测存在 16B 与 24B 两种形态（Werewolf 为 24B=pos+normal f32×3）,
        // stride 取值以数据自校验为准, 见下方 stride 的实现。
        /** sc2 解析出的材质名（原始映射，未经几何校验，不可直接显示） */
        var sc2Name: String? = null

        /** 最终显示/导出名（assignNames() 决定：几何分类 > 皮肤/装饰名 > 白名单名） */
        var name: String? = null

        /** LOD 等级：0 = 最高细节 */
        var lod = 0

        /** sc2 组件周期号（parseSc2Info 填充，-1 = 无 sc2/未匹配）。同周期网格 = 同部件多级 LOD */
        var family: Int = -1

        /** 包围盒 [mnX,mnY,mnZ,mxX,mxY,mxZ]，assignLod() 时计算 */
        var bbox: FloatArray? = null

        /**
         * 顶点步长（字节）。
         * 表值仅作首选；若顶点区能整除出不同的步长（12..128 字节），以实测数据为准——
         * Werewolf 的 vfmt=3 实测 24B（pos12+normal12）而表记 16B，按表读会把法线/
         * UV 位当位置读，部件渲染成一坨。此规则同时兼容表值正确的其它格式。
         */
        val stride: Int
            get() {
                val t = STRIDES[vertexFormat]
                if (vertexCount > 0) {
                    val q = vertices.size / vertexCount
                    if (vertices.size % vertexCount == 0 && q in 12..128 && q != t) return q
                }
                return t ?: if (vertexCount > 0) vertices.size / vertexCount else 0
            }
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

    // ---------- SC2 信息（词汇表 + LOD 家族） ----------

    /** 真实配件名前缀：hull / turret_01 / gun_01 / chassis_*** */
    private val VOCAB_NAME = Regex("^(hull|turret_\\d+|gun_\\d+|chassis_)")

    /**
     * sc2 解析结果。
     * 注意: 周期名 familyName 与网格的对应被游戏导出器打乱（字母序名字 zip 场景序网格），
     * 不可当部件名用——只用于挂点 HP_* 兜底。
     */
    class Sc2Info(val vocab: Set<String>, private val famOf: Map<Long, Int>, val familyName: Map<Int, String?>) {
        fun familyOf(gid: Long): Int = famOf[gid] ?: -1
        val hasFamilies: Boolean get() = famOf.isNotEmpty()
    }

    /** 在 sc2 中定位字符串表（"##name" 项起的 [u16 len][utf8] 序列），失败返回空表 */
    private fun parseSc2Strings(sc2: ByteArray): List<String> {
        for (j in 0 until sc2.size - 8) {
            if (sc2[j] == 0x06.toByte() && sc2[j + 1] == 0x00.toByte() &&
                sc2[j + 2] == 0x23.toByte() && sc2[j + 3] == 0x23.toByte() &&
                sc2[j + 4] == 0x6E.toByte() && sc2[j + 5] == 0x61.toByte()) {
                var p = j
                val out = ArrayList<String>()
                while (p + 2 <= sc2.size) {
                    val len = u16(sc2, p)
                    if (len == 0 || p + 2 + len > sc2.size) break
                    out.add(String(sc2, p + 2, len, Charsets.UTF_8))
                    p += 2 + len
                }
                if (out.size >= 4) return out
            }
        }
        return emptyList()
    }

    /**
     * 解析 sc2 尾部组件区（4 车 331 组实测验证）：
     *  - 周期 = 组件名属性(0xBF399D40 t7)之间的区段；周期内全部数据源(0xBDD9B834 t0A,
     *    值 = SCG gid) 属同一部件的多级 LOD（gid 引用的 AABB 与 SCG 网格包围盒全对齐）。
     *    例: T-55A 炮塔 3 周期 gid 229/230/231 = LOD0/1/2（vc 1232/673/151）。
     *  - 词汇表 = 字符串表里的 hull/turret_XX/gun_XX/chassis_*** 真名集合。
     * 周期名与网格的配对被导出器打乱（炮塔网格挂轮子名、炮管挂履带名，hull/turret/gun
     * 真名根本不在周期流里），所以周期名只用于挂点 HP_* 兜底，部件名走几何分类+词汇表。
     */
    fun parseSc2Info(sc2: ByteArray, groupIds: Set<Long>): Sc2Info {
        val strings = parseSc2Strings(sc2)
        val vocab = HashSet<String>()
        for (s in strings) if (VOCAB_NAME.containsMatchIn(s)) vocab.add(s)
        // 交错事件 [offset, kind(0=名字属性/1=数据源), value] 按偏移排序
        val events = ArrayList<LongArray>()
        var i = 0
        val n = sc2.size - 13
        while (i < n) {
            if (u32(sc2, i) == 0xBF399D40L && sc2[i + 4] == 0x07.toByte()) {
                events.add(longArrayOf(i.toLong(), 0L, u32(sc2, i + 5)))
            } else if (u32(sc2, i) == HASH_GEO_REF && sc2[i + 4] == 0x0A.toByte()) {
                var v = 0L
                for (k in 0 until 8) v = v or ((sc2[i + 5 + k].toLong() and 0xFF) shl (8 * k))
                events.add(longArrayOf(i.toLong(), 1L, v))
            }
            i++
        }
        events.sortBy { it[0] }
        val famOf = HashMap<Long, Int>()
        val familyName = HashMap<Int, String?>()
        var fam = -1
        for (e in events) {
            if (e[1] == 0L) {
                fam++
                val idx = e[2].toInt()
                familyName[fam] = if (idx in strings.indices) strings[idx] else null
            } else if (fam >= 0 && e[2] in groupIds && e[2] !in famOf) {
                famOf[e[2]] = fam
            }
        }
        return Sc2Info(vocab, famOf, familyName)
    }

    // ---------- LOD 分级 ----------

    /** 部件家族（同一部件的全部 LOD 级网格）。cycleName = sc2 周期名（被打乱，仅兜底用）。 */
    class Fam(val list: MutableList<ScgGroup>) {
        var cycleName: String? = null
    }

    /**
     * LOD 分级（家族内顶点数降序 → LOD 0,1,2...；相同顶点数 = 同级）。
     * 家族来源两级：sc2 组件周期（权威——同周期网格 = 同部件多级 LOD，
     * T-55A 炮塔 3 gid 229/230/231 即 LOD0/1/2）；无 sc2 时 AABB 相似聚类兜底。
     */
    fun assignLod(groups: List<ScgGroup>) {
        for (g in groups) g.bbox = computeBbox(g)
        for (fam in buildFamilies(groups)) rankLod(fam.list)
    }

    private fun rankLod(list: List<ScgGroup>) {
        val counts = list.map { it.vertexCount }.distinct().sortedDescending()
        for (g in list) g.lod = counts.indexOf(g.vertexCount)
    }

    /** sc2 family 字段优先分组；缺 family 的组按 AABB 相似度聚类兜底 */
    private fun buildFamilies(groups: List<ScgGroup>): List<Fam> {
        val out = ArrayList<Fam>()
        val byFam = HashMap<Int, Fam>()
        val noFam = ArrayList<ScgGroup>()
        for (g in groups) {
            if (g.family >= 0) {
                val f = byFam.getOrPut(g.family) { Fam(ArrayList()).also { out.add(it) } }
                f.list.add(g)
                if (f.cycleName == null) f.cycleName = g.sc2Name
            } else noFam.add(g)
        }
        if (noFam.isNotEmpty()) for (cluster in similarityClusters(noFam)) out.add(Fam(cluster))
        return out
    }

    /** AABB 相似聚类: 中心距 < 0.3m 且每维尺寸比 >= 0.95 视为同部件的不同 LOD */
    private fun similarityClusters(list: List<ScgGroup>): List<MutableList<ScgGroup>> {
        val fams = ArrayList<MutableList<ScgGroup>>()
        outer@ for (g in list) {
            val db = g.bbox
            if (db == null) { fams.add(arrayListOf(g)); continue }
            for (f in fams) {
                val rb = f.first().bbox ?: continue
                val dx = (db[0] + db[3] - rb[0] - rb[3]) / 2f
                val dy = (db[1] + db[4] - rb[1] - rb[4]) / 2f
                val dz = (db[2] + db[5] - rb[2] - rb[5]) / 2f
                if (dx * dx + dy * dy + dz * dz > 0.09f) continue
                var sim = true
                for (a in 0..2) {
                    val s1 = db[a + 3] - db[a]; val s2 = rb[a + 3] - rb[a]
                    if (s1 < 1e-4f || s2 < 1e-4f || minOf(s1, s2) / maxOf(s1, s2) < 0.95f) {
                        sim = false; break
                    }
                }
                if (sim) { f.add(g); continue@outer }
            }
            fams.add(arrayListOf(g))
        }
        return fams
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

    /** 真实配件名白名单（chassis_/gun_/hull/炮塔/履带/车轮/皮肤/活动装饰/挂点…） */
    private val GOOD_NAME = Regex(
        "^(chassis[_.-]?|gun[_.-]?|turret|hull|track|wheel|skin|decal|ny\\d|hw\\d|pumpkin|ladder|" +
        "cinema|blitz_logo|slottype|hp_|g0?\\d_|.*_mtr$|.*_mask$)"
    )

    /**
     * 统一配件命名（assignLod 之后调用，依赖 bbox/family）。
     *
     * sc2 尾区实测（4 车 331 组验证）：组件名流按字母序、网格流按场景序，导出器把两者
     * 按位置 zip 进同一条记录——周期名与网格完全错位（炮塔网格挂轮子名、炮管挂履带名），
     * hull/turret/gun 真名根本不在周期流里。因此坦克模型（词汇表含 >=2 个轮名）改用
     * 家族级几何分类 + 词汇表计数匹配：
     *   hull = 最大居中族（离群特效盒先行剔除）；turret = 高处居中族（vc 降序 → turret_01..）；
     *   gun = 细长居中族（vc 降序 → gun_01..）；wheel = 贴侧小盒（cy 序 → chassis_wheel_L_01..）；
     *   side = 贴侧长族（高履带凭 UV 平铺放行），履带/坠毁/侧板按 UV 平铺 + 网格复杂度区分:
     *   履带纹理反复平铺（跨度 >= 2.5）或无 UV 的长盒 = 履带池；坠毁履带是断成碎块的
     *   网格——同尺寸下顶点/连通碎块远多于正常履带（Object777 实测: 坠毁 294 顶点/167
     *   碎块 vs 正常 136/10，用户确认），故坠毁 = 池中顶点最多者，正常履带 = 其余较简单者;
     *   有 UV 但未平铺的长盒 = 侧板；词汇表轮数缺口 → 合并轮组吸收。
     * 每类先查字符串表真名词汇表——命中 = 游戏原名（无 ?），未命中 = 几何名（带 ?）。
     * 挂点标记只信周期名里的 HP_*；其余周期名一律不信（已被打乱）。
     * 非坦克文件（皮肤/装饰/词汇表缺失）走 assignNamesLegacy 逐组启发式。
     *
     * 官方参数强化（tank != null）：officialNames 把 null 名升级为官方碰撞盒匹配名。
     */
    fun assignNames(groups: List<ScgGroup>, tank: TankParams.Tank? = null, vocab: Set<String> = emptySet()) {
        if (groups.isEmpty()) return
        val tankLike = vocab.count { it.startsWith("chassis_wheel_") } >= 2
        if (!tankLike) {
            assignNamesLegacy(groups)
            if (tank != null) officialNames(groups, tank)
            return
        }
        for (g in groups) if (g.bbox == null) g.bbox = computeBbox(g)
        var halfW = 0.01f; var len = 0.01f
        for (g in groups) {
            val b = g.bbox ?: continue
            val gx = b[3] - b[0]; val gy = b[4] - b[1]; val gz = b[5] - b[2]
            // 离群防护: 粒子/特效垃圾盒（M4A3E8_BP 雪花粒子 275m、Lightbringer 光环 15.7m 方形）
            // 不参与车体尺寸估计——否则 halfW/len 被撑爆导致侧族/炮塔分类全灭
            if (maxOf(gx, gy, gz) > 25f) continue
            if (gx > 8f && gx > gy * 0.9f) continue
            halfW = maxOf(halfW, abs(b[0]), abs(b[3]))
            len = maxOf(len, gy)
        }
        val fams = buildFamilies(groups)
        // 每族几何量（用 LOD0 = 顶点数最多的成员代表）
        class P(val fam: Fam, val rep: ScgGroup) {
            val b = rep.bbox!!
            val sx = b[3] - b[0]; val sy = b[4] - b[1]; val sz = b[5] - b[2]
            val cx = (b[0] + b[3]) / 2f; val cy = (b[1] + b[4]) / 2f; val cz = (b[2] + b[5]) / 2f
            val vol = sx * sy * sz
            val mid = floatArrayOf(sx, sy, sz).sorted()[1]
            val zMin = b[2]
            /** LOD0 网格 UV 平铺跨度（履带纹理 >= 2.5；无 UV = null） */
            val tiles = familyTiles(rep)
            val sane = maxOf(sx, sy, sz) <= 25f && !(sx > 8f && sx > sy * 0.9f)
        }
        val parts = ArrayList<P>()
        var hull: P? = null
        for (fam in fams) {
            val rep = fam.list.maxByOrNull { it.vertexCount } ?: continue
            val p = P(fam, rep)
            parts.add(p)
            if (p.sane && abs(p.cx) < halfW * 0.3f && (hull == null || p.vol > hull!!.vol)) hull = p
        }
        val wheelsL = ArrayList<P>(); val wheelsR = ArrayList<P>()
        val sidesL = ArrayList<P>(); val sidesR = ArrayList<P>()
        val guns = ArrayList<P>(); val turrets = ArrayList<P>()
        val classOf = HashMap<Fam, String>()
        for (p in parts) {
            val cls = when {
                p.rep.vertexCount <= 3 || p.mid < 0.12f -> "meta"        // 挂点标记/微型参考物
                p.zMin < -1.2f -> "shadow"                              // 延伸到地下 = 阴影体
                // 车轮: 贴侧小盒（1.35m 容纳大负重轮/主动轮；B-1bis 小轮 0.34m 也在内）
                maxOf(p.sx, p.sy, p.sz) <= 1.35f && abs(p.cx) > halfW * 0.4f &&
                    p.cz < (hull?.cz ?: 2f) + (hull?.sz ?: 0f) * 0.75f -> "wheel"
                // 炮管: 细长（中间维 <= 0.7m）+ 居中 + 高于车体中线
                p.sy >= 2f && p.mid <= 0.7f && abs(p.cx) <= maxOf(0.6f, halfW * 0.4f) &&
                    p.cz > (hull?.cz ?: 1f) -> "gun"
                // 侧族: 贴侧长盒（履带/侧裙/坠毁裙板；Werewolf 侧箱高 1.7m；
                // BDR 1.78 / Mark I 2.33 的高环绕履带凭 UV 平铺特征放行）
                p.sy >= len * 0.5f && abs(p.cx) > halfW * 0.35f &&
                    (p.sz <= 1.75f || (p.tiles ?: 0f) >= 2.5f) && p.cz <= 1.6f -> "side"
                p !== hull && abs(p.cx) < halfW * 0.3f &&
                    p.cz >= (hull?.cz ?: 1.7f) + (hull?.sz ?: 0f) * 0.45f -> "turret"
                else -> "other"
            }
            classOf[p.fam] = cls
            when (cls) {
                "wheel" -> (if (p.cx < 0) wheelsL else wheelsR).add(p)
                "side" -> (if (p.cx < 0) sidesL else sidesR).add(p)
                "gun" -> guns.add(p)
                "turret" -> turrets.add(p)
            }
        }
        // ---- 词汇表命名 ----
        val has: (String) -> Boolean = { vocab.contains(it) }
        val famName = HashMap<Fam, String?>()
        if (hull != null) famName[hull!!.fam] = if (has("hull")) "hull" else "hull?"
        turrets.sortedByDescending { it.rep.vertexCount }.forEachIndexed { i, p ->
            val key = "turret_" + "%02d".format(i + 1)
            famName[p.fam] = when {
                i == 0 && !has(key) -> "turret?"
                has(key) -> key
                else -> key + "?"
            }
        }
        guns.sortedByDescending { it.rep.vertexCount }.forEachIndexed { i, p ->
            val key = "gun_" + "%02d".format(i + 1)
            famName[p.fam] = when {
                i == 0 && !has(key) -> "gun_barrel?"
                has(key) -> key
                else -> key + "?"
            }
        }
        // ---- 合并轮组吸收: 词汇表轮数 > 实际分出的轮数 → 缺的轮子并进了一个整组
        //      （一侧全部轮子合为一组 / 前后端轮簇横跨中线两种形态），先吸收再排侧族名，
        //      否则高 vc 轮组会挤掉履带名额造成 track/chassis/crash 轮转错位 ----
        for ((s, wl, sl) in listOf(Triple("L", wheelsL, sidesL), Triple("R", wheelsR, sidesR))) {
            val vocabW = vocab.count { it.startsWith("chassis_wheel_" + s + "_") }
            if (vocabW <= wl.size || sl.isEmpty()) continue
            val maxSy = sl.maxOf { it.sy }
            val cand = parts.filter { p ->
                p.fam !in famName && p !== hull && p.sane &&
                    p.rep.vertexCount > 3 && p.sz <= 1.05f && p.cz <= 1.0f &&
                    (p.tiles == null || p.tiles!! < 2.5f) &&
                    // 长条形（一侧轮列合并）或 宽短横跨（前后端轮簇）
                    ((p.sy >= 1.2f && p.sy < maxSy * 0.85f) || (p.sx >= halfW * 0.8f && p.sy <= 1.5f)) &&
                    (if (s == "L") p.cx <= 0.3f else p.cx >= -0.3f)
            }.maxByOrNull { it.rep.vertexCount }
            if (cand != null) {
                sl.remove(cand); wl.remove(cand)
                val centered = abs(cand.cx) < halfW * 0.3f
                val nm = when {
                    wl.isEmpty() -> "chassis_wheel_" + (if (centered) "" else s + "_") + "all"
                    wl.size + 1 <= vocabW -> "chassis_wheel_" + (if (centered) "" else s + "_") + "%02d".format(wl.size + 1)
                    else -> "chassis_wheel_" + (if (centered) "" else s + "_") + "grp"
                }
                famName[cand.fam] = nm + "?"
            }
        }
        // ---- 侧族命名: 履带池 = UV 平铺族（履带纹理沿长度反复平铺，跨度 >= 2.5）+ 无 UV 长盒;
        //      侧板 = 有 UV 但未平铺的长盒。坠毁履带 = 池中顶点最多者——坠毁网格断成碎块,
        //      同尺寸下顶点/连通碎块远多于正常履带（Object777 294顶点/167碎块 vs 136/10、
        //      T-55A 261/143 vs 168/43、BZ_75 656/529 vs 180/55，连通分量并查集实测一致;
        //      Object777 与合并轮车用户实测确认）; 正常履带 = 池中其余较简单者（优先平铺族） ----
        for ((s, list) in listOf("L" to sidesL, "R" to sidesR)) {
            if (list.isEmpty()) continue
            val tiledOf = { p: P -> (p.tiles ?: 0f) >= 2.5f }
            val tiled = list.filter { tiledOf(it) }
            val plates = list.filter { it.tiles != null && !tiledOf(it) }
            val candidates = if (tiled.isNotEmpty()) list.filter { tiledOf(it) || it.tiles == null } else list.toList()
            val crash = if (candidates.size >= 2) candidates.maxByOrNull { it.rep.vertexCount } else null
            val restCand = candidates.filter { it !== crash }
            val track = restCand.filter { tiledOf(it) }.minByOrNull { it.rep.vertexCount }
                ?: restCand.minByOrNull { it.rep.vertexCount }
            val rem = list.filter { it !== track && it !== crash }
            val chassis = plates.filter { it !== track && it !== crash }.maxByOrNull { it.rep.vertexCount }
                ?: rem.maxByOrNull { it.rep.vertexCount }
            if (track != null) {
                val key = "chassis_track_" + s
                famName[track.fam] = if (has(key)) key else key + "?"
            }
            if (crash != null) {
                val key = "chassis_track_crash_" + s
                famName[crash.fam] = if (has(key)) key else key + "?"
            }
            if (chassis != null) {
                val key = "chassis_chassis_" + s
                famName[chassis.fam] = if (has(key)) key else key + "?"
            }
            rem.filter { it !== chassis }.forEachIndexed { i, p ->
                famName[p.fam] = "side_" + s + (if (i > 0) (i + 1).toString() else "") + "?"
            }
        }
        for ((s, list) in listOf("L" to wheelsL, "R" to wheelsR)) {
            list.sortedBy { it.cy }.forEachIndexed { i, p ->
                val key = "chassis_wheel_" + s + "_" + "%02d".format(i + 1)
                famName[p.fam] = if (has(key)) key else "wheel_" + s + (i + 1) + "?"
            }
        }
        // ---- 未分类族兜底 ----
        for (p in parts) {
            if (p.fam in famName) continue
            val cn = p.fam.cycleName
            famName[p.fam] = when (classOf[p.fam]) {
                "meta" -> if (cn != null && cn.uppercase().startsWith("HP_")) cn else null
                "shadow" -> "shadow?"
                "other" -> when {
                    // 挡泥板: 小盒 + 车头/车尾极端位置 + 低位
                    maxOf(p.sx, p.sy, p.sz) <= 2.2f && abs(p.cy) >= len * 0.35f && p.cz <= 1.4f ->
                        if (p.cx < 0) "fender_L?" else "fender_R?"
                    hull != null && abs(p.cx) < halfW * 0.3f && p.vol > hull!!.vol * 0.3f &&
                        p.cz < hull!!.cz + hull!!.sz * 0.45f -> "hull_top?"
                    else -> null
                }
                else -> classOf[p.fam] + "?"
            }
        }
        for ((fam, nm) in famName) for (g in fam.list) g.name = nm
        // 官方参数区域匹配（存在时）：null 名网格按官方碰撞盒兜底
        if (tank != null) officialNames(groups, tank)
    }

    /**
     * 网格 UV 平铺跨度: 履带/坠毁履带纹理沿长度方向反复平铺（实测 T-55A 11.0、BZ_75 8.3、
     * BDR 11.0-12.4 格），侧板/轮子/车体都在 1 格上下。返回 max(跨度U, 跨度V)；无 UV 返回 null。
     */
    private fun familyTiles(g: ScgGroup): Float? {
        if (g.vertexCount <= 3 || isMetaRecord(g)) return null
        val part = extractUvParts(listOf(g)).firstOrNull() ?: return null
        if (!part.hasUv || part.uv.isEmpty()) return null
        val bb = part.bbox
        return maxOf(bb[2] - bb[0], bb[3] - bb[1])
    }

    /** 旧版逐组启发式命名（皮肤/装饰/非坦克文件——坦克车型已改走家族几何分类） */
    private fun assignNamesLegacy(groups: List<ScgGroup>) {
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
        val kept = groups.filter { it.id in selectedIds && it.stride >= 12 && !isMetaRecord(it) }
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

    // ---------- UV 查看器数据提取 ----------

    /**
     * 提取各部件的 UV 三角形汤（屏幕空间，OBJ 惯例 V 向上）。
     * 游戏 V 向下（原点左上），与 writeObj 相同地翻转 v' = 1 - v，
     * 因此本应用导出的 OBJ 与 SCG 源在 UV 查看器中显示完全一致。
     * 无 UV 通道的组（vfmt=3/24B 等）→ hasUv=false，查看器灰显。
     * 注意: 皮肤件 UV 平铺到 0..2 属正常，不裁剪。
     */
    fun extractUvParts(groups: List<ScgGroup>): List<UvPart> {
        val out = ArrayList<UvPart>(groups.size)
        for (g in groups) {
            val stride = g.stride
            if (isMetaRecord(g)) continue
            val uv = uvLayoutFor(g)
            if (uv == null || stride < 12 || g.vertexCount <= 0 || g.indexCount < 3) {
                out.add(UvPart(g.name, g.lod, FloatArray(0), false, null, g.indexCount / 3))
                continue
            }
            val off = uv.first
            val isHalf = uv.second
            // 顶点 UV 数组（屏幕空间）
            val vu = FloatArray(g.vertexCount)
            val vv = FloatArray(g.vertexCount)
            val v = g.vertices
            for (i in 0 until g.vertexCount) {
                val o = i * stride + off
                val rawU = if (isHalf) half(v, o) else f32(v, o)
                val rawV = if (isHalf) half(v, o + 2) else f32(v, o + 4)
                vu[i] = rawU
                vv[i] = 1f - rawV
            }
            // 索引 → 三角形汤
            val idx = g.indices
            val triN = g.indexCount / 3
            val soup = FloatArray(triN * 6)
            var n = 0
            var i16 = 0
            var i32 = 0
            var ok = true
            for (t in 0 until triN) {
                val a: Int; val b: Int; val c: Int
                if (g.indexFormat == 0) {
                    a = u16(idx, i16); i16 += 2
                    b = u16(idx, i16); i16 += 2
                    c = u16(idx, i16); i16 += 2
                } else {
                    a = u32(idx, i32).toInt(); i32 += 4
                    b = u32(idx, i32).toInt(); i32 += 4
                    c = u32(idx, i32).toInt(); i32 += 4
                }
                if (a < 0 || b < 0 || c < 0 || a >= g.vertexCount || b >= g.vertexCount || c >= g.vertexCount) {
                    ok = false; break
                }
                soup[n++] = vu[a]; soup[n++] = vv[a]
                soup[n++] = vu[b]; soup[n++] = vv[b]
                soup[n++] = vu[c]; soup[n++] = vv[c]
            }
            if (!ok) continue
            out.add(UvPart(g.name, g.lod, soup.copyOf(n), true, null, g.indexCount / 3))
        }
        return out
    }

    /**
     * 3D 贴图预览用: 单组的游戏空间 UV 三角形汤（不翻转 V，与 ScgGlView 顶点汤同三角/顶点序）。
     * 无 UV 通道或索引越界返回 null（ScgGlView 退回纯色渲染）。
     */
    fun extractGameUv(g: ScgGroup): FloatArray? {
        if (!posSane(g)) return null
        val uv = uvLayoutFor(g) ?: return null
        val stride = g.stride
        if (stride < 12 || g.vertexCount <= 0 || g.indexCount < 3) return null
        val off = uv.first
        val isHalf = uv.second
        val v = g.vertices
        val vu = FloatArray(g.vertexCount)
        val vv = FloatArray(g.vertexCount)
        for (i in 0 until g.vertexCount) {
            val o = i * stride + off
            vu[i] = if (isHalf) half(v, o) else f32(v, o)
            vv[i] = if (isHalf) half(v, o + 2) else f32(v, o + 4)
        }
        val idx = g.indices
        val triN = g.indexCount / 3
        val soup = FloatArray(triN * 6)
        var n = 0
        var i16 = 0
        var i32 = 0
        for (t in 0 until triN) {
            val a: Int; val b: Int; val c: Int
            if (g.indexFormat == 0) {
                a = u16(idx, i16); i16 += 2
                b = u16(idx, i16); i16 += 2
                c = u16(idx, i16); i16 += 2
            } else {
                a = u32(idx, i32).toInt(); i32 += 4
                b = u32(idx, i32).toInt(); i32 += 4
                c = u32(idx, i32).toInt(); i32 += 4
            }
            if (a < 0 || b < 0 || c < 0 || a >= g.vertexCount || b >= g.vertexCount || c >= g.vertexCount) return null
            soup[n++] = vu[a]; soup[n++] = vv[a]
            soup[n++] = vu[b]; soup[n++] = vv[b]
            soup[n++] = vu[c]; soup[n++] = vv[c]
        }
        return soup
    }
}
