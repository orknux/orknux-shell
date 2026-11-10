# Orknux Shell

Orknux in the terminal: a chat with one of your workspace's agents, in the
style of Claude Code, where the agent can also run commands on this computer -
with your approval wherever your rules, `sudo` or the agent's own caution say
to ask.

## Installing

Linux (x86-64; glibc 2.17 or newer, which is RHEL 7, Debian 8, Ubuntu 14.04 and everything since):

```
curl -fsSL https://orkx.ai/install/shell.sh | sh
```

Windows (x64), in PowerShell:

```
irm https://orkx.ai/install/shell.ps1 | iex
```

Those are this repository's `install.sh` and `install.ps1`, as the newest
release's CI uploaded them to orknux.ai, whose short address is orkx.ai. Both
take the newest release, check it against the release's `SHA256SUMS` before
putting it in place, and need no root or administrator. The command is
`orknux`, with `orkx` as its short name. Linux installs to `~/.local/bin`
(`ORKNUX_SHELL_DIR` to choose), Windows to
`%LOCALAPPDATA%\Programs\orknux-shell`, which it adds to your PATH. Running
either again updates, and so does `orknux update` (below). Options: `--version v1.0.0` / `-Version v1.0.0` for a
given release, `--uninstall` / `-Uninstall` to remove it, with `--purge` /
`-Purge` to remove the sign-in too. On Linux, pass them as
`curl ... | sh -s -- --uninstall`; on Windows, as
`& ([scriptblock]::Create((irm https://orkx.ai/install/shell.ps1))) -Uninstall`.

