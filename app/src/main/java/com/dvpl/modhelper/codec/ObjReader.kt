package com.dvpl.modhelper.codec

import java.nio.charset.StandardCharsets

/**
 * UV 部件（查看器数据单元）。
 * uv 为屏幕空间坐标（OBJ 惯例 V 向上），三角形汤展开（2 floats/顶点），
 * 上传 GL 后 glDrawArrays(GL_TRIANGLES) 直接绘；线框缓冲惰性生成。
 * SCG 侧提取见 ScgConverter.extractUvParts（V 翻转后同样落在此屏幕空间）。
 */
class UvPart(
    val name: String?,
    val lod: Int,
    /** 三角形汤: 6 floats/三角 (u1,v1,u2,v2,u3,v3) */
    val uv: FloatArray,
    val hasUv: Boolean,
    /** 3D 预览用位置汤: 9 floats/三角 (x,y,z ×3)，OBJ 输入时非空；SCG 提取为 null */
    val pos: FloatArray? = null,
    /** 三角形数（部件列表面数显示） */
    val tris: Int = 0
) {
    /** UV 包围盒 [minU,minV,maxU,maxV]（flyTo 用） */
    val bbox: FloatArray by lazy {
        if (!hasUv || uv.isEmpty()) floatArrayOf(0f, 0f, 1f, 1f)
        else {
            var mnU = Float.MAX_VALUE; var mnV = Float.MAX_VALUE
            var mxU = -Float.MAX_VALUE; var mxV = -Float.MAX_VALUE
            for (i in uv.indices step 2) {
                val u = uv[i]; val v = uv[i + 1]
                if (u < mnU) mnU = u; if (u > mxU) mxU = u
                if (v < mnV) mnV = v; if (v > mxV) mxV = v
            }
            floatArrayOf(mnU, mnV, mxU, mxV)
        }
    }
}

/**
 * 最小 OBJ 读取器：UV 查看器 + 3D 预览需要的数据（vt/v 坐标 + 面索引 + 分组名）。
 * 支持: v/vt/f 行、o/g 分组、负索引（相对寻址）、多边形面扇形三角化。
 * 面缺少 vt 分量（f 1 2 3 或 f 1//1）→ 该组 hasUv=false，但位置汤照常生成（3D 仍可显示）。
 * 本应用导出的 OBJ（ScgConverter.writeObj）vt 与顶点 1:1，是最主要输入。
 */
object ObjReader {

