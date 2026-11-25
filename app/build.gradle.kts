import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 发布签名配置来自本地，不进入仓库：优先环境变量，其次 local.properties 指向的文件。
fun loadKeystoreProperties(): Properties? {
    val candidates = mutableListOf<File>()
    System.getenv("GUARDSTAR_KEYSTORE_PROPERTIES")?.let { candidates.add(File(it)) }
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) {
        val local = Properties().apply { localProperties.inputStream().use { load(it) } }
        local.getProperty("guardstar.keystoreProperties")?.let { candidates.add(File(it)) }
    }
    candidates.add(rootProject.file("keystore.properties"))
    val file = candidates.firstOrNull { it.isFile } ?: return null
    return Properties().apply { file.inputStream().use { load(it) } }
}

// 高德地图 Key 来自 local.properties（不进仓库），缺失时填空避免构建中断。
fun loadAmapKey(): String {
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) {
        val local = Properties().apply { localProperties.inputStream().use { load(it) } }
        local.getProperty("amap.key")?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

android {
    namespace = "com.guardstar.app"
    compileSdk = 37
    // Compose 1.13.0 要求编译目标为 android-37.1
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.guardstar.app"
        minSdk = 26
        targetSdk = 37
        // 版本号可由 -PversionCode / -PversionName 覆盖（CI 发布时按 tag 注入）。
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1.0"

        // 开发默认指向宿主机；真机联调时改为局域网地址。
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080\"")

        // 高德 3D 地图 SDK 仅支持 armeabi-v7a 与 arm64-v8a。
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        // 高德地图 Key 由 local.properties 注入，不写入仓库。
        manifestPlaceholders["amapKey"] = loadAmapKey()
    }

    signingConfigs {
        val keystoreProperties = loadKeystoreProperties()
        if (keystoreProperties != null) {
            create("release") {
                storeFile = File(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        } else {
            logger.warn("未找到发布签名配置，release 构建将不签名")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 高德 3D 地图 SDK（含地图+定位+搜索，官方推荐单依赖，避免子模块版本冲突）
    implementation("com.amap.api:3dmap-location-search:latest.integration")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
