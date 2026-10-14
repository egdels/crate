// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AVFoundation
import Foundation

/// The words of a track, one line at a time, each with the moment it is
/// sung when the source is timed (an LRC file) and without when it is
/// plain text (a USLT frame, a ©lyr atom, a LYRICS comment).
public struct Lyrics: Equatable, Sendable {
    public struct Line: Equatable, Sendable {
        /// Seconds into the track, nil for plain text.
        public var time: TimeInterval?
        public var text: String

        public init(time: TimeInterval? = nil, text: String) {
            self.time = time
            self.text = text
        }
    }

    public var lines: [Line]

    public init(lines: [Line]) {
        self.lines = lines
    }

    public var isEmpty: Bool { lines.isEmpty }
    public var isTimed: Bool { lines.contains { $0.time != nil } }

    /// The line being sung at `time`: the last timed one at or before it.
    /// nil before the first, and for plain text.
    public func index(at time: TimeInterval) -> Int? {
        var found: Int?
        for (index, line) in lines.enumerated() {
            guard let at = line.time else { continue }
            if at <= time { found = index } else { break }
        }
        return found
    }

    /// LRC when the text has time stamps ("[mm:ss.xx]", also the
    /// "[mm:ss:xx]" some files have), otherwise plain lines. LRC header
    /// tags ("[ti:...]", "[ar:...]") are dropped, "[offset:ms]" is
    /// applied, a line with several stamps becomes several lines, and
    /// the result is in time order.
    public static func parse(_ text: String) -> Lyrics {
        var timed: [Line] = []
        var plain: [Line] = []
        var offset: TimeInterval = 0
        var sawStamp = false
        for rawLine in text.split(omittingEmptySubsequences: false, whereSeparator: { $0 == "\n" || $0 == "\r\n" }) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            var rest = Substring(line)
            var stamps: [TimeInterval] = []
            while let (stamp, after) = Self.leadingTag(rest) {
                rest = after
                if let seconds = Self.seconds(of: stamp) {
                    stamps.append(seconds)
                } else if stamp.lowercased().hasPrefix("offset:"), let ms = Double(stamp.dropFirst(7).trimmingCharacters(in: .whitespaces)) {
                    offset = ms / 1000
                }
                // Any other tag is a header line: dropped.
            }
            let words = rest.trimmingCharacters(in: .whitespaces)
            if stamps.isEmpty {
                if rest.count == line.count {
                    plain.append(Line(text: words))
                }
                continue
            }
            sawStamp = true
            for stamp in stamps where !words.isEmpty {
                timed.append(Line(time: stamp, text: words))
            }
        }
        if sawStamp {
            let sorted = timed.enumerated().sorted { a, b in
                a.element.time! != b.element.time! ? a.element.time! < b.element.time! : a.offset < b.offset
            }.map { Line(time: max(0, $0.element.time! + offset), text: $0.element.text) }
            return Lyrics(lines: sorted)
        }
        // Plain text: keep single blank lines as paragraph breaks, not runs
        // of them, and nothing blank at either end.
        var lines: [Line] = []
        for line in plain where !(line.text.isEmpty && (lines.last?.text.isEmpty ?? true)) {
            lines.append(line)
        }
        while lines.last?.text.isEmpty == true { lines.removeLast() }
        return Lyrics(lines: lines)
    }

    /// "[tag]" at the start, and what follows it.
    private static func leadingTag(_ s: Substring) -> (String, Substring)? {
        guard s.first == "[", let close = s.firstIndex(of: "]") else { return nil }
        let tag = String(s[s.index(after: s.startIndex)..<close])
        guard !tag.contains("["), !tag.isEmpty else { return nil }
        return (tag, s[s.index(after: close)...])
    }

    /// "mm:ss", "mm:ss.xx", "mm:ss:xx", "h:mm:ss.xx".
    private static func seconds(of stamp: String) -> TimeInterval? {
        let parts = stamp.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 2, parts.count <= 4 else { return nil }
        var numbers: [Double] = []
        for (index, part) in parts.enumerated() {
            if index == parts.count - 1, parts.count == 3 || parts.count == 2 {
                // The last part may be "ss.xx"; a third colon part is "xx".
                guard let value = Double(part) else { return nil }
                numbers.append(value)
            } else {
                guard let value = Double(part), !part.contains(".") || index == parts.count - 1 else { return nil }
                numbers.append(value)
            }
        }
        switch numbers.count {
        case 2: return numbers[0] * 60 + numbers[1]
        case 3:
            // "mm:ss:xx" (hundredths) when the last part has two digits and
            // the first is small; "h:mm:ss" otherwise.
            if parts[2].count <= 3, parts[1].count == 2, !parts[2].contains(".") {
                return numbers[0] * 60 + numbers[1] + numbers[2] / pow(10, Double(parts[2].count))
            }
            return numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        case 4:
            return numbers[0] * 3600 + numbers[1] * 60 + numbers[2] + numbers[3] / pow(10, Double(parts[3].count))
        default: return nil
        }
    }
}

public protocol LyricsSource: Sendable {
    /// nil when the track has none anywhere.
    func lyrics(for track: Track) async -> Lyrics?
}

/// Lyrics from next to the file first, then from its tags: an `.lrc`
/// (timed) or `.txt` (plain) with the track's name beside it or in a
/// `lyrics` subfolder, then a LYRICS comment (FLAC), a USLT frame (MP3)
/// or a ©lyr atom (M4A).
public struct FileLyricsSource: LyricsSource {
    public init() {}

    public func lyrics(for track: Track) async -> Lyrics? {
        if let text = Self.sidecar(track.url) {
            let lyrics = Lyrics.parse(text)
            if !lyrics.isEmpty { return lyrics }
        }
        guard let text = await Self.tagged(track.url) else { return nil }
        let lyrics = Lyrics.parse(text)
        return lyrics.isEmpty ? nil : lyrics
    }

    /// `.lrc` before `.txt`, beside the file before the subfolder.
    static func sidecar(_ url: URL) -> String? {
        let base = url.deletingPathExtension().lastPathComponent
        let folder = url.deletingLastPathComponent()
        let folders = [folder] + ["lyrics", "Lyrics"].map { folder.appendingPathComponent($0) }
        for ext in ["lrc", "txt"] {
            for place in folders {
                guard let data = try? Data(contentsOf: place.appendingPathComponent(base + "." + ext)) else { continue }
                return String(decoding: data, as: UTF8.self)
            }
        }
        return nil
    }

    static func tagged(_ url: URL) async -> String? {
        if url.pathExtension.lowercased() == "flac" {
            guard let flac = try? FLACMetadata.read(url) else { return nil }
            return flac.value("LYRICS") ?? flac.value("UNSYNCEDLYRICS") ?? flac.value("UNSYNCED LYRICS")
        }
        let asset = AVURLAsset(url: url)
        guard let items = try? await asset.load(.metadata) else { return nil }
        for identifier in [AVMetadataIdentifier.id3MetadataUnsynchronizedLyric, .iTunesMetadataLyrics] {
            for item in AVMetadataItem.metadataItems(from: items, filteredByIdentifier: identifier) {
                if let text = try? await item.load(.stringValue), !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    return text
                }
            }
        }
        return nil
    }
}
