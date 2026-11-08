# Orknux Shell

Orknux in the terminal: a chat with one of your workspace's agents, in the
style of Claude Code, where the agent can also run commands on this computer -
with your approval wherever your rules, `sudo` or the agent's own caution say
to ask.

## Installing

Linux (x86-64; glibc of Ubuntu 24.04, Debian 13, Fedora 40 or newer):

```
curl -fsSL https://raw.githubusercontent.com/orknux/orknux-shell/main/install.sh | sh
```

Windows (x64), in PowerShell:

```
irm https://raw.githubusercontent.com/orknux/orknux-shell/main/install.ps1 | iex
```

Both take the newest release, check it against the release's `SHA256SUMS`
before putting it in place, and need no root or administrator. The command is
`orknux`, with `orkx` as its short name. Linux installs to `~/.local/bin`
(`ORKNUX_SHELL_DIR` to choose), Windows to
`%LOCALAPPDATA%\Programs\orknux-shell`, which it adds to your PATH. Running
either again updates, and so does `orknux update` (below). Options: `--version v1.0.0` / `-Version v1.0.0` for a
given release, `--uninstall` / `-Uninstall` to remove it, with `--purge` /
`-Purge` to remove the sign-in too. On Linux, pass them as
`curl ... | sh -s -- --uninstall`; on Windows, as
`& ([scriptblock]::Create((irm .../install.ps1))) -Uninstall`.

