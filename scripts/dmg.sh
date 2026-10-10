#!/bin/sh
# Packages build/Crate.app (built by scripts/bundle.sh, which this runs
# first) into build/Crate-<version>.dmg with an Applications shortcut, the
# way the release workflow ships it. The version comes from
# Packaging/Info.plist, the single place it is defined.
#
#   scripts/dmg.sh [--universal]    # the flag is passed on to bundle.sh
set -eu
cd "$(dirname "$0")/.."

scripts/bundle.sh "$@"

VERSION=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' Packaging/Info.plist)
DMG="build/Crate-${VERSION}.dmg"
STAGING=build/dmg
rm -rf "$STAGING" "$DMG"
mkdir -p "$STAGING"
cp -R build/Crate.app "$STAGING/"
ln -s /Applications "$STAGING/Applications"
hdiutil create -volname "Crate ${VERSION}" -srcfolder "$STAGING" -ov -format UDZO -quiet "$DMG"
rm -rf "$STAGING"
echo "built $DMG"
