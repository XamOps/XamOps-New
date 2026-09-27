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
     *
     * <p>Lenient: a scan that could not read Security Hub anywhere yields an empty list. The
     * Alerts page uses {@link #getSecurityAlerts} instead, which reports that as a failure.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<com.xammer.cloud.dto.AwsSecurityFinding>> getSecurityFindings(String accountId,
            boolean forceRefresh) {
        return loadFindings(accountId, forceRefresh)
                .exceptionally(ex -> {
                    logger.warn("SecurityHub findings unavailable for account {}: {}", accountId, ex.getMessage());
                    return new ArrayList<>();
                });
    }

    /**
     * Cache, then every active region. Completes exceptionally when no region could be read.
     *
     * <p>"Could not be read" means a real error in every region — typically AccessDenied. A region
     * where Security Hub is simply not enabled is not an error; an account with Security Hub
     * switched off everywhere legitimately has no findings.
     */
    private CompletableFuture<List<com.xammer.cloud.dto.AwsSecurityFinding>> loadFindings(String accountId,
            boolean forceRefresh) {
        String cacheKey = CACHE_PREFIX + accountId;

        if (!forceRefresh) {
            Optional<List<com.xammer.cloud.dto.AwsSecurityFinding>> cached = redisCache.get(
                    cacheKey, new TypeReference<List<com.xammer.cloud.dto.AwsSecurityFinding>>() {
                    });
            if (cached.isPresent()) {
                logger.info("Returning cached SecurityHub findings for account {} ({} findings)",
                        accountId, cached.get().size());
                return CompletableFuture.completedFuture(cached.get());
            }
        }

        CloudAccount account = getAccount(accountId);

        return cloudListService.getRegionStatusForAccountOrFail(account, forceRefresh)
                .thenApply(activeRegions -> {
                    // Keyed by finding id. With cross-region aggregation on, the aggregation
                    // region returns every linked region's findings as well as its own, so the
                    // same finding arrived once from its home region and again from there.
                    Map<String, com.xammer.cloud.dto.AwsSecurityFinding> byId = new LinkedHashMap<>();
                    List<String> failedRegions = new ArrayList<>();
                    String firstError = null;

                    for (DashboardData.RegionStatus region : activeRegions) {
                        try {
                            for (com.xammer.cloud.dto.AwsSecurityFinding f
                                    : fetchFindingsForRegion(account, region.getRegionId())) {
                                byId.putIfAbsent(f.getId() != null ? f.getId() : UUID.randomUUID().toString(), f);
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to fetch SecurityHub findings for region {}: {}",
                                    region.getRegionId(), e.getMessage());
                            failedRegions.add(region.getRegionId());
                            if (firstError == null) {
                                firstError = e.getMessage();
                            }
                        }
                    }

                    if (!activeRegions.isEmpty() && failedRegions.size() == activeRegions.size()) {
                        // Not cached, so the next read tries again rather than serving the failure.
                        throw new IllegalStateException(
                                "Security Hub could not be read in any region: " + firstError);
                    }

                    List<com.xammer.cloud.dto.AwsSecurityFinding> allFindings = new ArrayList<>(byId.values());
                    redisCache.put(cacheKey, allFindings, 60);
                    logger.info("Fetched {} SecurityHub findings for account {} ({} region(s) failed)",
                            allFindings.size(), accountId, failedRegions.size());

                    return allFindings;
                });
    }

    /** Pages per region. At 100 a page this is a thousand CRITICAL/HIGH findings per region. */
    private static final int MAX_PAGES_PER_REGION = 10;

    /**
     * Fetch findings for a specific region with filters for active, high-severity
     * findings.
     *
     * <p>Throws on a real error so the caller can tell a failed region from a clean one. Security
     * Hub not being enabled in the region returns an empty list.
     */
    private List<com.xammer.cloud.dto.AwsSecurityFinding> fetchFindingsForRegion(CloudAccount account,
            String regionId) {
        List<com.xammer.cloud.dto.AwsSecurityFinding> findings = new ArrayList<>();

        try {
            SecurityHubClient client = awsClientProvider.getSecurityHubClient(account, regionId);

            // Build filter for CRITICAL and HIGH severity, active findings
            AwsSecurityFindingFilters filters = AwsSecurityFindingFilters.builder()
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
                                    .build()))
                    .workflowStatus(List.of(StringFilter.builder()
                            .value("NEW")
                            .comparison(StringFilterComparison.EQUALS)
                            .build()))
                    .build();

            // Followed past the first page, which previously was all that was read: a region with
            // more than 100 open findings silently lost the rest.
            String nextToken = null;
            int pages = 0;
            do {
                GetFindingsResponse response = client.getFindings(GetFindingsRequest.builder()
                        .filters(filters)
                        .maxResults(100)
                        .nextToken(nextToken)
                        .build());
                for (software.amazon.awssdk.services.securityhub.model.AwsSecurityFinding finding : response.findings()) {
                    findings.add(mapToDto(finding, regionId));
                }
                nextToken = response.nextToken();
                pages++;
            } while (nextToken != null && !nextToken.isBlank() && pages < MAX_PAGES_PER_REGION);

            logger.debug("Fetched {} findings from SecurityHub in region {}", findings.size(), regionId);

        } catch (SecurityHubException e) {
            if (isNotSubscribed(e)) {
                logger.debug("SecurityHub not enabled in region {}", regionId);
                return findings;
            }
            throw e;
        }

        return findings;
    }

    /**
     * Whether the error only means Security Hub is not turned on in this region.
     *
     * <p>Matched on the message, not the status code. AWS returns this as an
     * InvalidAccessException with status 401 ("Account ... is not subscribed to AWS Security Hub"),
     * while the check used to require 400 — so on an account without Security Hub every region
     * read as a failure and the Alerts page reported Security Hub as broken.
     */
    static boolean isNotSubscribed(SecurityHubException e) {
        String message = e.getMessage();
        if (message != null && message.toLowerCase(Locale.ROOT).contains("not subscribed")) {
            return true;
        }
        return e.awsErrorDetails() != null
                && e.awsErrorDetails().errorMessage() != null
                && e.awsErrorDetails().errorMessage().toLowerCase(Locale.ROOT).contains("not subscribed");
    }

    /**
     * Map AWS SDK Finding to our DTO.
     */
    private com.xammer.cloud.dto.AwsSecurityFinding mapToDto(
            software.amazon.awssdk.services.securityhub.model.AwsSecurityFinding finding, String regionId) {
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
                remediation);
    }

    /**
     * Convert security findings to AlertDto format for CloudGuard integration.
     *
     * <p>Completes exceptionally when Security Hub could not be read in any region, so the Alerts
     * page shows the failure instead of an empty security section that reads as "nothing wrong".
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<List<AlertDto>> getSecurityAlerts(String accountId, boolean forceRefresh) {
        return loadFindings(accountId, forceRefresh)
                .thenApply(findings -> {
                    return findings.stream()
                            .filter(f -> HIGH_SEVERITY.contains(f.getSeverity()))
                            .map(finding -> {
                                // Derived from the finding's own id, not a running counter, so the
                                // same finding keeps the same row id from one scan to the next.
                                String uniqueId = "securityhub-" + UUID.nameUUIDFromBytes(
                                        String.valueOf(finding.getId()).getBytes(
                                                java.nio.charset.StandardCharsets.UTF_8));

                                String description = finding.getDescription();
                                if (finding.getRemediation() != null && !finding.getRemediation().isEmpty()) {
                                    description += " | Remediation: " + finding.getRemediation();
                                }

                                return new AlertDto(
                                        uniqueId,
                                        // The product that raised it, not its finding type. The
                                        // type is a path like "Software and Configuration Checks/
                                        // Industry and Regulatory Standards", which crowded the
                                        // Service column and stretched the filter to fit.
                                        finding.getProductName() != null && !finding.getProductName().isBlank()
                                                ? finding.getProductName()
                                                : "Security Hub",
                                        finding.getTitle(),
                                        description,
                                        finding.getSeverity(),
                                        0, // usage not applicable for security
                                        0, // limit not applicable for security
                                        "SECURITY",
                                        finding.getRegion());
                            })
                            .collect(Collectors.toList());
                });
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
