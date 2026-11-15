package im.cue.saltaudio.mcp

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * 通用游标分页（下放自盐音 app 层 McpBridge 的 pageRange/pageJson/cursor 范式）。
 *
 * 统一契约（任何 tool 分页都用同一套语义）：
 *   - 请求：limit（1–200，默认 50）；offset（0 起）与 cursor 二选一，同时传视为非法参数
 *   - 响应：count（总数）/ offset / limit / has_more / next_offset / next_cursor
 *   - cursor 格式：Base64URL( "v1:<offset>" )；解码失败/前缀不符/超界一律拒绝
 */
object McpPage {

    const val DEFAULT_PAGE_SIZE = 50
    const val MAX_PAGE_SIZE = 200
    private const val MAX_OFFSET = 1_000_000
    private const val CURSOR_PREFIX = "v1:"

    data class Range(
        val count: Int,
        val offset: Int,
        val limit: Int,
        val endExclusive: Int,
        val nextCursor: String?
    ) {
        val hasMore: Boolean get() = nextCursor != null
    }

    /** offset 与 cursor 二选一；同时传或非法参数抛 [IllegalArgumentException]。 */
    fun range(count: Int, limit: Int?, offset: Int?, cursor: String?): Range {
        if (offset != null && cursor != null) throw IllegalArgumentException("offset and cursor cannot both be set")
        val requestedStart = offset ?: cursor?.let(::decodeCursor) ?: 0
        if (requestedStart !in 0..MAX_OFFSET) throw IllegalArgumentException("offset out of range")
        val pageSize = (limit ?: DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)
        val start = requestedStart.coerceAtMost(count)
        val end = (start + pageSize).coerceAtMost(count)
        return Range(count, start, pageSize, end, if (end < count) encodeCursor(end) else null)
    }

    /** 把一批条目切成 JSON 页（条目转 JSON 由 [mapper] 处理）。 */
    fun <T> pageJson(items: List<T>, limit: Int?, offset: Int?, cursor: String?, mapper: (T) -> JSONObject): JSONObject {
        val r = range(items.size, limit, offset, cursor)
        return r.metaJson().put("items", JSONArray().apply {
            items.subList(r.offset, r.endExclusive).forEach { put(mapper(it)) }
        })
    }

    private fun Range.metaJson(): JSONObject = JSONObject()
        .put("count", count)
        .put("offset", offset)
        .put("limit", limit)
        .put("has_more", hasMore)
        .put("next_offset", if (hasMore) endExclusive else JSONObject.NULL)
        .put("next_cursor", nextCursor ?: JSONObject.NULL)

    fun encodeCursor(offset: Int): String = Base64.encodeToString(
        "$CURSOR_PREFIX$offset".toByteArray(StandardCharsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    )

    fun decodeCursor(cursor: String): Int {
        val decoded = runCatching {
            String(Base64.decode(cursor, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), StandardCharsets.UTF_8)
        }.getOrNull() ?: throw IllegalArgumentException("invalid cursor")
        if (!decoded.startsWith(CURSOR_PREFIX)) throw IllegalArgumentException("invalid cursor")
        return decoded.substringAfter(CURSOR_PREFIX).toIntOrNull()?.takeIf { it in 0..MAX_OFFSET }
            ?: throw IllegalArgumentException("invalid cursor")
    }
}