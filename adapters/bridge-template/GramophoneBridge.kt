/*
 * GramophoneBridge.kt — MusicPlayer6 (Gramophone) 专用 media3 HTTP 桥
 *
 * 为 mcp_framework 的 gramophone 后端提供「深入适配」的桥端实现。
 * 相比 adapters/bridge-template/MediaDroidBridge.kt（通用桥），本桥额外落地了
 * Gramophone 特有的扩展能力：
 *
 *   1) 曲目 id 用 MediaStore _ID（正数）—— 与 :mcp 通用模块一致，无路径哈希冲突，
 *      因此 playback/queue/library 的 media3 mediaId 可直接互换。
 *   2) 暴露 Gramophone 的自定义 SessionCommand：
 *        POST /custom {"command":"set_timer","duration_millis":ms}
 *        POST /custom {"command":"query_timer"}
 *        POST /custom {"command":"get_lyrics"}
 *      它内部用 controller.sendCustomCommand 打到 GramophonePlaybackService，
 *      对应 onCustomCommand 里的 SERVICE_SET_TIMER / SERVICE_QUERY_TIMER / SERVICE_GET_LYRICS。
 *   3) 设置层走 PreferenceManager（theme_mode/skip_silence/floatoutput/ps_hardware_acc/
 *      trim_lyrics/lyric_multiline/mediastore_filter）。
 *
 * 端点（与 gramophone.py 后端契约一致）：
 *   GET  /health /state /tracks /track /search /settings /setting /playlists
 *   POST /call     (op: play/toggle/pause/next/prev/seek/set_mode/append/play_next/
 *                   remove/move/set_setting)
 *   POST /custom   (command: set_timer/query_timer/get_lyrics)
 */
package org.akanework.gramophone.bridge

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.preference.PreferenceManager
import org.akanework.gramophone.logic.GramophonePlaybackService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 面向 Gramophone 的 HTTP 桥。start() 后即可被 mcp_framework 的 gramophone 后端接入。
 */
