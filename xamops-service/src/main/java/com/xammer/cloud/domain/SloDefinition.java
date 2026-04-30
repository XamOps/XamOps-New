package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "slo_definition")
public class SloDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "slo_def_seq")
    @SequenceGenerator(name = "slo_def_seq", sequenceName = "slo_definition_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false)
    private String entity;

    @Column(nullable = false)
    private String signal;

    @Column(name = "stream_name", nullable = false)
    private String streamName;

    @Column(name = "query_template", nullable = false, columnDefinition = "TEXT")
    private String queryTemplate;

    @Column(name = "window_seconds", nullable = false)
    private int windowSeconds = 300;

    @Column(name = "zscore_threshold", nullable = false)
    private double zscoreThreshold = 3.0;

    @Column(name = "cooldown_seconds", nullable = false)
    private int cooldownSeconds = 600;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
