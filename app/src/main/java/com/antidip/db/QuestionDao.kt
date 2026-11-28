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

    /**
     * 2026-11-21 开放式题库：type = 'open' 的题目没有标准答案，全部由 AI
     * 判分。[includeOpen] = false（AI 未配置）时从随机池中屏蔽开放题。
     */
    @Query(
        "SELECT * FROM questions " +
            "WHERE (:includeOpen = 1 OR type != 'open') " +
            "ORDER BY RANDOM() LIMIT 1"
    )
    suspend fun getRandomQuestion(includeOpen: Boolean): QuestionEntity?

    /** Get a random question, avoiding recently asked ones (within last 50). */
    @Query(
        "SELECT * FROM questions " +
            "WHERE (:includeOpen = 1 OR type != 'open') AND id NOT IN (" +
            "SELECT id FROM questions " +
            "WHERE (:includeOpen = 1 OR type != 'open') " +
            "ORDER BY last_asked DESC LIMIT 50" +
            ") ORDER BY RANDOM() LIMIT 1"
    )
    suspend fun getRandomQuestionAvoidingRecent(includeOpen: Boolean): QuestionEntity?

    @Query("UPDATE questions SET last_asked = :timestamp WHERE id = :id")
    suspend fun updateLastAsked(id: Long, timestamp: Long)

    @Query("SELECT subject, COUNT(*) as count FROM questions GROUP BY subject")
    suspend fun getStatsBySubject(): List<SubjectStats>

    // ------------------------------------------------------------------
    // Batch 2-c: grade-scoped quiz bank
    // ------------------------------------------------------------------

    @Query(
        "SELECT * FROM questions " +
            "WHERE grade = :grade AND (:includeOpen = 1 OR type != 'open') " +
            "ORDER BY RANDOM() LIMIT 1"
    )
    suspend fun getRandomQuestionByGrade(grade: Int, includeOpen: Boolean): QuestionEntity?

    @Query("SELECT COUNT(*) FROM questions WHERE grade = :grade")
    suspend fun getCountByGrade(grade: Int): Int

    /** Real per-grade row counts for the parent-console grade spinner. */
    @Query("SELECT grade, COUNT(*) AS count FROM questions GROUP BY grade ORDER BY grade")
    suspend fun getGradeCounts(): List<GradeCount>

    /** 错题"重做这道题"：按内容 + 年级定位原题（WrongEntry 不带 id）。 */
    @Query(
        "SELECT * FROM questions WHERE content = :content AND grade = :grade LIMIT 1"
    )
    suspend fun getByContentAndGrade(content: String, grade: Int): QuestionEntity?

    /** V2.5 本年级各科目的题量（>0 的科目才展示"科目完成徽章"）。 */
    @Query(
        "SELECT subject, COUNT(*) AS count FROM questions " +
            "WHERE grade = :grade GROUP BY subject"
    )
    suspend fun getGradeSubjectCounts(grade: Int): List<SubjectCount>

    /** V2.5.1 标记某题曾答对过（幂等，不随答错重置）。 */
    @Query("UPDATE questions SET answered_correct = 1 WHERE id = :id")
    suspend fun markAnsweredCorrect(id: Long)

    /** V2.5.1 本年级各科目「曾答对过」的题数（科目过半徽章用）。 */
    @Query(
        "SELECT subject, COUNT(*) AS count FROM questions " +
            "WHERE grade = :grade AND answered_correct = 1 GROUP BY subject"
    )
    suspend fun getGradeSubjectCorrectCounts(grade: Int): List<SubjectCount>

    /** V2.5.1 本年级「曾答对过」的题总数（无所不知成就用）。 */
    @Query(
        "SELECT COUNT(*) FROM questions WHERE grade = :grade AND answered_correct = 1"
    )
    suspend fun getGradeAnsweredTotal(grade: Int): Int
}

data class SubjectCount(
    val subject: String,
    val count: Int
)

data class SubjectStats(
    val subject: String,
    val count: Int
)

data class GradeCount(
    val grade: Int,
    val count: Int
)
