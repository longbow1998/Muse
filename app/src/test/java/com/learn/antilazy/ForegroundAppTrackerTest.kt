package com.learn.antilazy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAppTrackerTest {

    @Test
    fun incrementalQueryReReadsOneMinuteForLateOemEvents() {
        assertEquals(40_000L, ForegroundAppTracker.incrementalQueryStart(100_000L))
        assertEquals(0L, ForegroundAppTracker.incrementalQueryStart(30_000L))
    }

    @Test
    fun lateOlderTransitionCannotReplaceNewerCurrentState() {
        assertTrue(ForegroundAppTracker.mayReplaceCurrent(100_001L, 100_000L))
        assertTrue(ForegroundAppTracker.mayReplaceCurrent(100_000L, 100_000L))
        assertFalse(ForegroundAppTracker.mayReplaceCurrent(99_999L, 100_000L))
    }
}
