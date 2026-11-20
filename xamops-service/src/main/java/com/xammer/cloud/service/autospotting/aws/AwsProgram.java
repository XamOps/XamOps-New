package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.autospotting.OperationKind;

import java.util.UUID;

/**
 * The immutable inputs of one AWS program, taken from the operation (design-core.md §9.1): a program
 * is re-derived from these on any instance, so a resume sees the same steps. {@code config} is the
 * configuration the update, rollout wait and verification aim at: the confirmed target for a forward
 * leg, the prior configuration for a compensation leg. {@code compensation} marks the second case.
 */
public record AwsProgram(UUID operationId, OperationKind kind, UUID groupId, Long cloudAccountId,
                         String groupKey, String region, String groupName, UUID planId,
                         JsonNode config, boolean compensation) {

    /** Standard profile numbers: base (B) and percent above base (P) of the {@code config}. */
    public int base() {
        return config.path("instancesDistribution").path("onDemandBaseCapacity").asInt(1);
    }

    public int percentAboveBase() {
        return config.path("instancesDistribution").path("onDemandPercentageAboveBaseCapacity").asInt(0);
    }
}
