# orknux-shell

An MCP server that is a POSIX shell with a workspace. Orknux reaches it over
Streamable HTTP with a bearer token, relays every tool it advertises to a
granted agent, and each MCP session gets a jailed throwaway directory of its
own. It implements `docs/orknux-shell-contract.md` from the orknux-server
repository, natively — no DesktopCommander underneath.

## The shape of it

- **One endpoint.** `POST /mcp` speaks JSON-RPC 2.0 (`initialize`,
  `tools/list`, `tools/call`, `ping`); `DELETE /mcp` closes a session. No
  stdio, no SSE — a request and its answer is the whole of the transport.
- **One credential.** Every request carries `Authorization: Bearer <token>`.
  The token is configured at deploy and the server refuses to start without
  one.
- **One workspace per session.** `initialize` allocates a private root
  directory and answers with its id in `Mcp-Session-Id`. Every path a tool on
  that session names must resolve inside that root — relative paths resolve
  against it, `~` means it, and anything that escapes is refused. The root is
  destroyed when the session is DELETEd, or when it sits idle past
  `ORKNUX_SHELL_SESSION_TIMEOUT`.

## The tools

DesktopCommander's names and shapes, which is what the contract requires of a
native reimplementation:

- **Processes** — `start_process`, `read_process_output`,
  `interact_with_process`, `force_terminate`, `list_sessions`,
  `list_processes`, `kill_process`. `start_process` without `timeout_ms`
  blocks until the command finishes and returns its combined output verbatim
  (a command that ran and failed is still a normal result; only a shell that
  could not run at all is an MCP error). With `timeout_ms`, a command still
  running is handed back as a pid to follow.
- **Files** — `read_file`, `read_multiple_files`, `write_file`, `edit_block`,
  `create_directory`, `list_directory`, `move_file`, `get_file_info`.
- **Search** — `search_files` (by name), `search_code` (by content, as
  `path:line: text`).

The jail confines every file and search path, and processes start inside the
workspace — but a shell command can read whatever the *process* can. The
container is the boundary around that, which is why the shipped image is
minimal and runs as a system user. Run this in a container for anything real.

## The image

A GraalVM native executable on a `node:22-slim` base: the server itself starts
in milliseconds and idles in tens of megabytes, and the base hands agents a
real toolchain — `sh`, `node`, `npm` — which is most of what a coding harness
is for. DesktopCommander is bundled too (`desktop-commander` on the PATH), so
a deployment that wants DC's own stdio surface has it in the same container.
DC is MIT-licensed, which permits exactly this — redistribution in an image —
and its license text ships inside the installed package.

## Metrics

Prometheus at `/actuator/prometheus`, behind the same bearer token as
everything else — a scrape config carries it as `bearer_token`. Beside the
JVM and HTTP metrics:

- `orknux_shell_sessions_open` — workspaces that exist right now; a leak here
  is disk filling in session-sized steps.
- `orknux_shell_tool_calls_total{tool,outcome}` — every call, split by tool
  and by how it went (`ok`, `refused`, `unavailable`, `failed`).

The health probes — `/actuator/health/liveness` and `/readiness` — are the
one thing served without the token, because a Kubernetes probe cannot fetch a
secret to send and "up or not" gives nothing away.

## Kubernetes

`deploy/kubernetes.yaml` is a sample Deployment and Service with reasoned
resource requests and limits, probes wired to the health endpoints, a
read-only root filesystem, and the workspaces on a size-limited `emptyDir`.
Read the comments in it before copying the numbers — the limits are really a
budget for the commands agents run, not for the server.

## Running it

```sh
ORKNUX_SHELL_TOKEN=orkx_something_long ./mvnw spring-boot:run
```

Or the image:

```sh
docker run -p 8090:8090 -e ORKNUX_SHELL_TOKEN=orkx_something_long orknux-shell
```

Configuration is environment variables, all prefixed the same way:

| Variable | Default | What it decides |
| --- | --- | --- |
| `ORKNUX_SHELL_TOKEN` | — (required) | The bearer token every request must carry. |
| `ORKNUX_SHELL_PORT` | `8090` | The port. |
| `ORKNUX_SHELL_BINARY` | `/bin/sh` | The shell that runs commands. |
| `ORKNUX_SHELL_SESSIONS_ROOT` | `$TMPDIR/orknux-shell` | Where session workspaces are made. |
| `ORKNUX_SHELL_SESSION_TIMEOUT` | `2h` | How long an untouched session keeps its workspace. |
| `ORKNUX_SHELL_COMMAND_TIMEOUT` | `10m` | The ceiling on a blocking `start_process`. |
| `ORKNUX_SHELL_READ_TIMEOUT` | `5s` | How long `read_process_output` waits for something new. |
| `ORKNUX_SHELL_OUTPUT_LIMIT` | `1000000` | How many characters of a process's output are kept. |

One caveat worth knowing on the Orknux side: its `McpClient` gives a call
thirty seconds by default (`orknux.mcp.timeout`), and a blocking
`start_process` holds its answer until the command finishes. A deployment that
runs long commands raises that timeout, or the agent passes `timeout_ms` and
follows the pid.

## Building

```sh
./mvnw verify              # build and test on the JVM; needs a POSIX sh for the shell tests
./mvnw -Pnative package    # the native executable, with GraalVM installed
docker build -t orknux-shell .   # compiles natively inside the build stage
```

CI builds and tests every push, boots the built image and speaks one round of
MCP at it, and publishes to Docker Hub from `main` (as `latest`) and from
`v*` tags (as `X.Y.Z`, `X.Y`, and the commit sha) — set the
`DOCKERHUB_USERNAME` variable and `DOCKERHUB_TOKEN` secret on the repository
to switch publishing on.

## License

[AGPL-3.0](LICENSE), the same terms as orknux-server.
