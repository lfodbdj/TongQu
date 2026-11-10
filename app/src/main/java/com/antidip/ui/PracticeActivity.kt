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

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bankManager = QuestionBankManager(this)

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
            visibility = android.view.View.GONE
        }
        box.addView(fillInput, margin(8))
        feedbackLabel = TextView(this).apply {
            textSize = 15f
            setTextColor(getColor(R.color.on_surface))
            visibility = android.view.View.GONE
        }
        box.addView(feedbackLabel, margin(10))
        actionButton = Button(this).apply { text = getString(R.string.practice_next) }
        box.addView(actionButton, margin(16))
        box.addView(Button(this).apply {
            text = getString(R.string.practice_done)
            setOnClickListener { finish() }
        }, margin(6))

        actionButton.setOnClickListener { onAction() }
        nextQuestion()
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
                intArrayOf(0xFFFFD3A5.toInt(), 0xFFFFAA6B.toInt())
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

    private fun nextQuestion() {
        if (answeredCount >= PRACTICE_LENGTH) {
            showSummary()
            return
        }
        submitted = false
        feedbackLabel.visibility = android.view.View.GONE
        actionButton.text = getString(R.string.btn_submit)
        lifecycleScope.launch {
            currentQuestion = bankManager.getRandomQuestionOrFallback(
                AppConfig.getSelectedGrade(this@PracticeActivity)
            )
            val q = currentQuestion ?: return@launch
            progressLabel.text = getString(
                R.string.practice_progress, answeredCount + 1, PRACTICE_LENGTH
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
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    }
                    optionsBox.addView(rb)
                }
            }
            else -> {
                optionsBox.removeAllViews()
                fillInput.visibility = android.view.View.VISIBLE
                fillInput.text?.clear()
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
            answeredCount++
            nextQuestion()
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
        val correct = engine.isAnswerCorrect(q, userAnswer)
        submitted = true

        lifecycleScope.launch {
            val (state, newMedals) =
                ProgressStore.applyAnswer(this@PracticeActivity, correct, q.type == "fill")
            if (correct) correctCount++
            if (!correct && q.type == "fill") {
                ProgressStore.addWrongEntry(
                    this@PracticeActivity,
                    ProgressRules.WrongEntry(
                        content = q.content,
                        answer = q.answer,
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
                getString(R.string.fill_explain, q.answer)
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
            if (answeredCount + 1 >= PRACTICE_LENGTH) {
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
    }

    private companion object {
        /** Questions per practice session. */
        const val PRACTICE_LENGTH = 10
    }
}
