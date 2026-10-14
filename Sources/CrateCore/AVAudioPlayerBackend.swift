// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AVFoundation
import Foundation

/// `AudioPlayer` on top of `AVAudioPlayer`, which decodes every format in
/// `LibraryScanner.defaultExtensions` and routes through the system's
/// default output. Gapless playback is out of its reach, which is why the
/// app uses `AVAudioEngineBackend`; this one stays as the simple fallback
/// behind the same protocol.
@MainActor
public final class AVAudioPlayerBackend: NSObject, AudioPlayer, AVAudioPlayerDelegate {
    private var player: AVAudioPlayer?
    private var storedVolume: Float = 1
    /// `AVAudioPlayer` only attenuates, so a positive gain is capped at
    /// unity here; the engine backend has a real gain stage.
    private var gainFactor: Float = 1

    public var onFinished: (@MainActor () -> Void)?

    public override init() {
        super.init()
    }

    public var isPlaying: Bool { player?.isPlaying ?? false }
    public var currentTime: TimeInterval { player?.currentTime ?? 0 }
    public var duration: TimeInterval { player?.duration ?? 0 }

    public var volume: Float {
        get { storedVolume }
        set {
            storedVolume = min(max(newValue, 0), 1)
            player?.volume = min(storedVolume * gainFactor, 1)
        }
    }

    public func load(_ url: URL, gain: Double) throws {
        player?.stop()
        let next = try AVAudioPlayer(contentsOf: url)
        next.delegate = self
        gainFactor = ReplayGain.factor(gain)
        next.volume = min(storedVolume * gainFactor, 1)
        next.prepareToPlay()
        player = next
    }

    public func play() {
        player?.play()
    }

    public func pause() {
        player?.pause()
    }

    public func stop() {
        player?.stop()
        player = nil
    }

    public func seek(to time: TimeInterval) {
        guard let player else { return }
        player.currentTime = min(max(time, 0), player.duration)
    }

    // MARK: - AVAudioPlayerDelegate

    nonisolated public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            self.onFinished?()
        }
    }
}
