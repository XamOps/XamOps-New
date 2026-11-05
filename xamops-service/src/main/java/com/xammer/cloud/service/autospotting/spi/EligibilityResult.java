package com.xammer.cloud.service.autospotting.spi;

import java.util.List;

/**
 * The result of an eligibility evaluation (design-core.md §9.2). Carries blockers, warnings and
 * deferred checks, each with a stable id and rule code, the same shape in every {@link EvalMode}
 * (AC-ELG-05). {@code checkEligibility} never throws for "ineligible" nor for a failed check: a
 * check whose read fails yields a BLOCKED {@code CHECK_FAILED} entry naming the check and its
 * {@link ErrorKind}, so it cannot pass (AC-ELG-03).
 */
public record EligibilityResult(
        List<Check> blockers,
        List<Check> warnings,
        List<Check> deferred) {

    public EligibilityResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        deferred = deferred == null ? List.of() : List.copyOf(deferred);
    }

    /** No blockers (a group with only deferred checks still shows Enable; the plan runs them). */
    public boolean eligible() {
        return blockers.isEmpty();
    }

    public static EligibilityResult eligible(List<Check> warnings, List<Check> deferred) {
        return new EligibilityResult(List.of(), warnings, deferred);
    }

    /**
     * One eligibility check outcome. {@code id} is stable across modes; {@code code} is the rule
     * code; {@code text} is human-readable. {@code errorKind} is set only for a {@code CHECK_FAILED}
     * blocker (the read failed), null otherwise.
     */
    public record Check(String id, String code, String text, ErrorKind errorKind) {

        public static Check of(String id, String code, String text) {
            return new Check(id, code, text, null);
        }

        /** A check whose read failed: BLOCKED {@code CHECK_FAILED} naming the check and its kind (AC-ELG-03). */
        public static Check failed(String id, String text, ErrorKind errorKind) {
            return new Check(id, "CHECK_FAILED", text, errorKind);
        }
    }
}
