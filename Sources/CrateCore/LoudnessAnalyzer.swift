// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// The measurements of the library's files, in `loudness.json` next to
/// the index: one entry per track id with the file's modification date,
/// so a changed file is measured again. Only files without ReplayGain
/// tags are measured; a tagged file keeps its tags. Nothing is written
/// into the music files.
public struct Loudness: Equatable, Sendable, Codable {
    public struct Entry: Equatable, Sendable, Codable {
        public var modified: Date
        public var measurement: TrackLoudness
    }

    public private(set) var entries: [String: Entry]

    public init(entries: [String: Entry] = [:]) {
        self.entries = entries
    }

    public var count: Int { entries.count }

    /// Whether the track is one the analysis measures: it has no
    /// ReplayGain tags (a file with those has an album gain).
    public static func needsMeasuring(_ track: Track) -> Bool { track.albumGain == nil }

    /// The measurement, when it is for the file as it is now.
    public func measurement(for track: Track) -> TrackLoudness? {
        guard let entry = entries[track.id], entry.modified == track.modified else { return nil }
        return entry.measurement
    }

    public func isFresh(_ track: Track) -> Bool { measurement(for: track) != nil }

    public mutating func set(_ measurement: TrackLoudness, for track: Track) {
        entries[track.id] = Entry(modified: track.modified, measurement: measurement)
    }

    /// The gain for one track on its own.
    public func trackGain(for track: Track) -> Double? { measurement(for: track)?.gain }

    /// The gain for the tracks played as one record, gated over all their
    /// blocks; nil unless every one of them is measured.
    public func albumGain(for tracks: [Track]) -> Double? {
        var measurements: [TrackLoudness] = []
        for track in tracks {
            guard let measurement = measurement(for: track) else { return nil }
            measurements.append(measurement)
        }
        return TrackLoudness.albumGain(measurements)
    }

    public mutating func removeAll(under folder: URL) {
        entries = entries.filter { !Library.isUnder(URL(fileURLWithPath: $0.key), folder) }
    }
}

public protocol LoudnessStore: Sendable {
    func load() throws -> Loudness?
    func save(_ loudness: Loudness) throws
}

public struct JSONLoudnessStore: LoudnessStore {
    public static let currentVersion = 1

    public let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
    }

    /// `~/Library/Application Support/<appName>/loudness.json`.
    public static func defaultFileURL(appName: String) throws -> URL {
        try JSONLibraryStore.defaultFileURL(appName: appName)
            .deletingLastPathComponent()
            .appendingPathComponent("loudness.json")
    }

    private struct File: Codable {
        var version: Int
        var entries: [String: Loudness.Entry]
    }

    public func load() throws -> Loudness? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        let data = try Data(contentsOf: fileURL)
        return Loudness(entries: try JSONDecoder().decode(File.self, from: data).entries)
    }

    public func save(_ loudness: Loudness) throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        try encoder.encode(File(version: Self.currentVersion, entries: loudness.entries)).write(to: fileURL, options: .atomic)
    }
}

/// Measures what the library has not measured yet, a few files at a
/// time at utility priority, and writes the results down every few
/// files, so a run that is cancelled (quit, a scan starting) picks up
/// where it was. Hours for a large collection; the app never waits for
/// it, playback uses whatever is measured by then.
public actor LoudnessAnalyzer {
    public typealias Measure = @Sendable (URL) throws -> TrackLoudness?

    public struct Progress: Equatable, Sendable {
        public var done: Int
        public var total: Int

        public init(done: Int, total: Int) {
            self.done = done
            self.total = total
        }
    }

    private let store: any LoudnessStore
    private let measure: Measure
    private let concurrency: Int
    public private(set) var loudness = Loudness()
    private var loaded = false
    /// Files that could not be measured (undecodable), by id, with the
    /// modification date they had: not tried again in this process
    /// until the file changes.
    private var unmeasurable: [String: Date] = [:]

    public init(store: any LoudnessStore, measure: @escaping Measure = LoudnessMeter.measure, concurrency: Int = 2) {
        self.store = store
        self.measure = measure
        self.concurrency = max(1, concurrency)
    }

    /// Reads the file once.
    public func load() -> Loudness {
        if !loaded {
            loudness = (try? store.load()) ?? Loudness()
            loaded = true
        }
        return loudness
    }

    public func removeAll(under folder: URL) {
        loudness.removeAll(under: folder)
        try? store.save(loudness)
    }

    /// The tracks a run would measure.
    public func pending(in tracks: [Track]) -> [Track] {
        tracks.filter { track in
            Loudness.needsMeasuring(track) && !loudness.isFresh(track) && unmeasurable[track.id] != track.modified
        }
    }

    /// Measures the pending tracks. `progress` is called after every
    /// file with the count and, every few files and at the end, the
    /// measurements so far. Returns when done or cancelled.
    public func run(tracks: [Track], progress: @escaping @Sendable (Progress, Loudness?) -> Void) async -> Loudness {
        _ = load()
        let pending = self.pending(in: tracks)
        let total = pending.count
        guard total > 0 else { return loudness }
        var done = 0
        var unsaved = 0
        let measure = self.measure
        await withTaskGroup(of: (Track, TrackLoudness??).self) { group in
            var iterator = pending.makeIterator()
            func enqueue() {
                guard !Task.isCancelled, let track = iterator.next() else { return }
                group.addTask(priority: .utility) {
                    // Detached, off the actor, but cancelled with the run:
                    // otherwise a cancelled run decoded its files to the end.
                    let task = Task.detached(priority: .utility) { try measure(track.url) }
                    let result = await withTaskCancellationHandler {
                        await task.result
                    } onCancel: {
                        task.cancel()
                    }
                    // nil: cancelled, try again next run; .some(nil): this
                    // file cannot be measured.
                    if Task.isCancelled { return (track, nil) }
                    return (track, .some(try? result.get()))
                }
            }
            for _ in 0..<concurrency { enqueue() }
            for await (track, outcome) in group {
                switch outcome {
                case .some(.some(let result)):
                    loudness.set(result, for: track)
                    unsaved += 1
                case .some(.none):
                    unmeasurable[track.id] = track.modified
                case .none:
                    break
                }
                done += 1
                if unsaved >= 10 {
                    try? store.save(loudness)
                    unsaved = 0
                    progress(Progress(done: done, total: total), loudness)
                } else {
                    progress(Progress(done: done, total: total), nil)
                }
                enqueue()
            }
        }
        if unsaved > 0 { try? store.save(loudness) }
        progress(Progress(done: done, total: total), loudness)
        return loudness
    }
}
