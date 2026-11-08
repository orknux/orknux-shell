# Changelog

Each release's section is its notes, on GitHub and on orknux.ai, and is what
`/update` shows before it installs. Written before the tag: the release job
refuses a tag with no section here.

## 1.0.3

### Added

- `/update` and `orknux update`: the newest Orknux Shell from orknux.ai,
  installed in place. A download is put there only when its size, its sha256
  and its signature check out - each release is signed with Orknux's release
  key, which this program carries - and only when it is newer; anything else
  leaves the installed program as it was. Restart to use it.
- Once a day, a chat looks for a newer version in the background and says so
  in one line. It never updates on its own. `ORKNUX_SHELL_UPDATES=off`, or
  `"updates": false` in the configuration, turns the look off.
- `/agent`, `/workspace`, `/skill`, `/resume`, `/subagents` and `/effort`
  without an argument are lists you choose from with the arrow keys and Enter.
- A mistyped command (`/agnet`) is said to be unknown, with the nearest one,
  instead of being sent to the agent; `/agents`, `/workspaces`, `/skills`,
  `/chats` and `/exit` work as other spellings.

### Changed

- The prompt sits on the last row above the status bar, and the screen is
  written from the foot up, as in Claude Code.
- A blank line between your message, the agent's work and its answer.
- The command list that opens on `/` is in Orknux's colours, closes with Esc
  or as soon as what you type matches nothing, and lists the skills as `/key`.
- The binary is 35 MB instead of 44, and starts as fast.
- The installers retry a failed download and fall back to GitHub's API when
  the release's download address fails.

### Fixed

- Ctrl+C during a turn interrupted it with a stack trace on the screen.

## 1.0.1 and earlier

See the [GitHub releases](https://github.com/orknux/orknux-shell/releases).
