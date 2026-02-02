package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ResourceDetailDto;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.dto.ServicePaginatedResponse;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.ResourceDetailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/cloudlist")
public class CloudlistController {

    private static final Logger logger = LoggerFactory.getLogger(CloudlistController.class);

    private final CloudListService cloudListService;
    private final ResourceDetailService resourceDetailService;
    private final CloudAccountRepository cloudAccountRepository;

    public CloudlistController(CloudListService cloudListService,
            ResourceDetailService resourceDetailService,
            CloudAccountRepository cloudAccountRepository) {
        this.cloudListService = cloudListService;
        this.resourceDetailService = resourceDetailService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Helper method to get CloudAccount by accountId
     */
    private CloudAccount getAccount(String accountId) {
        if (accountId == null || accountId.isEmpty()) {
            throw new IllegalArgumentException("Account ID cannot be null or empty.");
        }

        // Handle multi-account strings
        String idToUse = accountId.contains(",") ? accountId.split(",")[0] : accountId;

        // Use the more robust provider-aware lookup
        return cloudAccountRepository.findByProviderAccountId(idToUse)
                .orElseGet(() -> {
                    // Fallback to older lookup if needed
                    List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountIdOrGcpProjectId(idToUse,
                            idToUse);
                    if (accounts.isEmpty()) {
                        throw new RuntimeException("Account not found: " + idToUse);
                    }
                    return accounts.get(0);
                });
    }

    /**
     * NEW ENDPOINT: Get services with pagination (5 services per page with all
     * their resources)
     */
    @GetMapping("/services-paginated")
    public CompletableFuture<ResponseEntity<ServicePaginatedResponse>> getServicesPaginated(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int servicesPerPage,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        // Handle multi-account IDs if passed (take the first one)
        String accountIdToUse = accountId.contains(",") ? accountId.split(",")[0] : accountId;

        logger.info("Fetching paginated services for account: {}, page: {}, servicesPerPage: {}",
                accountIdToUse, page, servicesPerPage);

        return cloudListService.getAllServicesGroupedPaginated(accountIdToUse, page, servicesPerPage, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching paginated services for account {}", accountIdToUse, ex);
                    return ResponseEntity.internalServerError().build();
                });
    }

    /**
     * EXISTING ENDPOINT - Get all resources grouped by service type
     */
    @GetMapping("/resources")
    public CompletableFuture<ResponseEntity<Object>> getAllResourcesGrouped(
            @RequestParam String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        String accountIdToUse = accountIds.split(",")[0];
        CloudAccount account = getAccount(accountIdToUse);

        if (!"AWS".equals(account.getProvider())) {
            logger.warn("Request for grouped cloudlist resources for a non-AWS account received. Returning empty.",
                    account.getProvider());
            return CompletableFuture.completedFuture(ResponseEntity.ok((Object) Collections.emptyList()));
        }

        if (forceRefresh) {
            cloudListService.triggerGetResourcesAsync(List.of(accountIdToUse));
            // Immediately return a response indicating the refresh has started
            return CompletableFuture.completedFuture(
                    ResponseEntity.accepted().body((Object) Collections.singletonMap("message",
                            "Refresh triggered for account: " + accountIdToUse)));
        }

        // Use explicit casts to bypass Java's async generic type capture issues
        return cloudListService.getAllResourcesGrouped(accountIdToUse, false)
                .thenApply(res -> (ResponseEntity<Object>) ResponseEntity.ok((Object) res))
                .exceptionally(ex -> {
                    logger.error("Error fetching grouped resources for AWS account {}", accountIdToUse, ex);
                    return (ResponseEntity<Object>) ResponseEntity.status(500).body((Object) Collections.emptyList());
                });
    }

    /**
     * EXISTING ENDPOINT - Fetches a flat list of all resources with pagination
     * support
     */
    @GetMapping("/all")
    public CompletableFuture<ResponseEntity<Page<ResourceDto>>> getAllResourcesPaginated(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        CloudAccount account = getAccount(accountId);

        if (!"AWS".equals(account.getProvider())) {
            logger.warn("Request for paginated resources for a non-AWS account received. Returning empty page.",
                    account.getProvider());
            return CompletableFuture.completedFuture(ResponseEntity.ok(Page.empty()));
        }

        return cloudListService.getAllResourcesPaginated(account, forceRefresh, page, size)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching paginated resources for AWS account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Page.empty());
                });
    }

    /**
     * EXISTING ENDPOINT - Fetches details for a specific resource
     */
    @GetMapping("/resource/{service}/{resourceId}")
    public CompletableFuture<ResponseEntity<ResourceDetailDto>> getResourceDetails(
            @RequestParam String accountId,
            @PathVariable String service,
            @PathVariable String resourceId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        String decodedService = service.replace("%20", " ");

        return resourceDetailService.getResourceDetails(accountId, decodedService, resourceId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Failed to get details for resource {} in account {}", service, resourceId, accountId,
                            ex);
                    return ResponseEntity.status(500).body(null);
                });
    }

    /**
     * Triggers a background refresh of resources
     * The client should listen on the WebSocket for completion
     */
    @PostMapping("/trigger-refresh")
    public ResponseEntity<java.util.Map<String, Object>> triggerRefresh(@RequestParam List<String> accountIds) {
        logger.info("Triggering cloudlist refresh for accounts: {}", accountIds);
        cloudListService.triggerGetResourcesAsync(accountIds);

        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("message", "Refresh triggered for accounts");
        response.put("accountIds", accountIds);
        response.put("status", "accepted");

        return ResponseEntity.ok(response);
    }

    /**
     * Get region statuses for an account
     */
    @GetMapping("/regions")
    public CompletableFuture<ResponseEntity<List<DashboardData.RegionStatus>>> getRegions(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("Fetching region statuses for account: {}", accountId);

        CloudAccount account = getAccount(accountId);

        return cloudListService.getRegionStatusForAccount(account, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching regions for account {}", accountId, ex);
                    return ResponseEntity.ok(Collections.emptyList());
                });
    }
}