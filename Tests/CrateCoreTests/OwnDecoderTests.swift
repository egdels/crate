// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AVFoundation
import Foundation
import Testing
@testable import CrateCore

/// minimp3 and dr_flac against AVFoundation on the same files.
@Suite struct OwnDecoderTests {
    /// A 1 kHz tone, 1.5 s, mono, 22.05 kHz at 16 kbit/s, made with
    /// `ffmpeg -f lavfi -i sine=... -c:a libmp3lame`: AVFoundation
    /// writes no MP3.
    private static let toneMP3 = Data(base64Encoded: """
        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
        lPmaTMcLPJmM0mSwu1NCYjj/8yDE6wpQlhQAB7iJWIgVAQsQ0cYUIzdhgf7Z2Zi/bGQcQYv7+QcLlxpMkzG//Z7mOxAi//MgxOgK
        EJIYAAe2iZlG3d3/yzHPcRcWDIz//+f91ytfrX6v5zhIJDv8KrAJZSrhFUDSw//zIsTmCQCSIAAHuIlFvWCqg6V+DSw0W+GlB1bP
        ZdKy35aVTEFNRTMuMTAwVVVVVVVVVVVV//MgxOoK+IY9/g90plVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
        Vf/zIMTlBjh6ZpgIdhZVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/8yDE8wxQjhgBVuAAVVVVVVVVVVVV
        VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//MixOgRIO6BkY/IAFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
        VVVVVVVVVVX/8yDEywcAAUwBwAAAVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
        """, options: .ignoreUnknownCharacters)!

    private func flac(in directory: TemporaryDirectory, named name: String = "tone.flac") throws -> URL {
        let url = directory.url.appendingPathComponent(name)
        let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2)!
        let file = try AVAudioFile(forWriting: url, settings: [
            AVFormatIDKey: kAudioFormatFLAC, AVSampleRateKey: 44_100, AVNumberOfChannelsKey: 2, AVLinearPCMBitDepthKey: 16,
        ])
        let frames = AVAudioFrameCount(3 * 44_100)
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)!
        buffer.frameLength = frames
        for channel in 0..<2 {
            let samples = buffer.floatChannelData![channel]
            for frame in 0..<Int(frames) {
                // Two tones, the right channel quieter, swelling over time.
                let time = Double(frame) / 44_100
                samples[frame] = Float((channel == 0 ? 0.3 : 0.1) * (0.5 + time / 6) * sin(2 * .pi * (channel == 0 ? 440 : 2500) * time))
            }
        }
        try file.write(from: buffer)
        return url
    }

    @Test func mp3MeasuresAsAVFoundationDoes() throws {
        let directory = try TemporaryDirectory()
        let url = directory.url.appendingPathComponent("tone.mp3")
        try Self.toneMP3.write(to: url)
        let own = try #require(try LoudnessMeter.measureWithOwnDecoder(url))
        let reference = try #require(try LoudnessMeter.measureWithAVFoundation(url))
        // AVFoundation leaves out the encoder's lead-in; on a file this
        // short that moves the blocks.
        #expect(abs(try #require(own.loudness) - (try #require(reference.loudness))) < 0.3)
        #expect(abs(own.peak - reference.peak) < 0.01)
        #expect(try LoudnessMeter.measure(url) == own)
    }

    @Test func flacMeasuresAsAVFoundationDoes() throws {
        let directory = try TemporaryDirectory()
        let url = try flac(in: directory)
        let own = try #require(try LoudnessMeter.measureWithOwnDecoder(url))
        let reference = try #require(try LoudnessMeter.measureWithAVFoundation(url))
        #expect(abs(try #require(own.loudness) - (try #require(reference.loudness))) < 0.001)
        #expect(abs(own.peak - reference.peak) < 0.0001)
        #expect(try LoudnessMeter.measure(url) == own)
    }

    @Test func otherFormatsAndWronglyNamedFilesAreLeftToAVFoundation() throws {
        let directory = try TemporaryDirectory()
        let wav = try directory.wav("a.wav", seconds: 1) { _ in 0.25 }
        #expect(try LoudnessMeter.measureWithOwnDecoder(wav) == nil)
        // A WAV and a FLAC under the name .mp3: not MP3 streams.
        let named = directory.url.appendingPathComponent("b.mp3")
        try FileManager.default.copyItem(at: wav, to: named)
        #expect(try LoudnessMeter.measureWithOwnDecoder(named) == nil)
        let flacAsMP3 = directory.url.appendingPathComponent("c.mp3")
        try FileManager.default.copyItem(at: try flac(in: directory), to: flacAsMP3)
        #expect(try LoudnessMeter.measureWithOwnDecoder(flacAsMP3) == nil)
        // And noise under the name .flac.
        let noise = directory.url.appendingPathComponent("d.flac")
        try Data((0..<4096).map { UInt8(truncatingIfNeeded: $0 &* 31) }).write(to: noise)
        #expect(try LoudnessMeter.measureWithOwnDecoder(noise) == nil)
    }

    @Test func bytesThatAreNoStreamGiveNothing() throws {
        let directory = try TemporaryDirectory()
        let url = directory.url.appendingPathComponent("none.mp3")
        try Data(repeating: 0xff, count: 2000).write(to: url)
        #expect(try LoudnessMeter.measureWithOwnDecoder(url) == nil)
    }
}
