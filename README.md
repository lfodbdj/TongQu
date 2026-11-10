# 学习守护 APP (AntiAddiction)

防沉迷学习守护应用，安装在孩子手机上，通过定时知识问答引导合理使用手机。

## 目标设备
- 华为 nova7 se 5G
- IP 地址：192.168.1.101

## 环境要求
- Android Studio Hedgehog (2023.1.1) 或更新版本
- JDK 17
- Gradle 8.5（项目已自带 wrapper：`gradlew` / `gradlew.bat` / `gradle-wrapper.jar`，无需本机另装 Gradle；wrapper 从腾讯云镜像下载发行包）
- Android SDK 34

## 构建步骤

### 1. 用 Android Studio 打开项目
```
File → Open → 选择 AntiAddiction 文件夹
```
等待 Gradle 同步完成。

### 2. 配置签名（构建 APK 必需）
签名已在 `app/build.gradle.kts` 中预配置，**通常无需手动改动**：

- **Debug 构建**：自动使用 Android 公共调试密钥（`~/.android/debug.keystore`），可直接侧载调试。
- **Release 构建**：可选。在 `local.properties`（已被 gitignore）中配置以下项即可启用正式签名，密钥不会进入仓库：
  ```properties
  RELEASE_STORE_FILE=/绝对路径/your-keystore.jks
  RELEASE_STORE_PASSWORD=your-password
  RELEASE_KEY_ALIAS=your-alias
  RELEASE_KEY_PASSWORD=your-key-password
  ```
  未配置时 Release 构建不附加签名配置，产出的 APK 未签名、无法直接安装；调试与侧载请直接用 Debug APK（见步骤 3）。

### 3. 构建 APK
侧载调试推荐直接构建 Debug 包（自动用公共调试密钥签名，可立即安装）：
```bash
./gradlew assembleDebug
```
APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`

> 只有在 `local.properties` 配好 `RELEASE_*` 签名项后，才应改用 `./gradlew assembleRelease`（输出 `app/build/outputs/apk/release/app-release.apk`）。未配置签名时生成的 release 包是未签名的，无法 `adb install`。

验证命令（可选）：
```bash
./gradlew testDebugUnitTest   # 运行 QuizEngine 单元测试（10 个用例）
```

### 4. 安装到华为 nova7 se
**方式一：USB 连接安装**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**方式二：WiFi 安装（设备 IP: 192.168.1.101）**
```bash
# 先在设备上开启 ADB over WiFi（需要先在 USB 连接下执行）
adb tcpip 5555
# 断开 USB，通过 WiFi 连接
adb connect 192.168.1.101:5555
# 安装
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 5. 首次安装后设置
1. 打开"学习守护"APP
2. 授予以下权限：
   - **悬浮窗权限** — 用于弹出答题界面
   - **电池优化白名单** — 防止后台服务被杀
   - **自启动权限** — 在手机管家中允许自启动
   - **通知权限** — Android 13+（API 33）为运行时权限，首次打开会自动申请；拒绝后守护服务仍运行，但通知栏不显示
3. 点击"启动服务"
4. （可选但推荐）在"设置 → 真机锁屏（设备管理器）"中点击"启用设备管理器"。启用后，连续答错被锁定时会**真正关闭手机屏幕**，需解锁才能继续；未启用则仅应用内锁定。启用设备管理器会把本应用设为设备管理员，可在同一页面"禁用设备管理器"撤销。

## 功能说明

### 答题机制
- 手机亮屏累计 **10 分钟**（默认值，家长可在控制台改为 **1–60 分钟**）弹出一次答题界面
- 每道题 **3 次** 答题机会
- 第 1 题全错 → 追加第 2 题（**30 秒**后可答）
- 第 2 题全错 → 追加第 3 题（**60 秒**后可答）
- 任一题答对 → 弹窗消失，计时重置
- 连续 **5 轮** 全部答错 → 手机锁定 **10 分钟**（可看题不可答）

### 通话优先（2026-11-08）
- **答题绝不在通话中弹出**：到点出题时若正在通话，本次出题自动跳过并重开一个完整间隔。通话覆盖**系统电话**（响铃/通话中）与**任意 VoIP 语音/视频通话**（微信语音、微信视频、QQ 通话等，基于全局音频模式检测，无需任何白名单配置）。
- 进程死亡后重弹答题同样遵守：通话中延迟重试（每 60 秒重试，直到通话结束再弹题），绝不打断通话。
- **微信小程序、小游戏、视频号不属于通话**，照常出题。检测是功能级的（音频模式），不是包级的——这正是取代旧「微信前台即跳过」规则的原因：旧规则会把小程序/游戏/视频号一起豁免。
- 家长控制台「前台例外应用」按**应用包**粒度生效，无法区分微信内部的聊天与小程序：如需微信聊天也免打扰可把微信加入例外（但小程序等会一并豁免）；不加例外时小程序/游戏/视频号照常出题，**通话保护不受影响**。

