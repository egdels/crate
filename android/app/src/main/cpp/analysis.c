// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

// The per-sample work of the loudness analysis, on the interleaved PCM
// as a decoder left it: the K-weighting filter with the energy per
// 100 ms step and the sample peak (what LoudnessMeter.add does), and
// peak and energy per 10 ms block for the outline (WaveformBuilder.add).
// Behind de.schliweb.crate.library.NativeAnalysis. Everything after the
// samples stays in Kotlin: the filter's design comes in as coefficients,
// the steps go back to the meter for the blocks, the gates and the
// histogram, the blocks to the waveform builder.
//
// The meter's arithmetic is the Kotlin one operation for operation
// (built with -ffp-contract=off, so no fused multiply-add), and its
// results are the same to the bit; two channels go through the filter
// in one loop, which is where the time is won: each channel is a chain
// of dependent operations, and two chains run side by side. The
// outline's sums are taken in another order (as integers for 16-bit
// samples), so they can differ in the last bit of a double.

#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    int channels;
    double k[10];      // b0 b1 b2 a1 a2 of the shelf, then of the high-pass
    double *state;     // per channel: x1 x2 y1 y2 hx1 hx2 hy1 hy2
    int step_frames, step_filled;
    double *step_sums; // per channel, the step being filled
    float peak;
    int block_frames, block_filled;
    float block_peak;
    double block_squares;
    // What Kotlin has not fetched yet.
    double *steps;     // step_count times `channels` sums
    int step_count, step_cap;
    float *peaks;
    double *squares;
    int block_count, block_cap;
} analysis;

#define FILTER(x, s, sum) do { \
    double y = b0 * (x) + b1 * (s)[0] + b2 * (s)[1] - a1 * (s)[2] - a2 * (s)[3]; \
    (s)[1] = (s)[0]; (s)[0] = (x); (s)[3] = (s)[2]; (s)[2] = y; \
    double z = hb0 * y + hb1 * (s)[4] + hb2 * (s)[5] - ha1 * (s)[6] - ha2 * (s)[7]; \
    (s)[5] = (s)[4]; (s)[4] = y; (s)[7] = (s)[6]; (s)[6] = z; \
    (sum) += z * z; \
} while (0)

#define COEFFICIENTS \
    const double b0 = a->k[0], b1 = a->k[1], b2 = a->k[2], a1 = a->k[3], a2 = a->k[4]; \
    const double hb0 = a->k[5], hb1 = a->k[6], hb2 = a->k[7], ha1 = a->k[8], ha2 = a->k[9]

// One function per sample type. `count` frames of one step: each
// channel's peak and filtered energy, the energy added to the step's
// sum as one value, as the Kotlin meter adds what KFilter.energy gave.
#define METER(name, type, TO_FLOAT) \
static void meter_##name(analysis *a, const type *in, int count) { \
    COEFFICIENTS; \
    const int channels = a->channels; \
    float peak = a->peak; \
    if (channels == 2) { \
        double l[8], r[8], suml = 0, sumr = 0; \
        memcpy(l, a->state, sizeof l); \
        memcpy(r, a->state + 8, sizeof r); \
        for (int i = 0; i < count; i++) { \
            float fl = TO_FLOAT(in[2 * i]), fr = TO_FLOAT(in[2 * i + 1]); \
            float ml = fabsf(fl), mr = fabsf(fr); \
            if (ml > peak) peak = ml; \
            if (mr > peak) peak = mr; \
            double xl = fl, xr = fr; \
            FILTER(xl, l, suml); \
            FILTER(xr, r, sumr); \
        } \
        memcpy(a->state, l, sizeof l); \
        memcpy(a->state + 8, r, sizeof r); \
        a->step_sums[0] += suml; \
        a->step_sums[1] += sumr; \
    } else { \
        for (int c = 0; c < channels; c++) { \
            double s[8], sum = 0; \
            memcpy(s, a->state + 8 * c, sizeof s); \
            for (int i = 0; i < count; i++) { \
                float f = TO_FLOAT(in[(size_t) i * channels + c]); \
                float m = fabsf(f); \
                if (m > peak) peak = m; \
                double x = f; \
                FILTER(x, s, sum); \
            } \
            memcpy(a->state + 8 * c, s, sizeof s); \
            a->step_sums[c] += sum; \
        } \
    } \
    a->peak = peak; \
}

#define S16_TO_FLOAT(v) ((float) (v) / 32768.0f)
#define FLOAT_TO_FLOAT(v) (v)
METER(s16, int16_t, S16_TO_FLOAT)
METER(f32, float, FLOAT_TO_FLOAT)

