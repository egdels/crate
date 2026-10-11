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
                if !model.isLoaded {
                    Color.clear
                } else if model.folders.isEmpty {
                    EmptyLibraryView()
                } else if model.detail == .allTracks {
                    AllTracksView()
                } else {
                    TrackList()
                }
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
            Divider()
            PlayerBar()
        }
        .overlay(alignment: .top) {
            if let error = model.lastError {
                Text(error)
                    .font(.callout)
                    .padding(8)
                    .frame(maxWidth: .infinity)
                    .background(.red.opacity(0.85))
                    .foregroundStyle(.white)
            }
        }
    }

    private func playSelection() {
        if model.detail == .allTracks {
            model.play(tracks: model.filteredTracks)
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
                    AllTracksTile(count: model.filteredTracks.count, selected: model.selection == .allTracks)
                        .onTapGesture(count: 2) { model.play(tracks: model.filteredTracks) }
                        .onTapGesture { model.selection = .allTracks }
                    ForEach(model.filteredAlbums) { album in
                        AlbumTile(album: album, selected: model.selection == .album(album.id))
                            .onTapGesture(count: 2) { model.play(album: album) }
                            .onTapGesture { model.selection = .album(album.id) }
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

/// The first tile of the grid: the whole library as one flat list.
struct AllTracksTile: View {
    let count: Int
    let selected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RoundedRectangle(cornerRadius: 8)
                .fill(.quaternary)
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    Image(systemName: "music.note.list")
                        .font(.system(size: 44, weight: .regular))
                        .foregroundStyle(.secondary)
                }
            Text("All Tracks")
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

/// Every track as one flat list with title, artist, album and time; the
/// search field narrows it. Double-click plays from that row through the
/// end of the list. A List, not a Table: Table has no double-click
/// action before macOS 13.
struct AllTracksView: View {
    @EnvironmentObject private var model: PlayerModel

    var body: some View {
        let tracks = model.filteredTracks
        List {
            ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                HStack(spacing: 12) {
                    Group {
                        if track.id == model.current?.id {
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
                    Text(timeText(track.duration)).monospacedDigit().foregroundStyle(.secondary)
                        .frame(width: 44, alignment: .trailing)
                }
                .padding(.vertical, 2)
                .contentShape(Rectangle())
                .onTapGesture(count: 2) { model.play(tracks: tracks, startingAt: index) }
            }
        }
        .listStyle(.inset)
        .navigationTitle(model.searchText.isEmpty ? "All Tracks" : "Search")
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
                        Text([album.artist, album.year.map(String.init)].compactMap { $0 }.joined(separator: " · "))
                            .foregroundStyle(.secondary)
                        Text(summary(album))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(16)
                List {
                    ForEach(Array(album.tracks.enumerated()), id: \.element.id) { index, track in
                        TrackRow(track: track, playing: track.id == model.current?.id)
                            .contentShape(Rectangle())
                            .onTapGesture(count: 2) { model.play(album: album, startingAt: index) }
                    }
                }
                .listStyle(.inset)
            }
        } else {
            Text("Select an album")
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func summary(_ album: Album) -> String {
        let count = album.tracks.count == 1 ? "1 track" : "\(album.tracks.count) tracks"
        return "\(count) · \(lengthText(album.duration))"
    }
}

struct TrackRow: View {
    let track: Track
    let playing: Bool

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
            Text(timeText(track.duration))
                .monospacedDigit()
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}
