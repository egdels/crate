// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

// minimp3 (CC0) and dr_flac (public domain) behind one small interface,
// see CrateDecoders.h. The two headers are the Android app's
// (android/app/src/main/cpp), one copy in the repository; the readers
// around them follow that app's mp3.c and flac.c.
//
// Files are read in pieces with pread, not mapped: a mapped file that
// shrinks while it is read (a sync in progress) kills the process.

#include "CrateDecoders.h"

#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define MINIMP3_IMPLEMENTATION
#include "minimp3.h"

#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO
#define DR_FLAC_NO_OGG
// One pread per 64 KB instead of per 4 KB: the file may be on a share.
#define DR_FLAC_BUFFER_SIZE 65536
#include "dr_flac.h"

#define BUFFER_BYTES (256 * 1024)
// What minimp3 wants to see ahead of a frame to be sure of it.
#define LOOKAHEAD_BYTES (16 * 1024)

struct crate_decoder {
    int fd;
    off_t offset;        // of the next byte to read (MP3), the position (FLAC)
    off_t size;          // of the file (FLAC)
    int ended;           // nothing more to read
    size_t length, position;
    int hz, channels;
    drflac *flac;        // NULL for MP3
    mp3dec_t mp3;
    uint8_t *buffer;     // BUFFER_BYTES, MP3 only
};

// MP3

static void fill(crate_decoder *r) {
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

crate_decoder *crate_mp3_open(int fd) {
    uint8_t head[12];
    off_t start = 0;
    if (pread(fd, head, 10, 0) != 10) return NULL;
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
            || memcmp(head, "OggS", 4) == 0 || memcmp(head, "fLaC", 4) == 0) return NULL;
    }
    crate_decoder *r = calloc(1, sizeof(crate_decoder));
    if (!r) return NULL;
    r->buffer = malloc(BUFFER_BYTES);
    if (!r->buffer) { free(r); return NULL; }
    r->fd = fd;
    r->offset = start;
    mp3dec_init(&r->mp3);
    return r;
}

// Frames whose rate or channel count differ from the first frame's are
// left out.
static int read_mp3(crate_decoder *r, int16_t *out, int capacity) {
    int filled = 0;
    while (capacity - filled >= MINIMP3_MAX_SAMPLES_PER_FRAME) {
        if (r->length - r->position < LOOKAHEAD_BYTES && !r->ended) fill(r);
        if (r->length == r->position) break;
        mp3dec_frame_info_t info;
        int samples = mp3dec_decode_frame(&r->mp3, r->buffer + r->position, (int) (r->length - r->position), out + filled, &info);
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

// FLAC

static size_t on_read(void *user, void *out, size_t bytes) {
    crate_decoder *r = user;
    size_t done = 0;
    while (done < bytes) {
        ssize_t n = pread(r->fd, (char *) out + done, bytes - done, r->offset);
        if (n <= 0) break;
        done += (size_t) n;
        r->offset += n;
    }
    return done;
}

static drflac_bool32 on_seek(void *user, int offset, drflac_seek_origin origin) {
    crate_decoder *r = user;
    off_t target = origin == DRFLAC_SEEK_SET ? offset : origin == DRFLAC_SEEK_CUR ? r->offset + offset : r->size + offset;
    if (target < 0 || target > r->size) return DRFLAC_FALSE;
    r->offset = target;
    return DRFLAC_TRUE;
}

static drflac_bool32 on_tell(void *user, drflac_int64 *cursor) {
    *cursor = ((crate_decoder *) user)->offset;
    return DRFLAC_TRUE;
}

crate_decoder *crate_flac_open(int fd) {
    struct stat st;
    if (fstat(fd, &st) != 0) return NULL;
    crate_decoder *r = calloc(1, sizeof(crate_decoder));
    if (!r) return NULL;
    r->fd = fd;
    r->size = st.st_size;
    r->flac = drflac_open(on_read, on_seek, on_tell, r, NULL);
    if (!r->flac || r->flac->channels == 0) {
        if (r->flac) drflac_close(r->flac);
        free(r);
        return NULL;
    }
    r->hz = (int) r->flac->sampleRate;
    r->channels = r->flac->channels;
    return r;
}

int crate_decoder_read(crate_decoder *r, int16_t *out, int capacity) {
    if (!r->flac) return read_mp3(r, out, capacity);
    drflac_uint64 frames = drflac_read_pcm_frames_s16(r->flac, (drflac_uint64) (capacity / r->channels), out);
    return (int) (frames * (drflac_uint64) r->channels);
}

int crate_decoder_rate(const crate_decoder *r) { return r->hz; }

int crate_decoder_channels(const crate_decoder *r) { return r->channels; }

void crate_decoder_close(crate_decoder *r) {
    if (!r) return;
    if (r->flac) drflac_close(r->flac);
    free(r->buffer);
    free(r);
}
