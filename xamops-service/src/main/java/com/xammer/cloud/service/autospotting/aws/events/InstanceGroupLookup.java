package com.xammer.cloud.service.autospotting.aws.events;

import com.xammer.cloud.domain.CloudAccount;

import java.util.Optional;

/**
 * Finds the Auto Scaling group of an instance when the reconciler's index has no entry
 * (design-aws.md §9.6 step 5): {@code DescribeAutoScalingInstances} first, then the
 * {@code aws:autoscaling:groupName} tag. Read only, bounded to 10 s.
 */
public interface InstanceGroupLookup {

    /**
     * @return the group name, or empty when the instance is in no group
     * @throws RuntimeException on a read failure (retried by the caller)
     */
    Optional<String> groupName(CloudAccount account, String region, String instanceId);
}
