package com.xammer.cloud.service.autospotting.ledger.calc;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Cud;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.CommitmentData;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.CommitmentsStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The GCP resource CUD deduction (design-ledger.md §5.3, §5.4, PO-07). An idle commitment would have covered
 * part of the counterfactual usage at no extra cost, so that usage's value comes off, in the commitment's own
 * units (vCPU-hours and GiB-hours) and priced at the counterfactual's effective core and RAM rates after SUD:
 * <pre>
 * Cv_σ = min(Uv_σ, Σ cfV_g)   allocated by cfV      DED_g = min(CF_g, Cv_g × rCore_g + Cm_g × rRam_g)
 * </pre>
 * Allocation is at scale 10, {@code HALF_UP}; the last group takes the remainder, so the group shares add up to
 * the pool exactly. A commitment whose utilization or quantities cannot be read never gets a guessed deduction:
 * it caps the tier at MEASURED_ONLY with {@code COMMITMENT_DATA_UNAVAILABLE} or {@code COMMITMENT_RATE_UNKNOWN}
 * (FR-LED-07). The floor is 0: {@code DED ≥ 0}.
 */
final class CudDeduction {
    static final int SCALE = 10;

    /** The counterfactual's use of one series: hours consumed and the effective rates it was priced at. */
    record SeriesUse(BigDecimal vcpuHours, BigDecimal gibHours, BigDecimal coreRate, BigDecimal ramRate) {}

    /** The deduction (scale 10, at least 0) and the tier cap reason, or null when nothing caps the tier. */
    record Result(BigDecimal deduction, String capReason) {}

    private CudDeduction() {}

    static Result compute(BigDecimal counterfactualCost, Map<String, SeriesUse> uses, CommitmentsStatus status,
                          List<Cud> commitments) {
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
        for (Cud c : commitments.stream().sorted(Comparator.comparing(Cud::scopeId)).toList()) {
            if (c.data() == CommitmentData.UNAVAILABLE) {
                cap = Reasons.COMMITMENT_DATA_UNAVAILABLE;
                continue;
            }
            if (c.data() == CommitmentData.RATE_UNKNOWN || c.unusedVcpuHours() == null || c.unusedGibHours() == null
                    || c.unusedVcpuHours().signum() < 0 || c.unusedGibHours().signum() < 0) {
                cap = cap == null ? Reasons.COMMITMENT_RATE_UNKNOWN : cap;
                continue;
            }
            SeriesUse use = uses.get(c.series());
            if (use == null) {
                continue; // the group used none of this series: nothing to cover
            }
            BigDecimal v = share(use.vcpuHours(), c.unusedVcpuHours(), c.otherVcpuHours(), c.lastGroup(),
                    c.allocatedVcpuToOthers());
            BigDecimal m = share(use.gibHours(), c.unusedGibHours(), c.otherGibHours(), c.lastGroup(),
                    c.allocatedGibToOthers());
            total = total.add(v.multiply(use.coreRate()).add(m.multiply(use.ramRate())));
        }
        if (total.compareTo(counterfactualCost) > 0) {
            total = counterfactualCost;
        }
        return new Result(total.max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP), cap);
    }

    /** This group's allocated hours of one resource, at most what it could consume. */
    private static BigDecimal share(BigDecimal coverable, BigDecimal unused, BigDecimal others, boolean last,
                                    BigDecimal allocatedToOthers) {
        if (coverable.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal o = others == null ? BigDecimal.ZERO : others;
        BigDecimal pool = coverable.add(o);
        BigDecimal covered = unused.min(pool);
        BigDecimal share;
        if (last) {
            share = covered.subtract(allocatedToOthers == null ? BigDecimal.ZERO : allocatedToOthers);
        } else {
            share = covered.multiply(coverable).divide(pool, SCALE, RoundingMode.HALF_UP);
        }
        return share.max(BigDecimal.ZERO).min(coverable);
    }
}
