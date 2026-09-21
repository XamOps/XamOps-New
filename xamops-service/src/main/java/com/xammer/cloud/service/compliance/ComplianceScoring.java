package com.xammer.cloud.service.compliance;

import com.xammer.cloud.domain.compliance.ComplianceControlResult;

import java.util.Collection;

/**
 * The one place compliance scores are calculated.
 *
 * <p>
 * The headline number is an <strong>unweighted control pass rate</strong>, because that is the
 * only thing a compliance framework actually asks. CIS does not rank its recommendations,
 * NIST 800-53 does not say AC-2 outranks AU-6, and PCI DSS has no severity tiers at all — there
 * is no such thing as being 83% PCI compliant. An auditor counts unmet controls; so does this.
 *
 * <p>
 * Severity weighting used to drive the headline and no longer does. Two problems with it:
 *
 * <ol>
 *   <li>Scanner severity describes how dangerous a <em>misconfiguration</em> is, not how much a
 *       <em>control</em> matters to a framework. Using one as a proxy for the other is a category
 *       error.</li>
 *   <li>Weighting the numerator pays you for passing critical controls, but passing is the
 *       baseline expectation, not an achievement. The effect was that the score tracked an
 *       account's service mix rather than its hygiene: five passing criticals with five failing
 *       lows scored 91%, while five passing lows with five failing criticals scored 9% — both
 *       accounts having exactly five unmet controls.</li>
 * </ol>
 *
 * Severity still matters for deciding what to fix first, so it survives as
 * {@link Accumulator#riskWeightedScore()} — a separate, separately-labelled number — and as the
 * ordering behind the top-risks list. It is not the compliance figure.
 *
 * <p>
 * Three further rules:
 *
 * <ul>
 *   <li><strong>Waiving is score-neutral.</strong> WAIVED sits in the denominator but not the
 *       numerator, so moving a control from FAIL to WAIVED cannot change the score. Excluding
 *       waivers from the denominator — the previous behaviour — made filing exceptions the
 *       cheapest way to improve posture, which is the one incentive a compliance tool must not
 *       create. {@link Accumulator#scoreWithExceptions()} is the auditor's met-with-exception
 *       view, offered alongside rather than instead.</li>
 *   <li><strong>MANUAL is excluded from the score but not from coverage.</strong> The scanner
 *       could not decide, so it is neither a pass nor a failure. But a score computed over three
 *       of three hundred controls is not a measurement, which is why {@link Accumulator#coverage()}
 *       travels with the score and {@link Accumulator#assessable()} refuses to rate a scan that
 *       settled less than {@link #MIN_COVERAGE_RATIO} of its own control set.</li>
 *   <li><strong>Null, never zero,</strong> when nothing can be assessed. "We could not measure
 *       this" and "you failed everything" are different statements.</li>
 * </ul>
 */
public final class ComplianceScoring {

    private ComplianceScoring() {}

    /**
     * Fewest settled controls that can carry a percentage. Four controls out of four produce a
     * confident-looking 100% that describes almost nothing about an account.
     */
    public static final int MIN_SCOREABLE_CONTROLS = 5;

    /**
     * Share of the scan's own control set that must be settled before a score is published.
     *
     * <p>
     * Guards the failure mode the old {@code scoreable > 0} test allowed: 150 evaluated controls
     * out of 300, all passing, rendered as 100% "Strong" with no warning anywhere on the page.
     * Below this line the page says "Unable to assess" and shows coverage instead, which is the
     * honest answer to a shallow scan.
     */
    public static final double MIN_COVERAGE_RATIO = 0.5;

    /**
     * Ordinal severity weights, used only by {@link Accumulator#riskWeightedScore()} and for
     * ranking work. The ratios matter more than the absolute numbers: one critical outranks three
     * highs, and low findings stay visible without being able to dominate.
     *
     * <p>
     * Anything unrecognised weighs the same as MEDIUM rather than LOW. A control whose severity we
     * could not classify is an unknown, and the previous default quietly made unknowns the least
     * important things in the model — failing open on exactly the rows that deserve a look.
     * INFORMATIONAL is kept explicitly at 1 because that one genuinely is classified as trivial.
     */
    public static int weight(String severity) {
        if (severity == null) return 3;
        switch (severity.toUpperCase()) {
            case "CRITICAL":      return 10;
            case "HIGH":          return 6;
            case "MEDIUM":        return 3;
            case "LOW":           return 1;
            case "INFORMATIONAL": return 1;
            default:              return 3; // UNKNOWN and anything unrecognised
        }
    }

    /** True for a severity string this model has no opinion about. */
    public static boolean isUnclassified(String severity) {
        if (severity == null) return true;
        switch (severity.toUpperCase()) {
            case "CRITICAL":
            case "HIGH":
            case "MEDIUM":
            case "LOW":
            case "INFORMATIONAL":
                return false;
            default:
                return true;
        }
    }

