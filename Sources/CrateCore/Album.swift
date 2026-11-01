// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// Tracks grouped by album. Derived from the tracks on demand, never
/// stored: the library's only persistent unit is the `Track`.
///
/// Tags are the primary source, but the folder a file lies in breaks
/// ties, because tags are often half right: a compilation whose tracks
/// carry each artist but no album artist, an album title spelled two
/// ways, a stray ";" behind one track's artist, half an album left
/// untagged. Every one of these would split a folder into several tiles
/// if tags alone decided. So an album is "the tracks in one folder with
/// the same album title", compared loosely, with the artist derived from
/// what the tracks agree on.
public struct Album: Identifiable, Hashable, Sendable {
    public static let unknownTitle = "Unknown Album"
    public static let unknownArtist = "Unknown Artist"
    public static let variousArtists = "Various Artists"

    /// Folder, artist and title: two folders with an identically tagged
    /// album are two albums, which keeps the id unique.
    public var id: String { "\(folder.path)\u{1F}\(artist)\u{1F}\(title)" }

    public var title: String
    public var artist: String
    public var year: Int?
    /// The genre most of the tracks carry; nil when none is tagged.
    public var genre: String?
    /// The folder the tracks lie in; disc subfolders ("CD 2") count as
    /// their parent.
    public var folder: URL
    /// Sorted by disc number, then track number, then title.
    public var tracks: [Track]

    public var duration: TimeInterval { tracks.reduce(0) { $0 + $1.duration } }

    /// One disc of the album: its number and its tracks, in order.
    public struct Disc: Equatable, Sendable {
        public let number: Int
        public let tracks: [Track]
    }

    /// The tracks by disc, in order; untagged tracks count as disc 1. An
    /// album page shows the disc numbers when there is more than one.
    public var discs: [Disc] {
        var discs: [Disc] = []
        for track in tracks {
            let number = track.discNumber ?? 1
            if let last = discs.last, last.number == number {
                discs[discs.count - 1] = Disc(number: number, tracks: last.tracks + [track])
            } else {
                discs.append(Disc(number: number, tracks: [track]))
            }
        }
        return discs
    }

    /// What the album sorts by: the tracks' sort tags when they have
    /// them ("Beatles, The"), otherwise the name without a leading
    /// "The". Album artist sort tags win over track artist ones, like
    /// the names themselves.
    public var sortArtist: String {
        Self.mostCommon(tracks.compactMap { $0.albumArtistSort?.trimmed.nonEmpty })
            ?? Self.mostCommon(tracks.compactMap { $0.artistSort?.trimmed.nonEmpty })
            ?? Self.sortName(artist)
    }

    public var sortTitle: String {
        Self.mostCommon(tracks.compactMap { $0.albumSort?.trimmed.nonEmpty }) ?? Self.sortName(title)
    }

