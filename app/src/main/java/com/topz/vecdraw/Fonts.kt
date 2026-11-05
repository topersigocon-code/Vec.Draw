package com.topz.vecdraw

import android.content.ContentResolver
import android.graphics.Typeface
import android.net.Uri
import android.util.Base64
import java.io.File

/** Built-in font families plus .ttf / .otf files the user imports (copied into the app's private storage, fully offline). */
object Fonts {
    val BUILTIN = listOf("sans", "serif", "mono", "cursive", "casual")
    private var dir: File? = null
    private val cache = HashMap<String, Typeface>()

    fun init(filesDir: File) { dir = File(filesDir, "fonts").also { it.mkdirs() } }

    /** Names offered in the text dialog. */
    fun all(): List<String> = BUILTIN + (dir?.listFiles()?.map { it.name }?.sorted() ?: emptyList())

    private fun base(name: String): Typeface = cache.getOrPut(name) {
        when (name) {
            "", "sans" -> Typeface.SANS_SERIF
            "serif" -> Typeface.SERIF
            "mono" -> Typeface.MONOSPACE
            "cursive" -> Typeface.create("cursive", Typeface.NORMAL)
            "casual" -> Typeface.create("casual", Typeface.NORMAL)
            else -> try { val f = File(dir, name); if (f.exists()) Typeface.createFromFile(f) else Typeface.SANS_SERIF } catch (e: Exception) { Typeface.SANS_SERIF }
        }
    }

    fun typeface(name: String, bold: Boolean, italic: Boolean): Typeface {
        val style = when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
        return Typeface.create(base(name), style)
    }

    fun isCustom(name: String) = name.isNotEmpty() && name !in BUILTIN

    fun cssFamily(name: String): String = when (name) {
        "", "sans" -> "sans-serif"; "serif" -> "serif"; "mono" -> "monospace"; "cursive" -> "cursive"; "casual" -> "fantasy"
        else -> "'" + name.substringBeforeLast('.').replace("'", "") + "', sans-serif"
    }

    fun fontData(name: String): String? { val f = File(dir, name); return if (f.exists()) Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else null }
    fun mime(name: String) = if (name.endsWith(".otf", true)) "font/otf" else "font/ttf"

    /** Copy a picked font file into private storage; returns its stored name, or null if it could not be used. */
    fun import(cr: ContentResolver, uri: Uri, displayName: String?): String? {
        val d = dir ?: return null
        var name = (displayName ?: "font.ttf").replace(Regex("[^A-Za-z0-9._-]"), "_"); if (!name.contains('.')) name += ".ttf"
        val f = File(d, name)
        return try {
            cr.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            if (f.length() < 100) throw IllegalStateException("empty")
            Typeface.createFromFile(f); cache.remove(name); name
        } catch (e: Exception) { f.delete(); null }
    }
}
