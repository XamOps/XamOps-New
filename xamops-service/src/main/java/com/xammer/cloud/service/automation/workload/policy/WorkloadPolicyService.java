package com.xammer.cloud.service.automation.workload.policy;

import com.xammer.cloud.domain.WorkloadVerticalPolicy;
import com.xammer.cloud.repository.WorkloadVerticalPolicyRepository;
import com.xammer.cloud.service.automation.workload.ApplyMethod;
import com.xammer.cloud.service.automation.workload.HysteresisBand;
import com.xammer.cloud.service.automation.workload.WorkloadMode;
import com.xammer.cloud.service.automation.workload.WorkloadPolicy;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the policy that applies to a workload: its stored row, or the defaults.
 *
 * <p>Read-only in this run — writing a policy is endpoint 2, which is contract-only.
 *
 * <p><strong>No row means defaults</strong>, with {@link WorkloadPolicy.Source#DEFAULTS} recorded so
 * the API can report whether a human chose the numbers. That is what makes this feature safe to ship
 * with no backfill: a cluster registered tomorrow behaves exactly like one registered last year, and
 * the default mode is {@code OFF}.
 */
@Service
public class WorkloadPolicyService {

    private static final Logger logger = LoggerFactory.getLogger(WorkloadPolicyService.class);

    private final WorkloadVerticalPolicyRepository repository;

    public WorkloadPolicyService(WorkloadVerticalPolicyRepository repository) {
        this.repository = repository;
    }

    /** The policy for one workload. */
    public WorkloadPolicy resolve(Long clusterConfigId, WorkloadRef ref) {
        Optional<WorkloadVerticalPolicy> row =
                repository.findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                        clusterConfigId, ref.namespace(), ref.kind(), ref.name());
        return row.map(WorkloadPolicyService::toPolicy).orElseGet(WorkloadPolicyDefaults::policy);
    }

    /**
     * Every stored policy for a cluster, keyed {@code namespace/kind/name}, for the read endpoint's
     * single pass over a cluster's workloads. Workloads absent from the map take the defaults.
     */
    public Map<String, WorkloadPolicy> resolveAll(Long clusterConfigId) {
        List<WorkloadVerticalPolicy> rows = repository.findByClusterConfigId(clusterConfigId);
        Map<String, WorkloadPolicy> byKey = new HashMap<>();
        for (WorkloadVerticalPolicy row : rows) {
            byKey.put(key(row.getNamespace(), row.getWorkloadKind(), row.getWorkloadName()), toPolicy(row));
        }
        return byKey;
    }

    /** The lookup key {@link #resolveAll} uses. */
    public static String key(String namespace, String kind, String name) {
        return namespace + "/" + kind + "/" + name;
    }

    public static String key(WorkloadRef ref) {
        return key(ref.namespace(), ref.kind(), ref.name());
    }

    /**
     * Maps a stored row onto the engine's policy record.
     *
     * <p>An unrecognised {@code mode} or {@code apply_method} falls back to the <em>safe</em> value
     * ({@code OFF} / {@code IN_PLACE}) with a warning, rather than throwing. A row written by a
     * future version of the product must not be able to take the read endpoint down, and the safe
     * value is the one that does nothing.
     */
    private static WorkloadPolicy toPolicy(WorkloadVerticalPolicy row) {
        return new WorkloadPolicy(
                mode(row),
                row.getHeadroomPct(),
                row.getPercentile(),
                row.getWindowHours(),
                row.getCpuFloorMillicores(),
                row.getMemoryFloorBytes(),
                new HysteresisBand(row.getMinIncreasePct(), row.getMinIncreaseCpuMillicores()),
                new HysteresisBand(row.getMinDecreasePct(), row.getMinDecreaseCpuMillicores()),
                new HysteresisBand(row.getMinIncreasePct(), row.getMinIncreaseMemoryBytes()),
                new HysteresisBand(row.getMinDecreasePct(), row.getMinDecreaseMemoryBytes()),
                applyMethod(row),
                row.getCooldownMinutes(),
                row.getMaxLimitIncreaseFactor(),
                Boolean.TRUE.equals(row.getProtectSingleReplica()),
                row.getNodeCapacityFraction(),
                row.getLastAppliedAt(),
                WorkloadPolicy.Source.WORKLOAD_ROW);
    }

    private static WorkloadMode mode(WorkloadVerticalPolicy row) {
        String stored = row.getMode();
        if (stored == null) {
            return WorkloadMode.OFF;
        }
        try {
            return WorkloadMode.valueOf(stored.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            logger.warn("Unknown vertical policy mode '{}' on policy {}; treating it as OFF",
                    stored, row.getId());
            return WorkloadMode.OFF;
        }
    }

    private static ApplyMethod applyMethod(WorkloadVerticalPolicy row) {
        String stored = row.getApplyMethod();
        if (stored == null) {
            return ApplyMethod.IN_PLACE;
        }
        try {
            return ApplyMethod.valueOf(stored.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            logger.warn("Unknown vertical apply method '{}' on policy {}; treating it as IN_PLACE",
                    stored, row.getId());
            return ApplyMethod.IN_PLACE;
        }
    }
}
