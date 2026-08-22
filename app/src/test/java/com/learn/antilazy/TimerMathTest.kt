package com.learn.antilazy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerMathTest {

    @Test
    fun belowIntervalAccumulatesWithoutReminder() {
        val result = TimerMath.advance(10_000, 60_000, 20_000)
        assertEquals(30_000, result.elapsedMs)
        assertFalse(result.due)
    }

    @Test
    fun reachingIntervalTriggersAndResets() {
        val result = TimerMath.advance(59_000, 60_000, 1_000)
        assertEquals(0, result.elapsedMs)
        assertTrue(result.due)
    }

    @Test
    fun delayedTickPreservesRemainder() {
        val result = TimerMath.advance(55_000, 60_000, 12_000)
        assertEquals(7_000, result.elapsedMs)
        assertTrue(result.due)
    }

    @Test
    fun lockResetIsStrictlyLongerThanOneMinute() {
        assertFalse(TimerMath.shouldResetAfterLock(60_000))
        assertTrue(TimerMath.shouldResetAfterLock(60_001))
    }

    @Test
    fun unlockAfterShortLockKeepsProgressAndResumes() {
        val elapsedBeforeLock = 45_000L
        val elapsedAfterUnlock = TimerMath.elapsedAfterUnlock(elapsedBeforeLock, 60_000)

        val afterFirstUnlockedSecond = TimerMath.advance(elapsedAfterUnlock, 60_000, 1_000)

        assertEquals(46_000, afterFirstUnlockedSecond.elapsedMs)
        assertFalse(afterFirstUnlockedSecond.due)
    }

    @Test
    fun unlockAfterLongLockResetsThenResumesFromZero() {
        val elapsedAfterUnlock = TimerMath.elapsedAfterUnlock(45_000, 60_001)

        val afterFirstUnlockedSecond = TimerMath.advance(elapsedAfterUnlock, 60_000, 1_000)

        assertEquals(1_000, afterFirstUnlockedSecond.elapsedMs)
        assertFalse(afterFirstUnlockedSecond.due)
    }

    @Test
    fun delayedUnknownGapIsDetectedWithoutCallingItALock() {
        assertFalse(TimerMath.isUncertainGap(60_000))
        assertTrue(TimerMath.isUncertainGap(60_001))
    }

    @Test
    fun bootIdentityMustMatchBeforeRestoringProgress() {
        assertTrue(TimerMath.isSameBoot(10, 10, 50_000, 60_000))
        assertFalse(TimerMath.isSameBoot(9, 10, 50_000, 60_000))
        assertFalse(TimerMath.isSameBoot(-1, -1, 50_000, 60_000))
        assertFalse(TimerMath.isSameBoot(10, 10, 70_000, 60_000))
    }
}
