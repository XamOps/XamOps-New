package com.xammer.cloud.service.compliance;

import com.xammer.cloud.domain.compliance.ComplianceControlResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scoring contract.
 *
 * <p>
 * These tests exist because the headline number was previously severity-weighted, which made it
 * answer a risk question while the page claimed it answered a compliance one. The properties
 * asserted here are the ones that went wrong: the score must not depend on the severity mix, a
 * waiver must not be able to raise it, and a scan that decided almost nothing must not be allowed
 * to publish a confident percentage.
 */
class ComplianceScoringTest {

    private static ComplianceControlResult control(String severity, String status) {
        ComplianceControlResult r = new ComplianceControlResult();
        r.setSeverity(severity);
        r.setStatus(status);
        return r;
    }

    /** n controls of one severity and status, to keep the arithmetic in the test readable. */
    private static List<ComplianceControlResult> many(int n, String severity, String status) {
        List<ComplianceControlResult> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(control(severity, status));
        return out;
    }

    private static List<ComplianceControlResult> concat(
            List<ComplianceControlResult>... parts) {
        List<ComplianceControlResult> out = new ArrayList<>();
        for (List<ComplianceControlResult> p : parts) out.addAll(p);
        return out;
    }

    // ── The headline is unweighted ────────────────────────────────────────────

    @Nested
    class HeadlineScore {

        @Test
        void countsControlsRatherThanSeverity() {
            ComplianceScoring.Accumulator acc = ComplianceScoring.accumulate(
                    concat(many(5, "CRITICAL", ComplianceControlResult.PASS),
                            many(5, "LOW", ComplianceControlResult.FAIL)));

            // Five of ten controls pass. Not 91%, which is what severity weighting produced.
            assertThat(acc.score()).isEqualTo(50);
        }

        @Test
        void isIdenticalWhenTheSeverityMixIsInverted() {
            ComplianceScoring.Accumulator lowRisk = ComplianceScoring.accumulate(
                    concat(many(5, "CRITICAL", ComplianceControlResult.PASS),
                            many(5, "LOW", ComplianceControlResult.FAIL)));
            ComplianceScoring.Accumulator highRisk = ComplianceScoring.accumulate(
                    concat(many(5, "LOW", ComplianceControlResult.PASS),
                            many(5, "CRITICAL", ComplianceControlResult.FAIL)));

            // Both accounts have exactly five unmet controls, which is what an auditor counts.
            // The old weighted score split these 91% / 9%.
            assertThat(lowRisk.score()).isEqualTo(highRisk.score());
        }

        @Test
        void separatesRiskFromCompliance() {
            ComplianceScoring.Accumulator acc = ComplianceScoring.accumulate(
                    concat(many(5, "LOW", ComplianceControlResult.PASS),
                            many(5, "CRITICAL", ComplianceControlResult.FAIL)));

            assertThat(acc.score()).isEqualTo(50);
            // Severity still has somewhere to speak: 5 weight passing against 50 failing.
            assertThat(acc.riskWeightedScore()).isEqualTo(9);
        }

        @Test
        void reachesBothEndpoints() {
            assertThat(ComplianceScoring.accumulate(
                    concat(many(3, "CRITICAL", ComplianceControlResult.PASS),
                            many(3, "LOW", ComplianceControlResult.PASS))).score())
                    .isEqualTo(100);

            assertThat(ComplianceScoring.accumulate(
                    concat(many(3, "CRITICAL", ComplianceControlResult.FAIL),
                            many(3, "LOW", ComplianceControlResult.FAIL))).score())
                    .isZero();
        }
    }

    // ── Risk weighting still ranks by danger ─────────────────────────────────

    @Nested
    class RiskWeighting {

        @Test
        void ordersSeveritiesOrdinally() {
            // The ratios are what matter: one critical must outrank several highs, and lows must
            // stay visible without being able to dominate.
            assertThat(ComplianceScoring.weight("CRITICAL"))
                    .isGreaterThan(ComplianceScoring.weight("HIGH"));
            assertThat(ComplianceScoring.weight("HIGH"))
                    .isGreaterThan(ComplianceScoring.weight("MEDIUM"));
            assertThat(ComplianceScoring.weight("MEDIUM"))
                    .isGreaterThan(ComplianceScoring.weight("LOW"));
        }

