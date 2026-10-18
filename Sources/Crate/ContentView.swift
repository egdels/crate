// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import CrateCore
import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: PlayerModel

    /// The navigation view is the window's root from the first frame on,
    /// with the empty state inside it rather than in its place: one that
    /// appears later (library already loaded when the window came up, as
    /// when Finder launches the app with a file) comes without its
    /// toolbar on macOS 26. NavigationView rather than NavigationSplitView
    /// because the app runs on macOS 12.
    var body: some View {
        VStack(spacing: 0) {
            NavigationView {
                Group {
                    if !model.isLoaded {
                        Color.clear
                    } else if model.folders.isEmpty {
                        Text("No music folder yet")
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    } else {
                        AlbumGrid()
                    }
                }
                .frame(minWidth: 380, idealWidth: 420)
                // The grid is the app's main view, not a sidebar list: the
                // same background as the track list, instead of the grey
                // sidebar material NavigationView gives its first column
                // (which also ended a few points short of the column under
                // the toolbar).
                .background(Color(nsColor: .textBackgroundColor))
                HStack(spacing: 0) {
                    Group {
                        if !model.isLoaded {
                            Color.clear
                        } else if model.folders.isEmpty {
                            EmptyLibraryView()
                        } else if model.detail == .allTracks {
                            FlatTrackList(
                                tracks: model.filteredTracks,
                                title: model.searchText.isEmpty ? "All Tracks" : "Search")
                        } else if model.detail == .favorites {
                            FlatTrackList(
                                tracks: model.favoriteTracks, title: "Favorites",
                                emptyText: model.favorites.isEmpty
                                    ? "No favorites yet. Click the heart on a track."
                                    : "No favorites match the search.")
                        } else if model.detail == .recentlyAdded {
                            FlatTrackList(
                                tracks: model.recentlyAddedTracks, title: "Recently Added",
                                emptyText: "Nothing matches the search.")
                        } else if model.detail == .recentlyPlayed {
                            FlatTrackList(
                                tracks: model.recentlyPlayedTracks, title: "Recently Played",
                                emptyText: model.history.isEmpty
                                    ? "Nothing played yet."
                                    : "Nothing played matches the search.")
                        } else {
                            TrackList()
                        }
                    }
                    // The lyrics pane sits beside whatever the detail shows,
                    // only while the track playing has lyrics.
                    if model.showsLyrics, let lyrics = model.lyrics {
                        Divider()
                        LyricsView(lyrics: lyrics)
                            .frame(width: 320)
                    }
                }
                .background(Color(nsColor: .textBackgroundColor))
            }
            // Toolbar items live on the navigation view, not on the
            // columns: items a column registers while its content is still
            // changing (play starts right after launch) get dropped.
            .toolbar {
                Button { playSelection() } label: { Label("Play", systemImage: "play.fill") }
                    .disabled(model.folders.isEmpty)
                    .help("Play the selected album or list")
            }
            // Toolbar, not sidebar: with `.sidebar` placement the field never
            // appeared above the ScrollView-based grid (macOS 26).
            .searchable(text: $model.searchText, placement: .toolbar, prompt: "Search")
            .modifier(ClearToolbar())
            .background(TitlebarStyle())
            Divider()
            PlayerBar()
        }
        // A failure shows as a red line over the window's top until it is
        // clicked away or eight seconds pass; some failures have no later
        // success that would clear them, and the line covers the toolbar.
        .overlay(alignment: .top) {
            if let error = model.lastError {
                Text(error)
                    .font(.callout)
                    .padding(8)
                    .frame(maxWidth: .infinity)
                    // only behind the line itself: a plain background reaches
                    // up under the toolbar and reddens the title bar
                    // opaque: through a translucent red the column divider
                    // and whatever lies below showed
                    .background(Color.red, ignoresSafeAreaEdges: [])
                    .foregroundStyle(.white)
                    .contentShape(Rectangle())
                    .onTapGesture { model.lastError = nil }
                    .help("Click to dismiss")
                    .task(id: error) {
                        try? await Task.sleep(nanoseconds: 8_000_000_000)
                        if model.lastError == error { model.lastError = nil }
                    }
            }
        }
    }

    private func playSelection() {
        if model.detail == .allTracks {
            model.play(tracks: model.filteredTracks)
        } else if model.detail == .favorites {
            model.play(tracks: model.favoriteTracks)
        } else if model.detail == .recentlyAdded {
            model.play(tracks: model.recentlyAddedTracks)
        } else if model.detail == .recentlyPlayed {
            model.play(tracks: model.recentlyPlayedTracks)
        } else if let album = model.selectedAlbum {
            model.play(album: album)
        }
    }
}

