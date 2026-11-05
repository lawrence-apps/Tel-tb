package com.rgbotz.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rgbotz.tv.core.Item
import com.rgbotz.tv.core.Kind
import com.rgbotz.tv.core.TdStatus

class MainActivity : BaseActivity() {
    private val app get() = App.inst
    private lateinit var recent: RecyclerView
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private val adapter = RowAdapter { Nav.open(this, it) }
    private val libListener: () -> Unit = { runOnUiThread { render() } }
    private val stListener: (TdStatus, String?) -> Unit = { _, _ -> runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        recent = findViewById(R.id.recent)
        recent.layoutManager = LinearLayoutManager(this)
        recent.adapter = adapter
        recent.itemAnimator = null

        val tiles = listOf(R.id.tileVideos, R.id.tileMusic, R.id.tilePhotos, R.id.tileResume, R.id.tileTV)
        tiles.forEach { val t = findViewById<View>(it); t.findViewWithTag<View>("clip").clipToOutline = true; Focus.grow(t, 1.05f) }
        findViewById<View>(R.id.tileVideos).setOnClickListener { Nav.browse(this, "videos") }
        findViewById<View>(R.id.tileMusic).setOnClickListener { startActivity(Intent(this, MusicActivity::class.java)) }
        findViewById<View>(R.id.tilePhotos).setOnClickListener { Nav.browse(this, "photos") }
        findViewById<View>(R.id.tileResume).setOnClickListener { Nav.browse(this, "resume") }
        findViewById<View>(R.id.tileTV).setOnClickListener { startActivity(Intent(this, LiveTvActivity::class.java)) }
        findViewById<View>(R.id.chipSearch).setOnClickListener { Nav.browse(this, "search") }
        findViewById<View>(R.id.chipFolders).setOnClickListener { Nav.browse(this, "folders") }
        findViewById<View>(R.id.chipSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        listOf(R.id.chipSearch, R.id.chipFolders, R.id.chipSettings).forEach { Focus.grow(findViewById(it), 1.06f) }
        findViewById<View>(R.id.tileVideos).requestFocus()
    }


    override fun onResume() {
        super.onResume()
        app.lib.listeners.add(libListener)
        app.td.addStatusListener(stListener)
        render()
    }

    override fun onPause() {
        super.onPause()
        app.lib.listeners.remove(libListener)
        app.td.removeStatusListener(stListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) { app.streams.releaseAll(); Thumbs.clear() }
    }

    private fun render() {
        val lib = app.lib
        val (st, col) = statusLine()
        status.text = st
        (findViewById<TextView>(R.id.statusDot)).setTextColor(col)
        val nv = lib.count(Kind.VIDEO); val na = lib.count(Kind.AUDIO); val np = lib.count(Kind.PHOTO)
        findViewById<TextView>(R.id.nVideos).text = "${Fmt.count(nv)} files" + lib.totalSize(Kind.VIDEO).let { if (it > 0) " · ${Fmt.size(it)}" else "" }
        findViewById<TextView>(R.id.nMusic).text = "${Fmt.count(na)} gaane"
        findViewById<TextView>(R.id.nPhotos).text = "${Fmt.count(np)} photos"
        val res = app.prefs.resumeIds().count { lib.get(it)?.kind == Kind.VIDEO }
        findViewById<TextView>(R.id.nResume).text = if (res == 0) "kuch adhoora nahi" else "$res adhoore"
        val ntv = app.prefs.tvCount
        findViewById<TextView>(R.id.nTV).text = if (ntv > 0) "${Fmt.count(ntv)} channels live" else "Live darshan"
        val items = lib.items().filter { it.kind != Kind.SUB }.take(8)
        adapter.set(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when (app.td.status) {
            TdStatus.NO_API -> "Telegram API ID abhi app me nahi hai. Wo milte hi library yahan dikhegi."
            TdStatus.AUTH_ERROR -> "Bot login nahi hua: ${app.td.statusMsg ?: ""}"
            TdStatus.READY -> if (lib.scanning) "Channel padh raha hoon…" else "Channel me abhi koi video / gaana / photo nahi mila"
            else -> "Telegram se connect ho raha hai…"
        }
    }
}

/** "Naya aaya" style list rows: icon, title, type, size, day. */
class RowAdapter(private val click: (Item) -> Unit) : RecyclerView.Adapter<RowAdapter.VH>() {
    private var items: List<Item> = emptyList()
    fun set(list: List<Item>) { items = list; notifyDataSetChanged() }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val type: TextView = v.findViewById(R.id.type)
        val size: TextView = v.findViewById(R.id.size)
        val day: TextView = v.findViewById(R.id.day)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.row_recent, p, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val it = items[i]
        h.icon.setImageResource(Nav.iconFor(it.kind))
        h.title.text = if (it.kind == Kind.VIDEO || it.kind == Kind.AUDIO) (it.fileName.ifEmpty { it.title }) else it.title
        if (it.kind == Kind.PHOTO) h.title.text = it.title
        h.type.text = Fmt.typeLabel(it)
        h.size.text = Fmt.size(it.size)
        h.day.text = Fmt.day(it.date)
        h.itemView.setOnClickListener { _ -> click(it) }
    }
}
