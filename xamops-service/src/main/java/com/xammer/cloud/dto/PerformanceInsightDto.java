package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceInsightDto {
    private String id;
    private String insight;
    private String description;
    private InsightSeverity severity;
    private InsightCategory category;
    private String account;
    private int quantity;
    private String resourceType;
    private String resourceId;
    private String recommendation;
    private String documentationUrl;
    private double potentialSavings;
    private String region;
    private String createdDate;

    /**
     * True when a monthly saving could not be priced, so {@link #potentialSavings} carries no
     * information.
     *
     * <p>It exists because {@code potentialSavings} is a primitive: without a flag, "we could not
     * price this" and "this costs nothing" are the same value. Callers summing savings must exclude
     * these, and the UI must render them as unknown rather than as zero — otherwise a pricing
     * outage silently understates a project's savings instead of admitting it could not answer.
     */
    private boolean savingsUnknown;

    /** Why the saving could not be priced, for display beside the finding. Null when priced. */
    private String savingsUnavailableReason;

    /**
     * Constructor for a finding whose saving is priced.
     *
     * <p>Retained with the original parameter list so the existing call sites in both the AWS and
     * GCP insight scanners keep compiling; the two fields above default to "priced".
     */
    public PerformanceInsightDto(String id, String insight, String description,
            InsightSeverity severity, InsightCategory category, String account, int quantity,
            String resourceType, String resourceId, String recommendation, String documentationUrl,
            double potentialSavings, String region, String createdDate) {
        this.id = id;
        this.insight = insight;
        this.description = description;
        this.severity = severity;
        this.category = category;
        this.account = account;
        this.quantity = quantity;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.recommendation = recommendation;
        this.documentationUrl = documentationUrl;
        this.potentialSavings = potentialSavings;
        this.region = region;
        this.createdDate = createdDate;
    }

    /**
     * Marks this finding's saving as unpriced and records why.
     *
     * <p>Zeroes the amount deliberately: leaving a stale figure behind a true flag invites a
     * caller that forgets the flag to keep reading it.
     */
    public PerformanceInsightDto withUnknownSavings(String reason) {
        this.savingsUnknown = true;
        this.savingsUnavailableReason = reason;
        this.potentialSavings = 0.0;
        return this;
    }

    public enum InsightSeverity {
        CRITICAL, WARNING, WEAK_WARNING
    }

    public enum InsightCategory {
        COST, PERFORMANCE, SECURITY, FAULT_TOLERANCE, BEST_PRACTICE // ADDED
    }
}