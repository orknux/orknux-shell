# Changelog

Each release's section is its notes, on GitHub and on orknux.ai, and is what
`/update` shows before it installs. Written before the tag: the release job
refuses a tag with no section here.

## 1.0.6

### Added

- Write while the agent works: what you type shows under the spinner, Enter
  queues it, and queued messages are sent one after another when the turn
  ends. A line begun and not sent waits in the next prompt.
- The command list under `/` is drawn by the Shell: Up and Down move through
  it, Tab completes, Enter runs the highlighted command, Esc closes it.

### Changed

- The conversation starts at the top of the window, and the prompt with the
  status line under it follows it down, as in Claude Code; the window is not
  empty above a prompt at its foot any more.
- The workspace is in the window's title, "Orknux · Private", and at the right
  of the status line.
- Each Shell says which it is, so its chats' commands run on its computer and
  not on another one of yours with a Shell open (with a server that knows).
- The command link stays quiet through a short outage, such as a server
  restart, and says when the server answers again.
- `/resume` shows the chat with room between its messages.
- The status line no longer says commands can run here, which is so whenever
  the Shell is open; it says when they cannot.

### Fixed

- "Reading the conversation" no longer goes past 100% with a server that
  counts the cached tokens in what it has processed.
- Resizing the window redraws the status line at the new width: while a line
  was being read the line editor held the resize signal, and it kept the old one.

## 1.0.5

### Added

- Ctrl+O at the prompt shows the last turn in full - every thought, and every
  tool call with all it was given and all it answered, a command's output as
  it printed - on a page of its own; Ctrl+O, Esc or q closes it, the
  conversation as it was. During a turn Ctrl+O shows the thinking live and
  hides it again.
- The status bar shows how fast the model writes (decode) and reads the
  prompt (eval), where its server reports them.

### Changed

- The workspace is at the right of the status bar; in a window under 160
  columns it has a row of its own above, with a rule between.
- "local" in the status bar says what it meant: "runs commands here" or "no
  commands here".
- The list of an answer's links under it is gone; `/open` with no number
  lists them.
- `/update` shows the release notes as they are written, headings and room
  between sections, and says where the rest is if they are long.

### Fixed

- A thought no longer says "(Ctrl+O to read)", which worked only for the
  last turn.
- What the model said while it worked came with newlines that left blank
  rows the screen had not counted: three blank lines before a tool call.
- After a line you typed, the blank line before the answer was dropped.
- Under WSL, `/open` opens a file in Windows.

## 1.0.4

### Added

- Questions an agent puts to you with ask_person are a list to choose from,
  with a row for your own words and Space to tick several where the
  question takes more than one. The agent's next turn follows as soon as you
  answer. Esc leaves a question open; `/answer` comes back to it.
- Tables in answers are drawn with borders, each column as wide as its widest
  cell, and narrowed to fit the window, a long cell wrapping onto more lines.
- A file path an answer names is a link that opens the file: Windows paths,
  Linux paths and `~/`, and under WSL the right Windows address for either.
- `/theme` is a list: choose a colour, type its new value, and it changes at
  once and is kept. `/theme <name> #rrggbb` and `/theme reset` too.

### Changed

- The banner stays at the top of the window; the prompt sits above the status
  bar, which has a rule across above it.
- A model's thinking is not written out live any more: "Thinking" says it is
  happening, and Ctrl+O reads it. `/thinking expanded` streams it as before.
- "Thinking" and "Thought" are in the thinking colour.
- A blank line between every piece of work - a thought, a tool call - and
  around an interruption, never two.
- A command run here is no longer said twice in a turn: the tool call's line
  says it, unless you approved it, its output was cut or it timed out.
- Down and Up move into the list of commands that opens on `/`.

### Fixed

- `/workspace` with one workspace failed with "Cannot coerce value to an
  empty range".
- A list you choose from no longer flashes on every arrow key.
- A refused command left no blank line before the next prompt.

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
