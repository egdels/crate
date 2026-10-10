# Crate

A FLOSS music player for macOS, written because the developer dislikes the
Music app. Named "Crate". GPL-3.0-or-later. The Mac app is Swift, SwiftUI,
AVFoundation, nothing cross-platform. Since 2026-10-10 there is also an
Android app in `android/` (Kotlin, Compose, Media3), decided by the
developer: same product, same album logic, no shared code; the two apps
never get a common framework. Do not reopen that either.

The product rule, inherited from the developer's other apps: **open it and
play**. No account, no cloud, no store, no settings screen. Defaults
instead of options and dialogs. Internal complexity is fine if the user
never sees it.

## Conversation and code

- The developer writes German; answer in German. Code, comments, commit
  messages and docs are English.
- Every Swift file starts with the two-line copyright/SPDX header (copy
  from any existing file).
- Commits end with the `Co-Authored-By: Claude ...` trailer. AI_POLICY.md
  explains that to readers; keep it true.
- Never state the developer's credentials in public-facing text.
- `.idea/` and other IDE files are never committed.

## Build, test, run

```sh
swift build                    # whole package
swift test                     # 71 tests, Swift Testing, needs Xcode (macro plugins); ~5 s, plays silence
swift run crate-scan <folder>  # tag-reader check against real files, prints gaps
scripts/bundle.sh              # release build -> build/Crate.app (ad-hoc signed)
scripts/dmg.sh --universal     # -> build/Crate-<version>.dmg, what a release ships
scripts/icon.sh                # redraw Packaging/AppIcon.icns and the README PNG from scripts/icon.swift
scripts/install.sh             # bundle + copy to /Applications + lsregister; quits a running Crate
open <file>                    # what Finder does: plays the file (its album if in the library)
open build/Crate.app           # run it; pkill -x Crate to stop
scripts/screenshot.sh          # PNG of the Crate window, then Read the file
```

No Xcode project. The app is a SwiftPM executable target; `scripts/bundle.sh`
is the whole packaging step. Xcode 27 is installed and `xcode-select`ed; the
Command Line Tools alone cannot build the SwiftUI target (no macro plugin).

Android, from `android/` (needs JDK 17+, Android SDK with platform 37;
`ANDROID_HOME` is set on this machine, Gradle 9.7 via the wrapper):

```sh
./gradlew :core:test             # 44 JVM tests, ~2 s
CRATE_MUSIC_DIRS=~/Music/Music/Media.localized ./gradlew :core:test --tests '*TagParserTest*' -i
                                 # also runs the MP3/MP4 parsers over real files (colon-separated dirs)
./gradlew :app:assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug         # must stay at zero errors (CI runs it)
./gradlew :app:assembleRelease   # R8; unsigned without RELEASE_KEYSTORE_PATH etc.
~/Library/Android/sdk/emulator/emulator -avd Pixel_9a &   # Android 17 (API 37) image
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n de.schliweb.crate/.MainActivity
adb exec-out screencap -p > shot.png   # then Read the file; adb shell input tap X Y
```

Screenshots and `osascript` need Screen Recording and Accessibility granted
to the terminal's host app; see the header of `scripts/screenshot.sh`.
Playback can be verified without a screen: start playback, then check
`model.isPlaying`-visible state via a screenshot, or run the app and
inspect `~/Library/Application Support/Crate/library.json` after a scan.

## Layout

```
Sources/CrateCore/    UI-free library: Track, Album, LibraryScanner,
                      MetadataReader (+AVFoundation impl), Library (actor),
                      LibraryStore (+JSON impl), PlayQueue, AudioPlayer
                      (+AVAudioPlayer impl), ID3v1Genres
Sources/Crate/        SwiftUI app: CrateApp, PlayerModel (@Observable,
                      the only UI state), ContentView (album grid + track
                      list + empty state), PlayerBar
Sources/crate-scan/   developer tool
Tests/CrateCoreTests/ Swift Testing; fakes for reader; real WAVs for AVFoundation
Packaging/Info.plist  bundle metadata for scripts/bundle.sh
```

Album grouping (`Album.group`) is folder-aware by design, not by option:
key = (folder, loosely compared album title), artist from what the
tracks agree on, "Various Artists" when they do not, untagged tracks
named after the folder. `swift run crate-scan <folder> --albums` shows
the result for a real folder.
Design rules: everything with I/O sits behind a protocol so tests use
fakes. `Library` is an actor, `PlayQueue` a value type, views never touch
`Library` or `AudioPlayer` directly, only `PlayerModel`.

## Android layout

```
android/core/   Kotlin/JVM, package de.schliweb.crate.core: Track (uri +
                path), Album.group (port of the Swift one, on "/"-joined
                document paths), Library (Mutex, incremental rescans),
                JsonLibraryStore (kotlinx.serialization), FileSource
                (+LocalFileSource for tests), MetadataReader, FlacMetadata,
                TagText/Id3v1Genres, FolderImages, SearchQuery, Formatting
android/app/    de.schliweb.crate: CrateApplication (owns Library and
                ArtworkCache), PlayerViewModel (the only UI state),
                MainActivity, ui/CrateScreen (grid + detail, two panes
                from 720 dp, else grid -> album page), ui/Theme,
                library/SafFileSource (DocumentsContract walk),
                library/AndroidMetadataReader (MediaMetadataRetriever,
                FLAC via FlacMetadata), library/ArtworkCache (+reader),
                playback/PlaybackService (MediaSessionService + ExoPlayer,
                ArtworkBitmapLoader resolves crate://album/<key>)
```

