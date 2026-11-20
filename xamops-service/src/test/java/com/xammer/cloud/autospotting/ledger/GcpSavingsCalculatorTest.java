package com.xammer.cloud.autospotting.ledger;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.CommitmentData;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.CommitmentsStatus;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Gcp;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Shape;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Tier;
import com.xammer.cloud.service.autospotting.ledger.calc.CanonicalJson;
import com.xammer.cloud.service.autospotting.ledger.calc.EntryCalc;
import com.xammer.cloud.service.autospotting.ledger.calc.Reasons;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.ledger.calc.StatementLineMath;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GCP branch, calc version {@code gcp-1} (design-ledger.md §3.5, §3.9, §5.3, §5.4, PO-08, PD-06, PD-09), on the
 * golden fixtures: worked example C to the cent, the four SUD cases, the first-minute rule, a partly used CUD,
 * shared-core pricing, and the cases that must be UNKNOWN or MEASURED_ONLY. Numbers are worked by hand from the
 * Catalog prices in the design (no floating point anywhere).
 */
class GcpSavingsCalculatorTest {
    private final SavingsCalculator calculator = new SavingsCalculator();

    private List<EntryCalc> run(String fixture) throws IOException {
        List<EntryCalc> out = new ArrayList<>();
        for (CalcInputs day : GoldenFixtures.inputs(fixture).days()) {
            calculator.calculate(day).ifPresent(out::add);
        }
        return out;
    }