Anywhere else, the jar on the [releases page](https://github.com/orknux/orknux-shell/releases)
runs with Java 25: `java -jar orknux-shell.jar`.

## Using it

```
orknux                      chat (asks for a server and signs in the first time)
orknux login [server]       sign in: server, username, password (never echoed)
orknux login URL --token orkx_...   sign in with an access token instead
orknux login https://orknux.example.com/pair/K7P-4QX   connect with a pairing link (orknux <link> too)
orknux logout
orknux -p "message"         say one thing, print the answer, leave
orknux update               install the newest version
orknux --version
```
`orkx` does the same as `orknux` everywhere.

A pairing link - Orknux's **Preferences › Connected clients › Connect a
client**, the one the Chrome extension and Orknux for Windows take - can be
pasted where the Shell asks for a server, or given on the command line, and
the Shell trades it once for a credential of its own (listed as "Orknux Shell
on <computer>"), with no address or password typed. A code alone (K7P-4QX)
pairs with the server the Shell already knows. A server that lets paired
clients through only the clients' own doors - the browser, desktop, Claude
Code and terminal-command doors - does not open its chat to such a
credential; the Shell says so and asks for a username and password for the
link's server instead. `orknux logout` forgets the credential here; it is
disconnected for good in that same list.

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
| `/copy-image [n\|list]` | copy a picture an answer showed to the clipboard, as an image - the last one unless told (below) |
| `/voice [headphones]` | talk instead of typing: it listens, answers, and reads the answer aloud (below) |
| `/update` | install the newest Orknux Shell |
| `/paste` | attach the picture on the clipboard to the next message |
| `/version` | the Shell's version, its server, clipboard and terminal |
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

## Pictures in and out

**Pasting.** Ctrl+V pastes the clipboard (Alt+V too, for a terminal that keeps
Ctrl+V for its own paste - Windows Terminal does, for Ctrl+Shift+V as well, and
its paste carries text only). `/paste` does the same with no key at all, and
lists the picture with the message's other attachments. A picture on it is uploaded
at once to the workspace's attachments, as `/attach` uploads a file, and stands
in the line as a chip, `[image 1: 1280×720 png]`; it goes with the message as
the web chat sends one, by its attachment id. Backspace at a chip's end takes
it out, and a chip edited away any other way is detached when the line is
sent. Pictures with no words are a message of their own. Files copied in a
file manager are pasted the same way where they are pictures, and as their
paths otherwise; text is typed in as it is. A terminal's own paste never
carries a picture's bytes, but a path to a picture pasted with it, or a file
dragged onto the window, becomes a chip as well.

**Copying.** Every picture an answer shows - one the agent drew, a file it put
on the chat that is a picture, an image in its markdown - is numbered under the
answer ("picture 3 · /copy-image copies it"), and `/copy-image [n]` puts it on
the clipboard as an image, the newest unless told; `/copy-image list` names
them. A picture on the server is fetched with your sign-in; one elsewhere on the
web without it. The Shell has no selection of its own: copying text is still
the terminal's, as before.

**The clipboard** is reached through the platform's own clients: wl-paste and
wl-copy (the wl-clipboard package) in a Wayland session, xclip on X11 or
XWayland - xsel moves only text - and on Windows PowerShell's Windows Forms
clipboard. Inside WSL it is Windows' clipboard, through `powershell.exe`, since
a picture copied in Windows is there and WSLg's Linux clipboard does not
reliably carry it; a file copied in Explorer arrives as its WSL path. `/version`
names the clipboard the Shell reaches, or why there is none. Under `java -jar` with a display and none of those, Java's AWT
clipboard is the last resort; the native binary carries no AWT and does not
need it. Where there is none - no tool installed, or no display at all, as
over ssh, where the clipboard is the other computer's - Ctrl+V and
`/copy-image` say which, and what to install. A copied picture is held by
wl-copy or xclip in a session of its own, so it outlives the Shell.

## Voice mode

`/voice` is the web Chat page's Voice panel in the terminal. It listens to the
microphone and, when you stop talking, sends what you said to the workspace's
transcription model and the words as a spoken turn (`voice: true` on the
stream, as the panel sends). The answer is shown as always and read aloud by
the workspace's speech model as it arrives, cut into sentences or paragraphs
as the workspace says; then it listens again. It never stops listening
between turns: what you say while the agent works waits under the spinner and
is the next turn, and Ctrl+S sends it into the running turn now.

| Key | Listening | While the answer is written or read |
|---|---|---|
| Space | your turn is over now | stop reading, and listen |
| Esc | leave voice mode | stop the answer - the server's turn too - and listen |
| M | mute, and again to unmute | |

When a turn ends, how loud a voice must be against the room, how long the
microphone may run unattended and how long talking over an answer takes to
stop it are the workspace's voice settings, the ones the browser uses, with
the same defaults (2.5 s, 250 %, 10 minutes, 1 s). A terminal has no echo
cancellation, so with speakers nothing is listened to while the answer plays
and a key cuts in; `/voice headphones` listens throughout, and talking over
the answer stops it. The workspace chooses both models: without a
transcription model voice mode says so and does not start, and without a
speech model the answers are shown and not read.

Sound is Java Sound - ALSA (with PulseAudio or PipeWire behind it) on Linux,
DirectSound on Windows - and the speech endpoint's MP3 is decoded by JLayer.
The microphone is recorded at 16 kHz mono and sent as WAV. The native binary
carries Java Sound's own library inside it and writes it to
`<config dir>/sound/<version>/` the first time, readable by you alone; on
Linux that library needs `libasound.so.2`. No microphone, or no audio output,
is said when voice mode starts. `ORKNUX_SHELL_MICROPHONE=<16 kHz mono .wav>`
hears that recording instead of the microphone, once, and then a quiet room:
how voice mode is driven where nobody can talk to it.

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

The native build carries the GraalVM JDK's `libjsound.so` (`jsound.dll` on
Windows) as a resource, for voice mode: `build-native.sh` and CI copy it into
`target/native-sound/` and hand that to `native-image` beside the jar. Its JNI
and service-loader needs are in `reachability-metadata.json`, traced with the
native-image agent over a real sound device.

CI (`.github/workflows/build.yml`) builds native binaries for linux-x64 and
windows-x64 and attaches them to a release on a `v*` tag. A release is the
version in `pom.xml`, tagged `v` and that version, with its section in
`CHANGELOG.md` written first; the job refuses a tag that differs from the pom
or has no section. With the `ORKNUX_SHELL_SIGNING_KEY` and
`ORKNUX_AI_PUBLISH_TOKEN` secrets set it also signs every file and offers the
release through orknux.ai, which is what `/update` installs from.
`ORKNUX_SHELL_TRACE=<file>` logs keys and phases, for a terminal that misbehaves.

## Pictures

`scripts/screenshot.py` photographs the Shell for the manual: it runs the
binary in a pseudo-terminal, feeds it keys, and draws the screen as a terminal
would into a PNG, so the pictures are taken on a machine with no display and
come out the same every time. `pip install pyte pillow`, then

```
ORKNUX_SHELL_CONFIG=... scripts/screenshot.py target/orknux-shell out/ \
    'wait:4' 'Reply with the single word: ok\r' 'wait:40' 'shot:shell-chat'
```

Steps are `wait:SECONDS`, `shot:NAME`, or keys with Python escapes (`\r`
Enter, `\x14` Ctrl+T, `\x1b[B` Down).
