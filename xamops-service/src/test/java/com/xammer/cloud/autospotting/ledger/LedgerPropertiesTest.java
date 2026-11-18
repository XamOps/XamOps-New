package com.xammer.cloud.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.*;
import com.xammer.cloud.service.autospotting.ledger.calc.CanonicalJson;
import com.xammer.cloud.service.autospotting.ledger.calc.EntryCalc;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.ledger.calc.StatementLineMath;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property tests of the savings invariants (design-ledger.md §14.1, AC-LED-02; plan-phase-16.md task
 * 16.3), run with jqwik's default 1,000 tries per property over random intervals, state windows, prices,
 * commitments, contracts and tiers. A failing seed is reported by jqwik and reproduces the input exactly
 * (the generator is a pure function of the {@code long} seed, built from integers only).
 *
 * <ul>
 *   <li>{@code 0 <= billable_amount <= round(CF_billable)}; a negative net keeps its raw value and bills 0;</li>
 *   <li>UNKNOWN and legacy never billable, and an UNKNOWN entry carries no money;</li>
 *   <li>zero money outside {@code M(W)}, from the floor and from the original share;</li>
 *   <li>commercial-only entries never billable; {@code ck_sle_math} holds;</li>
 *   <li>transitions out of {@code id} order give {@code UNKNOWN(MISSING_STATE_ORDER)};</li>
 *   <li>the result does not depend on the order facts are listed, and recomputing is idempotent.</li>
 * </ul>
 */
class LedgerPropertiesTest {
    private static final SavingsCalculator CALCULATOR = new SavingsCalculator();
    private static final Instant DAY = Instant.parse("2026-10-01T00:00:00Z");
    private static final String[] TYPES = {"m5.xlarge", "c5.xlarge"};

    // ================================================================ generator

    private static Instant at(long offsetSeconds) {
        return DAY.plusSeconds(offsetSeconds);
    }

    private static BigDecimal units(long n, int scale) {
        return BigDecimal.valueOf(n, scale);
    }

