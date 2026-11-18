package com.xammer.cloud.service.autospotting.ledger.calc;

import com.xammer.cloud.service.autospotting.ledger.calc.AwsBillingUnits.Charge;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.CapacitySample;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Interval;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.OdPrice;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Original;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.PurchaseOption;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.SpotPrice;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Tier;
import com.xammer.cloud.service.autospotting.ledger.calc.CounterfactualRates.Rate;
import com.xammer.cloud.service.autospotting.ledger.calc.StateWindows.Cls;
import com.xammer.cloud.service.autospotting.ledger.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The AWS accrual calculator, calc version {@code aws-1} (design-ledger.md §3, §5, §6; plan-phase-16.md
 * task 16.1). A pure function of {@link CalcInputs}: no clock, no zone, no randomness, no I/O. It
 * turns one group's observed intervals, state windows and prices into the entry for one UTC day.
 *
 * <p>For every segment between two boundaries (interval ends, capacity samples, state transitions, price
 * changes, go-live, a revert's confirm and end) inside {@code M(W)}:
 * <pre>
 * cfUnits = max(0, min(S, max(0, D - O)) - P)           (3.2)
 * CF      = Σ a_i × r_i × Δ / 3600   r_i = lower-of      (3.3, 3.4)
 * SPOT    = Σ s_i × billedSeconds / 3600                 (3.4, 3.5)
 * GROSS   = CF - SPOT      NET = GROSS - DED             (3.4, 5.4)
 * </pre>
 * Each sum is divided by 3600 once, at scale 10, {@code HALF_UP}; nothing else is rounded. A negative
 * net stays negative (PO-26). Anything unknown inside {@code M(W)} makes the entry UNKNOWN with zero
 * money (§6.3), and a MEASURED_ONLY cap, a state or a commercial reason keeps the measured money and
 * sets {@code billable=false} with one reason code (§3.6, §6.2).
 *
 * <p>Order independence: the calculator sorts what it reads (transitions by {@code id}, intervals by
 * start and id, prices and commitments by key), so the same facts in any list order give the same entry.
 * It is stateless and has no side effects, so recomputing an entry from its stored inputs gives it
 * back.
 */
public final class SavingsCalculator {
    public static final String VERSION = "aws-1";
    public static final String PROVIDER = "AWS";
    private static final Currency USD = Currency.getInstance("USD");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);
    private static final int MONEY_SCALE = Money.ENTRY_SCALE;
    private static final int UNIT_SCALE = 6;

    /** Calculates the entry of the day, or empty when the group spent no part of it in a state that earns an entry (§6.1). */
    public Optional<EntryCalc> calculate(CalcInputs in) {
        validate(in);
        long ws = Instants.sec(in.windowStart());
        long we = Instants.sec(in.windowEnd());
        Long goLive = in.goLiveAt() == null ? null : Instants.sec(in.goLiveAt());
        StateWindows windows = new StateWindows(in.transitions(), in.revertOps(), goLive, ws, we);
        if (windows.orderViolation()) {
            return Optional.of(orderViolationEntry(in));
        }
        List<Item> items = items(in, ws, we);
        Pass pass = new Pass(in, windows, items, ws, we, false);
        pass.run();
        if (!pass.exists) {
            return Optional.empty();
        }
        return Optional.of(assemble(in, windows, items, ws, we, pass));
    }

    // ---------------------------------------------------------------- validation

    private static void validate(CalcInputs in) {
        if (!VERSION.equals(in.calcVersion())) {
            throw new IllegalArgumentException("this calculator runs " + VERSION + ", not " + in.calcVersion());
        }
        if (!PROVIDER.equals(in.provider())) {
            throw new IllegalArgumentException("provider must be AWS: " + in.provider());
        }
        if (!"USD".equals(in.currency())) {
            throw new IllegalArgumentException("v1 is USD only (PD-01): " + in.currency());
        }
        long ws = Instants.sec(in.windowStart());
        if (ws % Instants.DAY != 0 || Instants.sec(in.windowEnd()) - ws != Instants.DAY) {
            throw new IllegalArgumentException("the accrual window is one UTC day: " + in.windowStart() + " to "
                    + in.windowEnd());
        }
    }

    // ---------------------------------------------------------------- items

    /** An instance interval prepared for the day. Times are epoch seconds; {@code OPEN} is "still running". */
    private static final class Item {
        Interval iv;
        boolean spot;
        String platform;
        BigDecimal weight;
        boolean suse;
        long start;
        long lo;
        long hi;
        Charge charge = Charge.NORMAL;

        boolean costCovers(long a, long b) {
            return spot && charge != Charge.NOT_CHARGED && start <= a && b <= hi;
        }

        boolean weightOk() {
            return weight != null && weight.signum() > 0;
        }
    }

    private static List<Item> items(CalcInputs in, long ws, long we) {
        List<Item> out = new ArrayList<>();
        for (Interval iv : in.intervals()) {
            if (iv.startAt() == null) {
                continue;
            }
            Item it = new Item();
            it.iv = iv;
            it.spot = iv.purchaseOption() == PurchaseOption.SPOT;
            it.platform = CounterfactualRates.normalizePlatform(iv.platform());
            if (it.platform != null && it.platform.isEmpty()) {
                it.platform = null;
            }
            it.weight = iv.capacityWeight();
            it.suse = AwsBillingUnits.isSuse(it.platform);
            it.start = Instants.sec(iv.startAt());
            boolean endKnown = iv.endAt() != null;
            if (endKnown) {
                it.lo = Instants.sec(iv.endAt());
                it.hi = it.lo;
            } else if (iv.firstAbsentAt() != null) {
                it.hi = Instants.sec(iv.firstAbsentAt());
                it.lo = iv.lastSeenAt() == null ? it.start : Instants.sec(iv.lastSeenAt());
                it.lo = Math.max(it.start, Math.min(it.lo, it.hi));
            } else {
                it.lo = Instants.OPEN;
                it.hi = Instants.OPEN;
            }
            if (it.hi <= it.start || it.start >= we || it.hi <= ws) {
                continue;
            }
            if (it.spot) {
                it.charge = AwsBillingUnits.charge(endKnown, it.hi - it.start, iv.endCause());
            }
            out.add(it);
        }
        out.sort(Comparator.comparingLong((Item i) -> i.start).thenComparing(i -> String.valueOf(i.iv.intervalId()))
                .thenComparing(i -> String.valueOf(i.iv.instanceId())));
        return out;
    }

    // ---------------------------------------------------------------- the pass over the day

    /** One walk over the day's segments, with or without substituting a missing Spot rate (the §3.6 bound). */
    private static final class Pass {
        final CalcInputs in;
        final StateWindows windows;
        final List<Item> items;
        final long ws;
        final long we;
        final boolean substitute;
        final CounterfactualRates rates;
        final List<CapacitySample> samples;
        final List<SpotPrice> spotPrices;
        final Original original;

        BigDecimal cfRs = BigDecimal.ZERO;
        BigDecimal cfListRs = BigDecimal.ZERO;
        BigDecimal spotRs = BigDecimal.ZERO;
        BigDecimal cfUnitSeconds = BigDecimal.ZERO;
        long instanceSeconds;
        long measuredSeconds;
        long legacySeconds;
        boolean exists;
        boolean discountUndeclared;
        boolean noBound;
        final TreeSet<String> unknown = new TreeSet<>();
        final TreeSet<String> snapshotIds = new TreeSet<>();
        final Exclusions exclusions = new Exclusions();

        Pass(CalcInputs in, StateWindows windows, List<Item> items, long ws, long we, boolean substitute) {
            this.in = in;
            this.windows = windows;
            this.items = items;
            this.ws = ws;
            this.we = we;
            this.substitute = substitute;
            this.original = in.original();
            this.rates = new CounterfactualRates(in.odPrices(), in.original());
            this.samples = in.capacitySamples().stream().filter(s -> s.at() != null)
                    .sorted(Comparator.comparingLong((CapacitySample s) -> Instants.sec(s.at()))
                            .thenComparing(s -> s.desired() == null ? "" : s.desired().toPlainString())).toList();
            this.spotPrices = in.spotPrices().stream()
                    .sorted(Comparator.comparing((SpotPrice p) -> String.valueOf(p.snapshotId()))).toList();
        }

        void run() {
            TreeSet<Long> cuts = new TreeSet<>();
            cuts.add(ws);
            cuts.add(we);
            cuts.addAll(windows.breakpoints());
            for (Item it : items) {
                cuts.add(it.start);
                cuts.add(it.lo);
                cuts.add(it.hi);
            }
            for (CapacitySample s : samples) {
                cuts.add(Instants.sec(s.at()));
            }
            for (SpotPrice p : spotPrices) {
                if (p.effectiveFrom() != null) {
                    cuts.add(Instants.sec(p.effectiveFrom()));
                }
                if (p.effectiveTo() != null) {
                    cuts.add(Instants.sec(p.effectiveTo()));
                }
            }
            List<Long> points = cuts.stream().filter(t -> t >= ws && t <= we).toList();
            for (int k = 0; k + 1 < points.size(); k++) {
                segment(points.get(k), points.get(k + 1));
            }
            hourlyAndMinimumExtras();
        }

        private void segment(long a, long b) {
            long delta = b - a;
            if (windows.entryState(a)) {
                exists = true;
            }
            Cls cls = windows.classify(a);
            if (cls.kind() == StateWindows.Kind.NONE) {
                return;
            }
            BigDecimal d = BigDecimal.valueOf(delta);
            List<Item> cost = new ArrayList<>();
            List<Item> notCharged = new ArrayList<>();
            List<Item> cfItems = new ArrayList<>();
            List<Item> gap = new ArrayList<>();
            List<Item> causeUnknown = new ArrayList<>();
            List<Item> onDemand = new ArrayList<>();
            for (Item it : items) {
                if (it.start > a || b > it.hi) {
                    continue;
                }
                if (!it.spot) {
                    onDemand.add(it);
                } else if (it.charge == Charge.NOT_CHARGED) {
                    notCharged.add(it);
                } else {
                    cost.add(it);
                    if (it.charge == Charge.CAUSE_UNKNOWN) {
                        causeUnknown.add(it);
                    } else if (b <= it.lo) {
                        cfItems.add(it);
                    } else {
                        gap.add(it);
                    }
                }
            }
            boolean measured = cls.measured();
            if (measured) {
                measuredSeconds += delta;
            } else {
                exclusions.addWindow(cls.reason(), BigDecimal.ZERO, BigDecimal.ZERO, delta);
                if (Reasons.LEGACY_UNCONFIRMED.equals(cls.reason())) {
                    legacySeconds += delta;
                }
            }
            Cf cf = counterfactual(a, cfItems, onDemand, measured);
            if (measured) {
                measuredSegment(a, d, cost, notCharged, gap, causeUnknown, cf);
            } else if (!cost.isEmpty()) {
                BigDecimal units = weightSum(cost).multiply(d);
                exclusions.addWindow(cls.reason(), units, cf.rateSeconds(d), 0L);
            }
        }

        private void measuredSegment(long a, BigDecimal d, List<Item> cost, List<Item> notCharged, List<Item> gap,
                                     List<Item> causeUnknown, Cf cf) {
            cfRs = cfRs.add(cf.effectiveRate.multiply(d));
            cfListRs = cfListRs.add(cf.listRate.multiply(d));
            cfUnitSeconds = cfUnitSeconds.add(cf.units.multiply(d));
            snapshotIds.addAll(cf.snapshotIds);
            discountUndeclared |= cf.discountUndeclared;
            for (Item it : cost) {
                instanceSeconds += d.longValueExact();
                if (!it.weightOk()) {
                    unknown.add(Reasons.MISSING_WEIGHT);
                }
                if (it.platform == null) {
                    unknown.add(Reasons.MISSING_PLATFORM);
                }
                BigDecimal rate = spotRate(it, a);
                if (rate != null) {
                    spotRs = spotRs.add(rate.multiply(d));
                }
            }
            if (cf.odFloor.signum() > 0) {
                exclusions.addZero(Reasons.OD_FLOOR, cf.odFloor.multiply(d));
            }
            if (cf.share.signum() > 0) {
                exclusions.addZero(Reasons.ORIGINAL_SPOT_SHARE, cf.share.multiply(d));
            }
            if (cf.over.signum() > 0) {
                exclusions.addZero(Reasons.OVER_DESIRED, cf.over.multiply(d));
            }
            if (!gap.isEmpty()) {
                exclusions.addZero(Reasons.UNKNOWN_INTERVAL_END, weightSum(gap).multiply(d));
            }
            if (!causeUnknown.isEmpty()) {
                exclusions.addZero(Reasons.INTERRUPTION_CAUSE_UNKNOWN, weightSum(causeUnknown).multiply(d));
            }
            if (!notCharged.isEmpty()) {
                exclusions.addZero(Reasons.PROVIDER_NOT_CHARGED, weightSum(notCharged).multiply(d));
            }
        }

        /** Spot rate per instance-hour in force for the item at {@code t}; with substitution, the On-Demand list rate. */
        private BigDecimal spotRate(Item it, long t) {
            SpotPrice best = null;
            for (SpotPrice p : spotPrices) {
                if (p.rate() == null || p.effectiveFrom() == null || p.snapshotId() == null
                        || !java.util.Objects.equals(p.instanceType(), it.iv.instanceType())
                        || !java.util.Objects.equals(CounterfactualRates.normalizePlatform(p.platform()), it.platform)
                        || (p.zone() != null && !p.zone().equals(it.iv.zone()))
                        || Instants.sec(p.effectiveFrom()) > t
                        || (p.effectiveTo() != null && t >= Instants.sec(p.effectiveTo()))) {
                    continue;
                }
                if (best == null || better(p, best)) {
                    best = p;
                }
            }
            if (best != null) {
                snapshotIds.add(best.snapshotId());
                return best.rate();
            }
            unknown.add(Reasons.MISSING_SPOT_PRICE);
            if (substitute) {
                for (OdPrice od : in.odPrices()) {
                    if (java.util.Objects.equals(od.instanceType(), it.iv.instanceType())
                            && java.util.Objects.equals(CounterfactualRates.normalizePlatform(od.platform()), it.platform)
                            && od.listRate() != null) {
                        return od.listRate();
                    }
                }
                noBound = true;
            }
            return null;
        }

        private static boolean better(SpotPrice p, SpotPrice best) {
            int c = p.effectiveFrom().compareTo(best.effectiveFrom());
            if (c != 0) {
                return c > 0;
            }
            boolean pz = p.zone() != null;
            boolean bz = best.zone() != null;
            if (pz != bz) {
                return pz;
            }
            return p.snapshotId().compareTo(best.snapshotId()) > 0;
        }

        /** The hourly (SUSE) round-up and the 60 s minimum: Spot cost only, attributed to the day the instance ends (§3.5). */
        private void hourlyAndMinimumExtras() {
            for (Item it : items) {
                if (!it.spot || it.charge == Charge.NOT_CHARGED || it.hi == Instants.OPEN) {
                    continue;
                }
                long last = Math.max(it.start, it.hi - 1);
                if (last < ws || last >= we) {
                    continue;
                }
                long life = it.hi - it.start;
                long extra = AwsBillingUnits.billedSeconds(it.suse, life) - life;
                if (extra <= 0 || !windows.classify(last).measured()) {
                    continue;
                }
                BigDecimal rate = spotRate(it, last);
                if (rate != null) {
                    spotRs = spotRs.add(rate.multiply(BigDecimal.valueOf(extra)));
                }
            }
        }

        // -- counterfactual capacity and rate

        private BigDecimal desiredAt(long t) {
            BigDecimal d = null;
            for (CapacitySample s : samples) {
                if (Instants.sec(s.at()) <= t) {
                    d = s.desired();
                } else {
                    break;
                }
            }
            return d;
        }

        private static BigDecimal weightSum(List<Item> list) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Item it : list) {
                if (it.weightOk()) {
                    sum = sum.add(it.weight);
                }
            }
            return sum;
        }

        private Cf counterfactual(long a, List<Item> cfItems, List<Item> onDemand, boolean measured) {
            BigDecimal o = weightSum(onDemand);
            if (measured) {
                for (Item od : onDemand) {
                    if (!od.weightOk()) {
                        unknown.add(Reasons.MISSING_WEIGHT);
                    }
                }
            }
            Cf cf = new Cf();
            cf.odFloor = o;
            if (cfItems.isEmpty()) {
                return cf;
            }
            boolean missing = false;
            for (Item it : cfItems) {
                if (!it.weightOk()) {
                    missing |= flag(measured, Reasons.MISSING_WEIGHT);
                }
                if (it.platform == null) {
                    missing |= flag(measured, Reasons.MISSING_PLATFORM);
                }
            }
            BigDecimal desired = desiredAt(a);
            if (desired == null) {
                missing |= flag(measured, Reasons.MISSING_DESIRED);
            }
            BigDecimal p = BigDecimal.ZERO;
            if (original == null || original.baseUnits() == null || original.onDemandPctAboveBase() == null
                    || original.preEnable().isEmpty()) {
                missing |= flag(measured, Reasons.MISSING_SNAPSHOT);
            } else if (desired != null) {
                p = desired.subtract(original.baseUnits()).max(BigDecimal.ZERO)
                        .multiply(HUNDRED.subtract(original.onDemandPctAboveBase())).divide(HUNDRED)
                        .setScale(0, RoundingMode.FLOOR);
                p = p.max(BigDecimal.ZERO);
            }
            List<Item> usable = new ArrayList<>();
            List<Rate> usableRates = new ArrayList<>();
            if (!missing) {
                for (Item it : cfItems) {
                    Rate r = rates.rateFor(it.iv.instanceType(), it.iv.platform(), it.weight);
                    if (r == null) {
                        missing |= flag(measured, Reasons.MISSING_OD_PRICE);
                    } else {
                        usable.add(it);
                        usableRates.add(r);
                    }
                }
            }
            if (missing) {
                cf.missing = true;
                return cf;
            }
            BigDecimal s = weightSum(cfItems);
            BigDecimal cap = desired.subtract(o).max(BigDecimal.ZERO);
            BigDecimal capped = s.min(cap);
            BigDecimal units = capped.subtract(p).max(BigDecimal.ZERO);
            cf.units = units;
            cf.over = s.subtract(capped);
            cf.share = capped.subtract(units);
            // Allocate to the cheapest counterfactual first (the conservative choice, §3.2).
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < usable.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparing((Integer i) -> usableRates.get(i).effective())
                    .thenComparing(i -> String.valueOf(usable.get(i).iv.intervalId())));
            BigDecimal remaining = units;
            for (int i : order) {
                BigDecimal allocated = usable.get(i).weight.min(remaining);
                if (allocated.signum() <= 0) {
                    break;
                }
                cf.effectiveRate = cf.effectiveRate.add(allocated.multiply(usableRates.get(i).effective()));
                cf.listRate = cf.listRate.add(allocated.multiply(usableRates.get(i).list()));
                cf.snapshotIds.addAll(usableRates.get(i).snapshotIds());
                cf.discountUndeclared |= usableRates.get(i).discountUndeclared();
                remaining = remaining.subtract(allocated);
            }
            return cf;
        }

        /** Records an unknown input when the segment is measured; returns true so the caller stops computing. */
        private boolean flag(boolean measured, String code) {
            if (measured) {
                unknown.add(code);
            }
            return true;
        }
    }

    /** The counterfactual of one segment, per second: units and the rate sums (multiply by Δ). */
    private static final class Cf {
        BigDecimal units = BigDecimal.ZERO;
        BigDecimal effectiveRate = BigDecimal.ZERO;
        BigDecimal listRate = BigDecimal.ZERO;
        BigDecimal odFloor = BigDecimal.ZERO;
        BigDecimal over = BigDecimal.ZERO;
        BigDecimal share = BigDecimal.ZERO;
        boolean missing;
        boolean discountUndeclared;
        final TreeSet<String> snapshotIds = new TreeSet<>();

        /** The informational counterfactual of an excluded window as rate-seconds, null when a price is missing. */
        BigDecimal rateSeconds(BigDecimal delta) {
            return missing ? null : effectiveRate.multiply(delta);
        }
    }

    // ---------------------------------------------------------------- the entry

    private EntryCalc orderViolationEntry(CalcInputs in) {
        BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE);
        return new EntryCalc(VERSION, PROVIDER, "USD", in.group().groupId(), in.group().cloudAccountId(),
                in.group().clientId(), in.windowStart(), in.windowEnd(), month(in), Tier.UNKNOWN, false,
                Reasons.MISSING_STATE_ORDER, List.of(Reasons.MISSING_STATE_ORDER), 0L,
                BigDecimal.ZERO.setScale(UNIT_SCALE), zero, zero, zero, zero, zero, zero, zero, null, "MEASURED",
                List.of(), in.contract() == null ? null : in.contract().contractId(), List.of());
    }

    private EntryCalc assemble(CalcInputs in, StateWindows windows, List<Item> items, long ws, long we, Pass pass) {
        boolean unknown = !pass.unknown.isEmpty();
        BigDecimal cfList = money(pass.cfListRs);
        BigDecimal cf = money(pass.cfRs);
        BigDecimal spot = money(pass.spotRs);
        BigDecimal ded = BigDecimal.ZERO.setScale(MONEY_SCALE);
        String commitmentCap = null;
        List<Exclusions.Item> exclusions;
        if (!unknown) {
            List<CalcInputs.PreType> pre = in.original() == null ? List.of() : in.original().preEnable();
            CommitmentDeduction.Result r = CommitmentDeduction.compute(cf, pass.cfUnitSeconds, pre,
                    in.commitmentsStatus(), in.commitments());
            ded = r.deduction();
            commitmentCap = r.capReason();
            if (ded.signum() > 0) {
                BigDecimal covered = cf.signum() == 0 ? BigDecimal.ZERO
                        : pass.cfUnitSeconds.multiply(ded).divide(cf, UNIT_SCALE, RoundingMode.HALF_UP);
                pass.exclusions.setFixed(Reasons.COMMITMENT_COVERABLE, covered, ded);
            }
        }
        exclusions = pass.exclusions.build();

        BigDecimal adj = cfList.subtract(cf);
        BigDecimal gross = cf.subtract(spot);
        BigDecimal net = gross.subtract(ded);
        boolean measuredNonEmpty = pass.measuredSeconds > 0;

        String capReason = null;
        if (!unknown) {
            if (commitmentCap != null) {
                capReason = commitmentCap;
            } else if (pass.discountUndeclared) {
                capReason = Reasons.DISCOUNT_NOT_DECLARED;
            } else if (!in.reconciledTier().isBillable()) {
                capReason = Reasons.NOT_RECONCILED;
            }
        }
        Tier tier;
        if (unknown) {
            tier = Tier.UNKNOWN;
        } else if (commitmentCap != null || pass.discountUndeclared || !in.reconciledTier().isBillable()
                || (pass.legacySeconds > 0 && pass.measuredSeconds == 0)) {
            tier = Tier.MEASURED_ONLY;
        } else {
            tier = in.reconciledTier();
        }
        String contractReason = in.contractReason();
        boolean billable = measuredNonEmpty && !unknown && tier.isBillable() && contractReason == null;

        String reason = null;
        BigDecimal bound = null;
        if (!billable) {
            if (!measuredNonEmpty) {
                reason = dominantExclusion(pass.exclusions);
                bound = net;
            } else if (unknown) {
                reason = pass.unknown.first();
                bound = worstCase(in, windows, items, ws, we, pass);
            } else if (capReason != null) {
                reason = capReason;
                bound = Reasons.NOT_RECONCILED.equals(capReason) ? net : spot.negate();
            } else {
                reason = contractReason;
                bound = net;
            }
        }
        if (unknown) {
            BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE);
            return new EntryCalc(VERSION, PROVIDER, "USD", in.group().groupId(), in.group().cloudAccountId(),
                    in.group().clientId(), in.windowStart(), in.windowEnd(), month(in), tier, false, reason,
                    List.copyOf(pass.unknown), pass.instanceSeconds, BigDecimal.ZERO.setScale(UNIT_SCALE), zero,
                    zero, zero, zero, zero, zero, zero, bound, "MEASURED", List.copyOf(pass.snapshotIds),
                    contractId(in), toExclusions(exclusions));
        }
        return new EntryCalc(VERSION, PROVIDER, "USD", in.group().groupId(), in.group().cloudAccountId(),
                in.group().clientId(), in.windowStart(), in.windowEnd(), month(in), tier, billable, reason,
                List.of(), pass.instanceSeconds, pass.cfUnitSeconds.setScale(UNIT_SCALE, RoundingMode.HALF_UP),
                cfList, adj, cf, spot, gross, ded, net, bound, "MEASURED", List.copyOf(pass.snapshotIds),
                contractId(in), toExclusions(exclusions));
    }

    /** The §3.6 worst case when only Spot prices are missing: the missing Spot rate is the On-Demand list rate. */
    private BigDecimal worstCase(CalcInputs in, StateWindows windows, List<Item> items, long ws, long we, Pass first) {
        if (!first.unknown.equals(new TreeSet<>(List.of(Reasons.MISSING_SPOT_PRICE)))) {
            return null;
        }
        Pass p = new Pass(in, windows, items, ws, we, true);
        p.run();
        if (p.noBound) {
            return null;
        }
        BigDecimal cf = money(p.cfRs);
        BigDecimal spot = money(p.spotRs);
        List<CalcInputs.PreType> pre = in.original() == null ? List.of() : in.original().preEnable();
        CommitmentDeduction.Result r = CommitmentDeduction.compute(cf, p.cfUnitSeconds, pre, in.commitmentsStatus(),
                in.commitments());
        if (r.capReason() != null) {
            return spot.negate();
        }
        return cf.subtract(spot).subtract(r.deduction());
    }

    /** The state or go-live reason with the most seconds when {@code M(W)} is empty; ties go to the first by code. */
    private static String dominantExclusion(Exclusions exclusions) {
        String best = null;
        long bestSeconds = 0;
        for (var e : exclusions.wallSecondsByReason().entrySet()) {
            if (e.getValue() > bestSeconds) {
                best = e.getKey();
                bestSeconds = e.getValue();
            }
        }
        return best;
    }

    private static List<EntryCalc.Exclusion> toExclusions(List<Exclusions.Item> items) {
        return items.stream().map(i -> new EntryCalc.Exclusion(i.reason(), i.unitSeconds(), i.counterfactualCost()))
                .toList();
    }

    private static String contractId(CalcInputs in) {
        return in.contract() == null ? null : in.contract().contractId();
    }

    private static BigDecimal money(BigDecimal rateSeconds) {
        return Money.ofRateSeconds(rateSeconds, USD).amount();
    }

    private static LocalDate month(CalcInputs in) {
        return in.windowStart().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    }
}
