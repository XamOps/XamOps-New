package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.securitycenter.v2.Finding;
import com.google.cloud.securitycenter.v2.ListFindingsRequest;
import com.google.cloud.securitycenter.v2.SecurityCenterClient;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.ProwlerFinding;
import com.xammer.cloud.dto.gcp.GcpContainerVulnerabilityDto;
import com.xammer.cloud.dto.gcp.GcpIamPolicyDriftDto;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GcpSecurityService {

    private final GcpClientProvider gcpClientProvider;
    private final RedisCacheService redisCacheService;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final ProwlerService prowlerService;

    private final Executor executor = Executors.newCachedThreadPool();

    private static final String REDIS_SECURITY_PREFIX   = "gcp:security-findings:";
    private static final String REDIS_IAM_DRIFT_PREFIX  = "gcp:iam-policy-drift:";
    private static final String REDIS_CONTAINER_PREFIX  = "gcp:container-scanning:";
    private static final int    REDIS_TTL_MINUTES       = 1440; // Gap D: 24h aligned with staleness threshold

    private static final String[] GCP_PROWLER_FRAMEWORKS = {
            "cis_2.0_gcp", "soc2_gcp", "pci_3.2.1_gcp",
            "iso27001_2013_gcp", "mitre_attack_gcp",
            "hipaa_gcp", "gdpr_gcp", "nist_csf_1.1_gcp"
    };

    private static final Map<String, List<GcpIamPolicyDriftDto.DriftDetail>> BASELINE_IAM = Map.of(
            "roles/owner",
            List.of(new GcpIamPolicyDriftDto.DriftDetail("user:admin@example.com", "roles/owner", "BASELINE")));

    public GcpSecurityService(GcpClientProvider gcpClientProvider,
                              RedisCacheService redisCacheService,
                              CachedDataRepository cachedDataRepository,
                              ObjectMapper objectMapper,
                              ProwlerService prowlerService) {
        this.gcpClientProvider = gcpClientProvider;
        this.redisCacheService = redisCacheService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.prowlerService = prowlerService;
    }

    // -------------------------------------------------------------------------
    // Main security findings — public API
    // -------------------------------------------------------------------------

    public CompletableFuture<List<GcpSecurityFinding>> getSecurityFindings(String gcpProjectId) {
        return getSecurityFindings(gcpProjectId, false);
    }

    /** Gap 5: forceRefresh param. Gap B/C/D/E/F all implemented here. */
    public CompletableFuture<List<GcpSecurityFinding>> getSecurityFindings(String gcpProjectId, boolean forceRefresh) {
        String redisKey   = REDIS_SECURITY_PREFIX + gcpProjectId;
        String dbKeyLatest = "GCP_SECURITY::" + gcpProjectId + "::LATEST";
        String dbKeyDate   = "GCP_SECURITY::" + gcpProjectId + "::" + LocalDate.now();

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!forceRefresh) {
                    // L1: Redis
                    Optional<List<GcpSecurityFinding>> cached = redisCacheService.get(redisKey, new TypeReference<>() {});
                    if (cached.isPresent()) {
                        log.info("✅ Redis hit for GCP security findings project={}", gcpProjectId);
                        return cached.get();
                    }

                    // L2: DB LATEST (Gap B — dedicated LATEST key)
                    try {
                        Optional<CachedData> dbSnap = cachedDataRepository.findById(dbKeyLatest);
                        if (dbSnap.isPresent()) {
                            List<GcpSecurityFinding> dbResult = objectMapper.readValue(
                                    dbSnap.get().getJsonData(), new TypeReference<>() {});
                            log.info("✅ DB LATEST hit for GCP security project={} ({} findings) — re-warming Redis",
                                    gcpProjectId, dbResult.size());
                            redisCacheService.put(redisKey, dbResult, REDIS_TTL_MINUTES);

                            // Gap C: staleness check — if >24h old, fire background refresh
                            if (dbSnap.get().getLastUpdated().isBefore(LocalDateTime.now().minusHours(24))) {
                                log.info("[GcpSecurity] DB snapshot is stale (>24h) for project={} — triggering background refresh", gcpProjectId);
                                triggerBackgroundRefresh(gcpProjectId);
                            }
                            return dbResult;
                        }
                    } catch (Exception e) {
                        log.warn("[GcpSecurity] DB LATEST load failed for project={}: {}", gcpProjectId, e.getMessage());
                    }
                }

                // L3: Live SCC API
                log.info("🔍 Fetching fresh GCP security findings from SCC for project={}", gcpProjectId);
                List<GcpSecurityFinding> sccFindings = fetchFromScc(gcpProjectId);

                // Gap E: merge Prowler cached findings
                List<GcpSecurityFinding> prowlerFindings = mapProwlerFindings(gcpProjectId);
                log.info("[GcpSecurity] Merging {} SCC + {} Prowler findings for project={}",
                        sccFindings.size(), prowlerFindings.size(), gcpProjectId);
                List<GcpSecurityFinding> combined = new ArrayList<>(sccFindings);
                combined.addAll(prowlerFindings);

                // Gap F: only save to DB/Redis if we got real data (don't overwrite good snapshot with empty)
                if (!combined.isEmpty()) {
                    redisCacheService.put(redisKey, combined, REDIS_TTL_MINUTES);
                    archiveFindings(gcpProjectId, combined, dbKeyLatest, dbKeyDate);
                } else if (forceRefresh) {
                    // On explicit forceRefresh, save even empty to record the scan happened
                    redisCacheService.put(redisKey, combined, REDIS_TTL_MINUTES);
                    archiveFindings(gcpProjectId, combined, dbKeyLatest, dbKeyDate);
                } else {
                    log.warn("[GcpSecurity] SCC returned empty for project={} — NOT overwriting DB snapshot", gcpProjectId);
                }

                return combined;
            } catch (Throwable t) {
                log.error("[GcpSecurity] CRITICAL ERROR fetching findings for project={}", gcpProjectId, t);
                return Collections.emptyList();
            }
        }, executor);
    }

    // -------------------------------------------------------------------------
    // IAM Policy Drift — Redis-only cache (placeholder data, real check pending)
    // -------------------------------------------------------------------------

    public CompletableFuture<List<GcpIamPolicyDriftDto>> getIamPolicyDrift(String gcpProjectId) {
        String cacheKey = REDIS_IAM_DRIFT_PREFIX + gcpProjectId;
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<List<GcpIamPolicyDriftDto>> cached = redisCacheService.get(
                        cacheKey, new TypeReference<>() {});
                if (cached.isPresent()) return cached.get();

                List<GcpIamPolicyDriftDto> result = new ArrayList<>();
                redisCacheService.put(cacheKey, result, REDIS_TTL_MINUTES);
                return result;
            } catch (Throwable t) {
                log.error("[GcpSecurity] IAM drift failed for project={}: {}", gcpProjectId, t.getMessage(), t);
                return Collections.emptyList();
            }
        }, executor);
    }

    // -------------------------------------------------------------------------
    // Container Vulnerabilities — Redis-only cache (placeholder data, real check pending)
    // -------------------------------------------------------------------------

    public CompletableFuture<List<GcpContainerVulnerabilityDto>> getContainerScanningResults(String gcpProjectId) {
        String cacheKey = REDIS_CONTAINER_PREFIX + gcpProjectId;
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<List<GcpContainerVulnerabilityDto>> cached = redisCacheService.get(
                        cacheKey, new TypeReference<>() {});
                if (cached.isPresent()) return cached.get();

                List<GcpContainerVulnerabilityDto> result = new ArrayList<>();
                redisCacheService.put(cacheKey, result, REDIS_TTL_MINUTES);
                return result;
            } catch (Throwable t) {
                log.error("[GcpSecurity] Container scan failed for project={}: {}", gcpProjectId, t.getMessage(), t);
                return Collections.emptyList();
            }
        }, executor);
    }

    // -------------------------------------------------------------------------
    // Security score
    // -------------------------------------------------------------------------

    public int calculateSecurityScore(List<GcpSecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) return 100;
        Map<String, Long> counts = findings.stream()
                .collect(Collectors.groupingBy(GcpSecurityFinding::getSeverity, Collectors.counting()));
        long weighted = (counts.getOrDefault("CRITICAL", 0L) * 5)
                + (counts.getOrDefault("HIGH", 0L) * 2)
                + (counts.getOrDefault("MEDIUM", 0L));
        return Math.max(0, (int) Math.round(100.0 / (1 + 0.1 * weighted)));
    }

    // -------------------------------------------------------------------------
    // Force refresh (called by scheduler and controller)
    // -------------------------------------------------------------------------

    public CompletableFuture<List<GcpSecurityFinding>> refreshSecurityFindings(String gcpProjectId) {
        log.info("🔄 [GcpSecurity] Force refresh triggered for project={}", gcpProjectId);
        redisCacheService.evict(REDIS_SECURITY_PREFIX + gcpProjectId);
        // Also trigger a fresh Prowler scan so merged results are up-to-date
        triggerProwlerScan(gcpProjectId);
        return getSecurityFindings(gcpProjectId, true);
    }

    // -------------------------------------------------------------------------
    // Cache clear (exposed via controller)
    // -------------------------------------------------------------------------

    public void clearCacheForProject(String gcpProjectId) {
        redisCacheService.evict(REDIS_SECURITY_PREFIX   + gcpProjectId);
        redisCacheService.evict(REDIS_IAM_DRIFT_PREFIX  + gcpProjectId);
        redisCacheService.evict(REDIS_CONTAINER_PREFIX  + gcpProjectId);
        log.info("🗑️ [GcpSecurity] Cleared all security caches for project={}", gcpProjectId);
    }

    // -------------------------------------------------------------------------
    // Prowler helpers
    // -------------------------------------------------------------------------

    /** Gap A/E: trigger Prowler GCP scan for this project. */
    public void triggerProwlerScan(String gcpProjectId) {
        Map<String, Object> status = prowlerService.getGcpScanStatus(gcpProjectId);
        if ("RUNNING".equals(status.get("status"))) {
            log.info("[GcpSecurity] Prowler scan already running for project={} — skipping trigger", gcpProjectId);
            return;
        }
        log.info("[GcpSecurity] Triggering Prowler scan for project={}", gcpProjectId);
        prowlerService.triggerGcpScanAsync(gcpProjectId, GCP_PROWLER_FRAMEWORKS);
    }

    /** Gap E: map cached Prowler GCP findings → GcpSecurityFinding. */
    private List<GcpSecurityFinding> mapProwlerFindings(String gcpProjectId) {
        List<ProwlerFinding> raw = prowlerService.getCachedGcpFindings(gcpProjectId);
        List<GcpSecurityFinding> results = new ArrayList<>();
        for (ProwlerFinding pf : raw) {
            if (!"FAIL".equalsIgnoreCase(pf.getStatus())) continue;
            String severity = pf.getSeverity() != null
                    ? pf.getSeverity().substring(0, 1).toUpperCase() + pf.getSeverity().substring(1).toLowerCase()
                    : "Medium";
            String category = pf.getServiceName() != null ? pf.getServiceName().toUpperCase() : "UNKNOWN";

            Map<String, List<String>> compliance = pf.getCompliance();
            if (compliance != null && !compliance.isEmpty()) {
                for (Map.Entry<String, List<String>> entry : compliance.entrySet()) {
                    String controlId = (entry.getValue() != null && !entry.getValue().isEmpty())
                            ? String.join(", ", entry.getValue()) : pf.getCheckId();
                    results.add(new GcpSecurityFinding(
                            category,
                            pf.getDescription(),
                            severity,
                            pf.getResourceId(),
                            entry.getKey() + " / " + controlId));
                }
            } else {
                results.add(new GcpSecurityFinding(
                        category,
                        pf.getDescription(),
                        severity,
                        pf.getResourceId(),
                        "Prowler / " + pf.getCheckId()));
            }
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Background refresh (called on staleness detection)
    // -------------------------------------------------------------------------

    private void triggerBackgroundRefresh(String gcpProjectId) {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("[GcpSecurity] Background staleness refresh starting for project={}", gcpProjectId);
                triggerProwlerScan(gcpProjectId);
                // Re-fetch SCC so DB LATEST is updated
                getSecurityFindings(gcpProjectId, true).join();
                log.info("[GcpSecurity] Background refresh complete for project={}", gcpProjectId);
            } catch (Exception e) {
                log.error("[GcpSecurity] Background refresh failed for project={}: {}", gcpProjectId, e.getMessage());
            }
        }, executor);
    }

    // -------------------------------------------------------------------------
    // SCC fetch
    // -------------------------------------------------------------------------

    private List<GcpSecurityFinding> fetchFromScc(String gcpProjectId) {
        List<GcpSecurityFinding> findings = new ArrayList<>();
        Optional<SecurityCenterClient> clientOpt = gcpClientProvider.getSecurityCenterV2Client(gcpProjectId);
        if (clientOpt.isEmpty()) {
            log.warn("[GcpSecurity] SCC V2 client not available for project={}", gcpProjectId);
            return findings;
        }
        try (SecurityCenterClient client = clientOpt.get()) {
            String parent = String.format("projects/%s/sources/-", gcpProjectId);
            ListFindingsRequest request = ListFindingsRequest.newBuilder()
                    .setParent(parent)
                    .setFilter("state=\"ACTIVE\"")
                    .build();
            for (com.google.cloud.securitycenter.v2.ListFindingsResponse.ListFindingsResult result :
                    client.listFindings(request).iterateAll()) {
                findings.add(mapToDto(result.getFinding()));
            }
            log.info("[GcpSecurity] Fetched {} active SCC findings for project={}", findings.size(), gcpProjectId);
        } catch (Exception e) {
            log.error("[GcpSecurity] SCC fetch failed for project={}: {}", gcpProjectId, e.getMessage(), e);
        }
        return findings;
    }

    // -------------------------------------------------------------------------
    // DB archiving — Gap B: LATEST + dated history
    // -------------------------------------------------------------------------

    private void archiveFindings(String gcpProjectId, List<GcpSecurityFinding> findings,
                                  String dbKeyLatest, String dbKeyDate) {
        try {
            String json = objectMapper.writeValueAsString(findings);
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
            // LATEST — fast fallback on restart
            saveToDb(dbKeyLatest, json, now);
            // Dated — one row per day, audit trail
            saveToDb(dbKeyDate, json, now);
            log.info("💾 [GcpSecurity] Archived {} findings (LATEST + {}) for project={}",
                    findings.size(), LocalDate.now(), gcpProjectId);
        } catch (Exception e) {
            log.warn("[GcpSecurity] Failed to archive findings for project={}: {}", gcpProjectId, e.getMessage());
        }
    }

    private void saveToDb(String key, String json, LocalDateTime now) {
        try {
            CachedData entity = cachedDataRepository.findById(key).orElse(new CachedData());
            entity.setCacheKey(key);
            entity.setJsonData(json);
            entity.setLastUpdated(now);
            cachedDataRepository.save(entity);
        } catch (Exception e) {
            log.warn("[GcpSecurity] DB save failed for key={}: {}", key, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // DTO mapping
    // -------------------------------------------------------------------------

    private GcpSecurityFinding mapToDto(Finding finding) {
        return new GcpSecurityFinding(
                finding.getCategory(),
                finding.getDescription(),
                finding.getSeverity().toString(),
                finding.getResourceName(),
                mapToSoc2Control(finding.getCategory()));
    }

    private String mapToSoc2Control(String category) {
        if (category == null || category.isBlank()) return null;
        switch (category.toUpperCase()) {
            case "IAM_ANOMALOUS_GRANT":
            case "ADMIN_SERVICE_ACCOUNT":
            case "OVER_PRIVILEGED_SERVICE_ACCOUNT_USER":
            case "PRIMITIVE_ROLES_USED":
            case "KMS_PROJECT_HAS_OWNER":
            case "MFA_NOT_ENFORCED":
                return "SOC2 CC6.1";
            case "OPEN_FIREWALL":
            case "UNRESTRICTED_SSH":
            case "UNRESTRICTED_RDP":
            case "FIREWALL_RULE_ALLOWS_ALL":
                return "SOC2 CC6.6";
            case "BUCKET_POLICY_ONLY_DISABLED":
            case "PUBLICLY_ACCESSIBLE_BUCKET":
            case "OBJECT_VERSIONING_DISABLED":
            case "NON_CMEK_DISKS":
                return "SOC2 CC6.7";
            case "SERVICE_ACCOUNT_KEY_NOT_ROTATED":
            case "KMS_KEY_NOT_ROTATED":
                return "SOC2 CC6.8";
            case "AUDIT_LOGGING_DISABLED":
            case "BUCKET_LOGGING_DISABLED":
            case "FLOW_LOGS_DISABLED":
            case "LOG_NOT_EXPORTED":
            case "CLOUD_AUDIT_LOGGING_DISABLED":
                return "SOC2 CC7.1";
            default:
                break;
        }
        String upper = category.toUpperCase();
        if (upper.contains("IAM") || upper.contains("IDENTITY") || upper.contains("PRIVILEGE")
                || upper.contains("ADMIN") || upper.contains("MFA") || upper.contains("ACCESS_CONTROL"))
            return "SOC2 CC6.1";
        if (upper.contains("FIREWALL") || upper.contains("NETWORK") || upper.contains("UNRESTRICTED")
                || upper.contains("OPEN_PORT") || upper.contains("EXPOSED"))
            return "SOC2 CC6.6";
        if (upper.contains("ENCRYPT") || upper.contains("PUBLIC_BUCKET") || upper.contains("PUBLIC_ACCESS")
                || upper.contains("TLS") || upper.contains("SSL") || upper.contains("CMEK"))
            return "SOC2 CC6.7";
        if (upper.contains("KEY_ROTATION") || upper.contains("KEY_NOT_ROTATED") || upper.contains("SECRET")
                || upper.contains("KMS") || upper.contains("SERVICE_ACCOUNT_KEY"))
            return "SOC2 CC6.8";
        if (upper.contains("LOG") || upper.contains("AUDIT") || upper.contains("MONITOR")
                || upper.contains("FLOW_LOG") || upper.contains("TRAIL"))
            return "SOC2 CC7.1";
        return null;
    }
}
