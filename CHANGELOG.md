# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). The release workflow takes a
version's section from this file as the GitHub release notes.

## [0.10.0] - 2026-10-16

### Added

- Lyrics also come from a `.txt` file with the track's name beside it
  or in a `lyrics` folder, as plain text; an `.lrc` still wins when
  both exist. Rips often bring these along.

### Changed

- Android: the loudness analysis decodes in batches of frames where
  the platform's decoders allow it (Android 15 and later) and measures
  up to four files at once on a phone with the cores for it. On a
  Pixel 7a that is about 90 files a minute instead of one: a
  collection of 4,500 files is done in under an hour with the app open
  or playing, instead of days.
- Android: the analysis keeps running when you leave the app with
  Back and while music plays in the background; it only pauses when
  the system puts the idle app to sleep, and goes on when you return.

### Fixed

- Android: the footer counted measured files in library order, so one
  long file at the front kept it at "0 of n" for minutes although files
  behind it were being measured. It now counts files as they finish,
  and a file that takes longer than it plays is given up and tried on
  the next run.

## [0.9.0] - 2026-10-14

### Added

- Crate measures the loudness of files that carry no ReplayGain tags
  itself, in both apps: EBU R 128 (ITU-R BS.1770-4, what ReplayGain
  2.0 asks for), decoded in the background after a scan, a few files at
  a time, the line under the grid counting along; a quit or a scan
  interrupts it and the next run picks up where it was. The results go
  to `loudness.json` next to the index (`PRIVACY.md` lists it); the
  files are not touched. Playback then uses the measured track gain, or
  the album gain gated over all of an album's tracks when every one of
  them is measured, before falling back to iTunes Sound Check. Checked
  against ffmpeg's ebur128 filter: identical integrated loudness. The
  Mac measures about six files a second, Android depends on the
  device's decoders and runs only while the app is open.
- `crate-scan --loudness` measures a folder and prints loudness, peak
  and gain per file, with the speed.

## [0.8.0] - 2026-10-14

### Added

- Lyrics, in both apps. When the track playing has them, a button
  appears in the player bar: Mac beside the heart (also ⌥⌘L, Playback
  > Show Lyrics), opening a pane beside the track list; Android next to
  the slider, opening a sheet. Crate looks for an `.lrc` file with the
  track's name beside it or in a `lyrics` folder first, then in the
  tags (USLT in MP3, ©lyr in M4A, LYRICS in FLAC). A timed `.lrc`
  follows the playback: the current line is bold and kept in view.
  Nothing is read until the track plays; the index does not change.
- `crate-scan --lyrics` counts the files of a folder that carry lyrics,
  by source.
- Artist view, in both apps: the artist's name in an album's header is
  a link to that artist's albums. It fills the search field with
  `artist:Name`, which narrows the grid to albums whose artist or album
  artist matches, nothing else; the prefix can be typed as well, and
  clearing the field brings everything back. Also in a track's context
  menu (Mac) and long-press menu (Android).
- Two more tiles in the grid, in both apps. Recently Added lists the
  newest files by their date, newest day first and an album copied in
  one go together. Recently Played lists what was played last, newest
  first, one entry per track; kept in `history.json` next to the index
  (`PRIVACY.md` lists it), a track counts when it starts playing. Both
  tiles play their list, and the search narrows them.

## [0.7.0] - 2026-10-14

### Added

- Up Next, in both apps: the list of what plays after the current
  track, behind the list icon in the player bar (Mac: also ⌘U). A
  double-click (Mac) or tap (Android) plays an entry now, the × takes
  it out, Clear empties the list. Right-click (Mac) or press and hold
  (Android) a track or an album for Play Next and Play Last; with
  nothing playing, they start right away.
- Android: the media notification has the heart, so a track can be
  marked from the lock screen; the app's hearts follow at once.
- Both apps remember where playback stood and come up there on the
  next launch: the same queue, the same track at the same position,
  paused. Kept in `queue.json` and `position.json` next to the index
  (`PRIVACY.md` lists them); the queue file is rewritten only when the
  queue changes, the position every few seconds of playback. Android
  also answers the system's playback resumption (its media controls
  after the app was gone) from the same files.
- Multi-disc albums show a "Disc n" line before each disc's tracks on
  the album page, in both apps. Albums with one disc look as before.

### Fixed

- Android: turning shuffle on left the current track somewhere in the
  player's random order, so only the tracks drawn after it followed;
  now the current track leads and all the others follow. Tracks queued
  with Play Next while shuffled do play next.

## [0.6.3] - 2026-10-14

### Fixed

