"""抽象接口层：MCP 服务器与具体播放器解耦的唯一契约。

任何播放器要接入本框架，只需实现这三个接口（PlaybackBackend / LibraryBackend / SettingsBackend），
并以 `-–backend <名字>` 注册即可。服务器代码本身不依赖任何播放器的实现。
"""
from __future__ import annotations
from typing import Any, Dict, List, Optional


# ---------------------------------------------------------------------------
# 通用数据结构（跨播放器统一，避免各自自定义导致适配器零零散散）
# ---------------------------------------------------------------------------

class Track:
    """一首歌的统一表示。后端负责把它映射到播放器自己的数据模型。"""
    __slots__ = ("id", "title", "artist", "album", "duration_ms", "uri", "extra")

    def __init__(self, id: str, title: str, artist: str = "", album: str = "",
                 duration_ms: int = 0, uri: str = "", extra: Optional[Dict[str, Any]] = None):
        self.id = id
        self.title = title
        self.artist = artist
        self.album = album
        self.duration_ms = duration_ms
        self.uri = uri
        self.extra = extra or {}

    def to_dict(self) -> Dict[str, Any]:
        d = {
            "id": self.id,
            "title": self.title,
            "artist": self.artist,
            "album": self.album,
            "duration_ms": self.duration_ms,
            "uri": self.uri,
        }
        if self.extra:
            d["extra"] = self.extra
        return d


class PlaybackState:
    """当前播放状态。"""
    __slots__ = ("playing", "paused", "buffering", "position_ms", "duration_ms",
                 "queue", "current_index", "mode")

    def __init__(self, playing: bool = False, paused: bool = False, buffering: bool = False,
                 position_ms: int = 0, duration_ms: int = 0,
                 queue: Optional[List[Track]] = None, current_index: int = -1,
                 mode: str = "SEQUENTIAL"):
        self.playing = playing
        self.paused = paused
        self.buffering = buffering
        self.position_ms = position_ms
        self.duration_ms = duration_ms
        self.queue = queue or []
        self.current_index = current_index
        self.mode = mode

    def to_dict(self) -> Dict[str, Any]:
        current = None
        if 0 <= self.current_index < len(self.queue):
            current = self.queue[self.current_index].to_dict()
        return {
            "playing": self.playing,
            "paused": self.paused,
            "buffering": self.buffering,
            "position_ms": self.position_ms,
            "duration_ms": self.duration_ms,
            "current_index": self.current_index,
            "mode": self.mode,
            "current_track": current,
            "queue_size": len(self.queue),
        }


class PlaybackMode:
    SEQUENTIAL = "SEQUENTIAL"   # 顺序播放
    LIST_LOOP = "LIST_LOOP"     # 列表循环
    SINGLE_LOOP = "SINGLE_LOOP"  # 单曲循环
    SHUFFLE = "SHUFFLE"         # 随机播放

    ALL = (SEQUENTIAL, LIST_LOOP, SINGLE_LOOP, SHUFFLE)


class ConnectionError_(Exception):
    """后端无法连接到目标播放器。"""


class BackendError(Exception):
    """后端操作失败。"""


# ---------------------------------------------------------------------------
# 播放层 Playback
# ---------------------------------------------------------------------------

class PlaybackBackend:
    """播放层：控制播放 / 队列 / 播放模式。

    对应 SaltAudio 的 `PlaybackController` 抽象（state StateFlow + playQueue /
    appendToQueue / playNext / removeFromQueue / togglePlayPause / seekTo /
    next / previous / setPlaybackMode）。
    """

    def get_state(self) -> PlaybackState:
        raise NotImplementedError

    def play(self, track_ids: Optional[List[str]] = None, start_index: int = 0) -> PlaybackState:
        """开始播放。track_ids 为空时从当前位置恢复播放。"""
        raise NotImplementedError

    def toggle_play_pause(self) -> PlaybackState:
        raise NotImplementedError

    def pause(self) -> PlaybackState:
        raise NotImplementedError

    def next(self) -> PlaybackState:
        raise NotImplementedError

    def previous(self) -> PlaybackState:
        raise NotImplementedError

    def seek(self, position_ms: int) -> PlaybackState:
        raise NotImplementedError

    def set_mode(self, mode: str) -> PlaybackState:
        raise NotImplementedError

    def get_queue(self) -> List[Track]:
        raise NotImplementedError

    def append_to_queue(self, track_ids: List[str]) -> PlaybackState:
        raise NotImplementedError

    def play_next(self, track_ids: List[str]) -> PlaybackState:
        raise NotImplementedError

    def remove_from_queue(self, index: int) -> PlaybackState:
        raise NotImplementedError
    def move_queue_item(self, from_index: int, to_index: int) -> PlaybackState:
        raise NotImplementedError


# ---------------------------------------------------------------------------
# 曲库层 Library
# ---------------------------------------------------------------------------

class LibraryBackend:
    """曲库层：只读地获取歌曲元数据等信息。

    对应 SaltAudio 的 `AudioRepository.tracks StateFlow` 及歌曲信息读取。
    """

    def get_tracks(self, query: Optional[str] = None, limit: int = 200,
                   offset: int = 0) -> List[Track]:
        raise NotImplementedError

    def get_track(self, track_id: str) -> Optional[Track]:
        raise NotImplementedError

    def search(self, query: str) -> List[Track]:
        raise NotImplementedError

    def get_album(self, album: str) -> List[Track]:
        raise NotImplementedError
    def get_artist(self, artist: str) -> List[Track]:
        raise NotImplementedError
    def get_playlists(self) -> List[Dict[str, Any]]:
        """可选能力：播放清单。不支持的后端可不覆写，工具层会优雅降级。"""
        return []
# ---------------------------------------------------------------------------
# 设置层 Settings
# ---------------------------------------------------------------------------

class SettingsBackend:
    """设置层：读写通用播放器设置（播放模式 / 音量 / 过滤文件夹 / 外观等）。"""

    def get_setting(self, key: str) -> Any:
        raise NotImplementedError

    def set_setting(self, key: str, value: Any) -> Any:
        raise NotImplementedError

    def get_all(self) -> Dict[str, Any]:
        raise NotImplementedError


# ---------------------------------------------------------------------------
# 组合后端：一个播放器 = 三个层接口的聚合实现
# ---------------------------------------------------------------------------

class Backend(PlaybackBackend, LibraryBackend, SettingsBackend):
    """具体播放器后端应继承本类并实现上述所有方法。"""
    name = "base"
    label = "Base Backend"