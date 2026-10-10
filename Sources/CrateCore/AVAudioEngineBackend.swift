// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AVFoundation
import Foundation

/// `AudioPlayer` on `AVAudioEngine` with one `AVAudioPlayerNode`. Its
/// reason to exist is gapless playback: the next file is scheduled on the
/// node right behind the current one, so the transition happens inside
/// the render thread, sample-accurate, instead of through a round trip to
/// the main thread the way `AVAudioPlayerBackend` does it.
///
/// Bookkeeping: the node's own clock (`playerTime`) counts rendered frames
/// since the last `stop()`; every `load` and `seek` stops the node, so a
/// segment's position is simply its start offset plus that clock. On the
/// gapless hand-over the clock keeps running, so the frames of the
/// finished file are added to a running offset instead. Completion
/// handlers carry a generation number; anything from a segment that was
/// stopped since is ignored.
@MainActor
public final class AVAudioEngineBackend: AudioPlayer {
    private struct Segment {
        let url: URL
        let file: AVAudioFile
        /// Seconds into the file where this segment starts.
        let offset: TimeInterval
        let generation: Int

        var sampleRate: Double { file.processingFormat.sampleRate }
        var duration: TimeInterval { Double(file.length) / sampleRate }
        var remainingFrames: AVAudioFrameCount {
            AVAudioFrameCount(max(0, file.length - AVAudioFramePosition(offset * sampleRate)))
        }
    }

    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private var connectedFormat: AVAudioFormat?
    private var current: Segment?
    private var next: Segment?
    /// Node-clock frames that belong to segments finished before `current`.
    private var framesBeforeCurrent: AVAudioFramePosition = 0
    private var generation = 0
    private var playing = false
    /// Set when `current` has played to its end and the node has moved on
    /// to `next`; only then does loading `next`'s URL mean a hand-over
    /// rather than a skip.
    private var currentFinished = false
    private var storedVolume: Float = 1
    /// Only written in init and read in deinit, hence the unsafe opt-out.
    nonisolated(unsafe) private var configurationObserver: (any NSObjectProtocol)?

    public var onFinished: (@MainActor () -> Void)?

    public init() {
        engine.attach(node)
        configurationObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.outputChanged() }
        }
    }

    deinit {
        if let configurationObserver {
            NotificationCenter.default.removeObserver(configurationObserver)
        }
    }

    public var isPlaying: Bool { playing }

    public var currentTime: TimeInterval {
        guard let current else { return 0 }
        return min(current.offset + nodeSeconds(rate: current.sampleRate), current.duration)
    }

    public var duration: TimeInterval { current?.duration ?? 0 }

    public var volume: Float {
        get { storedVolume }
        set {
            storedVolume = min(max(newValue, 0), 1)
            engine.mainMixerNode.outputVolume = storedVolume
        }
    }

    public func load(_ url: URL) throws {
        if currentFinished, let next, next.url == url {
            // The gapless hand-over: this file is already playing.
            promoteNext()
            return
        }
        stopNode()
        let file = try AVAudioFile(forReading: url)
        current = Segment(url: url, file: file, offset: 0, generation: generation)
        next = nil
        framesBeforeCurrent = 0
        currentFinished = false
        try connect(for: file)
        schedule(current!)
    }

    public func preload(_ url: URL?) {
        guard let url, let current else {
            next = nil
            return
        }
        if next?.url == url { return }
        next = nil
        // Only a file in the current connection format can follow without
        // a stop; anything else goes through the normal onFinished/load path
        // and the backend reconnects then.
        guard let file = try? AVAudioFile(forReading: url),
              let connectedFormat, Self.compatible(file.processingFormat, connectedFormat) else { return }
        let segment = Segment(url: url, file: file, offset: 0, generation: current.generation)
        next = segment
        schedule(segment)
    }

    public func play() {
        guard current != nil else { return }
        do {
            if !engine.isRunning {
                engine.mainMixerNode.outputVolume = storedVolume
                try engine.start()
            }
            node.play()
            playing = true
        } catch {
            playing = false
        }
    }

    public func pause() {
        node.pause()
        playing = false
    }

    public func stop() {
        stopNode()
        current = nil
        next = nil
        framesBeforeCurrent = 0
        engine.stop()
    }

    public func seek(to time: TimeInterval) {
        guard let segment = current else { return }
        let wasPlaying = playing
        let nextURL = next?.url
        stopNode()
        let offset = min(max(time, 0), segment.duration)
        current = Segment(url: segment.url, file: segment.file, offset: offset, generation: generation)
        next = nil
        framesBeforeCurrent = 0
        currentFinished = false
        schedule(current!)
        preload(nextURL)
        if wasPlaying { play() }
    }

    // MARK: - Internals

    private func stopNode() {
        generation += 1
        node.stop()
        playing = false
    }

    private func connect(for file: AVAudioFile) throws {
        let format = file.processingFormat
        if let connectedFormat, Self.compatible(format, connectedFormat) { return }
        let wasRunning = engine.isRunning
        engine.stop()
        engine.disconnectNodeOutput(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        connectedFormat = format
        engine.prepare()
        if wasRunning { try engine.start() }
    }

    static func compatible(_ a: AVAudioFormat, _ b: AVAudioFormat) -> Bool {
        a.sampleRate == b.sampleRate && a.channelCount == b.channelCount
    }

    private func schedule(_ segment: Segment) {
        let frames = segment.remainingFrames
        let generation = segment.generation
        let start = AVAudioFramePosition(segment.offset * segment.sampleRate)
        guard frames > 0 else {
            // Nothing left to play: report completion as if it had played.
            Task { @MainActor in self.segmentFinished(generation: generation, url: segment.url) }
            return
        }
        node.scheduleSegment(
            segment.file, startingFrame: start, frameCount: frames, at: nil, completionCallbackType: .dataPlayedBack
        ) { [weak self] _ in
            Task { @MainActor in self?.segmentFinished(generation: generation, url: segment.url) }
        }
    }

    private func segmentFinished(generation: Int, url: URL) {
        guard let current, current.generation == generation, current.url == url else { return }
        currentFinished = true
        if next == nil {
            playing = false
        }
        onFinished?()
    }

    /// Makes the preloaded segment current while the node keeps rendering.
    private func promoteNext() {
        guard let next, let current else { return }
        framesBeforeCurrent += AVAudioFramePosition(Double(current.remainingFrames) * nodeRate / current.sampleRate)
        self.current = next
        self.next = nil
        currentFinished = false
    }

    private var nodeRate: Double {
        connectedFormat?.sampleRate ?? 44_100
    }

    /// Seconds the node has rendered for the current segment.
    private func nodeSeconds(rate: Double) -> TimeInterval {
        guard let nodeTime = node.lastRenderTime, let playerTime = node.playerTime(forNodeTime: nodeTime) else {
            return 0
        }
        let frames = max(0, playerTime.sampleTime - framesBeforeCurrent)
        return Double(frames) / playerTime.sampleRate
    }

    /// The output device changed (headphones in, Bluetooth out): the
    /// engine stops and forgets the node's clock. Resume at the same spot.
    private func outputChanged() {
        guard current != nil else { return }
        let position = currentTime
        let wasPlaying = playing
        playing = false
        seek(to: position)
        if wasPlaying { play() }
    }
}
