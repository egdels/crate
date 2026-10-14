// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct LoudnessMeterTests {
    /// A sine at `dBFS` (peak), `seconds` long, stereo unless `channels`.
    private func sine(dBFS: Double, seconds: Double, sampleRate: Double, frequency: Double = 1000, channels: Int = 2) -> [[Float]] {
        let amplitude = pow(10, dBFS / 20)
        let frames = Int(seconds * sampleRate)
        let one = (0..<frames).map { Float(amplitude * sin(2 * .pi * frequency * Double($0) / sampleRate)) }
        return Array(repeating: one, count: channels)
    }

    @Test(arguments: [48_000.0, 44_100.0, 96_000.0])
    func aMinus23dBFSSineReadsMinus23LUFS(sampleRate: Double) {
        var meter = LoudnessMeter(sampleRate: sampleRate, channelCount: 2)
        meter.add(sine(dBFS: -23, seconds: 5, sampleRate: sampleRate))
        let result = meter.result!
        #expect(abs(result.loudness! - -23) < 0.1)
        #expect(abs(result.peak - pow(10, -23.0 / 20)) < 0.001)
        #expect(abs(result.gain! - 5) < 0.1)
    }

    @Test func samplesArriveInAnyChunking() {
        let signal = sine(dBFS: -20, seconds: 3, sampleRate: 48_000)
        var whole = LoudnessMeter(sampleRate: 48_000, channelCount: 2)
        whole.add(signal)
        var pieces = LoudnessMeter(sampleRate: 48_000, channelCount: 2)
        var offset = 0
        while offset < signal[0].count {
            let end = min(signal[0].count, offset + 1234)
            pieces.add(signal.map { Array($0[offset..<end]) })
            offset = end
        }
        #expect(abs(whole.result!.loudness! - pieces.result!.loudness!) < 0.01)
        #expect(abs(whole.result!.loudness! - -20) < 0.1)
    }

    @Test func silenceIsGatedOut() {
        var meter = LoudnessMeter(sampleRate: 48_000, channelCount: 2)
        meter.add(Array(repeating: Array(repeating: Float(0), count: 48_000 * 5), count: 2))
        #expect(meter.result == nil)
        meter.add(sine(dBFS: -23, seconds: 4, sampleRate: 48_000))
        // The blocks straddling the start of the tone are quieter and
        // still above the relative gate, so a few tenths are expected.
        #expect(abs(meter.result!.loudness! - -23) < 0.3)
    }

    @Test func quietPassagesFallUnderTheRelativeGate() {
        var meter = LoudnessMeter(sampleRate: 48_000, channelCount: 2)
        meter.add(sine(dBFS: -23, seconds: 10, sampleRate: 48_000))
        meter.add(sine(dBFS: -60, seconds: 10, sampleRate: 48_000))
        // The -60 dBFS half is more than 10 LU below the mean: ignored.
        #expect(abs(meter.result!.loudness! - -23) < 0.3)
    }

    @Test func monoCountsOnce() {
        var meter = LoudnessMeter(sampleRate: 48_000, channelCount: 1)
        meter.add(sine(dBFS: -23, seconds: 4, sampleRate: 48_000, channels: 1))
        #expect(abs(meter.result!.loudness! - -26.01) < 0.1)
    }

    @Test func albumGainIsGatedOverAllTracks() {
        var loud = LoudnessMeter(sampleRate: 48_000, channelCount: 2)
        loud.add(sine(dBFS: -20, seconds: 6, sampleRate: 48_000))
        var quiet = LoudnessMeter(sampleRate: 48_000, channelCount: 2)
        quiet.add(sine(dBFS: -26, seconds: 6, sampleRate: 48_000))
        var together = LoudnessMeter(sampleRate: 48_000, channelCount: 2)
        together.add(sine(dBFS: -20, seconds: 6, sampleRate: 48_000))
        together.add(sine(dBFS: -26, seconds: 6, sampleRate: 48_000))
        let album = TrackLoudness.albumGain([loud.result!, quiet.result!])!
        #expect(abs(album - together.result!.gain!) < 0.05)
        // Gain is the inverse of loudness: between the two tracks' gains.
        #expect(album > loud.result!.gain! && album < quiet.result!.gain!)
    }

    @Test func histogramRoundTripsThroughJSON() throws {
        var meter = LoudnessMeter(sampleRate: 48_000, channelCount: 2)
        meter.add(sine(dBFS: -23, seconds: 2, sampleRate: 48_000))
        let result = meter.result!
        let data = try JSONEncoder().encode(result)
        let decoded = try JSONDecoder().decode(TrackLoudness.self, from: data)
        // The power is rounded on the way out: equal to hundredths of a dB.
        #expect(decoded.histogram.bins.keys == result.histogram.bins.keys)
        #expect(decoded.histogram.blockCount == result.histogram.blockCount)
        #expect(abs(decoded.loudness! - result.loudness!) < 0.01)
        #expect(decoded.peak == result.peak)
    }
}
