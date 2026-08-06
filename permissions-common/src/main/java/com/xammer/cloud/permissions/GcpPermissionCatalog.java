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
 * Maps GCP IAM permissions to XamOps features. GCP permissions follow the pattern
 * "service.resource.action", e.g. "compute.instances.list" or "cloudasset.assets.searchAll".
 *
 * Since GCP uses fine-grained permissions rather than services with actions,
 * we map patterns and prefixes to features.
 */
public final class GcpPermissionCatalog {

    private GcpPermissionCatalog() {
    }

    /** GCP service -> label. */
    private static final Map<String, String> SERVICE_LABELS = new HashMap<>();

    /** GCP permission pattern -> impacted features. */
    private static final Map<String, Set<XamOpsFeature>> PERMISSION_FEATURES = new HashMap<>();

    static {
        // ---- service labels ----
        SERVICE_LABELS.put("compute", "Compute Engine");
        SERVICE_LABELS.put("container", "GKE");
        SERVICE_LABELS.put("storage", "Cloud Storage");
        SERVICE_LABELS.put("sql", "Cloud SQL");
        SERVICE_LABELS.put("memcache", "Memorystore");
        SERVICE_LABELS.put("redis", "Memorystore Redis");
        SERVICE_LABELS.put("bigtable", "Cloud Bigtable");
        SERVICE_LABELS.put("datastore", "Firestore");
        SERVICE_LABELS.put("firestore", "Firestore");
        SERVICE_LABELS.put("cloudasset", "Cloud Asset");
        SERVICE_LABELS.put("monitoring", "Cloud Monitoring");
        SERVICE_LABELS.put("logging", "Cloud Logging");
        SERVICE_LABELS.put("cloudkms", "Cloud KMS");
        SERVICE_LABELS.put("iam", "IAM");
        SERVICE_LABELS.put("resourcemanager", "Resource Manager");
        SERVICE_LABELS.put("billing", "Billing");
        SERVICE_LABELS.put("bigquery", "BigQuery");
        SERVICE_LABELS.put("dataflow", "Dataflow");
        SERVICE_LABELS.put("composer", "Cloud Composer");
        SERVICE_LABELS.put("aiplatform", "Vertex AI");
        SERVICE_LABELS.put("run", "Cloud Run");
        SERVICE_LABELS.put("cloudfunctions", "Cloud Functions");
        SERVICE_LABELS.put("appengine", "App Engine");
        SERVICE_LABELS.put("cloudarmor", "Cloud Armor");
        SERVICE_LABELS.put("servicenetworking", "Service Networking");

        // ---- permission patterns -> features ----
        feature("compute", DASHBOARD, CLOUD_LIST, WASTE, RIGHTSIZING, CLOUDSITTER, SPOT_AUTOMATION, PERFORMANCE);
        feature("container", KUBERNETES, CLOUD_LIST);
        feature("storage", CLOUD_LIST, SECURITY, WASTE, MEDIAOPS);
        feature("sql", CLOUD_LIST, DBOPS, WASTE);
        feature("memcache", CLOUD_LIST, DBOPS);
        feature("redis", CLOUD_LIST, DBOPS);
        feature("bigtable", CLOUD_LIST, DBOPS, DATAOPS);
        feature("datastore", CLOUD_LIST, DBOPS);
        feature("firestore", CLOUD_LIST, DBOPS);
        feature("monitoring", PERFORMANCE, ALERTS, DASHBOARD);
        feature("logging", OBSERVABILITY, ALERTS, SRE);
        feature("cloudkms", SECURITY, CLOUD_LIST);
        feature("iam", SECURITY, COMPLIANCE, CLOUD_LIST);
        feature("resourcemanager", DASHBOARD, COMPLIANCE);
        feature("billing", FINOPS, BILLOPS);
        feature("bigquery", DATAOPS, FINOPS);
        feature("dataflow", DATAOPS);
        feature("composer", DATAOPS);
        feature("aiplatform", AIOPS);
        feature("run", CLOUD_LIST, CICD);
        feature("cloudfunctions", CLOUD_LIST);
        feature("appengine", CLOUD_LIST);
        feature("cloudarmor", SECURITY);
        feature("cloudasset", CLOUD_LIST, SECURITY, COMPLIANCE);
    }

    private static void feature(String service, XamOpsFeature... features) {
        PERMISSION_FEATURES.put(service, Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(features))));
    }

    /** Label for a GCP service. */
    public static String labelFor(String service) {
        String label = SERVICE_LABELS.get(service);
        return label != null ? label : service.substring(0, 1).toUpperCase() + service.substring(1);
    }

    /** Features blanked by losing a permission to a service. */
    public static Set<XamOpsFeature> featuresFor(String service) {
        Set<XamOpsFeature> impacted = PERMISSION_FEATURES.get(service);
        return impacted != null ? impacted : Collections.emptySet();
    }

    public static List<String> featureLabels(Set<XamOpsFeature> impacted) {
        Set<String> labels = new LinkedHashSet<>();
        for (XamOpsFeature feature : impacted) {
            labels.add(feature.getLabel());
        }
        return List.copyOf(labels);
    }

    /** Extract service name from a GCP permission (e.g., "compute.instances.list" -> "compute"). */
    public static String serviceFromPermission(String permission) {
        if (permission == null || !permission.contains(".")) {
            return "gcp";
        }
        return permission.substring(0, permission.indexOf("."));
    }
}
