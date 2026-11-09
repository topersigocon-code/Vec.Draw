package com.topz.vecdraw

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.math.*

/** One entry of a themed popup menu: an action, a submenu (children) or an inline custom view (sliders, colour wheel, lists ...). */
class MNode(
    val title: String,
    val icon: String? = null,              // vector path data (same format as the tool icons)
    val glyph: String? = null,             // or a text glyph
    val selected: Boolean = false,
    val action: (() -> Unit)? = null,
    val more: (() -> Unit)? = null,        // optional "⋯" on the row (for example tool options)
    val children: (() -> List<MNode>)? = null,
    val custom: ((() -> Unit) -> View)? = null,   // builds an inline view; receives a "close the popup" callback
    val onClose: (() -> Unit)? = null
)

/**
 * The app's own popup menu: dark rounded card, gold accents, breadcrumb + back button, any depth of submenus.
 * Every `menu(...)` in the app goes through this, so nothing looks like a foreign Android dialog.
 */
class TreePopup(private val ctx: Context, private val host: View) {
    private class Level(val title: String, val nodes: List<MNode>?, val view: View?, val onClose: (() -> Unit)?)
    private val stack = ArrayList<Level>()
    private var pop: PopupWindow? = null
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    var pushes = 0; private set
    val isShowing: Boolean get() = pop?.isShowing == true
    private fun dp(n: Int) = ctx.dp(n)

    fun open(title: String, nodes: List<MNode>) {
        val fresh = !isShowing
        if (fresh) { stack.clear(); createWindow() }
        push(Level(title, nodes, null, null))
        if (fresh) pop?.showAtLocation(host, Gravity.CENTER, 0, 0)
    }

    fun openView(title: String, build: (() -> Unit) -> View, onClose: (() -> Unit)?) {
        val fresh = !isShowing
        if (fresh) { stack.clear(); createWindow() }
        push(Level(title, null, build { dismiss() }, onClose))
        if (fresh) pop?.showAtLocation(host, Gravity.CENTER, 0, 0)
    }

    fun dismiss() { pop?.dismiss() }

