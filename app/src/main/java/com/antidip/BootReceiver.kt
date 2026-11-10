package com.antidip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
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

        Log.d(TAG, "Boot completed, starting ScreenMonitorService")

        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        // A parent temporary pause is measured from elapsedRealtime, which
        // restarts at boot. Clearing the marker guarantees the documented
        // "下次开机自动恢复" behaviour (A01/A15).
        prefs.edit().remove(Constants.KEY_PARENT_PAUSE_AT_ELAPSED).apply()

        // Only the settings page's persistent-stop decision survives a reboot.
        val isPaused = prefs.getBoolean(Constants.KEY_SERVICE_PAUSED, false)

        if (!isPaused) {
            val serviceIntent = Intent(context, ScreenMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
