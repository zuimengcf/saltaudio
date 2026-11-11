"""Demo 后端：纯内存模拟，无需任何真实播放器。

用于验证 MCP 服务器框架的完整流程（initialize / tools/list / tools/call 三层工具）。
真实播放器后端（如 saltaudio）替换掉本文件的数据源即可，接口契约完全一致。
"""
from __future__ import annotations
from typing import Any, Dict, List, Optional
from .base import Backend, Track, PlaybackState, PlaybackMode


class DemoBackend(Backend):
    name = "demo"
    label = "Demo Backend (in-memory, no player needed)"

    settings_schema = [
        {"key": "playback_mode", "label": "播放模式", "type": "string"},
        {"key": "volume", "label": "音量", "type": "number"},
    ]

    def __init__(self):
        self._tracks = [
            Track("t1", "晴天", "周杰伦", "叶惠美", 269000, "file:///music/qingtian.mp3"),
            Track("t2", "稻香", "周杰伦", "魔杰座", 223000, "file:///music/daoxiang.mp3"),
            Track("t3", "枫", "周杰伦", "十一月的萧邦", 269000, "file:///music/feng.mp3"),
            Track("t4", "夜曲", "周杰伦", "十一月的萧邦", 225000, "file:///music/yeau.mp3"),
        ]
        self._state = PlaybackState(queue=self._tracks, mode=PlaybackMode.SEQUENTIAL)
        self._settings: Dict[str, Any] = {
            "playback_mode": "SEQUENTIAL",
            "volume": 70,
        }
        # 让 settings 里的 playback_mode 与播放层联动的意图：真实后端会统一，这里做简单同步
        self._mode = PlaybackMode.SEQUENTIAL

    # ---- 播放层 ----
    def _after(self) -> PlaybackState:
        self._state.mode = self._mode
        return self._state

    def get_state(self) -> PlaybackState:
        return self._after()

    def play(self, track_ids=None, start_index=0):
        if track_ids:
            by_id = {t.id: t for t in self._tracks}
            selected = [by_id[i] for i in track_ids if i in by_id]
            if selected:
                idx = min(start_index, len(selected) - 1)
                self._state = PlaybackState(queue=selected, current_index=idx, playing=True, paused=False, mode=self._mode)
        else:
            self._state.playing = True
            self._state.paused = False
        return self._after()

    def toggle_play_pause(self):
        self._state.playing = not self._state.playing
        self._state.paused = not self._state.playing
        return self._after()

    def pause(self):
        self._state.playing = False
        self._state.paused = True
        return self._after()

    def next(self):
        n = len(self._state.queue)
        if n:
            self._state.current_index = (self._state.current_index + 1) % n
        return self._after()

    def previous(self):
        n = len(self._state.queue)
        if n:
            self._state.current_index = (self._state.current_index - 1) % n
        return self._after()

    def seek(self, position_ms):
        self._state.position_ms = position_ms
        return self._after()

    def set_mode(self, mode):
        if mode not in PlaybackMode.ALL:
            raise ValueError(f"非法模式 {mode}")
        self._mode = mode
        self._settings["playback_mode"] = mode
        return self._after()

    def get_queue(self):
        return list(self._state.queue)

    def append_to_queue(self, track_ids):
        by_id = {t.id: t for t in self._tracks}
        existing = {t.id for t in self._state.queue}
        for i in track_ids:
            if i in by_id and i not in existing:
                self._state.queue.append(by_id[i])
        return self._after()

    def play_next(self, track_ids):
        by_id = {t.id: t for t in self._tracks}
        cur = self._state.current_index
        for i in reversed(track_ids):
            if i in by_id:
                self._state.queue.insert(cur + 1, by_id[i])
        return self._after()

    def remove_from_queue(self, index):
        if 0 <= index < len(self._state.queue):
            del self._state.queue[index]
        return self._after()

    def move_queue_item(self, from_index, to_index):
        q = self._state.queue
        if 0 <= from_index < len(q) and 0 <= to_index < len(q):
            item = q.pop(from_index)
            q.insert(to_index, item)
        return self._after()

    # ---- 曲库层 ----
    def get_tracks(self, query=None, limit=200, offset=0):
        out = self._tracks
        if query:
            q = query.lower()
            out = [t for t in out if q in t.title.lower() or q in t.artist.lower() or q in t.album.lower()]
        return out[offset:offset + limit]

    def get_track(self, track_id):
        return next((t for t in self._tracks if t.id == track_id), None)

    def search(self, query):
        return self.get_tracks(query)

    def get_album(self, album):
        return [t for t in self._tracks if t.album == album]

    def get_artist(self, artist):
        return [t for t in self._tracks if t.artist == artist]

    def get_playlists(self):
        return [{"id": "p1", "name": "默认播放清单", "track_ids": [t.id for t in self._tracks]}]

    def get_stats(self):
        artists = {t.artist for t in self._tracks}
        albums = {t.album for t in self._tracks}
        total = sum(t.duration_ms for t in self._tracks)
        return {"track_count": len(self._tracks), "artist_count": len(artists),
                "album_count": len(albums), "total_duration_ms": total}

    # ---- 设置层 ----
    def get_setting(self, key):
        if key in self._settings:
            return self._settings[key]
        raise KeyError(f"未知设置项: {key}")

    def set_setting(self, key, value):
        if key not in self._settings:
            raise KeyError(f"未知设置项: {key}")
        if key == "playback_mode":
            value = str(value).upper()
            if value not in PlaybackMode.ALL:
                raise ValueError(f"非法播放模式 {value}")
            self._mode = value
        self._settings[key] = value
        return value

    def get_all(self):
        return dict(self._settings)