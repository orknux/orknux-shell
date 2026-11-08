#!/usr/bin/env python3
"""
Photographs Orknux Shell for the manual: runs the binary in a pseudo-terminal,
feeds it keys, and draws the screen as a terminal would - colours, bold, the
status bar - into a PNG. No terminal emulator window is involved, so it runs
on a machine with no display and the picture is the same every time.

    pip install pyte pillow
    ORKNUX_SHELL_CONFIG=... scripts/screenshot.py target/orknux-shell out/ \
        'wait:4' 'Reply with the single word: ok\\r' 'wait:40' 'shot:shell-chat'

Steps: `wait:SECONDS`, `shot:NAME` (writes NAME.png into the output directory),
anything else is typed as given with Python escapes (`\\r` Enter, `\\x14` Ctrl+T,
`\\x1b[B` Down). The Shell is started with ORKNUX_SHELL_UPDATES=off so the
picture never says a newer version is out.
"""
import fcntl
import os
import pty
import select
import struct
import sys
import termios
import time

import pyte
from PIL import Image, ImageDraw, ImageFont


class Terminal(pyte.Screen):
    """pyte with CSI S and T - scroll the region up and down - which the Shell uses to pull the screen down to
    the status bar and pyte leaves undone, so the rows it moved stayed where they were."""

    def scroll_up(self, count=None, **_):
        y = self.cursor.y
        self.cursor.y = self.margins.bottom if self.margins else self.lines - 1
        for _ in range(count or 1):
            self.index()
        self.cursor.y = y

    def cursor_down(self, count=None, **_):
        # pyte clamps every move to the region's bottom, even from below it: the status bar, written under the
        # rule with a newline, landed inside the region and was scrolled away. Below the region, the screen's edge.
        top, bottom = self.margins or pyte.screens.Margins(0, self.lines - 1)
        limit = bottom if self.cursor.y <= bottom else self.lines - 1
        self.cursor.y = min(self.cursor.y + (count or 1), limit)

    def scroll_down(self, count=None, **_):
        y = self.cursor.y
        self.cursor.y = self.margins.top if self.margins else 0
        for _ in range(count or 1):
            self.reverse_index()
        self.cursor.y = y

ROWS, COLS = 30, 110
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"
SIZE = 15
PAD = 28
BACKGROUND = (12, 12, 14)
FOREGROUND = (228, 228, 231)
# The sixteen named colours as a dark terminal draws them.
NAMED = {
    "black": (12, 12, 14), "red": (239, 68, 68), "green": (34, 197, 94), "brown": (234, 179, 8),
    "blue": (96, 165, 250), "magenta": (192, 132, 252), "cyan": (34, 211, 238), "white": (228, 228, 231),
    "brightblack": (113, 113, 122), "brightred": (248, 113, 113), "brightgreen": (74, 222, 128),
    "brightbrown": (250, 204, 21), "brightblue": (147, 197, 253), "brightmagenta": (216, 180, 254),
    "brightcyan": (103, 232, 249), "brightwhite": (250, 250, 250),
}


def colour(value, fallback):
    if value in (None, "default"):
        return fallback
    if value in NAMED:
        return NAMED[value]
    try:
        return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))
    except (ValueError, TypeError):
        return fallback


def draw(screen, path):
    font = ImageFont.truetype(FONT, SIZE)
    bold = ImageFont.truetype(FONT_BOLD, SIZE)
    left, top, right, bottom = font.getbbox("M")
    cell_w, cell_h = right - left, int((bottom - top) * 1.45)
    image = Image.new("RGB", (COLS * cell_w + 2 * PAD, ROWS * cell_h + 2 * PAD), BACKGROUND)
    pen = ImageDraw.Draw(image)
    for y in range(ROWS):
        row = screen.buffer[y]
        # The status bar's rule is drawn in the terminal's line-drawing set, which pyte leaves as the letters sent.
        rule = sum(1 for x in range(COLS) if row[x].data == "q") > COLS // 2
        for x in range(COLS):
            char = row[x]
            if rule and char.data == "q":
                char = char._replace(data="─")
            px, py = PAD + x * cell_w, PAD + y * cell_h
            bg = colour(char.bg, BACKGROUND)
            fg = colour(char.fg, FOREGROUND)
            if char.reverse:
                fg, bg = bg, fg
            if bg != BACKGROUND:
                pen.rectangle([px, py, px + cell_w, py + cell_h], fill=bg)
            if char.data and char.data != " ":
                pen.text((px, py + 2), char.data, font=bold if char.bold else font, fill=fg)
    image.save(path)
    print(f"wrote {path}", file=sys.stderr)


def main():
    binary, out = sys.argv[1], sys.argv[2]
    steps = sys.argv[3:]
    os.makedirs(out, exist_ok=True)
    env = dict(os.environ, TERM="xterm-256color", COLORTERM="truecolor", ORKNUX_SHELL_UPDATES="off")
    pid, fd = pty.fork()
    if pid == 0:
        # The size before the Shell starts: set after, it has read the default by the time the call lands.
        fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", ROWS, COLS, 0, 0))
        os.execve(binary, [binary], env)
    screen = Terminal(COLS, ROWS)
    stream = pyte.ByteStream(screen)
    stream.csi["S"] = "scroll_up"
    stream.csi["T"] = "scroll_down"
    raw = open(os.environ["ORKNUX_SHOT_RAW"], "ab") if os.environ.get("ORKNUX_SHOT_RAW") else None
    # The Shell asks where the cursor is before each prompt; a terminal answers.
    screen.write_process_input = lambda data: os.write(fd, data.encode())

    def pump(seconds):
        end = time.time() + seconds
        while time.time() < end:
            ready, _, _ = select.select([fd], [], [], 0.05)
            if ready:
                try:
                    data = os.read(fd, 65536)
                except OSError:
                    return
                if raw:
                    raw.write(data)
                stream.feed(data)

    pump(3)
    for step in steps:
        if step.startswith("wait:"):
            pump(float(step[5:]))
        elif step.startswith("shot:"):
            pump(0.5)
            draw(screen, os.path.join(out, step[5:] + ".png"))
        else:
            os.write(fd, step.encode().decode("unicode_escape").encode())
            pump(0.4)
    os.kill(pid, 9)


if __name__ == "__main__":
    main()
