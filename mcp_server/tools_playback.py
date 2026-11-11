"""播放层工具（Playback Layer）。

职责：控制播放。所有工具名以 playback_ 开头，最终委托给所选后端的 PlaybackBackend。
"""
from __future__ import annotations
from typing import Any, Dict, Optional
from .backends import PlaybackMode, PlaybackState


# 每个工具的基本信息（name, description, inputSchema）
TOOLS: list[Dict[str, Any]] = [
    {
        "name": "playback_get_state",
        "description": "获取当前播放状态：是否播放、暂停、缓冲、进度、时长、当前歌曲、队列、播放模式。",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "playback_play",
        "description": "开始播放。可传入 track_ids 指定播放哪些歌（留空则从当前位置恢复播放），start_index 指定从第几首开始。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "track_ids": {"type": "array", "items": {"type": "string"}, "description": "要播放的歌曲 id 列表，不传则恢复当前队列播放"},
                "start_index": {"type": "integer", "description": "从 track_ids 的第几首开始播（0 起）"},
            },
            "required": [],
        },
    },
    {
        "name": "playback_toggle_pause",
        "description": "播放/暂停切换。",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "playback_pause",
        "description": "暂停播放。",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "playback_next",
        "description": "下一首。",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "playback_previous",
        "description": "上一首。",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "playback_seek",
        "description": "跳转到指定毫秒位置。",
        "inputSchema": {
            "type": "object",
            "properties": {"position_ms": {"type": "integer", "description": "目标位置（毫秒）"}},
            "required": ["position_ms"],
        },
    },
    {
        "name": "playback_set_mode",
        "description": "设置播放模式。取值：SEQUENTIAL(顺序) / LIST_LOOP(列表循环) / SINGLE_LOOP(单曲循环) / SHUFFLE(随机)。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "mode": {"type": "string", "enum": list(PlaybackMode.ALL),
                         "description": "播放模式"},
            },
            "required": ["mode"],
        },
    },
    {
        "name": "playback_get_queue",
        "description": "获取当前播放队列（歌曲列表）。",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "playback_append_to_queue",
        "description": "把指定歌曲追加到队尾。",
        "inputSchema": {
            "type": "object",
            "properties": {"track_ids": {"type": "array", "items": {"type": "string"}}},
            "required": ["track_ids"],
        },
    },
    {
        "name": "playback_play_next",
        "description": "把指定歌曲插入到当前歌曲之后播放。",
        "inputSchema": {
            "type": "object",
            "properties": {"track_ids": {"type": "array", "items": {"type": "string"}}},
            "required": ["track_ids"],
        },
    },
    {
        "name": "playback_remove_from_queue",
        "description": "按索引从队列移除歌曲。",
        "inputSchema": {
            "type": "object",
            "properties": {"index": {"type": "integer", "description": "在队列中的索引（0 起）"}},
            "required": ["index"],
        },
    },
    {
        "name": "playback_move_queue_item",
        "description": "移动队列中的歌曲位置。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "from_index": {"type": "integer", "description": "当前位置（0 起）"},
                "to_index": {"type": "integer", "description": "目标位置（0 起）"},
            },
            "required": ["from_index", "to_index"],
        },
    },
]


def handle(name: str, backend, args: Dict[str, Any]) -> Dict[str, Any]:
    """根据工具名分派到后端。异常统一转成 MCP error。"""
    from .backends import ConnectionError_, BackendError

    try:
        if name == "playback_get_state":
            return _ok(backend.get_state().to_dict())
        if name == "playback_play":
            return _ok(backend.play(args.get("track_ids"), args.get("start_index", 0)).to_dict())
        if name == "playback_toggle_pause":
            return _ok(backend.toggle_play_pause().to_dict())
        if name == "playback_pause":
            return _ok(backend.pause().to_dict())
        if name == "playback_next":
            return _ok(backend.next().to_dict())
        if name == "playback_previous":
            return _ok(backend.previous().to_dict())
        if name == "playback_seek":
            return _ok(backend.seek(int(args["position_ms"])).to_dict())
        if name == "playback_set_mode":
            mode = str(args["mode"]).upper()
            if mode not in PlaybackMode.ALL:
                raise BackendError(f"非法播放模式: {mode}，可选 {PlaybackMode.ALL}")
            return _ok(backend.set_mode(mode).to_dict())
        if name == "playback_get_queue":
            return _ok({"tracks": [t.to_dict() for t in backend.get_queue()]})
        if name == "playback_append_to_queue":
            return _ok(backend.append_to_queue(list(args.get("track_ids", []))).to_dict())
        if name == "playback_play_next":
            return _ok(backend.play_next(list(args.get("track_ids", []))).to_dict())
        if name == "playback_remove_from_queue":
            return _ok(backend.remove_from_queue(int(args["index"])).to_dict())
        if name == "playback_move_queue_item":
            return _ok(backend.move_queue_item(int(args["from_index"]), int(args["to_index"])).to_dict())
    except ConnectionError_ as e:
        raise BackendError(f"无法连接播放器: {e}") from e
    except KeyError as e:
        raise BackendError(f"缺少参数: {e}") from e
    raise BackendError(f"未知播放层工具: {name}")


def _ok(data: Any) -> Dict[str, Any]:
    return {"content": [{"type": "text", "text": _to_text(data)}], "isError": False}


def _to_text(data: Any) -> str:
    import json
    if isinstance(data, str):
        return data
    return json.dumps(data, ensure_ascii=False, indent=2)