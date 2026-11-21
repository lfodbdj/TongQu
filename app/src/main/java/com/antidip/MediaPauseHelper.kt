package com.antidip

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import android.view.KeyEvent

/**
 * Silences AND pauses background media while a quiz is on screen (V2.2
 * requirement 2026-11-10: real-device finding — 红果/快手 keep playing behind
 * the overlay; they ignore audio-focus loss AND the media-key pause, because
 * the media-button session at that moment is not necessarily theirs).
 *
 * Layers, strongest first:
 *  1. MediaSession pause (NEW, V2.2): enumerate every active session via
 *     MediaSessionManager.getActiveSessions (requires the parent to grant
 *     notification access to [MediaSessionPauseService], one-time toggle) and
 *     send transportControls.pause() to each PLAYING session — this actually
 *     PAUSES the player, exactly like the user pressing pause in the app.
 *  2. Global media key PAUSE (legacy best-effort).
 *  3. Audio focus AUDIOFOCUS_GAIN (polite).
 *  4. STREAM_MUSIC volume = 0, re-asserted periodically (guaranteed silence
 *     even when a player ignores everything above).
 *
 * Previous volume is restored on dismiss; paused playback is NOT auto-resumed.
 */
object MediaPauseHelper {

    private const val TAG = "MediaPauseHelper"
    private const val STREAM = AudioManager.STREAM_MUSIC
    private const val SELF_PACKAGE = "com.antidip"
    private const val PREFS = "media_pause_state"
    private const val KEY_MUTED = "muted_by_us"
    private const val KEY_PREV_VOLUME = "previous_volume"

    /** In-memory mirror; the source of truth is the prefs (process deaths must
     *  not leave the device stuck muted — V2.2 device finding: after a
     *  force-stop mid-quiz the in-memory flag was lost, the next pause saved
     *  prev=0 and "restored" silence forever). */
    @Volatile
    private var muted: Boolean = false

    @Volatile
    private var previousVolume: Int = -1

    @Volatile
    private var focusRequest: AudioFocusRequest? = null

    private val legacyListener = AudioManager.OnAudioFocusChangeListener { }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadState(context: Context) {
        muted = prefs(context).getBoolean(KEY_MUTED, false)
        previousVolume = prefs(context).getInt(KEY_PREV_VOLUME, -1)
    }

    private fun saveState(context: Context, mutedNow: Boolean, prev: Int) {
        muted = mutedNow
        previousVolume = prev
        prefs(context).edit()
            .putBoolean(KEY_MUTED, mutedNow)
            .putInt(KEY_PREV_VOLUME, prev)
            .apply()
    }

    /** True when the parent granted notification access (session pause works). */
    fun hasSessionAccess(context: Context): Boolean {
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? MediaSessionManager ?: return false
            val listener = ComponentName(context, MediaSessionPauseService::class.java)
            msm.getActiveSessions(listener) // SecurityException when not granted
            true
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "hasSessionAccess failed", e)
            false
        }
    }

    private fun pauseSessions(context: Context) {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as? MediaSessionManager ?: return
            val listener = ComponentName(context, MediaSessionPauseService::class.java)
            val controllers: List<MediaController> = msm.getActiveSessions(listener)
            var paused = 0
            for (c in controllers) {
                if (c.packageName == SELF_PACKAGE) continue
                val state = c.playbackState?.state ?: continue
                val playing = state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_BUFFERING ||
                    state == PlaybackState.STATE_CONNECTING
                if (playing) {
                    c.transportControls.pause()
                    paused++
                    Log.d(TAG, "Session pause sent to ${c.packageName}")
                }
            }
            if (paused == 0) {
                Log.d(TAG, "No playing media sessions found")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Session pause skipped: notification access not granted")
        } catch (e: Exception) {
            Log.w(TAG, "pauseSessions failed", e)
        }
    }

    /** Mutes media audio, pauses every playing session, takes focus. Idempotent. */
    @Synchronized
    fun pause(context: Context) {
        loadState(context)
        if (muted) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 1) Real pause: every active media session (requires the grant).
            pauseSessions(context)

            // 2) Legacy best-effort: global media key.
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))

            // 3) Polite: exclusive audio focus.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(legacyListener, STREAM, AudioManager.AUDIOFOCUS_GAIN)
            }

            // 4) Guaranteed: mute the media stream entirely. Never remember 0
            // as the "previous" volume — after a mid-quiz process death the
            // stored prev would otherwise restore silence forever (V2.2 fix).
            val current = am.getStreamVolume(STREAM)
            val prev = if (current > 0) current else previousVolume.takeIf { it > 0 } ?: 2
            previousVolume = prev
            am.setStreamVolume(STREAM, 0, 0)
            saveState(context, mutedNow = true, prev = prev)
            Log.d(TAG, "Media silenced (prev volume=$prev)")
        } catch (e: Exception) {
            Log.w(TAG, "pause failed; quiz continues", e)
        }
    }

    /** Re-applies mute + session pause (players may auto-resume mid-quiz). */
    @Synchronized
    fun reassertMute(context: Context) {
        loadState(context)
        if (!muted) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.getStreamVolume(STREAM) != 0) {
                am.setStreamVolume(STREAM, 0, 0)
                Log.d(TAG, "Media mute re-asserted")
            }
            pauseSessions(context)
        } catch (e: Exception) {
            Log.w(TAG, "reassertMute failed", e)
        }
    }

    /** True while this helper is the party that muted the stream. */
    fun isMuting(): Boolean = muted

    /** Restores the previous volume and abandons focus. Idempotent. */
    @Synchronized
    fun resume(context: Context) {
        loadState(context)
        if (!muted) return
        saveState(context, mutedNow = false, prev = -1)
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(legacyListener)
            }
            // A stale/mid-quiz entry can carry prev <= 0 (process death while
            // muted, or a racing second dismiss). Never "restore" silence:
            // fall back to a usable default level instead.
            val prev = if (previousVolume > 0) previousVolume else 3
            am.setStreamVolume(STREAM, prev, 0)
            Log.d(TAG, "Media restored (volume=$prev)")
        } catch (e: Exception) {
            Log.w(TAG, "resume failed", e)
        }
    }
}
