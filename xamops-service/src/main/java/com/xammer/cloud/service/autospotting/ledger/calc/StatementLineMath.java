package com.xammer.cloud.service.autospotting.ledger.calc;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Tier;
import com.xammer.cloud.service.autospotting.ledger.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;

/**
 * The statement-line rule of design-ledger.md §8.3 over a group's entries in one statement month, as a
 * pure function. The recompute tool diffs stored lines against it, and the golden fixtures' line files
 * are built from it. Persisting and closing statements belongs to Phase 32.
 * <pre>
 * raw_net         = Σ_E net_savings                                   (shown, may be negative, PO-26)
 * basis           = Σ_{E_b} net_savings + Σ_{E_n} min(0, net_savings_lower_bound)
 * line.billable   = E_b non-empty AND no entry of E_n has a null lower bound AND the contract applies
 * billable_amount = line.billable ? round_HALF_UP(max(0, basis), 2) : 0.00
 * </pre>
 * Rounding happens once per line. The assertion {@code 0 ≤ billable_amount ≤ round(Σ_{E_b} CF)}
 * (AC-LED-02) is checked before a line is returned: a violation is a bug and throws.
 */
public final class StatementLineMath {
    private static final Currency USD = Currency.getInstance("USD");

    private StatementLineMath() {}

    /**
     * One line of a statement.
     *
     * @param instanceHours Spot instance-hours of the group in the month (scale 6)
     * @param entryCount    number of entries behind the line
     */
    public record Line(String groupId, String statementMonth, Tier tier, boolean billable, String nonBillableReason,
                       BigDecimal instanceHours, BigDecimal counterfactualCost, BigDecimal spotCost,
                       BigDecimal commitmentDeduction, BigDecimal rawNet, BigDecimal billableAmount, String currency,
                       int entryCount) {}

    /**
     * Builds the line of one group-month.
     *
     * @param entries         the group's entries of the month
     * @param contractApplies whether the contract applies to the group for the month (§3.1)
     */
    public static Line build(List<EntryCalc> entries, boolean contractApplies) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("a line needs at least one entry");
        }
        List<EntryCalc> ordered = entries.stream().sorted(Comparator.comparing(EntryCalc::periodStart)).toList();
        BigDecimal rawNet = BigDecimal.ZERO;
        BigDecimal basis = BigDecimal.ZERO;
        BigDecimal cf = BigDecimal.ZERO;
        BigDecimal cfBillable = BigDecimal.ZERO;
        BigDecimal spot = BigDecimal.ZERO;
        BigDecimal ded = BigDecimal.ZERO;
        long seconds = 0;
        boolean anyBillable = false;
        boolean nullBound = false;
        String firstReason = null;
        Tier weakestBillable = null;
        Tier tierOfNonBillable = null;
        for (EntryCalc e : ordered) {
            rawNet = rawNet.add(e.netSavings());
            cf = cf.add(e.counterfactualCost());
            spot = spot.add(e.spotCost());
            ded = ded.add(e.commitmentDeduction());
            seconds += e.instanceSeconds();
            if (e.billable()) {
                anyBillable = true;
                basis = basis.add(e.netSavings());
                cfBillable = cfBillable.add(e.counterfactualCost());
                weakestBillable = weakestBillable == null || rank(e.tier()) < rank(weakestBillable)
                        ? e.tier() : weakestBillable;
            } else {
                if (e.netSavingsLowerBound() == null) {
                    nullBound = true;
                } else {
                    basis = basis.add(e.netSavingsLowerBound().min(BigDecimal.ZERO));
                }
                if (firstReason == null) {
                    firstReason = e.nonBillableReason();
                }
                tierOfNonBillable = tierOfNonBillable == null || rank(e.tier()) < rank(tierOfNonBillable)
                        ? e.tier() : tierOfNonBillable;
            }
        }
        boolean billable = anyBillable && !nullBound && contractApplies;
        BigDecimal amount = BigDecimal.ZERO.setScale(2);
        if (billable) {
            amount = new Money(basis.max(BigDecimal.ZERO), USD).toMinorUnit().amount();
        }
        BigDecimal cap = new Money(cfBillable, USD).toMinorUnit().amount();
        if (amount.signum() < 0 || amount.compareTo(cap) > 0) {
            throw new IllegalStateException("billable_amount " + amount + " outside [0, " + cap + "]");
        }
        String reason = null;
        if (!billable) {
            reason = firstReason != null ? firstReason : Reasons.NO_CONTRACT;
        }
        EntryCalc head = ordered.get(0);
        return new Line(head.groupId(), head.statementMonth().toString(), anyBillable ? weakestBillable
                : tierOfNonBillable, billable, reason,
                BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600L), 6, RoundingMode.HALF_UP), cf, spot, ded,
                rawNet, amount, "USD", ordered.size());
    }

    /** Weakest tier ranks lowest. */
    private static int rank(Tier tier) {
        return switch (tier) {
            case UNKNOWN -> 0;
            case MEASURED_ONLY -> 1;
            case AGGREGATE_VERIFIED -> 2;
            case BILL_VERIFIED -> 3;
        };
    }
}
