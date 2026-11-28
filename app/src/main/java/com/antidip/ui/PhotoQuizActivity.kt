package com.antidip.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.antidip.AiJudge
import com.antidip.AppConfig
import com.antidip.Constants
import com.antidip.QuestionBankManager
import com.antidip.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * V2.4 拍照录题（用户要求 2026-11-24/28）：家长拍一张题目照片 → 拖动裁剪框
 * 精确框选题目区域 →（可选）AI 视觉模型识别 → 复核后**一律转成填空题**存入题库。
 *
 * 流程与设计：
 *  - 拍照用系统相机（ActivityResultContracts.TakePicture + FileProvider），无需
 *    相机权限；
 *  - 拍完先进入裁剪模式（[CropOverlayView] 拖动框选），确认裁剪后才可 AI 识别
 *    ——裁剪能去掉页面杂物，识别更精确；「重新裁剪」随时回到原图重框；
 *  - AI 识别把当前（裁剪后的）图发给视觉模型；识别为选择题时，把正确选项的
 *    文字自动转换为填空答案（用户要求：转换成填空题添加）；
 *  - 保存走 [QuestionBankManager.addSingleQuestion]，type 固定 fill，按
 *    （题干, 年级）去重，年级取控制台当前所选年级；
 *  - 位图解码/缩放/编码全部在 IO 线程（V2.4 优化，避免大图 ANR）。
 *
 * 本页从成长乐园首页进入（V2.4 起不再放家长控制台）；不导出。
 */
class PhotoQuizActivity : AppCompatActivity() {

    private lateinit var bankManager: QuestionBankManager
    private lateinit var photoWrap: FrameLayout
    private lateinit var preview: ImageView
    private lateinit var cropOverlay: CropOverlayView
    private lateinit var cropHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnCrop: Button
    private lateinit var btnRecrop: Button
    private lateinit var btnExtract: Button
    private lateinit var formBox: LinearLayout
    private lateinit var spSubject: Spinner
    private lateinit var etContent: EditText
    private lateinit var etAnswer: EditText

    private var fullBitmap: Bitmap? = null    // 本次拍摄的原图（重新裁剪用）
    private var currentBitmap: Bitmap? = null // 当前预览位图（原图或裁剪后）
    private var photoFile: File? = null
    private var photoUri: Uri? = null

    private val grade: Int
        get() = AppConfig.getSelectedGrade(this).let {
            if (it == Constants.ALL_GRADES) 4 else it
        }

