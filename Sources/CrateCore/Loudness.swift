// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// Loudness the way ReplayGain 2.0 wants it measured: ITU-R BS.1770-4,
/// which is EBU R 128. Samples go through the K-weighting filter (a high
/// shelf, then a high-pass), their power is taken over 400 ms blocks
/// every 100 ms, blocks quieter than -70 LUFS are dropped, then blocks
/// more than 10 LU below the mean of the rest, and the integrated
/// loudness is the mean power of what survives. The blocks are kept as a
/// histogram, so an album's loudness can be gated over all its tracks'
/// blocks without keeping the blocks.
public struct LoudnessMeter {
    public let sampleRate: Double
    public let channelCount: Int

    private var filters: [KFilter]
    private let weights: [Double]
    private let stepFrames: Int
    private var stepSums: [Double]
    private var stepFilled = 0
    /// The last four steps' sums per channel, oldest first.
    private var ring: [[Double]] = []
    private var histogram = LoudnessHistogram()
    private var peak: Float = 0

    public init(sampleRate: Double, channelCount: Int) {
        self.sampleRate = sampleRate
        self.channelCount = channelCount
        filters = (0..<channelCount).map { _ in KFilter(sampleRate: sampleRate) }
        // L, R, C count once, LFE not at all, the surrounds 1.41, in the
        // usual order; stereo and mono are the first two.
        weights = (0..<channelCount).map { index in
            guard channelCount >= 6 else { return 1 }
            if index == 3 { return 0 }
            if index == 4 || index == 5 { return 1.41 }
            return 1
        }
        stepFrames = Int((sampleRate * 0.1).rounded())
        stepSums = Array(repeating: 0, count: channelCount)
    }

    /// Planar samples, one array per channel, all the same length.
    public mutating func add(_ channels: [[Float]]) {
        guard let frames = channels.first?.count, frames > 0 else { return }
        channels.withUnsafeBufferPointers { pointers in
            add(frames: frames, channels: pointers)
        }
    }

    /// Planar samples by pointer, for decoders' buffers.
    public mutating func add(frames: Int, channels: [UnsafePointer<Float>]) {
        var offset = 0
        while offset < frames {
            let count = min(frames - offset, stepFrames - stepFilled)
            for c in 0..<min(channelCount, channels.count) {
                let base = channels[c] + offset
                var sum = 0.0
                var peak = self.peak
                var filter = filters[c]
                for i in 0..<count {
                    let x = base[i]
                    let magnitude = abs(x)
                    if magnitude > peak { peak = magnitude }
                    let y = filter.process(Double(x))
                    sum += y * y
                }
                filters[c] = filter
                self.peak = peak
                stepSums[c] += sum
            }
            stepFilled += count
            offset += count
            if stepFilled == stepFrames {
                stepDone()
            }
        }
    }

    private mutating func stepDone() {
        ring.append(stepSums)
        if ring.count > 4 { ring.removeFirst() }
        stepSums = Array(repeating: 0, count: channelCount)
        stepFilled = 0
        guard ring.count == 4 else { return }
        var power = 0.0
        for c in 0..<channelCount {
            let sum = ring[0][c] + ring[1][c] + ring[2][c] + ring[3][c]
            power += weights[c] * sum / Double(4 * stepFrames)
        }
        histogram.add(power: power)
    }

    /// What was measured so far; nil before the first full block.
    public var result: TrackLoudness? {
        guard histogram.blockCount > 0 else { return nil }
        return TrackLoudness(loudness: histogram.integratedLoudness, peak: Double(peak), histogram: histogram)
    }

}

extension Array where Element == [Float] {
    fileprivate func withUnsafeBufferPointers(_ body: ([UnsafePointer<Float>]) -> Void) {
        var pointers: [UnsafePointer<Float>] = []
        func collect(_ index: Int) {
            if index == count {
                body(pointers)
                return
            }
            self[index].withUnsafeBufferPointer { buffer in
                pointers.append(buffer.baseAddress!)
                collect(index + 1)
            }
        }
        if allSatisfy({ !$0.isEmpty }) { collect(0) }
    }
}

/// The two BS.1770 biquads: a high shelf of about +4 dB above 1.5 kHz
/// for the head, then a high-pass at 38 Hz. Coefficients follow the
/// standard's design for any sample rate.
struct KFilter {
    private var b0 = 0.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0
    private var x1 = 0.0, x2 = 0.0, y1 = 0.0, y2 = 0.0
    private var hb0 = 0.0, hb1 = 0.0, hb2 = 0.0, ha1 = 0.0, ha2 = 0.0
    private var hx1 = 0.0, hx2 = 0.0, hy1 = 0.0, hy2 = 0.0

    init(sampleRate fs: Double) {
        // Stage 1: shelf.
        let f0 = 1681.974450955533, gain = 3.999843853973347, q = 0.7071752369554196
        let k = tan(Double.pi * f0 / fs)
        let vh = pow(10, gain / 20)
        let vb = pow(vh, 0.4996667741545416)
        let a0 = 1 + k / q + k * k
        b0 = (vh + vb * k / q + k * k) / a0
        b1 = 2 * (k * k - vh) / a0
        b2 = (vh - vb * k / q + k * k) / a0
        a1 = 2 * (k * k - 1) / a0
        a2 = (1 - k / q + k * k) / a0
        // Stage 2: high-pass.
        let hf0 = 38.13547087602444, hq = 0.5003270373238773
        let hk = tan(Double.pi * hf0 / fs)
        let ha0 = 1 + hk / hq + hk * hk
        hb0 = 1 / ha0
        hb1 = -2 / ha0
        hb2 = 1 / ha0
        ha1 = 2 * (hk * hk - 1) / ha0
        ha2 = (1 - hk / hq + hk * hk) / ha0
    }

