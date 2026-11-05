package com.rgbotz.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun CoroutineScope.launchSafe(block: suspend () -> Unit): Job = launch {
    try { block() } catch (t: Throwable) { android.util.Log.w("RGTV", "bg: $t") }
}
