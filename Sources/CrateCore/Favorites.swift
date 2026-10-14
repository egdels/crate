// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// The tracks the user marked with a heart, as a set of track ids (file
/// paths). Kept apart from the library on purpose: a `Track` is rebuilt
/// whenever its file changes, and the library's index is megabytes that
/// should not be rewritten for one tap on a heart. Ids of files that are
/// gone stay in the set; they do no harm and the heart is still there
/// when a disconnected volume comes back. Only removing a folder from
/// the library drops the ids under it.
public struct Favorites: Equatable, Sendable {
    public private(set) var ids: Set<String>

    public init(ids: Set<String> = []) {
        self.ids = ids
    }

    public var isEmpty: Bool { ids.isEmpty }
    public var count: Int { ids.count }

    public func contains(_ track: Track) -> Bool { ids.contains(track.id) }

    /// Whether every one of the tracks is a favorite; false for none.
    public func containsAll(_ tracks: [Track]) -> Bool {
        !tracks.isEmpty && tracks.allSatisfy { ids.contains($0.id) }
    }

    /// Flips the track and returns its new state.
    @discardableResult
    public mutating func toggle(_ track: Track) -> Bool {
        if ids.contains(track.id) {
            ids.remove(track.id)
            return false
        }
        ids.insert(track.id)
        return true
    }

    public mutating func set(_ on: Bool, for tracks: [Track]) {
        for track in tracks {
            if on { ids.insert(track.id) } else { ids.remove(track.id) }
        }
    }

    /// The favorites among the given tracks, in the given order.
    public func filter(_ tracks: [Track]) -> [Track] {
        tracks.filter { ids.contains($0.id) }
    }

    /// Forgets every id under the folder, for when the folder leaves the
    /// library.
    public mutating func removeAll(under folder: URL) {
        let prefix = folder.standardizedFileURL.path
        let slashed = prefix.hasSuffix("/") ? prefix : prefix + "/"
        ids = ids.filter { !$0.hasPrefix(slashed) }
    }
}

public protocol FavoritesStore: Sendable {
    /// nil when nothing has been saved yet.
    func load() throws -> Favorites?
    func save(_ favorites: Favorites) throws
}

/// `favorites.json` next to the library's index: a version and the ids,
/// sorted so that the file is the same for the same set.
public struct JSONFavoritesStore: FavoritesStore {
    public static let currentVersion = 1

    public let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
    }

    /// `~/Library/Application Support/<appName>/favorites.json`.
    public static func defaultFileURL(appName: String) throws -> URL {
        try JSONLibraryStore.defaultFileURL(appName: appName)
            .deletingLastPathComponent()
            .appendingPathComponent("favorites.json")
    }

    private struct File: Codable {
        var version: Int
        var ids: [String]
    }

    public func load() throws -> Favorites? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        let data = try Data(contentsOf: fileURL)
        return Favorites(ids: Set(try JSONDecoder().decode(File.self, from: data).ids))
    }

    public func save(_ favorites: Favorites) throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        // Paths are readable as paths, not with every "/" escaped.
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let file = File(version: Self.currentVersion, ids: favorites.ids.sorted())
        try encoder.encode(file).write(to: fileURL, options: .atomic)
    }
}
