#!/bin/sh
# Builds Crate in release mode and wraps the binary into a plain,
# double-clickable build/Crate.app. No Xcode project involved: the app is
# a SwiftPM executable, and this script is the whole packaging step.
#
#   scripts/bundle.sh               # native architecture, for development
#   scripts/bundle.sh --universal   # arm64 + x86_64, what a release ships
#
# The bundle is ad-hoc signed (no Developer ID configured for this
# project), so a downloaded copy shows the usual Gatekeeper warning and
# has to be opened once via right-click > Open. Shipping it as a DMG keeps
# the ad-hoc signature intact, which a zip does not reliably do.
set -eu
cd "$(dirname "$0")/.."

if [ "${1:-}" = "--universal" ]; then
    set -- -c release --product Crate --arch arm64 --arch x86_64
else
    set -- -c release --product Crate
fi
swift build "$@"
# Where the product lands differs between toolchains (.build/release vs
# .build/apple/Products/Release), so ask rather than guess.
BIN="$(swift build "$@" --show-bin-path)/Crate"

APP=build/Crate.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/Crate"
cp Packaging/Info.plist "$APP/Contents/Info.plist"
cp Packaging/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
codesign --force --sign - "$APP"
echo "built $APP"
