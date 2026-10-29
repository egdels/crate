// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AppKit
import CrateCore
import MediaPlayer

/// The system side of playback: what Control Center, the Touch Bar, the
/// media keys and AirPods see. Publishes the current track to
/// `MPNowPlayingInfoCenter` and routes the remote commands back into the
/// model. The model calls `update` whenever something changed; the system
/// extrapolates the position from the rate itself, so no per-tick updates
/// are needed.
@MainActor
final class NowPlaying {
    private let center = MPNowPlayingInfoCenter.default()
    private let commands = MPRemoteCommandCenter.shared()

    init(model: PlayerModel) {
        commands.playCommand.addTarget { [weak model] _ in
            guard let model, model.current != nil else { return .noActionableNowPlayingItem }
            if !model.isPlaying { model.togglePlayPause() }
            return .success
        }
        commands.pauseCommand.addTarget { [weak model] _ in
            guard let model, model.current != nil else { return .noActionableNowPlayingItem }
            if model.isPlaying { model.togglePlayPause() }
            return .success
        }
        commands.togglePlayPauseCommand.addTarget { [weak model] _ in
            guard let model, model.current != nil else { return .noActionableNowPlayingItem }
            model.togglePlayPause()
            return .success
        }
        commands.nextTrackCommand.addTarget { [weak model] _ in
            guard let model, model.current != nil else { return .noActionableNowPlayingItem }
            model.next()
            return .success
        }
        commands.previousTrackCommand.addTarget { [weak model] _ in
            guard let model, model.current != nil else { return .noActionableNowPlayingItem }
            model.previous()
            return .success
        }
        commands.changePlaybackPositionCommand.addTarget { [weak model] event in
            guard let model, model.current != nil,
                  let event = event as? MPChangePlaybackPositionCommandEvent else { return .noActionableNowPlayingItem }
            model.seek(to: event.positionTime)
            return .success
        }
        // The jumps, for audiobooks and long tracks only (`update`
        // switches them): where they are on, the system shows them
        // instead of previous and next.
        commands.skipBackwardCommand.preferredIntervals = [NSNumber(value: Skip.back)]
        commands.skipForwardCommand.preferredIntervals = [NSNumber(value: Skip.forward)]
        commands.skipBackwardCommand.addTarget { [weak model] event in
            guard let model, model.current != nil,
                  let event = event as? MPSkipIntervalCommandEvent else { return .noActionableNowPlayingItem }
            model.skip(by: -event.interval)
            return .success
        }
        commands.skipForwardCommand.addTarget { [weak model] event in
            guard let model, model.current != nil,
                  let event = event as? MPSkipIntervalCommandEvent else { return .noActionableNowPlayingItem }
            model.skip(by: event.interval)
            return .success
        }
        for command in [commands.seekForwardCommand, commands.seekBackwardCommand, commands.stopCommand] {
            command.isEnabled = false
        }
    }

    func update(
        track: Track?, artwork: CGImage?, duration: TimeInterval, position: TimeInterval, isPlaying: Bool,
        tempo: Double = 1
    ) {
        let skips = track.map(Skip.isOffered) ?? false
        commands.skipBackwardCommand.isEnabled = skips
        commands.skipForwardCommand.isEnabled = skips
        guard let track else {
            center.nowPlayingInfo = nil
            center.playbackState = .stopped
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyPlaybackDuration: duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            // The tempo, so the system's clock runs at the speed of the file.
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? tempo : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let artist = track.artist { info[MPMediaItemPropertyArtist] = artist }
        if let album = track.album { info[MPMediaItemPropertyAlbumTitle] = album }
        if let albumArtist = track.albumArtist { info[MPMediaItemPropertyAlbumArtist] = albumArtist }
        if let number = track.trackNumber { info[MPMediaItemPropertyAlbumTrackNumber] = number }
        if let artwork {
            // The request handler runs on MediaPlayer's own queue when the
            // system serialises the cover (macOS 27.0.1 does, for Control
            // Center). A plain closure here would inherit this method's
            // main-actor isolation, and the runtime's isolation check
            // trapped on that queue. @Sendable keeps it nonisolated; the
            // image is built here, never mutated, and only read there.
            let image = NSImage(cgImage: artwork, size: NSSize(width: artwork.width, height: artwork.height))
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { @Sendable _ in image }
        }
        center.nowPlayingInfo = info
        center.playbackState = isPlaying ? .playing : .paused
    }
}
