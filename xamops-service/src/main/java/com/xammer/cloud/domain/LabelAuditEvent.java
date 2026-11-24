package com.xammer.cloud.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One label change a user made, or tried to make: who, when, from where, and to which label.
 * What it changed is in {@link LabelAuditChange}, one row per target. Append-only.
 *
 * <p>No foreign keys, and names copied in, so deleting a label or an account neither blocks on
 * its history nor erases it.
 */
@Entity
@Table(name = "label_audit_event", indexes = {
        @Index(name = "idx_label_audit_event_client", columnList = "client_id, id"),
        @Index(name = "idx_label_audit_event_account", columnList = "cloud_account_id, id")})
@Getter
@Setter
@NoArgsConstructor
public class LabelAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    /** Null for client-level events: a label created, edited or deleted. */
    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    /** The account id as the caller sent it, kept even when it resolved to no account. */
    @Column(name = "account_ref", length = 64)
    private String accountRef;

    @Column(name = "actor", length = 100)
    private String actor;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    /** OK, or REJECTED for an attempt the server refused; the reason is in {@link #detail}. */
    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    /** Which screen made the change, when the caller said so: COST_RESOURCE_PANEL, CLOUD_LIST, ... */
    @Column(name = "source", length = 32)
    private String source;

    /** The label the event is about, when it is about one. Name and colour as they were. */
    @Column(name = "label_id")
    private Long labelId;

    @Column(name = "label_name", length = 100)
    private String labelName;

    @Column(name = "label_color", length = 7)
    private String labelColor;

    @Column(name = "detail", length = 1000)
    private String detail;

    /** How many targets the event changed, including any not stored because of the cap. */
    @Column(name = "change_count", nullable = false)
    private int changeCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