        @Test
        void makesOneCriticalFailureCostMoreThanSeveralLesserOnes() {
            // This property used to be asserted against the headline score. It belongs to the risk
            // score now — the compliance score deliberately treats every unmet control alike — but
            // the property itself is still worth holding onto.
            ComplianceScoring.Accumulator criticalFail = ComplianceScoring.accumulate(
                    concat(many(1, "CRITICAL", ComplianceControlResult.FAIL),
                            many(5, "LOW", ComplianceControlResult.PASS)));

            ComplianceScoring.Accumulator lesserFails = ComplianceScoring.accumulate(
                    concat(many(1, "LOW", ComplianceControlResult.FAIL),
                            many(1, "MEDIUM", ComplianceControlResult.FAIL),
                            many(4, "CRITICAL", ComplianceControlResult.PASS)));

            assertThat(criticalFail.riskWeightedScore())
                    .isLessThan(lesserFails.riskWeightedScore());
        }

        @Test
        void isTheMetricSeverityMixMovesRatherThanTheComplianceScore() {
            ComplianceScoring.Accumulator criticalFail = ComplianceScoring.accumulate(
                    concat(many(1, "CRITICAL", ComplianceControlResult.FAIL),
                            many(5, "LOW", ComplianceControlResult.PASS)));

            ComplianceScoring.Accumulator lowFail = ComplianceScoring.accumulate(
                    concat(many(1, "LOW", ComplianceControlResult.FAIL),
                            many(5, "CRITICAL", ComplianceControlResult.PASS)));

            // One unmet control out of six either way.
            assertThat(criticalFail.score()).isEqualTo(lowFail.score()).isEqualTo(83);
            // But they are not equally dangerous, and the risk score says so.
            assertThat(criticalFail.riskWeightedScore())
                    .isLessThan(lowFail.riskWeightedScore());
        }

        @Test
        void roundsToAWholePercent() {
            ComplianceScoring.Accumulator acc = ComplianceScoring.accumulate(
                    concat(many(11, "HIGH", ComplianceControlResult.PASS),
                            many(26, "HIGH", ComplianceControlResult.FAIL)));

            // 11/37 = 29.7%.
            assertThat(acc.score()).isEqualTo(30);
            assertThat(acc.settled()).isEqualTo(37);
        }
    }

    // ── Waivers cannot buy a better number ───────────────────────────────────

    @Nested
    class Waivers {

        @Test
        void areNeutralOnTheHeadlineScore() {
            ComplianceScoring.Accumulator before = ComplianceScoring.accumulate(
                    concat(many(5, "HIGH", ComplianceControlResult.PASS),
                            many(5, "HIGH", ComplianceControlResult.FAIL)));

            ComplianceScoring.Accumulator after = ComplianceScoring.accumulate(
                    concat(many(5, "HIGH", ComplianceControlResult.PASS),
                            many(4, "HIGH", ComplianceControlResult.FAIL),
                            many(1, "HIGH", ComplianceControlResult.WAIVED)));

            // Waiving one failing control moves it out of `fail` and into `waived`, and the
            // denominator holds. Excluding waivers from the denominator used to lift this to 56%.
            assertThat(after.score()).isEqualTo(before.score()).isEqualTo(50);
        }

        @Test
        void areNeutralOnTheRiskScoreToo() {
            ComplianceScoring.Accumulator before = ComplianceScoring.accumulate(
                    concat(many(5, "HIGH", ComplianceControlResult.PASS),
                            many(5, "CRITICAL", ComplianceControlResult.FAIL)));

            ComplianceScoring.Accumulator after = ComplianceScoring.accumulate(
                    concat(many(5, "HIGH", ComplianceControlResult.PASS),
                            many(4, "CRITICAL", ComplianceControlResult.FAIL),
                            many(1, "CRITICAL", ComplianceControlResult.WAIVED)));

            assertThat(after.riskWeightedScore()).isEqualTo(before.riskWeightedScore());
        }

