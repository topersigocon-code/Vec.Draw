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

    fun gradPts(o: VObj): FloatArray {
        val b = o.bounds(); val r = max(b.width(), b.height()) / 2f; val a = Math.toRadians(o.gradAngle.toDouble())
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

    fun drawObj(c: Canvas, o: VObj, la: Float) {
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
                fillPaint(o, la)?.let { c.drawPath(p, it) }
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

    private fun obj(o: VObj, la: Float, id: Int): String {
        val sb = StringBuilder(); val op = o.alpha * la
        when (o.kind) {
            Kind.PATH -> {
                val d = pathData(o)
                if (o.shadow) sb.append("<path d=\"$d\" transform=\"translate(1.5 1.5)\" fill=\"${if (o.fill != null) "#000000" else "none"}\" stroke=\"${if (o.stroke != null) "#000000" else "none"}\" stroke-width=\"${f(o.strokeW)}\" opacity=\"${f(0.27f * op)}\"/>\n")
                var fillRef = "none"
                val f1 = o.fill; val f2 = o.fill2
                if (f1 != null && f2 != null) {
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
        Render.drawAll(page.canvas, doc)
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
