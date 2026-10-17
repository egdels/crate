# Crate

<img src="Packaging/AppIcon-256.png" width="128" height="128" alt="Crate app icon: a record pulled out of a wooden crate" align="right">

A music player for the Mac that opens and plays. Point it at a folder, it
reads the tags, and you listen. No account, no cloud, no store, no settings
screen.

Written in Swift, GPLv3, fully offline, no proprietary services. The
audio stack, the media keys and the Now Playing integration all come
straight from the system. There is an Android app with the same idea in
`android/`, written in Kotlin on top of the platform's own media stack;
see [Android](#android) below.

## What sets Crate apart

- **Your folders, not a media database.** Crate reads the folder you
  pick and nothing else. It does not rely on the system's media index,
  so files that index missed, tags it read wrong or albums it merged
  never reach Crate. On Android it needs no storage permission: the
  system's folder picker grants access to that one folder.
- **Albums as they lie on disk.** An album is a folder plus its title,
  compared loosely. A compilation without album artist, a title spelt
  two ways or a stray tag does not split an album; two albums of the
  same name in different folders do not merge; untagged files are named
  after their folder.
- **Even loudness, with or without tags.** ReplayGain and iTunes Sound
  Check tags are used where they exist. Every other file is measured in
  the background to EBU R 128 (ITU-R BS.1770, the same numbers as
  ffmpeg's ebur128 filter), so a collection without a single loudness
  tag still plays at an even level, album by album.
- **Lyrics from your own files.** A timed `.lrc` or a plain `.txt`
  beside the track or in a `lyrics` folder, or the lyrics tags; the line
  being sung follows the music. The apps fetch nothing from the
  internet; `scripts/lyrics.py` fills a collection with timed lyrics in
  one run you start yourself (see [below](#lyrics-for-a-collection)).
- **No settings.** What can be decided for you is decided: gapless
  playback, album gain when an album plays, resume where you stopped.
- **Fully offline.** No account, no telemetry, no network permission on
  Android.
- **One player, two native apps.** The Mac and the Android app share the
  album logic, the loudness measurement, lyrics, favorites and history,
  each built on its own platform's media stack.

## Status

This repository holds **CrateCore**, the UI-free Swift package, and the
**Crate** app on top of it, plus the Android app. Both work end to end
against a real collection: pick a folder, browse the album grid with
covers, search, mark favorites, queue tracks in Up Next, read lyrics,
play gapless at an even loudness, and control playback from the media
keys, the lock screen or Control Center. See `CHANGELOG.md` for the
releases.

## Download

Each release on the Releases page ships one universal DMG (Apple silicon
and Intel) for macOS 12 Monterey or later, and an APK for Android 10 or
later. Open it, drag Crate to
Applications, and start it once via right-click > Open: the app is
ad-hoc signed, there is no Developer ID behind it, and Gatekeeper asks
only that first time. Then pick a folder, or choose Crate > Use Crate for
Audio Files so that double-clicking a music file in Finder opens Crate.

What the package does:

- **Scan** a folder recursively for audio files (`LibraryScanner`)
- **Read tags** and duration with AVFoundation, FLAC with its own parser
  (`AVFoundationMetadataReader`, `FLACMetadata`)
- **Keep an index** of folders and tracks, persisted as one JSON file in
  Application Support, with incremental rescans: unchanged files are not
  re-read, removed files are dropped (`Library`, `JSONLibraryStore`)
- **Group albums** by folder and loosely compared tags (`Album`): a
  compilation without album artist, a title spelled two ways or a stray
  ";" in one artist tag does not split a folder into several albums, and
  untagged tracks are named after their folder
- **Queue** tracks with repeat, shuffle, play-next and removal (`PlayQueue`)
- **Play** through `AVAudioEngine`, with the next track scheduled behind
  the current one for gapless albums (`AudioPlayer`,
  `AVAudioEngineBackend`; `AVAudioPlayerBackend` is the simpler fallback)
- **Find covers** embedded in the files or lying next to them and cache
  them as thumbnails (`ArtworkReader`, `ArtworkCache`)
- **Search** titles, artists, albums, composers and genres (`SearchQuery`)
- **Level loudness** from ReplayGain or iTunes Sound Check tags, or from
  its own BS.1770 measurement of untagged files, limited by the peak,
  through a gain stage separate from the volume (`ReplayGain`,
  `LoudnessMeter`, `LoudnessAnalyzer`)
- **Lyrics** from `.lrc`/`.txt` files beside the track or in a `lyrics`
  folder, or from the tags, timed or plain (`Lyrics`, `FileLyricsSource`)
- **Favorites** as a set of track paths in a small file of its own, so
  a tap on a heart never rewrites the index (`Favorites`)

Formats are whatever AVFoundation decodes: MP3, AAC/M4A (including M4B
audiobooks), FLAC, ALAC, WAV, AIFF, CAF. Ogg Vorbis and Opus are not supported, because the system
cannot play them.

The tag reader goes through AVFoundation's common keys, then ID3 and
iTunes identifiers (including iTunes' binary genre index and the "(17)"
style ID3v1 references). FLAC is parsed by Crate itself
(`FLACMetadata`: STREAMINFO, Vorbis comments and embedded pictures),
because AVFoundation does not expose Vorbis comments on every macOS
version. Checked against real MP3, M4A and FLAC files; reports with a
sample file for anything that reads wrong are welcome.

## Build and test

```sh
swift build
swift test
```

Building needs Swift 6 and Xcode (the Command Line Tools alone lack the
SwiftUI and Swift Testing macro plugins). The app runs on macOS 12
Monterey and later, on Apple silicon and Intel.

To build the app itself:

```sh
scripts/bundle.sh      # -> build/Crate.app, ad-hoc signed
open build/Crate.app
scripts/dmg.sh --universal   # -> build/Crate-<version>.dmg, what a release ships
scripts/install.sh           # -> /Applications/Crate.app, registered with Launch Services
```

Crate > Use Crate for Audio Files binds every audio type (MP3, M4A, FLAC,
WAV, AIFF, ...) to the running copy, which should be the one in
/Applications. A file opened from Finder then plays its album from that
track if the file is in the library, or just the opened files if not; a
folder dropped on the app is added to the library.

Releases are cut by pushing a `v*` tag that matches the version in
`Packaging/Info.plist` and in `android/gradle.properties`; the workflow
builds the DMG and the signed APK and takes the release notes from
`CHANGELOG.md`.

To see what the tag reader makes of a real folder:

```sh
swift run crate-scan ~/Music/Music/Media.localized        # summary + tracks with gaps
swift run crate-scan <folder> --all                       # every track
swift run crate-scan <folder> --lyrics                    # how many files have lyrics, and where from
```

### Lyrics for a collection

The apps never go online, so lyrics have to be files next to the music.
`scripts/lyrics.py` makes them, on the Mac, as a separate step you run
yourself: for every file without lyrics it asks the open database at
[lrclib.net](https://lrclib.net), then listens to the track with
[whisper.cpp](https://github.com/ggml-org/whisper.cpp) and aligns the
text to what it heard. Time stamps that match the singing are kept,
plain text gets stamps when enough of it was heard, and anything doubtful
stays plain text. The result goes to `lyrics/<name>.lrc` or `.txt`
beside the audio, where both apps find it (copy the folder to the phone
as it is).

```sh
scripts/lyrics.py --dry-run <folder>   # everything but writing: see the verdicts first
scripts/lyrics.py <folder>             # fetch, check and write
scripts/lyrics.py --check <folder>     # only judge the lyrics files already there
scripts/lyrics.py --replace <folder>   # also replace lyrics that do not match the audio
scripts/lyrics.py --time <folder>      # give your own plain .txt lyrics time stamps
```

It needs Python 3, ffmpeg, whisper.cpp (`brew install whisper-cpp`) and
the large-v3-turbo model at `~/.cache/whisper-cpp/ggml-large-v3-turbo.bin`.
Whisper takes about half a minute per song on Apple silicon, so a large
collection is an overnight run; transcripts are cached, a second run is
quick.

## Android

`android/` holds the Android app: the same product, the same album
logic, no shared code with the Mac app (there is nothing to share across
Swift and Kotlin but the ideas). Pick a folder with the system's folder
picker, Crate reads the tags where the files are and plays them through
the system's media session, so the lock screen, headsets and Bluetooth
controls work. Formats are what Android decodes: MP3, AAC/M4A (including M4B
audiobooks), FLAC, WAV, Ogg Vorbis, Opus. Runs on Android 10 and later. MP3, M4A and FLAC tags
are read by Crate's own parsers, a few kilobytes per file, so a scan of a
few thousand files takes seconds; Ogg and Opus go through the platform.

```
android/core/   UI-free Kotlin library: Track, Album grouping, Library with
                incremental rescans, JSON store, FLAC parser, search,
                favorites (JVM tests)
android/app/    Jetpack Compose UI, Media3/ExoPlayer playback service,
                Storage Access Framework file source, cover cache
```

```sh
cd android
./gradlew :core:test            # the library's tests, no emulator needed
./gradlew :app:assembleDebug    # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease  # unsigned unless the signing env vars are set
```

Building needs JDK 17 or later and the Android SDK with platform 37
(`ANDROID_HOME` or `android/local.properties`). The `scripts/icon.sh`
run that draws the Mac icon also writes the Android launcher icon.

## Layout

```
Sources/CrateCore/    the package
Sources/Crate/        the SwiftUI app
Sources/crate-scan/   developer tool, not part of the app
Tests/CrateCoreTests/
Packaging/            Info.plist (the one place the macOS version lives) and AppIcon.icns
android/              the Android app, see above; its version lives in
                      android/gradle.properties
scripts/              bundle.sh (build Crate.app), dmg.sh (release DMG), install.sh
                      (copy to /Applications and register), icon.sh/icon.swift
                      (regenerate the icon), screenshot.sh (dev aid)
```

Design rules the code follows:

- The package has no UI and no global state. `Library` is an actor,
  `PlayQueue` is a value type, everything else is a plain struct or a
  protocol.
- Everything with I/O sits behind a protocol (`MetadataReader`,
  `ArtworkReader`, `LibraryStore`, `AudioPlayer`), so tests run with fakes
  and the app with the AVFoundation implementations.
- Defaults over options. If something can be decided for the user, it is.

## License

GPL-3.0-or-later, see `LICENSE`.

See `AI_POLICY.md` for how AI tooling is used in this project,
`PRIVACY.md` for what the app does with your data (nothing leaves your
device) and `THIRD-PARTY-NOTICES.md` for dependencies (none beyond the
system frameworks on the Mac; Kotlin, AndroidX and Media3 on Android).
