// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// One audio file in the library, with the tags that matter for browsing
/// and playing it. Identified by its file path: the library never holds the
/// same file twice.
public struct Track: Identifiable, Hashable, Codable, Sendable {
    public var id: String { url.path }

    public var url: URL
    /// Size and modification date as seen at the last scan. A file whose
    /// size and date are unchanged is not re-read on the next rescan.
    public var fileSize: Int64
    public var modified: Date

    /// Never empty: falls back to the file name without extension when the
    /// file carries no title tag.
    public var title: String
    public var artist: String?
    public var albumArtist: String?
    public var album: String?
    public var trackNumber: Int?
    public var trackTotal: Int?
    public var discNumber: Int?
    public var discTotal: Int?
    public var year: Int?
    public var genre: String?
    /// Seconds. 0 when the file reports no duration.
    public var duration: TimeInterval

    public init(
        url: URL,
        fileSize: Int64,
        modified: Date,
        title: String,
        artist: String? = nil,
        albumArtist: String? = nil,
        album: String? = nil,
        trackNumber: Int? = nil,
        trackTotal: Int? = nil,
        discNumber: Int? = nil,
        discTotal: Int? = nil,
        year: Int? = nil,
        genre: String? = nil,
        duration: TimeInterval = 0
    ) {
        self.url = url
        self.fileSize = fileSize
        self.modified = modified
        self.title = title
        self.artist = artist
        self.albumArtist = albumArtist
        self.album = album
        self.trackNumber = trackNumber
        self.trackTotal = trackTotal
        self.discNumber = discNumber
        self.discTotal = discTotal
        self.year = year
        self.genre = genre
        self.duration = duration
    }

    /// The artist an album is filed under: the album artist when tagged,
    /// otherwise the track artist.
    public var displayArtist: String? { albumArtist ?? artist }
}

/// What a `MetadataReader` extracts from one file. Everything is optional
/// because every field may be missing from the tags; `Library` turns this
/// into a `Track` by adding the file facts and the title fallback.
public struct TrackMetadata: Hashable, Sendable {
    public var title: String?
    public var artist: String?
    public var albumArtist: String?
    public var album: String?
    public var trackNumber: Int?
    public var trackTotal: Int?
    public var discNumber: Int?
    public var discTotal: Int?
    public var year: Int?
    public var genre: String?
    public var duration: TimeInterval

    public init(
        title: String? = nil,
        artist: String? = nil,
        albumArtist: String? = nil,
        album: String? = nil,
        trackNumber: Int? = nil,
        trackTotal: Int? = nil,
        discNumber: Int? = nil,
        discTotal: Int? = nil,
        year: Int? = nil,
        genre: String? = nil,
        duration: TimeInterval = 0
    ) {
        self.title = title
        self.artist = artist
        self.albumArtist = albumArtist
        self.album = album
        self.trackNumber = trackNumber
        self.trackTotal = trackTotal
        self.discNumber = discNumber
        self.discTotal = discTotal
        self.year = year
        self.genre = genre
        self.duration = duration
    }
}
