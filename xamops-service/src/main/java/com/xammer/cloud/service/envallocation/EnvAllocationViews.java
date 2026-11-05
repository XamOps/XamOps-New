package com.xammer.cloud.service.envallocation;

import com.xammer.cloud.domain.envallocation.EnvAllocationRun;
import com.xammer.cloud.service.envallocation.AllocationModel.AllocationKind;
import com.xammer.cloud.service.envallocation.AllocationModel.EnvironmentSpec;
import com.xammer.cloud.service.envallocation.AllocationModel.RuleSpec;
import com.xammer.cloud.service.envallocation.EnvAllocationEngine.ResultRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the environment card and its exports read. */
public final class EnvAllocationViews {

    private EnvAllocationViews() {
    }

    /**
     * Where a month stands.
     *
     * <ul>
     *   <li>{@code RUN} — a stored run answers it; the official one if a run is locked.</li>
     *   <li>{@code PREVIEW} — computed live and not stored: the open month, or a preview asked for.</li>
     *   <li>{@code RUNNING} — a run is queued or in progress and none has finished yet.</li>
     *   <li>{@code NOT_RUN} — a closed month nobody has run.</li>
     *   <li>{@code NO_RULES} — nothing has been published yet.</li>
     *   <li>{@code NO_REPORT} — the Cost and Usage Report cannot answer this month.</li>
     * </ul>
     */
    public enum Status { RUN, PREVIEW, RUNNING, NOT_RUN, NO_RULES, NO_REPORT }

    public record RunView(Long id,
                          String awsAccountId,
                          String period,
                          String basis,
                          int versionNumber,
                          String status,
                          String error,
                          BigDecimal totalCost,
                          BigDecimal allocatedCost,
                          BigDecimal unallocatedCost,
                          Integer lineCount,
                          LocalDateTime queuedAt,
                          LocalDateTime startedAt,
                          LocalDateTime finishedAt,
                          String startedBy,
                          boolean locked,
                          LocalDateTime lockedAt,
                          String lockedBy) {

        public static RunView of(EnvAllocationRun r) {
            return new RunView(r.getId(), r.getAwsAccountId(), r.getBillingPeriod(), r.getCostBasis(),
                    r.getVersionNumber(), r.getStatus(), r.getError(), r.getTotalCost(),
                    r.getAllocatedCost(), r.getUnallocatedCost(), r.getLineCount(), r.getQueuedAt(),
                    r.getStartedAt(), r.getFinishedAt(), r.getStartedBy(), r.isLocked(),
                    r.getLockedAt(), r.getLockedBy());
        }
    }

    /**
     * One environment's total, split by how it got there.
     *
     * @param direct a tag, a label, or a 100% rule
     * @param split  percentage and proportional shares
     * @param share  of the bill total, 0–100
     */
    public record EnvironmentTotal(String name, String color, BigDecimal total, BigDecimal direct,
                                   BigDecimal split, BigDecimal share, boolean unallocated) {
    }

    /**
     * The card's table grain: environment, rule, service — with the resources beneath it.
     *
     * @param resources     the costliest resources in this row, at most {@link #MAX_RESOURCES_PER_ROW};
     *                      empty when the month was not read per resource. Lines with no resource
     *                      (tax, support, or a read without resource grain) appear as one entry
     *                      with a null id, so the entries still add up to the row.
     * @param resourceCount how many distinct entries the row really has, so the card can say how
     *                      many it is not showing
     */
    public record BreakdownRow(String environment,
                               String color,
                               AllocationKind kind,
                               String ruleId,
                               String ruleName,
                               Integer rulePosition,
                               String service,
                               BigDecimal percent,
                               BigDecimal sourceCost,
                               BigDecimal amount,
                               int lineCount,
                               List<ResourceShare> resources,
                               int resourceCount) {
    }

    /** One resource's part of a breakdown row. {@code resourceId} null means "no resource". */
    public record ResourceShare(String resourceId, String region, BigDecimal sourceCost,
                                BigDecimal amount, int lineCount) {
    }

    /**
     * Per row, not per response. A service with thousands of resources would otherwise put every
     * one of them in a payload the card reads on every load; the costliest hundred answer "where
     * did UAT's EC2 go", and the CSV export still lists every row.
     */
    static final int MAX_RESOURCES_PER_ROW = 100;

    /** What one rule took this month. A rule that took nothing is worth knowing about. */
    public record RuleTotal(String ruleId, String name, int position, RuleSpec rule,
                            BigDecimal matchedCost, boolean matched) {
    }

    public record Breakdown(Status status,
                            String period,
                            String basis,
                            Integer versionNumber,
                            Integer latestVersion,
                            RunView run,
                            RunView pendingRun,
                            BigDecimal totalCost,
                            BigDecimal allocatedCost,
                            BigDecimal unallocatedCost,
                            List<EnvironmentTotal> environments,
                            List<BreakdownRow> rows,
                            List<RuleTotal> rules,
                            List<String> missingTagKeys,
                            String message) {

        static Breakdown empty(Status status, String period, String basis, Integer latestVersion,
                               RunView pendingRun, String message) {
            return new Breakdown(status, period, basis, null, latestVersion, null, pendingRun,
                    null, null, null, List.of(), List.of(), List.of(), List.of(), message);
        }
    }

