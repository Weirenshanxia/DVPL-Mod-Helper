package com.dvpl.modhelper

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import com.dvpl.modhelper.codec.ScgConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan

/**
 * OpenGL 3D 预览（GLES 2.0）：真深度缓冲 + 双方向光着色，无面数上限。
 * 交互：单指拖动旋转，双指捏合缩放。
 * 显示规则：solo 模式只高亮该组；否则勾选组实心、未勾选组幽灵灰；全不选时整车幽灵。
 *
 * 用 TextureView + 自管 EGL14 渲染线程而非 GLSurfaceView：部分 OEM 合成器
 * （实测天玑820 Mali-G57）在 AlertDialog 子窗口里对 SurfaceView 的"开洞"范围
 * 计算错误，把整个对话框背景当洞抠掉 → 界面背景变透明、只残预览框与零星文字。
 * TextureView 不开洞（作为普通纹理混入窗口层），从根上规避该类问题。
 */
class ScgGlView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private val renderer = Renderer()
    private var lastX = 0f
    private var lastY = 0f

    @Volatile var yaw = 38f
    @Volatile var pitch = -62f
    @Volatile var zoom = 1f

    @Volatile private var surfaceW = 1
    @Volatile private var surfaceH = 1
    @Volatile private var renderRequested = false
    @Volatile private var threadExit = false
    private val renderLock = Object()
    private var renderThread: Thread? = null

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // 双指张开 = 放大（靠近）
                zoom = (zoom * detector.scaleFactor).coerceIn(0.3f, 6f)
                requestRender()
                return true
            }
        })

    init {
        isOpaque = true   // 内容不透明（clear alpha=1），可免一层合成混合
        surfaceTextureListener = this
    }

    fun setScene(groups: List<ScgConverter.ScgGroup>) {
        renderer.setScene(groups)
        requestRender()
    }

    fun setDisplay(selectedIds: Set<Long>, soloId: Long?) {
        renderer.display(selectedIds, soloId)
        requestRender()
    }

    /** 停止渲染线程并释放 EGL 资源（AndroidView onRelease / 对话框关闭时调用） */
    fun onPause() = stopRenderThread()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            // 手指数量变化时重新锚定，避免抬指瞬间坐标跳变带跑镜头
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> {
                // 仅单指时旋转；双指（缩放中）不触发镜头移动
                if (!scaleDetector.isInProgress && e.pointerCount == 1) {
                    yaw += (e.x - lastX) * 0.5f
                    // 俯仰范围允许穿过水平视角一直到底部（仰视）
                    pitch = (pitch + (e.y - lastY) * 0.4f).coerceIn(-179f, 1f)
                    lastX = e.x; lastY = e.y
                    requestRender()
                }
            }
        }
        return true
    }

    // ---------- SurfaceTexture 生命周期 ----------

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surfaceW = max(1, width); surfaceH = max(1, height)
        startRenderThread(st)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        surfaceW = max(1, width); surfaceH = max(1, height)
        requestRender()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopRenderThread()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ---------- 渲染线程 ----------

    fun requestRender() {
        synchronized(renderLock) { renderRequested = true; renderLock.notifyAll() }
    }

    private fun startRenderThread(st: SurfaceTexture) {
        stopRenderThread()
        threadExit = false
        renderRequested = true
        val w0 = surfaceW; val h0 = surfaceH
        val t = Thread {
            val egl = EglWindow()
            if (!egl.create(st)) {
                Log.e("ScgGlView", "EGL 初始化失败: " + EGL14.eglGetError())
                egl.destroy()
                return@Thread
            }
            renderer.onSurfaceCreated()
            renderer.onSurfaceChanged(w0, h0)
            var running = true
            while (running) {
                var shouldDraw = false
                synchronized(renderLock) {
                    while (!renderRequested && !threadExit) {
                        try { renderLock.wait() } catch (_: InterruptedException) { }
                    }
                    if (threadExit) running = false
                    else { renderRequested = false; shouldDraw = true }
                }
                if (!running) break
                val w = surfaceW; val h = surfaceH
                renderer.onDrawFrame(w, h)
                if (!egl.swap()) {
                    Log.e("ScgGlView", "swapBuffers 失败: " + EGL14.eglGetError())
                    running = false
                }
            }
            egl.destroy()
        }
        t.name = "ScgGlRender"
        t.isDaemon = true
        renderThread = t
        t.start()
    }

    private fun stopRenderThread() {
        val t = renderThread ?: return
        synchronized(renderLock) { threadExit = true; renderLock.notifyAll() }
        try { t.join(3000) } catch (_: InterruptedException) { }
        renderThread = null
    }

    /** 最小 EGL 封装：window surface 基于 SurfaceTexture，显式 0 位 alpha */
    private class EglWindow {
        var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun create(st: SurfaceTexture): Boolean {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false
            if (!EGL14.eglInitialize(eglDisplay, IntArray(2), 0, null, 0)) return false
            val cfgAttr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 0, EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, configs, 0, 1, num, 0) ||
                num[0] < 1) return false
            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], st,
                intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false
            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        fun swap(): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        fun destroy() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE)
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
                EGL14.eglReleaseThread()
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    // ---------- 场景数据与绘制（与旧 GLSurfaceView 版逐字节等价） ----------

    private class Part(
        val gid: Long,
        val buf: FloatBuffer,
        val triCount: Int,
        val colorR: Float, val colorG: Float, val colorB: Float
    )

    private class SceneData(
        val parts: List<Part>,
        val cx: Float, val cy: Float, val cz: Float, val radius: Float
    )

    private inner class Renderer {
        @Volatile private var pendingGroups: List<ScgConverter.ScgGroup>? = null
        @Volatile private var selected: Set<Long> = emptySet()
        @Volatile private var solo: Long? = null
        private var scene: SceneData? = null
        private var program = 0
        private var aPos = 0; private var aNorm = 0
        private var uMvp = 0; private var uRot = 0; private var uColor = 0; private var uGhost = 0
        private var vpW = 1; private var vpH = 1

        private val mvp = FloatArray(16)
        private val rotM = FloatArray(16)
        private val projM = FloatArray(16)
        private val tmpM = FloatArray(16)
        private val tmp2M = FloatArray(16)
        private val viewM = FloatArray(16)

        fun setScene(groups: List<ScgConverter.ScgGroup>) { pendingGroups = groups }
        fun display(selectedIds: Set<Long>, soloId: Long?) { selected = selectedIds; solo = soloId }

        fun onSurfaceCreated() {
            program = buildProgram()
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            aNorm = GLES20.glGetAttribLocation(program, "aNorm")
            uMvp = GLES20.glGetUniformLocation(program, "uMvp")
            uRot = GLES20.glGetUniformLocation(program, "uRot")
            uColor = GLES20.glGetUniformLocation(program, "uColor")
            uGhost = GLES20.glGetUniformLocation(program, "uGhost")
            GLES20.glClearColor(0.07f, 0.08f, 0.10f, 1f)
        }

        fun onSurfaceChanged(width: Int, height: Int) {
            vpW = max(1, width); vpH = max(1, height)
            GLES20.glViewport(0, 0, vpW, vpH)
        }

        fun onDrawFrame(width: Int, height: Int) {
            if (vpW != width || vpH != height) onSurfaceChanged(width, height)
            pendingGroups?.let { buildAndUpload(it); pendingGroups = null }
            val sc = scene ?: run { GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); return }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(program)

            // 模型: Rx(pitch) * Rz(yaw) * T(-center)
            Matrix.setIdentityM(rotM, 0)
            Matrix.rotateM(rotM, 0, yaw, 0f, 0f, 1f)
            Matrix.setIdentityM(tmpM, 0)
            Matrix.rotateM(tmpM, 0, pitch, 1f, 0f, 0f)
            Matrix.multiplyMM(tmp2M, 0, tmpM, 0, rotM, 0)
            System.arraycopy(tmp2M, 0, rotM, 0, 16)
            Matrix.setIdentityM(tmpM, 0)
            Matrix.translateM(tmpM, 0, -sc.cx, -sc.cy, -sc.cz)
            Matrix.multiplyMM(tmp2M, 0, rotM, 0, tmpM, 0)

            val fovY = 33f
            val dist = sc.radius / tan(Math.toRadians((fovY * 0.5).toDouble()).toFloat()) / zoom
            val near = (dist - sc.radius * 1.3f).coerceAtLeast(dist * 0.05f)
            val far = dist + sc.radius * 1.3f
            Matrix.perspectiveM(projM, 0, fovY, vpW.toFloat() / vpH, near, far)
            Matrix.setIdentityM(viewM, 0)
            Matrix.translateM(viewM, 0, 0f, 0f, -dist)
            Matrix.multiplyMM(mvp, 0, viewM, 0, tmp2M, 0)
            Matrix.multiplyMM(mvp, 0, projM, 0, mvp, 0)

            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uRot, 1, false, rotM, 0)

            val sol = solo
            val isSolid: (Part) -> Boolean = when {
                sol != null -> { p -> p.gid == sol }
                selected.isNotEmpty() -> { p -> p.gid in selected }
                else -> { _ -> false }
            }

            // 幽灵层: 先画, 不写深度
            GLES20.glDepthMask(false)
            GLES20.glUniform1f(uGhost, 1f)
            for (i in sc.parts.indices) {
                val p = sc.parts[i]
                if (isSolid(p)) continue
                drawPart(p, i, 1f)
            }
            // 实体层
            GLES20.glDepthMask(true)
            GLES20.glUniform1f(uGhost, 0f)
            for (i in sc.parts.indices) {
                val p = sc.parts[i]
                if (!isSolid(p)) continue
                drawPart(p, i, 1f)
            }
            GLES20.glDepthMask(true)
        }

        private fun drawPart(p: Part, index: Int, alpha: Float) {
            val b = p.buf
            b.position(0)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, b)
            b.position(3)
            GLES20.glVertexAttribPointer(aNorm, 3, GLES20.GL_FLOAT, false, 24, b)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glEnableVertexAttribArray(aNorm)
            GLES20.glUniform4f(uColor, p.colorR, p.colorG, p.colorB, alpha)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, p.triCount * 3)
        }

        private fun buildAndUpload(groups: List<ScgConverter.ScgGroup>) {
            scene = buildScene(groups)
        }

        private fun buildProgram(): Int {
            val vs = "attribute vec3 aPos; attribute vec3 aNorm; " +
                "uniform mat4 uMvp; uniform mat4 uRot; varying vec3 vN; " +
                "void main(){ gl_Position = uMvp * vec4(aPos, 1.0); vN = (uRot * vec4(aNorm, 0.0)).xyz; }"
            val fs = "precision mediump float; varying vec3 vN; " +
                "uniform vec4 uColor; uniform float uGhost; " +
                "void main(){ vec3 n = normalize(vN); " +
                "float d1 = abs(dot(n, normalize(vec3(0.35, 0.55, 0.75)))); " +
                "float d2 = abs(dot(n, normalize(vec3(-0.5, -0.3, 0.4)))); " +
                "float lit = 0.36 + 0.46 * d1 + 0.18 * d2; " +
                "vec3 col = mix(uColor.rgb * lit, vec3(0.5), uGhost * 0.85); " +
                "float a = mix(uColor.a, 0.10, uGhost); " +
                "gl_FragColor = vec4(col, a); }"
            fun compile(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                return s
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }
    }

    // ---------- 三角面汤构建（CPU, 一次性） ----------

    private fun buildScene(groups: List<ScgConverter.ScgGroup>): SceneData {
        val parts = ArrayList<Part>()
        var mnX = Float.MAX_VALUE; var mnY = Float.MAX_VALUE; var mnZ = Float.MAX_VALUE
        var mxX = -Float.MAX_VALUE; var mxY = -Float.MAX_VALUE; var mxZ = -Float.MAX_VALUE
        for (g in groups) {
            val stride = g.stride
            if (stride < 12 || g.vertexCount < 3 || g.indexCount < 3) continue
            val tc = g.indexCount / 3
            val buf = ByteBuffer.allocateDirect(tc * 18 * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val vb = ByteBuffer.wrap(g.vertices).order(ByteOrder.LITTLE_ENDIAN)
            val ib = ByteBuffer.wrap(g.indices).order(ByteOrder.LITTLE_ENDIAN)
            var wrote = 0
            var ok = true
            for (t in 0 until tc) {
                if (!ok) break
                val vi = IntArray(3)
                for (c in 0..2) {
                    vi[c] = if (g.indexFormat == 0) {
                        ib.getShort(t * 6 + c * 2).toInt() and 0xFFFF
                    } else {
                        ib.getInt(t * 12 + c * 4)
                    }
                    if (vi[c] < 0 || vi[c] >= g.vertexCount) { ok = false; break }
                }
                if (!ok) break
                val ax = vb.getFloat(vi[0] * stride); val ay = vb.getFloat(vi[0] * stride + 4); val az = vb.getFloat(vi[0] * stride + 8)
                val bx = vb.getFloat(vi[1] * stride); val by = vb.getFloat(vi[1] * stride + 4); val bz = vb.getFloat(vi[1] * stride + 8)
                val cxv = vb.getFloat(vi[2] * stride); val cyv = vb.getFloat(vi[2] * stride + 4); val czv = vb.getFloat(vi[2] * stride + 8)
                // 面法线
                val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
                val e2x = cxv - ax; val e2y = cyv - ay; val e2z = czv - az
                var nx = e1y * e2z - e1z * e2y
                var ny = e1z * e2x - e1x * e2z
                var nz = e1x * e2y - e1y * e2x
                val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 1e-12f) { nx /= len; ny /= len; nz /= len } else { nx = 0f; ny = 0f; nz = 1f }
                buf.put(ax).put(ay).put(az).put(nx).put(ny).put(nz)
                buf.put(bx).put(by).put(bz).put(nx).put(ny).put(nz)
                buf.put(cxv).put(cyv).put(czv).put(nx).put(ny).put(nz)
                wrote++
                if (ax < mnX) mnX = ax; if (ax > mxX) mxX = ax
                if (bx < mnX) mnX = bx; if (bx > mxX) mxX = bx
                if (cxv < mnX) mnX = cxv; if (cxv > mxX) mxX = cxv
                if (ay < mnY) mnY = ay; if (ay > mxY) mxY = ay
                if (by < mnY) mnY = by; if (by > mxY) mxY = by
                if (cyv < mnY) mnY = cyv; if (cyv > mxY) mxY = cyv
                if (az < mnZ) mnZ = az; if (az > mxZ) mxZ = az
                if (bz < mnZ) mnZ = bz; if (bz > mxZ) mxZ = bz
                if (czv < mnZ) mnZ = czv; if (czv > mxZ) mxZ = czv
            }
            if (!ok || wrote == 0) continue
            buf.position(0); buf.limit(wrote * 6)
            val hue = ((g.id * 137L) % 360L).toFloat()
            val rgb = hsv(hue, 0.55f, 0.95f)
            parts.add(Part(g.id, buf, wrote, rgb[0], rgb[1], rgb[2]))
        }
        if (parts.isEmpty()) return SceneData(emptyList(), 0f, 0f, 0f, 1f)
        val cx = (mnX + mxX) / 2f; val cy = (mnY + mxY) / 2f; val cz = (mnZ + mxZ) / 2f
        val radius = max(max(abs(mxX - mnX), abs(mxY - mnY)), abs(mxZ - mnZ)) * 0.62f + 0.01f
        return SceneData(parts, cx, cy, cz, radius)
    }

    private fun hsv(h: Float, s: Float, v: Float): FloatArray {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return floatArrayOf(r + m, g + m, b + m)
    }
}