### 家长退出
- 点击答题界面底部"家长模式"
- 密码方式一：当前日期倒写（如 11月05日 → `5011`）
- 密码方式二：万能退出密码（由构建配置注入，不进源码字面量/README；构建时从 `local.properties` 的 `ANTI_ADDICTION_MASTER_PASSWORD` 读取，该文件已被 gitignore）。**未配置时万能口令自动禁用**（主密码为空、构建会告警），此时仅日期倒写口令可用；空输入一律不通过校验。需设置该项才能启用万能口令。
- 退出后服务暂停，下次开机自动恢复

### 家长控制台（2-c）
设置页顶部「家长控制台」入口，需通过家长密码才能进入；页面每次回到前台都会**重新验密**，验证通过前不渲染任何配置界面。

| 配置项 | 说明 |
|---|---|
| 试题间隔时间 | 1–60 分钟，默认 10 分钟。保存后立即生效：服务只取消并重建倒计时，**不会重置已累计的亮屏时长与当前窗口起点**，避免保存配置白送一个全新间隔。 |
| 年级题库 | 选项固定「全部年级」+ 小学 1–6 年级，每项显示该年级真实题量。默认「全部年级」。选中年级无题时自动回退到**全库真实题目**（不会用内存兜底题顶替）。 |
| 前台例外应用 | 读取已安装的可启动应用（`MAIN`+`LAUNCHER`，配合 `<queries>`，不申请 `QUERY_ALL_PACKAGES`），支持搜索与多选，最多 10 个。命中时跳过本次出题并重启一个完整间隔。 |

**安全约束（fail-closed）**：前台应用检测依赖「使用情况访问权限」。未授权、无数据或查询异常时一律**照常出题**——否则孩子只要拒绝授权就能彻底关闭防沉迷。控制台会显示权限状态并提供跳转开启按钮。例外判断发生在服务进入 `WAITING_QUIZ_RESULT` 之前，命中时保持 `IDLE`、绝不写 `quiz_in_progress=true`。

**年级数据现状**：5 个内置 JSON 中仅 `questions_grade4.json` 带 `grade:4`，其余 4 个纯数组按 `DEFAULT_GRADE=4` 入库，因此内置题库目前**全部为四年级**。要扩展其他年级，通过题库服务器下发带 `grade` 字段的 wrapped JSON 即可（`parseAndImport()` 已支持，无需升级 Room / 加 Migration），或把带 `grade` 字段的 JSON 放入本地导入目录（见「题库更新」节，批次 3 起已接线）。注意唯一索引为 `(content, grade)`，同一题目跨年级须各自显式声明 `grade`。

### 题库更新
**联网下载**：APP 内点击「更新题库」按钮。下载地址可在「设置 → 题库服务器」中配置，默认预填的是一个**示例局域网地址** `http://192.168.1.100:8080/questions.json`（见 `Constants.DEFAULT_QUIZ_URL`）——开源仓库不含任何真实服务器地址，请改成你自己的题库服务器（例如在题库目录执行 `python -m http.server 8080`）。设置页保存地址时会校验格式，只接受 `http://` / `https://` 开头的合法 URL；留空时回退到上述默认地址。
> 由于 targetSdk 34 默认禁止明文流量，项目通过 `res/xml/network_security_config.xml` 放行 HTTP，仅限**可信局域网题库服务器**。批次 3（A30）起该约束同时由代码强制执行（`UrlValidator.isAcceptable`）：**明文 HTTP 仅允许私网/本机地址**（192.168.x、10.x、172.16–31.x、127.x、169.254.x、`localhost`、单标签主机名、`.local/.lan/.internal/.home`、`[::1]`），**公网地址必须使用 https://**，保存与下载两处都会校验并拒绝。
**本地导入**：将 JSON 文件复制到应用外部私有目录（无需任何存储权限，适配 Android 11+ Scoped Storage）：
```
/storage/emulated/0/Android/data/com.antidip/files/AntiAddiction/questions/
```
两种方式生效（批次 3 / A08 已接线）：①设置界面"题库信息"卡片点「**导入本地题库**」立即导入；②不操作也会在**下次启动 APP 时自动扫描导入**。重复题目按 `(content, grade)` 唯一索引忽略，已导入的文件自动移入 `.processed` 子目录。

## 题库格式

JSON 文件格式：
```json
{
  "grade": 5,
  "version": "2026.12",
  "questions": [
    {
      "subject": "math",
      "content": "题目内容",
      "type": "choice",
      "options": ["选项A", "选项B", "选项C", "选项D"],
      "answer": "A",
      "difficulty": 1
    },
    {
      "subject": "chinese",
      "content": "填空题题目",
      "type": "fill",
      "options": [],
      "answer": "正确答案",
      "difficulty": 1
    }
  ]
}
```

