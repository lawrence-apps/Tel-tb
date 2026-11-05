package com.rgbotz.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import com.rgbotz.tv.core.Item
import com.rgbotz.tv.core.Kind
import com.rgbotz.tv.core.Local
import com.rgbotz.tv.core.TdStatus
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Fmt {
    fun size(b: Long): String = when {
        b <= 0 -> ""
        b >= 1L shl 30 -> String.format("%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format("%.0f MB", b / 1048576.0)
        else -> String.format("%.0f KB", b / 1024.0)
    }

    fun dur(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    fun ms(ms: Long) = dur(ms / 1000).ifEmpty { "0:00" }

    fun day(unix: Int): String {
        if (unix <= 0) return ""
        val c = Calendar.getInstance(); val now = Calendar.getInstance()
        c.timeInMillis = unix * 1000L
        val dd = (now.get(Calendar.YEAR) - c.get(Calendar.YEAR)) * 366 + now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
        return when (dd) {
            0 -> "Aaj"; 1 -> "Kal"
            else -> {
                val mon = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[c.get(Calendar.MONTH)]
                if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR)) "${c.get(Calendar.DAY_OF_MONTH)} $mon" else "${c.get(Calendar.DAY_OF_MONTH)} $mon ${c.get(Calendar.YEAR)}"
            }
        }
    }

    fun typeLabel(it: Item): String = when (it.kind) {
        Kind.VIDEO -> it.quality.ifEmpty { it.ext.uppercase().ifEmpty { "Video" } }
        Kind.AUDIO -> it.ext.uppercase().ifEmpty { "Audio" }
        Kind.PHOTO -> "Photo"
        Kind.SUB -> "Subtitle"
    }

    fun count(n: Int): String = String.format("%,d", n)
}

object Focus {
    /** Scale up a little when focused (TV 10-foot focus). */
    fun grow(v: View, scale: Float = 1.06f, extra: ((Boolean) -> Unit)? = null) {
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).translationZ(if (has) 12f else 0f).setDuration(140).start()
            extra?.invoke(has)
        }
    }
}

object Thumbs {
    private val mem = object : LruCache<Long, Bitmap>(((Runtime.getRuntime().maxMemory() / 12).coerceAtMost(48L shl 20)).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val exec = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun placeholder(kind: Kind): GradientDrawable {
        val c = when (kind) {
            Kind.VIDEO -> intArrayOf(0xFF3a1020.toInt(), 0xFF14070c.toInt())
            Kind.AUDIO -> intArrayOf(0xFF2a1650.toInt(), 0xFF0f0820.toInt())
            Kind.PHOTO -> intArrayOf(0xFF0f3a35.toInt(), 0xFF061614.toInt())
            Kind.SUB -> intArrayOf(0xFF333333.toInt(), 0xFF111111.toInt())
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, c)
    }

    fun load(iv: ImageView, item: Item, maxPx: Int = 480) {
        iv.tag = item.id
        mem.get(item.id)?.let { iv.setImageBitmap(it); return }
        val mini = item.mini?.let { try { BitmapFactory.decodeByteArray(it, 0, it.size) } catch (_: Throwable) { null } }
        if (mini != null) iv.setImageBitmap(mini) else iv.setImageDrawable(placeholder(item.kind))
        val app = App.inst
        if (app.td.status != TdStatus.READY) return
        exec.submit {
            try {
                if (iv.tag != item.id) return@submit
                val data = Local.bytes("/t/${item.id}", 30_000) ?: return@submit
                val bm = decodeBytes(data, maxPx) ?: return@submit
                mem.put(item.id, bm)
                main.post { if (iv.tag == item.id) iv.setImageBitmap(bm) }
            } catch (_: Throwable) {}
        }
    }

    fun decode(path: String, maxPx: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= maxPx || o.outHeight / (s * 2) >= maxPx) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 })
    }

    fun decodeBytes(data: ByteArray, maxPx: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= maxPx || o.outHeight / (s * 2) >= maxPx) s *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 })
    }

    fun clear() { mem.evictAll() }
}

object Nav {
    fun open(a: Activity, item: Item, context: List<Item>? = null) {
        when (item.kind) {
            Kind.VIDEO -> a.startActivity(Intent(a, PlayerActivity::class.java).putExtra("id", item.id)
                .putExtra("list", (context ?: App.inst.lib.items(Kind.VIDEO)).filter { it.kind == Kind.VIDEO }.map { it.id }.toLongArray()))
            Kind.AUDIO -> a.startActivity(Intent(a, MusicActivity::class.java).putExtra("id", item.id).putExtra("play", true))
            Kind.PHOTO -> a.startActivity(Intent(a, PhotoActivity::class.java).putExtra("id", item.id)
                .putExtra("list", (context ?: App.inst.lib.items(Kind.PHOTO)).filter { it.kind == Kind.PHOTO }.map { it.id }.toLongArray()))
            Kind.SUB -> {}
        }
    }

    fun browse(c: Context, mode: String, tag: String? = null) {
        c.startActivity(Intent(c, BrowseActivity::class.java).putExtra("mode", mode).putExtra("tag", tag))
    }

    fun iconFor(kind: Kind) = when (kind) {
        Kind.VIDEO -> R.drawable.ic_video
        Kind.AUDIO -> R.drawable.ic_music
        Kind.PHOTO -> R.drawable.ic_photo
        Kind.SUB -> R.drawable.ic_subtitles
    }
}

fun statusLine(): Pair<String, Int> {
    val app = App.inst
    app.demoStatus?.let { return it to 0xFF30d158.toInt() }
    val td = app.td
    return when {
        td.status == TdStatus.NO_API -> "API ID baaki hai" to 0xFFff453a.toInt()
        td.status == TdStatus.AUTH_ERROR -> ("Bot login error: " + (td.statusMsg ?: "")).take(60) to 0xFFff453a.toInt()
        td.status != TdStatus.READY -> "Connecting…" to 0xFFffd60a.toInt()
        app.lib.scanning -> "Library update… ${app.lib.scannedUpTo}" to 0xFFffd60a.toInt()
        !td.online -> "Internet nahi hai" to 0xFFffd60a.toInt()
        app.lib.lastError != null -> ("Channel error: " + app.lib.lastError).take(60) to 0xFFff453a.toInt()
        else -> "${app.lib.chatTitle.ifEmpty { "Tv db" }} · bot connected" to 0xFF30d158.toInt()
    }
}

@Suppress("unused")
private val keepTimeUnit = TimeUnit.SECONDS
