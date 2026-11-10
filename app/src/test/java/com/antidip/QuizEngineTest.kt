package com.antidip

import com.antidip.db.QuestionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [QuizEngine] — the pure-logic answer-state machine.
 *
 * [QuizEngine] is a pure-Kotlin domain class with no Android framework
 * dependency, so the entire state machine runs on the standard JVM via the
 * `testDebugUnitTest` task (no Robolectric / mocked android.jar needed).
 *
 * Run with `./gradlew testDebugUnitTest` (Gradle 8.5 wrapper).
 */
class QuizEngineTest {

    private lateinit var engine: QuizEngine

    private val choiceQ = QuestionEntity(
        subject = "math", grade = 4, type = "choice",
        content = "1+1=?", options = "A|2|B|3", answer = "A"
    )
    private val fillQ = QuestionEntity(
        subject = "chinese", grade = 4, type = "fill",
        content = "填空：你好", options = null, answer = "你好"
    )
    private val wrongAnswerForChoice = "B"
    private val wrongAnswerForFill = "再见"

    @Before
    fun setup() {
        engine = QuizEngine()
    }

    @Test
    fun startRound_resetsRoundStateButKeepsFailureStreak() {
        engine.consecutiveFailures = 3
        engine.startRound(choiceQ)

        assertEquals(0, engine.currentQuestionIndex)
        assertEquals(Constants.MAX_ATTEMPTS, engine.currentAttemptsLeft)
        assertEquals(3, engine.consecutiveFailures) // startRound must NOT clear the streak
        assertTrue(engine.isRoundActive)
        assertFalse(engine.isLocked)
        assertEquals(choiceQ, engine.getCurrentQuestion())
    }

    @Test
    fun correctChoice_answerIsCaseInsensitive() {
        engine.startRound(choiceQ)
        assertTrue(engine.submitAnswer("a") is AnswerResult.Correct)
        assertEquals(0, engine.consecutiveFailures)
        assertFalse(engine.isRoundActive)
    }

    @Test
    fun correctFill_matchesAfterNormalization() {
        engine.startRound(fillQ)
        // Extra whitespace + full-width punctuation must still match.
        assertTrue(engine.submitAnswer("你 好。") is AnswerResult.Correct)
    }

    @Test
    fun wrongAnswer_decrementsAttemptsUntilExhausted() {
        engine.startRound(choiceQ)
        var result = engine.submitAnswer(wrongAnswerForChoice)
        assertTrue(result is AnswerResult.Wrong)
        assertEquals(Constants.MAX_ATTEMPTS - 1, engine.currentAttemptsLeft)

        result = engine.submitAnswer(wrongAnswerForChoice)
        assertTrue(result is AnswerResult.Wrong)
        assertEquals(Constants.MAX_ATTEMPTS - 2, engine.currentAttemptsLeft)

        // Third wrong on Q1 exhausts it and advances to Q2. Q2 has not been
        // loaded yet, so the engine asks the caller to supply it.
        result = engine.submitAnswer(wrongAnswerForChoice)
        assertTrue(result is AnswerResult.NeedsNextQuestion)
        assertEquals(1, engine.currentQuestionIndex)
        assertEquals(Constants.QUESTION_2_DELAY_SECONDS, engine.getDelaySeconds())
    }

    @Test
    fun needsNextQuestion_whenNextNotLoaded() {
        engine.startRound(choiceQ)
        // Exhaust Q1 (3 wrong answers). Q2 is not loaded yet, so the engine
        // must ask the caller to supply it.
        var result: AnswerResult = AnswerResult.Wrong(0)
        repeat(Constants.MAX_ATTEMPTS) { result = engine.submitAnswer(wrongAnswerForChoice) }
        assertTrue(result is AnswerResult.NeedsNextQuestion)
        // `result` is a var captured by the repeat lambda, so Kotlin forbids a
        // smart cast here — take an explicitly-typed immutable snapshot instead.
        val q2 = result as AnswerResult.NeedsNextQuestion
        assertEquals(2, q2.questionNumber)
        assertEquals(Constants.QUESTION_2_DELAY_SECONDS, q2.delaySeconds)

        // After supplying Q2, exhausting it must again request Q3 (not loaded).
        engine.addQuestion(choiceQ)
        result = AnswerResult.Wrong(0)
        repeat(Constants.MAX_ATTEMPTS) { result = engine.submitAnswer(wrongAnswerForChoice) }
        assertTrue(result is AnswerResult.NeedsNextQuestion)
        val q3 = result as AnswerResult.NeedsNextQuestion
        assertEquals(3, q3.questionNumber)
        assertEquals(Constants.QUESTION_3_DELAY_SECONDS, q3.delaySeconds)
    }

