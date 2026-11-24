package com.xammer.cloud.service.autospotting.ledger.calc;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Commitment;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.CommitmentData;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.CommitmentKind;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.CommitmentsStatus;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.PreType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * The AWS commitment deduction (design-ledger.md §5.4, PO-07). An idle commitment would have covered
 * part of the counterfactual usage at no extra cost, so that usage's value comes off, in the
 * commitment's own units and priced at the counterfactual rate the entry used. Commitment dollars are
 * never subtracted directly.
 * <pre>
 * RI:           C_σ = min(U_σ, Σ cfNU)         DED_g = CF_g × C_g / cfNU_g
 * Savings Plan: K_σ = min(u_σ, Σ spCost)       DED_g = CF_g × K_g / spCost_g
 * </pre>
 * Matching uses the pre-enable type (§3.3, §5.4 "Matching type"); for several pre-enable types the one
 * that gives the largest deduction is used, which favors the customer. Allocation is at scale 10,
 * {@code HALF_UP}; the last group takes the remainder, so the group shares add up to the pool exactly.
 * {@code DED ≤ CF} always: each share is capped at what the group could consume, and the total over all
 * commitments is capped at {@code CF}. A missing input is never guessed: it caps the tier at
 * MEASURED_ONLY with {@code COMMITMENT_RATE_UNKNOWN} or {@code COMMITMENT_DATA_UNAVAILABLE}.
 */
final class CommitmentDeduction {
    static final int SCALE = 10;
    private static final BigDecimal HOUR = BigDecimal.valueOf(3600L);

    /** The deduction (scale 10, at least 0) and the tier cap reason, or null when nothing caps the tier. */
    record Result(BigDecimal deduction, String capReason) {}

    private CommitmentDeduction() {}

    static Result compute(BigDecimal counterfactualCost, BigDecimal cfUnitSeconds, List<PreType> preEnable,
                          CommitmentsStatus status, List<Commitment> commitments) {
        BigDecimal zero = BigDecimal.ZERO.setScale(SCALE);
        if (status == CommitmentsStatus.UNAVAILABLE) {
            return new Result(zero, Reasons.COMMITMENT_DATA_UNAVAILABLE);
        }
        if (status == CommitmentsStatus.NONE_PROVEN) {
            if (!commitments.isEmpty()) {
                throw new IllegalArgumentException("commitmentsStatus NONE_PROVEN with commitments listed");
            }
            return new Result(zero, null);
        }
        String cap = null;
        BigDecimal total = zero;
        List<Commitment> ordered = commitments.stream().sorted(Comparator.comparing(Commitment::scopeId)).toList();
        for (Commitment c : ordered) {
            if (c.data() == CommitmentData.UNAVAILABLE) {
                cap = Reasons.COMMITMENT_DATA_UNAVAILABLE;
                continue;
            }
            if (c.data() == CommitmentData.RATE_UNKNOWN) {
                cap = cap == null ? Reasons.COMMITMENT_RATE_UNKNOWN : cap;
                continue;
            }
            BigDecimal ded = deductionFor(c, counterfactualCost, cfUnitSeconds, preEnable);
            if (ded == null) {
                cap = cap == null ? Reasons.COMMITMENT_RATE_UNKNOWN : cap;
                continue;
            }
            total = total.add(ded);
        }
        if (total.compareTo(counterfactualCost) > 0) {
            total = counterfactualCost;
        }
        return new Result(total.setScale(SCALE, RoundingMode.HALF_UP), cap);
    }

    /** The deduction of one commitment, or null if a conversion input is missing. */
    private static BigDecimal deductionFor(Commitment c, BigDecimal cf, BigDecimal cfUnitSeconds,
                                           List<PreType> preEnable) {
        if (preEnable.isEmpty() || c.unused() == null || c.unused().signum() < 0) {
            return null;
        }
        BigDecimal best = BigDecimal.ZERO.setScale(SCALE);
        for (PreType pre : preEnable) {
            if (pre.weight() == null || pre.weight().signum() <= 0) {
                return null;
            }
            BigDecimal factor = c.kind() == CommitmentKind.RESERVED_INSTANCE
                    ? pre.normalizationFactor() : c.planRates().get(pre.instanceType());
            if (factor == null || factor.signum() <= 0) {
                return null;
            }
            BigDecimal coverable = cfUnitSeconds.multiply(factor)
                    .divide(pre.weight().multiply(HOUR), SCALE, RoundingMode.HALF_UP);
            if (coverable.signum() == 0) {
                continue;
            }
            BigDecimal others = c.otherCoverable() == null ? BigDecimal.ZERO : c.otherCoverable();
            BigDecimal pool = coverable.add(others);
            BigDecimal covered = c.unused().min(pool);
            BigDecimal share;
            if (c.lastGroup()) {
                BigDecimal allocated = c.allocatedToOthers() == null ? BigDecimal.ZERO : c.allocatedToOthers();
                share = covered.subtract(allocated);
            } else {
                share = covered.multiply(coverable).divide(pool, SCALE, RoundingMode.HALF_UP);
            }
            share = share.max(BigDecimal.ZERO).min(coverable);
            BigDecimal ded = cf.multiply(share).divide(coverable, SCALE, RoundingMode.HALF_UP);
            if (ded.compareTo(best) > 0) {
                best = ded;
            }
        }
        return best.min(cf);
    }
}
