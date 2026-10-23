// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

/// Real audio through the default output device, so these tests take a
/// few seconds of wall time and expect a device to exist.
@Suite(.serialized) @MainActor struct AVAudioEngineBackendTests {
    @Test func loadReportsDurationWithoutPlaying() throws {
        let dir = try TemporaryDirectory()
        let player = AVAudioEngineBackend()
        try player.load(try dir.wav("a.wav", seconds: 2))
        #expect(abs(player.duration - 2) < 0.01)
        #expect(!player.isPlaying)
        #expect(player.currentTime == 0)
    }

    @Test func seekMovesThePositionAndPlaybackFinishes() async throws {
        let dir = try TemporaryDirectory()
        let player = AVAudioEngineBackend()
        var finished = 0
        player.onFinished = { finished += 1 }
        try player.load(try dir.wav("a.wav", seconds: 2))
        player.seek(to: 1.5)
        #expect(abs(player.currentTime - 1.5) < 0.01)
        player.play()
        #expect(player.isPlaying)
        try await Task.sleep(nanoseconds: 1200000000)
        #expect(finished == 1)
        #expect(!player.isPlaying)
    }

    @Test func gainFollowsTheFileAcrossTheHandOver() async throws {
        let dir = try TemporaryDirectory()
        let player = AVAudioEngineBackend()
        var finished = 0
        player.onFinished = { finished += 1 }
        let first = try dir.wav("1.wav", seconds: 0.4)
        let second = try dir.wav("2.wav", seconds: 1)
        try player.load(first, gain: -6.5)
        #expect(player.currentGain == -6.5)
        player.preload(second, gain: 3)
        #expect(player.currentGain == -6.5, "the next file's gain waits for the hand-over")
        player.play()
        try await Task.sleep(nanoseconds: 700_000_000)
        #expect(finished == 1)
        try player.load(second, gain: 3)
        #expect(player.currentGain == 3)
        #expect(player.isPlaying)
        player.volume = 0.5
        #expect(player.currentGain == 3, "volume and gain are separate stages")
        try player.load(first, gain: 40)
        #expect(player.currentGain == 24, "clamped to the stage's range")
        player.stop()
    }

    @Test func preloadedFileContinuesWithoutRestart() async throws {
        let dir = try TemporaryDirectory()
        let player = AVAudioEngineBackend()
        var finished = 0
        player.onFinished = { finished += 1 }
        let first = try dir.wav("1.wav", seconds: 0.5)
        let second = try dir.wav("2.wav", seconds: 0.5)
        try player.load(first)
        player.preload(second)
        player.play()
        try await Task.sleep(nanoseconds: 750000000)
        #expect(finished == 1)
        #expect(player.isPlaying, "the preloaded file keeps the node running")

        // What the model does on onFinished: load the next one.
        try player.load(second)
        #expect(player.isPlaying)
        #expect(abs(player.duration - 0.5) < 0.01)
        #expect(player.currentTime > 0.1 && player.currentTime < 0.5)
        try await Task.sleep(nanoseconds: 500000000)
        #expect(finished == 2)
        #expect(!player.isPlaying)
    }

    @Test func skippingToThePreloadedFileRestartsIt() async throws {
        let dir = try TemporaryDirectory()
        let player = AVAudioEngineBackend()
        var finished = 0
        player.onFinished = { finished += 1 }
        try player.load(try dir.wav("1.wav", seconds: 2))
        let second = try dir.wav("2.wav", seconds: 0.5)
        player.preload(second)
        player.play()
        try await Task.sleep(nanoseconds: 300000000)

        // The user presses Next while the first file is still playing.
        try player.load(second)
        #expect(player.currentTime < 0.05)
        #expect(abs(player.duration - 0.5) < 0.01)
        player.play()
        try await Task.sleep(nanoseconds: 750000000)
        #expect(finished == 1, "only the second file finishes; the first was stopped")
    }

    @Test func stopDropsStaleCompletions() async throws {
        let dir = try TemporaryDirectory()
        let player = AVAudioEngineBackend()
        var finished = 0
        player.onFinished = { finished += 1 }
        try player.load(try dir.wav("a.wav", seconds: 0.3))
        player.play()
        player.stop()
        try await Task.sleep(nanoseconds: 500000000)
        #expect(finished == 0)
        #expect(player.duration == 0)
    }
}

extension AVAudioEngineBackendTests {
    @Test func tempoAndPitchBypassTheUnitOnlyWhenNeutral() throws {
        let dir = try TemporaryDirectory()
        let player = AVAudioEngineBackend()
        try player.load(try dir.wav("a.wav", seconds: 1))
        #expect(player.isTimePitchBypassed)
        player.setTempo(0.75, pitch: -150)
        #expect(player.tempo == 0.75)
        #expect(player.pitch == -150)
        #expect(!player.isTimePitchBypassed)
        try player.load(try dir.wav("b.wav", seconds: 1))
        #expect(player.tempo == 0.75, "tempo stays across loads")
        player.setTempo(1, pitch: 0)
        #expect(player.isTimePitchBypassed)
    }

    @Test func slowerTempoPlaysTheFileLonger() async throws {
        let dir = try TemporaryDirectory()
        let player = AVAudioEngineBackend()
        var finished = 0
        player.onFinished = { finished += 1 }
        try player.load(try dir.wav("a.wav", seconds: 0.5))
        player.setTempo(0.5, pitch: 0)
        player.play()
        try await Task.sleep(nanoseconds: 700_000_000)
        #expect(finished == 0, "half speed: 0.5 s of audio takes a second")
        #expect(player.currentTime > 0.25 && player.currentTime < 0.5)
        try await Task.sleep(nanoseconds: 600_000_000)
        #expect(finished == 1)
        player.stop()
    }

    @Test func loopRepeatsWithoutFinishing() async throws {
        let dir = try TemporaryDirectory()
        let player = AVAudioEngineBackend()
        var finished = 0
        player.onFinished = { finished += 1 }
        try player.load(try dir.wav("a.wav", seconds: 2))
        player.preload(try dir.wav("b.wav", seconds: 1))
        player.seek(to: 0.2)
        player.setLoop(PracticeLoop(start: 0.3, end: 0.6))
        #expect(player.loop == PracticeLoop(start: 0.3, end: 0.6))
        player.play()
        try await Task.sleep(nanoseconds: 2_200_000_000)
        #expect(finished == 0, "the loop never reaches the end of the file")
        #expect(player.isPlaying)
        #expect(player.currentTime >= 0.3 && player.currentTime < 0.6)
        player.setLoop(nil)
        let position = player.currentTime
        #expect(position >= 0.3 && position < 0.65)
        try await Task.sleep(nanoseconds: 2_000_000_000)
        #expect(finished == 1, "without the loop it plays on to the end")
        player.stop()
    }

    @Test func settingALoopBehindThePositionJumpsToItsStart() throws {
        let dir = try TemporaryDirectory()
        let player = AVAudioEngineBackend()
        try player.load(try dir.wav("a.wav", seconds: 2))
        player.seek(to: 1.5)
        player.setLoop(PracticeLoop(start: 0.5, end: 1))
        #expect(abs(player.currentTime - 0.5) < 0.01)
        player.seek(to: 0.2)
        #expect(abs(player.currentTime - 0.2) < 0.01, "before the loop it plays into it")
        #expect(player.loop != nil)
        try player.load(try dir.wav("b.wav", seconds: 1))
        #expect(player.loop == nil, "a new file starts without a loop")
    }
}
