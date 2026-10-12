// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// `SearchQuery` over a whole library, with every field folded once when
/// the index is built instead of compared with `localizedStandardContains`
/// on every keystroke and every redraw. Folding case and diacritics with
/// the current locale, then a plain `contains`, gives the same results.
public struct SearchIndex: Sendable {
    public let albums: [Album]
    /// Every track in album order.
    public let tracks: [Track]
    private let locale: Locale
    // The folded fields of one item joined by newlines: a word never
    // contains whitespace, so it cannot match across two fields.
    private let albumTexts: [String]
    private let trackTexts: [String]

    public struct Result: Sendable {
        public let albums: [Album]
        public let tracks: [Track]
    }

    public init(albums: [Album], locale: Locale = .current) {
        self.albums = albums
        self.locale = locale
        tracks = albums.flatMap(\.tracks)
        let fold = { (text: String) in Self.fold(text, locale: locale) }
        albumTexts = albums.map { [$0.title, $0.artist].map(fold).joined(separator: "\n") }
        trackTexts = tracks.map { track in
            [track.title, track.artist, track.albumArtist, track.album].compactMap { $0 }.map(fold).joined(separator: "\n")
        }
    }

    public func search(_ query: SearchQuery) -> Result {
        if query.isEmpty { return Result(albums: albums, tracks: tracks) }
        let words = query.words.map { Self.fold($0, locale: locale) }
        func matches(_ text: String) -> Bool { words.allSatisfy { text.contains($0) } }
        let trackMatches = trackTexts.map(matches)
        var foundAlbums: [Album] = []
        var start = 0
        for (i, album) in albums.enumerated() {
            let end = start + album.tracks.count
            if matches(albumTexts[i]) || trackMatches[start..<end].contains(true) {
                foundAlbums.append(album)
            }
            start = end
        }
        let foundTracks = zip(tracks, trackMatches).filter(\.1).map(\.0)
        return Result(albums: foundAlbums, tracks: foundTracks)
    }

    private static func fold(_ text: String, locale: Locale) -> String {
        // No .widthInsensitive: localizedStandardContains does not ignore width.
        text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: locale)
    }
}
