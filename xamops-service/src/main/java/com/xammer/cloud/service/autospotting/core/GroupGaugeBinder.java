package com.xammer.cloud.service.autospotting.core;

import com.xammer.cloud.domain.autospotting.DriftStatus;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Binds the DB-derived group gauges {@code autospotting.groups} and {@code autospotting.groups.drift}
 * through {@link AutoSpottingMetrics}, reading the counts from {@link SpotGroupRepository} behind a
 * 60 s per-instance cache (design-ux-ops.md §6.2, plan-phase-10.md task 10.1). It lives in
 * {@code ..service.autospotting.core..} because only the core (and the controllers) may touch the
 * spine repositories (ArchUnit §9.1); {@link AutoSpottingMetrics} itself stays repository-free and
 * only owns the meter registration.
 *
 * <p>One gauge per (provider, state) and (provider, MINOR|MATERIAL) cell is registered once at
 * startup; a cell with no rows reports 0. Every instance reports the same value, so the §6.4 alarms
 * use the Maximum statistic (never Sum). The cache means a Micrometer scrape of all the group gauges
 * costs at most one {@code count(*)}-style read per provider per minute per instance.
 *
 * <p><b>Dark.</b> At this phase no group is managed in production, so every gauge reads 0; the bean
 * only registers meters and queries the spine, never a cloud.
 */
@Component
public class GroupGaugeBinder {

    private final SpotGroupRepository groups;
    private final AutoSpottingMetrics metrics;
    private final GroupCountCache cache;

    public GroupGaugeBinder(SpotGroupRepository groups, AutoSpottingMetrics metrics) {
        this.groups = groups;
        this.metrics = metrics;
        this.cache = new GroupCountCache();
    }

    @PostConstruct
    void bind() {
        for (Provider provider : Provider.values()) {
            for (GroupState state : GroupState.values()) {
                metrics.bindGroupStateGauge(provider, state, () -> cache.groupCount(provider, state));
            }
            for (DriftStatus severity : List.of(DriftStatus.MINOR, DriftStatus.MATERIAL)) {
                metrics.bindGroupDriftGauge(provider, severity, () -> cache.driftCount(provider, severity));
            }
        }
    }

    /** A 60 s cache over {@code spot_group} group-by counts (design-ux-ops.md §6.2). */
    private final class GroupCountCache {
        private volatile long loadedAtMillis;
        private volatile Map<Provider, Map<GroupState, Long>> byState = new EnumMap<>(Provider.class);
        private volatile Map<Provider, Map<DriftStatus, Long>> byDrift = new EnumMap<>(Provider.class);

        double groupCount(Provider provider, GroupState state) {
            refreshIfStale();
            return byState.getOrDefault(provider, Map.of()).getOrDefault(state, 0L);
        }

        double driftCount(Provider provider, DriftStatus severity) {
            refreshIfStale();
            return byDrift.getOrDefault(provider, Map.of()).getOrDefault(severity, 0L);
        }

        private void refreshIfStale() {
            long now = System.currentTimeMillis();
            if (loadedAtMillis != 0 && now - loadedAtMillis < AutoSpottingMetrics.GAUGE_CACHE_TTL.toMillis()) {
                return;
            }
            synchronized (this) {
                if (loadedAtMillis != 0 && now - loadedAtMillis < AutoSpottingMetrics.GAUGE_CACHE_TTL.toMillis()) {
                    return;
                }
                load();
                loadedAtMillis = System.currentTimeMillis();
            }
        }

        private void load() {
            Map<Provider, Map<GroupState, Long>> state = new EnumMap<>(Provider.class);
            Map<Provider, Map<DriftStatus, Long>> drift = new EnumMap<>(Provider.class);
            for (Provider provider : Provider.values()) {
                Map<GroupState, Long> stateCounts = new EnumMap<>(GroupState.class);
                Map<DriftStatus, Long> driftCounts = new EnumMap<>(DriftStatus.class);
                // One pass per state: the per-state count, plus the state-agnostic MINOR/MATERIAL drift
                // count (§6.2 "groups with drift_status MINOR or MATERIAL").
                for (GroupState s : GroupState.values()) {
                    List<SpotGroup> rows = groups.findByProviderAndState(provider, s);
                    if (!rows.isEmpty()) {
                        stateCounts.put(s, (long) rows.size());
                    }
                    for (SpotGroup g : rows) {
                        DriftStatus ds = g.getDriftStatus();
                        if (ds == DriftStatus.MINOR || ds == DriftStatus.MATERIAL) {
                            driftCounts.merge(ds, 1L, Long::sum);
                        }
                    }
                }
                state.put(provider, stateCounts);
                drift.put(provider, driftCounts);
            }
            this.byState = state;
            this.byDrift = drift;
        }
    }
}
