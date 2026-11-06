package com.xammer.cloud.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One thing a {@link LabelAuditEvent} changed — a resource, a service, or the label itself — with
 * its state either side.
 *
 * <p>For a resource or service the state is the full set of labels on it with their shares, as
 * JSON: {@code [{"labelId":3,"name":"prod","color":"#6366f1","percent":40.00}]}, a null percent
 * meaning the whole cost. For a label it is the label's own fields. Null means there was none: no
 * before for a created label, no after for a deleted one.
 */
@Entity
@Table(name = "label_audit_change", indexes = {
        @Index(name = "idx_label_audit_change_event", columnList = "event_id"),
        @Index(name = "idx_label_audit_change_target", columnList = "cloud_account_id, target_type, target_id")})
@Getter
@Setter
@NoArgsConstructor
public class LabelAuditChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    /** The account the target lives in. Null for a label, which belongs to the client. */
    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    /** RESOURCE, SERVICE or LABEL. */
    @Column(name = "target_type", nullable = false, length = 16)
    private String targetType;

    /** The stored resource id, the billing service name, or the label id. */
    @Column(name = "target_id", length = 512)
    private String targetId;

    @Column(name = "target_name", length = 255)
    private String targetName;

    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;
}
