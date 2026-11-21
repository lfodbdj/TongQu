package com.antidip

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * V2.2 (requirement 2026-11-10: 手机重启后服务不会运行): persisted periodic
 * job — survives reboots (JobInfo.setPersisted(true)) — as the LAST-LINE
 * fallback for OEM autostart managers (EMUI 默认吞掉 BOOT_COMPLETED).
 * The job runs at most every ~15 minutes, and simply restarts the
 * foreground service when it is not running.
 */
class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!ScreenMonitorService.isRunning) {
            Log.w(TAG, "KeepAlive job: service not running, restarting")
            val intent = Intent(this, ScreenMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        // false: work is done synchronously.
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "KeepAliveJob"

        const val JOB_ID = 42

        /**
         * Schedules the persisted job (idempotent — later schedules with the
         * same jobId replace earlier ones). Call once when the service starts.
         */
        fun schedule(context: Context) {
            val js = context.getSystemService(JOB_SCHEDULER_SERVICE)
                as? android.app.job.JobScheduler ?: return
            val builder = android.app.job.JobInfo.Builder(
                JOB_ID,
                android.content.ComponentName(context, KeepAliveJobService::class.java)
            )
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
            try {
                val result = js.schedule(builder.build())
                Log.d(TAG, "Persisted keepalive job scheduled: result=$result")
            } catch (e: Exception) {
                Log.w(TAG, "schedule keepalive job failed", e)
            }
        }
    }
}
