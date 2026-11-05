package com.rgbotz.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View

/** D-pad seek bar: LEFT/RIGHT move 10 s (faster when held), commits the seek after a short pause. */
class TvSeekBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var duration = 0L
        set(v) { field = v; invalidate() }
    var position = 0L
        set(v) { field = v; if (pending < 0) invalidate() }
    var buffered = 0L
        set(v) { field = v; invalidate() }
    var onSeek: ((Long) -> Unit)? = null
    var onScrub: ((Long) -> Unit)? = null
    private var pending = -1L
    private val h = Handler(Looper.getMainLooper())
    private val commit = Runnable { val p = pending; pending = -1; if (p >= 0) onSeek?.invoke(p); invalidate() }
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init { isFocusable = true; isFocusableInTouchMode = true }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (duration > 0 && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val rep = event.repeatCount
            val step = when { rep > 30 -> 120_000L; rep > 12 -> 60_000L; rep > 4 -> 30_000L; else -> 10_000L }
            val base = if (pending >= 0) pending else position
            pending = (base + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step).coerceIn(0, duration - 1000)
            onScrub?.invoke(pending)
            h.removeCallbacks(commit); h.postDelayed(commit, 650)
            invalidate()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun shown(): Long = if (pending >= 0) pending else position

    override fun onDraw(c: Canvas) {
        val focused = isFocused
        val th = (if (focused) 7f else 5f) * d
        val cy = height / 2f
        val left = 8 * d; val right = width - 8 * d
        val w = right - left
        fun x(v: Long) = if (duration <= 0) left else left + w * (v.toFloat() / duration).coerceIn(0f, 1f)
        p.color = 0x38FFFFFF
        r.set(left, cy - th / 2, right, cy + th / 2); c.drawRoundRect(r, th, th, p)
        p.color = 0x59FFFFFF
        r.set(left, cy - th / 2, x(buffered), cy + th / 2); c.drawRoundRect(r, th, th, p)
        p.color = 0xFFfa2d48.toInt()
        val px = x(shown())
        r.set(left, cy - th / 2, px, cy + th / 2); c.drawRoundRect(r, th, th, p)
        if (focused) {
            p.color = 0x55fa2d48; c.drawCircle(px, cy, 14 * d, p)
            p.color = 0xFFFFFFFF.toInt(); c.drawCircle(px, cy, 9 * d, p)
        } else {
            p.color = 0xFFFFFFFF.toInt(); c.drawCircle(px, cy, 6 * d, p)
        }
    }
}
