// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct ReplayGainTests {
    @Test func parsesTheTextForms() {
        #expect(ReplayGain.decibels("-6.50 dB") == -6.5)
        #expect(ReplayGain.decibels("+3.25 dB") == 3.25)
        #expect(ReplayGain.decibels(" -2,5 DB ") == -2.5)
        #expect(ReplayGain.decibels("-7.25") == -7.25)
        #expect(ReplayGain.decibels("loud") == nil)
        #expect(ReplayGain.peak("0.988") == 0.988)
        #expect(ReplayGain.peak("0") == nil)
        #expect(ReplayGain.peak("x") == nil)
    }

    @Test func peakLimitsThePositiveGain() {
        // A peak of 0.5 leaves 6 dB of headroom; more than that would clip.
        #expect(abs(ReplayGain.limited(9, peak: 0.5) - 6.02) < 0.01)
        #expect(ReplayGain.limited(3, peak: 0.5) == 3)
        #expect(ReplayGain.limited(-8, peak: 0.5) == -8)
        #expect(ReplayGain.limited(9, peak: nil) == 9)
        #expect(ReplayGain.limited(40, peak: nil) == 24)
        #expect(ReplayGain.limited(-40, peak: 0.9) == -24)
    }

    @Test func soundCheckBecomesDecibelsAndPeak() throws {
        // 0x1070 = 4208 thousandths: a loud master, turned down 6.2 dB;
        // peak 0x82AF of 32768.
        let loud = try #require(ReplayGain.soundCheck(" 00001070 0000120F 00008A1B 000082AF 00023568 000234CB 00008000 00007E5F 00000000 00000000"))
        #expect(abs(loud.gain - (-10 * log10(4623.0 / 1000))) < 0.001, "the louder channel, 0x120F")
        #expect(abs(loud.peak! - 1.0) < 0.001, "0x8000 of 32768")
        // 0x18B = 395: a quiet track, turned up 4 dB.
        let quiet = try #require(ReplayGain.soundCheck(" 0000018B 0000016F 0000290C 0000264E 00013105 00013105 00006000 00005800 00000000 00000000"))
        #expect(abs(quiet.gain - 4.03) < 0.01)
        #expect(abs(quiet.peak! - 0.75) < 0.001)
        #expect(ReplayGain.soundCheck("00000000 00000000") == nil)
        #expect(ReplayGain.soundCheck("nonsense") == nil)
    }

    @Test func replayGainWinsOverSoundCheckAndFallsBackToIt() {
        let both = AVFoundationMetadataReader.gains(track: -6.5, trackPeak: 0.9, album: -7.25, albumPeak: nil, soundCheck: (4, 0.5))
        #expect(both.track == -6.5)
        #expect(both.album == -7.25)
        let onlyAlbum = AVFoundationMetadataReader.gains(track: nil, trackPeak: nil, album: -7.25, albumPeak: nil, soundCheck: (4, 0.5))
        #expect(onlyAlbum.track == nil)
        #expect(onlyAlbum.album == -7.25)
        let onlyCheck = AVFoundationMetadataReader.gains(track: nil, trackPeak: nil, album: nil, albumPeak: nil, soundCheck: (9, 0.5))
        #expect(abs(onlyCheck.track! - 6.02) < 0.01, "limited by the Sound Check peak")
        #expect(onlyCheck.album == nil)
        let nothing = AVFoundationMetadataReader.gains(track: nil, trackPeak: nil, album: nil, albumPeak: nil, soundCheck: nil)
        #expect(nothing.track == nil && nothing.album == nil)
    }

    @Test func anAlbumOfTrackGainsPlaysAtTheirMean() {
        var intro = makeTrack("Speak to Me", album: "Dark Side", track: 1)
        intro.trackGain = 13.9
        var money = makeTrack("Money", album: "Dark Side", track: 6)
        money.trackGain = -2.8
        var untagged = makeTrack("Bonus", album: "Dark Side", track: 11)
        let album = [intro, money, untagged]
        let mean = ReplayGain.albumMean(album)
        #expect(abs(mean! - 5.55) < 0.001, "untagged tracks do not count")
        #expect(ReplayGain.albumMean([untagged]) == nil)

        // Listening to the album: every track at the mean, the dynamics
        // between them intact. The untagged one too.
        for track in album {
            #expect(abs(ReplayGain.playbackGain(track, asAlbum: true, albumMean: mean) - 5.55) < 0.001)
        }
        // Shuffle or All Tracks: each on its own.
        #expect(ReplayGain.playbackGain(intro, asAlbum: false, albumMean: mean) == 13.9)
        #expect(ReplayGain.playbackGain(untagged, asAlbum: false, albumMean: mean) == 0)

        // A real album gain beats the mean; alone it also serves the
        // track outside an album when the track gain is missing.
        money.albumGain = -7.25
        #expect(ReplayGain.playbackGain(money, asAlbum: true, albumMean: mean) == -7.25)
        untagged.albumGain = -7.25
        #expect(ReplayGain.playbackGain(untagged, asAlbum: false, albumMean: nil) == -7.25)
        #expect(ReplayGain.playbackGain(intro, asAlbum: true, albumMean: nil) == 13.9, "no mean, no album gain: the track's own")
    }

    @Test func factorIsTheUsualTwentyLog() {
        #expect(ReplayGain.factor(0) == 1)
        #expect(abs(ReplayGain.factor(-6.0206) - 0.5) < 0.001)
        #expect(abs(ReplayGain.factor(6.0206) - 2) < 0.001)
    }
}
