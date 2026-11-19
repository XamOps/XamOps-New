package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.dto.autospotting.v2.GroupListDto;
import com.xammer.cloud.dto.autospotting.v2.GroupRowDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.aws.Sig;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Builds the GCP group list in LIST mode (design-gcp.md §6.3, design-ux-ops.md §4.6). Read only.
 *
 * <p>One {@code instanceGroupManagers.aggregatedList} sweep (zonal and regional MIGs), one
 * {@code autoscalers.aggregatedList}, and one template read per distinct self-link, so the cost is
 * bounded by projects and distinct templates, not groups (NFR-04). GKE node pools are excluded from
 * the dashboard potential because {@code GCP_GKE_NODE_POOL} runs in LIST (AC-CMP-04). The whole list
 * has a budget ({@code autospotting.list.budget}); a project not finished by then returns in
 * {@code failedLocations} with code {@code TIMEOUT}. A project shared by two {@code CloudAccount}
 * rows is listed only for the lowest id and reported {@code DUPLICATE_PROVIDER_ACCOUNT} for the other.
 */
@Component
public class GcpGroupLister {
    private static final Logger log = LoggerFactory.getLogger(GcpGroupLister.class);

    private final GcpViewSource views;
    private final GcpEligibilityEvaluator evaluator;
    private final GcpMigSpotProvider provider;
    private final GroupLookup groupLookup;
    private final CloudAccountRepository accounts;
    private final Duration budget;
    private final Clock clock;
    private final ExecutorService executor;

    @Autowired
    public GcpGroupLister(GcpViewSource views, GcpEligibilityEvaluator evaluator, GcpMigSpotProvider provider,
                          GroupLookup groupLookup, CloudAccountRepository accounts,
                          @Value("${autospotting.list.budget:20s}") Duration budget) {
        this(views, evaluator, provider, groupLookup, accounts, budget, Clock.systemUTC());
    }

