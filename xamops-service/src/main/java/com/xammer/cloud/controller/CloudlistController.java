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
        // Find account by AWS Account ID or GCP Project ID
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountIdOrGcpProjectId(accountId, accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found: " + accountId);
        }
        return accounts.get(0); // Use the first matching account
    }

    /**
     * NEW ENDPOINT: Get services with pagination (5 services per page with all their resources)
     */
    @GetMapping("/services-paginated")
    public CompletableFuture<ResponseEntity<ServicePaginatedResponse>> getServicesPaginated(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int servicesPerPage,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("Fetching paginated services for account: {}, page: {}, servicesPerPage: {}",
                    accountId, page, servicesPerPage);

        return cloudListService.getAllServicesGroupedPaginated(accountId, page, servicesPerPage, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching paginated services for account {}", accountId, ex);
                    return ResponseEntity.internalServerError().build();
                });
    }

    /**
     * EXISTING ENDPOINT - Get all resources grouped by service type
     */
    @GetMapping("/resources")
    public CompletableFuture<ResponseEntity<List<DashboardData.ServiceGroupDto>>> getAllResourcesGrouped(
            @RequestParam String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        String accountIdToUse = accountIds.split(",")[0];
        CloudAccount account = getAccount(accountIdToUse);

        if (!"AWS".equals(account.getProvider())) {
            logger.warn("Request for grouped cloudlist resources for a non-AWS account received. Returning empty.", account.getProvider());
            return CompletableFuture.completedFuture(ResponseEntity.ok(Collections.emptyList()));
        }

        if (forceRefresh) {
            cloudListService.triggerGetResourcesAsync(List.of(accountIdToUse));
            // Immediately return a response indicating the refresh has started
            return CompletableFuture.completedFuture(ResponseEntity.accepted().build());
        }

        return cloudListService.getAllResourcesGrouped(accountIdToUse, false)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching grouped resources for AWS account {}", accountIdToUse, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    /**
     * EXISTING ENDPOINT - Fetches a flat list of all resources with pagination support
     */
    @GetMapping("/all")
    public CompletableFuture<ResponseEntity<Page<ResourceDto>>> getAllResourcesPaginated(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        CloudAccount account = getAccount(accountId);

        if (!"AWS".equals(account.getProvider())) {
            logger.warn("Request for paginated resources for a non-AWS account received. Returning empty page.", account.getProvider());
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
                    logger.error("Failed to get details for resource {} in account {}", service, resourceId, accountId, ex);
                    return ResponseEntity.status(500).body(null);
                });
    }

    /**
     * Triggers a background refresh of resources
     * The client should listen on the WebSocket for completion
     */
    @PostMapping("/trigger-refresh")
    public ResponseEntity<String> triggerRefresh(@RequestParam List<String> accountIds) {
        logger.info("Triggering cloudlist refresh for accounts: {}", accountIds);
        cloudListService.triggerGetResourcesAsync(accountIds);
        return ResponseEntity.ok("Refresh triggered for accounts: " + accountIds);
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