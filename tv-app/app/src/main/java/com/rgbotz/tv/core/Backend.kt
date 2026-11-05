package com.rgbotz.tv.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Telegram ka kaam app ke andar chalne wale Python server (tgserver.py, Telethon bot login) se hota hai.
 * Ye file usse localhost par baat karti hai. Kuch bhi media TV storage me save nahi hota.
 * Settings me remote "Server URL" dala ho to wahi URL use hota hai (embedded server start hi nahi hota).
 */
object Local {
    const val PORT = 8090
    @Volatile var BASE = "http://127.0.0.1:$PORT"

    fun setCustom(url: String?) {
        BASE = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "http://127.0.0.1:$PORT"
    }

    val isRemote: Boolean get() = BASE != "http://127.0.0.1:$PORT"

    fun get(path: String, timeoutMs: Int = 15_000): String {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 3_000; c.readTimeout = timeoutMs
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    fun bytes(path: String, timeoutMs: Int = 60_000, progress: ((Long, Long) -> Unit)? = null): ByteArray? {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 3_000; c.readTimeout = timeoutMs
        try {
            if (c.responseCode !in 200..299) return null
            val total = c.contentLengthLong
            val out = ByteArrayOutputStream(if (total in 1..64_000_000) total.toInt() else 64 * 1024)
            val buf = ByteArray(64 * 1024)
            c.inputStream.use { i ->
                var done = 0L
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    progress?.invoke(done, total)
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    fun toFile(path: String, dst: File, progress: (Long, Long) -> Unit): Boolean {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 3_000; c.readTimeout = 60_000
        try {
            if (c.responseCode !in 200..299) return false
            val total = c.contentLengthLong
            dst.parentFile?.mkdirs()
            c.inputStream.use { i -> dst.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; progress(done, total) }
            } }
            return total <= 0 || dst.length() == total
        } finally { c.disconnect() }
    }
}

enum class TdStatus { STARTING, CONNECTING, READY, NO_API, AUTH_ERROR, CLOSED }

/** Login / connection status of the in-app server (name kept from the old TDLib version). */
class Td(hasApi: Boolean) {
    private val statusListeners = CopyOnWriteArrayList<(TdStatus, String?) -> Unit>()
    @Volatile var status = if (hasApi) TdStatus.STARTING else TdStatus.NO_API; internal set
    @Volatile var statusMsg: String? = null; internal set
    @Volatile var online = true; internal set

    fun addStatusListener(l: (TdStatus, String?) -> Unit) { statusListeners.add(l) }
    fun removeStatusListener(l: (TdStatus, String?) -> Unit) { statusListeners.remove(l) }

    internal fun set(s: TdStatus, msg: String?, net: Boolean) {
        val changed = s != status || msg != statusMsg || net != online
        status = s; statusMsg = msg; online = net
        if (changed) for (l in statusListeners) try { l(s, msg) } catch (_: Throwable) {}
    }
}

/** Channel ki media list (server se). */
class Library(private val td: Td) {
    private val map = TreeMap<Long, Item>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var scanning = false; private set
    @Volatile var scannedUpTo = 0L; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var chatTitle: String = ""; private set
    @Volatile var latestUpdate: UpdateInfo? = null; private set
    @Volatile private var version = -1L

    fun items(): List<Item> = synchronized(map) { map.values.reversed() }
    fun items(kind: Kind): List<Item> = items().filter { it.kind == kind }
    fun get(id: Long): Item? = synchronized(map) { map[id] }
    fun count(kind: Kind): Int = synchronized(map) { map.values.count { it.kind == kind } }
    fun totalSize(kind: Kind): Long = synchronized(map) { map.values.filter { it.kind == kind }.sumOf { it.size } }
    fun tags(): List<Pair<String, Int>> = synchronized(map) {
        map.values.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun subtitlesFor(video: Item): List<Item> {
        val base = video.fileName.substringBeforeLast('.').lowercase()
        return synchronized(map) {
            map.values.filter { s ->
                s.kind == Kind.SUB && (s.replyTo == video.id ||
                    (base.isNotEmpty() && s.fileName.lowercase().startsWith(base)) ||
                    (s.caption.isNotBlank() && video.title.isNotBlank() && s.caption.contains(video.title, ignoreCase = true)))
            }
        }
    }

    private fun notifyChanged() { for (l in listeners) try { l() } catch (_: Throwable) {} }

    /** Server status padho; list badli ho to dobara lao. Background thread se call hota hai. */
    fun poll(): Long {
        val o = try { JSONObject(Local.get("/api/status", 5_000)) } catch (t: Throwable) {
            if (td.status != TdStatus.NO_API) td.set(TdStatus.CONNECTING, null, td.online)
            return 0L
        }
        val ready = o.optBoolean("ready")
        val err = o.optString("error")
        val st = when {
            ready -> TdStatus.READY
            Regex("(?i)token|unauthori|auth_key|api_id|accesstoken").containsMatchIn(err) -> TdStatus.AUTH_ERROR
            else -> TdStatus.CONNECTING
        }
        val wasScanning = scanning
        scanning = o.optBoolean("scanning")
        scannedUpTo = o.optLong("scanned")
        chatTitle = o.optString("title")
        lastError = o.optString("channelError").ifEmpty { null }
        latestUpdate = o.optJSONObject("update")?.let { UpdateInfo(it.getLong("id"), it.getInt("code"), it.optString("name"), it.optLong("size")) }
        td.set(st, err.ifEmpty { null }, o.optBoolean("online", true))
        val v = o.optLong("version", 0)
        if (v != version && ready) {
            try {
                val arr = JSONArray(Local.get("/api/list", 30_000))
                val tmp = TreeMap<Long, Item>()
                for (i in 0 until arr.length()) { val it = Item.fromJson(arr.getJSONObject(i)); tmp[it.id] = it }
                synchronized(map) { map.clear(); map.putAll(tmp) }
                version = v
                notifyChanged()
            } catch (_: Throwable) {}
        } else if (wasScanning != scanning) notifyChanged()
        return o.optLong("speed")
    }

    fun refresh() { try { Local.get("/api/scan") } catch (_: Throwable) {} }

    /** Server switch: purani list saaf, agli poll naye server se sab padhega. */
    fun reset() {
        synchronized(map) { map.clear() }
        version = -1L
        scanning = false
        scannedUpTo = 0L
        lastError = null
        chatTitle = ""
        latestUpdate = null
        notifyChanged()
    }
}

/**
 * Download speed + buffer knobs. Server data seedha RAM se player ko deta hai (disk par kuch nahi),
 * isliye "lead" sirf player ka RAM buffer hai.
 */
class Streams {
    @Volatile var aheadMb: Int = 48
    @Volatile var cacheLimit: Long = 300L shl 20
    @Volatile var speedBps = 0L
    fun ahead(): Long = 24L shl 20
    fun leadBytes(): Long = 0L
    fun leadAtEnd(): Boolean = false
    fun speed(): Long = speedBps
    fun release(@Suppress("UNUSED_PARAMETER") fileId: Int) {}
    fun releaseAll() {}
}
