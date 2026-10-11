// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AppKit
import CrateCore
import Foundation

typealias PlayQueueRepeat = PlayQueue.RepeatMode

/// The one piece of UI state: a snapshot of the library for the views,
/// the queue, and the player. Everything the views call goes through
/// here; the views never touch `Library` or `AudioPlayer` directly.
@MainActor
final class PlayerModel: ObservableObject {
    // ObservableObject rather than @Observable: the app runs on macOS 12.
    @Published private(set) var folders: [URL] = []
    @Published private(set) var tracks: [Track] = []
    @Published private(set) var albums: [Album] = []
    @Published private(set) var queue = PlayQueue()
    @Published private(set) var isPlaying = false
    @Published private(set) var currentTime: TimeInterval = 0
    /// Cover of the album the current track belongs to, for the player bar.
    @Published private(set) var currentArtwork: CGImage?
    @Published private(set) var isScanning = false
    /// False until the saved library has been read; until then the views
    /// must not show the empty state as if there were no folder.
    @Published private(set) var isLoaded = false
    /// Files read so far and files to read, while a scan is in its reading
    /// phase; nil while it lists the folder.
    @Published private(set) var scanProgress: (read: Int, total: Int)?
    @Published private(set) var lastScan: Library.RescanSummary?
    @Published var lastError: String?
    /// A one-line result of something the user just did, shown in the
    /// footer until the next scan; nil most of the time.
    @Published private(set) var notice: String?
    /// Whether this copy of Crate is what Finder opens audio files with.
    @Published private(set) var opensAudioFiles = false
    @Published var selection: Selection?
    @Published var searchText = ""

    /// What the detail column shows: one album, or every track.
    enum Selection: Hashable {
        case album(Album.ID)
        case allTracks
    }

    @Published var volume: Float = 1 {
        didSet { player.volume = volume }
    }

    private let library: Library
    private let player: any AudioPlayer
    private let artworkCache: ArtworkCache
    private var ticker: Timer?
    private var artworkTask: Task<Void, Never>?
    /// Set by `live()`; nil in tests and previews.
    var nowPlaying: NowPlaying?

    init(library: Library, player: any AudioPlayer, artwork: ArtworkCache) {
        self.library = library
        self.player = player
        self.artworkCache = artwork
        player.onFinished = { [weak self] in self?.trackFinished() }
    }

    /// The real thing: JSON index in Application Support, AVFoundation
    /// for tags and audio.
    static func live() -> PlayerModel {
        let fileURL = (try? JSONLibraryStore.defaultFileURL(appName: "Crate"))
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("crate-library.json")
        let library = Library(store: JSONLibraryStore(fileURL: fileURL), metadataReader: AVFoundationMetadataReader())
        let artworkDirectory = (try? ArtworkCache.defaultDirectory(appName: "Crate"))
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("crate-artwork", isDirectory: true)
        let artwork = ArtworkCache(directory: artworkDirectory, reader: AVFoundationArtworkReader())
        let model = PlayerModel(library: library, player: AVAudioEngineBackend(), artwork: artwork)
        model.nowPlaying = NowPlaying(model: model)
        return model
    }

    var current: Track? { queue.current }

    var selectedAlbum: Album? {
        guard case .album(let id) = selection else { return nil }
        return albums.first { $0.id == id }
    }

    private var query: SearchQuery { SearchQuery(searchText) }

    /// What the detail column shows. Differs from `selection` while a
    /// search hides the selected album from the grid: then the search
    /// results take the detail, and the album returns with its tile.
    var detail: Selection? {
        guard case .album(let id) = selection else { return selection }
        if !query.isEmpty, !filteredAlbums.contains(where: { $0.id == id }) {
            return .allTracks
        }
        return selection
    }

    /// One line for the sidebar footer: what the last scan did, otherwise
    /// the size of the library.
    var statusText: String {
        if let notice {
            return notice
        }
        if isScanning {
            if let scanProgress, scanProgress.total > 0 {
                return "Scanning… \(scanProgress.read.formatted()) of \(scanProgress.total.formatted()) files"
            }
            return "Scanning…"
        }
        if let scan = lastScan {
            var parts: [String] = []
            if scan.added > 0 { parts.append("\(scan.added) added") }
            if scan.updated > 0 { parts.append("\(scan.updated) updated") }
            if scan.removed > 0 { parts.append("\(scan.removed) removed") }
            if scan.failed > 0 { parts.append("\(scan.failed) unreadable") }
            let change = parts.isEmpty ? "Nothing changed" : parts.joined(separator: ", ")
            return "\(change) · \(librarySize)"
        }
        return librarySize
    }

