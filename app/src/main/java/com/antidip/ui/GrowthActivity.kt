package com.antidip.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.antidip.AppConfig
import com.antidip.Constants
import com.antidip.ProgressRules
import com.antidip.QuestionBankManager
import com.antidip.R
import com.antidip.ScreenMonitorService
import com.antidip.data.BackupHelper
import com.antidip.data.ProgressStore
import kotlinx.coroutines.launch

/**
 * V2.2 童趣主页 — dark green theme matching the installed 童趣 reference
 * (deep-black bg #1A1A1A, dark-gray cards #1E1E1E, lime-green accents) with a
 * green→lime→orange gradient banner.
 *
 * Bottom navigation (3 tabs, per requirement "去掉成长乐园，将成长乐园内容做到
 * 导航页内"):
 *   首页      昵称/年级横幅 + 2x2 统计 + 主动答题 + 成长概况 + 已获得荣誉（原成长乐园全部内容）
 *   错题本    各科错题（按科目筛选，学习外为常识）
 *   设置      打开家长设置页（全部管理功能集中在此）
 *
 * No "童趣" title bar; APP 启动自动启动守护服务 (onResume, 不清家长暂停).
 */
class GrowthActivity : AppCompatActivity() {

    companion object {
        /** 设置页返回时要切到的 tab（SettingsActivity 底部导航回跳用）。 */
        internal var pendingTab: Int = 0
    }

