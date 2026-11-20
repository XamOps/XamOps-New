package com.xammer.cloud.service.autospotting.ledger.calc;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.DiscountBasis;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Gcp;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Interval;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.OdPrice;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Original;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.PreType;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Shape;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.SpotPrice;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Sud;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.UnitPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns the GCP block of {@link CalcInputs} into what the shared segment engine reads (design-ledger.md §3.5,
 * §3.9, §4.2): a per-instance-hour On-Demand list and effective rate, and a per-instance-hour Spot rate, for every
 * machine type in use. The engine then runs the same lower-of, capacity and exclusion rules as on AWS.
 *
 * <p>Per instance-hour {@code = vcpu x core + gib x ram} exactly, rounded once to scale 12, {@code HALF_UP}
 * (the rate column's scale, §9.1). The effective On-Demand rate is {@code list x (1 - sud)} (and then
 * {@code x (1 - declared/100)}), again rounded once to scale 12. Spot VMs earn no SUD. A type that cannot be priced
 * gets no rate and a reason code instead: the engine then reports the entry UNKNOWN with that code, and nothing is
 * defaulted (PD-09: a shared-core type missing from {@code gcp-shared-core-v1} is never priced at its
 * {@code guestCpus}).
 */
final class GcpPrep {
    static final int RATE_SCALE = 12;
    /** GCP has no platform dimension: one constant keeps the engine's platform checks and keys uniform. */
    static final String PLATFORM = "GCP";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);
    static final String CSP = "XAMMER_CSP_DATASET";

    /** The effective core and RAM rates of a series after SUD, and the SUD rate used. */
    record SeriesRates(BigDecimal coreRate, BigDecimal ramRate, BigDecimal sudRate) {}

    record Prepared(CalcInputs engine, Map<String, Shape> shapes, Map<String, String> problems,
                    Map<String, SeriesRates> series, boolean cspPending) {
        /** The unknown-input code for a type the engine found no On-Demand rate for. */
        String odProblem(String type, Original original) {
            String p = problems.get(type);
            if (p != null) {
                return p;
            }
            if (original != null) {
                for (PreType pre : original.preEnable()) {
                    String q = problems.get(pre.instanceType());
                    if (q != null) {
                        return q;
                    }
                }
            }
            return Reasons.MISSING_OD_PRICE;
        }

        String spotProblem(String type) {
            String p = problems.get(type);
            return Reasons.GCP_SHARED_CORE_UNSUPPORTED.equals(p) ? p : Reasons.MISSING_SPOT_PRICE;
        }
    }

    private GcpPrep() {}

    static Prepared prepare(CalcInputs in) {
        Gcp gcp = in.gcp();
        if (gcp == null) {
            throw new IllegalArgumentException("gcp-1 needs the gcp inputs block");
        }
        Map<String, Shape> shapes = new TreeMap<>();
        for (Shape s : gcp.shapes()) {
            shapes.put(s.instanceType(), s);
        }
        Map<String, Sud> sudBySeries = new HashMap<>();
        for (Sud s : gcp.sud()) {
            sudBySeries.put(s.series(), s);
        }
        TreeSet<String> types = new TreeSet<>();
        for (Interval iv : in.intervals()) {
            types.add(iv.instanceType());
        }
        if (in.original() != null) {
            for (PreType p : in.original().preEnable()) {
                types.add(p.instanceType());
            }
        }
        Map<String, String> problems = new TreeMap<>();
        Map<String, SeriesRates> series = new TreeMap<>();
        List<OdPrice> od = new ArrayList<>();
        List<SpotPrice> spot = new ArrayList<>();
        Map<String, List<Window>> spotBySeries = new HashMap<>();
        for (String type : types) {
            Shape shape = shapes.get(type);
            if (shape == null || shape.gib() == null || shape.series() == null) {
                problems.put(type, Reasons.MISSING_OD_PRICE);
                continue;
            }
            if (shape.vcpu() == null) {
                problems.put(type, Reasons.GCP_SHARED_CORE_UNSUPPORTED);
                continue;
            }
            // Spot: independent of SUD.
            List<Window> sp = spotBySeries.computeIfAbsent(shape.series(), k -> spotWindows(gcp, shape.series()));
            for (Window w : sp) {
                spot.add(new SpotPrice(w.snapshotId(), type, null, PLATFORM, perInstance(shape, w.core(), w.ram()),
                        w.from(), w.to()));
            }
            // On-Demand: list x (1 - SUD).
            UnitPrice core = latest(gcp, "ON_DEMAND", shape.series(), "CORE", in.windowStart());
            UnitPrice ram = latest(gcp, "ON_DEMAND", shape.series(), "RAM", in.windowStart());
            Sud sud = sudBySeries.get(shape.series());
            if (core == null || ram == null) {
                problems.put(type, Reasons.MISSING_OD_PRICE);
                continue;
            }
            if (sud == null) {
                problems.put(type, Reasons.MISSING_SUD_RATE);
                continue;
            }
            SeriesRates rates = series.get(shape.series());
            if (rates == null) {
                SustainedUseDiscount.Result r = SustainedUseDiscount.rate(sud, core.rate(), ram.rate());
                BigDecimal keep = keep(r.rate(), gcp.declaredDiscountPct());
                rates = new SeriesRates(core.rate().multiply(keep).setScale(RATE_SCALE, RoundingMode.HALF_UP),
                        ram.rate().multiply(keep).setScale(RATE_SCALE, RoundingMode.HALF_UP), r.rate());
                series.put(shape.series(), rates);
            }
            BigDecimal list = shape.vcpu().multiply(core.rate()).add(shape.gib().multiply(ram.rate()))
                    .setScale(RATE_SCALE, RoundingMode.HALF_UP);
            BigDecimal effective = list.multiply(keep(rates.sudRate(), gcp.declaredDiscountPct())).setScale(RATE_SCALE, RoundingMode.HALF_UP);
            String snapshot = core.snapshotId() + "+" + ram.snapshotId()
                    + (sud.snapshotId() == null ? "" : "+" + sud.snapshotId());
            od.add(new OdPrice(snapshot, type, PLATFORM, list, effective,
                    gcp.declaredDiscountPct() == null ? DiscountBasis.NONE : DiscountBasis.DECLARED));
        }
        List<Interval> intervals = in.intervals().stream().map(iv -> new Interval(iv.intervalId(), iv.instanceId(),
                iv.instanceType(), iv.zone(), PLATFORM, iv.purchaseOption(), iv.capacityWeight(), iv.startAt(),
                iv.endAt(), iv.lastSeenAt(), iv.firstAbsentAt(), iv.endCause())).toList();
        Original original = in.original() == null ? null : new Original(in.original().baseUnits(),
                in.original().onDemandPctAboveBase(), in.original().preEnable().stream()
                .map(p -> new PreType(p.instanceType(), PLATFORM, p.weight(), p.normalizationFactor())).toList());
        CalcInputs engine = new CalcInputs(in.calcVersion(), in.provider(), in.currency(), in.windowStart(),
                in.windowEnd(), in.group(), in.goLiveAt(), in.contract(), in.feeBasis(), in.reconciledTier(),
                in.transitions(), in.revertOps(), intervals, in.capacitySamples(), spot, od, original,
                in.commitmentsStatus(), List.of(), gcp);
        return new Prepared(engine, shapes, problems, series, CSP.equals(gcp.billingSource()));
    }

    /** Spot per instance-hour: vcpu x core + gib x ram, rounded once. {@code ram} comes from the paired snapshot. */
    private static BigDecimal perInstance(Shape shape, BigDecimal core, BigDecimal ram) {
        return shape.vcpu().multiply(core).add(shape.gib().multiply(ram)).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** The share of list that is paid: {@code (1 - sud) x (1 - declared/100)}. */
    private static BigDecimal keep(BigDecimal sud, BigDecimal declaredPct) {
        BigDecimal keep = BigDecimal.ONE.subtract(sud);
        if (declaredPct != null) {
            keep = keep.multiply(HUNDRED.subtract(declaredPct)).divide(HUNDRED, RATE_SCALE + 4, RoundingMode.HALF_UP);
        }
        return keep;
    }

    /** A Spot price window of a series: the core and RAM rates in force from {@code from} until {@code to}. */
    private record Window(String snapshotId, BigDecimal core, BigDecimal ram, Instant from, Instant to) {}

    /**
     * The Spot windows of a series: the core and RAM snapshots cut at every start and end, each window priced by
     * the newest snapshot of each resource in force at its start. A window with no core or no RAM snapshot is
     * left out, so a Spot instance there has no price (MISSING_SPOT_PRICE).
     */
    private static List<Window> spotWindows(Gcp gcp, String series) {
        List<UnitPrice> core = gcp.prices().stream().filter(p -> "SPOT".equals(p.model()) && series.equals(p.series())
                && "CORE".equals(p.resource())).toList();
        List<UnitPrice> ram = gcp.prices().stream().filter(p -> "SPOT".equals(p.model()) && series.equals(p.series())
                && "RAM".equals(p.resource())).toList();
        TreeSet<Instant> cuts = new TreeSet<>();
        for (UnitPrice p : core) {
            cuts.add(p.effectiveFrom());
            if (p.effectiveTo() != null) {
                cuts.add(p.effectiveTo());
            }
        }
        for (UnitPrice p : ram) {
            cuts.add(p.effectiveFrom());
            if (p.effectiveTo() != null) {
                cuts.add(p.effectiveTo());
            }
        }
        List<Instant> points = new ArrayList<>(cuts);
        List<Window> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Instant a = points.get(i);
            Instant b = i + 1 < points.size() ? points.get(i + 1) : null;
            UnitPrice c = inForce(core, a);
            UnitPrice r = inForce(ram, a);
            if (c == null || r == null) {
                continue;
            }
            out.add(new Window(c.snapshotId() + "+" + r.snapshotId(), c.rate(), r.rate(), a, b));
        }
        return out;
    }

    private static UnitPrice inForce(List<UnitPrice> prices, Instant at) {
        UnitPrice best = null;
        for (UnitPrice p : prices) {
            if (p.effectiveFrom() == null || p.effectiveFrom().isAfter(at)
                    || (p.effectiveTo() != null && !at.isBefore(p.effectiveTo()))) {
                continue;
            }
            if (best == null || p.effectiveFrom().isAfter(best.effectiveFrom())
                    || (p.effectiveFrom().equals(best.effectiveFrom())
                    && String.valueOf(p.snapshotId()).compareTo(String.valueOf(best.snapshotId())) > 0)) {
                best = p;
            }
        }
        return best;
    }

    private static UnitPrice latest(Gcp gcp, String model, String series, String resource, Instant at) {
        return inForce(gcp.prices().stream().filter(p -> model.equals(p.model()) && series.equals(p.series())
                && resource.equals(p.resource())).sorted(Comparator.comparing(UnitPrice::snapshotId)).toList(), at);
    }
}
