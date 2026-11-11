"""mcp_server 包：MCP 协议层 + 三层工具 + 后端适配。"""
from .backends import Backend, Track, PlaybackState, PlaybackMode, create, available, register
from .server import McpServer, ALL_TOOLS

__all__ = [
    "Backend", "Track", "PlaybackState", "PlaybackMode",
    "create", "available", "register",
    "McpServer", "ALL_TOOLS",
]