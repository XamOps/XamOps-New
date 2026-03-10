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
     * Map V2 Finding to DTO
     */
    private GcpSecurityFinding mapToDto(Finding finding) {
        return new GcpSecurityFinding(
                finding.getCategory(),
                finding.getDescription(),
                finding.getSeverity().toString(),
                finding.getResourceName());
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
