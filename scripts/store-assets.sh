#!/bin/sh
# Builds the Google Play listing graphics under
# android/fastlane/metadata/android/en-US/images/ from scripts/icon.swift
# (icon and feature graphic) and from raw device screenshots in
# build/store/raw/ (framed with captions by scripts/store-screenshot.swift).
#
#   scripts/store-assets.sh
#
# The raw screenshots are not committed; capture them with
#   adb -s <serial> exec-out screencap -p > build/store/raw/<name>.png
# with the status bar in demo mode (see the Android notes in CLAUDE.md).
# Expected names: phone-1..6.png (1080 x 2424, Pixel 9a), tablet7-1..3.png
# (1920 x 1200), tablet10-1..3.png (2560 x 1600). Missing ones are skipped.
set -eu
cd "$(dirname "$0")/.."
RAW=build/store/raw
META=android/fastlane/metadata/android

mkdir -p "$META/en-US/images"
swift scripts/icon.swift build/store/iconset --play "$META/en-US/images" >/dev/null
rm -rf build/store/iconset
echo "wrote icon.png and featureGraphic.png"

# name | canvas | caption | subcaption
captions() {
cat <<'TABLE'
phone-1|1080x1920|Your music, your files|Pick a folder. Crate reads the tags.
phone-2|1080x1920|Albums the way your folders are|Covers from the files or the folder
phone-3|1080x1920|Gapless playback|Keeps playing with the screen off
phone-4|1080x1920|Lock screen and headset controls|Cover and controls where the system shows them
phone-5|1080x1920|Find it fast|Titles, artists and albums
phone-6|1080x1920|No account, no cloud, no ads|Nothing leaves your device
tablet7-1|1920x1080|Grid and tracks side by side|Your music, your files
tablet7-2|1920x1080|Gapless playback|Lock screen and headset controls included
tablet7-3|1920x1080|Find it fast|Titles, artists and albums
tablet10-1|2560x1440|Grid and tracks side by side|Your music, your files
tablet10-2|2560x1440|Gapless playback|Lock screen and headset controls included
tablet10-3|2560x1440|Find it fast|Titles, artists and albums
TABLE
}

captions | while IFS='|' read -r name canvas caption subcaption; do
    [ -f "$RAW/$name.png" ] || { echo "skip $name (no $RAW/$name.png)"; continue; }
    w=${canvas%x*}; h=${canvas#*x}
    case $name in
        phone-*)    folder=phoneScreenshots ;;
        tablet7-*)  folder=sevenInchScreenshots ;;
        tablet10-*) folder=tenInchScreenshots ;;
    esac
    n=${name#*-}
    swift scripts/store-screenshot.swift "$RAW/$name.png" "$META/en-US/images/$folder/$n.png" "$w" "$h" "$caption" "$subcaption"
    echo "framed $name"
done
