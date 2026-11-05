package com.rgbotz.tv

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("rgtv", Context.MODE_PRIVATE)

    var cacheMb: Int
        get() = sp.getInt("cacheMb", 300)
        set(v) { sp.edit().putInt("cacheMb", v).apply() }
    /** 1 = Zyada (bigger buffer, default), 0 = Normal */
    var bufferMode: Int
        get() = sp.getInt("bufferMode", 1)
        set(v) { sp.edit().putInt("bufferMode", v).apply() }
    /** Smooth mode: on a stall pause until a big lead is buffered; wait for a lead at start (default ON) */
    var smooth: Boolean
        get() = sp.getBoolean("smooth", true)
        set(v) { sp.edit().putBoolean("smooth", v).apply() }
    /** Music auto-resume: last track, position, shuffle/repeat (restored when Music is opened from Home) */
    var musicId: Long
        get() = sp.getLong("musicId", 0)
        set(v) { sp.edit().putLong("musicId", v).apply() }
    var musicPos: Long
        get() = sp.getLong("musicPos", 0)
        set(v) { sp.edit().putLong("musicPos", v).apply() }
    var musicShuffle: Boolean
        get() = sp.getBoolean("musicShuffle", false)
        set(v) { sp.edit().putBoolean("musicShuffle", v).apply() }
    var musicRepeat: Int
        get() = sp.getInt("musicRepeat", 0)
        set(v) { sp.edit().putInt("musicRepeat", v).apply() }
    var audioLang: String
        get() = sp.getString("audioLang", "hi") ?: "hi"
        set(v) { sp.edit().putString("audioLang", v).apply() }
    /** "" = subtitles off, else preferred subtitle language / label */
    var subLang: String
        get() = sp.getString("subLang", "") ?: ""
        set(v) { sp.edit().putString("subLang", v).apply() }
    var subSize: Int   // 0 small, 1 normal, 2 big
        get() = sp.getInt("subSize", 1)
        set(v) { sp.edit().putInt("subSize", v).apply() }
    var softAudio: Boolean   // always decode audio in software (plays every codec)
        get() = sp.getBoolean("softAudio", true)
        set(v) { sp.edit().putBoolean("softAudio", v).apply() }
    /** Live TV: last fetched channel count (home tile par dikhata hai) */
    var tvCount: Int
        get() = sp.getInt("tvCount", 0)
        set(v) { sp.edit().putInt("tvCount", v).apply() }
    /** Live TV: last picked category ("All", "Sports"…) — agli baar wahi khule */
    var tvCategory: String
        get() = sp.getString("tvCategory", "All") ?: "All"
        set(v) { sp.edit().putString("tvCategory", v).apply() }
    /** Live TV: server switch — kaunsa server (0=JioStar, 1=+PlusTV, 2=InPlex+), agli baar wahi khule */
    var tvServer: Int
        get() = sp.getInt("tvServer", 0)
        set(v) { sp.edit().putInt("tvServer", v).apply() }
    /** "" = app ke andar wala server (isi TV par), warna remote server ka URL (http://...) — Coolify/PC */
    var serverUrl: String
        get() = sp.getString("serverUrl", "") ?: ""
        set(v) { sp.edit().putString("serverUrl", v.trim()).apply() }

    fun pos(id: Long): Long = sp.getLong("pos_$id", 0)
    fun dur(id: Long): Long = sp.getLong("dur_$id", 0)
    fun savePos(id: Long, pos: Long, dur: Long) {
        val e = sp.edit()
        if (dur > 0 && (pos > dur - 20_000 || pos > dur * 0.96)) {
            e.remove("pos_$id").remove("dur_$id").remove("at_$id")
        } else if (pos > 5_000) {
            e.putLong("pos_$id", pos).putLong("dur_$id", dur).putLong("at_$id", System.currentTimeMillis())
        }
        e.apply()
    }
    fun clearPos(id: Long) { sp.edit().remove("pos_$id").remove("dur_$id").remove("at_$id").apply() }

    /** ids of partly watched videos, most recent first */
    fun resumeIds(): List<Long> = sp.all.keys.filter { it.startsWith("at_") }
        .mapNotNull { k -> k.removePrefix("at_").toLongOrNull()?.let { it to sp.getLong(k, 0) } }
        .sortedByDescending { it.second }.map { it.first }
}
