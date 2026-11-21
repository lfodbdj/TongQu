package com.antidip.ui

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.antidip.R

/**
 * UI v3 底部导航（2026-11-21，按确认的设计稿实现）：
 * 白底圆角条 + 四项（emoji 图标 + 文字），选中项 = 图标外包深绿胶囊、
 * 文字深绿。设计稿的胶囊样式 Material 离线 AAR 属性链接不可用，故自绘。
 * 首页与设置页共用，保证两页导航外观/行为完全一致。
 */
object NavBar {

    data class Item(val id: Int, val emoji: String, val label: String)

    val ITEMS = listOf(
        Item(R.id.nav_home, "🏠", "首页"),
        Item(R.id.nav_pointsbadges, "🏅", "积分徽章"),
        Item(R.id.nav_wrong, "📕", "错题"),
        Item(R.id.nav_settings, "⚙️", "设置"),
    )

    private fun dp(activity: Activity, v: Int): Int =
        (activity.resources.displayMetrics.density * v).toInt()

    /** 构建导航条；onSelect 返回 true = 保持当前选中（如打开新页），false = 移动胶囊。 */
    fun build(
        activity: Activity,
        selectedId: Int,
        onSelect: (Int) -> Boolean
    ): LinearLayout {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 14))
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(activity, 20).toFloat()
            }
        }
        ITEMS.forEach { item ->
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                tag = item.id
                setOnClickListener {
                    // 只有真正切页时才移动胶囊；打开新页（返回 true）时保持
                    // 原选中——否则返回后"图标和文字"会对应错（用户反馈）。
                    if (!onSelect(item.id)) bind(bar, item.id)
                }
            }
            val icon = TextView(activity).apply {
                text = item.emoji
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
            }
            val label = TextView(activity).apply {
                text = item.label
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                // 竖向 LinearLayout 默认给无参子项 MATCH_PARENT 宽 + 默认左对齐，
                // 文字会偏在图标左侧——必须显式居中（用户反馈 2026-11-21）。
                gravity = Gravity.CENTER
            }
            col.addView(icon, LinearLayout.LayoutParams(dp(activity, 46), dp(activity, 27)))
            col.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(activity, 1) })
            bar.addView(col, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        bind(bar, selectedId)
        return bar
    }

    /** 重新高亮：选中项图标包深绿胶囊、文字深绿；其余灰。 */
    fun bind(bar: LinearLayout, selectedId: Int) {
        val activity = bar.context as Activity
        for (i in 0 until bar.childCount) {
            val col = bar.getChildAt(i) as LinearLayout
            val id = col.tag as Int
            val on = id == selectedId
            val icon = col.getChildAt(0) as TextView
            val label = col.getChildAt(1) as TextView
            if (on) {
                icon.background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(activity, R.color.primary))
                    cornerRadius = dp(activity, 999).toFloat()
                }
                label.setTextColor(ContextCompat.getColor(activity, R.color.primary))
            } else {
                icon.background = null
                label.setTextColor(0xFF8A9AA0.toInt())
            }
        }
    }
}
