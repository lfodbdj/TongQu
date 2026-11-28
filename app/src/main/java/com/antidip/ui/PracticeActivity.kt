package com.antidip.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antidip.AppConfig
import com.antidip.ProgressRules
import com.antidip.R
import com.antidip.data.ProgressStore
import com.antidip.db.QuestionEntity
import com.antidip.QuestionBankManager
import kotlinx.coroutines.launch

/**
 * V2.1 主动答题 (requirement 2026-11-10): a VOLUNTARY practice mode the child
 * can start from the growth screen. Completely decoupled from the forced-quiz
 * pipeline — it does NOT touch the service state machine, screen time, media
 * mute or lock task. It only reuses the bank, the engine rules and the same
 * points/medal/wrong-book persistence, so practice earns real rewards:
 *  - choice correct +1 / fill correct +2 / wrong -1 (clamped, via ProgressStore)
 *  - consecutive-correct streak continues here too
 *  - every wrong fill-in answer is recorded into the wrong-question book
 *
 * Session: PRACTICE_LENGTH questions, then a summary; the child can end early
 * with the finish button. Not exported (entered from GrowthActivity only).
 */
class PracticeActivity : AppCompatActivity() {

    private val engine = com.antidip.QuizEngine()
    private lateinit var bankManager: QuestionBankManager

    private lateinit var box: LinearLayout
    private lateinit var progressLabel: TextView
    private lateinit var questionLabel: TextView
    private lateinit var optionsBox: android.widget.RadioGroup
    private lateinit var feedbackLabel: TextView
    private lateinit var fillInput: EditText
    private lateinit var actionButton: Button

    private var answeredCount = 0
    private var correctCount = 0
    private var currentQuestion: QuestionEntity? = null
    private var submitted = false
    private var sessionOver = false

    /** UI v3 错题"重做这道题"：单题模式（从错题本点入）。 */
    private var totalQuestions = PRACTICE_LENGTH
    private var focusContent: String? = null
    private var focusGrade = 0
    private var focusPending = false

    /**
     * V2.3（用户要求 2026-11-24）：退出重进保持原题。当 [resumingPending] 为 true
     * 时，当前题是上次退出未作答/未推进恢复出来的，必须作答后才能推进（不可跳过）。
     */
    private var resumingPending = false

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bankManager = QuestionBankManager(this)

        focusContent = intent.getStringExtra("focus_content")
        focusGrade = intent.getIntExtra("focus_grade", 0)

        // 先同步搭建界面（避免 DataStore 读取期间白屏），再异步恢复会话。
        buildUi()

