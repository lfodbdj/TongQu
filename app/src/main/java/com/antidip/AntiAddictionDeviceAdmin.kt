package com.antidip

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device-admin receiver that enables the "real lock screen" feature (task B10).
 *
 * When the user activates device administration for this app (via Settings →
 * "启用设备管理器"), [android.app.admin.DevicePolicyManager.lockNow] becomes
 * available. The app uses it to truly turn off the screen during the 10-minute
 * hard-lock penalty, so the child cannot keep using the phone — they must unlock
 * (with the device PIN/pattern the parent controls) before the penalty overlay
 * even becomes visible.
 *
 * Screen pinning (lock task mode) is handled separately in [ui.QuizActivity] and
 * does NOT require this receiver.
 */
class AntiAddictionDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "设备管理器已启用，真机锁屏可用", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "设备管理器已关闭，真机锁屏不可用", Toast.LENGTH_SHORT).show()
    }
}
