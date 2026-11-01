package com.topz.vecdraw

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

enum class Tool(val label: String, val icon: String) {
    PAN("Pan", "M7 11V6a1.5 1.5 0 0 1 3 0v5 M10 11V4a1.5 1.5 0 0 1 3 0v7 M13 11V5a1.5 1.5 0 0 1 3 0v8 M16 9a1.5 1.5 0 0 1 3 0v6c0 4-3 6-6 6s-5-2-7-5l-2-3"),
    PICK("Pick", "M6 3L6 19L10 15L13 21L15.5 20L12.5 14L18 14Z"),
    NODE("Shape / Node", "M4 18C8 4 16 4 20 18 M3 17h3v3H3z M18 17h3v3h-3z M10.5 4.5h3v3h-3z"),
    CROP("Crop", "M6 2v16h16 M2 6h16v16"),
    FREEHAND("Freehand", "M3 15C6 6 9 6 11 12S16 18 21 8"),
    PEN("Pen", "M14 4l6 6-10 10H4v-6z M12 6l6 6"),
    BEZIER("Bezier", "M4 18C4 6 20 18 20 6 M3 17h2v2H3z M19 5h2v2h-2z"),
    POLYLINE("Polyline", "M3 18L9 8L14 15L21 5"),
    BSPLINE("B-Spline", "M3 19C8 3 10 21 14 9S19 6 21 5"),
    SMART("Smart draw", "M3 12C3 6 9 3 12 3 M12 3L9 1 M12 3L9 6 M21 12a9 9 0 0 1-9 9"),
    RECT("Rectangle", "M4 6h16v12H4z"),
    RECT3("3-pt Rect", "M4 16L9 5L21 9L16 20z"),
    ELLIPSE("Ellipse", "M3 12a9 6 0 1 0 18 0a9 6 0 1 0-18 0"),
    ELLIPSE3("3-pt Ellipse", "M5 17a9 5-35 1 0 14-10a9 5-35 1 0-14 10z"),
    POLYGON("Polygon", "M12 3l8 6-3 10H7L4 9z"),
    STAR("Star", "M12 3l2.6 6 6.4.6-4.8 4.3 1.4 6.3L12 17l-5.6 3.2 1.4-6.3L3 9.6 9.4 9z"),
    SPIRAL("Spiral", "M12 12a1 1 0 0 1 2 0a2.5 2.5 0 0 1-5 0a4 4 0 0 1 8 0a5.5 5.5 0 0 1-11 0a7 7 0 0 1 14 0"),
    BASIC("Basic shapes", "M12 4l7 7-7 9-7-9z"),
    TEXT("Text", "M5 6V4h14v2 M12 4v16 M9 20h6"),
    EYE("Eyedropper", "M15 4l5 5-3 3-5-5z M12 7l-8 8v4h4l8-8"),
    FILL("Interactive fill", "M6 11L12 5L19 12L13 18z M19 15c1 2 2 3 2 4a2 2 0 0 1-4 0c0-1 1-2 2-4")
}

class VecCanvas(ctx: Context) : View(ctx) {
    companion object {
        const val M_NONE = 0; const val M_PAN = 1; const val M_MOVE = 2; const val M_SCALE = 3; const val M_ROT = 4
        const val M_MARQ = 5; const val M_NODE = 6; const val M_HIN = 7; const val M_HOUT = 8; const val M_SHAPE = 9
        const val M_SHAPE3 = 10; const val M_PEN = 11; const val M_FH = 12
    }

    var doc = Doc()
    var scale = 3.78f; var panX = 30f; var panY = 30f
    var tool = Tool.PICK
        set(v) { finishPen(); p3 = 0; field = v; if (v != Tool.NODE) selNode = -1; invalidate(); status(); onChange() }
    val sel = mutableListOf<VObj>()
    var selNode = -1
    var activeLayer = 0
    var snapGrid = true; var snapGuides = true; var snapObjs = false; var showGrid = true
    var lockRatio = true
    var polySides = 6; var starPts = 5; var basicIdx = 0
    val style = VObj().also { it.strokeW = 0.3f }
    var onChange: () -> Unit = {}
    var onStatus: (String) -> Unit = {}
    var onText: (VObj, Boolean) -> Unit = { _, _ -> }

    private val uStack = ArrayDeque<List<VObj>>(); private val rStack = ArrayDeque<List<VObj>>()
    private var mode = M_NONE
    private var lx = 0f; private var ly = 0f
    private var sx0 = 0f; private var sy0 = 0f; private var cx = 0f; private var cy = 0f
    private var ox0 = 0f; private var oy0 = 0f; private var pdx = 0f; private var pdy = 0f
    private var moved = false; private var gCk = false; private var multi = false; private var skipDown = false
    private var rawDx = 0f; private var rawDy = 0f; private var sb = RectF()
    private var anchorX = 0f; private var anchorY = 0f; private var corner = 0
    private var rotCx = 0f; private var rotCy = 0f
    private var pen: VObj? = null
    private val fh = ArrayList<PointF>()
    private var p3 = 0; private var b1x = 0f; private var b1y = 0f; private var b2x = 0f; private var b2y = 0f

