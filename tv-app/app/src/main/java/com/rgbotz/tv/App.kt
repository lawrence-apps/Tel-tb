package com.rgbotz.tv

import android.app.Application
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.rgbotz.tv.core.Library
import com.rgbotz.tv.core.Local
import com.rgbotz.tv.core.Streams
import com.rgbotz.tv.core.Td
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class App : Application() {
    lateinit var td: Td
    lateinit var lib: Library
    lateinit var streams: Streams
    lateinit var prefs: Prefs
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var demoStatus: String? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        installCrashReporter()
        prefs = Prefs(this)
        Local.setCustom(prefs.serverUrl)
        File(cacheDir, "tdfiles").apply { deleteRecursively(); mkdirs() }
        val hasApi = BuildConfig.TG_API_HASH.isNotEmpty() && Secrets.botToken().isNotEmpty()
        td = Td(hasApi)
        streams = Streams()
        streams.aheadMb = BufferProfile.of(this, prefs.bufferMode == 1, prefs.smooth).aheadMb
        lib = Library(td)
        // Remote server set hai to embedded Python server start hi nahi hota — memory bachi, net bhi.
        if (hasApi && prefs.serverUrl.isBlank()) startServer()
        Thread {
            while (true) {
                try { streams.speedBps = lib.poll() } catch (t: Throwable) { Log.w("TGTV", "poll: $t") }
                Thread.sleep(1500)
            }
        }.apply { isDaemon = true; name = "tg-poll" }.start()
    }

    private fun startServer() {
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                Python.getInstance().getModule("tgserver").callAttr(
                    "start", filesDir.absolutePath, BuildConfig.TG_API_ID, BuildConfig.TG_API_HASH,
                    Secrets.botToken(), BuildConfig.TG_CHAT_ID, Local.PORT, BuildConfig.TG_ADMIN_ID
                )
            } catch (t: Throwable) { Log.e("TGTV", "server start failed", t) }
        }.apply { name = "tg-server-start" }.start()
    }

    /** Crash ho to stack trace file me likho; agli baar app khulte hi bot admin ko bhej deta hai. */
    private fun installCrashReporter() {
        val f = File(filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { f.writeText("v${BuildConfig.VERSION_NAME} ${android.os.Build.MODEL}\n" + Log.getStackTraceString(e).take(3500)) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        if (f.exists()) Thread {
            try {
                val tok = Secrets.botToken()
                val admin = BuildConfig.TG_ADMIN_ID.ifEmpty { "1345802943" }
                val c = java.net.URL("https://api.telegram.org/bot$tok/sendMessage").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                val body = "chat_id=$admin&text=" + java.net.URLEncoder.encode("TV crash:\n" + f.readText(), "UTF-8")
                c.outputStream.use { it.write(body.toByteArray()) }
                if (c.responseCode == 200) f.delete()
                c.disconnect()
            } catch (_: Throwable) {}
        }.start()
    }

    fun refreshSoon() { Thread { lib.refresh() }.start() }

    companion object {
        lateinit var inst: App
            private set
    }
}

object Secrets {
    fun botToken(): String {
        val hex = BuildConfig.TG_BOT_X
        if (hex.isEmpty()) return ""
        val key = "rgtv-tvdb".toByteArray()
        val out = ByteArray(hex.length / 2) { i -> (hex.substring(i * 2, i * 2 + 2).toInt(16) xor key[i % key.size].toInt()).toByte() }
        return String(out)
    }
}

/**
 * Errors bot admin ko khud bhej deta hai (crash reporter jaisa hi), bina kisi click ke.
 * Ek hi error 1 ghante me sirf 1 baar jata hai — spam nahi hoga.
 */
object Report {
    private val lastSent = HashMap<String, Long>()

    fun send(key: String, title: String, detail: String) {
        val admin = BuildConfig.TG_ADMIN_ID
        if (admin.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lastSent) {
            if (now - (lastSent[key] ?: 0) < 3_600_000) return
            lastSent[key] = now
        }
        Thread {
            try {
                val tok = Secrets.botToken()
                val c = java.net.URL("https://api.telegram.org/bot$tok/sendMessage").openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                val text = "$title\n$detail\n—\n${android.os.Build.MODEL} · v${BuildConfig.VERSION_NAME}"
                val body = "chat_id=$admin&text=" + java.net.URLEncoder.encode(text, "UTF-8")
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode; c.disconnect()
            } catch (_: Throwable) {}
        }.start()
    }
}
