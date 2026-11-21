package com.antidip

import android.content.Context
import android.content.Intent
import com.antidip.UrlValidator

/**
 * Single source of truth for the parent-console configuration (2-c, C1).
 *
 * UI and service MUST go through this object instead of touching the raw
 * SharedPreferences keys, so clamping / normalisation can never be bypassed.
 * The three pure-JVM helpers below contain no Android types and are directly
 * unit-testable; the Context accessors are thin wrappers around them.
 */
object AppConfig {

    /** Clamp a raw minute value into the supported 1..60 range. */
    fun clampIntervalMinutes(raw: Int): Int =
        raw.coerceIn(Constants.MIN_INTERVAL_MINUTES, Constants.MAX_INTERVAL_MINUTES)

    /** Convert (clamped) minutes to milliseconds. */
    fun intervalMinutesToMs(min: Int): Long = clampIntervalMinutes(min) * 60_000L

    /** An exempt-app set is valid only up to the configured cap. */
    fun isExemptSetValid(set: Set<String>): Boolean =
        set.size <= Constants.MAX_EXEMPT_APPS

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    /** Reads the configured interval, clamping any stale/out-of-range value. */
    fun getQuizIntervalMinutes(ctx: Context): Int = clampIntervalMinutes(
        prefs(ctx).getInt(
            Constants.KEY_QUIZ_INTERVAL_MINUTES,
            Constants.DEFAULT_INTERVAL_MINUTES
        )
    )

    /** Effective quiz interval in milliseconds for the timing state machine. */
    fun getQuizIntervalMs(ctx: Context): Long =
        intervalMinutesToMs(getQuizIntervalMinutes(ctx))

    /**
     * Persists the clamped interval and notifies the running service.
     *
     * An explicit (package-scoped) broadcast is required: sendBroadcast never
     * reaches onStartCommand, and startService would wrongly START the service
     * when it is not running. ScreenMonitorService receives this via its
     * dynamically registered, non-exported receiver (C3).
     */
    fun setQuizIntervalMinutes(ctx: Context, min: Int) {
        prefs(ctx).edit()
            .putInt(Constants.KEY_QUIZ_INTERVAL_MINUTES, clampIntervalMinutes(min))
            .apply()
        ctx.sendBroadcast(
            Intent(Constants.ACTION_CONFIG_CHANGED).setPackage(ctx.packageName)
        )
    }

    /** Only [Constants.ALL_GRADES] or GRADE_MIN..GRADE_MAX are legal. */
    private fun normalizeGrade(raw: Int): Int =
        if (raw == Constants.ALL_GRADES ||
            raw in Constants.GRADE_MIN..Constants.GRADE_MAX
        ) {
            raw
        } else {
            Constants.ALL_GRADES
        }

    fun getSelectedGrade(ctx: Context): Int = normalizeGrade(
        prefs(ctx).getInt(Constants.KEY_SELECTED_GRADE, Constants.ALL_GRADES)
    )

    fun setSelectedGrade(ctx: Context, grade: Int) {
        prefs(ctx).edit()
            .putInt(Constants.KEY_SELECTED_GRADE, normalizeGrade(grade))
            .apply()
    }

    /**
     * Returns a NEW HashSet copy, never the live mutable set held by
     * SharedPreferences. Blank entries are dropped and the result is capped;
     * an over-limit set degrades to empty (i.e. quizzes stay enabled).
     */
    fun getExemptPackages(ctx: Context): Set<String> {
        val stored =
            prefs(ctx).getStringSet(Constants.KEY_EXEMPT_PACKAGES, emptySet()) ?: emptySet()
        val copy = HashSet<String>(stored)
        val cleaned = copy
            .filter { it.isNotBlank() }
            .take(Constants.MAX_EXEMPT_APPS)
            .toHashSet()
        return if (isExemptSetValid(cleaned)) cleaned else emptySet()
    }

    /**
     * Persists a NEW HashSet copy. Blank entries are dropped and the set is
     * truncated to [Constants.MAX_EXEMPT_APPS] before it ever reaches prefs.
     */
    fun setExemptPackages(ctx: Context, pkgs: Set<String>) {
        val copy = pkgs
            .filter { it.isNotBlank() }
            .take(Constants.MAX_EXEMPT_APPS)
            .toHashSet()
        prefs(ctx).edit()
            .putStringSet(Constants.KEY_EXEMPT_PACKAGES, copy)
            .apply()
    }
}
