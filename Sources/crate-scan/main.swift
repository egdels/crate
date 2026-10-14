// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

// Developer tool: scans a folder with the real AVFoundation reader and
// prints what came out, so tag-reading gaps show up against a real
// collection instead of in bug reports. Not part of the app.
//
//   swift run crate-scan <folder> [--all] [--albums] [--concurrency N]
//   swift run crate-scan <folder> --lyrics [--concurrency N]
//   swift run crate-scan <folder> --loudness [--concurrency N]
//
// Prints a summary and every track that is missing a field; --all prints
// every track, --albums the albums the grouping makes of them. --concurrency overrides how many files are read at once,
// for measuring a network share. --lyrics instead counts the files that
// carry lyrics: USLT/SYLT frames (MP3), the ©lyr atom (M4A), LYRICS or
// UNSYNCEDLYRICS comments (FLAC) and .lrc files next to the audio (same
// name, or in a "lyrics" subfolder); groundwork for showing them.
// --loudness measures every file with the BS.1770 meter and prints
// loudness, peak and ReplayGain, to compare with `ffmpeg -af ebur128`
// and to time the decoding.

import AVFoundation
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

/// Where a file's lyrics came from, if anywhere.
struct LyricsFinding: Sendable {
    enum Kind: String, CaseIterable, Sendable {
        case uslt = "USLT (MP3, plain)", sylt = "SYLT (MP3, timed)", lyr = "©lyr (M4A)", vorbis = "LYRICS (FLAC)"
        case lrc = ".lrc next to the file", lrcFolder = ".lrc in a lyrics/ subfolder"
    }
    var url: URL
    var kinds: [Kind]
    /// Whether the text carries [mm:ss] time stamps.
    var timed: Bool
}

/// Lyrics in a file's own tags.
func taggedLyrics(_ url: URL) async -> (kinds: [LyricsFinding.Kind], text: String?) {
    if url.pathExtension.lowercased() == "flac" {
        guard let flac = try? FLACMetadata.read(url) else { return ([], nil) }
        let text = flac.value("LYRICS") ?? flac.value("UNSYNCEDLYRICS") ?? flac.value("UNSYNCED LYRICS")
        return (text.map { _ in [.vorbis] } ?? [], text)
    }
    let asset = AVURLAsset(url: url)
    guard let items = try? await asset.load(.metadata) else { return ([], nil) }
    var kinds: [LyricsFinding.Kind] = []
    var text: String?
    for item in items {
        guard let identifier = item.identifier else { continue }
        let kind: LyricsFinding.Kind?
        switch identifier {
        case .id3MetadataUnsynchronizedLyric: kind = .uslt
        case .id3MetadataSynchronizedLyric: kind = .sylt
        case .iTunesMetadataLyrics: kind = .lyr
        default: kind = nil
        }
        guard let kind, !kinds.contains(kind) else { continue }
        kinds.append(kind)
        if text == nil, let string = try? await item.load(.stringValue), !string.isEmpty { text = string }
    }
    return (kinds, text)
}

/// An .lrc with the file's name, next to it or in a lyrics/ subfolder.
func sidecar(_ url: URL) -> (kind: LyricsFinding.Kind, text: String)? {
    let base = url.deletingPathExtension().lastPathComponent
    let folder = url.deletingLastPathComponent()
    let candidates: [(LyricsFinding.Kind, URL)] = [(.lrc, folder.appendingPathComponent(base + ".lrc"))]
        + ["lyrics", "Lyrics"].map { (.lrcFolder, folder.appendingPathComponent($0).appendingPathComponent(base + ".lrc")) }
    for (kind, file) in candidates {
        guard let data = try? Data(contentsOf: file) else { continue }
        return (kind, String(decoding: data, as: UTF8.self))
    }
    return nil
}

/// "[mm:ss.xx]" as the LRC format has it, and "[mm:ss:xx]" as some
/// files in the wild have it.
let timeStamp = try! NSRegularExpression(pattern: "\\[\\d{1,2}:\\d{2}([.:]\\d{1,3})?\\]")

