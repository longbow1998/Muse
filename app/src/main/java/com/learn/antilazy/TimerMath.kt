package com.learn.antilazy

/** Pure timer math kept separate so boundary behavior can be unit tested. */
object TimerMath {

    const val LOCK_RESET_MS = 60_000L

    data class AdvanceResult(
        val elapsedMs: Long,
        val due: Boolean
    )

    fun advance(elapsedMs: Long, intervalMs: Long, deltaMs: Long): AdvanceResult {
        require(intervalMs > 0)
        val safeElapsed = elapsedMs.coerceAtLeast(0L)
        val safeDelta = deltaMs.coerceAtLeast(0L)
        val total = if (Long.MAX_VALUE - safeElapsed < safeDelta) {
            Long.MAX_VALUE
        } else {
            safeElapsed + safeDelta
        }
        return if (total >= intervalMs) {
            AdvanceResult(total % intervalMs, due = true)
        } else {
            AdvanceResult(total, due = false)
        }
    }

    fun shouldResetAfterLock(lockDurationMs: Long): Boolean =
        lockDurationMs > LOCK_RESET_MS

    fun elapsedAfterPause(elapsedMs: Long, pauseDurationMs: Long): Long =
        if (shouldResetAfterLock(pauseDurationMs)) 0L else elapsedMs.coerceAtLeast(0L)

    /** Lock and whitelist transitions share one uninterrupted pause start. */
    fun pauseStartedAt(currentPausedAtMs: Long, transitionAtMs: Long): Long =
        if (currentPausedAtMs in 1..transitionAtMs) currentPausedAtMs else transitionAtMs

    fun isUncertainGap(gapMs: Long): Boolean = gapMs > LOCK_RESET_MS

    fun isSameBoot(
        savedBootCount: Int,
        currentBootCount: Int,
        checkpointElapsed: Long,
        nowElapsed: Long
    ): Boolean = currentBootCount >= 0 &&
        savedBootCount == currentBootCount &&
        checkpointElapsed > 0L &&
        checkpointElapsed <= nowElapsed
}
