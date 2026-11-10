package com.antidip

import com.antidip.db.QuestionEntity

/**
 * Quiz engine managing the question flow and penalty mechanism.
 *
 * Rules:
 * - Each question has 3 attempts
 * - If all 3 attempts fail on question 1, add question 2 (30s delay before answering)
 * - If all 3 attempts fail on question 2, add question 3 (60s delay before answering)
 * - If all 3 attempts fail on question 3, the round counts as failed
 *   (batch 3, A27: the KDoc previously claimed "first attempt", which never
 *   matched [handleQuestionFailed] — the third question gets the same 3 tries)
 * - 5 consecutive failed rounds -> lock phone for 10 minutes
 * - Answering any question correctly resets everything
 *
 * All cross-module constants live in [Constants]; this class only references them.
 *
 * This is a pure-Kotlin domain class: it deliberately has no Android framework
 * dependency (no `android.util.Log`), so the whole state machine runs directly
 * on the JVM in `testDebugUnitTest`. Callers (QuizActivity) own logging.
 */
class QuizEngine {

    // Current round state
    var currentQuestionIndex: Int = 0 // 0-based: 0=Q1, 1=Q2, 2=Q3
    var currentAttemptsLeft: Int = Constants.MAX_ATTEMPTS
    var consecutiveFailures: Int = 0
    var isLocked: Boolean = false
    var questions: MutableList<QuestionEntity> = mutableListOf()
    var isRoundActive: Boolean = false

    /**
     * Start a new round with a question.
     */
    fun startRound(firstQuestion: QuestionEntity) {
        questions.clear()
        questions.add(firstQuestion)
        currentQuestionIndex = 0
        currentAttemptsLeft = Constants.MAX_ATTEMPTS
        isRoundActive = true
        isLocked = false
    }

    /**
     * Get the current question.
     */
    fun getCurrentQuestion(): QuestionEntity? {
        return if (currentQuestionIndex < questions.size) questions[currentQuestionIndex] else null
    }

    /**
     * Get the delay in seconds for the current question.
     * Returns 0 if no delay needed.
     */
    fun getDelaySeconds(): Int {
        return when (currentQuestionIndex) {
            0 -> 0
            1 -> Constants.QUESTION_2_DELAY_SECONDS
            2 -> Constants.QUESTION_3_DELAY_SECONDS
            else -> 0
        }
    }

    /**
     * Submit an answer for the current question.
     * Returns the result of the submission.
     */
    fun submitAnswer(userAnswer: String): AnswerResult {
        val question = getCurrentQuestion() ?: return AnswerResult.Error("No active question")

        val isCorrect = checkAnswer(question, userAnswer)

        if (isCorrect) {
            consecutiveFailures = 0
            isRoundActive = false
            return AnswerResult.Correct
        }

        currentAttemptsLeft--

        // Check if we should add next question
        if (currentAttemptsLeft <= 0) {
            return handleQuestionFailed()
        }

        return AnswerResult.Wrong(attemptsLeft = currentAttemptsLeft)
    }

    /**
     * Handle when all attempts for current question are exhausted.
     */
    private fun handleQuestionFailed(): AnswerResult {
        if (currentQuestionIndex < Constants.MAX_QUESTIONS_PER_ROUND - 1) {
            // Move to next question
            currentQuestionIndex++
            currentAttemptsLeft = Constants.MAX_ATTEMPTS

            val needsNextQuestion = currentQuestionIndex >= questions.size
            if (needsNextQuestion) {
                // Need to load next question (will be done by QuizActivity)
                return AnswerResult.NeedsNextQuestion(
                    questionNumber = currentQuestionIndex + 1,
                    delaySeconds = getDelaySeconds()
                )
            }
            return AnswerResult.NextQuestion(
                question = questions[currentQuestionIndex],
                questionNumber = currentQuestionIndex + 1,
                delaySeconds = getDelaySeconds()
            )
        } else {
            // All 3 questions failed in this round
            consecutiveFailures++

            if (consecutiveFailures >= Constants.MAX_CONSECUTIVE_FAILURES) {
                isLocked = true
                isRoundActive = false
                return AnswerResult.Locked(durationSeconds = Constants.LOCK_DURATION_SECONDS)
            }

            // Start new round with a new question (will be loaded by QuizActivity)
            isRoundActive = false
            return AnswerResult.RoundFailed(consecutiveFailures = consecutiveFailures)
        }
    }

