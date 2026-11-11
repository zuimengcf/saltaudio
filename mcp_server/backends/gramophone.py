"""Gramophone (MusicPlayer6) 后端。

目标：`jackball24/MusicPlayer6` —— 开源、简单的 media3 Android 播放器。
接入方式：通过宿主内嵌的标准 HTTP 桥（见 adapters/bridge-template/GramophoneBridge.kt）
暴露 media3 播放能力，本后端在 Python 侧做「深入适配」——不写死任何通用假设，
而是把 Gramophone 自身特有的东西精确映射进来：

  ① 设置键（SharedPreferences / PreferenceManager，与 GenericAndroid 的通用键不同）：
       theme_mode        "0"=跟随系统 "1"=深色 "2"=浅色 (string)
       skip_silence      跳过静音 (boolean)
       floatoutput       音频浮点输出 (boolean)
       ps_hardware_acc   硬件加速播放速度 (boolean)
       trim_lyrics       歌词裁剪 (boolean)
       lyric_multiline   歌词多行 (boolean)
       mediastore_filter 短曲过滤阈值(秒) (int)
       folderFilter      过滤文件夹 (string set)

  ② 自定义 SessionCommand（Gramophone 特有的扩展能力）：
       set_timer        定时停止播放 {"duration_millis": ms}  duration<=0 则取消
       query_timer      查询剩余定时 {duration_millis}
       get_lyrics       获取当前歌词 {"lyrics":[...]}
       shuffle_on/off   （映射到 playback_set_mode / set_mode）
       repeat_off/all/one （映射到 set_mode）

  ③ 曲目 id：MediaStore `_ID`（正数）。与 :mcp 通用模块一致，无 Sona 式路径哈希冲突，
     因此 playback/queue/library 的 id 可直接互操作，不需要 id 映射层。

与 saltaudio/generic_android 的区别：本后端内置了 Gramophone 的设置键白名单和
自定义命令到 playback* 的映射，让 mcp_framework 的工具能识别并操作 Gramophone 特有项
（而不只是通用播放状态）。
"""
from __future__ import annotations
import json
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional

from .base import Backend, Track, PlaybackState, PlaybackMode, ConnectionError_, BackendError


