package com.learn.antilazy

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Incrementally tracks the last explicitly resumed package. Call from one worker thread. */
class ForegroundAppTracker {

    companion object {
        private const val INITIAL_LOOKBACK_MS = 24 * 60 * 60_000L
        // UsageEvents may arrive late on OEM builds. Re-read a bounded overlap and deduplicate.
        private const val EVENT_DELIVERY_OVERLAP_MS = 60_000L
        private const val SEEN_EVENT_RETENTION_MS = 2 * EVENT_DELIVERY_OVERLAP_MS
        private const val FALLBACK_QUERY_INTERVAL_MS = 2_000L
        private const val FALLBACK_FRESHNESS_MS = 5_000L

        internal fun incrementalQueryStart(cursorWallMs: Long): Long =
            (cursorWallMs - EVENT_DELIVERY_OVERLAP_MS).coerceAtLeast(0L)

        internal fun mayReplaceCurrent(eventWallMs: Long, currentChangedAtWallMs: Long): Boolean =
            eventWallMs >= currentChangedAtWallMs
    }

    private var queryCursorWallMs = 0L
    private var lastFallbackQueryWallMs = 0L
    private var currentPackage: String? = null
    private var changedAtWallMs = 0L
    private val seenResumeKeys = HashSet<ResumeKey>()
    private val seenResumeOrder = ArrayDeque<ResumeKey>()

    private data class ResumeKey(val packageName: String, val changedAtWallMs: Long)

    data class Transition(
        val packageName: String,
        val changedAtWallMs: Long
    )

    data class Observation(
        val packageName: String?,
        val changedAtWallMs: Long,
        val hasUsageAccess: Boolean,
        val reliable: Boolean,
        val transitions: List<Transition>,
        val observedThroughWallMs: Long
    )

    fun observe(context: Context): Observation {
        if (!UsageStatsRepository.hasUsageAccess(context)) {
            reset()
            return Observation(
                null,
                0L,
                hasUsageAccess = false,
                reliable = true,
                transitions = emptyList(),
                observedThroughWallMs = System.currentTimeMillis()
            )
        }

        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return Observation(
                currentPackage,
                changedAtWallMs,
                hasUsageAccess = true,
                reliable = false,
                transitions = emptyList(),
                observedThroughWallMs = 0L
            )
        val end = System.currentTimeMillis()
        if (queryCursorWallMs > end) reset()
        val initialQuery = queryCursorWallMs == 0L
        val start = if (!initialQuery) {
            incrementalQueryStart(queryCursorWallMs)
        } else {
            (end - INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
        }
        val events = runCatching { manager.queryEvents(start, end) }.getOrNull()
            ?: return Observation(
                currentPackage,
                changedAtWallMs,
                hasUsageAccess = true,
                reliable = false,
                transitions = emptyList(),
                observedThroughWallMs = 0L
            )
        pruneSeenResumeKeys(end)
        val event = UsageEvents.Event()
        val transitions = ArrayList<Transition>()
        var initialLatest: Transition? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue

            val transition = Transition(event.packageName, event.timeStamp)
            val key = ResumeKey(event.packageName, event.timeStamp)
            if (initialQuery) {
                rememberResumeKey(key, end)
                initialLatest = transition
                if (mayReplaceCurrent(event.timeStamp, changedAtWallMs)) {
                    currentPackage = event.packageName
                    changedAtWallMs = event.timeStamp
                }
            } else if (rememberResumeKey(key, end)) {
                // Late events are still replayed, but an older event cannot replace current state.
                transitions.add(transition)
                if (mayReplaceCurrent(event.timeStamp, changedAtWallMs)) {
                    currentPackage = event.packageName
                    changedAtWallMs = event.timeStamp
                }
            }
        }
        if (initialQuery && initialLatest != null) transitions.add(initialLatest)

        // Some OEMs delay or omit UsageEvents while still updating UsageStats.lastTimeUsed.
        if (transitions.isEmpty() &&
            (initialQuery || end - lastFallbackQueryWallMs >= FALLBACK_QUERY_INTERVAL_MS)
        ) {
            lastFallbackQueryWallMs = end
            val fallback = runCatching {
                manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    (end - FALLBACK_FRESHNESS_MS).coerceAtLeast(0L),
                    end
                ).asSequence()
                    .filter {
                        it.lastTimeUsed > changedAtWallMs &&
                            it.lastTimeUsed in (end - FALLBACK_FRESHNESS_MS)..end
                    }
                    .sortedByDescending { it.lastTimeUsed }
                    .firstOrNull {
                        runCatching {
                            context.packageManager.getLaunchIntentForPackage(it.packageName)
                        }.getOrNull() != null
                    }
            }.getOrNull()
            if (fallback != null && fallback.packageName != currentPackage) {
                currentPackage = fallback.packageName
                changedAtWallMs = fallback.lastTimeUsed
                transitions.add(Transition(fallback.packageName, fallback.lastTimeUsed))
                rememberResumeKey(
                    ResumeKey(fallback.packageName, fallback.lastTimeUsed),
                    end
                )
            }
        }

        queryCursorWallMs = end
        return Observation(
            currentPackage,
            changedAtWallMs,
            hasUsageAccess = true,
            reliable = currentPackage != null,
            transitions = transitions,
            observedThroughWallMs = end
        )
    }

    private fun rememberResumeKey(key: ResumeKey, observedThroughWallMs: Long): Boolean {
        if (key.changedAtWallMs < observedThroughWallMs - SEEN_EVENT_RETENTION_MS) return false
        if (!seenResumeKeys.add(key)) return false
        seenResumeOrder.addLast(key)
        return true
    }

    private fun pruneSeenResumeKeys(nowWallMs: Long) {
        val cutoff = nowWallMs - SEEN_EVENT_RETENTION_MS
        while (seenResumeOrder.firstOrNull()?.changedAtWallMs?.let { it < cutoff } == true) {
            seenResumeKeys.remove(seenResumeOrder.removeFirst())
        }
    }

    private fun reset() {
        queryCursorWallMs = 0L
        lastFallbackQueryWallMs = 0L
        currentPackage = null
        changedAtWallMs = 0L
        seenResumeKeys.clear()
        seenResumeOrder.clear()
    }
}
