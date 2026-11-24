package com.antidip.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.antidip.AnswerResult
import com.antidip.AppConfig
import com.antidip.Constants
import com.antidip.ParentAuth
import com.antidip.QuestionBankManager
import com.antidip.QuizEngine
import com.antidip.R
import com.antidip.data.QuizStateStore
import com.antidip.databinding.ActivityQuizBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * System-overlay quiz surface (fix for the EMUI background-activity-start
 * blocker, device finding 2026-11-09: when this app has not been in the
 * foreground for a while — e.g. the child has been watching videos in another
 * app — the service's startActivity() is silently denied by Huawei's
 * background-start control and the quiz never appears).
 *
 * The quiz UI is presented as a TYPE_APPLICATION_OVERLAY window instead:
 * system overlay windows are NOT subject to background-activity-start
 * restrictions, they cover whatever app is in the foreground (videos
 * included), they survive app switches (switching apps cannot dismiss the
 * quiz), and they die with the process (a process death then goes through the
 * normal persisted WAITING state and is re-shown on restore).
 *
 * The question flow is a faithful port of QuizActivity (same engine, same
 * persisted counters, same outcomes reported to the service), except:
 *  - the parent gate is an in-overlay password panel instead of a dialog
 *    (AlertDialog needs an activity token);
 *  - the real device lock (lockNow) is not triggered here — the overlay
 *    itself is the blocking surface, and B10's screen pinning only ever
 *    existed for the Activity path.
 */