    @inline(__always)
    mutating func process(_ x: Double) -> Double {
        let y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        let z = hb0 * y + hb1 * hx1 + hb2 * hx2 - ha1 * hy1 - ha2 * hy2
        hx2 = hx1; hx1 = y; hy2 = hy1; hy1 = z
        return z
    }
}

/// Gated blocks as counts and summed power per 1 LU from -70 LUFS up,
/// enough to compute the relative gate and the integrated loudness of
/// one track or of several tracks merged. One LU per bin keeps a track
/// to a few dozen bins (speech spans more than music; 18,000 files come
/// to some megabytes) and moves the relative gate by half an LU at
/// most, which shifts the result by hundredths. Encoded as [index,
/// count, power] triples.
public struct LoudnessHistogram: Equatable, Sendable, Codable {
    public static let lowest = -70.0
    public static let binWidth = 1.0
    public static let binCount = 80

    /// Bin index to (block count, summed power); sparse.
    public private(set) var bins: [Int: Bin]

    public struct Bin: Equatable, Sendable {
        public var count: Int
        public var power: Double
    }

    public init(bins: [Int: Bin] = [:]) {
        self.bins = bins
    }

    public init(from decoder: Decoder) throws {
        var container = try decoder.unkeyedContainer()
        var bins: [Int: Bin] = [:]
        while !container.isAtEnd {
            var triple = try container.nestedUnkeyedContainer()
            let index = try triple.decode(Int.self)
            let count = try triple.decode(Int.self)
            let power = try triple.decode(Double.self)
            bins[index] = Bin(count: count, power: power)
        }
        self.bins = bins
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.unkeyedContainer()
        for index in bins.keys.sorted() {
            let bin = bins[index]!
            var triple = container.nestedUnkeyedContainer()
            try triple.encode(index)
            try triple.encode(bin.count)
            // Four significant digits: hundredths of a dB.
            try triple.encode(Double(String(format: "%.4g", bin.power)) ?? bin.power)
        }
    }

    public var blockCount: Int { bins.values.reduce(0) { $0 + $1.count } }

    static func loudness(ofPower power: Double) -> Double {
        -0.691 + 10 * log10(power)
    }

    /// Drops the block below the absolute gate; keeps the rest.
    public mutating func add(power: Double) {
        guard power > 0 else { return }
        let loudness = Self.loudness(ofPower: power)
        guard loudness >= Self.lowest else { return }
        let index = min(Self.binCount - 1, Int((loudness - Self.lowest) / Self.binWidth))
        var bin = bins[index] ?? Bin(count: 0, power: 0)
        bin.count += 1
        bin.power += power
        bins[index] = bin
    }

    public mutating func merge(_ other: LoudnessHistogram) {
        for (index, bin) in other.bins {
            var mine = bins[index] ?? Bin(count: 0, power: 0)
            mine.count += bin.count
            mine.power += bin.power
            bins[index] = mine
        }
    }

    /// nil when no block passed the absolute gate.
    public var integratedLoudness: Double? {
        var count = 0
        var power = 0.0
        for bin in bins.values {
            count += bin.count
            power += bin.power
        }
        guard count > 0 else { return nil }
        let threshold = Self.loudness(ofPower: power / Double(count)) - 10
        var gatedCount = 0
        var gatedPower = 0.0
        for (index, bin) in bins where Self.lowest + (Double(index) + 0.5) * Self.binWidth >= threshold {
            gatedCount += bin.count
            gatedPower += bin.power
        }
        guard gatedCount > 0 else { return nil }
        return Self.loudness(ofPower: gatedPower / Double(gatedCount))
    }
}

/// One track's measurement: integrated loudness in LUFS (nil for a
/// track that never rises above -70 LUFS), the sample peak as a linear
/// factor, and the blocks for album gating.
public struct TrackLoudness: Equatable, Sendable, Codable {
    public var loudness: Double?
    public var peak: Double
    public var histogram: LoudnessHistogram

    public init(loudness: Double?, peak: Double, histogram: LoudnessHistogram) {
        self.loudness = loudness
        self.peak = peak
        self.histogram = histogram
    }

    /// ReplayGain 2.0: the gain that brings the track to -18 LUFS, limited
    /// by the peak so nothing clips. nil for silence.
    public var gain: Double? {
        loudness.map { ReplayGain.limited(ReplayGain.reference - $0, peak: peak) }
    }

    /// The album gain over several tracks' blocks and the loudest peak.
    public static func albumGain(_ tracks: [TrackLoudness]) -> Double? {
        var histogram = LoudnessHistogram()
        var peak = 0.0
        for track in tracks {
            histogram.merge(track.histogram)
            peak = max(peak, track.peak)
        }
        return histogram.integratedLoudness.map { ReplayGain.limited(ReplayGain.reference - $0, peak: peak) }
    }
}

extension ReplayGain {
    /// ReplayGain 2.0's target: -18 LUFS, 5 dB above EBU R 128's -23.
    public static let reference = -18.0
}
