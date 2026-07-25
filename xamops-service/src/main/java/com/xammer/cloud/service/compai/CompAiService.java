package com.xammer.cloud.service.compai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.compai.CloudTestDto;
import com.xammer.cloud.dto.compai.ControlSummaryDto;
import com.xammer.cloud.dto.compai.CreateRiskRequestDto;
import com.xammer.cloud.dto.compai.CreateVendorRequestDto;
import com.xammer.cloud.dto.compai.IntegrationDto;
import com.xammer.cloud.dto.compai.OverviewDto;
import com.xammer.cloud.dto.compai.PersonDto;
import com.xammer.cloud.dto.compai.PolicyDto;
import com.xammer.cloud.dto.compai.RiskDto;
import com.xammer.cloud.dto.compai.TaskDto;
import com.xammer.cloud.dto.compai.VendorDto;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read side of the SOC2 Program tabs. Proxies the self-hosted comp.ai apps/api
 * (see {@link CompAiApiClient}) and normalizes its response shapes into
 * XamOps-owned DTOs so the frontend never depends on comp.ai's raw JSON.
 *
 * comp.ai controls carry no direct status/owner, so {@link #deriveControlStatus}
 * infers a status from linked policy/task state, and category comes from the
 * mapped requirement identifier (e.g. "CC6.1" -> "CC6").
 */
@Service
@Slf4j
public class CompAiService {

    private static final int CACHE_MINUTES = 10;

    private final CompAiApiClient apiClient;
    private final RedisCacheService redisCacheService;

    @Value("${compai.trust-center-url:}")
    private String trustCenterUrl;

    public CompAiService(CompAiApiClient apiClient, RedisCacheService redisCacheService) {
        this.apiClient = apiClient;
        this.redisCacheService = redisCacheService;
    }

    public List<ControlSummaryDto> getControls() {
        if (!apiClient.isConfigured()) return List.of();
        Optional<List<ControlSummaryDto>> cached =
                redisCacheService.get("compai:controls", new TypeReference<List<ControlSummaryDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<ControlSummaryDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/controls?perPage=100");
            for (JsonNode c : body.path("data")) {
                rows.add(mapControl(c));
            }
        } catch (Exception e) {
            log.warn("[CompAi] controls fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put("compai:controls", rows, CACHE_MINUTES);
        return rows;
    }

    private ControlSummaryDto mapControl(JsonNode c) {
        ControlSummaryDto dto = new ControlSummaryDto();
        dto.setId(c.path("id").asText(null));
        dto.setName(c.path("name").asText(null));

        // Category + requirement text from the first mapped requirement.
        JsonNode reqs = c.path("requirementsMapped");
        if (reqs.isArray() && reqs.size() > 0) {
            JsonNode req = reqs.get(0).path("requirement");
            String identifier = req.path("identifier").asText(null);
            dto.setCategory(categoryFromIdentifier(identifier));
            dto.setRequirementName(req.path("name").asText(null));
        }

        // First linked policy.
        JsonNode policies = c.path("policies");
        if (policies.isArray() && policies.size() > 0) {
            dto.setPolicyName(policies.get(0).path("name").asText(null));
            dto.setPolicyStatus(policies.get(0).path("status").asText(null));
        }

        JsonNode tasks = c.path("tasks");
        dto.setTaskCount(tasks.isArray() ? tasks.size() : 0);
        dto.setStatus(deriveControlStatus(policies, tasks));
        return dto;
    }

    /** "CC6.1" -> "CC6"; "C1.1" -> "C1"; null-safe. */
    private String categoryFromIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) return "Other";
        int dot = identifier.indexOf('.');
        return dot > 0 ? identifier.substring(0, dot) : identifier;
    }

    /**
     * comp.ai has no per-control status. Derive one: a published policy means the
     * control is satisfied; any in-progress signal (done/in_progress task, or a
     * policy under review) means in progress; otherwise not started.
     */
    private String deriveControlStatus(JsonNode policies, JsonNode tasks) {
        boolean anyProgress = false;
        if (policies.isArray()) {
            for (JsonNode p : policies) {
                String s = p.path("status").asText("");
                if ("published".equalsIgnoreCase(s)) return "PASSING";
                if ("needs_review".equalsIgnoreCase(s) || "in_review".equalsIgnoreCase(s)) anyProgress = true;
            }
        }
        if (tasks.isArray()) {
            for (JsonNode t : tasks) {
                String s = t.path("status").asText("");
                if ("done".equalsIgnoreCase(s) || "completed".equalsIgnoreCase(s)
                        || "in_progress".equalsIgnoreCase(s)) {
                    anyProgress = true;
                }
            }
        }
        return anyProgress ? "IN_PROGRESS" : "NOT_STARTED";
    }

    public List<PolicyDto> getPolicies() {
        if (!apiClient.isConfigured()) return List.of();
        Optional<List<PolicyDto>> cached =
                redisCacheService.get("compai:policies", new TypeReference<List<PolicyDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<PolicyDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/policies?perPage=100");
            for (JsonNode p : body.path("data")) {
                PolicyDto dto = new PolicyDto();
                dto.setId(p.path("id").asText(null));
                dto.setName(p.path("name").asText(null));
                dto.setDescription(p.path("description").asText(null));
                dto.setStatus(p.path("status").asText(null));
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] policies fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put("compai:policies", rows, CACHE_MINUTES);
        return rows;
    }

    /** Tasks aggregated from the tasks embedded on each control. */
    public List<TaskDto> getTasks() {
        if (!apiClient.isConfigured()) return List.of();
        Optional<List<TaskDto>> cached =
                redisCacheService.get("compai:tasks", new TypeReference<List<TaskDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<TaskDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/controls?perPage=100");
            for (JsonNode c : body.path("data")) {
                String controlName = c.path("name").asText(null);
                for (JsonNode t : c.path("tasks")) {
                    TaskDto dto = new TaskDto();
                    dto.setId(t.path("id").asText(null));
                    dto.setTitle(t.path("title").asText(null));
                    dto.setControlName(controlName);
                    dto.setStatus(t.path("status").asText(null));
                    rows.add(dto);
                }
            }
        } catch (Exception e) {
            log.warn("[CompAi] tasks (from controls) fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put("compai:tasks", rows, CACHE_MINUTES);
        return rows;
    }

    public List<PersonDto> getPersonnel() {
        if (!apiClient.isConfigured()) return List.of();
        Optional<List<PersonDto>> cached =
                redisCacheService.get("compai:people", new TypeReference<List<PersonDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<PersonDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/people?perPage=100");
            for (JsonNode m : body.path("data")) {
                PersonDto dto = new PersonDto();
                dto.setId(m.path("id").asText(null));
                JsonNode user = m.path("user");
                String name = user.path("name").asText("");
                String email = user.path("email").asText(null);
                dto.setName(name != null && !name.isEmpty() ? name : email);
                dto.setEmail(email);
                dto.setRole(m.path("role").asText(null));
                dto.setDepartment(m.path("department").asText(null));
                dto.setJobTitle(m.path("jobTitle").asText(null));
                dto.setActive(m.path("isActive").asBoolean(true) && !m.path("deactivated").asBoolean(false));
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] people fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put("compai:people", rows, CACHE_MINUTES);
        return rows;
    }

    public List<VendorDto> getVendors() {
        if (!apiClient.isConfigured()) return List.of();
        Optional<List<VendorDto>> cached =
                redisCacheService.get("compai:vendors", new TypeReference<List<VendorDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<VendorDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/vendors?perPage=100");
            for (JsonNode v : body.path("data")) {
                VendorDto dto = new VendorDto();
                dto.setId(v.path("id").asText(null));
                dto.setName(v.path("name").asText(null));
                dto.setCategory(v.path("category").asText(null));
                dto.setStatus(v.path("status").asText(null));
                dto.setDescription(v.path("description").asText(null));
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] vendors fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put("compai:vendors", rows, CACHE_MINUTES);
        return rows;
    }

    /** comp.ai risks — GET /v1/risks (may be empty). */
    public List<RiskDto> getRisks() {
        if (!apiClient.isConfigured()) return List.of();
        Optional<List<RiskDto>> cached =
                redisCacheService.get("compai:risks", new TypeReference<List<RiskDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<RiskDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/risks?perPage=100");
            for (JsonNode r : body.path("data")) {
                RiskDto dto = new RiskDto();
                dto.setId(r.path("id").asText(null));
                dto.setTitle(firstNonNull(r, "title", "name"));
                dto.setDescription(r.path("description").asText(null));
                dto.setCategory(r.path("category").asText(null));
                dto.setStatus(r.path("status").asText(null));
                dto.setSeverity(firstNonNull(r, "severity", "residualRisk", "inherentRisk"));
                JsonNode owner = r.path("assignee");
                dto.setOwner(owner.path("name").asText(owner.path("email").asText(null)));
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] risks fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put("compai:risks", rows, CACHE_MINUTES);
        return rows;
    }

    /** comp.ai cloud-security findings ("Cloud Tests") — GET /v1/cloud-security/findings (may be empty). */
    public List<CloudTestDto> getCloudTests() {
        if (!apiClient.isConfigured()) return List.of();
        Optional<List<CloudTestDto>> cached =
                redisCacheService.get("compai:cloudtests", new TypeReference<List<CloudTestDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<CloudTestDto> rows = new ArrayList<>();
        try {
            JsonNode body = apiClient.getJson("/cloud-security/findings?perPage=100");
            for (JsonNode f : body.path("data")) {
                CloudTestDto dto = new CloudTestDto();
                dto.setId(f.path("id").asText(null));
                dto.setTitle(firstNonNull(f, "title", "name", "checkTitle"));
                dto.setSeverity(f.path("severity").asText(null));
                dto.setStatus(f.path("status").asText(null));
                dto.setProvider(firstNonNull(f, "provider", "cloudProvider"));
                dto.setResource(firstNonNull(f, "resource", "resourceId", "resourceName"));
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] cloud-security findings fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put("compai:cloudtests", rows, CACHE_MINUTES);
        return rows;
    }

    /**
     * comp.ai integrations — the provider catalog (GET /v1/integrations/connections/providers,
     * a bare array) annotated with whether the org has connected each (GET
     * /v1/integrations/connections, a bare array).
     */
    public List<IntegrationDto> getIntegrations() {
        if (!apiClient.isConfigured()) return List.of();
        Optional<List<IntegrationDto>> cached =
                redisCacheService.get("compai:integrations", new TypeReference<List<IntegrationDto>>() {});
        if (cached.isPresent()) return cached.get();

        List<IntegrationDto> rows = new ArrayList<>();
        try {
            java.util.Set<String> connectedIds = new java.util.HashSet<>();
            try {
                JsonNode conns = apiClient.getJson("/integrations/connections");
                for (JsonNode c : conns) {
                    String pid = firstNonNull(c, "providerId", "provider", "integrationId", "slug");
                    if (pid != null) connectedIds.add(pid);
                }
            } catch (Exception ignore) { /* empty/optional */ }

            JsonNode providers = apiClient.getJson("/integrations/connections/providers");
            for (JsonNode p : providers) {
                IntegrationDto dto = new IntegrationDto();
                String id = p.path("id").asText(null);
                dto.setId(id);
                dto.setName(p.path("name").asText(null));
                dto.setCategory(p.path("category").asText(null));
                dto.setDescription(p.path("description").asText(null));
                dto.setConnected(id != null && connectedIds.contains(id));
                List<String> caps = new ArrayList<>();
                for (JsonNode c : p.path("capabilities")) caps.add(c.asText());
                dto.setCapabilities(String.join(", ", caps));
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] integrations fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put("compai:integrations", rows, CACHE_MINUTES);
        return rows;
    }

    /** Returns the first non-empty string field among the given keys, else null. */
    private String firstNonNull(JsonNode node, String... keys) {
        for (String k : keys) {
            String v = node.path(k).asText(null);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    public OverviewDto getOverview() {
        List<ControlSummaryDto> controls = getControls();
        List<PolicyDto> policies = getPolicies();
        List<TaskDto> tasks = getTasks();
        List<PersonDto> people = getPersonnel();
        List<VendorDto> vendors = getVendors();

        OverviewDto dto = new OverviewDto();
        dto.setFrameworkName("SOC 2");
        dto.setControlsTotal(controls.size());
        dto.setControlsPassing((int) controls.stream().filter(c -> "PASSING".equals(c.getStatus())).count());
        dto.setControlsInProgress((int) controls.stream().filter(c -> "IN_PROGRESS".equals(c.getStatus())).count());
        dto.setControlsNotStarted((int) controls.stream().filter(c -> "NOT_STARTED".equals(c.getStatus())).count());
        dto.setReadinessPercent(controls.isEmpty() ? 0 : (dto.getControlsPassing() * 100) / controls.size());
        dto.setPoliciesTotal(policies.size());
        dto.setPoliciesPublished((int) policies.stream().filter(p -> "published".equalsIgnoreCase(p.getStatus())).count());
        dto.setTasksTotal(tasks.size());
        dto.setTasksOpen((int) tasks.stream()
                .filter(t -> !"done".equalsIgnoreCase(t.getStatus()) && !"completed".equalsIgnoreCase(t.getStatus()))
                .count());
        dto.setPeopleTotal(people.size());
        dto.setVendorsTotal(vendors.size());
        dto.setRisksTotal(getRisks().size());
        dto.setCloudFindingsTotal(getCloudTests().size());
        dto.setIntegrationsConnected((int) getIntegrations().stream().filter(IntegrationDto::isConnected).count());
        dto.setTrustCenterUrl(trustCenterUrl);
        return dto;
    }

    // ---------------------------------------------------------------------
    // Write actions. Each evicts the Redis cache key(s) it affects so the
    // next read reflects the change immediately, rather than waiting out
    // the 10-minute TTL.
    // ---------------------------------------------------------------------

    /**
     * Publishes a policy's current draft version. comp.ai's endpoint
     * (POST /policies/:id/versions/publish) publishes the latest draft
     * content when no versionId is given.
     */
    public PolicyDto publishPolicy(String policyId) {
        if (!apiClient.isConfigured()) {
            throw new IllegalStateException("comp.ai is not configured (compai.api-key missing)");
        }
        apiClient.postJson("/policies/" + policyId + "/versions/publish", java.util.Map.of());
        redisCacheService.evict("compai:policies");
        redisCacheService.evict("compai:controls"); // control status is derived from policy status
        return getPolicies().stream().filter(p -> policyId.equals(p.getId())).findFirst().orElse(null);
    }

    /** Updates a task's status. status must be one of comp.ai's TaskStatus values
     *  (todo|in_progress|in_review|done|not_relevant|failed) — comp.ai rejects
     *  anything else with a 400, surfaced as-is. */
    public TaskDto updateTaskStatus(String taskId, String status) {
        if (!apiClient.isConfigured()) {
            throw new IllegalStateException("comp.ai is not configured (compai.api-key missing)");
        }
        apiClient.patchJson("/tasks/" + taskId, java.util.Map.of("status", status));
        redisCacheService.evict("compai:tasks");
        redisCacheService.evict("compai:controls"); // control status is derived from task status too
        return getTasks().stream().filter(t -> taskId.equals(t.getId())).findFirst().orElse(null);
    }

    /** Creates a vendor. See {@link CreateVendorRequestDto} for accepted category/status values. */
    public VendorDto createVendor(CreateVendorRequestDto request) {
        if (!apiClient.isConfigured()) {
            throw new IllegalStateException("comp.ai is not configured (compai.api-key missing)");
        }
        JsonNode created = apiClient.postJson("/vendors", request);
        redisCacheService.evict("compai:vendors");
        VendorDto dto = new VendorDto();
        dto.setId(created.path("id").asText(null));
        dto.setName(created.path("name").asText(request.getName()));
        dto.setCategory(created.path("category").asText(request.getCategory()));
        dto.setStatus(created.path("status").asText(request.getStatus()));
        dto.setDescription(created.path("description").asText(request.getDescription()));
        return dto;
    }

    /** Creates a risk. See {@link CreateRiskRequestDto} for accepted category/department values. */
    public RiskDto createRisk(CreateRiskRequestDto request) {
        if (!apiClient.isConfigured()) {
            throw new IllegalStateException("comp.ai is not configured (compai.api-key missing)");
        }
        JsonNode created = apiClient.postJson("/risks", request);
        redisCacheService.evict("compai:risks");
        RiskDto dto = new RiskDto();
        dto.setId(created.path("id").asText(null));
        dto.setTitle(created.path("title").asText(request.getTitle()));
        dto.setDescription(created.path("description").asText(request.getDescription()));
        dto.setCategory(created.path("category").asText(request.getCategory()));
        dto.setStatus(created.path("status").asText(null));
        dto.setSeverity(null);
        dto.setOwner(null);
        return dto;
    }
}
