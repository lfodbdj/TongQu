package com.antidip.ui

import android.content.Intent
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
}
