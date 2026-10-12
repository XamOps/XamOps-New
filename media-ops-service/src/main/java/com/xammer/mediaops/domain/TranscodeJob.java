package com.xammer.mediaops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "media_transcode_job")
public class TranscodeJob {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "media_job_seq")
    @SequenceGenerator(name = "media_job_seq", sequenceName = "media_transcode_job_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false, length = 512)
    private String name;

    @Column(name = "source_url", nullable = false, columnDefinition = "TEXT")
    private String sourceUrl;

    // The S3 key the source was picked from, e.g. "DRSAKSHI/1.mp4". Drives the mirrored
    // output layout. Null when the source was a direct URL or an upload — sourceUrl is a
    // presigned link and cannot be relied on to reveal this.
    @Column(name = "source_key", columnDefinition = "TEXT")
    private String sourceKey;

    // Where the source object lives, so sourceUrl can be presigned again at the moment
    // FFmpeg starts. sourceUrl on its own is not enough: it expires (X-Amz-Expires) and,
    // when signed with STS session credentials, dies with that session — which is why a
    // job that waited in the queue, ran on a schedule, or was retried the next day used
    // to fail "HTTP error 403 Forbidden" permanently, however often it was retried.
    //
    // sourceAccountId + sourceBucket means "a bucket in the customer's connected AWS
    // account, read through that account's onboarding IAM role"; sourceS3ConfigId means
    // "a key-based connected bucket (user_s3_configs)". Both are null for pasted URLs and
    // local uploads, which have nothing to re-sign.
    @Column(name = "source_bucket", length = 255)
    private String sourceBucket;

    @Column(name = "source_region", length = 64)
    private String sourceRegion;

    @Column(name = "source_account_id", length = 64)
    private String sourceAccountId;

    @Column(name = "source_s3_config_id")
    private Long sourceS3ConfigId;

    @Enumerated(EnumType.STRING)
    @Column(name = "output_format", nullable = false, length = 20)
    private OutputFormat outputFormat;

    @Column(nullable = false, length = 20)
    private String resolution;

    @Column(length = 30)
    private String bitrate;

    @Column
    private Integer fps;

    @Column(name = "enable_adaptive")
    private boolean enableAdaptive = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status = JobStatus.QUEUED;

    @Column(nullable = false)
    private int progress = 0;

    @Column(name = "input_format", length = 20)
    private String inputFormat;

    @Column(name = "input_size_bytes")
    private Long inputSizeBytes;

    @Column(name = "output_size_bytes")
    private Long outputSizeBytes;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "output_path", columnDefinition = "TEXT")
    private String outputPath;

    @Column(name = "output_url", columnDefinition = "TEXT")
    private String outputUrl;

    // Optional customer-owned S3 destination. When set, the transcoded output is
    // uploaded to the referenced user_s3_configs bucket instead of XamOps storage,
    // and playback/download is served through the backend proxy (bucket stays private).
    @Column(name = "output_s3_config_id")
    private Long outputS3ConfigId;

    // Key prefix (no trailing slash) under which this job's files live in the
    // customer bucket, e.g. "xamops-mediaops/jobs/42".
    @Column(name = "output_bucket_prefix", columnDefinition = "TEXT")
    private String outputBucketPrefix;

    // Alternative customer destination: a bucket in the customer's connected AWS
    // account (the account selected in the sidebar), written via that account's
    // onboarding IAM role. Mutually exclusive with outputS3ConfigId.
    @Column(name = "output_account_id", length = 64)
    private String outputAccountId;

    @Column(name = "output_bucket", length = 255)
    private String outputBucket;

    @Column(name = "output_region", length = 64)
    private String outputRegion;

    @Column(name = "ffmpeg_command", columnDefinition = "TEXT")
    private String ffmpegCommand;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "aws_equivalent_cost", precision = 10, scale = 4)
    private BigDecimal awsEquivalentCost;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // ========================================================
    // Advanced transcoding fields
    // ========================================================

    // Codec selection (H264, H265, AV1, VP9)
    @Column(length = 20)
    private String codec;

    // Encoding speed = x264 preset (fast | veryfast | superfast | ultrafast).
    // Faster presets reduce quality at the same bitrate; veryfast is the balanced default.
    @Column(name = "encoding_speed", length = 20)
    private String encodingSpeed = "veryfast";

    // Job priority
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Priority priority = Priority.NORMAL;

    // Batch and preset references
    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "preset_id")
    private Long presetId;

    // Trimming
    @Column(name = "start_time", length = 20)
    private String startTime; // HH:MM:SS or seconds

    @Column(name = "end_time", length = 20)
    private String endTime;

    // Subtitles
    @Column(name = "subtitle_path", columnDefinition = "TEXT")
    private String subtitlePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "subtitle_mode", length = 10)
    private SubtitleMode subtitleMode;

    // Watermark
    @Column(name = "watermark_image_path", columnDefinition = "TEXT")
    private String watermarkImagePath;

    @Column(name = "watermark_text", length = 500)
    private String watermarkText;

    @Enumerated(EnumType.STRING)
    @Column(name = "watermark_position", length = 15)
    private WatermarkPosition watermarkPosition;

    @Column(name = "watermark_opacity")
    private Double watermarkOpacity;

    @Column(name = "watermark_scale")
    private Integer watermarkScale; // percentage of video width (default 15)

    @Column(name = "watermark_x")
    private Double watermarkX; // center X as % of video width (0-100), overrides position enum

    @Column(name = "watermark_y")
    private Double watermarkY; // center Y as % of video height (0-100), overrides position enum

    // Thumbnails
    @Column(name = "thumbnail_enabled")
    private boolean thumbnailEnabled = false;

    @Column(name = "thumbnail_interval_seconds")
    private Integer thumbnailIntervalSeconds;

    @Column(name = "thumbnail_timestamp", length = 20)
    private String thumbnailTimestamp; // single poster frame

    @Column(name = "thumbnail_paths", columnDefinition = "TEXT")
    private String thumbnailPaths; // JSON array of paths

    // Audio-only transcoding parameters
    @Column(name = "audio_bitrate", length = 20)
    private String audioBitrate;

    @Column(name = "audio_sample_rate")
    private Integer audioSampleRate;

    @Column(name = "audio_channels")
    private Integer audioChannels;

    // Custom FFmpeg parameters
    @Column(name = "custom_ffmpeg_params", columnDefinition = "TEXT")
    private String customFfmpegParams;

    // Scheduling
    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    // Encryption (AES-128 for HLS)
    @Column(name = "encryption_enabled")
    private boolean encryptionEnabled = false;

    @Column(name = "encryption_key_path", columnDefinition = "TEXT")
    private String encryptionKeyPath;

    @Column(name = "encryption_key_url", columnDefinition = "TEXT")
    private String encryptionKeyUrl;

    // ========================================================
    // Enums
    // ========================================================

    public enum JobStatus {
        QUEUED, PROCESSING, PAUSED, COMPLETED, FAILED, CANCELLED, SCHEDULED
    }

    public enum OutputFormat {
        HLS, DASH, MP4, WEBM, MKV, MOV, MP3, AAC, FLAC, OGG
    }

    public enum Priority {
        HIGH, NORMAL, LOW
    }

    public enum SubtitleMode {
        BURN_IN, SIDECAR
    }

    public enum WatermarkPosition {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        MIDDLE_LEFT, CENTER, MIDDLE_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }
}
