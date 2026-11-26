// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

// MP3 decoding in the app's own process, for the loudness analysis and
// the waveform: minimp3 (minimp3.h, CC0) behind the five functions of
// de.schliweb.crate.library.Mp3Decoder. The platform's decoder lives in
// another process and costs a round trip per buffer and, through the
// extractor, one per frame; measured on a Pixel 7a it ran at 82 times
// real time per file, this at 428.
//
// The file is read in pieces with pread, not mapped: a mapped file that
// shrinks while it is read (a sync in progress) kills the process.

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define MINIMP3_IMPLEMENTATION
#include "minimp3.h"

#define BUFFER_BYTES (256 * 1024)
// What minimp3 wants to see ahead of a frame to be sure of it.
#define LOOKAHEAD_BYTES (16 * 1024)

typedef struct {
    int fd;
    off_t offset;        // of the next byte to read from the file
    int ended;           // nothing more to read
    size_t length, position;
    int hz, channels;
    mp3dec_t decoder;
    uint8_t buffer[BUFFER_BYTES];
} reader;

static void fill(reader *r) {
    memmove(r->buffer, r->buffer + r->position, r->length - r->position);
    r->length -= r->position;
    r->position = 0;
    while (!r->ended && r->length < BUFFER_BYTES) {
        ssize_t n = pread(r->fd, r->buffer + r->length, BUFFER_BYTES - r->length, r->offset);
        if (n <= 0) { r->ended = 1; break; }
        r->length += (size_t) n;
        r->offset += n;
    }
}

// 0 when the descriptor does not hold an MP3 stream this can read: the
// caller then asks the platform. The descriptor stays the caller's.
JNIEXPORT jlong JNICALL Java_de_schliweb_crate_library_Mp3Decoder_open(JNIEnv *env, jobject thiz, jint fd) {
    uint8_t head[12];
    off_t start = 0;
    if (pread(fd, head, 10, 0) != 10) return 0;
    // An ID3v2 tag in front: a cover in it may look like frames.
    if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
        start = ((off_t) (head[6] & 0x7f) << 21) | ((off_t) (head[7] & 0x7f) << 14)
            | ((off_t) (head[8] & 0x7f) << 7) | (off_t) (head[9] & 0x7f);
        start += 10 + ((head[5] & 0x10) ? 10 : 0);
    }
    // Other containers under the name .mp3 (an MP4 with an ID3 tag glued
    // in front exists): their bytes can pass for a few frames.
    if (pread(fd, head, 12, start) == 12) {
        if (memcmp(head + 4, "ftyp", 4) == 0 || memcmp(head, "RIFF", 4) == 0
            || memcmp(head, "OggS", 4) == 0 || memcmp(head, "fLaC", 4) == 0) return 0;
    }
    reader *r = calloc(1, sizeof(reader));
    if (!r) return 0;
    r->fd = fd;
    r->offset = start;
    mp3dec_init(&r->decoder);
    return (jlong) (intptr_t) r;
}

// Decodes whole frames into the direct buffer `out` (interleaved 16-bit
// samples, native order) until it is full; the number of samples, 0 at
// the end of the file. Frames whose rate or channel count differ from
// the first frame's are left out.
JNIEXPORT jint JNICALL Java_de_schliweb_crate_library_Mp3Decoder_read(JNIEnv *env, jobject thiz, jlong handle, jobject out) {
    reader *r = (reader *) (intptr_t) handle;
    mp3d_sample_t *pcm = (*env)->GetDirectBufferAddress(env, out);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, out) / (jlong) sizeof(mp3d_sample_t);
    if (!pcm) return 0;
    jint filled = 0;
    while (capacity - filled >= MINIMP3_MAX_SAMPLES_PER_FRAME) {
        if (r->length - r->position < LOOKAHEAD_BYTES && !r->ended) fill(r);
        if (r->length == r->position) break;
        mp3dec_frame_info_t info;
        int samples = mp3dec_decode_frame(&r->decoder, r->buffer + r->position, (int) (r->length - r->position), pcm + filled, &info);
        // No bytes used: a cut last frame, or nothing that is a frame.
        if (info.frame_bytes <= 0) { r->position = r->length; r->ended = 1; break; }
        r->position += (size_t) info.frame_bytes;
        if (samples <= 0) continue;
        if (r->hz == 0) { r->hz = info.hz; r->channels = info.channels; }
        else if (info.hz != r->hz || info.channels != r->channels) continue;
        filled += samples * info.channels;
    }
    return filled;
}

JNIEXPORT jint JNICALL Java_de_schliweb_crate_library_Mp3Decoder_rate(JNIEnv *env, jobject thiz, jlong handle) {
    return ((reader *) (intptr_t) handle)->hz;
}

JNIEXPORT jint JNICALL Java_de_schliweb_crate_library_Mp3Decoder_channels(JNIEnv *env, jobject thiz, jlong handle) {
    return ((reader *) (intptr_t) handle)->channels;
}

JNIEXPORT void JNICALL Java_de_schliweb_crate_library_Mp3Decoder_close(JNIEnv *env, jobject thiz, jlong handle) {
    free((reader *) (intptr_t) handle);
}
