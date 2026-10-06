package com.chardidathing.litehub.source.feed

import android.database.sqlite.SQLiteException
import com.chardidathing.litehub.core.model.FeedItem
import com.chardidathing.litehub.core.model.FeedSnapshot
import com.chardidathing.litehub.core.model.FeedSource
import com.chardidathing.litehub.core.model.SourceStatus
import com.chardidathing.litehub.source.fetch.Fetcher
import com.chardidathing.litehub.source.fetch.RefreshLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes

// every feed source, refreshed on its own interval while started. a failed refresh keeps the
// last good items and says why
class FeedRepository(
    sources: List<FeedSource>,
    private val fetcher: Fetcher,
    private val store: FeedStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val confined = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private val byId = sources.associateBy { it.id }
    private val _snapshot = MutableStateFlow(FeedSnapshot(emptyList(), emptyMap()))
    val snapshot: StateFlow<FeedSnapshot> = _snapshot

    private val loop = RefreshLoop(
        scope = scope,
        intervals = sources.associate { it.id to it.refreshMinutes.minutes },
        now = now,
        refresh = ::refresh,
    )

    // reads what's stored, call before the first frame so headlines draw with data
    suspend fun preload() = withContext(confined) {
        try {
            store.keepOnly(byId.keys)
            loop.seed(store.statuses().mapValues { it.value.lastGood })
        } catch (e: SQLiteException) {
            // no store just means fetching everything fresh
        }
        publish()
    }

    fun start() = loop.start()

    fun stop() = loop.stop()

    private suspend fun refresh(id: String): Boolean {
        val source = byId[id] ?: return true
        // an unchanged feed is a 304, nothing to parse
        val result = fetcher.get(source.url) { reader, changed -> if (changed) FeedParser.parse(reader, id) else null }
        try {
            result.fold(
                onSuccess = { items -> if (items == null) store.unchanged(id, now()) else store.replace(id, items, now()) },
                onFailure = { store.failed(id, it.message ?: "couldn't fetch") },
            )
        } catch (e: SQLiteException) {
            // the data was fine, the cache couldn't take it
        }
        publish()
        return result.isSuccess
    }

    private fun publish() {
        val (items, statuses) = try {
            store.items() to store.statuses()
        } catch (e: SQLiteException) {
            emptyList<FeedItem>() to emptyMap<String, SourceStatus>()
        }
        // newest first, undated items keep their place after the dated ones
        val sorted = items.filter { it.source in byId }.sortedByDescending { it.publishedMs ?: Long.MIN_VALUE }
        _snapshot.value = FeedSnapshot(sorted, statuses)
    }
}