    /**
     * Add a new question to the current round (for NeedsNextQuestion result).
     */
    fun addQuestion(question: QuestionEntity) {
        questions.add(question)
        isRoundActive = true
    }

    /**
     * Called when the question bank cannot supply the next question (A09).
     *
     * The failure counter is owned by the engine itself: previously QuizActivity
     * built `RoundFailed(consecutiveFailures + 1)` by hand, which left the
     * engine, DataStore and UI with three different values. Keeping the
     * increment here makes all three consistent and lets the 5-round lock rule
     * apply to an exhausted bank too.
     *
     * Pure Kotlin by design — no android.util.Log, so JVM unit tests can call
     * it without "Method d not mocked".
     */
    fun onQuestionBankExhausted(): AnswerResult {
        isRoundActive = false
        consecutiveFailures++
        return if (consecutiveFailures >= Constants.MAX_CONSECUTIVE_FAILURES) {
            isLocked = true
            AnswerResult.Locked(durationSeconds = Constants.LOCK_DURATION_SECONDS)
        } else {
            AnswerResult.RoundFailed(consecutiveFailures = consecutiveFailures)
        }
    }

    /**
     * Reset after lock expires.
     */
    fun resetAfterLock() {
        isLocked = false
        consecutiveFailures = 0
        isRoundActive = false
        currentQuestionIndex = 0
        currentAttemptsLeft = Constants.MAX_ATTEMPTS
        questions.clear()
    }

    /**
     * Check if user's answer is correct.
     */
    private fun checkAnswer(question: QuestionEntity, userAnswer: String): Boolean {
        val correctAnswer = question.answer.trim()
        val submitted = userAnswer.trim()

        return when (question.type) {
            "choice" -> {
                // For choice: answer is "A", "B", "C", "D"
                submitted.equals(correctAnswer, ignoreCase = true)
            }
            "fill" -> {
                // For fill: fuzzy match - ignore case, whitespace, punctuation
                normalizeAnswer(submitted) == normalizeAnswer(correctAnswer)
            }
            else -> false
        }
    }

    /**
     * V2.1 practice mode: pure correctness verdict WITHOUT touching the round
     * state machine (practice is one-shot per question and must not consume
     * attempts or advance the round).
     */
    fun isAnswerCorrect(question: QuestionEntity, userAnswer: String): Boolean =
        checkAnswer(question, userAnswer)

    private fun normalizeAnswer(answer: String): String {
        // Ignore case, whitespace AND punctuation for fill-in questions.
        // Full-width CJK punctuation is folded to ASCII first so the single
        // punctuation-removal pass below catches it too.
        val folded = answer.lowercase()
            .replace("，", ",")
            .replace("。", ".")
            .replace("？", "?")
            .replace("！", "!")
            .replace("；", ";")
            .replace("：", ":")
            .replace("、", ",")
        return folded.replace("[\\p{Punct}\\s]".toRegex(), "")
    }
}

/**
 * Result of submitting an answer.
 */
sealed class AnswerResult {
    object Correct : AnswerResult()
    data class Wrong(val attemptsLeft: Int) : AnswerResult()
    data class NextQuestion(
        val question: QuestionEntity,
        val questionNumber: Int,
        val delaySeconds: Int
    ) : AnswerResult()
    data class NeedsNextQuestion(
        val questionNumber: Int,
        val delaySeconds: Int
    ) : AnswerResult()
    data class RoundFailed(val consecutiveFailures: Int) : AnswerResult()
    data class Locked(val durationSeconds: Int) : AnswerResult()
    data class Error(val message: String) : AnswerResult()
}