struct EmptyLibraryView: View {
    @EnvironmentObject private var model: PlayerModel

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "music.note.list")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text("Pick a folder with music.")
                .font(.title2)
            Text("Crate reads the files where they are. Nothing is copied or uploaded.")
                .foregroundStyle(.secondary)
            Button("Choose Folder…") { model.chooseFolder() }
                .keyboardShortcut(.defaultAction)
                .disabled(model.isScanning)
            if model.isScanning {
                ProgressView().controlSize(.small)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct AlbumGrid: View {
    @EnvironmentObject private var model: PlayerModel

    private let columns = [GridItem(.adaptive(minimum: 150, maximum: 200), spacing: 16)]

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
            ScrollView {
                LazyVGrid(columns: columns, spacing: 16) {
                    // Double tap first: a single-tap modifier applied before
                    // it would claim every click and the double tap never fires.
                    LibraryTile(
                        title: "All Tracks", count: model.filteredTracks.count, systemImage: "music.note.list",
                        selected: model.selection == .allTracks)
                        .onTapGesture(count: 2) { model.play(tracks: model.filteredTracks) }
                        .onTapGesture { model.selection = .allTracks }
                    LibraryTile(
                        title: "Favorites", count: model.favoriteTracks.count, systemImage: "heart",
                        selected: model.selection == .favorites)
                        .onTapGesture(count: 2) { model.play(tracks: model.favoriteTracks) }
                        .onTapGesture { model.selection = .favorites }
                    LibraryTile(
                        title: "Recently Added", count: model.recentlyAddedTracks.count, systemImage: "sparkles",
                        selected: model.selection == .recentlyAdded)
                        .onTapGesture(count: 2) { model.play(tracks: model.recentlyAddedTracks) }
                        .onTapGesture { model.selection = .recentlyAdded }
                    LibraryTile(
                        title: "Recently Played", count: model.recentlyPlayedTracks.count, systemImage: "clock.arrow.circlepath",
                        selected: model.selection == .recentlyPlayed)
                        .onTapGesture(count: 2) { model.play(tracks: model.recentlyPlayedTracks) }
                        .onTapGesture { model.selection = .recentlyPlayed }
                    ForEach(model.filteredAlbums) { album in
                        AlbumTile(album: album, selected: model.selection == .album(album.id))
                            .onTapGesture(count: 2) { model.play(album: album) }
                            .onTapGesture { model.selection = .album(album.id) }
                            .albumContextMenu(album)
                            .id(album.id)
                    }
                }
                .padding(16)
            }
            // A selection made elsewhere (a file opened from Finder, the
            // saved library on launch) is brought into view; clicks are
            // already there. The first scroll waits a beat: during the
            // first layout pass it does nothing.
            .onChange(of: model.selection) { selection in
                scroll(proxy, to: selection)
            }
            .onAppear {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { scroll(proxy, to: model.selection) }
            }
            }
            Divider()
            // Scan feedback lives here, not in a dialog: what the last scan
            // did, or simply how big the library is.
            HStack(spacing: 6) {
                if model.isScanning {
                    ProgressView().controlSize(.mini)
                }
                Text(model.statusText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Albums")
    }

    private func scroll(_ proxy: ScrollViewProxy, to selection: PlayerModel.Selection?) {
        if case .album(let id) = selection {
            withAnimation { proxy.scrollTo(id, anchor: .center) }
        }
    }
}

struct AlbumTile: View {
    @EnvironmentObject private var model: PlayerModel
    let album: Album
    let selected: Bool
    @State private var image: CGImage?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ArtworkView(image: image, placeholder: initials, cornerRadius: 8)
                .task(id: album.id) { image = await model.artwork(for: album) }
                .overlay(alignment: .bottomTrailing) {
                    if model.isFavorite(album) {
                        Image(systemName: "heart.fill")
                            .font(.system(size: 14))
                            .foregroundStyle(.white)
                            .shadow(color: .black.opacity(0.6), radius: 2)
                            .padding(6)
                    }
                }
            Text(album.title)
                .font(.headline)
                .lineLimit(1)
            Text(album.artist)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .padding(8)
        .background(selected ? Color.accentColor.opacity(0.15) : .clear, in: RoundedRectangle(cornerRadius: 10))
        .contentShape(Rectangle())
    }

    private var initials: String {
        String(album.title.split(separator: " ").prefix(2).compactMap(\.first)).uppercased()
    }
}

