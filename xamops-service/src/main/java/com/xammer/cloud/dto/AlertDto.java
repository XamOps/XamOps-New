package com.xammer.cloud.dto;

import com.xammer.cloud.service.security.SecuritySeverity;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One alert on the CloudGuard / Alerts page, for any provider.
 *
 * <p><b>On {@link #status} versus {@link #severity}.</b> {@code status} carries two different
 * vocabularies depending on who built the alert: quota checks put {@code CRITICAL/WARNING/OK}
 * in it, while Security Hub and GCP Security Command Center put their real finding severity
 * ({@code CRITICAL/HIGH/MEDIUM/LOW}) in the same slot. That made a severity threshold
 * impossible to express — "only email me about criticals" cannot be written against a field
 * where {@code WARNING} and {@code MEDIUM} mean the same thing and {@code OK} means "not a
 * problem at all".
 *
 * <p>{@link #getSeverity()} is that threshold-able value: always one of
 * {@link SecuritySeverity#RANKED}, never null. {@code status} is left exactly as it was because
 * the frontend renders it directly and filters on it.
 */
@Data
@NoArgsConstructor
public class AlertDto {
    private String id;
    private String service;
    private String name;
    private String description;

    /**
     * Display state, provider-specific. Rendered verbatim by the UI badge.
     *
     * <p>Do not gate notifications on this — use {@link #getSeverity()}.
     */
    private String status;

    private double usage;
    private double limit;
    private String type;
    private String region; // MODIFIED: Added field for region

    /**
     * Explicitly-set severity, or null to derive from {@link #status}.
     *
     * <p>Read through {@link #getSeverity()}, which never returns null. Deriving in the getter
     * rather than in a constructor or in {@code setStatus} matters for three reasons: the
     * setter-based build sites ({@code new AlertDto()} then setters) would otherwise keep
     * whatever the field was initialised to; deriving inside {@code setStatus} would clobber an
     * explicit severity depending on call order; and alert lists already cached in Redis and
     * {@code CachedData} have no {@code severity} key, so they deserialise to null here and
     * still resolve correctly instead of all arriving as one wrong band.
     */
    private String severity;

    /**
     * Currency symbol for alerts whose figure is money, so the UI does not have to guess.
     *
     * <p>Null for alerts that carry no monetary amount. The GCP anomaly pill used to print a
     * hard-coded {@code $} in front of {@link #usage}, which is wrong for every project not
     * billed in USD.
     */
    private String currencySymbol;

    // ── Roll-up ──────────────────────────────────────────────────────────────
    //
    // A single alert row can stand for many underlying findings. Container image
    // vulnerabilities are the case that forced this: one GCP project produced 2,124 findings that
    // were emitted as 2,124 rows, every one of them carrying the identical headline, differing only
    // in a sha256 digest buried in the description. That is not a list a person can read, and it
    // made the notification digest just as unusable.
    //
    // Both fields are optional. Anything that does not group leaves affectedCount at its default
    // and resources null, and reads exactly as it did before.

    /**
     * How many underlying findings this row represents.
     *
     * <p>
     * Zero means "not a grouped row" and should be read as one. Consumers should treat
     * {@code max(affectedCount, 1)} as the count so an ungrouped alert needs no special case.
     */
    private int affectedCount;

    /**
     * The resources this row covers, when it is a grouped row.
     *
     * <p>
     * Held so the UI can expand a group without a second round trip, and so the notification
     * fingerprint can be computed over resource identity rather than over a row count.
     */
    private List<String> resources;

    /**
     * Original nine-field constructor.
     *
     * <p>
     * Written out by hand rather than generated. {@code @AllArgsConstructor} would silently widen to
     * include the roll-up fields above and break every positional call site across the AWS, Azure
     * and GCP services at once — the sort of change that compiles somewhere else and fails far from
     * where it was made.
     */
    public AlertDto(String id, String service, String name, String description, String status,
                    double usage, double limit, String type, String region) {
        this.id = id;
        this.service = service;
        this.name = name;
        this.description = description;
        this.status = status;
        this.usage = usage;
        this.limit = limit;
        this.type = type;
        this.region = region;
    }

    /** Grouped-row constructor. */
    public AlertDto(String id, String service, String name, String description, String status,
                    double usage, double limit, String type, String region,
                    int affectedCount, List<String> resources) {
        this(id, service, name, description, status, usage, limit, type, region);
        this.affectedCount = affectedCount;
        this.resources = resources;
    }

    /** The number of findings behind this row, treating an ungrouped alert as one. */
    public int effectiveCount() {
        return Math.max(affectedCount, 1);
    }

    /**
     * Normalised severity. Never null.
     *
     * <p>Falls back to {@link #status}, which is the right answer for security findings — their
     * status already <em>is</em> a severity — and for quota alerts, where {@code CRITICAL} stays
     * critical, {@code WARNING} becomes {@code MEDIUM} and {@code OK} becomes
     * {@code INFORMATIONAL}.
     */
    public String getSeverity() {
        return severity != null ? severity : SecuritySeverity.normalize(status);
    }

    /** Normalises on the way in, so an unrecognised provider severity cannot bypass a threshold. */
    public void setSeverity(String severity) {
        this.severity = severity == null ? null : SecuritySeverity.normalize(severity);
    }
}
