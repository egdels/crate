// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct LoudnessAnalyzerTests {
    private func store() -> (JSONLoudnessStore, URL) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("crate-loudness-\(UUID().uuidString).json")
        return (JSONLoudnessStore(fileURL: url), url)
    }

    private func measurement(_ loudness: Double) -> TrackLoudness {
        var histogram = LoudnessHistogram()
        histogram.add(power: pow(10, (loudness + 0.691) / 10))
        return TrackLoudness(loudness: loudness, peak: 0.5, histogram: histogram)
    }

    @Test func entriesAreOnlyFreshForTheFileAsItIs() {
        var loudness = Loudness()
        let track = makeTrack("a")
        loudness.set(measurement(-20), for: track)
        #expect(loudness.trackGain(for: track) == 4)
        var changed = track
        changed.modified = Date(timeIntervalSince1970: 99)
        #expect(loudness.measurement(for: changed) == nil)
        #expect(loudness.albumGain(for: [track, changed]) == nil)
        #expect(loudness.albumGain(for: [track]) != nil)
    }

    @Test func taggedTracksAreNotMeasured() {
        var tagged = makeTrack("t")
        tagged.albumGain = -3
        #expect(!Loudness.needsMeasuring(tagged))
        #expect(Loudness.needsMeasuring(makeTrack("u")))
    }

    @Test func storeRoundTrip() throws {
        let (store, url) = store()
        defer { try? FileManager.default.removeItem(at: url) }
        var loudness = Loudness()
        loudness.set(measurement(-15), for: makeTrack("a"))
        try store.save(loudness)
        let loaded = try store.load()!
        #expect(loaded.count == 1)
        #expect(abs(loaded.trackGain(for: makeTrack("a"))! - loudness.trackGain(for: makeTrack("a"))!) < 0.01)
    }

    @Test func runMeasuresWhatIsPendingAndSaves() async throws {
        let (store, url) = store()
        defer { try? FileManager.default.removeItem(at: url) }
        let tracks = (0..<25).map { makeTrack("t\($0)") }
        var tagged = makeTrack("tagged")
        tagged.albumGain = 0
        let analyzer = LoudnessAnalyzer(store: store, measure: { url in
            TrackLoudness(loudness: url.lastPathComponent.hasPrefix("t1") ? -10 : -20, peak: 0.1, histogram: LoudnessHistogram())
        }, concurrency: 3)
        nonisolated(unsafe) var last = LoudnessAnalyzer.Progress(done: 0, total: 0)
        let result = await analyzer.run(tracks: tracks + [tagged]) { progress, _ in last = progress }
        #expect(last == LoudnessAnalyzer.Progress(done: 25, total: 25))
        #expect(result.count == 25)
        #expect(result.trackGain(for: tracks[0]) == 4)
        #expect(result.trackGain(for: tracks[10]) == -6)
        #expect(result.measurement(for: tagged) == nil)
        #expect(try store.load()?.count == 25)
        // A second run has nothing left to do.
        #expect(await analyzer.pending(in: tracks + [tagged]).isEmpty)
    }

    @Test func runStopsWhenCancelled() async throws {
        let (store, url) = store()
        defer { try? FileManager.default.removeItem(at: url) }
        let tracks = (0..<40).map { makeTrack("t\($0)") }
        let analyzer = LoudnessAnalyzer(store: store, measure: { _ in
            Thread.sleep(forTimeInterval: 0.02)
            return TrackLoudness(loudness: -20, peak: 1, histogram: LoudnessHistogram())
        }, concurrency: 2)
        // Cancel once a few files are measured, not after a fixed time:
        // a slow CI runner may not have finished one by then.
        nonisolated(unsafe) var done = 0
        let task = Task { await analyzer.run(tracks: tracks) { progress, _ in done = progress.done } }
        for _ in 0..<500 where done < 3 {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        task.cancel()
        let result = await task.value
        #expect(result.count < 40)
        #expect(result.count > 0)
        #expect(try store.load()?.count == result.count)
    }

    @Test func silentAndUndecodableFilesAreNotPendingForever() async throws {
        let (store, url) = store()
        defer { try? FileManager.default.removeItem(at: url) }
        let silent = makeTrack("silent"), broken = makeTrack("broken")
        struct Undecodable: Error {}
        let analyzer = LoudnessAnalyzer(store: store, measure: { url in
            if url.lastPathComponent.hasPrefix("broken") { throw Undecodable() }
            return LoudnessMeter(sampleRate: 44_100, channelCount: 1).finalResult
        })
        let result = await analyzer.run(tracks: [silent, broken]) { _, _ in }
        #expect(result.measurement(for: silent)?.loudness == nil)
        #expect(result.isFresh(silent))
        #expect(result.trackGain(for: silent) == nil)
        #expect(await analyzer.pending(in: [silent, broken]).isEmpty)

        // A changed file is tried again.
        var changed = broken
        changed.modified = Date(timeIntervalSince1970: 99)
        #expect(await analyzer.pending(in: [changed]).count == 1)
    }

    @Test func cancellingReachesTheMeasurement() async throws {
        let (store, url) = store()
        defer { try? FileManager.default.removeItem(at: url) }
        final class Flag: @unchecked Sendable { var started = false; var sawCancel = false }
        let flag = Flag()
        let analyzer = LoudnessAnalyzer(store: store, measure: { _ in
            flag.started = true
            for _ in 0..<500 {
                if Task.isCancelled { flag.sawCancel = true; throw CancellationError() }
                Thread.sleep(forTimeInterval: 0.01)
            }
            return nil
        }, concurrency: 1)
        let task = Task { await analyzer.run(tracks: [makeTrack("long")]) { _, _ in } }
        for _ in 0..<500 where !flag.started {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        task.cancel()
        _ = await task.value
        #expect(flag.sawCancel)
        // A cancelled file is not given up: the next run measures it.
        #expect(await analyzer.pending(in: [makeTrack("long")]).count == 1)
    }
}
