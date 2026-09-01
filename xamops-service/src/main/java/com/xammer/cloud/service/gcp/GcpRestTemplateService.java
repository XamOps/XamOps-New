package com.xammer.cloud.service.gcp;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xammer.cloud.config.HttpClient5Support;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
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
     * Get access token for API requests
     */
    private String getAccessToken(String projectId) throws IOException {
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
            scheduling.addProperty("onHostMaintenance", "MIGRATE");
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

        return cleanedProps;
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
     * Delete an instance template
     */
    public void deleteTemplate(String projectId, String region, String templateName) throws IOException {
        String url;
        if (region != null) {
            url = String.format("%s/projects/%s/regions/%s/instanceTemplates/%s",
                    COMPUTE_API_BASE, projectId, region, templateName);
        } else {
            url = String.format("%s/projects/%s/global/instanceTemplates/%s",
                    COMPUTE_API_BASE, projectId, templateName);
        }

        try {
            HttpHeaders headers = createHeaders(projectId);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            log.info("🗑️ [GCP REST] Deleting old template: {}", templateName);
            restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
            log.info("✅ [GCP REST] Template deleted: {}", templateName);
        } catch (Exception e) {
            log.warn("⚠️ [GCP REST] Failed to delete template {} (non-critical): {}", templateName, e.getMessage());
        }
    }

    /**
     * Trigger a zero-downtime rolling update on a MIG using GCP's native update policy.
     *
     * Instead of recreateInstances (which kills everything at once), we PATCH the MIG
     * with a PROACTIVE update policy (maxSurge=1, maxUnavailable=0). GCP will then
     * replace instances one-by-one, bringing a new one UP before taking the old one DOWN.
     *
     * This is the equivalent of AWS AutoScaling "RefreshInstances" with
     * minHealthyPercentage=100%.
     */
    public void startRollingUpdate(String projectId, String zone, String migName,
                                   String templateUrl, int numZones)
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

        // ── Update policy ────────────────────────────────────────────────────────
        // GCP rule (all regions): maxSurge.fixed and maxUnavailable.fixed must each be
        // 0 OR >= numZones for regional MIGs. Any value between 1 and numZones-1 → 400.
        //
        // Strategy chosen for AutoSpotting:
        //   Zonal   → maxSurge=1, maxUnavailable=0  (zero-downtime: new VM up before old is down)
        //   Regional → maxSurge=0, maxUnavailable=numZones  (no surge cost; replace 1 row at a time)
        JsonObject updatePolicy = new JsonObject();
        updatePolicy.addProperty("type", "PROACTIVE");
        updatePolicy.addProperty("minReadySeconds", 30);

        if (isZonal) {
            JsonObject maxSurge = new JsonObject();
            maxSurge.addProperty("fixed", 1);
            updatePolicy.add("maxSurge", maxSurge);

            JsonObject maxUnavailable = new JsonObject();
            maxUnavailable.addProperty("fixed", 0);
            updatePolicy.add("maxUnavailable", maxUnavailable);

            log.info("🔄 [GCP REST] Rolling update: zonal MIG {} — maxSurge=1 maxUnavailable=0 (zero-downtime)", migName);
        } else {
            // Regional: maxSurge=0 (always valid), maxUnavailable=numZones (always valid, = 1 per zone)
            int effectiveZones = numZones > 0 ? numZones : 3; // fallback to 3 if unknown
            JsonObject maxSurge = new JsonObject();
            maxSurge.addProperty("fixed", 0);
            updatePolicy.add("maxSurge", maxSurge);

            JsonObject maxUnavailable = new JsonObject();
            maxUnavailable.addProperty("fixed", effectiveZones);
            updatePolicy.add("maxUnavailable", maxUnavailable);

            log.info("🔄 [GCP REST] Rolling update: regional MIG {} ({} zones) — maxSurge=0 maxUnavailable={} (1 row at a time)", migName, effectiveZones, effectiveZones);
        }

        JsonObject body = new JsonObject();
        body.addProperty("instanceTemplate", templateUrl);
        body.add("updatePolicy", updatePolicy);

        HttpHeaders headers = createHeaders(projectId);
        HttpEntity<String> entity = new HttpEntity<>(gson.toJson(body), headers);

        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.PATCH, entity, String.class);
        } catch (HttpClientErrorException e) {
            // 409 means another update is already in progress — that is fine, the template
            // change will still take effect when that update finishes.
            if (e.getStatusCode().value() == 409) {
                log.warn("⚠️ [GCP REST] Rolling update 409 Conflict (another update in progress) — template will apply when current update completes.");
                return;
            }
            log.error("❌ [GCP REST] Rolling update PATCH failed: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Rolling update PATCH failed (" + e.getStatusCode() + "): " + e.getResponseBodyAsString());
        }

        JsonObject operation = gson.fromJson(response.getBody(), JsonObject.class);
        log.info("✅ [GCP REST] Rolling update operation queued. GCP will replace instances one-by-one.");

        // Wait for the PATCH operation to be accepted (actual instance replacement is async).
        waitForOperation(projectId, operation);

        log.info("✅ [GCP REST] Rolling update accepted. Instances will be replaced gradually with minUnavailable=0.");
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
        JsonObject template = getInstanceTemplate(projectId, region, templateName);
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
