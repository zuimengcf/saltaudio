// 工具链与 saltaudio-dev 保持一致（已验证可构建成功）：
//  - AGP 9.4.1 内置 Kotlin 支持，模块只需声明 com.android.* 插件即可编译 Kotlin 源码
//  - 与模块的 compileSdk=37 兼容
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
}