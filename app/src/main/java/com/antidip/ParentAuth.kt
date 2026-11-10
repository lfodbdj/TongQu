package com.antidip

import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.material.textfield.TextInputEditText
import java.util.Calendar
import java.util.Locale

/**
 * Central parent-mode authentication shared by QuizActivity and
 * SettingsActivity (batch 2-b, T1).
 *
 * The four-argument [verify] overload is a pure-JVM, unit-testable core with
 * no Android framework dependency. The single-argument overload is the
 * Android wrapper that feeds [BuildConfig.MASTER_PASSWORD] and the current
 * calendar date into that core.
 *
 * [showVerifyDialog] is the ONLY gate for parent-only actions (including the
 * future parent console in 2-c), so it is deliberately non-cancellable: a
 * Back press or an outside tap must never let a child reach configuration UI.
 */
object ParentAuth {

    /**
     * Pure-JVM verification core. `month` is 1-12, `day` is 1-31.
     *
     * Semantics fixed in batch 1 (D01/D02/D03) and preserved verbatim:
     * - an empty password is always rejected (an unset master is "" and
     *   "" == "" would otherwise unlock parent mode),
     * - the master password is compared only when it is configured,
     * - the date password is MMDD reversed, formatted with [Locale.ROOT] so
     *   locale-specific digits cannot leak in.
     *
     * java.time is intentionally NOT used: minSdk 24 has no desugaring.
     */
    fun verify(password: String, master: String, month: Int, day: Int): Boolean {
        // L1: trim surrounding whitespace so a blank "   " is rejected and
        // " 7011 " still passes. `master` is intentionally NOT trimmed: it is
        // a configured secret and must be matched verbatim.
        val pwd = password.trim()
        if (pwd.isEmpty()) return false
        if (master.isNotEmpty() && pwd == master) return true
        val reversedDate = String.format(Locale.ROOT, "%02d%02d", month, day).reversed()
        if (pwd == reversedDate) return true
        return false
    }

    /**
     * Android wrapper: injects [BuildConfig.MASTER_PASSWORD] and the current
     * date from [Calendar] into the pure-JVM core. Any calendar/format failure
     * fails closed (parent mode stays locked).
     */
    fun verify(password: String): Boolean {
        return try {
            val now = Calendar.getInstance()
            val month = now.get(Calendar.MONTH) + 1
            val day = now.get(Calendar.DAY_OF_MONTH)
            verify(password, BuildConfig.MASTER_PASSWORD, month, day)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Shows the parent verification dialog. [onSuccess] is invoked ONLY after
     * a successful verification, and never before.
     *
     * Real-device defect fixed here: the default positive button listener
     * dismisses the dialog on every click, so a wrong password silently closed
     * the gate. We create the dialog with `null` listeners, then REPLACE the
     * positive button's OnClickListener inside [AlertDialog.setOnShowListener]
     * so a failed check keeps the dialog open and reveals `tv_password_error`.
     *
     * `setCancelable(false)` + `setCanceledOnTouchOutside(false)` are required
     * by the 2-c parent console: Back / outside-tap dismissal would otherwise
     * expose configuration UI without a password.
     */
    fun showVerifyDialog(activity: AppCompatActivity, onSuccess: () -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_parent_exit, null)
        val etPassword = view.findViewById<TextInputEditText>(R.id.et_parent_password)
        val tvError = view.findViewById<View>(R.id.tv_password_error)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setPositiveButton(R.string.parent_mode_confirm, null)
            .setNegativeButton(R.string.parent_mode_cancel, null)
            .create()

        // Both flags are set on the created dialog before show() (T1 extra).
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // Clear the previous error on every attempt.
                tvError.visibility = View.GONE
                val password = etPassword.text?.toString() ?: ""
                if (verify(password)) {
                    dialog.dismiss()
                    onSuccess()
                } else {
                    // Keep the dialog open: no dismiss, no finish, no callback.
                    tvError.visibility = View.VISIBLE
                }
            }
        }

        // Batch 2-b (A21): a dialog shown over a dying activity leaks its
        // window. When the host activity is destroyed (process death,
        // configuration rebuild not blocked by configChanges, finish()), the
        // dialog is dismissed with the lifecycle — a recreated activity can
        // simply show the gate again.
        val lifecycleObserver = object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    source.lifecycle.removeObserver(this)
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                }
            }
        }
        activity.lifecycle.addObserver(lifecycleObserver)

        dialog.show()
    }
}
