// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct LyricsTests {
    @Test func lrcLinesCarryTheirTimes() {
        let lyrics = Lyrics.parse("[ti:Song]\n[ar:Artist]\n\n[00:00.00]First line\n[00:07.50]Second line\n[00:14:00]Third line\n")
        #expect(lyrics.isTimed)
        #expect(lyrics.lines.map(\.text) == ["First line", "Second line", "Third line"])
        #expect(lyrics.lines.map { $0.time! } == [0, 7.5, 14])
    }

    @Test func severalStampsOnOneLineBecomeSeveralLinesInOrder() {
        let lyrics = Lyrics.parse("[00:30.00][00:10.00]Chorus\n[00:20.00]Verse")
        #expect(lyrics.lines.map(\.text) == ["Chorus", "Verse", "Chorus"])
        #expect(lyrics.lines.map { $0.time! } == [10, 20, 30])
    }

    @Test func offsetShiftsTheTimes() {
        // LRC convention: a positive offset shows the lines sooner.
        let lyrics = Lyrics.parse("[offset:500]\n[00:01.00]a\n[00:02.00]b")
        #expect(lyrics.lines.map { $0.time! } == [0.5, 1.5])
        let later = Lyrics.parse("[offset:-500]\n[00:01.00]a\n[00:02.00]b")
        #expect(later.lines.map { $0.time! } == [1.5, 2.5])
    }

    @Test func plainTextKeepsParagraphs() {
        let lyrics = Lyrics.parse("\n\nVerse one\nline two\n\n\n\nChorus\n\n")
        #expect(!lyrics.isTimed)
        #expect(lyrics.lines.map(\.text) == ["Verse one", "line two", "", "Chorus"])
        #expect(lyrics.index(at: 10) == nil)
    }

    @Test func indexFollowsTheTime() {
        let lyrics = Lyrics.parse("[00:05.00]a\n[00:10.00]b\n[00:15.00]c")
        #expect(lyrics.index(at: 0) == nil)
        #expect(lyrics.index(at: 5) == 0)
        #expect(lyrics.index(at: 12.3) == 1)
        #expect(lyrics.index(at: 99) == 2)
    }

    @Test func emptyTextIsEmpty() {
        #expect(Lyrics.parse("").isEmpty)
        #expect(Lyrics.parse("[ti:only a header]").isEmpty)
    }

    @Test func sidecarNextToTheFileOrInLyricsFolder() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("crate-lyrics-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory.appendingPathComponent("lyrics"), withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let a = makeTrack("a", folder: directory.path)
        let b = makeTrack("b", folder: directory.path)
        let c = makeTrack("c", folder: directory.path)
        let d = makeTrack("d", folder: directory.path)
        for track in [a, b, c, d] { try Data().write(to: track.url) }
        try Data("[00:01.00]beside".utf8).write(to: directory.appendingPathComponent("a.lrc"))
        try Data("below".utf8).write(to: directory.appendingPathComponent("lyrics/b.lrc"))
        // A plain .txt counts too, after an .lrc anywhere.
        try Data("plain text".utf8).write(to: directory.appendingPathComponent("lyrics/d.txt"))
        try Data("not this".utf8).write(to: directory.appendingPathComponent("a.txt"))
        let source = FileLyricsSource()
        #expect(await source.lyrics(for: a)?.lines == [Lyrics.Line(time: 1, text: "beside")])
        #expect(await source.lyrics(for: b)?.lines == [Lyrics.Line(text: "below")])
        #expect(await source.lyrics(for: c) == nil)
        #expect(await source.lyrics(for: d)?.lines == [Lyrics.Line(text: "plain text")])
    }

    @Test func plainTextKeepsSectionMarks() {
        let lyrics = Lyrics.parse("[ti:Song]\n[Chorus]\n[Verse 2] We will\nrock you")
        #expect(!lyrics.isTimed)
        #expect(lyrics.lines.map(\.text) == ["[Chorus]", "[Verse 2] We will", "rock you"])

        // In a timed file a mark without a stamp still goes.
        let timed = Lyrics.parse("[Chorus]\n[00:01.00]a")
        #expect(timed.lines.map(\.text) == ["a"])
    }

    @Test func byteOrderMarkAndWindows1252() {
        let lyrics = Lyrics.parse("\u{FEFF}[00:12.34]first\n[00:13.00]second")
        #expect(lyrics.lines.map(\.text) == ["first", "second"])

        let latin = Data([0x47, 0x72, 0xFC, 0xDF, 0x65])  // "Grüße" in Windows-1252
        #expect(FileLyricsSource.decode(latin) == "Grüße")
        #expect(FileLyricsSource.decode(Data("Grüße".utf8)) == "Grüße")
    }
}
