"""曲库层工具（Library Layer）。

职责：只读地检查歌曲信息 / 曲库元数据 / 搜索。所有工具名以 library_ 开头。
"""
from __future__ import annotations
from typing import Any, Dict
from .backends import BackendError, ConnectionError_


TOOLS: list[Dict[str, Any]] = [
    {
        "name": "library_get_tracks",
        "description": "获取曲库中的歌曲列表。可按关键词过滤，limit/offset 控制分页。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "可选，按标题/艺术家/专辑模糊匹配过滤"},
                "limit": {"type": "integer", "description": "每页数量（默认 200）"},
                "offset": {"type": "integer", "description": "从 0 开始的偏移（默认 0）"},
            },
            "required": [],
        },
    },
    {
        "name": "library_get_track",
        "description": "按 id 获取单首歌的详细信息。",
        "inputSchema": {
            "type": "object",
            "properties": {"track_id": {"type": "string", "description": "歌曲 id"}},
            "required": ["track_id"],
        },
    },
    {
        "name": "library_search",
        "description": "在曲库中搜索歌曲（标题/艺术家/专辑模糊匹配）。",
        "inputSchema": {
            "type": "object",
            "properties": {"query": {"type": "string", "description": "搜索关键词"}},
            "required": ["query"],
        },
    },
    {
        "name": "library_get_album",
        "description": "按专辑名字获取该专辑的歌曲列表。",
        "inputSchema": {
            "type": "object",
            "properties": {"album": {"type": "string", "description": "专辑名"}},
            "required": ["album"],
        },
    },
    {
        "name": "library_get_artist",
        "description": "按艺术家名字获取该艺术家的歌曲列表。",
        "inputSchema": {
            "type": "object",
            "properties": {"artist": {"type": "string", "description": "艺术家名"}},
            "required": ["artist"],
        },
    },
    {
        "name": "library_get_playlists",
        "description": "获取播放清单。后端不支持时返回 supported=false（属正常情况，非错误）。",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "library_get_stats",
        "description": "曲库统计：歌曲总数、艺术家数、专辑数、总时长。",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
]


def handle(name: str, backend, args: Dict[str, Any]) -> Dict[str, Any]:
    try:
        if name == "library_get_tracks":
            tracks = backend.get_tracks(args.get("query"), int(args.get("limit", 200)),
                                        int(args.get("offset", 0)))
            return _ok({"count": len(tracks), "offset": int(args.get("offset", 0)),
                        "tracks": [t.to_dict() for t in tracks]})
        if name == "library_get_track":
            t = backend.get_track(args["track_id"])
            return _ok({"track": t.to_dict() if t else None})
        if name == "library_search":
            tracks = backend.search(args["query"])
            return _ok({"count": len(tracks), "tracks": [t.to_dict() for t in tracks]})
        if name == "library_get_album":
            tracks = backend.get_album(args["album"])
            return _ok({"count": len(tracks), "tracks": [t.to_dict() for t in tracks]})
        if name == "library_get_artist":
            tracks = backend.get_artist(args["artist"])
            return _ok({"count": len(tracks), "tracks": [t.to_dict() for t in tracks]})
        if name == "library_get_playlists":
            # 兼容性：后端不支持播放清单属正常情况，返回 supported=false 而非报错
            if not hasattr(type(backend), "get_playlists"):
                return _ok({"supported": False, "playlists": []})
            try:
                playlists = backend.get_playlists()
            except NotImplementedError:
                return _ok({"supported": False, "playlists": []})
            except (BackendError, ConnectionError_) :
                raise
            except Exception:  # noqa: BLE001 — 其他异常也按不支持降级
                return _ok({"supported": False, "playlists": []})
            return _ok({"supported": True, "playlists": playlists})
        if name == "library_get_stats":
            return _ok(backend.get_stats())
    except ConnectionError_ as e:
        raise BackendError(f"无法连接播放器: {e}") from e
    except KeyError as e:
        raise BackendError(f"缺少参数: {e}") from e
    raise BackendError(f"未知曲库层工具: {name}")


def _ok(data: Any) -> Dict[str, Any]:
    from .tools_playback import _to_text
    return {"content": [{"type": "text", "text": _to_text(data)}], "isError": False}