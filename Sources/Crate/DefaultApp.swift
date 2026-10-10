// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AppKit
import UniformTypeIdentifiers

/// Makes this copy of Crate what Finder opens audio files with. macOS has
/// no "default music player" switch; the binding is per file type, and
/// doing it by hand means Get Info > Change All once per type. This does
/// all of them in one go, via the same Launch Services call.
enum DefaultApp {
    /// The types Crate declares in Info.plist, minus folders: a folder
    /// double-clicked in Finder should keep opening in Finder.
    static let audioTypes: [UTType] = [
        "public.mp3", "public.mpeg-4-audio", "com.apple.m4a-audio", "org.xiph.flac",
        "com.microsoft.waveform-audio", "public.aiff-audio", "public.aifc-audio",
        "com.apple.coreaudio-format", "public.audio",
    ].compactMap(UTType.init)

    /// True when every audio type already opens with Crate. Compared by
    /// bundle identifier, not path: Launch Services binds a type to the
    /// identifier and picks the copy it prefers (the one in
    /// /Applications), so claiming from a build copy changes nothing and
    /// must not look like it did.
    static var opensAudioFiles: Bool {
        guard let me = Bundle.main.bundleIdentifier else { return false }
        return audioTypes.allSatisfy { type in
            NSWorkspace.shared.urlForApplication(toOpen: type)
                .flatMap(Bundle.init(url:))?.bundleIdentifier == me
        }
    }

    /// Binds every audio type to the running bundle. Returns the types
    /// that could not be bound, by their file extension or identifier.
    static func claimAudioFiles() async -> [String] {
        let me = Bundle.main.bundleURL
        var failed: [String] = []
        for type in audioTypes {
            let error: Error? = await withCheckedContinuation { continuation in
                NSWorkspace.shared.setDefaultApplication(at: me, toOpen: type) { continuation.resume(returning: $0) }
            }
            if error != nil {
                failed.append(type.preferredFilenameExtension ?? type.identifier)
            }
        }
        return failed
    }
}