    private fun createWindow() {
        body.removeAllViews()
        val p = PopupWindow(ctx)
        p.contentView = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(0xF51F2329.toInt()); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x88E5A93C.toInt()) }
            setPadding(dp(6), dp(6), dp(6), dp(6)); (body.parent as? ViewGroup)?.removeView(body); addView(body)
        }
        p.isFocusable = true; p.isOutsideTouchable = true; p.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        p.setOnDismissListener { for (l in stack.reversed()) l.onClose?.invoke(); stack.clear() }
        pop = p
    }

    private fun push(l: Level) {
        pushes++
        if (stack.isNotEmpty() && l.view == null && stack.last().view == null && stack.last().title == l.title) stack.removeAt(stack.size - 1)
        stack.add(l); render()
    }

    private fun back() { if (stack.size > 1) { val l = stack.removeAt(stack.size - 1); l.onClose?.invoke(); render() } }

    private fun render() {
        body.removeAllViews()
        val top = stack.last()
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(6)) }
        if (stack.size > 1) head.addView(TextView(ctx).apply { text = "⟵"; setTextColor(GOLD); textSize = 20f; setPadding(dp(6), dp(2), dp(14), dp(2)); setOnClickListener { back() } })
        head.addView(TextView(ctx).apply {
            text = stack.takeLast(3).joinToString("  ›  ") { it.title }; setTextColor(GOLD); textSize = 12f; setSingleLine(); ellipsize = TextUtils.TruncateAt.START
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(ctx).apply { text = "✕"; setTextColor(Color.WHITE); textSize = 16f; setPadding(dp(14), dp(2), dp(6), dp(2)); setOnClickListener { dismiss() } })
        body.addView(head)
        body.addView(top.view?.let { v -> (v.parent as? ViewGroup)?.removeView(v); ScrollView(ctx).apply { addView(v) } } ?: listView(top.nodes ?: emptyList()))
        resize()
    }

    private fun listView(nodes: List<MNode>): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (n in nodes) col.addView(row(n))
        return ScrollView(ctx).apply { addView(col) }
    }

    private fun row(n: MNode): View {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(6)); minimumHeight = dp(44)
            background = GradientDrawable().apply { setColor(if (n.selected) 0x44E5A93C else 0x00000000); cornerRadius = dp(10).toFloat() }
        }
        if (n.icon != null) r.addView(IconView(ctx, n.icon), LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(12) })
        else if (n.glyph != null) r.addView(TextView(ctx).apply { text = n.glyph; setTextColor(GOLD); textSize = 16f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(12) })
        r.addView(TextView(ctx).apply { text = n.title; setTextColor(Color.WHITE); textSize = 15f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val more = n.more
        if (more != null) r.addView(TextView(ctx).apply {
            text = "⋯"; setTextColor(GOLD); textSize = 20f; setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { val b = pushes; more(); if (pushes == b) dismiss() }
        })
        if (n.children != null || n.custom != null) r.addView(TextView(ctx).apply { text = "›"; setTextColor(GOLD); textSize = 20f; setPadding(dp(6), 0, dp(2), 0) })
        r.setOnClickListener { click(n) }
        return r
    }

    private fun click(n: MNode) {
        val kids = n.children; val cust = n.custom
        when {
            kids != null -> push(Level(n.title, kids(), null, null))
            cust != null -> push(Level(n.title, null, cust { dismiss() }, n.onClose))
            else -> { val b = pushes; n.action?.invoke(); if (pushes == b) dismiss() }
        }
    }

    private fun resize() {
        val p = pop ?: return; val holder = p.contentView ?: return
        val dm = ctx.resources.displayMetrics
        val w = minOf((dm.widthPixels * 0.94f).toInt(), dp(340)); val maxH = (dm.heightPixels * 0.78f).toInt()
        holder.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        val h = holder.measuredHeight
        if (p.isShowing) p.update(w, h) else { p.width = w; p.height = h }
    }
}

/** Hue / saturation disc with its brightness controlled from outside (setValue). */
class ColorWheelView(ctx: Context) : View(ctx) {
    private val hsv = floatArrayOf(0f, 0f, 1f)
    var onChange: (Int) -> Unit = {}
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG); private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG); private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x66FFFFFF }
    private var cx = 0f; private var cy = 0f; private var r = 0f
    val value: Float get() = hsv[2]
    fun color(): Int = Color.HSVToColor(hsv)
    fun setColor(c: Int) { Color.colorToHSV(c, hsv); invalidate() }
    fun setValue(v: Float) { hsv[2] = v.coerceIn(0f, 1f); invalidate(); onChange(color()) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f; r = min(w, h) / 2f - 6f
        huePaint.shader = SweepGradient(cx, cy, intArrayOf(0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt()), null)
        satPaint.shader = RadialGradient(cx, cy, max(r, 1f), 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawCircle(cx, cy, r, huePaint); c.drawCircle(cx, cy, r, satPaint)
        darkPaint.color = Color.argb(((1f - hsv[2]) * 255).toInt(), 0, 0, 0); c.drawCircle(cx, cy, r, darkPaint)
        c.drawCircle(cx, cy, r, rim)
        val a = Math.toRadians(hsv[0].toDouble()); val kx = cx + (cos(a) * hsv[1] * r).toFloat(); val ky = cy + (sin(a) * hsv[1] * r).toFloat()
        knobFill.color = color(); c.drawCircle(kx, ky, 12f, knobFill); c.drawCircle(kx, ky, 12f, knobRing)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val dx = e.x - cx; val dy = e.y - cy
            hsv[0] = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
            hsv[1] = min(1f, hypot(dx, dy) / max(r, 1f))
            invalidate(); onChange(color())
        }
        return true
    }
}
