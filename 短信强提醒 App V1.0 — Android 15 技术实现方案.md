> **文档状态：首版代码同步版（2026-09-22）**
> 原文保留为设计基线；下方内容记录当前仓库的真实工程实现。若二者冲突，以本节为当前 V1.0 APK 的实现事实。

## 0. 首版工程实现摘要（2026-09-22）

### 0.1 构建基线

| 项目 | 当前值 |
| --- | --- |
| Application ID / Namespace | `com.messagestar.app` |
| Version | `1.0`（`versionCode = 1`） |
| Min SDK | 26（Android 8.0） |
| Target / Compile SDK | 35（Android 15） |
| Java / Kotlin JVM Target | 17 |
| Gradle | 8.9 |
| UI | Jetpack Compose + Material 3 |
| 持久化 | Preferences DataStore + 临时状态 SharedPreferences |
| 异步 | Kotlin Coroutines |
| Release 压缩混淆 | 当前关闭（`isMinifyEnabled = false`） |

主要依赖版本：Compose BOM `2024.12.01`、Core KTX `1.15.0`、Activity Compose `1.10.0`、Lifecycle `2.8.7`、DataStore Preferences `1.1.1`、Coroutines Android `1.9.0`、Kotlin Serialization JSON `1.6.3`。

### 0.2 当前源码结构

```text
com.messagestar.app/
├── MainActivity.kt                 # 首页、规则编辑、设置、权限检查 Compose UI
├── MessageStarApplication.kt       # 创建高优先级报警通知渠道
├── sms/
│   └── SmsReceiver.kt              # SMS_RECEIVED、长短信拼接、规则入口
├── rules/
│   └── RuleEngine.kt               # 号码归一化、PHONE/PLATFORM 匹配、规则测试
├── data/
│   ├── Models.kt                   # Rule、RuleMatch、AlertSnapshot
│   ├── SettingsRepository.kt       # DataStore 设置与规则
│   └── AlertStateStore.kt          # 报警计数、最新号码、60 秒冷却临时状态
├── alert/
│   ├── AlertCoordinator.kt         # trigger/stop 唯一协调入口
│   ├── AlertService.kt             # 前台报警服务、音频、震动、WakeLock、悬浮窗
│   ├── AlertNotification.kt        # 高优先级通知与 Full Screen Intent
│   └── AlertActivity.kt            # 锁屏提醒页与按键关闭
├── permissions/
│   └── DeviceSettingsHelper.kt     # 国产手机自启动/后台设置跳转
├── boot/
│   └── BootReceiver.kt             # 开机清理过期临时状态
└── ui/
    └── Theme.kt                    # Compose 明暗主题
```

原设计中的 `SmsAssembler`、`CooldownManager`、`AudioController`、`VibrationController` 和各独立 Screen 文件没有拆成单独类型，其职责分别合并在 `SmsReceiver`、`AlertStateStore`、`AlertService` 与 `MainActivity` 中。对 V1 规模而言当前结构可运行，后续若功能扩大再拆分。

### 0.3 实际运行链路

```text
系统 SMS_RECEIVED
    ↓
SmsReceiver.goAsync()
    ↓
按系统 PDU 顺序拼接正文 + 提取首段发送号码
    ↓
读取 DataStore 总开关和规则
    ↓
RuleEngine.match()
    ↓ 命中且不在 60 秒冷却
AlertCoordinator.trigger()
    ↓
AlertStateStore.begin()/append()
    ↓
AlertService（仅报警期间运行）
    ├── 前台临时通知
    ├── 已授权悬浮窗：直接显示全屏遮罩
    ├── 未授权悬浮窗：Notification Full Screen Intent → AlertActivity
    ├── WakeLock（最长约 5 分钟）
    ├── 闹钟音量临时提升并在结束时恢复
    ├── MediaPlayer 循环播放，失败时 Ringtone 回退
    ├── Audio Focus
    └── 循环震动
```

日常监听不启动 Service、不持有 WakeLock、不轮询数据库，也不显示常驻通知。`AlertService` 使用 `mediaPlayback` 前台服务类型，只有命中规则或执行“测试强提醒”时才启动。