        @Test
        void areCountedAsMetOnlyByTheExceptionsView() {
            ComplianceScoring.Accumulator acc = ComplianceScoring.accumulate(
                    concat(many(5, "HIGH", ComplianceControlResult.PASS),
                            many(4, "HIGH", ComplianceControlResult.FAIL),
                            many(1, "HIGH", ComplianceControlResult.WAIVED)));

            assertThat(acc.score()).isEqualTo(50);
            assertThat(acc.scoreWithExceptions()).isEqualTo(60);
            assertThat(acc.waivedCount()).isEqualTo(1);
        }

        @Test
        void countTowardCoverageBecauseSomebodyDecidedThem() {
            ComplianceScoring.Accumulator acc = ComplianceScoring.accumulate(
                    concat(many(5, "HIGH", ComplianceControlResult.PASS),
                            many(5, "HIGH", ComplianceControlResult.WAIVED)));

            assertThat(acc.coverage()).isEqualTo(100);
        }
    }

    // ── Coverage gates the score ─────────────────────────────────────────────

    @Nested
    class Coverage {

        @Test
        void refusesToRateAScanThatDecidedHalfOfNothing() {
            // The exact failure the old `scoreable > 0` test allowed: 150 of 300 settled, all
            // passing, published as 100% "Strong" with no warning anywhere.
            ComplianceScoring.Accumulator acc = ComplianceScoring.accumulate(
                    concat(many(3, "HIGH", ComplianceControlResult.PASS),
                            many(297, "HIGH", ComplianceControlResult.MANUAL)));

            assertThat(acc.assessable()).isFalse();
            assertThat(acc.score()).isNull();
            assertThat(ComplianceScoring.label(acc.score())).isEqualTo("Unable to assess");
            // Coverage is still reported — it is the one honest number available.
            assertThat(acc.coverage()).isEqualTo(1);
        }

        @Test
        void refusesAHandfulOfControlsEvenAtFullCoverage() {
            ComplianceScoring.Accumulator acc = ComplianceScoring.accumulate(
                    many(4, "HIGH", ComplianceControlResult.PASS));

            assertThat(acc.coverage()).isEqualTo(100);
            assertThat(acc.assessable()).isFalse();
            assertThat(acc.score()).isNull();
        }

        @Test
        void ratesOnceBothFloorsAreCleared() {
            ComplianceScoring.Accumulator acc = ComplianceScoring.accumulate(
                    concat(many(5, "HIGH", ComplianceControlResult.PASS),
                            many(5, "HIGH", ComplianceControlResult.MANUAL)));

            assertThat(acc.coverage()).isEqualTo(50);
            assertThat(acc.coverageAdequate()).isTrue();
            assertThat(acc.assessable()).isTrue();
            assertThat(acc.score()).isEqualTo(100);
        }

        @Test
        void excludesUndecidedControlsFromTheScoreButNotFromTheDenominatorOfCoverage() {
            ComplianceScoring.Accumulator acc = ComplianceScoring.accumulate(
                    concat(many(6, "HIGH", ComplianceControlResult.PASS),
                            many(2, "HIGH", ComplianceControlResult.FAIL),
                            many(2, "HIGH", ComplianceControlResult.MANUAL)));

            assertThat(acc.settled()).isEqualTo(8);
            assertThat(acc.totalControls()).isEqualTo(10);
            assertThat(acc.notEvaluatedCount()).isEqualTo(2);
            assertThat(acc.score()).isEqualTo(75);
            assertThat(acc.coverage()).isEqualTo(80);
        }

        @Test
        void isNullRatherThanZeroForAnEmptyScan() {
            ComplianceScoring.Accumulator acc = new ComplianceScoring.Accumulator();

            assertThat(acc.coverage()).isNull();
            assertThat(acc.score()).isNull();
            assertThat(acc.riskWeightedScore()).isNull();
            assertThat(acc.scoreWithExceptions()).isNull();
            assertThat(acc.assessable()).isFalse();
        }
    }

