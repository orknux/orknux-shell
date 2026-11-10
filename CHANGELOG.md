# Changelog

Each release's section is its notes, on GitHub and on orknux.ai, and is what
`/update` shows before it installs. Written before the tag: the release job
refuses a tag with no section here.

## 1.0.44

### Added

- The welcome lines and `/help` name the key that pastes a picture where the
  Shell runs: Alt+V in Windows Terminal (in PowerShell and in WSL), which keeps
  Ctrl+V for its own text-only paste, and Ctrl+V everywhere else.

## 1.0.43

### Fixed

- Pasting on Windows said "The clipboard holds nothing to paste" whatever it
  held: Java on Windows does not escape the double quotes in a command-line
  argument, so PowerShell was handed a broken script, and its failure read as
  an empty clipboard. The script now goes as `-EncodedCommand`, and a
  PowerShell that fails is said as one. A test runs the real PowerShell on the
  Windows build so this cannot pass unnoticed again.

## 1.0.42

### Fixed

- The Windows build found no clipboard - "PowerShell was not found on the
  PATH" - so pasting and `/copy-image` did nothing: Windows spells the search
  path `Path`, and the Shell looked only for `PATH`. It is found however it is
  spelled now, and Windows PowerShell in its usual place where it is not on it.

## 1.0.41

### Added

- `/paste <message>` attaches the picture on the clipboard and sends the
  message with it at once: `/paste what is this?`.

### Changed

- A blank row between the line being written and the status bar, so the
  prompt is not pressed against the bar's rule.

## 1.0.40

### Fixed

- Attaching a file or a picture no longer eats the blank line under what was
  said or draws over the status bar's rule: what goes with the next message is
  written into the rule, so the bar stays two rows tall.

## 1.0.39

### Added

- `/paste` attaches the picture on the clipboard to the next message with no
  key pressed, for a terminal that keeps Ctrl+V for its own paste - Windows
  Terminal does, and its paste carries text only, so a copied picture never
  reached the Shell by Ctrl+V or Ctrl+Shift+V there.
- `/version` (and `orknux version`) says this Shell's version, the server it
  is talking to, the clipboard it reaches and the terminal it runs in.

### Fixed

- Pasting a picture in WSL: the Shell read WSL's Linux clipboard, which does
  not reliably carry a picture copied in Windows. Inside WSL it now reads and
  writes Windows' own clipboard through `powershell.exe`, and a file copied in
  Explorer arrives as its WSL path.

## 1.0.38

### Added

- Voice mode: `/voice` (or `/talk`) talks to the agent instead of typing to
  it, as the Voice panel on the web Chat page does. It listens to the
  microphone, sends what you said when you stop - the workspace's
  transcription model writes it down - and reads the answer aloud with the
  workspace's speech model as it is written, sentence by sentence, then
  listens again. What you say while the agent works waits under the spinner
  and is the next turn (Ctrl+S sends it now). Space ends your turn early or
  cuts the reading short, M mutes, Esc stops the answer - on the server too,
  as the panel's stop does - or, while listening, leaves voice mode. The
  workspace's turn-taking settings (the pause that ends a turn, how loud a
  voice is against the room, barge-in) are the ones the browser uses.
- `/voice headphones` keeps listening while the answer is read, so talking
  over it stops it. With speakers there is no echo cancellation in a
  terminal, so the Shell does not listen to itself: Space or Esc cuts in.
- A pairing link from Orknux (Preferences › Connected clients › Connect a
  client) is taken where the Shell asks for a server, and as `orknux <link>` or
  `orknux login <link>`: the Shell trades it for a credential of its own and
  signs in with no address or password typed; a code alone pairs with the
  server it already knows. Where the server lets paired clients through only
  the clients' own doors, which the Shell's chat is not, it says so and asks
  for a username and password for the link's server instead.
- A workspace with no transcription model, one with no speech model (answers
  are then shown and not read), and a computer with no microphone or no audio
  output are each said in words when voice mode starts.
