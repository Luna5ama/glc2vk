# Capture control and MCP bridge

`vibris-capture` owns capture request state, shader-debug state, and OpenGL resource dumping. `vibris-mcp` owns the
authenticated loopback transport and stdio MCP bridge. The embedding host owns shader reload, screenshots,
render-thread dispatch, shader-pack metadata, and discovery of active resources.

Iris constructs one `ShaderDebugControl` shared by its render hooks and `CaptureControlServer`. Iris does not own MCP
schemas, JSON-RPC dispatch, transport routing, GPU timing history, or GL resource serialization. The first three live
in `vibris-mcp`; the latter two remain in `vibris-capture`.

## Host integration

```java
CaptureManager manager = new CaptureManager();
ShaderDebugControl shaderDebug = new ShaderDebugControl(new HostShaderDebugAdapter());
CaptureControlServer server = new CaptureControlServer(
    manager,
    runnable -> hostExecutor.execute(runnable),
    () -> {
        reloadShader();
        return null;
    },
    shaderDebug
);
server.start(Path.of("vibris-capture-control.json"));
```

`CaptureControlServer` retains its source-compatible FQCN,
`dev.luna5ama.vibris.capture.CaptureControlServer`, but is published by `dev.luna5ama:vibris-mcp`. Embedding hosts
must depend on `vibris-mcp`; bundlers with transitivity disabled must include both `vibris-mcp` and `vibris-capture`.

The three-argument constructor remains available for capture-only hosts. In that mode `reload_shader` uses the
supplied callback and shader-debug MCP tools and resources are unavailable.

The host forwards frame and compute capture events to `CaptureManager`. It also forwards GL debug groups, draw and
compute timing scopes, screenshot render-tail ticks, and shader errors to the shared `ShaderDebugControl`.

## Authenticated control transport

`start()` binds an ephemeral port on `127.0.0.1`, creates a random bearer token, and writes the selected control file.
Iris uses `iris-capture-control.json`. The file has this shape:

```json
{"host":"127.0.0.1","port":49152,"token":"<random UUID>"}
```

Every internal request must contain the exact header `Authorization: Bearer <token>`. Treat the control file as a
local capability secret. `close()` stops the server and deletes the file; failed startup also cleans up.

The HTTP routes are private transport details for the stdio bridge. There is no fixed port 7150, unauthenticated
shader-debug listener, or OpenAPI endpoint.

## MCP bridge

Run the Python 3.10+ stdio server for Iris with:

```powershell
py -3 I:\code\vibris\mcp\src\main\python\vibris_capture_mcp.py --control-file `
  I:\code\Iris\fabric\run\iris-capture-control.json
```

The bridge advertises protocol `2024-11-05`, server `vibris-capture` version `0.1.0`, and both `tools` and `resources`
capabilities. It rereads the control file for every call, so a restarted host may rotate its port and token without
restarting the MCP process.

Tool results contain one text content item whose `text` is the result JSON.

### Tools

| Tool | Arguments | Result |
|------|-----------|--------|
| `reload_shader` | none | `success` and captured `errors` |
| `capture_pass` | required `pass`, optional `path` | queued capture path |
| `capture_multi` | required `type`, optional `path` | queued capture path |
| `status` | none | capture state, output path, and error |
| `schedule_screenshot` | optional `frames`, default 1, minimum 1 | scheduling acknowledgement |
| `dump_ssbo` | optional `index`, default 0, minimum 0 | dump path, buffer id, and byte count |
| `dump_texture` | optional `name` or `id`, optional `raw` | dump metadata and path |

For `dump_texture`, `name` takes precedence when both selectors are supplied. If neither is supplied, OpenGL id 0 is
used for compatibility with the previous shader-debug API. `raw=false` writes PNG; `raw=true` writes the normalized
binary representation described by the result metadata.

`schedule_screenshot` is asynchronous. A value of 1 captures at the next render-tail tick. Read
`vibris://shader/screenshot-result` for the last completed path.

### Resources

| URI | JSON snapshot |
|-----|---------------|
| `vibris://shader/status` | loaded shader-pack name and state |
| `vibris://shader/errors` | newest 100 captured shader errors |
| `vibris://shader/screenshot-result` | last completed screenshot path |
| `vibris://shader/metrics` | GPU timing histories and statistics |
| `vibris://shader/storage-buffers` | active SSBO indices and OpenGL ids |
| `vibris://shader/textures` | available render-target and custom textures |
| `vibris://shader/patched-shaders` | debug-output state, directory, and sorted file names |

`resources/read` returns one `application/json` text content item. Dumps and screenshots remain local files; MCP
returns their paths rather than embedding binary data.

Unknown JSON-RPC methods return `-32601`. Unknown tools, resources, control-file failures, transport errors,
validation failures, and host exceptions return `-32000`. Notifications without an `id` are ignored.

## Capture behavior

`prepareSingleCapture` queues an exact case-sensitive pass match. `prepareMultiCapture` accepts `prepare`, `begin`,
`deferred`, or `composite` case-insensitively. A new request replaces an existing pending request.

`defaultOutputPath(name)` returns `vibris/<name>-yyyyMMdd-HHmmss`. Relative paths resolve from the host process
working directory. `status.saving` remains true until the capture writer thread exits.

## Verification

From `I:\code\vibris`:

```powershell
py -3 -m unittest mcp\src\test\python\test_vibris_capture_mcp.py
.\gradlew.bat :vibris-mcp:check
.\gradlew.bat :vibris-capture:test
.\gradlew.bat '-Pvibris.runtimeTest=true' :vibris-capture:test `
  --tests dev.luna5ama.vibris.capture.ShaderDebugRuntimeTest
```

From `I:\code\Iris`:

```powershell
.\gradlew.bat :common:compileJava :fabric:compileJava :fabric:remapJar
```