    private static BigDecimal sum(List<EntryCalc> es, java.util.function.Function<EntryCalc, BigDecimal> f) {
        return es.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private StatementLineMath.Line line(String fixture) throws IOException {
        return StatementLineMath.build(run(fixture), true);
    }

    @Test
    void workedExampleC_steadyMonth_listAndSpotMatchTheDesign() throws IOException {
        List<EntryCalc> month = run("gcp-steady");
        assertThat(month).hasSize(30);
        // 3 x 720 h x (4 x 0.031611 + 16 x 0.004237) = 419.54976 ; Spot 3 x 720 h x (4 x 0.00776 + 16 x 0.00104) = 102.9888
        assertThat(sum(month, EntryCalc::counterfactualListCost)).isEqualByComparingTo("419.5497600000");
        assertThat(sum(month, EntryCalc::spotCost)).isEqualByComparingTo("102.9888000000");
        assertThat(month.stream().mapToLong(EntryCalc::instanceSeconds).sum()).isEqualTo(7_776_000L);
        assertThat(line("gcp-steady").billableAmount()).isEqualByComparingTo("316.56");
        assertThat(month).allSatisfy(e -> {
            assertThat(e.calcVersion()).isEqualTo("gcp-1");
            assertThat(e.provider()).isEqualTo("GCP");
            assertThat(e.tier()).isEqualTo(Tier.BILL_VERIFIED);
        });
    }

    @Test
    void workedExampleC_noExport_usesTheSeriesMaximumSud() throws IOException {
        List<EntryCalc> month = run("gcp-sud-max");
        assertThat(sum(month, EntryCalc::counterfactualCost)).isEqualByComparingTo("335.6398080000");
        assertThat(sum(month, EntryCalc::grossSavings)).isEqualByComparingTo("232.6510080000");
        assertThat(sum(month, EntryCalc::effectiveRateAdjustment)).isEqualByComparingTo("83.9099520000");
        assertThat(line("gcp-sud-max").billableAmount()).isEqualByComparingTo("232.65");
    }

    @Test
    void workedExampleC_exportAt13_7_fullMonthCounterfactual_marginalRateIsTheMaximum() throws IOException {
        List<EntryCalc> month = run("gcp-sud-export");
        assertThat(sum(month, EntryCalc::counterfactualCost)).isEqualByComparingTo("335.6398080000");
        assertThat(line("gcp-sud-export").billableAmount()).isEqualByComparingTo("232.65");
        // Using the observed 13.7% would have produced 259.08.
        assertThat(line("gcp-sud-export").billableAmount()).isNotEqualByComparingTo("259.08");
    }

    @Test
    void workedExampleC_shortRun_observedRateWins() throws IOException {
        List<EntryCalc> month = run("gcp-sud-observed-wins");
        assertThat(sum(month, EntryCalc::counterfactualListCost)).isEqualByComparingTo("139.8499200000");
        assertThat(sum(month, EntryCalc::spotCost)).isEqualByComparingTo("34.3296000000");
        assertThat(sum(month, EntryCalc::counterfactualCost)).isEqualByComparingTo("120.6904809600");
        assertThat(line("gcp-sud-observed-wins").billableAmount()).isEqualByComparingTo("86.36");
    }

    @Test
    void workedExampleC_complementaryUsage_marginalRateWins() throws IOException {
        List<EntryCalc> month = run("gcp-sud-complementary");
        assertThat(sum(month, EntryCalc::counterfactualListCost)).isEqualByComparingTo("209.7748800000");
        assertThat(sum(month, EntryCalc::spotCost)).isEqualByComparingTo("51.4944000000");
        // 0.3333335 on 209.77488: 139.8498850375 over the month (each day rounds once at scale 10)
        assertThat(sum(month, EntryCalc::counterfactualCost)).isBetween(new BigDecimal("139.8498850370"),
                new BigDecimal("139.8498850380"));
        assertThat(line("gcp-sud-complementary").billableAmount()).isEqualByComparingTo("88.36");
    }

    @Test
    void aSpotVmPreemptedInItsFirstMinuteIsNotBilledAndEarnsNoCounterfactual() throws IOException {
        EntryCalc e = run("gcp-preempt-first-minute").get(0);
        // two full VMs for 24 h: 2 x 24 x 0.194236 = 9.323328 list; Spot 2 x 24 x 0.04768 = 2.28864
        assertThat(e.counterfactualListCost()).isEqualByComparingTo("9.3233280000");
        assertThat(e.spotCost()).isEqualByComparingTo("2.2886400000");
        assertThat(e.exclusions()).anySatisfy(x -> {
            assertThat(x.reason()).isEqualTo(Reasons.PROVIDER_NOT_CHARGED);
            assertThat(x.counterfactualCost()).isEqualByComparingTo("0");
        });
    }

    @Test
    void aSpotVmThatEndedInItsFirstMinuteWithCauseUnknownPaysTheMinimumAndEarnsNoCounterfactual() throws IOException {
        CalcInputs day = GoldenFixtures.inputs("gcp-preempt-first-minute").days().get(0);
        CalcInputs unknown = withIntervals(day, day.intervals().stream().map(iv -> "spot-1".equals(iv.intervalId())
                ? new CalcInputs.Interval(iv.intervalId(), iv.instanceId(), iv.instanceType(), iv.zone(), iv.platform(),
                iv.purchaseOption(), iv.capacityWeight(), iv.startAt(), iv.endAt(), null, null,
                CalcInputs.EndCause.UNKNOWN) : iv).toList());
        EntryCalc e = calculator.calculate(unknown).orElseThrow();
        // 45 s lived -> billed 60 s at 0.04768 per hour = 0.000794666666... added to the two full VMs' 2.28864
        assertThat(e.spotCost()).isEqualByComparingTo("2.2894346667");
        assertThat(e.counterfactualListCost()).isEqualByComparingTo("9.3233280000");
        assertThat(e.exclusions()).anySatisfy(x -> assertThat(x.reason()).isEqualTo(Reasons.INTERRUPTION_CAUSE_UNKNOWN));
    }

    @Test
    void aPartlyUsedResourceCud_deductsTheUnusedHoursAtTheCounterfactualRate_floorZero() throws IOException {
        EntryCalc e = run("gcp-cud-partial").get(0);
        // cf: 288 vCPU-h and 1152 GiB-h; unused 144 and 576 (half). Effective rates after 20% SUD:
        // core 0.031611 x 0.8 = 0.0252888 ; ram 0.004237 x 0.8 = 0.0033896.
        // DED = 144 x 0.0252888 + 576 x 0.0033896 = 3.6415872 + 1.9524096 = 5.5939968
        assertThat(e.commitmentDeduction()).isEqualByComparingTo("5.5939968000");
        assertThat(e.counterfactualCost()).isEqualByComparingTo("11.1879936000");
        assertThat(e.netSavings()).isEqualByComparingTo("2.1610368000");
        assertThat(e.commitmentDeduction().signum()).isGreaterThanOrEqualTo(0);
        assertThat(e.commitmentDeduction()).isLessThanOrEqualTo(e.counterfactualCost());
        assertThat(e.billable()).isTrue();
    }

    @Test
    void aCudWithoutUtilizationData_isMeasuredOnlyWithTheDataReason() throws IOException {
        CalcInputs day = GoldenFixtures.inputs("gcp-cud-partial").days().get(0);
        CalcInputs unavailable = new CalcInputs(day.calcVersion(), day.provider(), day.currency(), day.windowStart(),
                day.windowEnd(), day.group(), day.goLiveAt(), day.contract(), day.feeBasis(), day.reconciledTier(),
                day.transitions(), day.revertOps(), day.intervals(), day.capacitySamples(), day.spotPrices(),
                day.odPrices(), day.original(), CommitmentsStatus.UNAVAILABLE, List.of(), day.gcp());
        EntryCalc e = calculator.calculate(unavailable).orElseThrow();
        assertThat(e.tier()).isEqualTo(Tier.MEASURED_ONLY);
        assertThat(e.billable()).isFalse();
        assertThat(e.nonBillableReason()).isEqualTo(Reasons.COMMITMENT_DATA_UNAVAILABLE);
        assertThat(e.commitmentDeduction()).isEqualByComparingTo("0");
    }

    @Test
    void aCudWhoseQuantitiesAreUnknownIsMeasuredOnlyWithTheRateReason() throws IOException {
        CalcInputs day = GoldenFixtures.inputs("gcp-cud-partial").days().get(0);
        Gcp g = day.gcp();
        Gcp unknown = new Gcp(g.billingSource(), g.sharedCoreTable(), g.declaredDiscountPct(), g.shapes(), g.prices(),
                g.sud(), g.commitments().stream().map(c -> new CalcInputs.Cud(c.scopeId(), c.series(),
                CommitmentData.RATE_UNKNOWN, null, null, c.otherVcpuHours(), c.otherGibHours(), c.lastGroup(),
                c.allocatedVcpuToOthers(), c.allocatedGibToOthers())).toList());
        EntryCalc e = calculator.calculate(withGcp(day, unknown)).orElseThrow();
        assertThat(e.tier()).isEqualTo(Tier.MEASURED_ONLY);
        assertThat(e.nonBillableReason()).isEqualTo(Reasons.COMMITMENT_RATE_UNKNOWN);
    }

    @Test
    void sharedCoreE2Small_isPricedAtHalfAVcpuAndEarnsNoSud() throws IOException {
        EntryCalc e = run("gcp-e2-small-shared-core").get(0);
        // per instance-hour: 0.5 x 0.02181159 + 2 x 0.00292353 = 0.016752855 ; 2 VMs x 24 h = 48 h -> 0.80413704
        assertThat(e.counterfactualListCost()).isEqualByComparingTo("0.8041370400");
        assertThat(e.counterfactualCost()).isEqualByComparingTo("0.8041370400");
        assertThat(e.effectiveRateAdjustment()).isEqualByComparingTo("0");
        // Spot: 0.5 x 0.00654 + 2 x 0.000877 = 0.005024 -> 0.241152
        assertThat(e.spotCost()).isEqualByComparingTo("0.2411520000");
    }

    @Test
    void aSharedCoreTypeMissingFromTheTable_isUnknown_neverPricedAtGuestCpus() throws IOException {
        CalcInputs day = GoldenFixtures.inputs("gcp-e2-small-shared-core").days().get(0);
        Gcp g = day.gcp();
        Gcp unsupported = new Gcp(g.billingSource(), g.sharedCoreTable(), g.declaredDiscountPct(),
                List.of(new Shape("e2-small", "e2", null, new BigDecimal("2"), true)), g.prices(), g.sud(),
                g.commitments());
        EntryCalc e = calculator.calculate(withGcp(day, unsupported)).orElseThrow();
        assertThat(e.tier()).isEqualTo(Tier.UNKNOWN);
        assertThat(e.billable()).isFalse();
        assertThat(e.unknownInputs()).contains(Reasons.GCP_SHARED_CORE_UNSUPPORTED);
        assertThat(e.counterfactualCost()).isEqualByComparingTo("0");
        assertThat(e.spotCost()).isEqualByComparingTo("0");
    }

    @Test
    void aSeriesWithNoSudConfigIsUnknown_notAssumedToEarnNone() throws IOException {
        CalcInputs day = GoldenFixtures.inputs("gcp-sud-max").days().get(0);
        Gcp g = day.gcp();
        Gcp none = new Gcp(g.billingSource(), g.sharedCoreTable(), g.declaredDiscountPct(), g.shapes(), g.prices(),
                List.of(), g.commitments());
        EntryCalc e = calculator.calculate(withGcp(day, none)).orElseThrow();
        assertThat(e.tier()).isEqualTo(Tier.UNKNOWN);
        assertThat(e.unknownInputs()).contains(Reasons.MISSING_SUD_RATE);
        assertThat(e.netSavings()).isEqualByComparingTo("0");
    }

    @Test
    void aMissingCatalogPriceIsUnknown() throws IOException {
        CalcInputs day = GoldenFixtures.inputs("gcp-sud-max").days().get(0);
        Gcp g = day.gcp();
        Gcp noSpot = new Gcp(g.billingSource(), g.sharedCoreTable(), g.declaredDiscountPct(), g.shapes(),
                g.prices().stream().filter(p -> "ON_DEMAND".equals(p.model())).toList(), g.sud(), g.commitments());
        EntryCalc e = calculator.calculate(withGcp(day, noSpot)).orElseThrow();
        assertThat(e.tier()).isEqualTo(Tier.UNKNOWN);
        assertThat(e.unknownInputs()).contains(Reasons.MISSING_SPOT_PRICE);
        Gcp noOd = new Gcp(g.billingSource(), g.sharedCoreTable(), g.declaredDiscountPct(), g.shapes(),
                g.prices().stream().filter(p -> "SPOT".equals(p.model())).toList(), g.sud(), g.commitments());
        EntryCalc e2 = calculator.calculate(withGcp(day, noOd)).orElseThrow();
        assertThat(e2.tier()).isEqualTo(Tier.UNKNOWN);
        assertThat(e2.unknownInputs()).contains(Reasons.MISSING_OD_PRICE);
    }

    @Test
    void aCspManagedProject_isMeasuredOnlyWithCspDatasetPending_evenWhenReconciledBillVerified() throws IOException {
        CalcInputs day = GoldenFixtures.inputs("gcp-csp-dataset-pending").days().get(0);
        assertThat(day.reconciledTier()).isEqualTo(Tier.BILL_VERIFIED);
        EntryCalc e = calculator.calculate(day).orElseThrow();
        assertThat(e.tier()).isEqualTo(Tier.MEASURED_ONLY);
        assertThat(e.billable()).isFalse();
        assertThat(e.nonBillableReason()).isEqualTo(Reasons.CSP_DATASET_PENDING);
        assertThat(e.netSavings()).isGreaterThan(BigDecimal.ZERO); // measured, kept
    }

    @Test
    void anExportBackedProjectKeepsItsReconciledTier() throws IOException {
        for (String source : List.of("OWN_EXPORT", "XAMMER_EXPORT")) {
            CalcInputs day = GoldenFixtures.inputs("gcp-sud-max").days().get(0);
            Gcp g = day.gcp();
            Gcp withSource = new Gcp(source, g.sharedCoreTable(), g.declaredDiscountPct(), g.shapes(), g.prices(),
                    g.sud(), g.commitments());
            EntryCalc e = calculator.calculate(withGcp(day, withSource)).orElseThrow();
            assertThat(e.tier()).as(source).isEqualTo(Tier.BILL_VERIFIED);
            assertThat(e.billable()).isTrue();
        }
    }

    @Test
    void anUndeclaredNegotiatedDiscountCapsAtMeasuredOnly_asOnAws() throws IOException {
        CalcInputs day = GoldenFixtures.inputs("gcp-sud-max").days().get(0);
        Gcp g = day.gcp();
        Gcp undeclared = new Gcp(g.billingSource(), g.sharedCoreTable(), null, g.shapes(), g.prices(), g.sud(),
                g.commitments());
        EntryCalc e = calculator.calculate(withGcp(day, undeclared)).orElseThrow();
        assertThat(e.tier()).isEqualTo(Tier.MEASURED_ONLY);
        assertThat(e.nonBillableReason()).isEqualTo(Reasons.DISCOUNT_NOT_DECLARED);
    }

    @Test
    void duplicateProject_oneEntryPerGroupAndDay() throws IOException {
        List<EntryCalc> entries = run("duplicate-project");
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(e -> e.groupId() + "|" + e.periodStart()).distinct()).hasSize(2);
    }