- Mac: the 0.6.0 to 0.6.2 DMGs crashed within a minute of playing a
  track with a cover (`EXC_BREAKPOINT` on MediaPlayer's queue). The
  closure that hands the cover to Now Playing inherited the main actor,
  MediaPlayer calls it on its own queue, and the Swift runtime's
  isolation check trapped; the compiler on the release builder emits
  that check, the one used for local builds does not, which is why the
  crash never showed up in testing. The closure is nonisolated now.

## [0.6.2] - 2026-10-14

0.6.0 and 0.6.1 of the same day are withdrawn: their Android builds
could not play files without a loudness tag. This version carries
everything they brought, plus the fix.

### Added

- Favorites, in both apps: a heart on every track row, on the album
  page (for the whole album) and in the player bar; a Favorites tile in
  the grid lists them in album order and plays them; the search narrows
  them like everything else. Mac: Playback > Add to Favorites (⌘L) and
  the row's context menu. The hearts are kept per device in
  `favorites.json` next to the library index, a few kilobytes that are
  written on every change instead of the whole index; nothing is
  written into the music files. `PRIVACY.md` lists the new file
  (effective 2026-10-14).
- Albums show their genre in the header, and the search finds genre
  and composer ("jazz", "bach"), in both apps.
- Albums sort by the sort names in the tags when the files have them
  ("Beatles, The"), otherwise a leading "The" is ignored, so The Beatles
  file under B.
- The compilation flag taggers set (TCMP, cpil, COMPILATION) marks an
  album as Various Artists even when one artist is on most of its
  tracks, unless an album artist is tagged.
- Loudness levelling in both apps: tracks play at the level their
  ReplayGain tags describe (album gain when an album is playing, track
  gain otherwise), and iTunes Sound Check (`iTunNORM`) counts as the
  track gain when a file has no ReplayGain. An album whose files have
  only track gains plays at their mean, so the record lands on level
  without its own dynamics being flattened. Gains are limited by the
  tagged peak so nothing clips, and sit in a stage of their own, so the
  volume control behaves as before. No switch: files without tags play
  as they always did. Read from ID3 TXXX and COMM frames, iTunes
  freeform atoms and Vorbis comments.
- The library index is version 3 (composer, compilation flag, sort
  names, loudness gains). An index from an older version is kept and
  its files are read again once at the next launch, with the grid
  already up and the footer showing the progress.

### Changed

- Android targets API 37 (Android 17), the platform it is built and
  tested against; before, the app ran on Android 17 devices in the
  compatibility mode for API 36. Verified on the Pixel_9a emulator:
  launch with the saved library, album, playback, media notification,
  playback in the background, rescan, search, the folder picker,
  "open with", landscape and the help dialog.
- Help, both apps: how albums sort (a leading "The" is ignored, sort
  names in the tags count), and that an album keeps its dynamics (one
  correction for the whole record) while Shuffle and All Tracks level
  each track on its own.

### Fixed

- Android: the loudness stage stopped playback of every file without a
  loudness tag, which is most of them (0.6.0 and 0.6.1): it tried to
  copy an empty buffer into itself and the player reported "Unexpected
  runtime error". Tagged files played, which is why the check with
  ReplayGain test files missed it; untagged files are part of the check
  now.
- Android: the themed (monochrome) launcher icon showed a small record
  floating above a narrow crate. It is now the colour icon's silhouette,
  generated by `scripts/icon.swift` from the same geometry: the record
  standing in the crate, the rims behind it, three slats and the posts.

## [0.5.0] - 2026-10-12

### Changed

- Android: the window theme no longer sets the status and navigation bar
  colours; `enableEdgeToEdge()` already does, and the theme attributes
  are deprecated since Android 15 (flagged by the Play Console).
- Android: the APK no longer contains Media3's HTTP data source. Crate
  has no network permission and never used it, but the Play Console
  reported it as images being downloaded and decoded by hand. Embedded
  covers for the notification are now decoded with the same subsampling
  as the grid's thumbnails.

### Fixed

- With a large library and a search in the field, the Mac app kept
  filtering the whole library four times a second while playing (one
  CPU core at ~85 % with 18,000 tracks) and on every keystroke. The
  results are now computed once per change of the search or the
  library, off the main thread, from text prepared when the library
  loads.
- Android: typing in the search field lagged with a large library
  (about 0.75 s per letter with 13,000 tracks on a Galaxy A16): every
  letter filtered the library several times on the main thread. It
  now filters once per letter in the background, from text prepared
  when the library loads.
- Android: the search key on the keyboard did nothing; it now closes
  the keyboard and leaves the results in place.

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
