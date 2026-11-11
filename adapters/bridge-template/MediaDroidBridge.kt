/*
 * MediaDroidBridge.kt — 通用 Android media3 HTTP 桥模板
 *
 * 「桥是可行的适配方法」的落地：把任意 media3 播放器(MediaSession/ExoPlayer)暴露成
 * 标准本地 HTTP 端点，从而被 mcp_framework 的 generic_android.py 后端接入。
 *
 * 用法（宿主 —— 任意 media3 播放器 App）：
 *   在 Application.onCreate() 或 MediaSessionService 启动时：
 *       MediaDroidBridge.start(applicationContext) { context -> controllerFactory }
 *
 * 说明：
 *   - 桥用一个 ServerSocket 线程提供 /health /state /tracks /track /search /call 等端点。
 *   - 通过 MediaController 跨进程连接宿主自己的 MediaSession（同进程内更简单，直接用
 *     ExoPlayer 实例即可）。
 *   - 端口默认 18736；可用 MCP_BRIDGE_PORT 环境变量或参数覆盖。
 *   - 依赖：androidx.media3:media3-common / media3-session + 系统 org.json。
 *
 * 本文件是「最小可用模板」——各端点的 JSON 形状与 mcp_framework 后端契约一致
 * （见 adapters/README.md）。
 */
package com.example.media3bridge

import android.content.Context
import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 通用桥。用 [factory] 在需要时拿到一个 [Player]（media3 的 ExoPlayer 或 MediaController 代理）。
 */
