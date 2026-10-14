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
                Button { model.togglePlayPause() } label: {
                    Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title2)
                        .frame(width: 28)
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

            Spacer()

            HStack(spacing: 6) {
                Image(systemName: "speaker.fill").foregroundStyle(.secondary)
                Slider(value: $model.volume, in: 0...1).frame(width: 100)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.bar)
    }
}
