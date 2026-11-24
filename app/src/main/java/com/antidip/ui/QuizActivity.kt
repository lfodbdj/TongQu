package com.antidip.ui

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antidip.AnswerResult
import com.antidip.AppConfig
import com.antidip.Constants
import com.antidip.DeviceLockHelper
import com.antidip.ParentAuth
import com.antidip.QuestionBankManager
import com.antidip.QuizEngine
import com.antidip.R
import com.antidip.data.QuizStateStore
import com.antidip.databinding.ActivityQuizBinding
import kotlinx.coroutines.launch

class QuizActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuizBinding
    private val quizEngine = QuizEngine()
    private lateinit var questionBankManager: QuestionBankManager
    private var countdownTimer: CountDownTimer? = null
    private var lockTimer: CountDownTimer? = null

    // Batch 2-b (A21): coroutines are bound to the lifecycle instead of an
    // app-scoped CoroutineScope that was never cancelled.
    // The 1.5 s auto-finish after a correct answer also uses a tracked
    // Runnable that onDestroy() removes, so a destroyed activity is never
    // finish()ed through a leaked callback.
    private val autoFinishHandler = Handler(Looper.getMainLooper())
    private val autoFinishRunnable = Runnable { finish() }

    // Batch 2-a: guards terminal quiz outcomes so CORRECT / PARENT_EXIT /
    // DISMISSED_ABNORMAL are reported to the service exactly once.
    // OUTCOME_CONTINUE is a repeatable keep-alive and must NEVER consume this
    // slot, otherwise the first wrong answer would permanently block the later
    // CORRECT result and the service timer would never restart (defect M2).
    private var terminalOutcomeSent = false

    /** AI 判分进行中（防止重复提交，2026-11-21）。 */
    private var aiJudging = false

    // Batch 2-b (A10): id of the question that was actually submitted. The
    // engine moves currentQuestionIndex on NextQuestion / NeedsNextQuestion,
    // so getCurrentQuestion() cannot be used for B14 bookkeeping afterwards.
    private var lastSubmittedQuestionId: Long? = null

    // 2026-11-21: 原填空 60 秒节流被统一的 30 秒重试间隔取代（用户要求，
    // 见 Constants.RETRY_INTERVAL_MS）。第 2/3 次作答解锁时间戳。
    private var retryBlockUntil: Long = 0L

    /** V2.0: attempts-left before the last submit (1 = third wrong answer). */
    private var lastSubmitAttemptsBefore: Int = 0

    /** V2.0: question object as of the last submit (engine may have advanced). */
    private var lastSubmittedQuestion: com.antidip.db.QuestionEntity? = null

    // Batch 2-b (A20 / M5): persisted lock-screen visibility state covering
    // SHOW_WHEN_LOCKED + TURN_SCREEN_ON + KEEP_SCREEN_ON as one group. During
    // a penalty this is false; a configuration change / process restore must
    // not silently turn any of the three back on.
    private var lockScreenVisibilityEnabled = true

    private companion object {
        const val TAG = "QuizActivity"
        private const val KEY_SHOW_WHEN_LOCKED = "show_when_locked"

        // 2026-11-21: FILL_SUBMIT_INTERVAL_MS（填空 60 秒节流）被统一的
        // Constants.RETRY_INTERVAL_MS（第 2/3 次作答间隔 30 秒）取代。

        // Batch 3 (A12): in-round state, so a rebuilt Activity (process death
        // while backgrounded) continues the SAME question with the SAME
        // attempts left instead of silently opening a fresh round.
        private const val KEY_ROUND_INDEX = "round_question_index"
        private const val KEY_ATTEMPTS_LEFT = "round_attempts_left"
        private const val KEY_QUESTION_ID = "round_question_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A20 / M5: SHOW_WHEN_LOCKED, TURN_SCREEN_ON and KEEP_SCREEN_ON are
        // managed as one group by setLockScreenVisibilityCompat(). The
        // deprecated window flags are applied only on API 24-26 where
        // setShowWhenLocked(boolean)/setTurnScreenOn(boolean) do not exist
        // (D02 compatibility, preserved). Restore the persisted state BEFORE
        // applying it, so a rebuilt Activity during a penalty does not turn
        // all three flags back on and defeat DeviceLockHelper.lockNow().
        lockScreenVisibilityEnabled =
            savedInstanceState?.getBoolean(KEY_SHOW_WHEN_LOCKED, true) ?: true
        setLockScreenVisibilityCompat(lockScreenVisibilityEnabled)

        // V2.0: silence background media for the fallback quiz surface too.
        com.antidip.MediaPauseHelper.pause(this)

        binding = ActivityQuizBinding.inflate(layoutInflater)
        setContentView(binding.root)

        questionBankManager = QuestionBankManager(this)

        setupUI()

        // Restore persisted counter / lock state (B2), then start a round
        // unless we are resuming an active lock screen. Batch 3 (A12): an
        // interrupted in-round question (process death while backgrounded)
        // is restored first; only a genuinely fresh entry opens a new round.
        lifecycleScope.launch {
            restoreState()
            refreshProgressLine()
            if (quizEngine.isLocked) {
                return@launch
            }
            if (!restoreRoundFromInstanceState(savedInstanceState)) {
                startNewRound()
            }
        }
    }

    /**
     * Batch 3 (A12): rebuild an interrupted round from the instance state.
     * The current question is re-fetched from the Room bank by id; the
     * in-memory fallback (id = 0) is intentionally NOT restorable, so it
     * falls back to a fresh round. Returns true when the round was restored.
     */
    private suspend fun restoreRoundFromInstanceState(state: Bundle?): Boolean {
        if (state == null || !state.containsKey(KEY_QUESTION_ID)) return false
        val questionId = state.getLong(KEY_QUESTION_ID, 0L)
        if (questionId <= 0L) return false
        val index = state.getInt(KEY_ROUND_INDEX, 0)
        val attempts = state.getInt(KEY_ATTEMPTS_LEFT, Constants.MAX_ATTEMPTS)
        val question = questionBankManager.getQuestionById(questionId) ?: return false
        quizEngine.questions.clear()
        quizEngine.questions.add(question)
        quizEngine.currentQuestionIndex = index.coerceIn(0, Constants.MAX_QUESTIONS_PER_ROUND - 1)
        quizEngine.currentAttemptsLeft = attempts
        quizEngine.isRoundActive = true
        quizEngine.isLocked = false
        displayQuestion()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // A20: without this, a rebuild during a penalty would default to true
        // and draw the quiz over the lock screen, defeating lockNow().
        outState.putBoolean(KEY_SHOW_WHEN_LOCKED, lockScreenVisibilityEnabled)
        // Batch 3 (A12): persist the in-round question so a rebuilt Activity
        // continues the same question with the same attempts left. A lock
        // screen has no restorable round (restoreState() re-enters it), and
        // the in-memory fallback (id = 0) cannot be re-fetched from the bank.
        val current = quizEngine.getCurrentQuestion()
        if (quizEngine.isRoundActive && !quizEngine.isLocked && current != null && current.id > 0) {
            outState.putInt(KEY_ROUND_INDEX, quizEngine.currentQuestionIndex)
            outState.putInt(KEY_ATTEMPTS_LEFT, quizEngine.currentAttemptsLeft)
            outState.putLong(KEY_QUESTION_ID, current.id)
        }
    }

    override fun onResume() {
        super.onResume()
        // B10 / A07: pin this task (screen pinning / lock-task mode) so the
        // child cannot escape the forced quiz via Home or the recents button.
        enableLockTaskIfNeeded()
    }

    /**
     * A07: query the REAL lock-task state via ActivityManager instead of a
     * local boolean. lockTaskModeState is available from API 23 (minSdk 24).
     * On a device without device-owner privileges Android may show a "screen
     * pinned" confirmation that the user can dismiss; in that case the state
     * stays NONE and we simply try again on the next onResume, without ever
     * claiming we are pinned when we are not.
     */
    private fun isInLockTaskMode(): Boolean {
        return try {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            activityManager != null &&
                activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query lockTaskModeState", e)
            false
        }
    }

    private fun enableLockTaskIfNeeded() {
        try {
            if (!isInLockTaskMode()) {
                startLockTask()
            }
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask unavailable on this device", e)
        }
    }

    /**
     * A20 / M5: single entry point that manages SHOW_WHEN_LOCKED,
     * TURN_SCREEN_ON and KEEP_SCREEN_ON as ONE group, so the penalty state can
     * never be half-on/half-off. Keeps batch-1's D02 compatibility branches:
     * API 27+ uses setShowWhenLocked(boolean)/setTurnScreenOn(boolean), while
     * API 24-26 uses the three deprecated window flags. KEEP_SCREEN_ON has no
     * Activity-level API, so it is added/cleared via the window in both
     * branches. The field is updated together with the actual window state.
     */
    @Suppress("DEPRECATION")
    private fun setLockScreenVisibilityCompat(enabled: Boolean) {
        lockScreenVisibilityEnabled = enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(enabled)
            setTurnScreenOn(enabled)
            // V2.0 (requirement 2026-11-10): FLAG_KEEP_SCREEN_ON deliberately
            // NOT set anywhere — the app must never keep the screen awake.
        } else {
            val flags =
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            window.apply {
                if (enabled) {
                    addFlags(flags)
                } else {
                    clearFlags(flags)
                }
            }
        }
    }

    /**
     * Restore [QuizEngine.consecutiveFailures] and lock state from [QuizStateStore].
     * If a lock is still active we re-enter the lock screen with the correct
     * remaining time; if it has already expired we clear the persisted values.
     */
    private suspend fun restoreState() {
        quizEngine.consecutiveFailures = QuizStateStore.getConsecutiveFailures(this)

        val lockExpiry = QuizStateStore.getLockExpiry(this)
        val now = System.currentTimeMillis()
        if (lockExpiry > 0 && lockExpiry > now) {
            val remainingSeconds = ((lockExpiry - now) / 1000).coerceAtLeast(1).toInt()
            quizEngine.isLocked = true
            quizEngine.isRoundActive = false
            // Resuming an already-active lock: do NOT re-lock the screen (the
            // child may already be viewing the penalty), just show the overlay.
            showLockScreen(remainingSeconds, lockDeviceScreen = false)
        } else if (lockExpiry > 0) {
            // Lock expired while the app was dead: clear persisted state as
            // ONE atomic write (batch 3, task #12).
            quizEngine.consecutiveFailures = 0
            QuizStateStore.setLockState(this, 0, 0L)
        }
    }

    private fun setupUI() {
        binding.btnSubmit.setOnClickListener { submitAnswer() }
        binding.btnNext.setOnClickListener { startNewRound() }
        binding.tvParentMode.setOnClickListener {
            // Batch 2-b (T2): the shared, non-cancellable parent gate. Only a
            // successful verification reports the outcome; the UI never writes
            // KEY_SERVICE_PAUSED or KEY_PARENT_PAUSE_AT_ELAPSED (batch 2-a).
            ParentAuth.showVerifyDialog(this) {
                sendQuizResult(Constants.OUTCOME_PARENT_EXIT)
                Toast.makeText(this, R.string.service_paused, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startNewRound() {
        binding.btnNext.visibility = View.GONE
        binding.btnSubmit.visibility = View.VISIBLE
        binding.tvFeedback.visibility = View.GONE
        binding.layoutLocked.visibility = View.GONE
        binding.cardQuiz.visibility = View.VISIBLE
        // 新题首答不受 30 秒重试锁限制。
        retryBlockUntil = 0L

        lifecycleScope.launch {
            // A04: an empty/exhausted bank must never release the child. The
            // manager returns an in-memory fallback question (id = 0) when the
            // database cannot supply one, so the quiz always continues.
            // AI 未配置时开放题（type='open'）从题库屏蔽（2026-11-21）。
            val question = questionBankManager.getRandomQuestionOrFallback(
                AppConfig.getSelectedGrade(this@QuizActivity),
                includeOpen = com.antidip.AiJudge.loadConfig(this@QuizActivity) != null
            )
            quizEngine.startRound(question)
            displayQuestion()
        }
    }

    private fun displayQuestion() {
        val question = quizEngine.getCurrentQuestion() ?: return
        val delaySeconds = quizEngine.getDelaySeconds()

        binding.tvQuestionNumber.text = getString(R.string.question_number, quizEngine.currentQuestionIndex + 1)
        binding.tvQuestion.text = question.content
        binding.tvAttempts.text = getString(R.string.attempts_left, quizEngine.currentAttemptsLeft)

        when (question.type) {
            "choice" -> {
                binding.rgOptions.visibility = View.VISIBLE
                binding.tilFillAnswer.visibility = View.GONE
                binding.rgOptions.removeAllViews()

                val options = question.options?.split("|") ?: emptyList()
                val labels = listOf("A", "B", "C", "D")
                options.forEachIndexed { index, option ->
                    val rb = RadioButton(this).apply {
                        text = "${labels.getOrElse(index) { "$index" }}. $option"
                        id = View.generateViewId()
                        tag = labels.getOrElse(index) { "$index" }
                        textSize = 16f
                        // 显式深字 + 深绿选择圈（浅色主题统一）。
                        setTextColor(getColor(R.color.on_surface))
                        buttonTintList = android.content.res.ColorStateList.valueOf(
                            getColor(R.color.accent)
                        )
                        setPadding(8, 16, 8, 16)
                    }
                    binding.rgOptions.addView(rb)
                }
            }
            "fill", "open" -> {
                binding.rgOptions.visibility = View.GONE
                binding.tilFillAnswer.visibility = View.VISIBLE
                binding.etFillAnswer.text?.clear()
                binding.etFillAnswer.hint = getString(
                    if (question.type == "open") R.string.open_hint else R.string.fill_hint
                )
            }
        }

        // Handle delay for question 2 and 3
        if (delaySeconds > 0) {
            startDelayCountdown(delaySeconds)
        } else {
            binding.tvCountdown.visibility = View.GONE
            binding.btnSubmit.isEnabled = true
        }
    }

    private fun startDelayCountdown(seconds: Int) {
        binding.btnSubmit.isEnabled = false
        binding.tvCountdown.visibility = View.VISIBLE

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(seconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000).toInt() + 1
                binding.tvCountdown.text = getString(R.string.wait_countdown, secs)
            }

            override fun onFinish() {
                binding.tvCountdown.visibility = View.GONE
                binding.btnSubmit.isEnabled = true
                // "延迟后继续": the quiz is still on screen, keep the service
                // in WAITING_QUIZ_RESULT instead of restarting the timer.
                sendQuizResult(Constants.OUTCOME_CONTINUE)
            }
        }.start()
    }

    private fun submitAnswer() {
        val question = quizEngine.getCurrentQuestion() ?: return
        val userAnswer = when (question.type) {
            "choice" -> {
                val selectedId = binding.rgOptions.checkedRadioButtonId
                if (selectedId == -1) {
                    Toast.makeText(this, "请选择一个答案", Toast.LENGTH_SHORT).show()
                    return
                }
                val selectedRb = findViewById<RadioButton>(selectedId)
                selectedRb.tag?.toString() ?: ""
            }
            "fill", "open" -> {
                binding.etFillAnswer.text?.toString()?.trim() ?: ""
            }
            else -> ""
        }

        if (userAnswer.isEmpty()) {
            Toast.makeText(this, "请输入答案", Toast.LENGTH_SHORT).show()
            return
        }

        // 开放式题（无标准答案，2026-11-21）：判分完全交给 AI（与悬浮窗
        // 同一套三态处理）；AI 不可用 → 本题不计分，原地重试。
        if (question.type == "open") {
            if (aiJudging) return
            aiJudging = true
            binding.btnSubmit.isEnabled = false
            binding.tvFeedback.visibility = View.VISIBLE
            binding.tvFeedback.text = getString(R.string.ai_judging)
            lifecycleScope.launch {
                val aiOk = try {
                    com.antidip.AiJudge.judgeOpen(this@QuizActivity, question, userAnswer)
                } catch (e: Exception) {
                    null
                }
                aiJudging = false
                binding.btnSubmit.isEnabled = true
                if (aiOk == null) {
                    binding.tvFeedback.visibility = View.GONE
                    binding.etFillAnswer.text?.clear()
                    Toast.makeText(
                        this@QuizActivity,
                        "AI 判分暂时不可用，本题不计分，请稍后再试",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                lastSubmittedQuestionId = question.id
                lastSubmittedQuestion = question
                lastSubmitAttemptsBefore = quizEngine.currentAttemptsLeft
                handleAnswerResult(quizEngine.submitOpenAnswer(aiOk))
            }
            return
        }

        // 每题 3 次机会，第 2/3 次作答需间隔 30 秒（用户要求 2026-11-21）。
        if (quizEngine.currentAttemptsLeft < Constants.MAX_ATTEMPTS) {
            val remain = retryBlockUntil - System.currentTimeMillis()
            if (remain > 0) {
                Toast.makeText(
                    this,
                    getString(R.string.retry_wait_fmt, ((remain + 999) / 1000).toInt()),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        // AI 判题（V2.2，2026-11-21）：填空答案与标准答案字面不一致时先问 AI
        // 语义是否等价；fail-closed，未启用/失败按原答案提交。
        if (question.type == "fill" &&
            userAnswer != question.answer.trim() &&
            com.antidip.AiJudge.loadConfig(this) != null
        ) {
            if (aiJudging) return
            aiJudging = true
            binding.btnSubmit.isEnabled = false
            binding.tvFeedback.visibility = View.VISIBLE
            binding.tvFeedback.text = getString(R.string.ai_judging)
            lifecycleScope.launch {
                val aiOk = try {
                    com.antidip.AiJudge.judgeFill(this@QuizActivity, question, userAnswer)
                } catch (e: Exception) {
                    null
                }
                aiJudging = false
                binding.btnSubmit.isEnabled = true
                lastSubmittedQuestionId = question.id
                lastSubmittedQuestion = question
                lastSubmitAttemptsBefore = quizEngine.currentAttemptsLeft
                val finalAnswer = if (aiOk == true) question.answer.trim() else userAnswer
                handleAnswerResult(quizEngine.submitAnswer(finalAnswer))
            }
            return
        }

        // A10: record the id BEFORE submitAnswer() may advance
        // currentQuestionIndex inside the engine.
        lastSubmittedQuestionId = question.id
        lastSubmittedQuestion = question
        lastSubmitAttemptsBefore = quizEngine.currentAttemptsLeft

        val result = quizEngine.submitAnswer(userAnswer)
        handleAnswerResult(result)
    }

    private fun handleAnswerResult(result: AnswerResult) {
        // A10: every non-correct, non-error outcome breaks the "two correct
        // in a row" streak. Use the RECORDED id: NextQuestion /
        // NeedsNextQuestion already advanced currentQuestionIndex, so
        // getCurrentQuestion() would point at the wrong row. The reset is
        // idempotent and harmless to repeat.
        if (result !is AnswerResult.Correct && result !is AnswerResult.Error) {
            lastSubmittedQuestionId?.let { id ->
                lifecycleScope.launch {
                    questionBankManager.resetConsecutiveCorrect(id)
                }
            }
        }

        when (result) {
            is AnswerResult.Correct -> {
                binding.tvFeedback.visibility = View.VISIBLE
                binding.tvFeedback.setBackgroundResource(R.color.correct_bg)

                // 每题 3 次机会（用户要求 2026-11-21）：仅首答正确计积分/连对；
                // 第 2/3 次重试答对不加分（也不涨连对）。仍按 OUTCOME_CORRECT
                // 结算计时。
                val firstTry = lastSubmitAttemptsBefore >= Constants.MAX_ATTEMPTS
                binding.tvFeedback.text = getString(
                    if (firstTry) R.string.answer_correct else R.string.answer_correct_retry
                )
                if (firstTry) {
                    updateProgress(
                        isCorrect = true,
                        isFill = lastSubmittedQuestion?.type == "fill",
                        fingerprint = lastSubmittedQuestion?.let {
                            com.antidip.ProgressRules.questionFingerprint(it.content, it.grade)
                        }
                    )
                }

                // Persist cleared failure counter (B2) and report the terminal
                // outcome to the service, which restarts its own timer (batch 2-a).
                lifecycleScope.launch {
                    QuizStateStore.setConsecutiveFailures(this@QuizActivity, quizEngine.consecutiveFailures)
                    // B14 + A04: only a persisted row (id > 0) can be counted
                    // or removed; the in-memory fallback has id = 0.
                    val q = quizEngine.getCurrentQuestion()
                    if (q != null && q.id > 0) {
                        val n = questionBankManager.incrementConsecutiveCorrect(q.id)
                        if (n >= 2) {
                            questionBankManager.removeQuestionIfAboveMinimum(q.id)
                        }
                    }
                }
                sendQuizResult(Constants.OUTCOME_CORRECT)

                // Auto-close after brief delay. Batch 2-b (A21): the callback
                // is a tracked Runnable removed in onDestroy(), so a destroyed
                // activity is never finish()ed through a leaked handler.
                binding.btnSubmit.visibility = View.GONE
                autoFinishHandler.removeCallbacks(autoFinishRunnable)
                autoFinishHandler.postDelayed(autoFinishRunnable, 1500)
            }

            is AnswerResult.Wrong -> {
                // Still answering: keep the service waiting for the final result.
                sendQuizResult(Constants.OUTCOME_CONTINUE)
                binding.tvFeedback.visibility = View.VISIBLE
                binding.tvFeedback.text = getString(R.string.answer_wrong, result.attemptsLeft)
                binding.tvFeedback.setBackgroundResource(R.color.wrong_bg)

                // 答错 → 30 秒后才可再次作答（第 2/3 次机会）。
                retryBlockUntil = System.currentTimeMillis() + Constants.RETRY_INTERVAL_MS

                // V2.0: wrong answer -> -1 point, streak reset. On the THIRD
                // wrong fill-in answer also explain + record the wrong book.
                updateProgress(
                    isCorrect = false,
                    isFill = lastSubmittedQuestion?.type == "fill",
                    fingerprint = lastSubmittedQuestion?.let {
                        com.antidip.ProgressRules.questionFingerprint(it.content, it.grade)
                    }
                )
                if (lastSubmittedQuestion?.type == "fill" && result.attemptsLeft == 0) {
                    recordWrongAndExplain(lastSubmittedQuestion!!)
                    binding.tvFeedback.text = getString(R.string.fill_explain, lastSubmittedQuestion!!.answer)
                }
                binding.tvAttempts.text = getString(R.string.attempts_left, result.attemptsLeft)
                // A10: streak reset now handled once at the top of this method.
            }

            is AnswerResult.NextQuestion -> {
                sendQuizResult(Constants.OUTCOME_CONTINUE)
                binding.tvFeedback.visibility = View.VISIBLE
                binding.tvFeedback.text = getString(R.string.answer_wrong_add_question)
                binding.tvFeedback.setBackgroundResource(R.color.wrong_bg)
                // V2.0: third wrong fill-in answer may land here (engine moved
                // on) — same handling as the Wrong branch.
                updateProgress(
                    isCorrect = false,
                    isFill = lastSubmittedQuestion?.type == "fill",
                    fingerprint = lastSubmittedQuestion?.let {
                        com.antidip.ProgressRules.questionFingerprint(it.content, it.grade)
                    }
                )
                if (lastSubmittedQuestion?.type == "fill" && lastSubmitAttemptsBefore == 1) {
                    recordWrongAndExplain(lastSubmittedQuestion!!)
                    binding.tvFeedback.text = getString(R.string.fill_explain, lastSubmittedQuestion!!.answer)
                }
                displayQuestion()
            }

            is AnswerResult.NeedsNextQuestion -> {
                sendQuizResult(Constants.OUTCOME_CONTINUE)
                binding.tvFeedback.visibility = View.VISIBLE
                binding.tvFeedback.text = getString(R.string.answer_wrong_add_question)
                binding.tvFeedback.setBackgroundResource(R.color.wrong_bg)

                // V2.0: same third-wrong handling as NextQuestion (this is the
                // branch the third wrong fill-in answer actually takes when the
                // next question must be loaded from the bank).
                updateProgress(
                    isCorrect = false,
                    isFill = lastSubmittedQuestion?.type == "fill",
                    fingerprint = lastSubmittedQuestion?.let {
                        com.antidip.ProgressRules.questionFingerprint(it.content, it.grade)
                    }
                )
                if (lastSubmittedQuestion?.type == "fill" && lastSubmitAttemptsBefore == 1) {
                    recordWrongAndExplain(lastSubmittedQuestion!!)
                    binding.tvFeedback.text = getString(R.string.fill_explain, lastSubmittedQuestion!!.answer)
                }

                // Load next question
                lifecycleScope.launch {
                    // A04 + A09: getRandomQuestionOrFallback() is non-null by
                    // contract (it returns an in-memory fallback question), so
                    // the quiz always continues and the engine's failure count
                    // is never forged by the UI. QuizEngine.onQuestionBankExhausted()
                    // stays as the engine's own public API for QA coverage, but
                    // is intentionally not called from main/.
                    val nextQ = questionBankManager.getRandomQuestionOrFallback(
                        AppConfig.getSelectedGrade(this@QuizActivity),
                        includeOpen = com.antidip.AiJudge.loadConfig(this@QuizActivity) != null
                    )
                    quizEngine.addQuestion(nextQ)
                    displayQuestion()
                }
            }

            is AnswerResult.RoundFailed -> {
                // V2.0: a fill-in question exhausted at the ROUND-3 position
                // lands here instead of NeedsNextQuestion — same handling.
                updateProgress(
                    isCorrect = false,
                    isFill = lastSubmittedQuestion?.type == "fill",
                    fingerprint = lastSubmittedQuestion?.let {
                        com.antidip.ProgressRules.questionFingerprint(it.content, it.grade)
                    }
                )
                if (lastSubmittedQuestion?.type == "fill" && lastSubmitAttemptsBefore == 1) {
                    recordWrongAndExplain(lastSubmittedQuestion!!)
                }
                binding.tvFeedback.visibility = View.VISIBLE
                binding.tvFeedback.text = "本轮全部答错（连续${result.consecutiveFailures}轮），再来一题！"
                binding.tvFeedback.setBackgroundResource(R.color.wrong_bg)

                // Persist the (incremented) failure counter (B2). result already
                // carries the engine's latest value, correct for both the normal
                // path and the question-bank-exhausted path (A09).
                lifecycleScope.launch {
                    QuizStateStore.setConsecutiveFailures(this@QuizActivity, result.consecutiveFailures)
                }

                binding.btnSubmit.visibility = View.GONE
                binding.btnNext.visibility = View.VISIBLE
                binding.btnNext.text = "下一轮"
            }

            is AnswerResult.Locked -> {
                // Persist failure counter + lock expiry as ONE atomic write
                // (B2 + batch 3, task #12), then show lock.
                lifecycleScope.launch {
                    QuizStateStore.setLockState(
                        this@QuizActivity,
                        quizEngine.consecutiveFailures,
                        System.currentTimeMillis() + result.durationSeconds * 1000L
                    )
                }
                showLockScreen(result.durationSeconds, lockDeviceScreen = true)
            }

            is AnswerResult.Error -> {
                Toast.makeText(this, "错误: ${result.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLockScreen(durationSeconds: Int, lockDeviceScreen: Boolean = false) {
        // B10: if device administration is enabled, truly turn off the screen so
        // the child cannot keep using the phone during the hard-lock penalty.
        if (lockDeviceScreen) {
            // We must NOT show over the keyguard here, otherwise the activity
            // would reappear on top of the lock screen and defeat lockNow().
            // A20 / M5: clear all three flags as one group and keep the field
            // in sync before requesting the real lock.
            setLockScreenVisibilityCompat(false)
            val locked = DeviceLockHelper.lockNow(this)
            if (!locked) {
                // M5: device admin is inactive (common on non-owner devices),
                // so the screen
                // was NOT actually locked. Leaving SHOW/TURN/KEEP cleared would
                // hide the penalty overlay for the full 600s with no way back.
                // Roll back immediately: keep the quiz visible and let the
                // countdown run to completion anyway.
                Log.w(TAG, "Device admin inactive: real lock skipped, keeping quiz visible")
                setLockScreenVisibilityCompat(true)
            }
        }

        binding.cardQuiz.visibility = View.GONE
        binding.layoutLocked.visibility = View.VISIBLE

        // Show current question in locked view
        val currentQ = quizEngine.getCurrentQuestion()
        binding.tvLockedQuestion.text = currentQ?.content ?: "题目加载中..."

        lockTimer?.cancel()
        lockTimer = object : CountDownTimer(durationSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val mins = millisUntilFinished / 60000
                val secs = (millisUntilFinished % 60000) / 1000
                binding.tvLockedCountdown.text = getString(R.string.locked_countdown, mins, secs)
            }

            override fun onFinish() {
                quizEngine.resetAfterLock()
                // Persist cleared counter and cleared lock as ONE atomic
                // write (B2 + batch 3, task #12).
                lifecycleScope.launch {
                    QuizStateStore.setLockState(this@QuizActivity, 0, 0L)
                }
                // A20 / M5: restore all three lock-screen visibility flags
                // through the unified compat entry so the field and window
                // state stay consistent after the penalty.
                setLockScreenVisibilityCompat(true)
                binding.layoutLocked.visibility = View.GONE
                binding.cardQuiz.visibility = View.VISIBLE
                // Penalty is over but the quiz continues: keep the service
                // waiting instead of restarting the screen-time timer.
                sendQuizResult(Constants.OUTCOME_CONTINUE)
                startNewRound()
            }
        }.start()
    }

    /**
     * Batch 2-a: report a quiz outcome to [com.antidip.ScreenMonitorService]
     * through an explicit (package-scoped) broadcast.
     *
     * An explicit broadcast plus a dynamically registered, non-exported
     * receiver is required on targetSdk 34 (implicit broadcast restrictions);
     * startService() would never reach that receiver, so the state machine
     * would never receive CORRECT and the timer would stall (defect M2).
     *
     * Terminal outcomes are sent at most once ([terminalOutcomeSent]);
     * [Constants.OUTCOME_CONTINUE] is a repeatable keep-alive that must not
     * consume the one-shot slot, otherwise the first wrong answer would
     * permanently block the later CORRECT result.
     */
    /** V2.0: paints the points/streak line on the quiz card. */
    private suspend fun refreshProgressLine() {
        val st = com.antidip.data.ProgressStore.getState(this)
        binding.tvProgress.text = getString(R.string.progress_line, st.points, st.streak)
    }

    /** V2.0: +points / streak / medals through [com.antidip.data.ProgressStore]. */
    private fun updateProgress(isCorrect: Boolean, isFill: Boolean, fingerprint: String? = null) {
        lifecycleScope.launch {
            val (state, newMedals) =
                com.antidip.data.ProgressStore.applyAnswer(
                    this@QuizActivity, isCorrect, isFill, fingerprint
                )
            binding.tvProgress.text =
                getString(R.string.progress_line, state.points, state.streak)
            newMedals.forEach { id ->
                val medal = com.antidip.ProgressRules.MEDALS.firstOrNull { it.first == id }
                Toast.makeText(
                    this@QuizActivity,
                    getString(R.string.medal_earned, medal?.second ?: "🏅", medal?.third ?: id),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** V2.0: third wrong fill-in answer -> wrong-question book record. */
    private fun recordWrongAndExplain(question: com.antidip.db.QuestionEntity) {
        lifecycleScope.launch {
            com.antidip.data.ProgressStore.addWrongEntry(
                this@QuizActivity,
                com.antidip.ProgressRules.WrongEntry(
                    content = question.content,
                    // 2026-11-21（用户要求）：选择题写"结果"（选项内容）而非字母。
                    answer = com.antidip.ProgressRules.answerResultText(
                        question.answer,
                        question.options?.split("|") ?: emptyList()
                    ),
                    subject = question.subject,
                    grade = question.grade,
                    wrongCount = 1,
                    lastWrongAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun sendQuizResult(outcome: String) {
        if (outcome != Constants.OUTCOME_CONTINUE) {
            if (terminalOutcomeSent) return
            terminalOutcomeSent = true
        }
        try {
            val intent = Intent(Constants.ACTION_QUIZ_RESULT)
                .setPackage(packageName)
                .putExtra(Constants.EXTRA_QUIZ_OUTCOME, outcome)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send quiz result", e)
        }
    }

    override fun onBackPressed() {
        // Prevent back button from dismissing quiz
        // Only parent password can exit
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
        lockTimer?.cancel()
        // Batch 2-b (A21): drop any pending auto-finish callback.
        autoFinishHandler.removeCallbacks(autoFinishRunnable)
        // V2.0: release the media mute — only when THIS surface owns it (the
        // overlay may be the active muter; a stale QuizActivity onDestroy must
        // not clear the overlay's mute state, V2.2 device finding).
        if (com.antidip.MediaPauseHelper.isMuting()) {
            com.antidip.MediaPauseHelper.resume(this)
        }
        // Batch 2-a last-resort unblock: Home / process death / any finish()
        // that did not already report a terminal outcome must never leave the
        // service stuck in WAITING_QUIZ_RESULT. Configuration changes recreate
        // the activity, so they must not emit a dismissal result.
        if (!terminalOutcomeSent && !isChangingConfigurations) {
            sendQuizResult(Constants.OUTCOME_DISMISSED_ABNORMAL)
        }
    }

    /**
     * B10 / A07: always release lock-task mode (screen pinning) before the
     * activity goes away, based on the REAL lock-task state rather than a
     * local boolean, so stopLockTask() is never called outside a pinned state.
     */
    override fun finish() {
        try {
            if (isInLockTaskMode()) {
                stopLockTask()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed", e)
        }
        super.finish()
    }
}
