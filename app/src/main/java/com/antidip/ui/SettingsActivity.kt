package com.antidip.ui

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.Manifest
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.antidip.Constants
import com.antidip.DeviceLockHelper
import com.antidip.ParentAuth
import com.antidip.QuestionBankManager
import com.antidip.QuizDownloader
import com.antidip.R
import com.antidip.ScreenMonitorService
import com.antidip.UrlValidator
import com.antidip.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var questionBankManager: QuestionBankManager
    private lateinit var quizDownloader: QuizDownloader

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限，否则无法正常使用", Toast.LENGTH_LONG).show()
        }
    }

    private val batteryPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    // Batch 3 (A29): POST_NOTIFICATIONS is a runtime permission from API 33.
    // Without it the running foreground service is invisible on Android 13+,
    // so the child cannot see WHY a quiz pops up. Below API 33 this is a no-op.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.perm_notification_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        questionBankManager = QuestionBankManager(this)
        quizDownloader = QuizDownloader(this)

        setupUI()
        requestNecessaryPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateQuizStats()
        updateDeviceAdminStatus()
    }

    private fun setupUI() {
        // V2.0 童趣: child-facing growth screen (points / medals / wrong book).
        binding.btnGrowth.setOnClickListener {
            startActivity(Intent(this, GrowthActivity::class.java))
        }

        binding.btnParentConsole.setOnClickListener {
            // ConsoleActivity is exported=false and re-verifies the parent
            // password on every onResume; the gate lives there, not here.
            startActivity(Intent(this, ConsoleActivity::class.java))
        }

        binding.btnStartService.setOnClickListener {
            // Clear paused state
            val prefs = getSharedPreferences(
                Constants.PREFS_NAME, MODE_PRIVATE
            )
            prefs.edit().putBoolean(Constants.KEY_SERVICE_PAUSED, false).apply()

            val intent = Intent(this, ScreenMonitorService::class.java).apply {
                action = Constants.ACTION_RESUME
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // S1: startForegroundService() is asynchronous — onCreate() has not
            // yet set isRunning when this click returns. Defer the label
            // refresh so the in-memory snapshot is authoritative (real-device
            // finding: label stayed "paused" forever after starting).
            binding.root.postDelayed({ updateServiceStatus() }, 800)
        }

        binding.btnStopService.setOnClickListener {
            // 2-b (T4): stopping the guardian service is destructive, so the
            // shared parent gate must succeed first. On a wrong password
            // ParentAuth keeps the dialog open and touches nothing here: no
            // prefs write, no Intent, no status refresh.
            ParentAuth.showVerifyDialog(this) {
                val intent = Intent(this@SettingsActivity, ScreenMonitorService::class.java).apply {
                    action = Constants.ACTION_PAUSE
                }
                startService(intent)

                val prefs = getSharedPreferences(
                    Constants.PREFS_NAME, MODE_PRIVATE
                )
                prefs.edit().putBoolean(Constants.KEY_SERVICE_PAUSED, true).apply()

                // Defer: ACTION_PAUSE is handled asynchronously by the service, so
                // isPausedSnapshot may still be stale on the calling thread.
                binding.root.postDelayed({ updateServiceStatus() }, 300)
            }
        }

        binding.btnUpdateQuiz.setOnClickListener {
            downloadQuiz()
        }

        // Batch 3 (A08): wire the Scoped-Storage local import that README
        // already promises. Ungated like "更新题库" — it only ADDS rows to the
        // bank from the app-private directory (no storage permission, no way
        // for the child to reach that path without adb), while the bank itself
        // cannot be emptied or weakened through this path.
        binding.btnImportLocal.setOnClickListener {
            importLocalQuestions()
        }

        binding.layoutPermOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        binding.layoutPermBattery.setOnClickListener {
            requestBatteryPermission()
        }

        binding.layoutPermAutostart.setOnClickListener {
            // Open Huawei autostart settings
            try {
                val intent = Intent()
                intent.component = android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback: open app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        }

        // Batch 3 (A29): notification permission card. On API 33+ this either
        // fires the runtime request or jumps to the app notification settings
        // when the system already answered with "don't ask again"; below 33 it
        // simply opens the app page (the permission does not exist there).
        binding.layoutPermNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    Toast.makeText(this, R.string.perm_notification, Toast.LENGTH_SHORT).show()
                } else {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        }

        binding.tvImportPath.text = getString(
            R.string.settings_import_path,
            questionBankManager.getImportDirPath()
        )

        // B6: configurable download URL
        val savedUrl = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            .getString(Constants.KEY_QUIZ_DOWNLOAD_URL, "") ?: ""
        // Pre-fill the field with the default LAN server address (the quiz
        // server rooted at <本地题库目录>/ on the PC) so the parent sees a
        // concrete, editable value instead of an empty box. Saving an empty
        // value still falls back to DEFAULT_QUIZ_URL (see QuizDownloader).
        binding.etServerUrl.setText(savedUrl.ifEmpty { Constants.DEFAULT_QUIZ_URL })

        binding.btnSaveServerUrl.setOnClickListener {
            // 2-b (T4): changing the download URL is destructive — a bad URL
            // can make the bank permanently un-downloadable — so gate it too.
            ParentAuth.showVerifyDialog(this) {
                val url = binding.etServerUrl.text?.toString()?.trim() ?: ""
                // An empty value is still allowed: it means "fall back to the
                // default LAN address" (see QuizDownloader.getSavedUrl()).
                // Any non-empty value must pass M4 + the batch-3 transport
                // policy (http only for private/local hosts, https for public).
                val urlIsAcceptable = url.isEmpty() || UrlValidator.isAcceptable(url)
                if (!urlIsAcceptable) {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_url_rejected,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(Constants.KEY_QUIZ_DOWNLOAD_URL, url).apply()
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_server_save,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // B10: device administration (real lock screen) controls
        binding.btnEnableAdmin.setOnClickListener { requestDeviceAdmin() }
        binding.btnDisableAdmin.setOnClickListener {
            // 2-b (T4): revoking device admin removes the real lock-screen
            // capability, so require the shared parent gate first.
            ParentAuth.showVerifyDialog(this) {
                DeviceLockHelper.removeAdmin(this@SettingsActivity)
                updateDeviceAdminStatus()
            }
        }
        binding.btnLockNow.setOnClickListener {
            if (DeviceLockHelper.isAdminActive(this)) {
                DeviceLockHelper.lockNow(this)
            } else {
                Toast.makeText(this, R.string.settings_device_admin_disabled, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateDeviceAdminStatus() {
        binding.tvDeviceAdminStatus.text = if (DeviceLockHelper.isAdminActive(this)) {
            getString(R.string.settings_device_admin_enabled)
        } else {
            getString(R.string.settings_device_admin_disabled)
        }
    }

    private fun requestDeviceAdmin() {
        val context = this
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                DeviceLockHelper.component(context)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.settings_device_admin_desc)
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开设备管理器设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateServiceStatus() {
        // Batch 2-a (S1): two-part contract for the status label.
        // (1) Read the service's IN-MEMORY state. A parent temporary pause is
        //     written by the service to KEY_PARENT_PAUSE_AT_ELAPSED, never to
        //     KEY_SERVICE_PAUSED (that key is only the settings page's
        //     persistent-stop decision). Reading prefs here showed "running"
        //     during a parent pause, and "paused" forever after starting.
        // (2) Callers must DEFER this refresh: startForegroundService() and
        //     ACTION_PAUSE are asynchronous, so isRunning / isPausedSnapshot
        //     are not updated yet when the click handler returns. The button
        //     handlers post a delayed refresh; onResume() refreshes on return.
        val isRunning = ScreenMonitorService.isRunning
        val isPaused = ScreenMonitorService.isPausedSnapshot

        if (isRunning && !isPaused) {
            binding.tvServiceStatus.text = getString(R.string.settings_running)
            binding.tvServiceStatus.setTextColor(getColor(R.color.success))
        } else {
            binding.tvServiceStatus.text = getString(R.string.settings_stopped)
            binding.tvServiceStatus.setTextColor(getColor(R.color.error))
        }
    }

    private fun updateQuizStats() {
        lifecycleScope.launch {
            val stats = questionBankManager.getSubjectStats()
            val total = stats.values.sum()
            val chinese = stats["chinese"] ?: 0
            val math = stats["math"] ?: 0
            val english = stats["english"] ?: 0
            val science = stats["science"] ?: 0
            val moral = stats["moral"] ?: 0

            binding.tvQuizStats.text = getString(
                R.string.settings_quiz_stats,
                total, chinese, math, english, science, moral
            )
        }
    }

    private fun downloadQuiz() {
        binding.btnUpdateQuiz.isEnabled = false
        binding.btnUpdateQuiz.text = getString(R.string.update_downloading)

        lifecycleScope.launch {
            val result = quizDownloader.downloadQuiz()
            binding.btnUpdateQuiz.isEnabled = true
            binding.btnUpdateQuiz.text = getString(R.string.settings_update_quiz)

            result.fold(
                onSuccess = { count ->
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.update_success, count),
                        Toast.LENGTH_LONG
                    ).show()
                    updateQuizStats()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.update_failed, error.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun requestNecessaryPermissions() {
        requestOverlayPermission()
        requestBatteryPermission()
        requestNotificationPermission()
    }

    /**
     * Batch 3 (A29): request POST_NOTIFICATIONS at runtime on API 33+. On the
     * API 29 test device (and any build below T) this is a no-op.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Batch 3 (A08): run the README-promised local import on demand. Same
     * semantics as the boot-time scan: duplicates are ignored by the unique
     * (content, grade) index and processed files move to `.processed`.
     * Returns -1 on IO failure (surfaced as import_failed), 0 = nothing new.
     */
    private fun importLocalQuestions() {
        binding.btnImportLocal.isEnabled = false
        lifecycleScope.launch {
            val count = questionBankManager.importFromLocalDirectory()
            binding.btnImportLocal.isEnabled = true
            if (count >= 0) {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.import_success, count),
                    Toast.LENGTH_LONG
                ).show()
                updateQuizStats()
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.import_failed, "本地导入目录不可读"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestBatteryPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    batteryPermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    // Some devices don't support this intent
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallbackIntent)
                }
            }
        }
    }

}
