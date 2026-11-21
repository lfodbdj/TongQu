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
     * Queries the foreground package AND activity class within a short (15s)
     * window. V2.2: the class name enables per-PAGE exemption semantics
     * (requirement 2026-11-10: WeChat exemption covers only CHAT/contact
     * pages — Channels/official-accounts/mini-programs still trigger quizzes).
     */
    @Suppress("DEPRECATION")
    fun queryForeground(ctx: Context, windowMs: Long = 15_000L): Pair<String, String>? {
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
            var bestTime = Long.MIN_VALUE
            var best: Pair<String, String>? = null
            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == resumedType) {
                    val pkg = event.packageName ?: continue
                    if (pkg.isBlank()) continue
                    if (event.timeStamp >= bestTime) {
                        bestTime = event.timeStamp
                        best = pkg to (event.className ?: "")
                    }
                }
            }
            if (best != null) return best

            // No resume events in the window: fall back to the most recently
            // used package reported by the daily aggregate (no class info).
            val stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, begin, now
            )
            stats?.maxByOrNull { it.lastTimeUsed }
                ?.packageName
                ?.takeIf { it.isNotBlank() }
                ?.let { it to "" }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground query failed; quizzes stay enabled (fail-closed)", e)
            null
        }
    }

    /** Backwards-compatible package-only wrapper. */
    fun queryForegroundPackage(ctx: Context, windowMs: Long = 15_000L): String? =
        queryForeground(ctx, windowMs)?.first

    /**
     * Pure-JVM picker (no Android dependency) used by the characterization
     * tests: given a list of (timestamp, packageName) resume events, returns the
     * package with the largest timestamp. Blank packages are ignored (fail-
     * closed), and on equal timestamps the LAST occurrence wins. Returns null
     * when there is no valid (non-blank) event. Mirrors the resume-event
     * selection inside [queryForeground].
     */
    fun pickForegroundPackage(events: List<Pair<Long, String>>): String? {
        var bestTime = Long.MIN_VALUE
        var best: String? = null
        for ((ts, pkg) in events) {
            if (pkg.isBlank()) continue
            if (ts >= bestTime) {
                bestTime = ts
                best = pkg
            }
        }
        return best
    }

    // ------------------------------------------------------------------
    // V2.2 (requirement 2026-11-10): WeChat exemption is per-PAGE — only
    // chat/contact surfaces are exempt; Channels (视频号), official accounts
    // (公众号), mini-programs/games (小程序/游戏) and Moments (朋友圈) still
    // trigger quizzes. Implemented via the resumed Activity class name; when
    // class info is missing (daily-aggregate fallback) the whole-package
    // exemption holds.
    // ------------------------------------------------------------------

    /** WeChat activity-class fragments that count as CHAT/contact pages. */
    private val wechatExemptPageFragments = listOf(
        "LauncherUI",     // 聊天列表/主界面
        "ChatUI",         // 聊天会话页
        "ContactUI",      // 通讯录列表
        "ContactInfoUI",  // 联系人资料
        "ContactsUI"
    )

    /** True when [className] is a WeChat chat/contact page. */
    fun isWeChatChatPage(className: String): Boolean {
        if (className.isBlank()) return false
        return wechatExemptPageFragments.any { className.contains(it, ignoreCase = true) }
    }

    /**
     * True ONLY when a verified foreground app is exempt. V2.2: for WeChat
     * the exemption applies to chat/contact PAGES only — Channels, official
     * accounts and mini-programs are NOT exempt even with WeChat whitelisted.
     *
     * V2.2 fix (requirement 2026-11-10: 视频号停留久了不弹): the event window
     * covers the WHOLE quiz interval — a child sitting inside 视频号 for 20
     * minutes produces no NEW resume events in a 15s window, so the page
     * must be resolved from the last RESUMED event since the interval began.
     * When even that yields no class (child stayed past the whole interval),
     * WeChat FAILS CLOSED (quiz fires) while other whitelisted apps keep the
     * whole-package exemption.
     */
    fun isExemptActive(ctx: Context, exempt: Set<String>): Boolean {
        return try {
            if (exempt.isEmpty() || !AppConfig.isExemptSetValid(exempt)) {
                Log.w(TAG, "Exempt set empty/oversized; quizzes stay enabled (fail-closed)")
                return false
            }
            // Window covers the full interval (+1 min margin) so the last page
            // navigation is always inside it even for long single-page stays.
            val windowMs = AppConfig.getQuizIntervalMs(ctx) + 60_000L
            val fg = queryForeground(ctx, windowMs)
            if (fg == null) {
                Log.w(TAG, "No foreground data; quizzes stay enabled (fail-closed)")
                return false
            }
            val (pkg, className) = fg
            val active = if (pkg in exempt) {
                if (pkg == Constants.WECHAT_PACKAGE) {
                    // Only chat/contact pages are exempt. Blank class (child
                    // stayed past the whole interval, no recent event) →
                    // FAIL-CLOSED: quiz fires.
                    val chatPage = isWeChatChatPage(className)
                    Log.d(TAG, "WeChat whitelisted: class='$className' chatPage=$chatPage")
                    chatPage
                } else {
                    true
                }
            } else {
                false
            }
            Log.d(TAG, "Foreground exempt active=$active")
            active
        } catch (e: Exception) {
            Log.w(TAG, "Exempt check failed; quizzes stay enabled (fail-closed)", e)
            false
        }
    }
}
