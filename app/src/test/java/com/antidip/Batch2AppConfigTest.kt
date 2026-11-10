package com.antidip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM characterization tests for the 2-c configuration helpers.
 *
 * Only the three Android-free AppConfig functions are exercised; no Context
 * is instantiated so these run on a plain JVM without Robolectric.
 */
class Batch2AppConfigTest {

    @Test
    fun clamp_belowRange_isClampedToMin() {
        assertEquals(1, AppConfig.clampIntervalMinutes(0))
        assertEquals(1, AppConfig.clampIntervalMinutes(-5))
        assertEquals(1, AppConfig.clampIntervalMinutes(Int.MIN_VALUE))
    }

    @Test
    fun clamp_insideRange_isUnchanged() {
        assertEquals(1, AppConfig.clampIntervalMinutes(1))
        assertEquals(10, AppConfig.clampIntervalMinutes(10))
        assertEquals(37, AppConfig.clampIntervalMinutes(37))
        assertEquals(60, AppConfig.clampIntervalMinutes(60))
    }

    @Test
    fun clamp_aboveRange_isClampedToMax() {
        assertEquals(60, AppConfig.clampIntervalMinutes(61))
        assertEquals(60, AppConfig.clampIntervalMinutes(999))
        assertEquals(60, AppConfig.clampIntervalMinutes(Int.MAX_VALUE))
    }

    @Test
    fun intervalMinutesToMs_matchesLegacyConstant() {
        assertEquals(
            Constants.QUIZ_INTERVAL_MS,
            AppConfig.intervalMinutesToMs(Constants.DEFAULT_INTERVAL_MINUTES)
        )
        assertEquals(10L * 60L * 1000L, AppConfig.intervalMinutesToMs(10))
    }

    @Test
    fun intervalMinutesToMs_boundariesAreClampedFirst() {
        assertEquals(60_000L, AppConfig.intervalMinutesToMs(1))
        assertEquals(60_000L, AppConfig.intervalMinutesToMs(0))
        assertEquals(3_600_000L, AppConfig.intervalMinutesToMs(60))
        assertEquals(3_600_000L, AppConfig.intervalMinutesToMs(61))
    }

    @Test
    fun isExemptSetValid_respectsCap() {
        assertTrue(AppConfig.isExemptSetValid(emptySet()))
        assertTrue(AppConfig.isExemptSetValid((0 until 10).map { "com.example.a$it" }.toSet()))
        assertFalse(AppConfig.isExemptSetValid((0 until 11).map { "com.example.a$it" }.toSet()))
    }

    @Test
    fun constants_matchDesignDecisions() {
        assertEquals(10, Constants.DEFAULT_INTERVAL_MINUTES)
        assertEquals(1, Constants.MIN_INTERVAL_MINUTES)
        assertEquals(60, Constants.MAX_INTERVAL_MINUTES)
        assertEquals(-1, Constants.ALL_GRADES)
        assertEquals(1, Constants.GRADE_MIN)
        assertEquals(6, Constants.GRADE_MAX)
        assertEquals(10, Constants.MAX_EXEMPT_APPS)
    }
}
