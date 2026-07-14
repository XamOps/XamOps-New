package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_threat_detection")
public class UpwindThreatDetection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upwind_id", nullable = false, unique = true)
    private String upwindId;

    private String category;
    private String severity;
    private String status;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(name = "cloud_provider")
    private String cloudProvider;

    @Column(name = "cloud_account_id")
    private String cloudAccountId;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "resource_name")
    private String resourceName;

    @Column(name = "resource_type")
    private String resourceType;

    /** Comma-separated MITRE tactic names, e.g. "Initial Access,Persistence" */
    @Column(name = "mitre_tactics", columnDefinition = "TEXT")
    private String mitreTactics;

    @Column(name = "occurrence_count")
    private Integer occurrenceCount;

    @Column(name = "first_seen_time")
    private Instant firstSeenTime;

    @Column(name = "last_seen_time")
    private Instant lastSeenTime;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