// `count` samples (all channels) of one block of the outline. 16-bit
// samples are summed as integers, exactly: a block is far too short to
// overflow 64 bits.
static void outline_s16(analysis *a, const int16_t *in, int count) {
    int64_t sum = 0;
    int32_t top = 0;
    for (int i = 0; i < count; i++) {
        int32_t v = in[i];
        int32_t m = v < 0 ? -v : v;
        if (m > top) top = m;
        sum += (int64_t) (v * v);
    }
    float peak = (float) top / 32768.0f;
    if (peak > a->block_peak) a->block_peak = peak;
    a->block_squares += (double) sum / 1073741824.0;
}

static void outline_f32(analysis *a, const float *in, int count) {
    double s0 = 0, s1 = 0, s2 = 0, s3 = 0;
    float peak = a->block_peak;
    int i = 0;
    for (; i + 4 <= count; i += 4) {
        float m0 = fabsf(in[i]), m1 = fabsf(in[i + 1]), m2 = fabsf(in[i + 2]), m3 = fabsf(in[i + 3]);
        if (m0 > peak) peak = m0;
        if (m1 > peak) peak = m1;
        if (m2 > peak) peak = m2;
        if (m3 > peak) peak = m3;
        s0 += (double) in[i] * in[i];
        s1 += (double) in[i + 1] * in[i + 1];
        s2 += (double) in[i + 2] * in[i + 2];
        s3 += (double) in[i + 3] * in[i + 3];
    }
    for (; i < count; i++) {
        float m = fabsf(in[i]);
        if (m > peak) peak = m;
        s0 += (double) in[i] * in[i];
    }
    a->block_peak = peak;
    a->block_squares += (s0 + s1) + (s2 + s3);
}

static int step_done(analysis *a) {
    if (a->step_count == a->step_cap) {
        int cap = a->step_cap ? a->step_cap * 2 : 64;
        double *grown = realloc(a->steps, sizeof(double) * (size_t) cap * (size_t) a->channels);
        if (!grown) return 0;
        a->steps = grown;
        a->step_cap = cap;
    }
    memcpy(a->steps + (size_t) a->step_count * a->channels, a->step_sums, sizeof(double) * (size_t) a->channels);
    memset(a->step_sums, 0, sizeof(double) * (size_t) a->channels);
    a->step_count++;
    a->step_filled = 0;
    return 1;
}

static int block_done(analysis *a) {
    if (a->block_count == a->block_cap) {
        int cap = a->block_cap ? a->block_cap * 2 : 512;
        float *peaks = realloc(a->peaks, sizeof(float) * (size_t) cap);
        if (peaks) a->peaks = peaks;
        double *squares = realloc(a->squares, sizeof(double) * (size_t) cap);
        if (squares) a->squares = squares;
        if (!peaks || !squares) return 0;
        a->block_cap = cap;
    }
    a->peaks[a->block_count] = a->block_peak;
    a->squares[a->block_count] = a->block_squares;
    a->block_count++;
    a->block_peak = 0;
    a->block_squares = 0;
    a->block_filled = 0;
    return 1;
}

static void release(analysis *a) {
    if (!a) return;
    free(a->state);
    free(a->step_sums);
    free(a->steps);
    free(a->peaks);
    free(a->squares);
    free(a);
}

// A handle for the other functions, or 0. `coefficients`: the ten of
// LoudnessMeter.filterCoefficients; the frame counts are the meter's
// step and the builder's block.
JNIEXPORT jlong JNICALL Java_de_schliweb_crate_library_NativeAnalysis_open(
        JNIEnv *env, jobject thiz, jint channels, jdoubleArray coefficients, jint step_frames, jint block_frames) {
    if (channels <= 0 || channels > 64 || step_frames <= 0 || block_frames <= 0) return 0;
    if ((*env)->GetArrayLength(env, coefficients) != 10) return 0;
    analysis *a = calloc(1, sizeof(analysis));
    if (!a) return 0;
    a->channels = channels;
    a->step_frames = step_frames;
    a->block_frames = block_frames;
    (*env)->GetDoubleArrayRegion(env, coefficients, 0, 10, a->k);
    a->state = calloc((size_t) channels * 8, sizeof(double));
    a->step_sums = calloc((size_t) channels, sizeof(double));
    if (!a->state || !a->step_sums) { release(a); return 0; }
    return (jlong) (intptr_t) a;
}

