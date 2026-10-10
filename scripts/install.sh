#!/bin/sh
# Builds Crate and puts it into /Applications, replacing what is there,
# then tells Launch Services about the new copy. The default-app
# bindings for audio files point at /Applications/Crate.app, so this is
# the step after every change that should reach the double-click.
#
#   scripts/install.sh [--universal]    # the flag is passed on to bundle.sh
set -eu
cd "$(dirname "$0")/.."

scripts/bundle.sh "$@"

APP=/Applications/Crate.app
if pgrep -xq Crate; then
    osascript -e 'tell application "Crate" to quit' >/dev/null 2>&1 || true
    sleep 1
fi
rm -rf "$APP"
cp -R build/Crate.app "$APP"
LSREGISTER=/System/Library/Frameworks/CoreServices.framework/Versions/A/Frameworks/LaunchServices.framework/Versions/A/Support/lsregister
"$LSREGISTER" -f "$APP"
# The build copy must not compete for "Open With".
"$LSREGISTER" -u "$PWD/build/Crate.app" >/dev/null 2>&1 || true
echo "installed $APP"
