// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// Where each audiobook was left: a position per file, for the tracks
/// `Skip` treats as spoken word or long. Playing such a file again
/// starts there; playing its album starts at the file heard last. A
/// file that plays to its end is done and starts from the top next
/// time. Kept apart from the index like the favorites, in
/// `bookmarks.json`; songs never get one.
public struct Bookmarks: Equatable, Sendable {
    public struct Mark: Codable, Equatable, Sendable {
        /// Seconds into the file.
        public var position: TimeInterval
        /// When it was set, seconds since 1970: the album resumes at
        /// its newest mark.
        public var saved: TimeInterval

        public init(position: TimeInterval, saved: TimeInterval) {
            self.position = position
            self.saved = saved
        }
    }

    /// The oldest marks go beyond this many.
    public static let limit = 1000
    /// Closer to the start than this is no place worth coming back to.
    static let minimumPosition: TimeInterval = 5
    /// Closer to the end than this, the file counts as heard.
    static let endMargin: TimeInterval = 10

    public private(set) var entries: [String: Mark]

    public init(entries: [String: Mark] = [:]) {
        self.entries = entries
    }

    public var count: Int { entries.count }

    /// Remembers the position for a spoken or long track; any other
    /// track is left out. Back at the very start, the mark goes: a file
    /// just begun (or begun again) is no place to come back to, and the
    /// last file of a finished book, reloaded at 0, must not become the
    /// album's newest mark.
    public mutating func set(_ track: Track, at position: TimeInterval, now: Date = Date()) {
        guard Skip.isOffered(for: track) else { return }
        guard position >= Self.minimumPosition else {
            entries[track.id] = nil
            return
        }
        entries[track.id] = Mark(position: position, saved: now.timeIntervalSince1970)
        if entries.count > Self.limit {
            let oldest = entries.sorted { $0.value.saved < $1.value.saved }.prefix(entries.count - Self.limit)
            for (id, _) in oldest { entries[id] = nil }
        }
    }

    /// The track was heard to its end.
    public mutating func finish(_ track: Track) {
        entries[track.id] = nil
    }

    /// Where to start the track: its mark, unless that is at the very
    /// start or so close to the end that it was as good as finished.
    public func position(for track: Track) -> TimeInterval? {
        guard Skip.isOffered(for: track), let mark = entries[track.id], mark.position >= Self.minimumPosition else {
            return nil
        }
        if track.duration > 0, mark.position > track.duration - Self.endMargin { return nil }
        return mark.position
    }

    /// The index of the track heard last among the given ones (an
    /// album), nil when none has a mark.
    public func resumeIndex(in tracks: [Track]) -> Int? {
        var best: (index: Int, saved: TimeInterval)?
        for (index, track) in tracks.enumerated() {
            guard let mark = entries[track.id], Skip.isOffered(for: track) else { continue }
            if best == nil || mark.saved > best!.saved { best = (index, mark.saved) }
        }
        guard let index = best?.index else { return nil }
        // Stopped in the last seconds of a file: on with the next one.
        let track = tracks[index]
        if position(for: track) == nil, index + 1 < tracks.count { return index + 1 }
        return index
    }

    /// Forgets every track under the folder, for when the folder leaves
    /// the library.
    public mutating func removeAll(under folder: URL) {
        let prefix = folder.standardizedFileURL.path
        let slashed = prefix.hasSuffix("/") ? prefix : prefix + "/"
        entries = entries.filter { !$0.key.hasPrefix(slashed) }
    }
}

public protocol BookmarksStore: Sendable {
    /// nil when nothing has been saved yet.
    func load() throws -> Bookmarks?
    func save(_ bookmarks: Bookmarks) throws
}

/// `bookmarks.json` next to the library's index.
public struct JSONBookmarksStore: BookmarksStore {
    public static let currentVersion = 1

    public let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
    }

    /// `~/Library/Application Support/<appName>/bookmarks.json`.
    public static func defaultFileURL(appName: String) throws -> URL {
        try JSONLibraryStore.defaultFileURL(appName: appName)
            .deletingLastPathComponent()
            .appendingPathComponent("bookmarks.json")
    }

    private struct File: Codable {
        var version: Int
        var tracks: [String: Bookmarks.Mark]
    }

    public func load() throws -> Bookmarks? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        let data = try Data(contentsOf: fileURL)
        return Bookmarks(entries: try JSONDecoder().decode(File.self, from: data).tracks)
    }

    public func save(_ bookmarks: Bookmarks) throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        try encoder.encode(File(version: Self.currentVersion, tracks: bookmarks.entries)).write(to: fileURL, options: .atomic)
    }
}
