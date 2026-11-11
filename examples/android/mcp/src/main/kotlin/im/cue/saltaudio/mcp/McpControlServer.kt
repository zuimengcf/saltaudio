package im.cue.saltaudio.mcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlinx.coroutines.runBlocking

/**
 * 可注入任意播放器的独立 MCP 控制系统。
 *
 * 宿主接入只要一行：
 * ```
 * McpControlServer.init(context, McpControlConfig(
 *     bridgeFactory = HostController.forService(context, MyMediaSessionService::class.java)
 * ))
 * McpControlServer.start()
 * ```
 */
object McpControlServer {

    @Volatile var appContext: Context? = null
        private set

    private var config: McpControlConfig? = null
    private var serverSockets: MutableList<ServerSocket> = mutableListOf()
    @Volatile private var serverThread: Thread? = null
    @Volatile private var running = false
    private val io = Executors.newCachedThreadPool()
    @Volatile private var connectionSlots: Semaphore = Semaphore(16)

    @Volatile private var bridge: PlayerBridge? = null
    @Volatile private var scannedTracks: List<TrackRef> = emptyList()
    private val tokenRng = SecureRandom()

    /** 若 config 未显式设置 accessToken，则自动生成并返回。 */
    @Volatile private var generatedToken: String? = null

    /** 运行期生效的安全配置（init 时从 code + SecurityStore 合并解析；默认兜底安全）。 */
    @Volatile private var security: SecuritySettings = SecuritySettings.UNKNOWN

    /** 供设置页展示用的只读配置快照。 */
    data class ConfigSnapshot(val title: String, val port: Int, val notificationsEnabled: Boolean)
    fun configSnapshot(): ConfigSnapshot? {
        val c = config ?: return null
        return ConfigSnapshot(c.notificationTitle ?: c.serverName, c.port, c.notificationsEnabled)
    }

    fun accessToken(): String? = config?.accessToken ?: generatedToken

    /** 重置访问令牌（无显式 accessToken 时生成新随机 token）。 */
    @Synchronized
    fun resetAccessToken(): String {
        val t = newToken()
        if (generatedToken != null) generatedToken = t
        return t
    }

    private fun newToken(): String {
        val raw = ByteArray(32)
        tokenRng.nextBytes(raw)
        return raw.joinToString("") { "%02x".format(it, Locale.ROOT) }
    }

    fun init(context: Context, cfg: McpControlConfig) {
        appContext = context.applicationContext
        config = cfg
        security = resolveSecurity(context, cfg)
        connectionSlots = Semaphore(security.maxConnections.coerceAtLeast(1))
        if (cfg.accessToken == null) generatedToken = newToken()
        if (cfg.notificationsEnabled) {
            McpStatusNotifier.ensureChannel(context, (cfg.notificationTitle ?: cfg.serverName) + " - MCP")
        }
        // 注册桌面快捷键（长按 app 图标 → 进入控制面板），作为通知之外不被消失的常驻入口，
        // 避免「停止后找不到设置入口」的困境。
        McpShortcut.register(context, cfg.notificationTitle ?: cfg.serverName, cfg.notificationIcon)
        McpLog.log(
            "init: port=${cfg.port} token=${if (accessToken() != null) "set" else "none"} " +
                "conn=${security.maxConnections} lan=${security.lanEnabled} loopbackOnly=${security.restrictToLoopback}"
        )
    }

    /** 合并代码默认值 + SecurityStore（外部只读）得到运行期安全配置；store 中已存在的键覆盖默认值。 */
    private fun resolveSecurity(context: Context, cfg: McpControlConfig): SecuritySettings {
        val base = SecuritySettings(
            socketTimeoutMs = cfg.socketTimeoutMs,
            maxConnections = cfg.maxConnections,
            restrictToLoopback = cfg.restrictToLoopback,
            allowedHosts = cfg.allowedHosts,
            checkOrigin = cfg.checkOrigin,
            maxRequestLineBytes = cfg.maxRequestLineBytes,
            maxHeaderBytes = cfg.maxHeaderBytes,
            maxHeaders = cfg.maxHeaders,
            maxBodyBytes = cfg.maxBodyBytes,
            maxResponseBytes = cfg.maxResponseBytes,
            sanitizeErrors = cfg.sanitizeErrors,
            lanEnabled = cfg.lanEnabled,
        )
        val overrides = runCatching { cfg.securityStore.load(context) }.getOrDefault(emptyMap())
        if (overrides.isEmpty()) return base
        fun b(key: String, fallback: Boolean): Boolean =
            (overrides[key] as? Boolean) ?: (overrides[key] as? Int)?.let { it != 0 } ?: fallback
        fun i(key: String, fallback: Int): Int =
            (overrides[key] as? Int) ?: (overrides[key] as? Long)?.toInt() ?: fallback
        val hosts = (overrides["allowed_hosts"] as? Set<*>)?.filterIsInstance<String>()?.toSet()
            ?: (overrides["allowed_hosts"] as? String)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: base.allowedHosts
        return SecuritySettings(
            socketTimeoutMs = i("socket_timeout_ms", base.socketTimeoutMs),
            maxConnections = i("max_connections", base.maxConnections),
            restrictToLoopback = b("restrict_to_loopback", base.restrictToLoopback),
            allowedHosts = hosts,
            checkOrigin = b("check_origin", base.checkOrigin),
            maxRequestLineBytes = i("max_request_line_bytes", base.maxRequestLineBytes),
            maxHeaderBytes = i("max_header_bytes", base.maxHeaderBytes),
            maxHeaders = i("max_headers", base.maxHeaders),
            maxBodyBytes = i("max_body_bytes", base.maxBodyBytes),
            maxResponseBytes = i("max_response_bytes", base.maxResponseBytes),
            sanitizeErrors = b("sanitize_errors", base.sanitizeErrors),
            lanEnabled = b("lan_enabled", base.lanEnabled),
        )
    }

