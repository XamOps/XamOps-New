package com.xammer.mediaops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "media_transcode_preset")
public class TranscodePreset {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "media_preset_seq")
    @SequenceGenerator(name = "media_preset_seq", sequenceName = "media_transcode_preset_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "output_format", length = 20)
    private String outputFormat;

    @Column(length = 20)
    private String resolution;

    @Column(length = 30)
    private String bitrate;

    @Column
    private Integer fps;

    @Column(length = 20)
    private String codec;

    @Column(name = "audio_bitrate", length = 20)
    private String audioBitrate;

    @Column(name = "custom_ffmpeg_params", columnDefinition = "TEXT")
    private String customFfmpegParams;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;
}
