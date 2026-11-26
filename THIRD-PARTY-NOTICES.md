# Third-Party Notices

Crate is licensed under the GNU General Public License v3.0 or later
(see `LICENSE`).

## macOS

The SwiftPM package declares no dependencies. The app contains two
decoders as single C headers, minimp3 and dr_flac (the same files as the
Android app's, see below for authors and licenses), which decode MP3 and
FLAC files for the loudness measurement; playback uses AVFoundation.
Beyond those it links only frameworks that ship with macOS:

| Framework | Used for |
|---|---|
| AVFoundation | Reading tags, covers and audio; playback through `AVAudioEngine`, tempo and key through `AVAudioUnitTimePitch` |
| Accelerate | Reducing a track to its waveform, converting decoded samples |
| MediaPlayer | Now Playing information and the media keys |
| ImageIO, CoreGraphics | Decoding and scaling cover art |
| CryptoKit | Hashing album names for the cover cache file names |
| SwiftUI, AppKit | The user interface |

The frameworks are part of the operating system and are subject to
Apple's software license agreement for macOS; nothing from them is
redistributed with Crate.

The test suite uses Swift Testing, which is part of the Swift toolchain
(Apache License 2.0 with Runtime Library Exception) and is not shipped
with the app.

## Android

The Android app in `android/` bundles the following libraries, all under
the Apache License 2.0; the exact versions are in
`android/gradle/libs.versions.toml`:

| Library | Used for |
|---|---|
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization (JetBrains) | The language runtime, concurrency, the JSON library index |
| AndroidX Jetpack Compose, Material 3, Material Icons, Activity, Lifecycle (Google) | The user interface |
| AndroidX Media3: ExoPlayer and MediaSession (Google) | Playback, the media notification and session |
| Guava (Google), pulled in by Media3 | Futures |

The app also contains two decoders as single C headers under
`android/app/src/main/cpp/`, both unchanged: minimp3 by lieff
(`minimp3.h`, <https://github.com/lieff/minimp3>), dedicated to the
public domain under CC0 1.0, and dr_flac by David Reid (`dr_flac.h`,
v0.13.5, <https://github.com/mackron/dr_libs>), public domain
(Unlicense) or MIT No Attribution at the user's choice. They decode MP3
and FLAC files for the loudness measurement and the waveform; playback
uses Media3.

JUnit 4 (Eclipse Public License 1.0) and kotlinx-coroutines-test are used
by the tests only and are not shipped with the app. The platform's own
APIs (Storage Access Framework, MediaMetadataRetriever, MediaCodec) are
part of Android and subject to its license.
