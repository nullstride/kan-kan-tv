/* SPDX-License-Identifier: LGPL-2.1-or-later
 * ABI declarations for FFmpeg ff3.4--ijk0.8.7--20180103--001.
 * AVFrame prefix follows libavutil/frame.h in the corresponding source archive.
 * This header must be updated together with the pinned FFmpeg version.
 */
#pragma once
#include <stddef.h>
#include <stdint.h>

typedef struct AVCodec AVCodec;
typedef struct AVCodecContext AVCodecContext;
typedef struct AVPacket AVPacket;
typedef struct AVFrame {
    uint8_t *data[8];
    int linesize[8];
    uint8_t **extended_data;
    int width, height;
    int nb_samples;
    int format;
} AVFrame;

void avcodec_register_all(void);
AVCodec *avcodec_find_decoder(int codec_id);
AVCodecContext *avcodec_alloc_context3(const AVCodec *codec);
int avcodec_open2(AVCodecContext *context, const AVCodec *codec, void *options);
void avcodec_free_context(AVCodecContext **context);
int avcodec_decode_audio4(AVCodecContext *context, AVFrame *frame, int *got, const AVPacket *packet);
void avcodec_flush_buffers(AVCodecContext *context);
AVFrame *av_frame_alloc(void);
void av_frame_free(AVFrame **frame);
void av_frame_unref(AVFrame *frame);
int av_frame_get_channels(const AVFrame *frame);
int av_frame_get_sample_rate(const AVFrame *frame);
AVPacket *av_packet_alloc(void);
void av_packet_free(AVPacket **packet);
int av_packet_from_data(AVPacket *packet, uint8_t *data, int size);
void av_packet_unref(AVPacket *packet);
void *av_mallocz(size_t size);
void av_free(void *memory);
