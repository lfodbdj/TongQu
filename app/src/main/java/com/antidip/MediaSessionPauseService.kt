package com.antidip

import android.service.notification.NotificationListenerService

/**
 * V2.2 requirement (2026-11-10): while the quiz is up, background playback
 * (short-video apps, ads) must actually STOP, not just be muted. The reliable
 * way to pause another app's player is its MediaSession transport controls,
 * and `MediaSessionManager.getActiveSessions()` requires this service to be
 * granted "notification access" (通知使用权) by the parent — a one-time toggle
 * in system settings (guided from the settings page).
 *
 * The service itself does nothing: its existence + the grant are what unlock
 * session access for [MediaPauseHelper].
 */
class MediaSessionPauseService : NotificationListenerService()