/// The first tiles of the grid: the whole library, the favorites, the
/// newest files and what was played last, each as one flat list.
struct LibraryTile: View {
    let title: String
    let count: Int
    let systemImage: String
    let selected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RoundedRectangle(cornerRadius: 8)
                .fill(.quaternary)
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    Image(systemName: systemImage)
                        .font(.system(size: 44, weight: .regular))
                        .foregroundStyle(.secondary)
                }
            Text(title)
                .font(.headline)
                .lineLimit(1)
            Text(count == 1 ? "1 track" : "\(count) tracks")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .padding(8)
        .background(selected ? Color.accentColor.opacity(0.15) : .clear, in: RoundedRectangle(cornerRadius: 10))
        .contentShape(Rectangle())
    }
}

/// Tracks as one flat list with title, artist, album and time: every
/// track, or the favorites; the search field narrows both. Double-click
/// plays from that row through the end of the list. A List, not a Table:
/// Table has no double-click action before macOS 13.
struct FlatTrackList: View {
    @EnvironmentObject private var model: PlayerModel
    let tracks: [Track]
    let title: String
    var emptyText: String? = nil

    var body: some View {
        Group {
            if tracks.isEmpty, let emptyText {
                Text(emptyText)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                        FlatTrackRow(track: track, playing: track.id == model.current?.id)
                            .contentShape(Rectangle())
                            .onTapGesture(count: 2) { model.play(tracks: tracks, startingAt: index) }
                            .trackContextMenu(track)
                    }
                }
                .listStyle(.inset)
                .modifier(ContentBackgroundList())
            }
        }
        .navigationTitle(title)
    }
}

struct FlatTrackRow: View {
    @EnvironmentObject private var model: PlayerModel
    let track: Track
    let playing: Bool
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if playing {
                    Image(systemName: "speaker.wave.2.fill").foregroundStyle(Color.accentColor)
                }
            }
            .frame(width: 16)
            // Three equal columns that follow the window width.
            Text(track.title).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
            Text(track.artist ?? "").lineLimit(1).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(track.album ?? "").lineLimit(1).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
            HeartButton(isOn: model.isFavorite(track), visible: hovering) { model.toggleFavorite(track) }
            Text(timeText(track.duration)).monospacedDigit().foregroundStyle(.secondary)
                .frame(width: 44, alignment: .trailing)
        }
        .padding(.vertical, 2)
        .onHover { hovering = $0 }
    }
}

/// The heart on a row or in the player bar. A filled heart is always
/// there; an empty one only while the pointer is over the row, so a
/// list does not show a column of grey hearts.
struct HeartButton: View {
    let isOn: Bool
    var visible = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: isOn ? "heart.fill" : "heart")
                .foregroundStyle(isOn ? Color.accentColor : Color.secondary)
        }
        .buttonStyle(.borderless)
        .opacity(isOn || visible ? 1 : 0)
        .help(isOn ? "Remove from Favorites" : "Add to Favorites")
    }
}

extension View {
    /// Right-click on a row: queue the track, or the same as the heart.
    func trackContextMenu(_ track: Track) -> some View {
        modifier(TrackContextMenu(track: track))
    }

