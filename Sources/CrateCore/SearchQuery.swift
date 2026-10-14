// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// What the search field means: every whitespace-separated word has to
/// occur somewhere in a track's title, artist, album artist, album,
/// composer or genre, ignoring case and diacritics ("senorita" finds
/// "Señorita"). An album matches when its title, artist or genre does,
/// or when any of its tracks does, so searching for a song title still
/// shows the album it is on.
public struct SearchQuery: Equatable, Sendable {
    public let words: [String]

    public init(_ text: String) {
        words = text.split(whereSeparator: \.isWhitespace).map(String.init)
    }

    public var isEmpty: Bool { words.isEmpty }

    public func matches(_ track: Track) -> Bool {
        if isEmpty { return true }
        let fields = Self.fields(of: track)
        return words.allSatisfy { word in fields.contains { $0.localizedStandardContains(word) } }
    }

    public func matches(_ album: Album) -> Bool {
        if isEmpty { return true }
        let fields = Self.fields(of: album)
        if words.allSatisfy({ word in fields.contains { $0.localizedStandardContains(word) } }) {
            return true
        }
        return album.tracks.contains(where: matches)
    }

    /// The fields a search looks at; `SearchIndex` folds the same ones.
    static func fields(of track: Track) -> [String] {
        [track.title, track.artist, track.albumArtist, track.album, track.composer, track.genre].compactMap { $0 }
    }

    static func fields(of album: Album) -> [String] {
        [album.title, album.artist, album.genre].compactMap { $0 }
    }
}
