package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.*;
import com.xammer.cloud.domain.UpwindInventoryAsset;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.repository.UpwindInventoryAssetRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Read side of XamSecure. Every public method takes the caller's clientId and
 * filters strictly to cloud accounts owned by that client — org-wide Upwind
 * data must never leave this layer unfiltered.
 */
@Service
@Slf4j
public class XamSecureService {

    private static final int SUMMARY_CACHE_MINUTES = 5;
    private static final int APISEC_CACHE_MINUTES = 10;
    /** Cap for the pre-first-sync live endpoint read, which runs inside a user request. */
    private static final int LIVE_APISEC_MAX_ITEMS = 1200;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final CloudAccountRepository cloudAccountRepository;
    private final UpwindThreatDetectionRepository detectionRepo;
    private final UpwindVulnerabilityFindingRepository vulnRepo;
    private final UpwindPostureFindingRepository postureRepo;
    private final UpwindSyncStateRepository syncStateRepo;
    private final UpwindAccountConnectionRepository connectionRepo;
    private final RedisCacheService redisCacheService;
    private final UpwindInventoryAssetRepository inventoryRepo;
    private final UpwindApiEndpointRepository apiEndpointRepo;
    private final UpwindApiClient apiClient;

    /**
     * Cloud accounts whose owner may read org-level Upwind data (the shift-left CI stream).
     *
     * Empty means withheld from everyone. Setter is for tests; see {@link #ownsOrgLevelData}
     * for why this is configuration rather than something derived from the payload.
     */
    @Setter
    @Value("${upwind.shift-left.owner-account-ids:}")
    private String shiftLeftOwnerAccountIds;

