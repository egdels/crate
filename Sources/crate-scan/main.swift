// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

// Developer tool: scans a folder with the real AVFoundation reader and
// prints what came out, so tag-reading gaps show up against a real
// collection instead of in bug reports. Not part of the app.
//
//   swift run crate-scan <folder> [--all] [--albums] [--concurrency N]
//
// Prints a summary and every track that is missing a field; --all prints
// every track, --albums the albums the grouping makes of them. --concurrency overrides how many files are read at once,
// for measuring a network share.

import CrateCore
import Foundation

let arguments = CommandLine.arguments.dropFirst()
guard let folderArgument = arguments.first(where: { !$0.hasPrefix("--") }) else {
    FileHandle.standardError.write(Data("usage: crate-scan <folder> [--all]\n".utf8))
    exit(2)
}
let printAll = arguments.contains("--all")
let printAlbums = arguments.contains("--albums")
let folder = URL(fileURLWithPath: folderArgument)
let concurrency = arguments.firstIndex(of: "--concurrency")
    .flatMap { index in arguments.indices.contains(index + 1) ? Int(arguments[index + 1]) : nil } ?? 4

struct NullStore: LibraryStore {
    func load() throws -> LibrarySnapshot? { nil }
    func save(_ snapshot: LibrarySnapshot) throws {}
}

func describe(_ track: Track) -> String {
    func field(_ name: String, _ value: String?) -> String { "\(name)=\(value ?? "-")" }
    let number = [track.trackNumber.map(String.init), track.trackTotal.map(String.init)]
        .compactMap { $0 }.joined(separator: "/")
    let disc = [track.discNumber.map(String.init), track.discTotal.map(String.init)]
        .compactMap { $0 }.joined(separator: "/")
    return [
        track.url.lastPathComponent,
        field("title", track.title), field("artist", track.artist), field("albumArtist", track.albumArtist),
        field("album", track.album), field("track", number.nonEmpty), field("disc", disc.nonEmpty),
        field("year", track.year.map(String.init)), field("genre", track.genre),
        field("composer", track.composer), field("compilation", track.compilation ? "yes" : nil),
        field("artistSort", track.artistSort), field("albumSort", track.albumSort),
        field("trackGain", track.trackGain.map { String(format: "%.1f dB", $0) }),
        field("albumGain", track.albumGain.map { String(format: "%.1f dB", $0) }),
        String(format: "duration=%.1fs", track.duration),
    ].joined(separator: "  ")
}

extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}

let started = Date()
let library = Library(store: NullStore(), metadataReader: AVFoundationMetadataReader(), concurrency: concurrency)
let summary = try await library.addFolder(folder)
let tracks = await library.tracks
let elapsed = Date().timeIntervalSince(started)

func count(_ keyPath: KeyPath<Track, String?>) -> Int { tracks.filter { $0[keyPath: keyPath] == nil }.count }
func count(_ keyPath: KeyPath<Track, Int?>) -> Int { tracks.filter { $0[keyPath: keyPath] == nil }.count }

print("scanned \(folder.path) in \(String(format: "%.1f", elapsed))s")
print("added \(summary.added), failed \(summary.failed)")
print("missing: artist \(count(\.artist)), album \(count(\.album)), albumArtist \(count(\.albumArtist)), " +
      "trackNumber \(count(\.trackNumber)), discNumber \(count(\.discNumber)), year \(count(\.year)), genre \(count(\.genre))")
print("title fell back to file name: \(tracks.filter { $0.title == $0.url.deletingPathExtension().lastPathComponent }.count)")
print("albums: \(await library.albums.count)")
print()
for track in tracks where printAll || track.artist == nil || track.album == nil || track.trackNumber == nil {
    print(describe(track))
}

if printAlbums {
    print("")
    for album in Album.group(tracks, roots: [folder]) {
        let folder = album.folder.path.replacingOccurrences(of: folder.path, with: "")
        print("\(album.artist) — \(album.title) (\(album.tracks.count))  \(folder)")
    }
}
