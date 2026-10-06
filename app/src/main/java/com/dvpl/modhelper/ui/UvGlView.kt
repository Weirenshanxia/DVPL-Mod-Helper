package com.dvpl.modhelper.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import com.dvpl.modhelper.codec.UvPart
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 2D UV 查看视图（GLES 2.0，Blender UV 编辑器风格）。
 * - 线框/贴图/混合三模式；无贴图部件程序化棋盘格（Blender 同款语义）
 * - 正交相机: 单指平移、双指捏合 0.2x..128x（fit 基准），双指中点锚定
 * - NEAREST/LINEAR 可切 + 高倍率纹素网格（片元着色器，零贴图开销）
 * - flyTo(bbox) 平滑飞行至部件包围盒（细小零件定位）
 * - 整数 UV 块网格线（0/1/2，皮肤件跨块平铺可见）
 * TextureView + 自管 EGL14 渲染线程（同 ScgGlView，规避 MTK 对话框 SurfaceView 开洞 bug）。
 * UV 坐标约定: 输入已是屏幕空间（V 向上），SCG 侧在 extractUvParts 已翻转。
 * 可见性/贴图绑定一律按部件下标（列表顺序），部件名可能重复不可作键。
 */
class UvGlView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    companion object {
        const val MODE_WIREFRAME = 0
        const val MODE_TEXTURE = 1
        const val MODE_OVERLAY = 2
        private const val ZOOM_MIN = 0.2f
        private const val ZOOM_MAX = 128f
        private const val ANIM_MS = 220f
    }

    // ---------- 相机（UI 线程写 @Volatile，GL 线程读；竞态最多一帧陈旧值） ----------
    @Volatile var scale = 1f          // 屏幕像素 / UV 单位
    @Volatile var centerX = 0.5f
    @Volatile var centerY = 0.5f
    @Volatile var drawMode = MODE_WIREFRAME
    @Volatile var nearest = false
    @Volatile var visible: Set<Int>? = null      // null = 全部（部件下标集）
    @Volatile var highlight: Int = -1            // 选中部件下标（-1 无）; 其余部件淡化
    @Volatile private var anim: FloatArray? = null  // [cx0,cy0,s0,cx1,cy1,s1,t0ms]
    @Volatile private var vw = 1f; @Volatile private var vh = 1f
    @Volatile private var fitScale = 1f
    @Volatile private var fitBox: FloatArray? = null   // 全模型 UV 联合包围盒（初始摆位; 平铺件可远超 0..1）

    private var lastX = 0f; private var lastY = 0f
    private var pinching = false

    // ---------- 渲染线程（ScgGlView 同款模式） ----------
    private val renderLock = Object()
    @Volatile private var renderRequested = false
    @Volatile private var threadExit = false
    private var renderThread: Thread? = null
    private val renderer = Renderer()

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pinching = true; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomAt(d.focusX, d.focusY, d.scaleFactor)
                requestRender()
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) { pinching = false }
        })

    init {
        isOpaque = false  // 半透明表面: GL 画首帧前透出深色父背景, 避免合成器白缓冲白闪
        surfaceTextureListener = this
    }

    // ---------- 公共 API ----------

    fun setParts(parts: List<UvPart>) {
        // 全模型 UV 联合包围盒（初始摆位 + 网格范围; 平铺件可能到 ±6）
        var mnU = Float.MAX_VALUE; var mnV = Float.MAX_VALUE
        var mxU = -Float.MAX_VALUE; var mxV = -Float.MAX_VALUE
        for (p in parts) {
            if (!p.hasUv || p.uv.isEmpty()) continue
            val b = p.bbox
            if (b[0] < mnU) mnU = b[0]; if (b[2] > mxU) mxU = b[2]
            if (b[1] < mnV) mnV = b[1]; if (b[3] > mxV) mxV = b[3]
        }
        fitBox = if (mxU >= mnU && mxV >= mnV) floatArrayOf(mnU, mnV, mxU, mxV) else null
        renderer.setParts(parts, fitBox)
        // GL 表面未就绪时 fitScale=1, 此时摆位会生成错误动画目标;
        // onSurfaceTextureAvailable 会统一把相机放到全图视角
        if (vw > 1f) resetView()
    }

    fun setMode(m: Int) { drawMode = m; requestRender() }
    fun setFilter(n: Boolean) { nearest = n; requestRender() }
    fun setVisibility(v: Set<Int>?) { visible = v; requestRender() }
    // highlight 用属性直接赋值（@Volatile var 自带 setter, 再写 fun setHighlight 会撞 JVM 签名）

    /** 贴图绑定: idx=null 应用到全部无单独贴图的部件；bmp=null 清除 */
    fun setTexture(idx: Int?, bmp: Bitmap?) {
        renderer.queueTex(idx, bmp)
        requestRender()
    }

    /** 飞行至部件 UV 包围盒（细小零件定位） */
    fun flyTo(bbox: FloatArray) {
        if (bbox[2] <= bbox[0] || bbox[3] <= bbox[1]) return
        val spanU = max(1e-4f, bbox[2] - bbox[0])
        val spanV = max(1e-4f, bbox[3] - bbox[1])
        val s1 = min(vw / spanU * 0.8f, vh / spanV * 0.8f)
            .coerceIn(fitScale * ZOOM_MIN, fitScale * ZOOM_MAX)
        startAnim((bbox[0] + bbox[2]) / 2f, (bbox[1] + bbox[3]) / 2f, s1)
    }

    /** 初始相机: 适配全模型 UV 包围盒（至少能看到 0..1 主框; 平铺件全览） */
    private fun fitCamera(): FloatArray {
        val b = fitBox ?: return floatArrayOf(0.5f, 0.5f, fitScale)
        val spanU = max(1.15f, b[2] - b[0])
        val spanV = max(1.15f, b[3] - b[1])
        val s = min(vw / spanU, vh / spanV) * 0.95f
        return floatArrayOf((b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f, s)
    }

    fun resetView() { val c = fitCamera(); startAnim(c[0], c[1], c[2]) }

    fun onPause() = stopRenderThread()

    // ---------- 相机数学 ----------

    private fun zoomAt(fx: Float, fy: Float, factor: Float) {
        val s0 = scale
        val s1 = (s0 * factor).coerceIn(fitScale * ZOOM_MIN, fitScale * ZOOM_MAX)
        if (s1 == s0) return
        // 焦点下的 UV 点缩放前后不动。屏幕 y 向下, UV y 向上。
        val ux = centerX + (fx - vw / 2f) / s0
        val uy = centerY - (fy - vh / 2f) / s0
        scale = s1
        centerX = ux - (fx - vw / 2f) / s1
        centerY = uy + (fy - vh / 2f) / s1
    }

    private fun startAnim(cx: Float, cy: Float, s: Float) {
        anim = floatArrayOf(centerX, centerY, scale, cx, cy, s, android.os.SystemClock.uptimeMillis().toFloat())
        requestRender()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> {
                if (!pinching && e.pointerCount == 1) {
                    // 平移: 内容随手指移动, center 反向 (UV y 向上)
                    centerX -= (e.x - lastX) / scale
                    centerY += (e.y - lastY) / scale
                    anim = null
                    lastX = e.x; lastY = e.y
                    requestRender()
                }
            }
        }
        return true
    }

    fun requestRender() {
        synchronized(renderLock) { renderRequested = true; renderLock.notifyAll() }
    }

    // ---------- SurfaceTexture 生命周期 ----------

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        vw = max(1f, width.toFloat()); vh = max(1f, height.toFloat())
        fitScale = min(vw, vh) * 0.9f
        val c = fitCamera()
        centerX = c[0]; centerY = c[1]; scale = c[2]
        startRenderThread(st)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        val oldFit = fitScale
        vw = max(1f, width.toFloat()); vh = max(1f, height.toFloat())
        fitScale = min(vw, vh) * 0.9f
        scale = scale / oldFit * fitScale   // 保持相对缩放
        requestRender()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stopRenderThread()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun startRenderThread(st: SurfaceTexture) {
        stopRenderThread()
        threadExit = false
        renderRequested = true
        val t = Thread {
            val egl = EglWindow()
            if (!egl.create(st)) {
                Log.e("UvGlView", "EGL init failed: " + EGL14.eglGetError())
                egl.destroy()
                return@Thread
            }
            renderer.onSurfaceCreated()
            var running = true
            while (running) {
                synchronized(renderLock) {
                    while (!renderRequested && !threadExit) {
                        try { renderLock.wait() } catch (_: InterruptedException) { }
                    }
                    if (threadExit) running = false
                    else renderRequested = false
                }
                if (!running) break
                renderer.stepAnim()
                renderer.draw(vw, vh)
                if (!egl.swap()) {
                    Log.e("UvGlView", "swapBuffers failed")
                    running = false
                }
            }
            renderer.releaseGpu()
            egl.destroy()
        }
        t.name = "UvGlRender"
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

    /** 最小 EGL 封装（与 ScgGlView.EglWindow 相同: RGB8+A0+D16, GLES2） */
    private class EglWindow {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        fun create(st: SurfaceTexture): Boolean {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            if (!EGL14.eglInitialize(display, IntArray(2), 0, null, 0)) return false
            val cfgAttr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttr, 0, configs, 0, 1, num, 0) || num[0] < 1) return false
            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return false
            surface = EGL14.eglCreateWindowSurface(display, configs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) return false
            return EGL14.eglMakeCurrent(display, surface, surface, context)
        }

        fun swap(): Boolean = EGL14.eglSwapBuffers(display, surface)

        fun destroy() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                EGL14.eglReleaseThread()
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
        }
    }

    // ---------- 渲染器 ----------

    private class GpuPart(
        val idx: Int,
        val name: String?, val lod: Int, val hasUv: Boolean,
        val triBuf: FloatBuffer?, val triCount: Int,
        @Volatile var wireBuf: FloatBuffer?, @Volatile var wireVertCount: Int,
        val colorR: Float, val colorG: Float, val colorB: Float
    )

    private class TexUpload(val idx: Int?, val bmp: Bitmap?)

    private inner class Renderer {
        private var program = 0
        private var aUv = 0
        private var uCenter = 0; private var uScaleLoc = 0; private var uModeLoc = 0
        private var uTexLoc = 0; private var uTexSize = 0; private var uTexelLine = 0
        private var uPremul = 0; private var uColor = 0; private var uGridW = 0
        private var gridBuf: FloatBuffer? = null; private var gridVerts = 0
        private var frameBuf: FloatBuffer? = null   // 0..1 主框
        private val parts = ArrayList<GpuPart>()
        private val texIds = HashMap<Int?, Int>()
        private val texPremul = HashMap<Int?, Boolean>()
        private val texSizeW = HashMap<Int?, Float>()
        private val texSizeH = HashMap<Int?, Float>()
        private val pendingTex = ConcurrentLinkedQueue<TexUpload>()
        private var lastNearest = false

        fun setParts(list: List<UvPart>, fitBox: FloatArray?) {
            synchronized(parts) {
                parts.clear()
                var idx = 0
                for (p in list) {
                    if (!p.hasUv || p.uv.isEmpty()) {
                        parts.add(GpuPart(idx, p.name, p.lod, false, null, 0, null, 0, 0.5f, 0.5f, 0.5f))
                    } else {
                        val triCount = p.uv.size / 6
                        val fb = floatBuf(p.uv)
                        val seed = (p.name?.hashCode()?.toLong() ?: (7L + idx))
                        val hue = (((seed * 137L) % 360L + 360L) % 360L).toFloat()
                        val rgb = hsv(hue, 0.6f, 1.0f)
                        parts.add(GpuPart(idx, p.name, p.lod, true, fb, triCount, null, 0, rgb[0], rgb[1], rgb[2]))
                    }
                    idx++
                }
            }
            buildGrid(fitBox)
        }

        fun queueTex(idx: Int?, bmp: Bitmap?) { pendingTex.add(TexUpload(idx, bmp)) }

        fun stepAnim() {
            val a = anim ?: return
            val t = ((android.os.SystemClock.uptimeMillis() - a[6].toLong()) / ANIM_MS).coerceIn(0f, 1f)
            val e = t * t * (3f - 2f * t)   // smoothstep
            centerX = a[0] + (a[3] - a[0]) * e
            centerY = a[1] + (a[4] - a[1]) * e
            scale = exp(ln(a[2]) + (ln(a[5]) - ln(a[2])) * e)   // zoom 对数插值（视觉匀速）
            if (t >= 1f) anim = null else requestRender()
        }

        fun onSurfaceCreated() {
            program = buildProgram()
            aUv = GLES20.glGetAttribLocation(program, "aUv")
            uCenter = GLES20.glGetUniformLocation(program, "uCenter")
            uScaleLoc = GLES20.glGetUniformLocation(program, "uScale")
            uModeLoc = GLES20.glGetUniformLocation(program, "uMode")
            uTexLoc = GLES20.glGetUniformLocation(program, "uTex")
            uTexSize = GLES20.glGetUniformLocation(program, "uTexSize")
            uTexelLine = GLES20.glGetUniformLocation(program, "uTexelLine")
            uPremul = GLES20.glGetUniformLocation(program, "uPremul")
            uColor = GLES20.glGetUniformLocation(program, "uColor")
            uGridW = GLES20.glGetUniformLocation(program, "uGridW")
            GLES20.glClearColor(0.13f, 0.14f, 0.16f, 1f)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        private fun buildGrid(box: FloatArray?) {
            // 整数 UV 网格: 范围覆盖全模型 UV（至少 -1..2, 平铺件自动扩展, 上限 ±16）
            val x0 = kotlin.math.floor(minOf(-1f, box?.get(0) ?: 0f)).coerceAtLeast(-16f)
            val y0 = kotlin.math.floor(minOf(-1f, box?.get(1) ?: 0f)).coerceAtLeast(-16f)
            val x1 = kotlin.math.ceil(maxOf(2f, box?.get(2) ?: 1f)).coerceAtMost(16f)
            val y1 = kotlin.math.ceil(maxOf(2f, box?.get(3) ?: 1f)).coerceAtMost(16f)
            val nx = (x1 - x0).toInt() + 1
            val ny = (y1 - y0).toInt() + 1
            val f = FloatArray((nx + ny) * 4 + 16)
            var n = 0
            var i = x0.toInt()
            while (i <= x1.toInt()) {
                f[n++] = i.toFloat(); f[n++] = y0; f[n++] = i.toFloat(); f[n++] = y1
                i++
            }
            var j = y0.toInt()
            while (j <= y1.toInt()) {
                f[n++] = x0; f[n++] = j.toFloat(); f[n++] = x1; f[n++] = j.toFloat()
                j++
            }
            gridVerts = n / 2
            gridBuf = floatBuf(f.copyOf(n))
            // 0..1 主框（贴图基准框）
            val g = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, 0f, 1f, 0f, 0f)
            frameBuf = floatBuf(g)
        }

        fun draw(w: Float, h: Float) {
            while (true) { val up = pendingTex.poll() ?: break; uploadTex(up.idx, up.bmp) }
            GLES20.glViewport(0, 0, w.toInt(), h.toInt())
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform2f(uCenter, centerX, centerY)
            GLES20.glUniform2f(uScaleLoc, scale / (w / 2f), scale / (h / 2f))
            GLES20.glUniform1f(uGridW, 1.2f / scale)   // 网格线 ~1.2px
            val vis = visible
            val list = synchronized(parts) { parts.toList() }
            val m = drawMode

            // 1) 三角层（贴图或棋盘）; 选中独显（不同贴图的部件 UV 重叠时互不干扰）
            if (m != MODE_WIREFRAME) {
                for (p in list) {
                    if (!p.hasUv || p.triBuf == null) continue
                    val hl = highlight
                    if (hl >= 0) {
                        if (p.idx != hl) continue   // 只画选中件（无视隐藏/LOD 过滤）
                    } else if (!isVisible(p, vis)) continue
                    val texId = texIds[p.idx] ?: texIds[null]
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glUniform1i(uTexLoc, 0)
                    if (texId != null) {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                        applyFilterIfNeeded()
                        GLES20.glUniform1i(uModeLoc, 1)
                        GLES20.glUniform1f(uPremul, if (texPremul[p.idx] ?: texPremul[null] ?: false) 1f else 0f)
                        val tw = texSizeW[p.idx] ?: texSizeW[null] ?: 1f
                        val th = texSizeH[p.idx] ?: texSizeH[null] ?: 1f
                        GLES20.glUniform2f(uTexSize, tw, th)
                        val texelPx = scale / tw
                        GLES20.glUniform1f(uTexelLine, if (texelPx > 4f) 1.1f / texelPx * tw else 0f)
                        GLES20.glUniform4f(uColor, 1f, 1f, 1f, 1f)
                        drawBuf(p.triBuf, p.triCount * 3, GLES20.GL_TRIANGLES)
                    } else {
                        // 无贴图部件: 棋盘格半透明填充（Blender 同语义，贴图模式下仍可见部件范围）
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                        GLES20.glUniform1i(uModeLoc, 0)
                        GLES20.glUniform1f(uTexelLine, 0f)
                        GLES20.glUniform4f(uColor, 1f, 1f, 1f, 0.5f)
                        drawBuf(p.triBuf, p.triCount * 3, GLES20.GL_TRIANGLES)
                    }
                }
            }

            // 2) 线框层（棋盘底）; 选中独显
            if (m != MODE_TEXTURE) {
                for (p in list) {
                    if (!p.hasUv || p.triBuf == null) continue
                    val hl = highlight
                    if (hl >= 0) {
                        if (p.idx != hl) continue   // 只画选中件（无视隐藏/LOD 过滤）
                    } else if (!isVisible(p, vis)) continue
                    if (p.wireBuf == null) buildWire(p)
                    val wb = p.wireBuf ?: continue
                    GLES20.glUniform1i(uModeLoc, 0)
                    GLES20.glUniform1f(uTexelLine, 0f)
                    if (p.idx == hl) {
                        // 选中部件向白提亮, 一眼可辨
                        GLES20.glUniform4f(uColor,
                            p.colorR + (1f - p.colorR) * 0.45f,
                            p.colorG + (1f - p.colorG) * 0.45f,
                            p.colorB + (1f - p.colorB) * 0.45f, 1f)
                    } else {
                        GLES20.glUniform4f(uColor, p.colorR, p.colorG, p.colorB, 1f)
                    }
                    drawBuf(wb, p.wireVertCount, GLES20.GL_LINES)
                }
            }

            // 3) 整数网格线（最顶层; 放大看局部时隐藏, 避免整屏粗线添乱）
            val gb = if (scale <= fitScale * 1.5f) gridBuf else null
            if (gb != null) {
                GLES20.glUniform1i(uModeLoc, 0)
                GLES20.glUniform4f(uColor, 1f, 1f, 1f, 0.18f)
                drawBuf(gb, gridVerts, GLES20.GL_LINES)
                // 0..1 主框加亮: 一眼区分"平铺越框"与主框内
                GLES20.glUniform4f(uColor, 1f, 1f, 1f, 0.6f)
                frameBuf?.let { drawBuf(it, 8, GLES20.GL_LINES) }
            }
        }

        private fun isVisible(p: GpuPart, vis: Set<Int>?): Boolean = vis == null || p.idx in vis

        private fun applyFilterIfNeeded() {
            val n = nearest
            if (n == lastNearest) return
            lastNearest = n
            val f = if (n) GLES20.GL_NEAREST else GLES20.GL_LINEAR
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, f)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, f)
        }

        private fun uploadTex(idx: Int?, bmp: Bitmap?) {
            val old = texIds[idx]
            if (bmp == null) {
                if (old != null) {
                    GLES20.glDeleteTextures(1, intArrayOf(old), 0)
                    texIds.remove(idx); texPremul.remove(idx); texSizeW.remove(idx); texSizeH.remove(idx)
                }
                return
            }
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            // REPEAT 需 POT（GLES2 限制）; NPOT 退 CLAMP（游戏贴图均 POT, 用户图可能非 POT）
            val pot = Integer.bitCount(bmp.width) == 1 && Integer.bitCount(bmp.height) == 1
            val wrap = if (pot) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
            val filt = if (nearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filt)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filt)
            lastNearest = nearest
            try { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0) }
            catch (e: Throwable) {
                // 失败不能留空数据纹理（采样未定义 → 填充实层画不出来）:
                // 删新 id、保留旧纹理, 部件退回旧贴图/棋盘格
                Log.e("UvGlView", "tex upload failed", e)
                GLES20.glDeleteTextures(1, ids, 0)
                if (old != null) texIds[idx] = old
                return
            }
            if (old != null) GLES20.glDeleteTextures(1, intArrayOf(old), 0)
            texIds[idx] = ids[0]
            texPremul[idx] = bmp.isPremultiplied
            texSizeW[idx] = bmp.width.toFloat()
            texSizeH[idx] = bmp.height.toFloat()
        }

        private fun buildWire(p: GpuPart) {
            val src = p.triBuf ?: return
            val fa = FloatArray(p.triCount * 6)
            src.position(0); src.get(fa); src.position(0)
            val arr = FloatArray(p.triCount * 12)
            var n = 0
            var i = 0
            while (i < fa.size) {
                val ax = fa[i]; val ay = fa[i + 1]
                val bx = fa[i + 2]; val by = fa[i + 3]
                val cx = fa[i + 4]; val cy = fa[i + 5]
                arr[n++] = ax; arr[n++] = ay; arr[n++] = bx; arr[n++] = by
                arr[n++] = bx; arr[n++] = by; arr[n++] = cx; arr[n++] = cy
                arr[n++] = cx; arr[n++] = cy; arr[n++] = ax; arr[n++] = ay
                i += 6
            }
            p.wireBuf = floatBuf(arr.copyOf(n))
            p.wireVertCount = n / 2
        }

        private fun drawBuf(b: FloatBuffer, vertCount: Int, mode: Int) {
            b.position(0)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 8, b)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glDrawArrays(mode, 0, vertCount)
        }

        private fun floatBuf(arr: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(arr); position(0) }

        fun releaseGpu() {
            synchronized(parts) { parts.clear() }
            for (id in texIds.values) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            texIds.clear()
            if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
        }

        private fun hsv(h: Float, s: Float, v: Float): FloatArray {
            val c = v * s
            val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
            val m = v - c
            val r: Float; val g: Float; val b: Float
            when (((h / 60f).toInt() % 6 + 6) % 6) {
                0 -> { r = c; g = x; b = 0f }
                1 -> { r = x; g = c; b = 0f }
                2 -> { r = 0f; g = c; b = x }
                3 -> { r = 0f; g = x; b = c }
                4 -> { r = x; g = 0f; b = c }
                else -> { r = c; g = 0f; b = x }
            }
            return floatArrayOf(r + m, g + m, b + m)
        }

        private fun buildProgram(): Int {
            val vs = joinShader(arrayOf(
                "attribute vec2 aUv;",
                "uniform vec2 uCenter; uniform vec2 uScale;",
                "varying vec2 vUv;",
                "void main(){",
                "  vec2 d = (aUv - uCenter) * uScale;",
                "  gl_Position = vec4(d.x, d.y, 0.0, 1.0);",
                "  vUv = aUv;",
                "}"
            ))
            val fs = joinShader(arrayOf(
                "precision mediump float;",
                "varying vec2 vUv;",
                "uniform sampler2D uTex;",
                "uniform int uMode;",
                "uniform vec2 uTexSize;",
                "uniform float uTexelLine;",
                "uniform float uPremul;",
                "uniform vec4 uColor;",
                "uniform float uGridW;",
                "void main(){",
                "  vec4 col;",
                "  if (uMode == 0) {",
                "    float ck = mod(floor(vUv.x * 8.0) + floor(vUv.y * 8.0), 2.0);",
                "    vec3 base = mix(vec3(0.21,0.22,0.25), vec3(0.29,0.30,0.34), ck);",
                "    col = vec4(base * uColor.rgb, uColor.a);",
                "  } else {",
                "    col = texture2D(uTex, vec2(vUv.x, 1.0 - vUv.y));",
                "    if (uPremul > 0.5 && col.a > 0.001) col.rgb = min(col.rgb / col.a, vec3(1.0));",
                "    if (uTexelLine > 0.0) {",
                "      vec2 tp = fract(vec2(vUv.x, 1.0 - vUv.y) * uTexSize);",
                "      float tx = min(tp.x, 1.0 - tp.x);",
                "      float ty = min(tp.y, 1.0 - tp.y);",
                "      if (tx < uTexelLine || ty < uTexelLine) col.rgb *= 0.82;",
                "    }",
                "  }",
                "  float gx = min(fract(vUv.x), 1.0 - fract(vUv.x));",
                "  float gy = min(fract(vUv.y), 1.0 - fract(vUv.y));",
                "  if (min(gx, gy) < uGridW) col.rgb = mix(col.rgb, vec3(0.75), 0.55);",
                "  gl_FragColor = col;",
                "}"
            ))
            fun compile(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) Log.e("UvGlView", "shader: " + GLES20.glGetShaderInfoLog(s))
                return s
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }

        private fun joinShader(lines: Array<String>): String = lines.joinToString("\n")
    }
}