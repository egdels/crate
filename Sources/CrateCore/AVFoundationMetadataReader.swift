// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AVFoundation
import Foundation

/// Tag reader on top of AVFoundation, which covers every format
/// `LibraryScanner.defaultExtensions` lists without a third-party decoder,
/// except FLAC: that goes through `FLACMetadata`, because AVFoundation
/// returns no Vorbis comments for it on macOS 12.
///
/// Title, artist and album come from the common key space, which
/// AVFoundation fills for ID3 and iTunes/MP4. Track and disc numbers,
/// album artist, year and genre have no common key at all, so every field
/// is looked up by its ID3 and iTunes identifiers first and then by raw
/// key name, which also catches other containers' Vorbis-style keys.
/// Checked against real MP3, M4A and FLAC files; other containers are
/// best effort.
public struct AVFoundationMetadataReader: MetadataReader {
    public init() {}

    public func read(_ url: URL) async throws -> TrackMetadata {
        // FLAC goes through its own parser: AVFoundation on macOS 12 sees
        // none of its tags, and the file format is trivial to read.
        if url.pathExtension.lowercased() == "flac", let flac = try? FLACMetadata.read(url) {
            return Self.metadata(from: flac)
        }
        let asset = AVURLAsset(url: url)
        let (duration, common, all) = try await asset.load(.duration, .commonMetadata, .metadata)

        var meta = TrackMetadata(duration: duration.seconds.isFinite ? duration.seconds : 0)
        // Containers that carry Vorbis-style comments (key space "vorb",
        // keys TITLE, ARTIST, ALBUM, ...) leave the common key space
        // empty, so each common lookup has a key-name fallback.
        meta.title = await string(common, [.commonIdentifierTitle])
        if meta.title == nil {
            meta.title = await string(all, matchingKey: ["title"])
        }
        meta.artist = await string(common, [.commonIdentifierArtist])
        if meta.artist == nil {
            meta.artist = await string(all, matchingKey: ["artist", "performer"])
        }
        meta.album = await string(common, [.commonIdentifierAlbumName])
        if meta.album == nil {
            meta.album = await string(all, matchingKey: ["album"])
        }

        meta.albumArtist = await string(all, [.id3MetadataBand, .iTunesMetadataAlbumArtist])
        if meta.albumArtist == nil {
            meta.albumArtist = await string(all, matchingKey: ["albumartist", "album_artist", "band", "aart"])
        }
        meta.genre = await genre(all, [.id3MetadataContentType, .iTunesMetadataUserGenre, .iTunesMetadataPredefinedGenre])
        if meta.genre == nil {
            meta.genre = await genre(all, matchingKey: ["genre", "gnre"])
        }
        // Order matters: AVFoundation maps ID3v2.3's TDAT (day and month,
        // "0805") onto the common creation date, which would read as the
        // year 805. The real year lives in TYER (v2.3) or TDRC (v2.4), so
        // those come first and the common key is only a last resort.
        meta.year = await year(all, [.id3MetadataRecordingTime, .id3MetadataYear, .iTunesMetadataReleaseDate])
        if meta.year == nil {
            meta.year = await year(all, matchingKey: ["date", "year", "originaldate"])
        }
        if meta.year == nil {
            meta.year = await year(common, [.commonIdentifierCreationDate])
        }

        var track = await number(all, [.id3MetadataTrackNumber, .iTunesMetadataTrackNumber])
        if track == nil {
            track = await number(all, matchingKey: ["tracknumber", "track", "trkn"])
        }
        meta.trackNumber = track?.number
        meta.trackTotal = track?.total
        if meta.trackTotal == nil {
            meta.trackTotal = await string(all, matchingKey: ["tracktotal", "totaltracks"]).flatMap(Self.int)
        }

        var disc = await number(all, [.id3MetadataPartOfASet, .iTunesMetadataDiscNumber])
        if disc == nil {
            disc = await number(all, matchingKey: ["discnumber", "disc", "disk"])
        }
        meta.discNumber = disc?.number
        meta.discTotal = disc?.total
        if meta.discTotal == nil {
            meta.discTotal = await string(all, matchingKey: ["disctotal", "totaldiscs"]).flatMap(Self.int)
        }

        meta.composer = await string(all, [.id3MetadataComposer, .iTunesMetadataComposer])
        if meta.composer == nil {
            meta.composer = await string(all, matchingKey: ["composer", "wrt", "tcom"])
        }
        // ID3 has no identifier for TCMP (an iTunes extension); the key
        // name catches it, and "compilation" the Vorbis-style containers.
        meta.compilation = await flag(all, [.iTunesMetadataDiscCompilation])
        if !meta.compilation {
            meta.compilation = await flag(all, matchingKey: ["tcmp", "cpil", "compilation"])
        }
        meta.artistSort = await string(all, [.id3MetadataPerformerSortOrder])
        if meta.artistSort == nil {
            meta.artistSort = await string(all, matchingKey: ["tsop", "soar", "artistsort", "artist_sort"])
        }
        meta.albumArtistSort = await string(all, matchingKey: ["tso2", "soaa", "albumartistsort", "album_artist_sort"])
        meta.albumSort = await string(all, [.id3MetadataAlbumSortOrder])
        if meta.albumSort == nil {
            meta.albumSort = await string(all, matchingKey: ["tsoa", "soal", "albumsort", "album_sort"])
        }

        return meta
    }

