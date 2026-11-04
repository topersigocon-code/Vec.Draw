package com.topz.vecdraw

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.net.Uri
import android.util.AttributeSet
import android.text.TextUtils
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.PathParser
import androidx.core.view.doOnLayout
import org.json.JSONObject
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

const val GOLD = 0xFFE5A93C.toInt()
const val PANEL = 0xEE1F2329.toInt()
fun Context.dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()

class CreateAny : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Flat vector tool icon. Parts split by '|': plain = outline, '~' = soft accent fill, '@' = solid node marker (24x24 grid). */
class IconView(ctx: Context, d: String) : View(ctx) {
    private class Part(val kind: Char, val path: Path)
    private val parts = d.split("|").map { s ->
        val k = if (s.startsWith("~")) '~' else if (s.startsWith("@")) '@' else '-'
        Part(k, try { PathParser.createPathFromPathData(if (k == '-') s else s.substring(1)) } catch (e: Exception) { Path() })
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 24f; c.save(); c.scale(s, s)
        val ink = if (isSelected) 0xFF0B192C.toInt() else 0xFFEAEFF5.toInt()
        val accent = if (isSelected) 0x330B192C else 0x66E5A93C
        val node = if (isSelected) 0xFF0B192C.toInt() else GOLD
        for (part in parts) when (part.kind) {
            '~' -> { p.style = Paint.Style.FILL; p.color = accent; c.drawPath(part.path, p) }
            '@' -> { p.style = Paint.Style.FILL; p.color = node; c.drawPath(part.path, p) }
            else -> { p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f; p.color = ink; c.drawPath(part.path, p) }
        }
        c.restore()
    }
}

/** Lays children out left to right and wraps onto new lines when the available width runs out (one line when width is unbounded). */
class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    private var xs = IntArray(0); private var ys = IntArray(0)
    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is ViewGroup.MarginLayoutParams
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams = ViewGroup.MarginLayoutParams(context, attrs)
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = ViewGroup.MarginLayoutParams(p)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(wSpec)
        xs = IntArray(childCount); ys = IntArray(childCount)
        var x = 0; var y = 0; var rowH = 0; var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            val lp = c.layoutParams as ViewGroup.MarginLayoutParams
            c.measure(getChildMeasureSpec(wSpec, 0, lp.width), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val w = c.measuredWidth + lp.leftMargin + lp.rightMargin; val h = c.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + w > maxW) { widest = max(widest, x); x = 0; y += rowH; rowH = 0 }
            xs[i] = x; ys[i] = y; x += w; rowH = max(rowH, h)
        }
        widest = max(widest, x)
        setMeasuredDimension(resolveSize(widest, wSpec), resolveSize(y + rowH, hSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            val lp = c.layoutParams as ViewGroup.MarginLayoutParams
            val cx = xs[i] + lp.leftMargin; val cy = ys[i] + lp.topMargin
            c.layout(cx, cy, cx + c.measuredWidth, cy + c.measuredHeight)
        }
    }
}

/** Scroll wrapper whose row can flip between horizontal (HorizontalScrollView) and vertical (ScrollView) layouts. */
class AdaptiveScroll(ctx: Context, private val row: LinearLayout) : FrameLayout(ctx) {
    var onOrient: (Boolean) -> Unit = {}
    private var vertical = false
    init { rebuild() }
    fun isVerticalNow() = vertical
    fun setVertical(v: Boolean) { if (v == vertical && childCount > 0) return; vertical = v; rebuild() }
    private fun rebuild() {
        (row.parent as? ViewGroup)?.removeView(row); removeAllViews()
        row.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        row.gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        val sc: ViewGroup = if (vertical) ScrollView(context) else HorizontalScrollView(context).also { it.isHorizontalScrollBarEnabled = false }
        sc.addView(row); addView(sc); onOrient(vertical)
    }
}

/**
 * A draggable, resizable panel. Drag the title bar to move (edges snap when released near them), drag the ◢ grip to resize,
 * tap the title for dock / size options (or to expand it again when collapsed), ▾ opens a flyout showing everything in the panel,
 * — collapses the panel down to its title bar (the title always stays visible).
 */
class Dockable(ctx: Context, val title: String, val content: View, closable: Boolean = true) : LinearLayout(ctx) {
    var onDropped: (Dockable) -> Unit = {}
    var onHeaderTap: (Dockable) -> Unit = {}
    var onResized: (Dockable) -> Unit = {}
    var onFlyout: (Dockable, View) -> Unit = { _, _ -> }
    var onMinimize: (Dockable) -> Unit = {}
    var collapsed = false; private set
    var expW = ViewGroup.LayoutParams.WRAP_CONTENT; var expH = ViewGroup.LayoutParams.WRAP_CONTENT   // size / position to return to
    var expX = 0f; var expY = 0f; var anchorR = false; var anchorB = false
    private val bg = GradientDrawable().apply { setColor(PANEL); cornerRadius = ctx.dp(10).toFloat(); setStroke(1, 0x44FFFFFF) }
    private val titleTv = TextView(ctx).apply { text = "⠿  $title"; setTextColor(GOLD); textSize = 11f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
    private val flyBtn = TextView(ctx).apply { text = "▾"; setTextColor(GOLD); textSize = 15f; setPadding(ctx.dp(10), ctx.dp(1), ctx.dp(8), ctx.dp(1)) }
    private val grip = TextView(ctx).apply { text = "◢"; setTextColor(GOLD); textSize = 14f; setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(2)) }
    private val minBtn: TextView? = if (closable) TextView(ctx).apply { text = "—"; setTextColor(Color.WHITE); textSize = 14f; setPadding(ctx.dp(8), 0, ctx.dp(4), 0) } else null
    private var lent = false; private var lentLp: ViewGroup.LayoutParams? = null; private var lentVert = false

