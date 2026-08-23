package com.learn.antilazy

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Incrementally tracks the last explicitly resumed package. Call from one worker thread. */
class ForegroundAppTracker {

    companion object {
        private const val INITIAL_LOOKBACK_MS = 24 * 60 * 60_000L
    }

    private var queryCursorWallMs = 0L
    private var currentPackage: String? = null
    private var changedAtWallMs = 0L

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
            queryCursorWallMs = 0L
            currentPackage = null
            changedAtWallMs = 0L
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
        val initialQuery = queryCursorWallMs == 0L || queryCursorWallMs > end
        val start = if (queryCursorWallMs in 1..end) {
            queryCursorWallMs
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
        val event = UsageEvents.Event()
        val transitions = ArrayList<Transition>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                currentPackage = event.packageName
                changedAtWallMs = event.timeStamp
                transitions.add(Transition(event.packageName, event.timeStamp))
            }
        }
        queryCursorWallMs = end
        val newTransitions = if (initialQuery) transitions.takeLast(1) else transitions
        return Observation(
            currentPackage,
            changedAtWallMs,
            hasUsageAccess = true,
            reliable = currentPackage != null,
            transitions = newTransitions,
            observedThroughWallMs = end
        )
    }
}
