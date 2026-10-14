// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import AVFoundation
import Foundation

extension LoudnessMeter {
    /// Decodes the whole file through AVFoundation and measures it;
    /// nil when the file is silent throughout. Blocking: call it off
    /// the main thread. Checks for cancellation between buffers.
    public static func measure(_ url: URL) throws -> TrackLoudness? {
        let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        let format = file.processingFormat
        let channels = Int(format.channelCount)
        var meter = LoudnessMeter(sampleRate: format.sampleRate, channelCount: channels)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 65_536) else { return nil }
        while file.framePosition < file.length {
            try Task.checkCancellation()
            try file.read(into: buffer)
            let frames = Int(buffer.frameLength)
            guard frames > 0, let data = buffer.floatChannelData else { break }
            meter.add(frames: frames, channels: (0..<channels).map { UnsafePointer(data[$0]) })
        }
        return meter.result
    }
}
