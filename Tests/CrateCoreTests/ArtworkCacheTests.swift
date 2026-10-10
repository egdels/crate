// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import CrateCore

/// Hands out canned image bytes per file and counts the reads.
actor FakeArtworkReader: ArtworkReader {
    var images: [String: Data] = [:]
    var failing = false
    private(set) var reads: [String] = []

    struct Unreadable: Error {}

    func set(_ url: URL, _ data: Data) { images[url.path] = data }
    func setFailing(_ on: Bool) { failing = on }
    func resetReads() { reads = [] }

    func artwork(for url: URL) async throws -> Data? {
        reads.append(url.path)
        if failing { throw Unreadable() }
        return images[url.path]
    }
}

/// A solid PNG of the given size.
func pngData(width: Int, height: Int) -> Data {
    let space = CGColorSpaceCreateDeviceRGB()
    let context = CGContext(
        data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
        space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.setFillColor(CGColor(red: 0.2, green: 0.4, blue: 0.8, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    let image = context.makeImage()!
    let data = NSMutableData()
    let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, nil)
    CGImageDestinationFinalize(destination)
    return data as Data
}

@Suite struct ArtworkCacheTests {
    private func album(_ title: String, tracks: Int) -> Album {
        Album.group((1...tracks).map { makeTrack("\(title)-\($0)", artist: "A", album: title, track: $0) })[0]
    }

    @Test func firstTrackWithArtworkDecidesTheCover() async throws {
        let dir = try TemporaryDirectory()
        let reader = FakeArtworkReader()
        let cache = ArtworkCache(directory: dir.url, reader: reader)
        let album = album("X", tracks: 3)
        await reader.set(album.tracks[1].url, pngData(width: 40, height: 20))

        let image = try #require(await cache.thumbnail(for: album))
        #expect(image.width == 40 && image.height == 20)
        #expect(await reader.reads == [album.tracks[0].url.path, album.tracks[1].url.path])
        #expect(FileManager.default.fileExists(atPath: dir.url.appendingPathComponent(ArtworkCache.fileName(for: album) + ".jpg").path))
    }

    @Test func largeCoversAreDownsampled() async throws {
        let dir = try TemporaryDirectory()
        let reader = FakeArtworkReader()
        let cache = ArtworkCache(directory: dir.url, reader: reader)
        let album = album("Big", tracks: 1)
        await reader.set(album.tracks[0].url, pngData(width: 1500, height: 1000))

        let image = try #require(await cache.thumbnail(for: album))
        #expect(image.width == ArtworkCache.maxPixelSize)
        #expect(image.height == ArtworkCache.maxPixelSize * 2 / 3)
    }

    @Test func diskCacheSurvivesANewInstance() async throws {
        let dir = try TemporaryDirectory()
        let reader = FakeArtworkReader()
        let album = album("Y", tracks: 1)
        await reader.set(album.tracks[0].url, pngData(width: 10, height: 10))
        _ = await ArtworkCache(directory: dir.url, reader: reader).thumbnail(for: album)
        await reader.resetReads()

        let again = ArtworkCache(directory: dir.url, reader: FakeArtworkReader())
        #expect(await again.thumbnail(for: album) != nil)
        #expect(await reader.reads.isEmpty)
    }

    @Test func missingCoverIsRememberedUntilForgotten() async throws {
        let dir = try TemporaryDirectory()
        let reader = FakeArtworkReader()
        let cache = ArtworkCache(directory: dir.url, reader: reader)
        let album = album("None", tracks: 10)

        #expect(await cache.thumbnail(for: album) == nil)
        #expect(await reader.reads.count == ArtworkCache.tracksTried)

        await reader.resetReads()
        let again = ArtworkCache(directory: dir.url, reader: reader)
        #expect(await again.thumbnail(for: album) == nil)
        #expect(await reader.reads.isEmpty, "the marker file must stop the lookup")

        await reader.set(album.tracks[0].url, pngData(width: 10, height: 10))
        await again.forgetMissing()
        #expect(await again.thumbnail(for: album) != nil)
    }

    @Test func readErrorsAreNotRememberedAsNoCover() async throws {
        let dir = try TemporaryDirectory()
        let reader = FakeArtworkReader()
        let cache = ArtworkCache(directory: dir.url, reader: reader)
        let album = album("Flaky", tracks: 2)
        await reader.setFailing(true)
        #expect(await cache.thumbnail(for: album) == nil)
        #expect(try FileManager.default.contentsOfDirectory(atPath: dir.url.path).isEmpty, "no marker file")

        await reader.setFailing(false)
        await reader.set(album.tracks[0].url, pngData(width: 10, height: 10))
        #expect(await cache.thumbnail(for: album) != nil, "the next request reads again")
    }

    @Test func anyFolderImageIsTheLastResort() throws {
        let dir = try TemporaryDirectory()
        let track = try dir.wav("album/01.wav")
        try pngData(width: 20, height: 20).write(to: dir.url.appendingPathComponent("album/Back Scan.png"))
        try pngData(width: 8, height: 8).write(to: dir.url.appendingPathComponent("album/My Album Cover.png"))
        let data = try #require(AVFoundationArtworkReader.folderImage(nextTo: track))
        #expect(ArtworkCache.decode(data, maxPixelSize: 100)?.width == 8, "a name with 'cover' beats a larger file")

        try FileManager.default.removeItem(at: dir.url.appendingPathComponent("album/My Album Cover.png"))
        try pngData(width: 4, height: 4).write(to: dir.url.appendingPathComponent("album/tiny.png"))
        let largest = try #require(AVFoundationArtworkReader.folderImage(nextTo: track))
        #expect(ArtworkCache.decode(largest, maxPixelSize: 100)?.width == 20, "otherwise the largest image")
    }

    @Test func folderImageIsTheFallback() throws {
        let dir = try TemporaryDirectory()
        let track = try dir.wav("album/01.wav")
        try pngData(width: 8, height: 8).write(to: dir.url.appendingPathComponent("album/Folder.PNG"))
        let data = try #require(AVFoundationArtworkReader.folderImage(nextTo: track))
        #expect(ArtworkCache.decode(data, maxPixelSize: 100)?.width == 8)
        #expect(AVFoundationArtworkReader.folderImage(nextTo: dir.url.appendingPathComponent("nowhere/x.wav")) == nil)
    }

    @Test func untaggedFileHasNoArtwork() async throws {
        let dir = try TemporaryDirectory()
        let track = try dir.wav("plain/01.wav")
        #expect(try await AVFoundationArtworkReader().artwork(for: track) == nil)
    }
}
