package com.antidip.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
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
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * V2.3 拍照录题（用户要求 2026-11-24）：家长拍一张题目照片，交给（可选）
 * 视觉模型自动识别成结构化题目，家长在表单里复核/修改后保存进题库。
 *
 * 设计：
 *  - 用系统相机（ActivityResultContracts.TakePicture + FileProvider）拍照，不申请
 *    相机权限（拍照动作由相机应用完成，本应用只接收结果 Uri）；
 *  - AI 识别需要家长已在控制台配置支持图片的模型（如 Qwen2.5-VL）；未配置或
 *    识别失败时，直接进入手动录入表单，功能仍然可用；
 *  - 保存走 [QuestionBankManager.addSingleQuestion]，按 (题干, 年级) 去重。
 *
 * 本页仅家长可进（从家长控制台跳转，控制台本身有密码门）；不导出。
 */
class PhotoQuizActivity : AppCompatActivity() {

    private lateinit var bankManager: QuestionBankManager
    private lateinit var preview: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnExtract: Button
    private lateinit var formBox: LinearLayout
    private lateinit var spType: Spinner
    private lateinit var spSubject: Spinner
    private lateinit var etContent: EditText
    private lateinit var etA: EditText
    private lateinit var etB: EditText
    private lateinit var etC: EditText
    private lateinit var etD: EditText
    private lateinit var etAnswer: EditText

    private var photoFile: File? = null
    private var photoUri: Uri? = null
    private var hasPhoto = false

    private val grade: Int
        get() = AppConfig.getSelectedGrade(this).let {
            if (it == Constants.ALL_GRADES) 4 else it
        }

    private val typeLabels = listOf("选择题", "填空题")
    private val typeValues = listOf("choice", "fill")
    private val subjectLabels = listOf("数学", "语文", "英语", "科学", "道法")
    private val subjectValues = listOf("math", "chinese", "english", "science", "moral")

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            hasPhoto = true
            showPhoto(photoUri!!)
            tvStatus.text = getString(R.string.photo_capture_ok)
            btnExtract.isEnabled = true
            formBox.visibility = View.VISIBLE
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

        btnExtract = Button(this).apply {
            text = getString(R.string.photo_extract)
            styleLime()
            isEnabled = false
            setOnClickListener { runExtract() }
        }
        box.addView(btnExtract, margin(6))

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
        box.addView(preview, margin(10))

        formBox = buildForm()
        box.addView(formBox, margin(8))
    }

    private fun buildForm(): LinearLayout {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        form.addView(label(R.string.photo_form_title))
        form.addView(label(R.string.photo_type))
        spType = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@PhotoQuizActivity,
                android.R.layout.simple_spinner_item, typeLabels
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        form.addView(spType)

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

        form.addView(label(R.string.photo_options))
        etA = editText("A 选项")
        etB = editText("B 选项")
        etC = editText("C 选项")
        etD = editText("D 选项")
        form.addView(etA); form.addView(etB); form.addView(etC); form.addView(etD)

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

    private fun showPhoto(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val bmp = BitmapFactory.decodeStream(input)
            input.close()
            preview.setImageBitmap(bmp)
            preview.visibility = View.VISIBLE
        } catch (e: Exception) {
            // 预览失败不影响流程
        }
    }

    private fun runExtract() {
        val file = photoFile ?: return
        if (AiJudge.loadConfig(this) == null) {
            tvStatus.text = getString(R.string.photo_ai_not_configured)
            formBox.visibility = View.VISIBLE
            return
        }
        val bytes = try {
            downscaleToJpegBytes(file)
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            tvStatus.text = getString(R.string.photo_read_fail)
            formBox.visibility = View.VISIBLE
            return
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        btnExtract.isEnabled = false
        btnCapture.isEnabled = false
        tvStatus.text = getString(R.string.photo_extracting)
        lifecycleScope.launch {
            val parsed = try {
                AiJudge.extractQuestionFromImage(this@PhotoQuizActivity, b64)
            } catch (e: Exception) {
                null
            }
            btnExtract.isEnabled = true
            btnCapture.isEnabled = true
            formBox.visibility = View.VISIBLE
            if (parsed == null) {
                tvStatus.text = getString(R.string.photo_extract_fail_manual)
                return@launch
            }
            prefill(parsed)
            tvStatus.text = getString(R.string.photo_extract_ok)
        }
    }

    /** 图片解码 + 缩放到最大边 ~1600px + JPEG 编码（控制视觉请求体积）。 */
    private fun downscaleToJpegBytes(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val maxEdge = 1600
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun prefill(p: AiJudge.ParsedQuestion) {
        val typeIdx = typeValues.indexOf(p.type).coerceAtLeast(0)
        spType.setSelection(typeIdx)
        val subjIdx = subjectValues.indexOf(p.subject).coerceAtLeast(0)
        spSubject.setSelection(subjIdx)
        etContent.setText(p.content)
        etA.setText(p.options.getOrElse(0) { "" })
        etB.setText(p.options.getOrElse(1) { "" })
        etC.setText(p.options.getOrElse(2) { "" })
        etD.setText(p.options.getOrElse(3) { "" })
        etAnswer.setText(p.answer)
    }

    private fun saveQuestion() {
        val type = typeValues.getOrElse(spType.selectedItemPosition) { "fill" }
        val subject = subjectValues.getOrElse(spSubject.selectedItemPosition) { "math" }
        val content = etContent.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) {
            Toast.makeText(this, R.string.photo_need_content, Toast.LENGTH_SHORT).show()
            return
        }
        val options = mutableListOf<String>()
        if (type == "choice") {
            listOf(etA, etB, etC, etD).forEach { options.add(it.text?.toString()?.trim().orEmpty()) }
            if (options.count { it.isNotEmpty() } < 2) {
                Toast.makeText(this, R.string.photo_need_options, Toast.LENGTH_SHORT).show()
                return
            }
            // 去掉尾部空选项
            while (options.isNotEmpty() && options.last().isEmpty()) options.removeAt(options.size - 1)
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
                type = type,
                content = content,
                options = options,
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
