package com.dvpl.modhelper

import com.dvpl.modhelper.codec.WebpAnimMuxer
import org.junit.Test

/** 临时诊断: 用真实 Kotlin 混流器拼 Pillow 生成的帧, 输出文件交给 Pillow 裁决 */
class WebpInteropDiagTest {

    @Test
    fun muxRealFrames() {
        val dir = java.io.File("D:/Download/dsh_workplace/frames")
        val frames = dir.listFiles { f -> f.extension == "webp" }!!
            .sortedBy { it.name }
        check(frames.isNotEmpty()) { "no frames found" }
        val list = frames.map { f ->
            println("frame: " + f.name + " " + f.length() + "B")
            WebpAnimMuxer.Frame(f.readBytes(), 100)
        }
        val out = WebpAnimMuxer.mux(list, 64, 48, 0)
        java.io.File("D:/Download/dsh_workplace/kotlin_mux_out.webp").writeBytes(out)
        println("muxed " + out.size + " bytes from " + frames.size + " frames")
        // 打印 chunk 结构
        val chunks = WebpAnimMuxer.listChunks(out)
        chunks.forEach { (name, size) -> println("  " + name + " " + size) }
    }
}
