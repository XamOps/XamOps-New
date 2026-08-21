package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.Instant;

/**
 * One in-app notification about security findings.
 *
 * <p>
 * A row per <em>batch</em>, not per finding. A batch is the unit a user actually acts on —
 * "3 critical findings on production" is one thing to read and dismiss — and it is the same
 * unit the email uses, so the bell and the inbox never disagree with what was sent. A row per
 * finding would make the unread counter a second copy of the findings table with its own read
 * state to keep in sync, and those two would drift.
 *
 * <p>
 * Modelled deliberately on {@link SreNotification}: client-scoped, an {@code is_read} flag,
 * polled over REST. That is the one in-app notification pattern the platform already has, and
 * reusing its shape means the existing bell UI applies without inventing new concepts.
 */
@Entity
@Table(name = "security_notification")
@Data
@NoArgsConstructor
public class SecurityNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    /**
     * Nullable on purpose. A connection can be removed while the notification is still worth
     * reading, and the FK is {@code ON DELETE SET NULL} so the record that an account had
     * critical findings survives the account being deleted.
     */
    @Column(name = "cloud_account_id")
    private Long cloudAccountId;

    @Column(name = "aws_account_id", length = 32)
    private String awsAccountId;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    /** Highest severity in the batch, so the bell can be coloured without a join. */
    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "finding_count", nullable = false)
    private Integer findingCount = 1;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    /**
     * Whether an email accompanied this notification.
     *
     * <p>
     * Recorded because the two can legitimately diverge: a client with {@code emailEnabled}
     * false, or one that hit the hourly cap, still gets the inbox row. Without this a user
     * asking "why did I not get an email about this" has no answer in the data.
     */
    @Column(name = "email_sent", nullable = false)
    private boolean emailSent = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
