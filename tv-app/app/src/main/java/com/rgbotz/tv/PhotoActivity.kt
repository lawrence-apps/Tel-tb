package com.rgbotz.tv

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.rgbotz.tv.core.Local
import java.util.concurrent.Executors

/** Full-screen photo viewer: LEFT/RIGHT = previous/next, OK = slideshow on/off. */
class PhotoActivity : BaseActivity() {
    private val app get() = App.inst
    private var ids = LongArray(0)
    private var idx = 0
    private lateinit var img: ImageView
    private lateinit var info: TextView
    private lateinit var title: TextView
    private lateinit var loading: View
    private lateinit var overlay: View
    private val exec = Executors.newSingleThreadExecutor()
    private val h = Handler(Looper.getMainLooper())
    private var slideshow = false
    private val recent = ArrayDeque<Int>()   // file ids on disk, keep only a few
    private val cache = HashMap<Long, Bitmap>()
    private val slide = Runnable { next(1, auto = true) }
    private val hideOverlay = Runnable { if (slideshow) overlay.animate().alpha(0f).setDuration(400).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_photo)
        img = findViewById(R.id.img); info = findViewById(R.id.info); title = findViewById(R.id.title)
        loading = findViewById(R.id.loading); overlay = findViewById(R.id.overlay)
        val id = intent.getLongExtra("id", 0)
        ids = intent.getLongArrayExtra("list") ?: longArrayOf(id)
        idx = ids.indexOf(id).coerceAtLeast(0)
        show()
    }

    private fun show() {
        val id = ids.getOrNull(idx) ?: return
        val it = app.lib.get(id)
        title.text = it?.title ?: ""
        info.text = "${Fmt.count(idx + 1)} / ${Fmt.count(ids.size)}" + if (slideshow) "  ·  Slideshow ▶" else ""
        val bm = cache[id]
        if (bm != null) { setBitmap(bm) } else {
            loading.visibility = View.VISIBLE
            if (it != null) Thumbs.load(img, it, 480)
            load(id) { b -> if (ids.getOrNull(idx) == id) setBitmap(b) }
        }
        ids.getOrNull(idx + 1)?.let { n -> if (!cache.containsKey(n)) load(n) {} }
        if (slideshow) { h.removeCallbacks(slide); h.postDelayed(slide, 5000) }
    }

    private fun setBitmap(b: Bitmap?) {
        loading.visibility = View.GONE
        if (b == null) return
        img.alpha = 0.3f; img.setImageBitmap(b); img.animate().alpha(1f).setDuration(300).start()
    }

    private fun load(id: Long, done: (Bitmap?) -> Unit) {
        exec.submit {
            var bm: Bitmap? = null
            try {
                val data = Local.bytes("/f/$id", 90_000)
                if (data != null) {
                    val dm = resources.displayMetrics
                    bm = Thumbs.decodeBytes(data, maxOf(dm.widthPixels, dm.heightPixels).coerceAtMost(1920))
                }
            } catch (_: Throwable) {}
            h.post {
                if (bm != null) {
                    cache[id] = bm!!
                    val keep = setOf(ids.getOrNull(idx - 1), ids.getOrNull(idx), ids.getOrNull(idx + 1))
                    cache.keys.filter { it !in keep }.forEach { cache.remove(it) }
                }
                done(bm)
            }
        }
    }

    private fun next(d: Int, auto: Boolean = false) {
        if (ids.isEmpty()) return
        idx = if (auto) (idx + d + ids.size) % ids.size else (idx + d).coerceIn(0, ids.size - 1)
        show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> { next(1); poke(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { next(-1); poke(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                slideshow = !slideshow
                h.removeCallbacks(slide)
                if (slideshow) h.postDelayed(slide, 5000)
                show(); poke(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { poke(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun poke() {
        overlay.animate().cancel(); overlay.alpha = 1f
        h.removeCallbacks(hideOverlay); h.postDelayed(hideOverlay, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        h.removeCallbacksAndMessages(null)
        exec.shutdownNow()
        cache.clear()
    }
}
