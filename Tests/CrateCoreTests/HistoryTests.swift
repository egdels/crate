// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct HistoryTests {
    let a = makeTrack("a", album: "One")
    let b = makeTrack("b", album: "One")
    let c = makeTrack("c", album: "Two", folder: "/music/two")

    @Test func recordPutsTheNewestFirstOnce() {
        var history = History()
        history.record(a)
        history.record(b)
        history.record(a)
        #expect(history.ids == [a.id, b.id])
        #expect(history.tracks(from: [a, b, c]) == [a, b])
    }

    @Test func recordIsCapped() {
        var history = History()
        for i in 0..<(History.limit + 10) { history.record(makeTrack("t\(i)")) }
        #expect(history.ids.count == History.limit)
        #expect(history.ids.first == "/music/t\(History.limit + 9).mp3")
    }

    @Test func tracksSkipGoneIdsAndRemoveAllPrunesAFolder() {
        var history = History(ids: ["/elsewhere/gone.mp3", c.id, a.id])
        #expect(history.tracks(from: [a, b, c]) == [c, a])
        history.removeAll(under: URL(fileURLWithPath: "/music/two"))
        #expect(history.ids == ["/elsewhere/gone.mp3", a.id])
    }

    @Test func storeRoundTrip() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("crate-history-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = JSONHistoryStore(fileURL: url)
        #expect(try store.load() == nil)
        var history = History()
        history.record(b)
        history.record(a)
        try store.save(history)
        #expect(try store.load() == history)
    }

    @Test func recentlyAddedIsNewestDayFirstKeepingAlbumOrderWithinADay() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        func at(_ day: Int, _ hour: Int) -> Date { Date(timeIntervalSince1970: TimeInterval(day * 86_400 + hour * 3_600)) }
        var old1 = makeTrack("old1"); old1.modified = at(1, 10)
        var old2 = makeTrack("old2"); old2.modified = at(1, 9)
        var new1 = makeTrack("new1"); new1.modified = at(5, 1)
        var new2 = makeTrack("new2"); new2.modified = at(5, 23)
        let recent = Track.recentlyAdded([old1, old2, new1, new2], limit: 3, calendar: calendar)
        #expect(recent.map(\.title) == ["new1", "new2", "old1"])
    }
}