Anywhere else, the jar on the [releases page](https://github.com/orknux/orknux-shell/releases)
runs with Java 25: `java -jar orknux-shell.jar`.

## Using it

```
orknux                      chat (asks for a server and signs in the first time)
orknux login [server]       sign in: server, username, password (never echoed)
orknux login URL --token orkx_...   sign in with an access token instead
orknux logout
orknux -p "message"         say one thing, print the answer, leave
orknux update               install the newest version
orknux --version
```
`orkx` does the same as `orknux` everywhere.

## In the chat

| | |
|---|---|
| `/agent [name]` | list the workspace's agents, or switch |
| `@agent message` | ask another agent in a side conversation, without switching |
| `/skill [key] [message]` | list the skills the agent can reach, or use one (or type the workspace's marker and the key, e.g. `!deploy`) |
| `/attach [paths]`, `/detach <n\|all>` | files for the next message; a path pasted on its own is offered as one |
| `/review [--fix] [branch\|path]` | review the uncommitted changes, a branch, or a path, with git where the Shell runs |
| `/goal <what> until <condition> [max <n>] [reviewer <model>]` | work at a goal, judged each iteration; `/goal`, `/goal stop` |
| `/subagents [n]` | the agents this conversation asked, or read one |
| `/sessions` (Ctrl+T) | every session of this chat - its own and its agents' - and follow one here as it works; Ctrl+T, Enter goes back |
| `/effort [level\|default]` | the model's reasoning effort |
| `/thinking [collapsed\|expanded\|hidden]` | how a turn's thinking shows; Ctrl+O expands every thought and tool call on the screen, and folds them again |
| `/config [<shortcut> ctrl+<letter>\|default]` | the shortcuts - expand, sessions, send now - and change one |
| `/orkx-web-chat`, `/orkx-web-session`, `/orkx-web-agent-settings` | open this chat, the session on the screen, or the agent's settings in the browser |
| `/open <n>` | open a link of the last answer in the browser |
| `/update` | install the newest Orknux Shell |
| `/usage`, `/resume`, `/clear`, `/workspace`, `/mode`, `/theme`, `/rules`, `/help`, `/quit` | |

Keys: Enter sends; Alt+Enter, Shift+Enter (where the terminal tells them
apart) or a trailing `\` for a new line; Tab completes everything; Shift+Tab
cycles the mode; Ctrl+C or Esc interrupts a turn (twice: stop waiting); Ctrl+D
quits. While the agent works, typing queues a message for when the turn ends,
and Ctrl+S sends it into the turn now, where the agent reads it at its next
step. Ctrl+O, Ctrl+T and Ctrl+S are `/config`'s to change.

Under the input sits a status line - workspace, agent, mode, effort, a running
goal, tokens in/out (cached) and cost, and whether the local-command door is
connected - and above it, while a turn runs, one live line drawn in place: the
thinking as it streams, "Preparing a tool call", "Running terminal_run: git
status", "Asking Researcher…", "Waiting for your approval", each with its own
clock.

## Local commands

A chat opened here is opened with `openedIn: SHELL`, which is what lends its
agent the `terminal_run` tool. While the Shell is open it long-polls
`/api/terminal/next` for the agent's commands and answers each one. What
happens to a command, first match winning:

1. a `deny` rule matches: refused, without asking ("Your rules forbid this command.")
2. an `ask` rule matches: asked
3. the mode is `ask` (or `plan`): asked, even where an allow rule matches
4. an `allow` rule matches: run, even for `sudo` or a command the agent marked dangerous
5. `sudo`, `su`, `doas`, `runas`, `pkexec` at the start of any command in the line, or `dangerous: true`: asked
6. otherwise: run at once

Asking draws a box with the command, the directory, the agent's reason and a
red "dangerous" marker where the agent set it, and three choices - Approve,
Approve for pattern (proposes an anchored rule such as `^git\s+push\b` to edit
and keeps it in `commands.allow`), Decline (Esc). Output is capped at 30,000
characters a stream, keeping the end; a command past its time (120 s unless the
agent asks otherwise) is killed with everything it started.

Commands run in `bash -lc` (or `sh -c`) on Linux and macOS, and in
`powershell -NoProfile -Command` on Windows, unless `shell` says otherwise.

## Updates

Once a day a chat asks orknux.ai whether a newer version is out, and if one is
says so in a line under the banner; it never installs anything on its own.
`/update` (or `orknux update`) downloads it beside the program and puts it in
place only when its size, its sha256 and its Ed25519 signature check out and it
is newer than what runs. The signature is made in this repository's release CI
with a key whose public half is built in
(`src/main/resources/io/mszymanski/orknux/shell/release-keys.pem`); orknux.ai
only passes it on, so it cannot hand out anything this program will install.
On Windows the running program is renamed aside and removed at the next start.
`ORKNUX_SHELL_UPDATES=off` or `"updates": false` in the configuration turns the
daily look off, for a packaged or air-gapped install; `ORKNUX_SHELL_UPDATE_URL`
points it at another site, for testing one.

## Configuration

`~/.config/orknux/orknux-shell.json` (`$XDG_CONFIG_HOME` respected), or
`%APPDATA%\Orknux\orknux-shell.json` on Windows; `ORKNUX_SHELL_CONFIG` or
`--config` names another. Written readable by its owner only, because the
session cookie or token in it is as good as a password while it lasts.

```json
{
  "server": "https://orknux.example.com",
  "mode": "auto",
  "shell": null,
  "thinking": "collapsed",
  "links": "auto",
  "updates": true,
  "commands": {
    "deny": [],
    "ask": ["\\brm\\s+-[a-zA-Z]*r[a-zA-Z]*f", "\\bmkfs\\b", "\\bdd\\s+if=", "..."],
    "allow": ["^git\\s+status\\b"]
  },
  "theme": { "accent": "#4f7fc4", "text": "#e4e4e7", "muted": "#71717a",
             "success": "#10b981", "warning": "#f59e0b", "danger": "#f87171",
             "thinking": "#c4453d", "code": "#c084fc" }
}
```

The rules are Java regular expressions found anywhere in the command line,
case-sensitive; `^...$` anchors one to the whole of it. A rule that does not
compile is named at start and skipped. The theme is Orknux's own dark palette,
in 24-bit colour where `COLORTERM` says so and the nearest of 256 otherwise;
`NO_COLOR` turns colour off. Links are OSC 8 hyperlinks where the terminal draws
them (`links: osc8|plain` to decide yourself).

## Building

```
./mvnw package                    # tests, and target/orknux-shell.jar (java -jar works)
./scripts/build-native.sh         # target/orknux-shell, built in Docker with GraalVM 25
./mvnw test -Dtest=IntegrationTest -Dorknux.shell.binary=$PWD/target/orknux-shell
```

CI (`.github/workflows/build.yml`) builds native binaries for linux-x64 and
windows-x64 and attaches them to a release on a `v*` tag. A release is the
version in `pom.xml`, tagged `v` and that version, with its section in
`CHANGELOG.md` written first; the job refuses a tag that differs from the pom
or has no section. With the `ORKNUX_SHELL_SIGNING_KEY` and
`ORKNUX_AI_PUBLISH_TOKEN` secrets set it also signs every file and offers the
release through orknux.ai, which is what `/update` installs from.
`ORKNUX_SHELL_TRACE=<file>` logs keys and phases, for a terminal that misbehaves.
