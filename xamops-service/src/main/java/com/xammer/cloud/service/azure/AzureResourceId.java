package com.xammer.cloud.service.azure;

import java.util.Locale;

/**
 * The parts of an ARM resource id, read out of the id itself.
 *
 * <p>Exists because Cost Management charges by the grouping, not by the column: asking for
 * {@code ResourceGroupName} and {@code ResourceType} alongside {@code ResourceId} is three
 * dimensions on the query most likely to be refused outright, and all three facts are already
 * encoded in the id. Parsing is free and cannot be refused.
 *
 * <p>Handles the nested form as well as the flat one. A SQL database's id is
 * {@code .../providers/Microsoft.Sql/servers/prod/databases/orders}, where the useful type is
 * {@code databases} and not {@code servers} — so the type is taken as the segment immediately before
 * the name rather than the one after the provider namespace.
 *
 * <p>Cost Management lowercases resource ids, which is why nothing here depends on their case and
 * why the type is returned as Azure's own casing is not recoverable. {@code virtualmachines} is
 * returned rather than {@code virtualMachines}: guessing the camel-case boundaries of every ARM type
 * would be wrong for some of them, and a wrong label is worse than an unstyled one.
 *
 * @param name          the resource's own name — the last segment
 * @param resourceGroup the group it lives in, or empty for a subscription-level charge
 * @param type          the ARM type, without its provider namespace, or empty when the id has none
 * @param region        always empty. Kept so callers can ask one object for everything about an id
 *                      and get an honest blank for the part it does not carry — an ARM id names no
 *                      location, and reading one out of a resource group's name would be a guess.
 */
record AzureResourceId(String name, String resourceGroup, String type, String region) {

    private static final AzureResourceId EMPTY = new AzureResourceId("", "", "", "");

    /**
     * Reads an ARM resource id, tolerating anything that is not one.
     *
     * <p>Not every row Cost Management attributes to a "resource" carries an ARM path: Marketplace
     * purchases, support plans and tax arrive with an empty id or an opaque string. Those come back as
     * a name and nothing else, which is what the caller shows — the AWS section already groups
     * resource-less charges separately rather than hiding them.
     */
    static AzureResourceId parse(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return EMPTY;
        }
        String[] segments = resourceId.split("/");
        if (segments.length < 2) {
            return new AzureResourceId(resourceId.trim(), "", "", "");
        }

        String name = segments[segments.length - 1];
        String resourceGroup = "";
        String type = "";

        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i].toLowerCase(Locale.ROOT);
            if ("resourcegroups".equals(segment)) {
                resourceGroup = segments[i + 1];
            } else if ("providers".equals(segment) && i + 2 < segments.length) {
                // The segment before the name, so a nested resource reports the type it actually is.
                type = segments[segments.length - 2];
            }
        }

        return new AzureResourceId(name, resourceGroup, type, "");
    }
}
