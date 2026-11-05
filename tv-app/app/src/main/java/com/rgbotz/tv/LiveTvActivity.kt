package com.rgbotz.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** One live channel, exactly the data the JioStar+ app feeds its player. */
class Channel(
    val id: String,
    val name: String,
    val logo: String,
    val category: String,
    val url: String,
    val backup: String,
    val headers: Map<String, String>,
    val license: String,
)

/**
 * Same channel sources as the JioStar+ app: 2 workers + npoint JSON, merged and
 * deduped by name (best entry wins: HLS over DASH, extras over bare URLs).
 */
object Channels {
    const val UA = "Mozilla/5.0 (Linux; Android 9; Mi TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val SOURCES = listOf(
        "https://lemajekaat.streamxlive.workers.dev/",
        "https://endless.livenoww.workers.dev/",
        "https://api.npoint.io/5122554ee81206923fa7",
    )

    fun fetch(): List<Channel> {
        val raw = ArrayList<Channel>()
        for (s in SOURCES) {
            try {
                val c = URL(s).openConnection() as HttpURLConnection
                c.connectTimeout = 12_000; c.readTimeout = 12_000
                c.setRequestProperty("User-Agent", UA)
                val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                for (o in parse(text)) map(o)?.let { raw.add(it) }
            } catch (_: Throwable) {}
        }
        val byName = LinkedHashMap<String, Channel>()
        for (ch in raw.sortedByDescending { score(it) }) byName.putIfAbsent(ch.name.lowercase().trim(), ch)
        return byName.values.toList()
    }

    private fun parse(text: String): List<JSONObject> {
        val t = text.trim()
        return try {
            if (t.startsWith("[")) {
                val a = JSONArray(t)
                List(a.length()) { a.optJSONObject(it) }.filterNotNull()
            } else {
                val o = JSONObject(t)
                val arr = o.optJSONArray("channels") ?: o.optJSONArray("data") ?: return emptyList()
                List(arr.length()) { arr.optJSONObject(it) }.filterNotNull()
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun map(o: JSONObject): Channel? {
        val name = o.optString("name").ifEmpty { o.optString("channel_name") }.trim()
        if (name.isEmpty() || name.equals("null", true)) return null
        var raw = listOf("mpd_url", "m3u8_url", "url", "channel_url", "link", "stream_url")
            .firstOrNull { f -> o.optString(f).let { v -> v.isNotEmpty() && v != "null" } }?.let { o.getString(it) } ?: ""
        if (raw.isEmpty()) return null
        val headers = HashMap<String, String>()
        if (raw.contains('|')) { // pipe style: url|Cookie=...|User-Agent=...
            val parts = raw.split('|')
            raw = parts[0].trim()
            for (p in parts.drop(1)) { val i = p.indexOf('='); if (i > 0) headers[p.substring(0, i).trim().lowercase()] = p.substring(i + 1).trim() }
        }
        val cookie = o.optString("cookie").takeIf { it.isNotEmpty() && it != "null" }
            ?: o.optJSONObject("headers")?.let { hd ->
                hd.optString("cookie").ifEmpty { hd.optString("Cookie") }.takeIf { it.isNotEmpty() && it != "null" }
            }
        cookie?.let { headers["Cookie"] = it }
        val logo = o.optString("logo").takeIf { it.isNotEmpty() && it != "null" } ?: ""
        val backup = o.optString("url_backup").takeIf { it.isNotEmpty() && it != "null" } ?: ""
        return Channel(o.optString("id"), name, logo, guessCategory(o, name), raw, backup, headers, buildLicense(o))
    }

    private fun buildLicense(o: JSONObject): String {
        val allKeys = o.optString("all_keys").ifEmpty { o.optString("allKeys").ifEmpty { o.optString("allkeys") } }
        if (allKeys.isNotEmpty() && allKeys != "null" && allKeys.contains(':')) return allKeys
        val lu = o.optString("license_url").ifEmpty { o.optString("licenseUrl").ifEmpty { o.optString("drmLicense").ifEmpty { o.optString("drm_license") } } }
        if (lu.isNotEmpty() && lu != "null") return lu
        val kid = o.optString("keyId").ifEmpty { o.optString("kid").ifEmpty { o.optString("key_id") } }
        val key = o.optString("key").ifEmpty { o.optString("KID").ifEmpty { o.optString("drm_key") } }
        if (kid.isNotEmpty() && key.isNotEmpty() && kid != "null" && key != "null") return "$kid:$key"
        return ""
    }

    private val CAT_FIELDS = listOf("group", "category", "cat", "genre", "type", "language")

    private fun guessCategory(o: JSONObject, name: String): String {
        for (f in CAT_FIELDS) {
            val v = o.optString(f).trim()
            if (v.isNotEmpty() && !v.equals("null", true)) return v
        }
        return when {
            Regex("\\b(SPORTS?|CRICKET|FOOTBALL|ESPN|STARZPLAY|TENNIS|HOCKEY|KABADDI|TEN\\s?SPORTS?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Sports"
            Regex("\\b(NEWS|AAJ TAK|NDTV|CNN|BBC|REPUBLIC|ABP|INDIA TV|TIMES NOW|NEWS18)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "News"
            Regex("\\b(KIDS?|CARTOON|DISNEY|POGO|NICK|NICKELODEON|CHUTTI|SONY YAY|KUSHI|KOCHU|CHINTU|BABYTV)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Kids"
            Regex("\\b(MOVIES?|CINEMA|MAX|HBO|PICTURES|PIX|MINIPLEX|STAR GOLD|GOLDMINES)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Movies"
            Regex("\\b(MUSIC|MTV|SANGEET|9XM|B4U)\\b", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Music"
            else -> "Entertainment"
        }
    }

    private fun score(ch: Channel): Int =
        (if (ch.url.isEmpty()) 0 else 2) + (if (ch.url.contains(".m3u8")) 2 else 0) +
            (if (ch.license.isNotEmpty()) 1 else 0) + (if (ch.backup.isNotEmpty()) 1 else 0) +
            (if (ch.headers.isNotEmpty()) 1 else 0)

    // ---- offline cache: pehli baar ke baad instant open, worker down ho to bhi list dikhe ----
    private fun cacheFile(c: Context) = File(c.filesDir, "live_channels.json")

    fun saveCache(c: Context, list: List<Channel>) {
        try {
            val arr = JSONArray()
            for (ch in list) arr.put(
                JSONObject().put("id", ch.id).put("name", ch.name).put("logo", ch.logo).put("cat", ch.category)
                    .put("url", ch.url).put("backup", ch.backup).put("hdr", JSONObject(ch.headers)).put("drm", ch.license)
            )
            cacheFile(c).writeText(arr.toString())
        } catch (_: Throwable) {}
    }

    fun loadCache(c: Context): List<Channel> = try {
        val arr = JSONArray(cacheFile(c).readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val hdr = HashMap<String, String>()
            o.optJSONObject("hdr")?.let { h -> for (k in h.keys()) hdr[k] = h.optString(k) }
            Channel(o.optString("id"), o.optString("name"), o.optString("logo"), o.optString("cat"),
                o.optString("url"), o.optString("backup"), hdr, o.optString("drm"))
        }.filter { it.url.isNotEmpty() }
    } catch (_: Throwable) { emptyList() }
}

/** Channel logos from URLs (same pattern as Thumbs, but https). */
object LiveLogo {
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).coerceAtMost(24L shl 20).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val exec = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(iv: ImageView, url: String) {
        iv.tag = url
        mem.get(url)?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        if (url.isEmpty()) return
        exec.submit {
            try {
                if (iv.tag != url) return@submit
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", Channels.UA)
                val data = c.inputStream.use { it.readBytes() }
                val bm = Thumbs.decodeBytes(data, 220) ?: return@submit
                mem.put(url, bm)
                main.post { if (iv.tag == url) iv.setImageBitmap(bm) }
            } catch (_: Throwable) {}
        }
    }
}

/** "TV" tile screen: category chips + channel grid + search, sab D-pad se chalta hai. */
class LiveTvActivity : BaseActivity() {
    private lateinit var grid: RecyclerView
    private lateinit var sub: TextView
    private lateinit var search: EditText
    private lateinit var chipsRow: LinearLayout
    private lateinit var loading: View
    private lateinit var emptyBox: View
    private lateinit var empty: TextView
    private lateinit var btnRetry: TextView
    private var cards: ChannelAdapter? = null
    private var all: List<Channel> = emptyList()
    private var cat = "All"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_tv)
        grid = findViewById(R.id.grid)
        sub = findViewById(R.id.sub)
        search = findViewById(R.id.search)
        chipsRow = findViewById(R.id.chipsRow)
        loading = findViewById(R.id.loading)
        emptyBox = findViewById(R.id.emptyBox)
        empty = findViewById(R.id.empty)
        btnRetry = findViewById(R.id.btnRetry)
        grid.itemAnimator = null
        grid.layoutManager = GridLayoutManager(this, 5)
        cards = ChannelAdapter { list, i -> open(list, i) }
        grid.adapter = cards
        btnRetry.setOnClickListener { reload() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { applyFilter() }
        })
        val cached = Channels.loadCache(this)
        if (cached.isNotEmpty()) {
            all = cached
            afterData()
            grid.post { grid.getChildAt(0)?.requestFocus() }
        } else {
            loading.visibility = View.VISIBLE
        }
        reload()
    }

    private fun reload() {
        loading.visibility = View.VISIBLE
        emptyBox.visibility = View.GONE
        App.inst.io.launch {
            val list = try { Channels.fetch() } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                loading.visibility = View.GONE
                if (list.isNotEmpty()) {
                    Channels.saveCache(this@LiveTvActivity, list)
                    App.inst.prefs.tvCount = list.size
                    all = list
                    afterData()
                    if (!search.hasFocus()) grid.post { grid.getChildAt(0)?.requestFocus() }
                } else if (all.isEmpty()) {
                    empty.text = "Channel list nahi aayi — internet check karke dobara try karo"
                    btnRetry.visibility = View.VISIBLE
                    emptyBox.visibility = View.VISIBLE
                    btnRetry.requestFocus()
                }
            }
        }
    }

    private fun afterData() {
        buildChips()
        applyFilter()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildChips() {
        val cats = LinkedHashSet<String>()
        all.forEach { cats.add(it.category) }
        val order = listOf("All", "Sports", "Entertainment", "Movies", "News", "Music", "Kids", "Regional", "Channels")
        val sorted = cats.sortedBy { c -> order.indexOf(c).takeIf { it >= 0 } ?: (100 + c[0].code) }
        chipsRow.removeAllViews()
        val saved = App.inst.prefs.tvCategory
        cat = if (saved == "All" || sorted.contains(saved)) saved else "All"
        for (c in listOf("All") + sorted) {
            val chip = TextView(this)
            chip.text = c
            chip.textSize = 12f
            chip.setTypeface(chip.typeface, Typeface.BOLD)
            Fonts.apply(chip)
            chip.setBackgroundResource(R.drawable.chip_bg)
            chip.setPadding(dp(14), dp(6), dp(16), dp(6))
            chip.gravity = Gravity.CENTER_VERTICAL
            chip.isFocusable = true; chip.isClickable = true
            chip.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
            Focus.grow(chip, 1.06f)
            chip.setOnClickListener { selectCat(c) }
            chip.setTextColor(if (c == cat) 0xFFFF4FA3.toInt() else 0xFFFFFFFF.toInt())
            chipsRow.addView(chip)
        }
    }

    private fun selectCat(c: String) {
        cat = c
        App.inst.prefs.tvCategory = c
        for (i in 0 until chipsRow.childCount) {
            val chip = chipsRow.getChildAt(i) as TextView
            chip.setTextColor(if (chip.text.toString() == c) 0xFFFF4FA3.toInt() else 0xFFFFFFFF.toInt())
        }
        applyFilter()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val list = when {
            q.isNotEmpty() -> all.filter { it.name.lowercase().contains(q) }
            cat == "All" -> all
            else -> all.filter { it.category == cat }
        }
        sub.text = when {
            all.isEmpty() -> "Channels load ho rahe hain…"
            q.isNotEmpty() -> "${Fmt.count(list.size)} mile"
            cat != "All" -> "${Fmt.count(list.size)} channels · $cat"
            else -> "${Fmt.count(list.size)} channels"
        }
        cards?.set(list)
        if (list.isEmpty() && all.isNotEmpty()) {
            empty.text = "Is category / search me kuch nahi mila"
            btnRetry.visibility = View.GONE
            emptyBox.visibility = View.VISIBLE
        } else if (loading.visibility != View.VISIBLE) {
            emptyBox.visibility = View.GONE
        }
    }

    private fun open(list: List<Channel>, index: Int) {
        LivePlayActivity.queue = list
        startActivity(Intent(this, LivePlayActivity::class.java).putExtra("index", index))
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_MENU) { reload(); return true }
        return super.dispatchKeyEvent(e)
    }
}

class ChannelAdapter(private val click: (List<Channel>, Int) -> Unit) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    private var items: List<Channel> = emptyList()
    fun set(list: List<Channel>) { items = list; notifyDataSetChanged() }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val cat: TextView = v.findViewById(R.id.cat)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
        val v = LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false)
        Focus.grow(v, 1.07f)
        v.findViewById<View>(R.id.thumbWrap).clipToOutline = true
        return VH(v)
    }
    override fun getItemCount() = items.size
    override fun onBindViewHolder(h: VH, i: Int) {
        val ch = items[i]
        LiveLogo.load(h.logo, ch.logo)
        h.name.text = ch.name
        h.cat.text = ch.category
        h.itemView.setOnClickListener { _ -> click(items, i) }
    }
}
