package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.securitycenter.v2.Finding;
import com.google.cloud.securitycenter.v2.ListFindingsRequest;
import com.google.cloud.securitycenter.v2.SecurityCenterClient;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.gcp.GcpContainerVulnerabilityDto;
import com.xammer.cloud.dto.gcp.GcpIamPolicyDriftDto;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

// SOC2 Trust Service Criteria mapping for GCP Security Command Center finding categories.
// Keys are exact SCC category strings; the keyword fallback in mapToSoc2Control() handles
// categories not explicitly listed here.

@Service
@Slf4j
public class GcpSecurityService {

    private final GcpClientProvider gcpClientProvider;
    private final RedisCacheService redisCacheService;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    private static final String SECURITY_FINDINGS_CACHE_PREFIX = "gcp:security-findings:";
    private static final String IAM_POLICY_DRIFT_CACHE_PREFIX = "gcp:iam-policy-drift:";
    private static final String CONTAINER_SCANNING_CACHE_PREFIX = "gcp:container-scanning:";

    private static final Map<String, List<GcpIamPolicyDriftDto.DriftDetail>> BASELINE_IAM = Map.of(
            "roles/owner",
            List.of(new GcpIamPolicyDriftDto.DriftDetail("user:admin@example.com", "roles/owner", "BASELINE")));

    public GcpSecurityService(GcpClientProvider gcpClientProvider,
                              RedisCacheService redisCacheService,
                              CachedDataRepository cachedDataRepository,
                              ObjectMapper objectMapper) {
        this.gcpClientProvider = gcpClientProvider;
        this.redisCacheService = redisCacheService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    private void saveToDb(String dbKey, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to save GCP security DB snapshot for key {}: {}", dbKey, e.getMessage());
        }
    }

