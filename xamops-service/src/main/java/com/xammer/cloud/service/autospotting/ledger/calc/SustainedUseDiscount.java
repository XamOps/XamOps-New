package com.xammer.cloud.service.autospotting.ledger.calc;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Scope;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Span;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Sud;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The sustained use discount of the counterfactual (PO-08, refined by C-14; design-ledger.md §3.9):
 * <pre>
 * sudRate = max( observedSeriesRate,   export credits / eligible usage cost, series and month
 *                marginalRate )        1 - [ sudCost(cf + own) - sudCost(own) ] / listCost(cf)
 * </pre>
 * With no export the series maximum is used (PO-08). {@code sudCost} stacks usage into layers the way GCP
 * computes resource-based SUD: vCPU and GiB separately, a layer is one unit of the resource, and each layer earns
 * the tier multipliers for the share of the month it ran. {@code marginalRate} is computed for every candidate
 * pooling scope (V-LED-11) and the larger discount wins. Every figure is exact decimal; a division is to scale
 * {@value #SCALE}, {@code HALF_UP}.
 */
final class SustainedUseDiscount {
    static final int SCALE = 12;

    private SustainedUseDiscount() {}

    /** The rate and the terms it came from, to stamp as the {@code SUD_RATE} snapshot. */
    record Result(BigDecimal rate, BigDecimal observed, BigDecimal marginal, String scope) {}

    /**
     * @param vcpuRate the series' vCPU-hour list price, to weight vCPU against GiB in the marginal rate; null
     *                 or zero weighs both resources by their units
     */
    static Result rate(Sud sud, BigDecimal vcpuRate, BigDecimal gibRate) {
        BigDecimal max = sud.maxRate() == null ? BigDecimal.ZERO : sud.maxRate();
        if (max.signum() == 0 || sud.tiers().isEmpty()) {
            return new Result(BigDecimal.ZERO.setScale(SCALE), null, null, null); // the series earns no SUD
        }
        BigDecimal observed = sud.observedRate();
        BigDecimal marginal = null;
        String scope = null;
        if (observed != null && sud.monthHours() != null && sud.monthHours() > 0 && !sud.cf().isEmpty()
                && !sud.scopes().isEmpty()) {
            for (Scope s : sud.scopes()) {
                BigDecimal m = marginal(sud, s, vcpuRate, gibRate);
                if (m != null && (marginal == null || m.compareTo(marginal) > 0)) {
                    marginal = m;
                    scope = s.name();
                }
            }
        }
        if (observed == null || marginal == null) {
            // No export, or nothing to stack it on: the series maximum, the larger discount, the lower savings.
            return new Result(max.setScale(SCALE, RoundingMode.HALF_UP), observed, marginal, scope);
        }
        BigDecimal best = observed.max(marginal).setScale(SCALE, RoundingMode.HALF_UP);
        return new Result(best, observed, marginal, scope);
    }

    private static BigDecimal marginal(Sud sud, Scope scope, BigDecimal vcpuRate, BigDecimal gibRate) {
        int hours = sud.monthHours();
        BigDecimal wv = vcpuRate == null || vcpuRate.signum() == 0 ? BigDecimal.ONE : vcpuRate;
        BigDecimal wg = gibRate == null || gibRate.signum() == 0 ? BigDecimal.ONE : gibRate;
        List<Span> both = new ArrayList<>(sud.cf());
        both.addAll(scope.own());
        BigDecimal withCf = cost(both, hours, sud.tiers(), true).multiply(wv)
                .add(cost(both, hours, sud.tiers(), false).multiply(wg));
        BigDecimal ownOnly = cost(scope.own(), hours, sud.tiers(), true).multiply(wv)
                .add(cost(scope.own(), hours, sud.tiers(), false).multiply(wg));
        BigDecimal list = listCost(sud.cf(), true).multiply(wv).add(listCost(sud.cf(), false).multiply(wg));
        if (list.signum() <= 0) {
            return null;
        }
        BigDecimal paidMore = withCf.subtract(ownOnly);
        return BigDecimal.ONE.subtract(paidMore.divide(list, SCALE, RoundingMode.HALF_UP));
    }

    private static BigDecimal amount(Span s, boolean vcpu) {
        BigDecimal a = vcpu ? s.vcpu() : s.gib();
        return a == null ? BigDecimal.ZERO : a;
    }

    /** Σ usage (unit-hours) of one resource over the spans. */
    static BigDecimal listCost(List<Span> spans, boolean vcpu) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Span s : spans) {
            sum = sum.add(amount(s, vcpu).multiply(BigDecimal.valueOf((long) s.toHour() - s.fromHour())));
        }
        return sum;
    }

    /**
     * The discounted cost, in list-priced unit-hours, of one resource's usage profile. Layer {@code j} (one unit of
     * the resource) holds {@code clamp(u_h - (j-1), 0, 1)} in hour {@code h}; it ran {@code r_j} hours and pays
     * {@code Σ_t mult_t x clamp(r_j - t x Q, 0, Q)} per average unit, with {@code Q} a quarter of the month.
     */
    static BigDecimal cost(List<Span> spans, int monthHours, List<BigDecimal> tiers, boolean vcpu) {
        BigDecimal[] usage = new BigDecimal[monthHours];
        Arrays.fill(usage, BigDecimal.ZERO);
        for (Span s : spans) {
            BigDecimal a = amount(s, vcpu);
            for (int h = Math.max(0, s.fromHour()); h < Math.min(monthHours, s.toHour()); h++) {
                usage[h] = usage[h].add(a);
            }
        }
        BigDecimal max = BigDecimal.ZERO;
        for (BigDecimal u : usage) {
            max = max.max(u);
        }
        int layers = max.setScale(0, RoundingMode.CEILING).intValueExact();
        BigDecimal quarter = BigDecimal.valueOf(monthHours)
                .divide(BigDecimal.valueOf(tiers.size()), 12, RoundingMode.HALF_UP);
        BigDecimal total = BigDecimal.ZERO;
        for (int j = 1; j <= layers; j++) {
            BigDecimal weight = BigDecimal.ZERO;
            int running = 0;
            BigDecimal below = BigDecimal.valueOf(j - 1L);
            for (BigDecimal u : usage) {
                BigDecimal a = u.subtract(below).max(BigDecimal.ZERO).min(BigDecimal.ONE);
                if (a.signum() > 0) {
                    running++;
                    weight = weight.add(a);
                }
            }
            if (running == 0) {
                continue;
            }
            BigDecimal paidHours = BigDecimal.ZERO;
            for (int t = 0; t < tiers.size(); t++) {
                BigDecimal start = quarter.multiply(BigDecimal.valueOf(t));
                BigDecimal inTier = BigDecimal.valueOf(running).subtract(start).max(BigDecimal.ZERO).min(quarter);
                paidHours = paidHours.add(tiers.get(t).multiply(inTier));
            }
            // cost = average unit in the layer (weight / running) x paid hours
            total = total.add(weight.multiply(paidHours).divide(BigDecimal.valueOf(running), 12, RoundingMode.HALF_UP));
        }
        return total;
    }
}
