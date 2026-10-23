// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Accelerate
import AVFoundation
import Foundation

/// The outline of a file in equal slices of time, for finding a passage
/// by eye. Not stored; reading a four-minute song takes a fraction of a
/// second.
///
/// What is drawn is `levels`, the RMS of each slice: a mastered record
/// reaches full scale nearly everywhere, so its peaks make one solid
/// block, while the RMS still tells a quiet verse from a loud chorus.
public struct Waveform: Equatable, Sendable {
    /// One value per slice, 0...1, the loudest sample of any channel.
    public let peaks: [Float]
    /// One value per slice, 0...1, the RMS over all channels.
    public let levels: [Float]
    public let duration: TimeInterval

    public init(peaks: [Float], levels: [Float], duration: TimeInterval) {
        self.peaks = peaks
        self.levels = levels
        self.duration = duration
    }

    /// The loudest slice, for scaling the drawing to the file; never 0.
    public var maximum: Float { max(peaks.max() ?? 0, 0.001) }
    /// The slice with the most energy; never 0.
    public var maximumLevel: Float { max(levels.max() ?? 0, 0.001) }

    /// Decodes the whole file and reduces it to `slices` peaks. Blocking:
    /// call it off the main thread. Checks for cancellation between
    /// buffers.
    public static func read(_ url: URL, slices: Int = 1000) throws -> Waveform {
        let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        let format = file.processingFormat
        let channels = Int(format.channelCount)
        let length = max(file.length, 1)
        let count = max(1, min(slices, Int(length)))
        var peaks = [Float](repeating: 0, count: count)
        var squares = [Double](repeating: 0, count: count)
        var samples = [Double](repeating: 0, count: count)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 65_536) else {
            return Waveform(peaks: peaks, levels: peaks, duration: 0)
        }
        var position: AVAudioFramePosition = 0
        while file.framePosition < file.length {
            try Task.checkCancellation()
            try file.read(into: buffer)
            let frames = Int(buffer.frameLength)
            guard frames > 0, let data = buffer.floatChannelData else { break }
            // Slice s holds the frames f with f * count / length == s.
            var frame = 0
            while frame < frames {
                let absolute = position + AVAudioFramePosition(frame)
                let slice = Int(absolute * AVAudioFramePosition(count) / length)
                let sliceEnd = (AVAudioFramePosition(slice + 1) * length + AVAudioFramePosition(count) - 1)
                    / AVAudioFramePosition(count)
                let run = min(frames - frame, max(1, Int(sliceEnd - absolute)))
                for channel in 0..<channels {
                    var level: Float = 0
                    vDSP_maxmgv(data[channel] + frame, 1, &level, vDSP_Length(run))
                    peaks[slice] = max(peaks[slice], min(level, 1))
                    var sum: Float = 0
                    vDSP_svesq(data[channel] + frame, 1, &sum, vDSP_Length(run))
                    squares[slice] += Double(sum)
                    samples[slice] += Double(run)
                }
                frame += run
            }
            position += AVAudioFramePosition(frames)
        }
        let levels = zip(squares, samples).map { sum, n in n > 0 ? Float(min(1, (sum / n).squareRoot())) : 0 }
        return Waveform(peaks: peaks, levels: levels, duration: Double(file.length) / format.sampleRate)
    }
}
