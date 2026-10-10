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

    /// Replaces whatever is loaded, without starting playback.
    func load(_ url: URL) throws
    func play()
    func pause()
    /// Unloads the file.
    func stop()
    func seek(to time: TimeInterval)
    /// Announces what will be loaded once the current file finishes, so a
    /// backend that can chain files does so without a gap. nil withdraws
    /// an earlier announcement. Loading that same URL right after
    /// `onFinished` continues the playback already running.
    func preload(_ url: URL?)
}

public extension AudioPlayer {
    func preload(_ url: URL?) {}
}
