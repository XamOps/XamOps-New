package com.xammer.cloud.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class AlertDto {
    private String id;
    private String service;
    private String name;
    private String description;
    private String status;
    private double usage;
    private double limit;
    private String type;
    private String region; // MODIFIED: Added field for region

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
}
