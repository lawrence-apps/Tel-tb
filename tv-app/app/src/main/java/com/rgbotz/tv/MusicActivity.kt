package com.rgbotz.tv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rgbotz.tv.core.Item
import com.rgbotz.tv.core.Kind

/** Music: cover + controls on the left, song list on the right. */
@OptIn(UnstableApi::class)
class MusicActivity : BaseActivity() {
    private val app get() = App.inst
    private var songs: List<Item> = emptyList()
    private var player: ExoPlayer? = null
    private lateinit var list: RecyclerView
    private lateinit var seek: TvSeekBar
    private val adapter = SongAdapter { i -> play(i) }
    private val h = Handler(Looper.getMainLooper())
    private val playingFiles = HashSet<Int>()
    private val saver = object : Runnable {
        override fun run() { saveState(); h.postDelayed(this, 5000) }
    }

    /** remember the last track + position + shuffle/repeat so Music continues by itself next time */
    private fun saveState() {
        val p = player ?: return
        val s = songs.getOrNull(p.currentMediaItemIndex) ?: return
        val pr = app.prefs
        pr.musicId = s.id
        pr.musicPos = if (p.duration > 0 && p.currentPosition > p.duration - 3000) 0 else p.currentPosition
        pr.musicShuffle = p.shuffleModeEnabled
        pr.musicRepeat = p.repeatMode
    }
    private val tick = object : Runnable {
        override fun run() {
            player?.let { p ->
                seek.duration = p.duration.coerceAtLeast(0); seek.position = p.currentPosition; seek.buffered = p.bufferedPosition
                findViewById<TextView>(R.id.tPos).text = Fmt.ms(seek.shown())
                findViewById<TextView>(R.id.tDur).text = Fmt.ms(p.duration.coerceAtLeast(0))
            }
            h.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_music)
        songs = app.lib.items(Kind.AUDIO)
        findViewById<View>(R.id.coverWrap).clipToOutline = true
        list = findViewById(R.id.songs)
        seek = findViewById(R.id.seek)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null
        adapter.set(songs)
        findViewById<TextView>(R.id.listTitle).text = "Gaane · ${Fmt.count(songs.size)}"
        findViewById<View>(R.id.empty).visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        listOf(R.id.btnShuffle, R.id.btnPrev, R.id.btnPlay, R.id.btnNext, R.id.btnRepeat).forEach { Focus.grow(findViewById(it), 1.12f) }
        findViewById<View>(R.id.btnPlay).setOnClickListener { val p = player; if (p == null) play(0) else if (p.isPlaying) p.pause() else p.play() }
        findViewById<View>(R.id.btnNext).setOnClickListener { player?.seekToNextMediaItem() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { player?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
        findViewById<View>(R.id.btnShuffle).setOnClickListener { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled; renderModes() } }
        findViewById<View>(R.id.btnRepeat).setOnClickListener { player?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; renderModes() } }
        seek.onSeek = { player?.seekTo(it) }
        val startId = intent.getLongExtra("id", 0)
        var idx = songs.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        // opened from Home (no song picked): continue the last track from where it stopped, no popup
        val resumeIdx = if (startId == 0L) songs.indexOfFirst { it.id == app.prefs.musicId } else -1
        showSong(if (resumeIdx >= 0) resumeIdx else idx)
        if (resumeIdx >= 0) {
            idx = resumeIdx
            play(idx, app.prefs.musicPos)
            player?.let { it.shuffleModeEnabled = app.prefs.musicShuffle; it.repeatMode = app.prefs.musicRepeat }
            renderModes()
        } else if (intent.getBooleanExtra("play", false) && songs.isNotEmpty()) play(idx)
        list.post { list.scrollToPosition(idx); list.post { list.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() ?: list.getChildAt(0)?.requestFocus() } }
        h.post(tick)
        h.postDelayed(saver, 5000)
    }

