// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// Plays one file at a time. The queue and the UI only talk to this
/// protocol; `AVAudioEngineBackend` is the implementation the app uses,
/// `AVAudioPlayerBackend` the simpler fallback.
/// Main-actor bound because every caller is UI state anyway.
@MainActor
public protocol AudioPlayer: AnyObject {
    var isPlaying: Bool { get }
    var currentTime: TimeInterval { get }
    var duration: TimeInterval { get }
    /// 0...1
    var volume: Float { get set }
    /// Called when the loaded file reaches its end on its own, not on
    /// `stop()`. The queue's owner advances and loads the next track here.
    var onFinished: (@MainActor () -> Void)? { get set }

    /// Replaces whatever is loaded, without starting playback. `gain` is
    /// the file's loudness correction in dB (see `Track.trackGain`), which
    /// the backend applies on top of `volume`; 0 leaves the file as is.
    func load(_ url: URL, gain: Double) throws
    func play()
    func pause()
    /// Unloads the file.
    func stop()
    func seek(to time: TimeInterval)
    /// Announces what will be loaded once the current file finishes, so a
    /// backend that can chain files does so without a gap. nil withdraws
    /// an earlier announcement. Loading that same URL right after
    /// `onFinished` continues the playback already running. `gain` is
    /// that file's correction, applied at the hand-over.
    func preload(_ url: URL?, gain: Double)
    /// Speed (1 = as recorded) and pitch shift in cents, independent of
    /// each other. Stays in effect across loads until set again.
    func setTempo(_ tempo: Double, pitch cents: Double)
    /// Repeats the passage without a gap once playback reaches its end;
    /// nil plays on to the end of the file. A position after the end
    /// jumps back to the start. `load` clears it.
    func setLoop(_ loop: PracticeLoop?)
}

public extension AudioPlayer {
    func preload(_ url: URL?, gain: Double) {}
    func setTempo(_ tempo: Double, pitch cents: Double) {}
    func setLoop(_ loop: PracticeLoop?) {}

    func load(_ url: URL) throws { try load(url, gain: 0) }
    func preload(_ url: URL?) { preload(url, gain: 0) }
}
