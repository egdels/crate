// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct PlayQueueTests {
    let a = makeTrack("a"), b = makeTrack("b"), c = makeTrack("c"), d = makeTrack("d")

    @Test func emptyQueueHasNoCurrent() {
        var queue = PlayQueue()
        #expect(queue.current == nil)
        #expect(queue.advance() == nil)
        #expect(queue.goBack() == nil)
        #expect(!queue.hasNext)
    }

    @Test func replaceStartsAtGivenIndex() {
        var queue = PlayQueue()
        queue.replace(with: [a, b, c], startingAt: 1)
        #expect(queue.current == b)
        #expect(queue.upcoming.map(\.track) == [c])
        queue.replace(with: [a, b, c], startingAt: 99)
        #expect(queue.current == c)
    }

    @Test func advanceStopsAtEndWithRepeatOff() {
        var queue = PlayQueue()
        queue.replace(with: [a, b])
        #expect(queue.advance() == b)
        #expect(!queue.hasNext)
        #expect(queue.advance() == nil)
        #expect(queue.current == b)
    }

    @Test func repeatAllWrapsBothWays() {
        var queue = PlayQueue()
        queue.replace(with: [a, b])
        queue.repeatMode = .all
        #expect(queue.goBack() == b)
        #expect(queue.advance() == a)
        #expect(queue.hasNext)
    }

    @Test func nextTrackPreviewsAdvance() {
        var queue = PlayQueue()
        queue.replace(with: [makeTrack("a"), makeTrack("b")])
        #expect(queue.nextTrack?.title == "b")
        queue.advance()
        #expect(queue.nextTrack == nil)
        queue.repeatMode = .all
        #expect(queue.nextTrack?.title == "a")
        queue.repeatMode = .one
        #expect(queue.nextTrack?.title == "b")
        #expect(PlayQueue().nextTrack == nil)
    }

    @Test func repeatOneStaysPut() {
        var queue = PlayQueue()
        queue.replace(with: [a, b])
        queue.repeatMode = .one
        #expect(queue.advance() == a)
        #expect(queue.advance() == a)
    }

    @Test func goBackAtStartStaysWithRepeatOff() {
        var queue = PlayQueue()
        queue.replace(with: [a, b])
        #expect(queue.goBack() == a)
    }

    @Test func appendOnEmptyQueueMakesFirstCurrent() {
        var queue = PlayQueue()
        queue.append([a, b])
        #expect(queue.current == a)
        #expect(queue.upcoming.map(\.track) == [b])
    }

    @Test func playNextInsertsAfterCurrent() {
        var queue = PlayQueue()
        queue.replace(with: [a, b, c])
        queue.playNext([d])
        #expect(queue.entries.map(\.track) == [a, d, b, c])
        #expect(queue.advance() == d)
        #expect(queue.advance() == b)
    }

    @Test func playNextOnEmptyQueueAppends() {
        var queue = PlayQueue()
        queue.playNext([a])
        #expect(queue.current == a)
    }

    @Test func removingBeforeCurrentKeepsCurrent() {
        var queue = PlayQueue()
        queue.replace(with: [a, b, c], startingAt: 2)
        queue.remove(queue.entries[0].id)
        #expect(queue.current == c)
        #expect(queue.count == 2)
    }

    @Test func removingCurrentMovesToNext() {
        var queue = PlayQueue()
        queue.replace(with: [a, b, c], startingAt: 1)
        queue.remove(queue.entries[1].id)
        #expect(queue.current == c)
    }

    @Test func removingLastCurrentMovesToPrevious() {
        var queue = PlayQueue()
        queue.replace(with: [a, b], startingAt: 1)
        queue.remove(queue.entries[1].id)
        #expect(queue.current == a)
        queue.remove(queue.entries[0].id)
        #expect(queue.current == nil)
        #expect(queue.isEmpty)
    }

    @Test func removeAllKeepsCurrentWhenItSurvives() {
        var queue = PlayQueue()
        queue.replace(with: [a, b, c], startingAt: 2)
        queue.removeAll { $0 == a }
        #expect(queue.current == c)
        #expect(queue.entries.map(\.track) == [b, c])
    }

    @Test func removeAllMovesOnWhenCurrentGoes() {
        var queue = PlayQueue()
        queue.replace(with: [a, b, c], startingAt: 1)
        queue.removeAll { $0 == b }
        #expect(queue.current == c)
        queue.removeAll { $0 == c }
        #expect(queue.current == a)
        queue.removeAll { _ in true }
        #expect(queue.current == nil)
        #expect(queue.isEmpty)
    }

    @Test func sameTrackTwiceHasTwoEntries() {
        var queue = PlayQueue()
        queue.replace(with: [a, a])
        #expect(queue.count == 2)
        #expect(queue.entries[0].id != queue.entries[1].id)
        queue.remove(queue.entries[0].id)
        #expect(queue.count == 1)
        #expect(queue.current == a)
    }

    @Test func jumpSelectsEntry() {
        var queue = PlayQueue()
        queue.replace(with: [a, b, c])
        #expect(queue.jump(to: queue.entries[2].id) == c)
        #expect(queue.jump(to: UUID()) == nil)
        #expect(queue.current == c)
    }

    @Test func shuffleKeepsCurrentFirstAndRestoresOrder() {
        var queue = PlayQueue()
        let tracks = (0..<20).map { makeTrack("t\($0)") }
        queue.replace(with: tracks, startingAt: 5)
        var rng = SeededGenerator(seed: 42)
        queue.setShuffle(true, using: &rng)

        #expect(queue.isShuffled)
        #expect(queue.current == tracks[5])
        #expect(queue.playbackOrder.first?.track == tracks[5])
        #expect(Set(queue.playbackOrder.map(\.id)) == Set(queue.entries.map(\.id)))
        #expect(queue.playbackOrder.map(\.track) != tracks)
        #expect(queue.entries.map(\.track) == tracks)

        queue.advance()
        let playing = queue.current
        queue.setShuffle(false, using: &rng)
        #expect(!queue.isShuffled)
        #expect(queue.current == playing)
        #expect(queue.playbackOrder.map(\.track) == tracks)
    }

    @Test func shuffleOnEmptyQueueIsHarmless() {
        var queue = PlayQueue()
        var rng = SeededGenerator(seed: 1)
        queue.setShuffle(true, using: &rng)
        #expect(queue.current == nil)
        queue.append([a])
        #expect(queue.current == a)
    }

    @Test func appendWhileShuffledPlaysLast() {
        var queue = PlayQueue()
        queue.replace(with: [a, b, c])
        var rng = SeededGenerator(seed: 7)
        queue.setShuffle(true, using: &rng)
        queue.append([d])
        #expect(queue.playbackOrder.last?.track == d)
        #expect(queue.entries.last?.track == d)
    }

    @Test func clearEmptiesEverything() {
        var queue = PlayQueue()
        queue.replace(with: [a, b])
        queue.clear()
        #expect(queue.isEmpty)
        #expect(queue.current == nil)
        #expect(queue.playbackOrder.isEmpty)
    }
}
