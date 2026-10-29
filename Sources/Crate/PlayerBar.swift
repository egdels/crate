// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import CrateCore
import SwiftUI

struct PlayerBar: View {
    @EnvironmentObject private var model: PlayerModel

    var body: some View {
        HStack(spacing: 16) {
            HStack(spacing: 8) {
                Button { model.previous() } label: { Image(systemName: "backward.fill") }
                // Audiobooks and long tracks: the jumps around Play.
                if model.offersSkips {
                    Button { model.skip(by: -Skip.back) } label: { Image(systemName: "gobackward.15") }
                        .help("Back 15 Seconds (⌥⌘←)")
                }
                Button { model.togglePlayPause() } label: {
                    Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title2)
                        .frame(width: 28)
                }
                if model.offersSkips {
                    Button { model.skip(by: Skip.forward) } label: { Image(systemName: "goforward.30") }
                        .help("Forward 30 Seconds (⌥⌘→)")
                }
                Button { model.next() } label: { Image(systemName: "forward.fill") }
            }
            .buttonStyle(.borderless)
            .disabled(model.current == nil)

            if model.current != nil {
                ArtworkView(image: model.currentArtwork, cornerRadius: 4)
                    .frame(width: 44, height: 44)
            }

            VStack(spacing: 4) {
                if let track = model.current {
                    Text(track.title).lineLimit(1)
                    Text([track.artist, track.album].compactMap { $0 }.joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                } else {
                    Text("Nothing playing").foregroundStyle(.secondary)
                }
                HStack(spacing: 8) {
                    Text(timeText(model.currentTime))
                    Slider(
                        value: Binding(get: { model.currentTime }, set: { model.seek(to: $0) }),
                        in: 0...max(model.duration, 1)
                    )
                    .disabled(model.current == nil)
                    Text(timeText(model.duration))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
            .frame(maxWidth: 520)

            if let track = model.current {
                HeartButton(isOn: model.isFavorite(track)) { model.toggleFavorite(track) }
                    .font(.title3)
            }
            // Lyrics: only while the track has some.
            if model.lyrics != nil {
                Button { model.showsLyrics.toggle() } label: {
                    Image(systemName: "text.quote")
                        .foregroundStyle(model.showsLyrics ? Color.accentColor : Color.secondary)
                }
                .buttonStyle(.borderless)
                .help(model.showsLyrics ? "Hide Lyrics (⌥⌘L)" : "Show Lyrics (⌥⌘L)")
            }

            Spacer()

            // Practice: tinted while the track plays other than recorded,
            // so a remembered 80 % never passes for the record.
            Button { model.showsPractice.toggle() } label: {
                Image(systemName: "slider.horizontal.3")
                    .foregroundStyle(model.showsPractice || model.practice.changesPlayback ? Color.accentColor : Color.secondary)
            }
            .buttonStyle(.borderless)
            .disabled(model.current == nil)
            .help(model.showsPractice ? "Hide Practice Panel (⌥⌘P)" : "Tempo, Key and Loop (⌥⌘P)")

            // Up Next: the icon with how many tracks follow; the popover
            // lists them.
            Button { model.showsUpNext.toggle() } label: {
                HStack(spacing: 4) {
                    Image(systemName: "list.bullet")
                    if !model.upcoming.isEmpty {
                        Text("\(model.upcoming.count)").font(.caption.monospacedDigit())
                    }
                }
                .foregroundStyle(model.showsUpNext ? Color.accentColor : Color.secondary)
            }
            .buttonStyle(.borderless)
            .disabled(model.queue.isEmpty)
            .help("Up Next (⌘U)")
            .popover(isPresented: $model.showsUpNext, arrowEdge: .top) {
                UpNextView().environmentObject(model)
            }

            HStack(spacing: 6) {
                Image(systemName: "speaker.fill").foregroundStyle(.secondary)
                Slider(value: $model.volume, in: 0...1).frame(width: 100)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .modifier(PlayerBarBackground())
    }
}

/// The queue after the current track. Double-click plays an entry now,
/// the × on hover takes it out, Clear empties the list. What is playing
/// is not in it: the bar already shows that.
struct UpNextView: View {
    @EnvironmentObject private var model: PlayerModel

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Up Next").font(.headline)
                Spacer()
                Text(summary).font(.caption).foregroundStyle(.secondary)
                Button("Clear") { model.clearUpNext() }
                    .controlSize(.small)
                    .disabled(model.upcoming.isEmpty)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            Divider()
            if model.upcoming.isEmpty {
                Text(model.queue.repeatMode == .off ? "Nothing after this track." : "Repeats from the start.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(model.upcoming) { entry in
                        UpNextRow(entry: entry)
                            .contentShape(Rectangle())
                            .onTapGesture(count: 2) { model.play(entry: entry) }
                            .contextMenu {
                                Button("Play Now") { model.play(entry: entry) }
                                Button("Remove") { model.remove(entry: entry) }
                                Divider()
                                Button("Show in Finder") { model.showInFinder(entry.track) }
                            }
                    }
                }
                .listStyle(.inset)
            }
        }
        .frame(width: 380, height: 420)
    }

    private var summary: String {
        let entries = model.upcoming
        guard !entries.isEmpty else { return "" }
        let count = entries.count == 1 ? "1 track" : "\(entries.count) tracks"
        return "\(count) · \(lengthText(entries.reduce(0) { $0 + $1.track.duration }))"
    }
}

private struct UpNextRow: View {
    @EnvironmentObject private var model: PlayerModel
    let entry: PlayQueue.Entry
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading) {
                Text(entry.track.title).lineLimit(1)
                Text([entry.track.artist, entry.track.album].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Text(timeText(entry.track.duration)).monospacedDigit().foregroundStyle(.secondary)
            Button { model.remove(entry: entry) } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .opacity(hovering ? 1 : 0)
            .help("Remove from Up Next")
        }
        .padding(.vertical, 2)
        .onHover { hovering = $0 }
    }
}

/// The content colour, like the rest of the window: the bar material is
/// white in light mode but a blue-tinted grey in dark mode, beside the
/// neutral grid and lists. macOS 12 keeps the material: in the test VM an
/// opaque background let the seek slider vanish in dark mode, which is
/// most likely the VM's slider rendering (see CLAUDE.md), but real
/// hardware with 12 has not been checked.
struct PlayerBarBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 13, *) {
            content.background(Color(nsColor: .textBackgroundColor))
        } else {
            content.background(.bar)
        }
    }
}
