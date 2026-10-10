// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// What the search field means: every whitespace-separated word has to
/// occur somewhere in a track's title, artist, album artist or album,
/// ignoring case and diacritics ("senorita" finds "Señorita"). An album
/// matches when its title or artist does, or when any of its tracks does,
/// so searching for a song title still shows the album it is on.
public struct SearchQuery: Equatable, Sendable {
    public let words: [String]

    public init(_ text: String) {
        words = text.split(whereSeparator: \.isWhitespace).map(String.init)
    }

    public var isEmpty: Bool { words.isEmpty }

    public func matches(_ track: Track) -> Bool {
        if isEmpty { return true }
        let fields = [track.title, track.artist, track.albumArtist, track.album].compactMap { $0 }
        return words.allSatisfy { word in fields.contains { $0.localizedStandardContains(word) } }
    }

    public func matches(_ album: Album) -> Bool {
        if isEmpty { return true }
        let fields = [album.title, album.artist]
        if words.allSatisfy({ word in fields.contains { $0.localizedStandardContains(word) } }) {
            return true
        }
        return album.tracks.contains(where: matches)
    }
}
