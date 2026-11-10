package com.antidip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent

/**
 * Silences background media while a quiz is on screen (V2.0 requirement
 * 2026-11-10: real-device finding — a short-video app behind the overlay
 * (红果) and its ads IGNORE audio-focus loss and media-key pauses, so both
 * mechanisms alone left sound playing).
 *
 * The guaranteed layer is STREAM_MUSIC VOLUME = 0 while the quiz shows:
 *  - mute via setStreamVolume(..., 0, 0), remembered previous value,
 *  - re-asserted periodically (the child can press volume-up on physical keys
 *    — the overlay cannot consume those keys),
 *  - restored on dismiss.
 * The polite layers are kept on top of it: a media-key PAUSE and an
 * AUDIOFOCUS_GAIN request (well-behaved apps actually pause instead of
 * continuing muted playback).
 *
 * Single quiz surface is active at a time (the service guards this), so a
 * simple singleton state is sufficient.
 */
object MediaPauseHelper {

    private const val TAG = "MediaPauseHelper"
    private const val STREAM = AudioManager.STREAM_MUSIC

    @Volatile
    private var muted: Boolean = false

    @Volatile
    private var previousVolume: Int = -1

    @Volatile
    private var focusRequest: AudioFocusRequest? = null

    private val legacyListener = AudioManager.OnAudioFocusChangeListener { }

    /** Mutes media audio and pauses/focus-steals playback. Idempotent. */
    @Synchronized
    fun pause(context: Context) {
        if (muted) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 1) Best-effort: ask the current media session to pause.
            val keyDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
            val keyUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
            am.dispatchMediaKeyEvent(keyDown)
            am.dispatchMediaKeyEvent(keyUp)

            // 2) Polite: take exclusive audio focus (well-behaved apps pause).
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
                am.requestAudioFocus(
                    legacyListener, STREAM, AudioManager.AUDIOFOCUS_GAIN
                )
            }

            // 3) Guaranteed: mute the media stream entirely.
            previousVolume = am.getStreamVolume(STREAM)
            am.setStreamVolume(STREAM, 0, 0)
            muted = true
            Log.d(TAG, "Media silenced (prev volume=$previousVolume)")
        } catch (e: Exception) {
            Log.w(TAG, "pause failed; quiz continues", e)
        }
    }

    /**
     * Re-applies the mute (physical volume keys bypass the overlay). Safe to
     * call repeatedly; no-op when we are not the muting party.
     */
    @Synchronized
    fun reassertMute(context: Context) {
        if (!muted) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.getStreamVolume(STREAM) != 0) {
                am.setStreamVolume(STREAM, 0, 0)
                Log.d(TAG, "Media mute re-asserted")
            }
        } catch (e: Exception) {
            Log.w(TAG, "reassertMute failed", e)
        }
    }

    /** Restores the previous volume and abandons focus. Idempotent. */
    @Synchronized
    fun resume(context: Context) {
        if (!muted) return
        muted = false
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(legacyListener)
            }
            val prev = previousVolume
            if (prev >= 0) {
                am.setStreamVolume(STREAM, prev, 0)
            }
            previousVolume = -1
            Log.d(TAG, "Media restored (volume=$prev)")
        } catch (e: Exception) {
            Log.w(TAG, "resume failed", e)
        }
    }
}
