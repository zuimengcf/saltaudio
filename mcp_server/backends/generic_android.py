"""通用 Android Media3 后端。

「通用」是目标：本后端不绑定任何具体 App，只要目标 Android 播放器(media3 栈)通过一个
**标准 HTTP 桥** 暴露它的播放能力，本后端即可用统一的适配方法接入它——这就是「桥是可行的
适配方法」的具体落地。

桥契约（与 saltaudio/MiniPro/Sona 等在验证中保持一致，宿主可在端口/路径上按需配置，后端
侧用 host/port 指定即可）：
  GET  /health            健康检查
  GET  /state             播放状态（含 queue 数组）
  GET  /tracks?query=&limit=&offset=   曲库列表
  GET  /track?id=         单曲信息
  GET  /search?q=         搜索
  GET  /settings          全部设置
  GET  /setting?key=      单项设置
  POST /call              {"op": "...", ...} 控制指令
     ops: play / toggle_play_pause / pause / next / previous / seek /
          set_mode / append_to_queue / play_next / remove_from_queue / move_queue_item /
          set_setting

与 SaltAudio 后端的区别：不写死默认端口，也不内置任何 App 专属设置项，全部通过构造参数
(MCP_BRIDGE_HOST / MCP_BRIDGE_PORT 环境变量或命令行) 指定，从而适配「任意」播放器。
"""
from __future__ import annotations
import json
import os
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional

from .base import Backend, Track, PlaybackState, PlaybackMode, ConnectionError_, BackendError


class GenericAndroidBackend(Backend):
    name = "generic_android"
    label = "Generic Android Media3 (via standard HTTP bridge)"

    settings_schema = [
        {"key": "playback_mode", "label": "播放模式", "type": "string"},
    ]

    def __init__(self, host: Optional[str] = None, port: Optional[int] = None, timeout: float = 5.0):
        # 允许通过环境变量/调用方覆盖，使同一后端可指向多台播放器
        host = host or os.environ.get("MCP_BRIDGE_HOST", "127.0.0.1")
        port = int(port or os.environ.get("MCP_BRIDGE_PORT", "18736"))
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
    def _state_from_dict(self, st: Dict[str, Any]) -> PlaybackState:
        mode = st.get("mode") or self._mode_cache
        self._mode_cache = mode
        queue = st.get("queue")
        queue_tracks: List[Track] = []
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

    def get_state(self) -> PlaybackState:
        return self._state_from_dict(self._get("/state"))

    def _parse_state(self, obj: Dict[str, Any]) -> PlaybackState:
        st = obj.get("state")
        if isinstance(st, dict):
            return self._state_from_dict(st)
        return self.get_state()

    def _call(self, payload: Dict[str, Any]) -> PlaybackState:
        return self._parse_state(self._post("/call", payload))

    def play(self, track_ids: Optional[List[str]] = None, start_index: int = 0):
        if track_ids:
            return self._call({"op": "play", "track_ids": list(track_ids), "start_index": int(start_index)})
        return self._call({"op": "play"})

    def toggle_play_pause(self):
        return self._call({"op": "toggle_play_pause"})

    def pause(self):
        return self._call({"op": "pause"})

    def next(self):
        return self._call({"op": "next"})

    def previous(self):
        return self._call({"op": "previous"})

    def seek(self, position_ms):
        return self._call({"op": "seek", "position_ms": int(position_ms)})

    def set_mode(self, mode):
        mode = str(mode).upper()
        if mode not in PlaybackMode.ALL:
            raise BackendError(f"非法播放模式: {mode}")
        self._mode_cache = mode
        return self._call({"op": "set_mode", "mode": mode})

    def get_queue(self):
        return self.get_state().queue

    def append_to_queue(self, track_ids):
        return self._call({"op": "append_to_queue", "track_ids": list(track_ids)})

    def play_next(self, track_ids):
        return self._call({"op": "play_next", "track_ids": list(track_ids)})

    def remove_from_queue(self, index):
        return self._call({"op": "remove_from_queue", "index": int(index)})

    def move_queue_item(self, from_index, to_index):
        return self._call({"op": "move_queue_item",
                           "from_index": int(from_index), "to_index": int(to_index)})

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
        d = self._get("/track?id=" + urllib.parse.quote(str(track_id)))
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
        # 桥若未暴露播放清单端点，返回空列表让工具层优雅降级 supported=false
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
        if key == "playback_mode":
            return self.set_mode(str(value).upper()).mode
        d = self._post("/call", {"op": "set_setting", "key": key, "value": value})
        if d.get("error"):
            raise BackendError(f"设置项写入失败: {key}: {d.get('error')}")
        return d.get("value")

    def get_all(self):
        d = self._get("/settings")
        return d.get("settings") or {}


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