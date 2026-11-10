package com.antidip

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Call-priority detection (requirement 2026-11-08: a quiz must NEVER pop up
 * during a call; while the app runs, calls have the HIGHEST priority).
 *
 * The detector reads the GLOBAL audio mode ([AudioManager.getMode]), which
 * covers every call path without any package-level whitelist:
 *  - MODE_RINGING / MODE_IN_CALL: a system telephony call (set by the
 *    telephony stack while ringing or in a cellular call);
 *  - MODE_IN_COMMUNICATION: any VoIP voice/video call — WeChat voice calls,
 *    WeChat video calls, QQ calls etc. all set this while the realtime audio
 *    channel is up;
 *  - MODE_CALL_SCREENING / MODE_CALL_REDIRECT / MODE_COMMUNICATION_REDIRECT:
 *    call screening / call redirection paths, still calls.
 *
 * Ordinary media consumption stays in MODE_NORMAL and is deliberately NOT
 * covered: WeChat mini-programs, mini-games and Channels (视频号) do NOT get
 * call priority and the quiz fires normally while they are in use.
 *
 * FAIL-CLOSED: a missing AudioManager or any exception is treated as "not in
 * a call" so the quiz still fires. This mirrors [ForegroundAppDetector]: a
 * broken detection must never become a bypass, only a stricter experience.
 */
object CallPriorityDetector {

    private const val TAG = "CallPriorityDetector"

    /**
     * Pure-JVM core: true only for audio modes that belong to a call path.
     * Takes the raw [AudioManager.getMode()] value so QA can unit-test the
     * mapping without a device. The AudioManager constants are compile-time
     * ints, so the mapping is identical on every API level.
     */
    fun isCallAudioMode(mode: Int): Boolean = when (mode) {
        AudioManager.MODE_RINGTONE,               // 1: system call ringing
        AudioManager.MODE_IN_CALL,                // 2: cellular call active
        AudioManager.MODE_IN_COMMUNICATION,       // 3: VoIP call (WeChat voice/video etc.)
        AudioManager.MODE_CALL_SCREENING,         // 4: incoming call being screened
        AudioManager.MODE_CALL_REDIRECT,          // 5: call redirected
        AudioManager.MODE_COMMUNICATION_REDIRECT  // 6: communication redirected
        -> true
        else -> false // MODE_NORMAL(0) and any unknown value
    }

    /**
     * Android wrapper: read the global audio mode once. Fail-closed — see the
     * class KDoc.
     */
    fun isInCall(ctx: Context): Boolean {
        return try {
            val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audio == null) {
                Log.w(TAG, "AudioManager unavailable; treating as not-in-call (quiz fires)")
                return false
            }
            isCallAudioMode(audio.mode)
        } catch (e: Exception) {
            Log.w(TAG, "Call-state check failed; treating as not-in-call (quiz fires)", e)
            false
        }
    }
}
