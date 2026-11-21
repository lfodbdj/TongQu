package com.antidip

import android.content.Context
import java.security.MessageDigest

/**
 * 独立扣减密码（2026-11-21 新增）：积分扣减（物品兑换 / 手动扣减）专用，
 * 与家长全局密码相互独立。设置页只存 SHA-256 哈希，明文不落盘。
 *
 * 修改授权（防遗忘，用户要求）：输入「旧扣减密码」或「家长全局密码」
 * （ParentAuth.verify）均可授权设置新密码。首次设置用全局密码授权。
 *
 * 安全边界：SettingsActivity 本身无全局密码门（与现有服务启停一致），
 * 扣减操作只认扣减密码——全局密码（含日期派生）不做扣减凭证。
 */
object DeductionAuth {

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun isSet(context: Context): Boolean =
        prefs(context).getString(Constants.KEY_DEDUCTION_HASH, null)?.isNotBlank() == true

    /** 设置/重置扣减密码；少于 4 位拒绝。 */
    fun set(context: Context, newPassword: String): Boolean {
        val pw = newPassword.trim()
        if (pw.length < 4) return false
        prefs(context).edit()
            .putString(Constants.KEY_DEDUCTION_HASH, sha256(pw))
            .apply()
        return true
    }

    /** 校验扣减密码；未设置或空输入一律拒绝。 */
    fun verify(context: Context, password: String): Boolean {
        val stored = prefs(context).getString(Constants.KEY_DEDUCTION_HASH, null) ?: return false
        if (stored.isBlank()) return false
        return sha256(password.trim()) == stored
    }
}
