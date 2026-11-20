package com.xammer.cloud.service.autospotting.ledger.observe;

import java.util.Locale;

/**
 * Turns what AWS says about the end of an instance into the {@code end_cause} of a
 * {@code spot_usage_interval} (design-ledger.md §2.1, §3.5, §6.4). The EC2 {@code StateReason.Code} of an
 * instance that left the group wins, because it is the provider's own statement that it reclaimed the
 * capacity ({@code Server.SpotInstanceTermination}); the Auto Scaling activity cause is the fallback.
 * Anything that matches neither is {@code UNKNOWN}, never a guess: an UNKNOWN end keeps the interval's
 * upper bound and blocks a billable entry (calculator rule, §3.6).
 */
public final class EndCauseClassifier {

    public static final String PROVIDER_INTERRUPTED = "PROVIDER_INTERRUPTED";
    public static final String SCALE_IN = "SCALE_IN";
    public static final String REPLACED = "REPLACED";
    public static final String USER = "USER";
    public static final String UNKNOWN = "UNKNOWN";

    public static final String SOURCE_EC2_STATE_REASON = "EC2_STATE_REASON";
    public static final String SOURCE_ASG_ACTIVITY_CAUSE = "ASG_ACTIVITY_CAUSE";
    /** A {@code compute.instances.preempted} zone operation (design-ledger.md §3.5). */
    public static final String SOURCE_GCE_PREEMPTED_OPERATION = "GCE_PREEMPTED_OPERATION";
    /** A {@code compute.instances.delete} zone operation: the end is known and the provider did not reclaim it. */
    public static final String SOURCE_GCE_DELETE_OPERATION = "GCE_DELETE_OPERATION";

    /** An end cause with the source that established it ({@code source} is null for UNKNOWN). */
    public record Cause(String cause, String source) {
    }

    private EndCauseClassifier() {
    }

    /**
     * @param ec2StateReasonCode {@code Instance.StateReason.Code} read while the instance was still listed, or null
     * @param activityCause      the {@code Cause} text of the terminating scaling activity, or null
     */
    public static Cause classify(String ec2StateReasonCode, String activityCause) {
        if (ec2StateReasonCode != null && ec2StateReasonCode.startsWith("Server.Spot")) {
            return new Cause(PROVIDER_INTERRUPTED, SOURCE_EC2_STATE_REASON);
        }
        if (ec2StateReasonCode != null && "Client.UserInitiatedShutdown".equals(ec2StateReasonCode)) {
            return new Cause(USER, SOURCE_EC2_STATE_REASON);
        }
        if (activityCause != null) {
            String c = activityCause.toLowerCase(Locale.ROOT);
            if (c.contains("rebalance") || c.contains("instance refresh") || c.contains("replac")
                    || c.contains("unhealthy") || c.contains("max instance lifetime")) {
                return new Cause(REPLACED, SOURCE_ASG_ACTIVITY_CAUSE);
            }
            if (c.contains("difference between desired and actual capacity") || c.contains("scale in")
                    || c.contains("scaling policy") || c.contains("scheduled action")) {
                return new Cause(SCALE_IN, SOURCE_ASG_ACTIVITY_CAUSE);
            }
            if (c.contains("user request") || c.contains("terminateinstanceinautoscalinggroup")
                    || c.contains("detach")) {
                return new Cause(USER, SOURCE_ASG_ACTIVITY_CAUSE);
            }
        }
        return new Cause(UNKNOWN, null);
    }
}
