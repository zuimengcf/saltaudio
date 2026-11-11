package im.cue.saltaudio.mcp

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * MCP 操作日志：同时写 logcat（tag=McpControl）与内存环形缓冲（最近 200 条）。
 * 宿主可调用 [dump] 展示，或通过 `get_mcp_log` 工具远程读取。
 */
object McpLog {

    private const val TAG = "McpControl"
    private const val MAX = 200
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(msg: String) {
        val line = fmt.format(Date()) + " " + msg
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > MAX) buf.removeFirst()
        }
        Log.i(TAG, msg)
    }

    fun dump(): List<String> = synchronized(buf) { buf.toList() }
}