    /// Right-click on a tile: play or queue the album, or heart it.
    func albumContextMenu(_ album: Album) -> some View {
        modifier(AlbumContextMenu(album: album))
    }
}

private struct TrackContextMenu: ViewModifier {
    @EnvironmentObject private var model: PlayerModel
    let track: Track

    func body(content: Content) -> some View {
        content.contextMenu {
            Button("Play Next") { model.playNext([track]) }
            Button("Play Last") { model.playLater([track]) }
            Divider()
            Button(model.isFavorite(track) ? "Remove from Favorites" : "Add to Favorites") {
                model.toggleFavorite(track)
            }
            Divider()
            if let artist = track.displayArtist {
                Button("Show Albums by \(artist)") { model.showAlbums(ofArtist: artist) }
            }
            Button("Show in Finder") { model.showInFinder(track) }
        }
    }
}

private struct AlbumContextMenu: ViewModifier {
    @EnvironmentObject private var model: PlayerModel
    let album: Album

    func body(content: Content) -> some View {
        content.contextMenu {
            Button("Play") { model.play(album: album) }
            Button("Play Next") { model.playNext(album.tracks) }
            Button("Play Last") { model.playLater(album.tracks) }
            Divider()
            Button(model.isFavorite(album) ? "Remove from Favorites" : "Add to Favorites") {
                model.setFavorite(!model.isFavorite(album), album: album)
            }
            Divider()
            Button("Show in Finder") { model.showInFinder(album: album) }
        }
    }
}

/// The words of the track playing. Timed lyrics follow the playback:
/// the current line is bold and kept in view, the rest grey; plain
/// lyrics are just the text.
struct LyricsView: View {
    @EnvironmentObject private var model: PlayerModel
    let lyrics: Lyrics

    var body: some View {
        let current = lyrics.index(at: model.currentTime)
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if let track = model.current {
                        Text(track.title).font(.headline)
                        Text(track.artist ?? "").font(.subheadline).foregroundStyle(.secondary)
                        Divider().padding(.vertical, 4)
                    }
                    ForEach(Array(lyrics.lines.enumerated()), id: \.offset) { index, line in
                        Text(line.text.isEmpty ? " " : line.text)
                            .font(lyrics.isTimed ? .title3 : .body)
                            .fontWeight(index == current ? .semibold : .regular)
                            .foregroundStyle(lyrics.isTimed && index != current ? Color.secondary : Color.primary)
                            .fixedSize(horizontal: false, vertical: true)
                            .id(index)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
            }
            .onChange(of: current) { index in
                if let index { withAnimation { proxy.scrollTo(index, anchor: .center) } }
            }
        }
    }
}

/// Square cover, or a grey tile with the given text while there is none.
struct ArtworkView: View {
    let image: CGImage?
    var placeholder: String = ""
    var cornerRadius: CGFloat = 8

    var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius)
            .fill(.quaternary)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                } else {
                    Text(placeholder)
                        .font(.system(size: 36, weight: .semibold, design: .rounded))
                        .foregroundStyle(.secondary)
                        .minimumScaleFactor(0.3)
                        .padding(4)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}

struct TrackList: View {
    @EnvironmentObject private var model: PlayerModel
    @State private var image: CGImage?

