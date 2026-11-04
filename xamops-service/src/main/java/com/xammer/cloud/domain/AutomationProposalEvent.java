package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One audited transition of a proposal (who, when, what). Schema: {@code V20261104_1}. */
@Entity
@Table(name = "automation_proposal_event",
        indexes = @Index(name = "ix_ape_proposal", columnList = "proposal_id, at"))
@Getter
@Setter
@NoArgsConstructor
public class AutomationProposalEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposal_id", nullable = false, length = 36)
    private String proposalId;

    @Column(name = "cluster_config_id", nullable = false)
    private Long clusterConfigId;

    @Column(name = "at", nullable = false)
    private Instant at;

    /** USER | AGENT | SYSTEM. */
    @Column(name = "actor_type", nullable = false, length = 8)
    private String actorType;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", length = 255)
    private String actorName;

    @Column(name = "event", nullable = false, length = 16)
    private String event;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;
}
