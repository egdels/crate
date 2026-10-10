// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AVFoundation
import Foundation

/// Embedded cover art via AVFoundation, with a folder image as fallback.
///
/// ID3 (APIC) and iTunes (covr) pictures arrive under the common artwork
/// key. FLAC's PICTURE block is read by `FLACMetadata` directly, since
/// AVFoundation does not expose it on every macOS version; the raw
/// Vorbis key is still tried for other containers. Files without an
/// embedded picture fall back to an image in the same folder: cover.jpg,
/// folder.jpg or similar first (how rippers and the Windows media players
/// leave album art), then anything named like a cover, then the largest
/// image there, which is usually a scan of the front and better than a
/// grey tile.
public struct AVFoundationArtworkReader: ArtworkReader {
    /// Lowercased file names tried in the track's folder, in order.
    public static let folderImageNames = [
        "cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png",
        "front.jpg", "front.jpeg", "front.png", "albumart.jpg", "albumartsmall.jpg", "album.jpg",
    ]

    public init() {}

    public func artwork(for url: URL) async throws -> Data? {
        if url.pathExtension.lowercased() == "flac", let flac = try? FLACMetadata.read(url) {
            if let cover = flac.cover {
                return cover.data
            }
            return Self.folderImage(nextTo: url)
        }
        if let embedded = try await embedded(in: url) {
            return embedded
        }
        return Self.folderImage(nextTo: url)
    }

    private func embedded(in url: URL) async throws -> Data? {
        let asset = AVURLAsset(url: url)
        let (common, all) = try await asset.load(.commonMetadata, .metadata)
        for item in AVMetadataItem.metadataItems(from: common, filteredByIdentifier: .commonIdentifierArtwork) {
            if let data = try? await item.load(.dataValue), !data.isEmpty {
                return data
            }
        }
        for item in all {
            guard let key = item.key as? String, key.uppercased() == "METADATA_BLOCK_PICTURE" else { continue }
            if let data = try? await item.load(.dataValue), !data.isEmpty {
                return data
            }
        }
        return nil
    }

    static let imageExtensions: Set<String> = ["jpg", "jpeg", "png"]

    /// Case-insensitive: "Cover.JPG" counts.
    static func folderImage(nextTo url: URL) -> Data? {
        let folder = url.deletingLastPathComponent()
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: folder.path) else { return nil }
        let images = names.filter { imageExtensions.contains(($0 as NSString).pathExtension.lowercased()) }
        guard !images.isEmpty else { return nil }
        let byLowercase = Dictionary(images.map { ($0.lowercased(), $0) }, uniquingKeysWith: { first, _ in first })
        var ordered: [String] = folderImageNames.compactMap { byLowercase[$0] }
        ordered += images.filter { name in
            let lower = name.lowercased()
            return !ordered.contains(name) && (lower.contains("cover") || lower.contains("front"))
        }
        let rest = images.filter { !ordered.contains($0) }
        ordered += rest.sorted { a, b in size(of: folder.appendingPathComponent(a)) > size(of: folder.appendingPathComponent(b)) }
        for name in ordered {
            if let data = try? Data(contentsOf: folder.appendingPathComponent(name)), !data.isEmpty {
                return data
            }
        }
        return nil
    }

    private static func size(of url: URL) -> Int {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
    }
}
