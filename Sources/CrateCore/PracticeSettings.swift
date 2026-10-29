// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// How one track is practised: its tempo, its key, its tuning and the
/// passage that repeats. Remembered per file, so a song comes back the
/// way it was left; a track nobody touched has the neutral settings and
/// takes no room in the store.
public struct PracticeSettings: Codable, Equatable, Sendable {
    /// Playback speed, 1 = as recorded. Tempo and key are independent:
    /// slower does not mean lower.
    public var tempo: Double
    /// Transposition in semitones.
    public var semitones: Int
    /// Fine tuning in cents on top of the semitones, for a recording
    /// that is not at A = 440 Hz or an instrument that cannot follow it.
    public var cents: Int
    public var loop: PracticeLoop? {
        didSet { if loop == nil { loopOff = false } }
    }
    /// The loop is kept but not played: practising the passage in its
    /// context, then back to it, without setting A and B again.
    public var loopOff: Bool

    public static let tempoRange = 0.5...2.0
    public static let semitoneRange = -12...12
    public static let centRange = -50...50
    /// The steps the tempo moves in from the keyboard and the buttons.
    public static let tempoStep = 0.05
    public static let neutral = PracticeSettings()

    public init(
        tempo: Double = 1, semitones: Int = 0, cents: Int = 0, loop: PracticeLoop? = nil, loopOff: Bool = false
    ) {
        self.tempo = min(max(tempo, Self.tempoRange.lowerBound), Self.tempoRange.upperBound)
        self.semitones = min(max(semitones, Self.semitoneRange.lowerBound), Self.semitoneRange.upperBound)
        self.cents = min(max(cents, Self.centRange.lowerBound), Self.centRange.upperBound)
        self.loop = loop
        self.loopOff = loop != nil && loopOff
    }

    /// Clamps on the way in as well, so a hand-edited file cannot ask the
    /// audio unit for something it refuses.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            tempo: try container.decodeIfPresent(Double.self, forKey: .tempo) ?? 1,
            semitones: try container.decodeIfPresent(Int.self, forKey: .semitones) ?? 0,
            cents: try container.decodeIfPresent(Int.self, forKey: .cents) ?? 0,
            loop: try container.decodeIfPresent(PracticeLoop.self, forKey: .loop),
            loopOff: try container.decodeIfPresent(Bool.self, forKey: .loopOff) ?? false)
    }

    /// `loopOff` only when set, so a file without switched-off loops
    /// reads the same as before it existed.
    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(tempo, forKey: .tempo)
        try container.encode(semitones, forKey: .semitones)
        try container.encode(cents, forKey: .cents)
        try container.encodeIfPresent(loop, forKey: .loop)
        if loopOff { try container.encode(true, forKey: .loopOff) }
    }

    private enum CodingKeys: String, CodingKey {
        case tempo, semitones, cents, loop, loopOff
    }

    /// The loop that plays: nil while there is none or it is switched off.
    public var activeLoop: PracticeLoop? { loopOff ? nil : loop }

    /// Whether the track plays other than recorded: another sound, or a
    /// passage repeating.
    public var changesPlayback: Bool { changesSound || activeLoop != nil }

    /// The whole pitch shift in cents, what `AVAudioUnitTimePitch` takes.
    public var pitchCents: Double { Double(semitones * 100 + cents) }

    /// Whether the audio is played as recorded; the loop does not count.
    public var changesSound: Bool { tempo != 1 || semitones != 0 || cents != 0 }

    public var isNeutral: Bool { self == .neutral }

    /// The concert pitch the tuning amounts to: what an A4 in the
    /// recording sounds like after the cents, ignoring the semitones.
    public var referencePitch: Double { 440 * pow(2, Double(cents) / 1200) }

    /// Tempo, key and tuning back to the recording; the loop stays.
    public mutating func resetSound() {
        tempo = 1
        semitones = 0
        cents = 0
    }
}

/// A passage between two points in the file, in seconds.
public struct PracticeLoop: Codable, Equatable, Sendable {
    public let start: TimeInterval
    public let end: TimeInterval

    /// Shorter loops are a slip of the hand, not a passage.
    public static let minimumLength: TimeInterval = 0.2

    /// nil when the points are too close; reversed points are swapped.
    public init?(start: TimeInterval, end: TimeInterval) {
        let a = max(0, min(start, end))
        let b = max(start, end)
        guard b - a >= Self.minimumLength else { return nil }
        self.start = a
        self.end = b
    }

    public var length: TimeInterval { end - start }

    public func contains(_ time: TimeInterval) -> Bool { time >= start && time < end }

    /// The same loop fitted into a file of the given length; nil when
    /// nothing of it is left (a file that was replaced by a shorter one).
    public func clamped(to duration: TimeInterval) -> PracticeLoop? {
        guard duration > 0 else { return self }
        return PracticeLoop(start: min(start, duration), end: min(end, duration))
    }
}

/// Every track's practice settings, by track id (file path). Kept apart
/// from the library for the same reason as `Favorites`: the index is
/// rebuilt on rescans and is too big to rewrite for one turn of a knob.
public struct PracticeBook: Equatable, Sendable {
    public private(set) var entries: [String: PracticeSettings]

    public init(entries: [String: PracticeSettings] = [:]) {
        self.entries = entries.filter { !$0.value.isNeutral }
    }

    public var count: Int { entries.count }

    public func settings(for id: String) -> PracticeSettings { entries[id] ?? .neutral }

    public subscript(id: String) -> PracticeSettings {
        get { settings(for: id) }
        set { entries[id] = newValue.isNeutral ? nil : newValue }
    }

    /// An audiobook's tempo is the whole book's: sets it on the tracks
    /// that `Skip` treats as spoken word or long among the given ones (an
    /// album), leaving their other settings and every other track alone.
    public mutating func setTempo(_ tempo: Double, forBook tracks: [Track]) {
        for track in tracks where Skip.isOffered(for: track) {
            let old = self[track.id]
            self[track.id] = PracticeSettings(
                tempo: tempo, semitones: old.semitones, cents: old.cents, loop: old.loop, loopOff: old.loopOff)
        }
    }

    /// Forgets every track under the folder, for when the folder leaves
    /// the library.
    public mutating func removeAll(under folder: URL) {
        let prefix = folder.standardizedFileURL.path
        let slashed = prefix.hasSuffix("/") ? prefix : prefix + "/"
        entries = entries.filter { !$0.key.hasPrefix(slashed) }
    }
}

public protocol PracticeStore: Sendable {
    /// nil when nothing has been saved yet.
    func load() throws -> PracticeBook?
    func save(_ book: PracticeBook) throws
}

/// `practice.json` next to the library's index.
public struct JSONPracticeStore: PracticeStore {
    public static let currentVersion = 1

    public let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
    }

    /// `~/Library/Application Support/<appName>/practice.json`.
    public static func defaultFileURL(appName: String) throws -> URL {
        try JSONLibraryStore.defaultFileURL(appName: appName)
            .deletingLastPathComponent()
            .appendingPathComponent("practice.json")
    }

    private struct File: Codable {
        var version: Int
        var tracks: [String: PracticeSettings]
    }

    public func load() throws -> PracticeBook? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        let data = try Data(contentsOf: fileURL)
        return PracticeBook(entries: try JSONDecoder().decode(File.self, from: data).tracks)
    }

    public func save(_ book: PracticeBook) throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        try encoder.encode(File(version: Self.currentVersion, tracks: book.entries)).write(to: fileURL, options: .atomic)
    }
}
