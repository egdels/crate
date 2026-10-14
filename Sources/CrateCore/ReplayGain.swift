// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation

/// Loudness normalisation as the files describe it: ReplayGain tags
/// (track and album gain in dB plus the peak), and iTunes Sound Check
/// (`iTunNORM`), which iTunes-ripped files carry instead. The values a
/// `Track` stores are already limited by the peak, so applying them can
/// never clip; the player only converts dB to a factor.
public enum ReplayGain {
    /// The EQ stage's range, and more than any sane tag asks for.
    public static let range: ClosedRange<Double> = -24...24

    /// "-6.50 dB", "+3.25 dB", "-6.5" → the number.
    public static func decibels(_ text: String) -> Double? {
        let trimmed = text.trimmed.lowercased()
        let number = trimmed.hasSuffix("db") ? String(trimmed.dropLast(2)).trimmed : trimmed
        return Double(number.replacingOccurrences(of: ",", with: ".")).flatMap { $0.isFinite ? $0 : nil }
    }

    /// "0.988" → 0.988; a peak of 0 means unknown.
    public static func peak(_ text: String) -> Double? {
        Double(text.trimmed.replacingOccurrences(of: ",", with: ".")).flatMap { $0.isFinite && $0 > 0 ? $0 : nil }
    }

    /// The gain, capped so that the peak sample stays below full scale
    /// when the peak is known, and kept inside `range` either way.
    public static func limited(_ gain: Double, peak: Double?) -> Double {
        var limited = gain
        if let peak, peak > 0 {
            limited = min(limited, -20 * log10(peak))
        }
        return min(max(limited, range.lowerBound), range.upperBound)
    }

    /// iTunes Sound Check: ten hex fields. The first two are the volume
    /// adjustment per channel in thousandths relative to the reference
    /// level (gain in dB = -10·log10(value / 1000)), fields seven and
    /// eight the peak per channel out of 32768. The louder channel
    /// decides both.
    public static func soundCheck(_ text: String) -> (gain: Double, peak: Double?)? {
        let fields = text.split(whereSeparator: \.isWhitespace).compactMap { UInt32($0, radix: 16) }
        guard fields.count >= 2 else { return nil }
        let value = max(fields[0], fields[1])
        guard value > 0 else { return nil }
        let gain = -10 * log10(Double(value) / 1000)
        var peak: Double?
        if fields.count >= 8 {
            let p = max(fields[6], fields[7])
            if p > 0 { peak = Double(p) / 32768 }
        }
        return (gain, peak)
    }

    /// dB to the factor a volume or gain stage wants.
    public static func factor(_ decibels: Double) -> Float {
        Float(pow(10, decibels / 20))
    }

    /// A stand-in album gain for an album whose files carry only track
    /// gains (Sound Check knows no albums): the mean of them, so the
    /// record as a whole lands on level while its own dynamics stay.
    /// nil when no track has a gain.
    public static func albumMean(_ tracks: [Track]) -> Double? {
        let gains = tracks.compactMap(\.trackGain)
        return gains.isEmpty ? nil : gains.reduce(0, +) / Double(gains.count)
    }

    /// What to play a track at. Listening to an album: its album gain,
    /// else the album's mean (see `albumMean`), else the track's own.
    /// Otherwise the track gain, else the album gain. 0 without any.
    public static func playbackGain(_ track: Track, asAlbum: Bool, albumMean: Double?) -> Double {
        let gain = asAlbum
            ? track.albumGain ?? albumMean ?? track.trackGain
            : track.trackGain ?? track.albumGain
        return gain ?? 0
    }
}
