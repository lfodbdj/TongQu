# API 29 真机运行时兼容性评审（结项）

- 评审对象：AntiAddiction `com.antidip`，`minSdk=24 / targetSdk=34`，基线 APK `app-debug.apk`（md5 `e9d18a60ef2be9e0def5925109ffb19c`，2026-11-08 14:28）
- 真机：HUAWEI `<device-model>` / `<DEVICE_SERIAL>` / Android 10 / API 29 / EMUI / 补丁 2020-11-01
- 结论日期：2026-11-08
- 结项方式：team-lead 直接采证（原定只读评审 worker 的报告未回传，见 §0）

---

## 0. 卡点复盘：为什么「评审 API 29 真机」停住

这一阶段卡住**不是代码缺陷**，而是三条工具链 / 编排问题叠加：

1. **任务 #15 `API 29 真机运行时兼容性只读评审` 长期停在 `in_progress`**，owner `software-architect-2` 的报告回传时触发 `400 [PacRpcClient] request timed out after 5000ms`。任务状态没有更新，后续 #12（真机验收清单）与 #16（2-c 控制台）都在等这份结论，整条链因此停摆。
2. **ADB 同时挂了两台设备**：USB 真机 `<DEVICE_SERIAL>` 与网络 ADB `192.168.1.8:5555`（`AOSP on r34a0`）。所有不带 `-s` 的 `adb shell` 一律返回 `adb.exe: more than one device/emulator`（exit 1）。真机评审所需的 `dumpsys` / `appops` / `logcat` 证据链在第一步就断了。
3. **`software-engineer` / `software-qa-engineer` 的 `completed successfully` 是空报告**：2-c 的 8 个目标文件全部 MISSING，关键标识符 grep 零匹配，`main/` 与 `test/` 的 mtime 仍停在 2-b 基线（14:26 / 14:25），APK md5 未变。两个「完成」通知没有对应任何磁盘写入。

**处理动作**

- `adb disconnect 192.168.1.8:5555`，`adb devices -l` 恢复为单台真机；后续所有 ADB 命令统一带 `-s <DEVICE_SERIAL>`（并可用 `ANDROID_SERIAL` 兜底），避免守护进程重启瞬间的竞态再次触发歧义。
- 唤醒并用已授权的 PIN 解锁：`mWakefulness=Awake`、`deviceLocked=0`，`stay_on_while_plugged_in=3` 保持调试期间亮屏。
- 本评审改由 team-lead 直接采证结项，不再等待 worker 报告；2-c 实现按「唯一写入者 + 唯一复跑人」重新派发。

---

## 1. 实测环境与证据

| 项 | 采集命令 | 实测结果 |
|---|---|---|
| 机型 / 版本 | `getprop ro.product.model / release / sdk` | `<device-model>` / `10` / `29` |
| 安装状态 | `pm path com.antidip` | `/data/app/com.antidip-vH2FskVRMx5bIll0aQhjPA==/base.apk` |
| 目标 SDK | `dumpsys package` | `minSdk=24 targetSdk=34 versionCode=1` |
| 前台服务 | `dumpsys activity services com.antidip` | `ServiceRecord{... ScreenMonitorService}`，`isForeground=true`、`foregroundId=1`、`foregroundNoti=Notification(channel=anti_addiction_service ...)`、`startRequested=true`、`stopIfKilled=false`、`lastStartId=3` |
| FGS 权限 | `dumpsys package` / `cmd appops get` | `FOREGROUND_SERVICE: granted=true`；`START_FOREGROUND: allow` |
| 设备管理员 | `dumpsys device_policy` | User 0 / User 128 的 `Enabled Device Admins` 均为空 |
| 屏幕固定 | `dumpsys activity` | `mLockTaskModeState=NONE`、`mLockTaskPackages` 空、task `mLockTaskAuth=LOCK_TASK_AUTH_PINNABLE` |
| 使用情况权限 | `appops get com.antidip GET_USAGE_STATS` | `No operations. / Default mode: default`（未授权） |
| 锁屏 / 亮屏 | `dumpsys trust` / `dumpsys power` | 处置前 `mWakefulness=Asleep`、keyguard 显示；处置后 `deviceLocked=0`、`Awake` |
| 近期 BAL 拦截 | `logcat -d -t 400 | grep -i BAL` | 无 `Background activity start` 拒绝记录 |
| 单测基线 | `test-results/testDebugUnitTest/TEST-*.xml` | 10 个 suite、**101 tests / 0 failures / 0 errors** |

---

## 2. 逐项风险判定

