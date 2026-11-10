package com.antidip.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.antidip.ProgressRules
import com.antidip.R
import com.antidip.data.ProgressStore
import kotlinx.coroutines.launch

/**
 * V2.1 童趣 "成长乐园" — kawaii restyle per the approved mockup
 * (<用户目录>/WorkBuddy\2026-11-09-23-33-46\童趣_ui_mockups.html):
 * gradient banner with the fox mascot, stats row, the voluntary practice
 * entry (主动答题), the medal wall, the points rules and the wrong-question
 * book. Sections are stacked in one scroll page (mockup's five tabs folded
 * into sections — same content, phone-friendly for the API 29 test device).
 *
 * Read-only except for the practice entry; nothing here can change the
 * anti-addiction configuration.
 */
class GrowthActivity : AppCompatActivity() {

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    private fun bannerGradient(start: Int, end: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end))
            .apply { cornerRadius = dp(22).toFloat() }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun sectionTitle(text: String, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(color)
    }

    private fun childParams(topMarginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topMarginDp) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setBackgroundColor(0xFFFFF6EC.toInt()) // mockup cream background
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }
        root.addView(box)
        setContentView(root)

        // ---- kawaii banner (growth gradient + fox mascot) ----
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = bannerGradient(0xFFFFD3A5.toInt(), 0xFFFFAA6B.toInt())
        }
        val bannerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bannerText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bannerText.addView(TextView(this).apply {
            text = getString(R.string.growth_banner_title)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        })
        bannerText.addView(TextView(this).apply {
            text = getString(R.string.growth_banner_sub)
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        })
        bannerRow.addView(bannerText, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bannerRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.mascot_fox)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        banner.addView(bannerRow)
        box.addView(banner)

        // ---- stats card ----
        val stats = TextView(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.on_surface))
        }
        val statsCard = card().apply { addView(stats) }
        box.addView(statsCard, childParams(14))

        // ---- practice entry (V2.1 主动答题) ----
        val practiceBtn = Button(this).apply {
            text = getString(R.string.practice_entry)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            background = bannerGradient(0xFFFFAA6B.toInt(), 0xFFFF8E9E.toInt())
                .apply { cornerRadius = dp(18).toFloat() }
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener {
                startActivity(android.content.Intent(
                    this@GrowthActivity, PracticeActivity::class.java))
            }
        }
        box.addView(practiceBtn, childParams(12))

        // ---- medal wall ----
        box.addView(sectionTitle(
            getString(R.string.growth_medals_title),
            ContextCompat.getColor(this, R.color.accent)
        ), childParams(22))
        val medalsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(medalsBox)

        // ---- points rules ----
        box.addView(sectionTitle(
            getString(R.string.growth_points_title),
            ContextCompat.getColor(this, R.color.accent)
        ), childParams(22))
        val rules = card()
        listOf(
            getString(R.string.growth_rule_choice),
            getString(R.string.growth_rule_fill),
            getString(R.string.growth_rule_wrong),
            getString(R.string.growth_rule_book)
        ).forEach { rule ->
            rules.addView(TextView(this).apply {
                text = "✦ $rule"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.on_surface))
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        box.addView(rules, childParams(10))

        // ---- wrong-question book ----
        box.addView(sectionTitle(
            getString(R.string.growth_wrong_title),
            ContextCompat.getColor(this, R.color.accent)
        ), childParams(22))
        val wrongBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(wrongBox)

        box.addView(Button(this).apply {
            text = getString(R.string.growth_back)
            setOnClickListener { finish() }
        }, childParams(20))

        lifecycleScope.launch {
            val st = ProgressStore.getState(this@GrowthActivity)
            stats.text = getString(R.string.growth_stats, st.points, st.streak, st.bestStreak)

            ProgressRules.MEDALS.forEach { (id, emoji, name) ->
                val earned = id in st.medals
                medalsBox.addView(TextView(this@GrowthActivity).apply {
                    textSize = 15f
                    if (earned) {
                        text = "$emoji $name — ${ProgressRules.conditionOf(id)}"
                        setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.primary))
                    } else {
                        text = getString(
                            R.string.growth_medal_locked,
                            "$emoji $name", ProgressRules.conditionOf(id)
                        )
                        setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary))
                    }
                }, childParams(8))
            }

            val book = ProgressStore.getWrongBook(this@GrowthActivity)
            if (book.isEmpty()) {
                wrongBox.addView(TextView(this@GrowthActivity).apply {
                    text = getString(R.string.growth_wrong_empty)
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.success))
                }, childParams(8))
            } else {
                book.asReversed().forEach { e ->
                    val row = TextView(this@GrowthActivity).apply {
                        text = getString(
                            R.string.growth_wrong_item, e.content, e.answer, e.wrongCount
                        )
                        textSize = 14f
                        setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.on_surface))
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = GradientDrawable().apply {
                            setColor(0xFFFFF4F1.toInt())
                            cornerRadius = dp(14).toFloat()
                        }
                    }
                    wrongBox.addView(row, childParams(8))
                }
            }
        }
    }
}
