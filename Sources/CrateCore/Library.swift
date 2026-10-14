// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// The music library: a set of watched folders and the tracks found in
/// them, kept in memory and saved through a `LibraryStore` after every
/// change. An actor, so the UI can read while a rescan runs.
///
/// Rescans are incremental: a file whose size and modification date match
/// the stored track is not read again, files that disappeared are dropped,
/// and a file whose tags cannot be read keeps its previous entry (or stays
/// absent) and is only counted as failed.
public actor Library {
    public struct RescanSummary: Equatable, Sendable {
        public var added = 0
        public var updated = 0
        public var removed = 0
        public var unchanged = 0
        /// Files whose tags could not be read; `failed` is their count.
        public var failedFiles: [URL] = []

        public var failed: Int { failedFiles.count }

        public init(added: Int = 0, updated: Int = 0, removed: Int = 0, unchanged: Int = 0, failedFiles: [URL] = []) {
            self.added = added
            self.updated = updated
            self.removed = removed
            self.unchanged = unchanged
            self.failedFiles = failedFiles
        }

        public static func + (lhs: Self, rhs: Self) -> Self {
            Self(
                added: lhs.added + rhs.added, updated: lhs.updated + rhs.updated,
                removed: lhs.removed + rhs.removed, unchanged: lhs.unchanged + rhs.unchanged,
                failedFiles: lhs.failedFiles + rhs.failedFiles)
        }
    }

    public private(set) var folders: [URL] = []
    /// Sorted by path.
    public private(set) var tracks: [Track] = []
    /// True after loading an index written by an older version whose
    /// tracks lack fields a newer one reads: the next full rescan reads
    /// every file, not only the changed ones, and clears this.
    public private(set) var needsRescan = false

    private let store: any LibraryStore
    private let reader: any MetadataReader
    private let scanner: LibraryScanner
    /// Files read at once during a rescan. Reading is latency-bound, on a
    /// network share almost entirely so: measured on an SMB share with
    /// 2,000 MP3s, 4 in flight took 32 s, 16 took 21 s and 32 took 27 s.
    private let concurrency: Int

    /// Progress of the reading phase of a rescan: files read so far and
    /// files to read. Called from the actor's context; the UI hops to the
    /// main actor itself.
    public typealias Progress = @Sendable (_ read: Int, _ total: Int) -> Void

    public init(
        store: any LibraryStore,
        metadataReader: any MetadataReader,
        scanner: LibraryScanner = LibraryScanner(),
        concurrency: Int = 16
    ) {
        self.store = store
        self.reader = metadataReader
        self.scanner = scanner
        self.concurrency = max(1, concurrency)
    }

    /// Restores the last saved state. Nothing saved yet means an empty
    /// library, not an error.
    public func load() throws {
        guard let snapshot = try store.load() else { return }
        folders = snapshot.folders
        tracks = snapshot.tracks.sorted { $0.url.path < $1.url.path }
        needsRescan = snapshot.version < LibrarySnapshot.currentVersion
    }

    public var albums: [Album] { Album.group(tracks, roots: folders) }

    public func track(for url: URL) -> Track? {
        let path = url.standardizedFileURL.path
        return tracks.first { $0.url.path == path }
    }

    /// Reads one file into a `Track` without adding it to the library,
    /// for playing something the user opened from outside the watched
    /// folders.
    public func probe(_ url: URL) async throws -> Track {
        let file = url.standardizedFileURL
        let values = try file.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
        let scanned = ScannedFile(
            url: file, fileSize: Int64(values.fileSize ?? 0), modified: values.contentModificationDate ?? .distantPast)
        return Self.makeTrack(scanned, try await reader.read(file))
    }

    /// Adds the folder (no-op when already watched) and scans it.
    @discardableResult
    public func addFolder(_ url: URL, progress: Progress? = nil) async throws -> RescanSummary {
        let folder = url.standardizedFileURL
        if !folders.contains(folder) {
            folders.append(folder)
        }
        return try await rescan(folder: folder, progress: progress)
    }

    /// Forgets the folder and every track under it.
    @discardableResult
    public func removeFolder(_ url: URL) throws -> Int {
        let folder = url.standardizedFileURL
        folders.removeAll { $0 == folder }
        let before = tracks.count
        tracks.removeAll { Self.isUnder($0.url, folder) }
        try save()
        return before - tracks.count
    }

    /// Rescans every watched folder.
    @discardableResult
    public func rescan(progress: Progress? = nil) async throws -> RescanSummary {
        var total = RescanSummary()
        for folder in folders {
            total = total + (try await rescan(folder: folder, progress: progress))
        }
        if needsRescan {
            needsRescan = false
            try save()
        }
        return total
    }

    @discardableResult
    public func rescan(folder url: URL, progress: Progress? = nil) async throws -> RescanSummary {
        let folder = url.standardizedFileURL
        let scanned = try scanner.scan(folder)
        let scannedPaths = Set(scanned.map(\.url.path))
        var existing = Dictionary(
            uniqueKeysWithValues: tracks.lazy.filter { Self.isUnder($0.url, folder) }.map { ($0.url.path, $0) })

        var summary = RescanSummary()
        var toRead: [ScannedFile] = []
        for file in scanned {
            if !needsRescan, let track = existing[file.url.path], Self.unchanged(track, file) {
                summary.unchanged += 1
            } else {
                toRead.append(file)
            }
        }

        for (file, result) in await readAll(toRead, progress: progress) {
            switch result {
            case .success(let meta):
                let wasKnown = existing[file.url.path] != nil
                existing[file.url.path] = Self.makeTrack(file, meta)
                if wasKnown { summary.updated += 1 } else { summary.added += 1 }
            case .failure:
                summary.failedFiles.append(file.url)
            }
        }
        summary.failedFiles.sort { $0.path < $1.path }

        let removedPaths = existing.keys.filter { !scannedPaths.contains($0) }
        summary.removed = removedPaths.count
        for path in removedPaths {
            existing[path] = nil
        }

        tracks.removeAll { Self.isUnder($0.url, folder) }
        tracks.append(contentsOf: existing.values)
        tracks.sort { $0.url.path < $1.url.path }
        try save()
        return summary
    }

    // MARK: - Internals

    private func save() throws {
        try store.save(LibrarySnapshot(folders: folders, tracks: tracks))
    }

    private func readAll(_ files: [ScannedFile], progress: Progress?) async -> [(ScannedFile, Result<TrackMetadata, any Error>)] {
        let reader = self.reader
        var results: [(ScannedFile, Result<TrackMetadata, any Error>)] = []
        results.reserveCapacity(files.count)
        progress?(0, files.count)
        await withTaskGroup(of: (ScannedFile, Result<TrackMetadata, any Error>).self) { group in
            for (index, file) in files.enumerated() {
                if index >= concurrency, let done = await group.next() {
                    results.append(done)
                    progress?(results.count, files.count)
                }
                group.addTask {
                    do {
                        return (file, .success(try await reader.read(file.url)))
                    } catch {
                        return (file, .failure(error))
                    }
                }
            }
            for await done in group {
                results.append(done)
                progress?(results.count, files.count)
            }
        }
        return results
    }

    static func makeTrack(_ file: ScannedFile, _ meta: TrackMetadata) -> Track {
        Track(
            url: file.url,
            fileSize: file.fileSize,
            modified: file.modified,
            title: meta.title?.trimmed.nonEmpty ?? file.url.deletingPathExtension().lastPathComponent,
            artist: meta.artist?.trimmed.nonEmpty,
            albumArtist: meta.albumArtist?.trimmed.nonEmpty,
            album: meta.album?.trimmed.nonEmpty,
            trackNumber: meta.trackNumber,
            trackTotal: meta.trackTotal,
            discNumber: meta.discNumber,
            discTotal: meta.discTotal,
            year: meta.year,
            genre: meta.genre?.trimmed.nonEmpty,
            composer: meta.composer?.trimmed.nonEmpty,
            compilation: meta.compilation,
            artistSort: meta.artistSort?.trimmed.nonEmpty,
            albumArtistSort: meta.albumArtistSort?.trimmed.nonEmpty,
            albumSort: meta.albumSort?.trimmed.nonEmpty,
            trackGain: meta.trackGain,
            albumGain: meta.albumGain,
            duration: meta.duration
        )
    }

    static func unchanged(_ track: Track, _ file: ScannedFile) -> Bool {
        track.fileSize == file.fileSize
            && abs(track.modified.timeIntervalSince(file.modified)) < 0.001
    }

    public static func isUnder(_ url: URL, _ folder: URL) -> Bool {
        url.path.hasPrefix(folder.path.hasSuffix("/") ? folder.path : folder.path + "/")
    }
}
