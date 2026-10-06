package com.chardidathing.litehub

import android.app.Application
import com.chardidathing.litehub.core.config.ConfigException
import com.chardidathing.litehub.core.config.SourcesCodec
import com.chardidathing.litehub.core.model.Sources
import com.chardidathing.litehub.source.calendar.CalendarRepository
import com.chardidathing.litehub.source.calendar.CalendarStore
import com.chardidathing.litehub.source.fetch.Fetcher
import com.chardidathing.litehub.source.feed.FeedRepository
import com.chardidathing.litehub.source.feed.FeedStore
import com.chardidathing.litehub.source.ha.EntityCache
import com.chardidathing.litehub.source.ha.EntityRepository
import com.chardidathing.litehub.source.ha.HaCredentials
import com.chardidathing.litehub.ui.components.Icons
import com.chardidathing.litehub.ui.tokens.Fonts
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

// hand wired singletons. every one of these reads disk, so first touch them off the main thread
class LitehubApp : Application() {

    val fonts by lazy { Fonts(assets) }

    val icons by lazy { Icons(assets) }

    // one connection pool for everything, the disk cache is what turns unchanged feeds into 304s
    val http by lazy { OkHttpClient.Builder().cache(Cache(File(cacheDir, "http"), HTTP_CACHE_BYTES)).build() }

    val ha by lazy {
        EntityRepository(
            credentials = HaCredentials.load(File(filesDir, HA_FILE)),
            http = http,
            cache = EntityCache(this),
        )
    }

    // no file is no sources, a broken one is a failure the widgets show
    val sources: Result<Sources> by lazy {
        val file = File(filesDir, SOURCES_FILE)
        if (!file.exists()) return@lazy Result.success(Sources(SourcesCodec.VERSION))
        try {
            Result.success(SourcesCodec.decode(file.readText()))
        } catch (e: ConfigException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(IOException("couldn't read sources.json", e))
        }
    }

    val calendars by lazy {
        CalendarRepository(sources.getOrNull()?.calendars.orEmpty(), Fetcher(http), CalendarStore(this), ha)
    }

    val feeds by lazy { FeedRepository(sources.getOrNull()?.feeds.orEmpty(), Fetcher(http), FeedStore(this)) }

    companion object {
        const val HA_FILE = "ha.json"
        const val CONFIG_FILE = "config.json"
        const val SOURCES_FILE = "sources.json"
        const val HTTP_CACHE_BYTES = 10L * 1024 * 1024
    }
}