object MediaDroidBridge {
    private var serverThread: Thread? = null
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "media-droid-bridge") }

    // 宿主在需要真正访问播放器时通过它拿到当前 player
    @Volatile
    private var playerProvider: (() -> Player?)? = null

    /** 启动桥。port 默认 18736。 */
    fun start(port: Int = 18736, provider: () -> Player?) {
        playerProvider = provider
        if (running) return
        running = true
        serverThread = Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", port))
                serverSocket = ss
                while (running) {
                    val s = try { ss.accept() } catch (_: Exception) { break }
                    io.execute { handle(s) }
                }
            } catch (_: Exception) {
                running = false
            }
        }.apply { isDaemon = true; name = "media-droid-bridge"; start() }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    // ---------------------------------------------------------------- handlers
    private fun handle(socket: Socket) {
        try {
            val reader = BufferedInputStream(socket.getInputStream())
            // 解析请求行 + 原始 body
            val req = readRequest(reader)
            val path = req.path
            val body = req.body

            val respBody: String = when {
                path == "/health" -> JSONObject().put("ok", true).toString()

                path == "/state" -> buildState()

                path.startsWith("/tracks") -> buildTracks(queryParam(path, "query"),
                    queryParamInt(path, "limit", 200), queryParamInt(path, "offset", 0))

                path.startsWith("/track") -> buildTrack(queryParam(path, "id"))

                path.startsWith("/search") -> search(queryParam(path, "q"))

                path.startsWith("/settings") -> buildSettings()

                path.startsWith("/setting") -> buildSetting(queryParam(path, "key"))

                path.startsWith("/call") && req.method == "POST" -> doCall(JSONObject(body))

                else -> JSONObject().put("error", "not found").toString()
            }
            writeResponse(socket, respBody)
        } catch (e: Exception) {
            runCatching {
                writeResponse(socket, JSONObject().put("error", e.message ?: "err").toString())
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    // ---------------------------------------------------------------- 状态
    private fun buildState(): String {
        val p = player() ?: return stateFromPlayer(null)
        // 主线程访问 player，这里用 runBlocking 委托——模板简化为主线程直接读（宿主应包一层 handler）
        return stateFromPlayer(p)
    }

    private fun stateFromPlayer(p: Player?): String {
        val o = JSONObject()
        o.put("playing", p?.isPlaying ?: false)
        o.put("paused", p != null && !p.isPlaying && p.playbackState != Player.STATE_IDLE)
        o.put("buffering", p?.playbackState == Player.STATE_BUFFERING)
        o.put("position_ms", p?.currentPosition ?: 0L)
        o.put("duration_ms", p?.duration ?: 0L)
        o.put("current_index", p?.currentMediaItemIndex ?: -1)
        o.put("mode", modeName(p))
        val q = JSONArray()
        if (p != null) {
            for (i in 0 until p.mediaItemCount) {
                val mi = p.getMediaItemAt(i)
                q.put(itemToJson(mi))
            }
        }
        o.put("queue", q)
        return o.toString()
    }

    private fun modeName(p: Player?): String = when {
        p == null -> "SEQUENTIAL"
        p.shuffleModeEnabled -> "SHUFFLE"
        p.repeatMode == Player.REPEAT_MODE_ONE -> "SINGLE_LOOP"
        p.repeatMode == Player.REPEAT_MODE_ALL -> "LIST_LOOP"
        else -> "SEQUENTIAL"
    }

    private fun itemToJson(mi: MediaItem): JSONObject {
        val o = JSONObject()
        o.put("id", mi.mediaId)
        val md = mi.mediaMetadata
        o.put("title", md.title?.toString() ?: "")
        o.put("artist", md.artist?.toString() ?: "")
        o.put("album", md.albumTitle?.toString() ?: "")
        o.put("duration_ms", mi.playbackProperties?.durationMs ?: 0L)
        o.put("uri", mi.localConfiguration?.uri?.toString() ?: "")
        return o
    }

    // ---------------------------------------------------------------- 曲库（MediaStore 简化示意）
    // 真实实现应扫 MediaStore；这里给出占位，宿主可替换为自己的曲库。
    private fun buildTracks(query: String?, limit: Int, offset: Int): String {
        // TODO: 宿主接入点 —— 从自己的 MediaStore / 数据库查询曲目
        return JSONObject().apply {
            put("tracks", JSONArray())
        }.toString()
    }

    private fun buildTrack(id: String?): String {
        return JSONObject().put("track", JSONObject()).toString()
    }

    private fun search(q: String?): String {
        return buildTracks(q, 100, 0)
    }

    // ---------------------------------------------------------------- 设置
    private fun buildSettings(): String = JSONObject().put("settings", JSONObject()).toString()

    private fun buildSetting(key: String?): String = JSONObject().put("value", JSONObject.NULL).toString()

    private fun doCall(body: JSONObject): String {
        val op = body.optString("op", "")
        val p = player()
        when (op) {
            "play" -> {
                val ids = body.optJSONArray("track_ids")
                // 简化：无队列建立逻辑，直接让宿主处理；这里仅调 play
                p?.play()
            }
            "toggle_play_pause" -> p?.let { if (it.isPlaying) it.pause() else it.play() }
            "pause" -> p?.pause()
            "next" -> p?.next()
            "previous" -> p?.previous()
            "seek" -> p?.seekTo(body.optLong("position_ms", 0L))
            "set_mode" -> {
                when (body.optString("mode", "SEQUENTIAL")) {
                    "SHUFFLE" -> p?.shuffleModeEnabled = true
                    "LIST_LOOP" -> { p?.shuffleModeEnabled = false; p?.repeatMode = Player.REPEAT_MODE_ALL }
                    "SINGLE_LOOP" -> { p?.shuffleModeEnabled = false; p?.repeatMode = Player.REPEAT_MODE_ONE }
                    else -> { p?.shuffleModeEnabled = false; p?.repeatMode = Player.REPEAT_MODE_OFF }
                }
            }
            // 队列操作（占位：宿主接入实际队列管理）
            else -> {}
        }
        return JSONObject().put("state", JSONObject(stateFromPlayer(p))).toString()
    }

    // ---------------------------------------------------------------- helpers
    private fun player(): Player? = runCatching { playerProvider?.invoke() }.getOrNull()

    private fun queryParam(path: String, key: String): String? {
        val idx = path.indexOf('?')
        if (idx < 0) return null
        val q = path.substring(idx + 1)
        return q.split("&").firstOrNull { it.startsWith("$key=") }?.substringAfter("=")
    }

    private fun queryParamInt(path: String, key: String, def: Int): Int =
        queryParam(path, key)?.toIntOrNull() ?: def

    private data class Req(val method: String, val path: String, val body: String)

    private fun readRequest(reader: BufferedInputStream): Req {
        val head = StringBuilder()
        var c = reader.read()
        var contentLength = -1
        while (c >= 0) {
            if (head.isNotEmpty() && head.endsWith("\r\n\r\n")) break
            head.append(c.toChar())
            c = reader.read()
        }
        val lines = head.toString().split("\r\n")
        val requestLine = lines.firstOrNull()?.split(" ") ?: listOf("GET", "/", "")
        for (l in lines) {
            if (l.lowercase().startsWith("content-length:")) {
                contentLength = l.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        var body = ""
        if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            body = String(buf, 0, read, StandardCharsets.UTF_8)
        }
        return Req(requestLine[0], requestLine[1], body)
    }

    private fun writeResponse(socket: Socket, jsonBody: String) {
        val bytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    /** 便捷工厂：连到宿主自己的 MediaSessionService 并代理其 player。 */
    fun controllerOf(context: Context, serviceClass: Class<*>): MediaController {
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, serviceClass))
        val future = MediaController.Builder(app, token).buildAsync()
        return future.get()
    }
}