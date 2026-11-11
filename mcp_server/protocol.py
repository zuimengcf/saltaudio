"""MCP (Model Context Protocol) 协议层。

实现 MCP 最小可用的 JSON-RPC 2.0 报文封装：
- stdio 模式：逐行读取 stdin，逐行写回 stdout（严格禁止 print 污染 stdout）。
- HTTP 模式：由 run_http_server.py 共用同一套 encode/decode。

MCP 生命周期（2024/2025 规范核心）：
1. client → initialize {protocolVersion, capabilities}
2. server → initialize result {protocolVersion, capabilities}
3. client → notifications/initialized
4. client → tools/list        → server 返回工具清单
5. client → tools/call        → server 执行工具并返回结果

本框架只实现这 4 项，足够让 AI 客户端以标准 MCP 方式调用三层工具。
"""
from __future__ import annotations
import json
from typing import Any, Dict, Optional

# 我们声明支持的协议版本（尽量兼容主流客户端）
PROTOCOL_VERSION = "2024-11-05"


class JsonRpcError(Exception):
    def __init__(self, code: int, message: str, data: Any = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.data = data


def make_request(method: str, params: Any, msg_id: Any) -> Dict[str, Any]:
    """构造一个 JSON-RPC 请求。"""
    return {"jsonrpc": "2.0", "id": msg_id, "method": method, "params": params}


def make_response(msg_id: Any, result: Any) -> Dict[str, Any]:
    return {"jsonrpc": "2.0", "id": msg_id, "result": result}


def make_error(msg_id: Any, code: int, message: str, data: Any = None) -> Dict[str, Any]:
    payload = {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}
    if data is not None:
        payload["error"]["data"] = data
    return payload


def make_notification(method: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None:
        msg["params"] = params
    return msg


def parse_message(line: str) -> Optional[Dict[str, Any]]:
    """解析一行 JSON，返回 dict；空行/非法 JSON 返回 None（由调用方决定忽略或报错）。"""
    line = line.strip()
    if not line:
        return None
    try:
        obj = json.loads(line)
    except json.JSONDecodeError:
        return None
    if not isinstance(obj, dict):
        return None
    return obj


def encode_message(msg: Dict[str, Any]) -> str:
    """把 dict 封装成一行 JSON（compact，单行，无多余空白）。"""
    return json.dumps(msg, ensure_ascii=False, separators=(",", ":"))