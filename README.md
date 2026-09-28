# 短信强提醒（MessageStar）

面向 Android 8.0 及以上设备的原生短信强提醒应用。应用监听系统 `SMS_RECEIVED` 广播，在短信命中手机号或关键词规则时播放提醒音、振动并显示高优先级提醒。项目当前重点适配 Android 15 与 vivo/OriginOS。

## 主要功能

- 手机号规则：归一化 `+86`、`86`、空格、短横线后精确匹配
- 平台短信规则：1～5 个关键词全部命中时触发
- 最多 10 条规则，支持总开关、单条开关、编辑和删除
- 组装长短信后再执行规则匹配
- 后台悬浮提醒，并在未授权悬浮窗时回退到高优先级全屏通知
- 提醒期间亮屏、振动并临时提高闹钟音量，关闭后恢复
- 重复命中时聚合计数，最长提醒 5 分钟，关闭后冷却 60 秒
- 系统铃声选择、试听、规则测试和权限检查
- 通知筛选与收纳：按 APP 与标题/正文关键词保留、轻提醒或强提醒重要通知；其余记录到本地收纳箱
- 应用内检查更新：显示版本说明、下载并校验正式 APK，再交由系统安装器确认覆盖安装

## 应用内更新通道

当前源码仓库是私有的，不能把其 GitHub Releases API 或资产直链直接放进面向普通用户的 App；也不要把 GitHub Token 写进 APK。更新清单和已签名的正式 APK 应单独放在可匿名访问的公开 HTTPS 地址，源码与签名密钥保持私有。

