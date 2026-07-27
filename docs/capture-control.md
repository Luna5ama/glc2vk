# Capture control and MCP bridge

`vibris-capture` owns capture request state, pass matching, capture-aware dispatch, the loopback HTTP control
server, and the stdio MCP bridge. An embedding host owns the application-specific edges: reload and thread-dispatch
callbacks, frame-boundary calls, compute shader source and pass-name extraction, and the native dispatch fallback.

Iris is one such host. Its glue constructs the Vibris manager and server, forwards frame and compute events, and keeps
the normal OpenGL dispatch when Vibris reports that a dispatch was not captured. Iris also forwards OpenGL debug-group
push/pop events to Vibris. Session state and remote protocol handling do not live in Iris.

## Capture manager

Create one `CaptureManager` for the lifetime of the host integration.

- `prepareSingleCapture(path, passName)` queues an exact, case-sensitive pass-name match.
- `prepareMultiCapture(path, programType)` accepts `prepare`, `begin`, `deferred`, or `composite`, case-insensitively.
  It matches the normalized type followed by an optional pass number from 1 through 99 and an optional lowercase
  letter suffix, for example `composite`, `composite7`, or `composite7_a`.
- `startFrame()` consumes a pending request and starts the underlying GL capture. Call it at the start of each rendered
  frame.
- `dispatchCompute(source, passName, x, y, z)` and `dispatchComputeIndirect(source, passName, offset)` return `true`
  when Vibris handled the dispatch. The host must perform its normal OpenGL dispatch only when they return `false`.
  A single-pass capture finishes after its first matching dispatch; any still-active capture finishes in `endFrame()`.
- `endFrame()` finishes the active capture and starts its asynchronous save. Call it at the end of each rendered frame.
- `status()` returns `pending`, `active`, `saving`, `lastOutputPath`, and `lastError`. `saving` remains true while the
  thread returned by the capture writer is alive.
- `defaultOutputPath(name)` returns a relative path shaped like `vibris/<name>-yyyyMMdd-HHmmss`. Relative paths are
  resolved from the host process's working directory.

Preparing a new request replaces an existing pending request. An unsupported multi-capture type throws
`IllegalArgumentException`.

## HTTP control server

Construct `CaptureControlServer` with the shared manager, an `Executor` that schedules work on the host's required
thread, and a `Callable<Void>` that reloads the active shader:

```java
CaptureManager manager = new CaptureManager();
CaptureControlServer server = new CaptureControlServer(
    manager,
    runnable -> hostExecutor.execute(runnable),
    () -> {
        reloadShader();
        return null;
    }
);
server.start(Path.of("vibris-capture-control.json"));
```

`start()` is idempotent while the server is running. It binds an ephemeral port on `127.0.0.1`, creates a random token,
and writes the selected control file. Calling `start()` without a path uses `vibris-capture-control.json`. A host that
owns a server should call `close()` during its shutdown path; `close()` stops HTTP work and deletes the control file.
Failed startup also removes a partially written control file.

Iris deliberately preserves its existing runtime filename:

```java
server.start(Path.of("iris-capture-control.json"));
```

With the normal Fabric development working directory, that file is
`I:\code\Iris\fabric\run\iris-capture-control.json`. The current Iris integration starts the server but does not call
`close()` during shutdown.

The control file is JSON:

```json
{"host":"127.0.0.1","port":49152,"token":"<random UUID>"}
```

The port is chosen at runtime. Every request must contain the exact, case-sensitive header
`Authorization: Bearer <token>`; a missing or different value returns HTTP 401 with `Unauthorized`. The server is
loopback-only, so it is not reachable directly from another machine, but any local process that can read the control
file can use its token. Treat the file as a capability secret and do not publish or copy it to an untrusted location.

The bridge uses these routes. The server currently does not reject a route solely because a different HTTP method was
used; clients should use the methods shown here.

- `GET /status`
  - Body: none.
  - Success: `{"pending":false,"active":false,"saving":false,"lastOutputPath":null,"lastError":null}`.
- `POST /reload_shader`
  - Body: `{}`.
  - Success: `{"ok":true}`.
- `POST /capture_pass`
  - Body: `{"pass":"<exact pass>","path":"<optional output path>"}`.
  - Success: `{"ok":true,"path":"<selected output path>"}`.
- `POST /capture_multi`
  - Body: `{"type":"prepare|begin|deferred|composite","path":"<optional output path>"}`.
  - Success: `{"ok":true,"path":"<selected output path>"}`.

Omit `path`, set it to `null`, or pass an empty string to use `defaultOutputPath`.
Invalid JSON, missing required fields,
unsupported multi-capture types, and callback failures return HTTP 500 as
`{"ok":false,"error":"<message>"}`.

`reload_shader`, `capture_pass`, and `capture_multi` run through the supplied dispatcher. The HTTP request waits for
that dispatched task to finish, so a success response means the reload or queue mutation has completed, not merely
that it was submitted. The host must supply an executor that can make progress independently of the HTTP handler.

## MCP bridge

`tools/vibris_capture_mcp.py` is a Python 3.10+ stdio JSON-RPC bridge. It reads the control file for each tool call and
forwards the request to the live HTTP server. Run it for Iris with:

```powershell
py -3 I:\code\vibris\tools\vibris_capture_mcp.py --control-file I:\code\Iris\fabric\run\iris-capture-control.json
```

It advertises MCP protocol `2024-11-05`, server name `vibris-capture`, version `0.1.0`, and these tools in order:

1. `reload_shader` — no arguments.
2. `capture_pass` — required string `pass`, optional string `path`.
3. `capture_multi` — required string `type`, optional string `path`.
4. `status` — no arguments.

Tool results contain one text item whose text is the HTTP response serialized as JSON. Unknown JSON-RPC methods return
error `-32601`; bridge, control-file, HTTP, and tool failures return error `-32000`. JSON-RPC notifications without an
`id` are ignored.

This PowerShell command exercises initialization without requiring a running host, because initialization does not
read the control file:

```powershell
'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' |
  py -3 I:\code\vibris\tools\vibris_capture_mcp.py --control-file I:\code\Iris\fabric\run\iris-capture-control.json
```

Expected output:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": {"tools": {}},
    "serverInfo": {"name": "vibris-capture", "version": "0.1.0"}
  }
}
```

## Verification

From `I:\code\vibris`:

```powershell
.\gradlew.bat :vibris-capture:test
py -3 -m unittest tools\test_vibris_capture_mcp.py
```

From `I:\code\Iris`, with the sibling Vibris composite build and local Sodium dependency available:

```powershell
.\gradlew.bat :common:compileJava :fabric:compileJava :fabric:remapJar
```