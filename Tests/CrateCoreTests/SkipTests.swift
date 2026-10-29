// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct SkipTests {
    private func track(_ file: String, genre: String? = nil, duration: TimeInterval = 240) -> Track {
        Track(
            url: URL(fileURLWithPath: "/books/\(file)"), fileSize: 1, modified: Date(timeIntervalSince1970: 0),
            title: file, genre: genre, duration: duration)
    }

    @Test func offeredForSpokenWordAndLongTracks() {
        #expect(Skip.isOffered(for: track("Book.M4B")))
        #expect(Skip.isOffered(for: track("01.mp3", genre: "Hörbuch")))
        #expect(Skip.isOffered(for: track("01.mp3", genre: "HÖRSPIEL")))
        #expect(Skip.isOffered(for: track("01.mp3", genre: "Audiobooks")))
        #expect(Skip.isOffered(for: track("01.mp3", genre: "Spoken Word")))
        #expect(Skip.isOffered(for: track("01.mp3", genre: "Podcast")))
        #expect(Skip.isOffered(for: track("Live Set.mp3", genre: "Techno", duration: 75 * 60)))
        #expect(!Skip.isOffered(for: track("Song.m4a", genre: "Rock")))
        #expect(!Skip.isOffered(for: track("Song.mp3")))
    }

    @Test func jumpsStayInsideTheTrack() {
        #expect(Skip.target(from: 60, by: -15, duration: 300) == .position(45))
        #expect(Skip.target(from: 10, by: -15, duration: 300) == .position(0))
        #expect(Skip.target(from: 100, by: 30, duration: 300) == .position(130))
        #expect(Skip.target(from: 280, by: 30, duration: 300) == .nextTrack)
        // Duration not known yet: no next track, just forward.
        #expect(Skip.target(from: 280, by: 30, duration: 0) == .position(310))
    }

    @Test func jumpsInsideAPlayingLoopStayInTheLoop() throws {
        let loop = try #require(PracticeLoop(start: 40, end: 50))
        #expect(Skip.target(from: 43, by: -5, duration: 300, loop: loop) == .position(40))
        #expect(Skip.target(from: 47, by: 5, duration: 300, loop: loop) == .position(49))
        #expect(Skip.target(from: 42, by: 5, duration: 300, loop: loop) == .position(47))
        // Before the loop (its run-in) the jump is free.
        #expect(Skip.target(from: 20, by: -5, duration: 300, loop: loop) == .position(15))
        let tiny = try #require(PracticeLoop(start: 40, end: 40.5))
        #expect(Skip.target(from: 40.2, by: 5, duration: 300, loop: tiny) == .position(40))
    }
}