- Pictures pasted into the chat: Ctrl+V (or Alt+V) attaches a picture on the
  clipboard to the next message, shown in the line as a chip like
  `[image 1: 1280×720 png]` that Backspace takes out; files copied in a file
  manager and a pasted or dragged path to a picture do the same. It uploads to
  the workspace's attachments and goes with the message by id, as the web chat
  sends one.
- `/copy-image [n|list]` copies a picture an answer showed - drawn, put on the
  chat as a file, or in its markdown - to the clipboard as an image; each one
  is numbered under its answer.
- The clipboard is wl-clipboard on Wayland, xclip on X11 and PowerShell on
  Windows, with Java's AWT clipboard last under `java -jar`; where there is
  none, Ctrl+V and `/copy-image` say what is missing.

### Changed

- The release binaries carry Java Sound's small native library (`libjsound.so`,
  `jsound.dll`) inside them and write it out once, beside the configuration
  (`sound/<version>/`), the first time voice mode is used. On Linux it needs
  ALSA's `libasound.so.2`, which every desktop has.

## 1.0.37

### Fixed

- The Shell names the agent it will talk to from the first screen - the one
  last used here, or else the one your newest chat was with - where it used to
  say "agent" until the first answer came, which read as an agent called that.
- Closing the workspace list at the start no longer leaves blank rows above
  the banner for the rest of the conversation.

## 1.0.36

### Fixed

- The Linux binary runs on glibc 2.17 and newer - RHEL 7, Debian 8, Ubuntu
  14.04 and everything since - where it used to need 2.34 (Ubuntu 22.04,
  Debian 12, RHEL 9) and refused to start on anything older. It is built in
  GraalVM's Oracle Linux 8 image now rather than on the CI runner's Ubuntu,
  whose glibc it inherited.
- When the installed binary does not start, the installer prints the binary's
  own error - the loader naming the glibc version it misses, most often -
  together with what the binary needs and the jar as the way round, instead of
  a guess that named too new a glibc.
- Installing is `curl -fsSL https://orkx.ai/install/shell.sh | sh` and
  `irm https://orkx.ai/install/shell.ps1 | iex`: the installers orknux.ai
  serves from the newest release, at the site's short address.

## 1.0.35

### Fixed

- A followed subagent was declared finished a few seconds in, while it
  worked on: the Shell's own poll never asked the server whether it was
  working, and took the silence for no. With that, its live line vanished -
  "Reading the prompt" among it - and the typing line said it had finished.
  Both stay now for as long as the server says it works.
- A message sent into a subagent's turn is shown once more as "✓ read: …"
  when it has read it, not as a second copy of the message.
- A followed subagent's thinking streams live under its spinner where the
  details are expanded, as this chat's own does, and is written into the
  transcript only once it is done - written as it began, it stayed as its
  first words.
- A subagent followed after this chat's own turn has ended is followed all
  the same: its spinner, its typing line and Enter into its turn, until it
  finishes or Ctrl+T, Enter goes back. It used to be lines appearing above a
  prompt, with nothing to say what it was doing or where to write to it.

## 1.0.34

### Fixed

- A blank line above a message sent into the turn, as below it.
- A followed session's thoughts say how long they took - "Thought for 75s" -
  as this chat's do, rather than a bare "Thought" that read as silence while
  the model worked on a big page.

## 1.0.33

### Changed

- On a subagent's screen what you write goes to that subagent and nowhere
  else: Enter and Ctrl+S both send it into its turn now, where it reads it
  at its next step; nothing is queued there, and with nothing typed Ctrl+S
  sends nothing. Where it has finished, the Shell says so and sends nothing.
  Messages queued for this chat are shown as its while you are there.
- "Waiting for the model" where that is all the Shell knows - after a tool
  answers, before the model has said anything - rather than "Thinking": a
  call queued behind another agent's at the model server stood as thinking
  for a minute while the model had not begun on it. "Thinking" is said once
  the model thinks.
