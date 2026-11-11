# Adapters · 播放器桥接接入点（Kotlin）

「通用」是 `mcp_framework` 的目标，**桥（HTTP bridge）是可行的适配方法**：

- 服务器侧（`mcp_server/backends/`）：只认统一接口，通过**标准 HTTP 桥**读写任意播放器。
- 本目录：给出**桥本身**的规格与 Kotlin 模板，让任意 Android media3 播放器能内嵌一个小桥，
  把它的 MediaSession / ExoPlayer 暴露成本地 HTTP 端点，从而被 `generic_android.py` 后端接入。

一张图说清关系：

```
mcp_framework (Python, 通用)              目标播放器 App (Android, Kotlin)
┌──────────────────────────┐             ┌──────────────────────────────┐
│ generic_android.py        │  HTTP over  │  宿主播放器 (media3:         │
│  (Backend 客户端)          │ ──────────▶ │    PlaybackService/ExoPlayer)│
│  127.0.0.1:<port>         │  loopback   │  ├─ MediaDroidBridge.kt  ←── 内嵌桥
└──────────────────────────┘             │  │    GET /state /tracks ...   │
                                          │  │    POST /call {op:...}     │
                                          └──────────────────────────────┘
```

## 桥契约（标准端点）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 健康检查 `{"ok":true}` |
| GET | `/state` | 播放状态（含 `queue` 数组、`mode`、`playing`/`paused` 等）|
| GET | `/tracks?query=&limit=&offset=` | 曲库列表（可过滤）|
| GET | `/track?id=` | 单曲信息 |
| GET | `/search?q=` | 搜索 |
| GET | `/settings` | 全部设置 |
| GET | `/setting?key=` | 单项设置 |
| POST | `/call` | 控制指令 `{"op": ...}`，ops 见下表 |

`/call` 支持的 op：
`play` `toggle_play_pause` `pause` `next` `previous` `seek` `set_mode`
`append_to_queue` `play_next` `remove_from_queue` `move_queue_item` `set_setting`

## 目录

- `bridge-template/` — 可直接嵌入任意 media3 播放器的泛用 Kotlin 桥模板
  （`MediaDroidBridge.kt` + 最小接入说明），由宿主在一个 `ServerSocket` 线程上提供通用端点。
- `bridge-template/GramophoneBridge.kt` — **深入适配示例**：把开源简单的 media3 播放器
  `jackball24/MusicPlayer6`(Gramophone) 特有能力（设置键白名单 + 自定义 SessionCommand
  `set_timer/query_timer/get_lyrics` + MediaStore 正数 id）落地成专用桥，被 `gramophone` 后端接入。
- `saltaudio/` — SaltAudio 的既有桥接入（在 `saltaudio-dev` 工程内，`McpBridge`/`McpControlServer` 体系）。

## 如何接入一个新播放器

1. 把 `bridge-template/MediaDroidBridge.kt` 拷入目标播放器工程。
2. 在宿主的 `Application.onCreate()` 或 `MediaSessionService` 启动时，用目标 `MediaController`
   / `ExoPlayer` 调用 `MediaDroidBridge.start(context, port)`。
3. 用 `generic_android.py` 后端 + 环境变量 `MCP_BRIDGE_PORT=<port>` 启动 mcp_framework 即可控制。