// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

/// Builds the metadata part of a FLAC file by hand: marker, STREAMINFO,
/// VORBIS_COMMENT, PICTURE. No audio frames; the parser never reads them.
func flacBytes(comments: [String], sampleRate: Int = 44_100, totalSamples: Int = 44_100 * 90, picture: Data? = nil) -> Data {
    var out = Data("fLaC".utf8)
    func block(_ type: UInt8, _ body: Data, last: Bool) {
        out.append(type | (last ? 0x80 : 0))
        out.append(contentsOf: [UInt8(body.count >> 16 & 0xff), UInt8(body.count >> 8 & 0xff), UInt8(body.count & 0xff)])
        out.append(body)
    }
    var info = Data(repeating: 0, count: 34)
    info[10] = UInt8(sampleRate >> 12 & 0xff)
    info[11] = UInt8(sampleRate >> 4 & 0xff)
    info[12] = UInt8((sampleRate & 0x0f) << 4) | UInt8((2 - 1) << 1)   // 2 channels, 16 bit continues
    info[13] = UInt8(totalSamples >> 32 & 0x0f)
    info[14] = UInt8(totalSamples >> 24 & 0xff)
    info[15] = UInt8(totalSamples >> 16 & 0xff)
    info[16] = UInt8(totalSamples >> 8 & 0xff)
    info[17] = UInt8(totalSamples & 0xff)
    block(0, info, last: false)
    var vorbis = Data()
    func le(_ n: Int) { vorbis.append(contentsOf: [UInt8(n & 0xff), UInt8(n >> 8 & 0xff), UInt8(n >> 16 & 0xff), UInt8(n >> 24 & 0xff)]) }
    let vendor = Data("test".utf8)
    le(vendor.count); vorbis.append(vendor)
    le(comments.count)
    for comment in comments { let d = Data(comment.utf8); le(d.count); vorbis.append(d) }
    block(4, vorbis, last: picture == nil)
    if let picture {
        var pic = Data()
        func be(_ n: Int) { pic.append(contentsOf: [UInt8(n >> 24 & 0xff), UInt8(n >> 16 & 0xff), UInt8(n >> 8 & 0xff), UInt8(n & 0xff)]) }
        be(3); let mime = Data("image/png".utf8); be(mime.count); pic.append(mime)
        be(0); be(8); be(8); be(24); be(0); be(picture.count); pic.append(picture)
        block(6, pic, last: true)
    }
    return out
}

@Suite struct FLACMetadataTests {
    @Test func readsCommentsDurationAndCover() throws {
        let dir = try TemporaryDirectory()
        let file = dir.url.appendingPathComponent("song.flac")
        let png = pngData(width: 8, height: 8)
        try flacBytes(comments: ["TITLE=My Baby", "artist=Ada Lark", "ALBUM=Songs", "TRACKNUMBER=3/12", "DATE=2010-05-01", "DISCNUMBER=1",
                                 "COMPOSER=Ada Lark", "COMPILATION=1", "ARTISTSORT=Lark, Ada", "ALBUMARTISTSORT=Lark", "ALBUMSORT=Songs, The"],
                      picture: png).write(to: file)
        let flac = try FLACMetadata.read(file)
        #expect(flac.value("title") == "My Baby")
        #expect(flac.value("ARTIST") == "Ada Lark")
        #expect(abs(flac.duration - 90) < 0.001)
        #expect(flac.cover?.type == 3)
        #expect(flac.cover?.data == png)

        let meta = AVFoundationMetadataReader.metadata(from: flac)
        #expect(meta.title == "My Baby")
        #expect(meta.album == "Songs")
        #expect(meta.trackNumber == 3)
        #expect(meta.trackTotal == 12)
        #expect(meta.year == 2010)
        #expect(meta.discNumber == 1)
        #expect(abs(meta.duration - 90) < 0.001)
        #expect(meta.composer == "Ada Lark")
        #expect(meta.compilation)
        #expect(meta.artistSort == "Lark, Ada")
        #expect(meta.albumArtistSort == "Lark")
        #expect(meta.albumSort == "Songs, The")

        try flacBytes(comments: ["TITLE=Plain", "COMPILATION=0"]).write(to: file)
        let plain = AVFoundationMetadataReader.metadata(from: try FLACMetadata.read(file))
        #expect(!plain.compilation)
        #expect(plain.composer == nil)
    }

    @Test func readerUsesTheParserForFLAC() async throws {
        let dir = try TemporaryDirectory()
        let file = dir.url.appendingPathComponent("song.flac")
        try flacBytes(comments: ["TITLE=Parsed", "ALBUMARTIST=Band", "TOTALTRACKS=9"]).write(to: file)
        let meta = try await AVFoundationMetadataReader().read(file)
        #expect(meta.title == "Parsed")
        #expect(meta.albumArtist == "Band")
        #expect(meta.trackTotal == 9)
        let png = pngData(width: 4, height: 4)
        try flacBytes(comments: [], picture: png).write(to: file)
        #expect(try await AVFoundationArtworkReader().artwork(for: file) == png)
    }

    @Test func notAFLACFileThrows() throws {
        let dir = try TemporaryDirectory()
        let file = try dir.file("x.flac", contents: "not flac at all")
        #expect(throws: FLACMetadata.NotFLAC.self) { try FLACMetadata.read(file) }
    }
}