- A message sent into the turn is counted beside the spinner - "1 waiting to
  be read at its next step" - until the agent reads it, which is written as
  "✓ read: …". The agent reads its inbox only when a model call returns, so
  a call queued behind a subagent's big prompt is why a message waits.
- A followed session says exactly whether its agent is working, from the
  server, rather than guessing from its transcript: nothing is drawn once it
  has finished, and the status bar says "finished". Needs a server with
  `working` on sessions.

## 1.0.32

### Changed

- Send now (Ctrl+S) while a subagent's session is on the screen sends what
  you typed into that subagent's turn, where it reads it at its next step -
  the agent you are watching is the one being steered. Enter still queues for
  this chat's main session. Where the subagent is not working on anything,
  the Shell says so and sends nothing. Needs a server with
  `postSessionMessageDuringTurn`.

### Fixed

- A blank line after "sent now", before the agent's next step.

## 1.0.31

### Added

- `/config` shows the Shell's shortcuts - expand (Ctrl+O), sessions (Ctrl+T),
  send now (Ctrl+S) - and changes one: choose it and press the new key, or
  `/config send-now ctrl+g`; `/config send-now default` puts it back. Ctrl and
  a letter only, and not one the terminal or the line already uses. Kept in
  the configuration file, and every hint says the key that is set.

### Changed

- Sending into the running turn is Ctrl+S, not Alt+Enter, which is Windows
  Terminal's full screen. The hint is beside what is queued, and the typing
  line says where a message goes.
- A followed session's live line is drawn as this chat's own: "Thinking ·
  12s", "Running http_get: …", "Preparing a tool call: …", "Writing the
  answer · 80 chars", from what the server says its model is doing - and
  nothing once it has finished, as a finished turn draws nothing.

### Fixed

- A message queued while a subagent's session is on the screen goes to this
  chat's main session, as it always did - and is now shown there: the screen
  goes back to this chat as it is sent, rather than writing it under the
  subagent's lines as if it had been sent to it.

## 1.0.30

### Fixed

- Ctrl+O and a resize keep the welcome at the top of the window, the
  conversation at the foot over the status bar and the gap between, as the
  start lays them out; the screen written again used to pull the welcome down
  with the rest.

## 1.0.29

### Fixed

- A followed session is drawn as this chat's own: a blank line between its
  steps, a tool call on one line - "✓ http_get …" - and Ctrl+O writing each
  out whole, rather than "● name {json}" lines run together.
- /sessions and Ctrl+T leave nothing behind: "Sessions of this chat" is the
  list's own title, and goes with it.
- After the screen is written again - Ctrl+O, a resize, going back to this
  chat - the live block keeps its blank line above it, and lines that came
  while a list was open are not written twice.
- A followed session that has finished shows a tick, not a spinner.

## 1.0.28

### Fixed

- /orkx-web-session opens the session on the screen: the subagent's while one
  is followed, rather than always this chat's own.

## 1.0.27

### Added

- Alt+Enter while the agent works sends what you typed into the turn now,
  rather than queuing it for when the turn ends: the agent reads it at its
  next step, as you speaking, and can change course without being stopped.
  With nothing typed, Alt+Enter sends everything queued now. Enter still
  queues. A chat that cannot be told anything mid-turn - My Claude Code -
  says so, and the message stays queued.

## 1.0.26

### Added

- My Claude Code in /agent, where the server offers it, said whether your
  Claude Code is connected: the conversation is answered by your own Claude
  Code, through the Orknux channel, as in the browser.

### Changed

- A subagent chosen in /sessions or with Ctrl+T is followed on its own screen:
  its lines so far, then what it does as it does it; the live block says
  what it is at, and the status bar what it has spent. This chat's own turn
  goes on unseen meanwhile, and Ctrl+T, Enter - or sending a message - goes
  back to it, everything that came meanwhile in place.

### Fixed

