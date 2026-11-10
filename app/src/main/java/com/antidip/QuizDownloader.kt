package com.antidip

import android.content.Context
import android.util.Log
import com.antidip.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class QuizDownloader(private val context: Context) {

    companion object {
        private const val TAG = "QuizDownloader"
        private const val CONNECT_TIMEOUT = 10000
        private const val READ_TIMEOUT = 30000
    }

    private val questionBankManager = QuestionBankManager(context)

    /**
     * Download quiz from the configured URL.
     * Returns the number of imported questions, or -1 on error.
     */
    suspend fun downloadQuiz(urlString: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        val effectiveUrl = urlString ?: getSavedUrl() ?: Constants.DEFAULT_QUIZ_URL
        // M4: never log the full URL/query — it may contain credentials. Log
        // only the host, and degrade safely when the URL cannot be parsed.
        val hostForLog = runCatching { java.net.URI(effectiveUrl).host }.getOrNull() ?: "invalid"
        Log.d(TAG, "Downloading quiz from host: $hostForLog")

        // Keep URL construction and the HttpURLConnection cast inside try so
        // MalformedURLException / ClassCastException are wrapped into a failed
        // Result instead of escaping to the caller's coroutine (D09). The
        // connection is tracked in an outer var so finally always disconnects
        // it, on every exit path (B12 #M).
        var connection: HttpURLConnection? = null
        try {
            // M4 + batch 3 (A30): reject malformed/hostless/credential-bearing
            // URLs BEFORE any connection is created, and enforce the transport
            // policy: plaintext HTTP only for local/private hosts, HTTPS for
            // anything public. connection stays null, so the finally block's
            // safe call is a no-op and cannot NPE.
            if (!UrlValidator.isAcceptable(effectiveUrl)) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "Unacceptable quiz URL: public http is not allowed (use https), " +
                            "http is limited to private/local hosts"
                    )
                )
            }
            val url = URL(effectiveUrl)
            val conn = url.openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.requestMethod = "GET"

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                // Release the error body before disconnecting (D09). L2:
                // swallow any IOException from close() so the HTTP error
                // Result below is returned instead of a misleading IO failure.
                runCatching { conn.errorStream?.close() }
                return@withContext Result.failure(Exception("HTTP error: $responseCode"))
            }

            // Stream is closed by `use {}`.
            val jsonStr = conn.inputStream.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).readText()
            }

            val count = questionBankManager.importFromJson(jsonStr)
            if (count >= 0) {
                Result.success(count)
            } else {
                Result.failure(Exception("Failed to parse quiz file"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun getSavedUrl(): String? {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(Constants.KEY_QUIZ_DOWNLOAD_URL, null)?.takeIf { it.isNotBlank() }
    }
}
