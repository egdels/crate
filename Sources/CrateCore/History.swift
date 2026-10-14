// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// What was played, newest first: track ids, each once, capped. Kept
/// apart from the library like the favorites, in `history.json`, so a
/// track starting never rewrites the index. Ids of files that are gone
/// stay until a folder is removed.
public struct History: Equatable, Sendable {
    public static let limit = 500

    /// Newest first, no id twice.
    public private(set) var ids: [String]

    public init(ids: [String] = []) {
        var seen = Set<String>()
        self.ids = Array(ids.filter { seen.insert($0).inserted }.prefix(Self.limit))
    }

    public var isEmpty: Bool { ids.isEmpty }

    /// Puts the track in front; a repeat moves it, so the list stays
    /// one entry per track.
    public mutating func record(_ track: Track) {
        ids.removeAll { $0 == track.id }
        ids.insert(track.id, at: 0)
        if ids.count > Self.limit { ids.removeLast(ids.count - Self.limit) }
    }

    /// The tracks in the history's order, skipping ids no longer in the
    /// given tracks.
    public func tracks(from tracks: [Track]) -> [Track] {
        let byID = Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return ids.compactMap { byID[$0] }
    }

    public mutating func removeAll(under folder: URL) {
        ids.removeAll { Library.isUnder(URL(fileURLWithPath: $0), folder) }
    }
}

public protocol HistoryStore: Sendable {
    /// nil when nothing has been saved yet.
    func load() throws -> History?
    func save(_ history: History) throws
}

/// `history.json` next to the library's index: a version and the ids.
public struct JSONHistoryStore: HistoryStore {
    public static let currentVersion = 1

    public let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
    }

    /// `~/Library/Application Support/<appName>/history.json`.
    public static func defaultFileURL(appName: String) throws -> URL {
        try JSONLibraryStore.defaultFileURL(appName: appName)
            .deletingLastPathComponent()
            .appendingPathComponent("history.json")
    }

    private struct File: Codable {
        var version: Int
        var ids: [String]
    }

    public func load() throws -> History? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        let data = try Data(contentsOf: fileURL)
        return History(ids: try JSONDecoder().decode(File.self, from: data).ids)
    }

    public func save(_ history: History) throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes]
        try encoder.encode(File(version: Self.currentVersion, ids: history.ids)).write(to: fileURL, options: .atomic)
    }
}

extension Track {
    /// The newest files first, for a Recently Added list: by the file's
    /// modification day, newest day first, and within one day in the
    /// given (album) order, so an album copied in one go stays together.
    public static func recentlyAdded(_ tracks: [Track], limit: Int = 200, calendar: Calendar = .current) -> [Track] {
        let keyed = tracks.enumerated().map { (offset: $0.offset, day: calendar.startOfDay(for: $0.element.modified), track: $0.element) }
        return keyed.sorted { a, b in a.day != b.day ? a.day > b.day : a.offset < b.offset }
            .prefix(limit).map(\.track)
    }
}
