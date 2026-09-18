package com.xammer.cloud.service.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins severity normalisation across the four vocabularies the codebase already contains.
 *
 * <p>
 * The case worth the most attention is GuardDuty. It scores findings as a float rather than
 * labelling them, so reading {@code detail.severity} as text and normalising the string
 * matches nothing and falls through to the MEDIUM default — silently flattening an active
 * compromise scored 9.5 down to the same level as a routine config drift. That failure has no
 * symptom other than a severity badge nobody questions, which is exactly why it is tested
 * rather than reasoned about.
 */
class SecuritySeverityTest {

    @Test
    void securityHubAndComplianceOpsUpperCasePassesThrough() {
        assertEquals(SecuritySeverity.CRITICAL, SecuritySeverity.normalize("CRITICAL"));
        assertEquals(SecuritySeverity.HIGH, SecuritySeverity.normalize("HIGH"));
        assertEquals(SecuritySeverity.MEDIUM, SecuritySeverity.normalize("MEDIUM"));
        assertEquals(SecuritySeverity.LOW, SecuritySeverity.normalize("LOW"));
        assertEquals(SecuritySeverity.INFORMATIONAL, SecuritySeverity.normalize("INFORMATIONAL"));
    }

    /** {@code SecurityService} and {@code ProwlerService} emit title case. */
    @Test
    void prowlerTitleCaseNormalises() {
        assertEquals(SecuritySeverity.CRITICAL, SecuritySeverity.normalize("Critical"));
        assertEquals(SecuritySeverity.HIGH, SecuritySeverity.normalize("High"));
        assertEquals(SecuritySeverity.INFORMATIONAL, SecuritySeverity.normalize("Informational"));
    }

    /** {@code PerformanceInsightDto.InsightSeverity} uses a third set of words. */
    @Test
    void performanceInsightsVocabularyNormalises() {
        assertEquals(SecuritySeverity.MEDIUM, SecuritySeverity.normalize("WARNING"));
        assertEquals(SecuritySeverity.LOW, SecuritySeverity.normalize("WEAK_WARNING"));
    }

    /**
     * GuardDuty's 1.0–10.0 scale, banded Low 1.0–3.9, Medium 4.0–6.9, High 7.0–8.9,
     * Critical 9.0–10.0. The band edges are asserted because an off-by-one there is the
     * difference between paging someone and not.
     */
    @Test
    void guardDutyNumericScoreMapsToBands() {
        assertEquals(SecuritySeverity.CRITICAL, SecuritySeverity.fromGuardDutyScore(10.0));
        assertEquals(SecuritySeverity.CRITICAL, SecuritySeverity.fromGuardDutyScore(9.0));
        assertEquals(SecuritySeverity.HIGH, SecuritySeverity.fromGuardDutyScore(8.9));
        assertEquals(SecuritySeverity.HIGH, SecuritySeverity.fromGuardDutyScore(7.0));
        assertEquals(SecuritySeverity.MEDIUM, SecuritySeverity.fromGuardDutyScore(6.9));
        assertEquals(SecuritySeverity.MEDIUM, SecuritySeverity.fromGuardDutyScore(4.0));
        assertEquals(SecuritySeverity.LOW, SecuritySeverity.fromGuardDutyScore(3.9));
        assertEquals(SecuritySeverity.LOW, SecuritySeverity.fromGuardDutyScore(0.1));
        assertEquals(SecuritySeverity.INFORMATIONAL, SecuritySeverity.fromGuardDutyScore(0.0));
    }

    /**
     * A GuardDuty score must never be routed through {@link SecuritySeverity#normalize}.
     * This documents the wrong call rather than the right one, because the wrong call
     * compiles, runs, and produces a plausible-looking answer.
     */
    @Test
    void numericSeverityAsTextWouldSilentlyBecomeMedium() {
        assertEquals(SecuritySeverity.MEDIUM, SecuritySeverity.normalize("9.5"),
                "normalize() cannot read a GuardDuty score — use fromGuardDutyScore()");
        assertEquals(SecuritySeverity.CRITICAL, SecuritySeverity.fromGuardDutyScore(9.5));
    }

    /**
     * Unknown input becomes MEDIUM, not INFORMATIONAL. A finding hidden by a parser gap is
     * invisible; one shown at the wrong severity is merely wrong, and gets noticed.
     */
    @Test
    void unrecognisedSeverityDegradesToMediumRatherThanHiding() {
        assertEquals(SecuritySeverity.MEDIUM, SecuritySeverity.normalize("SEV1"));
        assertEquals(SecuritySeverity.MEDIUM, SecuritySeverity.normalize(""));
        assertEquals(SecuritySeverity.MEDIUM, SecuritySeverity.normalize(null));
    }