    private fun buildPlayer(): ExoPlayer {
        val rf = DefaultRenderersFactory(this)
            .setExtensionRendererMode(if (app.prefs.softAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, rf)
            .setMediaSourceFactory(DefaultMediaSourceFactory(TgDataSource.Factory(app), DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)))
            .build()
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = p.currentMediaItemIndex
                showSong(i); adapter.playing = songs.getOrNull(i)?.id ?: 0; adapter.notifyDataSetChanged()
                releaseOld()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveState()
                findViewById<ImageView>(R.id.btnPlay).setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }
            override fun onPlayerError(error: PlaybackException) {
                findViewById<TextView>(R.id.artist).text = "Ye gaana nahi chal paya: " + (error.cause?.message ?: error.errorCodeName)
                h.postDelayed({ player?.let { if (it.hasNextMediaItem()) { it.seekToNextMediaItem(); it.prepare(); it.play() } } }, 2500)
            }
        })
        return p
    }

    private fun play(i: Int, startPos: Long = 0L) {
        if (songs.isEmpty()) return
        val p = player ?: buildPlayer().also { player = it }
        if (p.mediaItemCount != songs.size) {
            p.setMediaItems(songs.map {
                MediaItem.Builder().setUri(TgDataSource.uriFor(it.id)).setMediaId(it.id.toString())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.performer).build()).build()
            }, i, startPos)
            p.prepare()
        } else p.seekTo(i, startPos)
        p.play()
        showSong(i)
        adapter.playing = songs[i].id; adapter.notifyDataSetChanged()
    }

    private fun showSong(i: Int) {
        val s = songs.getOrNull(i) ?: return
        findViewById<TextView>(R.id.title).text = s.title
        findViewById<TextView>(R.id.artist).text = listOf(s.performer, s.ext.uppercase(), Fmt.size(s.size)).filter { it.isNotEmpty() }.joinToString(" · ")
        Thumbs.load(findViewById(R.id.cover), s, 600)
    }

    private fun renderModes() {
        val p = player ?: return
        findViewById<ImageView>(R.id.btnShuffle).alpha = if (p.shuffleModeEnabled) 1f else 0.45f
        findViewById<ImageView>(R.id.btnRepeat).alpha = if (p.repeatMode != Player.REPEAT_MODE_OFF) 1f else 0.45f
        findViewById<ImageView>(R.id.btnRepeat).setImageResource(if (p.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
    }

    /** delete temp parts of songs that are no longer current */
    private fun releaseOld() {
        val cur = songs.getOrNull(player?.currentMediaItemIndex ?: -1) ?: return
        if (cur.id < 0) return
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { findViewById<View>(R.id.btnPlay).performClick(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { player?.seekToNextMediaItem(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { player?.seekToPreviousMediaItem(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveState()
        h.removeCallbacksAndMessages(null)
        player?.release(); player = null
    }
}

class SongAdapter(private val click: (Int) -> Unit) : RecyclerView.Adapter<SongAdapter.VH>() {
    private var items: List<Item> = emptyList()
    var playing = 0L
    fun set(l: List<Item>) { items = l; notifyDataSetChanged() }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val title: TextView = v.findViewById(R.id.title)
        val artist: TextView = v.findViewById(R.id.artist)
        val dur: TextView = v.findViewById(R.id.dur)
        val eq: ImageView = v.findViewById(R.id.eq)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.row_song, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val s = items[i]
        val on = s.id == playing
        h.num.text = (i + 1).toString(); h.num.visibility = if (on) View.GONE else View.VISIBLE
        h.eq.visibility = if (on) View.VISIBLE else View.GONE
        h.title.text = s.title
        h.title.setTextColor(if (on) 0xFFff6b80.toInt() else 0xFFFFFFFF.toInt())
        h.artist.text = s.performer.ifEmpty { s.ext.uppercase() }
        h.dur.text = Fmt.dur(s.duration.toLong())
        h.itemView.setOnClickListener { click(h.bindingAdapterPosition) }
    }
}