    fun parse(data: ByteArray): List<UvPart> {
        val text = String(data, StandardCharsets.UTF_8)
        // 全局坐标表（OBJ 索引跨组共享）
        var vtList = FloatArray(1024); var vtN = 0
        fun addVt(u: Float, v: Float) {
            if (vtN * 2 + 2 > vtList.size) vtList = vtList.copyOf(vtList.size * 2)
            vtList[vtN * 2] = u; vtList[vtN * 2 + 1] = v; vtN++
        }
        var vList = FloatArray(1024); var vN = 0
        fun addV(x: Float, y: Float, z: Float) {
            if (vN * 3 + 3 > vList.size) vList = vList.copyOf(vList.size * 2)
            vList[vN * 3] = x; vList[vN * 3 + 1] = y; vList[vN * 3 + 2] = z; vN++
        }

        val parts = ArrayList<UvPart>()
        var curName: String? = null
        var curLod = 0
        // 当前组三角形汤（屏幕空间）
        var curUv = FloatArray(4096); var curN = 0
        // 当前组位置汤（x,y,z ×3/三角）
        var curPos = FloatArray(6144); var curP = 0
        var curTris = 0
        var curHasUv = true
        var vtUsedInPart = false   // 本组是否真的用到 vt（防 vt 缺失误判）

        fun flushPart() {
            if (curTris > 0 || curName != null) {
                parts.add(UvPart(curName, curLod, curUv.copyOf(curN),
                    curHasUv && vtUsedInPart, curPos.copyOf(curP), curTris))
            }
            curUv = FloatArray(4096); curN = 0
            curPos = FloatArray(6144); curP = 0; curTris = 0
            curHasUv = true; vtUsedInPart = false
            curName = null; curLod = 0
        }

        fun ensureUv(extra: Int) {
            if (curN + extra > curUv.size) {
                var ns = curUv.size
                while (ns < curN + extra) ns *= 2
                curUv = curUv.copyOf(ns)
            }
        }
        fun ensurePos(extra: Int) {
            if (curP + extra > curPos.size) {
                var ns = curPos.size
                while (ns < curP + extra) ns *= 2
                curPos = curPos.copyOf(ns)
            }
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val sp = line.indexOf(' ')
            if (sp <= 0) continue
            val tag = line.substring(0, sp)
            val rest = line.substring(sp + 1).trim()
            when (tag) {
                "vt" -> {
                    // vt u v [w] —— w 忽略
                    val toks = rest.split(' ', '\t').filter { it.isNotEmpty() }
                    if (toks.size >= 2) {
                        val u = toks[0].toFloatOrNull()
                        val v = toks[1].toFloatOrNull()
                        if (u != null && v != null) addVt(u, v)
                    }
                }
                "v" -> {
                    // v x y z [w] —— w 忽略
                    val toks = rest.split(' ', '\t').filter { it.isNotEmpty() }
                    if (toks.size >= 3) {
                        val x = toks[0].toFloatOrNull()
                        val y = toks[1].toFloatOrNull()
                        val z = toks[2].toFloatOrNull()
                        if (x != null && y != null && z != null) addV(x, y, z)
                    }
                }
                "o", "g" -> {
                    flushPart()
                    var nm = rest.trim()
                    if (nm.isEmpty()) nm = "obj_" + (parts.size + 1)
                    // "_lodN" 后缀 → lod 级别（本应用 writeObj 命名约定）
                    var lod = 0
                    val m = Regex("_lod(\\d+)$").find(nm)
                    if (m != null) { lod = m.groupValues[1].toIntOrNull() ?: 0; nm = nm.dropLast(m.value.length) }
                    curName = nm
                    curLod = lod
                }
                "f" -> {
                    // f v/vt/vn ... 多边形 → 扇形三角化
                    val toks = rest.split(' ', '\t').filter { it.isNotEmpty() }
                    if (toks.size < 3) continue
                    val vIdx = IntArray(toks.size)
                    val vtIdx = IntArray(toks.size)
                    var allHaveVt = true
                    for (i in toks.indices) {
                        val c = toks[i]
                        val s1 = c.indexOf('/')
                        // 顶点索引（/ 前段; 无 / 整段）
                        val vTok = if (s1 > 0) c.substring(0, s1) else c
                        var vi = vTok.toIntOrNull() ?: 0
                        if (vi < 0) vi = vN + vi + 1
                        vIdx[i] = vi
                        if (s1 < 0) { vtIdx[i] = 0; allHaveVt = false; continue }
                        val s2 = c.indexOf('/', s1 + 1)
                        val vtTok = if (s2 > s1) c.substring(s1 + 1, s2) else c.substring(s1 + 1)
                        if (vtTok.isEmpty()) { vtIdx[i] = 0; allHaveVt = false; continue }
                        var idx = vtTok.toIntOrNull() ?: 0
                        if (idx < 0) idx = vtN + idx + 1   // 负索引 = 相对当前vt数
                        vtIdx[i] = idx
                    }
                    if (!allHaveVt) curHasUv = false
                    else vtUsedInPart = true
                    // 扇形三角化 + 写入汤（OBJ 索引 1-based; 0 = 缺失 → 跳该角）
                    for (t in 1 until toks.size - 1) {
                        val va = vIdx[0]; val vb = vIdx[t]; val vc = vIdx[t + 1]
                        // 位置汤: v 索引全有效才写（3D 预览）
                        if (va > 0 && vb > 0 && vc > 0 && va <= vN && vb <= vN && vc <= vN) {
                            ensurePos(9)
                            curPos[curP++] = vList[(va - 1) * 3]; curPos[curP++] = vList[(va - 1) * 3 + 1]; curPos[curP++] = vList[(va - 1) * 3 + 2]
                            curPos[curP++] = vList[(vb - 1) * 3]; curPos[curP++] = vList[(vb - 1) * 3 + 1]; curPos[curP++] = vList[(vb - 1) * 3 + 2]
                            curPos[curP++] = vList[(vc - 1) * 3]; curPos[curP++] = vList[(vc - 1) * 3 + 1]; curPos[curP++] = vList[(vc - 1) * 3 + 2]
                            curTris++
                        }
                        // UV 汤: vt 索引全有效才写（2D 预览）
                        if (allHaveVt) {
                            val a = vtIdx[0] - 1; val b = vtIdx[t] - 1; val c2 = vtIdx[t + 1] - 1
                            if (a in 0 until vtN && b in 0 until vtN && c2 in 0 until vtN) {
                                ensureUv(6)
                                curUv[curN++] = vtList[a * 2]; curUv[curN++] = vtList[a * 2 + 1]
                                curUv[curN++] = vtList[b * 2]; curUv[curN++] = vtList[b * 2 + 1]
                                curUv[curN++] = vtList[c2 * 2]; curUv[curN++] = vtList[c2 * 2 + 1]
                            } else curHasUv = false
                        }
                    }
                }
                // vn / usemtl / mtllib / s 等忽略
            }
        }
        flushPart()
        return parts
    }
}