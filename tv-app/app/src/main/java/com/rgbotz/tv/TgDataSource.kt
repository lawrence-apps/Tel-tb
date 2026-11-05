package com.rgbotz.tv

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.rgbotz.tv.core.Local

/** Player app ke andar wale server (127.0.0.1) se Range requests me file padhta hai. */
@UnstableApi
object TgDataSource {
    class Factory(@Suppress("UNUSED_PARAMETER") app: App) : DataSource.Factory {
        private val f = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(90_000)
            .setKeepPostFor302Redirects(false)
        override fun createDataSource(): DataSource = f.createDataSource()
    }

    fun uriFor(id: Long): Uri = Uri.parse("${Local.BASE}/f/$id")
}
