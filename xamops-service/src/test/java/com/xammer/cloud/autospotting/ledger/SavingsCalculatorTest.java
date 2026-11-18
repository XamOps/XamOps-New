package com.xammer.cloud.autospotting.ledger;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.*;
import com.xammer.cloud.service.autospotting.ledger.calc.EntryCalc;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.ledger.calc.StatementLineMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.xammer.cloud.autospotting.ledger.LedgerTestInputs.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The worked examples of design-ledger.md §3.7 and §3.8 to the cent, and the rules around them, with
 * numbers worked out by hand (rate x seconds / 3600, one division, scale 10, HALF_UP). The calculator
 * under test is {@code aws-1}.
 */
class SavingsCalculatorTest {
    private final SavingsCalculator calculator = new SavingsCalculator();

    private EntryCalc one(LedgerTestInputs b) {
        Optional<EntryCalc> e = calculator.calculate(b.build());
        assertThat(e).isPresent();
        return e.get();
    }

    private static void money(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(actual.scale()).as("entry amounts are scale 10").isEqualTo(10);
    }

    // ---------------------------------------------------------------- §3.7 example A

    @Test
    void exampleA_steadyMonthWithoutCommitment_is291_60() {
        // 3 Spot units x 720 h x 0.192 = 414.72 list; Spot 0.057 -> 123.12; gross 291.60 (design says "without the EDP and the deduction").
        List<EntryCalc> month = new ArrayList<>();
        for (int d = 1; d <= 30; d++) {
            month.add(one(septemberDay(d)));
        }
        BigDecimal cf = month.stream().map(EntryCalc::counterfactualCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal spot = month.stream().map(EntryCalc::spotCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(cf).isEqualByComparingTo("414.7200000000");
        assertThat(spot).isEqualByComparingTo("123.1200000000");
        StatementLineMath.Line line = StatementLineMath.build(month, true);
        assertThat(line.rawNet()).isEqualByComparingTo("291.6000000000");
        assertThat(line.billableAmount()).isEqualByComparingTo("291.60");
    }

    @Test
    void exampleA_discountedCommitment_lineIs244_48() {
        List<EntryCalc> month = new ArrayList<>();
        for (int d = 1; d <= 30; d++) {
            LedgerTestInputs b = septemberDay(d);
            b.odPrices = new ArrayList<>(List.of(od("m5.xlarge", "0.1920000000", "0.1824000000",
                    DiscountBasis.NET_DERIVED, "Linux")));
            b.commitmentsStatus = CommitmentsStatus.KNOWN;
            // $20.00 idle over the month, split over 30 days that add up to exactly 20 (the last day takes the remainder).
            String unused = d < 30 ? "0.6666666667" : "0.6666666657";
            b.commitments = new ArrayList<>(List.of(new Commitment("sp-compute-1", CommitmentKind.SAVINGS_PLAN,
                    CommitmentData.AVAILABLE, bd(unused), bd("0"), false, bd("0"),
                    Map.of("m5.xlarge", bd("0.1382400000")))));
            month.add(one(b));
        }
        BigDecimal cfList = sum(month, EntryCalc::counterfactualListCost);
        BigDecimal cf = sum(month, EntryCalc::counterfactualCost);
        BigDecimal adj = sum(month, EntryCalc::effectiveRateAdjustment);
        BigDecimal spot = sum(month, EntryCalc::spotCost);
        BigDecimal gross = sum(month, EntryCalc::grossSavings);
        BigDecimal ded = sum(month, EntryCalc::commitmentDeduction);
        BigDecimal net = sum(month, EntryCalc::netSavings);
        assertThat(cfList).isEqualByComparingTo("414.7200000000");
        assertThat(cf).isEqualByComparingTo("393.9840000000");
        assertThat(adj).isEqualByComparingTo("20.7360000000");
        assertThat(spot).isEqualByComparingTo("123.1200000000");
        assertThat(gross).isEqualByComparingTo("270.8640000000");
        // The month example gives DED = 26.3888888889. Thirty daily rounds to scale 10 stay within 3e-9 of it.
        assertThat(ded.subtract(new BigDecimal("26.3888888889")).abs()).isLessThan(new BigDecimal("0.000000005"));
        assertThat(net.subtract(new BigDecimal("244.4751111111")).abs()).isLessThan(new BigDecimal("0.000000005"));
        // The deduction is $26.39 and not $20.00 (it is the coverable usage value, 5.4), and the line is 244.48.
        assertThat(ded.setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("26.39");
        StatementLineMath.Line line = StatementLineMath.build(month, true);
        assertThat(line.billableAmount()).isEqualByComparingTo("244.48");
        assertThat(line.billable()).isTrue();
        assertThat(month.get(0).exclusions()).extracting(EntryCalc.Exclusion::reason)
                .contains("COMMITMENT_COVERABLE");
    }

    /** A September day of a group active since mid-August (so the whole month is measured). */
    private static LedgerTestInputs septemberDay(int d) {
        LedgerTestInputs b = LedgerTestInputs.steady(String.format("2026-09-%02d", d));
        b.goLive = at("2026-08-01T00:00:00Z");
        b.transitions = new ArrayList<>(List.of(transition(1, GroupState.PLANNED, GroupState.ACTIVE, "2026-08-15T00:00:00Z")));
        b.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-08-15T00:00:00Z"), bd("4"))));
        b.intervals = new ArrayList<>(List.of(od("od-floor-1", "m5.xlarge", "2026-08-15T00:00:00Z", null),
                spotInstance("spot-1", "m5.xlarge", "2026-08-20T00:00:00Z", null, EndCause.TERMINATED),
                spotInstance("spot-2", "m5.xlarge", "2026-08-20T00:00:00Z", null, EndCause.TERMINATED),
                spotInstance("spot-3", "m5.xlarge", "2026-08-20T00:00:00Z", null, EndCause.TERMINATED)));
        return b;
    }

    private static BigDecimal sum(List<EntryCalc> list, java.util.function.Function<EntryCalc, BigDecimal> f) {
        return list.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void commitmentAllocation_lastGroupTakesTheRemainder_sharesAddUpToThePoolExactly() {
        // Day analogue of the §5.4 example: two groups share $0.6666666667 idle. A: 3 units, B: 1 unit.
        // spCost_A = 0.13824 x 72 = 9.95328, spCost_B = 0.13824 x 24 = 3.31776; pool = 13.27104.
        LedgerTestInputs a = commitmentDay(3, "0.6666666667", "3.3177600000", false, "0");
        EntryCalc ea = one(a);
        // K_A = 0.6666666667 x 9.95328 / 13.27104 = 0.5000000000 (0.50000000002 rounded); DED_A = 13.1328 x K_A / 9.95328.
        money(ea.commitmentDeduction(), "0.6597222222");
        LedgerTestInputs b = commitmentDay(1, "0.6666666667", "9.9532800000", true, "0.5000000000");
        EntryCalc eb = one(b);
        // K_B = 0.6666666667 - 0.5 = 0.1666666667; DED_B = CF_B x K_B / spCost_B with CF_B = 0.1824 x 24 = 4.3776.
        money(eb.commitmentDeduction(), "0.2199074075");
    }

    private LedgerTestInputs commitmentDay(int spots, String unused, String other, boolean last, String allocated) {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-09-10");
        b.intervals = new ArrayList<>();
        b.intervals.add(od("od-floor-1", "m5.xlarge", "2026-09-01T00:00:00Z", null));
        for (int k = 1; k <= spots; k++) {
            b.intervals.add(spotInstance("s" + k, "m5.xlarge", "2026-09-02T00:00:00Z", null, EndCause.TERMINATED));
        }
        b.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-01T00:00:00Z"), bd(String.valueOf(spots + 1)))));
        b.transitions = new ArrayList<>(List.of(transition(1, GroupState.PLANNED, GroupState.ACTIVE, "2026-09-01T00:00:00Z")));
        b.odPrices = new ArrayList<>(List.of(od("m5.xlarge", "0.1920000000", "0.1824000000", DiscountBasis.NET_DERIVED,
                "Linux")));
        b.commitmentsStatus = CommitmentsStatus.KNOWN;
        b.commitments = new ArrayList<>(List.of(new Commitment("sp-1", CommitmentKind.SAVINGS_PLAN,
                CommitmentData.AVAILABLE, bd(unused), bd(other), last, bd(allocated), Map.of("m5.xlarge", bd("0.1382400000")))));
        return b;
    }

    // ---------------------------------------------------------------- §3.8 example B

    @Test
    void exampleB1_90MinuteLinuxInstance_noHourRounding() {
        EntryCalc e = one(single("10:00:00", "11:30:00", "2"));
        money(e.counterfactualCost(), "0.2880000000");
        money(e.spotCost(), "0.0855000000");
        money(e.grossSavings(), "0.2025000000");
    }

    /** A floor and one Spot instance that ran from start to end, desired as given. */
    private static LedgerTestInputs single(String start, String end, String desired) {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.intervals = new ArrayList<>(List.of(od("od-floor-1", "m5.xlarge", "2026-09-15T00:00:00Z", null),
                spotInstance("spot-1", "m5.xlarge", "2026-10-01T" + start + "Z", "2026-10-01T" + end + "Z",
                        EndCause.TERMINATED)));
        b.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-15T00:00:00Z"), bd(desired))));
        return b;
    }

    @Test
    void exampleB2_rebalanceOverlap_costsExactlyTheOverlapAndEarnsNoCounterfactual() {
        EntryCalc steady = one(LedgerTestInputs.steady("2026-10-01"));
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.intervals.add(spotInstance("spot-4", "m5.xlarge", "2026-10-01T12:00:00Z", "2026-10-01T12:10:00Z", EndCause.TERMINATED));
        EntryCalc e = one(b);
        money(e.counterfactualCost(), steady.counterfactualCost().toPlainString());
        money(e.spotCost().subtract(steady.spotCost()), "0.0095000000");
        money(e.grossSavings(), "9.7105000000");
        assertThat(e.exclusions()).anySatisfy(x -> {
            assertThat(x.reason()).isEqualTo("OVER_DESIRED");
            assertThat(x.unitSeconds()).isEqualByComparingTo("600");
        });
    }

    @Test
    void exampleB3_lowerOf_ranTypeCheaperThanPreEnableType() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.intervals = new ArrayList<>(List.of(od("od-floor-1", "m5.xlarge", "2026-09-15T00:00:00Z", null),
                spotInstance("spot-1", "c5.xlarge", "2026-10-01T10:00:00Z", "2026-10-01T20:00:00Z", EndCause.TERMINATED)));
        b.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-15T00:00:00Z"), bd("2"))));
        b.spotPrices = new ArrayList<>(List.of(spot("c5.xlarge", "0.0500000000", "Linux")));
        b.odPrices = new ArrayList<>(List.of(od("m5.xlarge", "0.1920000000", "0.1920000000", DiscountBasis.PROVEN_NONE, "Linux"),
                new OdPrice("60000000-0000-4000-8000-000000000002", "c5.xlarge", "Linux", bd("0.1700000000"),
                        bd("0.1700000000"), DiscountBasis.PROVEN_NONE)));
        EntryCalc e = one(b);
        money(e.counterfactualCost(), "1.7000000000");
    }

    @Test
    void exampleB4_negativeRawLine_keepsTheRawNegativeAndBillsZero() {
        // 10 h, one Spot instance at a spiked 0.312/h: CF 1.92, SPOT 3.12, raw net -1.2 (PO-26).
        LedgerTestInputs b = single("10:00:00", "20:00:00", "2");
        b.spotPrices = new ArrayList<>(List.of(spot("m5.xlarge", "0.3120000000", "Linux")));
        EntryCalc e = one(b);
        money(e.counterfactualCost(), "1.9200000000");
        money(e.spotCost(), "3.1200000000");
        money(e.netSavings(), "-1.2000000000");
        assertThat(e.billable()).isTrue();
        StatementLineMath.Line line = StatementLineMath.build(List.of(e), true);
        assertThat(line.rawNet()).isEqualByComparingTo("-1.2");
        assertThat(line.billableAmount()).isEqualByComparingTo("0.00");
        assertThat(line.billableAmount().scale()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- units and counterfactual capacity

    @Test
    void steadyDay_cfIs13_824_spotIs4_104() {
        EntryCalc e = one(LedgerTestInputs.steady("2026-10-01"));
        money(e.counterfactualCost(), "13.8240000000");
        money(e.spotCost(), "4.1040000000");
        money(e.grossSavings(), "9.7200000000");
        money(e.netSavings(), "9.7200000000");
        assertThat(e.billable()).isTrue();
        assertThat(e.instanceSeconds()).isEqualTo(259_200L);
        assertThat(e.statementMonth().toString()).isEqualTo("2026-10-01");
    }

    @Test
    void floorAndOriginalShare_areRemovedFromTheCounterfactual() {
        // Floor: two On-Demand units, desired 6, four Spot -> cf 4.
        LedgerTestInputs f = LedgerTestInputs.steady("2026-10-01");
        f.intervals.add(od("od-floor-2", "m5.xlarge", "2026-09-15T00:00:00Z", null));
        f.intervals.add(spotInstance("spot-4", "m5.xlarge", "2026-09-20T00:00:00Z", null, EndCause.TERMINATED));
        f.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-15T00:00:00Z"), bd("6"))));
        money(one(f).counterfactualCost(), "18.4320000000");
        // Original share: base 1, 75% On-Demand above base, desired 5 -> P = floor(4 x 0.25) = 1; four Spot, cf = 3.
        LedgerTestInputs p = LedgerTestInputs.steady("2026-10-01");
        p.intervals.add(spotInstance("spot-4", "m5.xlarge", "2026-09-20T00:00:00Z", null, EndCause.TERMINATED));
        p.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-15T00:00:00Z"), bd("5"))));
        p.original = new Original(bd("1"), bd("75"), List.of(pre("m5.xlarge", "Linux", "1", "8")));
        EntryCalc e = one(p);
        money(e.counterfactualCost(), "13.8240000000");
        money(e.spotCost(), "5.4720000000");
        assertThat(e.exclusions()).anySatisfy(x -> assertThat(x.reason()).isEqualTo("ORIGINAL_SPOT_SHARE"));
    }

    @Test
    void weightedCapacity_usesUnitRates() {
        // m5.2xlarge (w 2, list 0.384) and m5.4xlarge (w 4, list 0.768), pre-enable m5.xlarge 0.192: r = 0.192 per unit.
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.intervals = new ArrayList<>(List.of(
                weighted(spotInstance("w2", "m5.2xlarge", "2026-09-20T00:00:00Z", null, EndCause.TERMINATED), "2"),
                weighted(spotInstance("w4", "m5.4xlarge", "2026-09-20T00:00:00Z", null, EndCause.TERMINATED), "4")));
        b.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-15T00:00:00Z"), bd("6"))));
        b.spotPrices = new ArrayList<>(List.of(spot("m5.2xlarge", "0.1140000000", "Linux"), spot("m5.4xlarge", "0.2280000000", "Linux")));
        b.odPrices = new ArrayList<>(List.of(od("m5.xlarge", "0.1920000000", "0.1920000000", DiscountBasis.PROVEN_NONE, "Linux"),
                od("m5.2xlarge", "0.3840000000", "0.3840000000", DiscountBasis.PROVEN_NONE, "Linux"),
                od("m5.4xlarge", "0.7680000000", "0.7680000000", DiscountBasis.PROVEN_NONE, "Linux")));
        EntryCalc e = one(b);
        money(e.counterfactualCost(), "27.6480000000");
        money(e.spotCost(), "8.2080000000");
    }

    // ---------------------------------------------------------------- provider billing units

    @Test
    void awsBilling_45SecondInstance_spotAt60s_counterfactualAt45s() {
        EntryCalc e = one(single("10:00:00", "10:00:45", "2"));
        money(e.counterfactualCost(), "0.0024000000");
        money(e.spotCost(), "0.0009500000");
    }

    @Test
    void awsBilling_suseIsBilledInFullHours_counterfactualUsesActualSeconds() {
        LedgerTestInputs b = single("10:00:00", "11:30:00", "2");
        b.intervals = new ArrayList<>(List.of(platform(b.intervals.get(0), "SUSE"), platform(b.intervals.get(1), "SUSE")));
        b.spotPrices = new ArrayList<>(List.of(spot("m5.xlarge", "0.0570000000", "SUSE")));
        b.odPrices = new ArrayList<>(List.of(od("m5.xlarge", "0.1920000000", "0.1920000000", DiscountBasis.PROVEN_NONE, "SUSE")));
        b.original = new Original(bd("0"), bd("100"), List.of(pre("m5.xlarge", "SUSE", "1", "8")));
        EntryCalc e = one(b);
        money(e.counterfactualCost(), "0.2880000000");
        money(e.spotCost(), "0.1140000000");
    }

    @Test
    void awsBilling_interruptedByEc2InTheFirstHour_isNotCharged() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-15T00:00:00Z"), bd("5"))));
        b.intervals.add(spotInstance("spot-4", "m5.xlarge", "2026-10-01T10:00:00Z", "2026-10-01T10:30:00Z", EndCause.PROVIDER_INTERRUPTED));
        EntryCalc e = one(b);
        EntryCalc base = one(withDesired5());
        money(e.counterfactualCost(), base.counterfactualCost().toPlainString());
        money(e.spotCost(), base.spotCost().toPlainString());
        assertThat(e.exclusions()).anySatisfy(x -> {
            assertThat(x.reason()).isEqualTo("PROVIDER_NOT_CHARGED");
            assertThat(x.unitSeconds()).isEqualByComparingTo("1800");
        });
    }

    private static LedgerTestInputs withDesired5() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-15T00:00:00Z"), bd("5"))));
        return b;
    }

    @Test
    void awsBilling_endedInTheFirstHourCauseUnknown_zeroCounterfactual_spotAtTheUpperBound() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-15T00:00:00Z"), bd("5"))));
        b.intervals.add(spotInstance("spot-4", "m5.xlarge", "2026-10-01T10:00:00Z", "2026-10-01T10:40:00Z", EndCause.UNKNOWN));
        EntryCalc e = one(b);
        EntryCalc base = one(withDesired5());
        money(e.counterfactualCost(), base.counterfactualCost().toPlainString());
        // 2400 s x 0.057 / 3600 = 0.038
        money(e.spotCost().subtract(base.spotCost()), "0.0380000000");
        assertThat(e.exclusions()).anySatisfy(x -> assertThat(x.reason()).isEqualTo("INTERRUPTION_CAUSE_UNKNOWN"));
    }

    @Test
    void unknownIntervalEnd_counterfactualToLastSeen_spotToFirstAbsent() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-15T00:00:00Z"), bd("5"))));
        b.intervals.add(unknownEnd(spotInstance("spot-4", "m5.xlarge", "2026-10-01T09:00:00Z", null, null),
                "2026-10-01T10:00:00Z", "2026-10-01T10:05:00Z"));
        EntryCalc e = one(b);
        // CF: 3 x 24 h + 1 h = 73 unit-hours x 0.192 = 14.016. Spot: (259200 + 3900 s) x 0.057 / 3600 = 4.16575.
        money(e.counterfactualCost(), "14.0160000000");
        money(e.spotCost(), "4.1657500000");
        assertThat(e.exclusions()).anySatisfy(x -> {
            assertThat(x.reason()).isEqualTo("UNKNOWN_INTERVAL_END");
            assertThat(x.unitSeconds()).isEqualByComparingTo("300");
        });
    }

    // ---------------------------------------------------------------- states, windows, go-live

    @Test
    void pausedWindow_neverBilled_whateverTheOrderTheTransitionsAreListed() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.transitions.add(transition(2, GroupState.ACTIVE, GroupState.PAUSED, "2026-10-01T09:00:00Z"));
        EntryCalc forward = one(b);
        Collections.reverse(b.transitions);
        EntryCalc reversed = one(b);
        assertThat(reversed).isEqualTo(forward);
        // 9 h x 3 units x 0.192 = 5.184
        money(forward.counterfactualCost(), "5.1840000000");
        money(forward.spotCost(), "1.5390000000");
    }