    var body: some View {
        if let album = model.selectedAlbum {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 16) {
                    ArtworkView(image: image, cornerRadius: 6)
                        .frame(width: 96, height: 96)
                        .task(id: album.id) {
                            image = nil
                            image = await model.artwork(for: album)
                        }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(album.title).font(.title2.bold())
                        HStack(spacing: 0) {
                            // The artist view: the name fills the search
                            // field with "artist:" and the grid narrows to
                            // that artist's albums.
                            if album.artist == Album.variousArtists {
                                Text(album.artist)
                            } else {
                                Button(album.artist) { model.showAlbums(ofArtist: album.artist) }
                                    .buttonStyle(.plain)
                                    .help("Show this artist's albums")
                            }
                            let rest = [album.year.map(String.init), album.genre].compactMap { $0 }
                            if !rest.isEmpty {
                                Text(" · " + rest.joined(separator: " · "))
                            }
                        }
                        .foregroundStyle(.secondary)
                        Text(summary(album))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    // Hearts the whole album; a filled heart means every
                    // track has one.
                    HeartButton(isOn: model.isFavorite(album)) {
                        model.setFavorite(!model.isFavorite(album), album: album)
                    }
                    .font(.title2)
                }
                .padding(16)
                // The header and the list share one background; the line
                // keeps rows that scroll up from running into the header.
                Divider()
                // One section per disc; the headers only appear when the
                // album has more than one.
                let discs = album.discs
                List {
                    // Sections only for more than one disc: macOS 12 draws
                    // an empty section header as a blank row with a line.
                    if discs.count > 1 {
                        ForEach(discs, id: \.number) { disc in
                            Section {
                                rows(disc.tracks, of: album)
                            } header: {
                                Text("Disc \(disc.number)")
                            }
                        }
                    } else {
                        rows(album.tracks, of: album)
                    }
                }
                .listStyle(.inset)
                .modifier(ContentBackgroundList())
            }
        } else {
            Text("Select an album")
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func rows(_ tracks: [Track], of album: Album) -> some View {
        ForEach(tracks, id: \.id) { track in
            TrackRow(track: track, playing: track.id == model.current?.id)
                .contentShape(Rectangle())
                .onTapGesture(count: 2) {
                    model.play(album: album, startingAt: album.tracks.firstIndex { $0.id == track.id } ?? 0)
                }
                .trackContextMenu(track)
        }
    }

    private func summary(_ album: Album) -> String {
        let count = album.tracks.count == 1 ? "1 track" : "\(album.tracks.count) tracks"
        return "\(count) · \(lengthText(album.duration))"
    }
}

struct TrackRow: View {
    @EnvironmentObject private var model: PlayerModel
    let track: Track
    let playing: Bool
    @State private var hovering = false

    var body: some View {
        HStack {
            Group {
                if playing {
                    Image(systemName: "speaker.wave.2.fill").foregroundStyle(Color.accentColor)
                } else {
                    Text(track.trackNumber.map(String.init) ?? "").foregroundStyle(.secondary)
                }
            }
            .frame(width: 28, alignment: .trailing)
            VStack(alignment: .leading) {
                Text(track.title).lineLimit(1)
                if let artist = track.artist, artist != track.displayArtist {
                    Text(artist).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            HeartButton(isOn: model.isFavorite(track), visible: hovering) { model.toggleFavorite(track) }
            Text(timeText(track.duration))
                .monospacedDigit()
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
        .onHover { hovering = $0 }
    }
}

/// The toolbar over the detail column is drawn with a tinted material of
/// its own, grey next to the white grid. Painted in the content colour
/// instead, it matches the grid's half; it must stay opaque, because the
/// column divider runs the window's full height and only the toolbar
/// covers its top (hidden, the line showed through the title bar). Needs
/// macOS 13; on 12 it stays tinted.
private struct ClearToolbar: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 13, *) {
            content
                .toolbarBackground(Color(nsColor: .textBackgroundColor), for: .windowToolbar)
                .toolbarBackground(.visible, for: .windowToolbar)
        } else {
            content
        }
    }
}

/// In full screen AppKit moves the toolbar into a window of its own,
/// which ignores `toolbarBackground` and drew it grey again; a transparent
/// title bar over a window background in the content colour keeps it
/// white there too. Set once the view is in its window.
private struct TitlebarStyle: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { Probe() }
    func updateNSView(_ nsView: NSView, context: Context) {}
    final class Probe: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            guard let window else { return }
            window.titlebarAppearsTransparent = true
            window.backgroundColor = .textBackgroundColor
        }
    }
}

/// An inset list paints its own background, which in dark mode is a
/// slightly blue grey beside the neutral content colour of the grid and
/// the album header; without it the column's background shows. Needs
/// macOS 13 (scrollContentBackground); on 12 the list keeps its own.
private struct ContentBackgroundList: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 13, *) {
            content.scrollContentBackground(.hidden)
        } else {
            content
        }
    }
}
