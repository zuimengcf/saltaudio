package im.cue.saltaudio.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用链式命令协议（下放自盐音 app 层 McpBridge 的 commands 组合命令范式）。
 *
 * 让任意 tool 调用可以附带一段"后续命令队列"——主工具执行完后，按顺序依次放行
 * 队列里的每个命令。原实现绑定 McpAiBoard/task_id，这里剥成纯通用：
 *  - 不要求 task_id（没有消息板，链只是主工具上下文里的后续步骤）
 *  - 不落库、不记历史；每个命令是否执行、执行结果由调用方决定
 *
 * 请求格式（tool schema 自动注入 commands 字段）：
 *   "commands": [
 *     {"tool": "playback_pause", "arguments": {}},
 *     {"tool": "queue_append_tracks", "arguments": {"track_ids": [1,2]}}
 *   ]
 */
object McpCommandChain {

    const val MAX_CHAIN_LENGTH = 20
    private const val FIELD = "commands"

    /** 解析 nested commands 字段为有序 [(tool, arguments)]；非法条目静默跳过。 */
    fun parse(raw: Any?): List<Pair<String, JSONObject>> {
        val array = when (raw) {
            is String -> runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            is JSONArray -> raw
            is JSONObject -> raw.optJSONArray("steps") ?: JSONArray()
            else -> JSONArray()
        }
        return buildList {
            for (i in 0 until array.length().coerceAtMost(MAX_CHAIN_LENGTH)) {
                val item = array.optJSONObject(i) ?: continue
                val tool = item.optString("tool").takeIf(String::isNotBlank) ?: continue
                val args = item.optJSONObject("arguments") ?: JSONObject()
                add(tool to JSONObject(args.toString()).apply { remove("task_id"); remove("commands") })
            }
        }
    }

    /** 从参数里摘出 commands 并返回清洗后的纯参数（剔除 task_id/commands）。 */
    fun extract(args: JSONObject): Pair<List<Pair<String, JSONObject>>, JSONObject> {
        val chain = parse(args.opt(FIELD))
        val clean = JSONObject(args.toString()).apply { remove("task_id"); remove(FIELD) }
        return chain to clean
    }

    /** 把 commands 字段注入 tool schema（供 tools/list 暴露给客户端）。 */
    fun injectSchema(inputSchema: JSONObject, allow: (String) -> Boolean = { true }): JSONObject {
        val schema = JSONObject(inputSchema.toString())
        val props = schema.optJSONObject("properties") ?: JSONObject().also { schema.put("properties", it) }
        if (!props.has(FIELD)) {
            props.put(
                FIELD,
                JSONObject()
                    .put("type", "array")
                    .put("description", "可选的后续命令队列：主工具执行完后按顺序放行的独立 {tool, arguments} 列表。仅接受受支持的工具名。")
                    .put("items", JSONObject().put("type", "object")
                        .put("properties", JSONObject()
                            .put("tool", JSONObject().put("type", "string"))
                            .put("arguments", JSONObject().put("type", "object")))
                        .put("required", JSONArray(listOf("tool", "arguments")))
                        .put("additionalProperties", false))
                    .put("maxItems", MAX_CHAIN_LENGTH)
            )
        }
        return schema
    }
}