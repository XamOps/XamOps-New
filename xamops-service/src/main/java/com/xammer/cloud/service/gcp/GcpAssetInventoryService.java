package com.xammer.cloud.service.gcp;

import com.google.cloud.asset.v1.AssetServiceClient;
import com.google.cloud.asset.v1.ResourceSearchResult;
import com.xammer.cloud.dto.gcp.GcpResourceDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Enumerates every resource in a GCP project through Cloud Asset Inventory.
 *
 * <p>Replaces the shape of the inventory scan rather than one of its steps. {@code GcpDataService}
 * enumerates a project by calling roughly thirty per-service clients — Compute, Storage, GKE, Cloud
 * SQL, DNS, KMS, Functions, Pub/Sub and the rest — each with its own client construction, its own
 * failure mode and its own hand-written mapper. Cloud Asset Inventory answers the same question in
 * one paginated query.
 *
 * <p>Three things follow from that:
 *
 * <ul>
 *   <li><b>Coverage stops being a function of what someone remembered to write.</b> The per-service
 *       list can only see types with a collector. A project using Dataflow, Composer, Filestore or
 *       Spanner showed none of them, and would not until someone shipped a new collector. Asset
 *       Inventory returns every type GCP indexes, and {@link #friendlyType} derives a readable label
 *       for anything not explicitly mapped, so a new service appears without a code change.
 *   <li><b>One request instead of ~30.</b> The existing fan-out is parallel, so wall-clock time was
 *       tolerable, but it is thirty times the API calls per refresh per project and thirty separate
 *       chances to trip a quota.
 *   <li><b>Pagination is uniform.</b> {@code iterateAll()} follows page tokens to the end, rather
 *       than each collector handling — or not handling — its own paging.
 *
 * </ul>
 *
 * <p><b>What this deliberately does not provide.</b> Search results identify a resource by its
 * name, not by the numeric id Compute Engine assigns it, and Cloud Monitoring filters for GCE
 * metrics require that numeric id. {@code GcpOptimizationService} builds a name-to-id map from the
 * inventory for exactly that purpose, so if this were used as a drop-in replacement every rightsizing
 * recommendation would silently lose its utilisation enrichment. The caller therefore overlays the
 * Compute Engine collector on top of this result; see {@code GcpDataService.getAllResources}.
 *
 * <p>Requires {@code cloudasset.googleapis.com} to be enabled on the project and the service account
 * to hold {@code cloudasset.assets.searchAllResources}. When either is missing this returns an empty
 * {@link Optional} — not an empty list — so the caller can tell "could not ask" from "nothing there"
 * and fall back to the per-service scan instead of reporting an empty project.
 */
@Service
@Slf4j
public class GcpAssetInventoryService {

    private final GcpClientProvider gcpClientProvider;

    public GcpAssetInventoryService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    /**
     * Asset type to the label the per-service collectors already produce.
     *
     * <p>These strings are a contract, not cosmetics: {@code buildGrouped} groups the cloudlist by
     * them and the UI renders the group headings and per-service icons from them. Diverging here
     * would split one service into two rows depending on which code path filled the cache.
     */
    private static final Map<String, String> ASSET_TYPE_TO_LABEL = Map.ofEntries(
            Map.entry("compute.googleapis.com/Instance", "Compute Engine"),
            Map.entry("compute.googleapis.com/Disk", "Persistent Disk"),
            Map.entry("compute.googleapis.com/RegionDisk", "Persistent Disk"),
            Map.entry("compute.googleapis.com/Snapshot", "Disk Snapshot"),
            Map.entry("compute.googleapis.com/Image", "Compute Image"),
            Map.entry("compute.googleapis.com/Network", "VPC Network"),
            Map.entry("compute.googleapis.com/Firewall", "Firewall Rule"),
            Map.entry("compute.googleapis.com/Router", "Cloud NAT"),
            Map.entry("compute.googleapis.com/ForwardingRule", "Load Balancer"),
            Map.entry("compute.googleapis.com/GlobalForwardingRule", "Load Balancer"),
            Map.entry("compute.googleapis.com/SecurityPolicy", "Cloud Armor"),
            Map.entry("storage.googleapis.com/Bucket", "Cloud Storage"),
            Map.entry("container.googleapis.com/Cluster", "Kubernetes Engine"),
            Map.entry("sqladmin.googleapis.com/Instance", "Cloud SQL"),
            Map.entry("dns.googleapis.com/ManagedZone", "Cloud DNS"),
            Map.entry("cloudkms.googleapis.com/CryptoKey", "Cloud KMS"),
            Map.entry("cloudfunctions.googleapis.com/CloudFunction", "Cloud Function"),
            Map.entry("cloudfunctions.googleapis.com/Function", "Cloud Function"),
            Map.entry("run.googleapis.com/Service", "Cloud Run"),
            Map.entry("redis.googleapis.com/Instance", "Memorystore Redis"),
            Map.entry("secretmanager.googleapis.com/Secret", "Secret Manager"),
            Map.entry("apigateway.googleapis.com/Api", "API Gateway"),
            Map.entry("bigquery.googleapis.com/Dataset", "BigQuery Dataset"),
            Map.entry("bigqueryreservation.googleapis.com/Reservation", "BigQuery Reservation"),
            Map.entry("logging.googleapis.com/LogBucket", "Logging Bucket"),
            Map.entry("pubsub.googleapis.com/Topic", "Pub/Sub Topic"),
            Map.entry("pubsub.googleapis.com/Subscription", "Pub/Sub Subscription"),
            Map.entry("monitoring.googleapis.com/AlertPolicy", "Monitoring Alert Policy"),
            Map.entry("monitoring.googleapis.com/UptimeCheckConfig", "Monitoring Uptime Check"),
            Map.entry("cloudscheduler.googleapis.com/Job", "Cloud Scheduler Job"),
            Map.entry("dataplex.googleapis.com/Lake", "Dataplex Lake"),
            Map.entry("osconfig.googleapis.com/PatchDeployment", "VM Manager Patch Deployment"),
            Map.entry("aiplatform.googleapis.com/Model", "Vertex AI Model"),
            Map.entry("aiplatform.googleapis.com/Endpoint", "Vertex AI Endpoint"),
            Map.entry("artifactregistry.googleapis.com/Repository", "Artifact Registry"),
            Map.entry("cloudbuild.googleapis.com/Build", "Cloud Build"),
            Map.entry("appengine.googleapis.com/Application", "App Engine"));

    /** Service-name fragments that read better expanded than title-cased. */
    private static final Map<String, String> SERVICE_PREFIX_LABELS = Map.ofEntries(
            Map.entry("compute", "Compute"),
            Map.entry("sqladmin", "Cloud SQL"),
            Map.entry("container", "Kubernetes Engine"),
            Map.entry("bigquery", "BigQuery"),
            Map.entry("bigqueryreservation", "BigQuery"),
            Map.entry("aiplatform", "Vertex AI"),
            Map.entry("cloudfunctions", "Cloud Function"),
            Map.entry("cloudkms", "Cloud KMS"),
            Map.entry("cloudscheduler", "Cloud Scheduler"),
            Map.entry("cloudresourcemanager", "Resource Manager"),
            Map.entry("artifactregistry", "Artifact Registry"),
            Map.entry("secretmanager", "Secret Manager"),
            Map.entry("osconfig", "VM Manager"),
            Map.entry("serviceusage", "Service Usage"),
            Map.entry("iam", "IAM"),
            Map.entry("dataproc", "Dataproc"),
            Map.entry("dataflow", "Dataflow"),
            Map.entry("composer", "Cloud Composer"),
            Map.entry("file", "Filestore"),
            Map.entry("spanner", "Spanner"),
            Map.entry("bigtableadmin", "Bigtable"),
            Map.entry("memcache", "Memorystore Memcached"),
            Map.entry("redis", "Memorystore Redis"),
            Map.entry("apigateway", "API Gateway"),
            Map.entry("certificatemanager", "Certificate Manager"),
            Map.entry("networkservices", "Network Services"),
            Map.entry("vpcaccess", "Serverless VPC Access"));

    /**
     * Enumerates the project.
     *
     * @return the resources, or empty when Asset Inventory could not be queried at all — the caller
     *         must fall back rather than treat that as an empty project
     */
    public Optional<List<GcpResourceDto>> searchAllResources(String gcpProjectId) {
        if (gcpProjectId == null || gcpProjectId.isBlank()) {
            return Optional.empty();
        }

        Optional<AssetServiceClient> clientOpt = gcpClientProvider.getAssetServiceClient(gcpProjectId);
        if (clientOpt.isEmpty()) {
            log.warn("[GcpAssetInventory] No Cloud Asset client for project {} — caller should fall back "
                    + "to the per-service scan", gcpProjectId);
            return Optional.empty();
        }

        long startMs = System.currentTimeMillis();
        List<GcpResourceDto> resources = new ArrayList<>();
        Map<String, Integer> unmappedTypes = new HashMap<>();

        try (AssetServiceClient asset = clientOpt.get()) {
            // Empty query and empty asset-type filter: everything the project has. iterateAll()
            // follows page tokens to the end, so there is no truncation cliff.
            for (ResourceSearchResult result :
                    asset.searchAllResources("projects/" + gcpProjectId, "", List.of()).iterateAll()) {

                String assetType = result.getAssetType();
                String label = ASSET_TYPE_TO_LABEL.get(assetType);
                if (label == null) {
                    label = friendlyType(assetType);
                    unmappedTypes.merge(assetType, 1, Integer::sum);
                }

                GcpResourceDto dto = new GcpResourceDto();
                // Name, not numeric id — see the class comment. The caller overlays Compute Engine
                // from its own collector so GCE keeps the numeric id that Monitoring filters need.
                dto.setId(lastSegment(result.getName()));
                dto.setName(displayNameOf(result));
                dto.setType(label);
                dto.setLocation(result.getLocation().isBlank() ? "global" : result.getLocation());
                dto.setStatus(result.getState().isBlank() ? "ACTIVE" : result.getState());
                dto.setTags(result.getLabelsMap().isEmpty() ? Map.of() : new HashMap<>(result.getLabelsMap()));
                resources.add(dto);
            }
        } catch (Exception e) {
            // Empty Optional, not a partial list. A permission or API-not-enabled failure part-way
            // through would otherwise present as a project that owns only the resources scanned
            // before the error.
            log.warn("[GcpAssetInventory] searchAllResources failed for project {} ({}). Falling back "
                    + "to the per-service scan. Enable cloudasset.googleapis.com and grant "
                    + "cloudasset.assets.searchAllResources to use the single-query path.",
                    gcpProjectId, e.getMessage());
            return Optional.empty();
        }

        if (!unmappedTypes.isEmpty()) {
            // Informational, not a problem: these resources ARE in the result with a derived label.
            // Logged so a frequently-seen type can be given an explicit label later.
            log.info("[GcpAssetInventory] {} asset type(s) had no explicit label and were derived: {}",
                    unmappedTypes.size(), unmappedTypes);
        }

        log.info("[GcpAssetInventory] {} resources for project {} in {}ms via one Asset Inventory query",
                resources.size(), gcpProjectId, System.currentTimeMillis() - startMs);

        // An genuinely empty project is a real answer, but it is also what a silently-misconfigured
        // one looks like, and the per-service scan is the cheaper way to tell them apart.
        if (resources.isEmpty()) {
            log.warn("[GcpAssetInventory] Asset Inventory returned no resources for project {} — "
                    + "falling back to the per-service scan to confirm", gcpProjectId);
            return Optional.empty();
        }

        return Optional.of(resources);
    }

    /**
     * Derives a readable label for an asset type with no explicit mapping.
     *
     * <p>{@code dataflow.googleapis.com/Job} becomes "Dataflow Job", {@code spanner.../Database}
     * becomes "Spanner Database". This is what removes the hand-maintained type list: an unmapped
     * type is displayed under a sensible heading instead of being dropped.
     */
    static String friendlyType(String assetType) {
        if (assetType == null || assetType.isBlank()) {
            return "Other";
        }
        int slash = assetType.indexOf('/');
        if (slash < 0) {
            return splitCamelCase(assetType);
        }

        String service = assetType.substring(0, slash).replace(".googleapis.com", "");
        String kind = splitCamelCase(assetType.substring(slash + 1));
        String serviceLabel = SERVICE_PREFIX_LABELS.get(service.toLowerCase(Locale.ROOT));
        if (serviceLabel == null) {
            serviceLabel = splitCamelCase(capitalise(service));
        }

        // Avoid "Spanner Spanner" / "Dataflow Dataflow" when the kind repeats the service.
        if (kind.equalsIgnoreCase(serviceLabel) || serviceLabel.toLowerCase(Locale.ROOT)
                .contains(kind.toLowerCase(Locale.ROOT))) {
            return serviceLabel;
        }
        return serviceLabel + " " + kind;
    }

    /** {@code UptimeCheckConfig} -> {@code Uptime Check Config}. */
    private static String splitCamelCase(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim();
    }

    private static String capitalise(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** Last path segment of a full resource name. */
    private static String lastSegment(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return "";
        }
        int slash = resourceName.lastIndexOf('/');
        return slash < 0 ? resourceName : resourceName.substring(slash + 1);
    }

    /**
     * The name to show.
     *
     * <p>{@code displayName} is unset for many asset types, in which case the last segment of the
     * resource name is what a user recognises.
     */
    private static String displayNameOf(ResourceSearchResult result) {
        String displayName = result.getDisplayName();
        return displayName != null && !displayName.isBlank()
                ? displayName
                : lastSegment(result.getName());
    }
}
