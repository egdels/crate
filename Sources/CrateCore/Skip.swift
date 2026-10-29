// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// Jumping back and forth in a track by some seconds. The player bar
/// offers it where it helps: audiobooks, radio plays, podcasts, and any
/// track long enough to look for a place in it (a live set, a DJ mix).
/// A four-minute song keeps previous and next alone. The practice panel
/// offers it always, in shorter steps.
public enum Skip {
    /// Back less than forward, like Apple's players: back is "what did
    /// she say?", forward is "skip this part".
    public static let back: TimeInterval = 15
    public static let forward: TimeInterval = 30
    /// The practice panel's step: a bar or two of music.
    public static let practiceStep: TimeInterval = 5
    /// From here on a track counts as long, whatever its genre.
    public static let longTrack: TimeInterval = 20 * 60
    /// How far before the loop's end a jump forward inside it stops.
    static let loopEndMargin: TimeInterval = 1

    /// Folded genre parts that mean spoken word, in English and German.
    static let spokenGenres = ["audiobook", "audio book", "horbuch", "horspiel", "spoken", "speech", "podcast", "lesung"]

    /// Whether the player bar offers the jumps for this track.
    public static func isOffered(for track: Track) -> Bool {
        if track.url.pathExtension.lowercased() == "m4b" || track.duration >= longTrack { return true }
        guard let genre = track.genre?.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil) else {
            return false
        }
        return spokenGenres.contains { genre.contains($0) }
    }

    /// How far an audiobook goes back when it plays on after a pause of
    /// the given length: after a breath nothing, after a while a
    /// sentence or two, the longer the pause the more, so the thread is
    /// picked up again.
    public static func rewind(afterPause seconds: TimeInterval) -> TimeInterval {
        switch seconds {
        case ..<5: 0
        case ..<60: 3
        case ..<3600: 10
        default: 20
        }
    }

    public enum Target: Equatable, Sendable {
        case position(TimeInterval)
        /// The jump went past the end: on to the next track, as if the
        /// track had finished.
        case nextTrack
    }

    /// Where a jump by `seconds` (negative: back) from `time` lands.
    /// Never before the start; inside a playing loop never out of it,
    /// since leaving the passage is a slip while practising.
    public static func target(
        from time: TimeInterval, by seconds: TimeInterval, duration: TimeInterval, loop: PracticeLoop? = nil
    ) -> Target {
        let to = time + seconds
        if let loop, loop.contains(time) {
            return .position(min(max(to, loop.start), max(loop.start, loop.end - loopEndMargin)))
        }
        if duration > 0, to >= duration { return .nextTrack }
        return .position(max(0, to))
    }
}
