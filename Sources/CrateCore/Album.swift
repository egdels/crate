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
    /// The folder the tracks lie in; disc subfolders ("CD 2") count as
    /// their parent.
    public var folder: URL
    /// Sorted by disc number, then track number, then title.
    public var tracks: [Track]

    public var duration: TimeInterval { tracks.reduce(0) { $0 + $1.duration } }

    /// Groups by (folder, loosely compared album title) and sorts albums
    /// by artist, then year, then title. `roots` are the watched folders:
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
        return buckets.values.map { bucket in
            let parent = bucket.folder.deletingLastPathComponent()
            let insideRoot = !rootPaths.contains(bucket.folder.path.lowercased())
                && !rootPaths.contains(parent.path.lowercased()) && parent.path != bucket.folder.path
            let parentName = insideRoot ? parent.lastPathComponent.nonEmpty : nil
            return Album(
                title: title(of: bucket.tracks, folder: bucket.folder),
                artist: artist(of: bucket.tracks, fallback: parentName),
                year: bucket.tracks.compactMap(\.year).min(),
                folder: bucket.folder,
                tracks: bucket.tracks.sorted(by: trackOrder)
            )
        }
        .sorted(by: albumOrder)
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
    /// into a compilation; anything less does. No name at all falls back
    /// to the given one (the parent folder) or "Unknown Artist".
    static func artist(of tracks: [Track], fallback: String? = nil) -> String {
        for names in [tracks.compactMap { $0.albumArtist?.trimmed.nonEmpty }, tracks.compactMap { $0.artist?.trimmed.nonEmpty }] {
            guard let common = mostCommon(names) else { continue }
            let share = names.filter { looseKey($0) == looseKey(common) }.count
            return share * 3 >= names.count * 2 ? common : variousArtists
        }
        return fallback ?? unknownArtist
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
    private static func mostCommon(_ values: [String]) -> String? {
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

    private static func albumOrder(_ a: Album, _ b: Album) -> Bool {
        let byArtist = a.artist.localizedStandardCompare(b.artist)
        if byArtist != .orderedSame { return byArtist == .orderedAscending }
        let ya = a.year ?? Int.max, yb = b.year ?? Int.max
        if ya != yb { return ya < yb }
        return a.title.localizedStandardCompare(b.title) == .orderedAscending
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    var nonEmpty: String? { isEmpty ? nil : self }
}
