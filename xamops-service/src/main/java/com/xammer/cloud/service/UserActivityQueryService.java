package com.xammer.cloud.service;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.UserActivityEvent;
import com.xammer.cloud.dto.activity.UserActivitySummaryDto;
import com.xammer.cloud.dto.activity.UserActivitySummaryDto.DayBucket;
import com.xammer.cloud.dto.activity.UserActivitySummaryDto.DeviceSlice;
import com.xammer.cloud.dto.activity.UserActivitySummaryDto.HourBucket;
import com.xammer.cloud.dto.activity.UserActivitySummaryDto.ImpersonationEntry;
import com.xammer.cloud.dto.activity.UserActivitySummaryDto.IpSlice;
import com.xammer.cloud.dto.activity.UserActivitySummaryDto.PageStat;
import com.xammer.cloud.dto.activity.UserActivitySummaryDto.ProviderSlice;
import com.xammer.cloud.dto.activity.UserActivitySummaryDto.TimelineEntry;
import com.xammer.cloud.repository.UserActivityEventRepository;
import com.xammer.cloud.repository.UserPageViewRepository;
import com.xammer.cloud.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles the "Check Activity" payload for one user.
 *
 * <p>Read-only. Every figure here is derived from {@code user_activity_event} and
 * {@code user_page_view}, both of which only began collecting when this feature shipped —
 * hence {@link UserActivitySummaryDto#collectingSince()}, which the window renders so that
 * an empty chart reads as "not yet measured" rather than "this user is inactive".
 */
@Service
public class UserActivityQueryService {

    private static final Logger log = LoggerFactory.getLogger(UserActivityQueryService.class);

    /**
     * Inactivity thresholds, in days.
     *
     * Chosen to agree with {@code WorkspaceService.INACTIVE_DAYS_THRESHOLD}, which already
     * classifies Google Workspace seats. "Inactive" meaning two different spans on two
     * screens of the same product is the kind of inconsistency nobody reports but everybody
     * mistrusts.
     */
    private static final long IDLE_AFTER_DAYS = 7;
    private static final long DORMANT_AFTER_DAYS = 30;

    private static final int TIMELINE_LIMIT = 60;

    /** Ceiling on a single session before it is treated as a stale cookie rather than work. */
    private static final long MAX_SESSION_MS = 24L * 60 * 60 * 1000;

    private final UserActivityEventRepository eventRepository;
    private final UserPageViewRepository pageViewRepository;
    private final UserRepository userRepository;
    private final RouteCatalog routeCatalog;

    public UserActivityQueryService(UserActivityEventRepository eventRepository,
                                    UserPageViewRepository pageViewRepository,
                                    UserRepository userRepository,
                                    RouteCatalog routeCatalog) {
        this.eventRepository = eventRepository;
        this.pageViewRepository = pageViewRepository;
        this.userRepository = userRepository;
        this.routeCatalog = routeCatalog;
    }

    public UserActivitySummaryDto summarise(Long userId, int days) {
        int window = Math.max(1, Math.min(days, 365));
        LocalDateTime since = LocalDateTime.now().minusDays(window);

        User user = userRepository.findById(userId).orElse(null);

        UserActivitySummaryDto dto = new UserActivitySummaryDto();
        dto.setUserId(userId);
        dto.setWindowDays(window);

        if (user != null) {
            dto.setUsername(user.getUsername());
            dto.setEmail(user.getEmail());
            dto.setRole(user.getRole());
            dto.setConfiguredAuthProvider(user.getAuthProvider());
            dto.setContact(user.getContact());
            dto.setParentId(user.getCreatedBy());
            dto.setEmailNotificationsEnabled(user.isEmailNotificationsEnabled());
            dto.setTourSeen(user.isTourSeen());
            dto.setLastLoginAt(user.getLastLoginAt());
            dto.setHasGoogleLink(user.getGoogleSub() != null);
            dto.setHasMicrosoftLink(user.getMicrosoftSub() != null);
        }

        applyLoginFigures(dto, userId, since, window);
        applyPageFigures(dto, userId, since);
        applyImpersonation(dto, userId);
        applyTimeline(dto, userId);

        dto.setCollectingSince(eventRepository.findFirstEventAt(userId));
        return dto;
    }

    // ───────────────────────────────── logins ─────────────────────────────────

    private void applyLoginFigures(UserActivitySummaryDto dto, Long userId,
                                   LocalDateTime since, int window) {
        dto.setLoginCount(eventRepository.countByUserIdAndActionAndCreatedAtAfter(
                userId, UserActivityEvent.ACTION_LOGIN, since));
        dto.setFailedLoginCount(eventRepository.countByUserIdAndActionAndCreatedAtAfter(
                userId, UserActivityEvent.ACTION_LOGIN_FAILED, since));
        dto.setActiveDays(eventRepository.countActiveDays(userId, since));

        dto.setLoginsPerDay(toDayBuckets(eventRepository.countLoginsPerDay(userId, since)));
        dto.setLoginsPerHour(toHourBuckets(eventRepository.countLoginsPerHour(userId, since)));
        dto.setProviders(toProviderSlices(eventRepository.countLoginsByProvider(userId, since)));
        dto.setIpAddresses(toIpSlices(eventRepository.countByIpAddress(userId, since)));
        dto.setDevices(toDeviceSlices(eventRepository.countByUserAgent(userId, since)));

        // Longest run of consecutive days with no login, which reads more usefully than a
        // bare "last seen" when someone logs in heavily then disappears for a month.
        dto.setLongestGapDays(longestGapDays(dto.getLoginsPerDay(), window));

        applySessionDurations(dto, userId, since);
        applyStatus(dto);
    }

    /**
     * Average session length, from LOGIN/LOGOUT pairs sharing a session id.
     *
     * Reports the sample size next to the average because the two are not comparable:
     * closing a tab produces no logout event, so only deliberate sign-outs are measurable.
     * Publishing the mean without saying it covers 29 of 64 sessions would invite reading it
     * as typical when it describes only the subset of people who click the button.
     */
    private void applySessionDurations(UserActivitySummaryDto dto, Long userId,
                                       LocalDateTime since) {
        try {
            List<Object[]> pairs = eventRepository.findClosedSessions(userId, since);
            if (pairs == null || pairs.isEmpty()) {
                dto.setClosedSessionCount(0);
                return;
            }
            long totalMs = 0;
            int counted = 0;
            for (Object[] row : pairs) {
                LocalDateTime loginAt = asDateTime(row[1]);
                LocalDateTime logoutAt = asDateTime(row[2]);
                if (loginAt == null || logoutAt == null || !logoutAt.isAfter(loginAt)) {
                    continue;
                }
                long ms = ChronoUnit.MILLIS.between(loginAt, logoutAt);
                // A "session" spanning more than a day is a stale remember-me cookie being
                // reused, not someone working. One such row would dominate the mean.
                if (ms > MAX_SESSION_MS) {
                    continue;
                }
                totalMs += ms;
                counted++;
            }
            dto.setClosedSessionCount(counted);
            if (counted > 0) {
                dto.setAvgSessionMs(totalMs / counted);
            }
        } catch (Exception e) {
            log.warn("Could not compute session durations for user {}: {}", userId, e.getMessage());
        }
    }

    private void applyStatus(UserActivitySummaryDto dto) {
        LocalDateTime last = dto.getLastLoginAt();
        if (last == null) {
            dto.setStatus("NEVER");
            return;
        }
        long days = ChronoUnit.DAYS.between(last, LocalDateTime.now());
        dto.setDaysSinceLastLogin(days);
        if (days >= DORMANT_AFTER_DAYS) {
            dto.setStatus("DORMANT");
        } else if (days >= IDLE_AFTER_DAYS) {
            dto.setStatus("IDLE");
        } else {
            dto.setStatus("ACTIVE");
        }
    }

    // ───────────────────────────────── pages ─────────────────────────────────

    private void applyPageFigures(UserActivitySummaryDto dto, Long userId, LocalDateTime since) {
        try {
            List<Object[]> totals = pageViewRepository.aggregateTotals(userId, since);
            if (totals != null && !totals.isEmpty()) {
                Object[] row = totals.get(0);
                dto.setTotalVisibleMs(asLong(row[0]));
                dto.setTotalDwellMs(asLong(row[1]));
                dto.setTotalPageVisits(asLong(row[2]));
            }

            List<PageStat> pages = new ArrayList<>();
            List<Object[]> byRoute = pageViewRepository.aggregateByRoute(userId, since);
            if (byRoute != null) {
                for (Object[] row : byRoute) {
                    PageStat stat = new PageStat();
                    stat.setRoute(asString(row[0]));
                    stat.setLabel(routeCatalog.labelFor(stat.getRoute()));
                    stat.setVisibleMs(asLong(row[1]));
                    stat.setDwellMs(asLong(row[2]));
                    stat.setVisits(asLong(row[3]));
                    stat.setAvgVisibleMs(asLong(row[4]));
                    stat.setLastVisitAt(asDateTime(row[5]));
                    pages.add(stat);
                }
            }
            dto.setPages(pages);

            dto.setPageTimePerDay(toPageDayBuckets(
                    pageViewRepository.aggregatePerDay(userId, since)));
            dto.setEntryRoutes(toEntrySlices(
                    pageViewRepository.findEntryRoutes(userId, since)));

            // Engagement ratio: how much of the wall time the tab was actually in front of
            // someone. A low number means these totals are mostly parked tabs.
            if (dto.getTotalDwellMs() > 0) {
                dto.setEngagementRatio(
                        (double) dto.getTotalVisibleMs() / (double) dto.getTotalDwellMs());
            }

            applyNeverVisited(dto, userId);
        } catch (Exception e) {
            log.warn("Could not aggregate page views for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Parts of the product this user has never opened.
     *
     * The most directly actionable thing in the window: "this customer has never opened
     * Rightsizing" is a concrete prompt for whoever owns the account. Derived by subtracting
     * the routes they have visited from the catalogue, across all time rather than the
     * selected window — a page opened once last year is still not unvisited.
     */
    private void applyNeverVisited(UserActivitySummaryDto dto, Long userId) {
        try {
            Set<String> visited = new HashSet<>(
                    pageViewRepository.findDistinctRoutesEverVisited(userId));
            List<PageStat> never = new ArrayList<>();
            for (Map.Entry<String, String> entry : routeCatalog.trackedRoutes().entrySet()) {
                if (visited.contains(entry.getKey())) {
                    continue;
                }
                PageStat stat = new PageStat();
                stat.setRoute(entry.getKey());
                stat.setLabel(entry.getValue());
                never.add(stat);
            }
            never.sort(Comparator.comparing(PageStat::getLabel,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            dto.setNeverVisited(never);
        } catch (Exception e) {
            log.warn("Could not compute unvisited routes for user {}: {}", userId, e.getMessage());
        }
    }

    // ───────────────────────────── impersonation ─────────────────────────────

    /**
     * Every "Login as" against this account, newest first.
     *
     * Not limited to the selected window. An admin entering a customer's account is exactly
     * the event nobody should have to guess a date range to find.
     */
    private void applyImpersonation(UserActivitySummaryDto dto, Long userId) {
        try {
            List<UserActivityEvent> events = eventRepository.findByUserIdAndActionOrderByCreatedAtDesc(
                    userId, UserActivityEvent.ACTION_IMPERSONATION_STARTED);
            Map<Long, String> adminNames = new HashMap<>();
            List<ImpersonationEntry> entries = new ArrayList<>();
            for (UserActivityEvent e : events) {
                ImpersonationEntry entry = new ImpersonationEntry();
                entry.setAt(e.getCreatedAt());
                entry.setActorUserId(e.getActorUserId());
                entry.setIpAddress(e.getIpAddress());
                entry.setMetadata(e.getMetadata());
                if (e.getActorUserId() != null) {
                    entry.setActorLabel(adminNames.computeIfAbsent(e.getActorUserId(),
                            id -> userRepository.findById(id)
                                    .map(u -> u.getEmail() != null ? u.getEmail() : u.getUsername())
                                    .orElse("user " + id)));
                } else {
                    entry.setActorLabel("unrecorded");
                }
                entries.add(entry);
            }
            dto.setImpersonations(entries);
        } catch (Exception e) {
            log.warn("Could not load impersonation history for user {}: {}", userId, e.getMessage());
        }
    }

    // ───────────────────────────────── timeline ─────────────────────────────────

    private void applyTimeline(UserActivitySummaryDto dto, Long userId) {
        try {
            List<TimelineEntry> entries = new ArrayList<>();
            eventRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, TIMELINE_LIMIT))
                    .forEach(e -> {
                        TimelineEntry entry = new TimelineEntry();
                        entry.setAt(e.getCreatedAt());
                        entry.setAction(e.getAction());
                        entry.setAuthProvider(e.getAuthProvider());
                        entry.setIpAddress(e.getIpAddress());
                        entry.setOutcome(e.getOutcome());
                        entry.setFailureReason(e.getFailureReason());
                        entry.setActorUserId(e.getActorUserId());
                        entries.add(entry);
                    });
            dto.setTimeline(entries);
        } catch (Exception e) {
            log.warn("Could not load timeline for user {}: {}", userId, e.getMessage());
        }
    }

    // ───────────────────────────────── mapping ─────────────────────────────────

    private List<DayBucket> toDayBuckets(List<Object[]> rows) {
        List<DayBucket> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Object[] row : rows) {
            DayBucket bucket = new DayBucket();
            bucket.setDay(asLocalDate(row[0]));
            bucket.setLogins(asLong(row[1]));
            out.add(bucket);
        }
        return out;
    }

    private List<DayBucket> toPageDayBuckets(List<Object[]> rows) {
        List<DayBucket> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Object[] row : rows) {
            DayBucket bucket = new DayBucket();
            bucket.setDay(asLocalDate(row[0]));
            bucket.setVisibleMs(asLong(row[1]));
            bucket.setVisits(asLong(row[2]));
            out.add(bucket);
        }
        return out;
    }

    private List<HourBucket> toHourBuckets(List<Object[]> rows) {
        // Always 24 entries, so the chart has a stable x-axis instead of collapsing to
        // whichever hours happen to have data.
        Map<Integer, Long> counts = new HashMap<>();
        if (rows != null) {
            for (Object[] row : rows) {
                counts.put((int) asLong(row[0]), asLong(row[1]));
            }
        }
        List<HourBucket> out = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            HourBucket bucket = new HourBucket();
            bucket.setHour(hour);
            bucket.setLogins(counts.getOrDefault(hour, 0L));
            out.add(bucket);
        }
        return out;
    }

    private List<ProviderSlice> toProviderSlices(List<Object[]> rows) {
        List<ProviderSlice> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Object[] row : rows) {
            ProviderSlice slice = new ProviderSlice();
            slice.setProvider(asString(row[0]));
            slice.setLogins(asLong(row[1]));
            out.add(slice);
        }
        return out;
    }

    private List<IpSlice> toIpSlices(List<Object[]> rows) {
        List<IpSlice> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Object[] row : rows) {
            IpSlice slice = new IpSlice();
            slice.setIpAddress(asString(row[0]));
            slice.setHits(asLong(row[1]));
            slice.setLastSeenAt(asDateTime(row[2]));
            out.add(slice);
        }
        return out;
    }

    private List<PageStat> toEntrySlices(List<Object[]> rows) {
        List<PageStat> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Object[] row : rows) {
            PageStat stat = new PageStat();
            stat.setRoute(asString(row[0]));
            stat.setLabel(routeCatalog.labelFor(stat.getRoute()));
            stat.setVisits(asLong(row[1]));
            out.add(stat);
        }
        return out;
    }

    /**
     * Groups raw user-agent strings into coarse device buckets.
     *
     * Deliberately crude. Full UA parsing needs a maintained database and the window only
     * needs to distinguish "their work laptop" from "their phone"; anything unrecognised is
     * reported as Other rather than guessed at.
     */
    private List<DeviceSlice> toDeviceSlices(List<Object[]> rows) {
        Map<String, Long> grouped = new LinkedHashMap<>();
        if (rows != null) {
            for (Object[] row : rows) {
                String label = describeUserAgent(asString(row[0]));
                grouped.merge(label, asLong(row[1]), Long::sum);
            }
        }
        List<DeviceSlice> out = new ArrayList<>();
        grouped.forEach((label, hits) -> {
            DeviceSlice slice = new DeviceSlice();
            slice.setLabel(label);
            slice.setHits(hits);
            out.add(slice);
        });
        out.sort(Comparator.comparingLong(DeviceSlice::getHits).reversed());
        return out;
    }

    static String describeUserAgent(String agent) {
        if (agent == null || agent.isBlank()) {
            return "Unknown";
        }
        String ua = agent.toLowerCase();
        String os = ua.contains("windows") ? "Windows"
                : ua.contains("iphone") || ua.contains("ipad") ? "iOS"
                : ua.contains("android") ? "Android"
                : ua.contains("mac os") || ua.contains("macintosh") ? "macOS"
                : ua.contains("linux") ? "Linux"
                : "Other";
        // Order matters: Edge and Chrome both claim Safari, and Edge claims Chrome.
        String browser = ua.contains("edg/") || ua.contains("edge") ? "Edge"
                : ua.contains("opr/") || ua.contains("opera") ? "Opera"
                : ua.contains("firefox") ? "Firefox"
                : ua.contains("chrome") || ua.contains("crios") ? "Chrome"
                : ua.contains("safari") ? "Safari"
                : "Other";
        return browser + " on " + os;
    }

    private long longestGapDays(List<DayBucket> buckets, int window) {
        if (buckets == null || buckets.isEmpty()) {
            return window;
        }
        List<LocalDate> days = buckets.stream()
                .map(DayBucket::getDay)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        long longest = 0;
        for (int i = 1; i < days.size(); i++) {
            long gap = ChronoUnit.DAYS.between(days.get(i - 1), days.get(i)) - 1;
            longest = Math.max(longest, gap);
        }
        // Include the trailing gap up to today, which is usually the interesting one.
        longest = Math.max(longest,
                ChronoUnit.DAYS.between(days.get(days.size() - 1), LocalDate.now()));
        return longest;
    }

    // ───────────────────────────── coercion helpers ─────────────────────────────
    // Native queries return driver-specific numeric and temporal types, so every
    // projection goes through these rather than casting at the call site.

    private static long asLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalDateTime asDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.util.Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.systemDefault());
        }
        return null;
    }

    private static LocalDate asLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate ld) {
            return ld;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        return Optional.ofNullable(asDateTime(value)).map(LocalDateTime::toLocalDate).orElse(null);
    }
}
