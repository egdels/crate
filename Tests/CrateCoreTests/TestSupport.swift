// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AVFoundation
import Foundation
@testable import CrateCore

/// Deterministic generator so shuffle tests are repeatable (SplitMix64).
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

/// A fresh, empty directory that is removed when the value goes away.
final class TemporaryDirectory: @unchecked Sendable {
    let url: URL

    init() throws {
        url = FileManager.default.temporaryDirectory
            .appendingPathComponent("CrateCoreTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit {
        try? FileManager.default.removeItem(at: url)
    }

    @discardableResult
    func file(_ relativePath: String, contents: String = "x") throws -> URL {
        let file = url.appendingPathComponent(relativePath)
        try FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try contents.data(using: .utf8)!.write(to: file)
        return file.standardizedFileURL
    }

    /// A real, playable, untagged WAV of silence.
    func wav(_ relativePath: String, seconds: Double = 1) throws -> URL {
        let file = url.appendingPathComponent(relativePath)
        try FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!
        let audioFile = try AVAudioFile(forWriting: file, settings: format.settings)
        let frames = AVAudioFrameCount(seconds * format.sampleRate)
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)!
        buffer.frameLength = frames
        try audioFile.write(from: buffer)
        return file.standardizedFileURL
    }
}

/// Serves canned metadata and counts reads, so library tests can assert
/// which files were (not) re-read.
actor FakeMetadataReader: MetadataReader {
    var metadata: [String: TrackMetadata] = [:]
    var failing: Set<String> = []
    private(set) var reads: [String] = []

    struct Unreadable: Error {}

    func set(_ url: URL, _ meta: TrackMetadata) { metadata[url.path] = meta }
    func fail(_ url: URL) { failing.insert(url.path) }
    func resetReads() { reads = [] }

    func read(_ url: URL) async throws -> TrackMetadata {
        reads.append(url.path)
        if failing.contains(url.path) { throw Unreadable() }
        return metadata[url.path] ?? TrackMetadata()
    }
}

func makeTrack(_ name: String, artist: String? = nil, album: String? = nil,
               albumArtist: String? = nil, track: Int? = nil, disc: Int? = nil, year: Int? = nil,
               folder: String = "/music") -> Track {
    Track(
        url: URL(fileURLWithPath: "\(folder)/\(name).mp3"),
        fileSize: 1, modified: Date(timeIntervalSince1970: 0),
        title: name, artist: artist, albumArtist: albumArtist, album: album,
        trackNumber: track, discNumber: disc, year: year, duration: 100)
}