    /**
     * {@code worstOf} exists because {@code stream().min(...)} over raw severity strings throws
     * NPE on a null element — {@code Stream.min} wraps its result in {@code Optional.of}. The
     * callers are an email builder and a scheduled notifier, so that exception surfaces as "that
     * account was never notified" rather than as a stack trace anyone reads.
     */
    @Test
    void worstOfPicksTheMostSevereAndToleratesNulls() {
        assertEquals(SecuritySeverity.CRITICAL,
                SecuritySeverity.worstOf(java.util.List.of("LOW", "CRITICAL", "HIGH")));
        assertEquals(SecuritySeverity.HIGH,
                SecuritySeverity.worstOf(java.util.List.of("LOW", "HIGH", "MEDIUM")));
        assertEquals(SecuritySeverity.MEDIUM,
                SecuritySeverity.worstOf(java.util.Arrays.asList((String) null)));
        assertEquals(SecuritySeverity.CRITICAL,
                SecuritySeverity.worstOf(java.util.Arrays.asList(null, "CRITICAL")));
        assertEquals(SecuritySeverity.MEDIUM, SecuritySeverity.worstOf(java.util.List.of()));
        assertEquals(SecuritySeverity.MEDIUM, SecuritySeverity.worstOf(null));
    }

    /** Mixed casing across sources must still compare correctly. */
    @Test
    void worstOfNormalisesBeforeComparing() {
        assertEquals(SecuritySeverity.CRITICAL,
                SecuritySeverity.worstOf(java.util.List.of("high", "Critical")));
    }

    @Test
    void rankOrdersMostSevereFirst() {
        assertTrue(SecuritySeverity.rank(SecuritySeverity.CRITICAL)
                < SecuritySeverity.rank(SecuritySeverity.HIGH));
        assertTrue(SecuritySeverity.rank(SecuritySeverity.HIGH)
                < SecuritySeverity.rank(SecuritySeverity.LOW));
    }

    /** The comparison an "email me about criticals" threshold is built on. */
    @Test
    void atLeastImplementsASeverityThreshold() {
        assertTrue(SecuritySeverity.atLeast(SecuritySeverity.CRITICAL, SecuritySeverity.HIGH));
        assertTrue(SecuritySeverity.atLeast(SecuritySeverity.HIGH, SecuritySeverity.HIGH));
        assertFalse(SecuritySeverity.atLeast(SecuritySeverity.MEDIUM, SecuritySeverity.HIGH));
        assertFalse(SecuritySeverity.atLeast(SecuritySeverity.LOW, SecuritySeverity.CRITICAL));
    }

    /**
     * The Security Center page renders badges by exact string match against the title-case
     * form its existing rows use, so live findings have to convert on the way out or they
     * render unbadged next to identical rows.
     */
    @Test
    void titleCaseMatchesWhatTheExistingBadgesExpect() {
        assertEquals("Critical", SecuritySeverity.toTitleCase(SecuritySeverity.CRITICAL));
        assertEquals("High", SecuritySeverity.toTitleCase("HIGH"));
        assertEquals("Informational", SecuritySeverity.toTitleCase("informational"));
    }

    /**
     * A healthy quota is not a mid-severity problem.
     *
     * <p>Quota checks report a resource comfortably under its limit as {@code OK}. That fell
     * through to the MEDIUM default, so "this is fine" ranked identically to a real warning and
     * would have cleared a MEDIUM digest threshold — the alert equivalent of a false positive
     * that no amount of threshold tuning could remove.
     */
    @Test
    void healthyStatesLandOnTheFloorNotTheMediumDefault() {
        assertEquals(SecuritySeverity.INFORMATIONAL, SecuritySeverity.normalize("OK"));
        assertEquals(SecuritySeverity.INFORMATIONAL, SecuritySeverity.normalize("ok"));
        assertEquals(SecuritySeverity.INFORMATIONAL, SecuritySeverity.normalize("HEALTHY"));
    }

    /**
     * Azure Monitor's Sev4. Below Informational in Azure's own ordering, and there is no band
     * beneath INFORMATIONAL here, so it belongs on the floor rather than out-ranking a warning.
     */
    @Test
    void azureVerboseIsInformational() {
        assertEquals(SecuritySeverity.INFORMATIONAL, SecuritySeverity.normalize("Verbose"));
    }

    /**
     * Azure folds five bands into three on its way to {@code status}, turning Sev1 Error into
     * CRITICAL. Normalising the raw label keeps Error at HIGH, which is what stops a
     * critical-only threshold from paging on every Sev1.
     */
    @Test
    void azureErrorIsHighNotCritical() {
        assertEquals(SecuritySeverity.HIGH, SecuritySeverity.normalize("Error"));
        assertEquals(SecuritySeverity.CRITICAL, SecuritySeverity.normalize("Critical"));
    }
}
