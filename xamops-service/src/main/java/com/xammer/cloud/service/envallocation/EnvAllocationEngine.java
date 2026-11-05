package com.xammer.cloud.service.envallocation;

import com.xammer.cloud.service.envallocation.AllocationModel.AllocationKind;
import com.xammer.cloud.service.envallocation.AllocationModel.AllocationType;
import com.xammer.cloud.service.envallocation.AllocationModel.CostLine;
import com.xammer.cloud.service.envallocation.AllocationModel.EnvironmentSpec;
import com.xammer.cloud.service.envallocation.AllocationModel.MatchField;
import com.xammer.cloud.service.envallocation.AllocationModel.MatchOperator;
import com.xammer.cloud.service.envallocation.AllocationModel.RuleSpec;
import com.xammer.cloud.service.envallocation.AllocationModel.SplitSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Puts a month of bill lines through an ordered rule list.
 *
 * <p>Pure: no database, no Athena, no Spring. Everything it needs arrives as arguments, which is
 * what lets one implementation serve both a stored run and a live preview of an unsaved draft.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>First match wins.</b> Each line is taken by exactly one rule, so no amount is counted
 *       twice. A {@code FROM_VALUE} rule whose value names no environment does not match.</li>
 *   <li><b>Nothing is dropped.</b> A line no rule takes is reported as {@link AllocationModel#UNALLOCATED}.</li>
 *   <li><b>Splits are exact.</b> Shares are rounded to six decimal places and the last share takes
 *       the remainder, so the parts of a line always add up to the line, and every run's
 *       environment totals add up to the bill total it read.</li>
 * </ul>
 */
public final class EnvAllocationEngine {

    /** Amounts are kept to a millionth of a dollar, the precision a CUR reports at. */
    static final int AMOUNT_SCALE = 6;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private EnvAllocationEngine() {
    }

    /** One row of output, already aggregated across the lines that share every field. */
    public record ResultRow(String environmentName,
                            String environmentColor,
                            AllocationKind kind,
                            String ruleId,
                            String ruleName,
                            Integer rulePosition,
                            String service,
                            String usageType,
                            String region,
                            String chargeType,
                            /*
                             * The CUR resource id, or null. Only present when the month was read
                             * at resource grain (a label or resource-id rule), and never for
                             * resource-less charges such as tax and support.
                             */
                            String resourceId,
                            String matchDetail,
                            BigDecimal percent,
                            BigDecimal sourceCost,
                            BigDecimal amount,
                            int lineCount) {
    }

    /**
     * What a run produced.
     *
     * @param totalCost       every line read, summed — the bill figure
     * @param allocatedCost   the part some rule placed
     * @param unallocatedCost the part none did; {@code allocated + unallocated == total} exactly
     */
    public record Outcome(BigDecimal totalCost,
                          BigDecimal allocatedCost,
                          BigDecimal unallocatedCost,
                          int lineCount,
                          List<ResultRow> rows) {
    }

    /**
     * Allocates every line.
     *
     * @param environments in display order; a {@code FROM_VALUE} rule picks the first whose name
     *                     or match values equal the line's value
     */
    public static Outcome allocate(List<CostLine> lines, List<RuleSpec> rules,
                                   List<EnvironmentSpec> environments) {
        Map<Long, EnvironmentSpec> envById = new LinkedHashMap<>();
        for (EnvironmentSpec env : environments) {
            envById.put(env.id(), env);
        }

        Map<RowKey, Acc> rows = new LinkedHashMap<>();
        // Proportional lines are held back until every other line is placed, because their
        // weights are what the other rules produced.
        Map<RowKey, Acc> deferred = new LinkedHashMap<>();

        BigDecimal total = BigDecimal.ZERO;
        for (CostLine line : lines) {
            BigDecimal cost = scale(line.cost());
            total = total.add(cost);

            boolean placed = false;
            for (int i = 0; i < rules.size() && !placed; i++) {
                RuleSpec rule = rules.get(i);
                int position = i + 1;
                Optional<String> matched = match(rule, line);
                if (matched.isEmpty()) {
                    continue;
                }
                placed = switch (rule.allocation()) {
                    case FIXED -> placeFixed(rows, rule, position, line, cost, envById);
                    case FROM_VALUE -> placeFromValue(rows, rule, position, line, cost,
                            matched.get(), environments);
                    case PROPORTIONAL -> {
                        add(deferred, new RowKey(null, null, AllocationKind.PROPORTIONAL, rule.id(),
                                rule.name(), position, line, null, null), cost, cost);
                        yield true;
                    }
                };
            }
            if (!placed) {
                add(rows, new RowKey(AllocationModel.UNALLOCATED, AllocationModel.UNALLOCATED_COLOR,
                        AllocationKind.UNALLOCATED, null, null, null, line, null, null), cost, cost);
            }
        }

        placeProportional(rows, deferred, environments);

        List<ResultRow> out = new ArrayList<>(rows.size());
        BigDecimal unallocated = BigDecimal.ZERO;
        for (Map.Entry<RowKey, Acc> e : rows.entrySet()) {
            RowKey k = e.getKey();
            Acc a = e.getValue();
            if (k.kind() == AllocationKind.UNALLOCATED) {
                unallocated = unallocated.add(a.amount);
            }
            out.add(new ResultRow(k.environmentName(), k.environmentColor(), k.kind(), k.ruleId(),
                    k.ruleName(), k.rulePosition(), k.service(), k.usageType(), k.region(),
                    k.chargeType(), k.resourceId(), k.matchDetail(), k.percent(), a.sourceCost,
                    a.amount, a.lines));
        }
        out.sort(Comparator
                .comparing((ResultRow r) -> r.rulePosition() == null ? Integer.MAX_VALUE : r.rulePosition())
                .thenComparing(r -> r.amount().negate()));

        return new Outcome(total, total.subtract(unallocated), unallocated, lines.size(), out);
    }

    // ── Matching ────────────────────────────────────────────────────────────────

    /**
     * Whether the rule's condition holds, and the value it matched on.
     *
     * <p>The value is what a {@code FROM_VALUE} rule maps to an environment: the tag value, or
     * for labels the matched label names joined with a newline (several labels can satisfy the
     * condition, and each is tried in turn). Empty means no match.
     */
    static Optional<String> match(RuleSpec rule, CostLine line) {
        MatchField field = rule.field();
        if (field == MatchField.ANY) {
            return Optional.of("");
        }
        if (field == MatchField.LABEL) {
            List<String> hits = new ArrayList<>();
            for (String label : line.labels()) {
                if (compare(rule.operator(), label, rule.value())) {
                    hits.add(label);
                }
            }
            hits.sort(String.CASE_INSENSITIVE_ORDER);
            return hits.isEmpty() ? Optional.empty() : Optional.of(String.join("\n", hits));
        }
        String actual = switch (field) {
            case SERVICE -> line.service();
            case USAGE_TYPE -> line.usageType();
            case REGION -> line.region();
            case ACCOUNT -> line.account();
            case CHARGE_TYPE -> line.chargeType();
            case RESOURCE_ID -> line.resourceId();
            case TAG -> line.tags().get(rule.tagKey());
            default -> null;
        };
        return compare(rule.operator(), actual, rule.value()) ? Optional.of(actual) : Optional.empty();
    }

    private static boolean compare(MatchOperator operator, String actual, String expected) {
        if (actual == null || actual.isBlank()) {
            return false;
        }
        if (operator == MatchOperator.EXISTS) {
            return true;
        }
        if (expected == null || expected.isBlank()) {
            return false;
        }
        String a = actual.toLowerCase(Locale.ROOT);
        String e = expected.trim().toLowerCase(Locale.ROOT);
        return switch (operator) {
            case EQUALS -> a.equals(e);
            case CONTAINS -> a.contains(e);
            case STARTS_WITH -> a.startsWith(e);
            case EXISTS -> true;
        };
    }

    /** The first environment whose name or match values equal {@code value}, ignoring case. */
    static Optional<EnvironmentSpec> environmentFor(String value, List<EnvironmentSpec> environments) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String v = value.trim();
        for (EnvironmentSpec env : environments) {
            if (env.name().equalsIgnoreCase(v)) {
                return Optional.of(env);
            }
            if (env.matchValues() != null) {
                for (String alias : env.matchValues()) {
                    if (alias != null && alias.trim().equalsIgnoreCase(v)) {
                        return Optional.of(env);
                    }
                }
            }
        }
        return Optional.empty();
    }

    // ── Placement ───────────────────────────────────────────────────────────────

    private static boolean placeFixed(Map<RowKey, Acc> rows, RuleSpec rule, int position,
                                      CostLine line, BigDecimal cost, Map<Long, EnvironmentSpec> envById) {
        List<SplitSpec> splits = rule.splits() == null ? List.of() : rule.splits();
        if (splits.isEmpty()) {
            // Validation refuses this on save. A stored version that somehow carries it must not
            // silently swallow money, so it is treated as not matching.
            return false;
        }
        AllocationKind kind = splits.size() == 1 ? AllocationKind.RULE : AllocationKind.SPLIT;
        BigDecimal[] shares = divide(cost, splits.stream().map(SplitSpec::percent).toList());
        for (int i = 0; i < splits.size(); i++) {
            SplitSpec split = splits.get(i);
            EnvironmentSpec env = envById.get(split.environmentId());
            String name = env != null ? env.name() : split.environmentName();
            String color = env != null ? env.color() : null;
            add(rows, new RowKey(name, color, kind, rule.id(), rule.name(), position, line, null,
                    scalePercent(split.percent())), cost, shares[i]);
        }
        return true;
    }

    private static boolean placeFromValue(Map<RowKey, Acc> rows, RuleSpec rule, int position,
                                          CostLine line, BigDecimal cost, String matched,
                                          List<EnvironmentSpec> environments) {
        for (String candidate : matched.split("\n")) {
            Optional<EnvironmentSpec> env = environmentFor(candidate, environments);
            if (env.isPresent()) {
                String detail = switch (rule.field()) {
                    case TAG -> rule.tagKey() + "=" + candidate;
                    case LABEL -> "label " + candidate;
                    default -> rule.field().name().toLowerCase(Locale.ROOT) + " " + candidate;
                };
                add(rows, new RowKey(env.get().name(), env.get().color(), AllocationKind.TAGGED,
                        rule.id(), rule.name(), position, line, truncate(detail, 255), HUNDRED_PERCENT),
                        cost, cost);
                return true;
            }
        }
        return false;
    }

    private static final BigDecimal HUNDRED_PERCENT = scalePercent(HUNDRED);

    /**
     * Shares every held-back line in proportion to what each environment received from the other
     * rules. Only positive totals weigh: an environment that netted to a credit cannot sensibly
     * absorb a share of tax. With nothing to weigh by, the line is unallocated rather than guessed.
     */
    private static void placeProportional(Map<RowKey, Acc> rows, Map<RowKey, Acc> deferred,
                                          List<EnvironmentSpec> environments) {
        if (deferred.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        Map<String, String> colors = new LinkedHashMap<>();
        for (EnvironmentSpec env : environments) {
            weights.put(env.name(), BigDecimal.ZERO);
            colors.put(env.name(), env.color());
        }
        for (Map.Entry<RowKey, Acc> e : rows.entrySet()) {
            RowKey k = e.getKey();
            if (k.kind() == AllocationKind.UNALLOCATED) {
                continue;
            }
            weights.merge(k.environmentName(), e.getValue().amount, BigDecimal::add);
            colors.putIfAbsent(k.environmentName(), k.environmentColor());
        }
        List<String> names = new ArrayList<>();
        List<BigDecimal> positive = new ArrayList<>();
        BigDecimal weightTotal = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> w : weights.entrySet()) {
            if (w.getValue().signum() > 0) {
                names.add(w.getKey());
                positive.add(w.getValue());
                weightTotal = weightTotal.add(w.getValue());
            }
        }

        // Each environment's share as a percentage, to four places, the last absorbing rounding so
        // they total exactly 100. Amounts are then computed from these displayed percentages, so
        // anyone checking a row can reproduce it as source cost × percent.
        List<BigDecimal> percents = new ArrayList<>();
        BigDecimal givenPercent = BigDecimal.ZERO;
        for (int i = 0; i < positive.size(); i++) {
            BigDecimal p = i == positive.size() - 1
                    ? HUNDRED.subtract(givenPercent).setScale(4, RoundingMode.HALF_EVEN)
                    : positive.get(i).multiply(HUNDRED).divide(weightTotal, 4, RoundingMode.HALF_EVEN);
            givenPercent = givenPercent.add(p);
            percents.add(p);
        }

        for (Map.Entry<RowKey, Acc> e : deferred.entrySet()) {
            RowKey k = e.getKey();
            BigDecimal cost = e.getValue().amount;
            int lineCount = e.getValue().lines;
            if (names.isEmpty()) {
                Acc acc = rows.computeIfAbsent(k.withEnvironment(AllocationModel.UNALLOCATED,
                        AllocationModel.UNALLOCATED_COLOR, AllocationKind.UNALLOCATED,
                        "nothing else was allocated to weigh by", null), x -> new Acc());
                acc.sourceCost = acc.sourceCost.add(cost);
                acc.amount = acc.amount.add(cost);
                acc.lines += lineCount;
                continue;
            }
            BigDecimal[] shares = divide(cost, percents);
            for (int i = 0; i < names.size(); i++) {
                Acc acc = rows.computeIfAbsent(k.withEnvironment(names.get(i), colors.get(names.get(i)),
                        AllocationKind.PROPORTIONAL, null, percents.get(i)), x -> new Acc());
                acc.sourceCost = acc.sourceCost.add(cost);
                acc.amount = acc.amount.add(shares[i]);
                acc.lines += lineCount;
            }
        }
    }

    // ── Arithmetic ──────────────────────────────────────────────────────────────

    /** {@code amount} divided by percentages; the last share absorbs rounding so the sum is exact. */
    static BigDecimal[] divide(BigDecimal amount, List<BigDecimal> percents) {
        return divide(amount, percents, AMOUNT_SCALE);
    }

    private static BigDecimal[] divide(BigDecimal amount, List<BigDecimal> percents, int scale) {
        BigDecimal[] out = new BigDecimal[percents.size()];
        BigDecimal given = BigDecimal.ZERO;
        for (int i = 0; i < percents.size(); i++) {
            if (i == percents.size() - 1) {
                out[i] = amount.subtract(given).setScale(scale, RoundingMode.HALF_EVEN);
            } else {
                out[i] = amount.multiply(percents.get(i))
                        .divide(HUNDRED, scale, RoundingMode.HALF_EVEN);
                given = given.add(out[i]);
            }
        }
        return out;
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal scalePercent(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_EVEN);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static void add(Map<RowKey, Acc> rows, RowKey key, BigDecimal sourceCost, BigDecimal amount) {
        Acc acc = rows.computeIfAbsent(key, k -> new Acc());
        acc.sourceCost = acc.sourceCost.add(sourceCost);
        acc.amount = acc.amount.add(amount);
        acc.lines++;
    }

    /** Everything a result row is grouped by. */
    private record RowKey(String environmentName,
                          String environmentColor,
                          AllocationKind kind,
                          String ruleId,
                          String ruleName,
                          Integer rulePosition,
                          String service,
                          String usageType,
                          String region,
                          String chargeType,
                          String resourceId,
                          String matchDetail,
                          BigDecimal percent) {

        RowKey(String environmentName, String environmentColor, AllocationKind kind, String ruleId,
               String ruleName, Integer rulePosition, CostLine line, String matchDetail,
               BigDecimal percent) {
            this(environmentName, environmentColor, kind, ruleId, ruleName, rulePosition,
                    line.service(), line.usageType(), line.region(), line.chargeType(),
                    // Blank means "no resource" — read without resource grain, or a charge that
                    // belongs to none. Held as null so those lines group together.
                    line.resourceId() == null || line.resourceId().isBlank() ? null : line.resourceId(),
                    matchDetail, percent);
        }

        RowKey withEnvironment(String name, String color, AllocationKind newKind, String detail,
                               BigDecimal newPercent) {
            return new RowKey(name, color, newKind, ruleId, ruleName, rulePosition, service,
                    usageType, region, chargeType, resourceId, detail != null ? detail : matchDetail,
                    newPercent);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RowKey k)) {
                return false;
            }
            return Objects.equals(environmentName, k.environmentName)
                    && kind == k.kind
                    && Objects.equals(ruleId, k.ruleId)
                    && Objects.equals(rulePosition, k.rulePosition)
                    && Objects.equals(service, k.service)
                    && Objects.equals(usageType, k.usageType)
                    && Objects.equals(region, k.region)
                    && Objects.equals(chargeType, k.chargeType)
                    && Objects.equals(resourceId, k.resourceId)
                    && Objects.equals(matchDetail, k.matchDetail)
                    && (percent == null ? k.percent == null
                            : k.percent != null && percent.compareTo(k.percent) == 0);
        }

        @Override
        public int hashCode() {
            return Objects.hash(environmentName, kind, ruleId, rulePosition, service, usageType,
                    region, chargeType, resourceId, matchDetail,
                    percent == null ? null : percent.stripTrailingZeros());
        }
    }

    private static final class Acc {
        BigDecimal sourceCost = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
        BigDecimal amount = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
        int lines;
    }
}
