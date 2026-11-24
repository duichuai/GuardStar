plugins {
    // AGP 9 内置 Kotlin 支持；Compose 编译器插件仍需单独声明。
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
