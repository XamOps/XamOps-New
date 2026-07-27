package com.xammer.cloud.service.compai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.compai.CloudTestDto;
import com.xammer.cloud.dto.compai.ControlPolicyRefDto;
import com.xammer.cloud.dto.compai.ControlRequirementRefDto;
import com.xammer.cloud.dto.compai.ControlSummaryDto;
import com.xammer.cloud.dto.compai.CreateRiskRequestDto;
import com.xammer.cloud.dto.compai.CreateTaskRequestDto;
import com.xammer.cloud.dto.compai.CreateVendorRequestDto;
import com.xammer.cloud.dto.compai.IntegrationDto;
import com.xammer.cloud.dto.compai.InviteMemberRequestDto;
import com.xammer.cloud.dto.compai.OverviewDto;
import com.xammer.cloud.dto.compai.PersonDto;
import com.xammer.cloud.dto.compai.PolicyDto;
import com.xammer.cloud.dto.compai.RiskDto;
import com.xammer.cloud.dto.compai.TaskDto;
import com.xammer.cloud.dto.compai.UpdatePersonRequestDto;
import com.xammer.cloud.dto.compai.UpdateRiskRequestDto;
import com.xammer.cloud.dto.compai.UpdateTaskRequestDto;
import com.xammer.cloud.dto.compai.UpdateVendorRequestDto;
import com.xammer.cloud.dto.compai.VendorDto;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
        dto.setDescription(c.path("description").asText(null));

        // Category + requirement text from the first mapped requirement; full list for the drawer.
        JsonNode reqs = c.path("requirementsMapped");
        List<ControlRequirementRefDto> requirements = new ArrayList<>();
        if (reqs.isArray() && reqs.size() > 0) {
            JsonNode firstReq = reqs.get(0).path("requirement");
            String identifier = firstReq.path("identifier").asText(null);
            dto.setCategory(categoryFromIdentifier(identifier));
            dto.setRequirementName(firstReq.path("name").asText(null));
            for (JsonNode r : reqs) {
                JsonNode req = r.path("requirement");
                requirements.add(new ControlRequirementRefDto(
                        req.path("identifier").asText(null), req.path("name").asText(null)));
            }
        }
        dto.setRequirements(requirements);

        // First linked policy; full list for the drawer.
        JsonNode policies = c.path("policies");
        List<ControlPolicyRefDto> policyRefs = new ArrayList<>();
        if (policies.isArray() && policies.size() > 0) {
            dto.setPolicyName(policies.get(0).path("name").asText(null));
            dto.setPolicyStatus(policies.get(0).path("status").asText(null));
            for (JsonNode p : policies) {
                policyRefs.add(new ControlPolicyRefDto(
                        p.path("id").asText(null), p.path("name").asText(null), p.path("status").asText(null)));
            }
        }
        dto.setPolicies(policyRefs);

        JsonNode tasksNode = c.path("tasks");
        dto.setTaskCount(tasksNode.isArray() ? tasksNode.size() : 0);
        List<TaskDto> controlTasks = new ArrayList<>();
        if (tasksNode.isArray()) {
            String controlName = dto.getName();
            for (JsonNode t : tasksNode) {
                controlTasks.add(new TaskDto(
                        t.path("id").asText(null), t.path("title").asText(null),
                        controlName, t.path("status").asText(null), null, null));
            }
        }
        dto.setTasks(controlTasks);

        dto.setStatus(deriveControlStatus(policies, tasksNode));
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
                dto.setContent(extractPlainText(p.path("content")));
                rows.add(dto);
            }
        } catch (Exception e) {
            log.warn("[CompAi] policies fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put("compai:policies", rows, CACHE_MINUTES);
        return rows;
    }

    /**
     * Extracts readable plain text from comp.ai's tiptap/prosemirror rich-text
     * JSON (an array wrapping a single "doc" node, or the doc node directly).
     * Not a full renderer — just enough for the read-only policy detail drawer.
     */
    private String extractPlainText(JsonNode contentField) {
        if (contentField == null || contentField.isMissingNode() || contentField.isNull()) return null;
        StringBuilder sb = new StringBuilder();
        if (contentField.isArray()) {
            for (JsonNode docNode : contentField) {
                appendNodeText(docNode, sb);
            }
        } else {
            appendNodeText(contentField, sb);
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private void appendNodeText(JsonNode node, StringBuilder sb) {
        if (node == null || node.isMissingNode()) return;
        if (node.has("text")) {
            sb.append(node.path("text").asText(""));
        }
        JsonNode children = node.path("content");
        if (children.isArray()) {
            for (JsonNode child : children) {
                appendNodeText(child, sb);
            }
        }
        String type = node.path("type").asText("");
        if ("heading".equals(type) || "paragraph".equals(type)) {
            sb.append("\n\n");
        }
    }

    /**
     * Tasks aggregated from the tasks embedded on each control. comp.ai's list
     * endpoints omit assigneeId, so it's resolved with one GET per task id
     * (absorbed by the 10-minute cache) and joined against {@link #getPersonnel()}.
     */
    public List<TaskDto> getTasks() {
        if (!apiClient.isConfigured()) return List.of();
        Optional<List<TaskDto>> cached =
                redisCacheService.get("compai:tasks", new TypeReference<List<TaskDto>>() {});
        if (cached.isPresent()) return cached.get();

        Map<String, String> personNames = getPersonnel().stream()
                .collect(Collectors.toMap(PersonDto::getId, PersonDto::getName, (a, b) -> a));

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
                    enrichAssignee(dto, personNames);
                    rows.add(dto);
                }
            }
        } catch (Exception e) {
            log.warn("[CompAi] tasks (from controls) fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put("compai:tasks", rows, CACHE_MINUTES);
        return rows;
    }

    private void enrichAssignee(TaskDto dto, Map<String, String> personNames) {
        if (dto.getId() == null) return;
        try {
            JsonNode t = apiClient.getJson("/tasks/" + dto.getId());
            String assigneeId = t.path("assigneeId").isNull() ? null : t.path("assigneeId").asText(null);
            dto.setAssigneeId(assigneeId);
            dto.setAssigneeName(assigneeId != null ? personNames.get(assigneeId) : null);
        } catch (Exception e) {
            log.warn("[CompAi] task assignee fetch failed for {}: {}", dto.getId(), e.getMessage());
        }
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
                dto.setWebsite(v.path("website").asText(null));
                dto.setSubProcessor(v.path("isSubProcessor").asBoolean(false));
                dto.setInherentSeverity(deriveSeverity(
                        v.path("inherentProbability").asText(""), v.path("inherentImpact").asText("")));
                dto.setResidualSeverity(deriveSeverity(
                        v.path("residualProbability").asText(""), v.path("residualImpact").asText("")));
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
                dto.setSeverity(deriveSeverity(r.path("likelihood").asText(""), r.path("impact").asText("")));
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

    private static final Map<String, Integer> LIKELIHOOD_SCORES = Map.of(
            "very_unlikely", 1, "unlikely", 2, "possible", 3, "likely", 4, "very_likely", 5);
    private static final Map<String, Integer> IMPACT_SCORES = Map.of(
            "insignificant", 1, "minor", 2, "moderate", 3, "major", 4, "severe", 5);

    /**
     * comp.ai has no direct "severity" field on risks or vendors -- both derive
     * one from a probability/likelihood x impact matrix (see
     * apps/app/src/lib/risk-score.ts upstream). Mirrors that exact scoring so
     * XamOps' severity chips match what comp.ai itself would show.
     */
    private String deriveSeverity(String probability, String impact) {
        Integer p = LIKELIHOOD_SCORES.get(probability);
        Integer i = IMPACT_SCORES.get(impact);
        if (p == null || i == null) return null;
        int raw = p * i;
        if (raw > 16) return "critical";
        if (raw > 9) return "high";
        if (raw > 4) return "medium";
        return "low";
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

    /** Edits a vendor. Unset fields in {@code request} are left unchanged (see {@link UpdateVendorRequestDto}). */
    public VendorDto updateVendor(String vendorId, UpdateVendorRequestDto request) {
        requireConfigured();
        JsonNode updated = apiClient.patchJson("/vendors/" + vendorId, request);
        redisCacheService.evict("compai:vendors");
        VendorDto dto = new VendorDto();
        dto.setId(updated.path("id").asText(vendorId));
        dto.setName(updated.path("name").asText(null));
        dto.setCategory(updated.path("category").asText(null));
        dto.setStatus(updated.path("status").asText(null));
        dto.setDescription(updated.path("description").asText(null));
        return dto;
    }

    public void deleteVendor(String vendorId) {
        requireConfigured();
        apiClient.deleteJson("/vendors/" + vendorId);
        redisCacheService.evict("compai:vendors");
    }

    /** Edits a risk. Unset fields in {@code request} are left unchanged (see {@link UpdateRiskRequestDto}). */
    public RiskDto updateRisk(String riskId, UpdateRiskRequestDto request) {
        requireConfigured();
        JsonNode updated = apiClient.patchJson("/risks/" + riskId, request);
        redisCacheService.evict("compai:risks");
        RiskDto dto = new RiskDto();
        dto.setId(updated.path("id").asText(riskId));
        dto.setTitle(updated.path("title").asText(null));
        dto.setDescription(updated.path("description").asText(null));
        dto.setCategory(updated.path("category").asText(null));
        dto.setStatus(updated.path("status").asText(null));
        return dto;
    }

    public void deleteRisk(String riskId) {
        requireConfigured();
        apiClient.deleteJson("/risks/" + riskId);
        redisCacheService.evict("compai:risks");
    }

    public void deleteControl(String controlId) {
        requireConfigured();
        apiClient.deleteJson("/controls/" + controlId);
        redisCacheService.evict("compai:controls");
        redisCacheService.evict("compai:tasks"); // tasks are aggregated from controls
    }

    /** Archives or unarchives a policy (comp.ai's PATCH /policies/:id accepts isArchived). */
    public PolicyDto setPolicyArchived(String policyId, boolean archived) {
        requireConfigured();
        JsonNode updated = apiClient.patchJson("/policies/" + policyId, java.util.Map.of("isArchived", archived));
        redisCacheService.evict("compai:policies");
        redisCacheService.evict("compai:controls"); // control status is derived from policy status
        PolicyDto dto = new PolicyDto();
        dto.setId(updated.path("id").asText(policyId));
        dto.setName(updated.path("name").asText(null));
        dto.setDescription(updated.path("description").asText(null));
        dto.setStatus(updated.path("status").asText(null));
        return dto;
    }

    /** Creates a task, optionally linked to one or more controls. See {@link CreateTaskRequestDto}. */
    public TaskDto createTask(CreateTaskRequestDto request) {
        requireConfigured();
        JsonNode created = apiClient.postJson("/tasks", request);
        redisCacheService.evict("compai:tasks");
        redisCacheService.evict("compai:controls");
        TaskDto dto = new TaskDto();
        dto.setId(created.path("id").asText(null));
        dto.setTitle(created.path("title").asText(request.getTitle()));
        dto.setStatus(created.path("status").asText("todo"));
        return dto;
    }

    /** Edits a task's title/description/assignee/status. Unset fields are left unchanged (see {@link UpdateTaskRequestDto}). */
    public TaskDto updateTask(String taskId, UpdateTaskRequestDto request) {
        requireConfigured();
        apiClient.patchJson("/tasks/" + taskId, request);
        redisCacheService.evict("compai:tasks");
        redisCacheService.evict("compai:controls"); // control status is derived from task status too
        return getTasks().stream().filter(t -> taskId.equals(t.getId())).findFirst().orElse(null);
    }

    /** Invites a single new member by email with the given comp.ai built-in role (e.g. "admin", "employee"). */
    public void inviteMember(InviteMemberRequestDto request) {
        requireConfigured();
        java.util.Map<String, Object> invite = new java.util.HashMap<>();
        invite.put("email", request.getEmail());
        invite.put("roles", java.util.List.of(request.getRole()));
        apiClient.postJson("/people/invite", java.util.Map.of("invites", java.util.List.of(invite)));
        redisCacheService.evict("compai:people");
    }

    /** Edits a person's role/department, or (de)activates them. See {@link UpdatePersonRequestDto}. */
    public PersonDto updatePerson(String memberId, UpdatePersonRequestDto request) {
        requireConfigured();
        apiClient.patchJson("/people/" + memberId, request);
        redisCacheService.evict("compai:people");
        return getPersonnel().stream().filter(p -> memberId.equals(p.getId())).findFirst().orElse(null);
    }

    public void deletePerson(String memberId) {
        requireConfigured();
        apiClient.deleteJson("/people/" + memberId);
        redisCacheService.evict("compai:people");
    }

    private void requireConfigured() {
        if (!apiClient.isConfigured()) {
            throw new IllegalStateException("comp.ai is not configured (compai.api-key missing)");
        }
    }
}