    @Synchronized
    fun start() {
        if (running) return
        val cfg = config ?: return
        val ctx = appContext ?: return
        running = true
        serverThread = Thread {
            try {
                val bindHosts = mutableListOf<Pair<InetSocketAddress, String>>()
                bindHosts += InetSocketAddress("127.0.0.1", cfg.port) to "127.0.0.1"
                if (security.lanEnabled) {
                    privateLanIpv4Addresses().forEach { ip -> bindHosts += InetSocketAddress(ip, cfg.port) to ip }
                }
                val sockets = mutableListOf<Pair<ServerSocket, String>>()
                for ((addr, label) in bindHosts) {
                    try {
                        val ss = ServerSocket()
                        ss.reuseAddress = true
                        ss.bind(addr)
                        sockets += ss to label
                    } catch (_: Exception) { }
                }
                if (sockets.isEmpty()) { running = false; return@Thread }
                serverSockets = sockets.map { it.first }.toMutableList()
                sockets.forEach { (ss, label) ->
                    Thread {
                        try {
                            while (running && !ss.isClosed) {
                                val socket = try { ss.accept() } catch (e: Exception) { break }
                                if (!connectionSlots.tryAcquire()) {
                                    runCatching { socket.close() }
                                    continue
                                }
                                io.execute {
                                    try { handleSafe(socket, cfg, ctx) }
                                    finally { connectionSlots.release() }
                                }
                            }
                        } catch (_: Exception) { }
                    }.apply { isDaemon = true; name = "mcp-control-$label"; start() }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; name = "mcp-control-server"; start() }
        showStatusNotification()
    }

    /** 展示常驻状态通知（端口 / 运行状态 + 按钮操作；不显示 token 明文）。 */
    fun refreshStatusNotification() {
        showStatusNotification()
    }

    private fun showStatusNotification() {
        val cfg = config ?: return
        val ctx = appContext ?: return
        if (!cfg.notificationsEnabled) return
        if (accessToken() == null) return
        val title = cfg.notificationTitle ?: cfg.serverName
        val listeners = serverSockets.count { !it.isClosed && it.isBound }
        McpLog.log("status notification: $title running=$running port=${cfg.port}")
        McpStatusNotifier.show(ctx, title, running, cfg.port, listeners, cfg.notificationIcon)
    }

    @Synchronized
    fun stop() {
        running = false
        serverSockets.toList().forEach { runCatching { it.close() } }
        serverSockets.clear()
        bridge?.release()
        bridge = null
        appContext?.let { if (config?.notificationsEnabled == true) McpStatusNotifier.hide(it) }
    }

    fun status(): JSONObject = JSONObject()
        .put("running", running)
        .put("port", config?.port ?: -1)
        .put("listeners", serverSockets.count { !it.isClosed && it.isBound })
        .put("connected", bridge != null)
        .put("tokenSet", accessToken() != null)

    /** 供设置页展示当前生效的安全配置概览（仅只读展示）。 */
    fun securitySnapshot(context: Context): List<Pair<String, String>> = listOf(
        context.getString(R.string.mcp_sec_loopback) to (if (security.restrictToLoopback) context.getString(R.string.mcp_sec_loopback_only) else context.getString(R.string.mcp_sec_loopback_open)),
        context.getString(R.string.mcp_sec_lan) to (if (security.lanEnabled) context.getString(R.string.mcp_sec_on) else context.getString(R.string.mcp_sec_off)),
        context.getString(R.string.mcp_sec_max_conn) to security.maxConnections.toString(),
        context.getString(R.string.mcp_sec_socket_timeout) to (security.socketTimeoutMs / 1000).toString() + "s",
        context.getString(R.string.mcp_sec_max_body) to (security.maxBodyBytes / 1024).toString() + "KiB",
        context.getString(R.string.mcp_sec_max_response) to (security.maxResponseBytes / 1024).toString() + "KiB",
        context.getString(R.string.mcp_sec_host_check) to (if (security.restrictToLoopback) context.getString(R.string.mcp_sec_on) else context.getString(R.string.mcp_sec_off)),
        context.getString(R.string.mcp_sec_origin_check) to (if (security.checkOrigin) context.getString(R.string.mcp_sec_on) else context.getString(R.string.mcp_sec_off)),
        context.getString(R.string.mcp_sec_error_sanitize) to (if (security.sanitizeErrors) context.getString(R.string.mcp_sec_on) else context.getString(R.string.mcp_sec_off)),
    )

    /** 供设置页展示最近日志（只读）。 */
    fun logSnapshot(n: Int): List<String> =
        McpLog.dump().takeLast(n.coerceIn(1, 200))

    /**
     * 从设置页写入安全配置项并热生效。
     *
     * 写入默认的 `mcp_security` SharedPreferences（与默认 [SecurityStore.ofPrefs] 同源），
     * 重新解析运行期安全配置；若 MCP 正在运行则自动重启监听使局域网/回环等生效，并刷新通知。
     * 若宿主注入了自定义 SecurityStore，需由宿主自行接管写回路径。
     */
    @Synchronized
    fun updateSecurity(writes: Map<String, Any?>, restartIfRunning: Boolean = true) {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("mcp_security", Context.MODE_PRIVATE)
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
        val cfg = config ?: return
        security = resolveSecurity(ctx, cfg)
        McpLog.log("security updated: lan=${security.lanEnabled} loopbackOnly=${security.restrictToLoopback}")
        if (restartIfRunning && running) {
            stop()
            start()
        }
        refreshStatusNotification()
    }

    // ---------- 设置页/播放控制：给 McpSettingsActivity 使用的公开能力 ----------

    /** 当前播放快照；桥未连接返回 null。 */
    fun playbackSnapshot(): PlaybackSnapshot? = bridge?.snapshot()

    fun playbackToggle() { bridge?.let { runCatching { it.togglePlayPause() } } }
    fun playbackNext() { bridge?.let { runCatching { it.next() } } }
    fun playbackPrevious() { bridge?.let { runCatching { it.previous() } } }
    fun playbackVolume(): Float? = bridge?.getVolume()
    fun playbackSetVolume(v: Float) { bridge?.let { runCatching { it.setVolume(v.coerceIn(0f, 1f)) } } }

    private fun privateLanIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct().sorted()
    }.getOrDefault(emptyList())

    // ---------- 请求处理 ----------

    private fun handleSafe(socket: Socket, cfg: McpControlConfig, ctx: Context) {
        runCatching {
            handle(socket, cfg, ctx)
        }.onFailure { runCatching { socket.close() } }
    }

    private data class Req(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray)

    private fun handle(socket: Socket, cfg: McpControlConfig, ctx: Context) {
        socket.soTimeout = security.socketTimeoutMs.coerceAtLeast(1_000)
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val request = readRequest(input) ?: run {
            runCatching { socket.close() }
            return
        }
        McpLog.log("handle: ${request.method} ${request.target}")
        // 基础访问控制：Host 白名单 + Origin 校验（只放行本机/配置地址）
        val hostCheck = isHostAllowed(request.headers["host"])
        if (hostCheck != null) {
            writeResponse(output, hostCheck.first, hostCheck.second)
            runCatching { socket.close() }
            return
        }
        if (security.checkOrigin) {
            val origin = request.headers["origin"]
            if (origin != null && !isOriginAllowed(origin)) {
                writeResponse(output, 403, """{"error":"origin not allowed"}""")
                runCatching { socket.close() }
                return
            }
        }

        // 鉴权（accessToken 为空时可关闭；本机回环默认无需 token）
        val token = accessToken()
        if (token != null) {
            val auth = request.headers["authorization"]
            if (auth != "Bearer $token") {
                writeResponse(output, 401, """{"jsonrpc":"2.0","error":{"code":-32001,"message":"unauthorized"}}""")
                runCatching { socket.close() }
                return
            }
        }

        val response = processHttp(request, cfg, ctx)
        writeResponse(output, response.first, response.second)
        runCatching { socket.close() }
    }

    /** Host 校验；返回 null=通过，否则 Pair(状态码, 错误体)。restrictToLoopback 时只放行本机回环。 */
    private fun isHostAllowed(hostHeader: String?): Pair<Int, String>? {
        if (!security.restrictToLoopback) return null
        val h = hostHeader?.trim()?.lowercase(Locale.ROOT) ?: return 400 to """{"error":"host required"}"""
        val hostOnly = h.substringBefore(':')
        val loopback = hostOnly == "127.0.0.1" || hostOnly == "localhost" || hostOnly == "::1" || hostOnly == "[::1]"
        if (loopback) return null
        if (hostOnly in security.allowedHosts) return null
        return 403 to """{"error":"host not allowed"}"""
    }

    private fun isOriginAllowed(origin: String): Boolean {
        val o = origin.trim().lowercase(Locale.ROOT)
        return o.isEmpty() ||
            o == "null" ||
            o.startsWith("http://127.0.0.1") || o.startsWith("http://localhost") ||
            o.startsWith("https://127.0.0.1") || o.startsWith("https://localhost") ||
            security.allowedHosts.any { host -> o.contains(host, ignoreCase = true) }
    }

    private fun readRequest(input: BufferedInputStream): Req? {
        val requestLine = readAsciiLine(input, security.maxRequestLineBytes) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        var headerCount = 0
        while (true) {
            val line = readAsciiLine(input, security.maxHeaderBytes) ?: break
            if (line.isEmpty()) break
            if (++headerCount > security.maxHeaders) return null
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase(Locale.ROOT)] = line.substring(idx + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > security.maxBodyBytes) return null
        val body = if (len > 0) ByteArray(len).also { readFully(input, it) } else ByteArray(0)
        return Req(parts[0].uppercase(Locale.ROOT), parts[1], headers, body)
    }

    private fun readFully(input: BufferedInputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) break
            offset += n
        }
    }

    private fun readAsciiLine(input: BufferedInputStream, maxBytes: Int): String? {
        val buf = StringBuilder()
        var bytes = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) return buf.toString().trimEnd('\r')
            buf.append(b.toChar())
            if (++bytes > maxBytes) return null
        }
    }

    private fun writeResponse(output: BufferedOutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 500 -> "Internal Server Error"; else -> "OK"
    }

    // ---------- 协议（简化 MCP-over-HTTP：JSON-RPC 单请求单响应） ----------

    /** 严格 UTF-8 解码器：遇到非法字节抛异常（CodingErrorAction.REPORT）。 */
    private val strictDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)

    private fun processHttp(req: Req, cfg: McpControlConfig, ctx: Context): Pair<Int, String> {
        if (req.method == "GET" && req.target == "/health") {
            return 200 to JSONObject().put("ok", true).put("server", cfg.serverName).put("version", cfg.serverVersion).toString()
        }
        if (req.method != "POST") return 405 to """{"error":"method not allowed"}"""
        if (req.target != "/mcp") return 404 to """{"error":"not found"}"""
        // 严格 UTF-8 解码：非法编码直接拒 400（CodingErrorAction.REPORT）
        val bodyText = try {
            ByteBuffer.wrap(req.body).let { strictDecoder.decode(it).toString() }
        } catch (e: Exception) {
            return 400 to """{"error":"bad encoding: invalid utf-8"}"""
        }
        val json = runCatching { JSONObject(bodyText) }.getOrElse {
            return 400 to """{"error":"bad json"}"""
        }
        val method = json.optString("method")
        val id = json.opt("id")
        val params = json.optJSONObject("params") ?: JSONObject()
        val responseBody: String = try {
            when (method) {
                "initialize" -> wrapResult(id, JSONObject()
                    .put("protocolVersion", params.optString("protocolVersion", "2025-06-18"))
                    .put("serverInfo", JSONObject().put("name", cfg.serverName).put("version", cfg.serverVersion))
                    .put("capabilities", JSONObject().put("tools", JSONObject())))
                "notifications/initialized", "initialized" -> wrapResult(id, JSONObject().put("ok", true))
                "tools/list" -> wrapResult(id, toolsList())
                "tools/call" -> wrapResult(id, callTool(params, cfg, ctx))
                "ping" -> wrapResult(id, JSONObject().put("ok", true))
                else -> JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", id)
                    .put("error", JSONObject().put("code", -32601).put("message", "method not found: $method"))
                    .toString()
            }
        } catch (e: ToolError) {
            McpLog.log("error $method: ${e.message}")
            JSONObject()
                .put("jsonrpc", "2.0").put("id", id)
                .put("result", JSONObject().put("isError", true).put("content",
                    JSONArray().put(JSONObject().put("type", "text").put("text", e.message ?: "error"))))
                .toString()
        } catch (e: Exception) {
            McpLog.log("internal error $method: ${e.message}")
            JSONObject()
                .put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", -32603).put("message", "internal error"))
                .toString()
        }
        // 错误脱敏 + 响应体 256KiB 限幅（-32002）
        val sanitized = if (security.sanitizeErrors) sanitizeErrorText(responseBody) else responseBody
        val limited = clampResponse(sanitized, id)
        return 200 to limited
    }

    private fun wrapResult(id: Any?, result: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0").put("id", id).put("result", result).toString()

    /** 响应体限幅：超过 maxResponseBytes 时返回 -32002（请求方应分页/缩小范围）。 */
    private fun clampResponse(body: String, id: Any?): String {
        if (body.toByteArray(Charsets.UTF_8).size <= security.maxResponseBytes.coerceAtLeast(1_024)) return body
        McpLog.log("response over max ${security.maxResponseBytes} bytes; returning -32002")
        return JSONObject()
            .put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject()
                .put("code", -32002)
                .put("message", "response too large (>${security.maxResponseBytes} bytes); narrow your request / paginate"))
            .toString()
    }

    /** 错误消息脱敏：隐藏 Bearer token、Authorization 值、超长十六进制/hash。 */
    private fun sanitizeErrorText(text: String): String {
        var s = text
        // 隐藏 "Bearer <token>"
        s = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=\\-]{8,}").replace(s) { "Bearer <redacted>" }
        // 隐藏超长十六进制串（≥40 位，倾向是 sha/hash）
        s = Regex("#?[0-9a-fA-F]{40,}").replace(s) { "<redacted:" + it.value.length + ">" }
        // 隐藏带引号的密钥值
        s = Regex("(\"(?:token|secret|password|authorization)\"\\s*:\\s*\")[^\"]{4,}\"").replace(s) { it.value.substringBefore('"') }
        return s
    }

    private class ToolError(msg: String) : Exception(msg)

    // ---------- 工具 ----------

    private fun toolsList(): JSONObject {
        val tools = JSONArray()
        fun t(name: String, desc: String, schema: String = "{}") {
            tools.put(JSONObject()
                .put("name", name).put("description", desc)
                .put("inputSchema", JSONObject(schema)))
        }
        // 播放层
        t("playback_get_state", "读取当前播放状态：播放/暂停/进度/时长/队列/当前曲目/播放模式")
        t("playback_toggle", "切换播放/暂停状态")
        t("playback_play", "开始或继续播放（不替换队列）")
        t("playback_pause", "暂停播放")
        t("playback_next", "切换到队列下一首")
        t("playback_previous", "切换到队列上一首（播放超过 3 秒先回到开头）")
        t("playback_seek", "跳转到指定毫秒位置", """{"type":"object","properties":{"position_ms":{"type":"integer","description":"目标位置（毫秒），≥0"}},"required":["position_ms"]}""")
        t("playback_set_mode", "设置播放模式", """{"type":"object","properties":{"mode":{"type":"string","enum":["SEQUENTIAL","LIST_LOOP","SINGLE_LOOP","SHUFFLE"],"description":"播放模式"}},"required":["mode"]}""")
        t("playback_set_volume", "设置播放器音量（0.0–1.0，播放器级，不影响系统媒体音量）", """{"type":"object","properties":{"volume":{"type":"number","description":"目标音量，0.0 静音 – 1.0 最大"}},"required":["volume"]}""")
        t("playback_get_volume", "读取当前播放器音量")
        t("playback_start_tracks", "按曲库 ID 替换队列并起播", """{"type":"object","properties":{"track_ids":{"type":"array","items":{"type":"integer"},"description":"曲库曲目 ID 列表（1–200）"},"start_index":{"type":"integer","description":"从 0 开始的起播位置，默认 0"}},"required":["track_ids"]}""")
        // 队列层
        t("queue_append_tracks", "追加曲目到队列末尾", """{"type":"object","properties":{"track_ids":{"type":"array","items":{"type":"integer"},"description":"要追加的曲库 ID 列表"}},"required":["track_ids"]}""")
        t("queue_insert_next", "插入曲目到当前曲目之后", """{"type":"object","properties":{"track_ids":{"type":"array","items":{"type":"integer"},"description":"要插入的曲库 ID 列表"}},"required":["track_ids"]}""")
        t("queue_remove_item", "按索引移除队列项", """{"type":"object","properties":{"index":{"type":"integer","description":"队列位置（0 起）"}},"required":["index"]}""")
        t("queue_move_item", "移动队列项", """{"type":"object","properties":{"from_index":{"type":"integer","description":"当前位置（0 起）"},"to_index":{"type":"integer","description":"目标位置（0 起）"}},"required":["from_index","to_index"]}""")
        // 曲库层
        t("library_list_tracks", "分页列出曲库曲目", """{"type":"object","properties":{"query":{"type":"string","description":"可选，按标题/艺术家/专辑模糊搜索"},"limit":{"type":"integer","description":"每页数量（1–200，默认 50）"},"offset":{"type":"integer","description":"从 0 开始的偏移，默认 0"}}}""")
        t("library_get_track", "按 ID 获取单曲详情", """{"type":"object","properties":{"track_id":{"type":"integer","description":"曲库曲目 ID"}},"required":["track_id"]}""")
        t("library_search_tracks", "搜索曲库（标题/艺术家/专辑模糊匹配）", """{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"},"limit":{"type":"integer","description":"最多返回数量（默认 50）"}},"required":["query"]}""")
        t("library_get_summary", "曲库统计：歌曲总数、艺术家数、专辑数、总时长")
        t("library_get_playlists", "获取播放清单。后端不支持时返回 supported=false（属正常情况，非错误）。")
        t("library_refresh", "请求重新扫描曲库（后台异步）")
        // 设置层
        t("settings_get", "读取单个设置项", """{"type":"object","properties":{"key":{"type":"string","description":"设置键名"}},"required":["key"]}""")
        t("settings_set", "写入单个设置项", """{"type":"object","properties":{"key":{"type":"string","description":"设置键名"},"value":{"description":"设置值（String/Boolean/Int/Long/Float）"}},"required":["key","value"]}""")
        t("settings_get_all", "读取全部设置项")
        t("settings_get_schema", "获取该项目支持的设置项清单（键名/说明/类型）")
        // 运维
        t("logs_get", "读取 MCP 操作日志", """{"type":"object","properties":{"lines":{"type":"integer","description":"返回最近多少条（1–200，默认 50）"}}}""")
        return JSONObject().put("tools", tools)
    }

    private fun callTool(params: JSONObject, cfg: McpControlConfig, ctx: Context): JSONObject {
        val name = params.optString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        // 日志只记工具名与参数键，不记参数值（避免敏感值经 logs_get/logcat 外漏）。
        McpLog.log("call $name args=[${args.keys().asSequence().joinToString(",")}]")
        val data: Any = when (name) {
            // 播放层
            "playback_get_state" -> playbackState()
            "playback_toggle" -> bridgeOp("playback_toggle") { it.togglePlayPause() }
            "playback_play" -> bridgeOp("playback_play") { it.play() }
            "playback_pause" -> bridgeOp("playback_pause") { it.pause() }
            "playback_next" -> bridgeOp("playback_next") { it.next() }
            "playback_previous" -> bridgeOp("playback_previous") { it.previous() }
            "playback_seek" -> bridgeOp("playback_seek") {
                it.seekTo(args.optLong("position_ms", 0L).coerceAtLeast(0L))
            }
            "playback_set_mode" -> bridgeOp("playback_set_mode") {
                val mode = args.optString("mode", "SEQUENTIAL")
                val shuffle = mode == "SHUFFLE"
                val repeat = when (mode) {
                    "SINGLE_LOOP" -> "ONE"
                    "LIST_LOOP" -> "ALL"
                    "SHUFFLE" -> "ALL"
                    else -> "OFF"
                }
                it.setMode(shuffle, repeat)
            }
            "playback_set_volume" -> bridgeOp("playback_set_volume") {
                it.setVolume(args.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f))
            }
            "playback_get_volume" -> {
                val b = ensureBridge() ?: throw ToolError("player not connected — host app not running?")
                val v = runCatching { b.getVolume() }.getOrElse { throw ToolError("get volume failed: ${it.message}") }
                JSONObject().put("ok", true).put("volume", v.toDouble())
            }
            "playback_start_tracks" -> playTracks(args, ctx)
            // 队列层
            "queue_append_tracks" -> queueOp(args, ctx) { b, items -> b.appendToQueue(items) }
            "queue_insert_next" -> queueOp(args, ctx) { b, items -> b.insertNext(items) }
            "queue_remove_item" -> bridgeOp("queue_remove_item") {
                it.removeQueueItem(args.optInt("index", 0).coerceAtLeast(0))
            }
            "queue_move_item" -> bridgeOp("queue_move_item") {
                it.moveQueueItem(
                    args.optInt("from_index", 0).coerceAtLeast(0),
                    args.optInt("to_index", 0).coerceAtLeast(0)
                )
            }
            // 曲库层
            "library_list_tracks" -> {
                val query = args.optString("query", "").lowercase()
                val limit = args.optInt("limit", 50).coerceIn(1, 200)
                val offset = args.optInt("offset", 0).coerceAtLeast(0)
                val all = ensureTracks(ctx)
                val filtered = all.filter { query.isEmpty() ||
                    (it.title ?: "").lowercase().contains(query) ||
                    (it.artist ?: "").lowercase().contains(query) ||
                    (it.album ?: "").lowercase().contains(query) }
                val page = filtered.drop(offset).take(limit)
                JSONObject().put("ok", true).put("total", filtered.size)
                    .put("offset", offset).put("limit", limit)
                    .put("returned", page.size)
                    .put("tracks", JSONArray().apply { page.forEach { tr ->
                        put(JSONObject()
                            .put("id", tr.id).put("title", tr.title).put("artist", tr.artist)
                            .put("album", tr.album).put("duration_ms", tr.durationMs))
                    } })
            }
            "library_get_track" -> {
                val id = args.optLong("track_id", -1L)
                val tr = ensureTracks(ctx).firstOrNull { it.id == id }
                if (tr != null) JSONObject().put("ok", true).put("track",
                    JSONObject().put("id", tr.id).put("title", tr.title).put("artist", tr.artist)
                        .put("album", tr.album).put("duration_ms", tr.durationMs))
                else JSONObject().put("ok", false).put("error", "track not found")
            }
            "library_search_tracks" -> {
                val q = args.optString("query", "").lowercase()
                val limit = args.optInt("limit", 50).coerceIn(1, 200)
                val results = ensureTracks(ctx).filter {
                    (it.title ?: "").lowercase().contains(q) ||
                    (it.artist ?: "").lowercase().contains(q) ||
                    (it.album ?: "").lowercase().contains(q)
                }.take(limit)
                JSONObject().put("ok", true).put("count", results.size)
                    .put("tracks", JSONArray().apply { results.forEach { tr ->
                        put(JSONObject()
                            .put("id", tr.id).put("title", tr.title).put("artist", tr.artist)
                            .put("album", tr.album).put("duration_ms", tr.durationMs))
                    } })
            }
            "library_get_summary" -> {
                val tracks = ensureTracks(ctx)
                val artists = tracks.mapNotNull { it.artist }.filter { it.isNotBlank() }.toSet()
                val albums = tracks.mapNotNull { it.album }.filter { it.isNotBlank() }.toSet()
                val total = tracks.sumOf { it.durationMs }
                JSONObject().put("ok", true).put("track_count", tracks.size)
                    .put("artist_count", artists.size).put("album_count", albums.size)
                    .put("total_duration_ms", total)
            }
            "library_get_playlists" -> JSONObject().put("ok", true)
                .put("supported", false).put("playlists", JSONArray())
            "library_refresh" -> {
                scannedTracks = emptyList()
                kotlinx.coroutines.runBlocking { ensureTracks(ctx) }
                McpLog.log("library_refresh: scanned ${scannedTracks.size} tracks")
                JSONObject().put("ok", true).put("track_count", scannedTracks.size)
            }
            // 设置层
            "settings_get" -> {
                val key = args.optString("key")
                val all = cfg.settingsStore.apply(emptyMap())
                JSONObject().put("ok", true).put("key", key).put("value", all[key] ?: JSONObject.NULL)
            }
            "settings_set" -> {
                val key = args.optString("key")
                val value = args.opt("value")
                val writes = linkedMapOf<String, Any?>(key to (if (value == JSONObject.NULL) null else value))
                val all = cfg.settingsStore.apply(writes)
                JSONObject().put("ok", true).put("key", key).put("value", all[key] ?: JSONObject.NULL)
            }
            "settings_get_all" -> {
                val all = cfg.settingsStore.apply(emptyMap())
                JSONObject().put("ok", true).put("settings", JSONObject(all.mapValues { kv ->
                    when (kv.value) { null -> JSONObject.NULL; else -> kv.value }
                }))
            }
            "settings_get_schema" -> JSONObject().put("ok", true).put("settings", JSONArray())
            // 运维
            "logs_get" -> {
                val lines = args.optInt("lines", 50).coerceIn(1, 200)
                val all = McpLog.dump()
                JSONObject().put("ok", true).put("count", all.size)
                    .put("log", all.takeLast(lines).joinToString("\n"))
            }
            else -> throw ToolError("unknown tool: $name")
        }
        McpLog.log("done $name")
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", data.toString())))
            .put("structuredContent", data)
            .put("isError", false)
    }

    private fun okAfter(action: String, vararg extra: Pair<String, Any?>): JSONObject {
        val o = JSONObject().put("ok", true).put("action", action)
        extra.forEach { (k, v) -> o.put(k, v) }
        bridge?.let { o.put("state", stateJson(it.snapshot())) }
        return o
    }

    private fun stateJson(s: PlaybackSnapshot): JSONObject = JSONObject()
        .put("playing", s.playing).put("position_ms", s.positionMs).put("duration_ms", s.durationMs)
        .put("current_index", s.currentIndex).put("queue_size", s.queueSize)
        .put("shuffle", s.shuffle).put("repeat", s.repeatMode)
        .put("current_title", s.currentTitle ?: JSONObject.NULL)
        .put("current_artist", s.currentArtist ?: JSONObject.NULL)

    /** 通过 Bridge 执行操作并回读状态 */
    private fun bridgeOp(tool: String, op: (PlayerBridge) -> Unit): JSONObject {
        val b = ensureBridge() ?: throw ToolError("player not connected — host app not running?")
        runCatching { op(b) }.getOrElse { throw ToolError("operation failed on $tool: ${it.message}") }
        return okAfter(tool.substringAfterLast('_'))
    }

    private fun playbackState(): JSONObject {
        val b = ensureBridge() ?: throw ToolError("player not connected — host app not running?")
        return stateJson(b.snapshot())
    }

    /** 懒连接：首次调用时通过 bridgeFactory 连宿主 */
    private fun ensureBridge(): PlayerBridge? {
        bridge?.let { return it }
        val cfg = config ?: return null
        val ctx = appContext ?: return null
        val b = runCatching {
            kotlinx.coroutines.runBlocking { cfg.bridgeFactory(ctx) }
        }.getOrElse {
            McpLog.log("bridge connect failed: ${it.message}")
            null
        } ?: return null
        bridge = b
        McpLog.log("bridge connected")
        return b
    }

    private fun playTracks(args: JSONObject, ctx: Context): JSONObject {
        val ids = args.optJSONArray("track_ids") ?: throw ToolError("track_ids required")
        if (ids.length() == 0) throw ToolError("track_ids empty")
        val start = args.optInt("start_index", 0).coerceIn(0, ids.length() - 1)
        val byId = ensureTracks(ctx).associateBy { it.id }
        val items = (0 until ids.length()).mapNotNull { byId[ids.optLong(it, -1L)] }
        if (items.isEmpty()) throw ToolError("no matching tracks found in MediaStore")
        val b = ensureBridge() ?: throw ToolError("player not connected — host app not running?")
        b.playQueue(items, start)
        val o = JSONObject().put("ok", true).put("action", "STARTED").put("queued", items.size)
        o.put("state", stateJson(b.snapshot()))
        return o
    }

    /** 队列操作通用辅助：解析 track_ids → TrackRef → 调 bridge 队列方法 */
    private fun queueOp(args: JSONObject, ctx: Context, op: (PlayerBridge, List<TrackRef>) -> Unit): JSONObject {
        val ids = args.optJSONArray("track_ids") ?: throw ToolError("track_ids required")
        if (ids.length() == 0) throw ToolError("track_ids empty")
        val byId = ensureTracks(ctx).associateBy { it.id }
        val items = (0 until ids.length()).mapNotNull { byId[ids.optLong(it, -1L)] }
        if (items.isEmpty()) throw ToolError("no matching tracks found in MediaStore")
        val b = ensureBridge() ?: throw ToolError("player not connected — host app not running?")
        runCatching { op(b, items) }.getOrElse { throw ToolError("queue operation failed: ${it.message}") }
        return okAfter("queue_op", "queued" to items.size)
    }

    @Synchronized
    private fun ensureTracks(ctx: Context): List<TrackRef> {
        if (scannedTracks.isEmpty()) {
            scannedTracks = runCatching {
                kotlinx.coroutines.runBlocking {
                    val custom = config?.trackSource
                    if (custom != null) custom.scan(ctx) else mediaStoreScan(ctx)
                }
            }.getOrElse {
                McpLog.log("track scan failed: ${it.message}")
                emptyList()
            }
        }
        return scannedTracks
    }

    /** 内置 MediaStore 扫描 → TrackRef */
    private suspend fun mediaStoreScan(ctx: Context): List<TrackRef> =
        McpTrackScanner.scan(ctx).map { t ->
            TrackRef(t.id, t.uri.toString(), t.title, t.artist, t.album, t.durationMs)
        }
}

