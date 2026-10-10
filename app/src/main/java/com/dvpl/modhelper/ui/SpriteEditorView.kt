package com.dvpl.modhelper.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.dvpl.modhelper.codec.SpriteFrame
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 精灵图集交互编辑视图（纯 Canvas 2D，无需 GL）。
 *
 * [Mode.ATLAS]  图集模式：显示图集位图与每帧裁取矩形叠加。
 *   拖帧体 → 改 srcX/srcY；拖 8 向把手 → 改 srcW/srcH（钳制在图集边界内）。
 * [Mode.PREVIEW] 预览模式：显示逻辑画布（棋盘底）+ 帧内容贴于 (offX, offY)。
 *   拖内容 → 改 offX/offY；拖右/下/右下把手 → 改 logW/logH。
 *
 * 手势：双指捏合缩放（0.05–64×）、单指拖空白平移、单击帧选中。
 * 拖动过程只更新内部副本实时重绘，ACTION_UP 时经 [Listener] 一次性提交。
 */
class SpriteEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onFrameSelected(index: Int)
        fun onFrameEdited(index: Int, frame: SpriteFrame)
        fun onLogicalEdited(w: Int, h: Int)
    }

    enum class Mode { ATLAS, PREVIEW }

    var listener: Listener? = null
    var placeholderText: String = ""

    // ── 数据 ──
    private var mode = Mode.ATLAS
    private var bitmap: Bitmap? = null
    private var frames: List<SpriteFrame> = emptyList()
    private var logW = 1
    private var logH = 1
    private var selected = -1

    // ── 视口（内容像素 → 屏幕像素）──
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    private var needFit = true

    private val contentW: Float
        get() = if (mode == Mode.ATLAS) (bitmap?.width ?: 0).toFloat() else logW.toFloat()
    private val contentH: Float
        get() = if (mode == Mode.ATLAS) (bitmap?.height ?: 0).toFloat() else logH.toFloat()

    // ── 拖动状态 ──
    private enum class Drag {
        NONE, PAN,
        MOVE, H_TL, H_T, H_TR, H_L, H_R, H_BL, H_B, H_BR,
        OFF_MOVE, L_R, L_B, L_BR
    }

    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var dragFrame: SpriteFrame? = null   // 拖动起始帧（绝对定位基准）
    private var dragLogW = 0
    private var dragLogH = 0
    private var liveFrame: SpriteFrame? = null   // 拖动中的临时帧（渲染用）
    private var liveLogW = -1
    private var liveLogH = -1

    // ── 画笔 ──
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gridPaint = Paint().apply { color = 0x2EFFFFFF }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
    }
    private val textBgPaint = Paint().apply { color = 0x66101216.toInt() }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9098A0.toInt()
        textAlign = Paint.Align.CENTER
    }

    private val checkerTile: Bitmap by lazy {
        val s = dp(8f).toInt().coerceAtLeast(4)
        val b = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val pA = Paint().apply { color = 0xFF26292E.toInt() }
        val pB = Paint().apply { color = 0xFF33373D.toInt() }
        c.drawRect(0f, 0f, s * 2f, s * 2f, pA)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), pB)
        c.drawRect(s.toFloat(), s.toFloat(), s * 2f, s * 2f, pB)
        b
    }
    private val checkerPaint = Paint().apply {
        shader = BitmapShader(checkerTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private val srcRect = Rect()
    private val dstRect = RectF()
    private val handlePts = FloatArray(16)

    // ── 外部推送 ──

    fun setAtlas(bmp: Bitmap?) {
        if (bitmap === bmp) return  // 同一位图不重置视口
        bitmap = bmp
        needFit = true
        invalidate()
    }

    fun setDocData(frames: List<SpriteFrame>, logW: Int, logH: Int, selected: Int) {
        this.frames = frames
        this.logW = logW
        this.logH = logH
        this.selected = selected
        liveFrame = null
        invalidate()
    }

    fun setMode(m: Mode) {
        if (mode == m) return
        mode = m
        needFit = true
        invalidate()
    }

    fun fitView() {
        needFit = true
        invalidate()
    }

    /** 视野对准当前帧：ATLAS 对准图集裁取矩形；PREVIEW 对准帧内容在画布上的区域 */
    fun zoomToFrame() {
        val f = frames.getOrNull(selected) ?: return
        if (width <= 0 || height <= 0) return
        val w = max(1, f.srcW).toFloat()
        val h = max(1, f.srcH).toFloat()
        val x = if (mode == Mode.ATLAS) f.srcX else f.offX
        val y = if (mode == Mode.ATLAS) f.srcY else f.offY
        val s = min(width / (w * 1.6f), height / (h * 1.6f))
            .coerceIn(0.05f, 64f)
        scale = s
        panX = width / 2f - (x + w / 2f) * s
        panY = height / 2f - (y + h / 2f) * s
        needFit = false
        clampPan()
        invalidate()
    }

    // ── 布局/绘制 ──

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (needFit) applyFit()
    }

    /** 平移钳制：内容不小于屏时边缘不超出屏幕；小于屏时锁定居中，图片永远出不了屏 */
    private fun clampPan() {
        if (width <= 0 || height <= 0) return
        val cw = contentW * scale
        val ch = contentH * scale
        panX = if (cw <= width) (width - cw) / 2f else panX.coerceIn(width - cw, 0f)
        panY = if (ch <= height) (height - ch) / 2f else panY.coerceIn(height - ch, 0f)
    }

    private fun applyFit() {
        val cw = contentW
        val ch = contentH
        if (cw <= 0f || ch <= 0f || width <= 0 || height <= 0) return
        scale = (min(width / cw, height / ch) * 0.92f).coerceAtLeast(0.05f)
        panX = (width - cw * scale) / 2f
        panY = (height - ch * scale) / 2f
        needFit = false
    }

    // applyFit 已居中；zoomToFrame/缩放/拖动后单独调用 clampPan

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF101216.toInt())
        if (needFit) applyFit()
        val bmp = bitmap
        if (mode == Mode.ATLAS) {
            if (bmp == null) {
                drawPlaceholder(canvas)
                return
            }
            canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(scale, scale)
            canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), checkerPaint)
            bmpPaint.isFilterBitmap = scale < 2f
            canvas.drawBitmap(bmp, 0f, 0f, bmpPaint)
            if (scale >= 6f) drawGrid(canvas, bmp.width.toFloat(), bmp.height.toFloat())
            canvas.restore()
        } else {
            val f = frames.getOrNull(selected)
            if (bmp == null || f == null) {
                drawPlaceholder(canvas)
                return
            }
            val lw = (if (liveLogW > 0) liveLogW else logW).toFloat()
            val lh = (if (liveLogH > 0) liveLogH else logH).toFloat()
            canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(scale, scale)
            canvas.drawRect(0f, 0f, lw, lh, checkerPaint)
            drawPreviewContent(canvas, liveFrame ?: f)
            canvas.restore()
        }
        drawOverlays(canvas)
    }

    private fun drawGrid(canvas: Canvas, bw: Float, bh: Float) {
        val x0 = max(0f, -panX / scale)
        val x1 = min(bw, (width - panX) / scale)
        val y0 = max(0f, -panY / scale)
        val y1 = min(bh, (height - panY) / scale)
        if (x1 <= x0 || y1 <= y0) return
        gridPaint.strokeWidth = dp(1f) / scale
        var x = ceil(x0)
        while (x <= x1) {
            canvas.drawLine(x, y0, x, y1, gridPaint)
            x += 1f
        }
        var y = ceil(y0)
        while (y <= y1) {
            canvas.drawLine(x0, y, x1, y, gridPaint)
            y += 1f
        }
    }

    /** PREVIEW 模式：把帧内容贴到逻辑画布 (offX, offY)（越界安全裁剪） */
    private fun drawPreviewContent(canvas: Canvas, f: SpriteFrame) {
        val bmp = bitmap ?: return
        val sx = max(0, f.srcX)
        val sy = max(0, f.srcY)
        val ex = min(bmp.width, f.srcX + f.srcW)
        val ey = min(bmp.height, f.srcY + f.srcH)
        if (ex <= sx || ey <= sy) return
        srcRect.set(sx, sy, ex, ey)
        dstRect.set(
            (f.offX + (sx - f.srcX)).toFloat(),
            (f.offY + (sy - f.srcY)).toFloat(),
            (f.offX + (ex - f.srcX)).toFloat(),
            (f.offY + (ey - f.srcY)).toFloat()
        )
        bmpPaint.isFilterBitmap = scale < 2f
        canvas.drawBitmap(bmp, srcRect, dstRect, bmpPaint)
    }

    private fun drawOverlays(canvas: Canvas) {
        if (mode == Mode.ATLAS) {
            for ((i, f0) in frames.withIndex()) {
                val f = if (i == selected) (liveFrame ?: f0) else f0
                val l = panX + f.srcX * scale
                val t = panY + f.srcY * scale
                val r = panX + (f.srcX + f.srcW) * scale
                val b = panY + (f.srcY + f.srcH) * scale
                val bmp = bitmap
                val oob = bmp != null && f.outsideAtlas(bmp.width, bmp.height)
                if (i == selected) {
                    fillPaint.color = 0x26FFD54F.toInt()
                    canvas.drawRect(l, t, r, b, fillPaint)
                }
                strokePaint.color = when {
                    i == selected -> 0xFFFFD54F.toInt()
                    oob -> Color.RED
                    else -> frameColor(i)
                }
                strokePaint.strokeWidth = if (i == selected) dp(2.5f) else dp(1.25f)
                strokePaint.pathEffect =
                    if (oob && i != selected) DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f) else null
                canvas.drawRect(l, t, r, b, strokePaint)
                drawLabel(canvas, f.name ?: "#$i", l, t, i == selected || oob)
            }
            val sel = frames.getOrNull(selected)
            if (sel != null) {
                drawHandles(canvas, handlePointsAtlas(liveFrame ?: sel), 8)
            }
        } else {
            val f0 = frames.getOrNull(selected) ?: return
            val f = liveFrame ?: f0
            val lw = (if (liveLogW > 0) liveLogW else logW).toFloat()
            val lh = (if (liveLogH > 0) liveLogH else logH).toFloat()
            // 逻辑画布边界
            strokePaint.color = 0xFFFFD54F.toInt()
            strokePaint.strokeWidth = dp(1.5f)
            strokePaint.pathEffect = null
            canvas.drawRect(panX, panY, panX + lw * scale, panY + lh * scale, strokePaint)
            // 内容框
            val cl = panX + f.offX * scale
            val ct = panY + f.offY * scale
            val cr = panX + (f.offX + f.srcW) * scale
            val cb = panY + (f.offY + f.srcH) * scale
            fillPaint.color = 0x12FFFFFF.toInt()
            canvas.drawRect(cl, ct, cr, cb, fillPaint)
            strokePaint.color = 0xFF66BB6A.toInt()
            canvas.drawRect(cl, ct, cr, cb, strokePaint)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, l: Float, t: Float, prominent: Boolean) {
        val pad = dp(3f)
        val tw = textPaint.measureText(text)
        var y = t - dp(4f)
        if (y < textPaint.textSize + pad) y = t + textPaint.textSize + pad
        textPaint.color = if (prominent) 0xFFFFE082.toInt() else 0xFFE0E0E0.toInt()
        textBgPaint.color = if (prominent) 0xB3101216.toInt() else 0x66101216.toInt()
        canvas.drawRect(l - pad, y - textPaint.textSize, l + tw + pad, y + dp(2f), textBgPaint)
        canvas.drawText(text, l, y, textPaint)
    }

    private fun drawHandles(canvas: Canvas, pts: FloatArray, count: Int) {
        handlePaint.color = 0xFFFFD54F.toInt()
        handleStrokePaint.color = 0xFF101216.toInt()
        handleStrokePaint.strokeWidth = dp(1.5f)
        val r = dp(5f)
        for (i in 0 until count) {
            canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], r, handlePaint)
            canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], r, handleStrokePaint)
        }
    }

    private fun handlePointsAtlas(f: SpriteFrame): FloatArray {
        val l = panX + f.srcX * scale
        val t = panY + f.srcY * scale
        val r = panX + (f.srcX + f.srcW) * scale
        val b = panY + (f.srcY + f.srcH) * scale
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        handlePts[0] = l; handlePts[1] = t
        handlePts[2] = cx; handlePts[3] = t
        handlePts[4] = r; handlePts[5] = t
        handlePts[6] = l; handlePts[7] = cy
        handlePts[8] = r; handlePts[9] = cy
        handlePts[10] = l; handlePts[11] = b
        handlePts[12] = cx; handlePts[13] = b
        handlePts[14] = r; handlePts[15] = b
        return handlePts
    }

    private fun frameColor(i: Int): Int {
        val hsv = floatArrayOf((i * 47f) % 360f, 0.7f, 1f)
        return Color.HSVToColor(0xD0, hsv)
    }

    private fun drawPlaceholder(canvas: Canvas) {
        placeholderPaint.textSize = dp(13f)
        canvas.drawText(placeholderText, width / 2f, height / 2f, placeholderPaint)
    }

    // ── 触控 ──

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val old = scale
                val ns = (scale * detector.scaleFactor).coerceIn(0.05f, 64f)
                val cx = (detector.focusX - panX) / old
                val cy = (detector.focusY - panY) / old
                scale = ns
                panX = detector.focusX - cx * ns
                panY = detector.focusY - cy * ns
                needFit = false
                clampPan()
                invalidate()
                return true
            }
        }
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            drag = Drag.NONE
            liveFrame = null
            liveLogW = -1
            liveLogH = -1
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragFrame = null
                liveFrame = null
                liveLogW = -1
                liveLogH = -1
                drag = hitTest(event.x, event.y)
                when (drag) {
                    Drag.MOVE, Drag.OFF_MOVE,
                    Drag.H_TL, Drag.H_T, Drag.H_TR, Drag.H_L, Drag.H_R,
                    Drag.H_BL, Drag.H_B, Drag.H_BR ->
                        dragFrame = frames.getOrNull(selected)
                    Drag.L_R, Drag.L_B, Drag.L_BR -> {
                        dragLogW = logW
                        dragLogH = logH
                    }
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                applyDrag(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) commitDrag()
                drag = Drag.NONE
                liveFrame = null
                liveLogW = -1
                liveLogH = -1
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hitTest(x: Float, y: Float): Drag {
        val sel = frames.getOrNull(selected)
        if (mode == Mode.ATLAS) {
            if (sel != null) {
                handleHitAtlas(x, y, sel)?.let { return it }
            }
            var best = -1
            var bestArea = Float.MAX_VALUE
            for ((i, f) in frames.withIndex()) {
                if (containsPx(f, x, y)) {
                    val a = f.srcW.toFloat() * f.srcH
                    if (a < bestArea) {
                        bestArea = a
                        best = i
                    }
                }
            }
            if (best >= 0) {
                if (best != selected) {
                    selected = best
                    listener?.onFrameSelected(best)
                }
                return Drag.MOVE
            }
        } else {
            if (sel != null) {
                val l = panX + sel.offX * scale
                val t = panY + sel.offY * scale
                val r = panX + (sel.offX + sel.srcW) * scale
                val b = panY + (sel.offY + sel.srcH) * scale
                if (x >= l && x <= r && y >= t && y <= b) return Drag.OFF_MOVE
            }
        }
        return Drag.PAN
    }

    private fun handleHitAtlas(x: Float, y: Float, f: SpriteFrame): Drag? {
        val pts = handlePointsAtlas(f)
        val names = arrayOf(
            Drag.H_TL, Drag.H_T, Drag.H_TR, Drag.H_L,
            Drag.H_R, Drag.H_BL, Drag.H_B, Drag.H_BR
        )
        return nearestHandle(x, y, pts, names)
    }

    private fun nearestHandle(x: Float, y: Float, pts: FloatArray, names: Array<Drag>): Drag? {
        val slop = dp(22f)
        var best: Drag? = null
        var bestD = slop * slop
        for (i in names.indices) {
            val dx = x - pts[i * 2]
            val dy = y - pts[i * 2 + 1]
            val d = dx * dx + dy * dy
            if (d <= bestD) {
                bestD = d
                best = names[i]
            }
        }
        return best
    }

    private fun containsPx(f: SpriteFrame, x: Float, y: Float): Boolean {
        val l = panX + f.srcX * scale
        val t = panY + f.srcY * scale
        val r = panX + (f.srcX + f.srcW) * scale
        val b = panY + (f.srcY + f.srcH) * scale
        return x >= l && x <= r && y >= t && y <= b
    }

    private fun applyDrag(x: Float, y: Float) {
        if (drag == Drag.PAN) {
            panX += x - downX
            panY += y - downY
            downX = x
            downY = y
            needFit = false
            clampPan()
            return
        }
        if (drag == Drag.NONE) return
        val dx = ((x - downX) / scale).roundToInt()
        val dy = ((y - downY) / scale).roundToInt()
        when (drag) {
            Drag.L_R -> {
                liveLogW = cr(dragLogW + dx, 1, 65536)
                liveLogH = dragLogH
            }
            Drag.L_B -> {
                liveLogW = dragLogW
                liveLogH = cr(dragLogH + dy, 1, 65536)
            }
            Drag.L_BR -> {
                liveLogW = cr(dragLogW + dx, 1, 65536)
                liveLogH = cr(dragLogH + dy, 1, 65536)
            }
            else -> {
                val f0 = dragFrame ?: return
                val limW = bitmap?.width ?: 65536
                val limH = bitmap?.height ?: 65536
                liveFrame = when (drag) {
                    Drag.MOVE -> f0.edit(
                        srcX = cr(f0.srcX + dx, 0, max(0, limW - f0.srcW)),
                        srcY = cr(f0.srcY + dy, 0, max(0, limH - f0.srcH))
                    )
                    Drag.H_TL -> {
                        val rt = f0.srcX + f0.srcW
                        val bt = f0.srcY + f0.srcH
                        val nl = cr(f0.srcX + dx, 0, rt - 1)
                        val nt = cr(f0.srcY + dy, 0, bt - 1)
                        f0.edit(srcX = nl, srcY = nt, srcW = rt - nl, srcH = bt - nt)
                    }
                    Drag.H_T -> {
                        val bt = f0.srcY + f0.srcH
                        val nt = cr(f0.srcY + dy, 0, bt - 1)
                        f0.edit(srcY = nt, srcH = bt - nt)
                    }
                    Drag.H_TR -> {
                        val bt = f0.srcY + f0.srcH
                        val nr = cr(f0.srcX + f0.srcW + dx, f0.srcX + 1, limW)
                        val nt = cr(f0.srcY + dy, 0, bt - 1)
                        f0.edit(srcY = nt, srcW = nr - f0.srcX, srcH = bt - nt)
                    }
                    Drag.H_L -> {
                        val rt = f0.srcX + f0.srcW
                        val nl = cr(f0.srcX + dx, 0, rt - 1)
                        f0.edit(srcX = nl, srcW = rt - nl)
                    }
                    Drag.H_R -> {
                        val nr = cr(f0.srcX + f0.srcW + dx, f0.srcX + 1, limW)
                        f0.edit(srcW = nr - f0.srcX)
                    }
                    Drag.H_BL -> {
                        val rt = f0.srcX + f0.srcW
                        val nl = cr(f0.srcX + dx, 0, rt - 1)
                        val nb = cr(f0.srcY + f0.srcH + dy, f0.srcY + 1, limH)
                        f0.edit(srcX = nl, srcW = rt - nl, srcH = nb - f0.srcY)
                    }
                    Drag.H_B -> {
                        val nb = cr(f0.srcY + f0.srcH + dy, f0.srcY + 1, limH)
                        f0.edit(srcH = nb - f0.srcY)
                    }
                    Drag.H_BR -> {
                        val nr = cr(f0.srcX + f0.srcW + dx, f0.srcX + 1, limW)
                        val nb = cr(f0.srcY + f0.srcH + dy, f0.srcY + 1, limH)
                        f0.edit(srcW = nr - f0.srcX, srcH = nb - f0.srcY)
                    }
                    Drag.OFF_MOVE -> f0.edit(
                        offX = cr(f0.offX + dx, 0, max(0, logW - f0.srcW)),
                        offY = cr(f0.offY + dy, 0, max(0, logH - f0.srcH))
                    )
                    else -> f0
                }
            }
        }
    }

    private fun cr(v: Int, lo: Int, hi: Int): Int =
        if (lo > hi) v.coerceIn(hi, lo) else v.coerceIn(lo, hi)

    private fun commitDrag() {
        val idx = selected
        when (drag) {
            Drag.MOVE, Drag.OFF_MOVE,
            Drag.H_TL, Drag.H_T, Drag.H_TR, Drag.H_L, Drag.H_R,
            Drag.H_BL, Drag.H_B, Drag.H_BR -> {
                val live = liveFrame
                if (live != null && idx in frames.indices) listener?.onFrameEdited(idx, live)
            }
            Drag.L_R, Drag.L_B, Drag.L_BR -> {
                val lw = liveLogW
                val lh = liveLogH
                if (lw > 0 && lh > 0) listener?.onLogicalEdited(lw, lh)
            }
            else -> {}
        }
    }
}
