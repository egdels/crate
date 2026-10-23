// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct PracticeSettingsTests {
    @Test func valuesAreClampedToWhatTheAudioUnitPlaysWell() {
        let settings = PracticeSettings(tempo: 5, semitones: -20, cents: 80)
        #expect(settings.tempo == 2)
        #expect(settings.semitones == -12)
        #expect(settings.cents == 50)
        #expect(PracticeSettings(tempo: 0.1).tempo == 0.5)
    }

    @Test func pitchCombinesSemitonesAndCents() {
        #expect(PracticeSettings(semitones: -2, cents: 15).pitchCents == -185)
        #expect(!PracticeSettings.neutral.changesSound)
        #expect(PracticeSettings(cents: 1).changesSound)
        #expect(!PracticeSettings(loop: PracticeLoop(start: 1, end: 2)).changesSound)
    }

    @Test func referencePitchFollowsTheCents() {
        #expect(PracticeSettings.neutral.referencePitch == 440)
        #expect(abs(PracticeSettings(cents: -32).referencePitch - 432) < 0.1)
        #expect(abs(PracticeSettings(cents: 8).referencePitch - 442) < 0.1)
    }

    @Test func resetKeepsTheLoop() {
        let loop = PracticeLoop(start: 3, end: 7)
        var settings = PracticeSettings(tempo: 0.75, semitones: 2, cents: -10, loop: loop)
        settings.resetSound()
        #expect(settings == PracticeSettings(loop: loop))
    }

    @Test func loopSwapsReversedPointsAndRefusesTinyOnes() throws {
        let loop = try #require(PracticeLoop(start: 9, end: 4))
        #expect(loop.start == 4 && loop.end == 9)
        #expect(loop.length == 5)
        #expect(loop.contains(4) && loop.contains(8.9) && !loop.contains(9))
        #expect(PracticeLoop(start: 1, end: 1.1) == nil)
        #expect(PracticeLoop(start: -1, end: 2)?.start == 0)
    }

    @Test func loopIsFittedIntoAShorterFile() {
        let loop = PracticeLoop(start: 10, end: 20)!
        #expect(loop.clamped(to: 15) == PracticeLoop(start: 10, end: 15))
        #expect(loop.clamped(to: 5) == nil)
        #expect(loop.clamped(to: 0) == loop, "an unknown length leaves it alone")
    }

    @Test func aSwitchedOffLoopIsKeptButDoesNotPlay() throws {
        let loop = try #require(PracticeLoop(start: 2, end: 6))
        var settings = PracticeSettings(loop: loop, loopOff: true)
        #expect(settings.loop == loop && settings.activeLoop == nil)
        #expect(!settings.changesPlayback)
        #expect(!settings.isNeutral, "the loop is still remembered")
        settings.loopOff = false
        #expect(settings.activeLoop == loop && settings.changesPlayback)
        settings.loopOff = true
        settings.loop = nil
        #expect(!settings.loopOff && settings.isNeutral, "no loop, nothing to switch off")
        #expect(!PracticeSettings(loopOff: true).loopOff)
    }

    @Test func bookKeepsOnlyTracksThatWereChanged() {
        var book = PracticeBook()
        book["/music/a.mp3"] = PracticeSettings(tempo: 0.8)
        book["/music/b.mp3"] = .neutral
        #expect(book.count == 1)
        #expect(book["/music/a.mp3"].tempo == 0.8)
        #expect(book["/music/b.mp3"] == .neutral)
        book["/music/a.mp3"] = .neutral
        #expect(book.count == 0)
    }

    @Test func removingAFolderDropsOnlyItsTracks() {
        var book = PracticeBook()
        book["/music/two/a.mp3"] = PracticeSettings(semitones: 1)
        book["/music/twofold/b.mp3"] = PracticeSettings(semitones: 2)
        book["/music2/c.mp3"] = PracticeSettings(semitones: 3)
        book.removeAll(under: URL(fileURLWithPath: "/music/two"))
        #expect(Set(book.entries.keys) == ["/music/twofold/b.mp3", "/music2/c.mp3"])
    }

    @Test func storeRoundTripsAndClampsWhatItReads() throws {
        let dir = try TemporaryDirectory()
        let store = JSONPracticeStore(fileURL: dir.url.appendingPathComponent("deep/practice.json"))
        #expect(try store.load() == nil)

        var book = PracticeBook()
        book["/music/a.mp3"] = PracticeSettings(tempo: 0.7, semitones: -3, cents: 12, loop: PracticeLoop(start: 1, end: 5))
        book["/music/b.mp3"] = PracticeSettings(cents: -32)
        try store.save(book)
        #expect(try store.load() == book)
        let text = try String(contentsOf: store.fileURL, encoding: .utf8)
        #expect(text.contains("\"version\" : 1"))
        #expect(text.contains("\"/music/a.mp3\""))

        #expect(!text.contains("loopOff"), "written only when set")
        book["/music/a.mp3"].loopOff = true
        try store.save(book)
        #expect(try store.load()?["/music/a.mp3"].loopOff == true)

        try Data(#"{"version":1,"tracks":{"/x.mp3":{"tempo":9,"semitones":0}}}"#.utf8).write(to: store.fileURL)
        #expect(try store.load()?["/x.mp3"] == PracticeSettings(tempo: 2))
    }

    @Test func defaultFileSitsNextToTheLibraryIndex() throws {
        let library = try JSONLibraryStore.defaultFileURL(appName: "CrateTests")
        let practice = try JSONPracticeStore.defaultFileURL(appName: "CrateTests")
        #expect(practice.deletingLastPathComponent() == library.deletingLastPathComponent())
        #expect(practice.lastPathComponent == "practice.json")
    }
}