    /// Vorbis comment keys as the common taggers write them.
    static func metadata(from flac: FLACMetadata) -> TrackMetadata {
        var meta = TrackMetadata(duration: flac.duration)
        meta.title = flac.value("TITLE")
        meta.artist = flac.value("ARTIST") ?? flac.value("PERFORMER")
        meta.album = flac.value("ALBUM")
        meta.albumArtist = flac.value("ALBUMARTIST") ?? flac.value("ALBUM ARTIST") ?? flac.value("ALBUM_ARTIST")
        meta.genre = flac.value("GENRE").map(genre(fromText:))
        meta.year = [flac.value("DATE"), flac.value("YEAR"), flac.value("ORIGINALDATE")].compactMap { $0 }.lazy.compactMap(year).first
        let track = flac.value("TRACKNUMBER").map(numberAndTotal)
        meta.trackNumber = track?.number
        meta.trackTotal = track?.total ?? flac.value("TRACKTOTAL").flatMap(int) ?? flac.value("TOTALTRACKS").flatMap(int)
        let disc = flac.value("DISCNUMBER").map(numberAndTotal)
        meta.discNumber = disc?.number
        meta.discTotal = disc?.total ?? flac.value("DISCTOTAL").flatMap(int) ?? flac.value("TOTALDISCS").flatMap(int)
        meta.composer = flac.value("COMPOSER")
        meta.compilation = flac.value("COMPILATION").map(flag(fromText:)) ?? false
        meta.artistSort = flac.value("ARTISTSORT")
        meta.albumArtistSort = flac.value("ALBUMARTISTSORT")
        meta.albumSort = flac.value("ALBUMSORT")
        return meta
    }

    /// "1", "true" or "yes", as the taggers write a set flag.
    static func flag(fromText text: String) -> Bool {
        ["1", "true", "yes"].contains(text.trimmed.lowercased())
    }

    /// "3/12" or "3".
    static func numberAndTotal(_ text: String) -> (number: Int?, total: Int?) {
        let parts = text.split(separator: "/", omittingEmptySubsequences: false).map { int(String($0)) }
        return (parts.first ?? nil, parts.count > 1 ? parts[1] : nil)
    }

    // MARK: - Lookup

    private func items(_ items: [AVMetadataItem], _ ids: [AVMetadataIdentifier]) -> [AVMetadataItem] {
        ids.flatMap { AVMetadataItem.metadataItems(from: items, filteredByIdentifier: $0) }
    }

    /// Items whose raw key name, stripped of key space prefixes and
    /// lowercased, equals one of `names`.
    private func items(_ items: [AVMetadataItem], matchingKey names: [String]) -> [AVMetadataItem] {
        items.filter { item in
            guard let key = Self.keyName(of: item) else { return false }
            return names.contains(key)
        }
    }

    private static func keyName(of item: AVMetadataItem) -> String? {
        let raw: String
        if let string = item.key as? String {
            raw = string
        } else if let identifier = item.identifier?.rawValue {
            raw = identifier
        } else {
            return nil
        }
        // "org.xiph.vorbis/TRACKNUMBER" or "itsk/aART" or "TRCK": keep the
        // last path component, drop a leading "©" (iTunes atoms) and any
        // "com.apple..." style dotted prefix.
        var name = raw.split(separator: "/").last.map(String.init) ?? raw
        name = name.split(separator: ".").last.map(String.init) ?? name
        name = name.replacingOccurrences(of: "©", with: "")
        return name.lowercased()
    }

    private func string(_ pool: [AVMetadataItem], _ ids: [AVMetadataIdentifier]) async -> String? {
        await firstString(in: items(pool, ids))
    }

    private func string(_ pool: [AVMetadataItem], matchingKey names: [String]) async -> String? {
        await firstString(in: items(pool, matchingKey: names))
    }

    private func firstString(in candidates: [AVMetadataItem]) async -> String? {
        for item in candidates {
            if let value = try? await item.load(.stringValue)?.trimmed.nonEmpty {
                return value
            }
            if let value = try? await item.load(.numberValue) {
                return value.stringValue
            }
        }
        return nil
    }

    private func flag(_ pool: [AVMetadataItem], _ ids: [AVMetadataIdentifier]) async -> Bool {
        await firstFlag(in: items(pool, ids))
    }