- Choosing from Ctrl+T's list no longer leaves a "› ~" behind.

## 1.0.25

### Fixed

- The status bar's output count moves while the model writes - its answer,
  its thinking and its tool calls - from the text as it streams, about four
  characters a token, until the real count replaces the guess at the end of
  the round. A long answer used to leave it standing still to the end.
- Opened during a turn, /sessions and Ctrl+T have their heading and the line
  above it again: they were held back until the list closed.
- While another session is being read, the live block says whose turn it is -
  "Browser helper (main): Running agent_wait" - since it is this chat's, not
  the session on the screen.
- A tool call in a session read here with no name recorded says "tool"
  rather than nothing.

## 1.0.24

### Changed

- agent_create and ask_agent are shown as every other tool call again, with
  what they answered: 1.0.23's shortened lines are taken back.

## 1.0.23

### Changed

- Making and asking agents fold to what was done: "✓ agent_create greeter ·
  tools http_get, web_search", "✓ ask_agent asked web-fetcher · Fetch live
  data from three sites" - not the JSON that said so, which Ctrl+O still
  shows whole.
- /sessions puts the chat's own session first, as "(main)", whichever one is
  being read; the agents it asked and the side chats follow, active ones
  first.

### Fixed

- The status bar's tokens grow while a turn runs, from what its session has
  spent so far, rather than only when it ends; and the count of subagents
  follows as agents are asked during the turn.
- The cursor no longer shows at the end of the typing line after a list or a
  command run during a turn; it had to be typed away.

## 1.0.22

### Fixed

- /sessions says an active session in green and an idle one in grey, and the
  row highlighted in any list keeps its colours, in bold - it used to be all
  white, a status included.

## 1.0.21

### Changed

- A newer Shell is said in the welcome at the top, under the /help line -
  "Orknux Shell 1.0.22 is out - /update to install it." - rather than on its
  own line above the prompt whenever the look came back. Found later, while
  the Shell runs, it is said in the status bar only.

## 1.0.20

### Changed

- The status bar says "update to 1.0.21 · /update" where a newer Shell is out,
  rather than "update 1.0.21".
- /help lists Ctrl+O as expand and fold, and Ctrl+T and Ctrl+L.

## 1.0.19

### Fixed

- Ctrl+L clears the screen with the prompt at the foot, over the status bar,
  as the Shell draws it - the line editor's own clear left the prompt on the
  top row and every command's answer hanging from there. The Shell also
  waits longer for the terminal to say where the cursor is before each
  prompt, which is how it puts the prompt back at the foot after a list.
- The welcome line names Ctrl+O as what it now is - expand and fold - and
  Ctrl+T.

## 1.0.18

### Added

- A command typed while the agent works runs at once where it can - /usage,
  /sessions, /subagents, /mode, /attach, /open, /thinking, /effort, /rules,
  /help, /update and the /orkx-web ones - written into the conversation with
  its answer, the live block set aside meanwhile. One that would start a
  turn or change the conversation - /clear, /resume, /agent, /review and the
  like - is still queued for when the turn ends, and the list under a / says
  which Enter will do.

### Fixed

- The line for typing during a turn stays directly above the status bar when
  the list of commands closes or a queued message is sent, instead of being
  left higher with empty rows under it.

## 1.0.17

### Added

- The status bar counts this chat's subagent sessions - "2 subagents Ctrl+T" -
  and Ctrl+T opens the list /sessions shows, at the prompt or while the agent
  works; Ctrl+T again closes it.

### Changed

- A session chosen in /sessions is read here, in the terminal, not opened in
  the browser: its transcript written out, and a line saying what you write
  still goes to this chat. /sessions afterwards lists the same family, from
  this chat's session, the one being read marked and highlighted; choosing
  this chat takes you back to it. /orkx-web-session still opens the log in
  the browser.
- The Shell looks for a newer version at every start and then every hour,
  rather than once a day: a version released in the morning went unsaid all
  day, through restarts.

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