    static CalcInputs generate(long seed) {
        Random r = new Random(seed);
        List<Transition> transitions = new ArrayList<>();
        GroupState[] pool = {GroupState.ACTIVE, GroupState.ACTIVE, GroupState.ACTIVE, GroupState.DEGRADED,
                GroupState.FALLBACK, GroupState.PAUSED, GroupState.REVERTING, GroupState.APPLYING,
                GroupState.LEGACY_UNCONFIRMED, GroupState.REVERTED, GroupState.GONE, GroupState.FAILED};
        long time = -(2 * 86_400L);
        GroupState previous = GroupState.PLANNED;
        int k = r.nextInt(5);
        long id = 1;
        if (r.nextInt(10) < 8) {
            transitions.add(new Transition(id++, previous, GroupState.ACTIVE, at(time), at(time)));
            previous = GroupState.ACTIVE;
        }
        for (int i = 0; i < k; i++) {
            time += r.nextInt(30_000);
            GroupState to = pool[r.nextInt(pool.length)];
            transitions.add(new Transition(id++, previous, to, at(time), at(time + r.nextInt(120))));
            previous = to;
        }
        List<RevertOp> ops = new ArrayList<>();
        if (r.nextInt(4) == 0) {
            long confirmed = r.nextInt(86_400);
            Instant finished = r.nextBoolean() ? null : at(confirmed + r.nextInt(7_200));
            ops.add(new RevertOp("op-" + seed, at(confirmed), finished, r.nextBoolean() ? null : 5L));
        }
        List<Interval> intervals = new ArrayList<>();
        int spots = r.nextInt(6);
        long[] lives = {30, 45, 59, 60, 61, 600, 3_599, 3_600, 5_400, 40_000};
        for (int i = 0; i < spots; i++) {
            long start = r.nextInt(90_000) - 7_200;
            String type = TYPES[r.nextInt(TYPES.length)];
            String platform = r.nextInt(8) == 0 ? "SUSE" : "Linux";
            BigDecimal weight = r.nextInt(40) == 0 ? null : BigDecimal.valueOf(new long[] {1, 2, 4}[r.nextInt(3)]);
            Instant startAt = at(start);
            Instant endAt = null;
            Instant lastSeen = null;
            Instant firstAbsent = null;
            EndCause cause = null;
            int shape = r.nextInt(10);
            if (shape < 3) {
                // still running
            } else if (shape < 4) {
                lastSeen = at(start + r.nextInt(2_000));
                firstAbsent = lastSeen.plusSeconds(1 + r.nextInt(600));
            } else {
                endAt = at(start + lives[r.nextInt(lives.length)]);
                cause = EndCause.values()[r.nextInt(EndCause.values().length)];
            }
            intervals.add(new Interval("s" + i, "i-s" + i, type, "us-east-1a", platform, PurchaseOption.SPOT, weight,
                    startAt, endAt, lastSeen, firstAbsent, cause));
        }
        int ods = r.nextInt(3);
        for (int i = 0; i < ods; i++) {
            long start = -r.nextInt(100_000);
            Instant endAt = r.nextInt(4) == 0 ? at(r.nextInt(90_000)) : null;
            intervals.add(new Interval("o" + i, "i-o" + i, "m5.xlarge", "us-east-1a", "Linux", PurchaseOption.ON_DEMAND,
                    BigDecimal.valueOf(1 + r.nextInt(2)), at(start), endAt, null, null, endAt == null ? null : EndCause.TERMINATED));
        }
        List<CapacitySample> samples = new ArrayList<>();
        int ns = r.nextInt(20) == 0 ? 0 : 1 + r.nextInt(3);
        long st = -(3 * 86_400L);
        for (int i = 0; i < ns; i++) {
            st += r.nextInt(60_000);
            samples.add(new CapacitySample(at(st), BigDecimal.valueOf(r.nextInt(11))));
        }
        List<SpotPrice> spotPrices = new ArrayList<>();
        List<OdPrice> odPrices = new ArrayList<>();
        int n = 0;
        for (String type : TYPES) {
            for (String platform : new String[] {"Linux", "SUSE"}) {
                n++;
                if (r.nextInt(12) != 0) {
                    spotPrices.add(new SpotPrice(String.format("50000000-0000-4000-8000-%012d", n), type, "us-east-1a",
                            platform, units(100 + r.nextInt(4_000), 4), at(-90_000 - r.nextInt(100_000)),
                            r.nextInt(8) == 0 ? at(r.nextInt(86_400)) : null));
                    if (r.nextInt(5) == 0) {
                        spotPrices.add(new SpotPrice(String.format("51000000-0000-4000-8000-%012d", n), type, "us-east-1a",
                                platform, units(100 + r.nextInt(4_000), 4), at(r.nextInt(86_400)), null));
                    }
                }
                if (r.nextInt(20) != 0) {
                    long list = 500 + r.nextInt(5_000);
                    long eff = list * (90 + r.nextInt(11)) / 100;
                    DiscountBasis basis = r.nextInt(10) == 0 ? DiscountBasis.NONE : DiscountBasis.NET_DERIVED;
                    odPrices.add(new OdPrice(String.format("60000000-0000-4000-8000-%012d", n), type, platform,
                            units(list, 4), units(eff, 4), basis));
                }
            }
        }
        Original original = r.nextInt(30) == 0 ? null : new Original(BigDecimal.valueOf(r.nextInt(4)),
                BigDecimal.valueOf(r.nextInt(2) == 0 ? 100 : r.nextInt(101)),
                List.of(new PreType("m5.xlarge", r.nextInt(8) == 0 ? "SUSE" : "Linux", BigDecimal.ONE, BigDecimal.valueOf(8))));
        CommitmentsStatus status = new CommitmentsStatus[] {CommitmentsStatus.NONE_PROVEN, CommitmentsStatus.NONE_PROVEN,
                CommitmentsStatus.NONE_PROVEN, CommitmentsStatus.KNOWN, CommitmentsStatus.KNOWN,
                CommitmentsStatus.UNAVAILABLE}[r.nextInt(6)];
        List<Commitment> commitments = new ArrayList<>();
        if (status == CommitmentsStatus.KNOWN) {
            int nc = 1 + r.nextInt(2);
            for (int i = 0; i < nc; i++) {
                boolean ri = r.nextBoolean();
                Map<String, BigDecimal> rates = r.nextInt(8) == 0 ? Map.of()
                        : Map.of("m5.xlarge", units(500 + r.nextInt(1_500), 4));
                commitments.add(new Commitment("scope-" + i, ri ? CommitmentKind.RESERVED_INSTANCE : CommitmentKind.SAVINGS_PLAN,
                        r.nextInt(8) == 0 ? CommitmentData.RATE_UNKNOWN : CommitmentData.AVAILABLE,
                        units(r.nextInt(500_000), 4), units(r.nextInt(300_000), 4), r.nextInt(3) == 0,
                        units(r.nextInt(100_000), 4), rates));
            }
        }
        Instant goLive = r.nextInt(20) == 0 ? null : (r.nextInt(7) == 0 ? at(r.nextInt(100_000) - 10_000) : at(-5 * 86_400L));
        Contract contract = r.nextInt(5) == 0 ? null : new Contract("cccccccc-0000-4000-8000-000000000001", units(200_000, 4));
        FeeBasis[] fee = FeeBasis.values();
        Tier[] tiers = {Tier.BILL_VERIFIED, Tier.BILL_VERIFIED, Tier.AGGREGATE_VERIFIED, Tier.MEASURED_ONLY};
        return new CalcInputs("aws-1", "AWS", "USD", DAY, DAY.plusSeconds(86_400L),
                new Group("11111111-1111-4111-8111-111111111111", 1001L, 501L, "us-east-1"), goLive, contract,
                fee[r.nextInt(fee.length)], tiers[r.nextInt(tiers.length)], transitions, ops, intervals, samples,
                spotPrices, odPrices, original, status, commitments);
    }

