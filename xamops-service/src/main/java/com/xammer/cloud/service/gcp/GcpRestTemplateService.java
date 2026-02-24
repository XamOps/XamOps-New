package com.xammer.cloud.service.gcp;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import lombok.extern.slf4j.Slf4j;
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

        // Configure RestTemplate with HttpComponents to support PATCH method
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
        requestFactory.setConnectTimeout(30000);
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
     * Trigger rolling update on MIG to replace existing instances
     * Uses recreateInstances API to force immediate replacement
     */
    public void startRollingUpdate(String projectId, String zone, String migName, String templateUrl)
            throws IOException, InterruptedException {

        String url;
        boolean isZonal = zone.split("-").length == 3;

        // Use recreateInstances endpoint to force immediate replacement
        if (isZonal) {
            url = String.format("%s/projects/%s/zones/%s/instanceGroupManagers/%s/recreateInstances",
                    COMPUTE_API_BASE, projectId, zone, migName);
        } else {
            url = String.format("%s/projects/%s/regions/%s/instanceGroupManagers/%s/recreateInstances",
                    COMPUTE_API_BASE, projectId, zone, migName);
        }

        // Get list of all instances in the MIG to recreate them
        String listUrl;
        if (isZonal) {
            listUrl = String.format("%s/projects/%s/zones/%s/instanceGroupManagers/%s/listManagedInstances",
                    COMPUTE_API_BASE, projectId, zone, migName);
        } else {
            listUrl = String.format("%s/projects/%s/regions/%s/instanceGroupManagers/%s/listManagedInstances",
                    COMPUTE_API_BASE, projectId, zone, migName);
        }

        try {
            log.info("🔄 [GCP REST] Fetching instances to recreate for MIG: {}", migName);

            // Get list of instances
            HttpHeaders headers = createHeaders(projectId);
            HttpEntity<String> listEntity = new HttpEntity<>(headers);
            ResponseEntity<String> listResponse = restTemplate.exchange(listUrl, HttpMethod.POST, listEntity, String.class);
            JsonObject instancesList = gson.fromJson(listResponse.getBody(), JsonObject.class);

            if (!instancesList.has("managedInstances")) {
                log.info("ℹ️ [GCP REST] No instances to replace in MIG");
                return;
            }

            // Build instances array with all instance URLs
            com.google.gson.JsonArray instancesArray = new com.google.gson.JsonArray();
            instancesList.getAsJsonArray("managedInstances").forEach(inst -> {
                JsonObject instance = inst.getAsJsonObject();
                if (instance.has("instance")) {
                    instancesArray.add(instance.get("instance").getAsString());
                }
            });

            if (instancesArray.size() == 0) {
                log.info("ℹ️ [GCP REST] No instances to replace");
                return;
            }

            log.info("🔄 [GCP REST] Found {} instance(s) to recreate with new Spot template", instancesArray.size());

            // Build request body
            JsonObject body = new JsonObject();
            body.add("instances", instancesArray);

            HttpEntity<String> entity = new HttpEntity<>(gson.toJson(body), headers);

            log.info("🔄 [GCP REST] Initiating instance recreation for MIG: {}", migName);
            log.info("🔄 [GCP REST] This will replace instances one-by-one with zero downtime");

            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            JsonObject operation = gson.fromJson(response.getBody(), JsonObject.class);

            log.info("✅ [GCP REST] Instance recreation operation started");

            // Wait for operation to complete
            waitForOperation(projectId, operation);

            log.info("✅ [GCP REST] Instance recreation completed successfully");
            log.info("ℹ️ [GCP REST] All instances have been replaced with Spot VMs");

        } catch (HttpClientErrorException e) {
            // If rolling update fails, log but don't fail the entire operation
            // Template update already succeeded, so new instances will use the new template
            log.warn("⚠️ [GCP REST] Instance recreation failed (template update still successful): {} - {}",
                e.getStatusCode(), e.getResponseBodyAsString());
            log.info("ℹ️ [GCP REST] Template updated successfully. New instances will use the updated template.");
            log.info("ℹ️ [GCP REST] Existing instances will be replaced during auto-scaling or manual recreation.");
        } catch (Exception e) {
            log.warn("⚠️ [GCP REST] Instance recreation failed (template update still successful): {}", e.getMessage());
            log.info("ℹ️ [GCP REST] Template updated successfully. New instances will use the updated template.");
            log.info("ℹ️ [GCP REST] Existing instances will be replaced during auto-scaling or manual recreation.");
        }
    }
}
