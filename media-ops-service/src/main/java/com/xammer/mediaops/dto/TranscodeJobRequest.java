package com.xammer.mediaops.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class TranscodeJobRequest {
    private String sourceUrl;

    // Set by the S3 browser: the key the source was picked from, e.g. "DRSAKSHI/1.mp4".
    // Output is written under a mirror of this path so results are identifiable.
    private String sourceKey;

    private String outputFormat = "HLS";
    private String resolution = "1080p";
    private String bitrate;
    private Integer fps;
    private boolean enableAdaptive = false;

    // Encoding speed = x264 preset: fast | veryfast (default) | superfast | ultrafast
    private String encodingSpeed;

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
    private String subtitlePath;
    private String subtitleMode;

    // Watermark
    private String watermarkText;
    private String watermarkImagePath;
    private String watermarkPosition;
    private Double watermarkOpacity;
    private Integer watermarkScale; // % of video width (default 15)
    private Double watermarkX;      // center X % (0-100), overrides position enum
    private Double watermarkY;      // center Y % (0-100), overrides position enum

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

    // Optional: id of a connected customer S3 bucket (user_s3_configs) to store the
    // transcoded output in. When null, output goes to XamOps storage as before.
    private Long outputS3ConfigId;

    // Optional: store output in a bucket of the customer's connected AWS account
    // (the account selected in the sidebar), written via that account's IAM role.
    // outputAccountId is the AWS account number; outputBucket is the chosen bucket.
    private String outputAccountId;
    private String outputBucket;
}