默认更新地址是公开仓库经 GitHub Pages 提供的 [`update.json`](https://zcohlaz.github.io/message-star-updates/update.json)；测试时可在构建命令中用 `-PmessageStarUpdateManifestUrl=https://.../update.json` 覆盖。若显式配置为空，“设置 → 检查更新”会显示“更新通道尚未配置”，不会联网。App 启动时最多每 24 小时自动检查一次；用户也可在设置页手动检查。强提醒进行中不检查、下载或发起安装。

`update.json` 是 UTF-8 JSON，字段为 `packageName`、`versionCode`、`versionName`、`notes`、`apkUrl`、`sizeBytes`、`sha256`。发布步骤：

1. 将 `app/build.gradle.kts` 中的 `versionCode` 提高，并用原正式密钥构建单个 APK。不要用 Debug 签名或新密钥覆盖旧版。
2. 先将 APK 上传至公开 HTTPS 下载地址，确认匿名用户能直接下载。APK 不要与源码、密钥或开发文档打包在一起。
3. 用 `scripts/New-UpdateManifest.ps1` 根据该 APK 生成清单，并核对版本号和下载 URL；最后上传 `update.json`。清单应在 APK 可下载之后发布。
4. 在旧版真机上验证检查、下载、未知来源授权和系统安装确认；安装后确认规则、通知收纳及设置仍在。Android 不允许普通 App 静默安装，用户必须在系统界面确认。

App 下载后校验文件大小、SHA-256、包名、更高的版本号和与当前安装一致的签名。若计划发布到 Google Play，需要使用商店内更新机制并移除当前站外安装流程。

## 通知筛选与收纳

1. 在首页打开“通知筛选与收纳”，授予通知使用权。服务会列出发送过通知的 APP。
2. 选择要管理的 APP，并从该 APP 的系统通知设置中将来源通知设为静默、关闭横幅；不要关闭其通知总开关。
3. 为重要消息添加规则：可匹配来源 APP、标题和正文，并选择保留、轻提醒或持续强提醒。规则按列表顺序匹配；未命中的受管 APP 通知进入收纳箱。
4. 最后打开“启用通知筛选”。新安装的 APP 默认不受管理，短信规则独立运行。

收纳记录只保存在设备内，最多保留 30 天、1000 条；可以在收纳箱中单条删除或清空。MessageStar 不上传通知正文，且不参与系统备份与设备迁移。Android 的通知监听在通知发布后才回调，因此无法保证过滤通知完全不闪现。部分系统、持续、通话及闹钟通知不会被自动收纳；内容被系统隐藏时默认保留。轻提醒可能在原通知旁短暂显示一条 MessageStar 提示。强提醒的锁屏后台表现需在目标 vivo/OriginOS 真机上验证。

## 技术栈

- Kotlin 1.9.25、Java 17
- Android SDK 35（最低 SDK 26）
- Jetpack Compose、Material 3、Navigation Compose
- AndroidX Lifecycle、DataStore
- Kotlin Coroutines、Kotlin Serialization
- Gradle 8.9、Android Gradle Plugin 8.7.3

## 项目结构

```text
app/src/main/              应用代码、Manifest 和资源
app/src/test/              JVM 单元测试
app/build.gradle.kts       Android 模块构建配置
gradle/wrapper/            Gradle Wrapper
```

真机调试说明保留在仓库根目录的 `真机调试指南.md` 中。

## 环境要求

- Android Studio（支持 AGP 8.7.x）
- JDK 17
- Android SDK Platform 35
- Windows、macOS 或 Linux

不要在共享的 `gradle.properties` 中配置本机 `org.gradle.java.home`。请使用 Android Studio 的 Gradle JDK 设置或本机 `JAVA_HOME`。

## 安装与运行

1. Clone 仓库并使用 Android Studio 打开项目根目录。
2. 等待 Gradle 同步完成。
3. 连接 Android 设备或启动模拟器。
4. 运行 `app` 配置。
5. 首次启动后按界面提示授予短信和通知权限。

命令行安装 Debug 包：

```powershell
.\gradlew.bat :app:installDebug --no-daemon
```

macOS/Linux 使用 `./gradlew` 替代 `.\gradlew.bat`。

## 构建、测试与检查

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

Debug APK 生成于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release 构建：

```powershell
.\gradlew.bat :app:assembleRelease --no-daemon
```

未配置本地签名时，Release 产物不会使用正式证书签名。

## 配置与签名

Android SDK 的本机路径由 `local.properties` 管理，该文件不会提交。

如需本地 Release 签名：

1. 将 `keystore.properties.example` 复制为 `keystore.properties`。
2. 填写签名文件路径、密码和别名。
3. 确保签名文件保存在安全位置且已备份。

`keystore.properties`、JKS、keystore、P12、PEM 和私钥均已被 `.gitignore` 排除。不要把真实密码或密钥写入 README、CI 文件或提交历史。

## 基础交互验收

在启用手势导航的真机上验证以下路径；顶部返回按钮与侧滑返回应得到相同结果：

1. 首页 → 设置 → 权限检查 → 返回：依次回到设置、首页；首页返回才退出 App。
2. 首页 → 权限检查 → 返回：回到首页，不退出 App。
3. 新建或编辑规则后修改字段，侧滑返回/点“取消”：弹出“放弃修改”确认；继续编辑时草稿仍在。
4. 编辑中旋转屏幕、切到后台再返回：当前页面、弹窗、已输入字段和规则列表滚动位置保持。
5. 从权限检查跳到系统设置并返回：短信、通知、悬浮窗等状态重新读取。
6. 保存、切换或删除规则：出现操作反馈；删除后点“撤销”可恢复原规则及顺序。
7. 在系统设置中强制结束 App 后重新打开：已保存的规则仍在。编辑草稿的恢复依赖系统是否保留任务状态，不承诺跨强制停止恢复。
## 真机验证重点

使用真实 SIM 验证锁屏、最近任务划掉、重启、连续短信、蓝牙和通话场景。Android 15 与厂商系统可能将全屏提醒降级为 Heads-up，最终可靠性应以目标设备实测为准。

## 常见问题

- **Gradle 找不到 Java：**确认 Android Studio Gradle JDK 或 `JAVA_HOME` 指向 JDK 17。
- **无法收到短信：**确认设备具备电话能力，并已授予短信权限。
- **后台提醒不出现：**检查通知、悬浮窗、电池优化、自启动和后台运行权限。
- **Release 未签名：**创建本地 `keystore.properties` 并确认其中的签名文件真实存在。

## 开发规范

- 不提交构建产物、IDE 状态、本地路径、日志或签名材料。
- 不在日志中输出短信正文、手机号、Token 或其他敏感数据。
- 修改规则引擎时同步补充单元测试。
- 提交前至少运行单元测试、Lint 和 Debug 构建。

## Git 分支与 Commit

小型项目使用 `main` 作为稳定分支；日常开发使用 `feature/*`，缺陷修复使用 `fix/*`。

Commit 使用 Conventional Commits，例如：

```text
feat(alert): add critical SMS notification
fix(rules): normalize mainland phone numbers
docs(readme): document local build setup
```

## 许可证

当前未声明开源许可证。将项目公开发布前，请由项目所有者明确选择并添加 LICENSE；在此之前不得假定代码已获开源授权。
