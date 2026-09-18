package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Per-client control over security notifications.
 *
 * <p>
 * The first notification preference table in the platform. Everything before this sent to
 * every user under the client with no opt-out, no severity threshold and no channel choice —
 * tolerable for a twice-weekly digest, not for an immediate email triggered by an event
 * stream. An account under attack produces findings in bursts, so "email on critical" without
 * a way to turn it off or cap it is a support incident waiting to happen.
 *
 * <p>
 * Absence of a row means defaults apply. That is what makes this deployable without a
 * backfill: existing clients get {@link #DEFAULT_SEVERITY_THRESHOLD} immediately and can
 * change it later, and no migration has to invent a row per client.
 */
@Entity
@Table(name = "security_notification_preference")
@Data
@NoArgsConstructor
public class SecurityNotificationPreference {

    /**
     * Immediate email means "stop what you are doing", so the default is the top band only.
     * Anything more frequent trains people to ignore it, which is worse than not sending —
     * HIGH and below still reach the existing twice-weekly digest.
     */
    public static final String DEFAULT_SEVERITY_THRESHOLD = "CRITICAL";

    public static final int DEFAULT_MAX_EMAILS_PER_HOUR = 4;

    /**
     * The digest admits everything by default.
     *
     * <p>Deliberately not {@link #DEFAULT_SEVERITY_THRESHOLD}. That default exists because an
     * immediate per-finding email should mean "stop what you are doing". The digest is the
     * opposite kind of message — a periodic summary that has always included quota warnings —
     * so defaulting it to CRITICAL would silently stop sending most of what customers get
     * today. {@code INFORMATIONAL} is the floor of the ranked vocabulary, so this admits every
     * severity and the knob exists to be raised, not to raise it.
     */
    public static final String DEFAULT_DIGEST_SEVERITY_THRESHOLD = "INFORMATIONAL";

    /** Not generated: the client id <em>is</em> the key, one preference row per client. */
    @Id
    @Column(name = "client_id")
    private Long clientId;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    /** Threshold for the <em>immediate</em> per-finding security email. */
    @Column(name = "severity_threshold", nullable = false, length = 16)
    private String severityThreshold = DEFAULT_SEVERITY_THRESHOLD;

    /**
     * Threshold for the periodic cloud alert digest, which is a different message.
     *
     * <p>See {@link #DEFAULT_DIGEST_SEVERITY_THRESHOLD} for why this is not the same field as
     * {@link #severityThreshold}.
     */
    @Column(name = "digest_severity_threshold", nullable = false, length = 16)
    private String digestSeverityThreshold = DEFAULT_DIGEST_SEVERITY_THRESHOLD;

    @Column(name = "max_emails_per_hour", nullable = false)
    private Integer maxEmailsPerHour = DEFAULT_MAX_EMAILS_PER_HOUR;

    /**
     * In-app inbox rows, controlled separately from email.
     *
     * <p>
     * The two have different tolerances: the inbox is free and silent, so a user who mutes
     * email usually still wants the bell. Folding them into one switch would force people who
     * dislike email into having no notification at all.
     */
    @Column(name = "inbox_enabled", nullable = false)
    private boolean inboxEnabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** The defaults, for a client with no row. Never persisted by the read path. */
    public static SecurityNotificationPreference defaults(Long clientId) {
        SecurityNotificationPreference prefs = new SecurityNotificationPreference();
        prefs.setClientId(clientId);
        return prefs;
    }
}