func countLyrics(in folder: URL, concurrency: Int) async throws {
    let started = Date()
    let files = try LibraryScanner().scan(folder).map(\.url).sorted { $0.path < $1.path }
    var findings: [LyricsFinding] = []
    var read = 0
    await withTaskGroup(of: LyricsFinding?.self) { group in
        var iterator = files.makeIterator()
        func enqueue() {
            guard let url = iterator.next() else { return }
            group.addTask {
                let tagged = await taggedLyrics(url)
                var kinds = tagged.kinds
                var text = tagged.text ?? ""
                if let side = sidecar(url) {
                    kinds.append(side.kind)
                    text += side.text
                }
                guard !kinds.isEmpty else { return nil }
                let timed = kinds.contains(.sylt) || timeStamp.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
                return LyricsFinding(url: url, kinds: kinds, timed: timed)
            }
        }
        for _ in 0..<max(1, concurrency) { enqueue() }
        for await finding in group {
            read += 1
            if read % 2000 == 0 { FileHandle.standardError.write(Data("\(read)/\(files.count)\n".utf8)) }
            if let finding { findings.append(finding) }
            enqueue()
        }
    }
    let elapsed = Date().timeIntervalSince(started)
    print("looked at \(files.count) files in \(String(format: "%.1f", elapsed))s")
    print("files with lyrics: \(findings.count) (\(findings.filter(\.timed).count) with time stamps)")
    for kind in LyricsFinding.Kind.allCases {
        let count = findings.filter { $0.kinds.contains(kind) }.count
        if count > 0 { print("  \(kind.rawValue): \(count)") }
    }
    var byExtension: [String: Int] = [:]
    for finding in findings { byExtension[finding.url.pathExtension.lowercased(), default: 0] += 1 }
    print("  by format: " + byExtension.sorted { $0.key < $1.key }.map { "\($0.key) \($0.value)" }.joined(separator: ", "))
    var folders: [String: Int] = [:]
    for finding in findings {
        folders[finding.url.deletingLastPathComponent().path.replacingOccurrences(of: folder.path, with: ""), default: 0] += 1
    }
    print("  in \(folders.count) folders")
    print()
    for (name, count) in folders.sorted(by: { $0.key < $1.key }) {
        print("\(count)\t\(name)")
    }
}

func measureLoudness(in folder: URL, concurrency: Int) async throws {
    let started = Date()
    let files = try LibraryScanner().scan(folder).map(\.url).sorted { $0.path < $1.path }
    var seconds = 0.0
    await withTaskGroup(of: (URL, TrackLoudness?, Double, Double).self) { group in
        var iterator = files.makeIterator()
        func enqueue() {
            guard let url = iterator.next() else { return }
            group.addTask {
                let t0 = Date()
                let result = try? LoudnessMeter.measure(url)
                let length = (try? AVAudioFile(forReading: url)).map { Double($0.length) / $0.processingFormat.sampleRate } ?? 0
                return (url, result, Date().timeIntervalSince(t0), length)
            }
        }
        for _ in 0..<max(1, concurrency) { enqueue() }
        for await (url, result, took, length) in group {
            seconds += length
            if let result, let loudness = result.loudness {
                print(String(format: "%@  I=%.1f LUFS  peak=%.1f dBFS  gain=%+.1f dB  (%.0f s audio in %.1f s)",
                             url.lastPathComponent, loudness, 20 * log10(max(result.peak, 1e-9)), result.gain ?? 0, length, took))
            } else {
                print("\(url.lastPathComponent)  no result")
            }
            enqueue()
        }
    }
    let elapsed = Date().timeIntervalSince(started)
    print(String(format: "measured %d files, %.0f min of audio, in %.1f s (%.0fx realtime, %d in flight)",
                 files.count, seconds / 60, elapsed, seconds / max(elapsed, 0.001), concurrency))
}

// Below every declaration: a top-level constant such as `timeStamp` is
// initialised when the code reaches its line.
if arguments.contains("--loudness") {
    try await measureLoudness(in: folder, concurrency: concurrency)
    exit(0)
}
if arguments.contains("--lyrics") {
    try await countLyrics(in: folder, concurrency: concurrency)
    exit(0)
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
