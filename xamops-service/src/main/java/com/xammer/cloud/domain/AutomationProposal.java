package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One proposed automation action awaiting (or past) a human decision (Manual mode, Phase B).
 *
 * <p>The state machine lives in {@code ProposalService}; every status change is a single guarded
 * {@code @Modifying} query on {@code AutomationProposalRepository}, so this entity is only ever
 * <em>inserted</em> through {@code save()}. {@link #version} is a plain column bumped by those
 * queries on status transitions only (never on a {@code last_seen_at} touch) and is deliberately NOT
 * a JPA {@code @Version}. Schema: {@code V20261104_1__manual_mode_proposals.sql}.
 *
 * <p>Large text columns use {@code columnDefinition = "TEXT"} (the repo pattern; no {@code @Lob}, which
 * Postgres maps to {@code oid}), so a table Hibernate creates before Flyway runs is still {@code text}.
 */
@Entity
@Table(name = "automation_proposal",
        indexes = @Index(name = "ix_ap_cluster_status", columnList = "cluster_config_id, status"))
@Getter
@Setter
@NoArgsConstructor
public class AutomationProposal {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "cluster_config_id", nullable = false)
    private Long clusterConfigId;

    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "action_type", nullable = false, length = 24)
    private String actionType;

    @Column(name = "operation", length = 16)
    private String operation;

    @Column(name = "source", nullable = false, length = 8)
    private String source;

    @Column(name = "origin_controller", nullable = false, length = 24)
    private String originController;

    @Column(name = "target_kind", nullable = false, length = 8)
    private String targetKind;

    @Column(name = "target_ref", nullable = false, length = 512)
    private String targetRef;

    @Column(name = "target_identity", length = 256)
    private String targetIdentity;

    @Column(name = "dedupe_key", nullable = false, length = 640)
    private String dedupeKey;

    @Column(name = "params_json", nullable = false, columnDefinition = "TEXT")
    private String paramsJson;

    @Column(name = "evidence_json", nullable = false, columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(name = "verdict", nullable = false, length = 8)
    private String verdict;

    @Column(name = "refusal_codes", length = 512)
    private String refusalCodes;

    @Column(name = "refusal_reason", columnDefinition = "TEXT")
    private String refusalReason;

    @Column(name = "gate_json", columnDefinition = "TEXT")
    private String gateJson;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "plan_fingerprint", nullable = false, length = 80)
    private String planFingerprint;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "status_reason", length = 255)
    private String statusReason;

    @Column(name = "refusal_stage", length = 10)
    private String refusalStage;

    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "last_progress_at")
    private Instant lastProgressAt;

    @Column(name = "dwell_seconds")
    private Integer dwellSeconds;

    @Column(name = "approvable_after")
    private Instant approvableAfter;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_by_name", length = 255)
    private String decidedByName;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "approved_until")
    private Instant approvedUntil;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "dispatch_attempts", nullable = false)
    private int dispatchAttempts;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "outcome_status", length = 16)
    private String outcomeStatus;

    @Column(name = "outcome_verified")
    private Boolean outcomeVerified;

    @Column(name = "outcome_json", columnDefinition = "TEXT")
    private String outcomeJson;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Plain column maintained by the guarded queries; NOT a JPA {@code @Version}. */
    @Column(name = "version", nullable = false)
    private long version;
}