    private func flag(_ pool: [AVMetadataItem], matchingKey names: [String]) async -> Bool {
        await firstFlag(in: items(pool, matchingKey: names))
    }

    /// A set flag is "1" as text (ID3, Vorbis) or a non-zero number or
    /// byte (the iTunes cpil atom).
    private func firstFlag(in candidates: [AVMetadataItem]) async -> Bool {
        for item in candidates {
            if let text = try? await item.load(.stringValue)?.trimmed.nonEmpty {
                return Self.flag(fromText: text)
            }
            if let value = try? await item.load(.numberValue) {
                return value.intValue != 0
            }
            if let data = try? await item.load(.dataValue), let byte = data.last {
                return byte != 0
            }
        }
        return false
    }

    private func year(_ pool: [AVMetadataItem], _ ids: [AVMetadataIdentifier]) async -> Int? {
        await firstYear(in: items(pool, ids))
    }

    private func year(_ pool: [AVMetadataItem], matchingKey names: [String]) async -> Int? {
        await firstYear(in: items(pool, matchingKey: names))
    }

    private func firstYear(in candidates: [AVMetadataItem]) async -> Int? {
        for item in candidates {
            if let text = try? await item.load(.stringValue), let year = Self.year(text) {
                return year
            }
        }
        return nil
    }

    private func genre(_ pool: [AVMetadataItem], _ ids: [AVMetadataIdentifier]) async -> String? {
        await firstGenre(in: items(pool, ids))
    }

    private func genre(_ pool: [AVMetadataItem], matchingKey names: [String]) async -> String? {
        await firstGenre(in: items(pool, matchingKey: names))
    }

    /// Three encodings: plain text; ID3v2.3's "(17)" or "17" referring to
    /// the ID3v1 list (0-based); and iTunes' binary `gnre` atom, a 16-bit
    /// big-endian index into the same list, 1-based.
    private func firstGenre(in candidates: [AVMetadataItem]) async -> String? {
        for item in candidates {
            if let text = try? await item.load(.stringValue)?.trimmed.nonEmpty {
                return Self.genre(fromText: text)
            }
            if let data = try? await item.load(.dataValue), data.count >= 2 {
                let bytes = [UInt8](data)
                let index = Int(bytes[0]) << 8 | Int(bytes[1])
                if let name = ID3v1Genres.name(index: index - 1) {
                    return name
                }
            }
        }
        return nil
    }

    static func genre(fromText text: String) -> String {
        let trimmed = text.trimmed
        let digits = trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "()"))
        if let index = Int(digits), let name = ID3v1Genres.name(index: index) {
            return name
        }
        return trimmed
    }

    private func number(_ pool: [AVMetadataItem], _ ids: [AVMetadataIdentifier]) async -> (number: Int?, total: Int?)? {
        await firstNumber(in: items(pool, ids))
    }

    private func number(_ pool: [AVMetadataItem], matchingKey names: [String]) async -> (number: Int?, total: Int?)? {
        await firstNumber(in: items(pool, matchingKey: names))
    }

    /// Handles the three encodings in the wild: "3/12" (ID3, Vorbis),
    /// a bare number, and iTunes' 8-byte binary atom (reserved, number,
    /// total, reserved, all big-endian 16-bit).
    private func firstNumber(in candidates: [AVMetadataItem]) async -> (number: Int?, total: Int?)? {
        for item in candidates {
            if let text = try? await item.load(.stringValue)?.trimmed.nonEmpty {
                let parts = text.split(separator: "/", omittingEmptySubsequences: false).map { Self.int(String($0)) }
                if let first = parts.first, first != nil || parts.count > 1 {
                    return (first, parts.count > 1 ? parts[1] : nil)
                }
            }
            if let value = try? await item.load(.numberValue) {
                return (value.intValue, nil)
            }
            if let data = try? await item.load(.dataValue), data.count >= 6 {
                let bytes = [UInt8](data)
                let number = Int(bytes[2]) << 8 | Int(bytes[3])
                let total = Int(bytes[4]) << 8 | Int(bytes[5])
                return (number == 0 ? nil : number, total == 0 ? nil : total)
            }
        }
        return nil
    }

    // MARK: - Parsing

    static func int(_ text: String) -> Int? {
        Int(text.trimmed)
    }

    /// First run of four digits that is a plausible year: handles "1997",
    /// "1997-03-12", "1997-03-12T00:00:00Z" and the like, and rejects
    /// "0805" (an ID3 TDAT day-and-month) or "1741" (a TIME) rather than
    /// filing an album under the year 805.
    static func year(_ text: String) -> Int? {
        var digits = ""
        for character in text {
            if character.isNumber {
                digits.append(character)
                if digits.count == 4 {
                    if let year = Int(digits), (1850...2100).contains(year) { return year }
                    digits = ""
                }
            } else {
                digits = ""
            }
        }
        return nil
    }
}
