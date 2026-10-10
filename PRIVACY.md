# Privacy Policy

*Crate for macOS. Last updated 2026-10-09.*

Crate does not collect, store, transmit or share any personal data. There
is no account, no analytics, no crash reporting, no advertising and no
telemetry. The app never opens a network connection of its own; it only
reads the music files in the folders you point it at, which may be on a
network share you have mounted yourself.

## What stays on your Mac

- **The library index**: the paths, tags and durations of your music
  files, so that the app does not have to read every file again on each
  launch. It is one JSON file in
  `~/Library/Application Support/Crate/library.json`.
- **Cover thumbnails**: scaled-down copies of the album covers embedded
  in your files or lying next to them, in
  `~/Library/Application Support/Crate/Artwork/`.

Deleting that folder removes everything Crate has ever written. The music
files themselves are never modified, copied or moved.

## What the system sees

Crate hands the title, artist, album and cover of the track that is
playing to macOS so that the media keys, Control Center and the Now
Playing widget work. That information stays on your Mac and is handled by
the system the same way as for any other player.

## Changes and contact

This policy changes only if the app's behaviour changes, and such a
change will be noted in `CHANGELOG.md`. Questions go to the project's
issue tracker.
