package com.xammer.mediaops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
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
        QUEUED, PROCESSING, COMPLETED, FAILED, CANCELLED, SCHEDULED
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
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER
    }
}
