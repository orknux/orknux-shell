#!/bin/sh
# Installs Orknux Shell on Linux from its GitHub release, checked against the
# release's SHA256SUMS before anything is put in place.
#
#   curl -fsSL https://raw.githubusercontent.com/orknux/orknux-shell/main/install.sh | sh
#   curl -fsSL .../install.sh | sh -s -- --version v1.0.0
#   curl -fsSL .../install.sh | sh -s -- --uninstall
#
# Installs into ~/.local/bin, or ORKNUX_SHELL_DIR when set. Needs no root: run
# it as the person who will use it. Settings and sign-in live in
# ~/.config/orknux/orknux-shell.json and are left alone by --uninstall unless --purge is
# given too.
set -eu

REPO="orknux/orknux-shell"
DIR="${ORKNUX_SHELL_DIR:-$HOME/.local/bin}"
VERSION=latest
UNINSTALL=no
PURGE=no

say() { printf '%s\n' "$*"; }
fail() { printf 'orknux-shell install: %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --version) [ $# -ge 2 ] || fail "--version needs a tag, e.g. v1.0.0"; VERSION="$2"; shift 2 ;;
    --version=*) VERSION="${1#--version=}"; shift ;;
    --dir) [ $# -ge 2 ] || fail "--dir needs a directory"; DIR="$2"; shift 2 ;;
    --uninstall) UNINSTALL=yes; shift ;;
    --purge) PURGE=yes; shift ;;
    -h|--help) sed -n '2,12p' "$0" 2>/dev/null || true; exit 0 ;;
    *) fail "unknown option $1" ;;
  esac
done

TARGET="$DIR/orknux-shell"

if [ "$UNINSTALL" = yes ]; then
  if [ -e "$TARGET" ]; then rm -f "$TARGET"; say "Removed $TARGET."; else say "Nothing at $TARGET."; fi
  if [ "$PURGE" = yes ]; then
    CONFIG="${ORKNUX_SHELL_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/orknux/orknux-shell.json}"
    if [ -e "$CONFIG" ]; then rm -f "$CONFIG"; say "Removed $CONFIG, with the sign-in it held."; fi
  fi
  exit 0
fi

# Only what CI builds: say so rather than install something that will not start.
case "$(uname -s)" in
  Linux) ;;
  MINGW*|MSYS*|CYGWIN*) fail "on Windows, run in PowerShell: irm https://raw.githubusercontent.com/$REPO/main/install.ps1 | iex" ;;
  *) fail "there is no build for $(uname -s) yet; the jar runs anywhere with Java 25: https://github.com/$REPO/releases" ;;
esac
case "$(uname -m)" in
  x86_64|amd64) ASSET=orknux-shell-linux-x64 ;;
  *) fail "there is no build for $(uname -m) yet; the jar runs anywhere with Java 25: https://github.com/$REPO/releases" ;;
esac

if command -v curl >/dev/null 2>&1; then
  get() { curl -fsSL --retry 3 -o "$2" "$1"; }
elif command -v wget >/dev/null 2>&1; then
  get() { wget -q -O "$2" "$1"; }
else
  fail "needs curl or wget"
fi
command -v sha256sum >/dev/null 2>&1 || fail "needs sha256sum to check the download"

if [ "$VERSION" = latest ]; then
  BASE="https://github.com/$REPO/releases/latest/download"
else
  case "$VERSION" in v*) ;; *) VERSION="v$VERSION" ;; esac
  BASE="https://github.com/$REPO/releases/download/$VERSION"
fi

# A mirror, or a test's own server: the folder that holds the asset and SHA256SUMS.
[ -n "${ORKNUX_SHELL_DOWNLOAD:-}" ] && BASE="$ORKNUX_SHELL_DOWNLOAD"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT INT TERM

say "Downloading Orknux Shell ($VERSION) ..."
get "$BASE/$ASSET" "$WORK/$ASSET" || fail "could not download $BASE/$ASSET - is there a published release$( [ "$VERSION" = latest ] || echo " $VERSION")? See https://github.com/$REPO/releases"
get "$BASE/SHA256SUMS" "$WORK/SHA256SUMS" || fail "could not download $BASE/SHA256SUMS"

WANTED="$(awk -v f="$ASSET" '$2 == f || $2 == "*"f { print $1 }' "$WORK/SHA256SUMS")"
[ -n "$WANTED" ] || fail "SHA256SUMS does not list $ASSET"
GOT="$(sha256sum "$WORK/$ASSET" | awk '{ print $1 }')"
[ "$WANTED" = "$GOT" ] || fail "the download does not match its checksum (wanted $WANTED, got $GOT); nothing was installed"

mkdir -p "$DIR"
chmod 755 "$WORK/$ASSET"
# Moved into place in one step, so a running copy is never half-overwritten.
mv -f "$WORK/$ASSET" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"

INSTALLED="$("$TARGET" --version 2>/dev/null || true)"
[ -n "$INSTALLED" ] || fail "installed $TARGET, but it does not start here (it needs a recent glibc: Ubuntu 24.04, Debian 13, Fedora 40 or newer)"
say "Installed $INSTALLED at $TARGET."

case ":$PATH:" in
  *":$DIR:"*) say "Run: orknux-shell" ;;
  *)
    say ""
    say "$DIR is not on your PATH. Add it, then open a new terminal:"
    say "  echo 'export PATH=\"$DIR:\$PATH\"' >> ~/.profile"
    say "Or run it as: $TARGET"
    ;;
esac
