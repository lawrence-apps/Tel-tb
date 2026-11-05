package com.rgbotz.tv

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rgbotz.tv.core.Item
import com.rgbotz.tv.core.Kind

/** Grids: Videos, Photos, Resume, Search, Folders (#tags) and one folder. */
class BrowseActivity : BaseActivity() {
    private val app get() = App.inst
    private lateinit var mode: String
    private var tag: String? = null
    private lateinit var grid: RecyclerView
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private var cards: CardAdapter? = null
    private var folders: FolderAdapter? = null
    private val libListener: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        mode = intent.getStringExtra("mode") ?: "videos"
        tag = intent.getStringExtra("tag")
        grid = findViewById(R.id.grid)
        title = findViewById(R.id.title)
        sub = findViewById(R.id.sub)
        empty = findViewById(R.id.empty)
        search = findViewById(R.id.search)
        grid.itemAnimator = null
        val cols = when (mode) { "photos" -> 5; else -> 4 }
        grid.layoutManager = GridLayoutManager(this, cols)
        if (mode == "folders") {
            folders = FolderAdapter { t -> Nav.browse(this, "tag", t) }
            grid.adapter = folders
        } else {
            cards = CardAdapter(photoStyle = mode == "photos") { item, list -> Nav.open(this, item, list) }
            grid.adapter = cards
        }
        if (mode == "search") {
            search.visibility = View.VISIBLE
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { render() }
            })
            search.requestFocus()
        }
        render()
        if (mode != "search") grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    override fun onResume() { super.onResume(); app.lib.listeners.add(libListener); if (mode == "resume") render() }
    override fun onPause() { super.onPause(); app.lib.listeners.remove(libListener) }

    private fun render() {
        val lib = app.lib
        val list: List<Item>
        when (mode) {
            "videos" -> { list = lib.items(Kind.VIDEO); title.text = "Videos"; sub.text = "${Fmt.count(list.size)} files · ${Fmt.size(list.sumOf { it.size })}" }
            "photos" -> { list = lib.items(Kind.PHOTO); title.text = "Photos"; sub.text = "${Fmt.count(list.size)} photos" }
            "resume" -> {
                list = app.prefs.resumeIds().mapNotNull { lib.get(it) }.filter { it.kind == Kind.VIDEO }
                title.text = "Resume"; sub.text = if (list.isEmpty()) "" else "${list.size} adhoore — jahan chhoda wahin se"
            }
            "tag" -> { list = lib.items().filter { tag in it.tags && it.kind != Kind.SUB }; title.text = "#$tag"; sub.text = "${list.size} files" }
            "search" -> {
                val q = search.text.toString().trim().lowercase()
                list = if (q.isEmpty()) emptyList() else lib.items().filter {
                    it.kind != Kind.SUB && (it.title.lowercase().contains(q) || it.fileName.lowercase().contains(q) || it.performer.lowercase().contains(q) || it.tags.any { t -> t.contains(q) })
                }
                title.text = "Search"; sub.text = if (q.isEmpty()) "naam likho" else "${list.size} mile"
            }
            "folders" -> {
                val tags = lib.tags()
                folders?.set(tags)
                title.text = "Folders"; sub.text = "Caption me #tag lagao, wahi folder ban jayega"
                empty.visibility = if (tags.isEmpty()) View.VISIBLE else View.GONE
                empty.text = "Abhi koi #tag nahi. Channel me post ke caption me #movies, #family jaisa likho."
                return
            }
            else -> list = emptyList()
        }
        cards?.set(list)
        empty.visibility = if (list.isEmpty() && mode != "search") View.VISIBLE else View.GONE
        empty.text = when (mode) { "resume" -> "Koi video adhoori nahi hai"; else -> "Yahan abhi kuch nahi hai" }
    }
}

class CardAdapter(private val photoStyle: Boolean, private val click: (Item, List<Item>) -> Unit) : RecyclerView.Adapter<CardAdapter.VH>() {
    private var items: List<Item> = emptyList()
    fun set(list: List<Item>) { items = list; notifyDataSetChanged() }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val badgeQ: TextView = v.findViewById(R.id.badgeQ)
        val badgeS: TextView = v.findViewById(R.id.badgeS)
        val badgeD: TextView = v.findViewById(R.id.badgeD)
        val prog: View = v.findViewById(R.id.prog)
        val progFill: View = v.findViewById(R.id.progFill)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(if (photoStyle) R.layout.item_photo else R.layout.item_card, p, false)
        Focus.grow(v, 1.07f)
        v.findViewById<View>(R.id.thumbWrap).clipToOutline = true
        return VH(v)
    }
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val it = items[i]
        Thumbs.load(h.thumb, it, if (photoStyle) 360 else 480)
        h.title.text = it.title
        val prefs = App.inst.prefs
        when (it.kind) {
            Kind.VIDEO -> {
                h.meta.text = listOf(Fmt.day(it.date), it.ext.uppercase()).filter { s -> s.isNotEmpty() }.joinToString(" · ")
                h.badgeQ.text = it.quality; h.badgeQ.visibility = if (it.quality.isEmpty()) View.GONE else View.VISIBLE
                val ds = if (it.duration > 0) it.duration.toLong() else prefs.dur(it.id) / 1000
                h.badgeD.text = Fmt.dur(ds); h.badgeD.visibility = if (ds > 0) View.VISIBLE else View.GONE
            }
            Kind.AUDIO -> { h.meta.text = it.performer.ifEmpty { it.ext.uppercase() }; h.badgeQ.visibility = View.GONE
                h.badgeD.text = Fmt.dur(it.duration.toLong()); h.badgeD.visibility = if (it.duration > 0) View.VISIBLE else View.GONE }
            else -> { h.meta.text = Fmt.day(it.date); h.badgeQ.visibility = View.GONE; h.badgeD.visibility = View.GONE }
        }
        h.badgeS.text = Fmt.size(it.size); h.badgeS.visibility = if (photoStyle || it.size <= 0) View.GONE else View.VISIBLE
        val pos = prefs.pos(it.id); val dur = prefs.dur(it.id)
        if (it.kind == Kind.VIDEO && pos > 0 && dur > 0) {
            h.prog.visibility = View.VISIBLE
            val lp = h.progFill.layoutParams as android.widget.LinearLayout.LayoutParams
            lp.weight = (pos.toFloat() / dur).coerceIn(0.02f, 1f); h.progFill.layoutParams = lp
            (h.prog.findViewById<View>(R.id.progRest).layoutParams as android.widget.LinearLayout.LayoutParams).weight = 1f - lp.weight
        } else h.prog.visibility = View.GONE
        h.itemView.setOnClickListener { _ -> click(it, items) }
    }
}

class FolderAdapter(private val click: (String) -> Unit) : RecyclerView.Adapter<FolderAdapter.VH>() {
    private var tags: List<Pair<String, Int>> = emptyList()
    fun set(t: List<Pair<String, Int>>) { tags = t; notifyDataSetChanged() }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }
    private val bgs = intArrayOf(R.drawable.tile_red, R.drawable.tile_purple, R.drawable.tile_teal, R.drawable.tile_orange)
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_folder, p, false)
        v.findViewById<View>(R.id.bg).clipToOutline = true
        Focus.grow(v, 1.06f)
        return VH(v)
    }
    override fun getItemCount() = tags.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val (t, n) = tags[i]
        h.name.text = "#$t"; h.count.text = "$n files"
        h.itemView.findViewById<View>(R.id.bg).setBackgroundResource(bgs[i % bgs.size])
        h.itemView.setOnClickListener { click(t) }
    }
}