Android tag reading: `Mp3Metadata` (ID3v2.2-2.4 with unsync, extended
header, ID3v1 tail, duration from Xing/VBRI or bitrate x size) and
`Mp4Metadata` (top-level atom walk, `moov` at either end, `mvhd`
duration, `ilst` items incl. binary trkn/disk/gnre and covr) read through
`ByteSource` (`ContentByteSource` = pread on the provider's descriptor;
`FileByteSource` in tests). `MediaMetadataRetriever` is the fallback and
handles Ogg/Opus/WAV. Why: in the emulator 900 files took 10.3 s through
the retriever at 4 in flight, 3.6 s with the parsers, 2.6 s at 8 and
2.2 s at 16 in flight; the Pixel 7a managed only ~30 files/s with the
retriever. Verified against the retriever's output for the same 900
files: all durations equal, the only tag differences were the retriever's
bogus year 1904 for M4As with an invalid date tag. Real-file check: set
`CRATE_MUSIC_DIRS` (see above); every MP3/M4A/M4B must yield title and
duration. On the Pixel 7a, 3,600 files: full scan 25.8 s with the
parsers at 16 in flight (the retriever path took over two minutes); a
no-change rescan is the folder walk alone, 8.1 s sequential and 3.4 s
with 8 folder queries in flight (`SafFileSource.QUERIES_IN_FLIGHT`);
emulator with 914 files: 610 ms to 277 ms. The developer's MP3 collection
has 20 zero-byte WAVs in a "(1)" folder (an interrupted copy): they are
the "20 unreadable" and are failed without a round trip.
Android decisions: the track's `path` is built from document display
names starting at the tree's document id ("primary:Music/Artist/Album/
01.mp3"), so grouping works for any provider; `uri` is the content URI
that opens it. ExoPlayer's playlist is the queue (there is no PlayQueue
port): next/previous/shuffle/repeat/gapless come from Media3, and the
notification's buttons work without extra plumbing. Ogg and Opus are
scanned because Android plays them. `mipmap-anydpi` without `-v26` is
silently dropped by the resource merger; keep the `-v26` folder. Lint
needs `@androidx.annotation.OptIn(UnstableApi::class)`, not Kotlin's
`@OptIn`. Verified on the Pixel_9a emulator (Android 17): folder picker,
scan (M4A, MP3, tagged FLAC with cover, untagged FLAC with folder.jpg,
Ogg), grid, detail, playback, auto-advance, media notification with
cover colour, search, two-pane landscape, "open with" via VIEW intent,
restart with the saved library, and the R8 release build. On the
developer's Pixel 7a (Android 17, wireless adb): install, folder pick,
scan of 3,600 files, rescan, "open with" chooser. Not yet tested:
output switching, playback on the phone by ear.

## Test material

- `~/Music/Music/Media.localized`: 40 M4A + 2 MP3 from the Music app,
  tags verified correct.
- The developer's collection on a NAS (SMB share) and a copy of it on an
  external SSD; host name, mount points and folder names are in a notes
  file next to the test VM, not here. One folder is MP3 (2,020 files),
  one is FLAC (1,984 files, one album of 36 FLAC used for tag checks).
  Measured over SMB: the directory walk alone takes 6-8 s for 2,000 files,
  a cold tag read 20-120 ms per file; a full scan of the MP3 folder took
  67 s cold and 32 s warm with 4 reads in flight, 21 s with 16, 27 s with
  32 (`swift run crate-scan <folder> --concurrency N`). 60 of the 226
  albums in the FLAC folder have no cover anywhere, verified down to the
  file bytes: no APIC/covr/PICTURE and no image in the folder. One MP3
  there is an MP4 with an ID3 tag glued in front and fails to read; that
  is the "1 unreadable".
- Tag reading is verified for MP3, M4A and FLAC against those files. Known
  AVFoundation quirks are documented in `AVFoundationMetadataReader.swift`
  (TDAT read as a year; iTunes binary genre atom). FLAC does not go through
  AVFoundation at all: `FLACMetadata` parses STREAMINFO, VORBIS_COMMENT and
  PICTURE itself, because AVFoundation on macOS 12 returns no Vorbis
  comments (verified in the Monterey VM) and on 26 only under raw keys.

## Status

Done and committed: CrateCore with 71 tests; crate-scan; the app verified
end to end on screen (empty state, folder picker, grid, double-click,
transport, seek, volume, auto-advance, rescan); album artwork
(`ArtworkReader`/`ArtworkCache`, thumbnails under Application
Support/Crate/Artwork); Now Playing and media keys (`NowPlaying.swift` in
the app target); search and the All Tracks table; gapless playback
(`AVAudioEngineBackend`, the app's default; `AVAudioPlayerBackend` kept as
fallback); release tooling (`scripts/dmg.sh`, `release.yml`, PRIVACY.md,
THIRD-PARTY-NOTICES.md, CHANGELOG.md).

