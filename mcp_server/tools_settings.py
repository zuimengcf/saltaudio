"""设置层工具（Settings Layer）。

职责：读写通用播放器设置（播放模式 / 音量 / 过滤文件夹 / 外观等静态配置）。
所有工具名以 settings_ 开头。
"""
from __future__ import annotations
from typing import Any, Dict
from .backends import BackendError, ConnectionError_


# 每个后端可声明「设置项清单（名称+描述+类型）」，供 settings_get_schema 返回
_SCHEMA_KEYS = ("key", "label", "type")


TOOLS: list[Dict[str, Any]] = [
    {
        "name": "settings_get",
        "description": "读取单个设置项。key 可用点的形式访问嵌套，如 favorites / playback_mode / excluded_folders。",
        "inputSchema": {
            "type": "object",
            "properties": {"key": {"type": "string", "description": "设置键名"}},
            "required": ["key"],
        },
    },
    {
        "name": "settings_set",
        "description": "写入单个设置项。value 类型由后端定义。",
        "inputSchema": {
            "type": "object",
            "properties": {"key": {"type": "string", "description": "设置键名"}, "value": {"description": "设置值"}},
            "required": ["key", "value"],
        },
    },
    {
        "name": "settings_get_all",
        "description": "读取全部设置项。",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
    {
        "name": "settings_get_schema",
        "description": "获取该项目支持的设置项清单（键名/说明/类型）。",
        "inputSchema": {"type": "object", "properties": {}, "required": []},
    },
]


def _schema_of(backend) -> list:
    raw = getattr(backend, "settings_schema", None)
    if callable(raw):
        raw = raw()
    return raw or []


def handle(name: str, backend, args: Dict[str, Any]) -> Dict[str, Any]:
    try:
        if name == "settings_get":
            return _ok({"key": args["key"], "value": backend.get_setting(args["key"])})
        if name == "settings_set":
            new_val = backend.set_setting(args["key"], args["value"])
            return _ok({"key": args["key"], "value": new_val})
        if name == "settings_get_all":
            return _ok(backend.get_all())
        if name == "settings_get_schema":
            return _ok({"settings": _schema_of(backend)})
    except ConnectionError_ as e:
        raise BackendError(f"无法连接播放器: {e}") from e
    except KeyError as e:
        raise BackendError(f"缺少参数: {e}") from e
    raise BackendError(f"未知设置层工具: {name}")


def _ok(data: Any) -> Dict[str, Any]:
    from .tools_playback import _to_text
    return {"content": [{"type": "text", "text": _to_text(data)}], "isError": False}