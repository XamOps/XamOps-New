package com.xammer.cloud.service.autospotting.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.SpotGroup;

/**
 * Customer choices that ride on a plan request (design-aws.md AD-09, design-core.md §7.3). Cloud-neutral: the
 * core passes them through and only the provider reads them.
 *
 * <p>{@code allowBurstable} is the value the customer sent on this request, or null when they did not say.
 * {@code desiredConfigJson} is the group's stored {@code desired_config}, whose {@code options} remember the
 * last confirmed choice, so a later plan starts from it. There is no account-level default.
 *
 * <p>{@code underManagement} says whether XamOps' configuration is on the group now (D-23b-01). Only then do the
 * ownership markers in {@code desired_config} (the burstable types XamOps added, AWS's owned maintenance policy)
 * describe the live group. After a revert, or before a first enable, the provider decides ownership from the live
 * group alone, so a setting the customer makes after a revert is never treated as XamOps'.
 */
public record PlanOptions(Boolean allowBurstable, String desiredConfigJson, boolean underManagement) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The states in which XamOps' configuration is on the group (no revert or first enable has removed it). */
    private static final java.util.Set<GroupState> MANAGED = java.util.EnumSet.of(GroupState.APPLYING,
            GroupState.ACTIVE, GroupState.DEGRADED, GroupState.FALLBACK, GroupState.PAUSED, GroupState.REVERTING,
            GroupState.FAILED);

    /** Options whose stored config's ownership markers apply (a group under management). */
    public PlanOptions(Boolean allowBurstable, String desiredConfigJson) {
        this(allowBurstable, desiredConfigJson, true);
    }

    public static PlanOptions none() {
        return new PlanOptions(null, null, false);
    }

    /** The options for a plan on {@code group} (null when it has no row yet). */
    public static PlanOptions forGroup(Boolean allowBurstable, SpotGroup group) {
        if (group == null) {
            return new PlanOptions(allowBurstable, null, false);
        }
        return new PlanOptions(allowBurstable, group.getDesiredConfig(), isUnderManagement(group.getState()));
    }

    public static boolean isUnderManagement(GroupState state) {
        return state != null && MANAGED.contains(state);
    }

    /** {@code desired_config} when its ownership markers apply to the live group, else null. */
    public String ownershipConfigJson() {
        return underManagement ? desiredConfigJson : null;
    }

    /** The choice stored in {@code desired_config.options}, or false. */
    public boolean storedAllowBurstable() {
        if (desiredConfigJson == null || desiredConfigJson.isBlank()) {
            return false;
        }
        try {
            JsonNode n = JSON.readTree(desiredConfigJson);
            return n.path("options").path("allowBurstable").asBoolean(false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    /**
     * The burstable types XamOps added in the last confirmed plan (desired_config.options.burstableAdded), or empty
     * when the group is not under management (a revert removed them; a type there now is the customer's).
     */
    public java.util.List<String> storedBurstableAdded() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!underManagement || desiredConfigJson == null || desiredConfigJson.isBlank()) {
            return out;
        }
        try {
            JSON.readTree(desiredConfigJson).path("options").path("burstableAdded")
                    .forEach(n -> out.add(n.asText()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            out.clear();
        }
        return out;
    }
    /** Request value, else the stored choice, else false. */
    public boolean effectiveAllowBurstable() {
        return allowBurstable != null ? allowBurstable : storedAllowBurstable();
    }

    /** True when the customer asked for the option on this request. */
    public boolean explicitlyAllowsBurstable() {
        return Boolean.TRUE.equals(allowBurstable);
    }
}
