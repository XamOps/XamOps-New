package com.xammer.cloud.service.automation.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The pure confidence model, asserted against hand-computed values.
 *
 * <p>No cluster, no Spring: {@link ConfidenceScore} is a pure function, so every case is a literal
 * input and a literal expectation.
 */
class ConfidenceScoreTest {

    @Test
    @DisplayName("stable many-sample series: full coverage, low CV -> near 1.0")
    void stableSeriesScoresHigh() {
        // coverage 1.0 -> sampleFraction 1.0; CV = 20/1000 = 0.02 -> volatility 0.98
        // 0.5*1.0 + 0.5*0.98 = 0.99
        assertThat(ConfidenceScore.of(1.0, 1000L, 20L)).isEqualTo(0.99);
    }

    @Test
    @DisplayName("few/volatile series scores low")
    void volatileThinSeriesScoresLow() {
        // coverage 0.5 -> sampleFraction 0.5; CV = 400/500 = 0.8 -> volatility 0.2
        // 0.5*0.5 + 0.5*0.2 = 0.35
        assertThat(ConfidenceScore.of(0.5, 500L, 400L)).isEqualTo(0.35);
    }

    @Test
    @DisplayName("mean or stddev null, or mean zero, gives a neutral volatility factor of 1.0")
    void absentVolatilityInputsAreNeutral() {
        // volatility 1.0 in every branch; with coverage 0.8 -> 0.5*0.8 + 0.5*1.0 = 0.9
        assertThat(ConfidenceScore.of(0.8, null, 20L)).isEqualTo(0.9);
        assertThat(ConfidenceScore.of(0.8, 1000L, null)).isEqualTo(0.9);
        assertThat(ConfidenceScore.of(0.8, 0L, 20L)).isEqualTo(0.9);
    }

    @Test
    @DisplayName("a coefficient of variation above 1 clamps the volatility factor to 0")
    void highCoefficientOfVariationClampsToZero() {
        // CV = 2000/1000 = 2 -> 1-CV = -1 -> clamp to 0; coverage 1.0 -> 0.5*1.0 + 0.5*0 = 0.5
        assertThat(ConfidenceScore.of(1.0, 1000L, 2000L)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("sampleFraction is the passed coverage verbatim, never recomputed")
    void sampleFractionIsTheCoverageVerbatim() {
        // With a neutral volatility (mean null), the score is exactly 0.5*coverage + 0.5*1.0.
        assertThat(ConfidenceScore.of(1.0, null, null)).isEqualTo(1.0);
        assertThat(ConfidenceScore.of(0.5, null, null)).isEqualTo(0.75);
        assertThat(ConfidenceScore.of(0.0, null, null)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("the result is rounded to three decimal places and clamped to [0,1]")
    void resultIsRoundedAndClamped() {
        // coverage 0.333..., neutral volatility -> 0.5*0.3333 + 0.5 = 0.66665 -> 0.667
        assertThat(ConfidenceScore.of(1.0 / 3.0, null, null)).isEqualTo(0.667);
        // a coverage above 1 (defensive) clamps sampleFraction to 1
        assertThat(ConfidenceScore.of(2.0, null, null)).isEqualTo(1.0);
        assertThat(ConfidenceScore.of(-1.0, null, null)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("band() uses the supplied cut points and bakes in no literal")
    void bandUsesSuppliedCutPoints() {
        // Default cuts: floor 0.5, high 0.8.
        assertThat(ConfidenceScore.band(0.80, 0.5, 0.8)).isEqualTo(ConfidenceScore.Band.HIGH);
        assertThat(ConfidenceScore.band(0.79, 0.5, 0.8)).isEqualTo(ConfidenceScore.Band.MEDIUM);
        assertThat(ConfidenceScore.band(0.50, 0.5, 0.8)).isEqualTo(ConfidenceScore.Band.MEDIUM);
        assertThat(ConfidenceScore.band(0.49, 0.5, 0.8)).isEqualTo(ConfidenceScore.Band.LOW);

        // Moving the cuts moves the band for a fixed score, proving no literal is baked in.
        assertThat(ConfidenceScore.band(0.79, 0.5, 0.7)).isEqualTo(ConfidenceScore.Band.HIGH);
        assertThat(ConfidenceScore.band(0.49, 0.4, 0.8)).isEqualTo(ConfidenceScore.Band.MEDIUM);
    }
}