    private var librarySize: String {
        let tracks = tracks.count == 1 ? "1 track" : "\(tracks.count.formatted()) tracks"
        let folders = folders.count == 1 ? "1 folder" : "\(folders.count) folders"
        return "\(tracks) in \(folders)"
    }

    /// Albums for the grid, narrowed by the search field.
    var filteredAlbums: [Album] {
        let query = query
        return query.isEmpty ? albums : albums.filter(query.matches)
    }

    /// Every track in album order, narrowed by the search field.
    var filteredTracks: [Track] {
        let query = query
        let all = albums.flatMap(\.tracks)
        return query.isEmpty ? all : all.filter(query.matches)
    }

    // MARK: - Library

    private var startTask: Task<Void, Never>?

    /// Loads the saved library once; later calls just wait for that.
    func start() async {
        if startTask == nil {
            startTask = Task {
                do {
                    try await library.load()
                    await refresh()
                } catch {
                    lastError = "Could not load the library: \(error.localizedDescription)"
                }
                isLoaded = true
                opensAudioFiles = DefaultApp.opensAudioFiles
            }
        }
        await startTask?.value
    }

    // MARK: - Default app

    /// Crate > Use Crate for Audio Files: binds every audio type to this
    /// copy of the app. Reported in the footer, not in a dialog.
    func makeDefaultForAudioFiles() async {
        let failed = await DefaultApp.claimAudioFiles()
        opensAudioFiles = DefaultApp.opensAudioFiles
        if failed.isEmpty {
            notice = "Finder now opens audio files with Crate"
            lastError = nil
        } else {
            lastError = "Could not register Crate for: \(failed.joined(separator: ", "))"
        }
    }

    /// What Finder hands over (double-click, drop on the Dock icon, Open
    /// With). Folders are added to the library. A file that is in the
    /// library plays with its album queued from that track; files from
    /// elsewhere play as given, in Finder's order, without touching the
    /// library.
    func open(_ urls: [URL]) async {
        await start()
        var folders: [URL] = []
        var files: [URL] = []
        for url in urls {
            var isDirectory: ObjCBool = false
            if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory), isDirectory.boolValue {
                folders.append(url)
            } else {
                files.append(url)
            }
        }
        for folder in folders {
            await addFolder(folder)
        }
        guard !files.isEmpty else { return }

