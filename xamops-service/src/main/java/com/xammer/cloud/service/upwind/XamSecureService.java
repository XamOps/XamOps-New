package com.xammer.cloud.service.upwind;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.*;
import com.xammer.cloud.domain.UpwindInventoryAsset;
import com.xammer.cloud.repository.*;
import com.xammer.cloud.repository.UpwindInventoryAssetRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final CloudAccountRepository cloudAccountRepository;
    private final UpwindThreatDetectionRepository detectionRepo;
    private final UpwindVulnerabilityFindingRepository vulnRepo;
    private final UpwindPostureFindingRepository postureRepo;
    private final UpwindSyncStateRepository syncStateRepo;
    private final UpwindAccountConnectionRepository connectionRepo;
    private final RedisCacheService redisCacheService;
    private final UpwindInventoryAssetRepository inventoryRepo;
    private final UpwindApiClient apiClient;

    public XamSecureService(CloudAccountRepository cloudAccountRepository,
                            UpwindThreatDetectionRepository detectionRepo,
                            UpwindVulnerabilityFindingRepository vulnRepo,
                            UpwindPostureFindingRepository postureRepo,
                            UpwindSyncStateRepository syncStateRepo,
                            UpwindAccountConnectionRepository connectionRepo,
                            RedisCacheService redisCacheService,
                            UpwindInventoryAssetRepository inventoryRepo,
                            UpwindApiClient apiClient) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.detectionRepo = detectionRepo;
        this.vulnRepo = vulnRepo;
        this.postureRepo = postureRepo;
        this.syncStateRepo = syncStateRepo;
        this.connectionRepo = connectionRepo;
        this.redisCacheService = redisCacheService;
        this.inventoryRepo = inventoryRepo;
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
                rows.add(m);
            }
        } catch (Exception e) {
            log.warn("[Upwind] threat-policies fetch failed: {}", e.getMessage());
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

    public List<Map<String, Object>> getApiSecurity(Long clientId, String accountId) {
        List<String> ids = resolveAccountIds(clientId, accountId);
        if (ids.isEmpty() || !apiClient.isConfigured()) return List.of();
        String cacheKey = "xamsecure:apisec:" + clientId + ":" + (accountId == null ? "all" : accountId);
        Optional<List<Map<String, Object>>> cached =
                redisCacheService.get(cacheKey, new TypeReference<List<Map<String, Object>>>() {});
        if (cached.isPresent()) return cached.get();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : ids) {
            try {
                for (JsonNode ep : apiClient.fetchApiSecurityEndpoints(id, 12)) {
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
            // all severities and rely on the per-account post-filter below for tenancy.
            List<Map<String, Object>> conditions = List.of(
                    Map.of("field", "severity", "operator", "in",
                            "value", List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")));
            Set<String> owned = new HashSet<>(ids);
            List<Map<String, Object>> stories = new ArrayList<>();
            int active = 0;
            for (JsonNode s : apiClient.searchThreatStories(conditions, 100)) {
                // Defence in depth: never surface a story outside the caller's accounts.
                String acct = firstNonNull(
                        s.path("resource").path("cloud_account_id").asText(null),
                        s.path("cloud_account_id").asText(null));
                if (acct != null && !owned.contains(acct)) continue;

                String status = s.path("status").asText(null);
                if (isActiveStory(status)) active++;
                if (stories.size() < 10) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.path("id").asText(null));
                    m.put("title", firstNonNull(s.path("title").asText(null), s.path("name").asText(null)));
                    m.put("severity", s.path("severity").asText(null));
                    m.put("status", status);
                    m.put("resourceName", firstNonNull(
                            s.path("resource").path("name").asText(null), acct));
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

    private static boolean isActiveStory(String status) {
        if (status == null) return true; // absent status → treat as active (matches console default)
        String s = status.toLowerCase();
        return !(s.contains("resolved") || s.contains("closed") || s.contains("archived") || s.contains("dismissed"));
    }

    private static String firstNonNull(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    /**
     * Resolves the provider account ids (AWS account / GCP project / Azure
     * subscription) owned by the client. If requestedAccountId is given it
     * must be one of them, otherwise AccessDeniedException.
     */
    public List<String> resolveAccountIds(Long clientId, String requestedAccountId) {
        List<String> owned = cloudAccountRepository.findByClientId(clientId).stream()
                .map(CloudAccount::getProviderAccountId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (requestedAccountId == null || requestedAccountId.isEmpty()) {
            return owned;
        }
        if (!owned.contains(requestedAccountId)) {
            throw new AccessDeniedException("Account not accessible: " + requestedAccountId);
        }
        return List.of(requestedAccountId);
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

        // Runtime exploit funnel (Upwind order). Stages we can source from data; the rest
        // (internet exposure, NIST critical, functions-in-use) need runtime signal we don't have → 0.
        int totalCves = byCve.size();
        long packageInUse = vulnRepo.countCvesPackageInUse(ids);
        long fixAvailable = vulnRepo.countCvesFixAvailable(ids);
        List<Map<String, Object>> funnel = new ArrayList<>();
        funnel.add(funnelStage("Total", totalCves, true));
        funnel.add(funnelStage("Package in use", (int) packageInUse, true));
        funnel.add(funnelStage("Internet exposure", 0, false));
        funnel.add(funnelStage("Known exploit", exploitable, true));
        funnel.add(funnelStage("Fix available", (int) fixAvailable, true));
        funnel.add(funnelStage("NIST critical sev", 0, false));
        funnel.add(funnelStage("EPSS-prioritized", epssPrioritized, true));
        funnel.add(funnelStage("Functions in Use", 0, false));

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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of());
        out.put("totalElements", 0);
        out.put("totalPages", 0);
        out.put("number", page);

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
        }

        List<CveGroup> all = byCve.values().stream()
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
                agg.resourceName = v.getResourceName();
                agg.resourceType = v.getResourceType();
                agg.region = v.getRegion();
                agg.cloudProvider = v.getCloudProvider();
                agg.cvssScore = v.getCvssScore();
            }
            if (Boolean.TRUE.equals(v.getExploitable())) agg.exploitable = true;
            agg.firstSeen = earliest(agg.firstSeen, v.getFirstSeenTime());
            agg.lastScan = latest(agg.lastScan, v.getLastScanTime());

            JsonNode root = parseJson(v.getRawPayload());
            if (root != null) {
                JsonNode pkg = root.path("package");
                String pkgName = textOrNull(pkg, "name");
                if (pkgName != null) agg.packages.add(pkgName);
                if (pkg.path("in_use").asBoolean(false)) agg.anyInUse = true;
                String img = textOrNull(root.path("image"), "name");
                if (img != null) agg.images.add(img);
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
            m.put("fixAvailable", a.fixedInVersion != null);
            m.put("fixedInVersion", a.fixedInVersion);
            return m;
        }).collect(Collectors.toList());
    }

    private static final class CveResourceAgg {
        String resourceName, resourceType, region, cloudProvider, cvssScore, fixedInVersion;
        boolean exploitable, anyInUse;
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
        int findingsCount;
        final Set<String> resources = new HashSet<>();
        Instant firstSeen;
        Instant lastScan;
        CveGroup(String cveId) { this.cveId = cveId; }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cveId", cveId);
            m.put("name", name);
            m.put("severity", severity);
            m.put("cvssScore", cvss > 0 ? cvss : null);
            m.put("epssScore", epss > 0 ? epss : null);
            m.put("exploitable", exploitable);
            m.put("findingsCount", findingsCount);
            m.put("resourceCount", resources.size());
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

        // Union of normalized keys: frameworks Upwind knows about + any that produced findings.
        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(meta.keySet());
        allKeys.addAll(byFramework.keySet());

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
                m.put("version", textOrNull(fw, "version"));
                m.put("status", textOrNull(fw, "status"));
                m.put("lastScanTime", textOrNull(fw, "last_scan_time"));
                JsonNode cs = fw.path("compliance_status");
                m.put("score", cs.has("score") ? cs.get("score").asInt() : null);
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
            return inventoryRepo.findByCloudAccountIdInAndAssetCategoryOrderByNameAsc(ids, category, pageable);
        }
        return inventoryRepo.findByCloudAccountIdInOrderByNameAsc(ids, pageable);
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
