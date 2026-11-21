package com.antidip

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.util.Log

/**
 * V2.2 (requirement 2026-11-10: 手机重启后服务不会运行): EMUI/华为的"自启动管理"
 * 默认禁止第三方接收 BOOT_COMPLETED，或首次启动被静默吞掉。兜底策略：开机后
 * 通过 AlarmManager 再排 3 次拉起重试（+2min / +10min / +30min），每次到达时若
 * 服务仍未运行则再次 startForegroundService。家长在系统设置允许"自启动"后
 * （设置页的引导项），首次拉起即成功，alarm 重试自然空跑。
 *
 * Also handles Quiet-Mode retry: `com.antidip.ACTION_SERVICE_KEEPALIVE` is
 * scheduled by [ScreenMonitorService] itself as a watchdog heartbeat.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Constants.ACTION_SERVICE_KEEPALIVE
        ) {
            return
        }

        // Direct Boot: credential-encrypted storage (SharedPreferences) is not
        // available until the user unlocks the device. Touching it here would
        // crash the receiver with IllegalStateException.
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.d(TAG, "Ignoring LOCKED_BOOT_COMPLETED (device still locked)")
            return
        }
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager != null && !userManager.isUserUnlocked) {
            Log.d(TAG, "Ignoring BOOT_COMPLETED (user not unlocked yet)")
            return
        }

        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        if (action == Constants.ACTION_SERVICE_KEEPALIVE) {
            // Watchdog: only restarts the service when it is NOT running.
            if (!ScreenMonitorService.isRunning) {
                Log.w(TAG, "Keepalive: service not running, restarting it")
                startService(context)
            }
            scheduleKeepalive(context) // keep the heartbeat going
            return
        }

        Log.d(TAG, "Boot completed, starting ScreenMonitorService")

        // A parent temporary pause is measured from elapsedRealtime, which
        // restarts at boot. Clearing the marker guarantees the documented
        // "下次开机自动恢复" behaviour (A01/A15).
        prefs.edit().remove(Constants.KEY_PARENT_PAUSE_AT_ELAPSED).apply()

        // Only the settings page's persistent-stop decision survives a reboot.
        val isPaused = prefs.getBoolean(Constants.KEY_SERVICE_PAUSED, false)

        if (!isPaused) {
            startService(context)
        }
        // Watchdog retries regardless of pause state (cheap no-op when paused).
        scheduleKeepalive(context)
    }

    private fun startService(context: Context) {
        val serviceIntent = Intent(context, ScreenMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        /** Schedules 3 keepalive alarms: +2min, +10min, +30min after boot. */
        fun scheduleKeepalive(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, BootReceiver::class.java)
                .setAction(Constants.ACTION_SERVICE_KEEPALIVE)
            val times = longArrayOf(2 * 60_000L, 10 * 60_000L, 30 * 60_000L)
            times.forEachIndexed { index, delayMs ->
                val pi = PendingIntent.getBroadcast(
                    context,
                    1000 + index,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                try {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delayMs,
                        pi
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "scheduleKeepalive failed for +${delayMs}ms", e)
                }
            }
            Log.d(TAG, "Keepalive alarms scheduled (+2/+10/+30 min)")
        }
    }
}
