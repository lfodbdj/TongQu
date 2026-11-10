package com.antidip

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * Detects the current foreground package for the 2-c "exempt apps" feature.
 *
 * FAIL-CLOSED is a hard requirement: missing usage-stats permission, missing
 * data, or any exception must yield "not exempt" so the quiz still fires.
 * Otherwise a child could simply refuse the usage-access grant and disable
 * anti-addiction entirely.
 */
object ForegroundAppDetector {

    private const val TAG = "ForegroundAppDetector"

    /** True only when AppOps reports GET_USAGE_STATS as allowed. */
    fun hasUsageStatsPermission(ctx: Context): Boolean {
        return try {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.w(TAG, "Usage-stats permission check failed; assuming not granted", e)
            false
        }
    }

    /**
     * Queries the foreground package within a short (15s) window.
     *
     * A 60s window would classify an app that just left the foreground as
     * still active. Events are read once, off the timing hot path (only at the
     * moment the interval elapses); there is no polling in CountDownTimer.onTick.
     * Falls back to queryUsageStats/lastTimeUsed when queryEvents has no data.
     */
    @Suppress("DEPRECATION")
    fun queryForegroundPackage(ctx: Context, windowMs: Long = 15_000L): String? {
        if (!hasUsageStatsPermission(ctx)) {
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted; quizzes stay enabled (fail-closed)")
            return null
        }
        return try {
            val now = System.currentTimeMillis()
            val begin = now - windowMs
            val manager = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return null

            // ACTIVITY_RESUMED is API 29+; older platforms report the
            // deprecated MOVE_TO_FOREGROUND transition instead.
            val resumedType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    UsageEvents.Event.ACTIVITY_RESUMED
                } else {
                    UsageEvents.Event.MOVE_TO_FOREGROUND
                }

            val usageEvents = manager.queryEvents(begin, now)
            val collected = mutableListOf<Pair<Long, String>>()
            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == resumedType) {
                    collected.add(event.timeStamp to (event.packageName ?: ""))
                }
            }

            val fromEvents = pickForegroundPackage(collected)
            if (fromEvents != null) {
                return fromEvents
            }

            // No resume events in the window: fall back to the most recently
            // used package reported by the daily aggregate.
            val stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, begin, now
            )
            stats?.maxByOrNull { it.lastTimeUsed }
                ?.packageName
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground query failed; quizzes stay enabled (fail-closed)", e)
            null
        }
    }

    /**
     * Pure-JVM, zero-Android helper: returns the package with the LARGEST
     * timestamp. Ties resolve to the LAST occurrence in the list (the most
     * recent resume wins). Blank packages are ignored; an empty/all-blank
     * list returns null.
     */
    fun pickForegroundPackage(events: List<Pair<Long, String>>): String? {
        var bestTime = Long.MIN_VALUE
        var bestPackage: String? = null
        for ((timestamp, pkg) in events) {
            if (pkg.isBlank()) continue
            if (timestamp >= bestTime) {
                bestTime = timestamp
                bestPackage = pkg
            }
        }
        return bestPackage
    }

    /**
     * True ONLY when a verified foreground package is in the exempt set.
     * Every failure mode returns false (quiz fires normally) and is logged.
     */
    fun isExemptActive(ctx: Context, exempt: Set<String>): Boolean {
        return try {
            if (exempt.isEmpty() || !AppConfig.isExemptSetValid(exempt)) {
                Log.w(TAG, "Exempt set empty/oversized; quizzes stay enabled (fail-closed)")
                return false
            }
            val foreground = queryForegroundPackage(ctx)
            if (foreground == null) {
                Log.w(TAG, "No foreground data; quizzes stay enabled (fail-closed)")
                return false
            }
            val active = foreground in exempt
            Log.d(TAG, "Foreground exempt active=$active")
            active
        } catch (e: Exception) {
            Log.w(TAG, "Exempt check failed; quizzes stay enabled (fail-closed)", e)
            false
        }
    }
}
