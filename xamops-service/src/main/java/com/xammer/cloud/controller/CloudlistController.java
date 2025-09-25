package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ResourceDetailDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.ResourceDetailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/cloudlist")
public class CloudlistController {

    private static final Logger logger = LoggerFactory.getLogger(CloudlistController.class);

    private final CloudListService cloudListService;
    private final ResourceDetailService resourceDetailService;
    private final CloudAccountRepository cloudAccountRepository;

    public CloudlistController(CloudListService cloudListService, ResourceDetailService resourceDetailService, CloudAccountRepository cloudAccountRepository) {
        this.cloudListService = cloudListService;
        this.resourceDetailService = resourceDetailService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @GetMapping("/resources")
    public CompletableFuture<ResponseEntity<List<DashboardData.ServiceGroupDto>>> getAllResources(
            @RequestParam String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        String accountIdToUse = accountIds.split(",")[0];

        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByProviderAccountId(accountIdToUse);

        if (accountOpt.isEmpty()) {
            logger.error("No account found for identifier: {}", accountIdToUse);
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Collections.emptyList()));
        }

        CloudAccount account = accountOpt.get();

        if (!"AWS".equals(account.getProvider())) {
            logger.warn("Request for cloudlist resources for a non-AWS account ({}) received by AWS controller. Returning empty.", account.getProvider());
            return CompletableFuture.completedFuture(ResponseEntity.ok(Collections.emptyList()));
        }

        return cloudListService.getAllResourcesGrouped(accountIdToUse, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching grouped resources for AWS account {}", accountIdToUse, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

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
                    logger.error("Failed to get details for resource {}/{} in account {}", service, resourceId, accountId, ex);
                    return ResponseEntity.status(500).body(null);
                });
    }
}