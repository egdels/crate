// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

// FLAC decoding in the app's own process, for the loudness analysis and
// the waveform: dr_flac (dr_flac.h, public domain) behind the five
// functions of de.schliweb.crate.library.FlacDecoder, the twin of mp3.c.
// The platform decodes FLAC inside its extractor process and hands the
// PCM over buffer by buffer.

#include <jni.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>

#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO
#define DR_FLAC_NO_OGG
// One pread per 64 KB instead of per 4 KB: the descriptor is a
// document provider's.
#define DR_FLAC_BUFFER_SIZE 65536
#include "dr_flac.h"

typedef struct {
    int fd;
    off_t position, size;
    drflac *flac;
} reader;

static size_t on_read(void *user, void *out, size_t bytes) {
    reader *r = user;
    size_t done = 0;
    while (done < bytes) {
        ssize_t n = pread(r->fd, (char *) out + done, bytes - done, r->position);
        if (n <= 0) break;
        done += (size_t) n;
        r->position += n;
    }
    return done;
}

static drflac_bool32 on_seek(void *user, int offset, drflac_seek_origin origin) {
    reader *r = user;
    off_t target = origin == DRFLAC_SEEK_SET ? offset : origin == DRFLAC_SEEK_CUR ? r->position + offset : r->size + offset;
    if (target < 0 || target > r->size) return DRFLAC_FALSE;
    r->position = target;
    return DRFLAC_TRUE;
}

static drflac_bool32 on_tell(void *user, drflac_int64 *cursor) {
    *cursor = ((reader *) user)->position;
    return DRFLAC_TRUE;
}

// 0 when the descriptor does not hold a FLAC stream this can read: the
// caller then asks the platform. The descriptor stays the caller's.
JNIEXPORT jlong JNICALL Java_de_schliweb_crate_library_FlacDecoder_open(JNIEnv *env, jobject thiz, jint fd) {
    struct stat st;
    if (fstat(fd, &st) != 0) return 0;
    reader *r = calloc(1, sizeof(reader));
    if (!r) return 0;
    r->fd = fd;
    r->size = st.st_size;
    r->flac = drflac_open(on_read, on_seek, on_tell, r, NULL);
    if (!r->flac) { free(r); return 0; }
    return (jlong) (intptr_t) r;
}

// Fills the direct buffer `out` with whole frames of interleaved 16-bit
// samples in native order; the number of samples, 0 at the end.
JNIEXPORT jint JNICALL Java_de_schliweb_crate_library_FlacDecoder_read(JNIEnv *env, jobject thiz, jlong handle, jobject out) {
    reader *r = (reader *) (intptr_t) handle;
    drflac_int16 *pcm = (*env)->GetDirectBufferAddress(env, out);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, out) / (jlong) sizeof(drflac_int16);
    if (!pcm || r->flac->channels == 0) return 0;
    drflac_uint64 frames = drflac_read_pcm_frames_s16(r->flac, (drflac_uint64) (capacity / r->flac->channels), pcm);
    return (jint) (frames * r->flac->channels);
}

JNIEXPORT jint JNICALL Java_de_schliweb_crate_library_FlacDecoder_rate(JNIEnv *env, jobject thiz, jlong handle) {
    return (jint) ((reader *) (intptr_t) handle)->flac->sampleRate;
}

JNIEXPORT jint JNICALL Java_de_schliweb_crate_library_FlacDecoder_channels(JNIEnv *env, jobject thiz, jlong handle) {
    return ((reader *) (intptr_t) handle)->flac->channels;
}

JNIEXPORT void JNICALL Java_de_schliweb_crate_library_FlacDecoder_close(JNIEnv *env, jobject thiz, jlong handle) {
    reader *r = (reader *) (intptr_t) handle;
    drflac_close(r->flac);
    free(r);
}
