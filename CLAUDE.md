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
swift test                     # 92 tests, Swift Testing, needs Xcode (macro plugins); ~5 s, plays silence
swift run crate-scan <folder>  # tag-reader check against real files, prints gaps
swift run crate-scan <folder> --lyrics   # counts files with lyrics (USLT/SYLT, ©lyr, FLAC LYRICS, .lrc)
swift run crate-scan <folder> --loudness # BS.1770 loudness, peak and gain per file, with the speed
scripts/lyrics.py [--dry-run|--check|--replace] <folder>  # lyrics from lrclib.net, stamps checked/made with whisper.cpp
                               # (needs ffmpeg, brew's whisper-cpp and ~/.cache/whisper-cpp/ggml-large-v3-turbo.bin;
                               # about 35 s per file, writes lyrics/<name>.lrc|.txt next to the audio)
scripts/covers.py [--dry-run|--accept|--no-itunes] <folder>  # covers from MusicBrainz/Cover Art Archive, iTunes as
                               # fallback, checked against the track list; writes cover.jpg into the album folder
swift run crate-scan <folder> --covers   # JSON of the albums the app shows without a cover (covers.py reads it)
scripts/tags.py [--dry-run|--local|--no-itunes|--undo <journal>] <folder>  # tag gaps and broken values filled
                               # locally and from MusicBrainz/iTunes, journal in ~/.cache/crate-tags; mutagen in a venv there
swift run crate-scan <folder> --json     # every album with its tracks' tags as JSON (tags.py reads it)
scripts/sync.py [--copy] <source> <target>  # compare two copies of a collection (names Unicode-normalised:
                               # openrsync would duplicate folders with umlauts on SMB); --copy never deletes
scripts/bundle.sh              # release build -> build/Crate.app (ad-hoc signed)
scripts/dmg.sh --universal     # -> build/Crate-<version>.dmg, what a release ships
scripts/icon.sh                # redraw Packaging/AppIcon.icns, the README PNG and the Android icon
                               # layers (mipmaps + drawable/ic_launcher_monochrome.xml) from scripts/icon.swift
scripts/install.sh             # bundle + copy to /Applications + lsregister; quits a running Crate
open <file>                    # what Finder does: plays the file (its album if in the library)
open build/Crate.app           # run it; pkill -x Crate to stop
scripts/screenshot.sh          # PNG of the Crate window, then Read the file
                               # (captures the screen area: an overlapping window shows instead;
                               # `screencapture -l <CGWindowID>` captures the window itself, and
                               # a SwiftUI button can be pressed by title through accessibility (AXUIElement walk + AXPress, see the
                               # scratch `axpress` tool idea) when the window is on another Space or covered;
                               # `open -g -a <abs path to build/Crate.app> <file>` plays a library file in that build without
                               # bringing it forward; a bare `open <file>` goes to /Applications/Crate.app, even
                               # while a build/Crate.app is running, and then two Crates run)
```

No Xcode project. The app is a SwiftPM executable target; `scripts/bundle.sh`
is the whole packaging step. Xcode 27 is installed and `xcode-select`ed; the
Command Line Tools alone cannot build the SwiftUI target (no macro plugin).

Android, from `android/` (needs JDK 17+, Android SDK with platform 37;
`ANDROID_HOME` is set on this machine, Gradle 9.7 via the wrapper):

```sh
./gradlew :core:test             # 65 JVM tests, ~2 s
CRATE_MUSIC_DIRS=~/Music/Music/Media.localized ./gradlew :core:test --tests '*TagParserTest*' -i
                                 # also runs the MP3/MP4 parsers over real files (colon-separated dirs)
./gradlew :app:testDebugUnitTest # 4 JVM tests in the app module (GainProcessor), ~10 s
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
                      (+AVAudioPlayer impl), ID3v1Genres, Favorites
                      (+FavoritesStore, JSON impl), ReplayGain,
                      PlaybackState (+PlaybackStateStore, JSON impl),
                      Lyrics (+LyricsSource, FileLyricsSource), History
                      (+HistoryStore, JSON impl; Track.recentlyAdded),
                      Loudness (LoudnessMeter, LoudnessHistogram,
                      TrackLoudness, Loudness, LoudnessStore + JSON impl,
                      LoudnessAnalyzer; AVAudioFile decoding)
Sources/Crate/        SwiftUI app: CrateApp, PlayerModel (@Observable,
                      the only UI state), ContentView (album grid + track
                      list + empty state), PlayerBar