    /** Inputs that are measured and complete most of the time: a long ACTIVE window and every price present. */
    static CalcInputs generateComplete(long seed) {
        CalcInputs g = generate(seed);
        Random r = new Random(seed ^ 0x5DEECE66DL);
        List<SpotPrice> sp = new ArrayList<>();
        List<OdPrice> od = new ArrayList<>();
        for (String type : TYPES) {
            for (String platform : new String[] {"Linux", "SUSE"}) {
                sp.add(new SpotPrice("50000000-0000-4000-8000-0000000000" + (sp.size() + 10), type, "us-east-1a", platform,
                        units(100 + r.nextInt(6_000), 4), at(-200_000), null));
                long list = 500 + r.nextInt(5_000);
                od.add(new OdPrice("60000000-0000-4000-8000-0000000000" + (od.size() + 10), type, platform, units(list, 4),
                        units(list * (90 + r.nextInt(11)) / 100, 4), DiscountBasis.NET_DERIVED));
            }
        }
        List<Interval> fixed = new ArrayList<>();
        for (Interval i : g.intervals()) {
            fixed.add(new Interval(i.intervalId(), i.instanceId(), i.instanceType(), i.zone(), i.platform(),
                    i.purchaseOption(), i.capacityWeight() == null ? BigDecimal.ONE : i.capacityWeight(), i.startAt(),
                    i.endAt(), i.lastSeenAt(), i.firstAbsentAt(), i.endCause()));
        }
        List<CapacitySample> samples = new ArrayList<>(g.capacitySamples());
        if (samples.isEmpty()) {
            samples.add(new CapacitySample(at(-300_000), BigDecimal.valueOf(1 + r.nextInt(10))));
        }
        List<Transition> transitions = new ArrayList<>();
        transitions.add(new Transition(1, GroupState.PLANNED, GroupState.ACTIVE, at(-200_000), at(-200_000)));
        if (r.nextBoolean()) {
            transitions.add(new Transition(2, GroupState.ACTIVE, GroupState.PAUSED, at(r.nextInt(86_400)), at(0)));
        }
        Original original = new Original(BigDecimal.valueOf(r.nextInt(3)), BigDecimal.valueOf(r.nextInt(101)),
                List.of(new PreType("m5.xlarge", "Linux", BigDecimal.ONE, BigDecimal.valueOf(8))));
        return new CalcInputs(g.calcVersion(), g.provider(), g.currency(), g.windowStart(), g.windowEnd(), g.group(),
                at(-5 * 86_400L), g.contract(), g.feeBasis(), g.reconciledTier(), transitions, List.of(), fixed, samples, sp,
                od, original, g.commitmentsStatus(), g.commitments());
    }

