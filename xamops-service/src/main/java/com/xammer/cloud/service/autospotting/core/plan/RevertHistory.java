package com.xammer.cloud.service.autospotting.core.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationStatus;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/**
 * What a group's last REVERT ended with, read from the operation and its plan body ({@code revertPath.reasons}).
 * Only GR-02 needs it: a REVERTED group may be reverted again only after a drain-only revert, one that ended
 * {@code EXACT_RESTORE_IMPOSSIBLE(PASSROLE_DENIED)} (design-aws.md §5.3, {@link PlanKinds}). No write, no cloud call.
 */
public final class RevertHistory {
    public static final String PASSROLE_DENIED = "PASSROLE_DENIED";
    private static final ObjectMapper JSON = new ObjectMapper();

    private RevertHistory() {
    }

    /** True when the group's most recent REVERT op SUCCEEDED as a drain-only revert (reason PASSROLE_DENIED). */
    public static boolean lastRevertPassRoleDenied(SpotOperationRepository operations, SpotPlanRepository plans,
                                                   UUID groupId) {
        if (groupId == null) {
            return false;
        }
        return operations.findByGroupId(groupId).stream()
                .filter(o -> o.getKind() == OperationKind.REVERT && o.getStatus() == OperationStatus.SUCCEEDED)
                .max(Comparator.comparing((SpotOperation o) -> o.getCreatedAt() == null ? Instant.EPOCH : o.getCreatedAt()))
                .map(o -> o.getPlanId() == null ? null : plans.findById(o.getPlanId()).orElse(null))
                .map(RevertHistory::drainOnlyForPassRole)
                .orElse(false);
    }

    private static boolean drainOnlyForPassRole(SpotPlan plan) {
        if (plan == null || plan.getBody() == null) {
            return false;
        }
        try {
            JsonNode reasons = JSON.readTree(plan.getBody()).path("revertPath").path("reasons");
            if (reasons.isArray()) {
                for (JsonNode r : reasons) {
                    if (PASSROLE_DENIED.equals(r.asText())) {
                        return true;
                    }
                }
            }
        } catch (java.io.IOException e) {
            return false;
        }
        return false;
    }
}
