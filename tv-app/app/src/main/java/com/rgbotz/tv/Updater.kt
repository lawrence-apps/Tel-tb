package com.rgbotz.tv

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Window
import android.widget.TextView
import androidx.core.content.FileProvider
import com.rgbotz.tv.core.Local
import com.rgbotz.tv.core.UpdateInfo
import java.io.File

/**
 * In-app updater. New versions are posted (by the bot) into the storage channel as a document
 * with caption "TGTV-UPDATE <versionCode> <versionName>". The id scan at launch and TDLib's live
 * channel updates (updateNewMessage) find it within seconds; if it is newer than this build a
 * popup opens on whatever screen is showing. TDLib downloads the APK into the temp cache and the
 * Android installer is opened.
 */
object Updater {
    private val app get() = App.inst
    private val h = Handler(Looper.getMainLooper())
    @Volatile var dismissed = -1
    private var dialog: Dialog? = null
    private var downloading = false
    private var pendingFile: File? = null
    private var pendingVersion = -1

    fun check(a: Activity) {
        val u = app.lib.latestUpdate ?: return
        if (u.versionCode <= BuildConfig.VERSION_CODE) return
        if (dismissed == u.versionCode || a.isFinishing) return
        if (dialog?.isShowing == true) return
        show(a, u)
    }

    private fun show(a: Activity, u: UpdateInfo) {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(Fonts.inflate(a, R.layout.dialog_update))
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.65f)
        val title = d.findViewById<TextView>(R.id.updTitle)
        val info = d.findViewById<TextView>(R.id.updInfo)
        val bUpd = d.findViewById<TextView>(R.id.btnUpdate)
        val bLater = d.findViewById<TextView>(R.id.btnLater)
        title.text = "Naya update aaya v${u.versionName}"
        info.text = "Abhi: v${BuildConfig.VERSION_NAME}  ·  Download: ${Fmt.size(u.size)}"
        if (pendingVersion != u.versionCode) pendingFile = null
        bUpd.text = if (pendingFile != null) "Install karo" else "Update"
        Focus.grow(bUpd, 1.05f); Focus.grow(bLater, 1.05f)
        bLater.setOnClickListener { dismissed = u.versionCode; d.dismiss() }
        d.setOnCancelListener { dismissed = u.versionCode }
        bUpd.setOnClickListener { val f = pendingFile; if (f != null) install(a, f, info) else if (!downloading) download(a, u, bUpd, info) }
        dialog = d
        d.show()
        bUpd.requestFocus()
    }

    private fun download(a: Activity, u: UpdateInfo, bUpd: TextView, info: TextView) {
        downloading = true
        bUpd.text = "Download ho raha hai… 0%"
        Thread {
            try {
                val apk = File(File(a.cacheDir, "tdfiles"), "update-${u.versionCode}.apk")
                var last = -1
                val ok = Local.toFile("/f/${u.msgId}", apk) { done, total ->
                    if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != last) { last = pct; h.post { bUpd.text = "Download ho raha hai… $pct%" } } }
                }
                if (!ok) throw IllegalStateException("download adhoora")
                h.post { downloading = false; pendingFile = apk; pendingVersion = u.versionCode; bUpd.text = "Install karo"; install(a, apk, info) }
            } catch (t: Throwable) {
                h.post { downloading = false; bUpd.text = "Dobara try karo"; info.text = "Download nahi hua: ${t.message}" }
            }
        }.start()
    }

    private fun install(a: Activity, apk: File, info: TextView) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            info.text = "Settings me is app ko Allow karo, phir Back dabao aur \"Install karo\" dabao"
            try { a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))) }
            catch (_: Throwable) { try { a.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Throwable) {} }
            return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW)
            if (Build.VERSION.SDK_INT >= 24) {
                val uri = FileProvider.getUriForFile(a, a.packageName + ".files", apk)
                i.setDataAndType(uri, "application/vnd.android.package-archive")
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                // Android 5/6: the installer needs a world-readable file
                val ext = File(a.externalCacheDir ?: a.cacheDir, "rgtv-update.apk")
                apk.copyTo(ext, overwrite = true)
                ext.setReadable(true, false)
                i.setDataAndType(Uri.fromFile(ext), "application/vnd.android.package-archive")
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            a.startActivity(i)
        } catch (t: Throwable) {
            info.text = "Install nahi khula: ${t.message}"
        }
    }
}
