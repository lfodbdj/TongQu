package com.antidip.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.media.ExifInterface
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.antidip.AiJudge
import com.antidip.AppConfig
import com.antidip.Constants
import com.antidip.QuestionBankManager
import com.antidip.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * V2.5 拍照录题（用户要求 2026-11-28）：
 *   快门（应用内相机）→ 直接裁剪 → AI 识别（一次识别多题）→ 家长勾选/修改
 *   → 一律转成填空题批量入库。
 *
 * V2.5 变更（对照 v2.4.1）：
 *  - 改用 Camera1（android.hardware.Camera，框架自带零依赖）做**应用内预览**，
 *    按下快门后立即进入裁剪模式，跳过系统相机的"确定"复核页；
 *  - AI 提示词改为识别照片中**所有**清晰题目（最多 8 道），
 *    [AiJudge.extractQuestionsFromImage] 返回列表；
 *  - 复核表单改为多题卡片列表（勾选 + 科目 + 题干 + 答案），一键批量保存；
 *    「手动添加一道」兜底未配置视觉模型 / 识别失败的场景；
 *  - 一律转填空题：choice 的正确选项文字自动转为填空答案；
 *  - 位图解码/旋转/缩放/JPEG 编码全部在 IO 线程。
 *
 * 权限：CAMERA 为运行时危险权限，进入页面即请求；拒绝则提示并保留"授权"按钮。
 * 本页从成长乐园首页进入；不导出。
 */
class PhotoQuizActivity : AppCompatActivity() {

    private lateinit var bankManager: QuestionBankManager

    // 相机态
    private lateinit var cameraBox: FrameLayout
    private lateinit var previewTexture: TextureView
    private lateinit var btnShutter: Button
    private lateinit var btnGrant: Button
    private var camera: Camera? = null

    // 裁剪态
    private lateinit var photoWrap: FrameLayout
    private lateinit var preview: ImageView
    private lateinit var cropOverlay: CropOverlayView
    private lateinit var cropHint: TextView
    private lateinit var btnCrop: Button
    private var frozenBitmap: Bitmap? = null
    private var currentBitmap: Bitmap? = null

    // 表单态（多题）
    private lateinit var tvStatus: TextView
    private lateinit var btnRetake: Button
    private lateinit var btnExtract: Button
    private lateinit var formTitle: TextView
    private lateinit var formBox: LinearLayout
    private lateinit var btnAddManual: Button
    private lateinit var btnSaveAll: Button
    private val drafts = mutableListOf<Draft>()

    private class Draft(
        val root: LinearLayout,
        val check: CheckBox,
        val spinner: Spinner,
        val content: EditText,
        val answer: EditText
    )

    private val grade: Int
        get() = AppConfig.getSelectedGrade(this).let {
            if (it == Constants.ALL_GRADES) 4 else it
        }

