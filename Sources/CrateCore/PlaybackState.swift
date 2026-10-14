// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// Where playback stood when the app last wrote it down: the queue as
/// track ids in insertion order, the cursor, the position in that
/// track, and the modes. The next launch comes up paused right there.
public struct PlaybackState: Codable, Equatable, Sendable {
    public var trackIDs: [String]
    /// Whether the queue was started as an album, which picks the album
    /// gain over the track gain.
    public var asAlbum: Bool
    /// Index into `trackIDs`.
    public var currentIndex: Int
    /// Seconds into the current track.
    public var position: TimeInterval
    public var isShuffled: Bool
    public var repeatMode: PlayQueue.RepeatMode

    public init(
        trackIDs: [String], asAlbum: Bool, currentIndex: Int, position: TimeInterval,
        isShuffled: Bool, repeatMode: PlayQueue.RepeatMode
    ) {
        self.trackIDs = trackIDs
        self.asAlbum = asAlbum
        self.currentIndex = currentIndex
        self.position = position
        self.isShuffled = isShuffled
        self.repeatMode = repeatMode
    }
}

public protocol PlaybackStateStore: Sendable {
    /// nil when nothing has been saved, or what was saved is unusable.
    func load() throws -> PlaybackState?
    func save(_ state: PlaybackState) throws
    func clear() throws
}

/// Two files next to the library's index, because the two halves change
/// at very different rates: `queue.json` holds the track ids (every
/// track of the library, when All Tracks is playing) and is rewritten
/// only when they change; `position.json` holds the cursor, the position
/// and the modes, a few dozen bytes written every few seconds of
/// playback.
public final class JSONPlaybackStateStore: PlaybackStateStore, @unchecked Sendable {
    public static let currentVersion = 1

    public let queueURL: URL
    public let positionURL: URL

    private let lock = NSLock()
    /// The queue half as last written, to skip the write when nothing
    /// in it changed.
    private var writtenQueue: QueueFile?
    /// For tests: how often `queue.json` was actually written.
    public private(set) var queueWrites = 0

    public init(directory: URL) {
        queueURL = directory.appendingPathComponent("queue.json")
        positionURL = directory.appendingPathComponent("position.json")
    }

    /// `~/Library/Application Support/<appName>/`.
    public static func defaultDirectory(appName: String) throws -> URL {
        try JSONLibraryStore.defaultFileURL(appName: appName).deletingLastPathComponent()
    }

    private struct QueueFile: Codable, Equatable {
        var version: Int
        var trackIDs: [String]
        var asAlbum: Bool
    }

    private struct PositionFile: Codable {
        var version: Int
        var currentIndex: Int
        var position: TimeInterval
        var isShuffled: Bool
        var repeatMode: PlayQueue.RepeatMode
    }

    public func load() throws -> PlaybackState? {
        lock.lock()
        defer { lock.unlock() }
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: queueURL.path), fileManager.fileExists(atPath: positionURL.path) else {
            return nil
        }
        let decoder = JSONDecoder()
        let queue = try decoder.decode(QueueFile.self, from: Data(contentsOf: queueURL))
        let position = try decoder.decode(PositionFile.self, from: Data(contentsOf: positionURL))
        guard queue.trackIDs.indices.contains(position.currentIndex) else { return nil }
        writtenQueue = queue
        return PlaybackState(
            trackIDs: queue.trackIDs, asAlbum: queue.asAlbum, currentIndex: position.currentIndex,
            position: max(0, position.position), isShuffled: position.isShuffled, repeatMode: position.repeatMode)
    }

    public func save(_ state: PlaybackState) throws {
        lock.lock()
        defer { lock.unlock() }
        try FileManager.default.createDirectory(
            at: queueURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        let queue = QueueFile(version: Self.currentVersion, trackIDs: state.trackIDs, asAlbum: state.asAlbum)
        if queue != writtenQueue {
            try encoder.encode(queue).write(to: queueURL, options: .atomic)
            writtenQueue = queue
            queueWrites += 1
        }
        let position = PositionFile(
            version: Self.currentVersion, currentIndex: state.currentIndex, position: state.position,
            isShuffled: state.isShuffled, repeatMode: state.repeatMode)
        try encoder.encode(position).write(to: positionURL, options: .atomic)
    }

    public func clear() throws {
        lock.lock()
        defer { lock.unlock() }
        for url in [queueURL, positionURL] where FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
        writtenQueue = nil
    }
}
