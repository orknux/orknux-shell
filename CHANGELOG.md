# Changelog

Each release's section is its notes, on GitHub and on orknux.ai, and is what
`/update` shows before it installs. Written before the tag: the release job
refuses a tag with no section here.

## 1.0.16

### Changed

- What you write stands apart from the rest: each message - and each command
  typed - is on a background a shade lighter than the terminal's, from the
  start of the line to the window's edge; in /resume's history and for the
  messages queued during a turn too. It is the theme's `message` colour,
  `/theme message #rrggbb` to change it.

## 1.0.15

### Added

- The list of commands opens under a / typed while the agent works, as it
  does at the prompt: Up and Down move through it, Tab takes the command to
  go on typing after it, Enter queues it for when the turn ends, and Esc
  closes the list - Esc with no list open still interrupts the turn.

### Changed

- "Reading the conversation" is now "Reading the prompt": what the model
  reads before it answers is the prompt - the part of it not already cached -
  not the conversation anew.

## 1.0.14

### Fixed

- A blank line between a command typed and what it answers, for every
  command, as between a message and its answer; between a heading and the
  list to choose from under it (/sessions, /resume, /workspace, /agent and
  the rest); and between /help's commands and its keys.

## 1.0.13

### Changed

- Ctrl+O unfolds the conversation in place: every thought written out whole
  and every tool call with all it was given and answered - the ones already
  on the screen as well as those to come, at the prompt or while a turn
  runs - and Ctrl+O again folds it back. The separate page 1.0.12 opened is
  gone. The screen is written again to do it, so the scrollback holds the
  view chosen.

### Fixed

- Resizing the window writes the conversation again at the new size, the
  status bar under it at the new width, rather than leaving the bar and the
  text where the old size put them.

## 1.0.12

### Added

- A newer Orknux Shell, once the daily look has found one, is said in the
  status bar beside the workspace - "update 1.0.13 · /update" - until /update
  installs it.

### Fixed

- The prompt is put back on the row directly above the status bar before each
  line, wherever something left it - a long list, a resize - so the live
  block and the line being typed during a turn are always just above the bar.
- Ctrl+O during a turn opens the turn so far in full - every thought, every
  tool call with all it was given and answered, the thinking as it streams -
  on a page that follows the turn as it goes, and stays open until Ctrl+O,
  Esc or q closes it, the conversation as it was. It used to show only what
  came after the key, and folded away when the thinking ended.
- A blank line between the messages queued and the line being typed.
- While the model writes its answer the spinner says "Writing the answer",
  not "Preparing a tool call".

## 1.0.11

### Added

- `/sessions` lists every session of this chat - its own, those of the agents
  it asked, and your @agent side chats - active ones first, each with its
  agent, what it was asked and what it spent; choose one to open its log in
  the browser.

## 1.0.10

### Added

- `/orkx-web-chat`, `/orkx-web-session` and `/orkx-web-agent-settings` open
  this chat, its session log and the agent's settings in Orknux in the
  browser, and print the address too.

## 1.0.9

### Fixed

- The workspace is at the right of the status bar again: 1.0.8 drew only the
  bar's left half.
- A blank line between the spinner and the messages queued and being typed
  under it.

## 1.0.8

### Changed

- The prompt and the status bar are at the foot of the window again, the
  prompt directly above the bar, the conversation growing up from there, as
  in 1.0.5. The workspace is at the bar's right and in the window's title.
- Ctrl+O during a turn shows its details: the thinking as it streams, and
  every tool call from then on written out in full - what it was given and
  all it answered. Again for one line each. The spinner's line says so.

### Fixed

- Ctrl+C did nothing in the turn an answer to the agent's question started,
  nor did Ctrl+O; that turn now takes the same keys as any other.
- A blank line above "Answered".

## 1.0.7

### Fixed

- "Reading the conversation" says how much of the prompt has been read: the
  server's count already holds the cached part, and 1.0.6 still added it
  while the reading was under way.
- A blank line between a question an agent asks and the answers to choose from.

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
