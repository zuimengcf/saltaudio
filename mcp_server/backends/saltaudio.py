"""SaltAudio（盐音）后端。

通过 SaltAudio 应用内的 McpBridgeProvider 暴露的本机 HTTP 端口（默认 127.0.0.1:18736）
读写真实播放器状态。本文件属于「独立存在、适配所有播放器」的 MCP 框架，
只做普通 HTTP 客户端，不依赖任何 SaltAudio(Android) 代码。

桥端点：
  GET  /health            健康检查
  GET  /state             播放状态
  GET  /tracks?query=&limit=     曲库列表（可按关键词过滤）
  GET  /track?id=         单曲信息
  GET  /search?q=         搜索
  GET  /settings          全部设置
  GET  /setting?key=      单项设置
  POST /call              {"op": "...", ...} 控制指令
"""
from __future__ import annotations
import json
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional

from .base import Backend, Track, PlaybackState, PlaybackMode, ConnectionError_, BackendError


class SaltAudioBackend(Backend):
    name = "saltaudio"
    label = "SaltAudio 盐音 (via local bridge)"

    settings_schema = [
        {"key": "playback_mode", "label": "播放模式", "type": "string"},
        {"key": "overlay_lyrics_enabled", "label": "桌面歌词开关", "type": "boolean"},
        {"key": "lyrics", "label": "歌词外观（只读）", "type": "object"},
        {"key": "excluded_folders", "label": "过滤文件夹（只读）", "type": "array"},
        {"key": "exclude_folder", "label": "过滤一个文件夹", "type": "string"},
        {"key": "include_folder", "label": "取消过滤一个文件夹", "type": "string"},
    ]

    def __init__(self, host: str = "127.0.0.1", port: int = 18736, timeout: float = 5.0):
        self.base = f"http://{host}:{port}"
        self.timeout = timeout
        self._mode_cache = PlaybackMode.SEQUENTIAL

    # ------------------------------------------------------------------
    # HTTP helpers
    # ------------------------------------------------------------------
    def _get(self, path: str) -> Dict[str, Any]:
        url = self.base + path
        try:
            with urllib.request.urlopen(url, timeout=self.timeout) as resp:
                data = resp.read().decode("utf-8")
        except Exception as e:  # noqa: BLE001
            raise ConnectionError_(f"桥 {self.base} 不可达: {e}") from e
        return _as_dict(data)

    def _post(self, path: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        url = self.base + path
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(url, data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                data = resp.read().decode("utf-8")
        except Exception as e:  # noqa: BLE001
            raise ConnectionError_(f"桥 {self.base} 不可达: {e}") from e
        return _as_dict(data)

    # ------------------------------------------------------------------
    # 播放层
    # ------------------------------------------------------------------
    def get_state(self) -> PlaybackState:
        d = self._get("/state")
        mode = d.get("mode") or PlaybackMode.SEQUENTIAL
        self._mode_cache = mode
        return PlaybackState(
            playing=bool(d.get("playing")),
            paused=bool(d.get("paused")),
            buffering=bool(d.get("buffering")),
            position_ms=int(d.get("position_ms") or 0),
            duration_ms=int(d.get("duration_ms") or 0),
            current_index=int(d.get("current_index") or -1),
            mode=mode,
            queue=self._queue_from_state(d),
        )

    def _queue_from_state(self, d: Dict[str, Any]) -> List[Track]:
        # 桥 /state 返回完整 queue 数组（含 id/title/artist/duration），可对齐完整队列。
        raw = d.get("queue")
        if isinstance(raw, list):
            return [_track_from_dict(t) for t in raw if isinstance(t, dict)]
        # 兼容旧桥：退化为当前曲目单元素。
        current = d.get("current_track")
        if isinstance(current, dict):
            return [_track_from_dict(current)]
        return []

    def _parse_state(self, obj: Dict[str, Any]) -> PlaybackState:
        st = obj.get("state")
        if isinstance(st, dict):
            return self._state_from_dict(st)
        return self.get_state()

    def _state_from_dict(self, st: Dict[str, Any]) -> PlaybackState:
        mode = st.get("mode") or self._mode_cache
        self._mode_cache = mode
        queue = st.get("queue")
        queue_tracks = []
        if isinstance(queue, list):
            queue_tracks = [_track_from_dict(t) for t in queue if isinstance(t, dict)]
        return PlaybackState(
            playing=bool(st.get("playing")),
            paused=bool(st.get("paused")),
            buffering=bool(st.get("buffering")),
            position_ms=int(st.get("position_ms") or 0),
            duration_ms=int(st.get("duration_ms") or 0),
            current_index=int(st.get("current_index") or -1),
            mode=mode,
            queue=queue_tracks,
        )

    def play(self, track_ids=None, start_index=0):
        if track_ids:
            payload = {"op": "play", "track_ids": list(track_ids), "start_index": int(start_index)}
        else:
            payload = {"op": "play"}
        return self._parse_state(self._post("/call", payload))

    def toggle_play_pause(self):
        return self._parse_state(self._post("/call", {"op": "toggle_play_pause"}))

    def pause(self):
        return self._parse_state(self._post("/call", {"op": "pause"}))

    def next(self):
        return self._parse_state(self._post("/call", {"op": "next"}))

    def previous(self):
        return self._parse_state(self._post("/call", {"op": "previous"}))

    def seek(self, position_ms):
        return self._parse_state(self._post("/call", {"op": "seek", "position_ms": int(position_ms)}))

    def set_mode(self, mode):
        mode = str(mode).upper()
        if mode not in PlaybackMode.ALL:
            raise BackendError(f"非法播放模式: {mode}")
        self._mode_cache = mode
        return self._parse_state(self._post("/call", {"op": "set_mode", "mode": mode}))

    def get_queue(self):
        st = self.get_state()
        return st.queue

    def append_to_queue(self, track_ids):
        return self._parse_state(self._post("/call", {"op": "append_to_queue", "track_ids": list(track_ids)}))

    def play_next(self, track_ids):
        return self._parse_state(self._post("/call", {"op": "play_next", "track_ids": list(track_ids)}))

    def remove_from_queue(self, index):
        return self._parse_state(self._post("/call", {"op": "remove_from_queue", "index": int(index)}))

    def move_queue_item(self, from_index, to_index):
        return self._parse_state(self._post("/call", {"op": "move_queue_item",
                                                      "from_index": int(from_index),
                                                      "to_index": int(to_index)}))

    # ------------------------------------------------------------------
    # 曲库层
    # ------------------------------------------------------------------
    def _fetch_tracks(self, query=None, limit=200, offset=0):
        params = {}
        if query:
            params["query"] = query
        if limit is not None:
            params["limit"] = str(limit)
        if offset:
            params["offset"] = str(offset)
        path = "/tracks" + ("?" + urllib.parse.urlencode(params) if params else "")
        d = self._get(path)
        items = d.get("tracks") or []
        return [_track_from_dict(t) for t in items if isinstance(t, dict)]

    def get_tracks(self, query=None, limit=200, offset=0):
        return self._fetch_tracks(query, limit, offset)

    def get_track(self, track_id):
        d = self._get("/track?id=" + urllib.parse.quote(track_id))
        t = d.get("track")
        return _track_from_dict(t) if isinstance(t, dict) else None

    def search(self, query):
        d = self._get("/search?q=" + urllib.parse.quote(query))
        items = d.get("tracks") or []
        return [_track_from_dict(t) for t in items if isinstance(t, dict)]

    def get_album(self, album):
        return [t for t in self._fetch_tracks(limit=2000) if t.album == album]

    def get_artist(self, artist):
        return [t for t in self._fetch_tracks(limit=2000) if t.artist == artist]

    def get_playlists(self):
        # 桥暂不暴露播放清单端点；返回空列表让工具层标记 supported=false
        return []

    def get_stats(self):
        tracks = self._fetch_tracks(limit=2000)
        artists = {t.artist for t in tracks}
        albums = {t.album for t in tracks}
        total = sum(t.duration_ms for t in tracks)
        return {"track_count": len(tracks), "artist_count": len(artists),
                "album_count": len(albums), "total_duration_ms": total}

    # ------------------------------------------------------------------
    # 设置层
    # ------------------------------------------------------------------
    def get_setting(self, key):
        d = self._get("/setting?key=" + urllib.parse.quote(key))
        if d.get("error"):
            raise BackendError(f"未知设置项: {key}")
        return d.get("value")

    def set_setting(self, key, value):
        # 播放模式：走专用播放层管道
        if key == "playback_mode":
            return self.set_mode(str(value).upper()).mode
        # 其余设置项统一复用桥的 /call set_setting 管道（仅通用项，不涉及 SaltAudio 定制数据）
        supported = {"overlay_lyrics_enabled", "exclude_folder", "include_folder"}
        if key not in supported:
            raise BackendError(f"未知设置项: {key}")
        d = self._post("/call", {"op": "set_setting", "key": key, "value": value})
        if d.get("error"):
            raise BackendError(f"设置项写入失败: {key}: {d.get('error')}")
        if key == "overlay_lyrics_enabled":
            return d.get("value")
        return d.get("value")

    def get_all(self):
        d = self._get("/settings")
        s = d.get("settings") or {}
        return s


# ----------------------------------------------------------------------
def _as_dict(raw: str) -> Dict[str, Any]:
    try:
        obj = json.loads(raw)
    except Exception as e:  # noqa: BLE001
        raise BackendError(f"桥返回了非法 JSON: {raw[:200]}") from e
    if isinstance(obj, dict):
        return obj
    raise BackendError(f"桥返回了非对象: {raw[:200]}")


def _track_from_dict(t: Dict[str, Any]) -> Track:
    return Track(
        id=str(t.get("id") or ""),
        title=str(t.get("title") or ""),
        artist=str(t.get("artist") or ""),
        album=str(t.get("album") or ""),
        duration_ms=int(t.get("duration_ms") or 0),
        uri=str(t.get("uri") or ""),
        extra={k: t[k] for k in ("origin", "artwork_url") if t.get(k) is not None},
    )