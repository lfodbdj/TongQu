package com.antidip

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.antidip.db.QuizDatabase

class AntiAddictionApp : Application() {

    val database: QuizDatabase by lazy { QuizDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.antidip.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(com.antidip.R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "anti_addiction_service"
        const val QUIZ_NOTIFICATION_ID = 1

        @Volatile
        var instance: AntiAddictionApp? = null
            private set
    }
}