Sources/crate-scan/   developer tool
Tests/CrateCoreTests/ Swift Testing; fakes for reader; real WAVs for AVFoundation
Packaging/Info.plist  bundle metadata for scripts/bundle.sh
```

Album grouping (`Album.group`) is folder-aware by design, not by option:
key = (folder, loosely compared album title), artist from what the
tracks agree on, "Various Artists" when they do not or when most tracks
carry the compilation flag without an album artist, untagged tracks
named after the folder. Albums sort by the tags' sort names (TSOP/TSO2/
TSOA, soar/soaa/soal, ARTISTSORT...) or, without them, by the name with
a leading "The" moved back. `LibrarySnapshot.currentVersion` is 2; an
older index sets `Library.needsRescan`, and the app's start triggers one
full re-read (every file, not only changed ones) with the grid up. Bump
the version whenever `Track` gains a tag field. Android: kotlinx leaves
default values out of the JSON, so `LibrarySnapshot.version` defaults
to 1 on read and the writer passes `CURRENT_VERSION` explicitly.
Loudness (version 3): `Track.trackGain`/`albumGain` in dB, already
limited by the tagged peak (`ReplayGain.limited`); ReplayGain from TXXX
(Mac: the item's `extraAttributes[.info]` names the frame), iTunes
freeform atoms and Vorbis comments, iTunes Sound Check (`iTunNORM`, COMM
frame or freeform atom) as the track gain when there is no ReplayGain.
A real collection may carry no ReplayGain at all but Sound Check in
part of its files (gains seen from -13.9 to +13.9 dB). Playback: album gain when `play(album:)` queued the album,
else the mean of the album's track gains (`ReplayGain.albumMean`, for
Sound Check, which knows no albums: one album's tracks spanned -2.8 to
+13.9 dB), else track gain; Mac through an `AVAudioUnitEQ` (`globalGain`, ±24 dB)
between player node and mixer, switched at the gapless hand-over;
Android through `GainProcessor` (16-bit PCM AudioProcessor in the sink)
fed from the media item's extras on each item transition, logged as
"gain: x dB for <title>". The processor must ignore an empty input:
the pipeline queues the shared EMPTY_BUFFER and replaceOutputBuffer(0)
returns that very object; copying it into itself threw and 0.6.0/0.6.1
could not play untagged files on Android (every verification had used
tagged files; test untagged ones too). The user's volume (Mac mixer, Android system)
is a separate stage. Test files with ReplayGain tags can be made with
ffmpeg (`-metadata replaygain_track_gain="-6.50 dB"`, TXXX) and
`metaflac --add-replay-gain`; ffmpeg's mp4 muxer drops them, so an M4A
needs the freeform atoms patched in by hand. `swift run crate-scan <folder> --albums` shows
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
                TagText/Id3v1Genres, FolderImages, SearchQuery, Formatting,
                Favorites (+JsonFavoritesStore), ReplayGain,
                PlaybackState (+JsonPlaybackStateStore), Lyrics
                (+LyricsSource; SafLyricsSource lives in the app),
                History (+JsonHistoryStore, recentlyAdded()), Loudness
                (meter, histogram, store, analyzer; MediaCodecLoudness
                in the app decodes)
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
handles Ogg/Opus/WAV. Why: in the emulator about 900 files took 10.3 s
through the retriever at 4 in flight, 3.6 s with the parsers, 2.6 s at 8
and 2.2 s at 16 in flight; the Pixel 7a managed only ~30 files/s with the
retriever. Verified against the retriever's output for the same
files: all durations equal, the only tag differences were the retriever's
bogus year 1904 for M4As with an invalid date tag. Real-file check: set
`CRATE_MUSIC_DIRS` (see above); every MP3/M4A/M4B must yield title and
duration. On the Pixel 7a, a few thousand files: full scan 26 s with the
parsers at 16 in flight (the retriever path took over two minutes); a
no-change rescan is the folder walk alone, 8.1 s sequential and 3.4 s
with 8 folder queries in flight (`SafFileSource.QUERIES_IN_FLIGHT`);
emulator with about 900 files: 610 ms to 277 ms. Zero-byte files (an
interrupted copy leaves them) count as unreadable and are failed without
a round trip.
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
scan of the library, rescan, "open with" chooser. Not yet tested:
output switching, playback on the phone by ear.

## Test material

- The Music app's media folder: a few M4A and MP3 files, tags verified
  correct.
- The developer's collection on a NAS (SMB share) and a copy of it on an
  external SSD; everything about it (hosts, paths, folders, contents) is
  in a notes file next to the test VM, not here. Measured over SMB with
  about 2,000 files: the directory walk alone takes 6-8 s, a cold tag
  read 20-120 ms per file; a full scan took 67 s cold and 32 s warm with
  4 reads in flight, 21 s with 16, 27 s with 32 (`swift run crate-scan
  <folder> --concurrency N`). Albums without any cover exist, verified
  down to the file bytes (no APIC/covr/PICTURE, no image in the folder).
  An MP4 with an ID3 tag glued in front and named .mp3 fails to read.
- Tag reading is verified for MP3, M4A and FLAC against those files. Known
  AVFoundation quirks are documented in `AVFoundationMetadataReader.swift`
  (TDAT read as a year; iTunes binary genre atom). FLAC does not go through
  AVFoundation at all: `FLACMetadata` parses STREAMINFO, VORBIS_COMMENT and
  PICTURE itself, because AVFoundation on macOS 12 returns no Vorbis
  comments (verified in the Monterey VM) and on 26 only under raw keys.

## Status

Done and committed: CrateCore with 92 tests; crate-scan; the app verified
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
Menus: `MenuCommands.swift`. The App holds the model in `@State`
without observing it, and the menus observe only `model.menu`, a
`MenuSnapshot` republished when what the menus show changes: observing
the whole model rebuilt the menu bar on every analysis progress step
and clock tick, and an open submenu was replaced under the pointer, so
real clicks in Remove Folder or Repeat did nothing (AXPress still
worked, so check menus with real mouse events; a closed SwiftUI menu
shows stale marks to Accessibility until it is opened).
Window look: both NavigationView columns carry `textBackgroundColor`;
the first column's default sidebar material ended 2 pt short of the
column under the toolbar (378 vs the divider at 380, measured on a
window capture), a NavigationView artefact NavigationSplitView (macOS
13) would not have. The toolbar over the detail column has its own
tinted material; `ClearToolbar` (macOS 13+) paints it opaque in
`textBackgroundColor` with `.toolbarBackground(.visible)`. Hidden
instead, the column divider (full window height in NSSplitView, only
the toolbar covers its top) showed through the title bar. In full
screen AppKit puts the toolbar in a window of its own that ignores
`toolbarBackground` (grey again, from x = 378); `TitlebarStyle` (an
NSView probe in the background) sets `titlebarAppearsTransparent` and
a `textBackgroundColor` window background, which keeps it white there.
Measured on window captures, windowed and full screen (the full-screen
window is the 1920x1200 one in the window list; toggle it through the
`AXFullScreen` attribute). macOS 12 has no `toolbarBackground`: there the
transparent title bar may let the divider through; unchecked. Without the grey, the album header needs its
Divider above the list. A `.background` reaches under the toolbar unless
given `ignoresSafeAreaEdges: []` (the red error line reddened the title
bar). Not checked in the macOS 12 VM.
`AppleInterfaceStyle` per app (argument or defaults) is ignored on
macOS 26: dark mode is checked by switching the system (System Events,
appearance preferences, then back). In dark mode the inset lists' own
background and the `.bar` material are a blue-tinted grey (30,34,43)
beside the neutral content colour (28); the lists hide theirs
(`scrollContentBackground`, macOS 13+) and the player bar uses the
content colour. Window captures in this session: both modes, windowed
and full screen, error line included.
Testing gotcha: `tell application id "de.schliweb.crate" to activate`
launches /Applications/Crate.app when the build copy is not running
yet; bring a build copy forward through System Events' process.
Errors: the red line (`lastError`) is dismissed by a click or after
eight seconds (`task(id:)`), since some failures have no later success
that would clear them.
Help: `HelpWindow.swift` replaces SwiftUI's default "Crate Help" item
(which otherwise shows "Help isn't available") with a plain NSWindow
holding one page of SwiftUI text, kept in a static and reused; no Apple
Help Book. The text is a Swift view, not a resource, because
scripts/bundle.sh copies only the binary. Verified on screen, incl.
Cmd+W and reopen. Android: "Help" in the overflow menu shows
`app/src/main/assets/HELP.md` through `ui/MarkdownDialog.kt` (the
privacy policy dialog, generalised); verified on the Pixel_9a emulator.
Now Playing: the `MPMediaItemArtwork` request handler in
`NowPlaying.update` must be a `@Sendable` closure. MediaPlayer calls it
on its own queue when it serialises the cover; a plain closure inherits
the method's main-actor isolation and the Swift 6 runtime check traps
(EXC_BREAKPOINT on "accessQueue", within a minute of playing a track
with a cover). Only the release DMGs crashed: the GitHub runner's Xcode
26.x (SDK 26.5) compiles the dynamic isolation check into that closure,
the local Xcode 27.0 (Swift 6.4) does not, so every local build passed
and a half-day bisect over local builds found nothing. Lesson, twice:
any closure handed to an Apple framework that may call it off the main
thread is `@Sendable`; and a Mac fix is verified on the DMG the workflow
built (download, mount, run, play), not only on a local build.
Finder integration: CFBundleDocumentTypes in Packaging/Info.plist,
`AppDelegate.application(_:open:)` in CrateApp.swift buffers URLs until the
model is attached, `PlayerModel.open(_:)` decides what to play.
Favorites (both apps): a set of track ids (paths) in `favorites.json`
next to the library index, deliberately not a field on `Track` (rebuilt
on every rescan) and not in the library snapshot (the developer's
`library.json` is 10.7 MB; a heart tap must not rewrite it). Ids of
files that are gone stay; only removing a folder prunes them. UI: heart
on rows (Mac: visible on hover or when set), album header (all tracks),
player bar, a Favorites tile after All Tracks (`Selection.favorites`;
Android's grid auto-select counts `LIBRARY_TILES` = 2), Mac menu
Playback > Add to Favorites ⌘L and row context menu. Verified on screen
on the Mac and on the Pixel_9a emulator incl. restart. Android also has
the heart in the media notification: `SharedFavorites` in the
application holds the one in-memory value (StateFlow) and writes the
file behind it; the view model mirrors it into Compose state, and
`PlaybackService` puts a `CommandButton` (ICON_HEART_FILLED/UNFILLED,
custom command `COMMAND_TOGGLE_FAVORITE`) into the session's media
button preferences, refreshed on every item transition and every
favorites change. Verified on the emulator both ways and across a
track skip.
Up Next (both apps): the queue after the current track, from
`PlayQueue.upcoming` (Mac, popover on the player bar, `UpNextView`,
`showsUpNext` on the model for ⌘U) or a walk over the controller's
timeline with `getNextWindowIndex(..., shuffleModeEnabled)` (Android,
`PlayerViewModel.upcoming`, `UpNextSheet`); the `RemotableTimeline` the
controller holds carries the shuffle order. Play Next / Play Last on
the row and tile context menus (Mac) and long-press menus (Android,
`QueueMenu`). Android shuffle lives in the ExoPlayer's `ShuffleOrder`,
which a controller cannot set: `PlaybackService` rewrites it when
shuffle turns on (current item first) and on the custom session command
`COMMAND_QUEUED_NEXT` (the items the view model just inserted go right
behind the current one). That command arrives before the insert itself,
because a controller's `addMediaItems` passes through the callback's
`onAddMediaItems` future, so the service waits for the playlist to
reach the length the controller reported (`KEY_TOTAL`). Verified on the
Pixel_9a emulator: sheet, tap, ×, Clear, Play Next with and without
shuffle; and on the Mac on screen.
Loudness analysis (both apps): `LoudnessMeter` is BS.1770-4 (the
K-filter biquads designed per sample rate as libebur128 does, 400 ms
blocks every 100 ms, -70 LUFS absolute gate, relative gate 10 LU under
the mean) with a `LoudnessHistogram` of 1 LU bins so an album can be
gated over all its tracks' blocks from the stored per-track data
(`TrackLoudness.albumGain`); gain = -18 LUFS minus loudness, limited by
the sample peak (`ReplayGain.limited`). Checked against
`ffmpeg -af ebur128`: identical LUFS on MP3, M4A and FLAC; the peak
differs where ffmpeg's decoder exceeds full scale (AVFoundation clips
at 0 dBFS). `LoudnessAnalyzer` measures what `Loudness.needsMeasuring`
(no ReplayGain tags) and is not fresh (file modified since), two in
flight at utility priority, saves every ten files, cancellable;
`loudness.json` costs about 1 KB per file (about 49 bins on average,
fewer for music than for speech). Mac: `PlayerModel.startAnalysis` after
load and after every scan, cancelled by scans and folder removal,
footer "Measuring loudness… x of y"; about six files a second with two
in flight (125x realtime per thread), 150% CPU, so a large collection
takes about an hour; `gain(for:)` prefers tags, then the
measurement (album gain only when the whole queued album is measured),
then Sound Check. Android: `MediaCodecLoudness` (MediaExtractor +
MediaCodec, 16-bit or float PCM to planar floats), the analyzer and
its job in the application (`CrateApplication.startAnalysis`, a
StateFlow of progress the view model mirrors), started after load and
after scans, cancelled before them; it runs as long as the process
does, so Back (which finishes the activity) does not stop it and
background playback keeps it going, while an idle app in the
background is frozen by the system until it is opened again (no
WorkManager, no background service); `mediaItems` applies the same
precedence. Each buffer costs a round trip to the codec process
(about 0.75 ms) and one MP3 frame is 26 ms of audio, so frame by
frame the decoder idles and a four-minute MP3 takes eight seconds
(30x realtime, with the CPU two thirds idle); on Android 15 and later
the platform decoders accept batches (`FEATURE_MultipleFrames`,
`queueInputBuffers`, `KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE`, callback mode
only: "Large Frame audioconfig works only with async mode"), which
`decodeBatched` uses on a `HandlerThread`, 250x realtime, a four-minute
MP3 in under a second; older devices get the synchronous loop. The
Kotlin analyzer takes results as they finish (a Channel), not in
library order, which once kept the Pixel 7a's footer at "0 of n" for
minutes, and gives up a file that takes longer than it plays
(`minTimeoutMillis` floor two minutes); half the cores, two to four,
run at once. Each file logs one line under the tag `Loudness`.
Measured on the Pixel 7a (2026-10-15, release build compiled with
`speed`): frame by frame one file a minute (the signed 0.9.0, which is
why "it does not work"); batched, four at once, 86 files a minute over
MP3, FLAC and WAV, no failures, an MP3 in 1 to 1.5 s and a FLAC in
about 3 s (the platform's FLAC extractor decodes to PCM in the
extractor process, so its cost shows up as "extract"). Two traps in
measuring that: the Pixel 7a has `dalvik.vm.usejit=false`, and an
`adb install` leaves the app `run-from-apk`, so everything runs
interpreted (the meter took 30 s of a 32 s file) until
`adb shell cmd package compile -m speed -f <package>`; a Play or
sideloaded install compiles with `speed` by itself (`cmd package dump
<package>` shows the "Dexopt state"). And a debug build is six times
slower than the release build even when compiled. A second instance for
such experiments goes on the phone with a temporary `applicationId`
(`de.schliweb.crate.probe`, pick the folder again in the picker, uninstall
after); the user's install and its data stay untouched. Verified: the
same MP3 measures the same LUFS and peak on the Mac and the emulator,
batched and frame by frame alike.
Recent tiles (both apps): Recently Added is `Track.recentlyAdded` /
`recentlyAdded()` over the album-ordered tracks (newest modification
day first, album order within a day, capped at 200), computed with
the grid on every refresh. Recently Played is `History` (ids newest
first, one per track, capped at 500) in `history.json` next to the
index; the Mac model records in `loadCurrent(autoplay:)` and on Play
(once per start, `recordedID`), Android's `PlaybackService` records
on item transitions and play while `playWhenReady` into the
process-wide `SharedHistory` the view model mirrors, so a restored,
paused playlist does not count. Both lists follow the search
(`listsChanged()` on the Mac); `Selection` has `.recentlyAdded` /
`.recentlyPlayed`, Android `LIBRARY_TILES` = 4. Scanning stays a
deliberate act: no folder watching, decided 2026-10-14. Verified on
both.
Artist view (both apps): no new navigation, a search. `SearchQuery`
takes "artist:Name" (`SearchQuery.artistPrefix`/`ARTIST_PREFIX`) and
then matches only the artist and album artist fields with the whole
phrase; `SearchIndex` keeps the folded artist fields apart for it. The
album header's artist name is a plain button (Mac) or clickable text
(Android) that sets the search text (`showAlbums(ofArtist:)`,
`showAlbumsOfArtist`); "Various Artists" is not clickable. Android's
screen watches the search text for the prefix to open the search bar
and, on a phone, return to the grid. Verified on both.
Lyrics (both apps): `Lyrics.parse` reads LRC (dot or colon
hundredths, several stamps per line, `[offset:]`, header tags dropped)
or plain text; `index(at:)`/`indexAt` gives the line being sung.
Looked up when a track starts, never at scan time (no Track field, no
index change): `FileLyricsSource` (Mac) and `SafLyricsSource`
(Android) try an `.lrc`, then a `.txt`, with the track's name beside it
or in a `lyrics/` subfolder, then the tags (Mac: FLACMetadata LYRICS,
AVFoundation USLT/©lyr; Android: FlacMetadata, `ParsedTags.lyrics`
from the MP3 USLT frame and the MP4 ©lyr atom). Android finds the
sidecar without a directory query by swapping the name in the
document id and opening it (`buildDocumentUriUsingTree` with the
track's own URI as the tree); on the storage provider the ids are
paths. UI: Mac pane beside the detail (`LyricsView`, `showsLyrics`,
⌥⌘L), Android `LyricsSheet`; the button exists only while
`lyrics != nil`. Counted first (`crate-scan --lyrics`, 2026-10-14):
few files had lyrics, of every kind (USLT, FLAC LYRICS, timed `.lrc`,
mostly in `lyrics/`), and some albums came with a plain `.txt` per
track, hence the `.txt` fallback. Whisper was first tried as the only
source and rejected: 23 to 55 % word error rate on sung vocals even
with the medium model and non-speech suppression, no verse-line
timing, while spoken word came out nearly perfect. Verified with a
timed `.lrc` on the Mac (pane, bold line moves) and on the emulator
(an MP3 and its `.lrc` under `/sdcard/Music`, sheet follows).
main.swift gotcha: a top-level `let` is initialised when execution
reaches its line, so the `--lyrics` branch runs below every
declaration.
Lyrics fetching (2026-10-16, `scripts/lyrics.py`, Python stdlib only):
text from lrclib.net (open database, no key; answers a third of all
requests with 503 whatever the pace, so retry after 0.7 s, no
back-off), stamps checked or made with whisper.cpp (brew, Metal) and
the large-v3-turbo ggml model: the known text is aligned to whisper's
DTW word times by a global Needleman-Wunsch over the two word
sequences (difflib's longest-block-first picked the wrong chorus), a
line's time is its first word's, stepped back at the track's word
rate when only a later word was heard, lines without heard words are
interpolated and count as unverified. An lrclib .lrc is "confirmed"
when 70 % of the checkable lines (at least five) lie within 1 s of the
aligned time after a constant offset (LRC stamps run 0.1 to 1.7 s
ahead of the singing by convention); plain text gets whisper's stamps
when 70 % of the lines were heard. Whisper findings: large-v3 is twice
as slow as turbo and hallucinates more; its language detection guesses
from the intro and transcribed a German song as Russian, so the
language comes from stop words in the lyrics text; `-sns -mc 0`
(suppress non-speech, no context between 30 s windows) is essential,
without it a quiet vocal gave a 4-minute loop of an invented caption
and nothing else; a looping phrase, seven words in a second or
three words on one instant are dropped as hallucinations unless the
lyrics repeat the phrase themselves (a refrain); the lyrics' opening
as `--prompt` is the fallback when under 30 % was heard (it hears more
but invents more). Existing lyrics files are judged the same way:
`--replace` swaps one that failed (an unrelated song filed under the
track's title) or a plain .txt for a confirmed .lrc. Verified on two
albums (confirmed and generated stamps, .txt upgraded, one wrong file
replaced); whisper's transcripts are cached in ~/.cache/crate-lyrics.
A full `--replace` run over a collection of about 2,000 files
(2026-10-16/17) took 5.5 hours under `caffeinate -i`; about a third
was not in the database, most found lyrics were confirmed, and some
existing files were replaced because the old .lrc matched under 30 %
of the audio while the new one matched nearly all of it. Traps
met on the way: urllib tries IPv6 first and waits the full timeout
when that route swallows SYNs (it did here, to Cloudflare), so the
script sorts IPv4 first (90 s per file became 18 s); whisper-cli
aborts (SIGABRT) on some files with `-dtw` plus `-mc 0`, and on some
once a `--prompt` is given, so both have fallbacks; a damaged AAC that
neither ffmpeg nor afconvert decodes just fails. `--time` was written
for lyrics that came as scanned PDFs: Vision OCR (a 40-line Swift tool,
scratch) read them, one song per page, the title line matched the file
names, and whisper stamped nearly all songs, dialect at 73 to 90 % of
the lines heard, the rest mostly at 100 %.
Cover fetching (2026-10-18, `scripts/covers.py`, Python stdlib only):
`crate-scan --covers` lists the albums the app shows without a cover
(ArtworkCache's rule: first three tracks' pictures, then the folder's
images) as JSON; the script searches MusicBrainz (1 request/s), fetches
up to six candidates' track lists and confirms one when 80 % of the
folder's tracks are found on it and at least half of the comparable
lengths agree within 5 s, or 70 % when at least eight tracks were found
and all their lengths agree (bonus tracks); releases with down to 70 %
of the folder's track count are candidates. Tracks pair by title, or by
position and length for "Track-01" placeholders and files without a
title tag (position from the track number, else the number in front of
the file name). The album title must reach 0.7 similarity (a short
title inside a long one counts only 0.7: a two-word title once matched
a podcast episode); a folder-name title loses "04_" and a trailing
year. Cover Art Archive front-1200, release group as fallback, iTunes
Search (lookup has track names and lengths, artwork URL scaled to
1200x1200bb; the artist check also accepts the artist inside the album
title, "(feat. Artist)") when MusicBrainz has no match or no image.
Writes cover.jpg/png into every folder holding the album's tracks
(the apps look next to the track; an album over CD1/CD2 subfolders once
got its cover in the folder above, where nothing finds it), never over
an existing one, never for a folder shared by two albums without a
cover. A single file as long as the whole release (an episode ripped as
one track) counts as that release, and a track far longer or shorter
than its namesake is not paired with it. Audiobooks: iTunes' audiobook
catalogue as the last resort, title and author only (0.9/0.8). Left
without a cover in practice: own compilations, bootlegs, broken tags,
releases with no image anywhere. Covers checked by eye after each run.
Tag repair (2026-10-18, `scripts/tags.py`): fills what is missing
and fixes what is provably broken, never restyles a good value.
Local: artist spellings unified (properly cased, small words lower
case inside, then the most common), "01 - " / "Artist - " in front of
titles removed, album artist where every track's artist tag agrees.
From the release confirmed as in covers.py (MusicBrainz, iTunes only
when MusicBrainz confirms nothing; `covers.pair` gives the track
pairing): year (MusicBrainz: the release group's first release),
album artist (iTunes: only when it is the album's artist), track/disc
numbers, missing album/artist, titles for placeholders, missing title
tags and values cut at 30 characters (28-30 chars and the release's
value begins with them). Track numbers come from a release only when
every track of the folder is on it and the number in front of the file
name does not disagree: a folder with bonus tracks has its own order.
Typographic quotes from MusicBrainz and iTunes' "(feat. ...)" become
plain: the apps' search folds case and accents, not ’. mutagen (in a
venv under ~/.cache/crate-tags, made on the first run, since
Homebrew's Python refuses global packages) writes in place; ID3 v2.3
stays v2.3 (the easy interface hides the version, so it is read from
the file); audio verified bit-identical by ffmpeg MD5 on MP3 and FLAC.
WAVs and MP4s named .mp3 (the apps go by the name and read no MP4
atoms there) are left alone. Spelling unification needs a second pass
after the first write (variants appear once MusicBrainz's spellings are
in); run until a dry run finds nothing.
Disc separators (both apps): `Album.discs` groups the sorted tracks
by `discNumber ?? 1`; the album page shows a "Disc n" line per disc
only when there is more than one (Mac: List sections with a
conditional header; Android: a header item per disc). Verified with a
two-disc album on both (CD1/CD2 folders under `/sdcard/Music` on the
emulator); `adb shell mkdir` does not quote, so paths there have no
spaces.
Resume on launch (both apps): `PlaybackState` (track ids in queue
order, asAlbum, cursor, position, shuffle, repeat) in two files next to
the index, `queue.json` written only when the ids change (All Tracks
is every id of the library) and `position.json` on every track or mode
change, pause, seek, quit and every ten seconds of playback. Mac:
`PlayerModel.savePlayback`/`restorePlayback`, the quit hook is
`AppDelegate.onTerminate`; restore loads and seeks the backend without
playing. Android: the view model saves from the controller's playlist
(never an empty one, which is what it sees before the restore) and
restores with `setMediaItems(items, index, position)` + `prepare()`,
which shows a paused media notification right away; the media items
come from `playback/MediaItems.kt`, shared with the service's
`onPlaybackResumption` (the system's resume after the process was
gone; written, not verified on a device). Verified: Mac quit and
relaunch at 1:15 of a track; Android force-stop at 0:15 came back at
0:09 (the last periodic save) and played on from there.

**Open, in no fixed order:**

1. Released: v0.1.0 (DMG only), v0.2.0 (DMG + signed APK), v0.3.0 to
   v0.10.0 (DMG + APK + AAB) are published at github.com/egdels/crate,
   built by `release.yml`. 0.6.0 to 0.6.2 (all 2026-10-14) are
   withdrawn: Android could not play untagged files (0.6.0, 0.6.1) and
   the Mac DMG crashed on Now Playing (all three); 0.6.3 was the first
   good one, its DMG verified on this Mac by download and a two-minute
   play, and 0.7.0 (Up Next, notification heart, resume, disc lines)
   followed the same day, its DMG verified the same way (two minutes
   of play with a cover, no crash report) and its APK signed with the
   same certificate as 0.6.3; 0.8.0 (lyrics, artist view, recent
   tiles) the same evening, DMG and APK verified the same way; 0.9.0
   (loudness analysis) followed, verified the same way (the DMG's app
   ran the analysis at about 100% CPU while playing, no crash report). 0.10.0 (batched
   loudness decoding, .txt lyrics) on 2026-10-16: the signed 0.9.0 on
   the Pixel 7a was the frame-by-frame build, one file a minute.
   For the next version: bump `Packaging/Info.plist` (both
   CFBundleShortVersionString and CFBundleVersion) and
   `android/gradle.properties` (appVersion), add the Play changelog
   `android/fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, turn the CHANGELOG's
   Unreleased section into the version's, push `main` and the tag only
   (`git push origin main vX.Y.Z`, never `--all` or `--mirror`: local
   branches hold pre-release history that stays local).