    // ---------- paints ----------
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val shadowPaint = Paint().apply { color = 0x66000000 }
    private val gridPaint = Paint().apply { color = 0x26000000; style = Paint.Style.STROKE; strokeWidth = 0f }
    private val guidePaint = Paint().apply { color = 0xFF00BCD4.toInt(); style = Paint.Style.STROKE; strokeWidth = 0f }
    private val pv = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E5FF.toInt(); style = Paint.Style.STROKE }
    private val selP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5A93C.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) }
    private val hFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val hGold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5A93C.toInt() }
    private val hStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5A93C.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }

    // ---------- coordinate helpers ----------
    private fun docX(x: Float) = (x - panX) / scale
    private fun docY(y: Float) = (y - panY) / scale
    private fun scrX(x: Float) = x * scale + panX
    private fun scrY(y: Float) = y * scale + panY

    fun fit() {
        if (width == 0) return
        scale = min(width / doc.w, height / doc.h) * 0.88f
        panX = (width - doc.w * scale) / 2; panY = (height - doc.h * scale) / 2; invalidate(); status()
    }

    fun status(extra: String = "") {
        onStatus(extra + "  ${tool.label}  ${(scale / 3.7795f * 100).roundToInt()}%  ${doc.unit}")
    }

    fun changed() { invalidate(); onChange() }

    // ---------- history ----------
    fun checkpoint() { uStack.addLast(doc.objs.map { it.copy() }); if (uStack.size > 60) uStack.removeFirst(); rStack.clear() }
    private fun ensureCk() { if (!gCk) { checkpoint(); gCk = true } }
    fun undo() { if (uStack.isEmpty()) return; rStack.addLast(doc.objs.map { it.copy() }); doc.objs = uStack.removeLast().toMutableList(); sel.clear(); selNode = -1; changed() }
    fun redo() { if (rStack.isEmpty()) return; uStack.addLast(doc.objs.map { it.copy() }); doc.objs = rStack.removeLast().toMutableList(); sel.clear(); selNode = -1; changed() }
    fun newDoc(d: Doc) { doc = d; sel.clear(); selNode = -1; uStack.clear(); rStack.clear(); activeLayer = 0; fit(); changed() }

    // ---------- object creation ----------
    fun copyStyle(s: VObj, d: VObj) {
        d.fill = s.fill; d.fill2 = s.fill2; d.gradAngle = s.gradAngle; d.stroke = s.stroke; d.strokeW = s.strokeW
        d.cap = s.cap; d.join = s.join; d.dash = s.dash; d.arrow = s.arrow; d.alpha = s.alpha; d.shadow = s.shadow
    }
    private fun newObj() = VObj().also { copyStyle(style, it); it.layer = activeLayer.coerceIn(0, doc.layers.lastIndex) }
    private fun commitNew(o: VObj) { checkpoint(); doc.objs.add(o); sel.clear(); sel.add(o); selNode = -1; changed() }
    fun commitText(o: VObj, isNew: Boolean) { if (isNew) { if (o.text.isNotBlank()) commitNew(o) } else changed() }

    // ---------- snapping ----------
    fun snapPt(x: Float, y: Float): PointF {
        val tol = 10f / scale
        if (snapObjs) for (o in doc.objs) {
            if (o in sel || o.kind != Kind.PATH) continue
            for (n in o.nodes) if (hypot(n.x - x, n.y - y) < tol) return PointF(n.x, n.y)
        }
        var px = x; var py = y; var gx = false; var gy = false
        if (snapGuides) {
            doc.vg.forEach { if (abs(it - x) < tol) { px = it; gx = true } }
            doc.hg.forEach { if (abs(it - y) < tol) { py = it; gy = true } }
        }
        if (snapGrid && doc.grid > 0f) {
            if (!gx) px = (x / doc.grid).roundToInt() * doc.grid
            if (!gy) py = (y / doc.grid).roundToInt() * doc.grid
        }
        return PointF(px, py)
    }

    // ---------- hit testing ----------
    private fun hit(o: VObj, x: Float, y: Float, tol: Float): Boolean {
        val b = RectF(o.bounds()); b.inset(-tol, -tol); if (!b.contains(x, y)) return false
        if (o.kind != Kind.PATH) return true
        val pts = Geo.flatten(o.path(), 0.5f)
        if (o.fill != null && Geo.inPoly(pts, x, y)) return true
        val t = tol + o.strokeW / 2
        for (i in 0 until pts.size - 1) if (Geo.segDist(x, y, pts[i], pts[i + 1]) <= t) return true
        return false
    }
    private fun pickable(o: VObj): Boolean { val l = doc.layers.getOrNull(o.layer) ?: return false; return l.visible && !l.locked && o.visible && !o.locked }
    fun topHit(x: Float, y: Float): VObj? { for (i in doc.objs.indices.reversed()) { val o = doc.objs[i]; if (pickable(o) && hit(o, x, y, 8f / scale)) return o }; return null }
    fun selBounds(): RectF? { if (sel.isEmpty()) return null; val r = RectF(sel[0].bounds()); for (i in 1 until sel.size) r.union(sel[i].bounds()); return r }
    private fun nodeObj() = sel.firstOrNull { it.kind == Kind.PATH }

    // ---------- drawing ----------
    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF1B1D21.toInt())
        c.save(); c.translate(panX, panY); c.scale(scale, scale)
        c.drawRect(1.5f, 1.5f, doc.w + 1.5f, doc.h + 1.5f, shadowPaint)
        c.drawRect(0f, 0f, doc.w, doc.h, pagePaint)
        if (showGrid && doc.grid * scale >= 6f) {
            var x = 0f; while (x <= doc.w + 0.001f) { c.drawLine(x, 0f, x, doc.h, gridPaint); x += doc.grid }
            var y = 0f; while (y <= doc.h + 0.001f) { c.drawLine(0f, y, doc.w, y, gridPaint); y += doc.grid }
        }
        Render.drawAll(c, doc)
        doc.vg.forEach { c.drawLine(it, -5000f, it, 5000f, guidePaint) }
        doc.hg.forEach { c.drawLine(-5000f, it, 5000f, it, guidePaint) }
        pv.strokeWidth = 1.5f / scale; pv.pathEffect = DashPathEffect(floatArrayOf(6f / scale, 4f / scale), 0f)
        pen?.let { Render.drawObj(c, it, 1f) }
        preview()?.let { c.drawPath(it, pv) }
        c.restore()
        overlays(c)
    }

    private fun preview(): Path? = when {
        p3 == 1 -> build3(cx, cy)?.path()
        mode == M_FH && fh.size > 1 -> Path().apply { moveTo(fh[0].x, fh[0].y); for (i in 1 until fh.size) lineTo(fh[i].x, fh[i].y) }
        mode == M_SHAPE && (tool == Tool.CROP || tool == Tool.TEXT) -> Path().apply { addRect(min(sx0, cx), min(sy0, cy), max(sx0, cx), max(sy0, cy), Path.Direction.CW) }
        mode == M_SHAPE && (tool == Tool.FILL || tool == Tool.RECT3 || tool == Tool.ELLIPSE3) -> Path().apply { moveTo(sx0, sy0); lineTo(cx, cy) }
        mode == M_SHAPE -> buildShape()?.path()
        else -> null
    }

    private fun cornersScr(b: RectF) = floatArrayOf(scrX(b.left), scrY(b.top), scrX(b.right), scrY(b.top), scrX(b.right), scrY(b.bottom), scrX(b.left), scrY(b.bottom))

    private fun overlays(c: Canvas) {
        selBounds()?.let { b ->
            val k = cornersScr(b)
            c.drawRect(k[0], k[1], k[4], k[5], selP)
            if (tool == Tool.PICK) {
                for (i in 0 until 4) { c.drawRect(k[i * 2] - 9, k[i * 2 + 1] - 9, k[i * 2] + 9, k[i * 2 + 1] + 9, hFill); c.drawRect(k[i * 2] - 9, k[i * 2 + 1] - 9, k[i * 2] + 9, k[i * 2 + 1] + 9, hStroke) }
                val rx = (k[0] + k[2]) / 2; val ry = k[1] - 40
                c.drawLine(rx, k[1], rx, ry, selP); c.drawCircle(rx, ry, 10f, hFill); c.drawCircle(rx, ry, 10f, hStroke)
            }
        }
        if (tool == Tool.NODE) nodeObj()?.let { o ->
            for ((i, n) in o.nodes.withIndex()) {
                val x = scrX(n.x); val y = scrY(n.y)
                if (i == selNode) {
                    if (!n.inLine()) { c.drawLine(x, y, scrX(n.ix), scrY(n.iy), selP); c.drawCircle(scrX(n.ix), scrY(n.iy), 8f, hGold) }
                    if (!n.outLine()) { c.drawLine(x, y, scrX(n.ox), scrY(n.oy), selP); c.drawCircle(scrX(n.ox), scrY(n.oy), 8f, hGold) }
                    c.drawRect(x - 9, y - 9, x + 9, y + 9, hGold)
                } else { c.drawRect(x - 7, y - 7, x + 7, y + 7, hFill); c.drawRect(x - 7, y - 7, x + 7, y + 7, hStroke) }
            }
        }
        pen?.let { p -> for (n in p.nodes) { c.drawCircle(scrX(n.x), scrY(n.y), 7f, hGold) } }
        if (mode == M_MARQ) c.drawRect(scrX(min(ox0, pdx)), scrY(min(oy0, pdy)), scrX(max(ox0, pdx)), scrY(max(oy0, pdy)), selP)
    }

    // ---------- touch ----------
    private val sd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var lfx = 0f; private var lfy = 0f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { lfx = d.focusX; lfy = d.focusY; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(0.3f, 400f); val k = ns / scale
            panX = d.focusX - (lfx - panX) * k; panY = d.focusY - (lfy - panY) * k
            scale = ns; lfx = d.focusX; lfy = d.focusY; invalidate(); status(); return true
        }
    })
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (pen != null) { finishPen(); skipDown = true }
            else if (tool == Tool.PICK) topHit(docX(e.x), docY(e.y))?.takeIf { it.kind == Kind.TEXT }?.let { onText(it, false) }
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sd.onTouchEvent(e); gd.onTouchEvent(e)
        if (e.pointerCount > 1 || sd.isInProgress) { if (!multi) cancelGesture(); multi = true; return true }
        if (multi) { if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) multi = false; return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (skipDown) { skipDown = false; mode = M_NONE } else down(e.x, e.y) }
            MotionEvent.ACTION_MOVE -> move(e.x, e.y)
            MotionEvent.ACTION_UP -> up(e.x, e.y)
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    private fun cancelGesture() {
        if (gCk && uStack.isNotEmpty()) { doc.objs = uStack.removeLast().toMutableList(); sel.clear() }
        mode = M_NONE; gCk = false; fh.clear(); invalidate()
    }

    private fun down(x: Float, y: Float) {
        lx = x; ly = y; moved = false; gCk = false
        ox0 = docX(x); oy0 = docY(y); pdx = ox0; pdy = oy0
        val p = snapPt(ox0, oy0); sx0 = p.x; sy0 = p.y; cx = p.x; cy = p.y
        mode = M_NONE
        when (tool) {
            Tool.PAN -> mode = M_PAN
            Tool.PICK -> pickDown(x, y)
            Tool.NODE -> nodeDown(x, y)
            Tool.PEN, Tool.BEZIER, Tool.POLYLINE, Tool.BSPLINE -> penDown(p)
            Tool.FREEHAND, Tool.SMART -> { fh.clear(); fh.add(PointF(ox0, oy0)); mode = M_FH }
            Tool.RECT3, Tool.ELLIPSE3 -> mode = if (p3 == 1) M_SHAPE3 else M_SHAPE
            Tool.TEXT -> {
                val h = topHit(ox0, oy0)
                if (h != null && h.kind == Kind.TEXT) { sel.clear(); sel.add(h); changed(); onText(h, false) } else mode = M_SHAPE
            }
            Tool.EYE -> eyeDown()
            Tool.FILL -> { if (sel.isEmpty()) topHit(ox0, oy0)?.let { sel.add(it); changed() }; mode = M_SHAPE }
            else -> mode = M_SHAPE
        }
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        val rx = docX(x); val ry = docY(y); val p = snapPt(rx, ry)
        if (abs(x - lx) + abs(y - ly) > 3f) moved = true
        when (mode) {
            M_PAN -> { panX += x - lx; panY += y - ly }
            M_MOVE -> if (moved) {
                ensureCk(); rawDx += rx - pdx; rawDy += ry - pdy
                val b = selBounds()!!; val t = snapPt(sb.left + rawDx, sb.top + rawDy)
                transformSel(Matrix().apply { setTranslate(t.x - b.left, t.y - b.top) })
            }
            M_SCALE -> { ensureCk(); scaleSel(rx, ry) }
            M_ROT -> {
                ensureCk()
                val a0 = atan2(pdy - rotCy, pdx - rotCx); val a1 = atan2(ry - rotCy, rx - rotCx)
                transformSel(Matrix().apply { setRotate(Math.toDegrees((a1 - a0).toDouble()).toFloat(), rotCx, rotCy) })
            }
            M_NODE, M_HIN, M_HOUT -> nodeDrag(rx, ry, p)
            M_PEN -> penDrag(p)
            M_FH -> fh.add(PointF(rx, ry))
        }
        pdx = rx; pdy = ry; cx = p.x; cy = p.y; lx = x; ly = y
        invalidate(); status("%.1f, %.1f".format(rx, ry))
    }

    private fun up(x: Float, y: Float) {
        val rx = docX(x); val ry = docY(y); val p = snapPt(rx, ry); cx = p.x; cy = p.y; pdx = rx; pdy = ry
        when (mode) {
            M_MARQ -> {
                val r = RectF(min(ox0, rx), min(oy0, ry), max(ox0, rx), max(oy0, ry))
                sel.clear(); doc.objs.filter { pickable(it) && r.contains(it.bounds()) }.let { sel.addAll(it) }
                changed()
            }
            M_SHAPE -> shapeUp()
            M_SHAPE3 -> { build3(cx, cy)?.let { commitNew(it) }; p3 = 0 }
            M_FH -> finishFreehand()
        }
        mode = M_NONE; gCk = false; fh.clear(); invalidate(); onChange()
    }

    // ---------- Pick tool ----------
    private fun handleAt(b: RectF, x: Float, y: Float): Int {
        val k = cornersScr(b)
        for (i in 0 until 4) if (hypot(x - k[i * 2], y - k[i * 2 + 1]) < 36f) return i
        if (hypot(x - (k[0] + k[2]) / 2, y - (k[1] - 40)) < 36f) return 4
        return -1
    }

    private fun pickDown(x: Float, y: Float) {
        selBounds()?.let { b ->
            val h = handleAt(b, x, y)
            if (h >= 0) {
                if (h == 4) { mode = M_ROT; rotCx = b.centerX(); rotCy = b.centerY() }
                else {
                    mode = M_SCALE; corner = h
                    val opp = (h + 2) % 4; val k = floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom)
                    anchorX = k[opp * 2]; anchorY = k[opp * 2 + 1]
                }
                return
            }
        }
        val o = topHit(ox0, oy0)
        if (o != null) { if (o !in sel) { sel.clear(); sel.add(o) }; mode = M_MOVE; sb = selBounds()!!; rawDx = 0f; rawDy = 0f }
        else { sel.clear(); mode = M_MARQ }
        selNode = -1; onChange()
    }

    private fun transformSel(m: Matrix) { sel.forEach { it.transform(m) } }

    private fun scaleSel(rx: Float, ry: Float) {
        val dxP = pdx - anchorX; val dyP = pdy - anchorY
        var sx = if (abs(dxP) > 1e-3f) (rx - anchorX) / dxP else 1f
        var sy = if (abs(dyP) > 1e-3f) (ry - anchorY) / dyP else 1f
        if (lockRatio) {
            val dP = hypot(dxP, dyP); val dC = hypot(rx - anchorX, ry - anchorY)
            val f = if (dP > 1e-3f) dC / dP else 1f; sx = f; sy = f
        }
        sx = sx.coerceIn(0.02f, 50f); sy = sy.coerceIn(0.02f, 50f)
        transformSel(Matrix().apply { setScale(sx, sy, anchorX, anchorY) })
    }

    // ---------- Node tool ----------
    private fun nodeDown(x: Float, y: Float) {
        val o = nodeObj()
        if (o != null) {
            val tol = 24f
            if (selNode in o.nodes.indices) {
                val n = o.nodes[selNode]
                if (!n.inLine() && hypot(scrX(n.ix) - x, scrY(n.iy) - y) < tol) { mode = M_HIN; return }
                if (!n.outLine() && hypot(scrX(n.ox) - x, scrY(n.oy) - y) < tol) { mode = M_HOUT; return }
            }
            for ((i, n) in o.nodes.withIndex()) if (hypot(scrX(n.x) - x, scrY(n.y) - y) < tol) { selNode = i; mode = M_NODE; changed(); return }
        }
        val h = topHit(ox0, oy0)
        if (h != null && h.kind == Kind.PATH) { sel.clear(); sel.add(h); selNode = -1; changed() }
    }

    private fun nodeDrag(rx: Float, ry: Float, p: PointF) {
        val o = nodeObj() ?: return; if (selNode !in o.nodes.indices || !moved) return
        ensureCk(); val n = o.nodes[selNode]
        when (mode) {
            M_NODE -> n.move(p.x - n.x, p.y - n.y)
            M_HOUT -> { n.ox = rx; n.oy = ry; if (n.smooth && !n.inLine()) mirror(n, true) }
            M_HIN -> { n.ix = rx; n.iy = ry; if (n.smooth && !n.outLine()) mirror(n, false) }
        }
    }

    private fun mirror(n: Node, fromOut: Boolean) {
        val vx = if (fromOut) n.ox - n.x else n.ix - n.x; val vy = if (fromOut) n.oy - n.y else n.iy - n.y
        val l = hypot(vx, vy); if (l < 1e-4f) return
        val ol = if (fromOut) hypot(n.ix - n.x, n.iy - n.y) else hypot(n.ox - n.x, n.oy - n.y)
        val ux = -vx / l * ol; val uy = -vy / l * ol
        if (fromOut) { n.ix = n.x + ux; n.iy = n.y + uy } else { n.ox = n.x + ux; n.oy = n.y + uy }
    }

    private inline fun nodeOp(minNodes: Int = 2, block: (VObj, Int) -> Unit) {
        val o = nodeObj() ?: return
        if (o.nodes.size < minNodes) return
        checkpoint(); block(o, selNode); changed()
    }
    fun nodeAdd() = nodeOp { o, s ->
        val n = o.nodes.size; var i = if (s < 0) 0 else s
        if (!o.closed && i >= n - 1) i = n - 2
        val a = o.nodes[i]; val b = o.nodes[(i + 1) % n]
        o.nodes.add(i + 1, Geo.splitSeg(a, b, 0.5f)); selNode = i + 1
    }
    fun nodeDelete() = nodeOp(if ((nodeObj()?.closed) == true) 4 else 3) { o, s -> if (s in o.nodes.indices) { o.nodes.removeAt(s); selNode = -1 } }
    fun nodeSmooth() = nodeOp { o, s ->
        if (s !in o.nodes.indices) return@nodeOp
        val n = o.nodes[s]; val cnt = o.nodes.size
        val pv = o.nodes[(s - 1 + cnt) % cnt]; val nx = o.nodes[(s + 1) % cnt]
        val tx = nx.x - pv.x; val ty = nx.y - pv.y; val l = hypot(tx, ty); if (l == 0f) return@nodeOp
        val ux = tx / l; val uy = ty / l
        val lo = if (n.outLine()) hypot(nx.x - n.x, nx.y - n.y) / 3 else hypot(n.ox - n.x, n.oy - n.y)
        val li = if (n.inLine()) hypot(pv.x - n.x, pv.y - n.y) / 3 else hypot(n.ix - n.x, n.iy - n.y)
        n.ox = n.x + ux * lo; n.oy = n.y + uy * lo; n.ix = n.x - ux * li; n.iy = n.y - uy * li; n.smooth = true
    }
    fun nodeCusp() = nodeOp { o, s -> if (s in o.nodes.indices) o.nodes[s].smooth = false }
    fun nodeToLine() = nodeOp { o, s -> if (s in o.nodes.indices) o.nodes[s].let { it.ix = it.x; it.iy = it.y; it.ox = it.x; it.oy = it.y; it.smooth = false } }
    fun nodeToCurve() = nodeOp { o, s ->
        if (s !in o.nodes.indices) return@nodeOp
        val cnt = o.nodes.size; val n = o.nodes[s]; val pv = o.nodes[(s - 1 + cnt) % cnt]; val nx = o.nodes[(s + 1) % cnt]
        n.ox = n.x + (nx.x - n.x) / 3; n.oy = n.y + (nx.y - n.y) / 3; n.ix = n.x + (pv.x - n.x) / 3; n.iy = n.y + (pv.y - n.y) / 3
    }
    fun nodeBreak() {
        val o = nodeObj() ?: return; val s = selNode; if (s !in o.nodes.indices) return
        checkpoint()
        if (o.closed) {
            val rot = o.nodes.subList(s, o.nodes.size) + o.nodes.subList(0, s)
            o.nodes = (rot.map { it.copy() } + rot[0].copy()).toMutableList(); o.closed = false
        } else if (s > 0 && s < o.nodes.size - 1) {
            val second = o.copy(); second.nodes = o.nodes.subList(s, o.nodes.size).map { it.copy() }.toMutableList()
            o.nodes = o.nodes.subList(0, s + 1).map { it.copy() }.toMutableList()
            doc.objs.add(doc.objs.indexOf(o) + 1, second)
        }
        selNode = -1; changed()
    }
    fun nodeJoin() { val o = nodeObj() ?: return; if (o.closed || o.nodes.size < 3) return; checkpoint(); o.closed = true; if (o.fill == null) o.fill = style.fill; changed() }

    // ---------- Pen / Bezier / Polyline / B-spline ----------
    private fun penDown(p: PointF) {
        val pn = pen ?: newObj().also { it.closed = false; it.fill = null; pen = it }
        val f = pn.nodes.firstOrNull()
        if (f != null && pn.nodes.size > 2 && hypot(p.x - f.x, p.y - f.y) < 12f / scale) { pn.closed = true; finishPen(); mode = M_NONE; return }
        pn.nodes.add(Node(p.x, p.y)); mode = M_PEN
    }
    private fun penDrag(p: PointF) {
        if (tool != Tool.PEN && tool != Tool.BEZIER) return
        val n = pen?.nodes?.lastOrNull() ?: return; if (!moved) return
        n.ox = p.x; n.oy = p.y; n.ix = 2 * n.x - p.x; n.iy = 2 * n.y - p.y; n.smooth = true
    }
    fun finishPen() {
        val pn = pen ?: return; pen = null
        if (pn.nodes.size >= 2) {
            val a = pn.nodes[pn.nodes.size - 1]; val b = pn.nodes[pn.nodes.size - 2]
            if (hypot(a.x - b.x, a.y - b.y) < 0.01f) pn.nodes.removeAt(pn.nodes.size - 1)
        }
        if (pn.nodes.size >= 2) {
            if (tool == Tool.BSPLINE) pn.nodes = Geo.smooth(pn.nodes.map { PointF(it.x, it.y) }, pn.closed)
            if (pn.closed) pn.fill = style.fill
            commitNew(pn)
        }
        invalidate()
    }

    // ---------- Freehand / Smart drawing ----------
    private fun finishFreehand() {
        if (fh.size < 3) return
        if (tool == Tool.SMART) recognize(fh)?.let { commitNew(it); return }
        val pts = Geo.rdp(fh, 2f / scale); if (pts.size < 2) return
        val o = newObj(); o.closed = false; o.fill = null; o.nodes = Geo.smooth(pts, false); commitNew(o)
    }

    private fun recognize(pts: List<PointF>): VObj? {
        val a = pts.first(); val z = pts.last()
        var l = pts[0].x; var t = pts[0].y; var r = l; var b = t
        pts.forEach { l = min(l, it.x); r = max(r, it.x); t = min(t, it.y); b = max(b, it.y) }
        val w = r - l; val h = b - t; val diag = hypot(w, h); if (diag < 1f) return null
        val o = newObj()
        if (hypot(a.x - z.x, a.y - z.y) > 0.2f * diag) {
            val dev = pts.maxOf { Geo.segDist(it.x, it.y, a, z) }
            o.closed = false; o.fill = null
            o.nodes = if (dev < 0.05f * hypot(z.x - a.x, z.y - a.y)) mutableListOf(Node(a.x, a.y), Node(z.x, z.y)) else Geo.smooth(Geo.rdp(pts, 2f / scale), false)
            return o
        }
        val ccx = (l + r) / 2; val ccy = (t + b) / 2
        if (w > 1f && h > 1f) {
            val ellErr = pts.map { abs(hypot((it.x - ccx) / (w / 2), (it.y - ccy) / (h / 2)) - 1f) }.average()
            if (ellErr < 0.12) { o.closed = true; o.nodes = Geo.ellipse(ccx, ccy, w / 2, 0f, 0f, h / 2); return o }
            val rectErr = pts.map { minOf(abs(it.x - l), abs(it.x - r), abs(it.y - t), abs(it.y - b)) }.average()
            if (rectErr < 0.04 * diag) { o.closed = true; o.nodes = Geo.rect(l, t, r, b); return o }
        }
        o.closed = true; o.nodes = Geo.smooth(Geo.rdp(pts, 2f / scale), true); return o
    }

    // ---------- Shapes ----------
    private fun buildShape(): VObj? {
        val x0 = sx0; val y0 = sy0; val x1 = cx; val y1 = cy
        if (abs(x1 - x0) < 0.3f && abs(y1 - y0) < 0.3f) return null
        val o = newObj(); o.closed = true
        val r = hypot(x1 - x0, y1 - y0); val ang = atan2(y1 - y0, x1 - x0)
        when (tool) {
            Tool.RECT -> o.nodes = Geo.rect(x0, y0, x1, y1)
            Tool.ELLIPSE -> o.nodes = Geo.ellipse((x0 + x1) / 2, (y0 + y1) / 2, (x1 - x0) / 2, 0f, 0f, (y1 - y0) / 2)
            Tool.POLYGON -> o.nodes = Geo.polygon(x0, y0, r, ang, polySides)
            Tool.STAR -> o.nodes = Geo.star(x0, y0, r, ang, starPts)
            Tool.SPIRAL -> { o.closed = false; o.fill = null; o.nodes = Geo.spiral(x0, y0, r, ang) }
            Tool.BASIC -> o.nodes = Geo.basic(basicIdx, x0, y0, x1, y1)
            else -> return null
        }
        return o
    }

    private fun build3(px: Float, py: Float): VObj? {
        val ux = b2x - b1x; val uy = b2y - b1y; val l = hypot(ux, uy); if (l < 0.3f) return null
        val nx = -uy / l; val ny = ux / l; val h = (px - b1x) * nx + (py - b1y) * ny
        val o = newObj(); o.closed = true
        o.nodes = if (tool == Tool.RECT3) mutableListOf(Node(b1x, b1y), Node(b2x, b2y), Node(b2x + nx * h, b2y + ny * h), Node(b1x + nx * h, b1y + ny * h))
        else Geo.ellipse((b1x + b2x) / 2, (b1y + b2y) / 2, ux / 2, uy / 2, nx * h, ny * h)
        return o
    }

    private fun shapeUp() {
        val dist = hypot(cx - sx0, cy - sy0)
        when (tool) {
            Tool.RECT3, Tool.ELLIPSE3 -> if (dist > 1f) { b1x = sx0; b1y = sy0; b2x = cx; b2y = cy; p3 = 1 }
            Tool.TEXT -> {
                val o = newObj(); o.kind = Kind.TEXT; o.fill = Color.BLACK; o.size = 10f; o.stroke = null
                if (abs(cx - sx0) > 5f) { o.tx = min(sx0, cx); o.ty = min(sy0, cy) + o.size; o.frameW = abs(cx - sx0) } else { o.tx = sx0; o.ty = sy0 }
                onText(o, true)
            }
            Tool.CROP -> cropTo(RectF(min(sx0, cx), min(sy0, cy), max(sx0, cx), max(sy0, cy)))
            Tool.FILL -> if (sel.isNotEmpty()) {
                checkpoint()
                if (dist > 2f) { val ang = Math.toDegrees(atan2((cy - sy0).toDouble(), (cx - sx0).toDouble())).toFloat(); sel.forEach { if (it.fill2 == null) it.fill2 = Color.WHITE; it.gradAngle = ang } }
                else sel.forEach { it.fill2 = null }
                changed()
            }
            else -> buildShape()?.let { commitNew(it) }
        }
    }

    // ---------- Crop (boolean intersect; result is flattened to polyline nodes) ----------
    private fun cropTo(r: RectF) {
        if (r.width() < 1f || r.height() < 1f) return
        val targets = (if (sel.isEmpty()) doc.objs.filter { pickable(it) } else sel.toList()).filter { it.kind == Kind.PATH && it.closed }
        if (targets.isEmpty()) return
        checkpoint(); val clip = Path().apply { addRect(r, Path.Direction.CW) }; val newSel = mutableListOf<VObj>()
        for (o in targets) {
            val res = Path(); val ok = res.op(o.path(), clip, Path.Op.INTERSECT)
            val idx = doc.objs.indexOf(o); doc.objs.remove(o)
            if (!ok || res.isEmpty) continue
            val pm = PathMeasure(res, false); var at = idx
            do {
                val len = pm.length; if (len <= 0f) continue
                val n = max(8, (len / 0.5f).toInt().coerceAtMost(800)); val pos = FloatArray(2)
                val piece = o.copy(); piece.closed = pm.isClosed
                piece.nodes = MutableList(n) { i -> pm.getPosTan(len * i / n, pos, null); Node(pos[0], pos[1]) }
                doc.objs.add(at++, piece); newSel.add(piece)
            } while (pm.nextContour())
        }
        sel.clear(); sel.addAll(newSel); changed()
    }

    // ---------- Eyedropper ----------
    private fun eyeDown() {
        val o = topHit(ox0, oy0) ?: return
        if (sel.isNotEmpty()) checkpoint()
        if (o.kind == Kind.IMAGE) {
            val b = o.bmp ?: return
            val u = ((ox0 - o.rect.left) / o.rect.width() * b.width).toInt().coerceIn(0, b.width - 1)
            val v = ((oy0 - o.rect.top) / o.rect.height() * b.height).toInt().coerceIn(0, b.height - 1)
            val col = b.getPixel(u, v) or (0xFF shl 24); style.fill = col; sel.forEach { it.fill = col }
        } else { copyStyle(o, style); sel.forEach { if (it !== o) copyStyle(style, it) } }
        changed()
    }

    // ---------- public editing API ----------
    fun editSel(ck: Boolean = true, block: (VObj) -> Unit) { if (sel.isNotEmpty()) { if (ck) checkpoint(); sel.forEach(block) }; changed() }
    fun setFill(c: Int?) { style.fill = c; editSel { it.fill = c } }
    fun setFill2(c: Int?, angle: Float? = null) { style.fill2 = c; editSel { it.fill2 = c; if (angle != null) it.gradAngle = angle } }
    fun setStroke(c: Int?) { style.stroke = c; editSel { it.stroke = c } }
    fun setStrokeW(w: Float) { style.strokeW = w; editSel { it.strokeW = w } }
    fun deleteSel() { if (sel.isEmpty()) return; checkpoint(); doc.objs.removeAll(sel.toSet()); sel.clear(); selNode = -1; changed() }
    fun duplicate() { if (sel.isEmpty()) return; checkpoint(); val c = sel.map { it.copy().also { d -> d.transform(Matrix().apply { setTranslate(5f, 5f) }) } }; doc.objs.addAll(c); sel.clear(); sel.addAll(c); changed() }
    fun selectAll() { sel.clear(); sel.addAll(doc.objs.filter { pickable(it) }); changed() }
    fun matrixSel(build: (RectF) -> Matrix) { val b = selBounds() ?: return; checkpoint(); transformSel(build(b)); changed() }
    fun flipH() = matrixSel { Matrix().apply { setScale(-1f, 1f, it.centerX(), it.centerY()) } }
    fun flipV() = matrixSel { Matrix().apply { setScale(1f, -1f, it.centerX(), it.centerY()) } }
    fun rotate(deg: Float) = matrixSel { Matrix().apply { setRotate(deg, it.centerX(), it.centerY()) } }
    fun skew(axDeg: Float, ayDeg: Float) = matrixSel { Matrix().apply { setSkew(tan(Math.toRadians(axDeg.toDouble())).toFloat(), tan(Math.toRadians(ayDeg.toDouble())).toFloat(), it.centerX(), it.centerY()) } }
    fun setBounds(x: Float, y: Float, w: Float, h: Float) {
        val b = selBounds() ?: return; if (w <= 0f || h <= 0f) return
        checkpoint(); transformSel(Matrix().apply { setRectToRect(b, RectF(x, y, x + w, y + h), Matrix.ScaleToFit.FILL) }); changed()
    }
    fun order(front: Boolean, step: Boolean) {
        if (sel.isEmpty()) return; checkpoint()
        val items = doc.objs.filter { it in sel }
        if (!step) { doc.objs.removeAll(items.toSet()); if (front) doc.objs.addAll(items) else doc.objs.addAll(0, items) }
        else if (front) { for (o in items.reversed()) { val i = doc.objs.indexOf(o); if (i < doc.objs.size - 1 && doc.objs[i + 1] !in sel) java.util.Collections.swap(doc.objs, i, i + 1) } }
        else { for (o in items) { val i = doc.objs.indexOf(o); if (i > 0 && doc.objs[i - 1] !in sel) java.util.Collections.swap(doc.objs, i, i - 1) } }
        changed()
    }

    fun attachTextToPath() {
        val t = sel.firstOrNull { it.kind == Kind.TEXT }; val p = sel.firstOrNull { it.kind == Kind.PATH } ?: return
        if (t == null) return
        checkpoint(); t.onPath = true; t.nodes = p.nodes.map { it.copy() }.toMutableList(); t.closed = p.closed; changed()
    }
    fun detachText() { sel.filter { it.kind == Kind.TEXT && it.onPath }.let { if (it.isNotEmpty()) { checkpoint(); it.forEach { t -> t.onPath = false; t.nodes.clear() }; changed() } } }

    fun importImage(bmp: Bitmap) {
        checkpoint()
        doc.layers.add(Layer("Reference", true, true, 0.5f)); val ri = doc.layers.lastIndex
        doc.layers.add(Layer("Trace")); activeLayer = doc.layers.lastIndex
        val o = VObj(Kind.IMAGE); o.bmp = bmp; o.layer = ri
        val s = min(doc.w / bmp.width, doc.h / bmp.height) * 0.9f; val w = bmp.width * s; val h = bmp.height * s
        o.rect = RectF((doc.w - w) / 2, (doc.h - h) / 2, (doc.w + w) / 2, (doc.h + h) / 2)
        doc.objs.add(0, o); sel.clear(); changed()
    }

    // ---------- effects ----------
    private fun lerpColor(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(), (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /** Approximate contour: concentric copies scaled about the bounding-box centre (not a true path offset). */
    fun contour(dist: Float, steps: Int, outward: Boolean) {
        val o = sel.firstOrNull { it.kind == Kind.PATH && it.closed } ?: return
        checkpoint(); val b = o.bounds(); val half = max(min(b.width(), b.height()) / 2f, 0.5f)
        val at = doc.objs.indexOf(o); val made = mutableListOf<VObj>()
        for (i in 1..steps) {
            val s = ((half + (if (outward) 1 else -1) * dist * i) / half).coerceAtLeast(0.03f)
            val c = o.copy(); c.shadow = false; c.transform(Matrix().apply { setScale(s, s, b.centerX(), b.centerY()) })
            val t = i.toFloat() / (steps + 1)
            c.fill = o.fill?.let { lerpColor(it, Color.WHITE, t * 0.7f) }; made.add(c)
        }
        if (outward) doc.objs.addAll(at, made.reversed()) else doc.objs.addAll(at + 1, made)
        changed()
    }

    /** Blend between the first two selected paths: resampled, interpolated intermediate shapes. */
    fun blend(steps: Int) {
        val ps = sel.filter { it.kind == Kind.PATH && it.nodes.size >= 2 }; if (ps.size < 2) return
        val a = ps[0]; val b = ps[1]; checkpoint()
        val n = 64; val pa = Geo.flatten(a.path(), 1f); val pb = Geo.flatten(b.path(), 1f)
        if (pa.size < 2 || pb.size < 2) return
        fun at(l: List<PointF>, i: Int): PointF = l[(i * (l.size - 1)) / (n - 1)]
        val both = a.closed && b.closed; val ins = doc.objs.indexOf(a) + 1; val made = mutableListOf<VObj>()
        for (k in 1..steps) {
            val t = k.toFloat() / (steps + 1)
            val pts = List(n) { i -> val p = at(pa, i); val q = at(pb, i); PointF(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t) }
            val o = a.copy(); o.shadow = false; o.closed = both
            o.nodes = Geo.smooth(Geo.rdp(pts, 0.05f).let { if (it.size < 3) pts else it }, both)
            val fa = a.fill; val fb = b.fill; o.fill = if (fa != null && fb != null) lerpColor(fa, fb, t) else fa
            val sa = a.stroke; val sb2 = b.stroke; o.stroke = if (sa != null && sb2 != null) lerpColor(sa, sb2, t) else sa
            o.strokeW = a.strokeW + (b.strokeW - a.strokeW) * t; made.add(o)
        }
        doc.objs.addAll(ins, made); changed()
    }

    /** Oblique (parallel-projection) extrude: stacked offset copies behind the object, shaded toward black. */
    fun extrude(depth: Float, angleDeg: Float, steps: Int) {
        val o = sel.firstOrNull { it.kind == Kind.PATH } ?: return; checkpoint()
        val at = doc.objs.indexOf(o); val a = Math.toRadians(angleDeg.toDouble()); val made = mutableListOf<VObj>()
        for (i in steps downTo 1) {
            val f = i.toFloat() / steps
            val c = o.copy(); c.shadow = false
            c.transform(Matrix().apply { setTranslate((cos(a) * depth * f).toFloat(), (sin(a) * depth * f).toFloat()) })
            val shade = 0.25f + 0.35f * f
            c.fill = o.fill?.let { lerpColor(it, Color.BLACK, shade) }; c.fill2 = null
            c.stroke = o.stroke?.let { lerpColor(it, Color.BLACK, shade) }; made.add(c)
        }
        doc.objs.addAll(at, made); changed()
    }
}
