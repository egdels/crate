#!/bin/sh
# Regenerates Packaging/AppIcon.icns, Packaging/AppIcon-256.png (the
# README picture) and the Android launcher icons under android/app/src/main/res
# from scripts/icon.swift. Only needed when the drawing changes; all of them
# are committed so a build never depends on this step.
set -eu
cd "$(dirname "$0")/.."
ICONSET=build/AppIcon.iconset
rm -rf "$ICONSET"
swift scripts/icon.swift "$ICONSET" --android android/app/src/main/res
iconutil -c icns "$ICONSET" -o Packaging/AppIcon.icns
cp "$ICONSET/icon_256x256.png" Packaging/AppIcon-256.png
rm -rf "$ICONSET"
echo "wrote Packaging/AppIcon.icns, Packaging/AppIcon-256.png and the Android mipmaps"
