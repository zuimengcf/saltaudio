pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}

rootProject.name = "MCPPlayerFrameworkMinipro"

// 一个最小可独立构建的 Android 示例工程：
//  - :mcp       —— 通用 MCP-over-HTTP 控制系统库（内嵌进任意播放器即可获得 MCP 控制能力）
//  - :minipro   —— 最简媒体3 播放器宿主，演示「一行 MCP 注入」
include(":mcp", ":minipro")