    /**
     * Accumulates control tallies so any subset — a framework, a service, the whole scan — is
     * scored by identical rules.
     */
    public static final class Accumulator {
        private int passCount;
        private int failCount;
        private int waivedCount;
        private int notEvaluatedCount;

        private int passWeight;
        private int failWeight;
        private int waivedWeight;

        private int unclassifiedSeverity;

        public void add(String severity, String status) {
            if (isUnclassified(severity)) {
                unclassifiedSeverity++;
            }
            int w = weight(severity);

            if (ComplianceControlResult.PASS.equals(status)) {
                passCount++;
                passWeight += w;
            } else if (ComplianceControlResult.FAIL.equals(status)) {
                failCount++;
                failWeight += w;
            } else if (ComplianceControlResult.WAIVED.equals(status)) {
                // Counted, and in the denominator. An accepted risk is not a passing control.
                waivedCount++;
                waivedWeight += w;
            } else {
                // MANUAL, or a status nobody established. Not scoreable, but it is coverage.
                notEvaluatedCount++;
            }
        }

        public int passCount() { return passCount; }

        public int failCount() { return failCount; }

        public int waivedCount() { return waivedCount; }

        public int notEvaluatedCount() { return notEvaluatedCount; }

        public int unclassifiedSeverity() { return unclassifiedSeverity; }

        /** Controls with an established outcome: passing, failing, or knowingly accepted. */
        public int settled() {
            return passCount + failCount + waivedCount;
        }

        public int totalControls() {
            return settled() + notEvaluatedCount;
        }

        /**
         * Retained for callers that want "how many controls back this number". Same value as
         * {@link #settled()}; kept under the old name so the intent at call sites stays readable.
         */
        public int scoreable() {
            return settled();
        }

        /** Percentage of the control set that was actually settled. Null when there are none. */
        public Integer coverage() {
            int total = totalControls();
            if (total <= 0) return null;
            return (int) Math.round(100.0 * settled() / total);
        }

        /** Whether coverage clears {@link #MIN_COVERAGE_RATIO}. */
        public boolean coverageAdequate() {
            int total = totalControls();
            if (total <= 0) return false;
            return settled() >= Math.ceil(MIN_COVERAGE_RATIO * total);
        }

        /**
         * A score is published only when enough of the control set was settled, in both absolute
         * and relative terms. Otherwise the page says so rather than rating a rounding error.
         */
        public boolean assessable() {
            return settled() >= MIN_SCOREABLE_CONTROLS && coverageAdequate();
        }

        /**
         * The headline. Unweighted share of settled controls that pass.
         *
         * <p>
         * Waived controls are in the denominator only, which is what makes waiving neutral: a
         * control moving FAIL → WAIVED leaves both numerator and denominator untouched.
         */
        public Integer score() {
            if (!assessable()) return null;
            return (int) Math.round(100.0 * passCount / settled());
        }

        /**
         * The auditor's view: controls covered by an approved exception count as met with a
         * caveat. Always at least {@link #score()}, and shown next to it rather than instead of
         * it so the gap between the two is visible rather than baked in.
         */
        public Integer scoreWithExceptions() {
            if (!assessable()) return null;
            return (int) Math.round(100.0 * (passCount + waivedCount) / settled());
        }

        /**
         * Severity-weighted pass rate — a risk indicator, not a compliance figure, and labelled
         * as such wherever it surfaces. Waiver-neutral for the same reason {@link #score()} is.
         */
        public Integer riskWeightedScore() {
            int total = passWeight + failWeight + waivedWeight;
            if (!assessable() || total <= 0) return null;
            return (int) Math.round(100.0 * passWeight / total);
        }
    }

    public static Accumulator accumulate(Collection<ComplianceControlResult> results) {
        Accumulator acc = new Accumulator();
        for (ComplianceControlResult r : results) {
            acc.add(r.getSeverity(), r.getStatus());
        }
        return acc;
    }

    /**
     * Score band. Deliberately not "Pass/Fail": no scanner output is a certification, and a page
     * used in audit conversations should not imply one.
     *
     * <p>
     * These strings are a contract — the frontend's {@code SCORE_BANDS} map keys off them exactly,
     * and an unrecognised label falls back to neutral grey. Renaming a band here without changing
     * {@code complianceTokens.ts} silently drains the colour out of the page.
     */
    public static String label(Integer score) {
        if (score == null) return "Unable to assess";
        if (score >= 90) return "Strong";
        if (score >= 75) return "Adequate";
        if (score >= 50) return "Weak";
        return "Critical";
    }

    /**
     * The methodology sentence rendered under the score. Lives here next to the arithmetic it
     * describes, because a footnote kept in the component drifts from the maths the moment either
     * changes.
     */
    public static String method() {
        return "Unweighted share of settled controls that pass, counted once per control. "
                + "Severity is not part of this figure — frameworks do not rank their own "
                + "controls, so the risk-weighted score is reported separately. Controls the "
                + "scanner could not decide are excluded and reported as coverage; waived "
                + "controls sit in the denominator, so an exception can never raise the score.";
    }
}
