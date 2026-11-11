"""MCP server 核心：处理初始化与工具分发（stdio / HTTP 共用）。

生命周期：
- initialize             → 返回协议版本与能力
- notifications/initialized → 客户端通知（无响应）
- tools/list             → 返回三层全部工具
- tools/call             → 按工具名分派到对应层并调用所选后端

设计要点：所有 JSON-RPC 响应都带与请求一致的 id。内部方法只负责算出 result/error
内容（不含 id），由 handle_message 统一封装 id，保证 id 透传正确。
"""
from __future__ import annotations
from typing import Any, Dict, List, Optional

from . import protocol
from . import tools_playback, tools_library, tools_settings
from .backends import Backend

# 全部工具 = 播放层 + 曲库层 + 设置层
ALL_TOOLS: List[Dict[str, Any]] = (
    tools_playback.TOOLS + tools_library.TOOLS + tools_settings.TOOLS
)

# 工具名前缀 → 层处理函数
_LAYERS = {
    "playback_": tools_playback.handle,
    "library_": tools_library.handle,
    "settings_": tools_settings.handle,
}


def pick_layer(tool_name: str):
    for prefix, handler in _LAYERS.items():
        if tool_name.startswith(prefix):
            return handler
    return None


class McpServer:
    """无状态的 JSON-RPC 逻辑核心。transport 层负责读写原始字节。"""

    def __init__(self, backend: Backend):
        self.backend = backend
        self.initialized = False
        self.server_info = {
            "name": "mcp-player-framework",
            "version": "0.1.0",
        }

    def handle_message(self, msg: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """处理一条 MCP 消息，返回要发回的响应 dict（通知/非法无响应返回 None）。"""
        method = msg.get("method")
        msg_id = msg.get("id")
        is_notification = msg_id is None

        if not isinstance(method, str) or not method:
            return None if is_notification else protocol.make_error(
                msg_id, -32600, "Invalid Request: missing method")

        # 通知（无 id）：更新状态，无响应
        if is_notification:
            if method == "notifications/initialized":
                self.initialized = True
            return None

        # normal request：计算内容再封装 id
        try:
            if method == "initialize":
                result = self._initialize_result(msg.get("params") or {})
                return protocol.make_response(msg_id, result)
            if method in ("tools/list", "tools/list_tools"):
                return protocol.make_response(msg_id, self._tools_list_result())
            if method in ("tools/call", "tools/call_tool"):
                return self._tools_call(msg_id, msg.get("params") or {})
            if method == "ping":
                return protocol.make_response(msg_id, {})
            return protocol.make_error(msg_id, -32601, f"Method not found: {method}")
        except protocol.JsonRpcError as e:
            return protocol.make_error(msg_id, e.code, e.message, e.data)
        except Exception as e:  # noqa: BLE001
            return protocol.make_error(msg_id, -32603, str(e))

    # ------------------------------------------------------------------
    def _initialize_result(self, params: Dict[str, Any]) -> Dict[str, Any]:
        self.initialized = True
        return {
            "protocolVersion": protocol.PROTOCOL_VERSION,
            "capabilities": {
                "tools": {},
                "resources": {},
                "prompts": {},
                "logging": {},
            },
            "serverInfo": self.server_info,
            "instructions": (
                "MCP Player Framework · 三层接入。\n"
                "• 播放层 playback_*：控制播放/暂停/切歌/seek/队列/播放模式\n"
                "• 曲库层 library_*：检查歌曲信息/搜索/曲库统计\n"
                "• 设置层 settings_*：读写项目设置（播放模式偏好/收藏/过滤/外观）\n"
            ),
        }

    def _tools_list_result(self) -> Dict[str, Any]:
        return {"tools": ALL_TOOLS}

    def _tools_call(self, msg_id, params: Dict[str, Any]) -> Dict[str, Any]:
        name = params.get("name")
        arguments = params.get("arguments") or {}
        if not isinstance(name, str) or not name:
            return protocol.make_error(msg_id, -32602, "tools/call missing 'name'")
        handler = pick_layer(name)
        if handler is None:
            return protocol.make_error(msg_id, -32602, f"Unknown tool: {name}",
                                       {"name": name})
        try:
            result: Dict[str, Any] = handler(name, self.backend, arguments)
            return protocol.make_response(msg_id, result)
        except Exception as e:
            return protocol.make_error(msg_id, -32603, str(e), {"name": name})


def build_all_tools() -> List[Dict[str, Any]]:
    return ALL_TOOLS