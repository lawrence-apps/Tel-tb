package com.rgbotz.tv.core

import android.util.Base64
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

enum class Kind { VIDEO, AUDIO, PHOTO, SUB }

/** One media post of the channel. Only this small index is cached on the TV, never the media. */
class Item(
    val id: Long,            // server message id (1, 2, 3 ...)
    val kind: Kind,
    val title: String,
    val fileName: String,
    val mime: String,
    val size: Long,
    val duration: Int,
    val width: Int,
    val height: Int,
    val date: Int,
    val performer: String,
    val tags: List<String>,
    val mini: ByteArray?,    // Telegram minithumbnail (tiny blurred JPEG, ~1 KB)
    val replyTo: Long,
    val caption: String,
    /** 2GB+ video: Telegram bots itni badi file nahi chala sakte (premium user session chahiye). */
    val oversize: Boolean = false,
) {
    val ext: String get() = fileName.substringAfterLast('.', "").lowercase()

    val quality: String
        get() {
            val h = if (height > 0) minOf(height, width.takeIf { it > 0 } ?: height) else 0
            val fromName = Regex("(?i)(2160p|4k|uhd|1440p|1080p|720p|576p|480p|360p)").find(fileName + " " + title)?.value?.lowercase()
            return when {
                h >= 1600 || fromName == "2160p" || fromName == "4k" || fromName == "uhd" -> "4K"
                h >= 1000 || fromName == "1080p" -> "1080p"
                h >= 700 || fromName == "720p" -> "720p"
                h >= 400 || fromName == "480p" || fromName == "576p" -> "480p"
                h > 0 || fromName == "360p" -> "SD"
                else -> ""
            }
        }

    fun write(o: DataOutputStream) {
        o.writeLong(id); o.writeByte(kind.ordinal); o.writeUTF(title.take(300)); o.writeUTF(fileName.take(300)); o.writeUTF(mime)
        o.writeLong(size); o.writeInt(duration); o.writeInt(width); o.writeInt(height); o.writeInt(date)
        o.writeUTF(performer.take(200)); o.writeUTF(tags.joinToString(" ")); o.writeLong(replyTo); o.writeUTF(caption.take(1000))
        val m = mini
        if (m == null || m.size > 30000) o.writeInt(0) else { o.writeInt(m.size); o.write(m) }
    }

    companion object {
        fun fromJson(o: JSONObject): Item {
            val tags = o.optJSONArray("tags")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val mini = o.optString("mini", "").takeIf { it.isNotEmpty() }?.let { try { Base64.decode(it, Base64.DEFAULT) } catch (_: Throwable) { null } }
            val kind = when (o.optString("kind")) { "video" -> Kind.VIDEO; "audio" -> Kind.AUDIO; "photo" -> Kind.PHOTO; else -> Kind.SUB }
            return Item(o.getLong("id"), kind, o.optString("title"), o.optString("fileName"), o.optString("mime"), o.optLong("size"),
                o.optInt("duration"), o.optInt("width"), o.optInt("height"), o.optInt("date"), o.optString("performer"), tags, mini,
                o.optLong("replyTo"), o.optString("caption"), o.optBoolean("big", false))
        }

        fun read(i: DataInputStream): Item {
            val id = i.readLong(); val kind = Kind.values()[i.readByte().toInt()]
            val title = i.readUTF(); val fileName = i.readUTF(); val mime = i.readUTF()
            val size = i.readLong(); val dur = i.readInt(); val w = i.readInt(); val h = i.readInt(); val date = i.readInt()
            val perf = i.readUTF(); val tags = i.readUTF().split(' ').filter { it.isNotEmpty() }
            val reply = i.readLong(); val cap = i.readUTF()
            val n = i.readInt(); val mini = if (n > 0) ByteArray(n).also { i.readFully(it) } else null
            return Item(id, kind, title, fileName, mime, size, dur, w, h, date, perf, tags, mini, reply, cap)
        }
    }
}

/** An app update posted in the channel: document with caption "RGTV-UPDATE <versionCode> <versionName>". */
class UpdateInfo(val msgId: Long, val versionCode: Int, val versionName: String, val size: Long)

object Parse {
    val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "webm", "wmv", "flv", "3gp", "mpg", "mpeg", "vob", "ogv", "divx", "rmvb", "mk3d")
    val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "oga", "opus", "wma", "ac3", "eac3", "dts", "mka", "alac", "aiff", "aif", "ape", "amr", "mp2")
    val PHOTO_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
    val SUB_EXT = setOf("srt", "vtt", "ass", "ssa")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun kindOfDocument(fileName: String, mime: String): Kind? {
        val e = ext(fileName)
        return when {
            e in SUB_EXT || mime == "application/x-subrip" || mime == "text/vtt" -> Kind.SUB
            e in VIDEO_EXT || mime.startsWith("video/") -> Kind.VIDEO
            e in AUDIO_EXT || mime.startsWith("audio/") -> Kind.AUDIO
            e in PHOTO_EXT || mime.startsWith("image/") -> Kind.PHOTO
            else -> null
        }
    }

    fun firstLine(text: String): String =
        text.lines().map { it.replace(Regex("#[\\p{L}\\p{N}_]+"), "").trim() }.firstOrNull { it.isNotEmpty() }?.take(140) ?: ""

    fun cleanName(fileName: String): String {
        if (fileName.isBlank()) return ""
        val base = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
        return base.replace('_', ' ').replace(Regex("(?<=\\S)\\.(?=\\S)"), " ").replace(Regex("\\s+"), " ").trim()
    }

}
