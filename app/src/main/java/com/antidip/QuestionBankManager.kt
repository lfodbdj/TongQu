package com.antidip

import android.content.Context
import android.util.Log
import com.antidip.db.QuestionDao
import com.antidip.db.QuestionEntity
import com.antidip.db.QuizDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class QuestionBankManager(private val context: Context) {

    private val database: QuizDatabase = QuizDatabase.getInstance(context)
    private val dao: QuestionDao = database.questionDao()

    companion object {
        private const val TAG = "QuestionBankManager"
        const val IMPORT_DIR = "AntiAddiction/questions"
        private const val DEFAULT_GRADE = 4

        // Subject name mapping
        val SUBJECT_NAMES = mapOf(
            "chinese" to "语文",
            "math" to "数学",
            "english" to "英语",
            "science" to "科学",
            "moral" to "道法"
        )

        /**
         * Stable fingerprint for a mastered question (M3 x B14).
         * String.hashCode() is defined by the Java/Kotlin language spec, so the
         * value is identical across processes and reboots. A Room row id cannot
         * be used here because the row is physically deleted on mastery.
         */
        private fun fingerprint(content: String, grade: Int): String =
            "$grade|${content.hashCode()}"
    }

    /**
     * Import built-in questions from assets.
     *
     * Idempotent (M3): this method scans the assets on EVERY launch and relies
     * on the Room unique (content, grade) index plus OnConflictStrategy.IGNORE
     * to skip rows already in the bank. There is deliberately NO "database
     * already has rows -> return" guard: with such a guard, a single successful
     * asset on the first run permanently blocked the remaining (previously
     * failing) assets from ever being retried. New assets and previously failed
     * files are therefore picked up on a later launch.
     *
     * Returns the number of rows ACTUALLY inserted on this call. A return value
     * of 0 means "everything is already known", NOT an import failure; the
     * caller (AppInitializer) only logs it.
     */
    suspend fun importBuiltInQuestions(): Int = withContext(Dispatchers.IO) {
        try {
            val assetManager = context.assets
            val files = assetManager.list("")?.filter { it.startsWith("questions_") && it.endsWith(".json") } ?: emptyList()

            var totalImported = 0
            for (file in files) {
                // Isolate failures per asset (D08): a single malformed JSON must
                // not abort the whole batch, so the remaining assets are still
                // processed on this launch and retried on the next one.
                // Streams are closed deterministically.
                try {
                    val jsonStr = assetManager.open(file).use { ins ->
                        BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use {
                            it.readText()
                        }
                    }
                    totalImported += parseAndImport(jsonStr)
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping malformed built-in asset: $file", e)
                    continue
                }
            }

            Log.d(TAG, "Imported $totalImported new built-in questions (idempotent scan)")
            totalImported
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import built-in questions", e)
            0
        }
    }

    /**
     * Import questions from a JSON string (from downloaded file or local file).
     *
     * Returns the number of rows ACTUALLY inserted (duplicates ignored by the
     * unique (content, grade) index are not counted); 0 means "no new rows",
     * which is a successful no-op, NOT a failure. Returns -1 on a parse error.
     */
    suspend fun importFromJson(jsonString: String): Int = withContext(Dispatchers.IO) {
        try {
            parseAndImport(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import questions", e)
            -1
        }
    }

    /**
     * Import questions from local directory.
     * Looks for JSON files in /sdcard/AntiAddiction/questions/
     *
     * Returns the number of rows ACTUALLY inserted (idempotent: duplicates are
     * ignored via the unique (content, grade) index); 0 is a successful no-op.
     * Returns -1 on a directory/IO error.
     */
    suspend fun importFromLocalDirectory(): Int = withContext(Dispatchers.IO) {
        try {
            // Use the app-specific external directory so it works on Android 11+
            // (Scoped Storage) without READ/WRITE_EXTERNAL_STORAGE (task B8).
            val base = context.getExternalFilesDir(null)
            if (base == null) {
                Log.e(TAG, "External files directory unavailable")
                return@withContext 0
            }
            val dir = File(base, IMPORT_DIR)
            if (!dir.exists()) {
                Log.d(TAG, "Import directory does not exist: ${dir.absolutePath}")
                return@withContext 0
            }

            val jsonFiles = dir.listFiles { file -> file.extension == "json" } ?: emptyArray()
            var totalImported = 0

            for (file in jsonFiles) {
                val jsonStr = file.readText(Charsets.UTF_8)
                totalImported += parseAndImport(jsonStr)
                // Move processed file to .processed subfolder
                val processedDir = File(dir, ".processed")
                processedDir.mkdirs()
                file.renameTo(File(processedDir, file.name))
            }

            Log.d(TAG, "Imported $totalImported questions from local directory")
            totalImported
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import from local directory", e)
            -1
        }
    }

    /**
     * Parse JSON string and import questions into database.
     *
     * Supports two formats:
     * 1. Wrapped object: {"grade": 4, "version": "2026.11", "questions": [...]}
     * 2. Plain array: [{...}, {...}]  (grade defaults to 4)
     */
    private suspend fun parseAndImport(jsonString: String): Int {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty()) {
            Log.w(TAG, "Empty JSON content, skipping")
            return 0
        }

        val grade: Int
        val questionsArray: JSONArray

        if (trimmed.startsWith("[")) {
            // Plain array format
            questionsArray = JSONArray(trimmed)
            grade = DEFAULT_GRADE
        } else if (trimmed.startsWith("{")) {
            // Wrapped object format
            val root = JSONObject(trimmed)
            grade = root.optInt("grade", DEFAULT_GRADE)
            questionsArray = root.optJSONArray("questions") ?: JSONArray()
        } else {
            throw IllegalArgumentException("Unsupported JSON format: must start with '[' or '{'")
        }

        // M3 x B14: read the persisted mastered fingerprints ONCE per import.
        // A mastered question was physically DELETEd, so the Room unique
        // (content, grade) index no longer sees it and every cold-start
        // re-import would resurrect it, undoing the user's "answered correctly
        // twice -> remove" decision. All three import paths (built-in assets,
        // downloaded JSON, local directory) funnel through this method.
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val mastered: Set<String> =
            prefs.getStringSet(Constants.KEY_MASTERED_FINGERPRINTS, emptySet()) ?: emptySet()
        val entities = mutableListOf<QuestionEntity>()

        for (i in 0 until questionsArray.length()) {
            val q = questionsArray.optJSONObject(i) ?: continue
            try {
                val entity = buildEntity(q, grade)
                if (fingerprint(entity.content, entity.grade) !in mastered) {
                    entities.add(entity)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Skipped malformed question at index $i: ${e.message}")
            }
        }

        if (entities.isEmpty()) {
            Log.w(TAG, "No valid questions parsed")
            return 0
        }

        // A25: insertAll() returns the generated row ids, with -1 for rows
        // ignored by the unique (content, grade) index. Counting only the
        // non-(-1) values gives the REAL number of newly inserted questions;
        // returning entities.size would over-report on every re-import.
        val inserted = dao.insertAll(entities).count { it != -1L }
        Log.d(TAG, "Imported $inserted new questions (of ${entities.size} parsed, grade $grade)")
        return inserted
    }

    /**
     * Build a QuestionEntity from a single JSON object.
     * Throws on missing required fields so the caller can skip bad entries.
     */
    private fun buildEntity(q: JSONObject, defaultGrade: Int): QuestionEntity {
        val type = q.optString("type", "choice")
        val options = if (type == "choice") {
            val optArray = q.optJSONArray("options")
            if (optArray == null || optArray.length() == 0) {
                throw IllegalArgumentException("choice question missing options")
            }
            val optList = mutableListOf<String>()
            for (j in 0 until optArray.length()) {
                optList.add(optArray.optString(j, ""))
            }
            optList.joinToString("|")  // Pipe-separated storage
        } else {
            null
        }

        return QuestionEntity(
            subject = q.getString("subject"),
            grade = q.optInt("grade", defaultGrade),
            type = type,
            content = q.getString("content"),
            options = options,
            answer = q.getString("answer"),
            difficulty = q.optInt("difficulty", 1)
        )
    }

    /**
     * Get total question count.
     */
    suspend fun getTotalCount(): Int = dao.getTotalCount()

    /**
     * Batch 3 (A12): fetch a question by row id, used by QuizActivity to
     * restore an interrupted round (saved id in the instance state).
     */
    suspend fun getQuestionById(id: Long): QuestionEntity? = dao.getById(id)

    /**
     * Get all subject stats.
     */
    suspend fun getSubjectStats(): Map<String, Int> {
        val stats = dao.getStatsBySubject()
        return stats.associate { it.subject to it.count }
    }

    /**
     * Batch 2-c: real per-grade row counts, used by the parent console to show
     * how many questions each grade actually has. Built-in assets currently
     * contain only grade 4, so the UI must show 0 for the other grades.
     */
    suspend fun getGradeCounts(): Map<Int, Int> =
        dao.getGradeCounts().associate { it.grade to it.count }

    /**
     * Get a random question, avoiding recently asked.
     */
    suspend fun getRandomQuestion(): QuestionEntity? {
        var question = dao.getRandomQuestionAvoidingRecent()
        if (question == null) {
            // Fallback: if not enough questions to avoid recent, get any random
            question = dao.getRandomQuestion()
        }
        // Update last asked time
        question?.let {
            dao.updateLastAsked(it.id, System.currentTimeMillis())
        }
        return question
    }

    /**
     * A04 + batch 2-c: never let an empty/exhausted question bank release the
     * child, and honour the parent-selected grade.
     *
     * Three-level fallback (deliberately NO avoidingRecent on a grade subset —
     * a grade with <=50 questions would make `NOT IN (recent 50)` degenerate):
     *   1. the selected grade's own random question;
     *   2. any real question from the whole bank (a 0-question grade falls back
     *      to REAL questions, never to the in-memory stub);
     *   3. only when the WHOLE bank is empty, the in-memory fallback built with
     *      named arguments. It is NOT inserted into the database (id stays 0),
     *      so it cannot violate the unique (content, grade) index and B14
     *      bookkeeping skips it via the `q.id > 0` guard in QuizActivity.
     *
     * The default [grade] keeps existing callers (and the 2-b tests) compiling.
     */
    suspend fun getRandomQuestionOrFallback(
        grade: Int = Constants.ALL_GRADES
    ): QuestionEntity {
        // V2.0 (requirement 2026-11-10: 确认年级选择后出题对应年级题库):
        // request + resolution are logged so the parent can verify on-device
        // that the console grade selection drives the question bank.
        Log.d(TAG, "Question request for grade=$grade")
        if (grade != Constants.ALL_GRADES) {
            val byGrade = dao.getRandomQuestionByGrade(grade)
            if (byGrade != null) {
                dao.updateLastAsked(byGrade.id, System.currentTimeMillis())
                Log.d(TAG, "Grade-scoped question served (grade=${byGrade.grade})")
                return byGrade
            }
            Log.d(TAG, "No questions for grade $grade, falling back to all grades")
        }
        return getRandomQuestion() ?: QuestionEntity(
            subject = "math",
            grade = 4,
            type = "fill",
            content = "请计算：1 + 1 = ？（请输入数字）",
            options = null,
            answer = "2",
            difficulty = 1
        )
    }

    /**
     * Get the import directory path for display.
     */
    fun getImportDirPath(): String {
        val base = context.getExternalFilesDir(null)
        return if (base != null) File(base, IMPORT_DIR).absolutePath else ""
    }

    // ------------------------------------------------------------------
    // B14: "answered correctly twice in a row -> remove from bank"
    // ------------------------------------------------------------------

    /**
     * Increment the consecutive-correct counter for [id] and return the new value.
     * Returns 0 if the question no longer exists (e.g. already removed).
     */
    suspend fun incrementConsecutiveCorrect(id: Long): Int {
        val entity = dao.getById(id) ?: return 0
        val n = entity.consecutiveCorrect + 1
        dao.setConsecutiveCorrect(id, n)
        return n
    }

    /**
     * Reset the consecutive-correct counter for [id] back to 0 (e.g. on a wrong answer).
     */
    suspend fun resetConsecutiveCorrect(id: Long) {
        dao.setConsecutiveCorrect(id, 0)
    }

    /**
     * Remove a question from the bank entirely (mastered).
     */
    suspend fun removeQuestion(id: Long) {
        dao.deleteById(id)
    }

    /**
     * A04: delete a mastered question ONLY while the bank stays above the
     * configured minimum. At or below [Constants.MIN_QUESTION_BANK_SIZE] we
     * keep the row and just reset its consecutive-correct streak, so repeated
     * correct answers can never shrink the bank to zero and open the "empty
     * bank -> child is free" bypass.
     *
     * M3 x B14: before the physical delete we persist a stable fingerprint so
     * the idempotent cold-start re-import cannot resurrect this question. The
     * below-minimum branch keeps the row and therefore writes NO fingerprint.
     */
    suspend fun removeQuestionIfAboveMinimum(id: Long) {
        if (dao.getTotalCount() > Constants.MIN_QUESTION_BANK_SIZE) {
            val entity = dao.getById(id) ?: return
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            // getStringSet() returns SharedPreferences' own internal set: copy
            // it before mutating, otherwise the change is invisible to the
            // in-memory map and would never be persisted reliably.
            val fingerprints = HashSet<String>(
                prefs.getStringSet(Constants.KEY_MASTERED_FINGERPRINTS, emptySet()) ?: emptySet()
            )
            fingerprints.add(fingerprint(entity.content, entity.grade))
            prefs.edit()
                .putStringSet(Constants.KEY_MASTERED_FINGERPRINTS, fingerprints)
                .apply()
            dao.deleteById(id)
        } else {
            dao.setConsecutiveCorrect(id, 0)
        }
    }
}
