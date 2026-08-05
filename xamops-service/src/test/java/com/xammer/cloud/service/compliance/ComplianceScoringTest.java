package com.xammer.cloud.service.compliance;

import com.xammer.cloud.domain.compliance.ComplianceControlResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the two scoring rules that the previous browser-side maths got wrong.
 *
 * <p>
 * The old page scored a flat ratio of passing resource checks, which meant a hundred
 * untagged volumes outweighed a publicly readable bucket, and it had no concept of a
 * control nobody had evaluated. Both mistakes produce a number that looks precise and
 * points the wrong way, which is worse than no number at all.
 */
class ComplianceScoringTest {

    private ComplianceControlResult result(String severity, String status) {
        ComplianceControlResult r = new ComplianceControlResult();
        r.setCheckId("check_" + severity + "_" + status);
        r.setSeverity(severity);
        r.setStatus(status);
        return r;
    }

    @Test
    void weightsSeverityOrdinally() {
        // The ratios are what matter: one critical must outrank several highs, and lows
        // must be visible without being able to dominate.
        assertThat(ComplianceScoring.weight("CRITICAL")).isGreaterThan(ComplianceScoring.weight("HIGH"));
        assertThat(ComplianceScoring.weight("HIGH")).isGreaterThan(ComplianceScoring.weight("MEDIUM"));
        assertThat(ComplianceScoring.weight("MEDIUM")).isGreaterThan(ComplianceScoring.weight("LOW"));
        assertThat(ComplianceScoring.weight("CRITICAL"))
                .isGreaterThan(ComplianceScoring.weight("HIGH"));
    }

    @Test
    void treatsUnknownSeverityAsLowestRatherThanZero() {
        // INFORMATIONAL findings still count for something; weighting them zero would let
        // an account pass on checks it never satisfied.
        assertThat(ComplianceScoring.weight("INFORMATIONAL")).isEqualTo(1);
        assertThat(ComplianceScoring.weight(null)).isEqualTo(1);
        assertThat(ComplianceScoring.weight("nonsense")).isEqualTo(1);
    }

    @Test
    void oneCriticalFailureCostsMoreThanSeveralLowFailures() {
        ComplianceScoring.Accumulator criticalFail = ComplianceScoring.accumulate(List.of(
                result("CRITICAL", ComplianceControlResult.FAIL),
                result("LOW", ComplianceControlResult.PASS)));

        ComplianceScoring.Accumulator lowFails = ComplianceScoring.accumulate(List.of(
                result("LOW", ComplianceControlResult.FAIL),
                result("MEDIUM", ComplianceControlResult.FAIL),
                result("CRITICAL", ComplianceControlResult.PASS)));

        assertThat(criticalFail.score()).isLessThan(lowFails.score());
    }

    @Test
    void excludesManualControlsFromTheDenominator() {
        // Counting MANUAL as failure makes a shallow scan indistinguishable from an
        // insecure account; counting it as a pass is worse.
        ComplianceScoring.Accumulator withManual = ComplianceScoring.accumulate(List.of(
                result("HIGH", ComplianceControlResult.PASS),
                result("HIGH", ComplianceControlResult.MANUAL),
                result("HIGH", ComplianceControlResult.MANUAL)));

        assertThat(withManual.score()).isEqualTo(100);
        assertThat(withManual.scoreable()).isEqualTo(1);
    }

    @Test
    void excludesWaivedControlsFromTheDenominator() {
        ComplianceScoring.Accumulator withWaiver = ComplianceScoring.accumulate(List.of(
                result("HIGH", ComplianceControlResult.PASS),
                result("CRITICAL", ComplianceControlResult.WAIVED)));

        assertThat(withWaiver.score()).isEqualTo(100);
        assertThat(withWaiver.scoreable()).isEqualTo(1);
    }

    @Test
    void reportsNullRatherThanZeroWhenNothingWasEvaluated() {
        ComplianceScoring.Accumulator nothing = ComplianceScoring.accumulate(List.of(
                result("CRITICAL", ComplianceControlResult.MANUAL),
                result("HIGH", ComplianceControlResult.MANUAL)));

        // A score of 0 reads as "you failed everything". Null reads as "we could not
        // measure this", which is what actually happened.
        assertThat(nothing.score()).isNull();
        assertThat(nothing.assessable()).isFalse();
    }

    @Test
    void reportsNullForAnEmptyScan() {
        ComplianceScoring.Accumulator empty = ComplianceScoring.accumulate(List.of());

        assertThat(empty.score()).isNull();
        assertThat(empty.assessable()).isFalse();
        assertThat(empty.scoreable()).isZero();
    }

    @Test
    void scoresAFullyPassingScanAt100AndAFullyFailingScanAt0() {
        assertThat(ComplianceScoring.accumulate(List.of(
                result("CRITICAL", ComplianceControlResult.PASS),
                result("LOW", ComplianceControlResult.PASS))).score()).isEqualTo(100);

        assertThat(ComplianceScoring.accumulate(List.of(
                result("CRITICAL", ComplianceControlResult.FAIL),
                result("LOW", ComplianceControlResult.FAIL))).score()).isZero();
    }

    @Test
    void labelsMatchTheBandsTheUiRenders() {
        // These strings are looked up by key in complianceTokens.ts. If they drift, the
        // score ring silently falls back to the grey "unable to assess" colour.
        assertThat(ComplianceScoring.label(100)).isEqualTo("Strong");
        assertThat(ComplianceScoring.label(90)).isEqualTo("Strong");
        assertThat(ComplianceScoring.label(89)).isEqualTo("Adequate");
        assertThat(ComplianceScoring.label(75)).isEqualTo("Adequate");
        assertThat(ComplianceScoring.label(74)).isEqualTo("Weak");
        assertThat(ComplianceScoring.label(50)).isEqualTo("Weak");
        assertThat(ComplianceScoring.label(49)).isEqualTo("Critical");
        assertThat(ComplianceScoring.label(0)).isEqualTo("Critical");
    }

    @Test
    void labelsAnUnscoredReportAsUnableToAssessRatherThanCritical() {
        assertThat(ComplianceScoring.label(null)).isEqualTo("Unable to assess");
    }
}
