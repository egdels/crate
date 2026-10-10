// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// The metadata blocks at the front of a FLAC file: STREAMINFO for the
/// duration, VORBIS_COMMENT for the tags, PICTURE for the cover. Read
/// directly from the file, because AVFoundation on macOS 12 returns no
/// Vorbis comments at all, and on later systems only under raw keys. The
/// format is simple enough that this is the more reliable path on every
/// system, and it reads a few kilobytes where AVFoundation opens an
/// asset. Audio frames are never touched.
public struct FLACMetadata: Sendable {
    public struct Picture: Sendable {
        /// 3 is the front cover, per the FLAC specification.
        public var type: Int
        public var mimeType: String
        public var data: Data
    }

    /// Keys uppercased, in file order; a key may repeat.
    public var comments: [(key: String, value: String)] = []
    public var duration: TimeInterval = 0
    public var pictures: [Picture] = []

    public struct NotFLAC: Error {}

    /// First value for the key, case-insensitively.
    public func value(_ key: String) -> String? {
        let wanted = key.uppercased()
        return comments.first { $0.key == wanted }?.value.trimmed.nonEmpty
    }

    /// The front cover if there is one, otherwise the first picture.
    public var cover: Picture? {
        pictures.first { $0.type == 3 } ?? pictures.first
    }

    public static func read(_ url: URL) throws -> FLACMetadata {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        guard try handle.read(upToCount: 4) == Data("fLaC".utf8) else { throw NotFLAC() }
        var meta = FLACMetadata()
        var last = false
        while !last {
            guard let header = try handle.read(upToCount: 4), header.count == 4 else { break }
            last = header[0] & 0x80 != 0
            let type = Int(header[0] & 0x7f)
            let length = Int(header[1]) << 16 | Int(header[2]) << 8 | Int(header[3])
            switch type {
            case 0, 4, 6:
                guard let block = try handle.read(upToCount: length), block.count == length else { break }
                switch type {
                case 0: meta.duration = streamInfoDuration(block)
                case 4: meta.comments += vorbisComments(block)
                default: if let picture = picture(block) { meta.pictures.append(picture) }
                }
            default:
                try handle.seek(toOffset: try handle.offset() + UInt64(length))
            }
        }
        return meta
    }

    // MARK: - Blocks

    /// Sample rate (20 bits) and total samples (36 bits) sit at byte 10.
    static func streamInfoDuration(_ block: Data) -> TimeInterval {
        guard block.count >= 18 else { return 0 }
        let b = [UInt8](block)
        let sampleRate = Int(b[10]) << 12 | Int(b[11]) << 4 | Int(b[12]) >> 4
        let totalSamples = Int(b[13] & 0x0f) << 32 | Int(b[14]) << 24 | Int(b[15]) << 16 | Int(b[16]) << 8 | Int(b[17])
        return sampleRate > 0 ? TimeInterval(totalSamples) / TimeInterval(sampleRate) : 0
    }

    /// Little-endian lengths, UTF-8 "KEY=value" entries.
    static func vorbisComments(_ block: Data) -> [(key: String, value: String)] {
        var reader = Reader(block)
        guard let vendorLength = reader.uint32LE(), reader.skip(Int(vendorLength)),
              let count = reader.uint32LE() else { return [] }
        var comments: [(key: String, value: String)] = []
        for _ in 0..<count {
            guard let length = reader.uint32LE(), let entry = reader.string(Int(length)) else { break }
            guard let equals = entry.firstIndex(of: "=") else { continue }
            comments.append((String(entry[..<equals]).uppercased(), String(entry[entry.index(after: equals)...])))
        }
        return comments
    }

    /// Big-endian: type, mime, description, four dimension fields, data.
    static func picture(_ block: Data) -> Picture? {
        var reader = Reader(block)
        guard let type = reader.uint32BE(),
              let mimeLength = reader.uint32BE(), let mime = reader.string(Int(mimeLength)),
              let descriptionLength = reader.uint32BE(), reader.skip(Int(descriptionLength)),
              reader.skip(16),
              let dataLength = reader.uint32BE(), let data = reader.bytes(Int(dataLength)), !data.isEmpty
        else { return nil }
        return Picture(type: Int(type), mimeType: mime, data: data)
    }

    private struct Reader {
        let data: Data
        var offset = 0

        init(_ data: Data) { self.data = Data(data) }

        mutating func bytes(_ count: Int) -> Data? {
            guard count >= 0, offset + count <= data.count else { return nil }
            defer { offset += count }
            return data.subdata(in: offset..<(offset + count))
        }

        mutating func skip(_ count: Int) -> Bool { bytes(count) != nil }

        mutating func string(_ count: Int) -> String? {
            bytes(count).flatMap { String(data: $0, encoding: .utf8) }
        }

        mutating func uint32LE() -> UInt32? {
            guard let b = bytes(4) else { return nil }
            return UInt32(b[b.startIndex]) | UInt32(b[b.startIndex + 1]) << 8 | UInt32(b[b.startIndex + 2]) << 16 | UInt32(b[b.startIndex + 3]) << 24
        }

        mutating func uint32BE() -> UInt32? {
            guard let b = bytes(4) else { return nil }
            return UInt32(b[b.startIndex]) << 24 | UInt32(b[b.startIndex + 1]) << 16 | UInt32(b[b.startIndex + 2]) << 8 | UInt32(b[b.startIndex + 3])
        }
    }
}