    private val subjectLabels = listOf("数学", "语文", "英语", "科学", "道法")
    private val subjectValues = listOf("math", "chinese", "english", "science", "moral")

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            tvStatus.text = getString(R.string.photo_perm_denied)
            btnGrant.visibility = View.VISIBLE
        }
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bankManager = QuestionBankManager(this)

        val root = ScrollView(this).apply { setBackgroundColor(getColor(R.color.background)) }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        root.addView(inner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)

        inner.addView(TextView(this).apply {
            text = getString(R.string.photo_title)
            textSize = 22f
            paint.isFakeBoldText = true
            setTextColor(getColor(R.color.on_surface))
        })
        inner.addView(TextView(this).apply {
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
        inner.addView(tvStatus)

        // 相机预览（TextureView 在 ScrollView 中不挖洞、可正常滚动）
        cameraBox = FrameLayout(this)
        previewTexture = TextureView(this)
        cameraBox.addView(previewTexture, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(320)
        ))
        inner.addView(cameraBox, margin(6))

        btnShutter = Button(this).apply {
            text = getString(R.string.photo_capture)
            styleLime()
            setOnClickListener { takePicture() }
        }
        inner.addView(btnShutter, margin(6))

        btnGrant = Button(this).apply {
            text = getString(R.string.photo_perm_needed)
            styleLime()
            visibility = View.GONE
            setOnClickListener {
                permLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        inner.addView(btnGrant, margin(6))

        // 裁剪区（快门后立即显示冻结帧 + 裁剪浮层）
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
        inner.addView(photoWrap, margin(10))

        cropHint = TextView(this).apply {
            text = getString(R.string.photo_crop_hint)
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(4))
            visibility = View.GONE
        }
        inner.addView(cropHint)
        btnCrop = Button(this).apply {
            text = getString(R.string.photo_btn_crop)
            styleLime()
            visibility = View.GONE
            setOnClickListener { confirmCrop() }
        }
        inner.addView(btnCrop, margin(4))

        btnRetake = Button(this).apply {
            text = getString(R.string.photo_retake)
            styleLime()
            visibility = View.GONE
            setOnClickListener { backToCamera() }
        }
        inner.addView(btnRetake, margin(4))

        btnExtract = Button(this).apply {
            text = getString(R.string.photo_extract)
            styleLime()
            isEnabled = false
            setOnClickListener { runExtract() }
        }
        inner.addView(btnExtract, margin(6))

        // 多题复核表单
        formTitle = TextView(this).apply {
            text = getString(R.string.photo_form_title)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.on_surface))
            setPadding(0, dp(12), 0, dp(4))
            visibility = View.GONE
        }
        inner.addView(formTitle)
        formBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        inner.addView(formBox)
        btnAddManual = Button(this).apply {
            text = getString(R.string.photo_add_manual)
            styleLime()
            visibility = View.GONE
            setOnClickListener { addDraft(null) }
        }
        inner.addView(btnAddManual, margin(6))
        btnSaveAll = Button(this).apply {
            text = getString(R.string.photo_save_selected)
            styleLime()
            visibility = View.GONE
            setOnClickListener { saveSelected() }
        }
        inner.addView(btnSaveAll, margin(14))
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            startCamera()
        } else if (camera == null) {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        super.onPause()
        releaseCamera()
    }

    // ---------------- Camera1 应用内相机 ----------------

    private fun startCamera() {
        btnGrant.visibility = View.GONE
        try {
            val cam = Camera.open(0)
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(0, info)
            val params = cam.parameters
            val best = params.supportedPreviewSizes
                .filter { it.width <= 1920 }
                .maxByOrNull { it.width * it.height }
            best?.let { params.setPreviewSize(it.width, it.height) }
            if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            }
            // 竖屏后置：JPEG 需按 sensor 方向旋转（写入 EXIF，解码时再应用）
            params.setRotation((info.orientation + 360) % 360)
            cam.parameters = params
            cam.setDisplayOrientation(90)
            camera = cam
            if (previewTexture.isAvailable) {
                attachPreviewAndStart()
            } else {
                previewTexture.surfaceTextureListener = surfaceListener
            }
        } catch (e: Exception) {
            camera = null
            tvStatus.text = getString(R.string.photo_camera_failed)
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
            attachPreviewAndStart()
        }
        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
            releaseCamera()
            return true
        }
        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
    }

    private fun attachPreviewAndStart() {
        val cam = camera ?: return
        try {
            cam.setPreviewTexture(previewTexture.surfaceTexture)
            cam.startPreview()
        } catch (e: Exception) {
            tvStatus.text = getString(R.string.photo_camera_failed)
        }
    }

    private fun releaseCamera() {
        try {
            camera?.stopPreview()
        } catch (e: Exception) { /* 预览可能未启动 */ }
        try {
            camera?.release()
        } catch (e: Exception) { /* 已释放 */ }
        camera = null
    }

    /** 快门：拍一张 → 直接进入裁剪（跳过系统相机复核页，用户要求 2026-11-28）。 */
    private fun takePicture() {
        val cam = camera ?: run {
            if (hasCameraPermission()) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        btnShutter.isEnabled = false
        cam.takePicture(null, null, Camera.PictureCallback { data, _ ->
            btnShutter.isEnabled = true
            if (data == null) {
                runCatching { cam.startPreview() }
                return@PictureCallback
            }
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { decodeUpright(data) }
                if (bmp == null) {
                    tvStatus.text = getString(R.string.photo_read_fail)
                    runCatching { cam.startPreview() }
                    return@launch
                }
                frozenBitmap = bmp
                enterCropMode(bmp)
            }
        })
    }

    /** 解码 + 按 EXIF 方向转正（IO 线程调用）。 */
    private fun decodeUpright(data: ByteArray): Bitmap? {
        return try {
            val src = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
            val exif = try {
                ExifInterface(ByteArrayInputStream(data))
            } catch (e: Exception) {
                null
            }
            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (degrees == 0) {
                src
            } else {
                val m = Matrix().apply { postRotate(degrees.toFloat()) }
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 裁剪 ----------------

    /** 进入裁剪模式：显示冻结帧 + 裁剪浮层，隐藏表单与识别按钮。 */
    private fun enterCropMode(bmp: Bitmap) {
        currentBitmap = bmp
        preview.setImageBitmap(bmp)
        cameraBox.visibility = View.GONE
        btnShutter.visibility = View.GONE
        preview.visibility = View.VISIBLE
        photoWrap.visibility = View.VISIBLE
        cropOverlay.visibility = View.VISIBLE
        cropOverlay.resetRect()
        cropHint.visibility = View.VISIBLE
        btnCrop.visibility = View.VISIBLE
        btnRetake.visibility = View.GONE
        btnExtract.isEnabled = false
        formTitle.visibility = View.GONE
        formBox.visibility = View.GONE
        btnAddManual.visibility = View.GONE
        btnSaveAll.visibility = View.GONE
        tvStatus.text = getString(R.string.photo_crop_hint)
    }

    /** 确认裁剪：浮层矩形经 imageMatrix 逆映射回位图坐标后裁剪 → 表单态。 */
    private fun confirmCrop() {
        val src = frozenBitmap ?: return
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
        btnRetake.visibility = View.VISIBLE
        btnExtract.isEnabled = true
        tvStatus.text = getString(R.string.photo_crop_ok)
    }

    /** 重拍：回到相机预览（裁剪/表单态 → 相机态）。 */
    private fun backToCamera() {
        frozenBitmap = null
        currentBitmap = null
        photoWrap.visibility = View.GONE
        preview.visibility = View.GONE
        cropOverlay.visibility = View.GONE
        cropHint.visibility = View.GONE
        btnCrop.visibility = View.GONE
        btnRetake.visibility = View.GONE
        btnExtract.isEnabled = false
        formTitle.visibility = View.GONE
        formBox.visibility = View.GONE
        formBox.removeAllViews()
        drafts.clear()
        btnAddManual.visibility = View.GONE
        btnSaveAll.visibility = View.GONE
        tvStatus.text = getString(R.string.photo_hint_capture)
        cameraBox.visibility = View.VISIBLE
        btnShutter.visibility = View.VISIBLE
        if (camera == null && hasCameraPermission()) {
            startCamera()
        } else {
            attachPreviewAndStart()
        }
    }

    // ---------------- AI 识别（多题） ----------------

    private fun runExtract() {
        val bmp = currentBitmap ?: return
        if (AiJudge.loadConfig(this) == null) {
            tvStatus.text = getString(R.string.photo_ai_not_configured)
            showForm(emptyList())
            return
        }
        btnExtract.isEnabled = false
        btnRetake.isEnabled = false
        tvStatus.text = getString(R.string.photo_extracting)
        lifecycleScope.launch {
            val parsed = try {
                val b64 = encodeBitmapJpegBase64(bmp)
                AiJudge.extractQuestionsFromImage(this@PhotoQuizActivity, b64)
            } catch (e: Exception) {
                emptyList()
            }
            btnExtract.isEnabled = true
            btnRetake.isEnabled = true
            if (parsed.isEmpty()) {
                tvStatus.text = getString(R.string.photo_none_found)
                showForm(emptyList())
                return@launch
            }
            tvStatus.text = getString(R.string.photo_found_fmt, parsed.size)
            showForm(parsed)
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

    // ---------------- 多题复核表单 ----------------

    /** 展示多题卡片（[parsed] 为空时放一张空白卡供手动填写）。 */
    private fun showForm(parsed: List<AiJudge.ParsedQuestion>) {
        formBox.removeAllViews()
        drafts.clear()
        formTitle.visibility = View.VISIBLE
        formBox.visibility = View.VISIBLE
        btnAddManual.visibility = View.VISIBLE
        btnSaveAll.visibility = View.VISIBLE
        if (parsed.isEmpty()) {
            addDraft(null)
        } else {
            parsed.forEach { addDraft(it) }
        }
    }

    /** 加一张题卡（[p] 非空时用识别结果预填；choice 自动转填空答案）。 */
    private fun addDraft(p: AiJudge.ParsedQuestion?) {
        val idx = drafts.size + 1
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(getColor(R.color.surface))
                setStroke(dp(1), getColor(R.color.divider))
            }
        }
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val check = CheckBox(this).apply {
            text = "题 $idx"
            isChecked = true
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.on_surface))
        }
        headRow.addView(check)
        headRow.addView(TextView(this).apply {
            text = getString(R.string.photo_subject)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(12), 0, dp(6), 0)
        })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@PhotoQuizActivity,
                android.R.layout.simple_spinner_item, subjectLabels
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        headRow.addView(spinner, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(headRow)

        card.addView(TextView(this).apply {
            text = getString(R.string.photo_content)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(2))
        })
        val contentEt = editText(getString(R.string.photo_content_hint))
        card.addView(contentEt)
        card.addView(TextView(this).apply {
            text = getString(R.string.photo_answer)
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(2))
        })
        val answerEt = editText(getString(R.string.photo_answer_hint))
        card.addView(answerEt)

        if (p != null) {
            val subjIdx = subjectValues.indexOf(p.subject).coerceAtLeast(0)
            spinner.setSelection(subjIdx)
            contentEt.setText(p.content)
            // 一律转填空题（用户要求 2026-11-28）：choice → 正确选项文字作答案
            val answerText = if (p.type == "choice") {
                val i = "ABCD".indexOf(p.answer.trim().uppercase())
                if (i in p.options.indices) p.options[i] else p.answer
            } else {
                p.answer
            }
            answerEt.setText(answerText)
        }
        formBox.addView(card, margin(6))
        drafts.add(Draft(card, check, spinner, contentEt, answerEt))
    }

    /** 保存所有勾选且填写完整的题目（一律 fill，按题干+年级去重）。 */
    private fun saveSelected() {
        val targets = drafts.filter { it.check.isChecked }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.photo_need_checked, Toast.LENGTH_SHORT).show()
            return
        }
        for (d in targets) {
            if (d.content.text?.toString()?.trim().isNullOrEmpty()) {
                Toast.makeText(this, R.string.photo_need_content, Toast.LENGTH_SHORT).show()
                return
            }
            if (d.answer.text?.toString()?.trim().isNullOrEmpty()) {
                Toast.makeText(this, R.string.photo_need_answer, Toast.LENGTH_SHORT).show()
                return
            }
        }
        btnSaveAll.isEnabled = false
        lifecycleScope.launch {
            var saved = 0
            var skipped = 0
            for (d in targets) {
                val subject = subjectValues.getOrElse(d.spinner.selectedItemPosition) { "math" }
                val ok = bankManager.addSingleQuestion(
                    subject = subject,
                    grade = grade,
                    type = "fill",
                    content = d.content.text?.toString()?.trim().orEmpty(),
                    options = emptyList(),
                    answer = d.answer.text?.toString()?.trim().orEmpty()
                )
                if (ok) saved++ else skipped++
            }
            btnSaveAll.isEnabled = true
            Toast.makeText(
                this@PhotoQuizActivity,
                getString(R.string.photo_saved_fmt, saved, skipped),
                Toast.LENGTH_LONG
            ).show()
            if (saved > 0) finish()
        }
    }

    // ---------------- 通用小件 ----------------

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

    override fun onDestroy() {
        super.onDestroy()
        releaseCamera()
    }
}