### 0.4 数据与状态实现

`SettingsRepository` 使用 Preferences DataStore 保存：

```text
masterEnabled
vibrationEnabled
ringtoneUri
rulesJson
```

`AlertStateStore` 使用独立 SharedPreferences 保存：

```text
isAlerting
count
latestSender
cooldownUntil
```

冷却时间采用 `SystemClock.elapsedRealtime()`，不会因为用户调整系统时间而异常。短信正文不写入 DataStore、SharedPreferences、文件或日志。

当前临时状态只用于聚合、界面刷新和冷却判断；进程在报警中被杀后，首版不会根据 `isAlerting` 自动恢复音频与震动。`BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED` 到达时会清理过期报警与冷却状态。

### 0.5 规则实现

- `PHONE`：去除空格、短横线、括号；只有后续号码符合大陆 11 位手机号格式时才去除 `+86` 或 `86`；最终执行完整字符串精确匹配。
- `PLATFORM`：过滤空关键词，对正文执行忽略大小写的 `contains`；所有关键词均存在才命中。
- UI 限制平台规则最多保存 5 个关键词，整个应用最多保存 10 条规则。
- 同一短信即使命中多条规则，也只返回首次命中结果并启动一轮报警。
- 双 SIM 依赖系统统一的 `SMS_RECEIVED` 投递，当前不读取或保存 subscription ID。

### 0.6 报警媒体实现

- 音频属性：`USAGE_ALARM + CONTENT_TYPE_SONIFICATION`。
- 音频焦点：`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`，收到短暂/永久丢失时暂停，重新获得时继续。
- 铃声候选顺序：用户 URI → 系统默认闹钟 → 系统默认来电 → 系统默认通知。
- 播放器：优先 `MediaPlayer` 循环播放；候选 URI 均失败时回退到 `Ringtone`。
- 音量：记录原 `STREAM_ALARM` 音量，报警期间临时调至最大，所有正常停止路径恢复原值。
- 通话：`CALL_STATE_OFFHOOK` 或 `CALL_STATE_RINGING` 时跳过音频，但仍可震动和展示提醒；挂断不补响。
- 震动：Android 13+ 使用 `VibrationAttributes.USAGE_ALARM`；当前波形是 1 秒震动、0.5 秒暂停并循环。
- 安全停止：报警持续 5 分钟后自动调用统一停止流程，并进入 60 秒冷却。

上述“5 分钟安全停止”和“报警期间保持亮屏”是当前实现对原设计的明确调整。原文中的“无限持续直到用户关闭”和“屏幕按系统超时自动熄灭”尚未按字面实现。

### 0.7 展示与关闭策略

首版参考 CarListener 的国产机后台提醒思路，采用两级展示：

1. 有 `SYSTEM_ALERT_WINDOW`：由前台报警服务添加 `TYPE_APPLICATION_OVERLAY` 全屏遮罩，可点击按钮或遮罩背景关闭。
2. 无悬浮窗权限：通知启用 Full Screen Intent，尝试拉起带 `showWhenLocked`、`turnScreenOn` 的 `AlertActivity`；Android/OriginOS 可能降级为高优先级 Heads-up。

`AlertActivity` 位于前台并收到按键事件时，可用音量加/减及普通可捕获按键关闭。Power、Home、系统导航，以及悬浮窗显示期间的音量键不能承诺被应用全局捕获。

所有关闭入口最终调用 `AlertCoordinator.stop()`，由 Service 销毁流程统一移除遮罩、停止音频和震动、释放音频焦点与 WakeLock、恢复闹钟音量，并清除临时通知。

### 0.8 Manifest 权限与组件

当前声明：

```text
RECEIVE_SMS
READ_PHONE_STATE
POST_NOTIFICATIONS
VIBRATE
RECEIVE_BOOT_COMPLETED
USE_FULL_SCREEN_INTENT
FOREGROUND_SERVICE
FOREGROUND_SERVICE_MEDIA_PLAYBACK
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
SYSTEM_ALERT_WINDOW
WAKE_LOCK
MODIFY_AUDIO_SETTINGS
```

