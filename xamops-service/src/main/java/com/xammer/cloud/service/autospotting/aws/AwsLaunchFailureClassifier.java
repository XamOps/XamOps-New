package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.aws.AwsSpotFailureMatcher.FailureClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * Classifies a failed Auto Scaling launch activity from its status message (design-aws.md §6.2, AC-EVT-03).
 * The table below is versioned; a change bumps {@link #TABLE_VERSION} and adds a recorded-message fixture under
 * {@code src/test/resources/autospotting/aws/activities/} (V-AWS-16 replaces the recorded text with real
 * samples in Phase 23). Each matcher has a stable id the tests and the audit trail can name.
 *
 * <ul>
 *   <li>{@code SPOT_NO_CAPACITY}, {@code SPOT_MAX_PRICE}: Spot-capacity failures.</li>
 *   <li>{@code SPOT_INSUFFICIENT_CAPACITY}: {@code InsufficientInstanceCapacity} <b>together with</b> a Spot
 *       marker. Without the marker it is an On-Demand failure.</li>
 *   <li>{@code SPOT_COUNT_EXCEEDED}, {@code SPOT_VCPU_QUOTA}: Spot quota failures; they do not heal by waiting.</li>
 *   <li>An unknown message is {@code OTHER}, is logged at info, and never triggers fallback. An On-Demand failure,
 *       inside the On-Demand floor or not, is never a Spot failure: it feeds DEGRADED, never FALLBACK.</li>
 * </ul>
 */
public final class AwsLaunchFailureClassifier {
    private static final Logger log = LoggerFactory.getLogger(AwsLaunchFailureClassifier.class);

    /** Version of the matcher table; bump with every change and add a fixture. */
    public static final int TABLE_VERSION = 1;

    /** The matcher ids, one fixture each. */
    public static final List<String> MATCHER_IDS = List.of("SPOT_NO_CAPACITY", "SPOT_MAX_PRICE",
            "SPOT_INSUFFICIENT_CAPACITY", "SPOT_COUNT_EXCEEDED", "SPOT_VCPU_QUOTA", "ON_DEMAND_INSUFFICIENT_CAPACITY");

    /** The classification and which matcher fired ({@code null} for OTHER and for an empty message). */
    public record Result(FailureClass failureClass, String matcherId) {
        public boolean spot() {
            return AwsSpotFailureMatcher.isSpotFailure(failureClass);
        }

        public boolean quota() {
            return failureClass == FailureClass.SPOT_QUOTA;
        }
    }

    private AwsLaunchFailureClassifier() {
    }

    /** Classify one failed launch. Quota phrases are checked first, because a quota message also says "Spot". */
    public static Result classify(String statusMessage) {
        String m = (statusMessage == null ? "" : statusMessage).toLowerCase(Locale.ROOT);
        if (m.isEmpty()) {
            return new Result(FailureClass.OTHER, null);
        }
        boolean spot = m.contains("spot");
        if (m.contains("maxspotinstancecountexceeded") || m.contains("max spot instance count exceeded")) {
            return new Result(FailureClass.SPOT_QUOTA, "SPOT_COUNT_EXCEEDED");
        }
        if (spot && (m.contains("spot instance vcpu") || m.contains("vcpu limit") || m.contains("spot vcpu"))) {
            return new Result(FailureClass.SPOT_QUOTA, "SPOT_VCPU_QUOTA");
        }
        if (m.contains("there is no spot capacity available")) {
            return new Result(FailureClass.SPOT_CAPACITY, "SPOT_NO_CAPACITY");
        }
        if (m.contains("spotmaxpricetoolow")) {
            return new Result(FailureClass.SPOT_CAPACITY, "SPOT_MAX_PRICE");
        }
        if (m.contains("insufficientinstancecapacity")) {
            return spot ? new Result(FailureClass.SPOT_CAPACITY, "SPOT_INSUFFICIENT_CAPACITY")
                    : new Result(FailureClass.ON_DEMAND, "ON_DEMAND_INSUFFICIENT_CAPACITY");
        }
        if (log.isInfoEnabled()) {
            // The message is not stored or logged: only that the table did not know it (FR-SEC-06).
            log.info("launch failure not in the matcher table (v{}); counted as OTHER, never a fallback trigger",
                    TABLE_VERSION);
        }
        return new Result(FailureClass.OTHER, null);
    }
}
