package com.antidip

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class ScreenMonitorService : Service() {

    /**
     * Batch 2-a timing state machine. The service owns this state and is the
     * only component allowed to restart the quiz timer; QuizActivity merely
     * reports outcomes via [Constants.ACTION_QUIZ_RESULT].
     */
    private enum class TimingState { IDLE, TIMING, WAITING_QUIZ_RESULT, PAUSED }

    private var screenOnReceiver: BroadcastReceiver? = null
    private var quizResultReceiver: BroadcastReceiver? = null
    private var accumulatedScreenTimeMs: Long = 0
    private var screenOnStartMs: Long = 0
    private var isScreenOn: Boolean = false
    private var quizTimer: CountDownTimer? = null
    private var isPaused: Boolean = false
    private var timingState: TimingState = TimingState.IDLE

    /**
     * Batch 2-a (③): set by onCreate() when a persisted quiz was interrupted
     * by a process kill. The quiz is re-launched from onStartCommand()'s
     * START_STICKY redelivery (null intent), never from onCreate() itself, so
     * foreground-service startup restrictions are respected.
     */
    private var needRelaunchQuiz: Boolean = false

    // Call priority (requirement 2026-11-08): a quiz must never pop up during
    // a call. When a START_STICKY relaunch lands mid-call, the relaunch is
    // deferred with this handler and retried until the call ends.
    private val relaunchRetryHandler = Handler(Looper.getMainLooper())
    private val relaunchRetryRunnable = Runnable { attemptStickyQuizRelaunch() }

    companion object {
        private const val TAG = "ScreenMonitorService"

        /** Retry cadence for a quiz relaunch deferred by an ongoing call. */
        private const val RELAUNCH_RETRY_DELAY_MS = 60_000L

        // Static state for QuizActivity / SettingsActivity to check.
        @Volatile
        var isRunning: Boolean = false
            private set

        /** Read-only snapshot of the in-memory pause flag for the settings UI. */
        @Volatile
        var isPausedSnapshot: Boolean = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        isRunning = true

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        // Batch 1: the settings page "persistent stop" decision and the
        // accumulated screen time survive a START_STICKY restart.
        val persistentlyStopped = prefs.getBoolean(Constants.KEY_SERVICE_PAUSED, false)
        accumulatedScreenTimeMs = prefs.getLong(Constants.KEY_ACCUMULATED_SCREEN_TIME, 0L)
        val quizInProgress = prefs.getBoolean(Constants.KEY_QUIZ_IN_PROGRESS, false)

        // M1: restore the still-open screen-on window. previously it lived
        // only in memory, so a force-stop that never called onDestroy() lost
        // the whole in-flight interval and granted a fresh 10 minutes.
        screenOnStartMs = prefs.getLong(Constants.KEY_SCREEN_ON_START_MS, 0L)
        if (screenOnStartMs > System.currentTimeMillis()) {
            // Wall clock rolled back (or a stale/future value): a future start
            // point would yield a negative elapsed and never settle.
            screenOnStartMs = 0
        }

        // Batch 2-a: a parent temporary pause is stored as a START point in
        // elapsedRealtime, never as an absolute wall-clock expiry.
        // elapsedRealtime resets to zero at boot, so a stale marker (at > nowEl)
        // is intentionally treated as expired -> the service auto-resumes,
        // exactly as the README promises ("下次开机自动恢复").
        val at = prefs.getLong(Constants.KEY_PARENT_PAUSE_AT_ELAPSED, -1L)
        val nowEl = SystemClock.elapsedRealtime()
        val parentPauseActive = at >= 0 && nowEl >= at &&
            (nowEl - at) < Constants.PARENT_PAUSE_DURATION_MS
        if (at >= 0L && !parentPauseActive) {
            prefs.edit().remove(Constants.KEY_PARENT_PAUSE_AT_ELAPSED).apply()
        }

        isPaused = persistentlyStopped || parentPauseActive
        isPausedSnapshot = isPaused
        timingState = when {
            isPaused -> TimingState.PAUSED
            quizInProgress -> TimingState.WAITING_QUIZ_RESULT
            else -> TimingState.IDLE
        }

        // ③: an interrupted quiz must be shown again after a process kill so
        // clearing the app from recents cannot buy a fresh 10-minute window.
        needRelaunchQuiz = quizInProgress

        // Check initial screen state.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = pm.isInteractive

        registerScreenReceiver()
        registerQuizResultReceiver()
        // Batch 3 (A29): declare the foreground-service type explicitly. On
        // API 34+ the specialUse type MUST be passed to startForeground(),
        // otherwise the system raises a MissingForegroundServiceTypeException;
        // on older builds (including the API 29 test device) the type is not
        // part of the startForeground contract, so the plain call stays.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                AntiAddictionApp.QUIZ_NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(AntiAddictionApp.QUIZ_NOTIFICATION_ID, createNotification())
        }

        // A restored WAITING_QUIZ_RESULT or PAUSED state must NOT restart the
        // timer here; only a fresh IDLE state starts timing (spec batch 2-a).
        if (isScreenOn && isTimingAllowed()) {
            startScreenTiming()
        }
        // Normalise all three runtime keys with the state as finally resolved
        // (also drops a rolled-back screenOnStartMs from prefs).
        persistRuntimeState()
        Log.d(
            TAG,
            "Restored state=$timingState paused=$isPaused quizInProgress=$quizInProgress " +
                "screenOnStart=$screenOnStartMs needRelaunchQuiz=$needRelaunchQuiz"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Constants.ACTION_PAUSE -> {
                isPaused = true
                isPausedSnapshot = true
                timingState = TimingState.PAUSED
                pauseTiming()
                markParentPaused()
                persistRuntimeState()
                Log.d(TAG, "Service paused by parent")
            }
            Constants.ACTION_RESUME -> {
                resumeTiming()
            }
            Constants.ACTION_QUIZ_RESULT -> {
                onQuizResult(intent.getStringExtra(Constants.EXTRA_QUIZ_OUTCOME))
            }
            Constants.ACTION_CONFIG_CHANGED -> {
                onConfigChanged()
            }
            null -> {
                if (needRelaunchQuiz && timingState == TimingState.WAITING_QUIZ_RESULT) {
                    // ③: START_STICKY redelivery after the process was killed
                    // while the quiz was on screen. Re-show the quiz and KEEP
                    // WAITING_QUIZ_RESULT: no timer restart, no counter reset.
                    attemptStickyQuizRelaunch()
                }
                // Otherwise (quizInProgress=false) do nothing: normal timing was
                // already started by onCreate()/startScreenTiming(). Never
                // self-heal with OUTCOME_DISMISSED_ABNORMAL here, or clearing
                // the app from recents would grant a brand-new 10-min window.
            }
            else -> {
                Log.d(TAG, "Ignoring unknown action: ${intent.action}")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        // Guard against IllegalArgumentException when the receiver was never
        // registered or has already been unregistered (D20).
        screenOnReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        screenOnReceiver = null
        quizResultReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        quizResultReceiver = null
        quizTimer?.cancel()
        // Call priority: drop any pending call-deferred relaunch.
        relaunchRetryHandler.removeCallbacks(relaunchRetryRunnable)
        // Settle the still-open screen-on window before persisting state, so a
        // destroy while the screen is on does not drop the last elapsed span.
        pauseTiming()
        persistRuntimeState()
        Log.d(TAG, "Service destroyed")
    }

    private fun registerScreenReceiver() {
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d(TAG, "Screen ON")
                        isScreenOn = true
                        if (isTimingAllowed()) {
                            startScreenTiming()
                        }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "Screen OFF")
                        isScreenOn = false
                        // M1: pauseTiming() now persists the settled total and
                        // the cleared window start on every screen-off.
                        pauseTiming()
                        if (timingState == TimingState.TIMING) {
                            timingState = TimingState.IDLE
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenOnReceiver, filter)
    }

    /**
     * QuizActivity sends [Constants.ACTION_QUIZ_RESULT] as an explicit
     * (package-scoped) broadcast. A dynamically registered, non-exported
     * receiver is required because sendBroadcast never reaches onStartCommand;
     * ContextCompat supplies the RECEIVER_NOT_EXPORTED flag required on API 33+.
     */
    private fun registerQuizResultReceiver() {
        quizResultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Constants.ACTION_CONFIG_CHANGED -> onConfigChanged()
                    else -> {
                        val outcome = intent.getStringExtra(Constants.EXTRA_QUIZ_OUTCOME)
                        Log.d(TAG, "Quiz result broadcast received: $outcome")
                        onQuizResult(outcome)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Constants.ACTION_QUIZ_RESULT)
            addAction(Constants.ACTION_CONFIG_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            quizResultReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Timing is allowed only outside a quiz wait and outside a parent pause. */
    private fun isTimingAllowed(): Boolean =
        !isPaused &&
            timingState != TimingState.WAITING_QUIZ_RESULT &&
            timingState != TimingState.PAUSED

    private fun startScreenTiming() {
        if (!isTimingAllowed()) {
            return
        }
        // Single-timestamp accumulation model (B1/M1):
        //   total used = accumulatedScreenTimeMs (settled windows)
        //              + (now - screenOnStartMs) (currently open window)
        // A restored (>0) start point is kept verbatim, so the interval that
        // was in flight before a force-stop is NOT lost and is NOT counted
        // twice. Only a zero start point opens a fresh window.
        val nowMs = System.currentTimeMillis()
        if (screenOnStartMs <= 0L) {
            screenOnStartMs = nowMs
        }
        timingState = TimingState.TIMING

        // M1: persist the start point immediately. This is the ONE new persist
        // call in the timing path — deliberately NOT in CountDownTimer.onTick,
        // which would just rewrite a constant value every 30 seconds.
        persistRuntimeState()

        quizTimer?.cancel()
        // Batch 3 (task #12 item 3): one window can never exceed one full
        // interval. If the wall clock jumps forward (manual time set, NTP
        // sync), the raw elapsed would be huge: without the clamp the quiz
        // would fire instantly and the settled window would inflate
        // accumulatedScreenTimeMs permanently. Clamping keeps the rhythm.
        val elapsedInWindow = openWindowElapsedMs(nowMs)
        // 2-c: the parent-configurable interval replaces the fixed constant.
        val remainingTime = AppConfig.getQuizIntervalMs(this) -
            accumulatedScreenTimeMs - elapsedInWindow
        if (remainingTime <= 0) {
            onIntervalElapsed()
            return
        }

        Log.d(TAG, "Starting quiz timer, accumulated: ${accumulatedScreenTimeMs}ms, remaining: ${remainingTime}ms")

        quizTimer = object : CountDownTimer(remainingTime, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                // No per-tick accumulation: elapsed screen time is tracked via
                // screenOnStartMs and added in pauseTiming()/triggerQuiz() (B1).
            }

            override fun onFinish() {
                onIntervalElapsed()
            }
        }.start()
    }

    private fun pauseTiming() {
        quizTimer?.cancel()
        if (screenOnStartMs > 0) {
            // Batch 3: same clamp as startScreenTiming() — a wall-clock jump
            // must not inflate the settled total beyond one full interval.
            val elapsed = openWindowElapsedMs(System.currentTimeMillis())
            accumulatedScreenTimeMs += elapsed
            screenOnStartMs = 0
        }
        // M1: settle + clear the window start durably. Guarantees no repeat
        // accumulation and no stale start point after a subsequent hard kill.
        persistRuntimeState()
        Log.d(TAG, "Timing paused, accumulated: ${accumulatedScreenTimeMs}ms")
    }

    /**
     * Batch 3 (task #12 item 3): elapsed time of the still-open screen-on
     * window, clamped to [0, one full interval]. The upper bound protects the
     * rhythm against a large forward wall-clock jump (manual time set / NTP
     * sync): the raw `now - start` would otherwise settle the whole interval
     * at once and trigger an instant quiz.
     */
    private fun openWindowElapsedMs(nowMs: Long): Long =
        (nowMs - screenOnStartMs).coerceIn(0L, AppConfig.getQuizIntervalMs(this))

    /**
     * Batch 2-c: called exactly when the current interval elapses (both from
     * CountDownTimer.onFinish() and from the "already overdue" branch of
     * startScreenTiming()).
     *
     * The exempt-app guard MUST run BEFORE [triggerQuiz] changes the state to
     * WAITING_QUIZ_RESULT or persists KEY_QUIZ_IN_PROGRESS=true. If we entered
     * WAITING first, the service would wait forever for a quiz result that
     * never arrives and START_STICKY would re-show a ghost quiz on restart.
     *
     * ForegroundAppDetector is fail-closed: no usage-stats permission, no data
     * or an exception all mean "not exempt", so the quiz fires normally and a
     * child cannot bypass anti-addiction by refusing the permission.
     */
    private fun onIntervalElapsed() {
        val exempt = AppConfig.getExemptPackages(this)
        // Suppress the quiz while an exempt app (parent-configured) is in the
        // foreground OR a call is in progress (call priority, 2026-11-08):
        // system telephony calls and any VoIP voice/video call (WeChat voice
        // and video included) defer the quiz. WeChat mini-programs, mini-games
        // and Channels are NOT calls — they keep triggering quizzes normally.
        val suppress = ForegroundAppDetector.isExemptActive(this, exempt) ||
            CallPriorityDetector.isInCall(this)
        if (suppress) {
            Log.d(TAG, "Foreground exempt or call active, skipping quiz and restarting a full interval")
            quizTimer?.cancel()
            quizTimer = null
            accumulatedScreenTimeMs = 0
            screenOnStartMs = 0
            timingState = TimingState.IDLE
            persistRuntimeState()
            if (isScreenOn) {
                startScreenTiming()
            }
            return
        }
        triggerQuiz()
    }

    /**
     * Batch 2-c: AppConfig broadcasts ACTION_CONFIG_CHANGED after the parent
     * saves a new interval. Recompute the remaining time using the NEW
     * interval while PRESERVING the already-accumulated screen time and the
     * open window start point.
     *
     * Deliberately does NOT call pauseTiming(): settling the window here would
     * hand the child a brand-new interval on every configuration save.
     */
    private fun onConfigChanged() {
        Log.d(TAG, "Config changed, recomputing timer for state=$timingState")
        quizTimer?.cancel()
        quizTimer = null
        if (timingState == TimingState.TIMING && isScreenOn && !isPaused) {
            startScreenTiming()
        } else {
            // WAITING_QUIZ_RESULT / PAUSED / screen-off: keep state untouched.
            // The next SCREEN_ON or the sticky restore will re-evaluate.
            persistRuntimeState()
        }
    }

    private fun triggerQuiz() {
        // Defensive: never launch a second quiz while waiting for a result.
        if (timingState == TimingState.WAITING_QUIZ_RESULT) {
            return
        }
        quizTimer?.cancel()
        quizTimer = null
        needRelaunchQuiz = false

        Log.d(TAG, "Triggering quiz! Accumulated screen time: ${accumulatedScreenTimeMs}ms")
        accumulatedScreenTimeMs = 0
        screenOnStartMs = 0
        timingState = TimingState.WAITING_QUIZ_RESULT
        persistRuntimeState()

        if (!presentQuiz()) {
            recoverFromQuizLaunchFailure()
        }
    }

    /** Rollback for a failed first-time quiz launch in triggerQuiz() only. */
    private fun recoverFromQuizLaunchFailure() {
        // Never stall silently: roll back to IDLE and restart timing when the
        // screen is on, so a launch failure cannot freeze the monitor.
        Log.w(TAG, "Quiz launch failed, rolling back to IDLE")
        quizTimer?.cancel()
        quizTimer = null
        accumulatedScreenTimeMs = 0
        screenOnStartMs = 0
        timingState = TimingState.IDLE
        persistRuntimeState()
        if (isScreenOn && !isPaused) {
            startScreenTiming()
        }
    }

    /**
     * ③ retry entry for the START_STICKY redelivery, now also carrying the
     * call-priority rule (requirement 2026-11-08): while a call is in
     * progress the relaunch is NOT attempted — the flag stays set, the state
     * stays WAITING_QUIZ_RESULT, and the launch is retried every
     * [RELAUNCH_RETRY_DELAY_MS] until the call ends. Everything else is
     * identical to the original behaviour: the flag is consumed only after a
     * successful launch, and a non-call launch failure keeps WAITING for the
     * next redelivery instead of opening a fresh 10-minute window.
     */
    private fun attemptStickyQuizRelaunch() {
        if (!needRelaunchQuiz || timingState != TimingState.WAITING_QUIZ_RESULT) {
            return
        }
        Log.d(TAG, "START_STICKY restart during quiz: re-launching QuizActivity")
        if (presentQuiz()) {
            // Consume the relaunch flag ONLY after a successful launch.
            needRelaunchQuiz = false
            relaunchRetryHandler.removeCallbacks(relaunchRetryRunnable)
        } else if (CallPriorityDetector.isInCall(this)) {
            // Call priority: never pop a quiz over an ongoing call. Keep
            // WAITING, keep needRelaunchQuiz = true, keep
            // KEY_QUIZ_IN_PROGRESS = true; do not zero the accumulated time /
            // window start, do not restart the timer, do not fall back to
            // IDLE. Retry until the call ends.
            Log.d(
                TAG,
                "Quiz relaunch deferred: call in progress (call priority), " +
                    "retrying in ${RELAUNCH_RETRY_DELAY_MS / 1000}s"
            )
            persistRuntimeState()
            relaunchRetryHandler.removeCallbacks(relaunchRetryRunnable)
            relaunchRetryHandler.postDelayed(relaunchRetryRunnable, RELAUNCH_RETRY_DELAY_MS)
        } else {
            // Launch failed for another reason (e.g. API 29 background
            // activity restriction with no Activity stack right after the
            // process restart). Do NOT reuse the shared rollback:
            // recoverFromQuizLaunchFailure() would go IDLE, clear the
            // counters and open a brand-new 10-minute window -- exactly the
            // "clear from recents" bypass that ③ must prevent. Keep WAITING
            // and persist so the next redelivery retries the relaunch.
            Log.w(
                TAG,
                "Quiz relaunch failed after START_STICKY; keeping " +
                    "WAITING_QUIZ_RESULT and retrying on next redelivery"
            )
            persistRuntimeState()
        }
    }

    /**
     * SINGLE choke point for putting a quiz on screen (call-priority rule,
     * requirement 2026-11-08, enforced once here) plus the surface choice:
     *
     * PRIMARY: the system-overlay quiz ([QuizOverlayController]). Device
     * finding 2026-11-09: Huawei's background-start control silently denies
     * startActivity() once this app has not been foreground for a while (the
     * child watching videos in another app), so the Activity path cannot be
     * trusted. TYPE_APPLICATION_OVERLAY windows are not subject to background
     * start restrictions and cover any foreground app.
     *
     * FALLBACK: QuizActivity, used only when the overlay cannot be attached
     * (e.g. the SYSTEM_ALERT_WINDOW grant was revoked).
     *
     * Returns false when nothing could be shown; callers handle the false
     * (triggerQuiz rolls back to IDLE and re-times; the sticky relaunch
     * defers and retries).
     */
    private fun presentQuiz(): Boolean {
        if (CallPriorityDetector.isInCall(this)) {
            Log.d(TAG, "Call in progress (call priority): quiz launch suppressed")
            return false
        }
        if (com.antidip.ui.QuizOverlayController.show(applicationContext)) {
            return true
        }
        Log.w(TAG, "Overlay unavailable, falling back to QuizActivity")
        return launchQuizActivity()
    }

    private fun launchQuizActivity(): Boolean {
        return try {
            val intent = Intent(this, com.antidip.ui.QuizActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch QuizActivity", e)
            false
        }
    }

    /**
     * Applies the outcome reported by QuizActivity. The service restarts its
     * timer itself for terminal outcomes, so recovery no longer depends on the
     * UI sending [Constants.ACTION_RESUME] (defects S1/M1/M2).
     */
    private fun onQuizResult(outcome: String?) {
        if (timingState == TimingState.PAUSED) {
            persistRuntimeState()
            return
        }
        when (outcome) {
            Constants.OUTCOME_CORRECT, Constants.OUTCOME_DISMISSED_ABNORMAL -> {
                timingState = TimingState.IDLE
                quizTimer?.cancel()
                quizTimer = null
                accumulatedScreenTimeMs = 0
                screenOnStartMs = 0
                // M1: clear the persisted window start before a new window can
                // be opened by startScreenTiming() below.
                persistRuntimeState()
                if (isScreenOn && !isPaused) {
                    startScreenTiming()
                }
                Log.d(TAG, "Quiz settled ($outcome), timing restarted by service")
            }
            Constants.OUTCOME_CONTINUE -> {
                // Keep WAITING_QUIZ_RESULT: the quiz is still on screen after a
                // wrong answer or a delay. Never restart the timer here.
                Log.d(TAG, "Quiz still in progress, keep waiting for a result")
            }
            Constants.OUTCOME_PARENT_EXIT -> {
                timingState = TimingState.PAUSED
                isPaused = true
                isPausedSnapshot = true
                pauseTiming()
                markParentPaused()
                Log.d(TAG, "Parent exited quiz, temporary pause recorded by service")
            }
            else -> {
                Log.w(TAG, "Unknown quiz outcome: $outcome")
            }
        }
        persistRuntimeState()
    }

    /** Clears every form of pause and returns the state machine to IDLE. */
    private fun resumeTiming() {
        // Batch 2-b (T12): the settings-page "start service" button sends
        // ACTION_RESUME. While a quiz is on screen that must NOT tear down
        // WAITING_QUIZ_RESULT and open a brand-new 10-minute window, which
        // would let the child bypass the current quiz. Keep the state machine
        // untouched and simply re-persist the (unchanged) runtime state.
        if (timingState == TimingState.WAITING_QUIZ_RESULT) {
            if (needRelaunchQuiz) {
                // Device-found defect (2026-11-09): a WAITING state restored
                // from a process kill only re-showed the owed quiz on the
                // START_STICKY null redelivery. When the parent manually
                // starts the service instead (ACTION_RESUME, non-null intent
                // -> no redelivery), the service stayed WAITING forever and
                // no quiz ever came back. The parent is standing in the app
                // here, so a foreground activity launch is definitely
                // allowed: re-show the owed quiz now. attemptStickyQuizRelaunch
                // keeps WAITING on failure and defers on a call (call
                // priority), so the bypass protections are unchanged.
                Log.w(TAG, "ACTION_RESUME with a restored owed quiz: re-launching it")
                attemptStickyQuizRelaunch()
            } else {
                Log.w(TAG, "Ignoring ACTION_RESUME while waiting for a quiz result")
            }
            persistRuntimeState()
            return
        }
        isPaused = false
        isPausedSnapshot = false
        clearParentPaused()
        quizTimer?.cancel()
        quizTimer = null
        timingState = TimingState.IDLE
        if (isScreenOn) {
            startScreenTiming()
        }
        persistRuntimeState()
        Log.d(TAG, "Service resumed (in-memory pause cleared, S1 fixed)")
    }

    /** The parent pause marker is written ONLY by this service. */
    private fun markParentPaused() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(Constants.KEY_PARENT_PAUSE_AT_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    private fun clearParentPaused() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(Constants.KEY_PARENT_PAUSE_AT_ELAPSED)
            .apply()
    }

    /**
     * Persists ONLY volatile runtime state — exactly three keys:
     *   [Constants.KEY_ACCUMULATED_SCREEN_TIME] settled screen time,
     *   [Constants.KEY_QUIZ_IN_PROGRESS]        whether a quiz result is awaited,
     *   [Constants.KEY_SCREEN_ON_START_MS]      start of the open screen window (0 = none).
     * KEY_SERVICE_PAUSED belongs to the settings page's persistent-stop decision
     * and KEY_PARENT_PAUSE_AT_ELAPSED belongs to the service's parent-pause path;
     * neither may be overwritten here (architecture issues A01/A15).
     */
    private fun persistRuntimeState() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putLong(Constants.KEY_ACCUMULATED_SCREEN_TIME, accumulatedScreenTimeMs)
            putBoolean(
                Constants.KEY_QUIZ_IN_PROGRESS,
                timingState == TimingState.WAITING_QUIZ_RESULT
            )
            putLong(Constants.KEY_SCREEN_ON_START_MS, screenOnStartMs)
            apply()
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.antidip.ui.SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AntiAddictionApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
