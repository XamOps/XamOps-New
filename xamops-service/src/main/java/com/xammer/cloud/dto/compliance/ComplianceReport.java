package com.xammer.cloud.dto.compliance;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the ComplianceOps page renders, assembled server-side from one scan.
 *
 * Three deliberate properties:
 *
 *  1. Every number here is derived from persisted scan rows. Nothing is synthesised.
 *     The previous implementation drew a 14-day "trend" from today's counts times a
 *     decay curve, which always sloped downwards regardless of reality. If there is
 *     only one scan, {@link #trend} has one point and the UI says so.
 *
 *  2. {@link #assessable} gates the score the same way the WAR page does. A scan that
 *     produced nothing scoreable renders as "—", not as zero, because "we could not
 *     measure this" and "you failed everything" are different statements.
 *
 *  3. WAIVED is a first-class status, not a hidden FAIL. Waived controls are excluded
 *     from the score denominator and their count travels alongside the score so the
 *     number can never be quietly inflated by waiving things.
 */
@Data
@NoArgsConstructor
public class ComplianceReport {

    private String accountId;
    private String provider;

    // ── Provenance ──────────────────────────────────────────────────────────
    private Long scanId;
    private Instant scannedAt;
    private String scanStatus;
    private String scanError;
    /** Regions the scan actually covered. A score only compares across equal scope. */
    private List<String> regions = new ArrayList<>();
    private List<String> frameworksRequested = new ArrayList<>();
    private String scannerVersion;

    // ── Score ───────────────────────────────────────────────────────────────
    /** Severity-weighted, 0–100. Null when {@link #assessable} is false. */
    private Integer score;
    private boolean assessable;
    /** Human label for the score band: Strong / Adequate / Weak / Critical / Unknown. */
    private String scoreLabel;
    private String scoreMethod;

    private Counts counts = new Counts();

    // ── Detail ──────────────────────────────────────────────────────────────
    private List<FrameworkScore> frameworks = new ArrayList<>();
    private List<ControlView> controls = new ArrayList<>();
    private List<ControlView> topRisks = new ArrayList<>();
    private List<TrendPoint> trend = new ArrayList<>();
    private ProgramHealth program = new ProgramHealth();

    /**
     * Plain-language caveats about this specific report, rendered verbatim. Used to say
     * things like "only one scan recorded, so there is no trend yet" rather than
     * silently drawing a flat line.
     */
    private List<String> notes = new ArrayList<>();

    // ────────────────────────────────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    public static class Counts {
        private int totalControls;
        private int passedControls;
        private int failedControls;
        private int manualControls;
        private int waivedControls;

        /** Resource-level tallies, summed across controls. */
        private int passedResources;
        private int failedResources;

        /** Failing controls by severity — the actual work queue. */
        private int critical;
        private int high;
        private int medium;
        private int low;

        /** Failing controls that were passing in the previous completed scan. */
        private int newlyFailing;
        /** Controls that were failing in the previous scan and now pass. */
        private int newlyPassing;
    }

    @Data
    @NoArgsConstructor
    public static class FrameworkScore {
        /** Raw framework id exactly as the scanner reported it, e.g. "CIS-3.0". */
        private String framework;
        private int totalControls;
        private int passedControls;
        private int failedControls;
        private int manualControls;
        private int waivedControls;
        private Integer score;
        private boolean assessable;
        private int critical;
        private int high;
        private int medium;
        private int low;
        /** Distinct framework control references touched, e.g. 42 of CIS's clauses. */
        private int frameworkControlCount;
    }

    @Data
    @NoArgsConstructor
    public static class ControlView {
        private String checkId;
        private String title;
        private String service;
        private String severity;
        /** PASS | FAIL | MANUAL | WAIVED */
        private String status;
        private int passedResources;
        private int failedResources;
        private String remediation;
        private List<String> remediationRefs = new ArrayList<>();
        private List<FrameworkRef> frameworks = new ArrayList<>();
        /** FAIL/MANUAL resource evidence. Passing resources are counted, not listed. */
        private List<ResourceRef> resources = new ArrayList<>();

        private Owner owner;
        private ExceptionRef exception;
        private RemediationRef task;

        /** True when this control passed in the previous completed scan. */
        private boolean newlyFailing;
        /** Consecutive completed scans this control has been failing, including this one. */
        private int failingForScans;
    }

    @Data
    @NoArgsConstructor
    public static class FrameworkRef {
        private String framework;
        private List<String> controls = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    public static class ResourceRef {
        private String uid;
        private String name;
        private String type;
        private String region;
        private String detail;
        private String status;
    }

    @Data
    @NoArgsConstructor
    public static class Owner {
        private String name;
        private String email;
        private String team;
    }

    @Data
    @NoArgsConstructor
    public static class ExceptionRef {
        private Long id;
        private String status;
        private String justification;
        private String compensatingControl;
        private String requestedBy;
        private String approvedBy;
        private Instant expiresAt;
        private long daysUntilExpiry;
    }

    @Data
    @NoArgsConstructor
    public static class RemediationRef {
        private Long id;
        private String status;
        private String assigneeName;
        private String assigneeEmail;
        private String ticketRef;
        private Instant dueAt;
        private boolean overdue;
    }

    @Data
    @NoArgsConstructor
    public static class TrendPoint {
        private Long scanId;
        private Instant at;
        private Integer score;
        private int passedControls;
        private int failedControls;
        private int critical;
        private int high;
        private int medium;
        private int low;
    }

    /**
     * How the compliance programme itself is doing, as opposed to the cloud config.
     * A 90% score with 40 unowned failing controls and 6 lapsed waivers is a worse
     * position than 80% with everything owned and reviewed, and the page should be
     * able to say so.
     */
    @Data
    @NoArgsConstructor
    public static class ProgramHealth {
        private int activeExceptions;
        private int exceptionsExpiringSoon;
        private int lapsedExceptions;
        private int pendingExceptions;

        private int failingControlsWithOwner;
        private int failingControlsWithoutOwner;

        private int remediationOpen;
        private int remediationInProgress;
        private int remediationAwaitingVerification;
        private int remediationOverdue;

        private int completedScans;
        private Instant previousScanAt;
    }
}