    // ── Building ────────────────────────────────────────────────────────────────

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Rolls result rows up into the card's totals, rows and per-rule figures. */
    static Breakdown build(Status status, String period, String basis, Integer versionNumber,
                           Integer latestVersion, RunView run, RunView pendingRun,
                           BigDecimal total, List<ResultRow> rows, List<EnvironmentSpec> envOrder,
                           List<RuleSpec> rules, List<String> missingTagKeys, String message) {
        Map<String, BigDecimal[]> byEnv = new LinkedHashMap<>();
        Map<String, String> colors = new HashMap<>();
        for (EnvironmentSpec env : envOrder) {
            byEnv.put(env.name(), zeros());
            colors.put(env.name(), env.color());
        }
        BigDecimal unallocated = BigDecimal.ZERO;
        Map<String, BigDecimal> byRule = new HashMap<>();
        Map<List<Object>, RowAcc> grouped = new LinkedHashMap<>();

        for (ResultRow r : rows) {
            if (r.kind() == AllocationKind.UNALLOCATED) {
                unallocated = unallocated.add(r.amount());
            } else {
                BigDecimal[] t = byEnv.computeIfAbsent(r.environmentName(), k -> zeros());
                colors.putIfAbsent(r.environmentName(), r.environmentColor());
                t[0] = t[0].add(r.amount());
                if (r.kind().isDirect()) {
                    t[1] = t[1].add(r.amount());
                } else {
                    t[2] = t[2].add(r.amount());
                }
            }
            if (r.ruleId() != null) {
                byRule.merge(r.ruleId(), r.amount(), BigDecimal::add);
            }

            List<Object> key = java.util.Arrays.asList(r.environmentName(), r.kind(), r.ruleId(),
                    r.rulePosition(), r.service(),
                    r.percent() == null ? null : r.percent().stripTrailingZeros());
            grouped.computeIfAbsent(key, k -> new RowAcc(r)).add(r);
        }

        BigDecimal billTotal = total != null ? total : BigDecimal.ZERO;
        List<EnvironmentTotal> envs = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : byEnv.entrySet()) {
            BigDecimal[] t = e.getValue();
            envs.add(new EnvironmentTotal(e.getKey(), colors.get(e.getKey()), t[0], t[1], t[2],
                    share(t[0], billTotal), false));
        }
        envs.add(new EnvironmentTotal(AllocationModel.UNALLOCATED, AllocationModel.UNALLOCATED_COLOR,
                unallocated, BigDecimal.ZERO, BigDecimal.ZERO, share(unallocated, billTotal), true));

        Map<String, Integer> envRank = new HashMap<>();
        for (int i = 0; i < envs.size(); i++) {
            envRank.put(envs.get(i).name(), i);
        }
        List<BreakdownRow> outRows = new ArrayList<>(grouped.size());
        for (RowAcc acc : grouped.values()) {
            outRows.add(acc.toRow());
        }
        outRows.sort(Comparator
                .comparing((BreakdownRow r) -> envRank.getOrDefault(r.environment(), Integer.MAX_VALUE))
                .thenComparing(r -> r.amount().negate()));

        List<RuleTotal> ruleTotals = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            RuleSpec rule = rules.get(i);
            BigDecimal matched = byRule.get(rule.id());
            ruleTotals.add(new RuleTotal(rule.id(), rule.name(), i + 1, rule,
                    matched == null ? BigDecimal.ZERO : matched, matched != null));
        }

        return new Breakdown(status, period, basis, versionNumber, latestVersion, run, pendingRun,
                billTotal, billTotal.subtract(unallocated), unallocated, envs, outRows, ruleTotals,
                missingTagKeys == null ? List.of() : missingTagKeys, message);
    }

    /** One breakdown row being summed, with its resources summed beneath it. */
    private static final class RowAcc {
        private final ResultRow first;
        private BigDecimal sourceCost = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;
        private int lineCount;
        /** Keyed by resource id; null is the "no resource" entry. LinkedHashMap allows a null key. */
        private final Map<String, ResourceAcc> resources = new LinkedHashMap<>();

        RowAcc(ResultRow first) {
            this.first = first;
        }

        void add(ResultRow r) {
            sourceCost = sourceCost.add(r.sourceCost());
            amount = amount.add(r.amount());
            lineCount += r.lineCount();
            ResourceAcc res = resources.computeIfAbsent(r.resourceId(), id -> new ResourceAcc(id, r.region()));
            res.sourceCost = res.sourceCost.add(r.sourceCost());
            res.amount = res.amount.add(r.amount());
            res.lineCount += r.lineCount();
        }

        BreakdownRow toRow() {
            // Only worth listing when at least one line carried a resource id. A row read without
            // resource grain has just the null entry, which would repeat the service total.
            boolean anyResource = resources.keySet().stream().anyMatch(java.util.Objects::nonNull);
            List<ResourceShare> shares = !anyResource ? List.of() : resources.values().stream()
                    .sorted(Comparator.comparing((ResourceAcc a) -> a.amount.abs()).reversed())
                    .limit(MAX_RESOURCES_PER_ROW)
                    .map(a -> new ResourceShare(a.resourceId, a.region, a.sourceCost, a.amount, a.lineCount))
                    .toList();
            return new BreakdownRow(first.environmentName(), first.environmentColor(), first.kind(),
                    first.ruleId(), first.ruleName(), first.rulePosition(), first.service(),
                    first.percent(), sourceCost, amount, lineCount, shares,
                    anyResource ? resources.size() : 0);
        }
    }

    private static final class ResourceAcc {
        private final String resourceId;
        private final String region;
        private BigDecimal sourceCost = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;
        private int lineCount;

        ResourceAcc(String resourceId, String region) {
            this.resourceId = resourceId;
            this.region = region;
        }
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static BigDecimal share(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_EVEN);
    }
}
