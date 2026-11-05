package com.rgbotz.tv

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextClock
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

object Fonts {
    @Volatile private var reg: Typeface? = null
    @Volatile private var bold: Typeface? = null
    fun regular(c: Context): Typeface? = reg ?: try { ResourcesCompat.getFont(c, R.font.comic_regular) } catch (_: Throwable) { null }.also { reg = it }
    fun bold(c: Context): Typeface? = bold ?: try { ResourcesCompat.getFont(c, R.font.comic_bold) } catch (_: Throwable) { null }.also { bold = it }
    fun inflate(a: Activity, res: Int): View = a.layoutInflater.inflate(res, null)
    fun apply(v: TextView) {
        val b = v.typeface?.isBold == true
        (if (b) bold(v.context) else regular(v.context))?.let { v.typeface = it }
    }
}

/** Sets the brand font (Comic Neue) on every TextView, without AppCompat (works on Android 5). */
class FontFactory : LayoutInflater.Factory2 {
    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        val v: TextView = when (name) {
            "TextView" -> TextView(context, attrs)
            "EditText" -> EditText(context, attrs)
            "TextClock" -> TextClock(context, attrs)
            else -> return null
        }
        Fonts.apply(v)
        return v
    }
    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? = onCreateView(null, name, context, attrs)
}

open class BaseActivity : Activity() {
    private val updListener: () -> Unit = { runOnUiThread { Updater.check(this) } }

    override fun onResume() {
        super.onResume()
        App.inst.lib.listeners.add(updListener)
        Updater.check(this)
    }

    override fun onPause() {
        super.onPause()
        App.inst.lib.listeners.remove(updListener)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (layoutInflater.factory2 == null && layoutInflater.factory == null) layoutInflater.factory2 = FontFactory()
        super.onCreate(savedInstanceState)
    }

    /** Let focused (scaled-up) items draw outside their parent, except inside scrolling lists. */
    private fun unclip(v: View) {
        if (v is android.view.ViewGroup && v !is androidx.recyclerview.widget.RecyclerView && v !is android.widget.ScrollView) {
            v.clipChildren = false
            for (i in 0 until v.childCount) unclip(v.getChildAt(i))
        }
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        (window.decorView as? android.view.ViewGroup)?.let { unclip(it) }
        findViewById<TextView>(R.id.footer)?.let { f ->
            val t = f.text.toString(); val i = t.indexOf('♥')
            if (i >= 0) f.text = SpannableString(t).apply { setSpan(ForegroundColorSpan(0xFFFA2D48.toInt()), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }
}
