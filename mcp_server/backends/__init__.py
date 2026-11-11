"""后端注册表：集中管理「能接入哪些播放器」。

新增播放器 = 在 backends/ 下新建一个继承 base.Backend 的文件，然后在本文件末尾的
注册区调用 register(...) 即可。MCP 服务器通过 --backend <名字> 选择。
"""
from __future__ import annotations
from typing import Dict, Type

from .base import Backend, Track, PlaybackState, PlaybackMode
from .base import PlaybackBackend, LibraryBackend, SettingsBackend
from .base import ConnectionError_ as ConnectionError_
from .base import BackendError as BackendError

_BACKENDS: Dict[str, Type[Backend]] = {}


def register(name: str, backend_cls: Type[Backend]) -> None:
    if not isinstance(name, str) or not name:
        raise ValueError("backend name must be non-empty string")
    _BACKENDS[name] = backend_cls


def available() -> list:
    return [{"name": n, "label": c.label, "cls": c.__name__} for n, c in _BACKENDS.items()]


def create(name: str, **kwargs) -> Backend:
    """实例化指定后端。未注册则报错。"""
    if name not in _BACKENDS:
        registered = ", ".join(_BACKENDS) or "(none yet)"
        raise KeyError(f"未注册的后端: {name!r}。当前可用: {registered}")
    return _BACKENDS[name](**kwargs)


# ---------------------------------------------------------------------------
# 注册区：在这里启用各后端
# 注意：为保证「独立存在、适配所有播放器」，每个后端可独立加载。
# 若某后端依赖的环境缺失（如 adb 不可用），应在实例化时才报错而不是 import 失败。
# ---------------------------------------------------------------------------
from .demo import DemoBackend  # noqa: E402
register("demo", DemoBackend)

from .saltaudio import SaltAudioBackend  # noqa: E402
register("saltaudio", SaltAudioBackend)

from .generic_android import GenericAndroidBackend  # noqa: E402
register("generic_android", GenericAndroidBackend)

from .gramophone import GramophoneBackend  # noqa: E402
register("gramophone", GramophoneBackend)


__all__ = [
    "Backend", "Track", "PlaybackState", "PlaybackMode",
    "PlaybackBackend", "LibraryBackend", "SettingsBackend",
    "register", "available", "create",
]