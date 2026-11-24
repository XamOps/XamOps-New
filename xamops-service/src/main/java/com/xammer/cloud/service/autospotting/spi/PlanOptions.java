package com.xammer.cloud.service.autospotting.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Customer choices that ride on a plan request (design-aws.md AD-09, design-core.md §7.3). Cloud-neutral: the
 * core passes them through and only the provider reads them.
 *
 * <p>{@code allowBurstable} is the value the customer sent on this request, or null when they did not say.
 * {@code desiredConfigJson} is the group's stored {@code desired_config}, whose {@code options} remember the
 * last confirmed choice, so a later plan starts from it. There is no account-level default.
 */
public record PlanOptions(Boolean allowBurstable, String desiredConfigJson) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static PlanOptions none() {
        return new PlanOptions(null, null);
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

    /** The burstable types XamOps added in the last confirmed plan (desired_config.options.burstableAdded). */
    public java.util.List<String> storedBurstableAdded() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (desiredConfigJson == null || desiredConfigJson.isBlank()) {
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