    GcpGroupLister(GcpViewSource views, GcpEligibilityEvaluator evaluator, GcpMigSpotProvider provider,
                   GroupLookup groupLookup, CloudAccountRepository accounts, Duration budget, Clock clock) {
        this.views = views;
        this.evaluator = evaluator;
        this.provider = provider;
        this.groupLookup = groupLookup;
        this.accounts = accounts;
        this.budget = budget;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "gcp-list");
            t.setDaemon(true);
            return t;
        });
    }

    /** The list for an account, within the budget. */
    public GroupListDto list(CloudAccount account) {
        Instant start = clock.instant();
        String project = account.getGcpProjectId();
        List<GroupListDto.FailedLocation> failed = new ArrayList<>();
        if (project == null || project.isBlank()) {
            failed.add(new GroupListDto.FailedLocation("project", "NOT_FOUND"));
            return assemble(List.of(), failed, start);
        }
        if (!ownsProject(account)) {
            failed.add(new GroupListDto.FailedLocation(project, "DUPLICATE_PROVIDER_ACCOUNT"));
            return assemble(List.of(), failed, start);
        }
        Map<String, SpotGroup> rows = rowsByKey(account);
        CompletableFuture<List<GroupRowDto>> task = CompletableFuture.supplyAsync(() -> project(account, rows), executor);
        List<GroupRowDto> items = new ArrayList<>();
        try {
            items.addAll(task.get(budget.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            task.cancel(true);
            failed.add(new GroupListDto.FailedLocation(project, "TIMEOUT"));
        } catch (ExecutionException e) {
            failed.add(new GroupListDto.FailedLocation(project, GcpErrorMapper.classify(e.getCause()).name()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed.add(new GroupListDto.FailedLocation(project, "TIMEOUT"));
        }
        return assemble(items, failed, start);
    }

    /** One group's row in LIST mode (the detail view), or empty when the group no longer exists. */
    public Optional<GroupRowDto> row(CloudAccount account, String groupKey, SpotGroup spineRow) {
        GcpEligibilityEvaluator.Key key = GcpEligibilityEvaluator.split(groupKey);
        GcpReadOnlyView view = views.view(account);
        Optional<JsonNode> raw;
        try {
            raw = Optional.of(view.reads().getMig(!key.zonal(), key.location(), key.name()));
        } catch (GcpReadException e) {
            if (e.kind() == com.xammer.cloud.service.autospotting.spi.ErrorKind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
        return Optional.of(buildRow(account, view, raw.get(), spineRow, new HashMap<>(), Map.of()));
    }

    private List<GroupRowDto> project(CloudAccount account, Map<String, SpotGroup> rows) {
        GcpReadOnlyView view = views.view(account);
        List<JsonNode> migs = view.reads().aggregatedListMigs();
        Map<String, JsonNode> autoscalers = new HashMap<>();
        try {
            for (JsonNode a : view.reads().aggregatedListAutoscalers()) {
                autoscalers.put(a.path("selfLink").asText(), a);
            }
        } catch (RuntimeException e) {
            log.debug("autoscaler list failed for {}: {}", view.project(), GcpErrorMapper.classify(e));
        }
        Map<String, Sig<JsonNode>> memo = new HashMap<>();
        List<GroupRowDto> out = new ArrayList<>();
        for (JsonNode raw : migs) {
            GcpMigFacts g = GcpMigFacts.from(view.project(), raw);
            if (g.name() == null || !g.locationKnown()) {
                continue;
            }
            out.add(buildRow(account, view, raw, rows.get(g.groupKey()), memo, autoscalers));
        }
        return out;
    }

    private GroupRowDto buildRow(CloudAccount account, GcpReadOnlyView view, JsonNode raw, SpotGroup spineRow,
                                 Map<String, Sig<JsonNode>> memo, Map<String, JsonNode> autoscalers) {
        GcpMigFacts g = GcpMigFacts.from(view.project(), raw);
        GcpEligibilityEvaluator.Evaluation ev = evaluator.evaluate(account, view, g.groupKey(), null, raw, null,
                EvalMode.LIST, memo);
        EligibilityResult eligibility = ev.result();
        Double savings = null;
        boolean applicable = false;
        if (eligibility.eligible() && !ev.templates().isEmpty()) {
            JsonNode autoscaler = g.autoscalerLink() == null ? null : autoscalers.get(g.autoscalerLink());
            GcpProfiles.Evaluated chosen = GcpProfiles.evaluateNamed(g.targetSize(), null, null).stream()
                    .filter(GcpProfiles.Evaluated::preselected).findFirst()
                    .or(() -> GcpProfiles.evaluateNamed(g.targetSize(), null, null).stream().filter(p -> !p.blocked()).findFirst())
                    .orElse(null);
            if (chosen != null) {
                applicable = true;
                try {
                    savings = provider.estimate(account, g, ev, chosen.name().name().toLowerCase(java.util.Locale.ROOT),
                            chosen.atCurrent().spot()).monthlySavings();
                } catch (RuntimeException e) {
                    log.debug("list estimate failed for {}: {}", g.groupKey(), GcpErrorMapper.classify(e));
                }
            }
            if (autoscaler == null && g.autoscalerLink() != null) {
                log.trace("autoscaler {} not in the aggregated list", g.autoscalerLink());
            }
        }
        boolean externalSpot = spineRow == null && ev.templates().stream()
                .anyMatch(t -> t.resource() != null && GcpGroupDiscovery.isSpot(t.resource())
                        && !t.resource().path("description").asText("").startsWith(GcpGroupDiscovery.LEGACY_DESCRIPTION)
                        && !t.resource().path("description").asText("").startsWith(GcpGroupDiscovery.COPY_DESCRIPTION_PREFIX)
                        && !t.resource().path("properties").path("labels").has(GcpGroupDiscovery.GROUP_LABEL));
        String state = spineRow == null ? "UNMANAGED" : spineRow.getState().name();
        Integer min = null;
        Integer max = null;
        JsonNode as = g.autoscalerLink() == null ? null : autoscalers.get(g.autoscalerLink());
        if (as != null) {
            min = as.path("autoscalingPolicy").hasNonNull("minNumReplicas") ? as.path("autoscalingPolicy").path("minNumReplicas").asInt() : null;
            max = as.path("autoscalingPolicy").hasNonNull("maxNumReplicas") ? as.path("autoscalingPolicy").path("maxNumReplicas").asInt() : null;
        }
        String instanceType = GcpMigSpotProvider.machineTypeOf(ev).orElse(null);
        return GroupRowDto.of(spineRow == null ? null : spineRow.getId(), "GCP", g.groupKey(), g.location(), g.name(),
                state, spineRow != null, externalSpot, g.targetSize(), min, max, instanceType, eligibility, savings,
                applicable);
    }

    private boolean ownsProject(CloudAccount account) {
        String project = account.getGcpProjectId();
        List<CloudAccount> rows = accounts.findByAwsAccountIdOrGcpProjectId(project, project);
        List<Long> ids = rows.stream().filter(a -> project.equals(a.getGcpProjectId())).map(CloudAccount::getId)
                .filter(java.util.Objects::nonNull).toList();
        return ids.size() <= 1 || ids.stream().min(Long::compare).orElse(-1L).equals(account.getId());
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
        for (SpotGroup r : groupLookup.forAccount(Provider.GCP, account.getId())) {
            if (r.getState() != GroupState.GONE) {
                rows.put(r.getGroupKey(), r);
            }
        }
        return rows;
    }
}
