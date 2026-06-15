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

    public enum JobStatus { QUEUED, PROCESSING, COMPLETED, FAILED, CANCELLED }

    public enum OutputFormat { HLS, DASH, MP4, WEBM, MKV, MOV }
}
