package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.dto.autospotting.v2.GroupListDto;
import com.xammer.cloud.dto.autospotting.v2.GroupRowDto;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the AWS group list in LIST mode (design-core.md §9.2, design-ux-ops.md §4.6). Read only.
 *
 * <p>Each enabled region is one task: one {@code DescribeAutoScalingGroups} sweep, then a LIST-mode
 * evaluation per group from that describe plus cached reads, so the cost is bounded by regions and
 * distinct templates, not groups (NFR-04). The whole list has a budget
 * ({@code autospotting.list.budget}, default 20 s). A region that is not finished by then is returned
 * in {@code failedLocations} with code {@code TIMEOUT} and the response is {@code partial=true}; a
 * region that cannot be read is reported with its error kind. Neither is ever read as "no groups"
 * (FR-OBS-06, NFR-03).
 */
@Component
public class AwsGroupLister {

    private static final Logger log = LoggerFactory.getLogger(AwsGroupLister.class);

    private final AwsViewSource views;
    private final AwsEligibilityEvaluator evaluator;
    private final AwsSpotProvider provider;
    private final AwsGroupStatusReader statusReader;
    private final GroupLookup groupLookup;
    private final Duration budget;
    private final Clock clock;
    private final ExecutorService executor;

    @Autowired
    public AwsGroupLister(AwsViewSource views, AwsEligibilityEvaluator evaluator, AwsSpotProvider provider,
                          AwsGroupStatusReader statusReader, GroupLookup groupLookup,
                          @Value("${autospotting.list.budget:20s}") Duration budget) {
        this(views, evaluator, provider, statusReader, groupLookup, budget, Clock.systemUTC());
    }

    AwsGroupLister(AwsViewSource views, AwsEligibilityEvaluator evaluator, AwsSpotProvider provider,
                   AwsGroupStatusReader statusReader, GroupLookup groupLookup, Duration budget, Clock clock) {
        this.views = views;
        this.evaluator = evaluator;
        this.provider = provider;
        this.statusReader = statusReader;
        this.groupLookup = groupLookup;
        this.budget = budget;
        this.clock = clock;
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "as-list-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(8, tf);
    }

