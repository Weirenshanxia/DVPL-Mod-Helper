package com.dvpl.modhelper.ui

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TextureRenderer(@Volatile var source: TextureSource) : GLSurfaceView.Renderer {

    // F3 修复：UI 线程写、GL 线程读的字段全部 @Volatile
    @Volatile var currentMip = 0
    @Volatile var zoom = 1f
    @Volatile var panX = 0f
    @Volatile var panY = 0f
    @Volatile var textureDirty = true
    var isHdrRendered = false
        private set
    var supportInfo = "初始化中..."
        private set
    var uploadFailed = false
        private set
    /** F2 修复：GPU 直传失败时回调（GL 线程触发），UI 层切换软解回退 */
    @Volatile var onUploadFailed: (() -> Unit)? = null

    private var program = 0
    private var texId = 0
    private var vao = 0
    // P4 优化：uniform location 缓存（不再每帧查询）
    private var uZoomLoc = 0
    private var uPanLoc = 0
    private var uTexLoc = 0
    private var uToneMapLoc = 0
    // P4 优化：复用的 direct buffer（按需扩容）
    private var uploadBuffer: ByteBuffer? = null

    fun markDirty() { textureDirty = true }

    override fun onDrawFrame(gl: GL10?) {
        if (textureDirty) uploadTexture()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (program == 0 || texId == 0) return
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1f(uZoomLoc, zoom)
        GLES30.glUniform2f(uPanLoc, panX, panY)
        GLES30.glUniform1i(uTexLoc, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val exts = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        val hasLdr = exts.contains("GL_KHR_texture_compression_astc_ldr")
        val hasHdr = exts.contains("GL_KHR_texture_compression_astc_hdr")
        supportInfo = buildString {
            append(if (hasLdr) "GPU 硬件 ASTC" else "无硬件 ASTC（软解）")
            append(if (hasHdr) " + HDR" else "")
        }
        GLES30.glClearColor(0.08f, 0.08f, 0.1f, 1f)
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program != 0) cacheUniformLocations()

        val verts = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )
        val vbo = IntArray(1)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(verts)
        buf.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES30.GL_STATIC_DRAW)
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        uploadTexture()
    }
    private fun uploadTexture() {
        if (texId == 0) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        uploadFailed = false
        when (val src = source) {
            is TextureSource.AstcCompressed -> {
                val mip = src.mips.getOrElse(currentMip) { src.mips.last() }
                val format = astcGlFormat(src.blockW, src.blockH)
                // P4 优化：复用 direct buffer
                val dataBuf = uploadBuffer?.takeIf { it.capacity() >= mip.data.size }
                    ?: ByteBuffer.allocateDirect(mip.data.size).order(ByteOrder.nativeOrder())
                        .also { uploadBuffer = it }
                dataBuf.clear()
                dataBuf.put(mip.data)
                dataBuf.position(0)
                dataBuf.limit(mip.data.size)
                GLES30.glCompressedTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, format,
                    mip.width, mip.height, 0,
                    mip.data.size, dataBuf
                )
                val err = GLES30.glGetError()
                if (err != 0) {
                    uploadFailed = true
                    android.util.Log.e("TextureRenderer", "glCompressedTexImage2D error 0x" + Integer.toHexString(err))
                    onUploadFailed?.invoke() // F2：通知 UI 层软解回退
                }
                isHdrRendered = src.isHdr
            }
            is TextureSource.DecodedBitmaps -> {
                val bmp = src.mips.getOrElse(currentMip) { src.mips.last() }
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                isHdrRendered = false
            }
        }
        textureDirty = false
        if (program != 0) {
            GLES30.glUseProgram(program)
            GLES30.glUniform1i(uToneMapLoc, if (isHdrRendered) 1 else 0)
        }
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES30.glCreateShader(type)
            GLES30.glShaderSource(sh, src)
            GLES30.glCompileShader(sh)
            val status = IntArray(1)
            GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                android.util.Log.e("TextureRenderer", GLES30.glGetShaderInfoLog(sh) ?: "shader compile fail")
                GLES30.glDeleteShader(sh)
                return 0
            }
            return sh
        }
        val vs = compile(GLES30.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        return if (status[0] == 0) 0 else prog
    }

    private fun cacheUniformLocations() {
        uZoomLoc = GLES30.glGetUniformLocation(program, "uZoom")
        uPanLoc = GLES30.glGetUniformLocation(program, "uPan")
        uTexLoc = GLES30.glGetUniformLocation(program, "uTex")
        uToneMapLoc = GLES30.glGetUniformLocation(program, "uToneMap")
    }

    companion object {
        private val VERTEX_SHADER = """
            #version 300 es
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUV;
            uniform float uZoom;
            uniform vec2 uPan;
            out vec2 vUV;
            void main() {
                vec2 pos = aPos * uZoom + uPan;
                gl_Position = vec4(pos, 0.0, 1.0);
                vUV = aUV;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #version 300 es
            precision highp float;
            in vec2 vUV;
            out vec4 fragColor;
            uniform sampler2D uTex;
            uniform bool uToneMap;
            void main() {
                vec4 c = texture(uTex, vUV);
                if (uToneMap) {
                    c.rgb = c.rgb * 2.0 / (1.0 + c.rgb);
                }
                fragColor = vec4(c.rgb, 1.0);
            }
        """.trimIndent()
    }
}