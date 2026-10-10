#!/bin/sh
# Regenerates Packaging/AppIcon.icns from scripts/icon.swift. Only needed
# when the drawing changes; the .icns is committed so a build never
# depends on this step.
set -eu
cd "$(dirname "$0")/.."
ICONSET=build/AppIcon.iconset
rm -rf "$ICONSET"
swift scripts/icon.swift "$ICONSET"
iconutil -c icns "$ICONSET" -o Packaging/AppIcon.icns
rm -rf "$ICONSET"
echo "wrote Packaging/AppIcon.icns"