**科目代码**：`chinese`(语文)、`math`(数学)、`english`(英语)、`science`(科学)、`moral`(道法)

## 项目结构
```
AntiAddiction/
├── app/src/main/
│   ├── java/com/antidip/
│   │   ├── AntiAddictionApp.kt        # Application 入口
│   │   ├── BootReceiver.kt            # 开机自启广播
│   │   ├── ScreenMonitorService.kt    # 前台服务：亮屏计时
│   │   ├── QuizEngine.kt             # 答题引擎核心逻辑
│   │   ├── QuestionBankManager.kt     # 题库管理
│   │   ├── QuizDownloader.kt          # 联网下载题库
│   │   ├── AppConfig.kt               # 家长控制台配置单一事实源 (2-c)
│   │   ├── ForegroundAppDetector.kt   # UsageStats 前台应用检测，fail-closed (2-c)
│   │   ├── ParentAuth.kt              # 家长口令校验与验证对话框 (2-b)
│   │   ├── UrlValidator.kt            # 纯 JVM URL 校验 (2-b)
│   │   ├── AntiAddictionDeviceAdmin.kt # 设备管理员接收器 (B10)
│   │   ├── DeviceLockHelper.kt         # 设备管理员/锁屏封装 (B10)
│   │   ├── AppInitializer.kt          # 启动时初始化
│   │   ├── ui/
│   │   │   ├── QuizActivity.kt        # 答题界面
│   │   │   ├── ConsoleActivity.kt    # 家长控制台 (2-c)
│   │   │   ├── AppPickerAdapter.kt   # 已安装应用多选/搜索列表 (2-c)
│   │   │   └── SettingsActivity.kt   # 设置界面
│   │   ├── data/
│   │   │   └── QuizStateStore.kt      # DataStore：失败/锁定状态持久化 (B2)
│   │   └── db/
│   │       ├── QuizDatabase.kt        # Room 数据库
│   │       ├── QuestionDao.kt         # 数据访问
│   │       └── QuestionEntity.kt      # 数据实体
│   ├── res/                           # 布局、字符串、颜色等资源
│   │   └── xml/device_admin_policies.xml  # 设备管理员策略 (B10)
│   ├── assets/                        # 内置题库（首次启动自动导入，共 5 个 JSON，约 427 题）
│   │   ├── questions_grade4.json      # 4 年级主题库 (201题)
│   │   ├── questions_math_extra.json  # 数学补充 (74题)
│   │   ├── questions_chinese_extra.json # 语文补充 (60题)
│   │   ├── questions_english_extra.json # 英语补充 (65题)
│   │   └── questions_extra.json       # 综合补充 (27题)
│   └── AndroidManifest.xml
├── build.gradle.kts                   # 项目级构建（AGP / Kotlin / KSP 插件版本）
├── app/build.gradle.kts               # 模块级构建
├── app/src/test/java/com/antidip/  # JVM 单元测试（QuizEngine / Batch1* / Batch2*）
├── gradlew / gradlew.bat              # Gradle 8.5 wrapper 启动脚本
├── gradle/wrapper/                    # gradle-wrapper.jar + properties
├── gradle.properties / local.properties  # 后者含本机 SDK 路径与家长密码，不入库
├── settings.gradle.kts                # 仓库与模块配置
└── docs/
    ├── architecture_review.md        # 架构评审与实施进度
    └── api29_device_review.md        # API 29 真机运行时兼容性评审（结项）
```

---

## 开源说明

- **许可**：见 [LICENSE](LICENSE)（MIT）。
- **本机配置**：`local.properties` 不入库，请参考 `local.properties.example`。家长万能密码由该文件注入 `BuildConfig`，仓库中不含任何真实口令；未配置时该通道禁用，仅「日期倒写」动态口令可用。
- **默认地址是示例**：`Constants.DEFAULT_QUIZ_URL` 中的 `192.168.1.100` 是占位地址，不代表任何真实服务器，请在「设置 → 题库服务器」中填写自己的地址。
- **题库内容**：`app/src/main/assets/questions_*.json` 为内置示例题库，仅供学习与演示使用，请自行核实来源与授权后再用于其他用途。
- **使用边界**：本项目是家长在**自有设备**上侧载使用的学习守护工具，依赖使用情况访问、悬浮窗、设备管理员等敏感权限，非应用商店上架产品。请在合法、知情的前提下使用，并自行承担部署与数据安全责任。
- **明文 HTTP**：为便于局域网自建题库，项目放行了明文 HTTP，但代码层强制只允许私网/本机地址；公网地址必须使用 HTTPS。

---

内容由 AI 生成
