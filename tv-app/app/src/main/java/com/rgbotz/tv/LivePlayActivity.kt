package com.rgbotz.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live channel player: HLS (.m3u8) aur DASH (.mpd) dono, channel ke Cookie
 * headers ke saath, aur `kid:key` clearkey DRM inline (JioStar style).
 * Up/Down = channel badlo, OK = play/pause, Back = channel list.
 */
@OptIn(UnstableApi::class)
class LivePlayActivity : BaseActivity() {
    private val app get() = App.inst
    private lateinit var ch: Channel
    private var index = 0
    private var player: ExoPlayer? = null
    private lateinit var pv: PlayerView
    private lateinit var controls: View
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var btnPlay: ImageView
    private val h = Handler(Looper.getMainLooper())
    private var playingBackup = false

    companion object {
        @Volatile var queue: List<Channel> = emptyList()
    }

    private val hide = Runnable { if (player?.isPlaying == true) controls.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_play)
        if (queue.isEmpty()) { finish(); return }
        index = (intent.getIntExtra("index", 0).coerceIn(0, queue.size - 1))
        pv = findViewById(R.id.pv)
        controls = findViewById(R.id.controls)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        errorPanel = findViewById(R.id.errorPanel)
        errorText = findViewById(R.id.errorText)
        btnPlay = findViewById(R.id.btnPlay)
        pv.useController = false
        listOf(R.id.btnPrev, R.id.btnPlay, R.id.btnNext).forEach { Focus.grow(findViewById(it), 1.12f) }
        findViewById<View>(R.id.btnPlay).setOnClickListener { togglePlay() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { zap(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { zap(1) }
        findViewById<View>(R.id.btnRetry).setOnClickListener { errorPanel.visibility = View.GONE; start(ch) }
        findViewById<View>(R.id.btnChannels).setOnClickListener { finish() }
        start(queue[index])
    }

    private fun start(c: Channel) {
        ch = c
        playingBackup = false
        play(c.url)
    }

    private fun play(url: String) {
        val c = ch
        loadingText.text = "${c.name} connect ho raha hai…"
        findViewById<TextView>(R.id.title).text = c.name
        findViewById<TextView>(R.id.meta).text = buildString {
            append("Channel ${index + 1}/${queue.size}")
            if (c.category.isNotEmpty()) append(" · ").append(c.category)
            if (playingBackup) append(" · backup stream")
        }
        player?.release()
        val rf = DefaultRenderersFactory(this)
            .setExtensionRendererMode(if (app.prefs.softAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(12_000)
            .setUserAgent(Channels.UA)
        if (c.headers.isNotEmpty()) http.setDefaultRequestProperties(c.headers)
        val p = ExoPlayer.Builder(this, rf)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        player = p
        pv.player = p
        p.addListener(listener)
        val item = MediaItem.Builder()
        item.setUri(url)
        if (c.license.isNotEmpty()) drmConfig(c.license)?.let { item.setDrmConfiguration(it) }
        p.setMediaItem(item.build())
        p.prepare()
        p.playWhenReady = true
        loading.visibility = View.VISIBLE
        controls.visibility = View.GONE
        errorPanel.visibility = View.GONE
        h.removeCallbacks(hide)
    }

    /** `kid:key[,kid:key]` = ClearKey inline; `https://…` = Widevine license server. */
    private fun drmConfig(license: String): MediaItem.DrmConfiguration? {
        val clearKey = Regex("^([0-9a-fA-F]{32}:[0-9a-fA-F]{32})(,[0-9a-fA-F]{32}:[0-9a-fA-F]{32})*$")
        return when {
            clearKey.matches(license) -> {
                val keys = JSONArray()
                for (pair in license.split(',')) {
                    val i = pair.indexOf(':')
                    keys.put(JSONObject().put("kty", "oct")
                        .put("kid", hexToB64(pair.substring(0, i)))
                        .put("k", hexToB64(pair.substring(i + 1))))
                }
                val json = JSONObject().put("type", "temporary").put("keys", keys)
                MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                    .setLicenseUri("data:application/json;base64," + Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP))
                    .setMultiSession(false)
                    .build()
            }
            license.startsWith("http") ->
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(license).setMultiSession(false).build()
            else -> null
        }
    }

    private fun hexToB64(hex: String): String {
        val b = ByteArray(hex.length / 2)
        for (i in b.indices) b[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) { controls.visibility = View.VISIBLE; btnPlay.requestFocus(); poke() }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
        override fun onPlayerError(error: PlaybackException) {
            if (!playingBackup && ch.backup.isNotEmpty()) {
                playingBackup = true
                play(ch.backup)
            } else {
                loading.visibility = View.GONE
                controls.visibility = View.GONE
                errorText.text = "${ch.name} nahi chal raha\n" + (error.cause?.message ?: error.errorCodeName)
                errorPanel.visibility = View.VISIBLE
                findViewById<View>(R.id.btnRetry).requestFocus()
            }
        }
    }

    // ---------- controls ----------
    private fun togglePlay() { val p = player ?: return; if (p.isPlaying) p.pause() else p.play(); poke() }
    private fun poke() { h.removeCallbacks(hide); h.postDelayed(hide, 5000) }
    private fun showControls() { controls.visibility = View.VISIBLE; btnPlay.requestFocus(); poke() }

    private fun zap(delta: Int) {
        val q = queue
        if (q.isEmpty()) { finish(); return }
        index = ((index + delta) % q.size + q.size) % q.size
        errorPanel.visibility = View.GONE
        start(q[index])
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            val k = e.keyCode
            when (k) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { togglePlay(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); showControls(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); return true }
                KeyEvent.KEYCODE_MENU -> { finish(); return true }
            }
            if (errorPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(e) // dialog ke buttons apni nav
            when (k) {
                KeyEvent.KEYCODE_DPAD_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { zap(1); return true }
            }
            if (controls.visibility != View.VISIBLE) {
                when (k) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { togglePlay(); showControls(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(); return true }
                }
            } else if (controls.visibility == View.VISIBLE) poke()
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            errorPanel.visibility == View.VISIBLE -> finish()
            controls.visibility == View.VISIBLE && player?.isPlaying == true -> controls.visibility = View.GONE
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onPause() { super.onPause(); player?.pause() }

    override fun onStop() {
        super.onStop()
        if (isFinishing) release()
    }

    override fun onDestroy() { super.onDestroy(); release() }

    private fun release() {
        h.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
