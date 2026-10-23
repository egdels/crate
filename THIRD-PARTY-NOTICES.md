# Third-Party Notices

Crate is licensed under the GNU General Public License v3.0 or later
(see `LICENSE`).

## macOS

The Mac app has no third-party dependencies. The SwiftPM package declares
none, and the app links only frameworks that ship with macOS:

| Framework | Used for |
|---|---|
| AVFoundation | Reading tags, covers and audio; playback through `AVAudioEngine`, tempo and key through `AVAudioUnitTimePitch` |
| Accelerate | Reducing a track to its waveform |
| MediaPlayer | Now Playing information and the media keys |
| ImageIO, CoreGraphics | Decoding and scaling cover art |
| CryptoKit | Hashing album names for the cover cache file names |
| SwiftUI, AppKit | The user interface |

These are part of the operating system and are subject to Apple's
software license agreement for macOS; nothing from them is redistributed
with Crate.

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

JUnit 4 (Eclipse Public License 1.0) and kotlinx-coroutines-test are used
by the tests only and are not shipped with the app. The platform's own
APIs (Storage Access Framework, MediaMetadataRetriever, MediaCodec) are
part of Android and subject to its license.
