package com.xammer.mediaops.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class TranscodeJobRequest {
    private String sourceUrl;
    private String outputFormat = "HLS";
    private String resolution = "1080p";
    private String bitrate;
    private Integer fps;
    private boolean enableAdaptive = false;

    // Preset reference
    private Long presetId;

    // Codec selection (H264, H265, AV1, VP9)
    private String codec;

    // Priority (HIGH, NORMAL, LOW)
    private String priority;

    // Trimming
    private String startTime;
    private String endTime;

    // Subtitles (BURN_IN, SIDECAR)
    private String subtitleMode;

    // Watermark
    private String watermarkText;
    private String watermarkPosition;
    private Double watermarkOpacity;

    // Thumbnails
    private boolean thumbnailEnabled;
    private Integer thumbnailIntervalSeconds;
    private String thumbnailTimestamp;

    // Audio parameters
    private String audioBitrate;
    private Integer audioSampleRate;
    private Integer audioChannels;

    // Custom FFmpeg parameters
    private String customFfmpegParams;

    // Scheduling
    private Instant scheduledAt;

    // Encryption
    private boolean encryptionEnabled;
}