`SmsReceiver` 受 `BROADCAST_SMS` 保护并监听 `SMS_RECEIVED`；`AlertService` 不导出；`AlertActivity` 不导出且排除最近任务；`BootReceiver` 监听普通与 Direct Boot 完成广播。

权限页可自动检查标准 Android 权限、Full Screen Intent、悬浮窗和电池优化状态。vivo/iQOO、OPPO/OnePlus/realme、小米、华为、荣耀、三星、魅族的自启动设置通过候选组件跳转；由于厂商未提供统一可读 API，该项只能引导用户手工确认，跳转失败时回退到应用详情页。

### 0.9 构建、签名与发布物

Release 构建从仓库根目录的 `keystore.properties` 读取签名信息；该文件以及 `*.jks`、`*.keystore` 已加入 `.gitignore`，不得提交或对外分享。正式密钥位于项目外的 F 盘，需要与配置文件一同离线备份，否则后续 APK 无法以相同签名覆盖升级。

构建命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

当前正式 APK 已验证 v2/v3 签名，证书 SHA-256：

```text
640F1EEF008E016277F08E00D7D69E6EC63E7AAA926DAC20BBCFB2FF789ED289
```

Debug 与 Release 证书不同，因此开发阶段已安装 Debug 包的手机首次切换到 Release 时必须卸载旧版；之后继续使用同一正式证书即可覆盖安装并保留 DataStore 配置。

### 0.10 已完成验证与待验收项

2026-09-22 最新执行结果：

```text
:app:testDebugUnitTest   PASS
:app:lintDebug           PASS
:app:assembleDebug       PASS
:app:assembleRelease     PASS
```

Android 15 / API 35 模拟器已验证：正式 APK 安装与启动、权限授予后的悬浮提醒、提醒关闭、前台服务生命周期、MediaPlayer 响铃日志、闹钟音量临时提升与恢复。规则引擎单元测试已覆盖手机号归一化、精确匹配和平台关键词 AND 逻辑。

最终仍必须在 vivo X200s / Android 15 使用真实 SIM 完成：

```text
真实短信端到端
锁屏与熄屏
最近任务划掉
重启后不打开 App
长时间待机
通话中短信
蓝牙连接/断开
静音与勿扰
连续多条短信
长短信
双 SIM
OriginOS 自启动、电池与后台弹窗组合
```

已知待收尾项：震动设置页文案写“停止 1 秒”，而代码实际暂停 0.5 秒；悬浮窗路径无法保证音量键关闭；进程被杀后不自动续响；厂商自启动授权状态不能自动读取；尚无 UI/仪器化自动测试。

---

# 1. 技术目标

目标设备：

* vivo X200s
* Android 15 / API 35
* Kotlin 原生 Android
* 自用 APK
* 不依赖服务器
* 不要求 Google Play 上架

核心工程目标：

1. 平时几乎不产生后台 CPU 开销
2. 不运行全天候前台服务
3. 不显示永久常驻通知
4. SMS 到达后由系统事件唤醒
5. 命中规则后立即进入高强度提醒状态
6. 报警状态尽可能可靠持续
7. vivo X200s 作为唯一 P0 目标机型实机调优

---

# 2. 推荐工程技术栈

建议：

* Kotlin
* Android SDK
* Jetpack Compose 或传统 XML 均可
* DataStore 保存设置和规则
* Coroutines
* AndroidX Core
* 原生 Notification API
* 原生 AudioManager / Ringtone / MediaPlayer
* 原生 VibratorManager

V1 不需要：

* 网络请求框架
* 登录 SDK
* 云数据库
* Room 短信历史数据库

如果规则只有 10 条以内，DataStore 已足够。

---

# 3. 总体架构

核心结构：

```text
Android SMS
    ↓
SmsReceiver
    ↓
SmsAssembler
    ↓
RuleEngine
    ↓
CooldownManager
    ↓
AlertCoordinator
    ├── Alert Notification
    ├── Full-screen AlertActivity
    ├── AlertService
    ├── AudioController
    └── VibrationController
```

配置部分：

```text
MainActivity
    ↓
RuleRepository
    ↓
DataStore
```

系统恢复部分：

