package im.cue.saltaudio.mcp

import android.content.Context

/**
 * MCP 独立控制系统的注入契约。
 *
 * 宿主播放器只需要提供一样核心东西：
 * 1. 一个 [PlayerBridge] 工厂 —— 连到宿主自己的播放内核。
 *    media3 播放器直接用 [HostController.forService]；
 *    其他栈（media-compat 等）实现自己的 Bridge 即可，server 零改动。
 *
 * 歌曲读取默认走内置 MediaStore 扫描；宿主可用 [trackSource] 覆盖
 * （例如曲库在 JS 侧/在线源的播放器）。
 */
data class McpControlConfig(
    /** 连接宿主播放器的 Bridge 工厂 */
    val bridgeFactory: suspend (Context) -> PlayerBridge?,
    /**
     * 设置接管：key -> 原始值（String/Int/Long/Boolean/Float）。
     * 写入 null 表示删除该 key。
     */
    val settingsStore: SettingsStore = SettingsStore.DEFAULT,
    /** 曲库来源；null 表示用内置 MediaStore 扫描 */
    val trackSource: TrackSource? = null,
    /** HTTP 监听端口 */
    val port: Int = 18790,
    /** 访问令牌；null 表示自动生成（经通知按钮复制取用） */
    val accessToken: String? = null,
    /** MCP 服务名（工具列表里展示） */
    val serverName: String = "salt-mcp-control",
    val serverVersion: String = "1.0.0",

    // ================= 基础安全能力（默认安全） =================

    /** Socket 读超时（毫秒）。防慢速/死连接占用线程。 */
    val socketTimeoutMs: Int = 10_000,
    /** 最大并发连接数。防止无界线程池被打爆。 */
    val maxConnections: Int = 16,
    /** 是否只允许本机回环 Host 访问。默认 true。 */
    val restrictToLoopback: Boolean = true,
    /** 额外的允许 Host（restrictToLoopback 为 true 时，在回环之外额外放行）。 */
    val allowedHosts: Set<String> = emptySet(),
    /** 是否校验 Origin 头。默认 true。 */
    val checkOrigin: Boolean = true,
    /** 请求行最大字节数。 */
    val maxRequestLineBytes: Int = 4_096,
    /** 单个头最大字节数。 */
    val maxHeaderBytes: Int = 8_192,
    /** 最大头数量。 */
    val maxHeaders: Int = 20,
    /** 请求体最大字节数。 */
    val maxBodyBytes: Int = 256 * 1024,
    /** 响应体最大字节数；超限返回 -32002。 */
    val maxResponseBytes: Int = 256 * 1024,
    /** 是否启用错误消息脱敏。默认 true。 */
    val sanitizeErrors: Boolean = true,
    /** 是否监听局域网地址。默认 false（仅回环）。 */
    val lanEnabled: Boolean = false,
    /** 安全配置读取源；默认从 "mcp_security" prefs 只读，宿主可注入。
     *  安全配置只从宿主侧读取，绝不通过 MCP settings_* 工具写入（避免自身控制回环）。 */
    val securityStore: SecurityStore = SecurityStore.ofPrefs("mcp_security"),
    /** 是否启用常驻状态通知（端口/运行状态 + 按钮操作）。默认 true。 */
    val notificationsEnabled: Boolean = true,
    /** 常驻通知的小图标；默认用系统图标，宿主可换成自己的 R.drawable。 */
    val notificationIcon: Int = android.R.drawable.ic_menu_manage,
    /** 常驻通知的展示名（标题），默认沿用 [serverName]。 */
    val notificationTitle: String? = null,
)

/**
 * 安全配置读取源（只读）。安全项由宿主外部写入（无 UI，可经 adb 写 prefs / 改代码），
 * MCP 侧的 settings_* 工具不会接触这些键——避免"MCP 控制自身安全策略"的回环。
 */
fun interface SecurityStore {
    /** 返回安全配置键值对（String / Boolean / Int / Long / Float）。 */
    fun load(context: Context): Map<String, Any?>

    companion object {
        /** 默认实现：从指定 SharedPreferences 文件只读。 */
        fun ofPrefs(name: String = "mcp_security") = SecurityStore { ctx ->
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all
        }
    }
}

fun interface SettingsStore {
    /** 返回当前全部设置（扁平键值）；写 null 表示删除 */
    fun apply(writes: Map<String, Any?>): Map<String, Any?>

    companion object {
        /** 默认实现：读写宿主指定 SharedPreferences 文件 */
        fun ofPrefs(prefsName: String) = SettingsStore { writes ->
            val ctx = McpControlServer.appContext ?: return@SettingsStore emptyMap()
            val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            writes.forEach { (k, v) ->
                when (v) {
                    null -> prefs.edit().remove(k).apply()
                    is Boolean -> prefs.edit().putBoolean(k, v).apply()
                    is Int -> prefs.edit().putInt(k, v).apply()
                    is Long -> prefs.edit().putLong(k, v).apply()
                    is Float -> prefs.edit().putFloat(k, v).apply()
                    is String -> prefs.edit().putString(k, v).apply()
                }
            }
            prefs.all
        }
        val DEFAULT = ofPrefs("mcp_host_settings")
    }
}

/** 曲库来源：宿主可提供自己的曲库（默认实现为 MediaStore 扫描） */
fun interface TrackSource {
    suspend fun scan(context: Context): List<TrackRef>
}