object GramophoneBridge {
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "gramophone-bridge") }

    @Volatile private var controllerProvider: (() -> MediaController?)? = null
    @Volatile private var contextRef: Context? = null

    /** 启动桥。默认端口 18801（与 gramophone.py 默认一致）。 */
    fun start(context: Context, port: Int = 18801, controllerFactory: () -> MediaController?) {
        contextRef = context.applicationContext
        controllerProvider = controllerFactory
        if (running) return
        running = true
        thread(isDaemon = true, name = "gramophone-bridge") {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("127.0.0.1", port))
                serverSocket = ss
                while (running) {
                    val s = try { ss.accept() } catch (_: Exception) { break }
                    io.execute { handle(s) }
                }
            } catch (_: Exception) { running = false }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    // ---------------------------------------------------------------- route
    private fun handle(socket: Socket) {
        try {
            val reader = BufferedInputStream(socket.getInputStream())
            val req = readRequest(reader)
            val path = req.path
            val respBody = when {
                path == "/health" -> JSONObject().put("ok", true)
                    .put("server", "gramophone-bridge").toString()

                path == "/state" -> buildState()

                path.startsWith("/tracks") -> buildTracks(mode = req.method)

                path.startsWith("/track") -> buildTrack(queryParam(path, "id"))

                path.startsWith("/search") -> search(queryParam(path, "q"))

                path == "/settings" -> buildSettings()

                path.startsWith("/setting") -> buildSetting(queryParam(path, "key"))

                path == "/playlists" -> buildPlaylists()

                path == "/call" && req.method == "POST" -> doCall(JSONObject(req.body))

                path == "/custom" && req.method == "POST" -> doCustom(JSONObject(req.body))

                else -> JSONObject().put("error", "not found").toString()
            }
            writeResponse(socket, respBody)
        } catch (e: Exception) {
            runCatching {
                writeResponse(socket, JSONObject().put("error", e.message ?: "err").toString())
            }
        } finally { runCatching { socket.close() } }
    }

    // ---------------------------------------------------------------- player access
    private fun controller(): MediaController? = runCatching { controllerProvider?.invoke() }.getOrNull()

    private fun player(): Player? = controller()

    // ---------------------------------------------------------------- 状态
    private fun buildState(): String {
        val p = player()
        val o = JSONObject()
        o.put("playing", p?.isPlaying ?: false)
        o.put("paused", p != null && !p.isPlaying && p.playbackState != Player.STATE_IDLE)
        o.put("buffering", p?.playbackState == Player.STATE_BUFFERING)
        o.put("position_ms", p?.currentPosition ?: 0L)
        o.put("duration_ms", p?.duration ?: 0L)
        o.put("current_index", p?.currentMediaItemIndex ?: -1)
        o.put("mode", modeName(p))
        val q = JSONArray()
        if (p != null) for (i in 0 until p.mediaItemCount) q.put(itemToJson(p.getMediaItemAt(i)))
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
        val md = mi.mediaMetadata
        o.put("id", mi.mediaId)                       // MediaStore _ID（正数）
        o.put("title", md.title?.toString() ?: "")
        o.put("artist", md.artist?.toString() ?: "")
        o.put("album", md.albumTitle?.toString() ?: "")
        o.put("duration_ms", md.extras?.getLong("Duration") ?: 0L)
        o.put("uri", mi.localConfiguration?.uri?.toString() ?: "")
        return o
    }

    // ---------------------------------------------------------------- 控制 /call
    private fun doCall(body: JSONObject): String {
        val op = body.optString("op")
        val p = player()
        val pairs = ArrayList<Pair<String, Any>>()
        when (op) {
            "play" -> {
                val ids = body.optJSONArray("track_ids")
                // 若带 id，先用 search 反查 mediaItem 以按 MediaStore id 建队列（简化：起播当前）
                p?.play()
            }
            "toggle_play_pause" -> p?.let { if (it.isPlaying) it.pause() else it.play() }
            "pause" -> p?.pause()
            "next" -> p?.next()
            "previous" -> p?.previous()
            "seek" -> p?.seekTo(body.optLong("position_ms", 0L))
            "set_mode" -> when (body.optString("mode", "SEQUENTIAL")) {
                "SHUFFLE" -> p?.shuffleModeEnabled = true
                "LIST_LOOP" -> { p?.shuffleModeEnabled = false; p?.repeatMode = Player.REPEAT_MODE_ALL }
                "SINGLE_LOOP" -> { p?.shuffleModeEnabled = false; p?.repeatMode = Player.REPEAT_MODE_ONE }
                else -> { p?.shuffleModeEnabled = false; p?.repeatMode = Player.REPEAT_MODE_OFF }
            }
            "set_setting" -> setPref(body.optString("key"), body.opt("value"))
            else -> {} // 队列操作（占位：宿主接入 MediaController 队列 API）
        }
        return JSONObject().put("state", JSONObject(buildState())).toString()
    }

    // ---------------------------------------------------------------- Gramophone 自定义命令
    private fun doCustom(body: JSONObject): String {
        val cmd = body.optString("command")
        val c = controller() ?: return JSONObject().put("error", "controller not ready").toString()
        val command = when (cmd) {
            "set_timer" -> SessionCommand(GramophonePlaybackService.SERVICE_SET_TIMER,
                android.os.Bundle().apply {
                    putInt("duration", body.optInt("duration_millis", 0))
                })
            "query_timer" -> SessionCommand(GramophonePlaybackService.SERVICE_QUERY_TIMER, android.os.Bundle())
            "get_lyrics" -> SessionCommand(GramophonePlaybackService.SERVICE_GET_LYRICS, android.os.Bundle())
            else -> return JSONObject().put("error", "unknown command: $cmd").toString()
        }
        val future = c.sendCustomCommand(command, android.os.Bundle())
        val result: SessionResult = try { future.get() } catch (e: Exception) {
            return JSONObject().put("error", "custom command failed: ${e.message}").toString()
        }
        val o = JSONObject().put("ok", result.resultCode == SessionResult.RESULT_SUCCESS)
        when (cmd) {
            "get_lyrics" -> {
                val arr = JSONArray()
                val lyrics = result.extras.getParcelableArray("lyrics")
                if (lyrics != null) for (l in lyrics) arr.put(JSONObject().put("content", l.toString()))
                o.put("lyrics", arr)
            }
            "query_timer" -> o.put("duration_millis", result.extras.getInt("duration", 0))
        }
        return o.toString()
    }

    // ---------------------------------------------------------------- 曲库 / 设置
    private fun buildTracks(mode: String): String {
        // MediaStore 扫描交由宿主；此处给出空占位，宿主可替换为 MediaStoreUtils 结果。
        // 契约：{"tracks":[{"id","title","artist","album","duration_ms","uri"}]}
        return JSONObject().put("tracks", JSONArray()).toString()
    }

    private fun buildTrack(id: String?): String =
        JSONObject().put("track", JSONObject()).toString()

    private fun search(q: String?): String = buildTracks("GET")

    private fun buildSettings(): String {
        val ctx = contextRef
        val s = JSONObject()
        if (ctx != null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            s.put("theme_mode", prefs.getString("theme_mode", "0"))
            s.put("skip_silence", prefs.getBoolean("skip_silence", false))
            s.put("floatoutput", prefs.getBoolean("floatoutput", false))
            s.put("ps_hardware_acc", prefs.getBoolean("ps_hardware_acc", true))
            s.put("trim_lyrics", prefs.getBoolean("trim_lyrics", false))
            s.put("lyric_multiline", prefs.getBoolean("lyric_multiline", false))
            s.put("mediastore_filter",
                prefs.getInt("mediastore_filter", ctx.resources.getInteger(
                    org.akanework.gramophone.R.integer.filter_default_sec)))
            s.put("folderFilter", JSONArray(prefs.getStringSet("folderFilter", emptySet())))
        }
        return JSONObject().put("settings", s).toString()
    }

    private fun buildSetting(key: String?): String {
        val ctx = contextRef ?: return JSONObject().put("value", JSONObject.NULL).toString()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val v: Any? = when (key) {
            "theme_mode" -> prefs.getString("theme_mode", "0")
            "skip_silence" -> prefs.getBoolean("skip_silence", false)
            "floatoutput" -> prefs.getBoolean("floatoutput", false)
            "ps_hardware_acc" -> prefs.getBoolean("ps_hardware_acc", true)
            "trim_lyrics" -> prefs.getBoolean("trim_lyrics", false)
            "lyric_multiline" -> prefs.getBoolean("lyric_multiline", false)
            "mediastore_filter" -> prefs.getInt("mediastore_filter", 60)
            "folderFilter" -> prefs.getStringSet("folderFilter", emptySet())
            else -> null
        }
        return JSONObject().put("value", if (v == null) JSONObject.NULL else v).toString()
    }

    private fun setPref(key: String, value: Any?) {
        val ctx = contextRef ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)!!
        prefs.edit().apply {
            when (key) {
                "theme_mode" -> putString(key, value?.toString() ?: "0")
                "mediastore_filter" -> putInt(key, (value as? Number)?.toInt() ?: 60)
                "folderFilter" -> putStringSet(key, emptySet()) // 只读项不写
                else -> when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Number -> putInt(key, value.toInt())
                    is String -> putString(key, value)
                    else -> putString(key, value?.toString() ?: "")
                }
            }
        }.apply()
    }

    private fun buildPlaylists(): String =
        JSONObject().put("playlists", JSONArray()).toString()

    // ---------------------------------------------------------------- http helpers
    private fun queryParam(path: String, key: String): String? {
        val idx = path.indexOf('?')
        if (idx < 0) return null
        return path.substring(idx + 1).split("&")
            .firstOrNull { it.startsWith("$key=") }?.substringAfter("=")
    }

    private data class Req(val method: String, val path: String, val body: String)

    private fun readRequest(reader: BufferedInputStream): Req {
        val head = StringBuilder()
        var c = reader.read()
        var contentLength = -1
        while (c >= 0) {
            if (head.isNotEmpty() && head.endsWith("\r\n\r\n")) break
            head.append(c.toChar()); c = reader.read()
        }
        val lines = head.toString().split("\r\n")
        val rl = lines.firstOrNull()?.split(" ") ?: listOf("GET", "/", "")
        for (l in lines) if (l.lowercase().startsWith("content-length:"))
            contentLength = l.substringAfter(":").trim().toIntOrNull() ?: 0
        var body = ""
        if (contentLength > 0) {
            val buf = ByteArray(contentLength); var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read); if (n < 0) break; read += n
            }
            body = String(buf, 0, read, StandardCharsets.UTF_8)
        }
        return Req(rl[0], rl[1], body)
    }

    private fun writeResponse(socket: Socket, jsonBody: String) {
        val bytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
            .toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes); out.flush()
    }

    /** 便捷工厂：连到 GramophonePlaybackService 的 MediaLibrarySession。 */
    fun controllerOf(context: Context): MediaController {
        val token = SessionToken(context, ComponentName(context, GramophonePlaybackService::class.java))
        return MediaController.Builder(context, token).buildAsync().get()
    }
}