```text
BOOT_COMPLETED
    ↓
BootReceiver
    ↓
仅做状态初始化/检查
```

---

# 4. SMS 接收方案

使用：

`Telephony.Sms.Intents.SMS_RECEIVED_ACTION`

Manifest 静态注册 `BroadcastReceiver`。

需要：

`android.permission.RECEIVE_SMS`

Android 官方提供 `Telephony.Sms.Intents.getMessagesFromIntent(intent)` 从收到的 SMS 广播中解析短信消息。普通 App 即使不是默认短信 App，也可以监听 `SMS_RECEIVED_ACTION`。

核心原则：

**不要轮询短信数据库。**

---

# 5. SmsReceiver 职责

Receiver 只处理短任务。

伪代码：

```text
onReceive(intent):

    if intent != SMS_RECEIVED:
        return

    if masterSwitch == OFF:
        return

    messages = getMessagesFromIntent(intent)

    sms = assemble(messages)

    if cooldownManager.isCoolingDown():
        return

    result = ruleEngine.match(
        sender = sms.sender,
        body = sms.body
    )

    if !result.matched:
        return

    alertCoordinator.trigger(
        sender = sms.sender
    )
```

BroadcastReceiver 不能作为持续响铃组件。

Receiver 做完匹配以后立即结束。

---

# 6. 长短信组装

短信可能被拆成多个 PDU。

需要先取得：

```text
SmsMessage[]
```

然后按照当前短信事件中的分段组合：

```text
body =
    message1.body +
    message2.body +
    message3.body
```

得到完整正文以后：

**再执行平台关键词匹配。**

禁止：

分别对每一个短信分段匹配关键词。

否则可能出现：

关键词 1 在第一段
关键词 2 在第二段

最终错误漏报。

---

# 7. 双 SIM

当前需求：

**SIM1 + SIM2 全部监听。**

V1 不做：

* SIM 白名单
* SIM 单独规则
* SIM 独立铃声

因此：

无论哪个 SIM 收到 SMS：

统一进入 RuleEngine。

如果系统 Intent 中能得到 subscription / slot 信息，可以内部保留用于 Debug，但业务层无需使用。

---

# 8. 手机号码归一化

输入：

`+86 138-0013-8000`

标准化：

`13800138000`

建议：

```text
trim()
删除空格
删除 "-"
删除 "(" ")"
```

对中国大陆普通手机号：

```text
+86XXXXXXXXXXX
86XXXXXXXXXXX
XXXXXXXXXXX
```

统一转换为：

```text
XXXXXXXXXXX
```

只有符合合法大陆手机号基本结构时才移除 `86`。

平台短号码：

例如：

`106910082527830`

不得执行普通手机号的 `+86` 规则。

---

# 9. RuleEngine 数据模型

统一：

```kotlin
Rule {
    id
    type
    enabled
}
```

两种实现：

```text
PhoneRule
PlatformRule
```

---

# 10. PhoneRule

数据：

```text
id
enabled
phoneNumber
```

判断：

```text
normalize(incomingSender)
==
normalize(configuredPhone)
```

禁止：

```text
contains()
startsWith()
```

用于完整手机号规则。

---

# 11. PlatformRule

数据：

```text
id
name
enabled
keywords[]
```

关键词：

1～5 个。

规则：

```text
keywords.all {
    smsBody contains keyword
}
```

即：

```text
K1 AND K2 AND K3...
```

---

# 12. 推荐的平台规则

特来电：

```text
【特来电】
免费停放时长
驶离
```

逻辑：

```text
【特来电】
AND
免费停放时长
AND
驶离
```

辽宁交警：

```text
【辽宁交警】
未按规定停放
请立即驶离
```

---

# 13. 关键词标准化

匹配之前：

```text
trim()
```

英文：

建议：

```text
ignoreCase = true
```

中文：

直接 Unicode 字符串包含判断。

V1 不建议把：

```text
，
。
：
[]
【】
```

全部自动删除。

因为例如：

`【辽宁交警】`

本身就是很有价值的平台身份指纹。

---

# 14. Alert 状态机

定义：

```text
IDLE
ALERTING
COOLDOWN
```

转换：

