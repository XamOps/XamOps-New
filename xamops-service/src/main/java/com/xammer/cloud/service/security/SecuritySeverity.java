package com.xammer.cloud.service.security;

import java.util.List;
import java.util.Locale;

/**
 * The one severity vocabulary, and the conversions into it.
 *
 * <p>
 * This exists because the codebase had four, and they are not interchangeable:
 * <ul>
 * <li>upper-case {@code CRITICAL/HIGH/MEDIUM/LOW} — Security Hub, ComplianceOps, WAR, Upwind
 * <li>title-case {@code Critical/High/Medium/Low} plus {@code Informational} —
 * {@code SecurityService}, {@code ProwlerService}
 * <li>{@code CRITICAL/WARNING/WEAK_WARNING} — {@code PerformanceInsightDto.InsightSeverity}
 * <li>a {@code 1.0}–{@code 10.0} float — GuardDuty
 * </ul>
 * A severity filter or an "email me about criticals" threshold cannot be written against
 * four vocabularies, so everything is normalised on the way in. Findings are stored
 * normalised and converted back only at the edges that need a different casing.
 *
 * <p>
 * Deliberately a small class of constants rather than an enum: the value is persisted as a
 * string and compared in SQL, and an enum would invite {@code Enum.valueOf} on
 * provider-supplied input, which throws on anything unexpected. An unrecognised severity
 * must degrade to {@link #MEDIUM}, not blow up ingest.
 */
public final class SecuritySeverity {

    public static final String CRITICAL = "CRITICAL";
    public static final String HIGH = "HIGH";
    public static final String MEDIUM = "MEDIUM";
    public static final String LOW = "LOW";
    public static final String INFORMATIONAL = "INFORMATIONAL";

    /** Most severe first, which is the order every findings list is sorted in. */
    public static final List<String> RANKED = List.of(CRITICAL, HIGH, MEDIUM, LOW, INFORMATIONAL);

    private SecuritySeverity() {
    }

    /**
     * Normalises a textual severity from any of the vocabularies above.
     *
     * <p>
     * Unknown input becomes {@link #MEDIUM} rather than {@link #INFORMATIONAL}. Guessing low
     * would hide a finding we failed to parse, and a finding hidden by a parser bug is worse
     * than one shown at the wrong severity — the first is invisible, the second is merely
     * wrong and gets corrected.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEDIUM;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        switch (v) {
            case "CRITICAL":
                return CRITICAL;
            case "HIGH":
            case "ERROR":
                return HIGH;
            case "MEDIUM":
            case "WARNING":
                return MEDIUM;
            case "LOW":
            case "WEAK_WARNING":
                return LOW;
            case "INFORMATIONAL":
            case "INFO":
            case "NONE":
                return INFORMATIONAL;
            default:
                return MEDIUM;
        }
    }

    /**
     * Normalises GuardDuty's numeric severity.
     *
     * <p>
     * GuardDuty scores findings on a 1.0–10.0 scale rather than with a label, banded as
     * Low 1.0–3.9, Medium 4.0–6.9, High 7.0–8.9, Critical 9.0–10.0. Reading
     * {@code detail.severity} as a string and passing it to {@link #normalize} would match
     * nothing and silently flatten every GuardDuty finding to MEDIUM — including an active
     * compromise.
     */
    public static String fromGuardDutyScore(double score) {
        if (score >= 9.0) {
            return CRITICAL;
        }
        if (score >= 7.0) {
            return HIGH;
        }
        if (score >= 4.0) {
            return MEDIUM;
        }
        if (score > 0.0) {
            return LOW;
        }
        return INFORMATIONAL;
    }

    /** Rank index, lower is more severe. Unknown sorts last. */
    public static int rank(String severity) {
        int i = RANKED.indexOf(normalize(severity));
        return i < 0 ? RANKED.size() : i;
    }

    /**
     * The most severe of a collection, normalising as it goes.
     *
     * <p>
     * Exists so callers do not reach for {@code stream().min(...)} on raw severity strings. That
     * looks correct and throws {@link NullPointerException} the moment one element is null,
     * because {@code Stream.min} wraps its result in {@code Optional.of}. Normalising first means
     * a null becomes {@link #MEDIUM} and the comparison never sees one — which matters because
     * the callers are an email builder and a scheduled notifier, where the exception surfaces as
     * "that account never got told" rather than as a stack trace anyone reads.
     *
     * @return the worst severity present, or {@link #MEDIUM} when the collection is empty
     */
    public static String worstOf(java.util.Collection<String> severities) {
        if (severities == null || severities.isEmpty()) {
            return MEDIUM;
        }
        String worst = null;
        for (String raw : severities) {
            String candidate = normalize(raw);
            if (worst == null || rank(candidate) < rank(worst)) {
                worst = candidate;
            }
        }
        return worst == null ? MEDIUM : worst;
    }

    /** Whether {@code severity} is at least as severe as {@code threshold}. */
    public static boolean atLeast(String severity, String threshold) {
        return rank(severity) <= rank(threshold);
    }

    /**
     * Title-case form, for the surfaces that already render it that way.
     *
     * <p>
     * {@code SecurityService} and {@code ProwlerService} emit "Critical"/"High", and the
     * frontend maps severity to a badge colour by exact string. Converting here rather than
     * storing both keeps one source of truth without breaking the existing badges.
     */
    public static String toTitleCase(String severity) {
        String v = normalize(severity);
        return v.charAt(0) + v.substring(1).toLowerCase(Locale.ROOT);
    }
}
