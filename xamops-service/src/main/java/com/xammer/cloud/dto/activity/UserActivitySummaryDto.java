package com.xammer.cloud.dto.activity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the "Check Activity" window renders for one user.
 *
 * <p>Assembled by {@code UserActivityQueryService} and served uncached. The user list behind
 * this window comes from a Redis-cached DTO built by {@code BillOpsClientsCacheWarmingService};
 * serving activity from that cache would show stale timestamps in the one place whose entire
 * purpose is being current.
 *
 * <p>Collections default to empty rather than null so the frontend can map over them without
 * guarding every field.
 */
@Data
@NoArgsConstructor
public class UserActivitySummaryDto {

    // ── identity ────────────────────────────────────────────────────────────────
    private Long userId;
    private String username;
    private String email;
    private String role;

    /**
     * The account's configured provider, from {@code app_user.auth_provider}. Distinct from
     * {@link #providers}, which is what was actually used per login.
     */
    private String configuredAuthProvider;

    private String contact;

    /** Non-null means this is a sub-user of that primary account. */
    private Long parentId;

    private boolean emailNotificationsEnabled;
    private boolean tourSeen;
    private boolean hasGoogleLink;
    private boolean hasMicrosoftLink;

    // ── window ──────────────────────────────────────────────────────────────────
    private int windowDays;

    /**
     * Timestamp of the earliest recorded event.
     *
     * Rendered by the window so that a sparse chart reads as "not measured before this date"
     * rather than "this user is inactive". There is nothing to backfill from: before these
     * tables existed, only a single overwritten {@code last_login_at} scalar was kept.
     */
    private LocalDateTime collectingSince;

    // ── login figures ───────────────────────────────────────────────────────────
    private LocalDateTime lastLoginAt;
    private Long daysSinceLastLogin;

    /** ACTIVE | IDLE | DORMANT | NEVER. Thresholds match WorkspaceService. */
    private String status;

    private long loginCount;
    private long failedLoginCount;
    private long activeDays;
    private long longestGapDays;

    /** Mean duration of sessions that ended in a deliberate sign-out. */
    private Long avgSessionMs;

    /**
     * How many sessions that average is drawn from.
     *
     * Published alongside the average because closing a tab produces no logout event, so the
     * mean describes only the subset of sessions that ended with the button being clicked.
     */
    private int closedSessionCount;

    private List<DayBucket> loginsPerDay = new ArrayList<>();
    private List<HourBucket> loginsPerHour = new ArrayList<>();
    private List<ProviderSlice> providers = new ArrayList<>();
    private List<IpSlice> ipAddresses = new ArrayList<>();
    private List<DeviceSlice> devices = new ArrayList<>();

    // ── page time ───────────────────────────────────────────────────────────────

    /** Total time the tab was visible across the window. The figure to report. */
    private long totalVisibleMs;

    /** Total wall-clock time, including background tabs. Context for the above. */
    private long totalDwellMs;

    private long totalPageVisits;

    /**
     * {@link #totalVisibleMs} over {@link #totalDwellMs}.
     *
     * Near 1.0 means the time was spent actively on screen; a low value means these totals
     * are mostly parked tabs and should be read with suspicion.
     */
    private Double engagementRatio;

    /** Per-page totals, ordered by visible time descending. */
    private List<PageStat> pages = new ArrayList<>();

    private List<DayBucket> pageTimePerDay = new ArrayList<>();

    /** Which page each session opened on. */
    private List<PageStat> entryRoutes = new ArrayList<>();

    /** Catalogued product areas this user has never opened. */
    private List<PageStat> neverVisited = new ArrayList<>();

    // ── access & timeline ───────────────────────────────────────────────────────

    /** Every "Login as" against this account, across all time. */
    private List<ImpersonationEntry> impersonations = new ArrayList<>();

    private List<TimelineEntry> timeline = new ArrayList<>();

    // ── nested projections ──────────────────────────────────────────────────────

    /** One calendar day. Login fields and page fields are populated by different queries. */
    @Data
    @NoArgsConstructor
    public static class DayBucket {
        private LocalDate day;
        private long logins;
        private long visibleMs;
        private long visits;
    }

    @Data
    @NoArgsConstructor
    public static class HourBucket {
        /** 0-23, always all 24 present so the chart keeps a stable axis. */
        private int hour;
        private long logins;
    }

    @Data
    @NoArgsConstructor
    public static class ProviderSlice {
        private String provider;
        private long logins;
    }

    @Data
    @NoArgsConstructor
    public static class IpSlice {
        private String ipAddress;
        private long hits;
        private LocalDateTime lastSeenAt;
    }

    @Data
    @NoArgsConstructor
    public static class DeviceSlice {
        /** Coarse bucket, e.g. "Chrome on Windows". */
        private String label;
        private long hits;
    }

    /** Per-route totals. Also reused for entry routes and unvisited pages. */
    @Data
    @NoArgsConstructor
    public static class PageStat {
        private String route;
        private String label;
        private long visibleMs;
        private long dwellMs;
        private long visits;

        /**
         * Mean visible time per visit.
         *
         * The field that distinguishes forty eight-second bounces from three twenty-minute
         * sessions. Identical totals, opposite conclusions.
         */
        private long avgVisibleMs;

        private LocalDateTime lastVisitAt;
    }

    @Data
    @NoArgsConstructor
    public static class ImpersonationEntry {
        private LocalDateTime at;
        private Long actorUserId;

        /** Admin's email where resolvable, else "unrecorded". */
        private String actorLabel;

        private String ipAddress;

        /** Raw JSON, currently carrying the granted role. */
        private String metadata;
    }

    @Data
    @NoArgsConstructor
    public static class TimelineEntry {
        private LocalDateTime at;
        private String action;
        private String authProvider;
        private String ipAddress;
        private String outcome;
        private String failureReason;

        /** Set when an admin caused this event rather than the user themselves. */
        private Long actorUserId;
    }
}