```text
IDLE
 ↓ 命中
ALERTING
 ↓ 用户关闭
COOLDOWN
 ↓ 60 秒
IDLE
```

报警期间再命中：

```text
ALERTING
 ↓
ALERTING
```

只执行：

```text
alertCount++
latestSender = newSender
```

不得重新创建独立报警实例。

---

# 15. AlertCoordinator

作为报警流程唯一入口。

接口概念：

```kotlin
trigger(sender)
stop(reason)
```

`trigger()`：

如果：

```text
state == IDLE
```

则：

```text
state = ALERTING
count = 1
saveTemporaryState()
startAlert()
```

如果：

```text
state == ALERTING
```

则：

```text
count++
updateUI()
```

如果：

```text
state == COOLDOWN
```

直接：

```text
return
```

---

# 16. 报警期间为什么仍需要临时通知

用户不能接受的是：

**24 小时常驻通知。**

这个完全可以避免。

但是 Android 对后台持续工作、全屏通知和前台服务有系统约束。

因此允许：

> 只有真正报警期间存在一条高优先级临时通知。

用户关闭提醒以后：

立即删除。

这和：

`短信监听正在运行`

这种永久通知是完全不同的。

---

# 17. Notification Channel

第一次运行创建：

```text
channelId = critical_sms_alert
```

级别：

```text
IMPORTANCE_HIGH
```

报警 Notification 用于：

* 锁屏提醒
* Full Screen Intent
* 停止操作
* 报警 Service 可见生命周期

Full Screen Intent 要求对应 Notification Channel 至少为高重要级别。

---

# 18. Full Screen Intent

Manifest 声明：

```xml
<uses-permission
    android:name="android.permission.USE_FULL_SCREEN_INTENT" />
```

Android 14+：

必须检查：

```kotlin
notificationManager.canUseFullScreenIntent()
```

如果没有授权：

跳转：

```text
ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
```

让用户手动开启。

Android 官方明确提供了这一检查及设置入口。

---

# 19. 一个必须接受的 Android 系统限制

原始需求是：

> 正在使用其他 App 时也直接全屏抢占。

但 Android 13 以后：

当设备正在被用户使用时，即便 Notification 配置了 Full Screen Intent，System UI 也可能只显示高优先级 Heads-up Notification，而不是强制打开 Activity。

因此：

### 锁屏 / 熄屏

目标：

尽可能：

```text
点亮屏幕
+
Full Screen AlertActivity
```

### 正在使用手机

目标：

```text
持续响铃
+
震动
+
高优先级持续 Heads-up
```

但：

**不能把“必然覆盖当前 App 打开全屏 Activity”作为验收失败条件。**

这是 Android 平台行为，不是 App Bug。

---

# 20. AlertActivity

Activity 内容：

```text
-------------------------

        重要短信

    收到 4 条重要短信

发送号码：
1069XXXXXXXXXXX

        11:32

   [   关闭提醒   ]

-------------------------
```

不展示：

```text
短信正文
规则关键词
短信历史
```

---

# 21. 锁屏相关 Window 设置

AlertActivity 应配置：

```text
showWhenLocked
turnScreenOn
```

目标：

锁屏时 Activity 可以展示。

注意：

这是：

**显示在锁屏之上**

不是：

**绕过用户锁屏认证。**

不得尝试解除 PIN / 指纹安全锁。

---

# 22. AlertService

只有真正发生报警时启动。

职责：

* 维持报警生命周期
* 播放声音
* 循环震动
* 管理 Audio Focus
* 响应 STOP
* 更新 Notification

不得用于：

```text
日常监听 SMS
```

---

# 23. Foreground Service 类型注意事项

如果实现采用 foreground service：

必须针对 targetSdk 35 正确声明 Service type 和对应权限。

Android 14+ 要求 App 声明前台服务类型；Android 15 对部分类型又增加了启动及运行时长限制。

不能随便声明：

```text
phoneCall
```

因为 phoneCall 类型需要满足默认拨号应用或特定 Telecom 条件。

因此开发阶段需要根据最终报警音频生命周期选择合法服务类型，不得为了“提高优先级”伪装成电话服务。

