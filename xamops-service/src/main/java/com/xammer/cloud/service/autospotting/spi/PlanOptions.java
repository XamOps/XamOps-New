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
 * describe the live group. It is true in the managed states, and after a restore program that left XamOps'
 * configuration on the group (a drain-only revert), which the provider records as {@link #CONFIG_ON_GROUP}.
 * Otherwise (before a first enable, after a full revert, a legacy row) the provider decides ownership from the live
 * group alone, so a setting the customer makes after a revert is never treated as XamOps'.
 *
 * <p>There is deliberately no constructor that defaults {@code underManagement}: production code builds the options
 * with {@link #forGroup} or {@link #none()}, so the pre-fix reading cannot come back through a convenience call.
 */
public record PlanOptions(Boolean allowBurstable, String desiredConfigJson, boolean underManagement,
                          boolean originalLinked) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Top-level {@code desired_config} key a provider writes when a restore program ended without removing
     * XamOps' configuration from the group (e.g. a drain-only revert): its ownership markers still apply.
     */
    public static final String CONFIG_ON_GROUP = "configOnGroup";

    /** The states in which XamOps' configuration is on the group (no revert or first enable has removed it). */
    private static final java.util.Set<GroupState> MANAGED = java.util.EnumSet.of(GroupState.APPLYING,
            GroupState.ACTIVE, GroupState.DEGRADED, GroupState.FALLBACK, GroupState.PAUSED, GroupState.REVERTING,
            GroupState.FAILED);

    /**
     * Options with no linked original snapshot known ({@code originalLinked=false}). {@code originalLinked} only
     * chooses the text of the CONFIG_STILL_ON_GROUP blocker (D-23b-03 option b).
     */
    public PlanOptions(Boolean allowBurstable, String desiredConfigJson, boolean underManagement) {
        this(allowBurstable, desiredConfigJson, underManagement, false);
    }

    public static PlanOptions none() {
        return new PlanOptions(null, null, false, false);
    }

    /** The options for a plan on {@code group} (null when it has no row yet). */
    public static PlanOptions forGroup(Boolean allowBurstable, SpotGroup group) {
        if (group == null) {
            return new PlanOptions(allowBurstable, null, false, false);
        }
        return new PlanOptions(allowBurstable, group.getDesiredConfig(),
                isUnderManagement(group.getState(), group.getDesiredConfig()), group.getSnapshotId() != null);
    }

    /** True in a managed state, or when {@code desiredConfigJson} records {@link #CONFIG_ON_GROUP}. */
    public static boolean isUnderManagement(GroupState state, String desiredConfigJson) {
        if (state != null && MANAGED.contains(state)) {
            return true;
        }
        if (state == GroupState.GONE || desiredConfigJson == null || desiredConfigJson.isBlank()) {
            return false;
        }
        try {
            return JSON.readTree(desiredConfigJson).path(CONFIG_ON_GROUP).asBoolean(false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    /**
     * The group's {@code desired_config} when its ownership markers describe the live group, else null. Every
     * reader of those markers on a group row goes through this (plans, status, drift), so they cannot disagree.
     */
    public static String ownershipConfigJson(SpotGroup group) {
        return group == null ? null : forGroup(null, group).ownershipConfigJson();
    }

    /** {@code desired_config} when its ownership markers apply to the live group, else null. */
    public String ownershipConfigJson() {
        return underManagement ? desiredConfigJson : null;
    }


    /**
     * True when the stored config says a restore program left XamOps' configuration on the group (a drain-only
     * revert, D-23b-03): the group's linked snapshot is still the customer's original.
     */
    public boolean configOnGroup() {
        if (!underManagement || desiredConfigJson == null || desiredConfigJson.isBlank()) {
            return false;
        }
        try {
            return JSON.readTree(desiredConfigJson).path(CONFIG_ON_GROUP).asBoolean(false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
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