    /// Groups by (folder, loosely compared album title) and sorts albums
    /// by artist, then year, then title, or a numbered series by its
    /// numbers (`sorted(_:)`). `roots` are the watched folders:
    /// an untagged album takes its artist from the parent folder's name,
    /// unless that parent is a root.
    public static func group(_ tracks: [Track], roots: [URL] = []) -> [Album] {
        struct Key: Hashable {
            let folder: String
            let title: String
        }
        var buckets: [Key: (folder: URL, tracks: [Track])] = [:]
        var order: [Key] = []
        for track in tracks {
            let folder = groupingFolder(of: track.url)
            let key = Key(folder: folder.path.lowercased(), title: looseKey(track.album ?? ""))
            if buckets[key] == nil {
                buckets[key] = (folder, [])
                order.append(key)
            }
            buckets[key]!.tracks.append(track)
        }

        // Untagged tracks in a folder that holds exactly one tagged album
        // belong to that album.
        for (_, keys) in Dictionary(grouping: order, by: \.folder) {
            guard keys.count == 2, let untagged = keys.first(where: { $0.title.isEmpty }),
                  let tagged = keys.first(where: { !$0.title.isEmpty }) else { continue }
            buckets[tagged]!.tracks.append(contentsOf: buckets[untagged]!.tracks)
            buckets[untagged] = nil
            order.removeAll { $0 == untagged }
        }

        // Sibling folders holding the same tagged album by the same artist
        // and named alike but for a number ("soul_1", "soul_2") are its
        // discs: one album, filed under the parent. Differently named
        // siblings with the same tags are left alone; those are copies.
        struct Sibling: Hashable {
            let parent: String
            let title: String
            let artist: String
            let stem: String
        }
        let siblings = Dictionary(grouping: order.filter { !$0.title.isEmpty }) { key in
            let folder = buckets[key]!.folder
            return Sibling(parent: folder.deletingLastPathComponent().path.lowercased(),
                           title: key.title, artist: looseKey(artist(of: buckets[key]!.tracks)),
                           stem: discStem(folder.lastPathComponent))
        }
        for (sibling, keys) in siblings where keys.count > 1 && !sibling.stem.isEmpty {
            let parent = buckets[keys[0]]!.folder.deletingLastPathComponent()
            let merged = Key(folder: sibling.parent, title: sibling.title)
            var tracks: [Track] = []
            for key in keys {
                tracks.append(contentsOf: buckets[key]!.tracks)
                buckets[key] = nil
            }
            buckets[merged] = (parent, tracks)
        }

        let rootPaths = Set(roots.map { $0.standardizedFileURL.path.lowercased() })
        return sorted(buckets.values.map { bucket in
            let parent = bucket.folder.deletingLastPathComponent()
            let insideRoot = !rootPaths.contains(bucket.folder.path.lowercased())
                && !rootPaths.contains(parent.path.lowercased()) && parent.path != bucket.folder.path
            let parentName = insideRoot ? parent.lastPathComponent.nonEmpty : nil
            return Album(
                title: title(of: bucket.tracks, folder: bucket.folder),
                artist: artist(of: bucket.tracks, fallback: parentName),
                year: bucket.tracks.compactMap(\.year).min(),
                genre: mostCommon(bucket.tracks.compactMap { $0.genre?.trimmed.nonEmpty }),
                folder: bucket.folder,
                tracks: bucket.tracks.sorted(by: trackOrder)
            )
        })
    }

    // MARK: - Order

    /// Sorted by artist; an artist's albums by year, then title. Unless
    /// they form a numbered series (`seriesNumbers`): radio plays and
    /// audiobook series carry the year of the edition, not of the
    /// episode (a reissued episode 36 came after episode 98), so then
    /// the number decides, ties by title, then year, and the albums
    /// without a number follow by year and title. Decided without a
    /// genre: in a real collection a big radio play series was tagged
    /// with none.
    static func sorted(_ albums: [Album]) -> [Album] {
        let keyed = albums.map { (album: $0, artist: $0.sortArtist, title: $0.sortTitle) }
            .sorted { $0.artist.localizedStandardCompare($1.artist) == .orderedAscending }
        var result: [Album] = []
        result.reserveCapacity(albums.count)
        var start = 0
        while start < keyed.count {
            var end = start + 1
            while end < keyed.count, keyed[end].artist.localizedStandardCompare(keyed[start].artist) == .orderedSame {
                end += 1
            }
            let run = keyed[start..<end]
            let numbers = seriesNumbers(run.map(\.title)) ?? Array(repeating: nil, count: run.count)
            let ordered = zip(run, numbers).sorted { a, b in
                switch (a.1, b.1) {
                case let (x?, y?) where x != y: return x < y
                case (_?, nil): return true
                case (nil, _?): return false
                case (_?, _?):
                    let byTitle = a.0.title.localizedStandardCompare(b.0.title)
                    if byTitle != .orderedSame { return byTitle == .orderedAscending }
                    return (a.0.album.year ?? Int.max) < (b.0.album.year ?? Int.max)
                case (nil, nil):
                    let ya = a.0.album.year ?? Int.max, yb = b.0.album.year ?? Int.max
                    if ya != yb { return ya < yb }
                    return a.0.title.localizedStandardCompare(b.0.title) == .orderedAscending
                }
            }
            result += ordered.map(\.0.album)
            start = end
        }
        return result
    }