        lifecycleScope.launch {
            // V2.3（用户要求 2026-11-24）：恢复上次未完成的练习会话——
            // 退出时若当前题未作答/未推进，则其 id 存于 practice_pending_id；
            // 再次进入时优先恢复同一道题（保持原题），并恢复已作答题数/答对数/总数。
            val pendingId = runCatching { ProgressStore.getPracticePending(this@PracticeActivity) }
                .getOrDefault(0L)
            val savedAnswered = runCatching { ProgressStore.getPracticeAnswered(this@PracticeActivity) }
                .getOrDefault(0)
            val savedCorrect = runCatching { ProgressStore.getPracticeCorrect(this@PracticeActivity) }
                .getOrDefault(0)
            val savedTotal = runCatching { ProgressStore.getPracticeTotal(this@PracticeActivity) }
                .getOrDefault(PRACTICE_LENGTH)
            val savedIsFocus = runCatching { ProgressStore.getPracticeIsFocus(this@PracticeActivity) }
                .getOrDefault(false)

            when {
                // 本次 intent 明确指定了"错题重做"（内容+年级）→ 单题重做模式。
                !focusContent.isNullOrBlank() -> {
                    totalQuestions = 1
                    focusPending = true
                    focusContentToRestore = focusContent
                    focusGradeToRestore = focusGrade
                }
                // 退出时处于重做模式且未作答 → 按保存的焦点题 id 恢复同一题。
                savedIsFocus && pendingId > 0L -> {
                    totalQuestions = 1
                    focusPending = true
                    focusIdToRestore = pendingId
                }
                // 退出时处于普通模式且当前题未作答 → 恢复同一道题与进度。
                pendingId > 0L -> {
                    totalQuestions = savedTotal.coerceAtLeast(1)
                    answeredCount = savedAnswered.coerceIn(0, totalQuestions)
                    correctCount = savedCorrect.coerceIn(0, totalQuestions)
                    pendingIdToRestore = pendingId
                    resumingPending = true
                }
                // 全新会话。
                else -> {
                    totalQuestions = PRACTICE_LENGTH
                }
            }
            nextQuestion()
        }
    }

    // 会话恢复用字段（lifecycleScope 内赋值，buildUi/nextQuestion 读取）。
    private var focusContentToRestore: String? = null
    private var focusGradeToRestore = 0
    private var focusIdToRestore: Long? = null
    private var pendingIdToRestore: Long? = null

    /** 构建练习界面（从 onCreate 抽出，便于先恢复会话再渲染）。 */
    private fun buildUi() {
        val root = ScrollView(this).apply { setBackgroundColor(getColor(R.color.background)) }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(box)
        setContentView(root)

        box.addView(banner())
        progressLabel = TextView(this).apply {
            textSize = 15f
            paint.isFakeBoldText = true
            setTextColor(getColor(R.color.accent))
        }
        box.addView(progressLabel, margin(12))
        questionLabel = TextView(this).apply {
            textSize = 18f
            setTextColor(getColor(R.color.on_surface))
        }
        box.addView(questionLabel, margin(10))
        optionsBox = android.widget.RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(optionsBox, margin(8))
        fillInput = EditText(this).apply {
            hint = getString(R.string.fill_hint)
            // 深色背景显式白字 + 灰提示（默认黑字不可见）+ 亮绿聚焦边框。
            setTextColor(getColor(R.color.on_surface))
            setHintTextColor(getColor(R.color.text_secondary))
            visibility = android.view.View.GONE
        }
        box.addView(fillInput, margin(8))
        feedbackLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(getColor(R.color.on_surface))
            visibility = android.view.View.GONE
        }
        box.addView(feedbackLabel, margin(10))
        actionButton = Button(this).apply {
            text = getString(R.string.btn_submit)
            styleLime()
        }
        box.addView(actionButton, margin(16))
        box.addView(Button(this).apply {
            text = getString(R.string.practice_done)
            styleLime()
            setOnClickListener { finish() }
        }, margin(6))

        actionButton.setOnClickListener { onAction() }
    }

    /** Kawaii gradient banner with the fox mascot (mockup growth banner). */
    private fun banner(): android.view.View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF39862E.toInt(), 0xFF94D411.toInt(), 0xFFFFB400.toInt())
            ).apply { cornerRadius = dp(22).toFloat() }
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = getString(R.string.practice_title)
            textSize = 20f
            paint.isFakeBoldText = true
            setTextColor(0xFFFFFFFF.toInt())
        })
        textCol.addView(TextView(this).apply {
            text = getString(R.string.practice_sub)
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        })
        banner.addView(textCol, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val mascot = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.mascot_fox)
        }
        banner.addView(mascot, LinearLayout.LayoutParams(dp(52), dp(52)))
        wrap.addView(banner)
        return wrap
    }

    private fun margin(dpV: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(dpV) }

    /** 主页同款亮绿渐变按钮（Material3.Light 默认灰钮在深色页上发灰）。 */
    private fun Button.styleLime() {
        setTextColor(0xFF121212.toInt())
        textSize = 16f
        paint.isFakeBoldText = true
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF9BEA00.toInt(), 0xFFB3EE00.toInt())
        ).apply { cornerRadius = dp(26).toFloat() }
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun nextQuestion() {
        if (answeredCount >= totalQuestions) {
            showSummary()
            return
        }
        submitted = false
        feedbackLabel.visibility = android.view.View.GONE
        actionButton.text = getString(R.string.btn_submit)
        lifecycleScope.launch {
            currentQuestion = when {
                // V2.3：恢复上次退出未作答的同一道题（普通模式）——保持原题。
                pendingIdToRestore != null -> {
                    val id = pendingIdToRestore!!
                    pendingIdToRestore = null
                    bankManager.getQuestionById(id) ?: run {
                        // 原题已从题库移除（如已掌握）→ 退回随机题。
                        bankManager.getRandomQuestionOrFallback(
                            AppConfig.getSelectedGrade(this@PracticeActivity),
                            includeOpen = com.antidip.AiJudge.loadConfig(this@PracticeActivity) != null
                        )
                    }
                }
                // V2.3：恢复重做模式（按保存的焦点题 id）。
                focusIdToRestore != null -> {
                    val id = focusIdToRestore!!
                    focusIdToRestore = null
                    bankManager.getQuestionById(id) ?: run {
                        Toast.makeText(
                            this@PracticeActivity, R.string.redo_not_found, Toast.LENGTH_SHORT
                        ).show()
                        bankManager.getRandomQuestionOrFallback(
                            AppConfig.getSelectedGrade(this@PracticeActivity),
                            includeOpen = com.antidip.AiJudge.loadConfig(this@PracticeActivity) != null
                        )
                    }
                }
                // 本次进入时的"错题重做"（按内容+年级找回原题）。
                focusPending || focusContentToRestore != null -> {
                    focusPending = false
                    val content = focusContentToRestore ?: focusContent
                    val grade = if (focusContentToRestore != null) focusGradeToRestore else focusGrade
                    focusContentToRestore = null
                    val q = content?.let { bankManager.getQuestionByContentGrade(it, grade) }
                    if (q == null) {
                        Toast.makeText(
                            this@PracticeActivity, R.string.redo_not_found, Toast.LENGTH_SHORT
                        ).show()
                        bankManager.getRandomQuestionOrFallback(
                            AppConfig.getSelectedGrade(this@PracticeActivity),
                            includeOpen = com.antidip.AiJudge.loadConfig(this@PracticeActivity) != null
                        )
                    } else {
                        q
                    }
                }
                // 常规：随机抽题。
                else -> bankManager.getRandomQuestionOrFallback(
                    AppConfig.getSelectedGrade(this@PracticeActivity),
                    includeOpen = com.antidip.AiJudge.loadConfig(this@PracticeActivity) != null
                )
            }
            val q = currentQuestion ?: return@launch
            progressLabel.text = getString(
                R.string.practice_progress, answeredCount + 1, totalQuestions
            )
            questionLabel.text = q.content
            renderQuestion(q)
        }
    }

    private fun renderQuestion(q: QuestionEntity) {
        optionsBox.removeAllViews()
        when (q.type) {
            "choice" -> {
                fillInput.visibility = android.view.View.GONE
                val labels = listOf("A", "B", "C", "D")
                val options = q.options?.split("|") ?: emptyList()
                options.forEachIndexed { index, option ->
                    val rb = RadioButton(this).apply {
                        text = "${labels.getOrElse(index) { "$index" }}. $option"
                        tag = labels.getOrElse(index) { "$index" }
                        textSize = 16f
                        // 深色背景显式白字 + 亮绿选择圈（默认黑字不可见）。
                        setTextColor(getColor(R.color.on_surface))
                        buttonTintList = android.content.res.ColorStateList.valueOf(
                            getColor(R.color.accent)
                        )
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    }
                    optionsBox.addView(rb)
                }
            }
            else -> {
                optionsBox.removeAllViews()
                fillInput.visibility = android.view.View.VISIBLE
                fillInput.text?.clear()
                fillInput.hint = getString(
                    if (q.type == "open") R.string.open_hint else R.string.fill_hint
                )
            }
        }
    }

    private fun onAction() {
        val q = currentQuestion ?: return
        if (sessionOver) {
            finish()
            return
        }
        if (!submitted) {
            submit(q)
        } else {
            // V2.3：作答完毕、推进到下一题 → 清除待作答题（原题已作废）。
            answeredCount++
            resumingPending = false
            lifecycleScope.launch {
                ProgressStore.clearPracticePending(this@PracticeActivity)
                ProgressStore.savePracticeSession(
                    this@PracticeActivity, answeredCount, correctCount,
                    totalQuestions, isFocus = totalQuestions == 1
                )
            }
            nextQuestion()
        }
    }

    /**
     * V2.3（用户要求 2026-11-24：退出重来保持原题）：离开页面时若当前题尚未
     * 作答（!submitted），把当前题 id 存为待作答题，下次进入时恢复同一道题。
     * 已作答但未推进的题同样保存（保证"已出题目无法跳过"）。
     */
    override fun onStop() {
        super.onStop()
        val q = currentQuestion
        if (!sessionOver && q != null && q.id > 0) {
            val isFocus = totalQuestions == 1
            val app = applicationContext
            val answered = answeredCount
            val correct = correctCount
            val total = totalQuestions
            // 用独立于生命周期的 scope 落盘：activity 即将 finish 时 lifecycleScope
            // 会在 ON_DESTROY 被取消，导致"退出重进保持原题"失效。
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
            ).launch {
                ProgressStore.savePracticePending(app, q.id)
                ProgressStore.savePracticeSession(app, answered, correct, total, isFocus = isFocus)
                if (isFocus) {
                    ProgressStore.savePracticeFocus(app, q.id)
                } else {
                    ProgressStore.clearPracticeFocus(app)
                }
            }
        }
    }

    private fun submit(q: QuestionEntity) {
        val userAnswer = when (q.type) {
            "choice" -> {
                val checkedId = optionsBox.checkedRadioButtonId
                if (checkedId == -1) return
                val selected = optionsBox.findViewById<RadioButton>(checkedId)
                selected.tag?.toString() ?: ""
            }
            else -> fillInput.text?.toString()?.trim().orEmpty()
        }
        if (userAnswer.isEmpty()) {
            Toast.makeText(this, "请选择或输入答案", Toast.LENGTH_SHORT).show()
            return
        }

        // Pure correctness verdict — practice must not touch the engine's
        // round state machine (attempts/rounds belong to the forced quiz).
        submitted = true

        lifecycleScope.launch {
            var correct = engine.isAnswerCorrect(q, userAnswer)
            // 开放式题（无标准答案，2026-11-21）：判分完全交给 AI；AI 不可用
            // → 本题不计分不扣分，直接进入下一题（练习场景不惩罚）。
            if (q.type == "open") {
                val aiOk = try {
                    com.antidip.AiJudge.judgeOpen(this@PracticeActivity, q, userAnswer)
                } catch (e: Exception) {
                    null
                }
                if (aiOk == null) {
                    feedbackLabel.visibility = android.view.View.VISIBLE
                    feedbackLabel.setTextColor(getColor(R.color.text_secondary))
                    feedbackLabel.text = "AI 判分暂时不可用，本题不计分"
                    actionButton.text = getString(R.string.practice_next)
                    return@launch
                }
                correct = aiOk
            } else {
                // AI 判题（V2.2，2026-11-21）：填空字面不一致时问 AI 语义是否
                // 等价；fail-closed，未启用/失败按原字面判分。
                if (!correct && q.type == "fill" &&
                    userAnswer != q.answer.trim() &&
                    com.antidip.AiJudge.loadConfig(this@PracticeActivity) != null
                ) {
                    if (com.antidip.AiJudge.judgeFill(this@PracticeActivity, q, userAnswer) == true) {
                        correct = true
                    }
                }
            }
            val (state, newMedals) =
                ProgressStore.applyAnswer(
                    this@PracticeActivity, correct, q.type == "fill", question = q
                )
            if (correct) {
                correctCount++
                // V2.5.1：标记该题曾答对过（科目过半徽章/无所不知成就）
                bankManager.markAnsweredCorrect(q.id)
            }
            if (!correct && q.type == "fill") {
                ProgressStore.addWrongEntry(
                    this@PracticeActivity,
                    ProgressRules.WrongEntry(
                        content = q.content,
                        // 2026-11-21（用户要求）：选择题写"结果"（选项内容）而非字母。
                        answer = ProgressRules.answerResultText(
                            q.answer,
                            q.options?.split("|") ?: emptyList()
                        ),
                        subject = q.subject,
                        grade = q.grade,
                        wrongCount = 1,
                        lastWrongAt = System.currentTimeMillis()
                    )
                )
            }
            feedbackLabel.visibility = android.view.View.VISIBLE
            feedbackLabel.text = if (correct) {
                getString(R.string.answer_correct)
            } else {
                getString(R.string.fill_explain, ProgressRules.answerResultText(
                    q.answer, q.options?.split("|") ?: emptyList()
                ))
            }
            feedbackLabel.setTextColor(
                getColor(if (correct) R.color.success else R.color.error)
            )
            progressLabel.text = getString(R.string.progress_line, state.points, state.streak)
            actionButton.text = getString(R.string.practice_next)
            newMedals.forEach { id ->
                val medal = ProgressRules.MEDALS.firstOrNull { it.first == id }
                Toast.makeText(
                    this@PracticeActivity,
                    getString(R.string.medal_earned, medal?.second ?: "🏅", medal?.third ?: id),
                    Toast.LENGTH_LONG
                ).show()
            }
            if (answeredCount + 1 >= totalQuestions) {
                // last question: the next "next" press shows the summary
                actionButton.text = getString(R.string.practice_summary_btn)
            }
        }
    }

    private fun showSummary() {
        sessionOver = true
        questionLabel.text = getString(
            R.string.practice_summary, answeredCount, correctCount, correctCount
        )
        optionsBox.removeAllViews()
        fillInput.visibility = android.view.View.GONE
        feedbackLabel.visibility = android.view.View.GONE
        progressLabel.visibility = android.view.View.GONE
        actionButton.text = getString(R.string.practice_done)
        // V2.3：会话结束 → 清除练习会话态，下次进入开新会话。
        lifecycleScope.launch {
            ProgressStore.clearPracticePending(this@PracticeActivity)
            ProgressStore.clearPracticeFocus(this@PracticeActivity)
            ProgressStore.savePracticeSession(
                this@PracticeActivity, 0, 0, PRACTICE_LENGTH, isFocus = false
            )
        }
    }

    private companion object {
        /** Questions per practice session. */
        const val PRACTICE_LENGTH = 10
    }
}
