// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// The list of tracks queued for playback, with a cursor, repeat and
/// shuffle. A value type with no I/O, so the whole behaviour is unit
/// tested and the UI can hold it as plain state.
///
/// Entries, not tracks, are the unit: the same track may be queued twice,
/// and each occurrence has its own identity. `entries` is the order the
/// user built; `playbackOrder` is what actually plays, which only differs
/// while shuffled.
public struct PlayQueue: Equatable, Sendable {
    public enum RepeatMode: CaseIterable, Equatable, Sendable {
        case off, all, one
    }

    public struct Entry: Identifiable, Equatable, Sendable {
        public let id: UUID
        public let track: Track

        public init(id: UUID = UUID(), track: Track) {
            self.id = id
            self.track = track
        }
    }

    /// Insertion order.
    public private(set) var entries: [Entry] = []
    /// Entry ids in the order they play.
    private var order: [UUID] = []
    /// Index into `order`; nil only when the queue is empty.
    private var position: Int?

    public var repeatMode: RepeatMode = .off
    public private(set) var isShuffled = false

    public init() {}

    // MARK: - Reading

    public var isEmpty: Bool { entries.isEmpty }
    public var count: Int { entries.count }

    public var currentEntry: Entry? {
        guard let position else { return nil }
        return entry(order[position])
    }

    public var current: Track? { currentEntry?.track }

    public var playbackOrder: [Entry] { order.compactMap(entry) }

    /// Entries after the current one in playback order.
    public var upcoming: [Entry] {
        guard let position else { return [] }
        return order[(position + 1)...].compactMap(entry)
    }

    /// Whether `advance()` would move to another entry.
    public var hasNext: Bool {
        guard let position else { return false }
        return repeatMode != .off || position + 1 < order.count
    }

    /// What `advance()` would make current, without moving. The player
    /// backend preloads this for a gapless transition.
    public var nextTrack: Track? {
        guard let position else { return nil }
        switch repeatMode {
        case .one:
            return current
        case .all, .off:
            if position + 1 < order.count { return entry(order[position + 1])?.track }
            return repeatMode == .all ? entry(order[0])?.track : nil
        }
    }

    // MARK: - Building

    /// Replaces the whole queue and makes `tracks[index]` current. While
    /// shuffled, the new queue is reshuffled with that track first.
    public mutating func replace(with tracks: [Track], startingAt index: Int = 0) {
        entries = tracks.map { Entry(track: $0) }
        order = entries.map(\.id)
        position = entries.isEmpty ? nil : min(max(index, 0), entries.count - 1)
        if isShuffled {
            var rng = SystemRandomNumberGenerator()
            shuffle(using: &rng)
        }
    }

    /// Appends to the end of both orders. Appended tracks play after
    /// everything already queued, in the given order, even while shuffled.
    public mutating func append(_ tracks: [Track]) {
        let new = tracks.map { Entry(track: $0) }
        entries.append(contentsOf: new)
        order.append(contentsOf: new.map(\.id))
        if position == nil, !entries.isEmpty {
            position = 0
        }
    }

    /// Inserts right after the current entry in both orders. On an empty
    /// queue this is the same as `append`.
    public mutating func playNext(_ tracks: [Track]) {
        guard let position, let currentID = order[safe: position] else {
            append(tracks)
            return
        }
        let new = tracks.map { Entry(track: $0) }
        let entryIndex = entries.firstIndex { $0.id == currentID } ?? entries.count - 1
        entries.insert(contentsOf: new, at: entryIndex + 1)
        order.insert(contentsOf: new.map(\.id), at: position + 1)
    }

    /// Removing the current entry makes the next one current; at the end
    /// of the queue, the previous one.
    public mutating func remove(_ id: UUID) {
        guard let orderIndex = order.firstIndex(of: id) else { return }
        entries.removeAll { $0.id == id }
        order.remove(at: orderIndex)
        guard let position else { return }
        if order.isEmpty {
            self.position = nil
        } else if orderIndex < position {
            self.position = position - 1
        } else if position >= order.count {
            self.position = order.count - 1
        }
    }

    /// Removes every entry whose track matches. If the current entry goes,
    /// the next remaining one in playback order becomes current (the
    /// previous one at the end), and the caller sees that in `current`.
    public mutating func removeAll(where shouldRemove: (Track) -> Bool) {
        let currentID = currentEntry?.id
        let currentOrderIndex = position
        entries.removeAll { shouldRemove($0.track) }
        let kept = Set(entries.map(\.id))
        order.removeAll { !kept.contains($0) }
        if order.isEmpty {
            position = nil
        } else if let currentID, let index = order.firstIndex(of: currentID) {
            position = index
        } else if let currentOrderIndex {
            position = min(currentOrderIndex, order.count - 1)
        }
    }

    public mutating func clear() {
        entries = []
        order = []
        position = nil
    }

    // MARK: - Moving the cursor

    /// Moves to the next entry and returns it. Returns nil, leaving the
    /// cursor on the last entry, when the end is reached with repeat off.
    @discardableResult
    public mutating func advance() -> Track? {
        guard let position else { return nil }
        switch repeatMode {
        case .one:
            return current
        case .all, .off:
            if position + 1 < order.count {
                self.position = position + 1
                return current
            }
            if repeatMode == .all {
                self.position = 0
                return current
            }
            return nil
        }
    }

    /// Moves to the previous entry. At the start, wraps with repeat all
    /// and otherwise stays put. Returns the new current entry.
    @discardableResult
    public mutating func goBack() -> Track? {
        guard let position else { return nil }
        if position > 0 {
            self.position = position - 1
        } else if repeatMode == .all {
            self.position = order.count - 1
        }
        return current
    }

    @discardableResult
    public mutating func jump(to id: UUID) -> Track? {
        guard let index = order.firstIndex(of: id) else { return nil }
        position = index
        return current
    }

    // MARK: - Shuffle

    public mutating func setShuffle(_ on: Bool) {
        var rng = SystemRandomNumberGenerator()
        setShuffle(on, using: &rng)
    }

    /// Turning shuffle on keeps the current entry playing and randomises
    /// what follows; turning it off restores insertion order with the
    /// cursor still on the same entry.
    public mutating func setShuffle(_ on: Bool, using rng: inout some RandomNumberGenerator) {
        isShuffled = on
        if on {
            shuffle(using: &rng)
        } else {
            let currentID = currentEntry?.id
            order = entries.map(\.id)
            position = currentID.flatMap { id in order.firstIndex(of: id) } ?? (order.isEmpty ? nil : 0)
        }
    }

    private mutating func shuffle(using rng: inout some RandomNumberGenerator) {
        guard let currentID = currentEntry?.id else {
            order = entries.map(\.id).shuffled(using: &rng)
            position = order.isEmpty ? nil : 0
            return
        }
        let rest = entries.map(\.id).filter { $0 != currentID }.shuffled(using: &rng)
        order = [currentID] + rest
        position = 0
    }

    // MARK: - Helpers

    private func entry(_ id: UUID) -> Entry? {
        entries.first { $0.id == id }
    }
}

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