    /**
     * ✅ Get IAM Policy Drift with Redis caching
     */
    public CompletableFuture<List<GcpIamPolicyDriftDto>> getIamPolicyDrift(String gcpProjectId) {
        String cacheKey = IAM_POLICY_DRIFT_CACHE_PREFIX + gcpProjectId;

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Try to get from cache
                Optional<List<GcpIamPolicyDriftDto>> cached = redisCacheService.get(
                        cacheKey,
                        new TypeReference<List<GcpIamPolicyDriftDto>>() {
                        });

                if (cached.isPresent()) {
                    log.info("✅ Returning cached IAM policy drift for project {}", gcpProjectId);
                    return cached.get();
                }

                log.info("🔍 Cache miss - fetching fresh IAM policy drift for project {}", gcpProjectId);

                // Fetch fresh data (placeholder implementation)
                List<GcpIamPolicyDriftDto> result = new ArrayList<>();

                // Cache the result
                redisCacheService.put(cacheKey, result, 60);

                return result;
            } catch (Throwable t) {
                log.error("Failed to get IAM drift for project {}: {}", gcpProjectId, t.getMessage(), t);
                return Collections.emptyList();
            }
        });
    }

    /**
     * ✅ Get Container Scanning Results with Redis caching
     */
    public CompletableFuture<List<GcpContainerVulnerabilityDto>> getContainerScanningResults(String gcpProjectId) {
        String cacheKey = CONTAINER_SCANNING_CACHE_PREFIX + gcpProjectId;

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Try to get from cache
                Optional<List<GcpContainerVulnerabilityDto>> cached = redisCacheService.get(
                        cacheKey,
                        new TypeReference<List<GcpContainerVulnerabilityDto>>() {
                        });

                if (cached.isPresent()) {
                    log.info("✅ Returning cached container scanning results for project {}", gcpProjectId);
                    return cached.get();
                }

                log.info("🔍 Cache miss - fetching fresh container scanning results for project {}", gcpProjectId);

                // Fetch fresh data (placeholder implementation)
                List<GcpContainerVulnerabilityDto> result = new ArrayList<>();

                // Cache the result
                redisCacheService.put(cacheKey, result, 60);

                return result;
            } catch (Throwable t) {
                log.error("Failed to get container scanning results for project {}: {}", gcpProjectId, t.getMessage(),
                        t);
                return Collections.emptyList();
            }
        });
    }

    /**
     * ✅ FIXED: Get Security Findings with proper Redis caching
     * This now caches the List<GcpSecurityFinding> instead of CompletableFuture
     */
    public CompletableFuture<List<GcpSecurityFinding>> getSecurityFindings(String gcpProjectId) {
        String cacheKey = SECURITY_FINDINGS_CACHE_PREFIX + gcpProjectId;

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Try Redis cache
                Optional<List<GcpSecurityFinding>> cached = redisCacheService.get(
                        cacheKey,
                        new TypeReference<List<GcpSecurityFinding>>() {
                        });

                if (cached.isPresent()) {
                    log.info("✅ Returning Redis-cached security findings for project {} ({} findings)",
                            gcpProjectId, cached.get().size());
                    return cached.get();
                }

                // 2. Try DB snapshot
                try {
                    Optional<CachedData> dbSnapshot = cachedDataRepository.findById(cacheKey);
                    if (dbSnapshot.isPresent()) {
                        List<GcpSecurityFinding> dbResult = objectMapper.readValue(
                                dbSnapshot.get().getJsonData(), new TypeReference<>() {});
                        log.info("✅ Returning DB-cached security findings for project {} — re-warming Redis", gcpProjectId);
                        redisCacheService.put(cacheKey, dbResult, 60);
                        return dbResult;
                    }
                } catch (Exception e) {
                    log.warn("DB snapshot load failed for security findings {}: {}", gcpProjectId, e.getMessage());
                }

                // 3. Fetch fresh from API
                log.info("🔍 Cache miss - fetching fresh security findings for project {}", gcpProjectId);

                Optional<SecurityCenterClient> clientOpt = gcpClientProvider.getSecurityCenterV2Client(gcpProjectId);
                if (clientOpt.isEmpty()) {
                    log.warn("Security Command Center V2 client not available for project {}. Returning empty list.",
                            gcpProjectId);
                    return Collections.emptyList();
                }

                List<GcpSecurityFinding> findings = new ArrayList<>();
                try (SecurityCenterClient client = clientOpt.get()) {
                    String parent = String.format("projects/%s/sources/-", gcpProjectId);
                    log.info("Fetching security findings for project {} from parent {} using V2 API",
                            gcpProjectId, parent);

                    ListFindingsRequest request = ListFindingsRequest.newBuilder()
                            .setParent(parent)
                            .setFilter("state=\"ACTIVE\"")
                            .build();

                    for (com.google.cloud.securitycenter.v2.ListFindingsResponse.ListFindingsResult result : client
                            .listFindings(request).iterateAll()) {
                        findings.add(mapToDto(result.getFinding()));
                    }

                    log.info("Successfully fetched {} active security findings for project {}.",
                            findings.size(), gcpProjectId);

                    redisCacheService.put(cacheKey, findings, 60);
                    saveToDb(cacheKey, findings);
                    log.info("💾 Cached {} security findings for project {}", findings.size(), gcpProjectId);

                } catch (Exception e) {
                    log.error("Failed to get security findings for project {}: {}",
                            gcpProjectId, e.getMessage(), e);
                }

                return findings;
            } catch (Throwable t) {
                log.error("CRITICAL ERROR: Failed to fetch security findings for project {}", gcpProjectId, t);
                return Collections.emptyList();
            }
        });
    }

    /**
     * Calculate security score based on findings
     */
    public int calculateSecurityScore(List<GcpSecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return 100;
        }

        Map<String, Long> counts = findings.stream()
                .collect(Collectors.groupingBy(GcpSecurityFinding::getSeverity, Collectors.counting()));

        long criticalWeight = 5;
        long highWeight = 2;
        long mediumWeight = 1;

        long weightedScore = (counts.getOrDefault("CRITICAL", 0L) * criticalWeight) +
                (counts.getOrDefault("HIGH", 0L) * highWeight) +
                (counts.getOrDefault("MEDIUM", 0L) * mediumWeight);

        double score = 100.0 / (1 + 0.1 * weightedScore);

        return Math.max(0, (int) Math.round(score));
    }

    /**
     * Map V2 Finding to DTO with SOC2 control tagging.
     */
    private GcpSecurityFinding mapToDto(Finding finding) {
        return new GcpSecurityFinding(
                finding.getCategory(),
                finding.getDescription(),
                finding.getSeverity().toString(),
                finding.getResourceName(),
                mapToSoc2Control(finding.getCategory()));
    }

    /**
     * Maps a GCP Security Command Center finding category to its SOC2 Trust Service
     * Criteria control. Returns null when no SOC2 mapping is applicable.
     *
     * CC6.1  – Logical access controls (IAM, identity, privilege)
     * CC6.6  – Network security (firewall, ports, exposure)
     * CC6.7  – Data protection (encryption, public storage, TLS)
     * CC6.8  – Key / credential management (KMS rotation, service-account keys)
     * CC7.1  – Monitoring and audit logging
     */
    private String mapToSoc2Control(String category) {
        if (category == null || category.isBlank()) return null;

        // --- exact matches for well-known SCC categories ---
        switch (category.toUpperCase()) {
            // CC6.1 — access / identity
            case "IAM_ANOMALOUS_GRANT":
            case "ADMIN_SERVICE_ACCOUNT":
            case "OVER_PRIVILEGED_SERVICE_ACCOUNT_USER":
            case "PRIMITIVE_ROLES_USED":
            case "KMS_PROJECT_HAS_OWNER":
            case "MFA_NOT_ENFORCED":
                return "SOC2 CC6.1";

            // CC6.6 — network
            case "OPEN_FIREWALL":
            case "UNRESTRICTED_SSH":
            case "UNRESTRICTED_RDP":
            case "FIREWALL_RULE_ALLOWS_ALL":
                return "SOC2 CC6.6";

            // CC6.7 — encryption / data protection
            case "BUCKET_POLICY_ONLY_DISABLED":
            case "PUBLICLY_ACCESSIBLE_BUCKET":
            case "OBJECT_VERSIONING_DISABLED":
            case "NON_CMEK_DISKS":
                return "SOC2 CC6.7";

            // CC6.8 — key management
            case "SERVICE_ACCOUNT_KEY_NOT_ROTATED":
            case "KMS_KEY_NOT_ROTATED":
                return "SOC2 CC6.8";

            // CC7.1 — monitoring / logging
            case "AUDIT_LOGGING_DISABLED":
            case "BUCKET_LOGGING_DISABLED":
            case "FLOW_LOGS_DISABLED":
            case "LOG_NOT_EXPORTED":
            case "CLOUD_AUDIT_LOGGING_DISABLED":
                return "SOC2 CC7.1";

            default:
                break;
        }

        // --- keyword fallback for unknown / future categories ---
        String upper = category.toUpperCase();

        if (upper.contains("IAM") || upper.contains("IDENTITY") || upper.contains("PRIVILEGE")
                || upper.contains("ADMIN") || upper.contains("MFA") || upper.contains("ACCESS_CONTROL")) {
            return "SOC2 CC6.1";
        }
        if (upper.contains("FIREWALL") || upper.contains("NETWORK") || upper.contains("UNRESTRICTED")
                || upper.contains("OPEN_PORT") || upper.contains("EXPOSED")) {
            return "SOC2 CC6.6";
        }
        if (upper.contains("ENCRYPT") || upper.contains("PUBLIC_BUCKET") || upper.contains("PUBLIC_ACCESS")
                || upper.contains("TLS") || upper.contains("SSL") || upper.contains("CMEK")) {
            return "SOC2 CC6.7";
        }
        if (upper.contains("KEY_ROTATION") || upper.contains("KEY_NOT_ROTATED") || upper.contains("SECRET")
                || upper.contains("KMS") || upper.contains("SERVICE_ACCOUNT_KEY")) {
            return "SOC2 CC6.8";
        }
        if (upper.contains("LOG") || upper.contains("AUDIT") || upper.contains("MONITOR")
                || upper.contains("FLOW_LOG") || upper.contains("TRAIL")) {
            return "SOC2 CC7.1";
        }

        return null;
    }

    /**
     * ✅ Clear cache for a specific project (useful when switching accounts)
     */
    public void clearCacheForProject(String gcpProjectId) {
        try {
            String securityKey = SECURITY_FINDINGS_CACHE_PREFIX + gcpProjectId;
            String iamKey = IAM_POLICY_DRIFT_CACHE_PREFIX + gcpProjectId;
            String containerKey = CONTAINER_SCANNING_CACHE_PREFIX + gcpProjectId;

            redisCacheService.evict(securityKey);
            redisCacheService.evict(iamKey);
            redisCacheService.evict(containerKey);

            log.info("🗑️ Cleared security cache for project {}", gcpProjectId);
        } catch (Exception e) {
            log.warn("Failed to clear cache for project {}: {}", gcpProjectId, e.getMessage());
        }
    }

    /**
     * ✅ Clear all security caches (if needed)
     */
    public void clearAllSecurityCaches() {
        log.warn("clearAllSecurityCaches() not implemented - requires pattern-based deletion in RedisCacheService");
        // Note: Your RedisCacheService doesn't support pattern-based deletion
        // You would need to add a method like evictByPattern(String pattern) if needed
    }

    /**
     * ✅ Force refresh security findings (evict cache and fetch fresh)
     */
    public CompletableFuture<List<GcpSecurityFinding>> refreshSecurityFindings(String gcpProjectId) {
        String cacheKey = SECURITY_FINDINGS_CACHE_PREFIX + gcpProjectId;
        redisCacheService.evict(cacheKey);
        log.info("🔄 Forced refresh of security findings for project {}", gcpProjectId);
        return getSecurityFindings(gcpProjectId);
    }
}
