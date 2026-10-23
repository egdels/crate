// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct WaveformTests {
    @Test func peaksFollowTheLevelOverTime() throws {
        let dir = try TemporaryDirectory()
        // Quiet first second, loud second second.
        let url = try dir.wav("a.wav", seconds: 2) { $0 < 1 ? 0.25 : 0.75 }
        let waveform = try Waveform.read(url, slices: 100)
        #expect(waveform.peaks.count == 100)
        #expect(abs(waveform.duration - 2) < 0.01)
        #expect(waveform.peaks[..<50].allSatisfy { abs($0 - 0.25) < 0.001 })
        #expect(waveform.peaks[50...].allSatisfy { abs($0 - 0.75) < 0.001 })
        #expect(abs(waveform.maximum - 0.75) < 0.001)
        // A square wave's RMS is its level.
        #expect(waveform.levels[..<50].allSatisfy { abs($0 - 0.25) < 0.001 })
        #expect(waveform.levels[50...].allSatisfy { abs($0 - 0.75) < 0.001 })
        #expect(abs(waveform.maximumLevel - 0.75) < 0.001)
    }

    @Test func levelsTellLoudFromQuietWhereThePeaksDoNot() throws {
        let dir = try TemporaryDirectory()
        // Full-scale clicks on a quiet bed in the first second, a loud
        // bed in the second: the same peaks, very different energy.
        let url = try dir.wav("c.wav", seconds: 2) { t in
            let click = Int(t * 44_100) % 441 == 0
            return click ? 1 : (t < 1 ? 0.05 : 0.6)
        }
        let waveform = try Waveform.read(url, slices: 20)
        #expect(waveform.peaks.allSatisfy { $0 == 1 })
        #expect(waveform.levels[..<10].allSatisfy { $0 < 0.2 })
        #expect(waveform.levels[10...].allSatisfy { $0 > 0.55 })
    }

    @Test func silenceGivesZeroPeaksButAUsableMaximum() throws {
        let dir = try TemporaryDirectory()
        let waveform = try Waveform.read(try dir.wav("s.wav", seconds: 0.5), slices: 10)
        #expect(waveform.peaks == Array(repeating: 0, count: 10))
        #expect(waveform.levels == Array(repeating: 0, count: 10))
        #expect(waveform.maximum > 0 && waveform.maximumLevel > 0)
    }

    @Test func aShortFileGetsNoMoreSlicesThanFrames() throws {
        let dir = try TemporaryDirectory()
        let waveform = try Waveform.read(try dir.wav("t.wav", seconds: 0.0001) { _ in 1 }, slices: 1000)
        #expect(waveform.peaks.count == 4)
        #expect(waveform.peaks.allSatisfy { $0 == 1 })
    }
}
