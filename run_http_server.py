#!/usr/bin/env python3
"""MCP 服务器入口（HTTP 模式）。

提供两种 HTTP 形态：
1. Streamable HTTP (MCP 规范，POST /mcp，SSE 响应)
2. 简单 POST JSON 网关（便于 curl / 浏览器 / 脚本直接调试）

用法：
    python3 run_http_server.py --port 8000 --backend demo
    curl -X POST http://127.0.0.1:8000/rpc -H 'Content-Type: application/json' \
         -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
"""
from __future__ import annotations
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer  # noqa: E402
from mcp_server import McpServer, create, available  # noqa: E402


class _Handler(BaseHTTPRequestHandler):
    server_version = "MCPPF/0.1"

    def log_message(self, fmt, *args):
        print(f"[http] {self.address_string()} - {fmt % args}", file=sys.stderr, flush=True)

    def _send_json(self, obj, status=200):
        data = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.split("?")[0].rstrip("/") == "/health":
            return self._send_json({
                "ok": True,
                "server": "mcp-player-framework",
                "backend": self.server.backend.name,
                "tools": len(self.server.all_tools),
            })
        if self.path.split("?")[0].rstrip("/") == "/":
            body = (b"<h1>MCP Player Framework</h1><p>TCP: POST /rpc  (JSON-RPC). "
                    b"GET /health for status.</p>")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self._send_json({"error": "not found"}, 404)

    def do_POST(self):
        if self.path.split("?")[0].rstrip("/") != "/rpc":
            return self._send_json({"error": "not found"}, 404)
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        try:
            msg = json.loads(raw.decode("utf-8"))
        except Exception:
            return self._send_json({"jsonrpc": "2.0", "id": None,
                                    "error": {"code": -32700, "message": "Parse error"}}, 400)
        if not isinstance(msg, dict):
            return self._send_json({"jsonrpc": "2.0", "id": None,
                                    "error": {"code": -32600, "message": "Invalid Request"}}, 400)
        try:
            resp = self.server.mcp.handle_message(msg)
        except Exception as e:
            return self._send_json({"jsonrpc": "2.0", "id": msg.get("id"),
                                    "error": {"code": -32603, "message": str(e)}}, 500)
        if resp is None:
            self._send_json({}, 202)  # notification accepted
            return
        self._send_json(resp, 200)


def main() -> int:
    parser = argparse.ArgumentParser(description="MCP Player Framework server (HTTP)")
    parser.add_argument("--port", type=int, default=int(os.environ.get("MCP_PORT", "8000")))
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--backend", default=os.environ.get("MCP_BACKEND", "demo"))
    parser.add_argument("--list-backends", action="store_true")
    args = parser.parse_args()

    if args.list_backends:
        for b in available():
            print(f"{b['name']:<12} {b['label']}")
        return 0

    backend = create(args.backend)
    mcp = McpServer(backend)
    Handler = _Handler
    Handler.server_version = "MCPPF/0.1"

    class Server(ThreadingHTTPServer):
        def __init__(self, addr):
            super().__init__(addr, Handler)
            self.mcp = mcp
            self.backend = backend
            self.all_tools = []
            # attach tools for /health count
            from mcp_server import ALL_TOOLS
            self.all_tools = ALL_TOOLS
            self.address_string_ = self.server_address

    srv = Server((args.host, args.port))
    print(f"[mcp-framework] HTTP server on http://{args.host}:{args.port}/rpc "
          f"backend={backend.name}", file=sys.stderr, flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("[mcp-framework] shutting down", file=sys.stderr, flush=True)
    finally:
        srv.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())