// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct LibraryTests {
    private func makeLibrary(_ dir: TemporaryDirectory, reader: FakeMetadataReader) -> Library {
        Library(
            store: JSONLibraryStore(fileURL: dir.url.appendingPathComponent("state/library.json")),
            metadataReader: reader,
            concurrency: 2)
    }

    @Test func firstScanAddsEverythingWithTitleFallback() async throws {
        let dir = try TemporaryDirectory()
        let music = dir.url.appendingPathComponent("music")
        let a = try dir.file("music/a.mp3")
        let b = try dir.file("music/b.m4a")
        let reader = FakeMetadataReader()
        await reader.set(a, TrackMetadata(title: " Real Title ", artist: "X", duration: 12))

        let library = makeLibrary(dir, reader: reader)
        let summary = try await library.addFolder(music)

        #expect(summary == Library.RescanSummary(added: 2))
        let tracks = await library.tracks
        #expect(tracks.map(\.title) == ["Real Title", "b"])
        #expect(tracks[0].artist == "X")
        #expect(tracks[0].duration == 12)
        #expect(tracks[1].url == b)
        #expect(await library.folders == [music.standardizedFileURL])
    }

    @Test func rescanSkipsUnchangedUpdatesChangedAndDropsMissing() async throws {
        let dir = try TemporaryDirectory()
        let music = dir.url.appendingPathComponent("music")
        let a = try dir.file("music/a.mp3")
        let b = try dir.file("music/b.mp3")
        let c = try dir.file("music/c.mp3")
        let reader = FakeMetadataReader()
        let library = makeLibrary(dir, reader: reader)
        try await library.addFolder(music)
        await reader.resetReads()

        // Same content, same size: must not be re-read.
        var summary = try await library.rescan()
        #expect(summary == Library.RescanSummary(unchanged: 3))
        #expect(await reader.reads.isEmpty)

        // b changes size, c disappears, d appears.
        try "longer".data(using: .utf8)!.write(to: b)
        try FileManager.default.removeItem(at: c)
        let d = try dir.file("music/d.mp3")
        await reader.set(b, TrackMetadata(title: "B2"))
        summary = try await library.rescan()
        #expect(summary == Library.RescanSummary(added: 1, updated: 1, removed: 1, unchanged: 1))
        let reread = await reader.reads
        #expect(Set(reread) == [b.path, d.path])
        let tracks = await library.tracks
        #expect(tracks.map(\.url) == [a, b, d])
        #expect(tracks[1].title == "B2")

        // A touched file with the same size is re-read too.
        await reader.resetReads()
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: 60)], ofItemAtPath: a.path)
        summary = try await library.rescan()
        #expect(summary == Library.RescanSummary(updated: 1, unchanged: 2))
        #expect(await reader.reads == [a.path])
    }

    @Test func unreadableFilesAreCountedAndKeepTheirOldEntry() async throws {
        let dir = try TemporaryDirectory()
        let music = dir.url.appendingPathComponent("music")
        let a = try dir.file("music/a.mp3")
        let b = try dir.file("music/b.mp3")
        let reader = FakeMetadataReader()
        await reader.fail(b)
        let library = makeLibrary(dir, reader: reader)

        var summary = try await library.addFolder(music)
        #expect(summary == Library.RescanSummary(added: 1, failedFiles: [b]))
        #expect(summary.failed == 1)
        #expect(await library.tracks.map(\.url) == [a])

        // a becomes unreadable after a change: its previous entry survives.
        await reader.fail(a)
        try "changed".data(using: .utf8)!.write(to: a)
        summary = try await library.rescan()
        #expect(summary == Library.RescanSummary(failedFiles: [a, b]))
        #expect(await library.tracks.map(\.url) == [a])
    }

    @Test func stateSurvivesReloadWithoutRereading() async throws {
        let dir = try TemporaryDirectory()
        let music = dir.url.appendingPathComponent("music")
        try dir.file("music/a.mp3")
        try dir.file("music/sub/b.mp3")
        let reader = FakeMetadataReader()
        try await makeLibrary(dir, reader: reader).addFolder(music)
        await reader.resetReads()

        let reloaded = makeLibrary(dir, reader: reader)
        try await reloaded.load()
        #expect(await reloaded.tracks.count == 2)
        #expect(await reloaded.folders == [music.standardizedFileURL])
        let summary = try await reloaded.rescan()
        #expect(summary == Library.RescanSummary(unchanged: 2))
        #expect(await reader.reads.isEmpty)
    }

    @Test func removeFolderDropsOnlyItsTracks() async throws {
        let dir = try TemporaryDirectory()
        let one = dir.url.appendingPathComponent("one")
        let two = dir.url.appendingPathComponent("two")
        try dir.file("one/a.mp3")
        try dir.file("two/b.mp3")
        // A sibling whose path merely starts with "one" must not be swept up.
        let onePlus = dir.url.appendingPathComponent("one-more")
        try dir.file("one-more/c.mp3")
        let library = makeLibrary(dir, reader: FakeMetadataReader())
        try await library.addFolder(one)
        try await library.addFolder(two)
        try await library.addFolder(onePlus)

        let removed = try await library.removeFolder(one)
        #expect(removed == 1)
        #expect(Set(await library.tracks.map(\.title)) == ["b", "c"])
        #expect(await library.folders == [two, onePlus].map(\.standardizedFileURL))
    }

    @Test func addingAFolderTwiceDoesNotDuplicate() async throws {
        let dir = try TemporaryDirectory()
        let music = dir.url.appendingPathComponent("music")
        try dir.file("music/a.mp3")
        let library = makeLibrary(dir, reader: FakeMetadataReader())
        try await library.addFolder(music)
        try await library.addFolder(music)
        #expect(await library.folders.count == 1)
        #expect(await library.tracks.count == 1)
    }

    @Test func albumsComeFromTracks() async throws {
        let dir = try TemporaryDirectory()
        let music = dir.url.appendingPathComponent("music")
        let a = try dir.file("music/a.mp3")
        let b = try dir.file("music/b.mp3")
        let reader = FakeMetadataReader()
        await reader.set(a, TrackMetadata(title: "One", artist: "Band", album: "LP", trackNumber: 2))
        await reader.set(b, TrackMetadata(title: "Two", artist: "Band", album: "LP", trackNumber: 1))
        let library = makeLibrary(dir, reader: reader)
        try await library.addFolder(music)
        let albums = await library.albums
        #expect(albums.count == 1)
        #expect(albums[0].tracks.map(\.title) == ["Two", "One"])
    }

    @Test func probeReadsAFileWithoutAddingIt() async throws {
        let dir = try TemporaryDirectory()
        let file = try dir.wav("loose/song.wav", seconds: 1)
        let reader = FakeMetadataReader()
        await reader.set(file, TrackMetadata(artist: "Someone", duration: 1))
        let library = Library(store: JSONLibraryStore(fileURL: dir.url.appendingPathComponent("lib.json")), metadataReader: reader)

        let track = try await library.probe(file)
        #expect(track.title == "song")
        #expect(track.artist == "Someone")
        #expect(track.fileSize > 0)
        #expect(await library.tracks.isEmpty)
        #expect(await library.folders.isEmpty)
    }

    @Test func rescanReportsReadProgress() async throws {
        let dir = try TemporaryDirectory()
        for i in 1...5 { try dir.file("a/\(i).mp3") }
        let library = Library(store: JSONLibraryStore(fileURL: dir.url.appendingPathComponent("lib.json")), metadataReader: FakeMetadataReader(), concurrency: 2)
        actor Seen { var values: [(Int, Int)] = []; func add(_ r: Int, _ t: Int) { values.append((r, t)) } }
        let seen = Seen()
        try await library.addFolder(dir.url.appendingPathComponent("a")) { read, total in
            Task { await seen.add(read, total) }
        }
        try await Task.sleep(nanoseconds: 50000000)
        let values = await seen.values
        #expect(values.first.map { $0.0 } == 0)
        #expect(values.allSatisfy { $0.1 == 5 })
        #expect(values.map(\.0).max() == 5)
    }
}
