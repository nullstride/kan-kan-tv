/* SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright (c) 2026 nullstride
 * Small JNI bridge to the pinned, dynamically linked FFmpeg library.
 */
#include "include/jni.h"
#include "ffmpeg-abi.h"

typedef struct Decoder {
    AVCodecContext *context;
    AVFrame *frame;
    AVPacket *packet;
} Decoder;

#define JNI_METHOD(name) Java_com_bjunicom_iptv_player_Mp2SoftwareDecoder_##name

static void release_decoder(Decoder *decoder) {
    if (!decoder) return;
    avcodec_free_context(&decoder->context);
    av_frame_free(&decoder->frame);
    av_packet_free(&decoder->packet);
    av_free(decoder);
}

JNIEXPORT jlong JNICALL JNI_METHOD(nativeCreateDecoder)(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    avcodec_register_all();
    /* AV_CODEC_ID_MP2 in the pinned FFmpeg codec_id.h. */
    AVCodec *codec = avcodec_find_decoder(0x15000);
    if (!codec) return 0;
    Decoder *decoder = av_mallocz(sizeof(*decoder));
    if (!decoder) return 0;
    decoder->context = avcodec_alloc_context3(codec);
    decoder->frame = av_frame_alloc();
    decoder->packet = av_packet_alloc();
    if (!decoder->context || !decoder->frame || !decoder->packet ||
            avcodec_open2(decoder->context, codec, NULL) < 0) {
        release_decoder(decoder);
        return 0;
    }
    return (jlong) (intptr_t) decoder;
}

static int16_t pcm_sample(AVFrame *frame, int sample, int channel, int channels) {
    const int format = frame->format;
    const int planar = format >= 5 && format <= 9;
    const int base = planar ? format - 5 : format;
    const int index = planar ? sample : sample * channels + channel;
    const uint8_t *data = planar ? frame->extended_data[channel] : frame->data[0];
    double value;
    switch (base) {
        case 0: return (int16_t) (((int) data[index] - 128) * 256);
        case 1: return ((const int16_t *) data)[index];
        case 2: return (int16_t) (((const int32_t *) data)[index] >> 16);
        case 3: value = ((const float *) data)[index] * 32768.0; break;
        case 4: value = ((const double *) data)[index] * 32768.0; break;
        default: return 0;
    }
    if (value != value) return 0;
    if (value >= 32767.0) return 32767;
    if (value <= -32768.0) return -32768;
    return (int16_t) value;
}

JNIEXPORT jint JNICALL JNI_METHOD(nativeDecodeFrame)(JNIEnv *env, jclass clazz,
        jlong handle, jbyteArray input, jint offset, jint length,
        jbyteArray output, jintArray metadata) {
    (void) clazz;
    Decoder *decoder = (Decoder *) (intptr_t) handle;
    if (!decoder || !input || !output || !metadata || offset < 0 || length <= 0 ||
            length > 16384 || (*env)->GetArrayLength(env, metadata) < 3) return 0;
    const jint input_size = (*env)->GetArrayLength(env, input);
    if (offset > input_size || length > input_size - offset) return 0;
    uint8_t *data = av_mallocz((size_t) length + 64);
    if (!data) return 0;
    (*env)->GetByteArrayRegion(env, input, offset, length, (jbyte *) data);
    if ((*env)->ExceptionCheck(env)) { av_free(data); return 0; }
    av_packet_unref(decoder->packet);
    av_frame_unref(decoder->frame);
    if (av_packet_from_data(decoder->packet, data, length) < 0) {
        av_free(data);
        return 0;
    }
    int got = 0;
    const int decoded = avcodec_decode_audio4(decoder->context, decoder->frame, &got, decoder->packet);
    if (decoded < 0 || !got) { av_packet_unref(decoder->packet); return 0; }
    const int channels = av_frame_get_channels(decoder->frame);
    const int samples = decoder->frame->nb_samples;
    if (channels < 1 || channels > 2 || samples < 1 || samples > 1152 ||
            decoder->frame->format < 0 || decoder->frame->format > 9) {
        av_packet_unref(decoder->packet);
        return 0;
    }
    const int bytes = channels * samples * 2;
    if ((*env)->GetArrayLength(env, output) < bytes) { av_packet_unref(decoder->packet); return 0; }
    /* MPEG Layer I/II has at most 1152 stereo samples per frame. */
    uint8_t pcm[1152 * 2 * 2];
    for (int sample = 0; sample < samples; sample++) {
        for (int channel = 0; channel < channels; channel++) {
            const int16_t value = pcm_sample(decoder->frame, sample, channel, channels);
            const int position = (sample * channels + channel) * 2;
            pcm[position] = (uint8_t) value;
            pcm[position + 1] = (uint8_t) ((uint16_t) value >> 8);
        }
    }
    const jint meta[3] = { av_frame_get_sample_rate(decoder->frame), channels, samples };
    (*env)->SetByteArrayRegion(env, output, 0, bytes, (const jbyte *) pcm);
    (*env)->SetIntArrayRegion(env, metadata, 0, 3, meta);
    av_packet_unref(decoder->packet);
    return (*env)->ExceptionCheck(env) ? 0 : bytes;
}

JNIEXPORT void JNICALL JNI_METHOD(nativeResetDecoder)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    Decoder *decoder = (Decoder *) (intptr_t) handle;
    if (decoder) avcodec_flush_buffers(decoder->context);
}

JNIEXPORT void JNICALL JNI_METHOD(nativeReleaseDecoder)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    release_decoder((Decoder *) (intptr_t) handle);
}
