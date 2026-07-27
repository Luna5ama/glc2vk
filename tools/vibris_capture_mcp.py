#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path


TOOLS = [
    {
        "name": "reload_shader",
        "description": "Reload the active shader in the running host application.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "capture_pass",
        "description": "Queue a vibris capture for one compute pass in the next rendered frame.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "pass": {"type": "string"},
                "path": {"type": "string"},
            },
            "required": ["pass"],
            "additionalProperties": False,
        },
    },
    {
        "name": "capture_multi",
        "description": "Queue a vibris multi-pass compute capture for prepare, begin, deferred, or composite.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "type": {"type": "string"},
                "path": {"type": "string"},
            },
            "required": ["type"],
            "additionalProperties": False,
        },
    },
    {
        "name": "status",
        "description": "Read vibris capture status from the running host application.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
]


def load_control(path: Path) -> dict:
    if not path.exists():
        raise RuntimeError(f"Vibris control file does not exist: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def request(control_file: Path, endpoint: str, payload: dict | None = None) -> dict:
    control = load_control(control_file)
    url = f"http://{control['host']}:{control['port']}/{endpoint}"
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={
            "Authorization": f"Bearer {control['token']}",
            "Content-Type": "application/json",
        },
        method="GET" if payload is None else "POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Vibris request failed: HTTP {exc.code}: {body}") from exc


def send(message: dict) -> None:
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def result(message_id, value: dict) -> None:
    send({"jsonrpc": "2.0", "id": message_id, "result": value})


def error(message_id, code: int, message: str) -> None:
    send({"jsonrpc": "2.0", "id": message_id, "error": {"code": code, "message": message}})


def handle_call(control_file: Path, name: str, arguments: dict) -> dict:
    if name == "reload_shader":
        response = request(control_file, "reload_shader", {})
    elif name == "capture_pass":
        response = request(control_file, "capture_pass", arguments)
    elif name == "capture_multi":
        response = request(control_file, "capture_multi", arguments)
    elif name == "status":
        response = request(control_file, "status")
    else:
        raise RuntimeError(f"Unknown tool: {name}")

    return {
        "content": [
            {
                "type": "text",
                "text": json.dumps(response, ensure_ascii=False),
            }
        ]
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--control-file",
        type=Path,
        default=Path("vibris-capture-control.json"),
    )
    args = parser.parse_args()

    for line in sys.stdin:
        if not line.strip():
            continue
        message = json.loads(line)
        message_id = message.get("id")
        method = message.get("method")

        if message_id is None:
            continue

        try:
            if method == "initialize":
                result(
                    message_id,
                    {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "vibris-capture", "version": "0.1.0"},
                    },
                )
            elif method == "tools/list":
                result(message_id, {"tools": TOOLS})
            elif method == "tools/call":
                params = message.get("params", {})
                result(message_id, handle_call(args.control_file, params.get("name"), params.get("arguments", {})))
            else:
                error(message_id, -32601, f"Unknown method: {method}")
        except Exception as exc:
            error(message_id, -32000, str(exc))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())