package com.xammer.cloud.dto.war;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * The full WAR payload for one cloud account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarReport {

    private String accountId;
    private String accountName;
    /** AWS | GCP | Azure. */
    private String provider;
    private String generatedAt;
    private List<String> scannedRegions;

    /** 0-100. 100 means every automated control passed. */
    private int warScore;
    /** CRITICAL | HIGH | MEDIUM | LOW — derived from score and open critical count. */
    private String riskLevel;

    private int totalControls;
    private int automatedControls;
    /** Controls that produced a real verdict (PASS, FAIL or WARN). Drives {@link #assessable}. */
    private int verifiedControls;
    /**
     * False when nothing could be verified. The score and risk level are meaningless
     * in that case and the UI must say "unable to assess" rather than "critical risk",
     * which would blame the customer's security posture for our own blind scan.
     */
    private boolean assessable;
    /**
     * The single cause behind most check failures, when there is one — typically
     * expired credentials or a revoked role. Present so the page can give one
     * actionable sentence instead of a hundred identical warnings.
     */
    private String blockedReason;

    private Map<String, Integer> statusCounts;
    /** Failing controls by advisory severity. */
    private Map<String, Integer> severityCounts;

    private List<AttackPathSummary> attackPaths;
    private List<CategorySummary> categories;
    private List<WarFinding> findings;
    /** Highest-weighted open failures, already ordered. */
    private List<WarFinding> topRisks;
    /** Actions explicitly denied to the role — a real IAM policy gap worth fixing. */
    private List<String> deniedActions;
    /**
     * Actions that failed for a non-authorization reason (expired credentials,
     * unreachable endpoint). Kept apart from {@link #deniedActions} so the UI never
     * tells a customer to grant a permission that was never the problem.
     */
    private List<String> blockedActions;
    /** Non-fatal problems from the scan (region timeouts, partial results). */
    private List<String> scanWarnings;

    /**
     * Event-driven findings that arrived after this report was generated.
     *
     * <p>
     * Stamped at read time and deliberately never persisted with the report — it describes the gap
     * between the report and now, so a cached value would be wrong the moment it was written.
     *
     * <p>
     * The point is honesty about a number that is expensive to recompute. A full WAR scan is heavy
     * enough to cache for twelve hours, so invalidating it on every finding would trade a stale
     * score for a slow page. Instead the page keeps showing the score it actually computed and says
     * how much has happened since, which lets the user decide whether to pay for a rescan. Silently
     * recalculating part of the score would be worse: the number would look current without being
     * so.
     */
    private int findingsSinceScan;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttackPathSummary {
        private String path;
        private String label;
        private String description;
        private int failed;
        private int warned;
        /**
         * Controls on this path that could not be verified. Reported separately from
         * {@code failed} so a card cannot claim "0 open" while its findings list shows
         * eight unverified controls.
         */
        private int unverified;
        private int total;
        private int affectedResources;
        /** OPEN | PARTIAL | CONTAINED | UNVERIFIED. */
        private String state;
        private String worstSeverity;
        private List<String> topControlIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategorySummary {
        private String category;
        private int total;
        private int passed;
        private int failed;
        private int warned;
        private int manual;
        private int unknown;
        private int notApplicable;
        /** 0-100 across the automated controls in this category. */
        private int score;
    }
}
