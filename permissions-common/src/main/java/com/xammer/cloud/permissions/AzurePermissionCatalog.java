package com.xammer.cloud.permissions;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xammer.cloud.permissions.XamOpsFeature.*;

/**
 * Maps Azure RBAC actions to XamOps features. Azure actions follow the pattern
 * "Microsoft.Provider/resourceType/action", e.g. "Microsoft.Compute/virtualMachines/read"
 * or "Microsoft.Authorization/roleAssignments/write".
 *
 * When a permission is denied, we extract the provider (Compute, Storage, etc.)
 * and map it to affected features.
 */
public final class AzurePermissionCatalog {

    private AzurePermissionCatalog() {
    }

    /** Azure provider -> label. */
    private static final Map<String, String> PROVIDER_LABELS = new HashMap<>();

    /** Azure provider -> impacted features. */
    private static final Map<String, Set<XamOpsFeature>> PROVIDER_FEATURES = new HashMap<>();

    static {
        // ---- provider labels ----
        PROVIDER_LABELS.put("Microsoft.Compute", "Virtual Machines");
        PROVIDER_LABELS.put("Microsoft.ContainerService", "AKS");
        PROVIDER_LABELS.put("Microsoft.Storage", "Storage Account");
        PROVIDER_LABELS.put("Microsoft.Sql", "SQL Database");
        PROVIDER_LABELS.put("Microsoft.CacheRedis", "Redis Cache");
        PROVIDER_LABELS.put("Microsoft.DBforMySQL", "MySQL");
        PROVIDER_LABELS.put("Microsoft.DBforPostgreSQL", "PostgreSQL");
        PROVIDER_LABELS.put("Microsoft.DocumentDB", "Cosmos DB");
        PROVIDER_LABELS.put("Microsoft.Insights", "Monitor");
        PROVIDER_LABELS.put("Microsoft.Authorization", "Authorization");
        PROVIDER_LABELS.put("Microsoft.Billing", "Billing");
        PROVIDER_LABELS.put("Microsoft.CostManagement", "Cost Management");
        PROVIDER_LABELS.put("Microsoft.KeyVault", "Key Vault");
        PROVIDER_LABELS.put("Microsoft.Network", "Network");
        PROVIDER_LABELS.put("Microsoft.DataFactory", "Data Factory");
        PROVIDER_LABELS.put("Microsoft.Synapse", "Synapse");
        PROVIDER_LABELS.put("Microsoft.Analytics", "Analytics");
        PROVIDER_LABELS.put("Microsoft.Search", "Cognitive Search");
        PROVIDER_LABELS.put("Microsoft.ManagedIdentity", "Managed Identity");
        PROVIDER_LABELS.put("Microsoft.SecurityAndCompliance", "Security & Compliance");

        // ---- provider -> features ----
        feature("Microsoft.Compute", DASHBOARD, CLOUD_LIST, WASTE, RIGHTSIZING, CLOUDSITTER, PERFORMANCE);
        feature("Microsoft.ContainerService", KUBERNETES, CLOUD_LIST);
        feature("Microsoft.Storage", CLOUD_LIST, SECURITY, WASTE, MEDIAOPS);
        feature("Microsoft.Sql", CLOUD_LIST, DBOPS, WASTE);
        feature("Microsoft.CacheRedis", CLOUD_LIST, DBOPS);
        feature("Microsoft.DBforMySQL", CLOUD_LIST, DBOPS);
        feature("Microsoft.DBforPostgreSQL", CLOUD_LIST, DBOPS);
        feature("Microsoft.DocumentDB", CLOUD_LIST, DBOPS, DATAOPS);
        feature("Microsoft.Insights", PERFORMANCE, ALERTS, OBSERVABILITY);
        feature("Microsoft.Authorization", SECURITY, COMPLIANCE);
        feature("Microsoft.Billing", FINOPS, BILLOPS);
        feature("Microsoft.CostManagement", FINOPS, DASHBOARD, WASTE);
        feature("Microsoft.KeyVault", SECURITY, CLOUD_LIST);
        feature("Microsoft.Network", CLOUD_LIST, SECURITY);
        feature("Microsoft.DataFactory", DATAOPS);
        feature("Microsoft.Synapse", DATAOPS);
        feature("Microsoft.Analytics", DATAOPS);
        feature("Microsoft.Search", CLOUD_LIST, AIOPS);
        feature("Microsoft.ManagedIdentity", SECURITY, COMPLIANCE);
        feature("Microsoft.SecurityAndCompliance", SECURITY, COMPLIANCE);
    }

    private static void feature(String provider, XamOpsFeature... features) {
        PROVIDER_FEATURES.put(provider, Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(features))));
    }

    /** Label for an Azure provider. */
    public static String labelFor(String provider) {
        String label = PROVIDER_LABELS.get(provider);
        if (label != null) {
            return label;
        }
        // Fallback: "Microsoft.Compute" -> "Compute"
        if (provider.startsWith("Microsoft.")) {
            return provider.substring("Microsoft.".length());
        }
        return provider;
    }

    /** Features blanked by losing a permission on a provider. */
    public static Set<XamOpsFeature> featuresFor(String provider) {
        Set<XamOpsFeature> impacted = PROVIDER_FEATURES.get(provider);
        return impacted != null ? impacted : Collections.emptySet();
    }

    public static List<String> featureLabels(Set<XamOpsFeature> impacted) {
        Set<String> labels = new LinkedHashSet<>();
        for (XamOpsFeature feature : impacted) {
            labels.add(feature.getLabel());
        }
        return List.copyOf(labels);
    }

    /** Extract provider from an Azure action (e.g., "Microsoft.Compute/virtualMachines/read" -> "Microsoft.Compute"). */
    public static String providerFromAction(String action) {
        if (action == null || !action.contains("/")) {
            return "Microsoft.Azure";
        }
        return action.substring(0, action.indexOf("/"));
    }
}
