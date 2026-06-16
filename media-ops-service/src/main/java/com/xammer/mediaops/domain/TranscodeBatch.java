package com.xammer.mediaops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "media_transcode_batch")
public class TranscodeBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "media_batch_seq")
    @SequenceGenerator(name = "media_batch_seq", sequenceName = "media_transcode_batch_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(length = 255)
    private String name;

    @Column(name = "total_jobs", nullable = false)
    private int totalJobs;

    @Column(name = "completed_jobs")
    private int completedJobs = 0;

    @Column(name = "failed_jobs")
    private int failedJobs = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BatchStatus status = BatchStatus.PROCESSING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public enum BatchStatus { PROCESSING, COMPLETED, CANCELLED, PARTIALLY_FAILED }
}