    init {
        orientation = VERTICAL; background = bg
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ctx.dp(10), ctx.dp(3), ctx.dp(6), ctx.dp(3)); setBackgroundColor(0x22FFFFFF) }
        head.addView(titleTv, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(flyBtn); head.addView(grip); minBtn?.let { head.addView(it) }
        addView(head); addView(content)
        flyBtn.setOnClickListener { onFlyout(this@Dockable, it) }
        minBtn?.setOnClickListener { onMinimize(this@Dockable) }
        var dX = 0f; var dY = 0f; var downX = 0f; var downY = 0f
        head.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dX = x - e.rawX; dY = y - e.rawY; downX = e.rawX; downY = e.rawY; bringToFront() }
                MotionEvent.ACTION_MOVE -> { x = e.rawX + dX; y = e.rawY + dY }
                MotionEvent.ACTION_UP -> {
                    if (hypot(e.rawX - downX, e.rawY - downY) < ctx.dp(8)) { x = downX + dX; y = downY + dY; onHeaderTap(this) }
                    else { dock(); onDropped(this) }
                }
            }
            true
        }
        var sw = 0; var sh = 0; var gx = 0f; var gy = 0f
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sw = width; sh = height; gx = e.rawX; gy = e.rawY; bringToFront() }
                MotionEvent.ACTION_MOVE -> {
                    val p = parent as? View
                    val maxW = ((p?.width ?: 4000) - x).toInt(); val maxH = ((p?.height ?: 4000) - y).toInt()
                    val nw = (sw + (e.rawX - gx)).toInt().coerceIn(ctx.dp(90), max(ctx.dp(90), maxW))
                    val nh = (sh + (e.rawY - gy)).toInt().coerceIn(ctx.dp(70), max(ctx.dp(70), maxH))
                    (layoutParams as? FrameLayout.LayoutParams)?.let { lp -> lp.width = nw; lp.height = nh; layoutParams = lp }
                    fit(nw, nh)
                }
                MotionEvent.ACTION_UP -> onResized(this)
            }
            true
        }
    }

    /** Narrow column panels drop the grip and minimise button (use the title menu instead) so the title and ▾ always fit. */
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!collapsed) { val wide = w >= context.dp(110); grip.visibility = if (wide) View.VISIBLE else View.GONE; minBtn?.visibility = if (wide) View.VISIBLE else View.GONE }
    }

    /** Collapse to the title bar only (the title stays visible and tapping it brings the panel back) or expand again. */
    fun collapseTo(c: Boolean) {
        collapsed = c; content.visibility = if (c) View.GONE else View.VISIBLE
        titleTv.text = (if (c) "▸  " else "⠿  ") + title
        flyBtn.visibility = if (c) View.GONE else View.VISIBLE
        if (c) { grip.visibility = View.GONE; minBtn?.visibility = View.GONE }
        else { val wide = width == 0 || width >= context.dp(110); grip.visibility = if (wide) View.VISIBLE else View.GONE; minBtn?.visibility = if (wide) View.VISIBLE else View.GONE }
    }

    /** Make the content follow an explicit panel size (w/h > 0) or wrap around itself. */
    fun fit(w: Int, h: Int) {
        val ww = if (w > 0) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        content.layoutParams = if (h > 0) LayoutParams(ww, 0, 1f) else LayoutParams(ww, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun setPanelAlpha(a: Float) { bg.setColor((((a.coerceIn(0.2f, 1f) * 255).toInt()) shl 24) or 0x1F2329) }

    /** Temporarily hand the panel's real content to a flyout (adaptive strips become a tall list); give it back with returnContent(). */
    fun lendContent(): View? {
        if (lent || collapsed) return null
        lent = true; lentLp = content.layoutParams; removeView(content)
        (content as? AdaptiveScroll)?.let { lentVert = it.isVerticalNow(); it.setVertical(true) }
        return content
    }

    fun returnContent() {
        if (!lent) return
        lent = false
        (content.parent as? ViewGroup)?.removeView(content)
        (content as? AdaptiveScroll)?.setVertical(lentVert)
        addView(content, lentLp ?: LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun dock() {
        val p = parent as? View ?: return; val m = context.dp(40).toFloat()
        if (x < m) x = 0f; if (x + width > p.width - m) x = (p.width - width).toFloat().coerceAtLeast(0f)
        if (y < m) y = 0f; if (y + height > p.height - m) y = (p.height - height).toFloat().coerceAtLeast(0f)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var cv: VecCanvas
    private lateinit var root: FrameLayout
    private lateinit var statusTv: TextView
    private val panels = LinkedHashMap<String, Dockable>()
    private val refreshers = mutableListOf<() -> Unit>()
    private var pendingWrite: ((OutputStream) -> Unit)? = null
    private var palMode = 0
    private var panelAlpha = 0.93f; private var iconDp = 30; private var appliedIcon = 0
    private var iconSizeApplier: (Int) -> Unit = {}
    private var panelsHidden = false
    private val hiddenBefore = mutableSetOf<String>()

    private val createDoc = registerForActivityResult(CreateAny()) { uri ->
        if (uri != null) try { contentResolver.openOutputStream(uri)?.use { pendingWrite?.invoke(it) }; toast("Saved") } catch (e: Exception) { toast("Failed: ${e.message}") }
    }
    private val openProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadProject(uri) }
    private val openImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadImage(uri) }
    private val openMask = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadMask(uri) }
    private var maskArgs = floatArrayOf(1f, 0f, 10f)
    private val openSvg = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadSvg(uri) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun write(name: String, mime: String, w: (OutputStream) -> Unit) { pendingWrite = w; createDoc.launch(name to mime) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this); cv = VecCanvas(this)
        root.addView(cv, FrameLayout.LayoutParams(-1, -1)); setContentView(root); restoreSettings(); restoreDoc()
        cv.onChange = { refreshers.forEach { it() } }
        cv.onText = { o, isNew -> textDialog(o, isNew) }
        addPanel("Toolbox", toolbox(), true)
        addPanel("Properties", propBar(), true)
        addPanel("Palette", palette(), true)
        addPanel("Objects", objectManager(), true)
        addPanel("Status", statusBar(), true)
        addPanel("Quick", quickBar(), false)
        cv.onStatus = { statusTv.text = it }
        applyPanelPrefs()
        root.doOnLayout { cv.fit(); layoutPanels(); refreshers.forEach { it() } }
    }

    override fun onPause() { super.onPause(); saveUi(); saveDoc() }

    private fun autosaveFile() = java.io.File(filesDir, "autosave.vdt")

    /** Autosave the current drawing to the app's private storage (no permission needed); an empty drawing clears it. */
    private fun saveDoc() {
        if (!::cv.isInitialized) return
        try {
            val f = autosaveFile()
            if (cv.doc.objs.isEmpty()) { f.delete(); return }
            val tmp = java.io.File(filesDir, "autosave.tmp")
            tmp.writeText(cv.doc.toJson().toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            getSharedPreferences("vd", MODE_PRIVATE).edit().putInt("activeLayer", cv.activeLayer).apply()
        } catch (e: Throwable) { }
    }

    private fun restoreDoc() {
        val f = autosaveFile(); if (!f.exists()) return
        try {
            cv.newDoc(Doc.fromJson(JSONObject(f.readText())))
            cv.activeLayer = getSharedPreferences("vd", MODE_PRIVATE).getInt("activeLayer", 0).coerceIn(0, cv.doc.layers.lastIndex)
            toast("Restored your last drawing")
        } catch (e: Throwable) { f.delete() }
    }

    private fun newDrawing() {
        if (cv.doc.objs.isEmpty()) { cv.newDoc(Doc()); return }
        AlertDialog.Builder(this).setTitle("New drawing?").setMessage("The current drawing will be replaced. Save it first if you need it.")
            .setPositiveButton("New") { _, _ -> cv.newDoc(Doc()) }.setNegativeButton("Cancel", null).show()
    }

    private fun colStr(c: Int?) = c?.toString() ?: "none"

    /** Persist tool settings, snapping options, default style and the panel arrangement. */
    private fun saveUi() {
        if (!::cv.isInitialized || root.width == 0) return
        val e = getSharedPreferences("vd", MODE_PRIVATE).edit(); val st = cv.style
        e.putBoolean("snapGrid", cv.snapGrid).putBoolean("snapGuides", cv.snapGuides).putBoolean("snapObjs", cv.snapObjs)
            .putBoolean("showGrid", cv.showGrid).putBoolean("lockRatio", cv.lockRatio)
            .putInt("meshRows", cv.meshRows).putInt("meshCols", cv.meshCols).putInt("polySides", cv.polySides).putInt("starPts", cv.starPts)
            .putInt("palMode", palMode).putString("tool", cv.tool.name)
            .putInt("bg", cv.bgColor).putInt("gridc", cv.gridColor).putInt("gridA", cv.gridAlpha).putInt("guidec", cv.guideColor).putInt("selc", cv.selColor)
            .putFloat("snapR", cv.snapRadius).putBoolean("snapN", cv.snapNodes).putBoolean("snapC", cv.snapCenters).putBoolean("snapE", cv.snapEdges)
            .putFloat("hScale", cv.handleScale).putFloat("fhEps", cv.freehandEps).putInt("undoN", cv.undoLimit).putFloat("pAlpha", panelAlpha).putInt("iconDp", iconDp)
            .putString("st_fill", colStr(st.fill)).putString("st_fill2", colStr(st.fill2)).putString("st_stroke", colStr(st.stroke))
            .putFloat("st_sw", st.strokeW).putInt("st_cap", st.cap).putInt("st_join", st.join).putInt("st_dash", st.dash)
            .putBoolean("st_arrow", st.arrow).putFloat("st_alpha", st.alpha).putBoolean("st_shadow", st.shadow)
        val w = root.width.toFloat(); val h = root.height.toFloat()
        for ((n, p) in panels) {
            val vis = if (panelsHidden && n != "Quick") n !in hiddenBefore else !p.collapsed
            e.putFloat("p_${n}_x", (if (p.collapsed) p.expX else p.x) / w).putFloat("p_${n}_y", (if (p.collapsed) p.expY else p.y) / h).putBoolean("p_${n}_vis", vis)
                .putBoolean("p_${n}_vert", (p.content as? AdaptiveScroll)?.isVerticalNow() ?: false)
                .putInt("p_${n}_w", if (p.collapsed) p.expW else p.layoutParams.width).putInt("p_${n}_h", if (p.collapsed) p.expH else p.layoutParams.height)
        }
        e.putInt("ref_w", root.width).putInt("ref_h", root.height).putInt("layout_v", 2).apply()
    }

    private fun restoreSettings() {
        val sp = getSharedPreferences("vd", MODE_PRIVATE); val st = cv.style
        fun col(key: String, def: Int?): Int? = if (!sp.contains(key)) def else sp.getString(key, "none")?.toIntOrNull()
        cv.snapGrid = sp.getBoolean("snapGrid", true); cv.snapGuides = sp.getBoolean("snapGuides", true); cv.snapObjs = sp.getBoolean("snapObjs", false)
        cv.showGrid = sp.getBoolean("showGrid", true); cv.lockRatio = sp.getBoolean("lockRatio", true)
        cv.meshRows = sp.getInt("meshRows", 3).coerceIn(2, 8); cv.meshCols = sp.getInt("meshCols", 3).coerceIn(2, 8)
        cv.polySides = sp.getInt("polySides", 6).coerceIn(3, 30); cv.starPts = sp.getInt("starPts", 5).coerceIn(3, 30)
        palMode = sp.getInt("palMode", 0).coerceIn(0, 3)
        cv.bgColor = sp.getInt("bg", cv.bgColor); cv.gridColor = sp.getInt("gridc", cv.gridColor); cv.gridAlpha = sp.getInt("gridA", cv.gridAlpha).coerceIn(5, 100)
        cv.guideColor = sp.getInt("guidec", cv.guideColor); cv.selColor = sp.getInt("selc", cv.selColor)
        cv.snapRadius = sp.getFloat("snapR", cv.snapRadius).coerceIn(4f, 30f); cv.snapNodes = sp.getBoolean("snapN", true)
        cv.snapCenters = sp.getBoolean("snapC", false); cv.snapEdges = sp.getBoolean("snapE", false)
        cv.handleScale = sp.getFloat("hScale", 1f).coerceIn(0.6f, 2f); cv.freehandEps = sp.getFloat("fhEps", cv.freehandEps).coerceIn(1f, 10f); cv.undoLimit = sp.getInt("undoN", cv.undoLimit).coerceIn(10, 200)
        panelAlpha = sp.getFloat("pAlpha", panelAlpha).coerceIn(0.3f, 1f); iconDp = sp.getInt("iconDp", iconDp).coerceIn(24, 44)
        cv.applyTheme()
        st.fill = col("st_fill", st.fill); st.fill2 = col("st_fill2", null); st.stroke = col("st_stroke", st.stroke)
        st.strokeW = sp.getFloat("st_sw", st.strokeW); st.cap = sp.getInt("st_cap", 0).coerceIn(0, 2); st.join = sp.getInt("st_join", 0).coerceIn(0, 2)
        st.dash = sp.getInt("st_dash", 0).coerceIn(0, 3); st.arrow = sp.getBoolean("st_arrow", false)
        st.alpha = sp.getFloat("st_alpha", 1f); st.shadow = sp.getBoolean("st_shadow", false)
        try { cv.tool = Tool.valueOf(sp.getString("tool", "PICK") ?: "PICK") } catch (e: Exception) { }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); root.postDelayed({ layoutPanels() }, 250) }

    private fun addPanel(name: String, content: View, closable: Boolean) {
        val d = Dockable(this, name, content, closable)
        d.onDropped = { onDropped(it) }; d.onHeaderTap = { if (it.collapsed) expand(it) else panelMenuFor(it) }
        d.onFlyout = { p, v -> showFlyout(p, v) }; d.onMinimize = { collapse(it) }
        d.onResized = { p -> p.dock(); bump(p, panels.values); reflow() }
        panels[name] = d
        root.addView(d, FrameLayout.LayoutParams(-2, -2))
    }

    private fun nameOf(d: Dockable) = panels.entries.firstOrNull { it.value === d }?.key ?: ""

    private fun setSize(n: String, w: Int, h: Int) {
        panels[n]?.let { p ->
            if (p.collapsed) { p.expW = w; p.expH = h; p.fit(w, h) }
            else { p.layoutParams = (p.layoutParams as FrameLayout.LayoutParams).also { it.width = w; it.height = h }; p.fit(w, h) }
        }
    }

    private fun toolboxW() = dp(iconDp + 16 + 4 + 14)

    private fun defSize(n: String, w: Int, h: Int): Pair<Int, Int> = when (n) {
        "Properties", "Status" -> w to ViewGroup.LayoutParams.WRAP_CONTENT
        "Palette" -> dp(60) to (h * 0.7f).toInt()
        "Toolbox" -> toolboxW() to (h * 0.55f).toInt()
        "Objects" -> dp(230) to (h * 0.5f).toInt()
        else -> ViewGroup.LayoutParams.WRAP_CONTENT to ViewGroup.LayoutParams.WRAP_CONTENT
    }

    /** Default arrangement: Properties top, Status bottom, Toolbox left, Palette right (vertical) - docked, so the canvas shrinks; nothing overlaps. */
    private fun layoutPanels(reset: Boolean = false) {
        val w = root.width; val h = root.height
        val sp = getSharedPreferences("vd", MODE_PRIVATE)
        panels.values.forEach { it.collapseTo(false) }
        // restore the saved arrangement if the screen size is unchanged
        if (!reset && sp.getInt("layout_v", 0) >= 2 && sp.contains("p_Toolbox_x") && abs(sp.getInt("ref_w", 0) - w) <= 2 && abs(sp.getInt("ref_h", 0) - h) <= 2) {
            for ((n, p) in panels) {
                (p.content as? AdaptiveScroll)?.setVertical(sp.getBoolean("p_${n}_vert", false))
                val d = defSize(n, w, h)
                var sw = sp.getInt("p_${n}_w", d.first); var sh = sp.getInt("p_${n}_h", d.second)
                if ((n == "Toolbox" || n == "Objects") && (sw <= 0 || sh <= 0)) { sw = d.first; sh = d.second }
                setSize(n, sw, sh)
                p.visibility = View.VISIBLE
            }
            root.postDelayed({
                for ((n, p) in panels) { p.x = sp.getFloat("p_${n}_x", 0f) * w; p.y = sp.getFloat("p_${n}_y", 0f) * h }
                resolveAll()
                for ((n, p) in panels) if (n != "Quick" && !sp.getBoolean("p_${n}_vis", true)) collapse(p)
                reflow(); cv.postDelayed({ cv.fit() }, 80)
            }, 100)
            return
        }
        if (reset) { panelsHidden = false; hiddenBefore.clear() }
        val hideObjects = reset || !sp.contains("p_Objects_vis")
        (panels["Palette"]?.content as? AdaptiveScroll)?.setVertical(true)
        (panels["Properties"]?.content as? AdaptiveScroll)?.setVertical(false)
        (panels["Status"]?.content as? AdaptiveScroll)?.setVertical(false)
        for (n in panels.keys) { val d = defSize(n, w, h); setSize(n, d.first, d.second) }
        root.postDelayed({
            val top = panels["Properties"]?.height ?: 0; val gap = dp(6)
            panels["Properties"]?.let { it.x = 0f; it.y = 0f }
            panels["Status"]?.let { it.x = 0f; it.y = (h - it.height).toFloat() }
            panels["Toolbox"]?.let { it.x = 0f; it.y = (top + gap).toFloat() }
            panels["Palette"]?.let { it.x = (w - it.width).toFloat(); it.y = (top + gap).toFloat() }
            panels["Quick"]?.let { it.x = (w - it.width - dp(70)).toFloat(); it.y = (top + gap).toFloat() }
            panels["Objects"]?.let { it.x = (w - it.width - dp(70)).toFloat(); it.y = (top + gap + dp(50)).toFloat() }
            resolveAll(); if (hideObjects) panels["Objects"]?.let { collapse(it) }; reflow(); cv.postDelayed({ cv.fit() }, 80)
        }, 100)
    }

    /** Which edge a panel is docked to: 0 none (floating), 1 left, 2 right, 3 top, 4 bottom. Only strip-like panels count. */
    private fun sideOf(p: Dockable): Int {
        if (p.visibility != View.VISIBLE || p.collapsed || p.width == 0 || root.width == 0) return 0
        val w = root.width.toFloat(); val h = root.height.toFloat(); val tol = 3f
        val l = p.x <= tol; val r = p.x + p.width >= w - tol; val t = p.y <= tol; val b = p.y + p.height >= h - tol
        val fw = p.width / w; val fh = p.height / h
        val vert = (l || r) && fh >= 0.45f && fw <= 0.45f
        val horiz = (t || b) && fw >= 0.45f && fh <= 0.45f
        return when {
            vert && horiz -> if (fh >= fw) (if (l) 1 else 2) else (if (t) 3 else 4)
            vert -> if (l) 1 else 2
            horiz -> if (t) 3 else 4
            else -> 0
        }
    }

    /** Resize the canvas so docked panels no longer cover it (keeps the drawing visually stationary). */
    private fun reflow() {
        var l = 0; var r = 0; var t = 0; var b = 0
        for (p in panels.values) when (sideOf(p)) { 1 -> l = max(l, p.width); 2 -> r = max(r, p.width); 3 -> t = max(t, p.height); 4 -> b = max(b, p.height) }
        val lp = cv.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin == l && lp.rightMargin == r && lp.topMargin == t && lp.bottomMargin == b) return
        cv.panX -= (l - lp.leftMargin); cv.panY -= (t - lp.topMargin)
        lp.setMargins(l, t, r, b); cv.layoutParams = lp; cv.invalidate()
    }

    private fun rectOf(p: Dockable) = RectF(p.x, p.y, p.x + p.width, p.y + p.height)
    private fun overlap(a: RectF, b: RectF) = a.left < b.right - 1f && a.right > b.left + 1f && a.top < b.bottom - 1f && a.bottom > b.top + 1f

    /** Snap a panel flush against neighbouring panels (and align edges) when released close to them. */
    private fun magnet(d: Dockable) {
        val t = dp(14).toFloat(); val r = rectOf(d)
        for (q in panels.values) {
            if (q === d || q.visibility != View.VISIBLE) continue
            val o = rectOf(q); val vOver = r.top < o.bottom && r.bottom > o.top; val hOver = r.left < o.right && r.right > o.left
            if (vOver && abs(r.left - o.right) < t) d.x = o.right
            else if (vOver && abs(r.right - o.left) < t) d.x = o.left - d.width
            else if (abs(r.left - o.left) < t && !vOver) d.x = o.left
            if (hOver && abs(r.top - o.bottom) < t) d.y = o.bottom
            else if (hOver && abs(r.bottom - o.top) < t) d.y = o.top - d.height
            else if (abs(r.top - o.top) < t && !hOver) d.y = o.top
        }
    }

    /** If the panel overlaps another visible panel, move it to the nearest free spot (flush beside a neighbour, or anywhere free). */
    private fun bump(p: Dockable, all: Collection<Dockable>) {
        val others = all.filter { it !== p && it.visibility == View.VISIBLE }
        val w = p.width.toFloat(); val h = p.height.toFloat(); val sw = root.width.toFloat(); val sh = root.height.toFloat()
        if (w <= 0f || others.none { overlap(rectOf(p), rectOf(it)) }) return
        var bestX = p.x; var bestY = p.y; var bestD = Float.MAX_VALUE
        fun consider(x0: Float, y0: Float) {
            val x = x0.coerceIn(0f, max(0f, sw - w)); val y = y0.coerceIn(0f, max(0f, sh - h)); val r = RectF(x, y, x + w, y + h)
            if (others.none { overlap(r, rectOf(it)) }) { val d = hypot(x - p.x, y - p.y); if (d < bestD) { bestD = d; bestX = x; bestY = y } }
        }
        for (q in others) { val o = rectOf(q); consider(o.right, p.y); consider(o.left - w, p.y); consider(p.x, o.bottom); consider(p.x, o.top - h) }
        if (bestD == Float.MAX_VALUE) {
            val step = dp(16).toFloat(); var yy = 0f
            while (yy <= sh - h) { var xx = 0f; while (xx <= sw - w) { consider(xx, yy); xx += step }; yy += step }
        }
        if (bestD != Float.MAX_VALUE) { p.x = bestX; p.y = bestY }
    }

    /** Later panels in this priority order are moved out of the way of earlier ones. */
    private fun resolveAll() {
        val order = listOf("Properties", "Status", "Toolbox", "Palette", "Quick", "Objects").mapNotNull { panels[it] }
        for (i in 1 until order.size) bump(order[i], order.subList(0, i))
    }

    /** After a drop: flip adaptive panels (Properties / Palette / Status) between strip and column to suit the edge, then tidy up. */
    private fun onDropped(d: Dockable) {
        val w = root.width; val h = root.height; val tol = 3f; val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val fl = d.x <= tol; val fr = d.x + d.width >= w - tol; val ft = d.y <= tol; val fb = d.y + d.height >= h - tol
        val name = nameOf(d); val ad = d.content as? AdaptiveScroll
        val finish = {
            if (fl) d.x = 0f; if (fr) d.x = (w - d.width).toFloat(); if (ft) d.y = 0f; if (fb) d.y = (h - d.height).toFloat()
            d.dock(); magnet(d); bump(d, panels.values); reflow()
        }
        if (ad != null && (fl || fr) && !ft && !fb) {
            ad.setVertical(true); setSize(name, dp(if (name == "Properties") 150 else 60), (h * 0.7f).toInt()); d.postDelayed({ finish() }, 80)
        } else if (ad != null && (ft || fb) && !fl && !fr) {
            ad.setVertical(false); setSize(name, w, wrap); d.postDelayed({ finish() }, 80)
        } else finish()
    }

    /** side: 1 left, 2 right, 3 top, 4 bottom. */
    private fun dockTo(d: Dockable, side: Int) {
        val w = root.width; val h = root.height; val name = nameOf(d); val ad = d.content as? AdaptiveScroll; val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        if (ad != null && (side == 1 || side == 2)) { ad.setVertical(true); setSize(name, dp(if (name == "Properties") 150 else 60), (h * 0.7f).toInt()) }
        if (ad != null && (side == 3 || side == 4)) { ad.setVertical(false); setSize(name, w, wrap) }
        d.postDelayed({
            when (side) { 1 -> d.x = 0f; 2 -> d.x = (w - d.width).toFloat(); 3 -> d.y = 0f; 4 -> d.y = (h - d.height).toFloat() }
            if (ad != null && (side == 3 || side == 4)) d.x = 0f
            if (side == 1 || side == 2) d.y = d.y.coerceIn(0f, (h - d.height).toFloat().coerceAtLeast(0f))
            if (side == 3 || side == 4) d.x = d.x.coerceIn(0f, (w - d.width).toFloat().coerceAtLeast(0f))
            bump(d, panels.values); reflow()
        }, 80)
    }

    private fun resizeBy(d: Dockable, f: Float) {
        val nw = (d.width * f).toInt().coerceIn(dp(90), root.width); val nh = (d.height * f).toInt().coerceIn(dp(70), root.height)
        setSize(nameOf(d), nw, nh)
        d.postDelayed({
            d.x = d.x.coerceIn(0f, max(0f, (root.width - d.width).toFloat())); d.y = d.y.coerceIn(0f, max(0f, (root.height - d.height).toFloat()))
            bump(d, panels.values); reflow()
        }, 80)
    }

    private fun panelMenuFor(d: Dockable) {
        val name = nameOf(d)
        val items = mutableListOf<Pair<String, () -> Unit>>(
            "Dock left" to { dockTo(d, 1) }, "Dock right" to { dockTo(d, 2) }, "Dock top" to { dockTo(d, 3) }, "Dock bottom" to { dockTo(d, 4) },
            "Bigger" to { resizeBy(d, 1.25f) }, "Smaller" to { resizeBy(d, 0.8f) })
        if (name != "Quick") items.add("Collapse to title" to { collapse(d) })
        menu("$name panel", items)
    }

    /** "Hide" = collapse to the title bar, so the title stays on screen and can be tapped to bring the panel back. */
    private fun collapse(d: Dockable) {
        if (d.collapsed) return
        val lp = d.layoutParams as FrameLayout.LayoutParams
        d.anchorR = d.x + d.width >= root.width - 3f; d.anchorB = d.y + d.height >= root.height - 3f
        d.expW = lp.width; d.expH = lp.height; d.expX = d.x; d.expY = d.y
        d.collapseTo(true)
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT; lp.height = ViewGroup.LayoutParams.WRAP_CONTENT; d.layoutParams = lp
        d.postDelayed({
            d.x = if (d.anchorR) (root.width - d.width).toFloat() else d.expX
            d.y = if (d.anchorB) (root.height - d.height).toFloat() else d.expY
            bump(d, panels.values); reflow()
        }, 80)
    }

    private fun expand(d: Dockable) {
        if (!d.collapsed) return
        d.collapseTo(false); setSize(nameOf(d), d.expW, d.expH)
        d.bringToFront()
        d.postDelayed({
            d.x = if (d.anchorR) (root.width - d.width).toFloat() else d.expX
            d.y = if (d.anchorB) (root.height - d.height).toFloat() else d.expY
            d.x = d.x.coerceIn(0f, max(0f, (root.width - d.width).toFloat())); d.y = d.y.coerceIn(0f, max(0f, (root.height - d.height).toFloat()))
            bump(d, panels.values); reflow()
        }, 80)
    }

    private fun toolFlyout(dismiss: () -> Unit): View {
        val g = GridLayout(this).apply { columnCount = 4; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        for (t in Tool.values()) {
            val on = cv.tool == t
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(6), dp(4), dp(4))
                layoutParams = GridLayout.LayoutParams().apply { width = dp(70); height = ViewGroup.LayoutParams.WRAP_CONTENT; setMargins(dp(2), dp(2), dp(2), dp(2)) }
                background = GradientDrawable().apply { setColor(if (on) GOLD else 0x22FFFFFF); cornerRadius = dp(8).toFloat() }
            }
            cell.addView(IconView(this, t.icon).also { it.isSelected = on }, LinearLayout.LayoutParams(dp(30), dp(30)))
            cell.addView(TextView(this).apply { text = t.label; textSize = 9f; gravity = Gravity.CENTER; maxLines = 2; setTextColor(if (on) 0xFF0B192C.toInt() else Color.WHITE) }, LinearLayout.LayoutParams(-1, -2))
            cell.setOnClickListener { cv.tool = t; dismiss() }
            cell.setOnLongClickListener { dismiss(); toolOptions(t); true }
            g.addView(cell)
        }
        return ScrollView(this).apply { addView(g) }
    }

    private fun paletteFlyout(dismiss: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val grid = GridLayout(this).apply { columnCount = 6 }
        fun fill(mode: Int) {
            grid.removeAllViews()
            for (c in paletteColors(mode)) {
                val v = View(this); setSwatch(v, c)
                v.layoutParams = GridLayout.LayoutParams().apply { width = dp(36); height = dp(36); setMargins(dp(3), dp(3), dp(3), dp(3)) }
                v.setOnClickListener { cv.setFill(c); dismiss() }
                v.setOnLongClickListener { cv.setStroke(c); dismiss(); true }
                grid.addView(v)
            }
        }
        listOf("RGB", "CMYK", "HSV", "Custom").forEachIndexed { i, n -> tabs.addView(chip(n) { fill(i) }) }
        fill(palMode.coerceIn(0, 3))
        box.addView(tabs); box.addView(ScrollView(this).apply { addView(grid) })
        box.addView(TextView(this).apply { text = "Tap = fill · long-press = outline"; textSize = 10f; setTextColor(0xAAFFFFFF.toInt()); setPadding(dp(4), dp(4), dp(4), 0) })
        return box
    }

    private fun quickFlyout(dismiss: () -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        val items = listOf<Pair<String, () -> Unit>>("☰  Menu" to { mainMenu() }, "↶  Undo" to { cv.undo() }, "↷  Redo" to { cv.redo() },
            "✓  Finish path" to { cv.finishPen() }, "⛶  Fit page" to { cv.fit() }, "◩  Collapse / show panels" to { togglePanels() }, "▤  Tidy collapsed titles" to { tidyCollapsed() })
        for ((t, a) in items) col.addView(chip(t) { dismiss(); a() })
        return col
    }

    /** ▾ on a panel: a small popup showing everything in that panel (all tool icons, all colours, all controls). */
    private fun showFlyout(d: Dockable, anchor: View) {
        val name = nameOf(d); val pop = PopupWindow(this); var lent = false
        val dismiss = { pop.dismiss() }
        val body: View = when (name) {
            "Toolbox" -> toolFlyout(dismiss)
            "Palette" -> paletteFlyout(dismiss)
            "Quick" -> quickFlyout(dismiss)
            else -> { val c = d.lendContent() ?: return; lent = true; FrameLayout(this).apply { addView(c, FrameLayout.LayoutParams(-2, -2)) } }
        }
        val holder = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0xF51F2329.toInt()); cornerRadius = dp(10).toFloat(); setStroke(1, 0x66E5A93C) }
            setPadding(dp(4), dp(4), dp(4), dp(4)); addView(body)
        }
        val dm = resources.displayMetrics
        holder.measure(View.MeasureSpec.makeMeasureSpec(minOf((dm.widthPixels * 0.94f).toInt(), dp(340)), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((dm.heightPixels * 0.7f).toInt(), View.MeasureSpec.AT_MOST))
        val pw = holder.measuredWidth; val ph = holder.measuredHeight
        pop.contentView = holder; pop.width = pw; pop.height = ph
        pop.isFocusable = true; pop.isOutsideTouchable = true; pop.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pop.setOnDismissListener { if (lent) d.returnContent() }
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val xoff = if (loc[0] + pw > dm.widthPixels) dm.widthPixels - pw - loc[0] else 0
        val yoff = if (loc[1] + anchor.height + ph > dm.heightPixels) -(anchor.height + ph) else 0
        pop.showAsDropDown(anchor, xoff, yoff)
    }

    /** Line the collapsed title bars up along the top of the canvas area so none hide behind or on top of another. */
    private fun tidyCollapsed() {
        val lp = cv.layoutParams as FrameLayout.LayoutParams
        val gap = dp(6); val left = lp.leftMargin + gap; val right = root.width - lp.rightMargin - gap
        var x = left.toFloat(); var y = (lp.topMargin + gap).toFloat(); var rowH = 0f
        for (p in panels.values.filter { it.collapsed }.sortedBy { it.x }) {
            if (x > left && x + p.width > right) { x = left.toFloat(); y += rowH + gap; rowH = 0f }
            p.x = x; p.y = y; p.anchorR = false; p.anchorB = false
            x += p.width + gap; rowH = max(rowH, p.height.toFloat())
        }
        for (p in panels.values.filter { it.collapsed }) bump(p, panels.values)
    }

    private fun applyPanelPrefs() { panels.values.forEach { it.setPanelAlpha(panelAlpha) }; iconSizeApplier(iconDp); appliedIcon = iconDp }

    /** Apply the live values from the Settings dialog. */
    private fun applySettings() {
        cv.applyTheme(); panels.values.forEach { it.setPanelAlpha(panelAlpha) }
        if (iconDp != appliedIcon) {
            appliedIcon = iconDp; iconSizeApplier(iconDp)
            setSize("Toolbox", toolboxW(), panels["Toolbox"]?.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT)
            root.postDelayed({ panels["Toolbox"]?.let { bump(it, panels.values) }; reflow() }, 100)
        }
    }

    private fun resetSettings() {
        cv.bgColor = 0xFF1B1D21.toInt(); cv.gridColor = Color.BLACK; cv.gridAlpha = 15; cv.guideColor = 0xFF00BCD4.toInt(); cv.selColor = GOLD
        cv.snapRadius = 10f; cv.snapNodes = true; cv.snapCenters = false; cv.snapEdges = false; cv.freehandEps = 2f; cv.undoLimit = 60; cv.handleScale = 1f
        panelAlpha = 0.93f; iconDp = 30; applySettings()
    }

    private fun settingsDialog() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        fun head(t: String) = col.addView(TextView(this).apply { text = t; setTextColor(GOLD); textSize = 13f; setPadding(0, dp(14), 0, dp(4)) })
        fun colorRow(name: String, get: () -> Int, set: (Int) -> Unit) {
            val sw = View(this); setSwatch(sw, get() or (0xFF shl 24))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = name }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sw.setOnClickListener { pickColor(get() or (0xFF shl 24)) { c -> set(c); setSwatch(sw, c); applySettings() } }
            row.addView(sw, LinearLayout.LayoutParams(dp(44), dp(30))); col.addView(row)
        }
        fun slider(name: String, lo: Int, hi: Int, get: () -> Int, set: (Int) -> Unit, unit: String) {
            val tv = TextView(this).apply { text = "$name: ${get()}$unit" }
            val sb = SeekBar(this)
            sb.max = hi - lo; sb.progress = (get() - lo).coerceIn(0, hi - lo)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { set(p + lo); tv.text = "$name: ${p + lo}$unit"; if (u) applySettings() }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            col.addView(tv); col.addView(sb)
        }
        fun check(name: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            col.addView(CheckBox(this).apply { text = name; isChecked = get(); setOnCheckedChangeListener { _, v -> set(v); applySettings() } })
        }
        head("Canvas")
        colorRow("Background", { cv.bgColor }, { cv.bgColor = it })
        colorRow("Grid", { cv.gridColor }, { cv.gridColor = it })
        colorRow("Guides", { cv.guideColor }, { cv.guideColor = it })
        colorRow("Selection & handles", { cv.selColor }, { cv.selColor = it })
        slider("Grid opacity", 5, 100, { cv.gridAlpha }, { cv.gridAlpha = it }, "%")
        slider("Touch handle size", 60, 200, { (cv.handleScale * 100).toInt() }, { cv.handleScale = it / 100f }, "%")
        head("Panels")
        slider("Panel opacity", 30, 100, { (panelAlpha * 100).toInt() }, { panelAlpha = it / 100f }, "%")
        slider("Tool icon size", 24, 44, { iconDp }, { iconDp = it }, " dp")
        head("Snapping")
        slider("Snap radius", 4, 30, { cv.snapRadius.toInt() }, { cv.snapRadius = it.toFloat() }, " px")
        check("To object nodes", { cv.snapNodes }, { cv.snapNodes = it })
        check("To object centres", { cv.snapCenters }, { cv.snapCenters = it })
        check("To object edges & corners", { cv.snapEdges }, { cv.snapEdges = it })
        col.addView(TextView(this).apply { text = "Object snapping is switched on with “Objects” in the status bar."; textSize = 11f })
        head("Drawing")
        slider("Freehand smoothing", 1, 10, { cv.freehandEps.toInt() }, { cv.freehandEps = it.toFloat() }, " px")
        slider("Undo steps", 10, 200, { cv.undoLimit }, { cv.undoLimit = it }, "")
        lateinit var dlg: AlertDialog
        col.addView(Button(this).apply { text = "Reset to defaults"; setOnClickListener { resetSettings(); dlg.dismiss(); settingsDialog() } })
        dlg = AlertDialog.Builder(this).setTitle("Settings").setView(ScrollView(this).apply { addView(col) }).setPositiveButton("Done", null)
            .setOnDismissListener { saveUi() }.create()
        dlg.show()
    }

    // ---------- small UI helpers ----------
    private fun chip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
        setPadding(dp(9), dp(6), dp(9), dp(6)); minWidth = dp(34)
        background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(6).toFloat() }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
    }
    private fun swatch(onClick: () -> Unit) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }; setOnClickListener { onClick() }
    }
    private fun setSwatch(v: View, c: Int?) { v.background = GradientDrawable().apply { setColor(c ?: 0x00000000); setStroke(dp(2), if (c == null) 0xFFFF5555.toInt() else Color.WHITE); cornerRadius = dp(5).toFloat() } }
    private fun field(width: Int = 64, hint: String = "") = EditText(this).apply {
        setTextColor(Color.WHITE); setHintTextColor(0x88FFFFFF.toInt()); textSize = 12f; this.hint = hint; setSingleLine()
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        imeOptions = EditorInfo.IME_ACTION_DONE; layoutParams = LinearLayout.LayoutParams(dp(width), -2)
    }
    private fun hscroll(row: LinearLayout): View = AdaptiveScroll(this, row)
    private fun label(t: String) = TextView(this).apply { text = t; setTextColor(0xAAFFFFFF.toInt()); textSize = 11f; setPadding(dp(4), 0, dp(2), 0); gravity = Gravity.CENTER_VERTICAL }
    private fun obj() = cv.sel.firstOrNull() ?: cv.style
    private fun uf() = cv.doc.uf()
    private fun fmt(v: Float) = String.format(java.util.Locale.US, "%.2f", v)
    private fun f(s: CharSequence, d: Float) = s.toString().toFloatOrNull() ?: d

    private fun menu(title: String, items: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this).setTitle(title).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun numDialog(title: String, labels: List<String>, defaults: List<Float>, ok: (List<Float>) -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val fs = labels.mapIndexed { i, l -> col.addView(TextView(this).apply { text = l }); field(200).also { it.setText(fmt(defaults[i]).trimEnd('0').trimEnd('.')); col.addView(it) } }
        AlertDialog.Builder(this).setTitle(title).setView(col).setPositiveButton("OK") { _, _ -> ok(fs.mapIndexed { i, e -> f(e.text, defaults[i]) }) }.setNegativeButton("Cancel", null).show()
    }

    // ---------- Toolbox ----------
    private fun toolbox(): View {
        val grid = GridLayout(this).apply { columnCount = 1; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val icons = HashMap<Tool, FrameLayout>()
        for (t in Tool.values()) {
            val cell = FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply { width = dp(46); height = dp(46); setMargins(dp(2), dp(2), dp(2), dp(2)) }
                background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(8).toFloat() }
                addView(IconView(context, t.icon), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                setOnClickListener { cv.tool = t; toast(if (t == Tool.MESH) "Mesh: drag points; select a point, then drag its handles to curve edges; tap a colour to paint" else t.label) }
                setOnLongClickListener { toolOptions(t); true }
            }
            icons[t] = cell; grid.addView(cell)
        }
        refreshers.add {
            for ((t, c) in icons) {
                val on = cv.tool == t; c.getChildAt(0).isSelected = on
                (c.background as GradientDrawable).setColor(if (on) GOLD else 0x22FFFFFF); c.getChildAt(0).invalidate()
            }
        }
        iconSizeApplier = { sz ->
            for (cell in icons.values) {
                (cell.layoutParams as GridLayout.LayoutParams).let { it.width = dp(sz + 16); it.height = dp(sz + 16) }
                (cell.getChildAt(0).layoutParams as FrameLayout.LayoutParams).let { it.width = dp(sz); it.height = dp(sz) }
                cell.requestLayout()
            }
        }
        return ScrollView(this).apply { addView(grid) }
    }

    private fun toolOptions(t: Tool) {
        when (t) {
            Tool.POLYGON -> numDialog("Polygon", listOf("Sides (3-30)"), listOf(cv.polySides.toFloat())) { cv.polySides = it[0].toInt().coerceIn(3, 30) }
            Tool.STAR -> numDialog("Star", listOf("Points (3-30)"), listOf(cv.starPts.toFloat())) { cv.starPts = it[0].toInt().coerceIn(3, 30) }
            Tool.BASIC -> AlertDialog.Builder(this).setTitle("Basic shape").setItems(Geo.BASIC.toTypedArray()) { _, i -> cv.basicIdx = i; cv.tool = Tool.BASIC }.show()
            Tool.NODE -> menu("Node operations", listOf(
                "Add node" to { cv.nodeAdd() }, "Delete node" to { cv.nodeDelete() }, "Break at node" to { cv.nodeBreak() }, "Join / close path" to { cv.nodeJoin() },
                "Convert to curve" to { cv.nodeToCurve() }, "Convert to line" to { cv.nodeToLine() }, "Make smooth" to { cv.nodeSmooth() }, "Make cusp" to { cv.nodeCusp() }))
            Tool.TRANS -> transMenu()
            Tool.MESH -> menu("Mesh fill", listOf(
                "Mesh size…" to { numDialog("Mesh vertices", listOf("Rows (2-8)", "Columns (2-8)"), listOf(cv.meshRows.toFloat(), cv.meshCols.toFloat())) { cv.meshRows = it[0].toInt().coerceIn(2, 8); cv.meshCols = it[1].toInt().coerceIn(2, 8); cv.meshApply() } },
                "Reset point positions" to { cv.meshReset() },
                "Straighten edges (reset handles)" to { cv.meshStraighten() },
                "Remove mesh" to { cv.meshClear() }))
            Tool.PEN, Tool.BEZIER, Tool.POLYLINE, Tool.BSPLINE -> cv.finishPen()
            else -> {}
        }
    }

    // ---------- Property bar ----------
    private fun propBar(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(2)) }
        val fillSw = swatch { pickColor(obj().fill ?: Color.WHITE) { cv.setFill(it) } }
        val strokeSw = swatch { pickColor(obj().stroke ?: Color.BLACK) { cv.setStroke(it) } }
        val wTxt = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; minWidth = dp(44); gravity = Gravity.CENTER }
        val capC = chip("Cap") { val n = (obj().cap + 1) % 3; cv.style.cap = n; cv.editSel { it.cap = n } }
        val joinC = chip("Join") { val n = (obj().join + 1) % 3; cv.style.join = n; cv.editSel { it.join = n } }
        val dashC = chip("Dash") { val n = (obj().dash + 1) % 4; cv.style.dash = n; cv.editSel { it.dash = n } }
        val arrC = chip("Arrow") { val n = !obj().arrow; cv.style.arrow = n; cv.editSel { it.arrow = n } }
        val shC = chip("Shadow") { val n = !obj().shadow; cv.style.shadow = n; cv.editSel { it.shadow = n } }
        val ratC = chip("Ratio🔒") { cv.lockRatio = !cv.lockRatio; refreshers.forEach { r -> r() } }
        val gradC = chip("Gradient") {
            menu("Fill", listOf("Uniform fill" to { cv.setFill2(null) }, "Gradient end colour…" to { pickColor(obj().fill2 ?: Color.WHITE) { c -> cv.setFill2(c) } },
                "Gradient angle…" to { numDialog("Angle (deg)", listOf("Angle"), listOf(obj().gradAngle)) { v -> cv.editSel { o -> if (o.fill2 == null) o.fill2 = Color.WHITE; o.gradAngle = v[0] } } }))
        }
        val alpha = SeekBar(this).apply {
            max = 100; progress = 100; layoutParams = LinearLayout.LayoutParams(dp(90), -2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) { cv.style.alpha = p / 100f; cv.editSel(false) { it.alpha = p / 100f } } }
                override fun onStartTrackingTouch(s: SeekBar) { if (cv.sel.isNotEmpty()) cv.checkpoint() }
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val ex = field(); val ey = field(); val ew = field(); val eh = field()
        val applyBounds = { cv.setBounds(f(ex.text, 0f) * uf(), f(ey.text, 0f) * uf(), f(ew.text, 1f) * uf(), f(eh.text, 1f) * uf()) }
        listOf(ex, ey, ew, eh).forEach { e -> e.setOnEditorActionListener { _, _, _ -> applyBounds(); e.clearFocus(); true } }
        val unitC = chip("mm") { menu("Units", listOf("mm", "cm", "in", "px").map { u -> u to { cv.doc.unit = u; cv.changed(); cv.status() } }) }
        val titles = mutableListOf<TextView>()
        fun group(title: String, vararg items: View) {
            val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(2), dp(10), dp(2)) }
            val t = label(title).also { it.visibility = View.GONE; titles.add(it) }
            val f = FlowLayout(this); items.forEach { f.addView(it) }
            g.addView(t); g.addView(f); row.addView(g)
        }
        group("Colour", label("Fill"), fillSw, chip("∅") { cv.setFill(null) }, label("Line"), strokeSw, chip("∅") { cv.setStroke(null) })
        group("Outline", chip("−") { cv.setStrokeW(max(0f, obj().strokeW - 0.1f)) }, wTxt, chip("+") { cv.setStrokeW(obj().strokeW + 0.1f) }, capC, joinC, dashC, arrC)
        group("Effects", gradC, shC, label("α"), alpha)
        group("Size & position", ratC, label("X"), ex, label("Y"), ey, label("W"), ew, label("H"), eh, unitC)
        refreshers.add {
            val o = obj(); setSwatch(fillSw, o.fill); setSwatch(strokeSw, o.stroke); wTxt.text = "${fmt(o.strokeW)}mm"
            capC.text = "Cap:" + arrayOf("R", "Sq", "B")[o.cap]; joinC.text = "Join:" + arrayOf("R", "Bev", "Mit")[o.join]; dashC.text = "Dash:" + arrayOf("—", "- -", "···", "-·")[o.dash]
            arrC.text = if (o.arrow) "Arrow✓" else "Arrow"; shC.text = if (o.shadow) "Shadow✓" else "Shadow"; ratC.text = if (cv.lockRatio) "Ratio🔒" else "Ratio🔓"
            alpha.progress = (o.alpha * 100).toInt(); unitC.text = cv.doc.unit
            val b = cv.selBounds()
            if (b != null) {
                val u = uf(); if (!ex.hasFocus()) ex.setText(fmt(b.left / u)); if (!ey.hasFocus()) ey.setText(fmt(b.top / u))
                if (!ew.hasFocus()) ew.setText(fmt(b.width() / u)); if (!eh.hasFocus()) eh.setText(fmt(b.height() / u))
            }
        }
        val sc = AdaptiveScroll(this, row)
        sc.onOrient = { v -> titles.forEach { it.visibility = if (v) View.VISIBLE else View.GONE } }
        return sc
    }

    // ---------- Colour picker (RGB / CMYK / HSV) & palette ----------
    private fun pickColor(init: Int, cb: (Int) -> Unit) {
        var mode = 0; var cur = init or (0xFF shl 24); var updating = false
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val prev = View(this); col.addView(prev, LinearLayout.LayoutParams(-1, dp(40)))
        val hexTv = TextView(this).apply { gravity = Gravity.CENTER }; col.addView(hexTv)
        val bars = List(4) { SeekBar(this) }; val names = List(4) { label("") }
        bars.forEachIndexed { i, b -> col.addView(names[i]); col.addView(b) }
        fun comps(): FloatArray = when (mode) {
            0 -> floatArrayOf(Color.red(cur) / 2.55f, Color.green(cur) / 2.55f, Color.blue(cur) / 2.55f)
            1 -> { val r = Color.red(cur) / 255f; val g = Color.green(cur) / 255f; val b = Color.blue(cur) / 255f; val k = 1 - maxOf(r, g, b)
                if (k >= 1f) floatArrayOf(0f, 0f, 0f, 100f) else floatArrayOf((1 - r - k) / (1 - k) * 100, (1 - g - k) / (1 - k) * 100, (1 - b - k) / (1 - k) * 100, k * 100) }
            else -> { val h = FloatArray(3); Color.colorToHSV(cur, h); floatArrayOf(h[0] / 3.6f, h[1] * 100, h[2] * 100) }
        }
        fun show() {
            updating = true; val c = comps(); val n = if (mode == 1) 4 else 3
            val nm = arrayOf(arrayOf("R", "G", "B", ""), arrayOf("C", "M", "Y", "K"), arrayOf("H", "S", "V", ""))[mode]
            bars.forEachIndexed { i, b -> b.visibility = if (i < n) View.VISIBLE else View.GONE; names[i].visibility = b.visibility; names[i].text = nm[i]; if (i < n) b.progress = c[i].roundToInt() }
            prev.setBackgroundColor(cur); hexTv.text = String.format("#%06X", cur and 0xFFFFFF); updating = false
        }
        bars.forEach { b -> b.max = 100; b.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                if (updating || !u) return; val v = bars.map { it.progress / 100f }
                cur = when (mode) { 0 -> Color.rgb((v[0] * 255).toInt(), (v[1] * 255).toInt(), (v[2] * 255).toInt())
                    1 -> Color.rgb(((1 - v[0]) * (1 - v[3]) * 255).toInt(), ((1 - v[1]) * (1 - v[3]) * 255).toInt(), ((1 - v[2]) * (1 - v[3]) * 255).toInt())
                    else -> Color.HSVToColor(floatArrayOf(v[0] * 360, v[1], v[2])) }
                prev.setBackgroundColor(cur); hexTv.text = String.format("#%06X", cur and 0xFFFFFF)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}; override fun onStopTrackingTouch(s: SeekBar) {} }) }
        val modeBtn = Button(this).apply { text = "Model: RGB"; setOnClickListener { mode = (mode + 1) % 3; text = "Model: " + arrayOf("RGB", "CMYK", "HSV")[mode]; show() } }
        col.addView(modeBtn); show()
        AlertDialog.Builder(this).setTitle("Colour").setView(ScrollView(this).apply { addView(col) }).setPositiveButton("OK") { _, _ -> cb(cur) }.setNegativeButton("Cancel", null).show()
    }

    private fun cmyk(c: Float, m: Float, y: Float, k: Float) = Color.rgb(((1 - c) * (1 - k) * 255).toInt(), ((1 - m) * (1 - k) * 255).toInt(), ((1 - y) * (1 - k) * 255).toInt())
    private fun paletteColors(mode: Int): List<Int> = when (mode) {
        0 -> listOf(0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x00FFFF, 0xFF00FF, 0x808080, 0xC0C0C0, 0x800000, 0x808000, 0x008000, 0x800080, 0x008080, 0x000080, 0xFF8000, 0x8000FF).map { it or (0xFF shl 24) }
        1 -> { val base = listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 1f, 1f), floatArrayOf(1f, 0f, 1f), floatArrayOf(1f, 1f, 0f))
            listOf(0f, .25f, .5f, .75f, 1f).map { k -> cmyk(0f, 0f, 0f, k) } + listOf(1f, .5f, .25f).flatMap { t -> base.map { b -> cmyk(b[0] * t, b[1] * t, b[2] * t, 0f) } } }
        2 -> listOf(1f to 1f, 0.5f to 1f, 1f to 0.5f).flatMap { (s, v) -> (0 until 360 step 20).map { h -> Color.HSVToColor(floatArrayOf(h.toFloat(), s, v)) } }
        else -> getSharedPreferences("vd", MODE_PRIVATE).getString("custom", "")!!.split(",").mapNotNull { it.toIntOrNull() }
    }

    private fun palette(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(2)) }
        fun rebuild() {
            row.removeAllViews()
            row.addView(chip(arrayOf("RGB", "CMYK", "HSV", "Custom")[palMode]) { palMode = (palMode + 1) % 4; rebuild() })
            row.addView(chip("＋") { val sp = getSharedPreferences("vd", MODE_PRIVATE); val cur = obj().fill ?: Color.WHITE
                sp.edit().putString("custom", (sp.getString("custom", "")!!.split(",").filter { it.isNotEmpty() } + cur.toString()).joinToString(",")).apply(); palMode = 3; rebuild() })
            for (c in paletteColors(palMode)) row.addView(swatch { cv.setFill(c) }.also { v -> setSwatch(v, c); v.setOnLongClickListener { cv.setStroke(c); true } })
        }
        rebuild(); return hscroll(row)
    }

    // ---------- Object manager (layers + objects) ----------
    private fun objectManager(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        refreshers.add {
            col.removeAllViews()
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("＋Layer" to { cv.doc.layers.add(Layer("Layer ${cv.doc.layers.size + 1}")); cv.activeLayer = cv.doc.layers.lastIndex; cv.changed() },
                "▲" to { cv.order(true, true) }, "▼" to { cv.order(false, true) }, "⧉" to { cv.duplicate() }, "🗑" to { cv.deleteSel() }).forEach { (t, a) -> bar.addView(chip(t, a)) }
            col.addView(hscroll(bar))
            for (li in cv.doc.layers.indices.reversed()) {
                val l = cv.doc.layers[li]; val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(chip(if (l.visible) "👁" else "—") { l.visible = !l.visible; cv.changed() })
                r.addView(chip(if (l.locked) "🔒" else "🔓") { l.locked = !l.locked; cv.changed() })
                r.addView(chip("α${(l.opacity * 100).toInt()}") { numDialog("Layer opacity %", listOf("0-100"), listOf(l.opacity * 100)) { v -> l.opacity = (v[0] / 100f).coerceIn(0f, 1f); cv.changed() } })
                r.addView(TextView(this).apply { text = (if (li == cv.activeLayer) "● " else "○ ") + l.name; setTextColor(if (li == cv.activeLayer) GOLD else Color.WHITE); textSize = 12f; setPadding(dp(6), dp(6), dp(6), dp(6)); setOnClickListener { cv.activeLayer = li; cv.changed() } })
                col.addView(r)
                for (i in cv.doc.objs.indices.reversed()) {
                    val o = cv.doc.objs[i]; if (o.layer != li) continue
                    val or = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, 0, 0) }
                    or.addView(chip(if (o.visible) "👁" else "—") { o.visible = !o.visible; cv.changed() })
                    or.addView(chip(if (o.locked) "🔒" else "🔓") { o.locked = !o.locked; cv.changed() })
                    or.addView(TextView(this).apply {
                        text = "${o.kind.name.lowercase()} #${i + 1}${if (o.group != 0) " ▣${o.group}" else ""}${if (o.holes.isNotEmpty()) " ◌" else ""}${if (o.kind == Kind.TEXT) " “" + o.text.take(10) + "”" else ""}"
                        setTextColor(if (o in cv.sel) GOLD else Color.WHITE); textSize = 12f; setPadding(dp(6), dp(5), dp(6), dp(5))
                        setOnClickListener { if (!l.locked) cv.selectOne(o) }
                    })
                    col.addView(or)
                }
            }
        }
        return ScrollView(this).apply { addView(col) }
    }

    // ---------- Status bar with snapping toggles ----------
    private fun statusBar(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(2)) }
        statusTv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f; minWidth = dp(120) }
        fun toggle(name: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            lateinit var c: TextView
            c = chip(name) { set(!get()); cv.invalidate(); refreshers.forEach { it() } }
            refreshers.add { c.setTextColor(if (get()) GOLD else Color.WHITE) }; row.addView(c)
        }
        row.addView(statusTv)
        toggle("Grid⌗", { cv.showGrid }, { cv.showGrid = it }); toggle("⊞Snap", { cv.snapGrid }, { cv.snapGrid = it })
        toggle("Guides", { cv.snapGuides }, { cv.snapGuides = it }); toggle("Objects", { cv.snapObjs }, { cv.snapObjs = it })
        return hscroll(row)
    }

    // ---------- Quick bar (menu / undo / redo / panel toggle) ----------
    private fun quickBar(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(2)) }
        listOf("☰" to { mainMenu() }, "↶" to { cv.undo() }, "↷" to { cv.redo() }, "✓" to { cv.finishPen() }, "⛶" to { cv.fit() }, "◩" to { togglePanels() }).forEach { (t, a) -> row.addView(chip(t, a)) }
        return row
    }

    private fun togglePanels() {
        if (!panelsHidden) { hiddenBefore.clear(); panels.forEach { (n, p) -> if (n != "Quick") { if (p.collapsed) hiddenBefore.add(n) else collapse(p) } } }
        else panels.forEach { (n, p) -> if (n != "Quick" && n !in hiddenBefore) expand(p) }
        panelsHidden = !panelsHidden; root.postDelayed({ resolveAll(); reflow() }, 150)
    }

    private fun panelMenu() {
        val names = panels.keys.filter { it != "Quick" }.toTypedArray(); val checked = BooleanArray(names.size) { !panels[names[it]]!!.collapsed }
        AlertDialog.Builder(this).setTitle("Show / collapse panels").setMultiChoiceItems(names, checked) { _, i, on -> panels[names[i]]!!.let { if (on) expand(it) else collapse(it) } }
            .setNeutralButton("Tidy titles") { _, _ -> tidyCollapsed() }.setPositiveButton("Done", null).setOnDismissListener { root.postDelayed({ resolveAll(); reflow() }, 150) }.show()
    }

    // ---------- Menus ----------
    private fun mainMenu() = menu("VecDraw by Topz", listOf(
        "File" to { fileMenu() }, "Edit" to { editMenu() }, "Arrange / Transform" to { arrangeMenu() },
        "Effects" to { effectsMenu() }, "Page & Grid" to { pageMenu() }, "Panels" to { panelMenu() }, "Settings…" to { settingsDialog() }, "Reset panel layout" to { layoutPanels(true) }))

    private fun fileMenu() = menu("File", listOf(
        "New" to { newDrawing() },
        "Open project (.vdt)…" to { openProject.launch(arrayOf("*/*")) },
        "Save project (.vdt)…" to { write("drawing.vdt", "application/json") { it.write(cv.doc.toJson().toString().toByteArray()) } },
        "Export SVG (vector)…" to { write("drawing.svg", "image/svg+xml") { it.write(Export.svg(cv.doc).toByteArray()) } },
        "Export PDF (vector)…" to { write("drawing.pdf", "application/pdf") { Export.pdf(cv.doc, it) } },
        "Export PNG…" to { dpiMenu(false) }, "Export JPEG…" to { dpiMenu(true) },
        "Import image (reference trace)…" to { openImage.launch(arrayOf("image/*")) },
        "Import SVG…" to { openSvg.launch(arrayOf("*/*")) }))

    private fun dpiMenu(jpeg: Boolean) = menu("Resolution", listOf(72, 150, 300, 600).map { dpi ->
        "$dpi DPI" to {
            val eff = Export.safeDpi(cv.doc, dpi); if (eff < dpi - 0.5f) toast("Reduced to ${eff.roundToInt()} DPI (memory limit)")
            write(if (jpeg) "drawing.jpg" else "drawing.png", if (jpeg) "image/jpeg" else "image/png") { os ->
                val b = Export.bitmap(cv.doc, eff, jpeg); b.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, os); b.recycle()
            }
        }
    })

    private fun editMenu() = menu("Edit", listOf(
        "Undo" to { cv.undo() }, "Redo" to { cv.redo() }, "Copy" to { cv.copySel() }, "Cut" to { cv.cutSel() }, "Paste" to { cv.paste(false) }, "Paste in place" to { cv.paste(true) }, "Select all" to { cv.selectAll() }, "Duplicate" to { cv.duplicate() }, "Delete" to { cv.deleteSel() },
        "Edit text…" to { cv.sel.firstOrNull { it.kind == Kind.TEXT }?.let { textDialog(it, false) } },
        "Fit text to selected path" to { cv.attachTextToPath() }, "Detach text from path" to { cv.detachText() }))

    private fun arrangeMenu() = menu("Arrange / Transform", listOf(
        "Group" to { cv.group() }, "Ungroup" to { cv.ungroup() }, "Combine (makes holes)" to { cv.combine() }, "Break apart" to { cv.breakApart() },
        "Shape operations…" to { shapeOpsMenu() }, "Align…" to { alignMenu() }, "Distribute…" to { distributeMenu() },
        "Bring to front" to { cv.order(true, false) }, "Send to back" to { cv.order(false, false) },
        "Flip horizontal" to { cv.flipH() }, "Flip vertical" to { cv.flipV() }, "Rotate 90°" to { cv.rotate(90f) },
        "Rotate…" to { numDialog("Rotate", listOf("Degrees"), listOf(15f)) { cv.rotate(it[0]) } },
        "Skew…" to { numDialog("Skew", listOf("Horizontal °", "Vertical °"), listOf(15f, 0f)) { cv.skew(it[0], it[1]) } }))

    private fun effectsMenu() = menu("Effects", listOf(
        "Drop shadow on/off" to { val n = !obj().shadow; cv.style.shadow = n; cv.editSel { it.shadow = n } },
        "Contour…" to { numDialog("Contour", listOf("Offset per step (mm)", "Steps", "Outward = 1, inward = 0", "Corners: 0 round, 1 sharp, 2 bevel"), listOf(2f, 3f, 1f, 0f)) { cv.contour(it[0], it[1].toInt().coerceIn(1, 40), it[2] > 0.5f, it[3].toInt()) } },
        "Blend (select 2 shapes)…" to { numDialog("Blend", listOf("Steps"), listOf(6f)) { cv.blend(it[0].toInt().coerceIn(1, 60)) } },
        "Extrude (perspective)…" to { numDialog("Perspective extrude", listOf("Depth %", "Vanishing point X offset (mm)", "Vanishing point Y offset (mm)", "Steps"), listOf(40f, 40f, -30f, 20f)) { cv.extrudePersp(it[0], it[1], it[2], it[3].toInt().coerceIn(2, 80)) } },
        "Extrude (oblique)…" to { numDialog("Extrude", listOf("Depth (mm)", "Angle (°)", "Steps"), listOf(12f, 45f, 14f)) { cv.extrude(it[0], it[1], it[2].toInt().coerceIn(2, 60)) } },
        "Transparency ▸ (uniform = α slider)" to { transMenu() }))

    private fun pageMenu() = menu("Page & Grid", listOf(
        "A4 (210×297 mm)" to { setPage(210f, 297f) }, "A3 (297×420 mm)" to { setPage(297f, 420f) }, "Letter (215.9×279.4 mm)" to { setPage(215.9f, 279.4f) },
        "Custom size…" to { val u = uf(); numDialog("Page size (${cv.doc.unit})", listOf("Width", "Height"), listOf(cv.doc.w / u, cv.doc.h / u)) { setPage(it[0] * u, it[1] * u) } },
        "Swap orientation" to { setPage(cv.doc.h, cv.doc.w) },
        "Grid spacing…" to { val u = uf(); numDialog("Grid (${cv.doc.unit})", listOf("Spacing"), listOf(cv.doc.grid / u)) { cv.doc.grid = (it[0] * u).coerceAtLeast(0.1f); cv.invalidate() } },
        "Add vertical guide…" to { val u = uf(); numDialog("Guide X (${cv.doc.unit})", listOf("Position"), listOf(10f)) { cv.doc.vg.add(it[0] * u); cv.invalidate() } },
        "Add horizontal guide…" to { val u = uf(); numDialog("Guide Y (${cv.doc.unit})", listOf("Position"), listOf(10f)) { cv.doc.hg.add(it[0] * u); cv.invalidate() } },
        "Clear guides" to { cv.doc.vg.clear(); cv.doc.hg.clear(); cv.invalidate() }))

    private fun setPage(w: Float, h: Float) { cv.doc.w = w; cv.doc.h = h; cv.fit(); cv.changed() }

    // ---------- Text dialog ----------
    private fun textDialog(o: VObj, isNew: Boolean) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val et = EditText(this).apply { setText(o.text); minLines = 2; hint = "Text" }; col.addView(et)
        val sz = field(200).also { it.setText(fmt(o.size * 72f / 25.4f)); col.addView(label("Size (pt)")); col.addView(it) }
        val kern = field(200).also { it.setText(fmt(o.spacing)); col.addView(label("Kerning / letter-spacing (em)")); col.addView(it) }
        val lead = field(200).also { it.setText(fmt(o.leading)); col.addView(label("Leading (× size)")); col.addView(it) }
        val bold = CheckBox(this).apply { text = "Bold"; isChecked = o.bold }; val ital = CheckBox(this).apply { text = "Italic"; isChecked = o.italic }
        col.addView(bold); col.addView(ital)
        val al = Spinner(this).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("Align left", "Align centre", "Align right")); setSelection(o.align) }; col.addView(al)
        AlertDialog.Builder(this).setTitle(if (isNew) "New text" else "Edit text").setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("OK") { _, _ ->
                if (!isNew) cv.checkpoint()
                o.text = et.text.toString(); o.size = f(sz.text, 12f).coerceAtLeast(1f) * 25.4f / 72f; o.spacing = f(kern.text, 0f); o.leading = f(lead.text, 1.2f)
                o.bold = bold.isChecked; o.italic = ital.isChecked; o.align = al.selectedItemPosition
                cv.commitText(o, isNew)
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- align / distribute / shape operations ----------
    private var alignToPage = false

    private fun alignMenu() {
        menu("Align to " + (if (alignToPage) "page" else "selection"), listOf(
            (if (alignToPage) "Aligning to: page  (tap to use selection)" else "Aligning to: selection  (tap to use page)") to { alignToPage = !alignToPage; alignMenu() },
            "Left" to { cv.align(0, alignToPage) }, "Centre horizontally" to { cv.align(1, alignToPage) }, "Right" to { cv.align(2, alignToPage) },
            "Top" to { cv.align(3, alignToPage) }, "Centre vertically" to { cv.align(4, alignToPage) }, "Bottom" to { cv.align(5, alignToPage) }))
    }

    private fun distributeMenu() {
        menu("Distribute (3+ objects)", listOf("Equal horizontal gaps" to { cv.distribute(0) }, "Equal vertical gaps" to { cv.distribute(1) },
            "Equal horizontal centres" to { cv.distribute(2) }, "Equal vertical centres" to { cv.distribute(3) }))
    }

    private fun shapeOpsMenu() {
        menu("Shape operations (closed shapes)", listOf(
            "Weld (merge into one)" to { cv.weld() }, "Intersect (shared area)" to { cv.intersect() }, "Exclude (overlaps become holes)" to { cv.exclude() },
            "Trim (front shape cuts the others, keep it)" to { cv.trim(true) }, "Subtract (front shape cuts, then removed)" to { cv.trim(false) }))
    }

    // ---------- Transparency (fountain / bitmap pattern) ----------
    private fun transMenu() = menu("Transparency", listOf(
        "Remove transparency mask" to { cv.transClear() },
        "Fountain (gradient)…" to { numDialog("Fountain transparency", listOf("Start opacity %", "End opacity %", "Angle °"), listOf(100f, 0f, 0f)) { cv.transFountain(it[0] / 100f, it[1] / 100f, it[2]) } },
        "Bitmap pattern…" to { patternMenu() }))

    private fun patternMenu() = menu("Pattern", Pat.NAMES.mapIndexed { i, n -> n to { patternArgs(i) } } + ("Image from file…" to { patternArgs(-1) }))

    private fun patternArgs(idx: Int) = numDialog("Pattern transparency", listOf("White-area opacity %", "Black-area opacity %", "Tile size (mm)"), listOf(100f, 0f, 10f)) {
        if (idx >= 0) cv.transPattern(idx, it[0] / 100f, it[1] / 100f, it[2])
        else { maskArgs = floatArrayOf(it[0] / 100f, it[1] / 100f, it[2]); openMask.launch(arrayOf("image/*")) }
    }

    private fun loadMask(uri: Uri) = try {
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1; while (max(o.outWidth, o.outHeight) / s > 512) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        if (bmp == null) toast("Unsupported image") else { cv.transPatternImage(bmp, maskArgs[0], maskArgs[1], maskArgs[2]); toast("Light areas stay visible, dark areas fade") }
    } catch (e: Exception) { toast("Could not load mask: ${e.message}") }

    private fun loadSvg(uri: Uri) = try {
        val txt = contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
        val list = SvgImport.parse(txt, cv.activeLayer.coerceIn(0, cv.doc.layers.lastIndex))
        if (list.isEmpty()) toast("No importable shapes (text, images, clips and <use> are skipped)")
        else { cv.importObjects(list); toast("Imported ${list.size} shapes") }
    } catch (e: Exception) { toast("Could not import SVG: ${e.message}") }

    // ---------- Storage (SAF) ----------
    private fun loadProject(uri: Uri) = try {
        val txt = contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
        cv.newDoc(Doc.fromJson(JSONObject(txt))); toast("Opened")
    } catch (e: Exception) { toast("Could not open: ${e.message}") }

    private fun loadImage(uri: Uri) = try {
        val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1; while (max(o.outWidth, o.outHeight) / s > 3000) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        if (bmp == null) toast("Unsupported image") else { cv.importImage(bmp); toast("Placed on a locked 50% reference layer; draw on 'Trace'") }
    } catch (e: Exception) { toast("Could not import: ${e.message}") }
}
