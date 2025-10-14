package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.securitycenter.v2.Finding;
import com.google.cloud.securitycenter.v2.ListFindingsRequest;
import com.google.cloud.securitycenter.v2.SecurityCenterClient;
import com.xammer.cloud.dto.gcp.GcpContainerVulnerabilityDto;
import com.xammer.cloud.dto.gcp.GcpIamPolicyDriftDto;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GcpSecurityService {

    private final GcpClientProvider gcpClientProvider;
    private final RedisCacheService redisCacheService;

    private static final String SECURITY_FINDINGS_CACHE_PREFIX = "gcp:security-findings:";
    private static final String IAM_POLICY_DRIFT_CACHE_PREFIX = "gcp:iam-policy-drift:";
    private static final String CONTAINER_SCANNING_CACHE_PREFIX = "gcp:container-scanning:";

    private static final Map<String, List<GcpIamPolicyDriftDto.DriftDetail>> BASELINE_IAM = Map.of(
            "roles/owner", List.of(new GcpIamPolicyDriftDto.DriftDetail("user:admin@example.com", "roles/owner", "BASELINE"))
    );

    public GcpSecurityService(GcpClientProvider gcpClientProvider, RedisCacheService redisCacheService) {
        this.gcpClientProvider = gcpClientProvider;
        this.redisCacheService = redisCacheService;
    }

    /**
     * ✅ Get IAM Policy Drift with Redis caching
     */
    public CompletableFuture<List<GcpIamPolicyDriftDto>> getIamPolicyDrift(String gcpProjectId) {
        String cacheKey = IAM_POLICY_DRIFT_CACHE_PREFIX + gcpProjectId;

        return CompletableFuture.supplyAsync(() -> {
            // Try to get from cache
            Optional<List<GcpIamPolicyDriftDto>> cached = redisCacheService.get(
                    cacheKey,
                    new TypeReference<List<GcpIamPolicyDriftDto>>() {}
            );

            if (cached.isPresent()) {
                log.info("✅ Returning cached IAM policy drift for project {}", gcpProjectId);
                return cached.get();
            }

            log.info("🔍 Cache miss - fetching fresh IAM policy drift for project {}", gcpProjectId);

            // Fetch fresh data (placeholder implementation)
            List<GcpIamPolicyDriftDto> result = new ArrayList<>();

            // Cache the result
            redisCacheService.put(cacheKey, result, 10);

            return result;
        });
    }

    /**
     * ✅ Get Container Scanning Results with Redis caching
     */
    public CompletableFuture<List<GcpContainerVulnerabilityDto>> getContainerScanningResults(String gcpProjectId) {
        String cacheKey = CONTAINER_SCANNING_CACHE_PREFIX + gcpProjectId;

        return CompletableFuture.supplyAsync(() -> {
            // Try to get from cache
            Optional<List<GcpContainerVulnerabilityDto>> cached = redisCacheService.get(
                    cacheKey,
                    new TypeReference<List<GcpContainerVulnerabilityDto>>() {}
            );

            if (cached.isPresent()) {
                log.info("✅ Returning cached container scanning results for project {}", gcpProjectId);
                return cached.get();
            }

            log.info("🔍 Cache miss - fetching fresh container scanning results for project {}", gcpProjectId);

            // Fetch fresh data (placeholder implementation)
            List<GcpContainerVulnerabilityDto> result = new ArrayList<>();

            // Cache the result
            redisCacheService.put(cacheKey, result, 10);

            return result;
        });
    }

    /**
     * ✅ FIXED: Get Security Findings with proper Redis caching
     * This now caches the List<GcpSecurityFinding> instead of CompletableFuture
     */
    public CompletableFuture<List<GcpSecurityFinding>> getSecurityFindings(String gcpProjectId) {
        String cacheKey = SECURITY_FINDINGS_CACHE_PREFIX + gcpProjectId;

        return CompletableFuture.supplyAsync(() -> {
            // Try to get from Redis cache using TypeReference for List
            Optional<List<GcpSecurityFinding>> cached = redisCacheService.get(
                    cacheKey,
                    new TypeReference<List<GcpSecurityFinding>>() {}
            );

            if (cached.isPresent()) {
                log.info("✅ Returning cached security findings for project {} ({} findings)",
                        gcpProjectId, cached.get().size());
                return cached.get();
            }

            // Cache miss - fetch fresh data
            log.info("🔍 Cache miss - fetching fresh security findings for project {}", gcpProjectId);

            Optional<SecurityCenterClient> clientOpt = gcpClientProvider.getSecurityCenterV2Client(gcpProjectId);
            if (clientOpt.isEmpty()) {
                log.warn("Security Command Center V2 client not available for project {}. Returning empty list.", gcpProjectId);
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

                for (com.google.cloud.securitycenter.v2.ListFindingsResponse.ListFindingsResult result :
                        client.listFindings(request).iterateAll()) {
                    findings.add(mapToDto(result.getFinding()));
                }

                log.info("Successfully fetched {} active security findings for project {}.",
                        findings.size(), gcpProjectId);

                // Cache the successful result in Redis
                redisCacheService.put(cacheKey, findings, 10);
                log.info("💾 Cached {} security findings for project {}", findings.size(), gcpProjectId);

            } catch (Exception e) {
                log.error("Failed to get security findings for project {}: {}",
                        gcpProjectId, e.getMessage(), e);
            }

            return findings;
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
                finding.getResourceName()
        );
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
