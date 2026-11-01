package com.topz.vecdraw

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.util.Base64
import java.io.OutputStream
import java.util.Locale
import kotlin.math.*

object Render {
    val CAPS = arrayOf(Paint.Cap.ROUND, Paint.Cap.SQUARE, Paint.Cap.BUTT)
    val JOINS = arrayOf(Paint.Join.ROUND, Paint.Join.BEVEL, Paint.Join.MITER)

    fun dashArr(o: VObj): FloatArray? {
        val w = max(o.strokeW, 0.2f)
        return when (o.dash) { 1 -> floatArrayOf(4 * w, 2 * w); 2 -> floatArrayOf(w, 2 * w); 3 -> floatArrayOf(4 * w, 2 * w, w, 2 * w); else -> null }
    }

    fun gradPts(o: VObj): FloatArray = linePts(o.bounds(), o.gradAngle)

    fun linePts(b: RectF, angle: Float): FloatArray {
        val r = max(b.width(), b.height()) / 2f; val a = Math.toRadians(angle.toDouble())
        val dx = (cos(a) * r).toFloat(); val dy = (sin(a) * r).toFloat()
        return floatArrayOf(b.centerX() - dx, b.centerY() - dy, b.centerX() + dx, b.centerY() + dy)
    }

    private fun a255(o: VObj, la: Float) = (255 * o.alpha * la).toInt().coerceIn(0, 255)

