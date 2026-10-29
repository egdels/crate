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
/// Loudness: an `AVAudioUnitEQ` with no bands sits between the node and
/// the mixer and carries each file's gain in its `globalGain`, separate
/// from the user's volume on the mixer. On the gapless hand-over the gain
/// switches when the main thread learns the previous file has played
/// out, a few milliseconds after the fact; within an album the gain is
/// the album's and does not change, and between albums there is no
/// seamless join to protect.
///
/// Tempo and key: an `AVAudioUnitTimePitch` sits between the node and
/// the gain stage and is bypassed while both are neutral, so a track
/// nobody practises plays bit for bit as before. The node still renders
/// the file's own frames, only faster or slower, so its clock keeps
/// counting file time and every position below stays in file seconds.
///
/// Loop: the passage is scheduled as segments of its own, always one
/// pass ahead of the one playing; each pass that finishes queues the
/// next, so the jump from the end back to the start happens inside the
/// render thread like a gapless hand-over. While a loop runs nothing is
/// preloaded; the announced file is kept and scheduled once the loop
/// is gone.
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
        /// dB, see `AudioPlayer.load(_:gain:)`.
        let gain: Double
        let loop: PracticeLoop?

        var sampleRate: Double { file.processingFormat.sampleRate }
        var duration: TimeInterval { Double(file.length) / sampleRate }
        /// Whether playback from `offset` runs into the loop.
        var isLooping: Bool {
            guard let loop else { return false }
            return offset < loop.end
        }
        /// Where the first pass stops: the loop's end when playback runs
        /// into it, else the end of the file.
        var firstEnd: TimeInterval { isLooping ? min(loop!.end, duration) : duration }
        var remainingFrames: AVAudioFrameCount {
            frames(from: offset, to: firstEnd)
        }

        func frames(from start: TimeInterval, to end: TimeInterval) -> AVAudioFrameCount {
            let first = AVAudioFramePosition(start * sampleRate)
            let last = min(file.length, AVAudioFramePosition(end * sampleRate))
            return AVAudioFrameCount(max(0, last - first))
        }

        func with(offset: TimeInterval, generation: Int, loop: PracticeLoop?) -> Segment {
            Segment(url: url, file: file, offset: offset, generation: generation, gain: gain, loop: loop)
        }
    }

    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let timePitch = AVAudioUnitTimePitch()
    private let gainStage = AVAudioUnitEQ(numberOfBands: 0)
    private var connectedFormat: AVAudioFormat?
    private var current: Segment?
    private var next: Segment?
    /// What `preload` was last told, kept while a loop holds it back.
    private var announced: (url: URL, gain: Double)?
    /// Node-clock frames that belong to segments finished before `current`.
    private var framesBeforeCurrent: AVAudioFramePosition = 0
    private var generation = 0
    private var playing = false
    /// The position at the last pause: a paused node has no player time,
    /// so its clock would read as the segment's start.
    private var pausedPosition: TimeInterval?
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
        engine.attach(timePitch)
        engine.attach(gainStage)
        timePitch.bypass = true
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
        if let pausedPosition { return pausedPosition }
        guard let current else { return 0 }
        let elapsed = nodeSeconds(rate: current.sampleRate)
        if current.isLooping, let loop = current.loop {
            let first = current.firstEnd - current.offset
            if elapsed < first { return current.offset + elapsed }
            return loop.start + (elapsed - first).truncatingRemainder(dividingBy: loop.length)
        }
        return min(current.offset + elapsed, current.duration)
    }

    /// The loop in effect, nil when playback runs to the end.
    public var loop: PracticeLoop? { current?.loop }
    public var tempo: Double { Double(timePitch.rate) }
    /// Cents.
    public var pitch: Double { Double(timePitch.pitch) }
    public var isTimePitchBypassed: Bool { timePitch.bypass }

    public var duration: TimeInterval { current?.duration ?? 0 }

    /// The gain in effect for the file playing, in dB.
    public var currentGain: Double { Double(gainStage.globalGain) }

    public var volume: Float {
        get { storedVolume }
        set {
            storedVolume = min(max(newValue, 0), 1)
            engine.mainMixerNode.outputVolume = storedVolume
        }
    }

    public func load(_ url: URL, gain: Double) throws {
        if currentFinished, let next, next.url == url {
            // The gapless hand-over: this file is already playing.
            promoteNext()
            return
        }
        stopNode()
        let file = try AVAudioFile(forReading: url)
        current = Segment(url: url, file: file, offset: 0, generation: generation, gain: gain, loop: nil)
        next = nil
        announced = nil
        framesBeforeCurrent = 0
        currentFinished = false
        try connect(for: file)
        apply(gain: gain)
        schedule(current!)
    }

    public func preload(_ url: URL?, gain: Double) {
        announced = url.map { ($0, gain) }
        guard let url, let current else {
            next = nil
            return
        }
        if next?.url == url { return }
        next = nil
        // A loop never reaches the end of the file; the next one waits.
        if current.isLooping { return }
        // Only a file in the current connection format can follow without
        // a stop; anything else goes through the normal onFinished/load path
        // and the backend reconnects then.
        guard let file = try? AVAudioFile(forReading: url),
              let connectedFormat, Self.compatible(file.processingFormat, connectedFormat) else { return }
        let segment = Segment(
            url: url, file: file, offset: 0, generation: current.generation, gain: gain, loop: nil)
        next = segment
        schedule(segment)
    }

    public func setTempo(_ tempo: Double, pitch cents: Double) {
        timePitch.rate = Float(tempo)
        timePitch.pitch = Float(cents)
        timePitch.bypass = tempo == 1 && cents == 0
    }

    public func setLoop(_ loop: PracticeLoop?) {
        guard let segment = current, segment.loop != loop else { return }
        let position = currentTime
        var start = position
        if let loop, position >= loop.end || position >= segment.duration { start = loop.start }
        restart(segment.with(offset: start, generation: generation, loop: loop))
    }

    private func apply(gain: Double) {
        gainStage.globalGain = Float(min(max(gain, ReplayGain.range.lowerBound), ReplayGain.range.upperBound))
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
            pausedPosition = nil
        } catch {
            playing = false
        }
    }

    public func pause() {
        if playing { pausedPosition = currentTime }
        node.pause()
        playing = false
    }

    public func stop() {
        stopNode()
        current = nil
        next = nil
        announced = nil
        framesBeforeCurrent = 0
        engine.stop()
    }

    public func seek(to time: TimeInterval) {
        guard let segment = current else { return }
        let offset = min(max(time, 0), segment.duration)
        restart(segment.with(offset: offset, generation: generation, loop: segment.loop))
    }

    /// Replaces the node's schedule with the segment, keeping the
    /// play state and the announced next file.
    private func restart(_ segment: Segment) {
        let wasPlaying = playing
        let following = announced
        stopNode()
        current = segment.with(offset: segment.offset, generation: generation, loop: segment.loop)
        next = nil
        framesBeforeCurrent = 0
        currentFinished = false
        schedule(current!)
        preload(following?.url, gain: following?.gain ?? 0)
        if wasPlaying { play() }
    }

    // MARK: - Internals

    private func stopNode() {
        pausedPosition = nil
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
        engine.disconnectNodeOutput(timePitch)
        engine.disconnectNodeOutput(gainStage)
        engine.connect(node, to: timePitch, format: format)
        engine.connect(timePitch, to: gainStage, format: format)
        engine.connect(gainStage, to: engine.mainMixerNode, format: format)
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
        if segment.isLooping {
            node.scheduleSegment(
                segment.file, startingFrame: start, frameCount: frames, at: nil, completionCallbackType: .dataPlayedBack
            ) { [weak self] _ in
                Task { @MainActor in self?.passFinished(generation: generation) }
            }
            schedulePass(of: segment)
            return
        }
        node.scheduleSegment(
            segment.file, startingFrame: start, frameCount: frames, at: nil, completionCallbackType: .dataPlayedBack
        ) { [weak self] _ in
            Task { @MainActor in self?.segmentFinished(generation: generation, url: segment.url) }
        }
    }

    /// Queues one more round of the segment's loop behind what is
    /// scheduled.
    private func schedulePass(of segment: Segment) {
        guard let loop = segment.loop else { return }
        let frames = segment.frames(from: loop.start, to: loop.end)
        guard frames > 0 else { return }
        let generation = segment.generation
        node.scheduleSegment(
            segment.file, startingFrame: AVAudioFramePosition(loop.start * segment.sampleRate), frameCount: frames,
            at: nil, completionCallbackType: .dataPlayedBack
        ) { [weak self] _ in
            Task { @MainActor in self?.passFinished(generation: generation) }
        }
    }

    /// A pass of the loop (or the run-in to it) has played; the next one
    /// is already playing, so queue the one after it.
    private func passFinished(generation: Int) {
        guard let current, current.generation == generation, current.isLooping else { return }
        schedulePass(of: current)
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
        apply(gain: next.gain)
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
        guard let current else { return }
        let position = currentTime
        let wasPlaying = playing
        playing = false
        restart(current.with(offset: position, generation: generation, loop: current.loop))
        if wasPlaying { play() }
    }
}
