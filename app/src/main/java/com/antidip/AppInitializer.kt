package com.antidip

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        Log.d(TAG, "AppInitializer: importing built-in questions")
        val manager = QuestionBankManager(context)

        CoroutineScope(Dispatchers.IO).launch {
            val count = manager.importBuiltInQuestions()
            Log.d(TAG, "Built-in questions imported: $count")

            // Batch 3 (A08): wire the README-promised boot-time scan of the
            // local import directory (Android/data/com.antidip/files/
            // AntiAddiction/questions). Duplicates are ignored by the unique
            // (content, grade) index and processed files move to .processed,
            // so this is idempotent. The settings page button (#A08) can run
            // the same import on demand without a reboot.
            val local = manager.importFromLocalDirectory()
            Log.d(TAG, "Local directory import: $local")
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    companion object {
        private const val TAG = "AppInitializer"
    }
}
