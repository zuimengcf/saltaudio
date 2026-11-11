# MiniPro 示例 —— 最小 Android 播放器 + 通用 MCP 接入

一个**最小可独立构建的 Android 示例工程**，演示如何用一个普通 media3 播放器
（空 `MediaSessionService`）通过一行注入获得完整的 MCP (Model Context Protocol) 控制能力。

它是对 `../` 框架主仓库「适配任意播放器」目标的 **Android 侧落地实证**：
- `:mcp`     —— 通用 MCP-over-HTTP 控制系统库（可内嵌进任意播放器）
- `:minipro` —— 最简 media3 播放器宿主，演示「一行 MCP 注入」

## 结构

```
examples/android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties   # 独立工程构建配置
├── gradlew / gradle/wrapper/                                     # Gradle Wrapper
├── mcp/       # Android 库模块 —— 内嵌式 MCP 控制系统
│   └── src/main/kotlin/im/cue/saltaudio/mcp/
│       ├── McpControlServer.kt      # 核心：MCP-over-HTTP 服务器（协议/安全/工具分发）
│       ├── PlayerBridge.kt          # 桥接口 + HostController 工厂 + Media3Bridge 实现
│       ├── BridgeAdaptor.kt         # 桥适配
│       ├── McpControlContract.kt    # 配置/安全/设置/曲库源契约
│       ├── McpTrackScanner( Access).kt
│       ├── McpLog / McpShortcut / McpStatusNotifier / McpSettingsActivity
│       └── (mcp 模块完全自包含，只依赖 media3 + coroutines + androidx.core)
└── minipro/   # Android 应用模块 —— 最小播放器宿主
    └── src/main/kotlin/im/cue/saltaudio/minipro/
        ├── MiniService.kt    # 最简方 MediaSessionService（只暴露一个 ExoPlayer）
        └── MainActivity.kt   # 播放页面 + 一行 MCP 注入 + 曲库 / 队列 / MCP 日志
```

## 构建

```bash
# 需在 gradle.properties / local.properties 配好 Android SDK 路径后执行
./gradlew :minipro:assembleDebug
# 产物：minipro/build/outputs/apk/debug/minipro-debug.apk
```

（仓库不提交 `local.properties`，首次构建需自行创建 `local.properties` 写入
 `sdk.dir=<你的 SDK 路径>`，或用 `ANDROID_HOME` 环境变量。）

## 核心：一行 MCP 注入

`MainActivity.kt` 里只需这几行，就让任意播放器拥有完整的 MCP 控制能力：

```kotlin
McpControlServer.init(
    applicationContext,
    McpControlConfig(
        bridgeFactory = HostController.forService(this, MiniService::class.java),
        port = 18790,
        accessToken = null
    )
)
McpControlServer.start()
```

- `MiniService` 是宿主自己的 `MediaSessionService`（这里用最简 ExoPlayer）
- `HostController.forService` 自动连到该服务并桥接播放器
- 启动后即可在 `http://127.0.0.1:18790/mcp` 用 MCP JSON-RPC 控制播放器

## MCP 端点 / 工具

启动后通过 MCP 客户端（或 curl）访问，工具见 `McpControlServer.toolsList()`，分四层：

| 层 | 前缀 | 示例 |
|---|---|---|
| 播放 | `playback_*` | `playback_get_state` / `playback_play` / `playback_set_mode` / `playback_start_tracks` |
| 队列 | `queue_*` | `queue_append_tracks` / `queue_move_item` |
| 曲库 | `library_*` | `library_list_tracks` / `library_search_tracks` / `library_get_summary` |
| 设置 | `settings_*` | `settings_get` / `settings_set` / `settings_get_all` / `logs_get` |

```bash
# 读取播放状态
curl -X POST http://127.0.0.1:18790/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"playback_get_state","arguments":{}}}'
```

## 页面上还有什么

MiniPro 不止是 MCP 服务端，还自带一个可用的播放页面：播放/暂停/切歌/进度拖动/
音量调节/播放模式切换/曲库列表/播放队列，以及一个 **MCP 实时日志区**，方便直观看到
每次 MCP 请求与响应。

## 与框架主仓库的关系

- 本示例里的 `:mcp` 库模块 = 框架文档里说的「Android 宿主侧桥（bridge）」的完整可运行实现。
- 框架的 `backends/`（Python 侧 `generic_android` / `saltaudio` 等）通过本桥暴露的
  HTTP 端点即可远程读写该播放器状态并下发控制指令。
- 对比 `../adapters/bridge-template/` 里的**最小模板**，`:mcp` 是功能更完整的参考实现
  （含安全配置、鉴权、设置页、状态通知等）。