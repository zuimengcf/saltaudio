# MCP Player Framework · 通用播放器接入层

一个**独立存在、适配所有播放器**的 MCP(Model Context Protocol) 接入层。

- 纯 Python 标准库，零第三方依赖，开箱即用。
- 通过**后端适配器(Backend Adapter)模式**，MCP 服务器只认抽象接口，**不绑定任何具体播放器**。
- 自带 4 个后端：`demo`（内存演示）/ `saltaudio` / `generic_android`（任意 Media3）/ `gramophone`。
- 支持 **stdio** 与 **HTTP** 两种 MCP 传输，可接入 Operit / Claude Desktop 等任意 MCP 客户端。

```
┌─────────────────────────────────────────────────────────┐
│   AI 客户端(任意 MCP 客户端)      │
└───────────────────────────┬─────────────────────────────┘
                            │  MCP 协议 (JSON-RPC over stdio / HTTP)
┌───────────────────────────▼─────────────────────────────┐
│            MCP Server (本工程, 与播放器解耦)                │
│   tools:  PlaybackLayer + LibraryLayer + SettingsLayer    │
└───────────────────────────┬─────────────────────────────┘
                            │  backend 抽象接口
┌───────────────────────────▼─────────────────────────────┐
│         Adapter: 每个播放器一个后端实现                      │
│   saltaudio.py      → SaltAudio(盐音)                    │
│   generic_android.py→ 任意 Android Media3 播放器           │
│   (可扩展: spotify / 本地文件 / 其它)                       │
└───────────────────────────┬─────────────────────────────┘
                            │  IPC / 进程 / 网络
                 ┌──────────▼──────────┐
                 │  目标播放器进程        │
                 └─────────────────────┘
```

## 三层职责（严格分离）

| 层 | 工具前缀 | 职责 |
|---|---|---|
| 播放层 Playback | `playback_*` | 播放/暂停/切歌/seek/队列/播放模式 |
| 曲库层 Library | `library_*` | 读取歌曲元数据 / 曲库信息 / 搜索 |
| 设置层 Settings | `settings_*` | 读写项目设置（播放模式偏好/收藏/过滤/外观） |

## 目录结构

```
mcp-player-framework/
├── README.md
├── LICENSE                  # MIT
├── run_server.py            # 入口（stdio 模式）
├── run_http_server.py       # 入口（HTTP 模式）
├── mcp_server/
│   ├── protocol.py          # MCP JSON-RPC 协议层
│   ├── server.py            # MCP server 核心（initialize/tools/list/call）
│   ├── tools_playback.py    # 播放层工具
│   ├── tools_library.py     # 曲库层工具
│   ├── tools_settings.py    # 设置层工具
│   └── backends/
│       ├── __init__.py      # backend 注册表（多播放器选择）
│       ├── base.py          # 三个抽象接口（唯一契约）
│       ├── demo.py          # 内存演示后端
│       ├── saltaudio.py     # SaltAudio(盐音) 后端
│       ├── generic_android.py # 通用 Android Media3 后端
│       └── gramophone.py    # Gramophone / MusicPlayer6 后端
└── adapters/
    ├── README.md            # 桥契约与接入说明
    └── bridge-template/     # 可直接嵌入任意 media3 播放器的 Kotlin 桥模板
        ├── MediaDroidBridge.kt
        └── GramophoneBridge.kt

└── examples/
    └── android/             # 独立可构建的 Android 示例工程（MiniPro）
        ├── mcp/             # 通用 MCP-over-HTTP 控制系统库（内嵌式桥，功能完整）
        └── minipro/         # 最简 media3 播放器宿主，演示「一行 MCP 注入」
```

`examples/android/` 是一个可独立构建的最小 Android 播放器示例：把内嵌式 MCP 控制系统
(`mcp` 库) + 最简 media3 播放器 (`minipro`) 放进同一个 apk，启动后在
`http://127.0.0.1:18790/mcp` 即可用 MCP 控制播放。详见 `examples/android/README.md`。

## Android 内嵌 MCP 库（`:mcp`，Kotlin）

`examples/android/mcp` 是可在任意播放器工程内嵌的通用 MCP-over-HTTP 控制库（Kotlin）：

