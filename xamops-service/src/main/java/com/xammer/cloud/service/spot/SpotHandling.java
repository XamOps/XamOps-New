package com.xammer.cloud.service.spot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Set;

/**
 * XamOps spot-interruption handling at notice time (design §B4.2 "Handling mapping").
 *
 * @param mode   live | simulate | off | not-eligible | unknown
 * @param reason human-readable reason, at most {@link #REASON_MAX} characters
 */
public record SpotHandling(String mode, String reason) {

    public static final String LIVE = "live";
    public static final String SIMULATE = "simulate";
    public static final String OFF = "off";
    public static final String NOT_ELIGIBLE = "not-eligible";
    public static final String UNKNOWN = "unknown";

    /** {@code handling_reason} is VARCHAR(255). */
    public static final int REASON_MAX = 255;

    private static final Set<String> KNOWN_MODES = Set.of(LIVE, SIMULATE, OFF);

    public SpotHandling {
        reason = truncate(reason);
    }

    /** Observations from the XamOps agent snapshot, which carries no gate decision. */
    public static SpotHandling agentSnapshot() {
        return new SpotHandling(UNKNOWN, "Seen in the XamOps agent snapshot");
    }

    /**
     * Rows are checked top to bottom: no decision → unknown; detection failed and the notice is a
     * legacy-label one → unknown (eligibility unknowable); a known mode and drain-eligible → that
     * mode with the decision's reason; a known mode and not eligible → not-eligible with the mode in
     * the reason; anything else (incl. {@code analyse}) → unknown.
     *
     * @param decision           the report's {@code interruptionHandling} ({@code {mode, reason}}), may be null
     * @param drainEligible      the notice's {@code drainEligible}
     * @param eligibilityUnknown the report's {@code interruptedNodesDetectionFailed}
     * @param source             the notice's source
     */
    public static SpotHandling from(JsonNode decision, boolean drainEligible, boolean eligibilityUnknown, String source) {
        if (decision == null || decision.isNull() || decision.isMissingNode() || !decision.isObject()
                || SnapshotView.text(decision, "mode") == null) {
            return new SpotHandling(UNKNOWN, "The automation agent didn't report its handling state");
        }
        String raw = SnapshotView.text(decision, "mode");
        String mode = raw.toLowerCase(Locale.ROOT);
        if (!KNOWN_MODES.contains(mode)) {
            return new SpotHandling(UNKNOWN, "Unrecognized handling mode " + raw);
        }
        if (eligibilityUnknown && SpotNoticeRules.SOURCE_NTH_LABEL_LEGACY.equals(source)) {
            return new SpotHandling(UNKNOWN, "The automation agent couldn't check drain eligibility this cycle");
        }
        if (drainEligible) {
            return new SpotHandling(mode, SnapshotView.text(decision, "reason"));
        }
        return new SpotHandling(NOT_ELIGIBLE, mode
                + ": XamOps drains only nodes with the legacy NTH label today; this notice came from " + source);
    }

    /** True for a mode that says what XamOps did or would do (not null, not unknown). */
    public static boolean isKnown(String mode) {
        return mode != null && !UNKNOWN.equals(mode);
    }

    static String truncate(String reason) {
        return reason != null && reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) : reason;
    }
}