/**
 * 运行期生效的安全配置（代码默认值 + SecurityStore 外部覆盖合并后的结果）。
 *
 * 字段与 [McpControlConfig] 的安全项一一对应；[UNKNOWN] 为兜底默认值（在没有 init 前使用）。
 */
data class SecuritySettings(
    val socketTimeoutMs: Int,
    val maxConnections: Int,
    val restrictToLoopback: Boolean,
    val allowedHosts: Set<String>,
    val checkOrigin: Boolean,
    val maxRequestLineBytes: Int,
    val maxHeaderBytes: Int,
    val maxHeaders: Int,
    val maxBodyBytes: Int,
    val maxResponseBytes: Int,
    val sanitizeErrors: Boolean,
    val lanEnabled: Boolean,
) {
    companion object {
        /** 兜底默认值：保守安全。init 前 / resolveSecurity 失败时使用。 */
        val UNKNOWN = SecuritySettings(
            socketTimeoutMs = 10_000,
            maxConnections = 16,
            restrictToLoopback = true,
            allowedHosts = emptySet(),
            checkOrigin = true,
            maxRequestLineBytes = 4_096,
            maxHeaderBytes = 8_192,
            maxHeaders = 20,
            maxBodyBytes = 256 * 1024,
            maxResponseBytes = 256 * 1024,
            sanitizeErrors = true,
            lanEnabled = false,
        )
    }
}