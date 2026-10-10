// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// A file the scanner found, with the two facts that decide whether it has
/// to be re-read: size and modification date.
public struct ScannedFile: Hashable, Sendable {
    public var url: URL
    public var fileSize: Int64
    public var modified: Date

    public init(url: URL, fileSize: Int64, modified: Date) {
        self.url = url
        self.fileSize = fileSize
        self.modified = modified
    }
}

/// Walks a folder recursively and lists the audio files in it. Pure file
/// system work, no tag reading; that is `MetadataReader`'s job.
public struct LibraryScanner: Sendable {
    /// What AVFoundation can decode on macOS. Ogg Vorbis and Opus are
    /// deliberately absent: the system cannot play them, so listing them
    /// would only produce tracks that fail on play.
    public static let defaultExtensions: Set<String> = [
        "mp3", "m4a", "aac", "flac", "wav", "aiff", "aif", "aifc", "caf",
    ]

    public var extensions: Set<String>

    public init(extensions: Set<String> = LibraryScanner.defaultExtensions) {
        self.extensions = Set(extensions.map { $0.lowercased() })
    }

    /// Regular files under `folder` whose extension matches, hidden files
    /// and package contents skipped, sorted by path so results are stable.
    public func scan(_ folder: URL) throws -> [ScannedFile] {
        // FileManager's enumerator stays silent on a missing folder and
        // simply yields nothing, which a caller could not tell apart from an
        // empty one, so check first.
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: folder.path, isDirectory: &isDirectory), isDirectory.boolValue else {
            throw CocoaError(.fileReadNoSuchFile, userInfo: [NSFilePathErrorKey: folder.path])
        }
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
        guard let enumerator = FileManager.default.enumerator(
            at: folder,
            includingPropertiesForKeys: Array(keys),
            options: [.skipsHiddenFiles, .skipsPackageDescendants]
        ) else {
            throw CocoaError(.fileReadNoSuchFile, userInfo: [NSFilePathErrorKey: folder.path])
        }

        var files: [ScannedFile] = []
        for case let url as URL in enumerator {
            guard extensions.contains(url.pathExtension.lowercased()) else { continue }
            let values = try url.resourceValues(forKeys: keys)
            guard values.isRegularFile == true else { continue }
            files.append(ScannedFile(
                url: url.standardizedFileURL,
                fileSize: Int64(values.fileSize ?? 0),
                modified: values.contentModificationDate ?? .distantPast
            ))
        }
        return files.sorted { $0.url.path < $1.url.path }
    }
}
