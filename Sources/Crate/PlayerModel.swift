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
    @Published var searchText = "" {
        didSet { updateSearch() }
    }
    /// Albums for the grid, narrowed by the search field.
    @Published private(set) var filteredAlbums: [Album] = []
    /// Every track in album order, narrowed by the search field.
    @Published private(set) var filteredTracks: [Track] = []
    /// The tracks marked with a heart. Saved in a file of their own, so
    /// a tap on a heart never rewrites the library's index.
    @Published private(set) var favorites = Favorites()
    /// The favorites in album order, narrowed by the search field.
    @Published private(set) var favoriteTracks: [Track] = []
    /// What was played, newest first; its own small file, like the
    /// favorites.
    @Published private(set) var history = History()
    /// The newest files of the library, narrowed by the search field.
    @Published private(set) var recentlyAddedTracks: [Track] = []
    /// The history's tracks, narrowed by the search field.
    @Published private(set) var recentlyPlayedTracks: [Track] = []
    /// `Track.recentlyAdded` of the whole library, computed with the grid.
    private var recentlyAdded: [Track] = []

    /// What the detail column shows: one album, every track, the
    /// favorites, or one of the recent lists.
    enum Selection: Hashable {
        case album(Album.ID)
        case allTracks
        case favorites
        case recentlyAdded
        case recentlyPlayed
    }

    /// Whether the player bar shows the Up Next popover. Held here so the
    /// Playback menu can toggle it.
    @Published var showsUpNext = false
    /// The current track's lyrics, from an .lrc beside it or its tags;
    /// nil while there are none. Looked up when the track loads.
    @Published private(set) var lyrics: Lyrics?
    /// Whether the detail column shows the lyrics pane; it appears only
    /// while the track has lyrics.
    @Published var showsLyrics = false

    @Published var volume: Float = 1 {
        didSet { player.volume = volume }
    }

    private let library: Library
    private let player: any AudioPlayer
    private let artworkCache: ArtworkCache
    private let favoritesStore: any FavoritesStore
    private let historyStore: any HistoryStore
    private let playbackStore: any PlaybackStateStore
    private let loudnessAnalyzer: LoudnessAnalyzer
    private var analysisTask: Task<Void, Never>?
    /// The measured loudness of files without ReplayGain tags; grows
    /// while the analysis runs.
    private var loudness = Loudness()
    /// Files measured so far and files to measure, while the analysis
    /// runs; nil otherwise.
    @Published private(set) var analysisProgress: LoudnessAnalyzer.Progress?
    private let lyricsSource: any LyricsSource
    private var ticker: Timer?
    private var artworkTask: Task<Void, Never>?
    private var lyricsTask: Task<Void, Never>?
    private var searchIndex = SearchIndex(albums: [])
    private var searchTask: Task<Void, Never>?
    /// Set by `live()`; nil in tests and previews.
    var nowPlaying: NowPlaying?

    init(
        library: Library, player: any AudioPlayer, artwork: ArtworkCache, favorites: any FavoritesStore,
        playback: any PlaybackStateStore, history: any HistoryStore, loudness: any LoudnessStore,
        lyrics: any LyricsSource = FileLyricsSource()
    ) {
        self.library = library
        self.player = player
        self.artworkCache = artwork
        self.favoritesStore = favorites
        self.historyStore = history
        self.loudnessAnalyzer = LoudnessAnalyzer(store: loudness)
        self.playbackStore = playback
        self.lyricsSource = lyrics
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
        let favoritesURL = (try? JSONFavoritesStore.defaultFileURL(appName: "Crate"))
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("crate-favorites.json")
        let playbackDirectory = (try? JSONPlaybackStateStore.defaultDirectory(appName: "Crate"))
            ?? FileManager.default.temporaryDirectory
        let historyURL = (try? JSONHistoryStore.defaultFileURL(appName: "Crate"))
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("crate-history.json")
        let loudnessURL = (try? JSONLoudnessStore.defaultFileURL(appName: "Crate"))
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("crate-loudness.json")
        let model = PlayerModel(
            library: library, player: AVAudioEngineBackend(), artwork: artwork,
            favorites: JSONFavoritesStore(fileURL: favoritesURL),
            playback: JSONPlaybackStateStore(directory: playbackDirectory),
            history: JSONHistoryStore(fileURL: historyURL),
            loudness: JSONLoudnessStore(fileURL: loudnessURL))
        model.nowPlaying = NowPlaying(model: model)
        return model
    }

    var current: Track? { queue.current }

    var selectedAlbum: Album? {
        guard case .album(let id) = selection else { return nil }
        return albums.first { $0.id == id }
    }

    /// What the detail column shows. Differs from `selection` while a
    /// search hides the selected album from the grid: then the search
    /// results take the detail, and the album returns with its tile.
    var detail: Selection? {
        guard case .album(let id) = selection else { return selection }
        if !SearchQuery(searchText).isEmpty, !filteredAlbums.contains(where: { $0.id == id }) {
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
        if let analysisProgress {
            return "Measuring loudness… \(analysisProgress.done.formatted()) of \(analysisProgress.total.formatted()) files · \(librarySize)"
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

    /// Stored rather than computed: every published change redraws the
    /// views, the playback clock four times a second, and filtering a
    /// large library on each redraw stalled typing. A search runs off the
    /// main actor; the field shows what was typed right away.
    private func updateSearch() {
        searchTask?.cancel()
        let query = SearchQuery(searchText)
        let index = searchIndex
        if query.isEmpty {
            filteredAlbums = index.albums
            filteredTracks = index.tracks
            listsChanged()
            return
        }
        searchTask = Task {
            let result = await Task.detached(priority: .userInitiated) { index.search(query) }.value
            guard !Task.isCancelled else { return }
            filteredAlbums = result.albums
            filteredTracks = result.tracks
            listsChanged()
        }
    }

    /// The three flat lists follow the search: each is its own order
    /// narrowed to the tracks the search left.
    private func listsChanged() {
        favoriteTracks = favorites.filter(filteredTracks)
        if SearchQuery(searchText).isEmpty {
            recentlyAddedTracks = recentlyAdded
            recentlyPlayedTracks = history.tracks(from: tracks)
        } else {
            let shown = Set(filteredTracks.map(\.id))
            recentlyAddedTracks = recentlyAdded.filter { shown.contains($0.id) }
            recentlyPlayedTracks = history.tracks(from: filteredTracks)
        }
    }

    // MARK: - History

    /// The id the history was last told about, so a track counts once
    /// per start, not once per pause and play.
    private var recordedID: String?

    /// Writes the file right away, like the favorites: a few kilobytes.
    private func recordPlay(_ track: Track) {
        guard recordedID != track.id else { return }
        recordedID = track.id
        history.record(track)
        listsChanged()
        try? historyStore.save(history)
    }

    /// The artist view: the search field takes "artist:Name" and the
    /// grid shows that artist's albums. The search field is the whole
    /// mechanism, so clearing it is the way back.
    func showAlbums(ofArtist artist: String) {
        searchText = SearchQuery.text(forArtist: artist)
    }

    // MARK: - Favorites

    func isFavorite(_ track: Track) -> Bool { favorites.contains(track) }

    /// Whether every track of the album has a heart.
    func isFavorite(_ album: Album) -> Bool { favorites.containsAll(album.tracks) }

    func toggleFavorite(_ track: Track) {
        favorites.toggle(track)
        favoritesChanged()
    }

    /// Hearts the whole album, or takes the hearts off all of it.
    func setFavorite(_ on: Bool, album: Album) {
        favorites.set(on, for: album.tracks)
        favoritesChanged()
    }

    /// The Playback menu's item, for the track that is playing.
    func toggleCurrentFavorite() {
        guard let current else { return }
        toggleFavorite(current)
    }

    /// Writes the file right away, on the main actor: it is a few
    /// kilobytes in Application Support, and writing it in order is what
    /// matters after two quick taps.
    private func favoritesChanged() {
        listsChanged()
        do {
            try favoritesStore.save(favorites)
        } catch {
            lastError = "Could not save the favorites: \(error.localizedDescription)"
        }
    }

    // MARK: - Library

    private var startTask: Task<Void, Never>?

    /// Loads the saved library once; later calls just wait for that.
    func start() async {
        if startTask == nil {
            startTask = Task {
                do {
                    try await library.load()
                    favorites = try favoritesStore.load() ?? Favorites()
                    history = try historyStore.load() ?? History()
                    await refresh()
                } catch {
                    lastError = "Could not load the library: \(error.localizedDescription)"
                }
                isLoaded = true
                opensAudioFiles = DefaultApp.opensAudioFiles
                loudness = await loudnessAnalyzer.load()
                restorePlayback()
                // An index from an older version lacks tags this one
                // reads: re-read the files once, with the grid already up
                // and the footer showing the progress.
                if await library.needsRescan {
                    await rescan()
                } else {
                    startAnalysis()
                }
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
        favorites.removeAll(under: url)
        favoritesChanged()
        history.removeAll(under: url)
        try? historyStore.save(history)
        analysisTask?.cancel()
        await loudnessAnalyzer.removeAll(under: url)
        loudness = await loudnessAnalyzer.load()
        let wasCurrent = current
        queue.removeAll { Library.isUnder($0.url, url) }
        if let wasCurrent, current?.id != wasCurrent.id {
            stop()
            // The cursor already moved on; nothing is loaded until the user
            // starts something, so the bar should not claim a track either.
            queue.clear()
        } else {
            preloadNext()
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
        { [weak self] read, total in Task { @MainActor in self?.scanProgress = (read, total) } }
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
        // The analysis reads the same files: out of the way until the
        // scan is done, then on with whatever is new.
        analysisTask?.cancel()
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
        startAnalysis()
    }

    // MARK: - Loudness analysis

    /// Measures files without ReplayGain tags in the background, lowest
    /// priority, a few at a time; results arrive every few files and the
    /// footer counts along. Cancelled by a scan or a folder removal and
    /// started again after, picking up where it was.
    private func startAnalysis() {
        analysisTask?.cancel()
        let tracks = self.tracks
        let analyzer = loudnessAnalyzer
        // Built here, where self is strong, so the closure owns its own
        // weak reference instead of capturing the task's.
        let report: @Sendable (LoudnessAnalyzer.Progress?, Loudness?) -> Void = { [weak self] progress, snapshot in
            Task { @MainActor in
                guard let self else { return }
                self.analysisProgress = progress
                if let snapshot { self.loudness = snapshot }
            }
        }
        analysisTask = Task(priority: .utility) {
            let pending = await analyzer.pending(in: tracks).count
            guard pending > 0 else { return }
            report(LoudnessAnalyzer.Progress(done: 0, total: pending), nil)
            let result = await analyzer.run(tracks: tracks) { progress, snapshot in report(progress, snapshot) }
            report(nil, result)
        }
    }

    private func refresh() async {
        // Read everything first and publish it in one go: setting folders
        // before the hop for the tracks could show an empty grid. Grouping
        // runs off the main actor; with tens of thousands of tracks it
        // would stall the window on a slow Mac.
        let folders = await library.folders
        let tracks = await library.tracks
        let (albums, index, recent) = await Task.detached(priority: .userInitiated) {
            let albums = Album.group(tracks, roots: folders)
            let index = SearchIndex(albums: albums)
            return (albums, index, Track.recentlyAdded(index.tracks))
        }.value
        self.albums = albums
        searchIndex = index
        recentlyAdded = recent
        self.tracks = tracks
        self.folders = folders
        updateSearch()
        switch selection {
        case .allTracks, .favorites, .recentlyAdded, .recentlyPlayed:
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
        play(tracks: album.tracks, startingAt: index, asAlbum: true)
    }

    /// Queues the given tracks and starts at the given one. `asAlbum`
    /// picks the album gain over the track gain, as ReplayGain intends
    /// for listening to a record as a whole.
    func play(tracks: [Track], startingAt index: Int = 0, asAlbum: Bool = false) {
        queue.replace(with: tracks, startingAt: index)
        queueIsAlbum = asAlbum
        queueAlbumMean = asAlbum ? ReplayGain.albumMean(tracks) : nil
        queueAlbumGain = asAlbum ? loudness.albumGain(for: tracks) : nil
        loadCurrent(autoplay: true)
    }

    private var queueIsAlbum = false
    /// The mean of the queued album's track gains, for files that have
    /// only those (Sound Check); nil otherwise.
    private var queueAlbumMean: Double?
    /// The measured album gain of the queued album, when every track of
    /// it is measured; nil otherwise.
    private var queueAlbumGain: Double?

    // MARK: - Up Next

    /// What plays after the current track, in playback order.
    var upcoming: [PlayQueue.Entry] { queue.upcoming }

    /// Queues the tracks right after the one playing. With nothing
    /// playing, they start right away.
    func playNext(_ tracks: [Track]) {
        guard !tracks.isEmpty else { return }
        let wasEmpty = queue.isEmpty
        queue.playNext(tracks)
        queueGrew(from: wasEmpty)
    }

    /// Queues the tracks at the end. With nothing playing, they start
    /// right away.
    func playLater(_ tracks: [Track]) {
        guard !tracks.isEmpty else { return }
        let wasEmpty = queue.isEmpty
        queue.append(tracks)
        queueGrew(from: wasEmpty)
    }

    private func queueGrew(from wasEmpty: Bool) {
        if wasEmpty {
            // Tracks from anywhere: each is levelled on its own.
            queueIsAlbum = false
            queueAlbumMean = nil
            loadCurrent(autoplay: true)
        } else {
            preloadNext()
        }
    }

    /// Plays the queued entry now. Entries between the one playing and
    /// it are skipped, as with Next.
    func play(entry: PlayQueue.Entry) {
        guard queue.jump(to: entry.id) != nil else { return }
        loadCurrent(autoplay: true)
    }

    /// Takes an upcoming entry out of the queue. The current entry is
    /// never in `upcoming`, so playback is not affected.
    func remove(entry: PlayQueue.Entry) {
        queue.remove(entry.id)
        preloadNext()
    }

    /// Empties the queue after the current track.
    func clearUpNext() {
        for entry in queue.upcoming {
            queue.remove(entry.id)
        }
        preloadNext()
    }

    /// The loudness correction to play the track with, in dB: the file's
    /// ReplayGain tags when it has them, else the measurement, else its
    /// Sound Check value, else nothing.
    func gain(for track: Track) -> Double {
        if track.albumGain == nil, let measured = loudness.measurement(for: track) {
            if queueIsAlbum, let queueAlbumGain { return queueAlbumGain }
            return measured.gain ?? 0
        }
        return ReplayGain.playbackGain(track, asAlbum: queueIsAlbum, albumMean: queueAlbumMean)
    }

    private func preloadNext() {
        let next = queue.nextTrack
        player.preload(next?.url, gain: next.map(gain) ?? 0)
    }

    func togglePlayPause() {
        guard current != nil else { return }
        if isPlaying {
            player.pause()
            isPlaying = false
            savePlayback()
        } else {
            player.play()
            isPlaying = true
            if let current { recordPlay(current) }
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
        savePlayback()
    }

    func setShuffle(_ on: Bool) {
        queue.setShuffle(on)
        preloadNext()
        savePlayback()
    }

    func setRepeat(_ mode: PlayQueue.RepeatMode) {
        queue.repeatMode = mode
        preloadNext()
        savePlayback()
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
            try player.load(track.url, gain: gain(for: track))
            currentTime = 0
            lastError = nil
            loadArtwork(for: track)
            loadLyrics(for: track)
            if autoplay {
                player.play()
                isPlaying = true
                recordPlay(track)
            }
            startTicking()
            syncNowPlaying()
            // Gapless: the backend chains the next file behind this one.
            preloadNext()
            savePlayback()
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

    /// Off the main actor: a sidecar is one small read, tags may open
    /// the file. The pane keeps its toggle; it just has nothing to show
    /// for a track without lyrics.
    private func loadLyrics(for track: Track) {
        lyricsTask?.cancel()
        lyrics = nil
        let source = lyricsSource
        lyricsTask = Task { [weak self] in
            let found = await Task.detached(priority: .userInitiated) { await source.lyrics(for: track) }.value
            guard let self, !Task.isCancelled, current?.id == track.id else { return }
            lyrics = found
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
        lyricsTask?.cancel()
        lyrics = nil
        ticker?.invalidate()
        ticker = nil
        syncNowPlaying()
        savePlayback()
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
        if Date().timeIntervalSince(lastSave) >= 10 {
            savePlayback()
        }
    }

    // MARK: - Resume

    private var lastSave = Date.distantPast

    /// Writes down the queue, the cursor and the position, so the next
    /// launch comes up paused right here. Called on every change of
    /// track or mode, on pause, seek and quit, and every ten seconds of
    /// playback; the store rewrites the queue only when it changed.
    func savePlayback() {
        lastSave = Date()
        do {
            guard let currentEntry = queue.currentEntry,
                  let index = queue.entries.firstIndex(where: { $0.id == currentEntry.id }) else {
                try playbackStore.clear()
                return
            }
            try playbackStore.save(PlaybackState(
                trackIDs: queue.entries.map(\.track.id), asAlbum: queueIsAlbum, currentIndex: index,
                position: currentTime, isShuffled: queue.isShuffled, repeatMode: queue.repeatMode))
        } catch {
            // Nothing the user can act on; the next launch starts fresh.
        }
    }

    /// Rebuilds the queue the last run left behind, loads its current
    /// track at the saved position and stays paused. Tracks that are no
    /// longer in the library are skipped; if the current one is gone,
    /// nothing is restored.
    private func restorePlayback() {
        guard current == nil, let state = try? playbackStore.load() else { return }
        let byID = Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        guard let currentTrack = byID[state.trackIDs[state.currentIndex]] else { return }
        var restored: [Track] = []
        var index = 0
        for (offset, id) in state.trackIDs.enumerated() {
            guard let track = byID[id] else { continue }
            if offset == state.currentIndex { index = restored.count }
            restored.append(track)
        }
        queue.replace(with: restored, startingAt: index)
        queue.repeatMode = state.repeatMode
        if state.isShuffled { queue.setShuffle(true) }
        queueIsAlbum = state.asAlbum
        queueAlbumMean = state.asAlbum ? ReplayGain.albumMean(restored) : nil
        queueAlbumGain = state.asAlbum ? loudness.albumGain(for: restored) : nil
        do {
            try player.load(currentTrack.url, gain: gain(for: currentTrack))
        } catch {
            queue.clear()
            return
        }
        let position = min(state.position, currentTrack.duration)
        if position > 0 {
            player.seek(to: position)
        }
        currentTime = position
        loadArtwork(for: currentTrack)
        loadLyrics(for: currentTrack)
        syncNowPlaying()
        preloadNext()
    }
}
