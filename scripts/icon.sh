#!/bin/sh
# Regenerates Packaging/AppIcon.icns and Packaging/AppIcon-256.png (the
# README picture) from scripts/icon.swift. Only needed when the drawing
# changes; both files are committed so a build never depends on this step.
set -eu
cd "$(dirname "$0")/.."
ICONSET=build/AppIcon.iconset
rm -rf "$ICONSET"
swift scripts/icon.swift "$ICONSET"
iconutil -c icns "$ICONSET" -o Packaging/AppIcon.icns
cp "$ICONSET/icon_256x256.png" Packaging/AppIcon-256.png
rm -rf "$ICONSET"
echo "wrote Packaging/AppIcon.icns and Packaging/AppIcon-256.png"
