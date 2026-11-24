package com.xammer.cloud.service.autospotting.ledger.calc;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.DiscountBasis;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.OdPrice;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Original;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.PreType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The counterfactual rate per capacity unit-hour of a Spot item, the lower-of rule (design-ledger.md §3.3):
 * <pre>r_i = min( odEff(type_i) / w_i , odEff(preEnableType) / w_pre )</pre>
 * over the effective On-Demand rates, and the same over list rates for {@code rList_i}. For a mixed
 * original group the cheapest original type per unit is used. If either side has no price the rate is
 * missing and the caller makes the entry {@code UNKNOWN(MISSING_OD_PRICE)}; nothing is defaulted.
 * Each division is to scale {@value #RATE_SCALE}, {@code HALF_UP} (the rate column's scale, §9.1).
 */
final class CounterfactualRates {
    static final int RATE_SCALE = 12;

    /** The rates of one item, the price snapshots they came from, and whether a discount was undeclared. */
    record Rate(BigDecimal effective, BigDecimal list, TreeSet<String> snapshotIds, boolean discountUndeclared) {}

    private final Map<String, OdPrice> prices = new HashMap<>();
    private final Original original;
    private final Map<String, Rate> cache = new HashMap<>();

    CounterfactualRates(java.util.List<OdPrice> odPrices, Original original) {
        for (OdPrice p : odPrices) {
            // Same key twice (a duplicate snapshot row): keep the one with the larger snapshot id, deterministically.
            prices.merge(key(p.instanceType(), p.platform()), p,
                    (a, b) -> String.valueOf(a.snapshotId()).compareTo(String.valueOf(b.snapshotId())) >= 0 ? a : b);
        }
        this.original = original;
    }

    static String normalizePlatform(String platform) {
        return platform == null ? null : platform.trim().toUpperCase(Locale.ROOT);
    }

    private static String key(String type, String platform) {
        return type + "|" + normalizePlatform(platform);
    }

    /** The rate of an item, or null when the ran type or any pre-enable type has no usable price. */
    Rate rateFor(String instanceType, String platform, BigDecimal weight) {
        String cacheKey = key(instanceType, platform) + "|" + weight.toPlainString();
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }
        Rate rate = compute(instanceType, platform, weight);
        cache.put(cacheKey, rate);
        return rate;
    }

    private Rate compute(String instanceType, String platform, BigDecimal weight) {
        if (original == null || original.preEnable().isEmpty()) {
            return null;
        }
        OdPrice ran = prices.get(key(instanceType, platform));
        if (!usable(ran)) {
            return null;
        }
        TreeSet<String> ids = new TreeSet<>();
        ids.add(ran.snapshotId());
        boolean undeclared = ran.discountBasis() == DiscountBasis.NONE;
        BigDecimal effective = ran.effectiveRate().divide(weight, RATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal list = ran.listRate().divide(weight, RATE_SCALE, RoundingMode.HALF_UP);
        for (PreType pre : original.preEnable()) {
            if (pre.weight() == null || pre.weight().signum() <= 0) {
                return null;
            }
            OdPrice p = prices.get(key(pre.instanceType(), pre.platform() == null ? platform : pre.platform()));
            if (!usable(p)) {
                return null;
            }
            ids.add(p.snapshotId());
            undeclared |= p.discountBasis() == DiscountBasis.NONE;
            effective = effective.min(p.effectiveRate().divide(pre.weight(), RATE_SCALE, RoundingMode.HALF_UP));
            list = list.min(p.listRate().divide(pre.weight(), RATE_SCALE, RoundingMode.HALF_UP));
        }
        return new Rate(effective, list, ids, undeclared);
    }

    private static boolean usable(OdPrice p) {
        return p != null && p.effectiveRate() != null && p.listRate() != null
                && p.effectiveRate().signum() >= 0 && p.listRate().signum() >= 0 && p.snapshotId() != null;
    }
}
