package com.antidip.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.Manifest
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.antidip.AppConfig
import com.antidip.Constants
import com.antidip.ParentAuth
import com.antidip.QuestionBankManager
import com.antidip.QuizDownloader
import com.antidip.R
import com.antidip.ScreenMonitorService
import com.antidip.BuildConfig
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
        setContentView(wrapWithBottomNav(binding.root))

        questionBankManager = QuestionBankManager(this)
        quizDownloader = QuizDownloader(this)

        setupUI()
        setupPointsCard()
        requestNecessaryPermissions()
    }

    /**
     * 设置页与成长主页共用同一套自绘底部导航（NavBar.kt，UI v3 胶囊样式）：
     * 进入时「设置」高亮，点其他 tab 回跳 GrowthActivity 并切到对应页
     * （pendingTab）。外观与主页完全一致（含 16dp 侧边距）。
     */
    private fun wrapWithBottomNav(content: android.view.View): android.view.View {
        val nav = NavBar.build(this, R.id.nav_settings) { target ->
            when (target) {
                R.id.nav_settings -> true
                else -> {
                    GrowthActivity.pendingTab = target
                    finish()
                    false
                }
            }
        }
        val dp16 = (resources.displayMetrics.density * 16).toInt()
        val dp12 = (resources.displayMetrics.density * 12).toInt()
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
        }
        wrap.addView(content, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        wrap.addView(nav, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp16
            marginEnd = dp16
            bottomMargin = dp12
        })
        return wrap
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateQuizStats()
        updatePointsCard()
        updateConfigStatusRows()
    }

    private fun setupUI() {
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

        // 2026-11-21：权限组已移入家长控制台（有密码门，防止误操作）。
        // 通知权限仍在本页启动时自动请求一次（requestNecessaryPermissions）。

        binding.tvImportPath.text = getString(
            R.string.settings_import_path,
            questionBankManager.getImportDirPath()
        )

        // 2026-11-21：AI 配置/题库服务器地址/权限组均已移入家长控制台
        //（有密码门，防止孩子/误触改动）。设置页只保留状态行，点击跳控制台。
        binding.layoutOpenAiRow.setOnClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java))
        }
        binding.layoutServerRow.setOnClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java))
        }

        // V2.2: version number at the page bottom.
        binding.tvVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)

        // V2.2: user-data backup (parent-gated) — export/import via quiz server.
        binding.btnBackupServer.setOnClickListener {
            val nick = com.antidip.data.BackupHelper.getNickname(this)
            if (nick.isBlank()) {
                Toast.makeText(this, R.string.backup_nickname_hint, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            ParentAuth.showVerifyDialog(this) { backupToServer(nick) }
        }
        binding.btnRestoreServer.setOnClickListener {
            ParentAuth.showVerifyDialog(this) { showRestoreDialog() }
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
                    // V2.2: every successful bank update also snapshots the
                    // user profile as {nickname}.json (server PUT best-effort
                    // + local copy) so a device switch can restore it.
                    val backupNick = com.antidip.data.BackupHelper.getNickname(this@SettingsActivity)
                    if (backupNick.isNotBlank()) {
                        val profile = com.antidip.data.BackupHelper.currentProfile(
                            this@SettingsActivity, backupNick
                        )
                        com.antidip.data.BackupHelper.saveLocal(this@SettingsActivity, profile)
                        com.antidip.data.BackupHelper.putToServer(this@SettingsActivity, profile)
                    }
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

    /**
     * V2.2: saves the backup locally (always) and HTTP-PUTs it to the quiz
     * server as `{nickname}.json` (best-effort; plain http.server ignores
     * PUT — docs/server_put_server.py provides a one-line PUT server).
     */
    private fun backupToServer(nick: String) {
        lifecycleScope.launch {
            val profile = com.antidip.data.BackupHelper.currentProfile(this@SettingsActivity, nick)
            val local = com.antidip.data.BackupHelper.saveLocal(this@SettingsActivity, profile)
            val msg = com.antidip.data.BackupHelper.putToServer(this@SettingsActivity, profile)
            Toast.makeText(
                this@SettingsActivity,
                getString(
                    R.string.backup_ok,
                    (local?.name ?: "backup.json") + " · " + msg
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** V2.2: nickname prompt then fetch-and-restore from the quiz server. */
    private fun showRestoreDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.restore_nickname_hint)
            setSingleLine(true)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.restore_btn_server))
            .setView(wrap)
            .setPositiveButton(R.string.parent_mode_confirm) { _, _ ->
                val nick = input.text?.toString()?.trim().orEmpty()
                if (nick.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    com.antidip.data.BackupHelper.fetchFromServer(this@SettingsActivity, nick)
                        .fold(
                            onSuccess = { profile ->
                                com.antidip.data.BackupHelper.restore(this@SettingsActivity, profile)
                                Toast.makeText(
                                    this@SettingsActivity,
                                    getString(R.string.restore_ok, profile.nickname),
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            onFailure = { e ->
                                Toast.makeText(
                                    this@SettingsActivity,
                                    getString(R.string.restore_fail, e.message ?: ""),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                }
            }
            .setNegativeButton(R.string.parent_mode_cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

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

    // ================= 积分管理（扣减 + 独立扣减密码，2026-11-21） =================

    private fun setupPointsCard() {
        binding.btnDeduct.setOnClickListener { showDeductDialog() }
        binding.btnSetDeductionPw.setOnClickListener { showSetDeductionPwDialog() }
        binding.btnViewDeductionLog.setOnClickListener { showDeductionLogDialog() }
    }

    /** 全部扣减记录（含原因），新→旧，最多 50 条。 */
    private fun showDeductionLogDialog() {
        lifecycleScope.launch {
            val log = com.antidip.data.ProgressStore.getDeductionLog(this@SettingsActivity)
            val body = if (log.isEmpty()) {
                getString(R.string.deduction_log_empty)
            } else {
                val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
                log.joinToString("\n") { line ->
                    val parts = line.split("|", limit = 4)
                    if (parts.size < 4) return@joinToString line
                    val ts = try {
                        fmt.format(java.util.Date(parts[0].toLong()))
                    } catch (e: Exception) {
                        parts[0]
                    }
                    val type = parts[2]
                    val reason = parts[3]
                    getString(
                        R.string.deduction_log_line_fmt, ts,
                        parts[1].toIntOrNull() ?: 0,
                        if (reason.isBlank()) type else "$type · $reason"
                    )
                }
            }
            val tv = android.widget.TextView(this@SettingsActivity).apply {
                text = body
                textSize = 13f
                setTextColor(getColor(R.color.on_surface))
                setPadding(48, 32, 48, 8)
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            }
            val scroller = android.widget.ScrollView(this@SettingsActivity).apply { addView(tv) }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.deduction_log_dialog_title)
                .setView(scroller)
                .setPositiveButton(R.string.parent_mode_confirm, null)
                .create()
                .show()
        }
    }

    private fun updatePointsCard() {
        lifecycleScope.launch {
            val st = com.antidip.data.ProgressStore.getState(this@SettingsActivity)
            binding.tvPointsSummary.text = getString(R.string.points_summary_fmt, st.points)
            val log = com.antidip.data.ProgressStore.getDeductionLog(this@SettingsActivity)
                .take(3)
            binding.tvDeductionLog.text = if (log.isEmpty()) {
                getString(R.string.deduction_log_empty)
            } else {
                val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
                getString(R.string.deduction_log_title) + "\n" + log.joinToString("\n") { line ->
                    val parts = line.split("|", limit = 4)
                    if (parts.size < 4) return@joinToString line
                    val ts = try {
                        fmt.format(java.util.Date(parts[0].toLong()))
                    } catch (e: Exception) {
                        parts[0]
                    }
                    val type = parts[2]
                    val reason = parts[3]
                    getString(
                        R.string.deduction_log_line_fmt, ts,
                        parts[1].toIntOrNull() ?: 0,
                        if (reason.isBlank()) type else "$type · $reason"
                    )
                }
            }
        }
    }

    private fun dialogInput(hint: String, password: Boolean = false, number: Boolean = false):
        android.widget.EditText = android.widget.EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            inputType = when {
                password -> android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> android.text.InputType.TYPE_CLASS_NUMBER
                else -> android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
            }
        }

    private fun dialogError(box: android.widget.LinearLayout, text: String): android.widget.TextView {
        val err = android.widget.TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(R.color.error))
            setPadding(0, 12, 0, 0)
            visibility = android.view.View.GONE
        }
        box.addView(err)
        return err
    }

    private fun showDeductDialog() {
        if (!com.antidip.DeductionAuth.isSet(this)) {
            Toast.makeText(this, R.string.deduct_pw_not_set, Toast.LENGTH_LONG).show()
            showSetDeductionPwDialog()
            return
        }
        lifecycleScope.launch {
            val st = com.antidip.data.ProgressStore.getState(this@SettingsActivity)
            val box = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(64, 40, 64, 0)
            }
            box.addView(android.widget.TextView(this@SettingsActivity).apply {
                text = getString(R.string.points_summary_fmt, st.points)
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
            })
            val etAmount = dialogInput(getString(R.string.deduct_hint_amount), number = true)
            box.addView(etAmount)

            val rg = android.widget.RadioGroup(this@SettingsActivity).apply {
                orientation = android.widget.RadioGroup.HORIZONTAL
            }
            val rbExchange = android.widget.RadioButton(this@SettingsActivity).apply {
                text = getString(R.string.deduct_type_exchange)
                id = android.view.View.generateViewId()
                isChecked = true
            }
            val rbManual = android.widget.RadioButton(this@SettingsActivity).apply {
                text = getString(R.string.deduct_type_manual)
                id = android.view.View.generateViewId()
            }
            rg.addView(rbExchange)
            rg.addView(rbManual)
            box.addView(rg)

            val etReason = dialogInput(getString(R.string.deduct_hint_reason))
            box.addView(etReason)
            val etPw = dialogInput(getString(R.string.deduct_hint_pw), password = true)
            box.addView(etPw)
            val err = dialogError(box, getString(R.string.deduct_pw_wrong))

            val dialog = AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.deduct_dialog_title)
                .setView(box)
                .setPositiveButton(R.string.parent_mode_confirm, null)
                .setNegativeButton(R.string.parent_mode_cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.accent))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.accent))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    err.visibility = android.view.View.GONE
                    val amount = etAmount.text?.toString()?.trim()?.toIntOrNull() ?: 0
                    if (amount !in 1..999) {
                        err.text = getString(R.string.deduct_amount_invalid)
                        err.visibility = android.view.View.VISIBLE
                        return@setOnClickListener
                    }
                    if (!com.antidip.DeductionAuth.verify(
                            this@SettingsActivity, etPw.text?.toString().orEmpty())
                    ) {
                        err.text = getString(R.string.deduct_pw_wrong)
                        err.visibility = android.view.View.VISIBLE
                        return@setOnClickListener
                    }
                    val type = getString(
                        if (rbExchange.isChecked) R.string.deduct_type_exchange
                        else R.string.deduct_type_manual
                    )
                    lifecycleScope.launch {
                        val (before, actual) = com.antidip.data.ProgressStore.deductPoints(
                            this@SettingsActivity, amount, type, etReason.text?.toString().orEmpty()
                        )
                        dialog.dismiss()
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.deduct_ok_fmt, actual, before - actual),
                            Toast.LENGTH_LONG
                        ).show()
                        updatePointsCard()
                    }
                }
            }
            dialog.show()
        }
    }

    /**
     * 设置/重置扣减密码（用户要求 2026-11-21）：授权 = 旧扣减密码 或
     * 家长全局密码（ParentAuth.verify，防止扣减密码遗忘后无法重置）。
     */
    private fun showSetDeductionPwDialog() {
        val alreadySet = com.antidip.DeductionAuth.isSet(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 0)
        }
        val etOld = dialogInput(
            getString(
                if (alreadySet) R.string.setpw_hint_old else R.string.setpw_hint_old_first
            ),
            password = true
        )
        box.addView(etOld)
        val etNew = dialogInput(getString(R.string.setpw_hint_new), password = true)
        box.addView(etNew)
        val etConfirm = dialogInput(getString(R.string.setpw_hint_confirm), password = true)
        box.addView(etConfirm)
        val err = dialogError(box, getString(R.string.setpw_auth_wrong))

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.setpw_dialog_title)
            .setView(box)
            .setPositiveButton(R.string.parent_mode_confirm, null)
            .setNegativeButton(R.string.parent_mode_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.accent))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.accent))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                err.visibility = android.view.View.GONE
                val auth = etOld.text?.toString().orEmpty()
                val authorized = if (alreadySet) {
                    com.antidip.DeductionAuth.verify(this, auth) || ParentAuth.verify(auth)
                } else {
                    ParentAuth.verify(auth)
                }
                if (!authorized) {
                    err.text = getString(R.string.setpw_auth_wrong)
                    err.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                val newPw = etNew.text?.toString()?.trim().orEmpty()
                if (newPw.length < 4) {
                    err.text = getString(R.string.setpw_too_short)
                    err.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                if (newPw != etConfirm.text?.toString()?.trim().orEmpty()) {
                    err.text = getString(R.string.setpw_mismatch)
                    err.visibility = android.view.View.VISIBLE
                    return@setOnClickListener
                }
                if (com.antidip.DeductionAuth.set(this, newPw)) {
                    dialog.dismiss()
                    Toast.makeText(this, R.string.setpw_ok, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    // ================= AI / 服务器状态行（配置在家长控制台内） =================

    private fun updateConfigStatusRows() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        binding.tvAiStatus.text = if (
            prefs.getBoolean(Constants.KEY_AI_ENABLED, false)
        ) {
            getString(
                R.string.settings_ai_status_fmt,
                prefs.getString(Constants.KEY_AI_MODEL, "")?.trim().orEmpty()
                    .ifEmpty { "gpt-4o-mini" }
            )
        } else {
            getString(R.string.settings_ai_status_off)
        }
        val url = prefs.getString(Constants.KEY_QUIZ_DOWNLOAD_URL, "")?.trim().orEmpty()
        binding.tvServerStatus.text = if (url.isEmpty()) {
            getString(R.string.settings_server_status_default)
        } else {
            getString(R.string.settings_server_status_fmt, url)
        }
    }

}
