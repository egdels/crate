// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import CoreGraphics
import CryptoKit
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Album thumbnails, scaled once and kept on disk so the grid never
/// decodes a 2 MB cover twice. Keyed by album, not file: the first track
/// that yields a picture decides the album's cover.
///
/// Three layers: a dictionary for what this process has already decoded,
/// JPEG files under `directory` for what any run has decoded, and the
/// `ArtworkReader` for everything else. An album with no cover is
/// remembered too (empty marker file), otherwise every launch would
/// re-read all its files only to find nothing again. A read that fails
/// (network share gone, file locked) is not remembered at all: the next
/// request for that album tries again.
public actor ArtworkCache {
    /// Longest edge of a cached thumbnail, in pixels. Enough for a 300 pt
    /// tile on a Retina display and for the Now Playing panel.
    public static let maxPixelSize = 600
    /// Files tried per album before giving up; a cover-less album with 40
    /// tracks on a network share must not cost 40 reads.
    static let tracksTried = 3

    public let directory: URL
    private let reader: any ArtworkReader
    private enum Lookup {
        case found(CGImage)
        case none
        case failed
    }

    private var memory: [Album.ID: CGImage?] = [:]
    private var inFlight: [Album.ID: Task<Lookup, Never>] = [:]

    public init(directory: URL, reader: any ArtworkReader) {
        self.directory = directory
        self.reader = reader
    }

    /// `~/Library/Application Support/<appName>/Artwork`.
    public static func defaultDirectory(appName: String) throws -> URL {
        try FileManager.default
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent(appName, isDirectory: true)
            .appendingPathComponent("Artwork", isDirectory: true)
    }

    /// The album's thumbnail, or nil when none of its files carries one.
    /// Concurrent callers for the same album share one lookup.
    public func thumbnail(for album: Album) async -> CGImage? {
        if let cached = memory[album.id] {
            return cached
        }
        if let task = inFlight[album.id] {
            if case .found(let image) = await task.value { return image }
            return nil
        }
        let task = Task<Lookup, Never> { await lookup(album) }
        inFlight[album.id] = task
        let result = await task.value
        inFlight[album.id] = nil
        switch result {
        case .found(let image):
            memory[album.id] = .some(image)
            return image
        case .none:
            memory[album.id] = .some(nil)
            return nil
        case .failed:
            return nil
        }
    }

    /// Drops the "no cover" markers so a rescan can pick up newly tagged
    /// albums. Found covers stay.
    public func forgetMissing() {
        memory = memory.filter { $0.value != nil }
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else { return }
        for name in names where name.hasSuffix(Self.missingSuffix) {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    // MARK: - Internals

    private static let missingSuffix = ".none"

    private func lookup(_ album: Album) async -> Lookup {
        let file = fileURL(for: album)
        if FileManager.default.fileExists(atPath: missingMarker(for: album).path) {
            return .none
        }
        if let image = Self.decode(contentsOf: file, maxPixelSize: Self.maxPixelSize) {
            return .found(image)
        }
        var failed = false
        for track in album.tracks.prefix(Self.tracksTried) {
            let data: Data?
            do {
                data = try await reader.artwork(for: track.url)
            } catch {
                failed = true
                continue
            }
            guard let data, let image = Self.decode(data, maxPixelSize: Self.maxPixelSize) else { continue }
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            Self.writeJPEG(image, to: file)
            return .found(image)
        }
        if failed {
            return .failed
        }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: missingMarker(for: album).path, contents: Data())
        return .none
    }

    func fileURL(for album: Album) -> URL {
        directory.appendingPathComponent(Self.fileName(for: album) + ".jpg")
    }

    private func missingMarker(for album: Album) -> URL {
        directory.appendingPathComponent(Self.fileName(for: album) + Self.missingSuffix)
    }

    /// Album ids carry arbitrary artist and title text; the file name is a
    /// hash of it, so slashes, length and case never matter.
    static func fileName(for album: Album) -> String {
        SHA256.hash(data: Data(album.id.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// Decodes and downsamples in one step: ImageIO's thumbnail path never
    /// materialises the full-size bitmap of a large cover.
    static func decode(_ data: Data, maxPixelSize: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return thumbnail(from: source, maxPixelSize: maxPixelSize)
    }

    static func decode(contentsOf url: URL, maxPixelSize: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        return thumbnail(from: source, maxPixelSize: maxPixelSize)
    }

    private static func thumbnail(from source: CGImageSource, maxPixelSize: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            kCGImageSourceShouldCache: false,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    static func writeJPEG(_ image: CGImage, to url: URL) {
        guard let destination = CGImageDestinationCreateWithURL(
            url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { return }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        CGImageDestinationFinalize(destination)
    }
}