    // ================================================================ helpers

    private static Optional<EntryCalc> calc(CalcInputs in) {
        return CALCULATOR.calculate(in);
    }

    private static void assertMath(EntryCalc e) {
        assertThat(e.grossSavings()).isEqualByComparingTo(e.counterfactualCost().subtract(e.spotCost()));
        assertThat(e.netSavings()).isEqualByComparingTo(e.grossSavings().subtract(e.commitmentDeduction()));
        assertThat(e.counterfactualCost()).isEqualByComparingTo(e.counterfactualListCost().subtract(e.effectiveRateAdjustment()));
        assertThat(e.commitmentDeduction().signum()).isGreaterThanOrEqualTo(0);
        assertThat(e.spotCost().signum()).isGreaterThanOrEqualTo(0);
        assertThat(e.commitmentDeduction()).isLessThanOrEqualTo(e.counterfactualCost().max(BigDecimal.ZERO));
        for (BigDecimal v : List.of(e.counterfactualListCost(), e.effectiveRateAdjustment(), e.counterfactualCost(),
                e.spotCost(), e.grossSavings(), e.commitmentDeduction(), e.netSavings())) {
            assertThat(v.scale()).as("entry amounts are scale 10").isEqualTo(10);
        }
    }

    private static boolean allMoneyZero(EntryCalc e) {
        return e.counterfactualListCost().signum() == 0 && e.effectiveRateAdjustment().signum() == 0
                && e.counterfactualCost().signum() == 0 && e.spotCost().signum() == 0 && e.grossSavings().signum() == 0
                && e.commitmentDeduction().signum() == 0 && e.netSavings().signum() == 0;
    }

    private static String canonical(Optional<EntryCalc> e) {
        return e.map(CanonicalJson::compact).orElse("none");
    }

    // ================================================================ properties

    @Property
    void billableAmountIsBetweenZeroAndTheCounterfactual_andANegativeNetBillsZeroWithTheRawKept(@ForAll long seed) {
        CalcInputs in = generate(seed);
        Optional<EntryCalc> maybe = calc(in);
        if (maybe.isEmpty()) {
            return;
        }
        EntryCalc e = maybe.get();
        assertMath(e);
        StatementLineMath.Line line = StatementLineMath.build(List.of(e), in.contractCovers());
        assertThat(line.billableAmount().signum()).isGreaterThanOrEqualTo(0);
        BigDecimal cfBillable = e.billable() ? e.counterfactualCost() : BigDecimal.ZERO;
        assertThat(line.billableAmount()).isLessThanOrEqualTo(cfBillable.setScale(2, RoundingMode.HALF_UP));
        assertThat(line.rawNet()).isEqualByComparingTo(e.netSavings());
        if (e.netSavings().signum() < 0) {
            assertThat(line.billableAmount()).isEqualByComparingTo("0.00");
        }
        if (!line.billable()) {
            assertThat(line.billableAmount()).isEqualByComparingTo("0.00");
        }
    }

