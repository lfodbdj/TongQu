# 童趣 · 改动总结（V2.3，2026-11-24）

版本号：versionCode 15 / versionName 2.3
本轮共四项功能（用户 2026-11-24 需求）：

## 一、积分按题递增扣减（第 1/2/5/10 次错误）

同一道题（按「题干 + 年级」指纹识别）每答错一次，扣分递增：

| 该题累计第 N 次答错 | 扣分 |
|---|---|
| 第 1 次 | -1 |
| 第 2 次 | -2 |
| 第 3 次 | -5 |
| 第 4 次及以后 | -10 |

- 实现：`ProgressRules.escalatingWrongDeduction(n)`（纯函数）+ `ProgressStore` 用
  DataStore 持久化「题目指纹 → 累计答错次数」（键 `question_wrong_counts`）。
- 强制答题与主动练习共用同一台账，跨进程/重装存活；累计次数不因答对而清零
  （用户原话「第四次错误扣 10 分」按错误次数计）。
- 无题面指纹的调用（如题库为空时的内存兜底题）仍按旧的固定 -1，保证兼容。
- 单元测试：`V2ProgressRulesTest.escalatingWrongDeduction_*`、
  `wrongAnswer_withFingerprint_escalatesDeduction`。

## 二、每题最多获得两次答对加分

同一道题最多通过 2 次「答对」拿到加分；第 3 次及以后答对只 +0（不加分也不扣分，
连对仍 +1）。

- 实现：`ProgressRules.MAX_CORRECT_BONUS_PER_QUESTION = 2`；
  `ProgressStore` 持久化「题目指纹 → 已获加分次数」（键 `question_bonus_tally`），
  `applyAnswer(fingerprint=...)` 内部判定 `bonusCapped`。
- 重试答对（强制答题第 2/3 次）本就不加分，也不计入这 2 次额度。
- 单元测试：`correctAnswer_cappedAfterTwoBonuses`、`correctFillAnswer_cappedGivesZero`。

## 三、主动练习：已出题不可跳过 + 退出重进保持原题

- 已出题目无法跳过：练习页必须「作答」后才能推进（原逻辑已如此，本轮显式保证）。
- 退出重进保持原题：离开练习页（`onStop`）时，若当前题尚未推进，则把题 id 写入
  DataStore（`practice_pending_id`），并保存已作答题数/答对数/总数/是否重做模式；
  再次进入时按该 id 恢复同一道题与进度，必须作答后才能推进。
- 会话结束（`showSummary`）清除全部练习态，下次进入开新会话。
- 重做模式（从错题本「重做这道题」进入）同样支持退出重进恢复同一焦点题。
- 实现：`ProgressStore` 新增 `savePracticePending/getPracticePending/clearPracticePending`、
  `savePracticeSession/getPracticeAnswered/...`；`PracticeActivity` 在 `onCreate`
  恢复、`onStop` 保存、推进时清除。

## 四、拍照录题（家长）

家长在 **设置 → 服务 → 家长控制台** 点「📷 拍照录题」：

1. 拍照（调用系统相机，FileProvider 共享临时图片，无需申请 CAMERA 权限）；
2. 若已在控制台配置**支持图片的视觉模型**（如 `Qwen/Qwen2.5-VL-7B-Instruct`），
   点「🤖 AI 识别题目」把照片发给视觉模型，自动抽取「题型/题干/选项/答案/科目」；
3. 家长在表单里复核/修改（识别失败或未配置视觉模型时直接手动填写）；
4. 点「保存到题库」入库（按「题干 + 年级」去重，年级取控制台当前所选年级）。

- 实现：`AiJudge.extractQuestionFromImage()`（OpenAI 兼容 vision 消息格式，
  `image_url` 用 base64 data URL；图片先缩放到最长边 ~1600px、JPEG 85%）；
  新增 `ui/PhotoQuizActivity`（不导出，仅从有密码门的控制台进入）；
  `QuestionBankManager.addSingleQuestion()`；`res/xml/file_paths.xml` +
  manifest `FileProvider`。
- 说明：视觉识别质量取决于所选模型；`Qwen2.5-VL` 系列对中文题目照片效果好。
  未配置视觉模型时该功能退化为「拍照 + 手动录入」，仍然可用。

## 其他

- `Constants` 新增台账与会话持久化键。
- `bytes`/`Bitmap` 缩放在 IO 线程外的主线程解码，图片较大时可能略卡；已在
  `downscaleToJpegBytes` 中按需采样（`inSampleSize`）降低内存占用。
- 单元测试全部通过（`testDebugUnitTest`），`assembleDebug` 打包成功。
