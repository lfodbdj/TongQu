package com.antidip

/**
 * Centralized constants shared across all modules of the AntiAddiction app.
 *
 * Keeping every cross-module literal (SharedPreferences names/keys, Intent
 * actions and timing/rule values) in a single source eliminates duplicate
 * string/number hardcoding and makes refactoring safe (task B4).
 */
object Constants {
    // ------------------------------------------------------------------
    // SharedPreferences
    // ------------------------------------------------------------------
    const val PREFS_NAME = "anti_addiction_prefs"
    const val KEY_SERVICE_PAUSED = "service_paused"
    const val KEY_ACCUMULATED_SCREEN_TIME = "accumulated_screen_time"

    /**
     * Wall-clock (System.currentTimeMillis) start of the still-open screen-on
     * window, persisted so a force-stop that never calls onDestroy() cannot
     * lose that in-flight interval (M1). 0 means no window is open.
     */
    const val KEY_SCREEN_ON_START_MS = "screen_on_start_ms"

    /**
     * Start point (SystemClock.elapsedRealtime) of a parent temporary pause.
     * Only ScreenMonitorService is allowed to write this value (batch 2-a).
     */
    const val KEY_PARENT_PAUSE_AT_ELAPSED = "parent_pause_at_elapsed"

    /** True while the service is waiting for a quiz result. */
    const val KEY_QUIZ_IN_PROGRESS = "quiz_in_progress"

    /**
     * Fingerprints of questions already mastered and removed (B14 x M3). A
     * physical DELETE removes the row, so the Room unique (content, grade)
     * index can no longer de-duplicate it; persisting a stable fingerprint
     * stops the idempotent M3 re-import from resurrecting it on every cold
     * start. Stored as a StringSet of "<grade>|<content hashCode>".
     */
    const val KEY_MASTERED_FINGERPRINTS = "mastered_fingerprints"

    // Parent-console configuration (batch 2-c). All reads/writes go through
    // AppConfig so clamping and normalisation cannot be bypassed by the UI.
    const val KEY_QUIZ_INTERVAL_MINUTES = "quiz_interval_minutes"
    const val KEY_SELECTED_GRADE = "selected_grade"
    const val KEY_EXEMPT_PACKAGES = "exempt_packages"

    /** V2.2: child's display nickname (growth banner + backup file name). */
    const val KEY_USER_NICKNAME = "user_nickname"

    // ---- 积分扣减（物品兑换/手动扣减）：独立扣减密码，仅存 SHA-256 哈希。
    // 修改授权=旧扣减密码 或 家长全局密码（DeductionAuth，防遗忘）。
    const val KEY_DEDUCTION_HASH = "deduction_password_hash"

    // ---- AI 判题（填空语义判分）：端点/Key/模型由家长在设置页提供
    // （OpenAI 兼容 chat/completions），开关控制启停；fail-closed。
    const val KEY_AI_ENABLED = "ai_enabled"
    const val KEY_AI_ENDPOINT = "ai_endpoint"
    const val KEY_AI_KEY = "ai_key"
    const val KEY_AI_MODEL = "ai_model"

    /**
     * 每题 3 次机会中的第 2/3 次作答间隔（用户要求 2026-11-21）：上一答错后
     * 锁定 30 秒；重试答对不计积分/连对（见 UI 层 lastSubmitAttemptsBefore）。
     */
    const val RETRY_INTERVAL_MS = 30_000L

    /**
     * WeChat package. V2.2 semantics (requirement 2026-11-10): the parent
     * whitelist covers WeChat CHAT/contact PAGES only — Channels (视频号),
     * official accounts (公众号), mini-programs/games and Moments still
     * trigger quizzes (page-level check in ForegroundAppDetector via the
     * resumed Activity class name).
     */
    const val WECHAT_PACKAGE = "com.tencent.mm"

