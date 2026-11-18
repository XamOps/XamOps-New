package com.xammer.cloud.service.autospotting.ledger.calc;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Tier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The result of one accrual calculation: the fields of a {@code savings_ledger_entry} that the
 * calculator decides (design-ledger.md §2.2, §3.4). Money is scale 10 ({@code NUMERIC(24,10)}), one
 * division by 3600 per sum, {@code HALF_UP}. {@code netSavings} may be negative and is never clamped
 * here (PO-26); the statement line clamps it. An UNKNOWN entry carries zero money: nothing is
 * interpolated (§6.3).
 *
 * @param netSavingsLowerBound the worst case of a non-billable entry (§3.6), null when a price is missing
 *                             or the entry is billable
 * @param exclusions           one record per reason code, sorted by reason
 */
public record EntryCalc(
        String calcVersion,
        String provider,
        String currency,
        String groupId,
        Long cloudAccountId,
        Long clientId,
        Instant periodStart,
        Instant periodEnd,
        LocalDate statementMonth,
        Tier tier,
        boolean billable,
        String nonBillableReason,
        List<String> unknownInputs,
        long instanceSeconds,
        BigDecimal counterfactualUnitSeconds,
        BigDecimal counterfactualListCost,
        BigDecimal effectiveRateAdjustment,
        BigDecimal counterfactualCost,
        BigDecimal spotCost,
        BigDecimal grossSavings,
        BigDecimal commitmentDeduction,
        BigDecimal netSavings,
        BigDecimal netSavingsLowerBound,
        String spotCostSource,
        List<String> priceSnapshotIds,
        String contractId,
        List<Exclusion> exclusions) {

    public EntryCalc {
        unknownInputs = List.copyOf(unknownInputs);
        priceSnapshotIds = List.copyOf(priceSnapshotIds);
        exclusions = List.copyOf(exclusions);
    }

    /** One exclusion (design-ledger.md §6.2): capacity unit-seconds and the counterfactual it carries, null if unpriced. */
    public record Exclusion(String reason, BigDecimal unitSeconds, BigDecimal counterfactualCost) {}
}