    /// The episode number of each title when one artist's titles form a
    /// numbered series, else nil. A number counts under the word in
    /// front of it ("Volume 36", "Folge 194", "Peter Grant 4", "Staffel
    /// 2"); a word is a series word when at least two titles carry it
    /// with different numbers, so a title spelled "Volume" here and
    /// "Folge" there still counts; a word and its abbreviation (three
    /// letters or more: "Vol." and "Volume") are one word. A series needs three numbered titles
    /// and two thirds of all. Not numbers: disc numbers ("CD 2"), digits
    /// inside a word ("Dr3i"), parts of "1.000", anything over four
    /// digits. Checked against a real collection: it took five radio
    /// play and audiobook series and three "Vol. 1-4" sets, and no
    /// artist whose albums only now and then carry a number.
    static func seriesNumbers(_ titles: [String]) -> [Int?]? {
        var found = titles.map(numberedWords)
        let words = Set(found.flatMap { $0.map(\.word) })
        let short = words.filter { $0.count >= 3 }.sorted { $0.count < $1.count }
        found = found.map { list in
            list.map { entry in (short.first { entry.word.hasPrefix($0) } ?? entry.word, entry.number) }
        }
        var titlesWith: [String: Int] = [:]
        var numbersOf: [String: Set<Int>] = [:]
        for words in found {
            for word in Set(words.map(\.word)) { titlesWith[word, default: 0] += 1 }
            for (word, number) in words { numbersOf[word, default: []].insert(number) }
        }
        let seriesWords = Set(titlesWith.keys.filter { titlesWith[$0]! >= 2 && numbersOf[$0]!.count >= 2 })
        let numbers = found.map { words in words.first { seriesWords.contains($0.word) }?.number }
        let numbered = numbers.compactMap { $0 }.count
        return numbered >= 3 && numbered * 3 >= titles.count * 2 ? numbers : nil
    }

    /// Every free-standing number in the title with the word before it,
    /// lowercased ("" at the start).
    static func numberedWords(_ title: String) -> [(word: String, number: Int)] {
        let chars = Array(title)
        func isDigit(_ i: Int) -> Bool { i >= 0 && i < chars.count && chars[i].isASCII && chars[i].isNumber }
        func isWordChar(_ i: Int) -> Bool { i >= 0 && i < chars.count && (chars[i].isLetter || chars[i].isNumber) }
        var result: [(word: String, number: Int)] = []
        var i = 0
        while i < chars.count {
            guard isDigit(i) else { i += 1; continue }
            let start = i
            while isDigit(i) { i += 1 }
            let end = i
            if end - start > 4 { continue }
            if (start > 0 && chars[start - 1].isLetter) || (end < chars.count && chars[end].isLetter) { continue }
            if start > 1, ".,".contains(chars[start - 1]), isDigit(start - 2) { continue }
            if end + 1 < chars.count, ".,".contains(chars[end]), isDigit(end + 1) { continue }
            var wordEnd = start - 1
            while wordEnd >= 0, !isWordChar(wordEnd) { wordEnd -= 1 }
            var wordStart = wordEnd
            while wordStart > 0, isWordChar(wordStart - 1) { wordStart -= 1 }
            let word = wordEnd >= 0 ? String(chars[wordStart...wordEnd]).lowercased() : ""
            if ["cd", "disc", "disk"].contains(word) { continue }
            result.append((word, Int(String(chars[start..<end]))!))
        }
        return result
    }

    // MARK: - Deriving the album from its tracks

    /// The most common album tag, spelled as the majority spells it;
    /// without any tag, the folder's name.
    static func title(of tracks: [Track], folder: URL) -> String {
        if let tag = mostCommon(tracks.compactMap { $0.album?.trimmed.nonEmpty }) {
            return tag
        }
        return folder.lastPathComponent.nonEmpty ?? unknownTitle
    }

