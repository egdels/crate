# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). The release workflow takes a
version's section from this file as the GitHub release notes.

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