class QuizOverlayController private constructor(
    private val appContext: Context
) {

    // Material views (TextInputLayout etc.) enforce a Material/AppCompat theme
    // at inflation time (ThemeEnforcement). The bare application context does
    // NOT satisfy that check even though the manifest declares a Material
    // application theme, so every view is created against a themed wrapper
    // (device finding 2026-11-09: overlay attach failed with
    // "checkAppCompatTheme" and the quiz silently fell back to QuizActivity).
    private val viewContext: Context =
        android.view.ContextThemeWrapper(appContext, R.style.Theme_AntiAddiction_Quiz)

    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val quizEngine = QuizEngine()
    private val questionBankManager = QuestionBankManager(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var binding: ActivityQuizBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var countdownTimer: CountDownTimer? = null
    private var lockTimer: CountDownTimer? = null
    private var autoFinishRunnable: Runnable? = null

    /** V2.0 fill-in throttle field removed 2026-11-21: replaced by the
     * uniform 30s retry interval (user requirement — see RETRY_INTERVAL_MS). */

    /** 第 2/3 次作答的解锁时间戳：答错后 30 秒内禁止再次提交。 */
    private var retryBlockUntil: Long = 0L

    /** V2.0: engine attempts-left BEFORE the last submit (1 = this submit was
     * the question's third wrong answer, even when the engine answers
     * NeedsNextQuestion/NextQuestion instead of Wrong). */
    private var lastSubmitAttemptsBefore: Int = 0

    /** V2.0: the question object AS OF the last submit — after a third wrong
     * answer the engine may already have advanced past it, so
     * getCurrentQuestion() can be null or point at the next question. */
    private var lastSubmittedQuestion: com.antidip.db.QuestionEntity? = null

    /** Guards terminal outcomes exactly like QuizActivity (defect M2). */
    private var terminalOutcomeSent = false

    /** AI 判分进行中（防止重复提交，2026-11-21）。 */
    private var aiJudging = false

    /** Batch 2-b (A10): id of the question actually submitted (B14 bookkeeping). */
    private var lastSubmittedQuestionId: Long? = null

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private fun attach(): Boolean {
        val inflater = LayoutInflater.from(viewContext)
        val b = ActivityQuizBinding.inflate(inflater)
        binding = b

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        // V2.0 (requirement 2026-11-10): the app must NOT keep the screen on —
        // FLAG_KEEP_SCREEN_ON deliberately removed; the overlay simply stays
        // behind the lockscreen when the display times out.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        layoutParams = lp

        setupUi(b)
        windowManager.addView(b.root, lp)
        startMediaSilence()

        // Restore persisted counter / lock state (B2), then start a round
        // unless an active lock screen must be resumed.
        scope.launch {
            restoreState()
            refreshProgressLine()
            if (!quizEngine.isLocked) {
                startNewRound()
            }
        }
        Log.d(TAG, "Quiz overlay attached")
        return true
    }

    fun dismiss() {
        // Clear the companion singleton FIRST: parent exit calls dismiss()
        // directly on the instance, and a stale `active` here would make the
        // next show() return true without ever attaching a window (device
        // finding 2026-11-09: quiz silently vanished after the first parent
        // exit because the flag stayed set).
        if (active === this) {
            active = null
        }
        mainHandler.removeCallbacksAndMessages(null)
        countdownTimer?.cancel()
        lockTimer?.cancel()
        com.antidip.MediaPauseHelper.resume(appContext)
        val b = binding
        binding = null
        if (b != null) {
            try {
                windowManager.removeView(b.root)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay view already detached", e)
            }
        }
        scope.cancel()
        Log.d(TAG, "Quiz overlay dismissed")
    }

    /**
     * V2.0 (requirement 2026-11-10): while the overlay is up, background
     * media (short-video apps, their ads) must not keep playing. Real-device
     * finding: 红果 IGNORES focus loss and media-key pauses, so the guaranteed
     * layer in [com.antidip.MediaPauseHelper] mutes STREAM_MUSIC; the polite
     * layers (media key + focus) stay. The mute is re-asserted every 3 s
     * because physical volume keys bypass the overlay. Previous volume is
     * restored on dismiss; paused playback is NOT auto-resumed.
     */
    private fun startMediaSilence() {
        com.antidip.MediaPauseHelper.pause(appContext)
        val reassert = object : Runnable {
            override fun run() {
                com.antidip.MediaPauseHelper.reassertMute(appContext)
                mainHandler.postDelayed(this, 3_000L)
            }
        }
        mainHandler.postDelayed(reassert, 3_000L)
    }

    // ------------------------------------------------------------------
    // UI wiring (ported from QuizActivity)
    // ------------------------------------------------------------------

    private fun setupUi(b: ActivityQuizBinding) {
        b.btnSubmit.setOnClickListener { submitAnswer() }
        b.btnNext.setOnClickListener { startNewRound() }
        b.tvParentMode.setOnClickListener { showParentExitPanel() }
    }

    /**
     * Parent gate without an activity token: swap the quiz card for a small
     * programmatic password panel. Only a successful ParentAuth verification
     * reports OUTCOME_PARENT_EXIT and releases the screen.
     */
    private fun showParentExitPanel() {
        val b = binding ?: return
        b.cardQuiz.visibility = android.view.View.GONE

        val box = LinearLayout(viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 120, 80, 80)
            setBackgroundColor(viewContext.getColor(R.color.surface))
        }
        box.addView(TextView(viewContext).apply {
            text = viewContext.getString(R.string.parent_mode_title)
            textSize = 20f
            setTextColor(viewContext.getColor(R.color.primary))
        })
        val error = TextView(viewContext).apply {
            text = viewContext.getString(R.string.password_wrong)
            textSize = 13f
            setTextColor(viewContext.getColor(R.color.error))
            visibility = android.view.View.GONE
        }
        val input = EditText(viewContext).apply {
            hint = viewContext.getString(R.string.parent_mode_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        box.addView(input)
        box.addView(error)
        box.addView(Button(viewContext).apply {
            text = viewContext.getString(R.string.parent_mode_confirm)
            setOnClickListener {
                if (ParentAuth.verify(input.text?.toString().orEmpty())) {
                    sendQuizResult(Constants.OUTCOME_PARENT_EXIT)
                    Toast.makeText(
                        appContext, R.string.service_paused, Toast.LENGTH_LONG
                    ).show()
                    dismiss()
                } else {
                    error.visibility = android.view.View.VISIBLE
                }
            }
        })
        box.addView(Button(viewContext).apply {
            text = viewContext.getString(R.string.parent_mode_cancel)
            setOnClickListener {
                (box.parent as? android.view.ViewGroup)?.removeView(box)
                b.cardQuiz.visibility = android.view.View.VISIBLE
            }
        })

        (b.root as android.view.ViewGroup).addView(box)
    }

    private suspend fun restoreState() {
        quizEngine.consecutiveFailures = QuizStateStore.getConsecutiveFailures(appContext)

        val lockExpiry = QuizStateStore.getLockExpiry(appContext)
        val now = System.currentTimeMillis()
        if (lockExpiry > 0 && lockExpiry > now) {
            val remainingSeconds = ((lockExpiry - now) / 1000).coerceAtLeast(1).toInt()
            quizEngine.isLocked = true
            quizEngine.isRoundActive = false
            showLockScreen(remainingSeconds)
        } else if (lockExpiry > 0) {
            quizEngine.consecutiveFailures = 0
            QuizStateStore.setLockState(appContext, 0, 0L)
        }
    }

    private fun startNewRound() {
        val b = binding ?: return
        b.btnNext.visibility = android.view.View.GONE
        b.btnSubmit.visibility = android.view.View.VISIBLE
        b.tvFeedback.visibility = android.view.View.GONE
        b.layoutLocked.visibility = android.view.View.GONE
        b.cardQuiz.visibility = android.view.View.VISIBLE
        // 新题首答不受 30 秒重试锁限制。
        retryBlockUntil = 0L

        scope.launch {
            // AI 未配置时开放题（type='open'）从题库屏蔽（用户要求 2026-11-21）。
            val question = questionBankManager.getRandomQuestionOrFallback(
                AppConfig.getSelectedGrade(appContext),
                includeOpen = com.antidip.AiJudge.loadConfig(appContext) != null
            )
            quizEngine.startRound(question)
            displayQuestion()
        }
    }

    private fun displayQuestion() {
        val b = binding ?: return
        val question = quizEngine.getCurrentQuestion() ?: return
        val delaySeconds = quizEngine.getDelaySeconds()

        b.tvQuestionNumber.text =
            appContext.getString(R.string.question_number, quizEngine.currentQuestionIndex + 1)
        b.tvQuestion.text = question.content
        b.tvAttempts.text =
            appContext.getString(R.string.attempts_left, quizEngine.currentAttemptsLeft)

        when (question.type) {
            "choice" -> {
                b.rgOptions.visibility = android.view.View.VISIBLE
                b.tilFillAnswer.visibility = android.view.View.GONE
                b.rgOptions.removeAllViews()

                val options = question.options?.split("|") ?: emptyList()
                val labels = listOf("A", "B", "C", "D")
                options.forEachIndexed { index, option ->
                    val rb = RadioButton(viewContext).apply {
                        text = "${labels.getOrElse(index) { "$index" }}. $option"
                        id = android.view.View.generateViewId()
                        tag = labels.getOrElse(index) { "$index" }
                        textSize = 16f
                        // 浅色卡片上显式深字（默认黑字即可读，颜色保持统一）。
                        setTextColor(viewContext.getColor(R.color.on_surface))
                        buttonTintList = android.content.res.ColorStateList.valueOf(
                            viewContext.getColor(R.color.accent)
                        )
                        setPadding(8, 16, 8, 16)
                    }
                    b.rgOptions.addView(rb)
                }
            }
            "fill", "open" -> {
                b.rgOptions.visibility = android.view.View.GONE
                b.tilFillAnswer.visibility = android.view.View.VISIBLE
                b.etFillAnswer.text?.clear()
                b.etFillAnswer.hint = appContext.getString(
                    if (question.type == "open") R.string.open_hint else R.string.fill_hint
                )
            }
        }

        if (delaySeconds > 0) {
            startDelayCountdown(delaySeconds)
        } else {
            b.tvCountdown.visibility = android.view.View.GONE
            b.btnSubmit.isEnabled = true
        }
    }

    private fun startDelayCountdown(seconds: Int) {
        val b = binding ?: return
        b.btnSubmit.isEnabled = false
        b.tvCountdown.visibility = android.view.View.VISIBLE

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(seconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = (millisUntilFinished / 1000).toInt() + 1
                b.tvCountdown.text = appContext.getString(R.string.wait_countdown, secs)
            }

            override fun onFinish() {
                b.tvCountdown.visibility = android.view.View.GONE
                b.btnSubmit.isEnabled = true
                sendQuizResult(Constants.OUTCOME_CONTINUE)
            }
        }.start()
    }

    private fun submitAnswer() {
        val b = binding ?: return
        val question = quizEngine.getCurrentQuestion() ?: return
        val userAnswer = when (question.type) {
            "choice" -> {
                val selectedId = b.rgOptions.checkedRadioButtonId
                if (selectedId == -1) {
                    Toast.makeText(appContext, "请选择一个答案", Toast.LENGTH_SHORT).show()
                    return
                }
                val selectedRb = b.rgOptions.findViewById<RadioButton>(selectedId)
                selectedRb.tag?.toString() ?: ""
            }
            "fill", "open" -> b.etFillAnswer.text?.toString()?.trim() ?: ""
            else -> ""
        }

        if (userAnswer.isEmpty()) {
            Toast.makeText(appContext, "请输入答案", Toast.LENGTH_SHORT).show()
            return
        }

        // 开放式题（无标准答案，2026-11-21）：判分完全交给 AI。
        //  - AI 判对 → 引擎按正确结算（首答计 +2 分，重试不计分规则同前）；
        //  - AI 判错 → 引擎按错误结算（-1 分 / 30 秒重试锁）；
        //  - AI 不可用 → 本题不计分不扣分，清空输入原地重试（防循环换题）。
        if (question.type == "open") {
            if (aiJudging) return
            aiJudging = true
            b.btnSubmit.isEnabled = false
            b.tvFeedback.visibility = android.view.View.VISIBLE
            b.tvFeedback.text = appContext.getString(R.string.ai_judging)
            scope.launch {
                val aiOk = try {
                    com.antidip.AiJudge.judgeOpen(appContext, question, userAnswer)
                } catch (e: Exception) {
                    null
                }
                aiJudging = false
                b.btnSubmit.isEnabled = true
                if (aiOk == null) {
                    b.tvFeedback.visibility = android.view.View.GONE
                    b.etFillAnswer.text?.clear()
                    Toast.makeText(
                        appContext,
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

        // 每题 3 次机会，第 2/3 次作答需间隔 30 秒（用户要求 2026-11-21）：
        // 首答错后锁定 30s，解锁后才能重试。
        if (quizEngine.currentAttemptsLeft < Constants.MAX_ATTEMPTS) {
            val remain = retryBlockUntil - System.currentTimeMillis()
            if (remain > 0) {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.retry_wait_fmt, ((remain + 999) / 1000).toInt()),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        // AI 判题（V2.2，2026-11-21）：填空答案与标准答案字面不一致时，先问
        // AI 语义是否等价（如 0.5 = 1/2）；等价则以标准答案提交（引擎自然判
        // 对）。fail-closed：未启用/网络失败按原答案提交，绝不因 AI 放水。
        if (question.type == "fill" &&
            userAnswer != question.answer.trim() &&
            com.antidip.AiJudge.loadConfig(appContext) != null
        ) {
            if (aiJudging) return
            aiJudging = true
            b.btnSubmit.isEnabled = false
            b.tvFeedback.visibility = android.view.View.VISIBLE
            b.tvFeedback.text = appContext.getString(R.string.ai_judging)
            scope.launch {
                val aiOk = try {
                    com.antidip.AiJudge.judgeFill(appContext, question, userAnswer)
                } catch (e: Exception) {
                    null
                }
                aiJudging = false
                b.btnSubmit.isEnabled = true
                lastSubmittedQuestionId = question.id
                lastSubmittedQuestion = question
                lastSubmitAttemptsBefore = quizEngine.currentAttemptsLeft
                val finalAnswer = if (aiOk == true) question.answer.trim() else userAnswer
                handleAnswerResult(quizEngine.submitAnswer(finalAnswer))
            }
            return
        }

        lastSubmittedQuestionId = question.id
        lastSubmittedQuestion = question
        lastSubmitAttemptsBefore = quizEngine.currentAttemptsLeft

        val result = quizEngine.submitAnswer(userAnswer)
        handleAnswerResult(result)
    }

    private fun handleAnswerResult(result: AnswerResult) {
        val b = binding ?: return
        if (result !is AnswerResult.Correct && result !is AnswerResult.Error) {
            lastSubmittedQuestionId?.let { id ->
                scope.launch { questionBankManager.resetConsecutiveCorrect(id) }
            }
        }

        when (result) {
            is AnswerResult.Correct -> {
                b.tvFeedback.visibility = android.view.View.VISIBLE
                b.tvFeedback.setBackgroundResource(R.color.correct_bg)

                // 每题 3 次机会（用户要求 2026-11-21）：仅首答正确计积分/连对；
                // 第 2/3 次重试答对不加分（也不涨连对，防反复试到对）。仍按
                // OUTCOME_CORRECT 结算计时，错题本/讲解路径不变。
                val firstTry = lastSubmitAttemptsBefore >= Constants.MAX_ATTEMPTS
                b.tvFeedback.text = appContext.getString(
                    if (firstTry) R.string.answer_correct else R.string.answer_correct_retry
                )
                if (firstTry) {
                    updateProgress(
                        isCorrect = true,
                        isFill = lastSubmittedQuestion?.type == "fill",
                        fingerprint = lastSubmittedQuestion?.let {
                            com.antidip.ProgressRules.questionFingerprint(
                                it.content, it.grade
                            )
                        }
                    )
                }

                scope.launch {
                    QuizStateStore.setConsecutiveFailures(
                        appContext, quizEngine.consecutiveFailures
                    )
                    val q = quizEngine.getCurrentQuestion()
                    if (q != null && q.id > 0) {
                        val n = questionBankManager.incrementConsecutiveCorrect(q.id)
                        if (n >= 2) {
                            questionBankManager.removeQuestionIfAboveMinimum(q.id)
                        }
                    }
                }
                sendQuizResult(Constants.OUTCOME_CORRECT)

                b.btnSubmit.visibility = android.view.View.GONE
                val finish = Runnable { dismiss() }
                autoFinishRunnable = finish
                mainHandler.postDelayed(finish, 1500)
            }

            is AnswerResult.Wrong -> {
                sendQuizResult(Constants.OUTCOME_CONTINUE)
                b.tvFeedback.visibility = android.view.View.VISIBLE
                b.tvFeedback.text = appContext.getString(R.string.answer_wrong, result.attemptsLeft)
                b.tvFeedback.setBackgroundResource(R.color.wrong_bg)
                b.tvAttempts.text =
                    appContext.getString(R.string.attempts_left, result.attemptsLeft)

                // 答错 → 30 秒后才可再次作答（第 2/3 次机会）。
                retryBlockUntil = System.currentTimeMillis() + Constants.RETRY_INTERVAL_MS

                // V2.0: wrong answer -> -1 point, streak reset. On the THIRD
                // wrong fill-in answer also explain the answer and record the
                // question in the wrong-question book.
                updateProgress(
                    isCorrect = false,
                    isFill = lastSubmittedQuestion?.type == "fill",
                    fingerprint = lastSubmittedQuestion?.let {
                        com.antidip.ProgressRules.questionFingerprint(it.content, it.grade)
                    }
                )
                if (lastSubmittedQuestion?.type == "fill" && lastSubmitAttemptsBefore == 1) {
                    recordWrongAndExplain(lastSubmittedQuestion!!)
                    b.tvFeedback.text = appContext.getString(
                        R.string.fill_explain, lastSubmittedQuestion!!.answer
                    )
                }
            }

            is AnswerResult.NextQuestion -> {
                sendQuizResult(Constants.OUTCOME_CONTINUE)
                b.tvFeedback.visibility = android.view.View.VISIBLE
                b.tvFeedback.text = appContext.getString(R.string.answer_wrong_add_question)
                b.tvFeedback.setBackgroundResource(R.color.wrong_bg)
                // V2.0: the third wrong attempt arrives here (engine moved on
                // instead of returning Wrong) — still costs a point, and a
                // fill-in question is explained + recorded (device finding:
                // the Wrong-branch-only check missed this path).
                updateProgress(
                    isCorrect = false,
                    isFill = lastSubmittedQuestion?.type == "fill",
                    fingerprint = lastSubmittedQuestion?.let {
                        com.antidip.ProgressRules.questionFingerprint(it.content, it.grade)
                    }
                )
                if (lastSubmittedQuestion?.type == "fill" && lastSubmitAttemptsBefore == 1) {
                    recordWrongAndExplain(lastSubmittedQuestion!!)
                    b.tvFeedback.text = appContext.getString(
                        R.string.fill_explain, lastSubmittedQuestion!!.answer
                    )
                }
                displayQuestion()
            }

            is AnswerResult.NeedsNextQuestion -> {
                sendQuizResult(Constants.OUTCOME_CONTINUE)
                b.tvFeedback.visibility = android.view.View.VISIBLE
                b.tvFeedback.text = appContext.getString(R.string.answer_wrong_add_question)
                b.tvFeedback.setBackgroundResource(R.color.wrong_bg)
                // V2.0: same third-wrong handling as NextQuestion (this is the
                // branch the third wrong fill-in answer actually takes when
                // the next question must be loaded from the bank).
                updateProgress(
                    isCorrect = false,
                    isFill = lastSubmittedQuestion?.type == "fill",
                    fingerprint = lastSubmittedQuestion?.let {
                        com.antidip.ProgressRules.questionFingerprint(it.content, it.grade)
                    }
                )
                if (lastSubmittedQuestion?.type == "fill" && lastSubmitAttemptsBefore == 1) {
                    recordWrongAndExplain(lastSubmittedQuestion!!)
                    b.tvFeedback.text = appContext.getString(
                        R.string.fill_explain, lastSubmittedQuestion!!.answer
                    )
                }
                scope.launch {
                    val nextQ = questionBankManager.getRandomQuestionOrFallback(
                        AppConfig.getSelectedGrade(appContext),
                        includeOpen = com.antidip.AiJudge.loadConfig(appContext) != null
                    )
                    quizEngine.addQuestion(nextQ)
                    displayQuestion()
                }
            }

            is AnswerResult.RoundFailed -> {
                // V2.0: a fill-in question exhausted at the ROUND-3 position
                // lands here (handleQuestionFailed else-branch) instead of
                // NeedsNextQuestion — apply the same third-wrong handling.
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
                b.tvFeedback.visibility = android.view.View.VISIBLE
                b.tvFeedback.text = "本轮全部答错（连续${result.consecutiveFailures}轮），再来一题！"
                b.tvFeedback.setBackgroundResource(R.color.wrong_bg)
                scope.launch {
                    QuizStateStore.setConsecutiveFailures(
                        appContext, result.consecutiveFailures
                    )
                }
                b.btnSubmit.visibility = android.view.View.GONE
                b.btnNext.visibility = android.view.View.VISIBLE
                b.btnNext.text = "下一轮"
            }

            is AnswerResult.Locked -> {
                persistLockStateDetached(
                    quizEngine.consecutiveFailures, result.durationSeconds
                )
                showLockScreen(result.durationSeconds)
            }

            is AnswerResult.Error -> {
                Toast.makeText(appContext, "错误: ${result.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Atomic lock-state persist on a DETACHED scope: the controller's own
     * scope is cancelled on dismiss(), but the lock state gates future rounds
     * and must survive a mid-write dismissal.
     */
    private fun persistLockStateDetached(failures: Int, durationSeconds: Int) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            QuizStateStore.setLockState(
                appContext,
                failures,
                System.currentTimeMillis() + durationSeconds * 1000L
            )
        }
    }

    private fun showLockScreen(durationSeconds: Int) {
        val b = binding ?: return
        b.cardQuiz.visibility = android.view.View.GONE
        b.layoutLocked.visibility = android.view.View.VISIBLE

        val currentQ = quizEngine.getCurrentQuestion()
        b.tvLockedQuestion.text = currentQ?.content ?: "题目加载中..."

        lockTimer?.cancel()
        lockTimer = object : CountDownTimer(durationSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val mins = millisUntilFinished / 60000
                val secs = (millisUntilFinished % 60000) / 1000
                b.tvLockedCountdown.text =
                    appContext.getString(R.string.locked_countdown, mins, secs)
            }

            override fun onFinish() {
                quizEngine.resetAfterLock()
                persistLockStateDetached(0, 0)
                b.layoutLocked.visibility = android.view.View.GONE
                b.cardQuiz.visibility = android.view.View.VISIBLE
                sendQuizResult(Constants.OUTCOME_CONTINUE)
                startNewRound()
            }
        }.start()
    }

    /** V2.0: paints the points/streak line on the quiz card. */
    private suspend fun refreshProgressLine() {
        val st = com.antidip.data.ProgressStore.getState(appContext)
        binding?.tvProgress?.text =
            appContext.getString(R.string.progress_line, st.points, st.streak)
    }

    /** V2.0: +points / streak / medals through [com.antidip.data.ProgressStore]. */
    private fun updateProgress(isCorrect: Boolean, isFill: Boolean, fingerprint: String? = null) {
        // 计分写入必须在 dismiss() 之后仍能落盘：dismiss 会 cancel 本控制器
        // 的 scope（见 dismiss()），用独立的 IO scope 保证 +1/-1 不被取消丢失。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val (state, newMedals) =
                com.antidip.data.ProgressStore.applyAnswer(
                    appContext, isCorrect, isFill, fingerprint
                )
            mainHandler.post {
                binding?.tvProgress?.text =
                    appContext.getString(R.string.progress_line, state.points, state.streak)
            }
            newMedals.forEach { id ->
                val medal = com.antidip.ProgressRules.MEDALS.firstOrNull { it.first == id }
                mainHandler.post {
                    Toast.makeText(
                        appContext,
                        appContext.getString(
                            R.string.medal_earned,
                            medal?.second ?: "🏅", medal?.third ?: id
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** V2.0: third wrong fill-in answer -> wrong-question book record. */
    private fun recordWrongAndExplain(
        question: com.antidip.db.QuestionEntity
    ) {
        // 错题本落盘同样走独立 scope：第三错触发时可能紧跟着 dismiss 取消。
        // 2026-11-21（用户要求）：选择题写"结果"（选项内容）而非选项字母。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            com.antidip.data.ProgressStore.addWrongEntry(
                appContext,
                com.antidip.ProgressRules.WrongEntry(
                    content = question.content,
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
                .setPackage(appContext.packageName)
                .putExtra(Constants.EXTRA_QUIZ_OUTCOME, outcome)
            appContext.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send quiz result", e)
        }
    }

    companion object {
        private const val TAG = "QuizOverlay"

        @Volatile
        private var active: QuizOverlayController? = null

        /** True while the overlay covers the screen. */
        val isShowing: Boolean
            get() = active != null

        /**
         * Shows the quiz overlay over whatever app is in the foreground.
         * Returns false when the overlay could not be attached (e.g. the
         * SYSTEM_ALERT_WINDOW grant was revoked), so the caller can fall back
         * to the Activity path / rollback.
         */
        fun show(appContext: Context): Boolean {
            if (active != null) return true
            return try {
                val c = QuizOverlayController(appContext.applicationContext)
                if (c.attach()) {
                    active = c
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to attach quiz overlay", e)
                false
            }
        }

        /** Dismisses the overlay when one is showing (parent exit / cleanup). */
        fun dismissIfShowing() {
            active?.dismiss()
            active = null
        }
    }
}
