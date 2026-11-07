package com.rgbotz.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.rgbotz.tv.core.Item
import com.rgbotz.tv.core.Parse
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : BaseActivity() {
    private val app get() = App.inst
    private lateinit var item: Item
    private var ids: LongArray = LongArray(0)
    private var player: ExoPlayer? = null
    private lateinit var pv: PlayerView
    private lateinit var controls: View
    private lateinit var seek: TvSeekBar
    private lateinit var tPos: TextView
    private lateinit var tDur: TextView
    private lateinit var btnPlay: ImageView
    private lateinit var loading: View
    private lateinit var trackPanel: View
    private lateinit var resumePanel: View
    private lateinit var errorPanel: View
    private val h = Handler(Looper.getMainLooper())
    private var mainFileId = 0
    private var firstTracks = true
    private var smoothLc: SmoothLoadControl? = null

    private val hideControls = Runnable { if (player?.isPlaying == true && trackPanel.visibility != View.VISIBLE) controls.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            seek.duration = p.duration.coerceAtLeast(0)
            seek.position = p.currentPosition
            seek.buffered = p.bufferedPosition
            tPos.text = Fmt.ms(seek.shown())
            tDur.text = Fmt.ms(p.duration.coerceAtLeast(0))
            if (loading.visibility == View.VISIBLE) updateLoading()
            h.postDelayed(this, 500)
        }
    }
    private val saver = object : Runnable {
        override fun run() { savePos(); h.postDelayed(this, 5000) }
    }

    // ---- Stall watchdog: buffering atak gayi (dheelara DC / mara hua chunk) to khud unstick karo ----
    private var stallSince = 0L
    private val stallWatch = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState != Player.STATE_BUFFERING || !p.playWhenReady) { stallSince = 0L; return }
            val stuck = System.currentTimeMillis() - stallSince
            if (stuck >= 55_000) {
                // 55s tak kuch nahi badha — stream poora reset, wahi position se (naye sire se HTTP)
                val pos = p.currentPosition
                stallSince = 0L
                h.removeCallbacks(this)
                start(item, pos)
                return
            }
            // 25s par: wahi par re-seek — request naye sire se jaati hai, atka hua chunk bypass ho jata hai
            p.seekTo(p.currentPosition)
            h.postDelayed(this, 25_000)
        }
    }

    private fun armStallWatch() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_BUFFERING && p.playWhenReady && stallSince == 0L) {
            stallSince = System.currentTimeMillis()
            h.postDelayed(stallWatch, 25_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        val id = intent.getLongExtra("id", 0)
        ids = intent.getLongArrayExtra("list") ?: longArrayOf(id)
        item = app.lib.get(id) ?: run { finish(); return }
        pv = findViewById(R.id.pv)
        controls = findViewById(R.id.controls)
        seek = findViewById(R.id.seek)
        tPos = findViewById(R.id.tPos)
        tDur = findViewById(R.id.tDur)
        btnPlay = findViewById(R.id.btnPlay)
        loading = findViewById(R.id.loading)
        trackPanel = findViewById(R.id.trackPanel)
        resumePanel = findViewById(R.id.resumePanel)
        errorPanel = findViewById(R.id.errorPanel)
        pv.useController = false
        pv.keepScreenOn = true  // view-level bhi — Mi TV jaise devices ke liye double safety
        pv.subtitleView?.apply {
            setStyle(CaptionStyleCompat(Color.WHITE, 0x00000000, 0x00000000, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, when (app.prefs.subSize) { 0 -> 20f; 2 -> 32f; else -> 26f })
            setBottomPaddingFraction(0.06f)
        }
        listOf(R.id.btnRew, R.id.btnPlay, R.id.btnFwd).forEach { Focus.grow(findViewById(it), 1.12f) }
        listOf(R.id.btnTracks, R.id.btnNext).forEach { Focus.grow(findViewById(it), 1.06f) }
        findViewById<View>(R.id.btnPlay).setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnRew).setOnClickListener { seekBy(-10_000) }
        findViewById<View>(R.id.btnFwd).setOnClickListener { seekBy(10_000) }
        findViewById<View>(R.id.btnTracks).setOnClickListener { showTracks() }
        findViewById<View>(R.id.btnNext).setOnClickListener { playNext() }
        findViewById<View>(R.id.btnRetry).setOnClickListener { errorPanel.visibility = View.GONE; player?.prepare(); player?.play() }
        seek.onScrub = { tPos.text = Fmt.ms(it); poke() }
        seek.onSeek = { player?.seekTo(it); poke() }
        start(item)
    }

    private fun buildPlayer(): ExoPlayer {
        val rf = DefaultRenderersFactory(this)
            .setExtensionRendererMode(if (app.prefs.softAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val ef = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        val msf = DefaultMediaSourceFactory(TgDataSource.Factory(app), ef)
        // RAM buffer is capped by bytes (low-end TVs); the big read-ahead lives on disk in the TDLib temp cache
        val smooth = app.prefs.smooth
        val bp = BufferProfile.of(this, app.prefs.bufferMode == 1, smooth)
        app.streams.aheadMb = bp.aheadMb
        val dlc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bp.minMs, bp.maxMs, bp.startMs, bp.rebufferMs)
            .setTargetBufferBytes(bp.ramBytes)
            .setBackBuffer(3_000, false)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val lc = SmoothLoadControl(dlc, app.streams, filmBytesPerSec(), smooth)
        smoothLc = lc
        val p = ExoPlayer.Builder(this, rf).setMediaSourceFactory(msf).setLoadControl(lc).build()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(app.prefs.audioLang, "hi", "en")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, app.prefs.subLang.isEmpty())
            .setPreferredTextLanguage(app.prefs.subLang.ifEmpty { null })
            .build()
        p.addListener(object : Player.Listener {
            private var prevState = Player.STATE_IDLE
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) lc.justSeeked = true
            }
            override fun onPlaybackStateChanged(state: Int) {
                lc.onState(prevState, state, p.playWhenReady); prevState = state
                loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_BUFFERING) {
                    updateLoading()
                    armStallWatch()
                } else {
                    stallSince = 0L
                    h.removeCallbacks(stallWatch)
                }
                if (state == Player.STATE_ENDED) { app.prefs.clearPos(item.id); playNext() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                if (isPlaying) poke() else { h.removeCallbacks(hideControls) }
                armStallWatch()
            }
            override fun onPlayerError(error: PlaybackException) {
                loading.visibility = View.GONE
                errorPanel.visibility = View.VISIBLE
                val msg = error.cause?.message ?: error.errorCodeName
                findViewById<TextView>(R.id.errorText).text = "Ye video nahi chal paya\n" + msg
                findViewById<View>(R.id.btnRetry).requestFocus()
                Report.send("play_${item.id}", "Video nahi chala: ${item.title}", msg)
            }
            override fun onTracksChanged(tracks: Tracks) {
                if (firstTracks && tracks.groups.isNotEmpty()) { firstTracks = false; updateMeta(tracks) }
            }
        })
        return p
    }

    private fun start(it: Item, startPos: Long = -1L) {
        item = it
        firstTracks = true
        stallSince = 0L
        h.removeCallbacks(stallWatch)
        findViewById<TextView>(R.id.title).text = it.title
        findViewById<TextView>(R.id.meta).text = (listOf(it.quality, Fmt.size(it.size), Fmt.dur(it.duration.toLong())).filter { s -> s.isNotEmpty() } +
                if (it.oversize) listOf("2GB+") else emptyList()).joinToString(" · ")
        findViewById<View>(R.id.btnRetry).visibility = View.VISIBLE
        player?.release()
        if (mainFileId != 0) app.streams.release(mainFileId)
        val p = buildPlayer(); player = p
        pv.player = p
        val subs = app.lib.subtitlesFor(it).map { s ->
            MediaItem.SubtitleConfiguration.Builder(TgDataSource.uriFor(s.id))
                .setMimeType(when (s.ext) { "vtt" -> MimeTypes.TEXT_VTT; "ass", "ssa" -> MimeTypes.TEXT_SSA; else -> MimeTypes.APPLICATION_SUBRIP })
                .setLanguage(guessLang(s.fileName + " " + s.caption))
                .setLabel(Parse.cleanName(s.fileName).ifEmpty { s.title })
                .build()
        }
        p.setMediaItem(MediaItem.Builder().setUri(TgDataSource.uriFor(it.id)).setMediaId(it.id.toString()).setSubtitleConfigurations(subs).build())
        // remember the file so we can delete its temp parts when leaving
        val saved = app.prefs.pos(it.id)
        val dur = app.prefs.dur(it.id)
        if (startPos >= 0) {
            // stall-watchdog ka reset: wahi position se turant chalao
            p.seekTo(startPos)
            p.playWhenReady = true
            p.prepare()
            controls.visibility = View.VISIBLE
            btnPlay.requestFocus()
            poke()
        } else if (saved > 15_000 && (dur <= 0 || saved < dur - 30_000)) {
            p.seekTo(saved)
            p.playWhenReady = false
            p.prepare()
            showResume(saved)
        } else {
            p.playWhenReady = true
            p.prepare()
            controls.visibility = View.VISIBLE
            btnPlay.requestFocus()
            poke()
        }
        h.removeCallbacks(tick); h.post(tick)
        h.removeCallbacks(saver); h.postDelayed(saver, 5000)
    }

    /** Average media bytes/s of the current film (file size / duration); 12 Mbps guess when the duration is unknown. */
    private fun filmBytesPerSec(): Long =
        if (item.size > 0 && item.duration > 0) item.size / item.duration else 1_500_000L

    /**
     * Smooth: "Buffering 40% · net 9 Mbps · film 12 Mbps — WiFi slow hai" (percent = lead so far / lead wanted).
     * Plain: "Buffering… 40% · 3.1 Mbps".
     */
    private fun updateLoading() {
        val p = player ?: return
        val tv = findViewById<TextView>(R.id.loadingText)
        val mbps = app.streams.speed() * 8 / 1_000_000.0
        val lc = smoothLc
        if (lc == null || !lc.enabled) {
            val bp = BufferProfile.of(this, app.prefs.bufferMode == 1)
            val need = (if (p.currentPosition > 1_000) bp.rebufferMs else bp.startMs).toLong()
            val have = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
            val pct = (have * 100 / need).coerceIn(0, 99)
            tv.text = "Buffering… $pct%" + if (mbps >= 0.1) " · " + String.format(Locale.US, "%.1f", mbps) + " Mbps" else ""
            return
        }
        val film = lc.bytesPerSec * 8 / 1_000_000.0
        val ram = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0)
        val pct = if (lc.refilling && lc.targetBytes > 0) (lc.leadBytes * 100 / lc.targetBytes)
                  else (ram * 100 / BufferProfile.of(this, app.prefs.bufferMode == 1, true).startMs)
        var t = "Buffering ${pct.coerceIn(0, 99)}%"
        if (mbps >= 0.1) t += " · net " + String.format(Locale.US, "%.0f", mbps) + " Mbps"
        t += " · film " + String.format(Locale.US, "%.0f", film) + " Mbps"
        if (mbps >= 0.1 && mbps < film * 1.15) t += " — WiFi slow hai"
        tv.text = t
    }

    private fun showResume(pos: Long) {
        resumePanel.visibility = View.VISIBLE
        controls.visibility = View.GONE
        findViewById<TextView>(R.id.resumeTitle).text = item.title
        val bRes = findViewById<TextView>(R.id.btnResume)
        val bStart = findViewById<TextView>(R.id.btnStartOver)
        bRes.text = "Resume (${Fmt.ms(pos)})"
        Focus.grow(bRes, 1.05f); Focus.grow(bStart, 1.05f)
        bRes.setOnClickListener { resumePanel.visibility = View.GONE; player?.play(); poke() }
        bStart.setOnClickListener { resumePanel.visibility = View.GONE; player?.seekTo(0); player?.play(); poke() }
        bRes.requestFocus()
    }

    private fun updateMeta(tracks: Tracks) {
        val a = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length }
        val s = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.sumOf { it.length }
        val base = listOf(item.quality, Fmt.size(item.size)).filter { it.isNotEmpty() }
        val extra = mutableListOf<String>()
        if (a > 1) extra += "$a audio"
        if (s > 0) extra += "$s subs"
        findViewById<TextView>(R.id.meta).text = (base + extra).joinToString(" · ")
    }

    // ---------- audio & subtitles popup ----------
    private fun showTracks() {
        val p = player ?: return
        val audioList = findViewById<LinearLayout>(R.id.audioList)
        val subList = findViewById<LinearLayout>(R.id.subList)
        audioList.removeAllViews(); subList.removeAllViews()
        val inf = LayoutInflater.from(this)
        var focusFirst: View? = null
        val groups = p.currentTracks.groups
        val audio = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        for (g in audio) for (i in 0 until g.length) {
            val f = g.getTrackFormat(i)
            val row = trackRow(inf, audioList, langName(f), audioCodec(f), g.isTrackSelected(i), g.isTrackSupported(i))
            row.setOnClickListener {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                f.language?.let { l -> app.prefs.audioLang = l }
                h.postDelayed({ showTracks() }, 250)
            }
            if (g.isTrackSelected(i) && focusFirst == null) focusFirst = row
        }
        if (audio.isEmpty()) trackRow(inf, audioList, "Audio nahi mila", "", false, true).isFocusable = false
        val text = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val textOff = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || text.none { it.isSelected }
        val off = trackRow(inf, subList, "Off", "", textOff, true)
        off.setOnClickListener {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            app.prefs.subLang = ""
            h.postDelayed({ showTracks() }, 250)
        }
        for (g in text) for (i in 0 until g.length) {
            val f = g.getTrackFormat(i)
            val row = trackRow(inf, subList, langName(f), subKind(f), !textOff && g.isTrackSelected(i), g.isTrackSupported(i))
            row.setOnClickListener {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                app.prefs.subLang = f.language ?: f.label ?: "on"
                h.postDelayed({ showTracks() }, 250)
            }
        }
        trackPanel.visibility = View.VISIBLE
        controls.visibility = View.GONE
        (focusFirst ?: audioList.getChildAt(0) ?: off).requestFocus()
    }

    private fun trackRow(inf: LayoutInflater, parent: LinearLayout, name: String, info: String, selected: Boolean, supported: Boolean): View {
        val v = inf.inflate(R.layout.row_track, parent, false)
        v.findViewById<TextView>(R.id.name).text = name
        v.findViewById<TextView>(R.id.info).text = if (supported) info else "$info · nahi chalega"
        v.findViewById<View>(R.id.check).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        if (selected) v.isSelected = true
        parent.addView(v)
        return v
    }

    private fun langName(f: Format): String {
        val lang = f.language?.takeIf { it.isNotEmpty() && it != "und" }
        val ln = lang?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).takeIf { n -> n.isNotEmpty() && n != it } ?: it.uppercase() }
        val label = f.label?.takeIf { it.isNotBlank() }
        return when {
            ln != null && label != null && !label.contains(ln, true) -> "$ln · $label"
            ln != null -> ln
            label != null -> label
            else -> "Track ${(f.id ?: "").takeLast(2)}".trim()
        }
    }

    private fun audioCodec(f: Format): String {
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby AC3"
            MimeTypes.AUDIO_E_AC3 -> "Dolby E-AC3"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
            MimeTypes.AUDIO_AC4 -> "Dolby AC4"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP3"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_VORBIS -> "Vorbis"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            MimeTypes.AUDIO_ALAC -> "ALAC"
            MimeTypes.AUDIO_RAW -> "PCM"
            else -> f.sampleMimeType?.substringAfter('/')?.uppercase() ?: ""
        }
        val ch = when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; Format.NO_VALUE -> ""; else -> "${f.channelCount}ch" }
        return listOf(codec, ch).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun subKind(f: Format): String = when (f.sampleMimeType ?: f.codecs) {
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.TEXT_VTT -> "VTT"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        else -> (f.sampleMimeType ?: "").substringAfter('/').uppercase()
    }

    private fun guessLang(s: String): String? {
        val t = s.lowercase()
        return when {
            Regex("(\\bhin\\b|hindi|\\.hi\\.)").containsMatchIn(t) -> "hi"
            Regex("(\\beng\\b|english|\\.en\\.)").containsMatchIn(t) -> "en"
            Regex("(tamil|\\.ta\\.)").containsMatchIn(t) -> "ta"
            Regex("(telugu|\\.te\\.)").containsMatchIn(t) -> "te"
            Regex("(punjabi|\\.pa\\.)").containsMatchIn(t) -> "pa"
            else -> null
        }
    }

    // ---------- controls ----------
    private fun togglePlay() { val p = player ?: return; if (p.isPlaying) p.pause() else p.play(); poke() }
    private fun seekBy(ms: Long) {
        val p = player ?: return
        val d = p.duration
        p.seekTo((p.currentPosition + ms).coerceIn(0, if (d > 0) d - 1000 else Long.MAX_VALUE))
        poke()
    }
    private fun poke() { h.removeCallbacks(hideControls); h.postDelayed(hideControls, 5000) }
    private fun showControls(focus: View? = null) {
        controls.visibility = View.VISIBLE
        (focus ?: btnPlay).requestFocus()
        poke()
    }
    private fun playNext() {
        val idx = ids.indexOf(item.id)
        val next = if (idx >= 0 && idx + 1 < ids.size) app.lib.get(ids[idx + 1]) else null
        if (next != null) { savePos(); start(next) } else finish()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            val k = e.keyCode
            when (k) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { togglePlay(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(); return true }
                KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, KeyEvent.KEYCODE_MENU -> { showTracks(); return true }
            }
            val overlay = trackPanel.visibility == View.VISIBLE || resumePanel.visibility == View.VISIBLE || errorPanel.visibility == View.VISIBLE
            if (!overlay && controls.visibility != View.VISIBLE) {
                when (k) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { togglePlay(); showControls(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(seek); seek.onKeyDown(k, e); return true }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
                }
            } else if (controls.visibility == View.VISIBLE) poke()
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            trackPanel.visibility == View.VISIBLE -> { trackPanel.visibility = View.GONE; showControls(findViewById(R.id.btnTracks)) }
            errorPanel.visibility == View.VISIBLE -> finish()
            controls.visibility == View.VISIBLE && resumePanel.visibility != View.VISIBLE && player?.isPlaying == true -> controls.visibility = View.GONE
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun savePos() {
        val p = player ?: return
        if (p.duration > 0 && resumePanel.visibility != View.VISIBLE) app.prefs.savePos(item.id, p.currentPosition, p.duration)
    }

    override fun onPause() { super.onPause(); savePos(); player?.pause() }

    override fun onStop() {
        super.onStop()
        if (isFinishing) release()
    }

    override fun onDestroy() { super.onDestroy(); release() }

    private fun release() {
        h.removeCallbacksAndMessages(null)
        player?.let { savePos(); it.release() }
        player = null
        if (mainFileId != 0) app.streams.release(mainFileId)
        mainFileId = 0
    }
}