    @Test
    fun fiveFailedRounds_locksDevice() {
        repeat(5) { round ->
            engine.startRound(choiceQ)
            val result = playRoundToFailure(choiceQ)
            if (round < 4) {
                assertTrue(
                    "round $round should be RoundFailed, was $result",
                    result is AnswerResult.RoundFailed
                )
                assertEquals(round + 1, (result as AnswerResult.RoundFailed).consecutiveFailures)
            } else {
                assertTrue(
                    "5th round should Lock, was $result",
                    result is AnswerResult.Locked
                )
                assertEquals(Constants.LOCK_DURATION_SECONDS, (result as AnswerResult.Locked).durationSeconds)
                assertTrue(engine.isLocked)
            }
        }
    }

    @Test
    fun singleCorrectAnswer_clearsFailureStreak() {
        // Simulate an existing streak, then a correct answer anywhere.
        engine.consecutiveFailures = 4
        engine.startRound(choiceQ)
        // Fail Q1 fully, then on Q2 answer correctly.
        repeat(Constants.MAX_ATTEMPTS) { engine.submitAnswer(wrongAnswerForChoice) }
        engine.addQuestion(choiceQ)
        val correct = engine.submitAnswer(choiceQ.answer)
        assertTrue(correct is AnswerResult.Correct)
        assertEquals(0, engine.consecutiveFailures)
    }

    @Test
    fun resetAfterLock_clearsAllState() {
        engine.isLocked = true
        engine.consecutiveFailures = 5
        engine.currentQuestionIndex = 2
        engine.currentAttemptsLeft = 0
        engine.questions.add(choiceQ)

        engine.resetAfterLock()

        assertFalse(engine.isLocked)
        assertEquals(0, engine.consecutiveFailures)
        assertEquals(0, engine.currentQuestionIndex)
        assertEquals(Constants.MAX_ATTEMPTS, engine.currentAttemptsLeft)
        assertTrue(engine.questions.isEmpty())
    }

    @Test
    fun getDelaySeconds_mapsIndexToConfiguredDelays() {
        engine.startRound(choiceQ) // index 0
        assertEquals(0, engine.getDelaySeconds())
        engine.currentQuestionIndex = 1
        assertEquals(Constants.QUESTION_2_DELAY_SECONDS, engine.getDelaySeconds())
        engine.currentQuestionIndex = 2
        assertEquals(Constants.QUESTION_3_DELAY_SECONDS, engine.getDelaySeconds())
    }

    @Test
    fun submitAnswer_withNoActiveQuestion_returnsError() {
        // brand-new engine, no round started
        assertTrue(engine.submitAnswer("A") is AnswerResult.Error)
        assertNull(engine.getCurrentQuestion())
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Plays the current round to completion by always answering wrong, supplying
     * additional questions whenever the engine signals [AnswerResult.NeedsNextQuestion].
     * Returns the terminal result (RoundFailed or Locked).
     */
    private fun playRoundToFailure(fallback: QuestionEntity): AnswerResult {
        var result: AnswerResult = engine.submitAnswer(wrongAnswerForChoice)
        // Exhaust the very first question (3 wrong) -> NextQuestion/NeedsNextQuestion
        while (result is AnswerResult.Wrong) {
            result = engine.submitAnswer(wrongAnswerForChoice)
        }
        while (result is AnswerResult.NextQuestion || result is AnswerResult.NeedsNextQuestion) {
            if (result is AnswerResult.NeedsNextQuestion) {
                engine.addQuestion(fallback)
            }
            // Exhaust the newly-loaded question.
            result = engine.submitAnswer(wrongAnswerForChoice)
            while (result is AnswerResult.Wrong) {
                result = engine.submitAnswer(wrongAnswerForChoice)
            }
            // result is now NextQuestion/NeedsNextQuestion (more questions) or
            // RoundFailed/Locked (Q3 exhausted).
        }
        return result
    }
}
