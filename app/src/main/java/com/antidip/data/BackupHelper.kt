package com.antidip.data

import android.content.Context
import android.util.Log
import com.antidip.Constants
import com.antidip.ProgressRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * V2.2 user profile backup/restore (requirement 2026-11-10): the child's
 * growth state (nickname, points, streak, medals, wrong-question book) can be
 * exported as a JSON file named after the NICKNAME, so switching devices can
 * restore it. Backups go to the app external dir AND — best-effort — are
 * HTTP-PUT to the configured quiz-bank server (a PUT-capable server is
 * documented in docs/server_put_server.py; plain `python -m http.server`
 * ignores PUT, in which case the file stays local and can be copied to the
 * server folder manually). Restore pulls `{nickname}.json` from the server
 * or reads a local backup file.
 */
object BackupHelper {

    private const val TAG = "BackupHelper"

    data class Profile(
        val nickname: String,
        val points: Int,
        val streak: Int,
        val bestStreak: Int,
        val medals: Set<String>,
        val wrongBook: List<ProgressRules.WrongEntry>
    )

    suspend fun currentProfile(context: Context, nickname: String): Profile {
        val st = ProgressStore.getState(context)
        return Profile(
            nickname = nickname,
            points = st.points,
            streak = st.streak,
            bestStreak = st.bestStreak,
            medals = st.medals,
            wrongBook = ProgressStore.getWrongBook(context)
        )
    }

    fun toJson(p: Profile): String {
        val root = JSONObject()
        root.put("app", "tongqu")
        root.put("version", 1)
        root.put("nickname", p.nickname)
        root.put("points", p.points)
        root.put("streak", p.streak)
        root.put("best_streak", p.bestStreak)
        val medals = JSONArray()
        p.medals.forEach { medals.put(it) }
        root.put("medals", medals)
        val book = JSONArray()
        p.wrongBook.forEach { e ->
            val o = JSONObject()
            o.put("content", e.content)
            o.put("answer", e.answer)
            o.put("subject", e.subject)
            o.put("grade", e.grade)
            o.put("wrong_count", e.wrongCount)
            o.put("last_wrong_at", e.lastWrongAt)
            book.put(o)
        }
        root.put("wrong_book", book)
        return root.toString()
    }

    fun fromJson(raw: String): Profile? {
        return try {
            val root = JSONObject(raw)
            if (root.optString("app") != "tongqu") return null
            val medals = mutableSetOf<String>()
            val medalArr = root.optJSONArray("medals") ?: JSONArray()
            for (i in 0 until medalArr.length()) medals.add(medalArr.optString(i))
            val book = mutableListOf<ProgressRules.WrongEntry>()
            val bookArr = root.optJSONArray("wrong_book") ?: JSONArray()
            for (i in 0 until bookArr.length()) {
                val o = bookArr.optJSONObject(i) ?: continue
                book.add(
                    ProgressRules.WrongEntry(
                        content = o.optString("content"),
                        answer = o.optString("answer"),
                        subject = o.optString("subject", "math"),
                        grade = o.optInt("grade", 4),
                        wrongCount = o.optInt("wrong_count", 1),
                        lastWrongAt = o.optLong("last_wrong_at", 0L)
                    )
                )
            }
            Profile(
                nickname = root.optString("nickname"),
                points = root.optInt("points", 0),
                streak = root.optInt("streak", 0),
                bestStreak = root.optInt("best_streak", 0),
                medals = medals,
                wrongBook = book
            )
        } catch (e: Exception) {
            Log.w(TAG, "Backup JSON parse failed", e)
            null
        }
    }

    fun backupDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "backup").apply { mkdirs() }
    }

    /** Writes `{nickname}_backup.json` locally; returns the file or null. */
    fun saveLocal(context: Context, p: Profile): File? {
        return try {
            val safe = p.nickname.ifBlank { "child" }.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5_-]"), "_")
            val f = File(backupDir(context), "${safe}_backup.json")
            f.writeText(toJson(p), Charsets.UTF_8)
            Log.d(TAG, "Local backup saved: ${f.absolutePath}")
            f
        } catch (e: Exception) {
            Log.w(TAG, "Local backup failed", e)
            null
        }
    }

    /**
     * Best-effort HTTP PUT of the backup to the quiz server as
     * `{nickname}.json`. Plain `python -m http.server` rejects PUT (501) —
     * that is NOT an error for us: the local copy remains, and the docs
     * describe the one-line PUT-capable server.
     */
    suspend fun putToServer(context: Context, p: Profile): String = withContext(Dispatchers.IO) {
        val safe = p.nickname.ifBlank { "child" }.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5_-]"), "_")
        val urlStr = prefs(context).getString(Constants.KEY_QUIZ_DOWNLOAD_URL, null)
            ?.takeIf { it.isNotBlank() } ?: Constants.DEFAULT_QUIZ_URL
        // .../questions.json -> .../{safe}.json
        val base = urlStr.substringBeforeLast('/')
        val target = "$base/${safe}.json"
        var connection: HttpURLConnection? = null
        try {
            val body = toJson(p).toByteArray(Charsets.UTF_8)
            val url = URL(target)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code in 200..299) "已上传到题库服务器：$target"
            else "服务器返回 $code（普通 http.server 不支持上传）——本地备份已保存，可手动复制到服务器目录"
        } catch (e: Exception) {
            Log.w(TAG, "PUT backup failed", e)
            "上传失败（${e.message}）——本地备份已保存，可手动复制到服务器目录"
        } finally {
            connection?.disconnect()
        }
    }

    /** Downloads `{nickname}.json` from the configured quiz server. */
    suspend fun fetchFromServer(context: Context, nickname: String): Result<Profile> =
        withContext(Dispatchers.IO) {
            val safe = nickname.ifBlank { "child" }.replace(Regex("[^0-9A-Za-z\\u4e00-\\u9fa5_-]"), "_")
            val urlStr = prefs(context).getString(Constants.KEY_QUIZ_DOWNLOAD_URL, null)
                ?.takeIf { it.isNotBlank() } ?: Constants.DEFAULT_QUIZ_URL
            val base = urlStr.substringBeforeLast('/')
            val target = "$base/${safe}.json"
            var connection: HttpURLConnection? = null
            try {
                val url = URL(target)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(Exception("服务器无此备份（HTTP $code）"))
                }
                val raw = connection.inputStream.use { ins ->
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).readText()
                }
                fromJson(raw)?.let { Result.success(it) }
                    ?: Result.failure(Exception("备份文件格式不正确"))
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                connection?.disconnect()
            }
        }

    /** Restores a profile into ProgressStore (nickname too). */
    suspend fun restore(context: Context, p: Profile) {
        setNickname(context, p.nickname)
        ProgressStore.restoreState(context, p.points, p.streak, p.bestStreak, p.medals)
        ProgressStore.replaceWrongBook(context, p.wrongBook)
    }

    fun getNickname(context: Context): String =
        prefs(context).getString(Constants.KEY_USER_NICKNAME, null).orEmpty()

    fun setNickname(context: Context, nickname: String) {
        prefs(context).edit()
            .putString(Constants.KEY_USER_NICKNAME, nickname.trim().take(20))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
