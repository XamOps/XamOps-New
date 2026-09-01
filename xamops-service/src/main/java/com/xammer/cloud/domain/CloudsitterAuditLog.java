package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cloudsitter_audit_log",
        indexes = {
                @Index(name = "idx_cs_audit_account",   columnList = "account_id"),
                @Index(name = "idx_cs_audit_instance",  columnList = "instance_id"),
                @Index(name = "idx_cs_audit_timestamp", columnList = "timestamp")
        })
@Data
@NoArgsConstructor
public class CloudsitterAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, length = 255)
    private String accountId;

    @Column(name = "instance_id", nullable = false, length = 255)
    private String instanceId;

    @Column(name = "instance_name", length = 255)
    private String instanceName;

    @Column(name = "region", length = 100)
    private String region;

    /** "STARTED" or "STOPPED" */
    @Column(name = "action", nullable = false, length = 50)
    private String action;

    @Column(name = "policy_id")
    private Long policyId;

    @Column(name = "policy_name", length = 255)
    private String policyName;

    /** "SCHEDULE" for automated enforcement; reserved for future "MANUAL" / "API" triggers */
    @Column(name = "trigger_reason", nullable = false, length = 100)
    private String triggerReason = "SCHEDULE";

    /** Instance state immediately before this action was taken */
    @Column(name = "previous_state", length = 50)
    private String previousState;

    /** "SUCCESS" or "FAILURE" */
    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome = "SUCCESS";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();
}
