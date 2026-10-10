# Third-Party Notices

Crate is licensed under the GNU General Public License v3.0 or later
(see `LICENSE`).

Crate has no third-party dependencies. The SwiftPM package declares none,
and the app links only frameworks that ship with macOS:

| Framework | Used for |
|---|---|
| AVFoundation | Reading tags, covers and audio; playback through `AVAudioEngine` |
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