---

# 24. BootReceiver

Manifest：

```text
ACTION_BOOT_COMPLETED
```

用途不是：

```text
开机启动长期 Service
```

而是：

* 确认配置可读取
* 初始化必要状态
* 清理过期临时状态
* 保持 Manifest SMS Receiver 可用

Android 15 对从 `BOOT_COMPLETED` 直接启动多类前台服务已经增加限制，包括 `mediaPlayback`。

所以：

**开机以后绝对不要为了“保活”启动一个播放器/前台服务。**

---

# 25. 为什么开机后仍能监听

SMS Receiver 本身：

通过系统 SMS 广播工作。

因此不需要：

```text
BootReceiver → 启动 SMS Service
```

这种旧式架构。

正确思路：

```text
手机启动
↓
App 没进程也没关系
↓
SMS 到达
↓
Android 创建 App 进程
↓
调用 SmsReceiver
```

---

# 26. Battery / Memory 设计

空闲状态：

理想情况：

```text
App 进程可以不存在
```

因此：

* CPU ≈ 0
* 无后台轮询
* 无定时器
* 无 Socket
* 无 WakeLock
* 无常驻 Service
* 无常驻 Notification

这是本项目最重要的架构优势。

---

# 27. Android 电池限制

如果用户把 App 设置成：

```text
Restricted
```

Android 可能限制：

* 后台工作
* Foreground Service
* Alarm
* Job
* BOOT_COMPLETED

因此权限检查页必须检测/提示电池后台限制风险。Android 官方也明确说明 Restricted 状态可能禁止后台行为，并可能阻止 `BOOT_COMPLETED` 的正常及时投递。

---

# 28. vivo X200s 专项策略

因为只适配这一台设备：

最终发布前必须在真机完成：

```text
设置检查
↓
锁屏测试
↓
后台测试
↓
进程清理测试
↓
重启测试
↓
长时间待机测试
```

应用内“权限检查”页面不要把 vivo 设置菜单路径写死为唯一逻辑。

原因：

OriginOS 系统版本升级后菜单名称可能变化。

优先：

1. 能使用标准 Android Intent 跳转的，用标准 Intent
2. 无标准 Intent 的，给用户展示操作指引
3. 必要时再为当前 X200s / OriginOS 版本加入专用跳转

---

# 29. 权限建议

Manifest / Runtime 需要评估至少：

```text
RECEIVE_SMS
POST_NOTIFICATIONS
VIBRATE
RECEIVE_BOOT_COMPLETED
USE_FULL_SCREEN_INTENT
FOREGROUND_SERVICE
```

以及最终 AlertService 对应的：

```text
FOREGROUND_SERVICE_xxx
```

如果通话状态判断通过 Telephony API 实现：

还需要单独评估：

```text
READ_PHONE_STATE
```

能避免额外敏感权限则尽量避免。

---

# 30. 首次启动权限流程

不要一次性连续弹十几个授权。

推荐：

```text
欢迎页
↓
SMS 权限
↓
通知权限
↓
强提醒权限检查
↓
后台可靠性检查
↓
测试强提醒
↓
完成
```

最后必须强烈建议用户执行：

**测试强提醒**

只有测试通过：

首页状态才显示：

```text
● 强提醒配置完成
```

---

# 31. 权限状态模型

建议：

```text
READY
WARNING
BLOCKED
```

例如：

### READY

SMS + Notification + Full Screen 等关键项全部正常。

显示：

```text
● 强提醒正常
```

### WARNING

例如电池设置不理想。

显示：

```text
⚠ 可靠性可能受到影响
```

### BLOCKED

例如：

```text
RECEIVE_SMS 未授权
```

显示：

```text
🔴 无法监听短信
```

---

# 32. 铃声实现

用户选择系统铃声：

保存：

```text
Uri
```

例如：

```text
content://settings/system/alarm_alert
```

未选择：

使用：

```text
RingtoneManager.TYPE_ALARM
```

默认系统闹钟铃声。

---

# 33. Audio Attributes

声音用途应尽量按照：

```text
USAGE_ALARM
```

而不是：

```text
USAGE_MEDIA
```

目的：

