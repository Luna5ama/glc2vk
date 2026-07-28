#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import TypeAlias


JsonValue: TypeAlias = str | int | float | bool | None | list["JsonValue"] | dict[str, "JsonValue"]
JsonObject: TypeAlias = dict[str, JsonValue]


class VibrisBridgeError(Exception):
    def __init__(self, message: str) -> None:
        self.message: str = message
        super().__init__(message)


TOOLS = [
    {
        "name": "reload_shader",
        "description": "Reload the active shader and return captured shader errors.",
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
    {
        "name": "schedule_screenshot",
        "description": "Schedule a host screenshot after the requested number of rendered frames.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "frames": {"type": "integer", "minimum": 1, "default": 1},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "dump_ssbo",
        "description": "Dump the shader storage buffer at the requested binding index.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "index": {"type": "integer", "minimum": 0, "default": 0},
            },
            "additionalProperties": False,
        },
    },
    {
        "name": "dump_texture",
        "description": "Dump a texture selected by shader name or OpenGL id.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string"},
                "id": {"type": "integer", "minimum": 0},
                "raw": {"type": "boolean", "default": False},
            },
            "additionalProperties": False,
        },
    },
]

RESOURCES = [
    {
        "uri": "vibris://shader/status",
        "name": "shader_status",
        "description": "Active shader-pack status.",
        "mimeType": "application/json",
    },
    {
        "uri": "vibris://shader/errors",
        "name": "shader_errors",
        "description": "Captured shader errors.",
        "mimeType": "application/json",
    },
    {
        "uri": "vibris://shader/screenshot-result",
        "name": "screenshot_result",
        "description": "Path to the last completed screenshot.",
        "mimeType": "application/json",
    },
    {
        "uri": "vibris://shader/metrics",
        "name": "gpu_metrics",
        "description": "Recent GPU timing metrics.",
        "mimeType": "application/json",
    },
    {
        "uri": "vibris://shader/storage-buffers",
        "name": "storage_buffers",
        "description": "Active shader storage buffers.",
        "mimeType": "application/json",
    },
    {
        "uri": "vibris://shader/textures",
        "name": "textures",
        "description": "Available render and custom textures.",
        "mimeType": "application/json",
    },
    {
        "uri": "vibris://shader/patched-shaders",
        "name": "patched_shaders",
        "description": "Patched shader output status and files.",
        "mimeType": "application/json",
    },
]

def load_control(path: Path) -> JsonObject:
    if not path.exists():
        raise VibrisBridgeError(f"Vibris control file does not exist: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def request(control_file: Path, endpoint: str, payload: JsonObject | None = None) -> JsonObject:
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
        raise VibrisBridgeError(f"Vibris request failed: HTTP {exc.code}: {body}") from exc


def send(message: JsonObject) -> None:
    sys.stdout.write(json.dumps(message, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def result(message_id, value: JsonObject) -> None:
    send({"jsonrpc": "2.0", "id": message_id, "result": value})


def error(message_id, code: int, message: str) -> None:
    send({"jsonrpc": "2.0", "id": message_id, "error": {"code": code, "message": message}})


def handle_call(control_file: Path, name: str, arguments: JsonObject) -> JsonObject:
    if name == "reload_shader":
        response = request(control_file, "reload_shader", {})
    elif name == "capture_pass":
        response = request(control_file, "capture_pass", arguments)
    elif name == "capture_multi":
        response = request(control_file, "capture_multi", arguments)
    elif name == "status":
        response = request(control_file, "status")
    elif name == "schedule_screenshot":
        response = request(control_file, "shader/screenshot", arguments)
    elif name == "dump_ssbo":
        response = request(control_file, "shader/ssbo", arguments)
    elif name == "dump_texture":
        response = request(control_file, "shader/texture", arguments)
    else:
        raise VibrisBridgeError(f"Unknown tool: {name}")

    return {
        "content": [
            {
                "type": "text",
                "text": json.dumps(response, ensure_ascii=False),
            }
        ]
    }


def read_resource(control_file: Path, uri: str) -> JsonObject:
    if not any(resource["uri"] == uri for resource in RESOURCES):
        raise VibrisBridgeError(f"Unknown resource: {uri}")
    endpoint = "shader/" + uri.removeprefix("vibris://shader/").replace("-", "_")
    response = request(control_file, endpoint)
    return {
        "contents": [
            {
                "uri": uri,
                "mimeType": "application/json",
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
                        "capabilities": {"tools": {}, "resources": {}},
                        "serverInfo": {"name": "vibris-capture", "version": "0.1.0"},
                    },
                )
            elif method == "tools/list":
                result(message_id, {"tools": TOOLS})
            elif method == "tools/call":
                params = message.get("params", {})
                result(message_id, handle_call(args.control_file, params.get("name"), params.get("arguments", {})))
            elif method == "resources/list":
                result(message_id, {"resources": RESOURCES})
            elif method == "resources/read":
                params = message.get("params", {})
                result(message_id, read_resource(args.control_file, params.get("uri")))
            else:
                error(message_id, -32601, f"Unknown method: {method}")
        except (VibrisBridgeError, OSError, ValueError, KeyError, TypeError) as exc:
            error(message_id, -32000, str(exc))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())