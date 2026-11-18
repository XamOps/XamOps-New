package com.xammer.cloud.service.autospotting.aws;

import java.util.List;
import java.util.Locale;

/**
 * Classifies a failed Auto Scaling activity (design-aws.md §6.2). A failure counts as a Spot-capacity
 * failure only when its status message carries one of the recorded phrases; an unknown message is
 * {@link FailureClass#OTHER}, never a trigger for rollback or fallback. The table is versioned; a
 * change adds a recorded-message fixture to the test.
 */
public final class AwsSpotFailureMatcher {

    /** Version of the phrase table; bump with every change and add a fixture. */
    public static final int TABLE_VERSION = 1;

    /** The {@code failure_class} values of {@code spot_group_event} (core §8.1). */
    public enum FailureClass { SPOT_CAPACITY, SPOT_QUOTA, ON_DEMAND, OTHER }

    private static final List<String> CAPACITY_PHRASES = List.of(
            "there is no spot capacity available", "spotmaxpricetoolow");
    private static final List<String> QUOTA_PHRASES = List.of(
            "maxspotinstancecountexceeded", "max spot instance count exceeded", "spot instance vcpu",
            "vcpu limit", "spot vcpu");

    private AwsSpotFailureMatcher() {
    }

    /** Classify a failed launch from its status message and description. */
    public static FailureClass classify(String statusMessage, String description) {
        String m = (statusMessage == null ? "" : statusMessage).toLowerCase(Locale.ROOT);
        if (m.isEmpty()) {
            return FailureClass.OTHER;
        }
        boolean spot = m.contains("spot");
        for (String p : QUOTA_PHRASES) {
            if (m.contains(p) && (spot || p.startsWith("maxspot") || p.startsWith("max spot"))) {
                return FailureClass.SPOT_QUOTA;
            }
        }
        for (String p : CAPACITY_PHRASES) {
            if (m.contains(p)) {
                return FailureClass.SPOT_CAPACITY;
            }
        }
        if (m.contains("insufficientinstancecapacity")) {
            return spot ? FailureClass.SPOT_CAPACITY : FailureClass.ON_DEMAND;
        }
        return FailureClass.OTHER;
    }

    /** True for the two Spot failure classes. */
    public static boolean isSpotFailure(FailureClass c) {
        return c == FailureClass.SPOT_CAPACITY || c == FailureClass.SPOT_QUOTA;
    }
}
