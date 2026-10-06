package com.chardidathing.litehub.source.fetch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

// refreshes each source when it's due, one at a time, while started. due times come from the
// last good fetch so a restart doesn't refetch everything that's still fresh. scope must be
// single threaded
class RefreshLoop(
    private val scope: CoroutineScope,
    private val intervals: Map<String, Duration>,
    private val now: () -> Long,
    private val refresh: suspend (id: String) -> Boolean,
) {

    private val nextDue = HashMap<String, Long>()
    private var job: Job? = null

    // lastGood per id, call before start so fresh data isn't fetched again
    fun seed(lastGood: Map<String, Long?>) {
        for ((id, interval) in intervals) {
            nextDue[id] = (lastGood[id] ?: 0L) + interval.inWholeMilliseconds
        }
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                val t = now()
                for ((id, interval) in intervals) {
                    if ((nextDue[id] ?: 0L) > t) continue
                    val ok = refresh(id)
                    nextDue[id] = now() + if (ok) interval.inWholeMilliseconds else RETRY.inWholeMilliseconds
                }
                val wait = (nextDue.values.minOrNull() ?: return@launch) - now()
                delay(wait.coerceAtLeast(0).milliseconds)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        // a failed fetch retries sooner than the source's interval, but not in a tight loop
        val RETRY = 5.minutes
    }
}
