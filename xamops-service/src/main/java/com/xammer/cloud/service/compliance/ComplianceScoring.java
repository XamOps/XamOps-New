package com.xammer.cloud.service.compliance;

import com.xammer.cloud.domain.compliance.ComplianceControlResult;

import java.util.Collection;

/**
 * The one place compliance scores are calculated.
 *
 * Two rules, both of which the previous per-request browser maths got wrong:
 *
 *  1. Severity-weighted, not a flat resource ratio. A public S3 bucket and an untagged
 *     volume are not the same finding, so a percentage that treats them identically
 *     rewards fixing whatever is most numerous rather than what is most dangerous.
 *
 *  2. Only controls that were actually evaluated count. MANUAL (the scanner could not
 *     decide) and WAIVED (a human accepted the risk) are excluded from the denominator.
 *     Counting MANUAL as failure makes a shallow scan look like an insecure account;
 *     counting it as a pass is worse.
 */
public final class ComplianceScoring {

    private ComplianceScoring() {}

    /**
     * Weights are ordinal, not statistical. The ratios matter more than the absolute
     * numbers: one critical outranks three highs, and low findings are visible in the
     * score without being able to dominate it.
     */
    public static int weight(String severity) {
        if (severity == null) return 1;
        switch (severity.toUpperCase()) {
            case "CRITICAL": return 10;
            case "HIGH":     return 6;
            case "MEDIUM":   return 3;
            case "LOW":      return 1;
            default:         return 1; // INFORMATIONAL and anything unrecognised
        }
    }

    /** Accumulates weighted pass/fail so callers can score any subset identically. */
    public static final class Accumulator {
        private int passWeight;
        private int failWeight;
        private int scoreable;

        public void add(String severity, String status) {
            if (ComplianceControlResult.PASS.equals(status)) {
                passWeight += weight(severity);
                scoreable++;
            } else if (ComplianceControlResult.FAIL.equals(status)) {
                failWeight += weight(severity);
                scoreable++;
            }
            // MANUAL and WAIVED are intentionally not scoreable.
        }

        /** How many controls contributed to the score. Zero means "not assessable". */
        public int scoreable() {
            return scoreable;
        }

        public boolean assessable() {
            return scoreable > 0 && (passWeight + failWeight) > 0;
        }

        /** Null rather than 0 when nothing was evaluated — "unknown" is not "failing". */
        public Integer score() {
            int total = passWeight + failWeight;
            if (!assessable()) return null;
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
     * Score band. Deliberately not "Pass/Fail": no scanner output is a certification,
     * and a page used in audit conversations should not imply one.
     */
    public static String label(Integer score) {
        if (score == null) return "Unable to assess";
        if (score >= 90) return "Strong";
        if (score >= 75) return "Adequate";
        if (score >= 50) return "Weak";
        return "Critical";
    }
}