    public XamSecureService(CloudAccountRepository cloudAccountRepository,
                            UpwindThreatDetectionRepository detectionRepo,
                            UpwindVulnerabilityFindingRepository vulnRepo,
                            UpwindPostureFindingRepository postureRepo,
                            UpwindSyncStateRepository syncStateRepo,
                            UpwindAccountConnectionRepository connectionRepo,
                            RedisCacheService redisCacheService,
                            UpwindInventoryAssetRepository inventoryRepo,
                            UpwindApiEndpointRepository apiEndpointRepo,
                            UpwindApiClient apiClient) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.detectionRepo = detectionRepo;
        this.vulnRepo = vulnRepo;
        this.postureRepo = postureRepo;
        this.syncStateRepo = syncStateRepo;
        this.connectionRepo = connectionRepo;
        this.redisCacheService = redisCacheService;
        this.inventoryRepo = inventoryRepo;
        this.apiEndpointRepo = apiEndpointRepo;
        this.apiClient = apiClient;
    }

    /**
     * Threat detection policies (org-level), fetched live from Upwind
     * ({@code GET /v1/threat-policies}). Org-wide config — gated on the client having
     * Upwind-connected accounts, like compliance frameworks.
     */
    public List<Map<String, Object>> getThreatPolicies(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty() || !apiClient.isConfigured()) return List.of();
        String cacheKey = "xamsecure:policies:" + clientId;
        Optional<List<Map<String, Object>>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<Map<String, Object>>>() {});
        if (cached.isPresent()) return cached.get();

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (JsonNode p : apiClient.fetchAll("/threat-policies", 10)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.path("id").asText(null));
                m.put("displayName", p.path("display_name").asText(null));
                m.put("category", p.path("category").asText(null));
                m.put("scope", p.path("scope").asText(null));
                m.put("openIssues", p.path("open_issues").asInt(0));
                m.put("managedBy", p.path("managed_by").asText(null));
                m.put("enabled", p.path("enabled").asBoolean(false));
                m.put("kind", "CLASSIC");
                m.put("severity", null);
                m.put("description", null);
                rows.add(m);
            }
        } catch (Exception e) {
            log.warn("[Upwind] threat-policies fetch failed: {}", e.getMessage());
        }

        // The v2 search is a separate set, not a newer version of the list above: Upwind's
        // console shows "Classic Policies" (v1, 49) and "Policies" (v2, 32) side by side and
        // counts 81. Reading only v1 under-reported by the entire v2 set.
        try {
            for (JsonNode p : apiClient.searchThreatPolicies(500)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.path("policy_id").asText(null));
                m.put("displayName", firstNonNull(
                        p.path("metadata").path("detection_title").asText(null),
                        p.path("name").asText(null)));
                // v2 carries no category; source_type is the closest equivalent and is what
                // Upwind groups these by (cloud_logs, sensor, ...).
                m.put("category", prettify(p.path("source_type").asText("")));
                m.put("scope", null);
                m.put("openIssues", 0);
                m.put("managedBy", p.path("creator_id").asText(null));
                m.put("enabled", p.path("is_enabled").asBoolean(false));
                m.put("kind", "POLICY");
                m.put("severity", upper(p.path("severity").asText(null)));
                m.put("description", p.path("metadata").path("detection_description").asText(null));
                rows.add(m);
            }
        } catch (Exception e) {
            log.warn("[Upwind] v2 threat policy merge failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put(cacheKey, rows, APISEC_CACHE_MINUTES);
        return rows;
    }

    /**
     * Response automation workflows (org-level), fetched live from Upwind
     * ({@code GET /v1/workflows}).
     */
    public List<Map<String, Object>> getWorkflows(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty() || !apiClient.isConfigured()) return List.of();
        String cacheKey = "xamsecure:workflows:" + clientId;
        Optional<List<Map<String, Object>>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<Map<String, Object>>>() {});
        if (cached.isPresent()) return cached.get();

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (JsonNode w : apiClient.fetchAll("/workflows", 10)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", w.path("id").asText(null));
                m.put("name", w.path("name").asText(null));
                m.put("type", w.path("type").asText(null));
                m.put("status", w.path("status").asText(null));
                m.put("lastExecutionTime", w.path("last_execution_time").asText(null));
                m.put("cron", w.path("config").path("cron").asText(null));
                List<String> actions = new ArrayList<>();
                w.path("config").path("actions").forEach(a -> actions.add(a.path("type").asText()));
                m.put("actions", actions);
                // Action audience and report type live deeper in config; the table can't show
                // them but the detail panel can, so pass them through rather than dropping them.
                List<Map<String, Object>> actionDetails = new ArrayList<>();
                w.path("config").path("actions").forEach(a -> {
                    Map<String, Object> ad = new LinkedHashMap<>();
                    ad.put("type", textOrNull(a, "type"));
                    ad.put("targetAudience", textOrNull(a, "target_audience"));
                    actionDetails.add(ad);
                });
                m.put("actionDetails", actionDetails);
                m.put("reportType", textOrNull(w.path("config").path("config"), "type"));
                rows.add(m);
            }
        } catch (Exception e) {
            log.warn("[Upwind] workflows fetch failed: {}", e.getMessage());
        }
        if (!rows.isEmpty()) redisCacheService.put(cacheKey, rows, APISEC_CACHE_MINUTES);
        return rows;
    }

    /**
     * Software components (SBOM) for the tenant's accounts, fetched live from Upwind
     * ({@code GET /v1/sbom-packages}) and cached briefly. Tenant-filtered via the
     * cloud-account-id query param; defence-in-depth post-filter on cloud_account_id.
     */
    public List<Map<String, Object>> getComponents(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty() || !apiClient.isConfigured()) return List.of();
        String cacheKey = "xamsecure:components:" + clientId + ":" + (accountId == null ? "all" : accountId);
        Optional<List<Map<String, Object>>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<Map<String, Object>>>() {});
        if (cached.isPresent()) return cached.get();

        Set<String> allowed = new HashSet<>(ids);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : ids) {
            try {
                for (JsonNode p : apiClient.fetchSbomPackages(id, 50)) {
                    String acct = p.path("cloud_account_id").asText(null);
                    if (acct != null && !allowed.contains(acct)) continue; // defence in depth
                    JsonNode vs = p.path("vulnerabilities_summary");
                    JsonNode rs = p.path("resources_summary");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.path("name").asText(null));
                    m.put("version", p.path("version").asText(null));
                    m.put("packageManager", p.path("package_manager").asText(null));
                    m.put("framework", p.path("framework").asText(null));
                    List<String> licenses = new ArrayList<>();
                    p.path("licenses").forEach(l -> licenses.add(l.asText()));
                    m.put("licenses", licenses);
                    m.put("cloudProvider", p.path("cloud_provider").asText(null));
                    m.put("cloudAccountId", acct != null ? acct : id);
                    m.put("critical", vs.path("critical_count").asInt(0));
                    m.put("high", vs.path("high_count").asInt(0));
                    m.put("medium", vs.path("medium_count").asInt(0));
                    m.put("low", vs.path("low_count").asInt(0));
                    m.put("totalVulns", vs.path("total_count").asInt(0));
                    m.put("inUse", rs.path("in_use_count").asInt(0));
                    m.put("resourceCount", rs.path("total_count").asInt(0));
                    m.put("affectedImages", p.path("images_summary").path("affected_count").asInt(0));
                    rows.add(m);
                }
            } catch (Exception e) {
                log.warn("[Upwind] sbom fetch failed for account {}: {}", id, e.getMessage());
            }
        }
        // Highest-risk components first (most vulns, then in-use).
        rows.sort(Comparator
                .comparingInt((Map<String, Object> m) -> -((Integer) m.getOrDefault("totalVulns", 0)))
                .thenComparingInt(m -> -((Integer) m.getOrDefault("inUse", 0))));
        // Never cache an empty result — a transient fetch failure or a cold start before creds
        // resolve would otherwise poison the cache and keep the Components tab empty for the TTL.
        if (!rows.isEmpty()) redisCacheService.put(cacheKey, rows, APISEC_CACHE_MINUTES);
        return rows;
    }

    /**
     * APIs tab data. Reads the locally synced endpoints so the request returns immediately —
     * walking Upwind inline blocked the tab for ~20s on every cache miss.
     *
     * Falls back to a live fetch only when nothing has been synced yet (fresh deploy before
     * the first full sync), so the tab isn't empty in the interim.
     */
    public List<Map<String, Object>> getApiSecurity(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return List.of();

        List<UpwindApiEndpoint> synced =
                apiEndpointRepo.findByCloudAccountIdInAndStatusOrderByUriAsc(ids, "ACTIVE");
        if (!synced.isEmpty()) {
            return synced.stream().map(this::toApiEndpointRow).collect(Collectors.toList());
        }
        return fetchApiSecurityLive(clientId, accountId, ids);
    }

    private Map<String, Object> toApiEndpointRow(UpwindApiEndpoint e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getUpwindId());
        m.put("method", e.getMethod());
        m.put("uri", e.getUri());
        m.put("resourceId", e.getResourceId());
        m.put("firstSeenTime", e.getFirstSeenTime() == null ? null : e.getFirstSeenTime().toString());
        m.put("lastSeenTime", e.getLastSeenTime() == null ? null : e.getLastSeenTime().toString());
        m.put("internetExposed", e.isInternetExposed());
        m.put("authState", e.getAuthState());
        m.put("authenticated", "AUTHENTICATED".equalsIgnoreCase(e.getAuthState()));
        m.put("domains", e.getDomains() == null || e.getDomains().isEmpty()
                ? List.of() : Arrays.asList(e.getDomains().split(",")));
        m.put("cloudAccountId", e.getCloudAccountId());
        m.put("sensitiveData", splitCsv(e.getSensitiveData()));
        m.put("hasSensitiveData", Boolean.TRUE.equals(e.getHasSensitiveData()));
        m.put("statusCodes", splitCsv(e.getStatusCodes()));
        return m;
    }

    private static List<String> splitCsv(String csv) {
        return csv == null || csv.isEmpty() ? List.of() : Arrays.asList(csv.split(","));
    }

    /** Pre-first-sync fallback: the original live read-through, still Redis-cached. */
    private List<Map<String, Object>> fetchApiSecurityLive(Long clientId, String accountId, List<String> ids) {
        if (!apiClient.isConfigured()) return List.of();
        String cacheKey = "xamsecure:apisec:" + clientId + ":" + (accountId == null ? "all" : accountId);
        Optional<List<Map<String, Object>>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<Map<String, Object>>>() {});
        if (cached.isPresent()) return cached.get();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : ids) {
            try {
                // Capped well below the sync's ceiling: this path runs inside a user request.
                for (JsonNode ep : apiClient.searchApiEndpoints(id, LIVE_APISEC_MAX_ITEMS).items()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", ep.path("id").asText(null));
                    m.put("method", ep.path("method").asText(null));
                    m.put("uri", ep.path("uri").asText(null));
                    m.put("resourceId", ep.path("resource_id").asText(null));
                    m.put("firstSeenTime", ep.path("first_seen_time").asText(null));
                    m.put("lastSeenTime", ep.path("last_seen_time").asText(null));
                    m.put("internetExposed", ep.path("risk_overview").path("internet_exposure")
                            .path("ingress").has("last_seen_time"));
                    // Authentication state from Upwind's risk overview (AUTHENTICATED / UNAUTHENTICATED
                    // / absent when not analysed). Only an explicit UNAUTHENTICATED counts as unauthenticated.
                    String authState = ep.path("risk_overview").path("authentication").path("state").asText(null);
                    m.put("authState", authState);
                    m.put("authenticated", "AUTHENTICATED".equalsIgnoreCase(authState));
                    List<String> domains = new ArrayList<>();
                    ep.path("domains").forEach(d -> domains.add(d.asText()));
                    m.put("domains", domains);
                    m.put("cloudAccountId", id);
                    List<String> sensitive = new ArrayList<>();
                    for (JsonNode s : ep.path("risk_overview").path("sensitive_data")) {
                        String category = s.path("category").asText(null);
                        String type = s.path("type").asText(null);
                        if (category == null && type == null) continue;
                        sensitive.add(category == null ? type
                                : (type == null ? category : category + ":" + type));
                    }
                    m.put("sensitiveData", sensitive);
                    m.put("hasSensitiveData", !sensitive.isEmpty());
                    List<String> codes = new ArrayList<>();
                    ep.path("status_codes").forEach(c -> codes.add(c.asText()));
                    m.put("statusCodes", codes);
                    rows.add(m);
                }
            } catch (Exception e) {
                log.warn("[Upwind] apisec fetch failed for account {}: {}", id, e.getMessage());
            }
        }
        if (!rows.isEmpty()) redisCacheService.put(cacheKey, rows, APISEC_CACHE_MINUTES);
        return rows;
    }

    /**
     * Everything XamSecure knows about a single resource, joined across the synced domains
     * on {@code resource_id}: its inventory identity, failing configuration checks, open
     * CVEs, threat detections, and served API endpoints.
     *
     * This is the join the UI was missing. Each tab already stored resource_id, but nothing
     * connected them, so a user could see a failing check without seeing that the same
     * machine also had exploitable CVEs and live detections.
     */
    public Map<String, Object> getResourceDetail(Long clientId, String accountId, String resourceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty() || resourceId == null || resourceId.isBlank()) return out;

        List<UpwindPostureFinding> posture = postureRepo.findFailingByResource(ids, resourceId);
        List<UpwindVulnerabilityFinding> vulns = vulnRepo.findOpenByResource(ids, resourceId);
        List<UpwindThreatDetection> detections = detectionRepo.findByResource(ids, resourceId);
        // NOTE: API endpoints are deliberately NOT joined here. Upwind stamps endpoints with an
        // internal API-resource id (ep-…), not the cloud resource id that findings use, so the
        // join can never match. Surfacing it would show a permanent zero. Revisit if Upwind
        // exposes a cloud resource reference on apisecurity-endpoints.

        // Identity: prefer the inventory asset, else fall back to whichever finding we have.
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("resourceId", resourceId);
        inventoryRepo.findByCloudResourceId(resourceId).ifPresentOrElse(a -> {
            identity.put("name", a.getName());
            identity.put("resourceType", a.getLabel());
            identity.put("assetCategory", a.getAssetCategory());
            identity.put("cloudProvider", a.getCloudProvider());
            identity.put("cloudAccountId", a.getCloudAccountId());
            identity.put("region", a.getRegion());
        }, () -> {
            if (!posture.isEmpty()) {
                UpwindPostureFinding f = posture.get(0);
                identity.put("name", f.getResourceName());
                identity.put("resourceType", f.getResourceType());
                identity.put("cloudProvider", f.getCloudProvider());
                identity.put("cloudAccountId", f.getCloudAccountId());
                identity.put("region", f.getRegion());
            } else if (!vulns.isEmpty()) {
                UpwindVulnerabilityFinding v = vulns.get(0);
                identity.put("name", v.getResourceName());
                identity.put("resourceType", v.getResourceType());
                identity.put("cloudProvider", v.getCloudProvider());
                identity.put("cloudAccountId", v.getCloudAccountId());
                identity.put("region", v.getRegion());
            } else if (!detections.isEmpty()) {
                UpwindThreatDetection d = detections.get(0);
                identity.put("name", d.getResourceName());
                identity.put("resourceType", d.getResourceType());
                identity.put("cloudProvider", d.getCloudProvider());
                identity.put("cloudAccountId", d.getCloudAccountId());
            }
        });

        out.put("identity", identity);
        out.put("counts", Map.of(
                "configurations", posture.size(),
                "vulnerabilities", vulns.size(),
                "detections", detections.size()));
        out.put("topSeverity", highestSeverity(posture, vulns, detections));

        out.put("configurations", posture.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("check", f.getCheckTitle() != null ? f.getCheckTitle() : f.getTitle());
            m.put("severity", f.getSeverity());
            m.put("framework", f.getFrameworkTitle());
            m.put("lastSeenTime", f.getLastSeenTime());
            return m;
        }).collect(Collectors.toList()));

        out.put("vulnerabilities", vulns.stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cveId", v.getCveId());
            m.put("name", v.getVulnerabilityName());
            m.put("severity", v.getSeverity());
            m.put("cvssScore", v.getCvssScore());
            m.put("epssScore", v.getEpssScore());
            m.put("exploitable", v.getExploitable());
            m.put("lastScanTime", v.getLastScanTime());
            return m;
        }).collect(Collectors.toList()));

        out.put("detections", detections.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("upwindId", d.getUpwindId());
            m.put("title", d.getTitle());
            m.put("severity", d.getSeverity());
            m.put("status", d.getStatus());
            m.put("mitreTactics", d.getMitreTactics());
            m.put("lastSeenTime", d.getLastSeenTime());
            return m;
        }).collect(Collectors.toList()));

        return out;
    }

    /** Worst severity across every domain touching the resource, for the drawer header. */
    private static String highestSeverity(List<UpwindPostureFinding> posture,
                                         List<UpwindVulnerabilityFinding> vulns,
                                         List<UpwindThreatDetection> detections) {
        String best = null;
        for (UpwindPostureFinding f : posture) best = worse(best, f.getSeverity());
        for (UpwindVulnerabilityFinding v : vulns) best = worse(best, v.getSeverity());
        for (UpwindThreatDetection d : detections) best = worse(best, d.getSeverity());
        return best;
    }

    private static String worse(String current, String candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate.toUpperCase();
        int ci = SEVERITY_ORDER.indexOf(candidate.toUpperCase());
        int cu = SEVERITY_ORDER.indexOf(current.toUpperCase());
        if (ci < 0) return current;
        if (cu < 0 || ci < cu) return candidate.toUpperCase();
        return current;
    }

    /**
     * Identities view, built from the inventory catalog's identity assets and annotated
     * with failing configuration checks.
     *
     * Upwind's dedicated Identities module (CIEM) genuinely does not exist on this API:
     * seven candidate paths across v1 and v2 all return 404, and
     * {@code access-management/*} is Upwind CONSOLE user management, not cloud identities.
     * So entitlement analysis, the human vs non-human split and the role-trust graph remain
     * unavailable.
     *
     * The ROSTER, though, is real. It was previously inferred from whichever identities
     * happened to be failing a check, on the basis that inventory exposed only one identity
     * asset — a claim that held for the flat {@code /inventory/assets/search} we used to
     * call. The catalog endpoint returns 1,076 identity assets across our accounts (IAM
     * users, roles, groups, policies, and Kubernetes service accounts, roles and bindings),
     * so this now lists every identity Upwind sees, with findings and Upwind's own
     * {@code high_privilege_risk.has_elevated_privilege} flag layered on top.
     */
    public Map<String, Object> getIdentities(Long clientId, String accountId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("identities", List.of());
        out.put("severityCounts", Map.of());
        out.put("byType", List.of());
        out.put("total", 0);
        out.put("withFindings", 0);
        out.put("privilegedTotal", 0);
        out.put("privileged", List.of());

        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return out;

        List<UpwindInventoryAsset> identityAssets = inventoryRepo.findIdentityAssets(ids);
        // Privilege spans every category, not just identities: an EC2 instance carrying a
        // highly-privileged role is exactly as interesting as the role itself.
        List<UpwindInventoryAsset> privilegedAssets = inventoryRepo.findElevatedPrivilegeAssets(ids);
        Set<String> privilegedResourceIds = privilegedAssets.stream()
                .map(UpwindInventoryAsset::getCloudResourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Failing checks for exactly those identities. Driven off the catalog's resource ids
        // rather than a resource-type LIKE guess, which cannot catch Kubernetes bindings.
        Map<String, IdentityAgg> findingsByResource = new LinkedHashMap<>();
        List<String> resourceIds = identityAssets.stream()
                .map(UpwindInventoryAsset::getCloudResourceId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        for (List<String> chunk : chunked(resourceIds, RESOURCE_ID_CHUNK)) {
            for (Object[] r : postureRepo.findFailingByResourceIds(ids, chunk)) {
                String resourceId = (String) r[0];
                if (resourceId == null) continue;
                IdentityAgg a = findingsByResource.computeIfAbsent(resourceId, k -> new IdentityAgg());
                String check = (String) r[1];
                a.checks.add(check != null ? check : (String) r[2]);
                a.severity = worse(a.severity, (String) r[3]);
                a.lastSeen = latest(a.lastSeen, toInstant(r[4]));
                a.firstSeen = earliest(a.firstSeen, toInstant(r[5]));
            }
        }

        // Identities that fail a check but are absent from the catalog (e.g. deleted since
        // the last inventory walk). Kept so the roster never loses a live finding.
        Map<String, IdentityAgg> orphans = new LinkedHashMap<>();
        Set<String> known = new HashSet<>(resourceIds);
        for (Object[] r : postureRepo.findIdentityFailings(ids)) {
            String resourceId = (String) r[0];
            String key = resourceId != null ? resourceId : (String) r[1];
            if (key == null || known.contains(key)) continue;
            IdentityAgg a = orphans.computeIfAbsent(key, k -> new IdentityAgg());
            if (a.resourceId == null) {
                a.resourceId = resourceId;
                a.name = (String) r[1];
                a.resourceType = (String) r[2];
                a.cloudProvider = (String) r[6];
                a.cloudAccountId = (String) r[7];
            }
            String check = (String) r[3];
            a.checks.add(check != null ? check : (String) r[4]);
            a.severity = worse(a.severity, (String) r[5]);
            a.lastSeen = latest(a.lastSeen, toInstant(r[8]));
            a.firstSeen = earliest(a.firstSeen, toInstant(r[9]));
        }

        List<Map<String, Object>> identities = new ArrayList<>();
        for (UpwindInventoryAsset a : identityAssets) {
            IdentityAgg f = a.getCloudResourceId() == null
                    ? null : findingsByResource.get(a.getCloudResourceId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resourceId", a.getCloudResourceId());
            m.put("name", a.getName());
            m.put("resourceType", a.getLabel());
            m.put("cloudProvider", a.getCloudProvider());
            m.put("cloudAccountId", a.getCloudAccountId());
            m.put("region", a.getRegion());
            m.put("elevatedPrivilege", Boolean.TRUE.equals(a.getHasElevatedPrivilege()));
            m.put("severity", f == null ? null : f.severity);
            m.put("findingCount", f == null ? 0 : f.checks.size());
            m.put("checks", f == null ? List.of() : new ArrayList<>(f.checks));
            m.put("firstSeen", f == null ? null : f.firstSeen);
            m.put("lastSeen", f == null ? null : f.lastSeen);
            m.put("inInventory", true);
            identities.add(m);
        }
        for (IdentityAgg a : orphans.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resourceId", a.resourceId);
            m.put("name", a.name);
            m.put("resourceType", a.resourceType);
            m.put("cloudProvider", a.cloudProvider);
            m.put("cloudAccountId", a.cloudAccountId);
            m.put("region", null);
            m.put("elevatedPrivilege",
                    a.resourceId != null && privilegedResourceIds.contains(a.resourceId));
            m.put("severity", a.severity);
            m.put("findingCount", a.checks.size());
            m.put("checks", new ArrayList<>(a.checks));
            m.put("firstSeen", a.firstSeen);
            m.put("lastSeen", a.lastSeen);
            m.put("inInventory", false);
            identities.add(m);
        }

        // Worst severity first, then most failing checks, then privileged, then by name —
        // so the rows that need attention lead and clean identities sort last.
        identities.sort(Comparator
                .comparingInt((Map<String, Object> m) -> {
                    Object sev = m.get("severity");
                    int i = sev == null ? -1 : SEVERITY_ORDER.indexOf(sev.toString());
                    return i < 0 ? Integer.MAX_VALUE : i;
                })
                .thenComparing(m -> -((Integer) m.get("findingCount")))
                .thenComparing(m -> Boolean.TRUE.equals(m.get("elevatedPrivilege")) ? 0 : 1)
                .thenComparing(m -> String.valueOf(m.get("name") == null ? "" : m.get("name"))));

        Map<String, Long> severityCounts = new LinkedHashMap<>();
        for (String sev : SEVERITY_ORDER) {
            long n = identities.stream().filter(m -> sev.equals(m.get("severity"))).count();
            if (n > 0) severityCounts.put(sev, n);
        }

        Map<String, Long> typeCounts = new LinkedHashMap<>();
        for (Map<String, Object> m : identities) {
            Object t = m.get("resourceType");
            typeCounts.merge(t == null ? "other" : t.toString(), 1L, Long::sum);
        }
        List<Map<String, Object>> byType = typeCounts.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        // Every elevated-privilege asset, across all categories. An asset can hold elevated
        // privilege while passing every check — precisely the case worth surfacing.
        List<Map<String, Object>> privileged = privilegedAssets.stream()
                .map(a -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("resourceId", a.getCloudResourceId());
                    p.put("name", a.getName());
                    p.put("resourceType", a.getLabel());
                    p.put("category", a.getAssetCategory());
                    p.put("cloudProvider", a.getCloudProvider());
                    p.put("cloudAccountId", a.getCloudAccountId());
                    p.put("region", a.getRegion());
                    p.put("internetExposureType", a.getInternetExposureType());
                    p.put("sensitivePii", Boolean.TRUE.equals(a.getSensitivePiiAtRest())
                            || Boolean.TRUE.equals(a.getSensitivePiiInTransit()));
                    p.put("hasFindings", a.getCloudResourceId() != null
                            && findingsByResource.containsKey(a.getCloudResourceId()));
                    return p;
                })
                .collect(Collectors.toList());

        out.put("identities", identities);
        out.put("severityCounts", severityCounts);
        out.put("byType", byType);
        out.put("total", identities.size());
        out.put("withFindings",
                (int) identities.stream().filter(m -> ((Integer) m.get("findingCount")) > 0).count());
        out.put("privilegedTotal", privileged.size());
        out.put("privileged", privileged);
        return out;
    }

    /** Keeps IN-clause sizes sane when annotating a large identity roster. */
    private static final int RESOURCE_ID_CHUNK = 500;

    private static <T> List<List<T>> chunked(List<T> src, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < src.size(); i += size) {
            out.add(src.subList(i, Math.min(src.size(), i + size)));
        }
        return out;
    }

    /** Mutable per-identity aggregate for the Identities view. */
    private static final class IdentityAgg {
        String resourceId, name, resourceType, cloudProvider, cloudAccountId, severity;
        final Set<String> checks = new LinkedHashSet<>();
        Instant firstSeen, lastSeen;
    }

    /** Domains the full/incremental syncs report state for, in display order. */
    private static final List<String> SYNC_DOMAINS = List.of(
            "DETECTIONS", "VULNERABILITIES", "POSTURE", "INVENTORY", "API_ENDPOINTS");

    /**
     * Per-domain sync freshness for the UI. Without this, a stalled or failing sync is
     * invisible in the product — the tabs simply render older data with no indication.
     *
     * Staleness thresholds are generous multiples of each cadence (detections poll every
     * 60s, the rest reconcile hourly) so normal jitter isn't flagged.
     */
    public Map<String, Object> getSyncStatus() {
        Instant now = Instant.now();
        List<Map<String, Object>> domains = new ArrayList<>();
        Instant oldest = null;
        boolean anyStale = false, anyError = false;

        for (String domain : SYNC_DOMAINS) {
            UpwindSyncState s = syncStateRepo.findByDomain(domain).orElse(null);
            Instant lastSuccess = s == null ? null : s.getLastSuccessAt();
            long staleAfter = "DETECTIONS".equals(domain) ? 600 : 10_800; // 10 min / 3 h
            Long ageSeconds = lastSuccess == null ? null
                    : Math.max(0, now.getEpochSecond() - lastSuccess.getEpochSecond());
            boolean stale = ageSeconds == null || ageSeconds > staleAfter;
            // An error only matters while it's the most recent outcome for that domain.
            boolean failing = false;
            String lastError = null;
            if (s != null && s.getLastErrorAt() != null
                    && (lastSuccess == null || s.getLastErrorAt().isAfter(lastSuccess))) {
                failing = true;
                lastError = s.getLastError();
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", domain);
            m.put("lastSuccessAt", lastSuccess == null ? null : lastSuccess.toString());
            m.put("ageSeconds", ageSeconds);
            m.put("stale", stale);
            m.put("failing", failing);
            m.put("lastError", lastError);
            domains.add(m);

            anyStale |= stale;
            anyError |= failing;
            if (lastSuccess != null && (oldest == null || lastSuccess.isBefore(oldest))) {
                oldest = lastSuccess;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", anyError ? "ERROR" : anyStale ? "STALE" : "OK");
        out.put("oldestSuccessAt", oldest == null ? null : oldest.toString());
        out.put("serverTime", now.toString());
        out.put("domains", domains);
        return out;
    }

    /**
     * Live threat-stories from Upwind (no local table — stories are a runtime concept).
     * Tenant-filtered by the caller's owned cloud accounts. Returns the active-story
     * count (for the Overview funnel) plus a small list of the newest stories.
     * Degrades gracefully to an empty summary if Upwind is unreachable/unconfigured.
     */
    public Map<String, Object> getThreatStories(Long clientId, String accountId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("activeCount", 0);
        summary.put("stories", List.of());

        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty() || !apiClient.isConfigured()) return summary;

        String cacheKey = "xamsecure:stories:" + clientId + ":" + (accountId == null ? "all" : accountId);
        Optional<Map<String, Object>> cached =
                redisCacheService.get(cacheKey, new TypeReference<Map<String, Object>>() {});
        if (cached.isPresent()) return cached.get();

        try {
            // The threat-stories search only supports severity/status/create_time/update_time
            // as filter fields — a cloud_account_id condition is rejected with HTTP 400. Match
            // all severities; tenancy is established below from correlated detections.
            List<Map<String, Object>> conditions = List.of(
                    Map.of("field", "severity", "operator", "in",
                            "value", List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")));

            List<JsonNode> raw = new ArrayList<>();
            Set<String> allDetectionIds = new LinkedHashSet<>();
            for (JsonNode s : apiClient.searchThreatStories(conditions, 100)) {
                raw.add(s);
                s.path("detection_ids").forEach(d -> allDetectionIds.add(d.asText()));
            }

            // Ownership comes from correlated detections, exactly as getThreatStoryDetail does.
            // Filtering on resource.cloud_account_id here was inert: stories carry no account id
            // at all, so the condition never matched and every org story reached every client —
            // while the detail endpoint correctly withheld it. That left a story listed and then
            // unopenable, and leaked other tenants' story titles, which name their namespaces.
            // One bulk query for all stories rather than one per story.
            Set<String> ourDetectionIds = allDetectionIds.isEmpty() ? Set.of()
                    : detectionRepo.findByAccountsAndUpwindIds(ids, new ArrayList<>(allDetectionIds))
                            .stream().map(UpwindThreatDetection::getUpwindId).collect(Collectors.toSet());

            List<Map<String, Object>> stories = new ArrayList<>();
            int active = 0;
            for (JsonNode s : raw) {
                List<String> detectionIds = new ArrayList<>();
                s.path("detection_ids").forEach(d -> detectionIds.add(d.asText()));
                long ours = detectionIds.stream().filter(ourDetectionIds::contains).count();
                if (ours == 0) continue;

                String status = s.path("status").asText(null);
                if (isActiveStory(status)) active++;
                if (stories.size() < 50) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.path("id").asText(null));
                    m.put("title", firstNonNull(s.path("title").asText(null), s.path("name").asText(null)));
                    m.put("severity", upper(s.path("severity").asText(null)));
                    m.put("status", status);
                    m.put("resourceName", s.path("resource").path("name").asText(null));
                    m.put("createTime", s.path("create_time").asText(null));
                    m.put("updateTime", s.path("update_time").asText(null));
                    // What the story references vs. how much of it we hold for these accounts.
                    m.put("relatedEvents", detectionIds.size());
                    m.put("resolvedCount", ours);
                    stories.add(m);
                }
            }
            summary.put("activeCount", active);
            summary.put("stories", stories);
        } catch (Exception e) {
            log.warn("[Upwind] threat-stories fetch failed for client {}: {}", clientId, e.getMessage());
            return summary;
        }
        redisCacheService.put(cacheKey, summary, SUMMARY_CACHE_MINUTES);
        return summary;
    }

    /**
     * One threat story in full, for the detail panel: Upwind's incident narrative plus the
     * detections it correlates, the MITRE tactics/techniques across them, and the affected
     * resources.
     *
     * Tenancy comes from the DETECTIONS, not the story. Story payloads carry no
     * cloud_account_id at all (verified live 2026-08-04: the only keys are id/title/severity/
     * status/create_time/update_time/detection_ids), so the previous
     * {@code story.resource.cloud_account_id} check could never match and every client saw
     * every story in the org. A story is the caller's only if it references at least one
     * detection in one of their accounts.
     */
    public Map<String, Object> getThreatStoryDetail(Long clientId, String accountId, String storyId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty() || storyId == null || storyId.isBlank() || !apiClient.isConfigured()) {
            return Map.of();
        }

        JsonNode story;
        try {
            story = apiClient.getThreatStory(storyId);
        } catch (Exception e) {
            log.warn("[Upwind] story {} fetch failed: {}", storyId, e.getMessage());
            return Map.of();
        }

        List<String> detectionIds = new ArrayList<>();
        story.path("detection_ids").forEach(d -> detectionIds.add(d.asText()));

        // Resolve against the synced table: it is already tenant-scoped, and avoids N API
        // calls for a story that can reference dozens of detections.
        List<UpwindThreatDetection> detections = detectionIds.isEmpty()
                ? List.of()
                : detectionRepo.findByAccountsAndUpwindIds(ids, detectionIds);

        // No detection of ours => not our story. Refusing here is what makes the story list
        // tenant-safe, since the story itself carries no ownership marker.
        if (detections.isEmpty()) {
            log.debug("[Upwind] story {} references no detection in client {}'s accounts", storyId, clientId);
            return Map.of();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", story.path("id").asText(null));
        out.put("title", story.path("title").asText(null));
        out.put("severity", upper(story.path("severity").asText(null)));
        out.put("status", upper(story.path("status").asText(null)));
        out.put("createTime", story.path("create_time").asText(null));
        out.put("updateTime", story.path("update_time").asText(null));
        // Upwind's own markdown narrative, passed through verbatim; the client renders it.
        out.put("description", story.path("description").asText(null));
        out.put("detectionCount", detectionIds.size());
        out.put("resolvedCount", detections.size());

        List<Map<String, Object>> detectionRows = new ArrayList<>();
        Map<String, Long> tacticCounts = new LinkedHashMap<>();
        Map<String, Map<String, Object>> resources = new LinkedHashMap<>();
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        String worst = null;
        Instant firstSeen = null, lastSeen = null;

        for (UpwindThreatDetection d : detections) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getUpwindId());
            m.put("title", d.getTitle());
            m.put("severity", d.getSeverity());
            m.put("status", d.getStatus());
            m.put("category", d.getCategory());
            m.put("resourceId", d.getResourceId());
            m.put("resourceName", d.getResourceName());
            m.put("resourceType", d.getResourceType());
            m.put("cloudAccountId", d.getCloudAccountId());
            m.put("mitreTactics", d.getMitreTactics() == null || d.getMitreTactics().isEmpty()
                    ? List.of() : Arrays.asList(d.getMitreTactics().split(",")));
            m.put("occurrenceCount", d.getOccurrenceCount());
            m.put("firstSeenTime", d.getFirstSeenTime());
            m.put("lastSeenTime", d.getLastSeenTime());
            detectionRows.add(m);

            worst = worse(worst, d.getSeverity());
            firstSeen = earliest(firstSeen, d.getFirstSeenTime());
            lastSeen = latest(lastSeen, d.getLastSeenTime());
            if (d.getCategory() != null) categoryCounts.merge(d.getCategory(), 1L, Long::sum);
            if (d.getMitreTactics() != null) {
                for (String t : d.getMitreTactics().split(",")) {
                    if (!t.isBlank()) tacticCounts.merge(t.trim(), 1L, Long::sum);
                }
            }
            String key = d.getResourceId() != null ? d.getResourceId() : d.getResourceName();
            if (key != null) {
                Map<String, Object> r = resources.computeIfAbsent(key, k -> {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("resourceId", d.getResourceId());
                    n.put("resourceName", d.getResourceName());
                    n.put("resourceType", d.getResourceType());
                    n.put("cloudProvider", d.getCloudProvider());
                    n.put("cloudAccountId", d.getCloudAccountId());
                    n.put("detectionCount", 0L);
                    return n;
                });
                r.put("detectionCount", ((Long) r.get("detectionCount")) + 1);
            }
        }

        out.put("detections", detectionRows);
        out.put("worstSeverity", worst);
        out.put("firstSeenTime", firstSeen);
        out.put("lastSeenTime", lastSeen);
        out.put("mitreTactics", tacticCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tactic", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                }).collect(Collectors.toList()));
        out.put("categories", categoryCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("category", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                }).collect(Collectors.toList()));
        out.put("resources", new ArrayList<>(resources.values()));
        return out;
    }

    /**
     * Findings grouped by something other than CVE — Upwind offers Image, Package and Resource
     * alongside it, and the answer to "what do I patch" is usually an image or a package rather
     * than a CVE.
     *
     * Rows carry the same runtime and risk rollups as the CVE list so a group can be judged
     * without drilling in: severity is the worst in the group, and the runtime flags are OR-ed,
     * because one reachable member makes the group worth attention.
     *
     * @param dimension one of {@code image}, {@code package}, {@code resource}
     */
    public Map<String, Object> getVulnerabilityGroups(Long clientId, String accountId,
                                                     String dimension, int page, int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of());
        out.put("totalElements", 0);
        out.put("totalPages", 0);
        out.put("number", page);
        out.put("dimension", dimension == null ? "image" : dimension.toLowerCase());

        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return out;

        List<Object[]> rows;
        String dim = dimension == null ? "image" : dimension.toLowerCase();
        switch (dim) {
            case "package" -> rows = vulnRepo.groupByPackage(ids);
            case "resource" -> rows = vulnRepo.groupByResource(ids);
            case "image" -> rows = vulnRepo.groupByImage(ids);
            default -> {
                // Unknown dimension returns empty rather than silently showing images: a typo
                // in a query param should not look like real data for the wrong grouping.
                out.put("dimension", dim);
                return out;
            }
        }

        Map<String, VulnGroup> groups = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String key = (String) r[0];
            if (key == null || key.isBlank()) continue;
            VulnGroup g = groups.computeIfAbsent(key, VulnGroup::new);
            if (g.secondary == null) g.secondary = (String) r[1];
            String sev = (String) r[2];
            if (isHigherVulnSeverity(sev, g.severity)) g.severity = sev;
            String cve = (String) r[3];
            if (cve != null && !cve.isBlank()) g.cves.add(cve);
            String resourceId = (String) r[4];
            if (resourceId != null) g.resources.add(resourceId);
            if (Boolean.TRUE.equals(r[5])) g.exploitable = true;
            if (Boolean.TRUE.equals(r[6])) g.packageInUse = true;
            if (Boolean.TRUE.equals(r[7])) g.internetExposed = true;
            g.findingsCount++;
        }

        List<VulnGroup> all = groups.values().stream()
                .sorted(Comparator
                        .comparingInt((VulnGroup g) -> vulnSeverityRank(g.severity))
                        .thenComparing(g -> -g.cves.size()))
                .collect(Collectors.toList());

        int total = all.size();
        int pageSize = Math.min(Math.max(size, 1), 200);
        int from = Math.min(page * pageSize, total);
        int to = Math.min(from + pageSize, total);
        out.put("content", all.subList(from, to).stream()
                .map(VulnGroup::toMap).collect(Collectors.toList()));
        out.put("totalElements", total);
        out.put("totalPages", (int) Math.ceil((double) total / pageSize));
        out.put("number", page);
        return out;
    }

    /** One image / package / resource with its vulnerability rollup. */
    private static final class VulnGroup {
        final String key;
        String secondary;
        String severity;
        boolean exploitable;
        boolean packageInUse;
        boolean internetExposed;
        int findingsCount;
        final Set<String> cves = new HashSet<>();
        final Set<String> resources = new HashSet<>();
        VulnGroup(String key) { this.key = key; }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("secondary", secondary);
            m.put("severity", severity);
            m.put("cveCount", cves.size());
            m.put("findingsCount", findingsCount);
            m.put("resourceCount", resources.size());
            m.put("exploitable", exploitable);
            m.put("packageInUse", packageInUse);
            m.put("internetExposed", internetExposed);
            m.put("runtimeExposed", packageInUse && internetExposed);
            return m;
        }
    }

    /**
     * Whether this client owns the ORG-LEVEL Upwind data — the shift-left CI stream.
     *
     * Two rules preceded this and both were wrong.
     *
     * ownsEntireOrg(): a client owning every cloud account in the org IS the org. Safe and
     * useless — no client owns every account while one org-id is shared across tenants, so the
     * Code tab could never render anything at all.
     *
     * Correlating each scan's image to an image we hold under an owned account. That looked
     * sound and matched nothing, because `image` on these events is not a container image:
     * measured 2026-08-12, all 100 sampled scans report image="XamOps-New", identical to
     * `repository` and populated on IaC builds too. The repository wired to this organization is
     * XamOps itself, and its builds never run as a workload in a customer account, so the join
     * had no overlap to find. A category error, not a tuning problem.
     *
     * What is left is a fact we hold and the API cannot state: which accounts belong to the
     * tenant that connected the repository. Configuration is the honest place for it. Empty
     * means withheld from everyone, so an unconfigured environment cannot leak.
     */
    private boolean ownsOrgLevelData(Long clientId) {
        Set<String> owners = shiftLeftOwnerAccountIds();
        if (owners.isEmpty()) return false;
        // Must own every configured account, so adding one narrows access rather than widening it.
        return resolveAccountIds(clientId, null).containsAll(owners);
    }

    private Set<String> shiftLeftOwnerAccountIds() {
        if (shiftLeftOwnerAccountIds == null || shiftLeftOwnerAccountIds.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String part : shiftLeftOwnerAccountIds.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * CI scan history — Upwind's shift-left stream, one row per build.
     *
     * {@code introduced} is the number this build ADDED relative to its baseline, which is the
     * whole point of a shift-left view and is not derivable from the runtime vulnerability data:
     * that tells you what is deployed, this tells you what a specific commit is about to add.
     */
    public Map<String, Object> getCodeScans(Long clientId, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scans", List.of());
        out.put("available", false);
        if (!apiClient.isConfigured()) {
            out.put("unavailableReason", "Upwind is not configured.");
            return out;
        }
        if (!ownsOrgLevelData(clientId)) {
            // Deliberately phrased as a fact about the caller's own accounts, not as a hint that
            // someone else's scans exist. Whether this organization does CI scanning at all is
            // itself information about another tenant.
            out.put("unavailableReason",
                    "No code scanning is connected for your cloud accounts. Upwind reports CI "
                    + "scans for a source repository rather than a cloud account, so they appear "
                    + "only for the accounts that connected one.");
            return out;
        }

        String cacheKey = "xamsecure:codescans:" + clientId;
        Optional<Map<String, Object>> cached =
                redisCacheService.get(cacheKey, new TypeReference<Map<String, Object>>() {});
        if (cached.isPresent()) return cached.get();

        List<Map<String, Object>> scans = new ArrayList<>();
        for (JsonNode s : apiClient.searchShiftLeftScans(Math.min(Math.max(limit, 1), 500))) {
            Map<String, Object> m = new LinkedHashMap<>();
            // Absent on ~11% of scans (28 of 256 measured); without it there is no way to fetch
            // findings, so the client must render those rows as history only.
            m.put("fingerprint", s.path("fingerprint").asText(null));
            m.put("type", s.path("type").asText(null));
            m.put("source", s.path("source").asText(null));
            m.put("repository", s.path("repository").asText(null));
            m.put("branch", s.path("branch").asText(null));
            m.put("commitSha", s.path("commit_sha").asText(null));
            m.put("initiator", s.path("initiator").asText(null));
            m.put("image", s.path("image").asText(null));
            m.put("imageOs", s.path("os").asText(null));
            JsonNode det = s.path("repository_scan_details");
            m.put("mainBranchName", det.path("main_branch_name").asText(null));
            m.put("pullRequestId", det.path("pr_id").asText(null));
            m.put("pullRequestUrl", det.path("pr_link").asText(null));
            m.put("createTime", s.path("create_time").asText(null));
            m.put("updateTime", s.path("update_time").asText(null));
            scans.add(m);
        }
        out.put("scans", scans);
        out.put("available", true);
        out.remove("unavailableReason");
        if (!scans.isEmpty()) redisCacheService.put(cacheKey, out, SUMMARY_CACHE_MINUTES);
        return out;
    }

    /** CVE buckets Upwind reports per scan, in the order a reviewer cares about. */
    private static final List<String> CODE_CVE_BUCKETS =
            List.of("high_impact_cves", "introduced_cves", "no_change_cves", "resolved_cves");

    /**
     * Findings for one scan.
     *
     * The upstream route returns HTTP 200 and echoes the fingerprint back for ids that do not
     * exist, so "no buckets" cannot be distinguished from "unknown fingerprint" by status code.
     * Only fingerprints from the scan search are accepted, and an all-empty result is reported as
     * a clean scan rather than an error.
     */
    public Map<String, Object> getCodeScanFindings(Long clientId, String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank() || !apiClient.isConfigured()) {
            return Map.of();
        }
        // The fingerprint arrives from the client and Upwind answers 200 for any tenant's
        // fingerprint, echoing unknown ones back — so a caller who cannot see the list must not be
        // able to read a result by guessing one.
        if (!ownsOrgLevelData(clientId)) {
            return Map.of();
        }
        JsonNode r;
        try {
            r = apiClient.getShiftLeftResult(fingerprint);
        } catch (Exception e) {
            log.warn("[Upwind] shift-left result {} fetch failed: {}", fingerprint, e.getMessage());
            return Map.of();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fingerprint", r.path("fingerprint").asText(null));
        out.put("initiator", r.path("initiator").asText(null));
        out.put("branch", r.path("branch").asText(null));
        out.put("imageName", r.path("image_name").asText(null));
        out.put("imageOs", r.path("image_os").asText(null));

        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String bucket : CODE_CVE_BUCKETS) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode c : r.path(bucket)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("cveId", c.path("cve_id").asText(null));
                m.put("name", c.path("cve_name").asText(null));
                m.put("description", c.path("cve_description").asText(null));
                m.put("severity", upper(c.path("severity").asText(null)));
                m.put("score", c.path("score").asText(null));
                m.put("cvssVector", c.path("cvss_vector").asText(null));
                m.put("packageName", c.path("package_name").asText(null));
                m.put("packageVersion", c.path("package_version").asText(null));
                m.put("fixAvailable", c.path("is_fix_available").asBoolean(false));
                m.put("fixedInVersion", c.path("fixed_in_version").asText(null));
                m.put("exploitable", c.path("is_exploitable").asBoolean(false));
                m.put("status", c.path("status").asText(null));
                rows.add(m);
            }
            rows.sort(Comparator.comparingInt(m -> vulnSeverityRank((String) m.get("severity"))));
            buckets.put(camel(bucket), rows);
            counts.put(camel(bucket), rows.size());
        }
        out.put("cves", buckets);
        out.put("counts", counts);
        return out;
    }

    /** high_impact_cves -> highImpact, so the client reads one naming convention. */
    private static String camel(String bucket) {
        String base = bucket.endsWith("_cves") ? bucket.substring(0, bucket.length() - 5) : bucket;
        String[] parts = base.split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return sb.toString();
    }

    /**
     * Per-control compliance for one framework: how many assets pass each rule, and how many fail.
     *
     * This is the detail our Compliance tab has never had. It reads v2 findings, which return
     * BOTH statuses with the rule and framework attached — the v1 list we sync gives one status
     * per request and no rule id, so it cannot answer "which controls, and how compliant".
     *
     * Read live rather than synced, deliberately. Re-pointing the posture walk we just repaired
     * is a separate and riskier change; this only adds a request behind a drill-down.
     *
     * A control with no findings at all is Upwind's "no relevant assets were found in your
     * environment" state, and it is reported as unscored rather than as 0% — the difference
     * between "nothing to check" and "everything failed" is the whole point of the column.
     */
    public Map<String, Object> getFrameworkControls(Long clientId, String frameworkId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("frameworkId", frameworkId);
        out.put("controls", List.of());
        List<String> ids = resolveAccountIds(clientId, null);
        if (ids.isEmpty() || frameworkId == null || frameworkId.isBlank()
                || !apiClient.isConfigured()) {
            return out;
        }

        String cacheKey = "xamsecure:controls:" + clientId + ":" + frameworkId;
        Optional<Map<String, Object>> cached =
                redisCacheService.get(cacheKey, new TypeReference<Map<String, Object>>() {});
        if (cached.isPresent()) return cached.get();

        List<Map<String, Object>> conditions = List.of(
                Map.of("field", "framework_id", "operator", "eq", "value", List.of(frameworkId)));

        Set<String> owned = new HashSet<>(ids);
        Map<String, ControlAgg> byRule = new LinkedHashMap<>();
        int skippedForeign = 0;
        for (JsonNode f : apiClient.searchConfigurationFindingsV2(conditions, CONTROL_MAX_FINDINGS)) {
            // v2 findings carry the account on the resource, so unlike threat stories these can
            // be scoped properly. Anything outside the caller's accounts is dropped.
            String acct = f.path("resource").path("cloud_account_id").asText(null);
            if (acct != null && !owned.contains(acct)) {
                skippedForeign++;
                continue;
            }
            JsonNode rule = f.path("rule");
            String ruleId = rule.path("id").asText(null);
            if (ruleId == null || ruleId.isBlank()) continue;
            ControlAgg a = byRule.computeIfAbsent(ruleId, k -> new ControlAgg(k));
            if (a.title == null) a.title = rule.path("title").asText(null);
            if (a.remediation == null) a.remediation = rule.path("remediation").asText(null);
            String sev = upper(f.path("severity").asText(null));
            if (isHigherVulnSeverity(sev, a.severity)) a.severity = sev;
            // Only pass and fail are scored. Live v2 returns nothing else today, but treating an
            // unrecognised status as failing is exactly how resolved vulnerabilities came to be
            // counted as current (53cb908a0); an unscored status must not inflate the failure
            // count. A control whose findings are all unscored reports no percentage at all.
            String status = f.path("status").asText(null);
            if ("pass".equalsIgnoreCase(status)) a.pass++;
            else if ("fail".equalsIgnoreCase(status)) a.fail++;
            else a.unscored++;
            String res = f.path("resource").path("name").asText(null);
            if (res != null) a.resources.add(res);
            a.lastEvaluated = latest(a.lastEvaluated, instantOrNull(f.path("evaluation_time")));
        }

        List<Map<String, Object>> controls = byRule.values().stream()
                // Worst compliance first: a reviewer works down from the least compliant control.
                .sorted(Comparator.comparingDouble(ControlAgg::complianceRatio)
                        .thenComparing(a -> -a.fail))
                .map(ControlAgg::toMap)
                .collect(Collectors.toList());
        out.put("controls", controls);
        out.put("controlCount", controls.size());
        out.put("skippedOtherAccounts", skippedForeign);
        if (!controls.isEmpty()) redisCacheService.put(cacheKey, out, SUMMARY_CACHE_MINUTES);
        return out;
    }

    /** Cap on findings pulled for one framework's control breakdown. */
    private static final int CONTROL_MAX_FINDINGS = 20000;

    /** v2 returns the whole framework catalogue; 100 covered every framework measured. */
    private static final int FRAMEWORK_MAX = 200;

    /** One compliance control with its pass/fail split across the caller's assets. */
    private static final class ControlAgg {
        final String ruleId;
        String title;
        String remediation;
        String severity;
        int pass;
        int fail;
        /** Findings carrying a status we do not score. Reported, never folded into fail. */
        int unscored;
        final Set<String> resources = new HashSet<>();
        Instant lastEvaluated;
        ControlAgg(String ruleId) { this.ruleId = ruleId; }

        /** Unscored controls sort first; they need attention of a different kind. */
        double complianceRatio() {
            int total = pass + fail;
            return total == 0 ? -1 : (double) pass / total;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            int total = pass + fail;
            m.put("ruleId", ruleId);
            m.put("title", title);
            m.put("severity", severity);
            m.put("passCount", pass);
            m.put("failCount", fail);
            m.put("assetCount", total);
            // Null, not 0: no findings means Upwind found no relevant assets, which is not the
            // same as every asset failing.
            m.put("compliancePercent", total == 0 ? null : (int) Math.round(100.0 * pass / total));
            m.put("unscoredCount", unscored);
            m.put("resourceCount", resources.size());
            m.put("remediation", remediation);
            m.put("lastEvaluated", lastEvaluated);
            return m;
        }
    }

    private static Instant instantOrNull(JsonNode node) {
        String v = node == null ? null : node.asText(null);
        if (v == null || v.isBlank()) return null;
        try { return Instant.parse(v); } catch (Exception e) { return null; }
    }

    /**
     * Provider-native relationships for one asset.
     *
     * The only place real edges are available. The asset search omits the relationship fields the
     * inventory schema declares, and nested conditions are rejected in every shape, so this is
     * one request per asset rather than a topology walk — which is why it is exposed behind an
     * asset drill-down and not as a graph over the estate.
     *
     * Keys are provider-native (SecurityGroups, VpcId, Instances, TargetGroupARNs), so rather
     * than enumerate every resource type we detect the shape: scalars that look like a resource
     * id, and arrays of ids or of objects carrying one.
     */
    public Map<String, Object> getAssetRelationships(Long clientId, String assetId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assetId", assetId);
        out.put("relationships", List.of());
        if (assetId == null || assetId.isBlank() || !apiClient.isConfigured()) return out;

        // Tenancy: only assets we already hold for this client, so the raw fetch cannot be used
        // to read an asset belonging to another account.
        List<String> ids = resolveAccountIds(clientId, null);
        if (ids.isEmpty()) return out;
        Optional<UpwindInventoryAsset> owned = inventoryRepo.findByUpwindId(assetId)
                .filter(a -> a.getCloudAccountId() != null && ids.contains(a.getCloudAccountId()));
        if (owned.isEmpty()) {
            throw new AccessDeniedException("Asset not accessible");
        }

        JsonNode raw;
        try {
            raw = apiClient.getAssetDetail(assetId).path("items").path(0).path("raw_data");
        } catch (Exception e) {
            log.warn("[Upwind] asset detail {} fetch failed: {}", assetId, e.getMessage());
            return out;
        }
        if (raw == null || !raw.isObject()) return out;

        List<Map<String, Object>> rels = new ArrayList<>();
        raw.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (RELATIONSHIP_SKIP.contains(key)) return;
            List<String> refs = extractReferences(entry.getValue());
            if (refs.isEmpty()) return;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", key);
            m.put("label", prettifyFieldName(key));
            m.put("references", refs.size() > 50 ? refs.subList(0, 50) : refs);
            m.put("count", refs.size());
            rels.add(m);
        });
        rels.sort(Comparator.comparing(m -> (String) m.get("label")));
        out.put("relationships", rels);
        out.put("resourceType", owned.get().getLabel());
        return out;
    }

    /** Noise that matches the id shapes below without describing a relationship. */
    private static final Set<String> RELATIONSHIP_SKIP = Set.of(
            "Tags", "Arn", "ARN", "OwnerId", "ClientToken", "Region", "AvailabilityZone");

    /** Matches the id forms AWS uses: sg-, vpc-, subnet-, i-, eni-, vol-, ami-, and full ARNs. */
    private static final java.util.regex.Pattern RESOURCE_ID =
            java.util.regex.Pattern.compile(
                    "^(arn:[^:]*:[^:]+:.*|(sg|vpc|subnet|i|eni|vol|ami|snap|rtb|acl|igw|nat|lt|tg)-[0-9a-f]{6,})$");

    /**
     * Pulls resource ids out of a provider-native value, whatever shape it takes: a bare id, an
     * array of ids, or an array of objects with one id-shaped field (ASG Instances carry
     * InstanceId, TrafficSources carry Identifier).
     */
    private static List<String> extractReferences(JsonNode value) {
        List<String> refs = new ArrayList<>();
        if (value == null || value.isNull()) return refs;
        if (value.isTextual()) {
            if (RESOURCE_ID.matcher(value.asText()).matches()) refs.add(value.asText());
            return refs;
        }
        if (value.isArray()) {
            for (JsonNode n : value) {
                if (n.isTextual() && RESOURCE_ID.matcher(n.asText()).matches()) {
                    refs.add(n.asText());
                } else if (n.isObject()) {
                    n.fields().forEachRemaining(f -> {
                        if (f.getValue().isTextual()
                                && RESOURCE_ID.matcher(f.getValue().asText()).matches()) {
                            refs.add(f.getValue().asText());
                        }
                    });
                }
            }
        }
        return refs.stream().distinct().collect(Collectors.toList());
    }

    /** Friendly labels for the detection sources Upwind emits, keyed by payload category. */
    private static final Map<String, String> SOURCE_LABELS = Map.of(
            "CLOUD_TRAIL", "AWS CloudTrail",
            "SENSOR", "Sensor",
            "AUDIT_LOGS", "Audit Logs",
            "CLOUD_SCANNER", "Cloud Scanner",
            "AZURE_LOGS", "Azure Logs",
            "GCP_LOGS", "GCP Logs");

    /** Selectable trend windows. 7 matches Upwind's default; the longer ones exist because
     *  detections here are often last seen well over a week ago, which leaves a 7-day chart
     *  almost empty and looking like missing data rather than quiet weeks. */
    private static final List<Integer> THREAT_TREND_WINDOWS = List.of(7, 30, 90);
    private static final int THREAT_TREND_DAYS = 7;

    /**
     * Aggregates behind the Threats dashboard: the detection funnel, a seven-day trend, and
     * breakdowns by MITRE tactic and source.
     *
     * The raw event count Upwind puts at the head of its funnel is deliberately reported as
     * unavailable rather than substituted. Upwind counts sensor events, of which detections are
     * the filtered remainder; we sync detections only, so any number we printed there would be
     * a different quantity wearing the same label.
     */
    public Map<String, Object> getThreatOverview(Long clientId, String accountId) {
        return getThreatOverview(clientId, accountId, THREAT_TREND_DAYS);
    }

    public Map<String, Object> getThreatOverview(Long clientId, String accountId, int days) {
        int window = THREAT_TREND_WINDOWS.contains(days) ? days : THREAT_TREND_DAYS;
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> ids = resolveAccountIds(clientId, accountId);

        List<Object[]> rows = ids.isEmpty() ? List.of() : detectionRepo.findDashboardFacets(ids);

        Map<String, Long> byTactic = new HashMap<>();
        Map<String, Long> bySource = new HashMap<>();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        // day -> severity band -> count
        Map<LocalDate, Map<String, Long>> trend = new HashMap<>();

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(window - 1L);

        for (Object[] r : rows) {
            String severity = upper((String) r[0]);
            String tactics = (String) r[1];
            String category = (String) r[2];
            Instant lastSeen = (Instant) r[3];

            if (severity != null) bySeverity.merge(severity, 1L, Long::sum);

            // A detection can map to several tactics; Upwind counts it under each.
            if (tactics != null && !tactics.isBlank()) {
                for (String t : tactics.split(",")) {
                    String tactic = t.trim();
                    if (!tactic.isEmpty()) byTactic.merge(tactic, 1L, Long::sum);
                }
            }

            String source = category == null || category.isBlank() ? "Unknown"
                    : SOURCE_LABELS.getOrDefault(upper(category), prettify(category));
            bySource.merge(source, 1L, Long::sum);

            if (lastSeen != null) {
                LocalDate day = lastSeen.atZone(ZoneOffset.UTC).toLocalDate();
                if (!day.isBefore(from) && !day.isAfter(today)) {
                    trend.computeIfAbsent(day, d -> new HashMap<>())
                         .merge(severityBand(severity), 1L, Long::sum);
                }
            }
        }

        // Dense series: a day with no detections has to appear as zero, or the chart implies
        // the quiet days never happened.
        List<Map<String, Object>> series = new ArrayList<>();
        long inWindow = 0;
        for (int i = 0; i < window; i++) {
            LocalDate day = from.plusDays(i);
            Map<String, Long> counts = trend.getOrDefault(day, Map.of());
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day.toString());
            point.put("critical", counts.getOrDefault("CRITICAL", 0L));
            point.put("high", counts.getOrDefault("HIGH", 0L));
            point.put("other", counts.getOrDefault("OTHER", 0L));
            inWindow += (Long) point.get("critical") + (Long) point.get("high")
                      + (Long) point.get("other");
            series.add(point);
        }

        Map<String, Object> stories = getThreatStories(clientId, accountId);

        out.put("detectionCount", rows.size());
        out.put("resourceCount", ids.isEmpty() ? 0L : detectionRepo.countDistinctResources(ids));
        out.put("storyCount", stories.getOrDefault("activeCount", 0));
        out.put("eventCount", null);
        out.put("eventCountAvailable", false);
        out.put("eventCountReason",
                "Upwind's funnel counts raw sensor events. We sync detections, not events, "
                + "so no comparable figure exists on our side.");
        out.put("bySeverity", bySeverity);
        out.put("trend", series);
        out.put("trendDays", window);
        out.put("trendWindows", THREAT_TREND_WINDOWS);
        // Detections whose last-seen falls inside the window. Without this the chart cannot be
        // read: a near-empty 7-day chart against 31 detections looks broken rather than stale.
        out.put("detectionsInWindow", inWindow);
        out.put("byTactic", topEntries(byTactic, 10, "tactic"));
        out.put("bySource", topEntries(bySource, 10, "source"));
        return out;
    }

    /**
     * Detections grouped by threat, resource or account — Upwind's Group by chips.
     *
     * A raw detection feed repeats the same rule against dozens of resources, so the flat list
     * answers "what happened most recently" but not "what is actually going on". Grouping by
     * threat collapses that; grouping by resource shows which workloads are noisy.
     *
     * @param dimension one of {@code threat}, {@code resource}, {@code account}
     */
    public Map<String, Object> getThreatGroups(Long clientId, String accountId, String dimension) {
        String dim = dimension == null ? "threat" : dimension.toLowerCase();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", dim);
        out.put("groups", List.of());

        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return out;
        if (!List.of("threat", "resource", "account").contains(dim)) return out;

        Map<String, ThreatGroup> groups = new LinkedHashMap<>();
        for (Object[] r : detectionRepo.findGroupingFacets(ids)) {
            String key;
            String secondary;
            switch (dim) {
                case "resource" -> {
                    key = firstNonNull((String) r[3], (String) r[2]);
                    secondary = (String) r[4];
                }
                case "account" -> {
                    key = (String) r[5];
                    secondary = null;
                }
                default -> {
                    key = (String) r[0];
                    secondary = (String) r[6];
                }
            }
            if (key == null || key.isBlank()) continue;
            ThreatGroup g = groups.computeIfAbsent(key, ThreatGroup::new);
            if (g.secondary == null) g.secondary = secondary;
            g.severity = worse(g.severity, (String) r[1]);
            if (r[2] != null) g.resources.add((String) r[2]);
            if (r[5] != null) g.accounts.add((String) r[5]);
            g.detections++;
            g.occurrences += r[7] == null ? 1 : ((Number) r[7]).intValue();
            g.firstSeen = earliest(g.firstSeen, (Instant) r[8]);
            g.lastSeen = latest(g.lastSeen, (Instant) r[9]);
        }

        out.put("groups", groups.values().stream()
                .sorted(Comparator
                        .comparingInt((ThreatGroup g) -> vulnSeverityRank(g.severity))
                        .thenComparing(g -> -g.detections))
                .map(ThreatGroup::toMap)
                .collect(Collectors.toList()));
        return out;
    }

    /** One threat / resource / account with its detection rollup. */
    private static final class ThreatGroup {
        final String key;
        String secondary;
        String severity;
        int detections;
        int occurrences;
        final Set<String> resources = new HashSet<>();
        final Set<String> accounts = new HashSet<>();
        Instant firstSeen;
        Instant lastSeen;
        ThreatGroup(String key) { this.key = key; }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("secondary", secondary);
            m.put("severity", severity);
            m.put("detections", detections);
            m.put("occurrences", occurrences);
            m.put("resourceCount", resources.size());
            m.put("accountCount", accounts.size());
            m.put("firstSeenTime", firstSeen);
            m.put("lastSeenTime", lastSeen);
            return m;
        }
    }

    /** Upwind's trend chart bands everything below High into one "Other" series. */
    private static String severityBand(String severity) {
        if ("CRITICAL".equals(severity)) return "CRITICAL";
        if ("HIGH".equals(severity)) return "HIGH";
        return "OTHER";
    }

    private static List<Map<String, Object>> topEntries(Map<String, Long> counts, int limit, String key) {
        return counts.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Long.compare(b.getValue(), a.getValue());
                    return c != 0 ? c : a.getKey().compareTo(b.getKey());
                })
                .limit(limit)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put(key, e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * SecurityGroups -> "Security Groups", TargetGroupARNs -> "Target Group ARNs".
     *
     * Provider payload keys are PascalCase, not the SCREAMING_SNAKE that {@link #prettify} was
     * written for; running them through that would lowercase the interior and produce
     * "Securitygroups". Splits only where a lowercase or digit meets an uppercase, so acronym
     * runs like ARN and DB survive intact.
     */
    private static String prettifyFieldName(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String spaced = raw.replace('_', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .trim()
                .replaceAll("\\s+", " ");
        if (spaced.isEmpty()) return raw;
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** CLOUD_TRAIL -> "Cloud Trail", for sources we have no curated label for. */
    private static String prettify(String raw) {
        String[] parts = raw.replace('-', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase());
        }
        return sb.length() == 0 ? raw : sb.toString();
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase();
    }

    private static boolean isActiveStory(String status) {
        if (status == null) return true; // absent status → treat as active (matches console default)
        String s = status.toLowerCase();
        return !(s.contains("resolved") || s.contains("closed") || s.contains("archived") || s.contains("dismissed"));
    }

    private static String firstNonNull(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    /**
     * Resolves the provider account ids (AWS account / GCP project / Azure subscription)
     * owned by the client, narrowed to the caller's selection.
     *
     * {@code requestedAccountId} may be a single id or a comma-separated list, because the
     * account picker supports multi-select. Every requested id must be owned by the client,
     * otherwise AccessDeniedException — a client must never read another client's findings.
     * An empty/absent selection means "all accounts I own".
     */
    public List<String> resolveAccountIds(Long clientId, String requestedAccountId) {
        List<String> owned = cloudAccountRepository.findByClientId(clientId).stream()
                .map(CloudAccount::getProviderAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (requestedAccountId == null || requestedAccountId.isEmpty()) {
            return owned;
        }
        List<String> requested = Arrays.stream(requestedAccountId.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (requested.isEmpty()) {
            return owned;
        }
        for (String id : requested) {
            if (!owned.contains(id)) {
                throw new AccessDeniedException("Account not accessible: " + id);
            }
        }
        return requested;
    }

    public Map<String, Object> getSummary(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        String cacheKey = "xamsecure:summary:" + clientId + ":" + (accountId == null ? "all" : accountId);
        Optional<Map<String, Object>> cached =
                redisCacheService.get(cacheKey, new TypeReference<Map<String, Object>>() {});
        if (cached.isPresent()) return cached.get();

        Map<String, Object> summary = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            summary.put("threatsBySeverity", Map.of());
            summary.put("vulnerabilitiesBySeverity", Map.of());
            summary.put("postureBySeverity", Map.of());
            summary.put("syncState", List.of());
            return summary;
        }
        summary.put("threatsBySeverity", toSeverityMap(detectionRepo.countOpenBySeverity(ids)));
        summary.put("vulnerabilitiesBySeverity", toSeverityMap(vulnRepo.countOpenBySeverity(ids)));
        summary.put("postureBySeverity", toSeverityMap(postureRepo.countFailBySeverity(ids)));
        summary.put("syncState", syncStateRepo.findAll().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", s.getDomain());
            m.put("lastSuccessAt", s.getLastSuccessAt());
            m.put("lastError", s.getLastError());
            return m;
        }).collect(Collectors.toList()));
        redisCacheService.put(cacheKey, summary, SUMMARY_CACHE_MINUTES);
        return summary;
    }

    public Page<UpwindThreatDetection> getThreats(Long clientId, String accountId,
                                                  String severity, int page, int size) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return Page.empty();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        if (severity != null && !severity.isEmpty()) {
            return detectionRepo.findRealByCloudAccountIdInAndSeverity(ids, severity, pageable);
        }
        return detectionRepo.findRealByCloudAccountIdIn(ids, pageable);
    }

    public Page<UpwindVulnerabilityFinding> getVulnerabilities(Long clientId, String accountId,
                                                               String severity, int page, int size) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return Page.empty();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        if (severity != null && !severity.isEmpty()) {
            return vulnRepo.findByCloudAccountIdInAndSeverityOrderByLastScanTimeDesc(ids, severity, pageable);
        }
        return vulnRepo.findByCloudAccountIdInOrderByLastScanTimeDesc(ids, pageable);
    }

    /**
     * Vulnerabilities dashboard aggregates, deduped by CVE id (a CVE affects many resources →
     * many findings; Upwind reports "Unique CVEs"). Mirrors Upwind's Vulnerabilities → Dashboard:
     * total CVEs, severity breakdown, workload-type split, exploitable/EPSS counts, new-in-7-days,
     * and a 7-day trend.
     */
    public Map<String, Object> getVulnerabilityDashboard(Long clientId, String accountId) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) {
            out.put("totalCves", 0); out.put("totalFindings", 0);
            out.put("severityCounts", Map.of()); out.put("byWorkloadType", List.of());
            out.put("exploitableCves", 0); out.put("epssPrioritizedCves", 0);
            out.put("newLast7Days", Map.of()); out.put("overTime", List.of());
            out.put("severityOverTime", List.of()); out.put("criticalWorkloadOverTime", List.of());
            out.put("workloadLabels", List.of());
            return out;
        }

        // Cumulative-over-time series for the last 7 days, at findings level, derived from
        // first_seen_time. Mirrors Upwind's "Vulnerability findings by severity" (stacked,
        // near-flat total) and "Critical vulnerability findings by workload type". A finding
        // counts toward every day on/after the day it was first seen; anything first seen
        // before the window sits in the baseline (index 0) so early days already reflect the
        // standing backlog. This is a real derivation (not fabricated) — it does not model
        // per-day resolution history, which Upwind retains but we don't.
        java.time.LocalDate todayUtc = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.util.List<java.time.LocalDate> trendDays = new ArrayList<>();
        for (int i = 6; i >= 0; i--) trendDays.add(todayUtc.minusDays(i));
        long[] newCrit = new long[7], newHigh = new long[7], newOther = new long[7];
        java.util.Map<String, long[]> critWorkloadNew = new LinkedHashMap<>();

        Map<String, CveAgg> byCve = new HashMap<>();
        long totalFindings = 0;
        for (Object[] r : vulnRepo.findForDashboard(ids)) {
            totalFindings++;
            // --- trend accumulation (all findings, including those without a CVE id) ---
            int idx = trendIndex((Instant) r[5], trendDays);
            if (idx >= 0) {
                String bucket = sevBucket((String) r[1]);
                if ("CRITICAL".equals(bucket)) {
                    newCrit[idx]++;
                    critWorkloadNew.computeIfAbsent(workloadLabel((String) r[2]), k -> new long[7])[idx]++;
                } else if ("HIGH".equals(bucket)) {
                    newHigh[idx]++;
                } else {
                    newOther[idx]++;
                }
            }
            String cve = (String) r[0];
            if (cve == null || cve.isBlank()) continue;
            CveAgg a = byCve.computeIfAbsent(cve, k -> new CveAgg());
            if (a.severity == null) a.severity = (String) r[1];
            if (a.resourceType == null) a.resourceType = (String) r[2];
            if (Boolean.TRUE.equals(r[3])) a.exploitable = true;
            double e = parseDoubleSafe((String) r[4]);
            if (e > a.epss) a.epss = e;
            a.firstSeen = earliest(a.firstSeen, (Instant) r[5]);
        }

        // Build cumulative series from the new-on-day counts.
        List<Map<String, Object>> severityOverTime = new ArrayList<>();
        long cCrit = 0, cHigh = 0, cOther = 0;
        for (int i = 0; i < 7; i++) {
            cCrit += newCrit[i]; cHigh += newHigh[i]; cOther += newOther[i];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", trendDays.get(i).toString().substring(5)); // MM-DD
            m.put("CRITICAL", cCrit); m.put("HIGH", cHigh); m.put("OTHER", cOther);
            severityOverTime.add(m);
        }
        List<String> workloadLabels = new ArrayList<>(critWorkloadNew.keySet());
        List<Map<String, Object>> criticalWorkloadOverTime = new ArrayList<>();
        Map<String, Long> wlRunning = new LinkedHashMap<>();
        for (String l : workloadLabels) wlRunning.put(l, 0L);
        for (int i = 0; i < 7; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", trendDays.get(i).toString().substring(5));
            for (String l : workloadLabels) {
                wlRunning.merge(l, critWorkloadNew.get(l)[i], Long::sum);
                m.put(l, wlRunning.get(l));
            }
            criticalWorkloadOverTime.add(m);
        }

        Map<String, Long> severityCounts = new LinkedHashMap<>();
        for (String s : SEVERITY_ORDER) severityCounts.put(s, 0L);
        Map<String, Set<String>> byType = new LinkedHashMap<>();
        int exploitable = 0, epssPrioritized = 0;
        Instant weekAgo = Instant.now().minus(java.time.Duration.ofDays(7));
        Map<String, Long> newBySeverity = new LinkedHashMap<>(Map.of("CRITICAL", 0L, "HIGH", 0L, "OTHER", 0L));
        Map<String, Long> overTime = new LinkedHashMap<>();

        for (Map.Entry<String, CveAgg> en : byCve.entrySet()) {
            CveAgg a = en.getValue();
            String sev = a.severity == null ? "" : a.severity.toUpperCase();
            if (severityCounts.containsKey(sev)) severityCounts.merge(sev, 1L, Long::sum);
            byType.computeIfAbsent(a.resourceType == null ? "OTHER" : a.resourceType, k -> new HashSet<>())
                    .add(en.getKey());
            if (a.exploitable) exploitable++;
            if (a.epss >= 0.5) epssPrioritized++;
            if (a.firstSeen != null && a.firstSeen.isAfter(weekAgo)) {
                String bucket = ("CRITICAL".equals(sev) || "HIGH".equals(sev)) ? sev : "OTHER";
                newBySeverity.merge(bucket, 1L, Long::sum);
                String day = a.firstSeen.toString().substring(0, 10);
                overTime.merge(day, 1L, Long::sum);
            }
        }

        List<Map<String, Object>> byWorkloadType = byType.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", e.getKey());
                    m.put("count", e.getValue().size());
                    return m;
                })
                .sorted(Comparator.comparingInt(m -> -((Integer) m.get("count"))))
                .collect(Collectors.toList());

        // Runtime exploit funnel (Upwind order). "Internet exposure" and "Functions in Use"
        // used to be hardcoded to unavailable because the sync discarded the fields that
        // carry them; both are real now. "NIST critical sev" remains unsourced — it needs
        // NVD's own severity banding rather than the CVSS v3 severity we store.
        int totalCves = byCve.size();
        long packageInUse = vulnRepo.countCvesPackageInUse(ids);
        long fixAvailable = vulnRepo.countCvesFixAvailable(ids);
        long internetExposed = 0, exposedAndInUse = 0, functionsInUse = 0, activeComm = 0;
        List<Object[]> runtime = vulnRepo.countRuntimeExposure(ids);
        if (!runtime.isEmpty() && runtime.get(0) != null) {
            Object[] r = runtime.get(0);
            internetExposed = r[0] == null ? 0 : ((Number) r[0]).longValue();
            exposedAndInUse = r[1] == null ? 0 : ((Number) r[1]).longValue();
            functionsInUse = r[2] == null ? 0 : ((Number) r[2]).longValue();
            activeComm = r[3] == null ? 0 : ((Number) r[3]).longValue();
        }
        List<Map<String, Object>> funnel = new ArrayList<>();
        funnel.add(funnelStage("Total", totalCves, true));
        funnel.add(funnelStage("Package in use", (int) packageInUse, true));
        funnel.add(funnelStage("Internet exposure", (int) internetExposed, true));
        funnel.add(funnelStage("Known exploit", exploitable, true));
        funnel.add(funnelStage("Fix available", (int) fixAvailable, true));
        funnel.add(funnelStage("NIST critical sev", 0, false));
        funnel.add(funnelStage("EPSS-prioritized", epssPrioritized, true));
        funnel.add(funnelStage("Functions in Use", (int) functionsInUse, true));

        // Headline runtime narrowing, surfaced separately from the funnel so the tab can lead
        // with "what is actually reachable and running" instead of the installed total.
        Map<String, Object> runtimeExposure = new LinkedHashMap<>();
        runtimeExposure.put("internetExposed", internetExposed);
        runtimeExposure.put("exposedAndInUse", exposedAndInUse);
        runtimeExposure.put("activeCommunication", activeComm);
        runtimeExposure.put("functionsInUse", functionsInUse);
        runtimeExposure.put("packageInUse", packageInUse);
        out.put("runtimeExposure", runtimeExposure);

        out.put("totalCves", totalCves);
        out.put("totalFindings", totalFindings);
        out.put("severityCounts", severityCounts);
        out.put("byWorkloadType", byWorkloadType);
        out.put("exploitableCves", exploitable);
        out.put("epssPrioritizedCves", epssPrioritized);
        out.put("newLast7Days", newBySeverity);
        out.put("overTime", overTime);
        out.put("severityOverTime", severityOverTime);
        out.put("criticalWorkloadOverTime", criticalWorkloadOverTime);
        out.put("workloadLabels", workloadLabels);
        out.put("funnel", funnel);
        return out;
    }

    /** Index (0-6) of the trend day a finding first counts toward; baseline (0) if on/before
     *  the window start or unknown; -1 if in the future (skipped). */
    private static int trendIndex(Instant firstSeen, java.util.List<java.time.LocalDate> days) {
        if (firstSeen == null) return 0;
        java.time.LocalDate d = firstSeen.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        java.time.LocalDate start = days.get(0);
        java.time.LocalDate end = days.get(days.size() - 1);
        if (!d.isAfter(start)) return 0;
        if (d.isAfter(end)) return -1;
        return (int) java.time.temporal.ChronoUnit.DAYS.between(start, d);
    }

    /** Buckets severity into Upwind's Critical / High / Other groups. */
    private static String sevBucket(String severity) {
        String s = severity == null ? "" : severity.toUpperCase();
        if (s.equals("CRITICAL")) return "CRITICAL";
        if (s.equals("HIGH")) return "HIGH";
        return "OTHER";
    }

    /** Maps a resource type to an Upwind workload category. */
    private static String workloadLabel(String resourceType) {
        String t = resourceType == null ? "" : resourceType.toUpperCase();
        if (t.contains("LAMBDA") || t.contains("FUNCTION")) return "Serverless";
        if (t.contains("ECS") || t.contains("EKS") || t.contains("KUBER")
                || t.contains("POD") || t.contains("CONTAINER")) return "Kubernetes workloads";
        if (t.contains("ASG") || t.contains("SCALING")) return "Scaling groups";
        if (t.contains("INSTANCE") || t.contains("EC2") || t.contains("HOST") || t.contains("VM")) return "Hosts";
        return "Other";
    }

    private static Map<String, Object> funnelStage(String label, int value, boolean available) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value);
        m.put("available", available); // false = we can't source it (shown as 0/—)
        return m;
    }

    /**
     * Findings grouped by CVE (Upwind's default Findings view), paginated. Each row collapses
     * all findings for a CVE: severity, max CVSS, finding count, distinct affected resources,
     * exploitability, max EPSS. Drill-down of the affected resources is {@link #getCveFindings}.
     */
    public Map<String, Object> getVulnerabilitiesByCve(Long clientId, String accountId,
                                                       String severity, int page, int size) {
        return getVulnerabilitiesByCve(clientId, accountId, severity, page, size, null);
    }

    /**
     * @param view {@code "runtime"} restricts to CVEs whose package is loaded at runtime on a
     *        workload taking inbound traffic — the narrowing Upwind's default saved view
     *        ("Runtime exposure vulnerabilities") applies. Any other value shows everything.
     *        Worth having because the unfiltered list is a different question: Onfinance holds
     *        thousands of installed CVEs against a double-digit number that are reachable.
     */
    public Map<String, Object> getVulnerabilitiesByCve(Long clientId, String accountId,
                                                       String severity, int page, int size,
                                                       String view) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of());
        out.put("totalElements", 0);
        out.put("totalPages", 0);
        out.put("number", page);
        // Keep the response shape stable on the empty path so the UI never sees it missing.
        out.put("severityCounts", Map.of());

        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return out;

        Map<String, CveGroup> byCve = new LinkedHashMap<>();
        for (Object[] r : vulnRepo.findAllForCveGrouping(ids)) {
            String cve = (String) r[0];
            if (cve == null || cve.isBlank()) continue;
            CveGroup g = byCve.computeIfAbsent(cve, k -> new CveGroup(k));
            if (g.name == null) g.name = (String) r[1];
            String sev = (String) r[2];
            if (isHigherVulnSeverity(sev, g.severity)) g.severity = sev;
            double cvss = parseDoubleSafe((String) r[3]);
            if (cvss > g.cvss) g.cvss = cvss;
            if (Boolean.TRUE.equals(r[4])) g.exploitable = true;
            double epss = parseDoubleSafe((String) r[5]);
            if (epss > g.epss) g.epss = epss;
            String resourceId = (String) r[6];
            if (resourceId != null) g.resources.add(resourceId);
            g.findingsCount++;
            g.firstSeen = earliest(g.firstSeen, (Instant) r[9]);
            g.lastScan = latest(g.lastScan, (Instant) r[10]);
            if (Boolean.TRUE.equals(r[11])) g.packageInUse = true;
            if (Boolean.TRUE.equals(r[12])) g.internetExposed = true;
            if (Boolean.TRUE.equals(r[13])) g.functionsInUse = true;
        }

        // Affected images per CVE, aggregated separately because a DISTINCT over image names
        // cannot be folded into the row-per-finding projection above.
        for (Object[] r : vulnRepo.countImagesPerCve(ids)) {
            CveGroup g = byCve.get((String) r[0]);
            if (g != null) g.imageCount = (int) ((Number) r[1]).longValue();
        }

        // CVE-level severity distribution, computed BEFORE the severity filter so the UI's
        // group-by chips show the whole set. The dashboard's counts are per finding, which
        // would not match these rows (one CVE spans many findings).
        Map<String, Long> cveSeverityCounts = new LinkedHashMap<>();
        for (String sev : SEVERITY_ORDER) {
            long n = byCve.values().stream()
                    .filter(g -> sev.equalsIgnoreCase(g.severity == null ? "" : g.severity))
                    .count();
            if (n > 0) cveSeverityCounts.put(sev, n);
        }
        out.put("severityCounts", cveSeverityCounts);
        // Both totals are pre-filter so the view toggle can be labelled with its own count
        // before it is applied.
        out.put("totalCveCount", byCve.size());
        out.put("runtimeExposedCount", byCve.values().stream().filter(CveGroup::runtimeExposed).count());

        boolean runtimeOnly = "runtime".equalsIgnoreCase(view);
        out.put("view", runtimeOnly ? "runtime" : "all");

        List<CveGroup> all = byCve.values().stream()
                .filter(g -> !runtimeOnly || g.runtimeExposed())
                .filter(g -> severity == null || severity.isEmpty()
                        || severity.equalsIgnoreCase(g.severity == null ? "" : g.severity))
                .sorted(Comparator
                        .comparingInt((CveGroup g) -> vulnSeverityRank(g.severity))
                        .thenComparing(g -> -g.findingsCount))
                .collect(Collectors.toList());

        int total = all.size();
        int pageSize = Math.min(Math.max(size, 1), 200);
        int from = Math.min(page * pageSize, total);
        int to = Math.min(from + pageSize, total);
        List<Map<String, Object>> content = all.subList(from, to).stream()
                .map(CveGroup::toMap).collect(Collectors.toList());

        out.put("content", content);
        out.put("totalElements", total);
        out.put("totalPages", (int) Math.ceil((double) total / pageSize));
        out.put("number", page);
        return out;
    }

    /** Drill-down: the affected resources/findings for one CVE. */
    public List<Map<String, Object>> getCveFindings(Long clientId, String accountId, String cveId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty() || cveId == null || cveId.isEmpty()) return List.of();

        // Group by affected RESOURCE (Upwind's framing). A single CVE can hit many distinct
        // packages on one resource (e.g. a Perl CVE flags 100 perl-* subpackages), which would
        // otherwise render as 100 near-identical rows. We collapse to one row per resource and
        // summarise: how many packages are affected, whether any is in use, whether a fix exists.
        Map<String, CveResourceAgg> byResource = new LinkedHashMap<>();
        for (UpwindVulnerabilityFinding v : vulnRepo.findByCloudAccountIdInAndCveIdOrderByLastScanTimeDesc(ids, cveId)) {
            String key = v.getResourceId() != null ? v.getResourceId()
                    : (v.getResourceName() != null ? v.getResourceName() : "unknown");
            CveResourceAgg agg = byResource.computeIfAbsent(key, k -> new CveResourceAgg());
            if (agg.resourceName == null) {
                agg.resourceId = v.getResourceId();
                agg.resourceName = v.getResourceName();
                agg.resourceType = v.getResourceType();
                agg.region = v.getRegion();
                agg.cloudProvider = v.getCloudProvider();
                agg.cvssScore = v.getCvssScore();
            }
            if (Boolean.TRUE.equals(v.getExploitable())) agg.exploitable = true;
            agg.firstSeen = earliest(agg.firstSeen, v.getFirstSeenTime());
            agg.lastScan = latest(agg.lastScan, v.getLastScanTime());

            // Package / image / in-use now come from promoted columns instead of
            // re-parsing raw_payload for every finding on every drawer open.
            if (v.getPackageName() != null) agg.packages.add(v.getPackageName());
            if (Boolean.TRUE.equals(v.getPackageInUse())) agg.anyInUse = true;
            if (v.getImageName() != null) agg.images.add(v.getImageName());
            if (Boolean.TRUE.equals(v.getInternetExposed())) agg.internetExposed = true;
            if (Boolean.TRUE.equals(v.getActiveCommunication())) agg.activeCommunication = true;
            if (Boolean.TRUE.equals(v.getExploitableFunctionsInUse())) agg.functionsInUse = true;
            if (agg.namespace == null) {
                agg.namespace = v.getNamespace();
                agg.clusterName = v.getClusterName();
                agg.packageVersion = v.getPackageVersion();
                agg.imageTag = v.getImageTag();
                // Base-image OS. Persisted since the runtime-context work but never exposed,
                // so the drill-down could not say which distro a finding came from.
                agg.imageOsName = v.getImageOsName();
                agg.imageOsVersion = v.getImageOsVersion();
                agg.imageOsName = v.getImageOsName();
                agg.imageOsVersion = v.getImageOsVersion();
            }

            // remediation has no promoted column, so it still needs the payload.
            JsonNode root = parseJson(v.getRawPayload());
            if (root != null) {
                if (agg.fixedInVersion == null) {
                    JsonNode rem = root.path("remediation");
                    if (rem.isArray()) {
                        for (JsonNode r : rem) {
                            String f = r.path("data").path("fixed_in_version").asText(null);
                            if (f != null && !f.isEmpty()) { agg.fixedInVersion = f; break; }
                        }
                    }
                }
            }
        }

        return byResource.values().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            // resourceId lets the UI open the cross-domain resource view from a CVE row.
            m.put("resourceId", a.resourceId);
            m.put("resourceName", a.resourceName);
            m.put("resourceType", a.resourceType);
            m.put("region", a.region);
            m.put("cloudProvider", a.cloudProvider);
            m.put("cvssScore", a.cvssScore);
            m.put("exploitable", a.exploitable);
            m.put("firstSeenTime", a.firstSeen);
            m.put("lastScanTime", a.lastScan);
            m.put("packageCount", a.packages.size());
            m.put("packageName", a.packages.size() == 1 ? a.packages.iterator().next()
                    : a.packages.size() + " packages");
            m.put("image", a.images.size() == 1 ? a.images.iterator().next()
                    : (a.images.isEmpty() ? null : a.images.size() + " images"));
            m.put("inUse", a.anyInUse);
            m.put("packageVersion", a.packageVersion);
            m.put("imageTag", a.imageTag);
            m.put("imageOsName", a.imageOsName);
            m.put("imageOsVersion", a.imageOsVersion);
            // Persisted since the runtime-context work but never exposed. The base image OS is
            // what tells you whether a CVE is even reachable in your distro's build.
            m.put("imageOsName", a.imageOsName);
            m.put("imageOsVersion", a.imageOsVersion);
            m.put("namespace", a.namespace);
            m.put("clusterName", a.clusterName);
            m.put("internetExposed", a.internetExposed);
            m.put("activeCommunication", a.activeCommunication);
            m.put("functionsInUse", a.functionsInUse);
            m.put("fixAvailable", a.fixedInVersion != null);
            m.put("fixedInVersion", a.fixedInVersion);
            return m;
        }).collect(Collectors.toList());
    }

    private static final class CveResourceAgg {
        String resourceId;
        String resourceName, resourceType, region, cloudProvider, cvssScore, fixedInVersion;
        String packageVersion, imageTag, namespace, clusterName, imageOsName, imageOsVersion;
        boolean exploitable, anyInUse, internetExposed, activeCommunication, functionsInUse;
        Instant firstSeen, lastScan;
        final Set<String> packages = new LinkedHashSet<>();
        final Set<String> images = new LinkedHashSet<>();
    }

    private static final List<String> VULN_SEVERITY_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    private static int vulnSeverityRank(String s) {
        int i = s == null ? -1 : VULN_SEVERITY_ORDER.indexOf(s.toUpperCase());
        return i < 0 ? Integer.MAX_VALUE : i;
    }
    private static boolean isHigherVulnSeverity(String candidate, String current) {
        if (candidate == null) return false;
        if (current == null) return true;
        return vulnSeverityRank(candidate) < vulnSeverityRank(current);
    }

    /** Per-CVE aggregate for the grouped Findings view. */
    private static final class CveGroup {
        final String cveId;
        String name;
        String severity;
        double cvss;
        double epss;
        boolean exploitable;
        boolean packageInUse;
        boolean internetExposed;
        boolean functionsInUse;
        int imageCount;
        int findingsCount;
        final Set<String> resources = new HashSet<>();
        Instant firstSeen;
        Instant lastScan;
        CveGroup(String cveId) { this.cveId = cveId; }
        /** Upwind's "runtime exposure": the package is loaded AND the workload takes ingress. */
        boolean runtimeExposed() { return packageInUse && internetExposed; }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cveId", cveId);
            m.put("name", name);
            m.put("severity", severity);
            m.put("cvssScore", cvss > 0 ? cvss : null);
            m.put("epssScore", epss > 0 ? epss : null);
            m.put("exploitable", exploitable);
            m.put("packageInUse", packageInUse);
            m.put("internetExposed", internetExposed);
            m.put("functionsInUse", functionsInUse);
            m.put("runtimeExposed", runtimeExposed());
            m.put("findingsCount", findingsCount);
            m.put("resourceCount", resources.size());
            m.put("imageCount", imageCount);
            m.put("firstSeen", firstSeen);
            m.put("lastScan", lastScan);
            return m;
        }
    }

    private static double parseDoubleSafe(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Per-CVE aggregate for the vulnerabilities dashboard. */
    private static final class CveAgg {
        String severity;
        String resourceType;
        boolean exploitable;
        double epss;
        Instant firstSeen;
    }

    public Page<UpwindPostureFinding> getPosture(Long clientId, String accountId,
                                                 String severity, int page, int size) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return Page.empty();
        // Unsorted page — the dedup query bakes in its own ordering; Spring only adds LIMIT/OFFSET.
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        String sev = (severity != null && !severity.isEmpty()) ? severity : null;
        // Collapse per-framework duplicates → one row per distinct (check, resource).
        return postureRepo.findDistinctFindings(ids, sev, pageable);
    }

    /** Highest-to-lowest severity ranking used to collapse a check's findings to one chip. */
    private static final List<String> SEVERITY_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    /**
     * Resource types that carry the {@code hasExposure} tag but are network/identity plumbing,
     * not actual exposed workloads/data. Excluded from the Attack Surface so counts reflect
     * real exposed resources (VMs, storage, load balancers, …) like Upwind's ASM view.
     */
    private static final Set<String> NON_RESOURCE_EXPOSURE_TYPES = Set.of(
            "AWS_EC2_SECURITY_GROUP", "AWS_EC2_SUBNET", "AWS_EC2_NETWORK_ACL",
            "AWS_EC2_ROUTE_TABLE", "AWS_EC2_VPC", "AWS_VPC", "AWS_EC2_INTERNET_GATEWAY",
            "AWS_ROUTE53_DNS_RECORD", "AWS_ACCOUNT", "AWS_IAM_USER");

    /**
     * The Issues view: failing posture findings collapsed into one row per check.
     * This is the actionable altitude — ~dozens of issues instead of thousands of raw
     * per-resource findings. Optionally filtered to a single severity.
     */
    public List<Map<String, Object>> getIssues(Long clientId, String accountId, String severity) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return List.of();

        // Group failing findings by check (fall back to title when the check title is absent).
        Map<String, IssueAccumulator> byCheck = new LinkedHashMap<>();
        for (Object[] r : postureRepo.findFailingForIssues(ids)) {
            String checkTitle = (String) r[0];
            String title = (String) r[1];
            String key = (checkTitle != null && !checkTitle.isBlank()) ? checkTitle : title;
            if (key == null) continue;
            IssueAccumulator acc = byCheck.computeIfAbsent(key, k -> new IssueAccumulator(k));
            acc.title = acc.title != null ? acc.title : (title != null ? title : checkTitle);
            // projection: severity=r[2], frameworkTitle=r[3], firstSeen=r[4], lastSeen=r[5], resourceId=r[6]
            acc.consider((String) r[6], (String) r[2], (String) r[3], (Instant) r[4], (Instant) r[5]);
        }

        return byCheck.values().stream()
                .filter(a -> severity == null || severity.isEmpty()
                        || severity.equalsIgnoreCase(a.topSeverity()))
                .sorted(Comparator
                        .comparingInt((IssueAccumulator a) -> SEVERITY_ORDER.indexOf(a.topSeverity()))
                        .thenComparing(a -> -a.resourceCount()))
                .map(IssueAccumulator::toMap)
                .collect(Collectors.toList());
    }

    /**
     * Detail for a single issue (check): its description/remediation (parsed from a
     * representative finding's raw payload) plus every affected resource.
     */
    public Map<String, Object> getIssueDetail(Long clientId, String accountId, String check) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        Map<String, Object> detail = new LinkedHashMap<>();
        if (ids.isEmpty() || check == null || check.isEmpty()) return detail;

        List<UpwindPostureFinding> findings = postureRepo.findFailingByCheck(ids, check);
        if (findings.isEmpty()) return detail;

        String topSeverity = findings.stream()
                .map(UpwindPostureFinding::getSeverity)
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(s -> {
                    int i = SEVERITY_ORDER.indexOf(s.toUpperCase());
                    return i < 0 ? Integer.MAX_VALUE : i;
                }))
                .orElse(null);

        Set<String> frameworks = new LinkedHashSet<>();
        Set<String> seenResources = new HashSet<>();
        Instant firstSeen = null, lastEvaluation = null;
        List<Map<String, Object>> resources = new ArrayList<>();
        for (UpwindPostureFinding f : findings) {
            if (f.getFrameworkTitle() != null) frameworks.add(f.getFrameworkTitle());
            firstSeen = earliest(firstSeen, f.getFirstSeenTime());
            lastEvaluation = latest(lastEvaluation, f.getLastSeenTime());
            // Dedupe the affected-resources table — the same resource repeats per framework.
            String rid = f.getResourceId() != null ? f.getResourceId() : f.getResourceName();
            if (rid != null && !seenResources.add(rid)) continue;
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("resourceId", f.getResourceId());
            res.put("resourceName", f.getResourceName());
            res.put("resourceType", f.getResourceType());
            res.put("cloudProvider", f.getCloudProvider());
            res.put("cloudAccountId", f.getCloudAccountId());
            res.put("region", f.getRegion());
            res.put("severity", f.getSeverity());
            res.put("lastSeenTime", f.getLastSeenTime());
            resources.add(res);
        }

        UpwindPostureFinding sample = findings.get(0);
        JsonNode checkNode = parseCheckNode(sample.getRawPayload());
        detail.put("title", check);
        detail.put("severity", topSeverity);
        detail.put("description", checkNode == null ? null : textOrNull(checkNode, "description"));
        detail.put("remediation", checkNode == null ? null : textOrNull(checkNode, "remediation"));
        detail.put("frameworks", new ArrayList<>(frameworks));
        detail.put("firstSeen", firstSeen);
        detail.put("lastEvaluation", lastEvaluation);
        detail.put("resourceCount", resources.size());
        detail.put("resources", resources);
        return detail;
    }

    private static JsonNode parseJson(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode parseCheckNode(String rawPayload) {
        if (rawPayload == null || rawPayload.isEmpty()) return null;
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode check = root.path("check");
            return check.isMissingNode() ? null : check;
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    /**
     * Mutable per-check aggregate for the Issues / Attack-Surface lists.
     * Upwind returns one configuration-finding per (check × resource × framework),
     * so the same resource repeats across many compliance frameworks. We therefore
     * count DISTINCT resources (matching Upwind's counts) rather than raw rows.
     */
    private static final class IssueAccumulator {
        final String check;
        String title;
        final Set<String> frameworks = new LinkedHashSet<>();
        final Set<String> resources = new HashSet<>();
        String severity;          // highest severity seen so far
        Instant firstSeen;
        Instant lastEvaluation;

        IssueAccumulator(String check) { this.check = check; }

        void consider(String resourceId, String sev, String framework, Instant first, Instant last) {
            if (resourceId != null) resources.add(resourceId);
            if (framework != null) frameworks.add(framework);
            if (isHigherSeverity(sev, severity)) severity = sev;
            firstSeen = earliest(firstSeen, first);
            lastEvaluation = latest(lastEvaluation, last);
        }

        int resourceCount() { return resources.size(); }

        String topSeverity() { return severity == null ? "LOW" : severity.toUpperCase(); }

        private static boolean isHigherSeverity(String candidate, String current) {
            if (candidate == null) return false;
            if (current == null) return true;
            int ci = SEVERITY_ORDER.indexOf(candidate.toUpperCase());
            int cu = SEVERITY_ORDER.indexOf(current.toUpperCase());
            if (ci < 0) return false;
            if (cu < 0) return true;
            return ci < cu;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("check", check);
            m.put("title", title != null ? title : check);
            m.put("severity", topSeverity());
            m.put("resourceCount", resources.size());
            m.put("frameworks", new ArrayList<>(frameworks));
            m.put("firstSeen", firstSeen);
            m.put("lastEvaluation", lastEvaluation);
            return m;
        }
    }

    /**
     * Attack Surface: exposure-related failing findings (resources with the {@code hasExposure}
     * risk category), collapsed by check. Mirrors Upwind's ASM dashboard from data we sync —
     * exposed-resource count, breakdown by resource type, severity summary, and the grouped
     * external-exposure findings. There is no dedicated ASM API; this is derived, not fetched.
     */
    public Map<String, Object> getAttackSurface(Long clientId, String accountId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exposedResourceCount", 0);
        out.put("exposedByType", List.of());
        out.put("severityCounts", Map.of());
        out.put("findings", List.of());

        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return out;

        Map<String, IssueAccumulator> byCheck = new LinkedHashMap<>();
        Map<String, String> checkResourceKind = new HashMap<>();
        Set<String> distinctResources = new HashSet<>();
        Map<String, Set<String>> resourcesByType = new LinkedHashMap<>();
        Map<String, Set<String>> resourcesByCountry = new LinkedHashMap<>();

        for (Object[] r : postureRepo.findExposureFailings(ids)) {
            String checkTitle = (String) r[0];
            String title = (String) r[1];
            String severity = (String) r[2];
            String resourceId = (String) r[3];
            String resourceType = (String) r[5];
            Instant first = toInstant(r[6]);
            Instant last = toInstant(r[7]);
            String framework = (String) r[8];
            String region = (String) r[9];

            // Only count actual exposed workloads/data — skip network/identity/account plumbing.
            if (resourceType != null && NON_RESOURCE_EXPOSURE_TYPES.contains(resourceType)) continue;

            String key = (checkTitle != null && !checkTitle.isBlank()) ? checkTitle : title;
            if (key == null) continue;
            // Attack Surface = genuine external exposure only. hasExposure also tags general
            // posture (encryption, IMDSv2, logging…); those aren't ASM findings — drop them.
            if ("Other exposure".equals(classifyAsmImpact(key))) continue;
            IssueAccumulator acc = byCheck.computeIfAbsent(key, IssueAccumulator::new);
            acc.title = acc.title != null ? acc.title : (title != null ? title : checkTitle);
            acc.consider(resourceId, severity, framework, first, last);
            if (resourceType != null) checkResourceKind.putIfAbsent(key, resourceType);

            if (resourceId != null) {
                distinctResources.add(resourceId);
                resourcesByType.computeIfAbsent(resourceType == null ? "other" : resourceType,
                        k -> new HashSet<>()).add(resourceId);
                resourcesByCountry.computeIfAbsent(regionToCountry(region), k -> new HashSet<>())
                        .add(resourceId);
            }
        }

        List<Map<String, Object>> findings = byCheck.values().stream()
                .sorted(Comparator
                        .comparingInt((IssueAccumulator a) -> SEVERITY_ORDER.indexOf(a.topSeverity()))
                        .thenComparing(a -> -a.resourceCount()))
                .map(a -> {
                    Map<String, Object> m = a.toMap();
                    m.put("resourceKind", checkResourceKind.get(a.check));
                    m.put("impact", classifyAsmImpact(a.check));
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Long> severityCounts = new LinkedHashMap<>();
        for (String sev : SEVERITY_ORDER) {
            long c = findings.stream().filter(f -> sev.equals(f.get("severity"))).count();
            if (c > 0) severityCounts.put(sev, c);
        }

        List<Map<String, Object>> exposedByType = resourcesByType.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", e.getKey());
                    m.put("count", e.getValue().size());
                    return m;
                })
                .sorted(Comparator.comparingInt(m -> -((Integer) m.get("count"))))
                .collect(Collectors.toList());

        List<Map<String, Object>> exposedByLocation = resourcesByCountry.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("country", e.getKey());
                    m.put("coordinates", COUNTRY_COORDS.getOrDefault(e.getKey(), new double[]{0, 0}));
                    m.put("count", e.getValue().size());
                    return m;
                })
                .sorted(Comparator.comparingInt(m -> -((Integer) m.get("count"))))
                .collect(Collectors.toList());

        out.put("exposedResourceCount", distinctResources.size());
        out.put("exposedByType", exposedByType);
        out.put("exposedByLocation", exposedByLocation);
        out.put("severityCounts", severityCounts);
        out.put("findings", findings);
        return out;
    }

    /** AWS region prefix → country for the exposed-resources map. */
    private static String regionToCountry(String region) {
        if (region == null || region.isBlank()) return "Unknown";
        String r = region.toLowerCase();
        if (r.startsWith("ap-south")) return "India";
        if (r.startsWith("ap-southeast-1")) return "Singapore";
        if (r.startsWith("ap-southeast-2")) return "Australia";
        if (r.startsWith("ap-northeast-1") || r.startsWith("ap-northeast-3")) return "Japan";
        if (r.startsWith("ap-northeast-2")) return "South Korea";
        if (r.startsWith("ap-east")) return "Hong Kong";
        if (r.startsWith("us-east") || r.startsWith("us-west") || r.startsWith("us-gov")) return "United States";
        if (r.startsWith("ca-")) return "Canada";
        if (r.startsWith("sa-")) return "Brazil";
        if (r.startsWith("eu-west-1")) return "Ireland";
        if (r.startsWith("eu-west-2")) return "United Kingdom";
        if (r.startsWith("eu-west-3")) return "France";
        if (r.startsWith("eu-central")) return "Germany";
        if (r.startsWith("eu-north")) return "Sweden";
        if (r.startsWith("eu-south")) return "Italy";
        if (r.startsWith("me-")) return "UAE";
        if (r.startsWith("af-")) return "South Africa";
        return region;
    }

    /** Approx [longitude, latitude] per country for the globe markers. */
    private static final Map<String, double[]> COUNTRY_COORDS = Map.ofEntries(
            Map.entry("India", new double[]{78.96, 20.59}),
            Map.entry("United States", new double[]{-98.58, 39.83}),
            Map.entry("Singapore", new double[]{103.82, 1.35}),
            Map.entry("Australia", new double[]{133.78, -25.27}),
            Map.entry("Japan", new double[]{138.25, 36.20}),
            Map.entry("South Korea", new double[]{127.77, 35.91}),
            Map.entry("Hong Kong", new double[]{114.11, 22.40}),
            Map.entry("Canada", new double[]{-106.35, 56.13}),
            Map.entry("Brazil", new double[]{-51.93, -14.24}),
            Map.entry("Ireland", new double[]{-8.24, 53.41}),
            Map.entry("United Kingdom", new double[]{-3.44, 55.38}),
            Map.entry("France", new double[]{2.21, 46.23}),
            Map.entry("Germany", new double[]{10.45, 51.17}),
            Map.entry("Sweden", new double[]{18.64, 60.13}),
            Map.entry("Italy", new double[]{12.57, 41.87}),
            Map.entry("UAE", new double[]{53.85, 23.42}),
            Map.entry("South Africa", new double[]{22.94, -30.56}));

    /**
     * Replicates Upwind's ASM "impact" classification for an exposure check. Upwind's ASM
     * playbooks are deterministic Configuration Validators; the impact label is their taxonomy,
     * not an API field, so we reproduce it with this curated mapping. Unmapped exposure checks
     * fall under "Other exposure".
     */
    private static String classifyAsmImpact(String checkTitle) {
        if (checkTitle == null) return "Other exposure";
        String t = checkTitle.toLowerCase();
        // Data readable without proper controls → Authenticated exposure (Upwind's grouping).
        if (t.contains("public read") || t.contains("anonymous read")
                || (t.contains("publicly accessible") && t.contains("read"))
                || t.contains("public access block"))
            return "Authenticated exposure";
        // Publicly reachable over the network → Public exposure.
        if (t.contains("publicly exposed") || t.contains("public ip") || t.contains("publicly accessible")
                || t.contains("unencrypted http") || t.contains("0.0.0.0") || t.contains("unrestricted")
                || t.contains("internet") || t.contains("wide"))
            return "Public exposure";
        return "Other exposure";
    }

    private static Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant) return (Instant) o;
        if (o instanceof java.sql.Timestamp) return ((java.sql.Timestamp) o).toInstant();
        if (o instanceof java.time.OffsetDateTime) return ((java.time.OffsetDateTime) o).toInstant();
        return null;
    }

    /** Normalizes a framework title for reliable matching: trim, collapse internal
     *  whitespace to single spaces, lowercase. */
    private static String normalizeFramework(String title) {
        if (title == null) return "";
        return title.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    public List<Map<String, Object>> getCompliance(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return List.of();

        String cacheKey = "xamsecure:compliance:" + clientId + ":" + (accountId == null ? "all" : accountId);
        Optional<List<Map<String, Object>>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<Map<String, Object>>>() {});
        if (cached.isPresent()) return cached.get();

        // Tenant-filtered pass/fail per framework (from synced posture findings).
        // Keyed by a NORMALIZED title (trim + collapse whitespace + lowercase) so it
        // reliably merges with Upwind's framework metadata even when titles differ by
        // stray whitespace/case (e.g. "NIST  SP 800-53 AWS" vs "NIST SP 800-53 AWS").
        Map<String, Map<String, Long>> byFramework = new LinkedHashMap<>();
        for (Object[] row : postureRepo.countByFrameworkAndStatus(ids)) {
            String framework = (String) row[0];
            if (framework == null) continue;
            String status = (String) row[1];
            Long count = (Long) row[2];
            byFramework.computeIfAbsent(normalizeFramework(framework), k -> new HashMap<>()).put(status, count);
        }

        // Framework metadata (title, version, status, score, last scan) from Upwind,
        // keyed by normalized title; keep the canonical display title from Upwind.
        Map<String, JsonNode> meta = new LinkedHashMap<>();
        Map<String, String> displayTitle = new LinkedHashMap<>();
        if (apiClient.isConfigured()) {
            try {
                for (JsonNode fw : apiClient.fetchConfigurationFrameworks(10)) {
                    String title = fw.path("title").asText(null);
                    if (title != null) {
                        String key = normalizeFramework(title);
                        meta.put(key, fw);
                        displayTitle.putIfAbsent(key, title);
                    }
                }
            } catch (Exception e) {
                log.warn("[Upwind] framework metadata fetch failed: {}", e.getMessage());
            }
        }

        // v2 adds `state`, which is the difference between "0% compliant" and "never evaluated
        // here". Without it a framework with no findings is indistinguishable from one that
        // failed everything, and the frameworks grid was hiding those rows on a heuristic
        // instead of saying why. Merged rather than replacing v1: v1 is the source of the
        // canonical title/version/status this map is already keyed on.
        Map<String, JsonNode> metaV2 = new LinkedHashMap<>();
        if (apiClient.isConfigured()) {
            for (JsonNode fw : apiClient.searchFrameworksV2(FRAMEWORK_MAX)) {
                String title = fw.path("title").asText(null);
                if (title != null) metaV2.putIfAbsent(normalizeFramework(title), fw);
            }
        }

        // Union of normalized keys: frameworks Upwind knows about + any that produced findings.
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(meta.keySet());
        allKeys.addAll(byFramework.keySet());
        // v2-only frameworks are deliberately NOT added here: v1 is the enabled-framework list,
        // and v2 returns the whole catalogue. Adding them would put a hundred never-enabled
        // frameworks in the grid.

        List<Map<String, Object>> result = allKeys.stream().map(key -> {
            Map<String, Long> counts = byFramework.getOrDefault(key, Map.of());
            JsonNode fw = meta.get(key);
            Map<String, Object> m = new LinkedHashMap<>();
            // Prefer Upwind's canonical title; fall back to whatever the posture data used.
            m.put("framework", displayTitle.getOrDefault(key, key));
            m.put("failCount", counts.getOrDefault("FAIL", 0L));
            m.put("passCount", counts.getOrDefault("PASS", 0L));
            if (fw != null) {
                m.put("framework", fw.path("title").asText((String) m.get("framework")));
                // The id is what the per-control drill-down queries on; without it the drawer
                // has nothing to ask for, which is why unmatched frameworks hide that section.
                m.put("frameworkId", textOrNull(fw, "id"));
                m.put("version", textOrNull(fw, "version"));
                m.put("status", textOrNull(fw, "status"));
                m.put("lastScanTime", textOrNull(fw, "last_scan_time"));
                JsonNode cs = fw.path("compliance_status");
                m.put("score", cs.has("score") ? cs.get("score").asInt() : null);
            }
            JsonNode v2 = metaV2.get(key);
            if (v2 != null) {
                m.put("state", textOrNull(v2, "state"));
                // v2 fields are a fallback only, so a v1 value is never overwritten by a null.
                if (m.get("score") == null && v2.hasNonNull("score")) {
                    m.put("score", v2.get("score").asInt());
                }
                if (m.get("lastScanTime") == null) {
                    m.put("lastScanTime", textOrNull(v2, "last_evaluation_time"));
                }
                if (m.get("frameworkId") == null) m.put("frameworkId", textOrNull(v2, "id"));
            }
            return m;
        }).collect(Collectors.toList());

        redisCacheService.put(cacheKey, result, APISEC_CACHE_MINUTES);
        return result;
    }

    /**
     * Configurations "Dashboard" sub-view (Upwind parity). Every metric is derived from
     * real synced posture findings and real per-framework compliance scores — nothing is
     * fabricated. We deliberately OMIT Upwind panels we have no data source for:
     * per-finding "impact %" and remediation-difficulty (not exposed by the API) and the
     * resolved-findings-over-time count (no resolution history is retained).
     */
    public Map<String, Object> getConfigurationsDashboard(Long clientId, String accountId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> emptySev = new LinkedHashMap<>();
        for (String s : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) emptySev.put(s, 0);
        out.put("severityCounts", emptySev);
        out.put("totalFindings", 0);
        out.put("newLast7Days", 0);
        out.put("topFindings", List.of());
        out.put("findingsOverTime", List.of());
        out.put("frameworkScores", List.of());
        out.put("avgComplianceScore", null);
        out.put("frameworkCount", 0);

        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return out;

        // Failing findings collapsed to one row per check (distinct-resource counts).
        List<Map<String, Object>> issues = getIssues(clientId, accountId, null);

        // Severity summary + "new in last 7 days" + cumulative-over-time buckets.
        Map<String, Integer> sev = new LinkedHashMap<>();
        for (String s : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) sev.put(s, 0);
        Instant weekAgo = Instant.now().minus(java.time.Duration.ofDays(7));
        int newCount = 0;

        java.time.LocalDate todayUtc = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        java.util.List<java.time.LocalDate> trendDays = new ArrayList<>();
        for (int i = 6; i >= 0; i--) trendDays.add(todayUtc.minusDays(i));
        long[] newHigh = new long[7], newOther = new long[7];

        for (Map<String, Object> is : issues) {
            String s = String.valueOf(is.getOrDefault("severity", "LOW")).toUpperCase();
            sev.merge(sev.containsKey(s) ? s : "LOW", 1, Integer::sum);
            Object fsObj = is.get("firstSeen");
            Instant first = (fsObj instanceof Instant) ? (Instant) fsObj : null;
            if (first != null && first.isAfter(weekAgo)) newCount++;
            int idx = trendIndex(first, trendDays);
            if (idx >= 0) {
                String bucket = sevBucket(s);
                if ("CRITICAL".equals(bucket) || "HIGH".equals(bucket)) newHigh[idx]++;
                else newOther[idx]++;
            }
        }
        out.put("severityCounts", sev);
        out.put("totalFindings", issues.size());
        out.put("newLast7Days", newCount);

        // Top findings — issues are already sorted by severity, then affected-resource count.
        out.put("topFindings", issues.stream().limit(20).collect(Collectors.toList()));

        // Findings over time — cumulative-as-of-day (High vs Other), last 7 days.
        List<Map<String, Object>> overTime = new ArrayList<>();
        long cHigh = 0, cOther = 0;
        for (int i = 0; i < 7; i++) {
            cHigh += newHigh[i]; cOther += newOther[i];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", trendDays.get(i).toString().substring(5)); // MM-DD
            m.put("HIGH", cHigh);
            m.put("OTHER", cOther);
            overTime.add(m);
        }
        out.put("findingsOverTime", overTime);

        // Per-framework compliance scores (real). Only frameworks Upwind actually scanned
        // against our assets (lastScanTime present) so unscanned 0%/N-A frameworks don't
        // pollute the panel or drag the average. Sorted worst-first, like Upwind highlights.
        List<Map<String, Object>> compliance = getCompliance(clientId, accountId);
        List<Map<String, Object>> fwScores = compliance.stream()
                .filter(m -> m.get("score") instanceof Integer && m.get("lastScanTime") != null)
                .sorted(Comparator.comparingInt(m -> (Integer) m.get("score")))
                .collect(Collectors.toList());
        out.put("frameworkScores", fwScores);
        out.put("frameworkCount", fwScores.size());
        java.util.OptionalDouble avg = fwScores.stream().mapToInt(m -> (Integer) m.get("score")).average();
        out.put("avgComplianceScore", avg.isPresent() ? (int) Math.round(avg.getAsDouble()) : null);

        return out;
    }

    /** Asset counts per category for the Inventory catalog rail (tenant-filtered). */
    public List<Map<String, Object>> getInventoryCategories(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return List.of();
        return inventoryRepo.countByCategory(ids).stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("category", r[0]);          // null → "uncategorized" on the client
                    m.put("count", r[1]);
                    return m;
                })
                .sorted(Comparator.comparingLong(m -> -((Long) m.get("count"))))
                .collect(Collectors.toList());
    }

    /**
     * Data-classification and exposure rollup over the synced inventory catalog: how many
     * assets Upwind sees holding PII/PHI/PCI, running elevated privilege, running
     * end-of-life software, or reachable from the internet.
     *
     * These come from the catalog asset's {@code sensitive_data_at_rest} /
     * {@code sensitive_data_in_transit} / {@code high_privilege_risk} / {@code technologies}
     * / {@code network_risk} objects. They were unavailable while the sync used the flat
     * {@code /inventory/assets/search}, which returns none of them.
     */
    public Map<String, Object> getInventoryRisk(Long clientId, String accountId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("assets", "pii", "phi", "pci",
                "elevatedPrivilege", "eolTechnology", "internetExposed")) {
            out.put(k, 0L);
        }
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return out;
        List<Object[]> rows = inventoryRepo.riskRollup(ids);
        if (rows.isEmpty() || rows.get(0) == null) return out;
        Object[] r = rows.get(0);
        String[] keys = {"assets", "pii", "phi", "pci",
                "elevatedPrivilege", "eolTechnology", "internetExposed"};
        for (int i = 0; i < keys.length && i < r.length; i++) {
            out.put(keys[i], r[i] == null ? 0L : ((Number) r[i]).longValue());
        }
        return out;
    }

    /**
     * Best-effort STATIC network topology from the inventory catalog — a stand-in for
     * Upwind's Runtime map (whose live traffic/flow data is not exposed by the API).
     * Returns the networking asset types and their counts, to render as a hub-and-spoke
     * of the account's network configuration. No runtime traffic — config inventory only.
     */
    public Map<String, Object> getNetworkTopology(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", accountId);
        out.put("accounts", ids.size());
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (Object[] r : inventoryRepo.countBySubCategory(ids, "networking")) {
                if (r[0] == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subCategory", r[0]);
                m.put("count", r[1]);
                nodes.add(m);
            }
            nodes.sort(Comparator.comparingLong(m -> -((Long) m.get("count"))));
        }
        out.put("nodes", nodes);
        return out;
    }

    public Page<UpwindInventoryAsset> getInventory(Long clientId, String accountId,
                                                   String category, int page, int size) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty()) return Page.empty();
        Pageable pageable = PageRequest.of(page, Math.min(size, 200));
        if (category != null && !category.isEmpty()) {
            return inventoryRepo.findActiveByAccountsAndCategory(ids, category, pageable);
        }
        return inventoryRepo.findActiveByAccounts(ids, pageable);
    }

    public List<Map<String, Object>> getOnboardingStatus(Long clientId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByClientId(clientId);
        List<String> ids = accounts.stream()
                .map(CloudAccount::getProviderAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<String, UpwindAccountConnection> connections = connectionRepo
                .findByCloudAccountIdIn(ids).stream()
                .collect(Collectors.toMap(UpwindAccountConnection::getCloudAccountId, c -> c));
        return accounts.stream()
                .filter(a -> a.getProviderAccountId() != null)
                .map(a -> {
                    UpwindAccountConnection c = connections.get(a.getProviderAccountId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("accountName", a.getAccountName());
                    m.put("cloudAccountId", a.getProviderAccountId());
                    m.put("provider", a.getProvider());
                    m.put("status", c == null ? "NOT_CONNECTED" : c.getStatus());
                    m.put("sensorInstallCommand", c == null ? null : c.getSensorInstallCommand());
                    return m;
                }).collect(Collectors.toList());
    }

    @org.springframework.transaction.annotation.Transactional
    public void updateThreatStatus(Long clientId, String upwindId, String status) {
        UpwindThreatDetection d = detectionRepo.findByUpwindId(upwindId)
                .orElseThrow(() -> new AccessDeniedException("Not found"));
        List<String> ids = resolveAccountIds(clientId, null);
        if (d.getCloudAccountId() == null || !ids.contains(d.getCloudAccountId())) {
            throw new AccessDeniedException("Detection not accessible");
        }
        apiClient.updateDetectionStatus(upwindId, status);
        d.setStatus(status);
        detectionRepo.save(d);
    }

    private static Map<String, Long> toSeverityMap(List<Object[]> rows) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) m.put((String) row[0], (Long) row[1]);
        }
        return m;
    }
}
