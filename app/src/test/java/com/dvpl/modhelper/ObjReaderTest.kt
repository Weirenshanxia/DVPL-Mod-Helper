package com.dvpl.modhelper

import com.dvpl.modhelper.codec.ObjReader
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** ObjReader 解析单测（纯合成样例，不依赖外部资产） */
class ObjReaderTest {

    private fun obj(s: String) = ObjReader.parse(s.toByteArray())

    @Test
    fun triangleWithVt() {
        val src = """
v 0 0 0
v 1 0 0
v 0 1 0
vt 0.0 0.0
vt 1.0 0.0
vt 0.0 1.0
o hull_lod0
f 1/1 2/2 3/3"""
        val parts = obj(src)
        assertEquals(1, parts.size)
        val p = parts[0]
        assertEquals("hull", p.name)
        assertEquals(0, p.lod)
        assertTrue(p.hasUv)
        assertEquals(6, p.uv.size)
        assertEquals(0.0f, p.uv[0]); assertEquals(0.0f, p.uv[1])
        assertEquals(1.0f, p.uv[2]); assertEquals(0.0f, p.uv[3])
    }

    @Test
    fun negativeIndices() {
        val src = """
vt 0.25 0.5
vt 0.75 0.5
vt 0.5 0.75
o t
f -3/-3 -2/-2 -1/-1"""
        val parts = obj(src)
        assertEquals(1, parts.size)
        assertTrue(parts[0].hasUv)
        assertEquals(0.25f, parts[0].uv[0], 1e-6f)
        assertEquals(0.5f, parts[0].uv[1], 1e-6f)
        assertEquals(0.75f, parts[0].uv[2], 1e-6f)   // -2 → 第2个 vt (0.75,0.5)
        assertEquals(0.5f, parts[0].uv[4], 1e-6f)    // -1 → 第3个 vt (0.5,0.75)
        assertEquals(0.75f, parts[0].uv[5], 1e-6f)
    }

    @Test
    fun quadFanTriangulated() {
        val src = """
vt 0 0
vt 1 0
vt 1 1
vt 0 1
o q
f 1/1 2/2 3/3 4/4"""
        val parts = obj(src)
        assertEquals(1, parts.size)
        assertEquals(12, parts[0].uv.size)  // 2 三角 x 6 floats
    }

    @Test
    fun faceWithoutVtIsNoUv() {
        val src = """
vt 0 0
o plain
f 1 2 3"""
        val parts = obj(src)
        assertEquals(1, parts.size)
        assertFalse(parts[0].hasUv)
    }

    @Test
    fun slashSlashForm() {
        val src = """
o nn
f 1//1 2//2 3//3"""
        val parts = obj(src)
        assertEquals(1, parts.size)
        assertFalse(parts[0].hasUv)
    }

    @Test
    fun multipleGroupsAndLod() {
        val src = """
o a_lod0
vt 0 0
vt 1 0
vt 0 1
f 1/1 2/2 3/3
o b_lod2
vt 1 1
f 2/2 3/3 1/1"""
        val parts = obj(src)
        assertEquals(2, parts.size)
        assertEquals("a", parts[0].name); assertEquals(0, parts[0].lod)
        assertEquals("b", parts[1].name); assertEquals(2, parts[1].lod)
        assertEquals(6, parts[1].uv.size)  // 复用全局 vt 表
    }
}