UI driving without a human: `scripts/screenshot.sh` plus small CGEvent
helpers (click, scroll, media keys) compiled ad hoc with `swiftc`; the
folder picker is driven with Cmd+O, Cmd+Shift+G, path, Return, Return.
Control Center's Now Playing panel is not reachable via Accessibility; the
menu bar Now Playing icon on the main display is, by clicking it.
`.searchable` with `.sidebar` placement never rendered above the
ScrollView-based grid on macOS 26; the field sits in the toolbar instead.
Scan feedback is the footer line under the grid (`PlayerModel.statusText`);
while a search hides the selected album, the detail shows the search
results (`PlayerModel.detail`).
The NavigationSplitView must be the window's root from the first frame
and carry the toolbar items itself (`.toolbar`, `.searchable` on it in
ContentView): a split view that replaces the empty state later, or
toolbar items registered by a column whose content changes right away
(Finder launches the app with a file), come up without toolbar on
macOS 26. `ScrollViewReader` in the sidebar did not scroll reliably;
`.scrollPosition(id:)` does.
Minimum macOS is 12 (an older Intel MacBook has to run it), so:
`ObservableObject`, not `@Observable`; `NavigationView`, not
`NavigationSplitView`; `WindowGroup`, not `Window`; `List`, not `Table`
with a double-click action; `timeText`/`lengthText` in Formatting.swift,
not `Duration.formatted`; `ScrollViewReader`, not `scrollPosition`; the
one-argument `onChange`; `Task.sleep(nanoseconds:)`. The compiler enforces
this through `platforms: [.macOS(.v12)]`.
One window with a WindowGroup: the delegate claims the kAEOpenDocuments
Apple event in `applicationWillFinishLaunching`, before SwiftUI would open
a window per file; `handlesExternalEvents` alone did not stop that.
After Cmd+W the app keeps running; the Dock reopen opens `crate://main`
(CFBundleURLTypes in Info.plist), which the WindowGroup handles by
showing its window, since `openWindow` needs macOS 13. `pkill` is no
clean quit and makes the system restore stale windows; quit via
`osascript -e 'tell application "Crate" to quit'`.
Verified in a macOS 12.7.6 VM (arm64): launch, grid, covers,
playback, seek, next, search, All Tracks, Cmd+W and Dock reopen all work.
The universal 0.1.0 DMG was also installed and run on an Intel MacBook
with 12.7.6, so the x86_64 slice is verified on real hardware too.
The VM is a Tart VM named `monterey` on the external SSD; its
`TART_HOME`, the guest credentials and the helpers (`tools/vm.sh`
ip|ssh|scp with a key) are described in `tools/NOTES.md` inside that
`TART_HOME`, not in this repo. Start it with
`open -n ~/Applications/tart.app --args run monterey` (TART_HOME exported).
Tart's own window never shows up for automation; look at the guest through
Screen Sharing instead (`open vnc://<user>:<password>@<ip>`, the VNC
password was set with kickstart), then screencapture the "Screen Sharing"
window and click into it with the CGEvent helper. `screencapture` inside
the guest over SSH shows no app windows (no Screen Recording permission),
which once looked like "the app has no window" and was not.
The installed copy is /Applications/Crate.app, and the
default-app bindings for all audio types point there, set via
NSWorkspace.setDefaultApplication (Crate > Use Crate for Audio Files does
the same from inside the app, `DefaultApp.swift`). Launch Services binds
by bundle identifier and resolves it to the copy it prefers, so a rebuilt
build/Crate.app does not reach the double-click until scripts/install.sh
copies it over. Rebinding a type *away* from Crate by script fails with
userCanceledErr on macOS 26 (no dialog appears); testing the "not yet
default" path needs Get Info > Change All by hand.
Finder integration: CFBundleDocumentTypes in Packaging/Info.plist,
`AppDelegate.application(_:open:)` in CrateApp.swift buffers URLs until the
model is attached, `PlayerModel.open(_:)` decides what to play.

**Open, in no fixed order:**

1. Released: v0.1.0 is published at github.com/egdels/crate with the
   universal DMG built by `release.yml` (CI and Release both green on the
   first run). For the next version: bump `Packaging/Info.plist`, add the
   CHANGELOG section, push `main` and the tag only (`git push origin main
   vX.Y.Z`, never `--all` or `--mirror`: local branches hold pre-release
   history that stays local).
2. Not yet verified by ear: the gapless transition is covered by a test on
   the node clock, not by listening. Output device switching mid-track is
   handled in code but untested.
3. Android: no release pipeline yet (`android.yml` only tests, lints and
   builds a debug APK). A release needs the signing env vars
   `RELEASE_KEYSTORE_PATH`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`,
   `SIGNING_STORE_PASSWORD` like the developer's other Android apps, and
   a versionName bump in `android/gradle.properties`. Not run on a real
   phone yet.