    // ── Severity classification ──────────────────────────────────────────────

    @Nested
    class Severity {

        @Test
        void weighsUnknownAsMediumRatherThanAsTheFloor() {
            assertThat(ComplianceScoring.weight("UNKNOWN")).isEqualTo(3);
            assertThat(ComplianceScoring.weight(null)).isEqualTo(3);
            assertThat(ComplianceScoring.weight("something-new")).isEqualTo(3);
            // A genuinely classified trivial finding still weighs 1.
            assertThat(ComplianceScoring.weight("INFORMATIONAL")).isEqualTo(1);
            assertThat(ComplianceScoring.weight("LOW")).isEqualTo(1);
        }

        @Test
        void isCaseInsensitive() {
            assertThat(ComplianceScoring.weight("critical")).isEqualTo(10);
            assertThat(ComplianceScoring.weight("High")).isEqualTo(6);
        }

        @Test
        void reportsHowManyControlsItCouldNotClassify() {
            ComplianceScoring.Accumulator acc = ComplianceScoring.accumulate(
                    concat(many(5, "HIGH", ComplianceControlResult.PASS),
                            many(3, "UNKNOWN", ComplianceControlResult.FAIL),
                            many(2, null, ComplianceControlResult.FAIL)));

            assertThat(acc.unclassifiedSeverity()).isEqualTo(5);
        }

        @Test
        void neverAffectsTheComplianceScore() {
            ComplianceScoring.Accumulator classified = ComplianceScoring.accumulate(
                    concat(many(5, "CRITICAL", ComplianceControlResult.PASS),
                            many(5, "CRITICAL", ComplianceControlResult.FAIL)));
            ComplianceScoring.Accumulator unclassified = ComplianceScoring.accumulate(
                    concat(many(5, null, ComplianceControlResult.PASS),
                            many(5, null, ComplianceControlResult.FAIL)));

            assertThat(unclassified.score()).isEqualTo(classified.score());
        }

        @Test
        void flagsOnlyUnrecognisedValues() {
            assertThat(ComplianceScoring.isUnclassified("CRITICAL")).isFalse();
            assertThat(ComplianceScoring.isUnclassified("informational")).isFalse();
            assertThat(ComplianceScoring.isUnclassified(null)).isTrue();
            assertThat(ComplianceScoring.isUnclassified("UNKNOWN")).isTrue();
        }
    }

    // ── Bands ────────────────────────────────────────────────────────────────

    @Nested
    class Bands {

        /**
         * These strings are a contract with {@code SCORE_BANDS} in complianceTokens.ts. An
         * unrecognised label falls back to neutral grey there, so a rename here drains the colour
         * out of the page rather than failing loudly.
         */
        @Test
        void mapScoresToTheLabelsTheFrontendKeysOff() {
            assertThat(ComplianceScoring.label(100)).isEqualTo("Strong");
            assertThat(ComplianceScoring.label(90)).isEqualTo("Strong");
            assertThat(ComplianceScoring.label(89)).isEqualTo("Adequate");
            assertThat(ComplianceScoring.label(75)).isEqualTo("Adequate");
            assertThat(ComplianceScoring.label(74)).isEqualTo("Weak");
            assertThat(ComplianceScoring.label(50)).isEqualTo("Weak");
            assertThat(ComplianceScoring.label(49)).isEqualTo("Critical");
            assertThat(ComplianceScoring.label(0)).isEqualTo("Critical");
            assertThat(ComplianceScoring.label(null)).isEqualTo("Unable to assess");
        }
    }

    // ── Methodology text ─────────────────────────────────────────────────────

    @Test
    void publishesAMethodSentenceThatMatchesTheArithmetic() {
        String method = ComplianceScoring.method();

        assertThat(method).contains("Unweighted");
        // The two claims a reader most needs, both of which the maths above enforces.
        assertThat(method).contains("coverage");
        assertThat(method).contains("never raise the score");
    }
}