        if files.count == 1, let album = albums.first(where: { album in album.tracks.contains { $0.id == files[0].standardizedFileURL.path } }),
           let index = album.tracks.firstIndex(where: { $0.id == files[0].standardizedFileURL.path }) {
            play(album: album, startingAt: index)
            return
        }
        var tracks: [Track] = []
        for file in files {
            if let known = self.tracks.first(where: { $0.id == file.standardizedFileURL.path }) {
                tracks.append(known)
            } else if let probed = try? await library.probe(file) {
                tracks.append(probed)
            } else {
                lastError = "Cannot open \(file.lastPathComponent)"
            }
        }
        if !tracks.isEmpty {
            play(tracks: tracks)
        }
    }

    func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = "Add"
        panel.message = "Choose a folder with music. Subfolders are included."
        guard panel.runModal() == .OK, let url = panel.url else { return }
        Task { await addFolder(url) }
    }

    func addFolder(_ url: URL) async {
        await scanning { try await self.library.addFolder(url, progress: self.progress) }
    }

    /// Forgets the folder and its tracks. Nothing on disk is touched; the
    /// folder can be added again any time. Queued tracks from it go too,
    /// and if the one playing was among them, playback stops.
    func removeFolder(_ url: URL) async {
        do {
            try await library.removeFolder(url)
        } catch {
            lastError = "Could not remove the folder: \(error.localizedDescription)"
            return
        }
        lastScan = nil
        let wasCurrent = current
        queue.removeAll { Library.isUnder($0.url, url) }
        if let wasCurrent, current?.id != wasCurrent.id {
            stop()
            // The cursor already moved on; nothing is loaded until the user
            // starts something, so the bar should not claim a track either.
            queue.clear()
        } else {
            player.preload(queue.nextTrack?.url)
        }
        await refresh()
    }

    func rescan() async {
        // A rescan is the moment to look again at albums that had no
        // cover last time.
        await artworkCache.forgetMissing()
        await scanning { try await self.library.rescan(progress: self.progress) }
    }

    private var progress: Library.Progress {
        { read, total in Task { @MainActor [weak self] in self?.scanProgress = (read, total) } }
    }

    /// Files the last scan could not read, for the File menu.
    var unreadableFiles: [URL] { lastScan?.failedFiles ?? [] }

    /// Selects the files the last scan could not read in Finder, so the
    /// user can see what they are and fix or delete them.
    func showUnreadableFilesInFinder() {
        guard !unreadableFiles.isEmpty else { return }
        NSWorkspace.shared.activateFileViewerSelecting(unreadableFiles)
    }

    /// Cached per album; the first call for an album reads its files.
    func artwork(for album: Album) async -> CGImage? {
        await artworkCache.thumbnail(for: album)
    }

    private func scanning(_ work: () async throws -> Library.RescanSummary) async {
        guard !isScanning else { return }
        isScanning = true
        scanProgress = nil
        notice = nil
        defer {
            isScanning = false
            scanProgress = nil
        }
        do {
            lastScan = try await work()
            lastError = nil
        } catch {
            lastError = "Scan failed: \(error.localizedDescription)"
        }
        await refresh()
    }

    private func refresh() async {
        // Read everything first and publish it in one go: setting folders
        // before the hop for the tracks could show an empty grid. Grouping
        // runs off the main actor; with tens of thousands of tracks it
        // would stall the window on a slow Mac.
        let folders = await library.folders
        let tracks = await library.tracks
        self.albums = await Task.detached(priority: .userInitiated) {
            Album.group(tracks, roots: folders)
        }.value
        self.tracks = tracks
        self.folders = folders
        switch selection {
        case .allTracks:
            break
        case .album(let id) where albums.contains(where: { $0.id == id }):
            break
        default:
            selection = albums.first.map { .album($0.id) }
        }
    }

    // MARK: - Playback

    /// Queues the album and starts at the given track.
    func play(album: Album, startingAt index: Int = 0) {
        selection = .album(album.id)
        play(tracks: album.tracks, startingAt: index)
    }

    /// Queues the given tracks and starts at the given one.
    func play(tracks: [Track], startingAt index: Int = 0) {
        queue.replace(with: tracks, startingAt: index)
        loadCurrent(autoplay: true)
    }

    func togglePlayPause() {
        guard current != nil else { return }
        if isPlaying {
            player.pause()
            isPlaying = false
        } else {
            player.play()
            isPlaying = true
        }
        syncNowPlaying()
    }

    func next() {
        guard queue.advance() != nil else {
            stop()
            return
        }
        loadCurrent(autoplay: isPlaying)
    }

    func previous() {
        // Like every player: early in a track, go back a track; later,
        // restart the one playing.
        if currentTime > 3 {
            player.seek(to: 0)
            currentTime = 0
            syncNowPlaying()
            return
        }
        queue.goBack()
        loadCurrent(autoplay: isPlaying)
    }

    func seek(to time: TimeInterval) {
        player.seek(to: time)
        currentTime = time
        syncNowPlaying()
    }

    func setShuffle(_ on: Bool) {
        queue.setShuffle(on)
        player.preload(queue.nextTrack?.url)
    }

    func setRepeat(_ mode: PlayQueue.RepeatMode) {
        queue.repeatMode = mode
        player.preload(queue.nextTrack?.url)
    }

    var duration: TimeInterval {
        let reported = player.duration
        return reported > 0 ? reported : (current?.duration ?? 0)
    }

    private func loadCurrent(autoplay: Bool) {
        guard let track = current else {
            stop()
            return
        }
        do {
            try player.load(track.url)
            currentTime = 0
            lastError = nil
            loadArtwork(for: track)
            if autoplay {
                player.play()
                isPlaying = true
            }
            startTicking()
            syncNowPlaying()
            // Gapless: the backend chains the next file behind this one.
            player.preload(queue.nextTrack?.url)
        } catch {
            lastError = "Cannot play \(track.url.lastPathComponent): \(error.localizedDescription)"
            isPlaying = false
            // Skip the broken file rather than stall the queue.
            if queue.hasNext, queue.advance() != nil {
                loadCurrent(autoplay: autoplay)
            }
        }
    }

    private func loadArtwork(for track: Track) {
        artworkTask?.cancel()
        guard let album = albums.first(where: { album in album.tracks.contains { $0.id == track.id } }) else {
            currentArtwork = nil
            return
        }
        artworkTask = Task { [weak self] in
            guard let self else { return }
            let image = await artworkCache.thumbnail(for: album)
            if !Task.isCancelled, current?.id == track.id {
                currentArtwork = image
                syncNowPlaying()
            }
        }
    }

    private func trackFinished() {
        if queue.advance() != nil {
            loadCurrent(autoplay: true)
        } else {
            stop()
        }
    }

    private func stop() {
        player.stop()
        isPlaying = false
        currentTime = 0
        currentArtwork = nil
        ticker?.invalidate()
        ticker = nil
        syncNowPlaying()
    }

    private func syncNowPlaying() {
        nowPlaying?.update(
            track: current, artwork: currentArtwork, duration: duration, position: currentTime, isPlaying: isPlaying)
    }

    private func startTicking() {
        guard ticker == nil else { return }
        ticker = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    private func tick() {
        guard isPlaying else { return }
        currentTime = player.currentTime
    }
}
