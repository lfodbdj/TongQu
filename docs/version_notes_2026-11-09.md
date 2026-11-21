# 学习守护 APP · 版本说明（2026-11-09）

> **版本 2.0「童趣」（2026-11-10，按用户表格要求优化 + 新需求）**
>
> | 需求 | 实现 | 真机验证 |
> |---|---|---|
> | 出题时后台暂停播放（红果/广告实测不暂停） | 新增 `MediaPauseHelper` 三层：媒体暂停键 + 音频焦点 + **STREAM_MUSIC 静音（保底）并每 3 秒重申**（物理音量键绕过 overlay 也能压住），退出恢复原音量；被暂停的播放不自动恢复 | 弹题时 volume 2→0、日志 `Media silenced (prev volume=2)`；退出恢复 2 ✅ |
> | APP 改名「童趣」 | app_name/通知/标题全部更新 + **UI 重设计**（暖橙+天蓝童趣配色、🎈🏆 emoji） | 桌面名「童趣」、标题「🎈 童趣 · 家长设置」、答题卡橙主题 ✅ |
> | 积分与勋章 | 选择题答对 +1、填空题 +2、错一次 -1（不为负）；连续对题计数 + 最高连对（DataStore 持久化）；8 枚勋章自动颁发（首次答对/积分 10/50/100/200/连对 5/10/20） | 答对 ⭐1·🔥1 → 答错 ⭐0·🔥0 → 答对 ⭐1；勋章 first_quiz 入库并点亮 ✅ |
> | 填空题提交间隔 ≥1 分钟 | 两次填空提交间隔 <60s 拒绝（toast 提示剩余秒数），不消耗机会 | 立即重提交被拦截（次数未消耗）✅ |
> | 填空错 3 次 → 讲解 + 错题本 | 讲解显示正确答案；错题本按 (内容,年级) 去重计数、上限 200 条，DataStore 持久化。**真机发现并修复**：第 3 错会走 NeedsNextQuestion/NextQuestion/**RoundFailed** 三个分支（取决于题目位置），统一用「提交前剩余次数==1」判定，三个分支全覆盖 | 第 3 错后讲解「📚 正确答案是 1/2，已记入错题本」显示 ✅；成长页错题本列出「7/8-3/8→1/2（已错 1 次）」✅ |
> | 成长展示界面 | 新增「🏆 成长乐园」页（设置页入口）：积分/连对/最高连对、已获得荣誉（点亮 vs 锁定+条件）、错题本（题目/正确答案/次数） | 截图验证全部渲染 ✅ |
> | 年级→题库映射确认 | 新增日志 `Question request for grade=X` / `Grade-scoped question served (grade=Y)` | 控制台选四年级 → 日志 grade=4 scoped served ✅（其他年级 0 题自动回退全库，内置题库目前全为四年级——扩展年级需服务器下发带 grade 字段题库） |
> | 不阻止手机休眠息屏 | overlay 移除 FLAG_KEEP_SCREEN_ON；QuizActivity 三旗组去掉 KEEP；WAKE_LOCK 权限删除（本就未用） | quiz 显示中按 POWER → `mWakefulness=Asleep` 正常熄屏 ✅ |
>
> 新增文件：`ProgressRules.kt`（纯 JVM 规则）、`data/ProgressStore.kt`（DataStore）、`MediaPauseHelper.kt`、`ui/GrowthActivity.kt`、`V2ProgressRulesTest.kt`（11 用例）。**139/139 单测全绿**。华为网关覆盖安装流程自动化（风险提示→勾选→继续→锁屏密码）。
>
> 设备交付态：间隔还原 5 分钟（服务 300000ms 倒计时运行）、例外集 {智慧中小学, 微信}、设备管理器已启用、无遗留答题。

> **问题修复轮增补（2026-11-09 晚，用户报障后）**
>
> | 报障 | 根因 | 修复 | 真机验证 |
> |---|---|---|---|
> | 1. 看视频（红果免费短剧等）不出题 | §三-P0 所述 EMUI 后台启动拦截：孩子久居其他应用时服务 `startActivity` 被华为管控静默拒绝（`areBackgroundActivityStartsAllowed=false`），答题只入栈不上屏 | **悬浮窗答题 Overlay 落地**（原 P0 方案①）：新增 `QuizOverlayController`，以 `TYPE_APPLICATION_OVERLAY` 直接呈现完整答题界面（同引擎/同持久化/同结果上报/锁定倒计时/家长密码面板），覆盖任何前台应用且切换应用无法绕开；`presentQuiz()` 为唯一出题口：通话优先拦截 → overlay 主路径 → QuizActivity 回退 | 红果短剧 `ShortSeriesActivity` 前台时服务到点触发 → overlay 窗口（无 activity 名）成为焦点并压在红果窗口之上 → 家长密码面板正常退出、焦点回红果 ✅ |
> | 2. 白名单修改不显示已选应用 | 列表按应用名排序，已选项混在全量列表中 | 控制台应用列表改为**已选中置顶 + 其余按名排序**（搜索仍可用） | 打开控制台：列表顶部即「微信」「智慧中小学」（已选 2 个），58同城/AirLink 等未选项排后 ✅ |
>
> Overlay 实施中真机抓出并修复的两个次生缺陷：
> 1. **Material 主题校验失败**：application context 不满足 `ThemeEnforcement` 检查（`checkAppCompatTheme` 抛异常→静默回退 Activity 路径）→ 视图统一改用 `ContextThemeWrapper(appContext, Theme.AntiAddiction.Quiz)` 创建
> 2. **单例残留**：家长退出走实例 `dismiss()` 时未清 companion `active` 标记 → 下次 `show()` 见非空直接返回 true 却不上屏（第一轮 E2E "触发但无窗口"的真因）→ `dismiss()` 内先清 `active === this`
>
> **增补 2（2026-11-09 深夜）：出题时暂停后台播放**
>
> 报障：悬浮窗答题后视频应用在背后继续出声——overlay 不是 Activity，视频应用收不到 `onPause`。
> 修复：overlay attach 时执行 `pauseBackgroundMedia()`——①`dispatchMediaKeyEvent(KEYCODE_MEDIA_PAUSE)` 暂停当前媒体会话；②`requestAudioFocus(AUDIOFOCUS_GAIN)` 独占音频焦点（守规矩应用收到 loss 即暂停，且答题期间新播放被阻断）。dismiss 时释放焦点；**被暂停的播放不自动恢复**（暂停本身是打断的一部分），由孩子答完后手动续播。
> 真机验证（红果播放中）：overlay 弹出后 `dumpsys audio` 焦点栈显示 `pack: com.antidip -- gain: GAIN -- notified: true`（红果已收到焦点丢失通知且未再抢回）；家长退出后焦点栈清空、焦点回红果、`quiz_in_progress=false`。已含 128/128 单测与构建通过、覆盖安装。

---

---

> 版本基线：`app-debug.apk` md5 `53cfcd80f937389a305ec4bdf448101c`（128/128 单测全绿）
> 真机：华为 <phone-model> SE（<device-model>，EMUI / Android 10 / API 29），USB 调试安装，覆盖安装走华为安装网关（风险提示 → 勾选风险 → 锁屏密码）
> 详细技术记录见 `docs/architecture_review.md` §10（批次3）与 §11（通话优先）

---

## 一、原先需求（按提出顺序汇总）

| 轮次 | 需求 |
|---|---|
| V1 基础形态 | 亮屏累计 10 分钟弹出知识问答；每题 3 次机会；第 1 题全错追加第 2 题（30 秒后可答）；第 2 题全错追加第 3 题（60 秒后可答）；任一题答对即恢复使用并重置计时；连续 5 轮全错锁定手机 10 分钟（可看题不可答） |
| V1 家长机制 | 答题界面「家长模式」凭密码退出：密码一 = 当天日期 MMDD 倒写（11月08日 → 8011）；密码二 = 构建注入的万能密码（存于 `local.properties`，不进源码）；退出后暂停 2 小时或到下次开机自动恢复 |
| V2 加固（任务板 B1–B14） | 修复亮屏计时双重累加；连续失败/锁定状态落盘（进程死亡不清零）；万能密码移出源码；常量集中；题库下载地址可配置；本地导入适配 Scoped Storage；真机锁屏（屏幕固定 + 设备管理员 lockNow）；一道题连续答对两次自动移出题库（不低于 100 题下限） |
| 2-b/2-c | 家长验证对话框防绕过（输错不关闭、随生命周期销毁）；家长控制台：出题间隔 1–60 分钟可调、年级题库选择（当前内置全部为四年级）、前台例外应用白名单（最多 10 个）；使用情况权限未授权时 fail-closed 照常出题 |
| 2-c 补充 | 控制台显示全部已安装应用；题库服务器默认指向 `http://192.168.1.100:8080/questions.json`（PC `<本地题库目录>/`） |
| **本轮（2026-11-08/09）通话优先** | **答题不得在通话时弹出；应用运行时以通话功能优先级最高，包括微信语音和视频通话；微信小程序、游戏、视频号等不包含在内（照常出题）** |
| 本轮附带（批次3加固） | 题库 URL 白名单（明文 HTTP 仅限局域网/本机，公网必须 HTTPS）；Android 13+ 通知权限运行时申请；锁定状态单事务原子写；协程/对话框生命周期绑定；旋转/深色模式重建后续答同一题；本地导入接线（按钮 + 启动扫描）；系统时间前跳钳制；死代码清理 |

---

## 二、已实现功能（当前版本状态）

### 2.1 核心防沉迷链路
- 前台服务 `ScreenMonitorService` 常驻计时（单一时间戳模型，START_STICKY 恢复，进程死亡不丢窗口），到点拉起全屏答题 `QuizActivity`（屏幕固定防脱离 + 设备管理员 lockNow 选配）
- 答题状态机 `QuizEngine`（纯 JVM，可单测）：3 题/轮、3 次/题、追加题延迟 30s/60s、5 轮锁定 10 分钟、答对清零
- 失败计数/锁定截止 DataStore 原子持久化；重建 Activity 续答同一题；家长口令两套（日期倒写 + 万能密码）

### 2.2 通话优先（本轮核心，真机已验证）
- **检测原理**：全局音频模式（`CallPriorityDetector`，纯 JVM 映射 + fail-closed）。覆盖：系统电话响铃/通话（`MODE_RINGTONE`/`MODE_IN_CALL`）、一切 VoIP 语音视频通话（`MODE_IN_COMMUNICATION`——微信语音、微信视频、QQ 等）、来电筛选/重定向（`MODE_CALL_SCREENING`/`MODE_CALL_REDIRECT`/`MODE_COMMUNICATION_REDIRECT`）
- **三道防线**：① 到点出题时通话中 → 跳过本次并重开完整间隔；② 弹题唯一入口 `launchQuizActivity()` 统一拦截，通话中拒绝启动（触发路径自动回 IDLE 重排）；③ 进程死亡后重弹答题遇通话 → 保持待答状态，每 60 秒重试直到通话结束
- **微信边界**：小程序、小游戏、视频号运行在 `MODE_NORMAL`，不属于通话，照常出题。旧版「微信前台即跳过」的整包豁免已**删除**（它会把小程序/游戏/视频号一起豁免）
- **白名单边界**：家长控制台「前台例外应用」按应用包粒度生效，无法区分微信内部的聊天与小程序——需要小程序照常出题时请勿把微信加入例外；不加入也不影响通话保护
- **真机 E2E 证据**：`mode=3`（用模拟 VoIP 行为的音频模式工具设置）期间 60 秒到期 → 日志 `Foreground exempt or call active, skipping quiz and restarting a full interval`，持续前台观察 70 秒无弹题；`mode=0` 后下一间隔 → `Triggering quiz!` 正常弹出；非例外应用前台 + 正常模式 → 照常弹题

### 2.3 本轮真机发现并修复的缺陷
1. **僵尸 WAITING 态（已修复）**：进程死亡后遗留 `quiz_in_progress=true` 时，重装/强停后家长点「启动服务」永远无法重弹答题（重弹只在 START_STICKY 空投递分支触发）。修复：`resumeTiming()` 的 WAITING 分支检测到待重投标记即重新拉起答题（真机日志链验证：`Restored state=WAITING... needRelaunchQuiz=true` → `ACTION_RESUME with a restored owed quiz: re-launching it` → 答题页弹出）
2. **EMUI 后台启动拦截（平台限制，已定方案见下节）**：本应用久未前台（孩子长时间使用其他应用）时，服务到点 `startActivity` 被华为管控拦截（`areBackgroundActivityStartsAllowed return false`），答题只入任务栈不到前台。应用刚在前台交互过（近期前台窗口内）时不受影响。此前所有弹题验收都发生在应用刚被操作过的场景，该限制首次被本轮真机测试暴露
3. 工具沉淀：`service call audio` 无法在 EMUI 上模拟通话模式（`setMode` 事务 34 三参调用被服务端静默拒绝）；改用最小测试 APK（模拟 VoIP 应用调用 `AudioManager.setMode`，与微信行为完全一致）精确控制全局音频模式，测完已卸载

### 2.4 质量与交付状态
- JVM 单元测试 **128/128 全绿**（新增通话优先 4 例、URL 白名单 10 例），构建 `--offline`（Gradle 缓存已迁 E 盘）
- 设备交付终态：间隔还原 5 分钟、例外集 {智慧教育, 微信}（家长原配置，未改动）、无遗留答题、服务前台运行（5 分钟倒计时）、测试工具已卸载

---

## 三、下一版本优化方向和方案

| 优先级 | 方向 | 方案 |
|---|---|---|
| **P0** | **EMUI 后台弹题被拦**（孩子长时间玩其他应用时防沉迷失效） | 三层组合：① **悬浮窗答题 Overlay**——用已授予的 `SYSTEM_ALERT_WINDOW` 直接以 `TYPE_APPLICATION_OVERLAY` 绘制答题界面，不经 `startActivity`，完全绕开后台启动管控（业界防沉迷应用标准做法）；② 通知栏强提醒兜底（高优先级全屏意图通知，点击进入答题，附带 10 秒级重试）；③ README/设置页引导家长开启华为「自启动管理 + 受保护应用 + 允许后台活动」，三者叠加后 EMUI 亦放行 |
| P1 | 锁定闭环真机验证 | 设备管理员 `lockNow()` 真机从未验证（设置页可启用）；补测：连续 5 轮失败 → 真关屏 → 解锁后锁定倒计时继续 |
| P1 | 题库年级扩展 | 内置题库全部为四年级（1–6 年级均为 0 题）。方案：`<本地题库目录>/` 按年级拆分 JSON（`parseAndImport` 已支持 `grade` 字段），服务器直接下发；控制台年级下拉已显示真实题量 |
| P1 | 家长口令强化 | 万能密码现为构建期静态注入；方案：改为远端校验（服务器只返回通过与否）或 TOTP 动态码，防孩子拿到 `local.properties` |
| P2 | 时间篡改防护 | 现有钳制只防「单次前跳超间隔」；方案：`SystemClock.elapsedRealtime` 与墙钟双源比对，前跳超阈值时按答错计一次 |
| P2 | 题库下载安全 | HTTP 白名单已落地；进一步：题库 JSON 加签名字段（HMAC），导入时校验，防局域网内篡改 |
| P2 | 数据可观测 | 家长端增加「本周答题记录」页（Room 已有 `last_asked`/结果数据，补 UI 汇总） |
| P3 | Room Migration 流程 | schema 已导出（`app/schemas/`）；后续任何建表变更走标准 Migration + 回归测试 |
| P3 | 锁定期体验 | 锁定 10 分钟内显示复习题干（现有）+ 倒计时通知，防孩子以为死机重启规避（重启后锁定已可恢复，补提示文案） |

---

## 四、本次会话交付物清单

- 代码：通话优先（`CallPriorityDetector.kt` + 服务三道防线 + 僵尸 WAITING 修复）、批次3加固 10 项、`Batch3UrlWhitelistTest`/`Batch3CallPriorityTest` 共 14 个新测试用例
- 文档：本说明 + `README.md`（通话优先节、权限说明、HTTP 白名单、本地导入） + `docs/architecture_review.md` §10/§11
- 构建：`GRADLE_USER_HOME=<GRADLE_USER_HOME>`（1.1GB 缓存自 C 盘迁入，全量离线构建可用）
- 真机：新 APK 已覆盖安装（华为网关流程自动化完成），交付终态已还原
