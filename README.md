# GuardStar

GuardStar 是一款面向老人、儿童等被守护人的 Android 定位守护客户端，支持位置共享、监护人地图查看、电子围栏、历史轨迹、SOS 求助和设备状态提醒。

本仓库只包含 Android 客户端。服务端实现、数据库配置和敏感证书不在公开仓库中。

## 功能

- **账号与守护关系**：验证码登录、监护人／被守护人角色、邀请码绑定与解除。
- **位置共享**：GPS／网络定位、自适应采样、本地 SQLite 队列、批量补传和断网退避。
- **持续运行**：前台服务、开机恢复、WorkManager 兜底补传、低电量节流和电池优化白名单引导。
- **监护人地图**：最新位置、精度圈、在线状态、电量与权限状态，支持主动刷新设备位置。
- **电子围栏**：安全区／危险区管理、地图展示及越界提醒。
- **历史轨迹**：时间范围查询、轨迹回放、起终点与停留点展示。
- **SOS 求助**：长按发起、紧急高频定位、监护人确认处理与误报记录。
- **设备状态提醒**：低电量、离线、恢复在线、定位关闭及围栏事件，支持系统通知和已读处理。

## 技术栈

- Kotlin、Java 17
- Jetpack Compose、Material 3、Navigation Compose
- Coroutines、WorkManager
- OkHttp、Kotlinx Serialization
- Android SQLiteOpenHelper
- 高德 3D 地图 SDK

## 环境要求

- JDK 17
- Android SDK 37，并安装 Android 37.1 扩展组件
- Android 8.0（API 26）及以上设备
- 设备 ABI：`arm64-v8a` 或 `armeabi-v7a`

## 本地配置

在项目根目录创建或编辑 `local.properties`。该文件已被 Git 忽略，不要提交真实密钥。

```properties
sdk.dir=/path/to/Android/Sdk
amap.key=your_amap_android_key
```

高德开放平台的 Key 需要绑定与构建变体一致的包名和签名 SHA1：

- release 包名：`com.guardstar.app`
- debug 包名：`com.guardstar.app.debug`

客户端默认请求当前临时部署地址 `http://120.48.105.157`（HTTP）。模拟器联调时可在 `app/build.gradle.kts` 中改为 `http://10.0.2.2:8080`；不要把个人凭证提交到仓库。

## 构建与安装

```bash
# 编译调试版
./gradlew :app:assembleDebug

# 安装到已连接设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

调试 APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可以使用 Android Studio 打开项目根目录，等待 Gradle 同步后直接运行 `app`。

## 测试与静态检查

```bash
# Kotlin 编译检查
./gradlew :app:compileDebugKotlin

# 单元测试
./gradlew :app:testDebugUnitTest

# Android Lint
./gradlew :app:lintDebug
```

定位、后台运行、通知和 SOS 等功能仍需要在两台真机上进行完整联调，模拟器无法覆盖所有系统限制与厂商后台策略。

## 发布签名（可选）

签名信息通过独立 properties 文件提供。在 `local.properties` 中指定其绝对路径：

```properties
guardstar.keystoreProperties=/absolute/path/to/keystore.properties
```

签名文件内容格式：

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

也可以通过 `GUARDSTAR_KEYSTORE_PROPERTIES` 环境变量指定该文件。发布版本可覆盖版本号：

```bash
./gradlew :app:assembleRelease -PversionCode=1 -PversionName=0.1.0
```

密钥库、签名密码和 properties 文件均不得提交到公开仓库。

## 权限说明

客户端会根据角色和功能申请以下权限：

- 精确定位与后台定位：用于被守护端主动开启的位置共享。
- 通知：用于位置共享前台服务、SOS 和设备状态提醒。
- 开机启动：用于已登录被守护端恢复位置共享。
- 忽略电池优化：由用户自行在系统设置中确认，减少后台采集中断。

位置坐标按原始 WGS-84 上报；仅在高德地图展示时转换为 GCJ-02。被守护端本地定位队列只保留最近 24 小时的数据。

## 代码结构

```text
app/src/main/java/com/guardstar/app/
├── core/       # 会话与通用基础设施
├── data/       # API、数据模型与本地定位队列
├── service/    # 定位采集、前台服务、通知与后台任务
└── ui/         # 登录、被守护端、监护人地图、围栏与轨迹页面
```

## 当前边界

- 本仓库不包含服务端，客户端需要配合兼容的 GuardStar API 使用。
- 监护人事件通知目前依赖 App 内轮询；进程被杀后的实时推送需要后续接入 WSS 或厂商推送。
- 发布前仍需完成隐私政策、用户授权流程、应用市场合规及目标 Android 版本的完整验收。

## 许可证

当前仓库尚未添加 `LICENSE` 文件，复用与分发前请先确认项目许可证。
