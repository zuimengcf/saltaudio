#!/usr/bin/env python3
"""MCP 服务器入口（stdio 模式）。

用法：
    python3 run_server.py --backend demo          # 使用 demo 后端
    python3 run_server.py --backend saltaudio     # 使用 SaltAudio 后端（需已实现）
    python3 run_server.py --list-backends         # 列出可用后端

MCP stdio 规范：从 stdin 逐行读 JSON-RPC，向 stdout 逐行写 JSON-RPC。
本进程内不得用 print 污染 stdout（调试日志请走 stderr）。
"""
from __future__ import annotations
import argparse
import sys
import os
import traceback

# 把项目根加入 sys.path，这样 `python3 run_server.py` 在任何 cwd 下都能 import mcp_server
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mcp_server.protocol import parse_message, encode_message  # noqa: E402
from mcp_server import McpServer, create, available  # noqa: E402


def _stderr(*args) -> None:
    print(*args, file=sys.stderr, flush=True)


def run_stdio(backend) -> None:
    server = McpServer(backend)
    _stderr(f"[mcp-framework] stdio server started, backend={backend.name}. "
            "Reading JSON-RPC lines from stdin.")
    for line in sys.stdin:
        msg = parse_message(line)
        if msg is None:
            continue
        try:
            resp = server.handle_message(msg)
            if resp is not None:
                sys.stdout.write(encode_message(resp) + "\n")
                sys.stdout.flush()
        except Exception as e:  # noqa: BLE001
            _stderr(f"[mcp-framework] unhandled error: {e}")
            traceback.print_exc(file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(description="MCP Player Framework server (stdio)")
    parser.add_argument("--backend", default=os.environ.get("MCP_BACKEND", "demo"),
                        help="选择后端（可用: demo / saltaudio ...）")
    parser.add_argument("--list-backends", action="store_true",
                        help="列出可用后端并退出")
    args = parser.parse_args()

    if args.list_backends:
        for b in available():
            print(f"{b['name']:<12} {b['label']}")
        return 0

    try:
        backend = create(args.backend)
    except KeyError as e:
        _stderr(f"后端错误: {e}")
        return 1

    run_stdio(backend)
    return 0


if __name__ == "__main__":
    sys.exit(main())