// `size` bytes of interleaved PCM in native order at `offset` of the
// direct buffer: 16-bit, or 32-bit floats. False when memory ran out.
JNIEXPORT jboolean JNICALL Java_de_schliweb_crate_library_NativeAnalysis_add(
        JNIEnv *env, jobject thiz, jlong handle, jobject buffer, jint offset, jint size, jboolean floats) {
    analysis *a = (analysis *) (intptr_t) handle;
    const char *base = (*env)->GetDirectBufferAddress(env, buffer);
    if (!base || offset < 0 || size < 0) return JNI_FALSE;
    base += offset;
    const int channels = a->channels;
    const int width = floats ? 4 : 2;
    const int frames = size / width / channels;
    for (int at = 0; at < frames;) {
        int count = frames - at;
        if (count > a->step_frames - a->step_filled) count = a->step_frames - a->step_filled;
        const char *in = base + (size_t) at * channels * width;
        if (floats) meter_f32(a, (const float *) in, count);
        else meter_s16(a, (const int16_t *) in, count);
        a->step_filled += count;
        at += count;
        if (a->step_filled == a->step_frames && !step_done(a)) return JNI_FALSE;
    }
    for (int at = 0; at < frames;) {
        int count = frames - at;
        if (count > a->block_frames - a->block_filled) count = a->block_frames - a->block_filled;
        const char *in = base + (size_t) at * channels * width;
        if (floats) outline_f32(a, (const float *) in, count * channels);
        else outline_s16(a, (const int16_t *) in, count * channels);
        a->block_filled += count;
        at += count;
        if (a->block_filled == a->block_frames && !block_done(a)) return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Moves finished steps and blocks into the arrays, as many as fit, the
// oldest first; (steps << 32) | blocks, 0 when nothing was left.
JNIEXPORT jlong JNICALL Java_de_schliweb_crate_library_NativeAnalysis_drain(
        JNIEnv *env, jobject thiz, jlong handle, jdoubleArray steps, jfloatArray peaks, jdoubleArray squares) {
    analysis *a = (analysis *) (intptr_t) handle;
    const size_t channels = (size_t) a->channels;
    int step_room = (*env)->GetArrayLength(env, steps) / a->channels;
    int block_room = (*env)->GetArrayLength(env, peaks);
    int squares_room = (*env)->GetArrayLength(env, squares);
    if (squares_room < block_room) block_room = squares_room;
    int s = a->step_count < step_room ? a->step_count : step_room;
    int b = a->block_count < block_room ? a->block_count : block_room;
    if (s > 0) {
        (*env)->SetDoubleArrayRegion(env, steps, 0, (jsize) (s * a->channels), a->steps);
        a->step_count -= s;
        memmove(a->steps, a->steps + (size_t) s * channels, sizeof(double) * (size_t) a->step_count * channels);
    }
    if (b > 0) {
        (*env)->SetFloatArrayRegion(env, peaks, 0, b, a->peaks);
        (*env)->SetDoubleArrayRegion(env, squares, 0, b, a->squares);
        a->block_count -= b;
        memmove(a->peaks, a->peaks + b, sizeof(float) * (size_t) a->block_count);
        memmove(a->squares, a->squares + b, sizeof(double) * (size_t) a->block_count);
    }
    return ((jlong) s << 32) | (jlong) b;
}

// At the end of the file: the frames of the block that was not full
// yet, with its peak and energy in the arrays' first elements; 0 when
// the audio ended on a block's edge. A step that was not full is
// dropped, as the meter drops it.
JNIEXPORT jint JNICALL Java_de_schliweb_crate_library_NativeAnalysis_finish(
        JNIEnv *env, jobject thiz, jlong handle, jfloatArray peak, jdoubleArray squares) {
    analysis *a = (analysis *) (intptr_t) handle;
    int frames = a->block_filled;
    if (frames > 0) {
        jfloat p = a->block_peak;
        jdouble s = a->block_squares;
        (*env)->SetFloatArrayRegion(env, peak, 0, 1, &p);
        (*env)->SetDoubleArrayRegion(env, squares, 0, 1, &s);
        a->block_peak = 0;
        a->block_squares = 0;
        a->block_filled = 0;
    }
    return frames;
}

// Floats that are no numbers, or infinite, become silence, as the
// Kotlin WAV reader makes them: `size` bytes from the buffer's start.
JNIEXPORT void JNICALL Java_de_schliweb_crate_library_NativeAnalysis_zeroNonFinite(
        JNIEnv *env, jobject thiz, jobject buffer, jint size) {
    float *samples = (*env)->GetDirectBufferAddress(env, buffer);
    if (!samples) return;
    for (jint i = 0; i < size / 4; i++) {
        if (!isfinite(samples[i])) samples[i] = 0;
    }
}

// The largest sample so far, as a factor.
JNIEXPORT jfloat JNICALL Java_de_schliweb_crate_library_NativeAnalysis_peak(JNIEnv *env, jobject thiz, jlong handle) {
    return ((analysis *) (intptr_t) handle)->peak;
}

JNIEXPORT void JNICALL Java_de_schliweb_crate_library_NativeAnalysis_close(JNIEnv *env, jobject thiz, jlong handle) {
    release((analysis *) (intptr_t) handle);
}