    fun fillPaint(o: VObj, la: Float): Paint? {
        val f = o.fill ?: return null
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = f
            val f2 = o.fill2
            if (f2 != null) { val g = gradPts(o); shader = LinearGradient(g[0], g[1], g[2], g[3], f, f2, Shader.TileMode.CLAMP) }
            alpha = a255(o, la)
        }
    }

    fun strokePaint(o: VObj, la: Float): Paint? {
        val s = o.stroke ?: return null
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = s; strokeWidth = o.strokeW
            strokeCap = CAPS[o.cap]; strokeJoin = JOINS[o.join]; strokeMiter = 4f
            dashArr(o)?.let { pathEffect = DashPathEffect(it, 0f) }
            alpha = a255(o, la)
        }
    }

    /** Arrowhead triangle (tip, left, right) at the end of an open path. */
    fun arrowPts(o: VObj): FloatArray? {
        if (!o.arrow || o.closed || o.nodes.size < 2) return null
        val e = o.nodes.last(); val pv = o.nodes[o.nodes.size - 2]
        val tailX: Float; val tailY: Float
        if (!e.inLine()) { tailX = e.ix; tailY = e.iy } else if (!pv.outLine()) { tailX = pv.ox; tailY = pv.oy } else { tailX = pv.x; tailY = pv.y }
        var dx = e.x - tailX; var dy = e.y - tailY; val l = hypot(dx, dy); if (l < 1e-4f) return null
        dx /= l; dy /= l
        val s = 3f + o.strokeW * 3f; val bx = e.x - dx * s; val by = e.y - dy * s; val nx = -dy; val ny = dx
        return floatArrayOf(e.x, e.y, bx + nx * s * .45f, by + ny * s * .45f, bx - nx * s * .45f, by - ny * s * .45f)
    }

    const val MESH_SUB = 8   // flat sub-cells per mesh cell, per axis

    class MeshCell(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val color: Int)

    fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())

    /** Bilinear colour at normalised (u,v) of an r x c vertex grid. */
    fun meshAt(m: IntArray, r: Int, c: Int, u: Float, v: Float): Int {
        val fx = u.coerceIn(0f, 1f) * (c - 1); val fy = v.coerceIn(0f, 1f) * (r - 1)
        val j = min(fx.toInt(), c - 2); val i = min(fy.toInt(), r - 2); val tx = fx - j; val ty = fy - i
        return mix(mix(m[i * c + j], m[i * c + j + 1], tx), mix(m[(i + 1) * c + j], m[(i + 1) * c + j + 1], tx), ty)
    }

    /** Flat-coloured sub-cells approximating the smooth mesh: stays 100% vector in screen, SVG and PDF. */
    fun meshCells(o: VObj): List<MeshCell> {
        val m = o.mesh ?: return emptyList(); val r = o.meshR; val c = o.meshC
        if (r < 2 || c < 2 || m.size < r * c) return emptyList()
        val b = o.bounds(); val nx = (c - 1) * MESH_SUB; val ny = (r - 1) * MESH_SUB; val out = ArrayList<MeshCell>(nx * ny)
        for (iy in 0 until ny) for (ix in 0 until nx) {
            val u0 = ix / nx.toFloat(); val u1 = (ix + 1) / nx.toFloat(); val v0 = iy / ny.toFloat(); val v1 = (iy + 1) / ny.toFloat()
            out.add(MeshCell(b.left + b.width() * u0, b.top + b.height() * v0, b.left + b.width() * u1, b.top + b.height() * v1, meshAt(m, r, c, (u0 + u1) / 2, (v0 + v1) / 2)))
        }
        return out
    }

    private fun drawMesh(c: Canvas, o: VObj, la: Float, p: Path) {
        val cells = meshCells(o); if (cells.isEmpty()) return
        val a = a255(o, la)
        val saved = if (a < 255) c.saveLayerAlpha(null, a) else c.save()
        c.clipPath(p)
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 0.15f }   // stroke hides seams
        for (cell in cells) { pt.color = cell.color; c.drawRect(cell.x0, cell.y0, cell.x1, cell.y1, pt) }
        c.restoreToCount(saved)
    }

    /** PDF export sets this so masked objects are baked into 300-dpi bitmaps (the PDF backend can't do DST_IN masking). */
    var rasterMasks = false

    private val LUM_TO_ALPHA = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.299f, 0.587f, 0.114f, 0f, 0f)))

    private fun maskedLayer(c: Canvas, o: VObj, la: Float, b: RectF) {
        val saved = c.saveLayer(b, null)
        drawBase(c, o, la)
        val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN); isFilterBitmap = true }
        if (o.tMode == 1) {
            val g = linePts(o.bounds(), o.tAngle)
            mp.shader = LinearGradient(g[0], g[1], g[2], g[3],
                Color.argb((o.tA1.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0), Color.argb((o.tA2.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0), Shader.TileMode.CLAMP)
        } else {
            val bmp = Pat.baked(o); val ob = o.bounds(); val s = o.tTile / bmp.width
            mp.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { setLocalMatrix(Matrix().apply { setScale(s, s); postTranslate(ob.left, ob.top) }) }
            mp.colorFilter = LUM_TO_ALPHA
        }
        c.drawRect(b, mp)
        c.restoreToCount(saved)
    }

    private fun drawMasked(c: Canvas, o: VObj, la: Float) {
        val b = RectF(o.bounds()); val pad = o.strokeW + 3f; b.inset(-pad, -pad)
        if (!rasterMasks) { maskedLayer(c, o, la, b); return }
        val k = 300f / 25.4f
        val w = (b.width() * k).toInt().coerceIn(1, 4000); val h = (b.height() * k).toInt().coerceIn(1, 4000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val bc = Canvas(bmp)
        bc.scale(w / b.width(), h / b.height()); bc.translate(-b.left, -b.top)
        maskedLayer(bc, o, la, b)
        c.drawBitmap(bmp, null, b, Paint(Paint.FILTER_BITMAP_FLAG)); bmp.recycle()
    }

    fun drawObj(c: Canvas, o: VObj, la: Float) { if (o.tMode != 0) drawMasked(c, o, la) else drawBase(c, o, la) }

    private fun drawBase(c: Canvas, o: VObj, la: Float) {
        when (o.kind) {
            Kind.PATH -> {
                val p = o.path()
                if (o.shadow) {
                    c.save(); c.translate(1.5f, 1.5f)
                    val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK; alpha = (70f * o.alpha * la).toInt().coerceIn(0, 255)
                        if (o.fill != null) style = Paint.Style.FILL else { style = Paint.Style.STROKE; strokeWidth = o.strokeW }
                    }
                    c.drawPath(p, sp); c.restore()
                }
                if (o.mesh != null && o.closed) drawMesh(c, o, la, p) else fillPaint(o, la)?.let { c.drawPath(p, it) }
                strokePaint(o, la)?.let { c.drawPath(p, it) }
                arrowPts(o)?.let { a ->
                    val ap = Path().apply { moveTo(a[0], a[1]); lineTo(a[2], a[3]); lineTo(a[4], a[5]); close() }
                    c.drawPath(ap, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = o.stroke ?: Color.BLACK; alpha = a255(o, la) })
                }
            }
            Kind.TEXT -> {
                val pt = o.textPaint().apply { color = o.fill ?: Color.BLACK; alpha = a255(o, la) }
                if (o.onPath) c.drawTextOnPath(o.text.replace("\n", " "), o.path(), 0f, 0f, pt)
                else { val x = o.textX(o.textW()); o.lines().forEachIndexed { i, s -> c.drawText(s, x, o.ty + i * o.size * o.leading, pt) } }
            }
            Kind.IMAGE -> o.bmp?.let { c.drawBitmap(it, null, o.rect, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = a255(o, la) }) }
        }
    }

    fun drawAll(c: Canvas, doc: Doc) {
        for (o in doc.objs) {
            val l = doc.layers.getOrNull(o.layer) ?: continue
            if (l.visible && o.visible) drawObj(c, o, l.opacity)
        }
    }
}