让系统按照报警类音频策略处理。

使用当前：

**Alarm Volume**

不擅自修改用户 Media Volume。

---

# 34. Audio Focus

触发报警：

申请音频焦点。

其他媒体：

允许系统根据 Audio Focus：

* pause
* duck

报警关闭：

立即：

```text
abandonAudioFocus()
```

---

# 35. 蓝牙输出

需求：

**使用系统当前音频输出。**

因此不要人为调用：

```text
setSpeakerphoneOn(true)
```

也不要强制切换 Speaker。

如果系统当前 Alarm 音频路由至蓝牙耳机：

则耳机输出。

---

# 36. 通话状态

需求：

正在通话时：

```text
不响铃
震动
显示提醒
```

AlertController：

```text
if callInProgress:
    audio = false
    vibration = configuredValue
    showAlert()
```

用户挂断：

不得：

```text
自动开始补响
```

当前 Alert 仍保持可见即可。

---

# 37. Vibrator

Android 新版本：

优先使用：

```text
VibratorManager
```

Pattern：

```text
1000 ms ON
1000 ms OFF
repeat
```

关闭：

```text
cancel()
```

如果：

```text
vibrationEnabled == false
```

不启动震动。

---

# 38. 用户关闭报警

统一走：

```text
AlertCoordinator.stop()
```

不能每个入口分别写停止逻辑。

停止流程：

```text
stop audio
↓
abandon audio focus
↓
cancel vibrator
↓
cancel alert notification
↓
close AlertActivity
↓
clear temporary state
↓
count = 0
↓
enter COOLDOWN
```

---

# 39. Volume Key

AlertActivity：

拦截：

```text
KEYCODE_VOLUME_UP
KEYCODE_VOLUME_DOWN
```

收到：

```text
AlertCoordinator.stop()
return true
```

即：

**按一下立即关闭。**

不先调整音量。

---

# 40. “任意按键”限制

Activity 当前在前台时：

能收到的普通 `KeyEvent` 可以统一处理。

但是：

```text
Power
Home
部分系统导航键
```

由 Android System 管理。

不能要求普通 App 对这些按键实现可靠全局拦截。

因此 P0 强制保证：

```text
音量+
音量-
关闭按钮
```

其他按键：

Best Effort。

---

# 41. 60 秒 Cooldown

关闭时间：

```text
cooldownUntil =
elapsedRealtime() + 60_000
```

判断不要单纯依赖：

```text
System.currentTimeMillis()
```

因为用户修改系统时间可能导致冷却异常。

建议使用：

```text
SystemClock.elapsedRealtime()
```

这种单调时间。

---

# 42. 冷却状态无需后台 Timer

不要：

```text
启动 60 秒计时 Service
```

只保存：

```text
cooldownUntil
```

下一条 SMS 到达时：

```text
if elapsedRealtime < cooldownUntil:
    ignore
else:
    process
```

因此：

**60 秒冷却本身零后台能耗。**

---

# 43. 临时报警状态

DataStore：

```text
isAlerting
alertCount
latestSender
```

不得存：

```text
body
```

正常关闭：

全部清理。

如果 App 因异常退出：

下次进入 App 可以知道上一次提醒没有正常完成。

但“系统杀进程后是否自动继续无限报警”需要在 vivo X200s 真机验证，不能只依赖持久化变量承诺。

---

# 44. 不保存短信正文

RuleEngine：

正文只存在于当前内存。

流程结束：

引用释放。

禁止写入：

* SQLite
* DataStore
* 文件
* Logcat

尤其生产版禁止：

```text
Log.d("SMS", smsBody)
```

避免敏感短信出现在调试日志。

---

# 45. 安全日志

可以记录：

```text
SMS_RECEIVED
RULE_MATCHED(ruleId)
ALERT_STARTED
ALERT_STOPPED
```

不能记录：

```text
短信正文
完整敏感内容
```

发送号码 Debug 版本可考虑记录。

Release 版本最好脱敏：

```text
1069****8911
```

---

# 46. 测试强提醒

测试按钮：

绕过：

```text
SmsReceiver
RuleEngine
```

直接：

