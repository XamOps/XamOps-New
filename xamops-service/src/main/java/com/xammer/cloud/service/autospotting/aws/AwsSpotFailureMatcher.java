package com.xammer.cloud.service.autospotting.aws;


/**
 * Classifies a failed Auto Scaling activity (design-aws.md §6.2). A failure counts as a Spot-capacity
 * failure only when its status message carries one of the recorded phrases; an unknown message is
 * {@link FailureClass#OTHER}, never a trigger for rollback or fallback. The table is versioned; a
 * change adds a recorded-message fixture to the test.
 */
public final class AwsSpotFailureMatcher {

    /** Version of the phrase table; bump with every change and add a fixture. */
    public static final int TABLE_VERSION = AwsLaunchFailureClassifier.TABLE_VERSION;

    /** The {@code failure_class} values of {@code spot_group_event} (core §8.1). */
    public enum FailureClass { SPOT_CAPACITY, SPOT_QUOTA, ON_DEMAND, OTHER }

    private AwsSpotFailureMatcher() {
    }

    /** Classify a failed launch from its status message and description (see {@link AwsLaunchFailureClassifier}). */
    public static FailureClass classify(String statusMessage, String description) {
        return AwsLaunchFailureClassifier.classify(statusMessage).failureClass();
    }

    /** True for the two Spot failure classes. */
    public static boolean isSpotFailure(FailureClass c) {
        return c == FailureClass.SPOT_CAPACITY || c == FailureClass.SPOT_QUOTA;
    }
}
