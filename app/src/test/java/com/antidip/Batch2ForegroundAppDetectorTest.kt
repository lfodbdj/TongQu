package com.antidip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM characterization tests for the 2-c foreground-event picker.
 *
 * ForegroundAppDetector is fail-closed on Android; the only JVM-safe part is
 * pickForegroundPackage(), which decides which resume event wins.
 */
class Batch2ForegroundAppDetectorTest {

    @Test
    fun emptyList_returnsNull() {
        assertNull(ForegroundAppDetector.pickForegroundPackage(emptyList()))
    }

    @Test
    fun singleEvent_returnsItsPackage() {
        assertEquals(
            "com.a",
            ForegroundAppDetector.pickForegroundPackage(listOf(100L to "com.a"))
        )
    }

    @Test
    fun outOfOrderEvents_returnsLargestTimestamp() {
        assertEquals(
            "com.b",
            ForegroundAppDetector.pickForegroundPackage(
                listOf(100L to "com.a", 300L to "com.b", 200L to "com.c")
            )
        )
    }

    @Test
    fun tiedTimestamps_lastOccurrenceWins() {
        assertEquals(
            "com.c",
            ForegroundAppDetector.pickForegroundPackage(
                listOf(100L to "com.a", 100L to "com.b", 100L to "com.c")
            )
        )
    }

    @Test
    fun blankPackages_areIgnored() {
        assertEquals(
            "com.a",
            ForegroundAppDetector.pickForegroundPackage(
                listOf(300L to "  ", 200L to "com.a")
            )
        )
        assertEquals(
            "com.x",
            ForegroundAppDetector.pickForegroundPackage(
                listOf(999L to "", 500L to "com.x")
            )
        )
    }

    @Test
    fun allBlankPackages_returnsNull() {
        assertNull(
            ForegroundAppDetector.pickForegroundPackage(
                listOf(10L to "", 20L to "   ")
            )
        )
    }
}
