package com.antidip.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.antidip.AppConfig
import com.antidip.Constants
import com.antidip.ForegroundAppDetector
import com.antidip.ParentAuth
import com.antidip.QuestionBankManager
import com.antidip.R
import com.antidip.databinding.ActivityConsoleBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parent console (batch 2-c): quiz interval, grade-scoped question bank and
 * foreground exempt apps.
 *
 * Security invariants:
 *  - declared exported=false with no intent-filter in the manifest;
 *  - the configuration layout stays GONE until [ParentAuth.showVerifyDialog]
 *    succeeds, so a cancelled/failed dialog exposes nothing;
 *  - EVERY onResume re-runs the gate, so Back / Home / recents / rotation
 *    cannot leave an unlocked console on screen for a child to reuse.
 */
class ConsoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsoleBinding
    private lateinit var questionBankManager: QuestionBankManager
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val appAdapter = AppPickerAdapter { updateExemptCount() }

    /** Parallel to the spinner labels: ALL_GRADES followed by 1..6. */
    private var gradeValues: List<Int> = emptyList()
    private val gradeNumbers = arrayOf("一", "二", "三", "四", "五", "六")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        questionBankManager = QuestionBankManager(this)

        binding.rvExemptApps.layoutManager = LinearLayoutManager(this)
        binding.rvExemptApps.adapter = appAdapter

        binding.btnRetryAuth.setOnClickListener { showGate() }
        binding.btnSaveConsole.setOnClickListener { saveAll() }
        binding.btnClearExempt.setOnClickListener { appAdapter.clearChecks() }
        binding.btnOpenUsageSettings.setOnClickListener { openUsageSettings() }
        binding.etAppSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                appAdapter.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        // 2026-11-21：AI 判题与题库服务器地址从设置页移入控制台（有密码门，
        // 防止孩子/误触改动需要填写的配置）。
        binding.btnAiSave.setOnClickListener { saveAiConfig() }
        binding.btnAiTest.setOnClickListener { testAi() }
        binding.btnSaveServerUrl.setOnClickListener { saveServerUrl() }
        // V2.3 拍照录题（2026-11-24）：进入拍照 + AI 识别 + 复核入库。
        binding.btnPhotoQuiz.setOnClickListener {
            startActivity(Intent(this@ConsoleActivity, PhotoQuizActivity::class.java))
        }

        // 2026-11-21 第二批：权限组从设置页移入控制台（同防误操作）。
        binding.layoutPermOverlay.setOnClickListener { requestOverlayPermission() }
        binding.layoutPermBattery.setOnClickListener { requestBatteryPermission() }
        binding.layoutPermAutostart.setOnClickListener { openAutostartManager() }
        binding.layoutPermNotification.setOnClickListener { requestNotificationPermission() }
        binding.layoutPermMediaSessions.setOnClickListener {
            runCatching {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        }
        binding.btnEnableAdmin.setOnClickListener { requestDeviceAdmin() }
        binding.btnDisableAdmin.setOnClickListener {
            // 撤销设备管理器会移除真机锁屏能力，与设置页一致要求确认。
            ParentAuth.showVerifyDialog(this) {
                com.antidip.DeviceLockHelper.removeAdmin(this@ConsoleActivity)
                updateDeviceAdminStatus()
            }
        }
        binding.btnLockNow.setOnClickListener {
            if (com.antidip.DeviceLockHelper.isAdminActive(this)) {
                com.antidip.DeviceLockHelper.lockNow(this)
            } else {
                Toast.makeText(this, R.string.settings_device_admin_disabled, Toast.LENGTH_SHORT).show()
            }
        }

        showLocked()
    }

    override fun onResume() {
        super.onResume()
        // Always re-lock: returning from the usage-access settings (or any
        // other app switch) must not keep the configuration visible.
        showLocked()
        refreshUsagePermissionBanner()
        showGate()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun showLocked() {
        binding.layoutConsoleLocked.visibility = View.VISIBLE
        binding.layoutConsoleContent.visibility = View.GONE
    }

    private fun showGate() {
        ParentAuth.showVerifyDialog(this) {
            binding.layoutConsoleLocked.visibility = View.GONE
            binding.layoutConsoleContent.visibility = View.VISIBLE
            loadConfigUi()
        }
    }

    private fun loadConfigUi() {
        binding.etInterval.setText(AppConfig.getQuizIntervalMinutes(this).toString())
        refreshUsagePermissionBanner()
        loadAiAndServerConfig()
        updatePermissionStatuses()
        updateDeviceAdminStatus()
        uiScope.launch {
            val counts = withContext(Dispatchers.IO) {
                runCatching { questionBankManager.getGradeCounts() }
                    .getOrDefault(emptyMap())
            }
            val total = counts.values.sum()

            gradeValues = listOf(Constants.ALL_GRADES) +
                (Constants.GRADE_MIN..Constants.GRADE_MAX).toList()
            val labels = mutableListOf(getString(R.string.console_grade_all, total))
            for (g in Constants.GRADE_MIN..Constants.GRADE_MAX) {
                labels.add(
                    getString(
                        R.string.console_grade_item,
                        gradeNumbers[g - Constants.GRADE_MIN],
                        counts[g] ?: 0
                    )
                )
            }
            val spinnerAdapter = ArrayAdapter(
                this@ConsoleActivity,
                android.R.layout.simple_spinner_item,
                labels
            )
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spGrade.adapter = spinnerAdapter
            val selected = AppConfig.getSelectedGrade(this@ConsoleActivity)
            binding.spGrade.setSelection(gradeValues.indexOf(selected).coerceAtLeast(0))

            val exempt = AppConfig.getExemptPackages(this@ConsoleActivity)
            // Selected apps first, then the rest alphabetically (requirement
            // 2026-11-09: opening the whitelist must show the already-selected
            // apps by default instead of burying them in the full app list).
            val apps = loadInstalledApps()
                .map { it.copy(checked = it.pkg in exempt) }
                .sortedWith(
                    compareByDescending<AppEntry> { it.checked }
                        .thenBy { it.label.lowercase() }
                )
            appAdapter.submit(apps)
            updateExemptCount()
        }
    }

    /**
     * Enumerate EVERY installed application (user + system), not just those
     * with a launcher icon. The previous implementation used
     * queryIntentActivities(MAIN + LAUNCHER), which only returns apps that
     * expose a desktop entry and therefore hid system/preinstalled apps.
     * getInstalledApplications() returns the full package set. On API 30+ this
     * needs the QUERY_ALL_PACKAGES permission (declared in the manifest); on
     * the API 29 test device it also works without it.
     */
    private suspend fun loadInstalledApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = this@ConsoleActivity.packageManager
        val selfPkg = this@ConsoleActivity.packageName
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        apps.mapNotNull { appInfo ->
            val pkg = appInfo.packageName ?: return@mapNotNull null
            if (pkg == selfPkg) return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(pkg)
            if (label.isBlank()) return@mapNotNull null
            AppEntry(pkg = pkg, label = label, checked = false)
        }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private fun updateExemptCount() {
        binding.tvExemptCount.text = getString(
            R.string.console_exempt_count,
            appAdapter.checkedPackages().size,
            Constants.MAX_EXEMPT_APPS
        )
    }

    private fun refreshUsagePermissionBanner() {
        val granted = ForegroundAppDetector.hasUsageStatsPermission(this)
        binding.tvUsagePermStatus.text = getString(
            if (granted) R.string.console_usage_granted else R.string.console_usage_missing
        )
    }

    private fun openUsageSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }.onFailure {
            Toast.makeText(this, R.string.console_usage_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveAll() {
        val raw = binding.etInterval.text?.toString()?.trim().orEmpty()
        val parsed = raw.toIntOrNull()
        if (parsed == null) {
            Toast.makeText(this, R.string.console_invalid_interval, Toast.LENGTH_SHORT).show()
            return
        }
        val minutes = AppConfig.clampIntervalMinutes(parsed)

        val exempt = appAdapter.checkedPackages()
        if (!AppConfig.isExemptSetValid(exempt)) {
            Toast.makeText(this, R.string.console_too_many_exempt, Toast.LENGTH_LONG).show()
            return
        }

        val grade = gradeValues.getOrElse(binding.spGrade.selectedItemPosition) {
            Constants.ALL_GRADES
        }

        // Order matters: setQuizIntervalMinutes() broadcasts
        // ACTION_CONFIG_CHANGED so the running service recomputes the timer.
        AppConfig.setSelectedGrade(this, grade)
        AppConfig.setExemptPackages(this, exempt)
        AppConfig.setQuizIntervalMinutes(this, minutes)

        val gradeLabel = if (grade == Constants.ALL_GRADES) {
            getString(R.string.console_grade_all_label)
        } else {
            gradeNumbers[grade - Constants.GRADE_MIN] + "年级"
        }
        val msg = getString(R.string.console_saved, minutes, gradeLabel, exempt.size)
        binding.tvConsoleMessage.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        loadConfigUi()
    }

    // ===== AI 判题 + 题库服务器地址（2026-11-21 从设置页移入，密码门内） =====

    private fun loadAiAndServerConfig() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        binding.switchAiEnabled.isChecked =
            prefs.getBoolean(Constants.KEY_AI_ENABLED, false)
        binding.etAiEndpoint.setText(prefs.getString(Constants.KEY_AI_ENDPOINT, "").orEmpty())
        binding.etAiKey.setText(prefs.getString(Constants.KEY_AI_KEY, "").orEmpty())
        binding.etAiModel.setText(prefs.getString(Constants.KEY_AI_MODEL, "").orEmpty())
        // 与设置页一致：空值时预填默认局域网地址（保存空值仍回退默认）。
        val savedUrl = prefs.getString(Constants.KEY_QUIZ_DOWNLOAD_URL, "") ?: ""
        binding.etServerUrl.setText(savedUrl.ifEmpty { Constants.DEFAULT_QUIZ_URL })
    }

    private fun saveAiConfig() {
        val endpoint = binding.etAiEndpoint.text?.toString()?.trim().orEmpty()
        val enabled = binding.switchAiEnabled.isChecked
        if (enabled && endpoint.isEmpty()) {
            Toast.makeText(this, R.string.ai_need_config, Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(Constants.KEY_AI_ENABLED, enabled)
            .putString(Constants.KEY_AI_ENDPOINT, endpoint)
            .putString(Constants.KEY_AI_KEY, binding.etAiKey.text?.toString()?.trim().orEmpty())
            .putString(Constants.KEY_AI_MODEL, binding.etAiModel.text?.toString()?.trim().orEmpty())
            .apply()
        Toast.makeText(this, R.string.ai_saved, Toast.LENGTH_SHORT).show()
    }

    private fun testAi() {
        val endpoint = binding.etAiEndpoint.text?.toString()?.trim().orEmpty()
        val key = binding.etAiKey.text?.toString()?.trim().orEmpty()
        if (endpoint.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, R.string.ai_need_config, Toast.LENGTH_LONG).show()
            return
        }
        binding.btnAiTest.isEnabled = false
        uiScope.launch {
            val ok = com.antidip.AiJudge.testConfig(
                this@ConsoleActivity, endpoint, key,
                binding.etAiModel.text?.toString().orEmpty()
            )
            binding.btnAiTest.isEnabled = true
            Toast.makeText(
                this@ConsoleActivity,
                if (ok == true) R.string.ai_test_ok else R.string.ai_test_fail,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveServerUrl() {
        // 空值允许（回退默认局域网地址）；非空必须通过 M4 + batch-3 传输策略。
        val url = binding.etServerUrl.text?.toString()?.trim().orEmpty()
        val acceptable = url.isEmpty() || com.antidip.UrlValidator.isAcceptable(url)
        if (!acceptable) {
            Toast.makeText(this, R.string.settings_url_rejected, Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit()
            .putString(Constants.KEY_QUIZ_DOWNLOAD_URL, url).apply()
        Toast.makeText(this, R.string.settings_server_save, Toast.LENGTH_SHORT).show()
    }

    // ===== 权限组（2026-11-21 从设置页移入，密码门内） =====

    /** UI v3：权限行真实状态（绿=已开启，橙=去开启）。 */
    private fun updatePermissionStatuses() {
        val okColor = getColor(R.color.primary)
        val warnColor = 0xFFC77700.toInt()
        fun setStatus(tv: android.widget.TextView?, granted: Boolean) {
            tv ?: return
            tv.text = if (granted) "✓ 已开启" else "去开启 ›"
            tv.setTextColor(if (granted) okColor else warnColor)
        }
        val overlayGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else true
        setStatus(binding.tvPermOverlayStatus, overlayGranted)
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val batteryGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
        setStatus(binding.tvPermBatteryStatus, batteryGranted)
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        setStatus(binding.tvPermNotificationStatus, nm.areNotificationsEnabled())
        val listeners = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        setStatus(binding.tvPermMediaStatus, listeners.contains(packageName))
    }

    private fun updateDeviceAdminStatus() {
        binding.tvDeviceAdminStatus.text = if (
            com.antidip.DeviceLockHelper.isAdminActive(this)
        ) {
            getString(R.string.settings_device_admin_enabled)
        } else {
            getString(R.string.settings_device_admin_disabled)
        }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                com.antidip.DeviceLockHelper.component(this@ConsoleActivity)
            )
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.settings_device_admin_desc)
            )
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开设备管理器设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOverlayPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            Toast.makeText(this, "请授予悬浮窗权限，否则无法正常使用", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private val batteryPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { }

    private fun requestBatteryPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).apply { data = Uri.parse("package:$packageName") }
                try {
                    batteryPermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.perm_notification_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.app.NotificationManagerCompat.from(this)
                .areNotificationsEnabled().not()
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openAutostartManager() {
        val emui = Intent().setComponent(
            android.content.ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
        )
        try {
            startActivity(emui)
            return
        } catch (e: Exception) {
            // 该机型无此组件，走应用详情页
        }
        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.perm_autostart_open, Toast.LENGTH_SHORT).show()
        }
    }
}
