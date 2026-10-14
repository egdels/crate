// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct PlaybackStateTests {
    private func makeStore() -> (JSONPlaybackStateStore, URL) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("crate-playback-\(UUID().uuidString)", isDirectory: true)
        return (JSONPlaybackStateStore(directory: directory), directory)
    }

    private let state = PlaybackState(
        trackIDs: ["/m/a.mp3", "/m/b.mp3", "/m/c.mp3"], asAlbum: true, currentIndex: 1, position: 42.5,
        isShuffled: true, repeatMode: .all)

    @Test func nothingSavedIsNil() throws {
        let (store, _) = makeStore()
        #expect(try store.load() == nil)
    }

    @Test func roundTrip() throws {
        let (store, directory) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        try store.save(state)
        #expect(try JSONPlaybackStateStore(directory: directory).load() == state)
    }

    @Test func queueFileIsWrittenOnlyWhenTheQueueChanges() throws {
        let (store, directory) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        try store.save(state)
        var later = state
        later.position = 60
        later.currentIndex = 2
        try store.save(later)
        #expect(store.queueWrites == 1)
        later.trackIDs.append("/m/d.mp3")
        try store.save(later)
        #expect(store.queueWrites == 2)
        #expect(try store.load() == later)
    }

    @Test func staleIndexIsNil() throws {
        let (store, directory) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        var broken = state
        broken.currentIndex = 7
        try store.save(broken)
        #expect(try store.load() == nil)
    }

    @Test func clearRemovesBothFiles() throws {
        let (store, directory) = makeStore()
        defer { try? FileManager.default.removeItem(at: directory) }
        try store.save(state)
        try store.clear()
        #expect(!FileManager.default.fileExists(atPath: store.queueURL.path))
        #expect(!FileManager.default.fileExists(atPath: store.positionURL.path))
        #expect(try store.load() == nil)
    }
}
