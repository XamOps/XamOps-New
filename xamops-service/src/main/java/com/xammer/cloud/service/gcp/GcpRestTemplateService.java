package com.xammer.cloud.service.gcp;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xammer.cloud.config.HttpClient5Support;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Service to interact with GCP Compute Engine REST API directly
 * This bypasses the Java SDK limitations and works without gcloud CLI
 */
@Service
@Slf4j
public class GcpRestTemplateService {

    private final RestTemplate restTemplate;
    private final CloudAccountRepository cloudAccountRepository;
    private final Gson gson = new Gson();

    private static final String COMPUTE_API_BASE = "https://compute.googleapis.com/compute/v1";

    public GcpRestTemplateService(CloudAccountRepository cloudAccountRepository) {
        this.cloudAccountRepository = cloudAccountRepository;

        // Configure RestTemplate with HttpComponents to support PATCH method.
        // Connect timeout lives on the HttpClient 5 connection manager, not the factory.
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom()
                        .setConnectionManager(HttpClient5Support.connectionManager(30000))
                        .build());
        requestFactory.setReadTimeout(60000);

        this.restTemplate = new RestTemplate(requestFactory);
    }

    /**
     * Get access token for API requests.
     *
     * <p>Package-private as a test seam: tests override it so no credentials are parsed and no
     * token request leaves the JVM.
     */
    String getAccessToken(String projectId) throws IOException {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByProviderAccountId(projectId);
        if (accountOpt.isEmpty()) {
            throw new RuntimeException("GCP account not found for project: " + projectId);
        }

        CloudAccount account = accountOpt.get();
        String serviceAccountKey = account.getGcpServiceAccountKey();
        if (serviceAccountKey == null || serviceAccountKey.isBlank()) {
            throw new RuntimeException("No service account credentials found for project: " + projectId);
        }

        GoogleCredentials credentials = GoogleCredentials.fromStream(
                new ByteArrayInputStream(serviceAccountKey.getBytes()))
                .createScoped("https://www.googleapis.com/auth/compute"); // Add Compute Engine scope
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    /**
     * Create HTTP headers with authentication
     */
    private HttpHeaders createHeaders(String projectId) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(getAccessToken(projectId));
        return headers;
    }

    /**
     * Get instance template via REST API
     */
    public JsonObject getInstanceTemplate(String projectId, String region, String templateName) throws IOException {
        String url;
        if (region != null) {
            // Regional template
            url = String.format("%s/projects/%s/regions/%s/instanceTemplates/%s",
                    COMPUTE_API_BASE, projectId, region, templateName);
        } else {
            // Global template
            url = String.format("%s/projects/%s/global/instanceTemplates/%s",
                    COMPUTE_API_BASE, projectId, templateName);
        }

        HttpHeaders headers = createHeaders(projectId);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return gson.fromJson(response.getBody(), JsonObject.class);
        } catch (Exception e) {
            log.error("Failed to get template {}: {}", templateName, e.getMessage());
            throw new RuntimeException("Failed to get instance template: " + e.getMessage());
        }
    }

    /**
     * Create a new instance template based on existing one with modified scheduling
     * This uses the REST API directly to work around Java SDK limitations
     */
    public void createSpotTemplate(String projectId, String region, String newTemplateName,
                                    JsonObject sourceTemplate, boolean enableSpot) throws IOException {

        // Build new template JSON
        JsonObject newTemplate = new JsonObject();
        newTemplate.addProperty("name", newTemplateName);
        newTemplate.addProperty("description",
            enableSpot ? "Spot template created by XamOps AutoSpotting"
                      : "On-demand template created by XamOps AutoSpotting");

        // Copy properties from source template
        JsonObject sourceProperties = sourceTemplate.getAsJsonObject("properties");
        JsonObject newProperties = cleanProperties(sourceProperties);

        // Update scheduling configuration
        JsonObject scheduling = new JsonObject();
        if (enableSpot) {
            scheduling.addProperty("provisioningModel", "SPOT");
            scheduling.addProperty("preemptible", true);
            scheduling.addProperty("onHostMaintenance", "TERMINATE");
            scheduling.addProperty("automaticRestart", false);
            // NOTE: Do NOT set instanceTerminationAction for MIGs - GCP doesn't support DELETE with MIGs
            // MIGs automatically handle instance lifecycle
        } else {
            scheduling.addProperty("provisioningModel", "STANDARD");
            scheduling.addProperty("preemptible", false);
            scheduling.addProperty("onHostMaintenance", onHostMaintenanceForStandard(sourceProperties));
            scheduling.addProperty("automaticRestart", true);
        }
        newProperties.add("scheduling", scheduling);

        newTemplate.add("properties", newProperties);

        // Determine API endpoint
        String url;
        if (region != null) {
            url = String.format("%s/projects/%s/regions/%s/instanceTemplates",
                    COMPUTE_API_BASE, projectId, region);
        } else {
            url = String.format("%s/projects/%s/global/instanceTemplates",
                    COMPUTE_API_BASE, projectId);
        }

        // Make API call
        HttpHeaders headers = createHeaders(projectId);
        HttpEntity<String> entity = new HttpEntity<>(gson.toJson(newTemplate), headers);

        try {
            log.info("🔧 [GCP REST] Creating template via REST API: {}", url);
            log.debug("Template JSON: {}", gson.toJson(newTemplate));

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            log.info("✅ [GCP REST] Template creation initiated: {}", newTemplateName);

            // Wait for operation to complete
            JsonObject operation = gson.fromJson(response.getBody(), JsonObject.class);
            waitForOperation(projectId, operation);

        } catch (HttpClientErrorException e) {
            log.error("❌ [GCP REST] Failed to create template: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to create template via REST API: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("❌ [GCP REST] Unexpected error: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create template: " + e.getMessage());
        }
    }

    /**
     * Clean properties by removing output-only fields
     */
    private JsonObject cleanProperties(JsonObject sourceProperties) {
        JsonObject cleanedProps = new JsonObject();

        // Copy safe fields
        if (sourceProperties.has("machineType")) {
            cleanedProps.add("machineType", sourceProperties.get("machineType"));
        }

        if (sourceProperties.has("description")) {
            cleanedProps.add("description", sourceProperties.get("description"));
        }

        // Clean disks (remove output-only fields)
        if (sourceProperties.has("disks")) {
            cleanedProps.add("disks", cleanDisks(sourceProperties.getAsJsonArray("disks")));
        }

        // Clean network interfaces (remove output-only fields)
        if (sourceProperties.has("networkInterfaces")) {
            cleanedProps.add("networkInterfaces",
                cleanNetworkInterfaces(sourceProperties.getAsJsonArray("networkInterfaces")));
        }

        // Copy metadata (remove fingerprint)
        if (sourceProperties.has("metadata")) {
            JsonObject metadata = sourceProperties.getAsJsonObject("metadata").deepCopy();
            metadata.remove("fingerprint");
            metadata.remove("kind");
            cleanedProps.add("metadata", metadata);
        }

        // Copy tags (remove fingerprint)
        if (sourceProperties.has("tags")) {
            JsonObject tags = sourceProperties.getAsJsonObject("tags").deepCopy();
            tags.remove("fingerprint");
            cleanedProps.add("tags", tags);
        }

        // Copy service accounts
        if (sourceProperties.has("serviceAccounts")) {
            cleanedProps.add("serviceAccounts", sourceProperties.get("serviceAccounts"));
        }

        // Copy labels
        if (sourceProperties.has("labels")) {
            cleanedProps.add("labels", sourceProperties.get("labels"));
        }

        // Copy attached GPUs. Dropping them would turn a GPU workload into a CPU-only VM.
        if (sourceProperties.has("guestAccelerators")) {
            cleanedProps.add("guestAccelerators", sourceProperties.get("guestAccelerators").deepCopy());
        }

        return cleanedProps;
    }

    /**
     * Host-maintenance policy for a STANDARD (On-Demand) clone of {@code properties}.
     *
     * <p>Google documents that VMs with attached GPUs, bare-metal instances and H4D VMs can't be
     * live-migrated, so they must use {@code TERMINATE}; the accelerator-optimized A and G series
     * attach GPUs automatically, so their templates have no {@code guestAccelerators} entry but
     * the same rule applies. Sending {@code MIGRATE} for any of them makes the template insert
     * fail. Everything else keeps {@code MIGRATE}. Sources (paraphrased):
     * https://docs.cloud.google.com/compute/docs/instances/live-migration-process and
     * https://docs.cloud.google.com/compute/docs/gpus
     */
    static String onHostMaintenanceForStandard(JsonObject properties) {
        if (properties == null) {
            return "MIGRATE";
        }
        if (properties.has("guestAccelerators")
                && properties.get("guestAccelerators").isJsonArray()
                && !properties.getAsJsonArray("guestAccelerators").isEmpty()) {
            return "TERMINATE";
        }
        if (properties.has("machineType") && properties.get("machineType").isJsonPrimitive()) {
            String machineType = properties.get("machineType").getAsString();
            machineType = machineType.substring(machineType.lastIndexOf('/') + 1).toLowerCase();
            for (String series : new String[] {"a2-", "a3-", "a4-", "a4x-", "g2-", "g4-", "h4d-"}) {
                if (machineType.startsWith(series)) {
                    return "TERMINATE";
                }
            }
            if (machineType.endsWith("-metal")) {
                return "TERMINATE";
            }
        }
        return "MIGRATE";
    }

    /**
     * Clean disk array by removing output-only fields
     */
    private com.google.gson.JsonArray cleanDisks(com.google.gson.JsonArray disks) {
        com.google.gson.JsonArray cleanedDisks = new com.google.gson.JsonArray();

        disks.forEach(diskElement -> {
            JsonObject disk = diskElement.getAsJsonObject().deepCopy();

            // Remove output-only fields
            disk.remove("source");
            disk.remove("licenses");
            disk.remove("kind");
            disk.remove("index");
            disk.remove("guestOsFeatures");

            // Clean initializeParams if present
            if (disk.has("initializeParams")) {
                JsonObject initParams = disk.getAsJsonObject("initializeParams");
                initParams.remove("sourceImageId");
            }

            cleanedDisks.add(disk);
        });

        return cleanedDisks;
    }

    /**
     * Clean network interfaces by removing output-only fields
     */
    private com.google.gson.JsonArray cleanNetworkInterfaces(com.google.gson.JsonArray networkInterfaces) {
        com.google.gson.JsonArray cleanedInterfaces = new com.google.gson.JsonArray();

        networkInterfaces.forEach(interfaceElement -> {
            JsonObject networkInterface = interfaceElement.getAsJsonObject().deepCopy();

            // Remove output-only fields
            networkInterface.remove("fingerprint");
            networkInterface.remove("kind");
            networkInterface.remove("ipAddress");
            networkInterface.remove("name");

            // Clean access configs
            if (networkInterface.has("accessConfigs")) {
                com.google.gson.JsonArray accessConfigs = networkInterface.getAsJsonArray("accessConfigs");
                com.google.gson.JsonArray cleanedConfigs = new com.google.gson.JsonArray();

                accessConfigs.forEach(configElement -> {
                    JsonObject config = configElement.getAsJsonObject().deepCopy();
                    config.remove("natIP");
                    config.remove("kind");
                    cleanedConfigs.add(config);
                });

                networkInterface.add("accessConfigs", cleanedConfigs);
            }

            cleanedInterfaces.add(networkInterface);
        });

        return cleanedInterfaces;
    }

    /**
     * Wait for GCP operation to complete
     */
    private void waitForOperation(String projectId, JsonObject operation) throws IOException, InterruptedException {
        if (!operation.has("name")) {
            return; // Already completed
        }

        String operationName = operation.get("name").getAsString();
        String operationUrl;

        if (operation.has("zone")) {
            String zone = operation.get("zone").getAsString();
            zone = zone.substring(zone.lastIndexOf('/') + 1);
            operationUrl = String.format("%s/projects/%s/zones/%s/operations/%s",
                    COMPUTE_API_BASE, projectId, zone, operationName);
        } else if (operation.has("region")) {
            String region = operation.get("region").getAsString();
            region = region.substring(region.lastIndexOf('/') + 1);
            operationUrl = String.format("%s/projects/%s/regions/%s/operations/%s",
                    COMPUTE_API_BASE, projectId, region, operationName);
        } else {
            operationUrl = String.format("%s/projects/%s/global/operations/%s",
                    COMPUTE_API_BASE, projectId, operationName);
        }

        // Poll for completion (max 60 seconds)
        for (int i = 0; i < 30; i++) {
            HttpHeaders headers = createHeaders(projectId);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(operationUrl, HttpMethod.GET, entity, String.class);
            JsonObject op = gson.fromJson(response.getBody(), JsonObject.class);

            String status = op.get("status").getAsString();
            if ("DONE".equals(status)) {
                if (op.has("error")) {
                    throw new RuntimeException("Operation failed: " + op.get("error"));
                }
                log.info("✅ [GCP REST] Operation completed successfully");
                return;
            }

            Thread.sleep(2000); // Wait 2 seconds
        }

        throw new RuntimeException("Operation timed out after 60 seconds");
    }

    /**
     * Update MIG to use new template
     */
    public void setMigTemplate(String projectId, String zone, String migName, String templateUrl)
            throws IOException, InterruptedException {

        String url;
        boolean isZonal = zone.split("-").length == 3;

        if (isZonal) {
            url = String.format("%s/projects/%s/zones/%s/instanceGroupManagers/%s",
                    COMPUTE_API_BASE, projectId, zone, migName);
        } else {
            url = String.format("%s/projects/%s/regions/%s/instanceGroupManagers/%s",
                    COMPUTE_API_BASE, projectId, zone, migName);
        }

        JsonObject body = new JsonObject();
        body.addProperty("instanceTemplate", templateUrl);

        HttpHeaders headers = createHeaders(projectId);
        HttpEntity<String> entity = new HttpEntity<>(gson.toJson(body), headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PATCH, entity, String.class);
            JsonObject operation = gson.fromJson(response.getBody(), JsonObject.class);
            waitForOperation(projectId, operation);
            log.info("✅ [GCP REST] MIG template updated");
        } catch (Exception e) {
            log.error("❌ [GCP REST] Failed to update MIG: {}", e.getMessage());
            throw new RuntimeException("Failed to update MIG: " + e.getMessage());
        }
    }

    /**
     * Point a MIG at {@code templateUrl} and let GCP replace its VMs with a PROACTIVE rolling
     * update that never drops below the target size.
     *
     * <p>Policy:
     * <ul>
     *   <li>Zonal MIG: {@code maxSurge.fixed=1}, {@code maxUnavailable.fixed=0}.</li>
     *   <li>Regional MIG: {@code maxSurge.fixed=numZones}, {@code maxUnavailable.fixed=0}. Google
     *       requires each fixed value on a regional MIG to be 0 or at least the zone count, so
     *       one surge VM per zone is the smallest valid surge. {@code maxUnavailable=0} with a
     *       positive surge means a replacement is running before an old VM is removed, so
     *       capacity is kept. (The previous 0 / numZones policy deleted VMs before their
     *       replacements existed.)</li>
     *   <li>Regional MIG with an unknown zone count ({@code numZones <= 0}): refused with
     *       {@link IllegalStateException} before any request, instead of guessing.</li>
     * </ul>
     * {@code minReadySeconds} is not sent: it is not a field of the v1 updatePolicy.
     *
     * <p>HTTP 409 or 412 from the PATCH means another update is running or the MIG changed
     * underneath us. That is a {@link AutoSpottingBlockedException#gcpUpdateConflict} (the MIG
     * was not changed), never a silent success.
     */
    public void startRollingUpdate(String projectId, String zone, String migName,
                                   String templateUrl, int numZones)
            throws IOException, InterruptedException {

        boolean isZonal = zone.split("-").length == 3;
        if (!isZonal && numZones <= 0) {
            throw new IllegalStateException("Cannot determine the zone count for regional MIG " + migName
                    + "; refusing to start a rollout");
        }

        String url;
        if (isZonal) {
            url = String.format("%s/projects/%s/zones/%s/instanceGroupManagers/%s",
                    COMPUTE_API_BASE, projectId, zone, migName);
        } else {
            url = String.format("%s/projects/%s/regions/%s/instanceGroupManagers/%s",
                    COMPUTE_API_BASE, projectId, zone, migName);
        }

        int surge = isZonal ? 1 : numZones;
        JsonObject updatePolicy = new JsonObject();
        updatePolicy.addProperty("type", "PROACTIVE");

        JsonObject maxSurge = new JsonObject();
        maxSurge.addProperty("fixed", surge);
        updatePolicy.add("maxSurge", maxSurge);

        JsonObject maxUnavailable = new JsonObject();
        maxUnavailable.addProperty("fixed", 0);
        updatePolicy.add("maxUnavailable", maxUnavailable);

        log.info("🔄 [GCP REST] Rolling update: {} MIG {} — maxSurge={} maxUnavailable=0 (capacity kept)",
                isZonal ? "zonal" : "regional (" + numZones + " zones)", migName, surge);

        JsonObject body = new JsonObject();
        body.addProperty("instanceTemplate", templateUrl);
        body.add("updatePolicy", updatePolicy);

        HttpHeaders headers = createHeaders(projectId);
        HttpEntity<String> entity = new HttpEntity<>(gson.toJson(body), headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.PATCH, entity, String.class);
        } catch (HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            if (code == 409 || code == 412) {
                log.warn("⚠️ [GCP REST] Rolling update PATCH on {} refused with {} (update in progress or "
                        + "MIG changed); the MIG was not changed", migName, code);
                throw AutoSpottingBlockedException.gcpUpdateConflict(migName);
            }
            log.error("❌ [GCP REST] Rolling update PATCH failed: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Rolling update PATCH failed (" + e.getStatusCode() + "): " + e.getResponseBodyAsString());
        }

        JsonObject operation = gson.fromJson(response.getBody(), JsonObject.class);
        log.info("✅ [GCP REST] Rolling update operation queued. GCP will replace instances one-by-one.");

        // Wait for the PATCH operation to be accepted (actual instance replacement is async).
        waitForOperation(projectId, operation);

        log.info("✅ [GCP REST] Rolling update accepted. Instances will be replaced gradually with maxUnavailable=0.");
    }

    /**
     * Generic PATCH on a MIG resource (used for updating updatePolicy without changing template).
     */
    public void patchMig(String projectId, String zone, String migName, com.google.gson.JsonObject patchBody)
            throws IOException, InterruptedException {

        boolean isZonal = zone.split("-").length == 3;
        String url;
        if (isZonal) {
            url = String.format("%s/projects/%s/zones/%s/instanceGroupManagers/%s",
                    COMPUTE_API_BASE, projectId, zone, migName);
        } else {
            url = String.format("%s/projects/%s/regions/%s/instanceGroupManagers/%s",
                    COMPUTE_API_BASE, projectId, zone, migName);
        }

        HttpHeaders headers = createHeaders(projectId);
        HttpEntity<String> entity = new HttpEntity<>(gson.toJson(patchBody), headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.PATCH, entity, String.class);
            JsonObject operation = gson.fromJson(response.getBody(), JsonObject.class);
            waitForOperation(projectId, operation);
            log.info("✅ [GCP REST] MIG {} patched successfully", migName);
        } catch (Exception e) {
            log.error("❌ [GCP REST] Failed to patch MIG {}: {}", migName, e.getMessage());
            throw new RuntimeException("Failed to patch MIG: " + e.getMessage());
        }
    }

    /**
     * Check if a template is configured as SPOT/Preemptible by reading the raw REST JSON.
     * This is more reliable than the Java SDK gRPC client which can return empty-string
     * defaults for provisioningModel even when the field is set.
     */
    public boolean isSpotTemplate(String projectId, String region, String templateName) throws IOException {
        // NOTE: Do NOT catch exceptions here — callers rely on exceptions propagating
        // so they can fall back to the Java SDK check if the REST call fails.
        return isSpotTemplateJson(getInstanceTemplate(projectId, region, templateName));
    }

    /**
     * True when the REST template JSON is SPOT ({@code provisioningModel=SPOT}) or legacy
     * preemptible.
     */
    static boolean isSpotTemplateJson(JsonObject template) {
        if (template == null) return false;
        JsonObject properties = template.getAsJsonObject("properties");
        if (properties == null) return false;

        JsonObject scheduling = properties.getAsJsonObject("scheduling");
        if (scheduling == null) return false;

        // Check provisioningModel = "SPOT"
        if (scheduling.has("provisioningModel")) {
            String model = scheduling.get("provisioningModel").getAsString();
            if ("SPOT".equalsIgnoreCase(model)) return true;
        }

        // Also check legacy preemptible flag
        if (scheduling.has("preemptible")) {
            return scheduling.get("preemptible").getAsBoolean();
        }

        return false;
    }
}
