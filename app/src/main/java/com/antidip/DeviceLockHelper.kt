package com.antidip

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Thin wrapper around the Device Policy Manager used by the "real lock screen"
 * feature (task B10).
 *
 * - [isAdminActive] tells whether the user has activated device administration
 *   for this app (required before [lockNow] works).
 * - [lockNow] truly turns off the screen. It is a no-op (returns false) when
 *   device administration is not active, so callers can call it unconditionally.
 */
object DeviceLockHelper {

    fun component(context: Context): ComponentName =
        ComponentName(context, AntiAddictionDeviceAdmin::class.java)

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(component(context))
    }

    /** Truly lock the screen (turn it off). Returns true if it succeeded. */
    fun lockNow(context: Context): Boolean {
        if (!isAdminActive(context)) return false
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Remove this app's device-administration rights (user-initiated). */
    fun removeAdmin(context: Context) {
        if (!isAdminActive(context)) return
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.removeActiveAdmin(component(context))
        } catch (e: Exception) {
            // ignore
        }
    }
}