class GramophoneBackend(Backend):
    name = "gramophone"
    label = "Gramophone / MusicPlayer6 (media3 via HTTP bridge)"

    #: Gramophone 特有设置键白名单（key -> (read_only, type)）
    SETTING_KEYS: Dict[str, Dict[str, Any]] = {
        "theme_mode":        {"ro": False, "type": "string"},
        "skip_silence":      {"ro": False, "type": "boolean"},
        "floatoutput":       {"ro": False, "type": "boolean"},
        "ps_hardware_acc":   {"ro": False, "type": "boolean"},
        "trim_lyrics":       {"ro": False, "type": "boolean"},
        "lyric_multiline":   {"ro": False, "type": "boolean"},
        "mediastore_filter": {"ro": False, "type": "number"},
        "folderFilter":      {"ro": True,  "type": "array"},
    }

    settings_schema = [
        {"key": k, "label": k, "type": v["type"], "readOnly": v["ro"]}
        for k, v in SETTING_KEYS.items()
    ]

    def __init__(self, host: str = "127.0.0.1", port: int = 18801, timeout: float = 8.0):
        self.base = f"http://{host}:{port}"
        self.timeout = timeout
        self._mode_cache = PlaybackMode.SEQUENTIAL
        self._tool_names: List[str] = []

    # ------------------------------------------------------------------
    # HTTP bridge helpers (通用媒体桥契约，见 adapters/README.md)
    # ------------------------------------------------------------------
    def _health(self) -> bool:
        try:
            with urllib.request.urlopen(self.base + "/health", timeout=self.timeout) as resp:
                return bool(json.loads(resp.read().decode("utf-8")).get("ok"))
        except Exception as e:  # noqa: BLE001
            raise ConnectionError_(f"桥 {self.base} 不可达: {e}") from e

    def _get(self, path: str) -> Dict[str, Any]:
        try:
            with urllib.request.urlopen(self.base + path, timeout=self.timeout) as resp:
                data = resp.read().decode("utf-8")
        except Exception as e:  # noqa: BLE001
            raise ConnectionError_(f"桥 {self.base} 不可达: {e}") from e
        return _as_dict(data)

    def _post(self, path: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                data = resp.read().decode("utf-8")
        except Exception as e:  # noqa: BLE001
            raise ConnectionError_(f"桥 {self.base} 不可达: {e}") from e
        return _as_dict(data)

    def _parse_state(self, obj: Dict[str, Any]) -> PlaybackState:
        st = obj.get("state")
        if isinstance(st, dict):
            return self._state_from_dict(st)
        return self.get_state()

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
            mode=mode, queue=queue_tracks)

    # ------------------------------------------------------------------
    # 播放层
    # ------------------------------------------------------------------
    def get_state(self) -> PlaybackState:
        return self._state_from_dict(self._get("/state"))

    def _call_control(self, payload: Dict[str, Any]) -> PlaybackState:
        return self._parse_state(self._post("/call", payload))

    def play(self, track_ids: Optional[List[str]] = None, start_index: int = 0):
        if track_ids:
            return self._call_control({"op": "play", "track_ids": list(track_ids),
                                       "start_index": int(start_index)})
        return self._call_control({"op": "play"})

    def toggle_play_pause(self):
        return self._call_control({"op": "toggle_play_pause"})

    def pause(self):
        return self._call_control({"op": "pause"})

    def next(self):
        return self._call_control({"op": "next"})

    def previous(self):
        return self._call_control({"op": "previous"})

    def seek(self, position_ms):
        return self._call_control({"op": "seek", "position_ms": int(position_ms)})

    def set_mode(self, mode):
        mode = str(mode).upper()
        if mode not in PlaybackMode.ALL:
            raise BackendError(f"非法播放模式: {mode}")
        self._mode_cache = mode
        return self._call_control({"op": "set_mode", "mode": mode})

    def get_queue(self):
        return self.get_state().queue

    def append_to_queue(self, track_ids):
        return self._call_control({"op": "append_to_queue", "track_ids": list(track_ids)})

    def play_next(self, track_ids):
        return self._call_control({"op": "play_next", "track_ids": list(track_ids)})

    def remove_from_queue(self, index):
        return self._call_control({"op": "remove_from_queue", "index": int(index)})

    def move_queue_item(self, from_index, to_index):
        return self._call_control({"op": "move_queue_item",
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
        # Gramophone 通过 MediaStore 播放清单；桥若未暴露则优雅降级
        d = self._get("/playlists")
        items = d.get("playlists") if isinstance(d, dict) else None
        if isinstance(items, list):
            return items
        return []

    def get_stats(self):
        tracks = self._fetch_tracks(limit=2000)
        artists = {t.artist for t in tracks}
        albums = {t.album for t in tracks}
        total = sum(t.duration_ms for t in tracks)
        return {"track_count": len(tracks), "artist_count": len(artists),
                "album_count": len(albums), "total_duration_ms": total}

    # ------------------------------------------------------------------
    # 设置层（Gramophone 特有键白名单）
    # ------------------------------------------------------------------
    def get_setting(self, key):
        if key not in self.SETTING_KEYS:
            # 允许退化为通用桥的 /setting?key=
            d = self._get("/setting?key=" + urllib.parse.quote(key))
            if d.get("error"):
                raise BackendError(f"未知设置项: {key}")
            return d.get("value")
        d = self._get("/setting?key=" + urllib.parse.quote(key))
        if d.get("error"):
            raise BackendError(f"未知设置项: {key}")
        return d.get("value")

    def set_setting(self, key, value):
        if key == "playback_mode":
            return self.set_mode(str(value).upper()).mode
        meta = self.SETTING_KEYS.get(key)
        if meta is None:
            raise BackendError(f"未知设置项: {key}")
        if meta["ro"]:
            raise BackendError(f"设置项只读: {key}")
        # 类型规整，让桥侧能直接写 SafeDataStore
        if meta["type"] == "boolean":
            coerced = bool(value)
        elif meta["type"] == "number":
            coerced = float(value)
        else:
            coerced = str(value)
        d = self._post("/call", {"op": "set_setting", "key": key, "value": coerced})
        if d.get("error"):
            raise BackendError(f"设置项写入失败: {key}: {d.get('error')}")
        # 桥的 /call 返回 state 而非 value，写后读回实际值以保证语义正确
        try:
            return self.get_setting(key)
        except BackendError:
            return d.get("value")

    def get_all(self):
        d = self._get("/settings")
        return d.get("settings") or {}

    # ------------------------------------------------------------------
    # Gramophone 自定义扩展工具（对 mcp_framework 暴露额外能力）
    # ------------------------------------------------------------------
    def set_timer(self, duration_millis: int) -> Dict[str, Any]:
        """设置定时停止播放。duration_millis<=0 表示取消。"""
        d = self._post("/custom", {"command": "set_timer", "duration_millis": int(duration_millis)})
        if d.get("error"):
            raise BackendError(f"set_timer 失败: {d.get('error')}")
        return d

    def query_timer(self) -> Dict[str, Any]:
        """查询剩余定时时间。"""
        d = self._post("/custom", {"command": "query_timer"})
        return d

    def get_lyrics(self) -> List[Dict[str, Any]]:
        """获取当前曲目的歌词（Gramophone 经 MediaStore/LRC 解析后广播）。"""
        d = self._post("/custom", {"command": "get_lyrics"})
        lyr = d.get("lyrics")
        if isinstance(lyr, list):
            return lyr
        if isinstance(lyr, dict):
            return [lyr]
        return []


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