object Export {
    private fun f(v: Float): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun pathData(o: VObj): String {
        val n = o.nodes; if (n.isEmpty()) return ""
        val sb = StringBuilder("M${f(n[0].x)} ${f(n[0].y)}")
        for (i in 0 until (if (o.closed) n.size else n.size - 1)) {
            val a = n[i]; val b = n[(i + 1) % n.size]
            if (a.outLine() && b.inLine()) sb.append("L${f(b.x)} ${f(b.y)}")
            else sb.append("C${f(a.ox)} ${f(a.oy)} ${f(b.ix)} ${f(b.iy)} ${f(b.x)} ${f(b.y)}")
        }
        if (o.closed) sb.append("Z")
        return sb.toString()
    }

    private fun strokeAttrs(o: VObj, op: Float): String {
        val s = o.stroke ?: return " stroke=\"none\""
        val sb = StringBuilder(" stroke=\"${hex(s)}\" stroke-width=\"${f(o.strokeW)}\"")
        sb.append(" stroke-linecap=\"${arrayOf("round", "square", "butt")[o.cap]}\" stroke-linejoin=\"${arrayOf("round", "bevel", "miter")[o.join]}\"")
        Render.dashArr(o)?.let { d -> sb.append(" stroke-dasharray=\"${d.joinToString(" ") { f(it) }}\"") }
        if (op < 0.999f) sb.append(" opacity=\"${f(op)}\"")
        return sb.toString()
    }

    private fun meshSvg(o: VObj, d: String, id: Int, op: Float): String {
        val sb = StringBuilder("<defs><clipPath id=\"mc$id\"><path d=\"$d\"/></clipPath></defs>\n<g clip-path=\"url(#mc$id)\"${if (op < 0.999f) " opacity=\"${f(op)}\"" else ""}>\n")
        for (c in Render.meshCells(o)) {
            val h = hex(c.color)
            sb.append("<rect x=\"${f(c.x0)}\" y=\"${f(c.y0)}\" width=\"${f(c.x1 - c.x0)}\" height=\"${f(c.y1 - c.y0)}\" fill=\"$h\" stroke=\"$h\" stroke-width=\"0.15\"/>\n")
        }
        return sb.append("</g>\n").toString()
    }