```text
AlertCoordinator.trigger(TEST)
```

用于验证：

* Notification
* FSI
* 声音
* Alarm volume
* Audio focus
* 蓝牙
* 震动
* 锁屏
* Volume key
* Stop
* Cooldown

测试结束：

可以选择不进入真实 Cooldown，避免影响实际短信。

---

# 47. 规则测试器

用户输入正文：

```text
【辽宁交警】……
```

RuleEngine 返回：

```text
RuleTestResult {
    matched
    matchedKeywords
    missingKeywords
}
```

页面：

```text
✓ 此短信会触发报警

命中：
✓ 【辽宁交警】
✓ 未按规定停放
✓ 请立即驶离
```

或者：

```text
✗ 不会触发

缺少：
× 请立即驶离
```

---

# 48. DataStore 建议结构

```text
masterEnabled

vibrationEnabled

ringtoneUri

rules[]

cooldownUntil

temporaryAlertState
```

规则：

```text
Rule {
    id
    enabled
    type

    phoneNumber?

    name?
    keywords?
}
```

---

# 49. Package 模块建议

```text
sms/
    SmsReceiver
    SmsParser

rules/
    RuleEngine
    PhoneRuleMatcher
    PlatformRuleMatcher

alert/
    AlertCoordinator
    AlertService
    AlertActivity
    AudioController
    VibrationController
    AlertNotificationManager

settings/
    SettingsRepository
    PermissionChecker

boot/
    BootReceiver

ui/
    MainScreen
    AddRuleScreen
    EditRuleScreen
    SettingsScreen
    PermissionScreen
    RuleTestScreen
```

---

# 50. 首个开发里程碑

不要第一天就实现所有设置页面。

第一个技术 Spike 只做：

```text
vivo X200s
↓
安装 APK
↓
授权 RECEIVE_SMS
↓
代码硬编码目标关键词
↓
锁屏
↓
真实 SMS 到达
↓
Receiver 收到
↓
发起报警
↓
声音 + 震动
↓
音量键关闭
```

只要这条链路跑通：

再开始做 UI 和完整规则系统。

---

# 51. 第二个里程碑

验证系统稳定性：

### Test A

App 最近任务划掉。

发目标短信。

必须测试。

### Test B

锁屏 30 分钟后发短信。

必须测试。

### Test C

手机重启，不打开 App。

发短信。

必须测试。

### Test D

连接蓝牙耳机。

发短信。

### Test E

正在通话。

发短信。

### Test F

连续发送 5 条目标短信。

必须保持一个 Alert。

---

# 52. 第三个里程碑

完成：

* 首页
* 总开关
* 规则 CRUD
* 关键词 AND
* Phone Rule
* 铃声选择
* 震动开关
* 权限检查
* 测试报警
* 规则测试器

---

# 53. 最终可靠性测试

目标机：

**vivo X200s / Android 15**

建议至少覆盖：

```text
前台
后台
最近任务划掉
熄屏
充电
未充电
Wi-Fi
移动网络
蓝牙连接
蓝牙断开
通话
勿扰
静音
重启
长时间待机
连续 SMS
双 SIM
```

---

# 54. 最重要的四条开发红线

### 红线 1

不得为了监听 SMS：

```text
启动 24 小时 Foreground Service
```

---

### 红线 2

不得：

```text
轮询短信数据库
```

---

### 红线 3

不得保存短信正文历史。

---

### 红线 4

不得把 Android 无法保证的系统行为伪装成已实现，例如：

```text
100% 绕过勿扰
100% 抢占其他 App 全屏
100% 捕获 Power/Home
```

这些必须以 vivo X200s 真机实测结果为最终依据。

---

# 55. V1 技术验收核心

V1 真正成功的标准不是：

“代码能运行”。

而是：

> vivo X200s 放在那里几小时甚至更久，App 平时不常驻、不轮询、不出现永久通知。当符合规则的真实 SMS 到达以后，系统仍然能够唤醒 App 并产生足够强的持续提醒。

开发验收必须以：

**真实 SIM + 真实短信 + 真实锁屏环境**

作为最终标准，而不能仅靠 Android Studio 模拟调用完成验收。
