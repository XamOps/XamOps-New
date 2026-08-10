package com.xammer.cloud.service.war;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.war.WarReport;
import com.xammer.cloud.service.CloudListService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Routes a WAR scan to the right provider scanner.
 *
 * <p>
 * Extracted from {@code WarController} so the HTTP endpoint and the onboarding email
 * run the identical scan. Two copies of the provider switch would drift, and the
 * failure mode is subtle: an emailed report that disagrees with the dashboard for the
 * same account undermines both.
 *
 * <p>
 * AWS needs its active-region list resolved first because most of its resources are
 * regional. GCP and Azure enumerate project- or subscription-wide in one call, so the
 * provider id is all their scanners need.
 */
@Component
public class WarScanDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WarScanDispatcher.class);

    /**
     * Regions scanned when the account has no discovered region list yet. Mirrors
     * SecurityController.ComplianceRegions so both pages describe the same footprint.
     */
    static final List<String> FALLBACK_REGIONS = List.of(
            "ap-south-1", "us-east-1", "ap-southeast-1");

    private final AwsWarService awsWarService;
    private final GcpWarService gcpWarService;
    private final AzureWarService azureWarService;
    private final CloudListService cloudListService;

    public WarScanDispatcher(AwsWarService awsWarService,
            GcpWarService gcpWarService,
            AzureWarService azureWarService,
            CloudListService cloudListService) {
        this.awsWarService = awsWarService;
        this.gcpWarService = gcpWarService;
        this.azureWarService = azureWarService;
        this.cloudListService = cloudListService;
    }

    /**
     * Scans the account against its provider's baseline.
     *
     * @param forceRefresh bypass the Redis and Postgres snapshot tiers and scan live
     * @return the assembled report, or a failed future for a provider with no baseline
     */
    public CompletableFuture<WarReport> scan(CloudAccount account, boolean forceRefresh) {
        String provider = account.getProvider() == null ? "AWS" : account.getProvider();
        return switch (provider) {
            case "GCP" -> gcpWarService.getReport(account, forceRefresh);
            case "Azure" -> azureWarService.getReport(account, forceRefresh);
            case "AWS" -> resolveRegions(account, forceRefresh)
                    .thenCompose(regions -> awsWarService.getReport(account, regions, forceRefresh));
            default -> CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "WAR does not have a baseline for provider " + provider + " yet"));
        };
    }

    /**
     * The account's active regions, falling back to the three-region default.
     *
     * <p>
     * Region discovery is a live API call and can fail for the same reasons a scan can.
     * Falling back rather than propagating keeps a partial report available: three
     * regions of real findings beat an empty page.
     */
    public CompletableFuture<List<String>> resolveRegions(CloudAccount account, boolean forceRefresh) {
        return cloudListService.getRegionStatusForAccount(account, forceRefresh)
                .thenApply(statuses -> {
                    List<String> regions = statuses.stream()
                            .map(s -> s.getRegionId())
                            .filter(id -> id != null && !id.isBlank())
                            .distinct()
                            .toList();
                    return regions.isEmpty() ? FALLBACK_REGIONS : regions;
                })
                .exceptionally(ex -> {
                    log.warn("[WAR] region discovery failed for account {}, using fallback list: {}",
                            account.getAwsAccountId(), ex.getMessage());
                    return FALLBACK_REGIONS;
                });
    }
}
