package com.xammer.cloud.controller.autospotting;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.dto.autospotting.v2.AnalyticsDto;
import com.xammer.cloud.dto.autospotting.v2.AnalyticsDto.Figures;
import com.xammer.cloud.dto.autospotting.v2.AnalyticsDto.GroupAnalytics;
import com.xammer.cloud.dto.autospotting.v2.AnalyticsDto.TypeFigures;
import com.xammer.cloud.repository.autospotting.SpotGroupEventRepository;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@code GET /api/autospotting/v2/accounts/{accountId}/analytics?from&to} (plan-phase-14.md task 14.2,
 * PD-08, AC-EVT-06): the read side of {@code spot_group_event}, per group and instance type. The range
 * is whole UTC days, default the last 30 days, at most 92. A metric with no event source for the account
 * is {@code null} and listed in {@code notTracked}, never 0.
 */
@RestController
@RequestMapping("/api/autospotting/v2")
public class AnalyticsController {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 92;

    private static final String UNKNOWN_TYPE = "unknown";

    private final CloudAccountAccessService accountAccessService;
    private final SpotGroupRepository groups;
    private final SpotGroupEventRepository events;
    private final Clock clock;

    @Autowired
    public AnalyticsController(CloudAccountAccessService accountAccessService, SpotGroupRepository groups,
                               SpotGroupEventRepository events) {
        this(accountAccessService, groups, events, Clock.systemUTC());
    }

    AnalyticsController(CloudAccountAccessService accountAccessService, SpotGroupRepository groups,
                        SpotGroupEventRepository events, Clock clock) {
        this.accountAccessService = accountAccessService;
        this.groups = groups;
        this.events = events;
        this.clock = clock;
    }

    @GetMapping("/accounts/{accountId}/analytics")
    public ResponseEntity<AnalyticsDto> analytics(
            @PathVariable Long accountId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal ClientUserDetails user) {
        CloudAccount account = accountAccessService.requireAccess(user, accountId);
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED",
                    "The start date must not be after the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED",
                    "The range can be at most " + MAX_DAYS + " days.");
        }
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder().cloudAccountId(account.getId()).open()) {
            var fromInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
            var toInstant = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            List<SpotGroup> accountGroups = groups.findByCloudAccountId(account.getId());
            List<UUID> ids = accountGroups.stream().map(SpotGroup::getId).toList();
            boolean tracked = !ids.isEmpty() && events.existsByGroupIdIn(ids);
            if (!tracked) {
                return noStore(new AnalyticsDto(fromInstant, toInstant,
                        List.of(AnalyticsDto.SPOT_LAUNCHES, AnalyticsDto.LAUNCH_FAILURES, AnalyticsDto.INTERRUPTIONS,
                                AnalyticsDto.REBALANCE_RECOMMENDATIONS, AnalyticsDto.SUCCESS_RATE),
                        new Figures(null, null, Map.of(), null, null, null), List.of()));
            }
            Map<UUID, Map<String, Acc>> perGroupType = new HashMap<>();
            for (Object[] r : events.countByGroupAndType(ids, fromInstant, toInstant)) {
                UUID groupId = (UUID) r[0];
                String type = r[2] == null || ((String) r[2]).isBlank() ? UNKNOWN_TYPE : (String) r[2];
                perGroupType.computeIfAbsent(groupId, k -> new TreeMap<>())
                        .computeIfAbsent(type, k -> new Acc())
                        .add((String) r[1], (String) r[3], (String) r[4], ((Number) r[5]).longValue());
            }
            Acc total = new Acc();
            List<GroupAnalytics> out = new ArrayList<>();
            for (SpotGroup g : accountGroups) {
                Map<String, Acc> byType = perGroupType.get(g.getId());
                if (byType == null) {
                    continue;
                }
                Acc sum = new Acc();
                List<TypeFigures> types = new ArrayList<>();
                byType.forEach((type, acc) -> {
                    sum.merge(acc);
                    types.add(new TypeFigures(type, acc.figures()));
                });
                total.merge(sum);
                String key = g.getGroupKey();
                int slash = key.indexOf('/');
                out.add(new GroupAnalytics(g.getId(), key, slash > 0 ? key.substring(slash + 1) : key,
                        slash > 0 ? key.substring(0, slash) : null, sum.figures(), types));
            }
            out.sort(Comparator.comparing(GroupAnalytics::groupKey));
            return noStore(new AnalyticsDto(fromInstant, toInstant, List.of(), total.figures(), out));
        }
    }

    /** Running counts for one scope. */
    private static final class Acc {
        long spotLaunches;
        long failures;
        long interruptions;
        long rebalance;
        final Map<String, Long> byCause = new LinkedHashMap<>();

        void add(String eventType, String lifecycle, String failureClass, long n) {
            if (eventType == null) {
                return;
            }
            switch (eventType) {
                case "LAUNCH" -> {
                    if ("spot".equalsIgnoreCase(lifecycle)) {
                        spotLaunches += n;
                    }
                }
                case "LAUNCH_FAILED" -> {
                    failures += n;
                    byCause.merge(failureClass == null || failureClass.isBlank() ? "OTHER" : failureClass, n, Long::sum);
                }
                case "INTERRUPTION_WARNING", "PREEMPTION" -> interruptions += n;
                case "REBALANCE_RECOMMENDATION" -> rebalance += n;
                default -> { /* other event types have no figure here */ }
            }
        }

        void merge(Acc o) {
            spotLaunches += o.spotLaunches;
            failures += o.failures;
            interruptions += o.interruptions;
            rebalance += o.rebalance;
            o.byCause.forEach((k, v) -> byCause.merge(k, v, Long::sum));
        }

        Figures figures() {
            long denominator = spotLaunches + failures;
            Double rate = denominator == 0 ? null : Math.round(spotLaunches * 1000.0 / denominator) / 10.0;
            return new Figures(spotLaunches, failures, byCause, interruptions, rebalance, rate);
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