    @Test
    void wholeDayPaused_isZeroMoney_withTheStateReason_evenWithNoContract() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.transitions.add(transition(2, GroupState.ACTIVE, GroupState.PAUSED, "2026-09-30T00:00:00Z"));
        b.contract = null;
        EntryCalc e = one(b);
        assertThat(e.billable()).isFalse();
        assertThat(e.nonBillableReason()).isEqualTo("STATE_PAUSED");
        money(e.counterfactualCost(), "0");
        money(e.netSavings(), "0");
        money(e.netSavingsLowerBound(), "0");
    }

    @Test
    void goLive_inTheMiddleOfTheDay_excludesTheEarlierPart() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.goLive = at("2026-10-01T12:00:00Z");
        EntryCalc e = one(b);
        money(e.counterfactualCost(), "6.9120000000");
        assertThat(e.exclusions()).anySatisfy(x -> assertThat(x.reason()).isEqualTo("BEFORE_GO_LIVE"));
        b.goLive = null;
        EntryCalc notLive = one(b);
        assertThat(notLive.billable()).isFalse();
        assertThat(notLive.nonBillableReason()).isEqualTo("BEFORE_GO_LIVE");
    }

    @Test
    void revertWindow_endsAtTheOpsFinishWhenItNeverMutated_andOtherwiseAtThePriorStateTransition() {
        LedgerTestInputs failed = LedgerTestInputs.steady("2026-10-01");
        failed.revertOps.add(new RevertOp("op1", at("2026-10-01T10:00:00Z"), at("2026-10-01T10:30:00Z"), null));
        // 23.5 h measured: 3 x 23.5 x 0.192 = 13.536
        money(one(failed).counterfactualCost(), "13.5360000000");
        LedgerTestInputs open = LedgerTestInputs.steady("2026-10-01");
        open.revertOps.add(new RevertOp("op2", at("2026-10-01T10:00:00Z"), null, 7L));
        // measured only to 10:00: 3 x 10 x 0.192 = 5.76
        money(one(open).counterfactualCost(), "5.7600000000");
    }

    @Test
    void lifecycle_dayWithNoManagedState_hasNoEntry_andAnEntirelyRevertedDayNeither() {
        LedgerTestInputs none = LedgerTestInputs.steady("2026-10-01");
        none.transitions = new ArrayList<>();
        assertThat(calculator.calculate(none.build())).isEmpty();
        LedgerTestInputs reverted = LedgerTestInputs.steady("2026-10-01");
        reverted.transitions.add(transition(2, GroupState.ACTIVE, GroupState.REVERTED, "2026-09-30T00:00:00Z"));
        assertThat(calculator.calculate(reverted.build())).isEmpty();
        LedgerTestInputs gone = LedgerTestInputs.steady("2026-10-01");
        gone.transitions.add(transition(2, GroupState.ACTIVE, GroupState.GONE, "2026-09-30T00:00:00Z"));
        assertThat(calculator.calculate(gone.build())).isEmpty();
    }

    @Test
    void legacyUnconfirmed_isAZeroMoneyEntry_neverBillable() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.transitions = new ArrayList<>(List.of(transition(1, GroupState.UNMANAGED, GroupState.LEGACY_UNCONFIRMED, "2026-09-10T00:00:00Z")));
        EntryCalc e = one(b);
        assertThat(e.billable()).isFalse();
        assertThat(e.nonBillableReason()).isEqualTo("LEGACY_UNCONFIRMED");
        assertThat(e.tier()).isEqualTo(Tier.MEASURED_ONLY);
        money(e.counterfactualCost(), "0");
        money(e.spotCost(), "0");
        assertThat(e.exclusions()).anySatisfy(x -> {
            assertThat(x.reason()).isEqualTo("LEGACY_UNCONFIRMED");
            assertThat(x.unitSeconds()).isEqualByComparingTo("259200");
        });
    }

    @Test
    void transitionsOutOfOrder_makeTheDayUnknownWithMissingStateOrder() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.transitions.add(transition(2, GroupState.ACTIVE, GroupState.PAUSED, "2026-10-01T12:00:00Z"));
        b.transitions.add(transition(3, GroupState.PAUSED, GroupState.ACTIVE, "2026-10-01T06:00:00Z"));
        EntryCalc e = one(b);
        assertThat(e.tier()).isEqualTo(Tier.UNKNOWN);
        assertThat(e.unknownInputs()).containsExactly("MISSING_STATE_ORDER");
        assertThat(e.billable()).isFalse();
        money(e.counterfactualCost(), "0");
    }

    // ---------------------------------------------------------------- unknowns, tiers and reasons

    @Test
    void missingSpotPrice_isUnknown_zeroMoney_withTheOdListWorstCase() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.spotPrices = new ArrayList<>();
        EntryCalc e = one(b);
        assertThat(e.tier()).isEqualTo(Tier.UNKNOWN);
        assertThat(e.unknownInputs()).containsExactly("MISSING_SPOT_PRICE");
        assertThat(e.billable()).isFalse();
        money(e.spotCost(), "0");
        // Worst case: Spot at the On-Demand list rate, so CF - SPOT = 0.
        money(e.netSavingsLowerBound(), "0");
    }

    @Test
    void missingOdPrice_missingDesired_missingSnapshot_missingWeight_areUnknown() {
        LedgerTestInputs od = LedgerTestInputs.steady("2026-10-01");
        od.odPrices = new ArrayList<>();
        assertThat(one(od).unknownInputs()).containsExactly("MISSING_OD_PRICE");
        assertThat(one(od).netSavingsLowerBound()).isNull();
        LedgerTestInputs desired = LedgerTestInputs.steady("2026-10-01");
        desired.samples = new ArrayList<>();
        assertThat(one(desired).unknownInputs()).containsExactly("MISSING_DESIRED");
        LedgerTestInputs snap = LedgerTestInputs.steady("2026-10-01");
        snap.original = null;
        assertThat(one(snap).unknownInputs()).containsExactly("MISSING_SNAPSHOT");
        LedgerTestInputs weight = LedgerTestInputs.steady("2026-10-01");
        weight.intervals.set(1, weighted(weight.intervals.get(1), "0"));
        assertThat(one(weight).unknownInputs()).contains("MISSING_WEIGHT");
    }

    @Test
    void anUnknownPriceInAnExcludedWindow_doesNotMakeTheEntryUnknown() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.transitions.add(transition(2, GroupState.ACTIVE, GroupState.PAUSED, "2026-10-01T00:00:00Z"));
        b.spotPrices = new ArrayList<>();
        b.odPrices = new ArrayList<>();
        EntryCalc e = one(b);
        assertThat(e.tier()).isNotEqualTo(Tier.UNKNOWN);
        assertThat(e.nonBillableReason()).isEqualTo("STATE_PAUSED");
        assertThat(e.exclusions()).anySatisfy(x -> {
            assertThat(x.reason()).isEqualTo("STATE_PAUSED");
            assertThat(x.counterfactualCost()).isNull();
        });
    }

    @Test
    void discountNotDeclared_capsTheTier_boundIsMinusSpot() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.odPrices = new ArrayList<>(List.of(od("m5.xlarge", "0.1920000000", "0.1920000000", DiscountBasis.NONE, "Linux")));
        EntryCalc e = one(b);
        assertThat(e.tier()).isEqualTo(Tier.MEASURED_ONLY);
        assertThat(e.billable()).isFalse();
        assertThat(e.nonBillableReason()).isEqualTo("DISCOUNT_NOT_DECLARED");
        money(e.netSavings(), "9.72");
        money(e.netSavingsLowerBound(), "-4.104");
    }

    @Test
    void commitmentDataUnavailable_andRateUnknown_capTheTier_neverGuessAConversion() {
        LedgerTestInputs unavailable = LedgerTestInputs.steady("2026-10-01");
        unavailable.commitmentsStatus = CommitmentsStatus.UNAVAILABLE;
        EntryCalc u = one(unavailable);
        assertThat(u.tier()).isEqualTo(Tier.MEASURED_ONLY);
        assertThat(u.nonBillableReason()).isEqualTo("COMMITMENT_DATA_UNAVAILABLE");
        LedgerTestInputs rate = LedgerTestInputs.steady("2026-10-01");
        rate.commitmentsStatus = CommitmentsStatus.KNOWN;
        rate.commitments = new ArrayList<>(List.of(new Commitment("sp", CommitmentKind.SAVINGS_PLAN, CommitmentData.AVAILABLE,
                bd("5"), bd("0"), false, bd("0"), Map.of())));
        EntryCalc r = one(rate);
        assertThat(r.tier()).isEqualTo(Tier.MEASURED_ONLY);
        assertThat(r.nonBillableReason()).isEqualTo("COMMITMENT_RATE_UNKNOWN");
        money(r.commitmentDeduction(), "0");
        money(r.netSavingsLowerBound(), "-4.104");
    }

    @Test
    void riDeduction_isPricedAtTheCounterfactualRate_matchedOnThePreEnableType() {
        // Ran c5.xlarge (0.17), pre-enable m5.xlarge (norm 8): cf 24 unit-hours -> cfNU 192; idle 100 NU -> share 100.
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.intervals = new ArrayList<>(List.of(od("od-floor-1", "m5.xlarge", "2026-09-15T00:00:00Z", null),
                spotInstance("spot-1", "c5.xlarge", "2026-09-20T00:00:00Z", null, EndCause.TERMINATED)));
        b.samples = new ArrayList<>(List.of(new CapacitySample(at("2026-09-15T00:00:00Z"), bd("2"))));
        b.spotPrices = new ArrayList<>(List.of(spot("c5.xlarge", "0.0500000000", "Linux")));
        b.odPrices = new ArrayList<>(List.of(od("m5.xlarge", "0.1920000000", "0.1920000000", DiscountBasis.PROVEN_NONE, "Linux"),
                new OdPrice("60000000-0000-4000-8000-000000000002", "c5.xlarge", "Linux", bd("0.1700000000"),
                        bd("0.1700000000"), DiscountBasis.PROVEN_NONE)));
        b.commitmentsStatus = CommitmentsStatus.KNOWN;
        b.commitments = new ArrayList<>(List.of(new Commitment("ri", CommitmentKind.RESERVED_INSTANCE, CommitmentData.AVAILABLE,
                bd("100"), bd("0"), false, bd("0"), Map.of())));
        EntryCalc e = one(b);
        money(e.counterfactualCost(), "4.0800000000");
        money(e.commitmentDeduction(), "2.1250000000"); // 4.08 x 100 / 192
        money(e.netSavings(), "0.7550000000");          // 4.08 - 1.2 - 2.125
    }

    @Test
    void deductionNeverExceedsTheCounterfactual_evenWithTwoCommitmentsOnTheSameUsage() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.commitmentsStatus = CommitmentsStatus.KNOWN;
        b.commitments = new ArrayList<>(List.of(
                new Commitment("ri-a", CommitmentKind.RESERVED_INSTANCE, CommitmentData.AVAILABLE, bd("1000"), bd("0"), false, bd("0"), Map.of()),
                new Commitment("ri-b", CommitmentKind.RESERVED_INSTANCE, CommitmentData.AVAILABLE, bd("1000"), bd("0"), false, bd("0"), Map.of())));
        EntryCalc e = one(b);
        money(e.commitmentDeduction(), "13.8240000000");
        money(e.netSavings(), "-4.1040000000");
    }

    @Test
    void commercialReasons_keepTheirMoney_neverBillable_boundEqualsNet_withTheDesignPrecedence() {
        LedgerTestInputs none = LedgerTestInputs.steady("2026-10-01");
        none.contract = null;
        none.feeBasis = FeeBasis.NO_FEE_ACCOUNT;
        EntryCalc n = one(none);
        assertThat(n.nonBillableReason()).isEqualTo("NO_CONTRACT");
        LedgerTestInputs before = LedgerTestInputs.steady("2026-10-01");
        before.feeBasis = FeeBasis.BEFORE_CONTRACT;
        EntryCalc bc = one(before);
        assertThat(bc.nonBillableReason()).isEqualTo("BEFORE_CONTRACT");
        LedgerTestInputs nofee = LedgerTestInputs.steady("2026-10-01");
        nofee.feeBasis = FeeBasis.NO_FEE_ACCOUNT;
        EntryCalc nf = one(nofee);
        assertThat(nf.nonBillableReason()).isEqualTo("NO_FEE_ACCOUNT");
        for (EntryCalc e : List.of(n, bc, nf)) {
            assertThat(e.billable()).isFalse();
            money(e.netSavings(), "9.72");
            money(e.netSavingsLowerBound(), "9.72");
            assertThat(StatementLineMath.build(List.of(e), false).billableAmount()).isEqualByComparingTo("0.00");
        }
    }

    @Test
    void notReconciled_isMeasuredOnly_andNeverBillable() {
        LedgerTestInputs b = LedgerTestInputs.steady("2026-10-01");
        b.tier = Tier.MEASURED_ONLY;
        EntryCalc e = one(b);
        assertThat(e.billable()).isFalse();
        assertThat(e.nonBillableReason()).isEqualTo("NOT_RECONCILED");
        money(e.netSavings(), "9.72");
    }

    // ---------------------------------------------------------------- input discipline

    @Test
    void inputs_mustBeOneUtcDayInUsd_andTheKnownVersion() {
        CalcInputs ok = LedgerTestInputs.steady("2026-10-01").build();
        assertThatThrownBy(() -> calculator.calculate(new CalcInputs("aws-2", ok.provider(), ok.currency(), ok.windowStart(),
                ok.windowEnd(), ok.group(), ok.goLiveAt(), ok.contract(), ok.feeBasis(), ok.reconciledTier(), ok.transitions(),
                ok.revertOps(), ok.intervals(), ok.capacitySamples(), ok.spotPrices(), ok.odPrices(), ok.original(),
                ok.commitmentsStatus(), ok.commitments()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(new CalcInputs("aws-1", "AWS", "INR", ok.windowStart(),
                ok.windowEnd(), ok.group(), ok.goLiveAt(), ok.contract(), ok.feeBasis(), ok.reconciledTier(), ok.transitions(),
                ok.revertOps(), ok.intervals(), ok.capacitySamples(), ok.spotPrices(), ok.odPrices(), ok.original(),
                ok.commitmentsStatus(), ok.commitments()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(new CalcInputs("aws-1", "AWS", "USD", ok.windowStart().plusSeconds(1),
                ok.windowEnd().plusSeconds(1), ok.group(), ok.goLiveAt(), ok.contract(), ok.feeBasis(), ok.reconciledTier(),
                ok.transitions(), ok.revertOps(), ok.intervals(), ok.capacitySamples(), ok.spotPrices(), ok.odPrices(),
                ok.original(), ok.commitmentsStatus(), ok.commitments()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ckSleMath_holdsOnEveryWorkedEntry() {
        List<EntryCalc> entries = new ArrayList<>();
        entries.add(one(LedgerTestInputs.steady("2026-10-01")));
        entries.add(one(single("10:00:00", "11:30:00", "2")));
        LedgerTestInputs neg = single("10:00:00", "20:00:00", "2");
        neg.spotPrices = new ArrayList<>(List.of(spot("m5.xlarge", "0.3120000000", "Linux")));
        entries.add(one(neg));
        for (EntryCalc e : entries) {
            assertThat(e.grossSavings()).isEqualByComparingTo(e.counterfactualCost().subtract(e.spotCost()));
            assertThat(e.netSavings()).isEqualByComparingTo(e.grossSavings().subtract(e.commitmentDeduction()));
            assertThat(e.counterfactualCost()).isEqualByComparingTo(e.counterfactualListCost().subtract(e.effectiveRateAdjustment()));
            assertThat(e.commitmentDeduction().signum()).isGreaterThanOrEqualTo(0);
            assertThat(e.spotCost().signum()).isGreaterThanOrEqualTo(0);
        }
    }
}