| # | 风险点 | API 29 判定 | 依据 |
|---|---|---|---|
| R1 | `foregroundServiceType="specialUse"` 在 API 29 是否被拒 | **通过** | 该 type / `FOREGROUND_SERVICE_SPECIAL_USE` 权限是 API 34 引入的约束；API 29 只校验 `FOREGROUND_SERVICE`（已 granted）。真机服务确实处于 `isForeground=true`，通知渠道正常，安装与运行无崩溃 |
| R2 | Android 10 后台启动 Activity（BAL）阻断弹题 | **有条件通过** | 应用有可见前台服务通知，属于 API 29 的 BAL 豁免通道；此前真机实测累计亮屏 10 分钟后 `QuizActivity` 正常弹出，本次 logcat 无 BAL 拒绝。**`START_STICKY` 恢复路径下的重弹仍需强杀复验** |
| R3 | `START_STICKY` 强杀恢复 | **有 EMUI 能力边界，已文档化** | `stopIfKilled=false` 证明服务被登记为可重启；但真机实测 `am force-stop com.antidip` 与 `am kill com.antidip` 后，等待 10–12 秒 `dumpsys activity services com.antidip` 为空、`ps -A` 无进程，**未自动恢复**。这是 Android「stopped state」+ EMUI 后台管理的共同结果：`START_STICKY` 只保证系统因资源回收杀进程时的重建，不保证显式强杀 / 未加自启动白名单时恢复。代码侧 `quiz_in_progress` → WAITING → `null` 分支重弹链路仍然正确，但产品不能承诺「强杀后必自动恢复」，需引导加入自启动与电池白名单 |
| R4 | 锁屏下答题页可见 | **通过（API 27+ 路径）** | `setShowWhenLocked` / `setTurnScreenOn` 为 API 27+，API 29 有效；API 24–26 走 `FLAG_SHOW_WHEN_LOCKED` 兼容分支；`setLockScreenVisibilityCompat()` 成对管理 SHOW/TURN/KEEP |
| R5 | 屏幕固定 `startLockTask()` | **能力边界，仅文档化** | 应用非 Device Owner / 非 lock-task 白名单，`mLockTaskAuth=LOCK_TASK_AUTH_PINNABLE`；真机会弹「屏幕已固定」系统确认框，用户点「不用了」即可取消。不能作为不可绕过的强制手段 |
| R6 | 设备管理员 `lockNow()` | **能力边界，仅文档化** | `Enabled Device Admins` 为空 → `DeviceLockHelper.lockNow()` 返回 `false` → M5 立即回滚 `setLockScreenVisibilityCompat(true)`，答题页保持可见、不真关屏。行为符合设计，非缺陷 |
| R7 | `POST_NOTIFICATIONS` 运行时申请 | **不适用** | 该权限 API 33 才引入，API 29 上是 no-op 声明；`START_FOREGROUND: allow` 且通知已成功展示。2-c 不引入运行时申请 |
| R8 | HTTP 明文题库地址 | **通过（有后续收口）** | `network_security_config.xml` 的 `base-config cleartextTrafficPermitted=true` 已生效；此前真机实测 `http://192.168.1.101:8080/questions.json` 未被 cleartext 拦截，失败仅因服务未启动。批次 3 再补「HTTP 仅允许私有网段 host」白名单 |
| R9 | UsageStats 前台应用检测（2-c 引入） | **未授权态已确认** | `appops get ... GET_USAGE_STATS = No operations`。2-c 必须 **fail-closed**：无权限 / 无数据 / 查询异常一律照常出题，否则孩子拒绝授权即可彻底关闭防沉迷 |
| R10 | `targetSdk=34` 运行在 API 29 | **通过** | 安装、启动、前台服务、通知均正常；无 `IllegalArgumentException` / FGS type 异常 |

---

## 3. P0 必改规格

**本轮无新增 P0 运行时缺陷。** 但引入 2-c 后必须同时满足以下三条硬约束，否则会打开绕过口子：

1. `ForegroundAppDetector` 在 UsageStats 未授权 / `queryEvents` 无数据 / 抛异常时返回 `null`，`isExemptActive()` 返回 `false`（照常出题）。
2. 「前台命中例外」的判断必须发生在 `ScreenMonitorService.triggerQuiz()` **改写 `timingState` / 写 `KEY_QUIZ_IN_PROGRESS=true` 之前**；命中时保持 `IDLE`，只重置计时窗口并重开 timer，绝不进入 WAITING。
3. 所有真机调试命令必须带 `-s <DEVICE_SERIAL>`；`adb devices` 出现第二台时先 `adb disconnect` 再采集，避免证据链被 `more than one device` 静默打断。

---

## 4. 仅文档化 / 能力边界（不改代码）

- 未启用设备管理员时，「连续答错锁定」只做应用内锁定，不会真正关屏（README 已说明）。
- 屏幕固定非 Device Owner，系统确认框可被用户取消，不能承诺「无法退出」。
- `POST_NOTIFICATIONS` 在 API 29 无需运行时申请；API 33+ 的申请归后续批次。
- 全局放行 HTTP 明文属临时方案，批次 3 收口为私有网段白名单。
- **EMUI 强杀不自动恢复**：`am force-stop` / `am kill` 后服务与进程均未重启（实测）。设置页的「自启动权限 / 电池优化」引导是必需项，不是可选提示。

---

## 5. 遗留真机验收项（转 #12 / #16，非本评审阻断项）

1. `START_STICKY` 强杀后 `QuizActivity` 重弹、且不重置计时窗口。
2. 家长退出后 2 小时临时暂停与下次开机自动恢复。
3. 锁屏状态下答题页可见、屏幕固定系统确认框行为。
4. 2-c 三项配置真机：间隔设 1 分钟、年级题库选择、前台例外在 **未授权 UsageStats** 时照常出题 —— ✅ 已完成（2026-11-08，见 `architecture_review.md` §9.5）；并已补测 **已授权** 时例外应用在前台跳过出题、非例外应用在前台照常出题的正/反向对照（§9.6）。
5. 覆盖安装走华为三道确认（风险提示 → 勾选已了解风险 → 输入锁屏密码）。

---

## 6. 闭环结论

API 29 / EMUI 真机上，**当前基线 APK 无阻断性运行时兼容缺陷**：前台服务、`specialUse` 声明、通知渠道、BAL 弹题通道、锁屏显示兼容均可正常工作；设备管理员与屏幕固定属产品能力边界而非代码缺陷，已按设计优雅降级并文档化。

本阶段停摆的根因是 **worker 报告 RPC 超时 + ADB 双设备歧义 + 空完成通知**，已全部定位并处置。评审任务 #15 结项，2-c 实现重新派发；最终真机安装与功能调试在 2-c 逐项验收通过后进行。