    private lateinit var bottomNav: LinearLayout

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    private fun bannerGradient3(start: Int, mid: Int, end: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, mid, end))
            .apply { cornerRadius = dp(22).toFloat() }

    private fun bannerGradient2(start: Int, end: Int): GradientDrawable =
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

    private fun bold(t: TextView): TextView = t.apply { setTypeface(null, Typeface.BOLD) }

    private fun childParams(topMarginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(topMarginDp) }

    private lateinit var bannerTitle: TextView
    private lateinit var bannerGrade: TextView
    /** V2.5.1 横幅副标题（名字下方）+ 无所不知成就 chip（年级后方）。 */
    private lateinit var bannerSubtitle: TextView
    private var achvChip: TextView? = null
    private lateinit var pagesRoot: FrameLayout
    private var homePoints: TextView? = null
    private var homeStreak: TextView? = null
    private var homeWrong: TextView? = null
    private var reviewCardSub: TextView? = null
    private var achvTitle: TextView? = null
    private var achvNext: TextView? = null
    private var achvBarBg: LinearLayout? = null
    private var achvBarFill: View? = null
    private var achvBarEmpty: View? = null
    private var badgesBoxPoints: LinearLayout? = null
    /** V2.5 本年级科目完成徽章容器。 */
    private var subjectBadgesBox: LinearLayout? = null
    private var subjectBadgesEmpty: TextView? = null
    private var wrongCountBadge: TextView? = null
    private var pointsHero: TextView? = null
    private var pointsLevel: TextView? = null
    private var heroBarFill: View? = null
    private var heroBarEmpty: View? = null
    private var heroCapView: TextView? = null
    private var wrongListWrap: LinearLayout? = null
    private var wrongChips: LinearLayout? = null

    private var wrongFilter: String = "all"

    /** 错题卡显示层兜底：旧记录若存的是选项字母，按题库解析成选项内容。 */
    private val bankManager = QuestionBankManager(this)

    private val schoolSubjects = mapOf(
        "chinese" to "语文", "math" to "数学", "english" to "英语",
        "science" to "科学", "moral" to "道法"
    )

    /** 学科 chip → filter key; anything outside the five subjects is 常识. */
    private fun subjectKeyOf(raw: String): String =
        if (raw in schoolSubjects.keys) raw else "common"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF6F7F2.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroller = ScrollView(this).apply {
            setBackgroundColor(0xFFF6F7F2.toInt())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroller.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(scroller, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ---- banner: green gradient, nickname + grade, gear -> settings ----
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = bannerGradient3(
                0xFF39862E.toInt(), 0xFF94D411.toInt(), 0xFFFFB400.toInt())
            setOnClickListener { editNickname() }
        }
        bannerTitle = bold(TextView(this).apply {
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
        })
        bannerGrade = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            // 设计稿：年级为白色半透明胶囊 chip
            background = GradientDrawable().apply {
                setColor(0x38FFFFFF)
                cornerRadius = dp(999).toFloat()
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        val bannerCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bannerCol.addView(bannerTitle)
        // V2.5.1：副标题行（名字下方）——解锁「无所不知」后替换为成就文案
        bannerSubtitle = TextView(this).apply {
            text = getString(R.string.growth_banner_sub)
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        }
        bannerCol.addView(bannerSubtitle)
        // 年级 chip 行：成就 chip 排在年级 chip 后方（V2.5.1）
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        achvChip = TextView(this).apply {
            text = getString(R.string.growth_omniscient_chip)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.primary))
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(999).toFloat()
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
            visibility = View.GONE
        }
        chipRow.addView(bannerGrade, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        chipRow.addView(achvChip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) })
        bannerCol.addView(chipRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        banner.addView(bannerCol, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        banner.addView(ImageView(this).apply {
            setImageResource(R.drawable.mascot_fox)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        box.addView(banner)

        // ---- pages container (bottom nav switches 首页/积分徽章/错题本) ----
        pagesRoot = FrameLayout(this)
        val homePage = buildHomePage().apply { tag = R.id.nav_home }
        val pointsBadgesPage = buildPointsBadgesPage().apply { tag = R.id.nav_pointsbadges }
        val wrongPage = buildWrongPage().apply { tag = R.id.nav_wrong }
        pagesRoot.addView(homePage, full())
        pagesRoot.addView(pointsBadgesPage, full())
        pagesRoot.addView(wrongPage, full())
        box.addView(pagesRoot, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- bottom navigation (UI v3：白底 + 选中项深绿胶囊，自绘) ----
        bottomNav = NavBar.build(this, R.id.nav_home) { target ->
            // Overlay 答题不经过 Activity 生命周期，切页时重读积分/徽章/错题，
            // 避免悬浮窗答完题后底下的页面一直显示旧数据。
            lifecycleScope.launch { refreshAll() }
            when (target) {
                // 打开设置页：本页内容不变 → 返回 true 保持胶囊在原页，
                // 返回后 onResume 会按实际页面恢复高亮（防"图标和文字不对应"）。
                R.id.nav_settings -> {
                    startActivity(
                        Intent(this@GrowthActivity, SettingsActivity::class.java)
                    )
                    true
                }
                else -> {
                    showPage(target)
                    false
                }
            }
        }
        root.addView(bottomNav, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        // FrameLayout pages all start VISIBLE — show the home tab only.
        showPage(R.id.nav_home)

        lifecycleScope.launch { refreshAll() }
    }

    private fun full(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

    /** 当前可见页 id——导航高亮永远跟随它（返回后恢复，防错位）。 */
    private var currentPageId: Int = R.id.nav_home

    private fun showPage(id: Int) {
        currentPageId = id
        for (i in 0 until pagesRoot.childCount) {
            val child = pagesRoot.getChildAt(i)
            child.visibility = if ((child.tag as? Int) == id) View.VISIBLE else View.GONE
        }
    }

    // ---------------- 首页 (UI v3：一屏四块，孩子一眼知道"现在做什么") ----------------

    private fun buildHomePage(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 今日三格：积分 / 连对 / 错题（白卡内浅灰 pill，点击直达对应导航页）
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        homePoints = statCell(row1, "⭐", "积分", R.id.nav_pointsbadges)
        homeStreak = statCell(row1, "🔥", "连对", R.id.nav_pointsbadges)
        homeWrong = statCell(row1, "📕", "错题", R.id.nav_wrong)
        val statsCard = card().apply { addView(row1) }
        page.addView(statsCard, childParams(10))

        // 主行动卡：开始主动答题（亮绿渐变 + 双行文案，设计稿样式）
        val cta = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bannerGradient2(
                0xFF9BEA00.toInt(), 0xFFB3EE00.toInt())
                .apply { cornerRadius = dp(18).toFloat() }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener {
                startActivity(Intent(this@GrowthActivity, PracticeActivity::class.java))
            }
        }
        cta.addView(TextView(this).apply {
            text = getString(R.string.growth_cta_title)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF121212.toInt())
            gravity = Gravity.CENTER
        })
        cta.addView(TextView(this).apply {
            text = getString(R.string.growth_cta_sub)
            textSize = 12f
            setTextColor(0xFF121212.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        })
        page.addView(cta, childParams(12))

        // 拍照录题入口（用户要求 2026-11-25：放到首页，不再只在家长控制台）。
        // 与 CTA 并列，白卡 + 图标 + 双行文案。进入拍照录题页（该页本身做复核）。
        val photoCard = card().apply {
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), ContextCompat.getColor(this@GrowthActivity, R.color.divider))
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener {
                startActivity(Intent(this@GrowthActivity, PhotoQuizActivity::class.java))
            }
        }
        val photoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        photoRow.addView(TextView(this).apply {
            text = "📷"
            textSize = 22f
        })
        photoRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
        }.also { col ->
            col.addView(TextView(this).apply {
                text = getString(R.string.home_photo_title)
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.on_surface))
            })
            col.addView(TextView(this).apply {
                text = getString(R.string.home_photo_sub)
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
        })
        photoRow.addView(TextView(this).apply {
            text = "›"
            textSize = 20f
            setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary))
        })
        photoCard.addView(photoRow)
        page.addView(photoCard, childParams(10))

        // 错题复习行动卡（点击直达错题页）
        val reviewCard = card().apply {
            // UI v3 复习行动卡：绿色描边（设计稿样式）
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), ContextCompat.getColor(this@GrowthActivity, R.color.primary))
            }
            setOnClickListener {
                NavBar.bind(bottomNav, R.id.nav_wrong)
                showPage(R.id.nav_wrong)
            }
        }
        val reviewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        reviewRow.addView(TextView(this).apply {
            text = "📕"
            textSize = 20f
        })
        reviewRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }.also { col ->
            col.addView(TextView(this).apply {
                text = getString(R.string.growth_wrong_title)
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.on_surface))
            })
            val sub = TextView(this).apply {
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary))
            }
            reviewCardSub = sub
            col.addView(sub, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        })
        reviewRow.addView(TextView(this).apply {
            text = "›"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.primary))
        })
        reviewCard.addView(reviewRow)
        page.addView(reviewCard, childParams(10))

        // 最新成就卡：最近解锁的勋章 + 下一枚进度条
        val achvCard = card()
        achvTitle = TextView(this).apply {
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.on_surface))
            text = getString(R.string.achv_empty)
        }
        achvCard.addView(achvTitle)
        achvNext = TextView(this).apply {
            textSize = 11.5f
            setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary))
            visibility = android.view.View.GONE
        }
        achvCard.addView(achvNext, childParams(8))
        achvBarBg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0xFFEFF1E8.toInt())
                cornerRadius = dp(4).toFloat()
            }
        }
        achvBarFill = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF94D411.toInt(), 0xFF3E9B1E.toInt()))
                .apply { cornerRadius = dp(4).toFloat() }
        }
        achvBarEmpty = View(this)
        achvBarBg?.addView(achvBarFill, LinearLayout.LayoutParams(0, dp(8), 0.001f))
        achvBarBg?.addView(achvBarEmpty, LinearLayout.LayoutParams(0, dp(8), 1f))
        achvCard.addView(achvBarBg, childParams(6))
        page.addView(achvCard, childParams(10))

        return page
    }

    // ---------------- 积分徽章 tab (UI v3：等级 hero + 勋章墙) ----------------

    private fun buildPointsBadgesPage(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(sectionLabel(getString(R.string.growth_points_badges_title)))
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = bannerGradient2(0xFF9BEA00.toInt(), 0xFF39862E.toInt())
        }
        val heroLv = bold(TextView(this).apply {
            textSize = 30f
            setTextColor(0xFF12240B.toInt())
            gravity = Gravity.CENTER
        })
        pointsLevel = heroLv
        hero.addView(heroLv)
        val heroNum = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF12240B.toInt())
            gravity = Gravity.CENTER
        }
        pointsHero = heroNum
        hero.addView(heroNum, childParams(2))

        // 等级进度条（每 10 分一级）
        val heroBarBg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0x55FFFFFF)
                cornerRadius = dp(4).toFloat()
            }
        }
        heroBarFill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFFFFFBEB.toInt())
                cornerRadius = dp(4).toFloat()
            }
        }
        heroBarEmpty = View(this)
        heroBarBg.addView(heroBarFill, LinearLayout.LayoutParams(0, dp(8), 0.001f))
        heroBarBg.addView(heroBarEmpty, LinearLayout.LayoutParams(0, dp(8), 1f))
        hero.addView(heroBarBg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        val heroCap = TextView(this).apply {
            textSize = 11.5f
            setTextColor(0xFF12240B.toInt())
            gravity = Gravity.CENTER
        }
        heroCapView = heroCap
        hero.addView(heroCap, childParams(6))
        page.addView(hero, childParams(6))

        page.addView(sectionLabel(getString(R.string.growth_medal_wall_title)))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        badgesBoxPoints = box
        page.addView(box, childParams(4))

        // V2.5 本年级科目完成徽章（各科一枚，按累计答对 20 题解锁）
        page.addView(sectionLabel(getString(R.string.growth_subject_badges_title)))
        val sbox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        subjectBadgesBox = sbox
        page.addView(sbox, childParams(4))
        subjectBadgesEmpty = TextView(this).apply {
            text = getString(R.string.growth_subject_badges_all_grade)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary))
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        page.addView(subjectBadgesEmpty, childParams(2))

        page.addView(sectionLabel(getString(R.string.growth_points_title)))
        val rules = card()
        listOf(
            getString(R.string.growth_rule_choice),
            getString(R.string.growth_rule_fill),
            getString(R.string.growth_rule_wrong),
            getString(R.string.growth_rule_bonus),
            getString(R.string.growth_rule_book),
            getString(R.string.growth_rule_retry),
            getString(R.string.growth_rule_retry_score)
        ).forEach { rule ->
            rules.addView(TextView(this).apply {
                text = "✦ $rule"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.on_surface))
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        page.addView(rules, childParams(8))
        return page
    }

    private fun statCell(row: LinearLayout, emoji: String, label: String, targetTab: Int): TextView {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFFF3F4EC.toInt())
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener {
                NavBar.bind(bottomNav, targetTab)
                showPage(targetTab)
            }
        }
        val value = bold(TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.on_surface))
            text = "0"
        })
        cell.addView(value)
        cell.addView(TextView(this).apply {
            text = "$emoji $label"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary))
        })
        row.addView(cell, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (row.childCount > 0) marginEnd = dp(8)
        })
        return value
    }

    private fun margin(dpV: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(dpV) }

    // ---------------- 错题本 tab ----------------

    private fun buildWrongPage(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // UI v3：标题 + 数量徽章（refreshWrongList 更新数字）
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(sectionLabel(getString(R.string.growth_wrong_title)))
        wrongCountBadge = TextView(this).apply {
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@GrowthActivity, R.color.primary))
                cornerRadius = dp(999).toFloat()
            }
            setPadding(dp(8), dp(1), dp(8), dp(1))
            text = "0"
        }
        titleRow.addView(wrongCountBadge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(8) })
        page.addView(titleRow)
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        wrongChips = chipsRow
        val chips = mutableListOf<Pair<String, String>>(
            "all" to getString(R.string.wrong_filter_all)
        )
        schoolSubjects.forEach { (k, v) -> chips.add(k to v) }
        chips.add("common" to getString(R.string.wrong_filter_common))
        chips.forEach { (key, label) ->
            chipsRow.addView(bold(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                tag = key
                setOnClickListener {
                    wrongFilter = key
                    refreshWrongList()
                    highlightChips()
                }
            }), LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        page.addView(chipsRow, childParams(6))
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrongListWrap = wrap
        page.addView(wrap, childParams(10))

        // 设计稿：消除规则提示卡
        val hint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xFFF3F4EC.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        hint.addView(TextView(this).apply {
            text = getString(R.string.growth_wrong_hint)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary))
        })
        page.addView(hint, childParams(10))
        return page
    }

    private fun highlightChips() {
        val chips = wrongChips ?: return
        for (i in 0 until chips.childCount) {
            val tv = chips.getChildAt(i) as TextView
            val on = (tv.tag as String) == wrongFilter
            val bg = GradientDrawable().apply {
                setColor(
                    if (on) ContextCompat.getColor(this@GrowthActivity, R.color.primary)
                    else 0xFFEDEDE8.toInt()
                )
                cornerRadius = dp(16).toFloat()
            }
            tv.background = bg
            tv.setTextColor(
                // 选中 chip 现在是深绿底 → 白字；未选中浅灰底灰字
                if (on) 0xFFFFFFFF.toInt()
                else ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary)
            )
        }
    }

    // ---------------- data refresh ----------------

    private suspend fun refreshAll() {
        val st = ProgressStore.getState(this)
        val nick = BackupHelper.getNickname(this).ifBlank {
            getString(R.string.growth_default_nickname)
        }
        bannerTitle.text = getString(R.string.growth_banner_nick, nick)
        val grade = AppConfig.getSelectedGrade(this)
        bannerGrade.text = if (grade == Constants.ALL_GRADES) {
            getString(R.string.growth_banner_grade_chip_all)
        } else {
            getString(R.string.growth_banner_grade_chip, grade)
        }

        homePoints?.text = "${st.points}"
        homeStreak?.text = "${st.streak}"
        val wrongCount = ProgressStore.getWrongBook(this).size
        homeWrong?.text = "$wrongCount"
        reviewCardSub?.text = if (wrongCount > 0) {
            getString(R.string.review_sub_fmt, wrongCount)
        } else {
            getString(R.string.review_sub_none)
        }

        // 最新成就卡（首页）：最近解锁 + 下一枚进度
        // V2.4：勋章解锁状态按当前积分/连对**动态判定**——新增徽章无需等下次
        // 答题触发 applyAnswer 才亮，渲染时立即反映（持久化集合照旧在答题时写）。
        val dynamicMedals = ProgressRules.evaluateMedals(st.medals, st.points, st.streak)
        val earnedIdx = ProgressRules.MEDALS.indexOfLast { it.first in dynamicMedals }
        if (earnedIdx >= 0) {
            achvTitle?.text = getString(
                R.string.achv_latest_fmt, ProgressRules.MEDALS[earnedIdx].second
            )
        } else {
            achvTitle?.text = getString(R.string.achv_empty)
        }
        val nextMedal = ProgressRules.MEDALS.firstOrNull { it.first !in dynamicMedals }
        if (nextMedal == null) {
            achvNext?.visibility = android.view.View.GONE
            achvBarBg?.visibility = android.view.View.GONE
        } else {
            achvNext?.visibility = android.view.View.VISIBLE
            achvBarBg?.visibility = android.view.View.VISIBLE
            val target = ProgressRules.conditionTarget(nextMedal.first)
            val current = when (target?.first) {
                "points" -> st.points
                "streak" -> st.streak
                else -> 0
            }
            val goal = target?.second ?: 1
            achvNext?.text = getString(
                R.string.achv_next_fmt, nextMedal.second,
                current.coerceAtMost(goal), goal
            )
            val fraction = (current.toDouble() / goal).coerceIn(0.0, 1.0)
            (achvBarFill?.layoutParams as? LinearLayout.LayoutParams)?.weight =
                if (fraction <= 0) 0.001f else fraction.toFloat()
            (achvBarEmpty?.layoutParams as? LinearLayout.LayoutParams)?.weight =
                (1.0 - fraction).toFloat().let { if (it <= 0) 0.001f else it }
            achvBarBg?.requestLayout()
        }

        // 积分徽章页 hero 卡（等级每 10 分一级，与积分规则一致）
        val level = st.points / 10 + 1
        val toNext = level * 10 - st.points
        pointsLevel?.text = getString(R.string.growth_points_lv_fmt, level)
        pointsHero?.text = getString(R.string.growth_points_hero2, st.points)
        heroCapView?.text = getString(R.string.growth_points_cap, level + 1, toNext)
        val lvFraction = (st.points % 10) / 10.0
        (heroBarFill?.layoutParams as? LinearLayout.LayoutParams)?.weight =
            if (lvFraction <= 0) 0.001f else lvFraction.toFloat()
        (heroBarEmpty?.layoutParams as? LinearLayout.LayoutParams)?.weight =
            (1.0 - lvFraction).toFloat().let { if (it <= 0) 0.001f else it }
        heroBarFill?.requestLayout()
        heroBarEmpty?.requestLayout()

        // 勋章墙（4×2）：已解锁绿边亮显，未解锁灰显
        badgesBoxPoints?.let { box ->
            box.removeAllViews()
            var row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            ProgressRules.MEDALS.forEachIndexed { idx, (id, emoji, name) ->
                val earned = id in dynamicMedals
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(10), dp(4), dp(10))
                    background = GradientDrawable().apply {
                        if (earned) {
                            setColor(0xFFF4FAEF.toInt())
                            setStroke(dp(1), ContextCompat.getColor(this@GrowthActivity, R.color.primary))
                        } else {
                            setColor(0xFFFFFFFF.toInt())
                            setStroke(dp(1), ContextCompat.getColor(this@GrowthActivity, R.color.divider))
                        }
                        cornerRadius = dp(14).toFloat()
                    }
                }
                cell.addView(TextView(this).apply {
                    text = if (earned) emoji else "🔒"
                    textSize = 17f
                    gravity = Gravity.CENTER
                    alpha = if (earned) 1f else 0.55f
                })
                cell.addView(TextView(this).apply {
                    text = name
                    textSize = 10.5f
                    gravity = Gravity.CENTER
                    setTypeface(null, if (earned) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(
                        ContextCompat.getColor(
                            this@GrowthActivity,
                            if (earned) R.color.on_surface else R.color.text_secondary
                        )
                    )
                })
                cell.addView(TextView(this).apply {
                    text = ProgressRules.conditionOf(id)
                    textSize = 9f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary))
                })
                row.addView(cell, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                if (idx % 4 == 3) {
                    box.addView(row, childParams(6))
                    row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                }
            }
            if (row.childCount > 0) box.addView(row, childParams(6))
        }

        // V2.5.1 本年级科目完成徽章：仅展示该年级题库中实际存在的科目，
        // 「答对该科一半以上」绿显（V2.5.1，用户要求），未达标灰显并显示进度。
        val selGrade = AppConfig.getSelectedGrade(this)
        if (selGrade == Constants.ALL_GRADES) {
            subjectBadgesBox?.visibility = android.view.View.GONE
            subjectBadgesEmpty?.visibility = View.VISIBLE
        } else {
            subjectBadgesBox?.visibility = View.VISIBLE
            subjectBadgesEmpty?.visibility = View.GONE
            val bankCounts = runCatching { bankManager.getGradeSubjectCounts(selGrade) }
                .getOrDefault(emptyMap())
            val answeredCounts = runCatching { bankManager.getGradeSubjectCorrectCounts(selGrade) }
                .getOrDefault(emptyMap())
            subjectBadgesBox?.let { sbox ->
                sbox.removeAllViews()
                var srow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                var sIdx = 0
                ProgressRules.SUBJECT_BADGES.forEach { (subject, meta) ->
                    val bankN = bankCounts[subject] ?: 0
                    if (bankN <= 0) return@forEach
                    val (emoji, name) = meta
                    val got = answeredCounts[subject] ?: 0
                    val earned = ProgressRules.subjectBadgeEarned(got, bankN)
                    val target = ProgressRules.subjectBadgeTarget(bankN)
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(4), dp(10), dp(4), dp(10))
                        background = GradientDrawable().apply {
                            if (earned) {
                                setColor(0xFFF4FAEF.toInt())
                                setStroke(dp(1), ContextCompat.getColor(this@GrowthActivity, R.color.primary))
                            } else {
                                setColor(0xFFFFFFFF.toInt())
                                setStroke(dp(1), ContextCompat.getColor(this@GrowthActivity, R.color.divider))
                            }
                            cornerRadius = dp(14).toFloat()
                        }
                    }
                    cell.addView(TextView(this).apply {
                        text = if (earned) emoji else "🔒"
                        textSize = 17f
                        gravity = Gravity.CENTER
                        alpha = if (earned) 1f else 0.55f
                    })
                    cell.addView(TextView(this).apply {
                        text = name
                        textSize = 10.5f
                        gravity = Gravity.CENTER
                        setTypeface(null, if (earned) Typeface.BOLD else Typeface.NORMAL)
                        setTextColor(
                            ContextCompat.getColor(
                                this@GrowthActivity,
                                if (earned) R.color.on_surface else R.color.text_secondary
                            )
                        )
                    })
                    cell.addView(TextView(this).apply {
                        text = if (earned) {
                            "${selGrade}年级完成"
                        } else {
                            "$got/$target"
                        }
                        textSize = 9f
                        gravity = Gravity.CENTER
                        setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary))
                    })
                    srow.addView(cell, LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                    if (sIdx % 4 == 3) {
                        sbox.addView(srow, childParams(6))
                        srow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    }
                    sIdx++
                }
                if (srow.childCount > 0) sbox.addView(srow, childParams(6))
            }
        }

        // V2.5.1 无所不知成就：本年级题库全部答对过（answered == total > 0）
        // → 横幅名字下方副标题 + 年级 chip 后方显示「🏆 无所不知」。
        runCatching { bankManager.getGradeAnsweredProgress(selGrade) }
            .getOrDefault(0 to 0)
            .let { (answered, total) ->
                val omniscient = selGrade != Constants.ALL_GRADES && total > 0 && answered >= total
                bannerSubtitle?.text = if (omniscient) {
                    getString(R.string.growth_omniscient_sub)
                } else {
                    getString(R.string.growth_banner_sub)
                }
                achvChip?.visibility = if (omniscient) View.VISIBLE else View.GONE
            }

        refreshWrongList()
        highlightChips()
    }

    private fun refreshWrongList() {
        val wrap = wrongListWrap ?: return
        wrap.removeAllViews()
        lifecycleScope.launch {
            val book = ProgressStore.getWrongBook(this@GrowthActivity)
            val filtered = when (wrongFilter) {
                "all" -> book
                else -> book.filter { subjectKeyOf(it.subject) == wrongFilter }
            }
            if (filtered.isEmpty()) {
                wrap.addView(TextView(this@GrowthActivity).apply {
                    text = getString(R.string.growth_wrong_empty)
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.success))
                }, childParams(8))
                return@launch
            }
            wrongCountBadge?.text = book.size.toString()

            // 2026-11-25 修复"错题本每科只显示一条"：原实现在渲染循环内
            // 调用 suspend 查库（getQuestionByContentGrade），每次挂起/恢复都
            // 可能被打断，导致只渲染最后一条。改为先在协程里一次性预解析
            // 答案文本（顺带按题库解析旧记录里的选项字母），随后同步一次性
            // 渲染全部条目，循环内不再有挂起点。
            val oldAnswerLookup = HashMap<String, String>()
            for (e in filtered) {
                if (e.answer.trim().length == 1 &&
                    "ABCD".contains(e.answer.trim().uppercase())
                ) {
                    val q = bankManager.getQuestionByContentGrade(e.content, e.grade)
                    if (q != null) {
                        oldAnswerLookup[e.content] = ProgressRules.answerResultText(
                            q.answer, q.options?.split("|") ?: emptyList()
                        )
                    }
                }
            }

            filtered.asReversed().forEach { e ->
                val key = subjectKeyOf(e.subject)
                val subjectLabel = if (key == "common") {
                    getString(R.string.wrong_filter_common)
                } else {
                    schoolSubjects[e.subject] ?: e.subject
                }
                val answerText = oldAnswerLookup[e.content] ?: e.answer

                // UI v3：错题卡（左侧绿条 + 科目标签 / 题干 / 正确答案 /
                // 错误次数 / 重做按钮），设计稿样式。卡片底由 row 提供。
                val item = LinearLayout(this@GrowthActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                item.addView(TextView(this@GrowthActivity).apply {
                    text = subjectLabel
                    textSize = 10.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.primary))
                    background = GradientDrawable().apply {
                        setColor(0xFFEAF4E3.toInt())
                        cornerRadius = dp(6).toFloat()
                    }
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                })
                item.addView(TextView(this@GrowthActivity).apply {
                    text = e.content
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.on_surface))
                    setPadding(0, dp(8), 0, 0)
                })
                if (answerText.isNotBlank()) {
                    item.addView(TextView(this@GrowthActivity).apply {
                        text = getString(R.string.growth_wrong_answer_fmt, answerText)
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.primary))
                        setPadding(0, dp(8), 0, 0)
                    })
                }
                item.addView(TextView(this@GrowthActivity).apply {
                    text = getString(
                        R.string.growth_wrong_meta_fmt, e.wrongCount,
                        java.text.SimpleDateFormat("MM-dd", java.util.Locale.ROOT)
                            .format(java.util.Date(e.lastWrongAt))
                    )
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.text_secondary))
                    setPadding(0, dp(4), 0, 0)
                })
                if (e.answer.isNotBlank()) {
                    item.addView(TextView(this@GrowthActivity).apply {
                        text = getString(R.string.redo_question)
                        textSize = 12.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.primary))
                        background = GradientDrawable().apply {
                            setColor(0x00000000)
                            setStroke(dp(1), ContextCompat.getColor(this@GrowthActivity, R.color.primary))
                            cornerRadius = dp(999).toFloat()
                        }
                        setPadding(dp(14), dp(6), dp(14), dp(6))
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.topMargin = dp(10)
                        layoutParams = lp
                        setOnClickListener {
                            val i = Intent(this@GrowthActivity, PracticeActivity::class.java)
                            i.putExtra("focus_content", e.content)
                            i.putExtra("focus_grade", e.grade)
                            startActivity(i)
                        }
                    })
                }
                // 左侧绿色竖条（设计稿 border-left 4px）
                val row = LinearLayout(this@GrowthActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = GradientDrawable().apply {
                        setColor(0xFFFBFDF7.toInt())
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(1), ContextCompat.getColor(this@GrowthActivity, R.color.divider))
                    }
                }
                row.addView(View(this@GrowthActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT)
                    background = GradientDrawable().apply {
                        cornerRadii = floatArrayOf(
                            dp(14).toFloat(), dp(14).toFloat(),
                            0f, 0f, 0f, 0f,
                            dp(14).toFloat(), dp(14).toFloat()
                        )
                        setColor(ContextCompat.getColor(this@GrowthActivity, R.color.primary))
                    }
                })
                // 2026-11-25 修复"错题本只显示一条"：item 高度必须 WRAP_CONTENT。
                // 原为 MATCH_PARENT，而外层 row 是 WRAP_CONTENT 高，HORIZONTAL
                // LinearLayout 对 MATCH_PARENT 高子项在父高度未定时会量成 0，
                // 导致 41 行全部塌陷、只有首行可见。改为 WRAP_CONTENT 后每行按
                // 内容自然撑开，左侧竖条仍 MATCH_PARENT 以贴合行高。
                row.addView(item, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ))
                wrap.addView(row, childParams(8))
            }
        }
    }

    private fun editNickname() {
        val input = EditText(this).apply {
            setText(BackupHelper.getNickname(this@GrowthActivity))
            hint = getString(R.string.growth_default_nickname)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.growth_edit_nickname))
            .setView(input)
            .setPositiveButton(R.string.parent_mode_confirm) { _, _ ->
                val nick = input.text?.toString()?.trim().orEmpty()
                if (nick.isNotEmpty()) {
                    lifecycleScope.launch {
                        BackupHelper.setNickname(this@GrowthActivity, nick)
                        bannerTitle.text = getString(
                            R.string.growth_banner_nick,
                            BackupHelper.getNickname(this@GrowthActivity)
                                .ifBlank { getString(R.string.growth_default_nickname) }
                        )
                    }
                }
            }
            .setNegativeButton(R.string.parent_mode_cancel, null)
            .show()
    }

    private fun sectionLabel(text: String): TextView = bold(TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(ContextCompat.getColor(this@GrowthActivity, R.color.primary))
    })

    // ---------------- service autostart (V2.2) ----------------

    /**
     * V2.2 (requirement 2026-11-10/11: APP 启动自动启动守护服务): whenever the
     * home screen is visible and the guardian service is NOT running, start
     * it. Uses a plain start (no ACTION_RESUME) so a parent's temporary pause
     * is preserved — children cannot bypass the pause by reopening the app;
     * only the parent's settings button clears it.
     */
    override fun onResume() {
        super.onResume()
        // 导航高亮永远跟随实际页面：从设置页返回（无论走 pendingTab 还是
        // 直接返回）都恢复到当前页的胶囊，防"图标和文字不对应"。
        NavBar.bind(bottomNav, currentPageId)
        // 从设置页底部导航回跳：切到目标 tab（保持导航不消失的体验）。
        if (pendingTab != 0) {
            showPage(pendingTab)
            NavBar.bind(bottomNav, pendingTab)
            pendingTab = 0
            lifecycleScope.launch { refreshAll() }
        }
        if (!ScreenMonitorService.isRunning) {
            try {
                val intent = Intent(this, ScreenMonitorService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                // Startup can fail transiently (installing, persistent-stop) —
                // the next onResume retries; quiz flow is unaffected.
            }
        }
    }
}
