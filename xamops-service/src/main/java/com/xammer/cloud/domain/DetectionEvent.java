package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "detection_event")
public class DetectionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "detection_event_seq_gen")
    @SequenceGenerator(name = "detection_event_seq_gen", sequenceName = "detection_event_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "slo_definition_id")
    private Long sloDefinitionId;

    @Column(nullable = false)
    private String entity;

    @Column(nullable = false)
    private String signal;

    @Column(nullable = false)
    private double score;

    @Column(name = "metric_value")
    private Double metricValue;

    @Column(name = "metric_baseline")
    private Double metricBaseline;

    @Column(name = "evidence_logs", columnDefinition = "TEXT")
    private String evidenceLogs;

    @Column(name = "investigation_id")
    private Long investigationId;

    @Column(name = "kafka_offset")
    private Long kafkaOffset;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt = Instant.now();
}
