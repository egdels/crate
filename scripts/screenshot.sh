#!/bin/sh
# Captures the main Crate window to a PNG, for checking the UI without a
# human in front of the screen. Prints the file path.
#
#   scripts/screenshot.sh [output.png]
#
# Needs two permissions for the app that hosts the terminal (Terminal,
# iTerm, IntelliJ, ...): Screen & System Audio Recording (for
# screencapture) and Accessibility (for System Events to read the window
# frame). Both under System Settings > Privacy & Security. Without them
# screencapture reports "could not create image" or osascript gets
# "not allowed assistive access".
set -eu
OUT="${1:-/tmp/crate-$(date +%H%M%S).png}"
if ! pgrep -xq Crate; then
    echo "Crate is not running" >&2
    exit 1
fi
FRAME=$(osascript -e 'tell application "System Events" to tell process "Crate"
    set p to position of window 1
    set s to size of window 1
    return (item 1 of p as text) & "," & (item 2 of p as text) & "," & (item 1 of s as text) & "," & (item 2 of s as text)
end tell')
screencapture -x -R "$FRAME" "$OUT"
echo "$OUT"
