package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "sre_investigation")
public class SreInvestigation {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sre_inv_seq_gen")
    @SequenceGenerator(name = "sre_inv_seq_gen", sequenceName = "sre_investigation_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 10)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20)
    private ResourceType resourceType;

    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @Column(name = "resource_name")
    private String resourceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InvestigationStatus status = InvestigationStatus.PENDING;

    @Column(name = "alert_payload", columnDefinition = "TEXT")
    private String alertPayload;

    @Column(name = "report_summary", columnDefinition = "TEXT")
    private String reportSummary;

    @Column(name = "report_root_cause", columnDefinition = "TEXT")
    private String reportRootCause;

    @Column(name = "report_recommendations", columnDefinition = "TEXT")
    private String reportRecommendations;

    @Column(name = "report_full_markdown", columnDefinition = "TEXT")
    private String reportFullMarkdown;

    @Column(name = "opensre_thread_id")
    private String opensreThreadId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", length = 20)
    private Verdict verdict;

    @Column(name = "verdict_at")
    private Instant verdictAt;

    @Column(name = "verdict_note", columnDefinition = "TEXT")
    private String verdictNote;

    @Column(name = "incident_id")
    private String incidentId;

    @Column(name = "version", nullable = false, columnDefinition = "int4 default 1 not null")
    private int version = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_status", nullable = false, columnDefinition = "varchar(10) default 'OPEN' not null")
    private IncidentStatus incidentStatus = IncidentStatus.OPEN;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public enum TriggerType { AUTO, MANUAL }

    public enum ResourceType { K8S_POD, EC2, RDS, AZURE_VM, GCP_VM, GENERIC }

    public enum InvestigationStatus { PENDING, RUNNING, COMPLETED, FAILED }

    public enum Verdict { RESOLVED, FALSE_POSITIVE, WRONG_ROOT_CAUSE }

    public enum IncidentStatus { OPEN, FINAL, CLOSED }
}
