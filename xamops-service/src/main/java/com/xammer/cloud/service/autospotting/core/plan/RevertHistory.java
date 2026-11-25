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

    /**
     * Whether a REVERT may be planned or confirmed for {@code group} in {@code state}, beyond the plain kind/state
     * table (design-core 7.4; phase 23b round 4 #1). {@code regrant} feeds {@link PlanKinds#isAllowed}.
     * <ul>
     *   <li>REVERTED: only when XamOps' configuration is still on the group, i.e. the last revert was drain-only
     *       (GR-02, PASSROLE_DENIED) or the row records {@code configOnGroup} (any drain-only revert, D-23b-01).</li>
     *   <li>PLANNED: only when a confirmed ENABLE has not finished, or the row records {@code configOnGroup}, so an
     *       enable that failed before its first mutation never takes away a revert that was available.</li>
     * </ul>
     * {@code refusal} is null when allowed, else the 409 code.
     */
    public record RevertGate(String refusal, boolean regrant) {
    }

    public static RevertGate revertGate(SpotOperationRepository operations, SpotPlanRepository plans,
                                        com.xammer.cloud.domain.autospotting.SpotGroup group,
                                        com.xammer.cloud.domain.autospotting.GroupState state) {
        if (group == null) {
            return new RevertGate("NOT_MANAGED", false);
        }
        boolean configOnGroup = com.xammer.cloud.service.autospotting.spi.PlanOptions.forGroup(null, group)
                .configOnGroup();
        boolean regrant = state == com.xammer.cloud.domain.autospotting.GroupState.REVERTED
                && (configOnGroup || lastRevertPassRoleDenied(operations, plans, group.getId()));
        String refusal = switch (state) {
            case UNMANAGED, GONE -> "NOT_MANAGED";
            case REVERTED -> regrant ? null : "NOT_MANAGED";
            case PLANNED -> configOnGroup || hasUnfinishedEnable(operations, group.getId()) ? null : "NOT_MANAGED";
            case REVERTING -> "AS_GROUP_BUSY";
            default -> null;
        };
        return new RevertGate(refusal, regrant);
    }

    /** True when a confirmed ENABLE op of the group has not ended (QUEUED, RUNNING or WAITING). */
    public static boolean hasUnfinishedEnable(SpotOperationRepository operations, UUID groupId) {
        return groupId != null && operations.findByGroupId(groupId).stream()
                .anyMatch(o -> o.getKind() == OperationKind.ENABLE && (o.getStatus() == OperationStatus.QUEUED
                        || o.getStatus() == OperationStatus.RUNNING || o.getStatus() == OperationStatus.WAITING));
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