    /// Album artist tags win over track artists. Either way: a name that
    /// two thirds of the tracks share (ignoring spelling noise) is the
    /// album's artist, so one mistagged track does not turn an album
    /// into a compilation; anything less does. A compilation flag on
    /// most tracks settles it the other way when only track artists are
    /// tagged: a tribute album with one artist on two thirds of it is
    /// still a compilation. No name at all falls back to the given one
    /// (the parent folder) or "Unknown Artist".
    static func artist(of tracks: [Track], fallback: String? = nil) -> String {
        if let common = mostCommon(tracks.compactMap { $0.albumArtist?.trimmed.nonEmpty }) {
            return agreed(on: common, by: tracks.compactMap { $0.albumArtist?.trimmed.nonEmpty }) ? common : variousArtists
        }
        if tracks.filter(\.compilation).count * 2 > tracks.count {
            return variousArtists
        }
        if let common = mostCommon(tracks.compactMap { $0.artist?.trimmed.nonEmpty }) {
            return agreed(on: common, by: tracks.compactMap { $0.artist?.trimmed.nonEmpty }) ? common : variousArtists
        }
        return fallback ?? unknownArtist
    }

    private static func agreed(on common: String, by names: [String]) -> Bool {
        names.filter { looseKey($0) == looseKey(common) }.count * 3 >= names.count * 2
    }

    /// The name as it sorts: a leading "The " goes to the back, so The
    /// Beatles file under B. Case and diacritics are left to the
    /// comparison. Only "The": "A Tribe Called Quest" stays under A, as
    /// most people would look for it.
    static func sortName(_ name: String) -> String {
        let trimmed = name.trimmed
        if trimmed.count > 4, trimmed.lowercased().hasPrefix("the ") {
            return String(trimmed.dropFirst(4)).trimmed + ", The"
        }
        return trimmed
    }

    /// A folder name without its trailing number: "soul_1" and "soul_2"
    /// share the stem "soul". Empty when the name is only a number.
    static func discStem(_ name: String) -> String {
        var key = looseKey(name)
        while let last = key.last, last.isNumber { key.removeLast() }
        return key
    }

    /// Lowercased, letters and digits only: "Winter-Works" and
    /// "Winterworks" compare equal, as do "Band" and "Band;".
    static func looseKey(_ text: String) -> String {
        String(text.lowercased().unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) })
    }

    /// The parent folder, or the grandparent when the parent is a disc
    /// folder like "CD1", "Disc 2" or "Disk02".
    static func groupingFolder(of url: URL) -> URL {
        let folder = url.deletingLastPathComponent()
        let name = folder.lastPathComponent.lowercased()
        let isDiscFolder = ["cd", "disc", "disk"].contains { prefix in
            name.hasPrefix(prefix) && name.dropFirst(prefix.count).trimmingCharacters(in: .whitespaces)
                .allSatisfy(\.isNumber) && name.count > prefix.count
        }
        return isDiscFolder ? folder.deletingLastPathComponent() : folder
    }

    /// Ties go to the value seen first, so the result is stable.
    static func mostCommon(_ values: [String]) -> String? {
        var counts: [String: Int] = [:]
        var first: [String: Int] = [:]
        for (index, value) in values.enumerated() {
            counts[value, default: 0] += 1
            if first[value] == nil { first[value] = index }
        }
        return counts.max { a, b in
            a.value != b.value ? a.value < b.value : first[a.key]! > first[b.key]!
        }?.key
    }

    private static func trackOrder(_ a: Track, _ b: Track) -> Bool {
        let da = a.discNumber ?? 1, db = b.discNumber ?? 1
        if da != db { return da < db }
        let ta = a.trackNumber ?? Int.max, tb = b.trackNumber ?? Int.max
        if ta != tb { return ta < tb }
        return a.title.localizedStandardCompare(b.title) == .orderedAscending
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    var nonEmpty: String? { isEmpty ? nil : self }
}