    private val subjectLabels = listOf("数学", "语文", "英语", "科学", "道法")
    private val subjectValues = listOf("math", "chinese", "english", "science", "moral")

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            tvStatus.text = getString(R.string.photo_capture_ok)
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    loadBitmapFromUri(photoUri!!)
                }
                if (bmp == null) {
                    tvStatus.text = getString(R.string.photo_read_fail)
                    return@launch
                }
                fullBitmap = bmp
                enterCropMode(bmp)
            }
        } else {
            tvStatus.text = getString(R.string.photo_capture_cancel)
        }
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bankManager = QuestionBankManager(this)

        val root = ScrollView(this).apply { setBackgroundColor(getColor(R.color.background)) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(box)
        setContentView(root)

        box.addView(TextView(this).apply {
            text = getString(R.string.photo_title)
            textSize = 22f
            paint.isFakeBoldText = true
            setTextColor(getColor(R.color.on_surface))
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.photo_sub)
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(6), 0, dp(12))
        })

        tvStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            text = getString(R.string.photo_hint_capture)
            setPadding(0, dp(4), 0, dp(10))
        }
        box.addView(tvStatus)

        btnCapture = Button(this).apply {
            text = getString(R.string.photo_capture)
            styleLime()
            setOnClickListener { launchCamera() }
        }
        box.addView(btnCapture, margin(6))

        // 照片区 + 裁剪浮层（拍照后显示；overlay 盖在预览上拖动框选）。
        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(getColor(R.color.surface))
                setStroke(dp(1), getColor(R.color.divider))
            }
        }
        cropOverlay = CropOverlayView(this).apply { visibility = View.GONE }
        photoWrap = FrameLayout(this).apply { visibility = View.GONE }
        photoWrap.addView(preview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        photoWrap.addView(cropOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        box.addView(photoWrap, margin(10))

        // 裁剪操作行：提示 + 确认裁剪 / 重新裁剪。
        cropHint = TextView(this).apply {
            text = getString(R.string.photo_crop_hint)
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(4))
            visibility = View.GONE
        }
        box.addView(cropHint)
        btnCrop = Button(this).apply {
            text = getString(R.string.photo_btn_crop)
            styleLime()
            visibility = View.GONE
            setOnClickListener { confirmCrop() }
        }
        box.addView(btnCrop, margin(4))
        btnRecrop = Button(this).apply {
            text = getString(R.string.photo_btn_recrop)
            styleLime()
            visibility = View.GONE
            setOnClickListener {
                fullBitmap?.let { enterCropMode(it) }
                tvStatus.text = getString(R.string.photo_crop_hint)
            }
        }
        box.addView(btnRecrop, margin(4))

        btnExtract = Button(this).apply {
            text = getString(R.string.photo_extract)
            styleLime()
            isEnabled = false
            setOnClickListener { runExtract() }
        }
        box.addView(btnExtract, margin(6))

        formBox = buildForm()
        box.addView(formBox, margin(8))
    }

    private fun buildForm(): LinearLayout {
        // 修复（真机发现 2026-11-28）：LinearLayout 默认 orientation=HORIZONTAL，
        // 不显式设 VERTICAL 时表单子项全部横向排列并溢出屏幕右侧（题干/答案/
        // 保存按钮不可见）。与 v2.3 首版同源问题，装机复测时暴露。
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        form.addView(label(R.string.photo_form_title))
        form.addView(label(R.string.photo_subject))
        spSubject = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@PhotoQuizActivity,
                android.R.layout.simple_spinner_item, subjectLabels
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        form.addView(spSubject)
        form.addView(label(R.string.photo_content))
        etContent = editText(getString(R.string.photo_content_hint))
        form.addView(etContent)
        form.addView(label(R.string.photo_answer))
        etAnswer = editText(getString(R.string.photo_answer_hint))
        form.addView(etAnswer)
        form.addView(Button(this).apply {
            text = getString(R.string.photo_save)
            styleLime()
            setOnClickListener { saveQuestion() }
        }, margin(14))
        return form
    }

    private fun label(resId: Int): TextView = TextView(this).apply {
        text = getString(resId)
        textSize = 13f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun editText(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setTextColor(getColor(R.color.on_surface))
        setHintTextColor(getColor(R.color.text_secondary))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(getColor(R.color.surface))
            setStroke(dp(1), getColor(R.color.divider))
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun margin(dpV: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(dpV) }

    private fun Button.styleLime() {
        setTextColor(0xFF121212.toInt())
        textSize = 16f
        paint.isFakeBoldText = true
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF9BEA00.toInt(), 0xFFB3EE00.toInt())
        ).apply { cornerRadius = dp(26).toFloat() }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        gravity = Gravity.CENTER
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "question_photos").apply { mkdirs() }
        val f = File(dir, "q_${System.currentTimeMillis()}.jpg")
        photoFile = f
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        photoUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.photo_no_camera, Toast.LENGTH_LONG).show()
        }
    }

    /** 从相机结果 Uri 解码位图（IO 线程调用）：限制最长边 2400，兼顾裁剪质量与内存。 */
    private fun loadBitmapFromUri(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** 进入裁剪模式：显示原图 + 裁剪浮层，隐藏表单，禁用 AI 识别。 */
    private fun enterCropMode(bmp: Bitmap) {
        currentBitmap = bmp
        preview.setImageBitmap(bmp)
        // onCreate 时 preview 初始为 GONE（无照片不占位）；进入裁剪必须显式
        // 恢复 VISIBLE，否则 FrameLayout 高度塌成 0，照片与裁剪浮层都不可见。
        preview.visibility = View.VISIBLE
        photoWrap.visibility = View.VISIBLE
        cropOverlay.visibility = View.VISIBLE
        cropOverlay.resetRect()
        cropHint.visibility = View.VISIBLE
        btnCrop.visibility = View.VISIBLE
        btnRecrop.visibility = View.GONE
        btnExtract.isEnabled = false
        formBox.visibility = View.GONE
    }

    /** 确认裁剪：把浮层矩形经 imageMatrix 逆映射回位图坐标后裁剪。 */
    private fun confirmCrop() {
        val src = fullBitmap ?: return
        val bw = src.width
        val bh = src.height
        val inv = Matrix()
        preview.imageMatrix.invert(inv)
        val dst = RectF()
        inv.mapRect(dst, cropOverlay.rect)
        val left = dst.left.toInt().coerceIn(0, bw - 1)
        val top = dst.top.toInt().coerceIn(0, bh - 1)
        val right = dst.right.toInt().coerceIn(left + 1, bw)
        val bottom = dst.bottom.toInt().coerceIn(top + 1, bh)
        if (right - left < 40 || bottom - top < 40) {
            Toast.makeText(this, R.string.photo_crop_too_small, Toast.LENGTH_SHORT).show()
            return
        }
        val cropped = Bitmap.createBitmap(src, left, top, right - left, bottom - top)
        currentBitmap = cropped
        preview.setImageBitmap(cropped)
        cropOverlay.visibility = View.GONE
        cropHint.visibility = View.GONE
        btnCrop.visibility = View.GONE
        btnRecrop.visibility = View.VISIBLE
        btnExtract.isEnabled = true
        formBox.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.photo_crop_ok)
    }

    private fun runExtract() {
        val bmp = currentBitmap ?: return
        if (AiJudge.loadConfig(this) == null) {
            tvStatus.text = getString(R.string.photo_ai_not_configured)
            formBox.visibility = View.VISIBLE
            return
        }
        btnExtract.isEnabled = false
        btnCapture.isEnabled = false
        btnRecrop.isEnabled = false
        tvStatus.text = getString(R.string.photo_extracting)
        lifecycleScope.launch {
            val parsed = try {
                val b64 = encodeBitmapJpegBase64(bmp)
                AiJudge.extractQuestionFromImage(this@PhotoQuizActivity, b64)
            } catch (e: Exception) {
                null
            }
            btnExtract.isEnabled = true
            btnCapture.isEnabled = true
            btnRecrop.isEnabled = true
            formBox.visibility = View.VISIBLE
            if (parsed == null) {
                tvStatus.text = getString(R.string.photo_extract_fail_manual)
                return@launch
            }
            prefill(parsed)
            tvStatus.text = getString(R.string.photo_extract_ok)
        }
    }

    /** 当前位图 → 缩放（最长边 1600）→ JPEG(85) → Base64。IO 线程执行。 */
    private suspend fun encodeBitmapJpegBase64(bmp: Bitmap): String =
        withContext(Dispatchers.IO) {
            val maxEdge = 1600
            val longest = maxOf(bmp.width, bmp.height)
            val src = if (longest > maxEdge) {
                val scale = maxEdge.toFloat() / longest
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bmp
            }
            val out = ByteArrayOutputStream()
            src.compress(Bitmap.CompressFormat.JPEG, 85, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

    /**
     * 识别结果回填（用户要求 2026-11-28：拍照录题一律转成填空题）——
     * 识别为选择题时，把正确选项的文字自动转换为填空答案。
     */
    private fun prefill(p: AiJudge.ParsedQuestion) {
        val subjIdx = subjectValues.indexOf(p.subject).coerceAtLeast(0)
        spSubject.setSelection(subjIdx)
        etContent.setText(p.content)
        val answerText = if (p.type == "choice") {
            val idx = "ABCD".indexOf(p.answer.trim().uppercase())
            if (idx in p.options.indices) p.options[idx] else p.answer
        } else {
            p.answer
        }
        etAnswer.setText(answerText)
    }

    /** 保存：固定填空题（V2.4），按（题干, 年级）去重。 */
    private fun saveQuestion() {
        val subject = subjectValues.getOrElse(spSubject.selectedItemPosition) { "math" }
        val content = etContent.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) {
            Toast.makeText(this, R.string.photo_need_content, Toast.LENGTH_SHORT).show()
            return
        }
        val answer = etAnswer.text?.toString()?.trim().orEmpty()
        if (answer.isEmpty()) {
            Toast.makeText(this, R.string.photo_need_answer, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ok = bankManager.addSingleQuestion(
                subject = subject,
                grade = grade,
                type = "fill",
                content = content,
                options = emptyList(),
                answer = answer
            )
            if (ok) {
                Toast.makeText(this@PhotoQuizActivity, R.string.photo_saved, Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this@PhotoQuizActivity, R.string.photo_duplicate, Toast.LENGTH_LONG).show()
            }
        }
    }
}