    /** The list for an account, within the budget. */
    public GroupListDto list(CloudAccount account) {
        Instant start = clock.instant();
        Map<String, SpotGroup> rows = rowsByKey(account);
        List<GroupListDto.FailedLocation> failed = new ArrayList<>();
        List<String> regions;
        try {
            regions = AwsRegions.enabled(views, account);
        } catch (RuntimeException e) {
            failed.add(new GroupListDto.FailedLocation("regions", AwsErrors.classify(e).name()));
            return assemble(List.of(), failed, start);
        }
        Map<String, CompletableFuture<List<GroupRowDto>>> futures = new HashMap<>();
        for (String region : regions) {
            futures.put(region, CompletableFuture.supplyAsync(() -> region(account, region, rows), executor));
        }
        long deadline = System.nanoTime() + budget.toNanos();
        List<GroupRowDto> items = new ArrayList<>();
        for (String region : regions) {
            CompletableFuture<List<GroupRowDto>> f = futures.get(region);
            long remaining = Math.max(0, deadline - System.nanoTime());
            try {
                items.addAll(f.get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                f.cancel(true);
                failed.add(new GroupListDto.FailedLocation(region, "TIMEOUT"));
            } catch (ExecutionException e) {
                failed.add(new GroupListDto.FailedLocation(region, AwsErrors.classify(e.getCause()).name()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed.add(new GroupListDto.FailedLocation(region, "TIMEOUT"));
            }
        }
        return assemble(items, failed, start);
    }

    /** One group's row in LIST mode (the detail view), or empty when the group no longer exists. */
    public Optional<GroupRowDto> row(CloudAccount account, String groupKey, SpotGroup spineRow) {
        String[] parts = AwsEligibilityEvaluator.split(groupKey);
        AwsReadOnlyView view = views.view(account, parts[0]);
        Optional<AutoScalingGroup> raw = statusReader.describeGroup(view, parts[1]);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(buildRow(account, view, parts[0], raw.get(), spineRow));
    }

    private List<GroupRowDto> region(CloudAccount account, String region, Map<String, SpotGroup> rows) {
        AwsReadOnlyView view = views.view(account, region);
        List<AutoScalingGroup> described = AwsGroupDiscovery.describeAll(view);
        List<GroupRowDto> out = new ArrayList<>();
        for (AutoScalingGroup raw : described) {
            out.add(buildRow(account, view, region, raw, rows.get(region + "/" + raw.autoScalingGroupName())));
        }
        return out;
    }

    private GroupRowDto buildRow(CloudAccount account, AwsReadOnlyView view, String region, AutoScalingGroup raw,
                                 SpotGroup spineRow) {
        AwsEligibilityEvaluator.Evaluation ev = evaluator.evaluate(account, region, raw.autoScalingGroupName(), raw,
                null, EvalMode.LIST, view);
        AwsGroupFacts g = ev.group();
        // Potential savings: the best non-blocked profile at the current size, priced from caches.
        Double savings = null;
        boolean applicable = false;
        if (g != null) {
            AwsProfiles.EvaluatedProfile chosen = AwsProfiles.evaluateNamed(g.desired()).stream()
                    .filter(p -> p.preselected()).findFirst()
                    .or(() -> AwsProfiles.evaluateNamed(g.desired()).stream().filter(p -> !p.blocked()).findFirst())
                    .orElse(null);
            if (chosen != null && ev.launch() != null) {
                applicable = true;
                try {
                    savings = provider.estimate(account, g, ev, chosen.name().name().toLowerCase(java.util.Locale.ROOT),
                            chosen.spotCount()).monthlySavings();
                } catch (RuntimeException e) {
                    log.debug("list estimate failed for {}: {}", g.groupKey(), AwsErrors.classify(e));
                }
            } else if (chosen != null) {
                applicable = true; // priceable in principle, but the template read did not complete
            }
        }
        boolean externalSpot = ev.result().blockers().stream().anyMatch(b -> "EXTERNAL_SPOT".equals(b.code()));
        String state = spineRow == null ? "UNMANAGED" : spineRow.getState().name();
        EligibilityResult eligibility = ev.result();
        return GroupRowDto.of(spineRow == null ? null : spineRow.getId(), "AWS", region + "/" + raw.autoScalingGroupName(),
                region, raw.autoScalingGroupName(), state, spineRow != null, externalSpot,
                g == null ? null : g.desired(), g == null ? null : g.min(), g == null ? null : g.max(),
                ev.launch() == null ? null : ev.launch().instanceType(), eligibility, savings, applicable);
    }

    private GroupListDto assemble(List<GroupRowDto> items, List<GroupListDto.FailedLocation> failed, Instant start) {
        items = new ArrayList<>(items);
        items.sort((a, b) -> a.groupKey().compareTo(b.groupKey()));
        Map<String, Integer> counts = new TreeMap<>();
        int eligible = 0;
        int blocked = 0;
        int unknown = 0;
        double total = 0;
        boolean anyPriced = false;
        for (GroupRowDto r : items) {
            if (r.enableAllowed()) {
                eligible++;
            } else {
                blocked++;
            }
            r.blockers().forEach(b -> counts.merge(b.code(), 1, Integer::sum));
            if (r.savingsUnknown()) {
                unknown++;
            } else if (r.monthlySavings() != null) {
                total += r.monthlySavings();
                anyPriced = true;
            }
        }
        GroupListDto.Kpis kpis = new GroupListDto.Kpis(items.size(), eligible, blocked,
                anyPriced ? Math.round(total * 100.0) / 100.0 : null, unknown);
        return new GroupListDto(items, !failed.isEmpty(), failed, clock.instant(), counts, kpis);
    }

    private Map<String, SpotGroup> rowsByKey(CloudAccount account) {
        Map<String, SpotGroup> rows = new HashMap<>();
        for (SpotGroup r : groupLookup.forAccount(Provider.AWS, account.getId())) {
            if (r.getState() != com.xammer.cloud.domain.autospotting.GroupState.GONE) {
                rows.put(r.getGroupKey(), r);
            }
        }
        return rows;
    }

    /** Whether a failed location's error kind is a permission problem (the UI shows guidance, never "no groups"). */
    static boolean isPermission(ErrorKind kind) {
        return kind == ErrorKind.PERMISSION;
    }
}