    @Property
    void billableAmountInvariantHoldsOnCompleteMeasuredInputs(@ForAll long seed) {
        CalcInputs in = generateComplete(seed);
        Optional<EntryCalc> maybe = calc(in);
        if (maybe.isEmpty()) {
            return;
        }
        EntryCalc e = maybe.get();
        assertMath(e);
        StatementLineMath.Line line = StatementLineMath.build(List.of(e), in.contractCovers());
        assertThat(line.billableAmount().signum()).isGreaterThanOrEqualTo(0);
        if (e.billable()) {
            assertThat(line.billableAmount()).isLessThanOrEqualTo(e.counterfactualCost().setScale(2, RoundingMode.HALF_UP));
            assertThat(line.billableAmount()).isLessThanOrEqualTo(e.netSavings().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        }
    }

    @Property
    void unknownAndLegacyAndCommercialAndUncappedEntriesAreNeverBillable_andUnknownCarriesNoMoney(@ForAll long seed) {
        CalcInputs in = generate(seed);
        Optional<EntryCalc> maybe = calc(in);
        if (maybe.isEmpty()) {
            return;
        }
        EntryCalc e = maybe.get();
        if (e.tier() == Tier.UNKNOWN) {
            assertThat(e.billable()).isFalse();
            assertThat(e.unknownInputs()).isNotEmpty();
            assertThat(allMoneyZero(e)).isTrue();
        } else {
            assertThat(e.unknownInputs()).isEmpty();
        }
        if (e.billable()) {
            assertThat(e.tier().isBillable()).isTrue();
            assertThat(e.nonBillableReason()).isNull();
            assertThat(in.contractCovers()).isTrue();
            assertThat(e.unknownInputs()).isEmpty();
            assertThat(e.netSavingsLowerBound()).isNull();
        } else {
            assertThat(e.nonBillableReason()).isNotBlank();
        }
        if (in.contractReason() != null) {
            assertThat(e.billable()).isFalse();
        }
        if ("LEGACY_UNCONFIRMED".equals(e.nonBillableReason())) {
            assertThat(e.billable()).isFalse();
        }
        if (!e.billable() && e.netSavingsLowerBound() != null && e.tier() != Tier.UNKNOWN) {
            assertThat(e.netSavingsLowerBound()).isLessThanOrEqualTo(e.netSavings());
        }
    }

    @Property
    void aLegacyGroupNeverEarnsMoneyOrBillsAnything(@ForAll long seed) {
        CalcInputs g = generateComplete(seed);
        CalcInputs in = withTransitions(g, List.of(new Transition(1, GroupState.UNMANAGED, GroupState.LEGACY_UNCONFIRMED,
                at(-200_000), at(-200_000))));
        EntryCalc e = calc(in).orElseThrow();
        assertThat(e.billable()).isFalse();
        assertThat(e.nonBillableReason()).isEqualTo("LEGACY_UNCONFIRMED");
        assertThat(allMoneyZero(e)).isTrue();
        assertThat(e.instanceSeconds()).isZero();
    }

    @Property
    void noMoneyOutsideTheMeasuredWindows(@ForAll long seed) {
        CalcInputs g = generateComplete(seed);
        // Never ACTIVE inside the day: PAUSED since before it.
        CalcInputs paused = withTransitions(g, List.of(new Transition(1, GroupState.PLANNED, GroupState.PAUSED, at(-200_000), at(-200_000))));
        EntryCalc p = calc(paused).orElseThrow();
        assertThat(allMoneyZero(p)).isTrue();
        assertThat(p.billable()).isFalse();
        assertThat(p.nonBillableReason()).isEqualTo("STATE_PAUSED");
        // Ledger goes live after the day.
        CalcInputs early = withGoLive(withTransitions(g, List.of(new Transition(1, GroupState.PLANNED, GroupState.ACTIVE,
                at(-200_000), at(-200_000)))), at(86_400L + 10));
        EntryCalc b = calc(early).orElseThrow();
        assertThat(allMoneyZero(b)).isTrue();
        assertThat(b.billable()).isFalse();
        assertThat(b.nonBillableReason()).isEqualTo("BEFORE_GO_LIVE");
    }

    @Property
    void theOnDemandFloorAndTheOriginalShareNeverRaiseTheCounterfactual(@ForAll long seed) {
        CalcInputs g = generateComplete(seed);
        CalcInputs base = withTransitions(g, List.of(new Transition(1, GroupState.PLANNED, GroupState.ACTIVE, at(-200_000), at(-200_000))));
        EntryCalc e = calc(base).orElseThrow();
        // One more On-Demand unit all day.
        List<Interval> more = new ArrayList<>(base.intervals());
        more.add(new Interval("extra-floor", "i-extra", "m5.xlarge", "us-east-1a", "Linux", PurchaseOption.ON_DEMAND,
                BigDecimal.ONE, at(-100_000), null, null, null, null));
        EntryCalc floor = calc(withIntervals(base, more)).orElseThrow();
        assertThat(floor.counterfactualCost()).isLessThanOrEqualTo(e.counterfactualCost());
        assertThat(floor.counterfactualUnitSeconds()).isLessThanOrEqualTo(e.counterfactualUnitSeconds());
        assertThat(floor.spotCost()).isEqualByComparingTo(e.spotCost());
        // A bigger original Spot share (lower On-Demand percentage above the base).
        Original o = base.original();
        Original moreShare = new Original(o.baseUnits(), o.onDemandPctAboveBase().divide(BigDecimal.valueOf(2), 0, RoundingMode.FLOOR), o.preEnable());
        EntryCalc share = calc(withOriginal(base, moreShare)).orElseThrow();
        assertThat(share.counterfactualCost()).isLessThanOrEqualTo(e.counterfactualCost());
        assertThat(share.spotCost()).isEqualByComparingTo(e.spotCost());
    }

    @Property
    void theResultDoesNotDependOnTheOrderFactsAreListed(@ForAll long seed) {
        CalcInputs in = generate(seed);
        String expected = canonical(calc(in));
        Random r = new Random(seed + 1);
        CalcInputs shuffled = new CalcInputs(in.calcVersion(), in.provider(), in.currency(), in.windowStart(), in.windowEnd(),
                in.group(), in.goLiveAt(), in.contract(), in.feeBasis(), in.reconciledTier(), shuffle(in.transitions(), r),
                shuffle(in.revertOps(), r), shuffle(in.intervals(), r), shuffle(in.capacitySamples(), r),
                shuffle(in.spotPrices(), r), shuffle(in.odPrices(), r), in.original(), in.commitmentsStatus(),
                shuffle(in.commitments(), r));
        assertThat(canonical(calc(shuffled))).isEqualTo(expected);
    }

    @Property
    void recomputingIsIdempotent_includingThroughTheStoredJsonInputs(@ForAll long seed) {
        CalcInputs in = generate(seed);
        String first = canonical(calc(in));
        assertThat(canonical(calc(in))).isEqualTo(first);
        String stored = CanonicalJson.compact(in);
        CalcInputs reloaded = CanonicalJson.read(stored, CalcInputs.class);
        assertThat(CanonicalJson.compact(reloaded)).isEqualTo(stored);
        assertThat(CanonicalJson.hash(reloaded)).isEqualTo(CanonicalJson.hash(in));
        assertThat(canonical(calc(reloaded))).isEqualTo(first);
    }

    @Property
    void transitionsOutOfIdOrderInsideTheDayGiveUnknownMissingStateOrder(@ForAll long seed) {
        Random r = new Random(seed);
        CalcInputs g = generateComplete(seed);
        int n = 2 + r.nextInt(3);
        List<Transition> list = new ArrayList<>();
        long t = 1_000 + r.nextInt(2_000);
        GroupState[] states = {GroupState.ACTIVE, GroupState.PAUSED, GroupState.DEGRADED, GroupState.FALLBACK};
        for (int i = 0; i < n; i++) {
            // ids descend while time ascends: the id order is the reverse of the effective order.
            list.add(new Transition(100 - i, GroupState.ACTIVE, states[r.nextInt(states.length)], at(t), at(t)));
            t += 600 + r.nextInt(5_000);
        }
        EntryCalc e = calc(withTransitions(g, list)).orElseThrow();
        assertThat(e.tier()).isEqualTo(Tier.UNKNOWN);
        assertThat(e.unknownInputs()).containsExactly("MISSING_STATE_ORDER");
        assertThat(e.billable()).isFalse();
        assertThat(allMoneyZero(e)).isTrue();
    }

    /** Guards against vacuous properties: the generators must reach every outcome the invariants talk about. */
    @Test
    void theGeneratorsReachEveryOutcome() {
        int none = 0, billable = 0, unknown = 0, measuredOnly = 0, negative = 0, commercial = 0, deducted = 0, zeroMeasured = 0;
        for (long seed = 0; seed < 3_000; seed++) {
            CalcInputs in = seed % 2 == 0 ? generate(seed) : generateComplete(seed);
            Optional<EntryCalc> maybe = calc(in);
            if (maybe.isEmpty()) {
                none++;
                continue;
            }
            EntryCalc e = maybe.get();
            billable += e.billable() ? 1 : 0;
            unknown += e.tier() == Tier.UNKNOWN ? 1 : 0;
            measuredOnly += e.tier() == Tier.MEASURED_ONLY ? 1 : 0;
            negative += e.netSavings().signum() < 0 ? 1 : 0;
            commercial += in.contractReason() != null && e.counterfactualCost().signum() > 0 ? 1 : 0;
            deducted += e.commitmentDeduction().signum() > 0 ? 1 : 0;
            zeroMeasured += e.tier() != Tier.UNKNOWN && e.counterfactualCost().signum() == 0 ? 1 : 0;
        }
        assertThat(none).as("none").isGreaterThan(20);
        assertThat(billable).as("billable").isGreaterThan(150);
        assertThat(unknown).as("unknown").isGreaterThan(100);
        assertThat(measuredOnly).as("measuredOnly").isGreaterThan(200);
        assertThat(negative).as("negative").isGreaterThan(50);
        assertThat(commercial).as("commercial").isGreaterThan(100);
        assertThat(deducted).as("deducted").isGreaterThan(50);
        assertThat(zeroMeasured).as("zeroMeasured").isGreaterThan(50);
    }

    // ================================================================ copies

    private static <T> List<T> shuffle(List<T> in, Random r) {
        List<T> copy = new ArrayList<>(in);
        Collections.shuffle(copy, r);
        return copy;
    }

    private static CalcInputs withTransitions(CalcInputs g, List<Transition> t) {
        return new CalcInputs(g.calcVersion(), g.provider(), g.currency(), g.windowStart(), g.windowEnd(), g.group(),
                g.goLiveAt(), g.contract(), g.feeBasis(), g.reconciledTier(), t, g.revertOps(), g.intervals(),
                g.capacitySamples(), g.spotPrices(), g.odPrices(), g.original(), g.commitmentsStatus(), g.commitments());
    }

    private static CalcInputs withGoLive(CalcInputs g, Instant goLive) {
        return new CalcInputs(g.calcVersion(), g.provider(), g.currency(), g.windowStart(), g.windowEnd(), g.group(),
                goLive, g.contract(), g.feeBasis(), g.reconciledTier(), g.transitions(), g.revertOps(), g.intervals(),
                g.capacitySamples(), g.spotPrices(), g.odPrices(), g.original(), g.commitmentsStatus(), g.commitments());
    }

    private static CalcInputs withIntervals(CalcInputs g, List<Interval> i) {
        return new CalcInputs(g.calcVersion(), g.provider(), g.currency(), g.windowStart(), g.windowEnd(), g.group(),
                g.goLiveAt(), g.contract(), g.feeBasis(), g.reconciledTier(), g.transitions(), g.revertOps(), i,
                g.capacitySamples(), g.spotPrices(), g.odPrices(), g.original(), g.commitmentsStatus(), g.commitments());
    }

    private static CalcInputs withOriginal(CalcInputs g, Original o) {
        return new CalcInputs(g.calcVersion(), g.provider(), g.currency(), g.windowStart(), g.windowEnd(), g.group(),
                g.goLiveAt(), g.contract(), g.feeBasis(), g.reconciledTier(), g.transitions(), g.revertOps(), g.intervals(),
                g.capacitySamples(), g.spotPrices(), g.odPrices(), o, g.commitmentsStatus(), g.commitments());
    }
}
