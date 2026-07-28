import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[2] / "main" / "python" / "vibris_capture_mcp.py"


def run_proxy(*messages, control_file=None):
    command = [sys.executable, str(SCRIPT)]
    if control_file is not None:
        command.extend(("--control-file", str(control_file)))
    return subprocess.run(
        command,
        input="".join(json.dumps(message, separators=(",", ":")) + "\n" for message in messages),
        capture_output=True,
        text=True,
        check=False,
    )


class VibrisCaptureMcpTest(unittest.TestCase):
    def test_initialize_when_requested(self):
        request = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}

        completed = run_proxy(request)

        expected = {
            "jsonrpc": "2.0",
            "id": 1,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}, "resources": {}},
                "serverInfo": {"name": "vibris-capture", "version": "0.1.0"},
            },
        }
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(json.dumps(expected, separators=(",", ":")) + "\n", completed.stdout)

    def test_tools_list_when_requested(self):
        request = {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}

        completed = run_proxy(request)

        response = json.loads(completed.stdout)
        tools = response["result"]["tools"]
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(
            [
                "reload_shader",
                "capture_pass",
                "capture_multi",
                "status",
                "schedule_screenshot",
                "dump_ssbo",
                "dump_texture",
            ],
            [tool["name"] for tool in tools],
        )
        self.assertTrue(all(set(tool) == {"name", "description", "inputSchema"} for tool in tools))
        self.assertEqual(
            [None, ["pass"], ["type"], None, None, None, None],
            [tool["inputSchema"].get("required") for tool in tools],
        )
        self.assertTrue(all(tool["inputSchema"]["type"] == "object" for tool in tools))
        self.assertTrue(all(tool["inputSchema"]["additionalProperties"] is False for tool in tools))
        schemas = {tool["name"]: tool["inputSchema"] for tool in tools}
        self.assertEqual(
            {"type": "integer", "minimum": 1, "default": 1},
            schemas["schedule_screenshot"]["properties"]["frames"],
        )
        self.assertEqual(
            {"type": "integer", "minimum": 0, "default": 0},
            schemas["dump_ssbo"]["properties"]["index"],
        )
        self.assertEqual(
            {
                "name": {"type": "string"},
                "id": {"type": "integer", "minimum": 0},
                "raw": {"type": "boolean", "default": False},
            },
            schemas["dump_texture"]["properties"],
        )

    def test_resources_list_when_requested(self):
        request = {"jsonrpc": "2.0", "id": 5, "method": "resources/list"}

        completed = run_proxy(request)

        response = json.loads(completed.stdout)
        resources = response["result"]["resources"]
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(
            [
                "vibris://shader/status",
                "vibris://shader/errors",
                "vibris://shader/screenshot-result",
                "vibris://shader/metrics",
                "vibris://shader/storage-buffers",
                "vibris://shader/textures",
                "vibris://shader/patched-shaders",
            ],
            [resource["uri"] for resource in resources],
        )
        self.assertTrue(
            all(set(resource) == {"uri", "name", "description", "mimeType"} for resource in resources)
        )
        self.assertTrue(all(resource["mimeType"] == "application/json" for resource in resources))

    def test_unknown_resource_when_read(self):
        request = {
            "jsonrpc": "2.0",
            "id": 6,
            "method": "resources/read",
            "params": {"uri": "vibris://shader/unknown"},
        }

        completed = run_proxy(request)

        response = json.loads(completed.stdout)
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(
            {"code": -32000, "message": "Unknown resource: vibris://shader/unknown"},
            response["error"],
        )

    def test_unknown_method_when_requested(self):
        request = {"jsonrpc": "2.0", "id": 3, "method": "unknown"}

        completed = run_proxy(request)

        response = json.loads(completed.stdout)
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual({"code": -32601, "message": "Unknown method: unknown"}, response["error"])

    def test_clean_exit_when_stdin_is_eof(self):
        completed = run_proxy()

        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual("", completed.stdout)

    def test_missing_control_file_when_status_requested(self):
        with tempfile.TemporaryDirectory() as directory:
            control_file = Path(directory) / "missing.json"
            request = {
                "jsonrpc": "2.0",
                "id": 4,
                "method": "tools/call",
                "params": {"name": "status", "arguments": {}},
            }

            completed = run_proxy(request, control_file=control_file)

        response = json.loads(completed.stdout)
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual(
            {"code": -32000, "message": f"Vibris control file does not exist: {control_file}"},
            response["error"],
        )


if __name__ == "__main__":
    unittest.main()