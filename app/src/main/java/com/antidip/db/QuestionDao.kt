package com.antidip.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface QuestionDao {

    /**
     * Returns the generated row ids; rows ignored by the unique
     * (content, grade) index are reported as -1 (A25/M3). Callers must count
     * only non-(-1) values to know the real number of new questions.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(questions: List<QuestionEntity>): List<Long>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: Long): QuestionEntity?

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE questions SET consecutive_correct = :value WHERE id = :id")
    suspend fun setConsecutiveCorrect(id: Long, value: Int)

    @Query("SELECT COUNT(*) FROM questions")
    suspend fun getTotalCount(): Int

    @Query("SELECT * FROM questions ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomQuestion(): QuestionEntity?

    /**
     * Get a random question, avoiding recently asked ones (within last 50 questions).
     */
    @Query("""
        SELECT * FROM questions
        WHERE id NOT IN (
            SELECT id FROM questions ORDER BY last_asked DESC LIMIT 50
        )
        ORDER BY RANDOM() LIMIT 1
    """)
    suspend fun getRandomQuestionAvoidingRecent(): QuestionEntity?

    @Query("UPDATE questions SET last_asked = :timestamp WHERE id = :id")
    suspend fun updateLastAsked(id: Long, timestamp: Long)

    @Query("SELECT subject, COUNT(*) as count FROM questions GROUP BY subject")
    suspend fun getStatsBySubject(): List<SubjectStats>

    // ------------------------------------------------------------------
    // Batch 2-c: grade-scoped quiz bank
    // ------------------------------------------------------------------

    @Query("SELECT * FROM questions WHERE grade = :grade ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomQuestionByGrade(grade: Int): QuestionEntity?

    @Query("SELECT COUNT(*) FROM questions WHERE grade = :grade")
    suspend fun getCountByGrade(grade: Int): Int

    /** Real per-grade row counts for the parent-console grade spinner. */
    @Query("SELECT grade, COUNT(*) AS count FROM questions GROUP BY grade ORDER BY grade")
    suspend fun getGradeCounts(): List<GradeCount>
}

data class SubjectStats(
    val subject: String,
    val count: Int
)

data class GradeCount(
    val grade: Int,
    val count: Int
)
