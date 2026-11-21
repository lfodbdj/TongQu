package com.antidip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 应用自身被覆盖安装后系统发出的 MY_PACKAGE_REPLACED（受保护广播，仅系统可发）。
 *
 * 真机根因（2026-11-21）：覆盖安装会强杀应用进程，EMUI 不允许被杀的
 * START_STICKY 服务后台自启，而孩子不会主动打开 App —— 结果是"装完新版
 * 守护服务一直不起，再也不弹题"。此接收器在更新完成后立即拉起前台服务，
 * 并重建 BootReceiver 的 +2/+10/+30 分钟保活闹钟，弹窗链路自愈。
 */
class AppUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.d(TAG, "Package replaced — restarting guardian service + keepalive alarms")
        try {
            val svc = Intent(context, ScreenMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            // 某些 ROM 在安装广播窗口内限制后台启动 —— 保活闹钟仍会兜底。
            Log.w(TAG, "Service start after update failed; alarms remain", e)
        }
        BootReceiver.scheduleKeepalive(context)
    }

    companion object {
        private const val TAG = "AppUpdateReceiver"
    }
}
