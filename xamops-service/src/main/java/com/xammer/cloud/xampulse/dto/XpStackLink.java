package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Where the customer approves a stack in the AWS console.
 * <ul>
 *   <li>{@code action=CREATE}: the stack does not exist yet; {@code quickCreateUrl} opens quick-create with every
 *       parameter filled in.</li>
 *   <li>{@code action=UPDATE}: the stack already exists (quick-create would fail with "already exists");
 *       {@code updateUrl} opens the console's Update stack flow for it, and {@code parameters} lists only the
 *       parameters whose value must change (the console keeps every other parameter's previous value). An empty
 *       string means "clear this value".</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpStackLink(String region, String kind, String stackName, String quickCreateUrl,
                          String action, String updateUrl, Map<String, String> parameters) {

    public static XpStackLink create(String region, String kind, String stackName, String quickCreateUrl) {
        return new XpStackLink(region, kind, stackName, quickCreateUrl, "CREATE", null, null);
    }

    public static XpStackLink update(String region, String kind, String stackName, String updateUrl, Map<String, String> parameters) {
        return new XpStackLink(region, kind, stackName, null, "UPDATE", updateUrl,
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(parameters)));
    }
}
