package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.AwsSecurityFinding;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.securityhub.model.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Service for fetching and processing AWS Security Hub findings.
 * Similar to GcpSecurityService but for AWS SecurityHub.
 */
@Service
public class AwsSecurityHubService {

    private static final Logger logger = LoggerFactory.getLogger(AwsSecurityHubService.class);
    private static final String CACHE_PREFIX = "aws:securityhub:";
    private static final Set<String> HIGH_SEVERITY = Set.of("CRITICAL", "HIGH");

    private final AwsClientProvider awsClientProvider;
    private final RedisCacheService redisCache;
    private final CloudAccountRepository cloudAccountRepository;
    private final CloudListService cloudListService;

    public AwsSecurityHubService(
            AwsClientProvider awsClientProvider,
            RedisCacheService redisCache,
            CloudAccountRepository cloudAccountRepository,
            @Lazy CloudListService cloudListService) {
        this.awsClientProvider = awsClientProvider;
        this.redisCache = redisCache;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cloudListService = cloudListService;
    }

    /**
     * Get security findings from AWS Security Hub across all active regions.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<com.xammer.cloud.dto.AwsSecurityFinding>> getSecurityFindings(String accountId, boolean forceRefresh) {
        String cacheKey = CACHE_PREFIX + accountId;

        if (!forceRefresh) {
            Optional<List<com.xammer.cloud.dto.AwsSecurityFinding>> cached = redisCache.get(
                    cacheKey, new TypeReference<List<com.xammer.cloud.dto.AwsSecurityFinding>>() {});
            if (cached.isPresent()) {
                logger.info("Returning cached SecurityHub findings for account {} ({} findings)",
                        accountId, cached.get().size());
                return CompletableFuture.completedFuture(cached.get());
            }
        }

        CloudAccount account = getAccount(accountId);

        return cloudListService.getRegionStatusForAccount(account, forceRefresh)
                .thenApply(activeRegions -> {
                    List<com.xammer.cloud.dto.AwsSecurityFinding> allFindings = new ArrayList<>();

                    for (DashboardData.RegionStatus region : activeRegions) {
                        try {
                            List<com.xammer.cloud.dto.AwsSecurityFinding> regionFindings =
                                    fetchFindingsForRegion(account, region.getRegionId());
                            allFindings.addAll(regionFindings);
                        } catch (Exception e) {
                            logger.warn("Failed to fetch SecurityHub findings for region {}: {}",
                                    region.getRegionId(), e.getMessage());
                        }
                    }

                    // Cache results
                    redisCache.put(cacheKey, allFindings, 10);
                    logger.info("Fetched {} SecurityHub findings for account {}", allFindings.size(), accountId);

                    return allFindings;
                });
    }

    /**
     * Fetch findings for a specific region with filters for active, high-severity findings.
     */
    private List<com.xammer.cloud.dto.AwsSecurityFinding> fetchFindingsForRegion(CloudAccount account, String regionId) {
        List<com.xammer.cloud.dto.AwsSecurityFinding> findings = new ArrayList<>();

        try {
            SecurityHubClient client = awsClientProvider.getSecurityHubClient(account, regionId);

            // Build filter for CRITICAL and HIGH severity, active findings
            GetFindingsRequest request = GetFindingsRequest.builder()
                    .filters(AwsSecurityFindingFilters.builder()
                            .recordState(List.of(StringFilter.builder()
                                    .value("ACTIVE")
                                    .comparison(StringFilterComparison.EQUALS)
                                    .build()))
                            .severityLabel(List.of(
                                    StringFilter.builder()
                                            .value("CRITICAL")
                                            .comparison(StringFilterComparison.EQUALS)
                                            .build(),
                                    StringFilter.builder()
                                            .value("HIGH")
                                            .comparison(StringFilterComparison.EQUALS)
                                            .build()
                            ))
                            .workflowStatus(List.of(StringFilter.builder()
                                    .value("NEW")
                                    .comparison(StringFilterComparison.EQUALS)
                                    .build()))
                            .build())
                    .maxResults(100)
                    .build();

            GetFindingsResponse response = client.getFindings(request);

            for (software.amazon.awssdk.services.securityhub.model.AwsSecurityFinding finding : response.findings()) {
                findings.add(mapToDto(finding, regionId));
            }

            logger.debug("Fetched {} findings from SecurityHub in region {}", findings.size(), regionId);

        } catch (SecurityHubException e) {
            if (e.statusCode() == 400 && e.getMessage() != null && e.getMessage().contains("not subscribed")) {
                logger.debug("SecurityHub not enabled in region {}", regionId);
            } else {
                logger.warn("SecurityHub error in region {}: {}", regionId, e.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Error fetching SecurityHub findings in region {}: {}", regionId, e.getMessage());
        }

        return findings;
    }

    /**
     * Map AWS SDK Finding to our DTO.
     */
    private com.xammer.cloud.dto.AwsSecurityFinding mapToDto(software.amazon.awssdk.services.securityhub.model.AwsSecurityFinding finding, String regionId) {
        String resourceId = "";
        String resourceType = "";

        if (finding.hasResources() && !finding.resources().isEmpty()) {
            Resource resource = finding.resources().get(0);
            resourceId = resource.id();
            resourceType = resource.type();
        }

        String remediation = "";
        if (finding.remediation() != null && finding.remediation().recommendation() != null) {
            Recommendation rec = finding.remediation().recommendation();
            remediation = rec.text() != null ? rec.text() : "";
        }

        String severity = "MEDIUM";
        if (finding.severity() != null && finding.severity().label() != null) {
            severity = finding.severity().labelAsString();
        }

        String category = "Unknown";
        if (finding.hasTypes() && !finding.types().isEmpty()) {
            category = String.join(", ", finding.types());
        }

        String complianceStatus = "NOT_AVAILABLE";
        if (finding.compliance() != null && finding.compliance().status() != null) {
            complianceStatus = finding.compliance().statusAsString();
        }

        return new com.xammer.cloud.dto.AwsSecurityFinding(
                finding.id(),
                finding.title(),
                finding.description(),
                severity,
                category,
                resourceType,
                resourceId,
                regionId,
                complianceStatus,
                finding.productName(),
                remediation
        );
    }

    /**
     * Convert security findings to AlertDto format for CloudGuard integration.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<AlertDto>> getSecurityAlerts(String accountId, boolean forceRefresh) {
        return getSecurityFindings(accountId, forceRefresh)
                .thenApply(findings -> {
                    AtomicInteger counter = new AtomicInteger(1);

                    return findings.stream()
                            .filter(f -> HIGH_SEVERITY.contains(f.getSeverity()))
                            .map(finding -> {
                                String uniqueId = String.format("security-%s-%s-%d",
                                        sanitizeForId(finding.getCategory()),
                                        sanitizeForId(finding.getResourceId()),
                                        counter.getAndIncrement());

                                String description = finding.getDescription();
                                if (finding.getRemediation() != null && !finding.getRemediation().isEmpty()) {
                                    description += " | Remediation: " + finding.getRemediation();
                                }

                                return new AlertDto(
                                        uniqueId,
                                        finding.getCategory(),
                                        finding.getTitle(),
                                        description,
                                        finding.getSeverity(),
                                        0, // usage not applicable for security
                                        0, // limit not applicable for security
                                        "SECURITY",
                                        finding.getRegion()
                                );
                            })
                            .collect(Collectors.toList());
                });
    }

    /**
     * Sanitize string for use in alert ID.
     */
    private String sanitizeForId(String input) {
        if (input == null || input.isEmpty()) return "unknown";
        String sanitized = input.toLowerCase().replaceAll("[^a-z0-9]", "-");
        return sanitized.substring(0, Math.min(sanitized.length(), 30));
    }

    /**
     * Get account from repository.
     */
    private CloudAccount getAccount(String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found: " + accountId);
        }
        return accounts.get(0);
    }

    /**
     * Clear cache for a specific account.
     */
    public void clearCacheForAccount(String accountId) {
        try {
            String cacheKey = CACHE_PREFIX + accountId;
            redisCache.evict(cacheKey);
            logger.info("Cleared SecurityHub cache for account {}", accountId);
        } catch (Exception e) {
            logger.warn("Failed to clear SecurityHub cache for account {}: {}", accountId, e.getMessage());
        }
    }

    /**
     * Force refresh security findings (evict cache and fetch fresh).
     */
    public CompletableFuture<List<com.xammer.cloud.dto.AwsSecurityFinding>> refreshSecurityFindings(String accountId) {
        clearCacheForAccount(accountId);
        logger.info("Forced refresh of SecurityHub findings for account {}", accountId);
        return getSecurityFindings(accountId, true);
    }
}
