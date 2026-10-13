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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the policy that applies to a workload: its stored row, or the defaults.
 *
 * <p>Reads, plus exactly one write: {@link #recordApplied}, which stamps
 * {@code last_applied_at} after a confirmed apply so the engine's cooldown guard is real. Setting
 * the rest of a policy is endpoint 2, which is still contract-only.
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

    /** The policy for one workload, falling back to the bare Java defaults when it has no row. */
    public WorkloadPolicy resolve(Long clusterConfigId, WorkloadRef ref) {
        return resolve(clusterConfigId, ref, WorkloadPolicyDefaults.policy());
    }

    /**
     * The policy for one workload, falling back to {@code whenAbsent} when it has no row.
     *
     * <p>Callers that act on a workload pass the environment's configured defaults
     * ({@code VerticalMetricsProperties#defaultsPolicy()}), the same value the read path reports, so
     * what is acted on and recorded is what the operator was shown.
     */
    public WorkloadPolicy resolve(Long clusterConfigId, WorkloadRef ref, WorkloadPolicy whenAbsent) {
        Optional<WorkloadVerticalPolicy> row =
                repository.findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                        clusterConfigId, ref.namespace(), ref.kind(), ref.name());
        return row.map(WorkloadPolicyService::toPolicy)
                .orElseGet(() -> whenAbsent == null ? WorkloadPolicyDefaults.policy() : whenAbsent);
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

    /**
     * Records that a change was just applied to this workload, so the engine's cooldown guard has
     * something real to read.
     *
     * <p>An upsert, because policy is <strong>absent by default</strong>: most workloads have no row
     * at all, and an apply against one of them still has to be remembered. The created row carries
     * <strong>every</strong> value of the policy that was <em>actually in force</em> at the time —
     * mode, window, percentile, headroom, floors, bands, cooldown and the rest — so after an Apply the
     * read endpoint reports the same policy it reported before, and keeps publishing
     * recommendations. Copying only the mode left the rest at the column defaults, and a caller that
     * passed the bare Java defaults wrote {@code OFF}: one Apply silently stopped the workload.
     *
     * <p>Known limit, deliberately not fixed without a migration: the row's existence still makes
     * {@code policySource} read {@code WORKLOAD_ROW}, so a later change to the environment's default
     * mode or window no longer reaches a workload that has been applied once. Every policy column is
     * {@code NOT NULL}, so recording "inherit the defaults" needs a schema change.
     *
     * <p>An existing row is never rewritten: only {@code last_applied_at} changes.
     *
     * <p>Tenant-scoped by {@code client_id} <em>and</em> {@code cluster_config_id}: the unique
     * constraint is on the cluster scope, and {@code client_id} is denormalised on the row precisely
     * so scoping never traverses the ambiguous {@code cloud_account_id} column.
     *
     * <p>No new migration: {@code last_applied_at} already exists on
     * {@code V20261010_1__vertical_workload_policy.sql}.
     *
     * @param inForce the policy that was resolved for this apply, used only when a row has to be
     *                created; null falls back to the bare defaults, whose mode is {@code OFF}
     */
    @Transactional
    public void recordApplied(Long clientId, Long clusterConfigId, WorkloadRef ref,
                              WorkloadPolicy inForce, Instant appliedAt) {
        if (clusterConfigId == null || ref == null || appliedAt == null) {
            throw new IllegalArgumentException(
                    "clusterConfigId, ref and appliedAt are mandatory to record an apply");
        }
        WorkloadVerticalPolicy row = repository
                .findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                        clusterConfigId, ref.namespace(), ref.kind(), ref.name())
                .orElseGet(() -> newRow(clientId, clusterConfigId, ref, inForce));
        if (row.getClientId() == null) {
            row.setClientId(clientId);
        }
        row.setLastAppliedAt(appliedAt);
        repository.save(row);
        logger.info("Recorded an apply of {} on cluster config {} at {}", ref.describe(),
                clusterConfigId, appliedAt);
    }

    /**
     * Sets a workload's policy {@code mode}, upserting the row, and returns the mode it had before.
     *
     * <p>This is endpoint 2's mode-only write: it changes <strong>only</strong> the {@code mode}
     * column and leaves every other policy field untouched. On an existing row the stored window,
     * percentile, floors, bands and cooldown are preserved exactly; on a <em>new</em> row they are
     * taken from {@code inForce} — the environment's configured defaults, the same policy endpoint 1
     * reports and {@link #recordApplied} uses — so a workload switched to {@code RECOMMEND} or
     * {@code AUTO} through the product carries the policy the operator was shown, not the bare Java
     * defaults whose mode is {@code OFF}. The full window / percentile / headroom editor is a later
     * task; this writes the mode and nothing else.
     *
     * <p>Tenant-scoped by {@code client_id} <em>and</em> {@code cluster_config_id}, the same
     * unambiguous pair the rest of this table uses — never the overloaded {@code cloud_account_id}.
     *
     * <p>No migration: the {@code mode} column already exists on
     * {@code vertical_workload_policy}.
     *
     * @param inForce the policy resolved for this workload, used only when a row has to be created;
     *                null falls back to the bare defaults, whose mode is {@code OFF}
     * @return the mode the workload had before this call — a stored row's mode, or {@code inForce}'s
     *         (the configured default) when no row existed
     */
    @Transactional
    public WorkloadMode setMode(Long clientId, Long clusterConfigId, WorkloadRef ref,
                                WorkloadMode newMode, WorkloadPolicy inForce) {
        if (clusterConfigId == null || ref == null || newMode == null) {
            throw new IllegalArgumentException(
                    "clusterConfigId, ref and newMode are mandatory to set a workload's mode");
        }
        Optional<WorkloadVerticalPolicy> existing = repository
                .findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                        clusterConfigId, ref.namespace(), ref.kind(), ref.name());
        WorkloadMode previous;
        WorkloadVerticalPolicy row;
        if (existing.isPresent()) {
            row = existing.get();
            previous = modeOf(row);
        } else {
            row = newRow(clientId, clusterConfigId, ref, inForce);
            // The mode the workload had before its first row is the default endpoint 1 reported.
            previous = inForce == null ? WorkloadMode.OFF : inForce.mode();
        }
        if (row.getClientId() == null) {
            row.setClientId(clientId);
        }
        row.setMode(newMode.name());
        repository.save(row);
        logger.info("Set vertical policy mode of {} on cluster config {} from {} to {}",
                ref.describe(), clusterConfigId, previous, newMode);
        return previous;
    }

    private static WorkloadMode modeOf(WorkloadVerticalPolicy row) {
        return mode(row);
    }

    private static WorkloadVerticalPolicy newRow(Long clientId, Long clusterConfigId, WorkloadRef ref,
                                                 WorkloadPolicy inForce) {
        WorkloadVerticalPolicy row = new WorkloadVerticalPolicy();
        row.setClientId(clientId);
        row.setClusterConfigId(clusterConfigId);
        row.setNamespace(ref.namespace());
        row.setWorkloadKind(ref.kind());
        row.setWorkloadName(ref.name());
        WorkloadPolicy policy = inForce == null ? WorkloadPolicyDefaults.policy() : inForce;
        row.setMode(policy.mode().name());
        row.setHeadroomPct(policy.headroomPct());
        row.setPercentile(policy.percentile());
        row.setWindowHours(policy.windowHours());
        row.setCpuFloorMillicores(policy.cpuFloorMillis());
        row.setMemoryFloorBytes(policy.memFloorBytes());
        // The table holds one relative threshold per direction, shared by CPU and memory, which is
        // also how toPolicy reads it back. A no-row policy is always the defaults, whose CPU and
        // memory percentages are equal, so nothing is lost; WorkloadPolicyServiceTest round-trips it.
        row.setMinIncreasePct(policy.cpuIncrease().minPercent());
        row.setMinDecreasePct(policy.cpuDecrease().minPercent());
        row.setMinIncreaseCpuMillicores(Math.toIntExact(policy.cpuIncrease().minAbsolute()));
        row.setMinDecreaseCpuMillicores(Math.toIntExact(policy.cpuDecrease().minAbsolute()));
        row.setMinIncreaseMemoryBytes(policy.memIncrease().minAbsolute());
        row.setMinDecreaseMemoryBytes(policy.memDecrease().minAbsolute());
        row.setApplyMethod(policy.applyMethod().name());
        row.setCooldownMinutes(policy.cooldownMinutes());
        row.setMaxLimitIncreaseFactor(policy.maxLimitIncreaseFactor());
        row.setProtectSingleReplica(policy.protectSingleReplica());
        row.setNodeCapacityFraction(policy.nodeCapacityFraction());
        return row;
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
