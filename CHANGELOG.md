# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). The release workflow takes a
version's section from this file as the GitHub release notes.

## [0.4.0] - 2026-10-11

### Added

- Help > Crate Help opens a one-page help window: library, playing,
  keyboard shortcuts, Finder integration, formats and where the library
  lives. Help > Crate on GitHub opens the repository. Before, the item
  SwiftUI puts there by default answered with "Help isn't available for
  Crate".
- Android: "Help" in the overflow menu shows the same kind of page
  (folders, playing, opening files, formats, where the library lives),
  in the dialog the privacy policy already uses. The text is
  `app/src/main/assets/HELP.md`.

### Fixed

- Android: going back from an album to the grid returned to the top of
  the grid instead of where the album was. With a large library that
  meant scrolling down again every time.
- Android: turning the phone to landscape and back opened the library's
  first album instead of returning to the grid. The two-pane view now
  shows an album from the part of the grid that is on screen, and drops
  it again when the panes collapse; an album the user tapped stays open.
- Android: after a rotation the selected album's tile could sit cut off
  at the bottom of the grid or outside it; the grid now scrolls it into
  view.
- Both apps: the "pick a folder" start page flashed at launch while the
  saved library was still loading, as if no folder had been added.
- Mac: album grouping no longer runs on the main thread, so the window
  stays responsive while a library of tens of thousands of tracks is
  loaded or rescanned.

## [0.3.0] - 2026-10-10

### Added

- Google Play listing: texts, framed phone and tablet screenshots, icon
  and feature graphic under `android/fastlane/metadata/`, built by
  `scripts/store-assets.sh`.
- The release workflow also builds and signs the Android App Bundle and
  attaches `Crate-<version>.aab` to the release, for the Play Console.
- Android: "Privacy Policy" in the overflow menu shows the policy in a
  dialog. Google Play's User Data policy asks for the policy to be
  reachable from inside the app, not only from the listing; showing the
  text instead of a link keeps the app offline. The build copies
  `PRIVACY.md` from the repository root into the app's assets, so the
  policy is maintained in one place.

### Changed

- The privacy policy now names the publisher and a contact route, lists
  every Android permission with its reason, and states explicitly that no
  third party receives anything, as Google Play's user data policy asks.

## [0.2.0] - 2026-10-10

### Added

- An Android app in `android/`: the same product on a phone or tablet.
  Pick a folder with the system's folder picker, Crate scans it and
  reads the tags (MP3, M4A, Ogg and Opus through the platform, FLAC
  through its own parser), groups albums by folder and loosely compared
  tags exactly like the Mac app, shows covers from the files or the
  folder, searches, and plays through a media session with gapless
  transitions, lock screen and headset controls. Rescans are
  incremental, the library is one JSON file, and "Open with Crate" from
  a file manager plays the file. Kotlin, Jetpack Compose, Media3;
  Android 10 and later. The core library has JVM tests, the app was
  verified on an Android 17 emulator and a Pixel 7a.
- Audiobooks: `.m4b` files are scanned on both platforms. They are
  M4A files by another name, and whoever picks a folder wants to hear
  what is in it. Playback position is not remembered, as for any track.
- Android reads MP3 and M4A/M4B tags itself (ID3v2.2 to 2.4, ID3v1,
  MP4 atoms, the duration from the Xing/VBRI header or the bitrate)
  instead of through the platform's media retriever, and reads 16 files
  at once: a 900-file folder in the emulator went from 10.3 s to 2.2 s.
  The retriever stays for Ogg, Opus and WAV. A side effect: M4A files
  without a date no longer get the year 1904 from their container
  timestamp. On a Pixel 7a a 3,600-file collection scans in 26 s
  instead of over two minutes.
- Releases ship the Android APK next to the DMG: the release workflow
  signs it with the project's Android release key from the repository
  secrets, and the tag has to match the Android version too.
- Android lists folders concurrently, so a rescan that finds nothing
  changed takes 3.4 s instead of 8 s for that collection, and empty
  files (interrupted copies) are reported as unreadable without being
  opened.

## [0.1.0] - 2026-10-10

### Added

- Crate > Use Crate for Audio Files binds every audio type to Crate in
  one go, instead of Get Info > Change All per type. The command is
  greyed out once Finder already opens audio files with Crate.

- The app: an album grid with a track list, a player bar with transport,
  seek and volume, a folder picker, and menu commands for open, remove
  folder, rescan, play/pause, next, previous, shuffle and repeat.
- Album covers: embedded pictures (ID3, iTunes, FLAC) or a `cover.jpg` /
  `folder.jpg` next to the files, cached as thumbnails, shown on the
  tiles, in the album header and in the player bar.
- Now Playing and media keys: the keyboard, Control Center and the menu
  bar Now Playing panel show and control what Crate plays.
- Search across titles, artists and albums, and an All Tracks view with
  every track as one flat table.
- Gapless playback: the next track is queued on the audio engine behind
  the current one, so albums play without a pause between tracks.
- CrateCore package: folder scanning, AVFoundation tag reading, a
  persisted incremental library index, album grouping, a play queue with
  repeat and shuffle, and audio playback behind an `AudioPlayer`
  protocol.
- An app icon (records in a crate), drawn by `scripts/icon.swift`.
- Opening from Finder: Crate registers for audio files and folders.
  Double-clicking a file that is in the library plays its album from that
  track; files from elsewhere play as given without touching the
  library; a folder is added to the library. Works whether Crate is
  running or not.
- Scanning reads 16 files at once (network shares are latency-bound) and
  the footer counts the files read; covers fall back to any image in the
  album folder, and a read error no longer marks an album as cover-less.
- File > Show Unreadable File in Finder selects the files the last scan
  could not read.
- Albums are grouped by folder and album title, compared loosely, so a
  compilation without album artist, a title spelled two ways or a stray
  ";" in one artist tag no longer splits a folder into several tiles.
  Untagged tracks are named after their folder, and join the folder's
  album when it has exactly one. Disc subfolders belong to their parent.
- FLAC tags and covers are read by Crate's own parser instead of
  AVFoundation, which sees no Vorbis comments on macOS 12. Faster, too.
- Runs on macOS 12 Monterey and later (was 14): ObservableObject instead
  of Observation, NavigationView instead of NavigationSplitView, a
  WindowGroup that keeps to one window, a List for All Tracks.
- One window: closing it keeps the music playing, the Dock icon brings
  it back, and opening files never adds windows.
- Release tooling: `scripts/dmg.sh` builds a universal DMG; a tag push
  publishes it as a GitHub release with this file's section as notes.