    private fun obj(o: VObj, la: Float, id: Int): String {
        val sb = StringBuilder(); val op = o.alpha * la
        when (o.kind) {
            Kind.PATH -> {
                val d = pathData(o)
                if (o.shadow) sb.append("<path d=\"$d\" transform=\"translate(1.5 1.5)\" fill=\"${if (o.fill != null) "#000000" else "none"}\" stroke=\"${if (o.stroke != null) "#000000" else "none"}\" stroke-width=\"${f(o.strokeW)}\" opacity=\"${f(0.27f * op)}\"/>\n")
                var fillRef = "none"
                val f1 = o.fill; val f2 = o.fill2
                if (o.mesh != null && o.closed) sb.append(meshSvg(o, d, id, op))
                else if (f1 != null && f2 != null) {
                    val g = Render.gradPts(o)
                    sb.append("<defs><linearGradient id=\"g$id\" gradientUnits=\"userSpaceOnUse\" x1=\"${f(g[0])}\" y1=\"${f(g[1])}\" x2=\"${f(g[2])}\" y2=\"${f(g[3])}\"><stop offset=\"0\" stop-color=\"${hex(f1)}\"/><stop offset=\"1\" stop-color=\"${hex(f2)}\"/></linearGradient></defs>\n")
                    fillRef = "url(#g$id)"
                } else if (f1 != null) fillRef = hex(f1)
                val fo = if (f1 != null && op < 0.999f && o.stroke == null) " opacity=\"${f(op)}\"" else ""
                sb.append("<path d=\"$d\" fill=\"$fillRef\"${strokeAttrs(o, op)}$fo/>\n")
                Render.arrowPts(o)?.let { a -> sb.append("<path d=\"M${f(a[0])} ${f(a[1])}L${f(a[2])} ${f(a[3])}L${f(a[4])} ${f(a[5])}Z\" fill=\"${hex(o.stroke ?: 0)}\"/>\n") }
            }
            Kind.TEXT -> {
                val common = "font-family=\"sans-serif\" font-size=\"${f(o.size)}\" fill=\"${hex(o.fill ?: 0)}\"" +
                    (if (o.bold) " font-weight=\"bold\"" else "") + (if (o.italic) " font-style=\"italic\"" else "") +
                    (if (o.spacing != 0f) " letter-spacing=\"${f(o.spacing * o.size)}\"" else "") + (if (op < 0.999f) " opacity=\"${f(op)}\"" else "")
                if (o.onPath) {
                    sb.append("<defs><path id=\"tp$id\" d=\"${pathData(o)}\"/></defs>\n<text $common><textPath xlink:href=\"#tp$id\" href=\"#tp$id\">${esc(o.text.replace("\n", " "))}</textPath></text>\n")
                } else {
                    val anchor = arrayOf("start", "middle", "end")[o.align]; val x = o.textX(o.textW())
                    o.lines().forEachIndexed { i, s -> sb.append("<text x=\"${f(x)}\" y=\"${f(o.ty + i * o.size * o.leading)}\" text-anchor=\"$anchor\" xml:space=\"preserve\" $common>${esc(s)}</text>\n") }
                }
            }
            Kind.IMAGE -> o.bmp?.let { b ->
                val data = Base64.encodeToString(Png.bytes(b), Base64.NO_WRAP)
                sb.append("<image x=\"${f(o.rect.left)}\" y=\"${f(o.rect.top)}\" width=\"${f(o.rect.width())}\" height=\"${f(o.rect.height())}\" preserveAspectRatio=\"none\" opacity=\"${f(op)}\" xlink:href=\"data:image/png;base64,$data\"/>\n")
            }
        }
        if (o.tMode == 0) return sb.toString()
        return maskSvg(o, id) + "<g mask=\"url(#tm$id)\">\n" + sb + "</g>\n"
    }

    /** SVG luminance mask equivalent of the transparency fountain / bitmap pattern. */
    private fun maskSvg(o: VObj, id: Int): String {
        val ob = o.bounds(); val b = RectF(ob); val pad = o.strokeW + 3f; b.inset(-pad, -pad)
        val sb = StringBuilder("<defs>")
        fun gray(a: Float) = (a.coerceIn(0f, 1f) * 255).toInt()
        val fillRef: String
        if (o.tMode == 1) {
            val g = Render.linePts(ob, o.tAngle)
            sb.append("<linearGradient id=\"tg$id\" gradientUnits=\"userSpaceOnUse\" x1=\"${f(g[0])}\" y1=\"${f(g[1])}\" x2=\"${f(g[2])}\" y2=\"${f(g[3])}\">")
            sb.append("<stop offset=\"0\" stop-color=\"rgb(${gray(o.tA1)},${gray(o.tA1)},${gray(o.tA1)})\"/><stop offset=\"1\" stop-color=\"rgb(${gray(o.tA2)},${gray(o.tA2)},${gray(o.tA2)})\"/></linearGradient>")
            fillRef = "url(#tg$id)"
        } else {
            val bmp = Pat.baked(o); val tw = o.tTile; val th = o.tTile * bmp.height / bmp.width
            val data = Base64.encodeToString(Png.bytes(bmp), Base64.NO_WRAP)
            sb.append("<pattern id=\"tpat$id\" patternUnits=\"userSpaceOnUse\" x=\"${f(ob.left)}\" y=\"${f(ob.top)}\" width=\"${f(tw)}\" height=\"${f(th)}\">")
            sb.append("<image width=\"${f(tw)}\" height=\"${f(th)}\" preserveAspectRatio=\"none\" xlink:href=\"data:image/png;base64,$data\"/></pattern>")
            fillRef = "url(#tpat$id)"
        }
        sb.append("<mask id=\"tm$id\" maskUnits=\"userSpaceOnUse\" x=\"${f(b.left)}\" y=\"${f(b.top)}\" width=\"${f(b.width())}\" height=\"${f(b.height())}\">")
        sb.append("<rect x=\"${f(b.left)}\" y=\"${f(b.top)}\" width=\"${f(b.width())}\" height=\"${f(b.height())}\" fill=\"$fillRef\"/></mask></defs>\n")
        return sb.toString()
    }

    fun svg(doc: Doc): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" width=\"${f(doc.w)}mm\" height=\"${f(doc.h)}mm\" viewBox=\"0 0 ${f(doc.w)} ${f(doc.h)}\">\n")
        var id = 0
        for (o in doc.objs) {
            val l = doc.layers.getOrNull(o.layer) ?: continue
            if (l.visible && o.visible) sb.append(obj(o, l.opacity, id++))
        }
        return sb.append("</svg>\n").toString()
    }

    /** Vector PDF via PdfDocument: paths/text stay vector; page is in PostScript points. */
    fun pdf(doc: Doc, out: OutputStream) {
        val pd = PdfDocument(); val k = 72f / 25.4f
        val page = pd.startPage(PdfDocument.PageInfo.Builder((doc.w * k).roundToInt(), (doc.h * k).roundToInt(), 1).create())
        page.canvas.scale(k, k); page.canvas.clipRect(0f, 0f, doc.w, doc.h)
        Render.rasterMasks = true
        try { Render.drawAll(page.canvas, doc) } finally { Render.rasterMasks = false }
        pd.finishPage(page); pd.writeTo(out); pd.close()
    }

    /** Lowers DPI if the bitmap would exceed ~40 megapixels (OOM guard). */
    fun safeDpi(doc: Doc, dpi: Int): Float {
        var d = dpi.toFloat()
        while ((doc.w / 25.4f * d).toLong() * (doc.h / 25.4f * d).toLong() > 40_000_000L) d *= 0.9f
        return d
    }

    fun bitmap(doc: Doc, dpi: Float, opaque: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap((doc.w / 25.4f * dpi).roundToInt().coerceAtLeast(1), (doc.h / 25.4f * dpi).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); if (opaque) c.drawColor(Color.WHITE)
        val s = dpi / 25.4f; c.scale(s, s); Render.drawAll(c, doc)
        return bmp
    }
}