- **传输**：`POST /mcp`（HTTP JSON-RPC），本地回环 `http://127.0.0.1:<port>/mcp`
- **协议**：双版本协商（`2025-06-18` / `2024-11-05`）、`server/discover`、`tools/list` 与
  `tools/call` 响应携带 `resultType` / `ttlMs` / `cacheScope`，便于客户端缓存与类型感知
- **安全**：Bearer 鉴权（可选 `accessToken`）、Host/Origin 校验、错误信息脱敏
- **A 档增强**：
  - `McpPage` —— 游标分页（Base64URL v1 cursor；`limit` / `offset` / `cursor` 三选一）
  - `McpCommandChain` —— 链式命令解析，schema 注入 `commands` 字段，一次调用多指令
  - `McpCommandGate` —— 播放命令互斥栅栏（`budgetMs=3000`），冲突时返回 Busy 而非排队
- **错误模型**：结构化错误 `failure_category` / `failure_stage` / `cause_category` / `cause_message`
- **3 秒规则**：`playback_previous` 若当前播放进度超过 3 秒，本次调用只会回到本曲开头
  （不切歌），需再次调用才会真正切到上一首；若当前进度不超过 3 秒直接切上一首

## 发行物

本仓库同时分发两个安装包（见 Releases）：

| 资产 | 角色 | 说明 |
|---|---|---|
| `SaltAudio-0.5.29-main.apk` | 主体播放器 | 本项目深度定制版，内嵌完整 MCP 控制面（`:mcp` 库），**闭源分发**——仅提供 APK，不随仓库提供源码 |
| `MiniPro-1.0-example.apk` | 开源示例 | 对应 `examples/android`，一行代码注入即得 MCP 控制能力的演示宿主，源代码即本仓库 `examples/android/`（MIT） |

- **SaltAudio（主体）**：以本框架的 `:mcp` 库为内核深度定制而成，与 MiniPro 共用同一套 MCP 服务实现；因闭源，仓库中不含其源码。
- **MiniPro（示例）**：完整开源，可供开发者对照学习"如何给自己的播放器接入 MCP"。

## 安装

零第三方依赖，仅需 Python 3.9+：

```bash
# 直接运行（无需安装）
python3 run_server.py --list-backends
```

## 快速开始

```bash
# stdio 模式（供 MCP 客户端以 stdio spawn）—— demo 后端无需任何播放器
python3 run_server.py --backend demo

# 通用 Android Media3 桥后端（经任意宿主 App 暴露的标准 HTTP 桥）
MCP_BRIDGE_PORT=18737 python3 run_server.py --backend generic_android

# Gramophone / MusicPlayer6 后端（开源简单 media3 播放器，深入适配）
MCP_BRIDGE_PORT=18801 python3 run_server.py --backend gramophone

# HTTP 模式（供远程/浏览器客户端）
python3 run_http_server.py --port 8000 --backend demo

# 列出可用后端
python3 run_server.py --list-backends
```

### 桥（Bridge）：把任意 media3 播放器接入 generic_android 后端

「通用」是目标，「桥」是可行的适配方法。`generic_android` 后端不绑定任何具体 App，
只要目标 Android media3 播放器通过一个**标准 HTTP 桥**暴露播放能力，即可被统一接入：

1. 把 `adapters/bridge-template/MediaDroidBridge.kt` 拷入目标播放器工程，
   在 `Application.onCreate()` 或 `MediaSessionService` 里用 `MediaDroidBridge.start(port) { player }` 启动桥。
2. 用环境变量 `MCP_BRIDGE_HOST` / `MCP_BRIDGE_PORT` 指向该桥的地址，运行 generic_android 后端。
3. 详细的桥契约与接入步骤见 `adapters/README.md`。

已知桥适配示例：SaltAudio(`McpBridge`/`McpControlServer`)、
MiniPro/Sona 的 `:mcp` 通用模块（`McpControlServer` 打进同一 apk，暴露本地 HTTP 控制面）、
Gramophone/MusicPlayer6（见 `adapters/bridge-template/GramophoneBridge.kt` + `backends/gramophone.py`）。

## 后端选择

新增后端 = 新建一个继承抽象接口的 python 文件并在 `backends/__init__.py` 注册。
`base.py` 中的 `PlaybackBackend / LibraryBackend / SettingsBackend` 是唯一契约。

## 许可证

[MIT](./LICENSE)