2. Not yet verified by ear: the gapless transition is covered by a test on
   the node clock, not by listening. Output device switching mid-track is
   handled in code but untested.
3. Android release: `release.yml` builds and signs the APK next to the
   DMG on a `v*` tag, with the developer's shared Android release key
   from the repository secrets `KEYSTORE_BASE64`, `SIGNING_KEY_ALIAS`,
   `SIGNING_KEY_PASSWORD`, `SIGNING_STORE_PASSWORD` (same names as the
   other apps; the developer stores them in GitHub). The tag must match
   both `Packaging/Info.plist` and `appVersion` in
   `android/gradle.properties`; bump both. The Pixel 7a runs the debug
   build of 2026-10-14 (target 37, favorites, loudness), installed after
   uninstalling the release-signed 0.5.0; the next signed release APK
   needs an uninstall there again (different signature), then the folder
   has to be picked again. The workflow ran green for v0.2.0-rc2 and
   v0.2.0; the APK's certificate was verified with apksigner to be the
   shared release key (same digest as the other apps), not the debug key.
4. Play Store listing: texts and graphics (en-US only, the app's UI is
   English) live in
   `android/fastlane/metadata/android/`, built by `scripts/store-assets.sh`
   from `scripts/icon.swift --play` and raw emulator screenshots in
   `build/store/raw/` (framed with captions by
   `scripts/store-screenshot.swift`). `android/fastlane/metadata/README.md`
   has the capture recipe (status bar demo mode, lock screen) and the
   answers for the Play Console forms. The screenshots were taken on the
   Pixel_9a, MoveAPiece_Tablet7 and MoveAPiece_Tablet10 AVDs with a
   test library of albums with covers under `/sdcard/Music` (not in
   the repo). `PRIVACY.md` is written for
   Play's User Data policy (publisher, contact route, every permission
   explained, no third parties), and the Android overflow menu shows it
   in a dialog (Play wants the policy in the app too, as link or text,
   verified in the policy text). The build copies the file from the
   repository root into the assets (`CopyPrivacyPolicyTask` in
   app/build.gradle.kts); it is the only place the policy is maintained.
   `ui/MarkdownDialog.kt` renders the small Markdown subset it and
   HELP.md use (headings, paragraphs, bullets, inline code/bold/italic):
   no tables or link syntax in those files. `release.yml` attaches the signed AAB next
   to the APK (checked for a META-INF signature block: the bundle task
   names its output the same either way and jarsigner -verify exits 0
   for an unsigned file). There is no release keystore in this repo or
   its notes, and no local build path for the AAB: releases come from
   the workflow. The dialog is a plain `Dialog` with its own
   Surface, not an `AlertDialog`: the latter sizes its text slot to the
   content and showed four lines in landscape. Its open state is
   `rememberSaveable`, so rotating keeps it open. Verified in portrait
   and landscape, light and dark, on the Pixel_9a emulator. The v0.3.0
   workflow ran green; the AAB's signer is the same certificate as the
   v0.2.0 APK (verified with keytool -printcert -jarfile) and it carries
   assets/PRIVACY.md. The Play Console entry was started on 2026-10-10
   (app created as de.schliweb.crate, "Crate: Offline Music Player",
   automatic integrity protection off on purpose: it would add Play-only
   checks to a GPL app whose APK is also on GitHub).