    // ------------------------------------------------------------------
    // Intent actions
    // ------------------------------------------------------------------
    // ACTION_TRIGGER_QUIZ was removed in batch 2-a: a global search confirmed
    // zero references outside this file. The quiz is now launched directly by
    // the service's timing state machine.
    const val ACTION_PAUSE = "com.antidip.ACTION_PAUSE"

    /** Kept for the settings page "start service" button (fixes S1). */
    const val ACTION_RESUME = "com.antidip.ACTION_RESUME"

    /**
     * V2.2: watchdog keepalive action. BootReceiver schedules 3 alarms
     * (+2/+10/+30 min) after every boot; each firing restarts the service if
     * it died (EMUI 自启动管理 sometimes swallows the first BOOT start).
     */
    const val ACTION_SERVICE_KEEPALIVE = "com.antidip.ACTION_SERVICE_KEEPALIVE"

    /** QuizActivity -> ScreenMonitorService outcome report. */
    const val ACTION_QUIZ_RESULT = "com.antidip.ACTION_QUIZ_RESULT"
    const val EXTRA_QUIZ_OUTCOME = "com.antidip.EXTRA_QUIZ_OUTCOME"

    /** AppConfig -> ScreenMonitorService: re-read the interval without resetting the window. */
    const val ACTION_CONFIG_CHANGED = "com.antidip.ACTION_CONFIG_CHANGED"

    // ------------------------------------------------------------------
    // Quiz outcomes (payload of EXTRA_QUIZ_OUTCOME)
    // ------------------------------------------------------------------
    const val OUTCOME_CORRECT = "correct"
    const val OUTCOME_CONTINUE = "continue"
    const val OUTCOME_PARENT_EXIT = "parent_exit"
    const val OUTCOME_DISMISSED_ABNORMAL = "dismissed_abnormal"

    // ------------------------------------------------------------------
    // Timing / rules
    // ------------------------------------------------------------------
    const val QUIZ_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes (default; kept for Batch1 tests)

    // Batch 2-c: parent-configurable quiz interval (inclusive 1..60 minutes).
    // DEFAULT must stay consistent with QUIZ_INTERVAL_MS above.
    const val DEFAULT_INTERVAL_MINUTES = 10
    const val MIN_INTERVAL_MINUTES = 1
    const val MAX_INTERVAL_MINUTES = 60

    // Grade selection: ALL_GRADES means "no grade filter".
    const val ALL_GRADES = -1
    const val GRADE_MIN = 1
    const val GRADE_MAX = 6

    /** Cap on the number of apps that may be exempted from quizzes. */
    const val MAX_EXEMPT_APPS = 10

    /**
     * A parent "exit then pause" is temporary (2h) and auto-expires at the
     * next boot, matching README's "下次开机自动恢复" (A01/A15).
     */
    const val PARENT_PAUSE_DURATION_MS = 2 * 60 * 60 * 1000L
    const val QUESTION_2_DELAY_SECONDS = 30
    const val QUESTION_3_DELAY_SECONDS = 60
    const val LOCK_DURATION_SECONDS = 10 * 60 // 10 minutes
    const val MAX_ATTEMPTS = 3
    const val MAX_QUESTIONS_PER_ROUND = 3
    const val MAX_CONSECUTIVE_FAILURES = 5

    /**
     * Lower bound for the question bank (A04). A mastered question is deleted
     * only while the bank holds MORE than this many rows; otherwise its
     * consecutive-correct counter is reset instead, so repeated correct answers
     * can never empty the bank and let the child through without a quiz.
     */
    const val MIN_QUESTION_BANK_SIZE = 100

    // ------------------------------------------------------------------
    // Network / download
    // ------------------------------------------------------------------
    // Example LAN address only (NOT a real server). Set the real address in
    // Settings -> Quiz server, or edit this constant for your own build.
    const val DEFAULT_QUIZ_URL = "http://192.168.1.100:8080/questions.json"
    const val KEY_QUIZ_DOWNLOAD_URL = "quiz_download_url"
}
