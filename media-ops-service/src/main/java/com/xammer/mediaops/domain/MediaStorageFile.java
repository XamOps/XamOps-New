package com.xammer.mediaops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "media_storage_file")
public class MediaStorageFile {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "media_file_seq")
    @SequenceGenerator(name = "media_file_seq", sequenceName = "media_storage_file_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false, length = 512)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 20)
    private FileType fileType;

    @Column(nullable = false, length = 20)
    private String format;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes = 0;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(length = 30)
    private String resolution;

    @Column(name = "file_path", nullable = false, columnDefinition = "TEXT")
    private String filePath;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum FileType { VIDEO, AUDIO, IMAGE, HLS }
}
