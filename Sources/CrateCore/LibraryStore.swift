// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// Everything the library persists between launches.
public struct LibrarySnapshot: Codable, Equatable, Sendable {
    public static let currentVersion = 1

    public var version: Int
    public var folders: [URL]
    public var tracks: [Track]

    public init(version: Int = LibrarySnapshot.currentVersion, folders: [URL] = [], tracks: [Track] = []) {
        self.version = version
        self.folders = folders
        self.tracks = tracks
    }
}

public protocol LibraryStore: Sendable {
    /// nil when nothing has been saved yet.
    func load() throws -> LibrarySnapshot?
    func save(_ snapshot: LibrarySnapshot) throws
}

/// One JSON file, written atomically. Dates are stored as seconds since
/// the reference date (the Codable default), not ISO 8601, so that a
/// file's modification date survives the round trip at full precision and
/// the next rescan does not mistake every file for changed.
public struct JSONLibraryStore: LibraryStore {
    public let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
    }

    /// `~/Library/Application Support/<appName>/library.json`.
    public static func defaultFileURL(appName: String) throws -> URL {
        try FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent(appName, isDirectory: true)
            .appendingPathComponent("library.json")
    }

    public func load() throws -> LibrarySnapshot? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        let data = try Data(contentsOf: fileURL)
        return try JSONDecoder().decode(LibrarySnapshot.self, from: data)
    }

    public func save(_ snapshot: LibrarySnapshot) throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(snapshot).write(to: fileURL, options: .atomic)
    }
}
