package com.xammer.mediaops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "media_stream")
public class MediaStream {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "media_stream_seq")
    @SequenceGenerator(name = "media_stream_seq", sequenceName = "media_stream_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(name = "stream_key", nullable = false, unique = true, length = 128)
    private String streamKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StreamStatus status = StreamStatus.IDLE;

    @Column(nullable = false, length = 20)
    private String resolution = "1080p";

    @Column(length = 30)
    private String bitrate;

    @Column(name = "rtmp_url", columnDefinition = "TEXT")
    private String rtmpUrl;

    @Column(name = "hls_url", columnDefinition = "TEXT")
    private String hlsUrl;

    @Column(name = "hls_output_dir", columnDefinition = "TEXT")
    private String hlsOutputDir;

    @Column(nullable = false)
    private int viewers = 0;

    @Column(name = "bandwidth_bytes", nullable = false)
    private long bandwidthBytes = 0;

    @Column(name = "uptime_seconds", nullable = false)
    private int uptimeSeconds = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    public enum StreamStatus { LIVE, IDLE, ERROR }
}