    @Test
    void anEntryRecomputesFromItsStoredInputsExactly() throws IOException {
        for (String fixture : List.of("gcp-sud-complementary", "gcp-cud-partial", "gcp-e2-small-shared-core")) {
            for (CalcInputs day : GoldenFixtures.inputs(fixture).days()) {
                String stored = CanonicalJson.compact(day);
                CalcInputs again = CanonicalJson.read(stored, CalcInputs.class);
                assertThat(CanonicalJson.compact(again)).as(fixture).isEqualTo(stored);
                assertThat(CanonicalJson.compact(calculator.calculate(again).orElseThrow()))
                        .as(fixture).isEqualTo(CanonicalJson.compact(calculator.calculate(day).orElseThrow()));
            }
        }
    }

    @Test
    void awsInputsStayByteIdentical_theGcpBlockIsLeftOutWhenNull() throws IOException {
        CalcInputs aws = GoldenFixtures.inputs("aws-steady").days().get(0);
        assertThat(aws.gcp()).isNull();
        assertThat(CanonicalJson.compact(aws)).doesNotContain("\"gcp\"");
    }

    @Test
    void thereIsNoFloatingPointInTheGcpCalcSources() throws IOException {
        for (String f : List.of("GcpPrep", "GcpBillingUnits", "SustainedUseDiscount", "CudDeduction")) {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/xammer/cloud/service/autospotting/ledger/calc/" + f + ".java"));
            assertThat(src).as(f).doesNotContainPattern("\\b(double|float|Double|Float)\\b")
                    .doesNotContain("Math.round").doesNotContain("BigDecimal.valueOf(0.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static CalcInputs withGcp(CalcInputs d, Gcp gcp) {
        return new CalcInputs(d.calcVersion(), d.provider(), d.currency(), d.windowStart(), d.windowEnd(), d.group(),
                d.goLiveAt(), d.contract(), d.feeBasis(), d.reconciledTier(), d.transitions(), d.revertOps(),
                d.intervals(), d.capacitySamples(), d.spotPrices(), d.odPrices(), d.original(),
                d.commitmentsStatus(), d.commitments(), gcp);
    }

    private static CalcInputs withIntervals(CalcInputs d, List<CalcInputs.Interval> intervals) {
        return new CalcInputs(d.calcVersion(), d.provider(), d.currency(), d.windowStart(), d.windowEnd(), d.group(),
                d.goLiveAt(), d.contract(), d.feeBasis(), d.reconciledTier(), d.transitions(), d.revertOps(),
                intervals, d.capacitySamples(), d.spotPrices(), d.odPrices(), d.original(), d.commitmentsStatus(),
                d.commitments(), d.gcp());
    }
}
