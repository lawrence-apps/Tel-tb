package com.rgbotz.tv

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs

/** Buffer presets for the player (RAM) and the disk read-ahead. Tuned for ~1 GB RAM Android TVs. */
class BufferProfile(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int, val ramBytes: Int, val aheadMb: Int) {
    companion object {
        fun of(ctx: Context, zyada: Boolean, smooth: Boolean = false): BufferProfile {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val heapMb = maxOf(am.memoryClass, if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP != 0) am.largeMemoryClass else 0)
            // never let the in-RAM media buffer take more than ~1/5 of the Java heap
            val cap = maxOf(12, heapMb / 5) shl 20
            return if (zyada) BufferProfile(20_000, 50_000, if (smooth) 15_000 else 2_000, 8_000, minOf(32 shl 20, cap), 128)
            else BufferProfile(15_000, 30_000, if (smooth) 15_000 else 2_000, 5_000, minOf(20 shl 20, cap), 48)
        }
        fun freeBytes(ctx: Context): Long = try { StatFs(ctx.cacheDir.path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }
    }
}

/**
 * "Smooth mode": after a real stall (not a seek) playback stays paused until a big lead is in the buffer:
 * RAM buffer + the disk read-ahead >= [targetBytes] (about 60 MB) or ~90 s of media, whichever comes first,
 * or the end of the file is buffered, or [CAP_MS] passed (so it never waits forever).
 * Wraps the normal LoadControl, which still decides how much goes into RAM.
 */
@androidx.media3.common.util.UnstableApi
class SmoothLoadControl(
    private val d: androidx.media3.exoplayer.LoadControl,
    private val streams: com.rgbotz.tv.core.Streams,
    /** average media bytes per second (file size / duration) */
    val bytesPerSec: Long,
    val enabled: Boolean,
) : androidx.media3.exoplayer.LoadControl {
    // Java default methods ko Kotlin "by" delegate nahi karta, isliye har method khud forward karo
    override fun onPrepared(playerId: androidx.media3.exoplayer.analytics.PlayerId) = d.onPrepared(playerId)
    override fun onTracksSelected(
        parameters: androidx.media3.exoplayer.LoadControl.Parameters,
        trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
        trackSelections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>,
    ) = d.onTracksSelected(parameters, trackGroups, trackSelections)
    @Suppress("DEPRECATION")
    override fun onTracksSelected(
        playerId: androidx.media3.exoplayer.analytics.PlayerId,
        timeline: androidx.media3.common.Timeline,
        mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId,
        renderers: Array<out androidx.media3.exoplayer.Renderer>,
        trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
        trackSelections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>,
    ) = d.onTracksSelected(playerId, timeline, mediaPeriodId, renderers, trackGroups, trackSelections)
    override fun onStopped(playerId: androidx.media3.exoplayer.analytics.PlayerId) = d.onStopped(playerId)
    override fun onReleased(playerId: androidx.media3.exoplayer.analytics.PlayerId) = d.onReleased(playerId)
    override fun getAllocator(): androidx.media3.exoplayer.upstream.Allocator = d.allocator
    override fun getBackBufferDurationUs(playerId: androidx.media3.exoplayer.analytics.PlayerId): Long = d.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: androidx.media3.exoplayer.analytics.PlayerId): Boolean = d.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean = d.shouldContinueLoading(parameters)

    @Volatile var refilling = false
    @Volatile var justSeeked = false
    @Volatile var waitSince = 0L
    @Volatile var leadBytes = 0L; private set
    @Volatile var targetBytes = 0L; private set

    fun target(): Long = minOf(TARGET_MB shl 20, streams.ahead() * 3 / 4)

    /** Called from the main thread by the player listener when the playback state changes. */
    fun onState(prev: Int, state: Int, playWhenReady: Boolean) {
        if (state == androidx.media3.common.Player.STATE_BUFFERING) {
            if (prev == androidx.media3.common.Player.STATE_READY && playWhenReady && !justSeeked) { refilling = true; waitSince = 0L }
        } else if (state == androidx.media3.common.Player.STATE_READY) {
            refilling = false; justSeeked = false; waitSince = 0L
        }
    }

    override fun shouldStartPlayback(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean {
        val base = d.shouldStartPlayback(parameters)
        if (!enabled || !refilling) return base
        val now = System.currentTimeMillis()
        if (waitSince == 0L) waitSince = now
        val bps = bytesPerSec.coerceAtLeast(100_000L)
        val lead = streams.leadBytes() + parameters.bufferedDurationUs * bps / 1_000_000L
        val tgt = target()
        leadBytes = lead; targetBytes = tgt
        val ok = lead >= tgt || lead >= bps * LEAD_SEC || streams.leadAtEnd() || now - waitSince > CAP_MS
        return base && ok
    }

    companion object {
        const val TARGET_MB = 60L
        const val LEAD_SEC = 90L
        const val CAP_MS = 150_000L
    }
}
