// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

// MP3 and FLAC decoding for the loudness analysis: minimp3 and dr_flac,
// which read a file two to three times as fast as AVFoundation does.
// Playback stays with AVFoundation.

#ifndef CRATE_DECODERS_H
#define CRATE_DECODERS_H

#include <stdint.h>

typedef struct crate_decoder crate_decoder;

/// A decoder for the MP3 stream behind the descriptor, or NULL when it
/// holds none this reads: the caller asks AVFoundation then. The
/// descriptor stays the caller's and has to stay open.
crate_decoder *crate_mp3_open(int fd);

/// The same for FLAC.
crate_decoder *crate_flac_open(int fd);

/// Fills `out` with whole frames of interleaved 16-bit samples, at most
/// `capacity` samples (at least 2304); the number of samples, 0 at the
/// end of the file.
int crate_decoder_read(crate_decoder *decoder, int16_t *out, int capacity);

/// Known after the first read that returned samples.
int crate_decoder_rate(const crate_decoder *decoder);
int crate_decoder_channels(const crate_decoder *decoder);

void crate_decoder_close(crate_decoder *decoder);

#endif
