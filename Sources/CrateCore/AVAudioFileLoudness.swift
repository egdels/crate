// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AVFoundation
import Accelerate
import CrateDecoders
import Foundation

extension LoudnessMeter {
    /// Decodes the whole file and measures it. Blocking: call it off
    /// the main thread. Checks for cancellation between buffers.
    ///
    /// MP3 and FLAC, most of a usual library, are decoded by minimp3
    /// and dr_flac (`CrateDecoders`), which read a file two to three
    /// times as fast as AVFoundation does; everything else, and any
    /// file those give up on, goes through AVFoundation.
    public static func measure(_ url: URL) throws -> TrackLoudness? {
        if let measured = try measureWithOwnDecoder(url) { return measured }
        return try measureWithAVFoundation(url)
    }

    /// The whole file through AVFoundation.
    public static func measureWithAVFoundation(_ url: URL) throws -> TrackLoudness? {
        let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        let format = file.processingFormat
        let channels = Int(format.channelCount)
        var meter = LoudnessMeter(sampleRate: format.sampleRate, channelCount: channels)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 65_536) else { return nil }
        // Counted here, not asked of the file: `framePosition` raises an
        // Objective-C exception (uncatchable, the app dies) on a damaged
        // file whose client format could not be set; `read` throws.
        var position: AVAudioFramePosition = 0
        while position < file.length {
            try Task.checkCancellation()
            do {
                try file.read(into: buffer)
            } catch let error as NSError
                where error.domain == NSOSStatusErrorDomain && error.code == kAudioFileEndOfFileError && position > 0 {
                // The length of an MP3 without a frame count is an
                // estimate; where it is too long the last read ends in
                // this error, and the file was never measured.
                break
            }
            let frames = Int(buffer.frameLength)
            guard frames > 0, let data = buffer.floatChannelData else { break }
            meter.add(frames: frames, channels: (0..<channels).map { UnsafePointer(data[$0]) })
            position += AVAudioFramePosition(frames)
        }
        return meter.finalResult
    }

    /// Samples per read of an own decoder: 64 MP3 frames, about 1.7 s.
    private static let ownBufferSamples = 1152 * 2 * 64

    /// The whole file through minimp3 or dr_flac, by its name; nil when
    /// it is neither, when the decoder finds no stream of its format,
    /// or when what came out is not as long as AVFoundation says the
    /// file is (2 s or 5 %): bytes of another format can pass for a few
    /// MP3 frames, and a damaged file is AVFoundation's to judge, as it
    /// was before. 16-bit samples, as the Android app measures them.
    public static func measureWithOwnDecoder(_ url: URL) throws -> TrackLoudness? {
        let open: (Int32) -> OpaquePointer?
        switch url.pathExtension.lowercased() {
        case "mp3": open = crate_mp3_open
        case "flac": open = crate_flac_open
        default: return nil
        }
        let descriptor = Darwin.open(url.path, O_RDONLY)
        guard descriptor >= 0 else { return nil }
        defer { close(descriptor) }
        guard let decoder = open(descriptor) else { return nil }
        defer { crate_decoder_close(decoder) }

        var samples = [Int16](repeating: 0, count: ownBufferSamples)
        var planar: [[Float]] = []
        var meter: LoudnessMeter?
        var channels = 0
        var rate = 0.0
        var frames = 0
        var scale: Float = 32768
        while true {
            try Task.checkCancellation()
            let count = Int(crate_decoder_read(decoder, &samples, Int32(samples.count)))
            guard count > 0 else { break }
            if meter == nil {
                channels = Int(crate_decoder_channels(decoder))
                rate = Double(crate_decoder_rate(decoder))
                guard channels > 0, rate > 0 else { return nil }
                meter = LoudnessMeter(sampleRate: rate, channelCount: channels)
                planar = Array(repeating: [Float](repeating: 0, count: ownBufferSamples / channels), count: channels)
            }
            let chunk = count / channels
            for c in 0..<channels {
                samples.withUnsafeBufferPointer { source in
                    planar[c].withUnsafeMutableBufferPointer { plane in
                        vDSP_vflt16(source.baseAddress! + c, vDSP_Stride(channels), plane.baseAddress!, 1, vDSP_Length(chunk))
                        vDSP_vsdiv(plane.baseAddress!, 1, &scale, plane.baseAddress!, 1, vDSP_Length(chunk))
                    }
                }
            }
            planar.withUnsafePointers { meter?.add(frames: chunk, channels: $0) }
            frames += chunk
        }
        guard let meter, rate > 0 else { return nil }
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let expected = Double(file.length) / file.processingFormat.sampleRate
        let seconds = Double(frames) / rate
        if abs(seconds - expected) > max(2, expected * 0.05) { return nil }
        return meter.finalResult
    }
}

extension Array where Element == [Float] {
    /// The arrays' storage, one pointer each, for the time of `body`.
    fileprivate func withUnsafePointers(_ body: ([UnsafePointer<Float>]) -> Void) {
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
        collect(0)
    }
}
