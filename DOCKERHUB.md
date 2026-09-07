# orknux-shell

An MCP server that is a POSIX shell with a workspace. It speaks Streamable HTTP
MCP behind a bearer token, exposes a DesktopCommander-compatible tool surface
(process, file, search and git tools), and gives every MCP session its own
jailed, throwaway directory. Built to sit behind [Orknux](https://github.com/michjak-szymanski/orknux-server),
which relays its tools to a granted agent — but it is an ordinary MCP server
and any MCP client can drive it.

The image is a GraalVM **native executable** on a `node:22-slim` base: it starts
in milliseconds, idles in tens of megabytes, and hands agents a real toolchain —
`sh`, `node`, `npm`, `git` — with DesktopCommander bundled (`desktop-commander`
on the PATH) for a client that wants its stdio surface.

## Run it

```sh
docker run -p 8090:8090 -e ORKNUX_SHELL_TOKEN=orkx_something_long \
  michjakszymanski/orknux-shell
```

There is no default token — the server refuses to start without one, because a
shell that answers unauthenticated requests is a shell for whoever finds the
port. Give the same token to whatever calls it.

The endpoint is `POST /mcp` (JSON-RPC 2.0: `initialize`, `tools/list`,
`tools/call`, `ping`) with `DELETE /mcp` to close a session. Every request
carries `Authorization: Bearer <token>`.

## What it guarantees

- **A workspace per session.** `initialize` allocates a private root directory
  and returns its id in `Mcp-Session-Id`. Every path a tool names must resolve
  inside that root; anything that escapes is refused. The root — and any GitHub
  token stored for the session — is destroyed on `DELETE`, or when the session
  sits idle past `ORKNUX_SHELL_SESSION_TIMEOUT`. Concurrent sessions are fully
  isolated from each other.
- **Blocking commands.** `start_process` without `timeout_ms` runs the command
  to completion and returns its combined output verbatim; a command that ran
  and failed is still a normal result. Only a shell that could not run at all
  is an MCP error. With `timeout_ms`, a long command is handed back as a pid to
  follow with `read_process_output`.
- **Per-session GitHub tokens.** `github_login` stores a token for one session
  only, written outside its jailed workspace so the file tools cannot read it
  back, and used automatically by git over https. `github_logout` forgets it.

## Configuration

Every value reads from the environment; `env | grep ORKNUX_SHELL_` is the whole
of it.

| Variable | Default | What it decides |
| --- | --- | --- |
| `ORKNUX_SHELL_TOKEN` | — (required) | The bearer token every request must carry. |
| `ORKNUX_SHELL_PORT` | `8090` | The port. |
| `ORKNUX_SHELL_BINARY` | `/bin/sh` | The shell that runs commands. |
| `ORKNUX_SHELL_SESSIONS_ROOT` | `$TMPDIR/orknux-shell` | Where session workspaces are made. |
| `ORKNUX_SHELL_SESSION_TIMEOUT` | `2h` | How long an untouched session keeps its workspace. |
| `ORKNUX_SHELL_COMMAND_TIMEOUT` | `10m` | The ceiling on a blocking `start_process`. |
| `ORKNUX_SHELL_READ_TIMEOUT` | `5s` | How long `read_process_output` waits for new output. |
| `ORKNUX_SHELL_OUTPUT_LIMIT` | `1000000` | How many characters of a process's output are kept. |
| `ORKNUX_SHELL_LOG_LEVEL` | `INFO` | Log level for the server's own packages. |

## Metrics

Prometheus at `/actuator/prometheus`, behind the same bearer token (a scrape
config carries it as `bearer_token`); the health probes at
`/actuator/health/liveness` and `/readiness` are the one thing served without
it. Beside the JVM and HTTP metrics: `orknux_shell_sessions_open` (workspaces
alive now) and `orknux_shell_tool_calls_total{tool,outcome}`.

## Isolation and safety

The jail confines every file and search path, and processes start inside the
workspace — but a shell command can read whatever the *process* can. The
container is the boundary around that, which is why this image is minimal and
runs as an unprivileged system user. Run it in a container, and pair it with
the Kubernetes descriptors in the repository (read-only root filesystem,
size-limited workspace volume, reasoned resource limits) for anything real.

## Tags

`latest` follows `main`. A `vX.Y.Z` release also publishes `X.Y.Z` and `X.Y`,
and every build carries its commit sha — pin as loosely or as tightly as you
like.

## Source & license

[github.com/michjak-szymanski/orknux-shell](https://github.com/michjak-szymanski/orknux-shell) —
AGPL-3.0. Bundled DesktopCommander is MIT-licensed.
