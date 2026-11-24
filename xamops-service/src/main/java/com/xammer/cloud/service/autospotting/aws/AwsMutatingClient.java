package com.xammer.cloud.service.autospotting.aws;

import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.CreateOrUpdateTagsRequest;
import software.amazon.awssdk.services.autoscaling.model.CreateOrUpdateTagsResponse;
import software.amazon.awssdk.services.autoscaling.model.DeleteTagsRequest;
import software.amazon.awssdk.services.autoscaling.model.DeleteTagsResponse;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse;

import java.util.Objects;

/**
 * AWS's mutating client (design-aws.md §2.1, §2.2). Built by {@link AwsAutoSpottingClientFactory}
 * from a {@code WRITE}-mode session, it exposes <strong>exactly</strong> the three Auto Scaling
 * writes AutoSpotting ever performs — {@code UpdateAutoScalingGroup}, {@code CreateOrUpdateTags},
 * {@code DeleteTags} — and nothing else.
 *
 * <p>No instance-refresh method exists on this surface, so the "never start a refresh" rule is
 * enforced by construction (AC-AWS-02): a step can't call {@code StartInstanceRefresh} because no
 * such method is reachable. This client has no caller until Phase 18 (the mutating programs), and is
 * never reachable from planning, discovery or eligibility, all of which take a
 * {@link AwsReadOnlyView}.
 */
public final class AwsMutatingClient implements AutoCloseable {

    private final String region;
    private final AutoScalingClient autoScaling;

    public AwsMutatingClient(String region, AutoScalingClient autoScaling) {
        this.region = region;
        this.autoScaling = Objects.requireNonNull(autoScaling, "autoScaling");
    }

    /** The region this client mutates. */
    public String region() {
        return region;
    }

    /** {@code autoscaling:UpdateAutoScalingGroup} — the only distribution/launch-template write (§2.4). */
    public UpdateAutoScalingGroupResponse updateAutoScalingGroup(UpdateAutoScalingGroupRequest request) {
        return autoScaling.updateAutoScalingGroup(request);
    }

    /** {@code autoscaling:CreateOrUpdateTags} — writes the XamOps marker tag (§2.3 step 6). */
    public CreateOrUpdateTagsResponse createOrUpdateTags(CreateOrUpdateTagsRequest request) {
        return autoScaling.createOrUpdateTags(request);
    }

    /** {@code autoscaling:DeleteTags} — removes the marker on revert (§5.3 step 5). */
    public DeleteTagsResponse deleteTags(DeleteTagsRequest request) {
        return autoScaling.deleteTags(request);
    }

    /** Closes the underlying Auto Scaling client when this client is evicted from the factory cache. */
    @Override
    public void close() {
        autoScaling.close();
    }
}
