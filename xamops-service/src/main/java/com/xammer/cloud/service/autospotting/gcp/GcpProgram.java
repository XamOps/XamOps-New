package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.autospotting.OperationKind;

import java.util.UUID;

/**
 * The immutable inputs of one GCP program, taken from the operation (design-core.md §9.1): a program is
 * re-derived from these on any instance, so a resume sees the same steps.
 *
 * @param target             the confirmed target (forward leg) or the configuration compensated toward (a
 *                           PRIOR_CONFIG plan {@code current}, or the snapshot payload); {@code versions[]} and
 *                           {@code instanceTemplate} name the templates
 * @param customerPolicy     the customer's own {@code updatePolicy}, restored after the rollout (§3.4)
 * @param compensation       true for the compensation leg
 */
public record GcpProgram(UUID operationId, OperationKind kind, UUID groupId, Long cloudAccountId, String groupKey,
                         UUID planId, JsonNode target, JsonNode customerPolicy, boolean compensation) {

    public GcpEligibilityEvaluator.Key key() {
        return GcpEligibilityEvaluator.split(groupKey);
    }

    /** The standard floor N of the target: {@code xas-standard}'s fixed size, 0 when there is none. */
    public int floorN() {
        for (JsonNode v : target.path("versions")) {
            if (GcpMigFacts.STANDARD_VERSION.equals(v.path("name").asText())) {
                return v.path("targetSize").path("fixed").asInt(0);
            }
        }
        return 0;
    }

    /** True when the forward target adds a Spot version (a conversion) rather than only a policy. */
    public boolean converts() {
        return !compensation && target.path("versions").isArray() && target.path("versions").size() > 0
                && !target.path("policyOnly").asBoolean(false);
    }
}
