package com.rgbotz.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.rgbotz.tv.core.Kind
import com.rgbotz.tv.core.Local
import java.io.File

class SettingsActivity : BaseActivity() {
    private val app get() = App.inst
    private lateinit var box: LinearLayout
    private val libListener: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        box = findViewById(R.id.rows)
        render()
        box.getChildAt(1)?.requestFocus()
    }

    override fun onResume() { super.onResume(); app.lib.listeners.add(libListener) }
    override fun onPause() { super.onPause(); app.lib.listeners.remove(libListener) }

    private fun row(title: String, value: String, click: (() -> Unit)?): View {
        val v = LayoutInflater.from(this).inflate(R.layout.row_setting, box, false)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.value).text = value
        if (click == null) { v.isFocusable = false; v.alpha = 0.85f } else v.setOnClickListener { click(); val f = box.indexOfChild(v); render(); box.getChildAt(f)?.requestFocus() }
        box.addView(v)
        return v
    }

    private fun render() {
        val lib = app.lib
        val p = app.prefs
        box.removeAllViews()
        val (st, _) = statusLine()
        row("Status", st, null)
        row("Library refresh karo", if (lib.scanning) "chal raha hai… ${lib.scannedUpTo}" else
            "${Fmt.count(lib.count(Kind.VIDEO))} videos · ${Fmt.count(lib.count(Kind.AUDIO))} gaane · ${Fmt.count(lib.count(Kind.PHOTO))} photos") { app.refreshSoon() }
        row("Server", if (p.serverUrl.isBlank()) "Isi TV par (app ka apna server)" else "Remote: ${p.serverUrl}") { askServer() }
        row("Buffer", if (p.bufferMode == 1) "Zyada (bada buffer, kam atakega) — recommended" else "Normal (kam memory)") {
            p.bufferMode = 1 - p.bufferMode
            app.streams.aheadMb = BufferProfile.of(this, p.bufferMode == 1, p.smooth).aheadMb
        }
        row("Smooth", if (p.smooth) "On (ruke to bada buffer bharke chalega — kam atakega)" else "Off (jaldi chalega, par atak sakta hai)") { p.smooth = !p.smooth }
        row("Audio decode", if (p.softAudio) "Software (har format chalega: AC3, EAC3, DTS, TrueHD)" else "TV ka decoder pehle, software backup") { p.softAudio = !p.softAudio }
        row("Pasand ki audio bhasha", langLabel(p.audioLang)) {
            p.audioLang = when (p.audioLang) { "hi" -> "en"; "en" -> "ta"; "ta" -> "te"; else -> "hi" }
        }
        row("Subtitles shuru me", if (p.subLang.isEmpty()) "Off" else "On (${langLabel(p.subLang)})") { p.subLang = if (p.subLang.isEmpty()) "en" else "" }
        row("Subtitle size", arrayOf("Chhota", "Normal", "Bada")[p.subSize]) { p.subSize = (p.subSize + 1) % 3 }
        row("Resume list saaf karo", "${p.resumeIds().size} adhoore") { p.resumeIds().forEach { p.clearPos(it) } }
        row("App", "v${BuildConfig.VERSION_NAME} · bot se, bina login · media TV pe save nahi hota", null)
    }

    private fun langLabel(l: String) = when (l) { "hi" -> "Hindi"; "en" -> "English"; "ta" -> "Tamil"; "te" -> "Telugu"; "" -> "—"; else -> l }

    /** Server URL dalo ya saaf karo. Khaali = app ka apna server (isi TV par). */
    private fun askServer() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val et = EditText(this).apply {
            hint = "http://1.2.3.4:8080"
            singleLine = true
            setText(app.prefs.serverUrl)
        }
        box.addView(et)
        AlertDialog.Builder(this)
            .setTitle("Server URL")
            .setMessage("Khaali chhodo = app ka apna server (isi TV par).\nRemote server (Coolify/PC) ka URL dalo — phir TV apna Python server nahi chalayega.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> app.prefs.serverUrl = et.text.toString().trim(); applyServer() }
            .setNeutralButton("Default") { _, _ -> app.prefs.serverUrl = ""; applyServer() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyServer() {
        Local.setCustom(app.prefs.serverUrl)
        app.lib.reset()
    }

    private fun dirSize(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)
}
