package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.gcp.GcpResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.gcp.GcpDataService;
import com.xammer.cloud.service.gcp.GcpNetworkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/cloudmap")
@Slf4j
public class GcpCloudmapController {

    private final GcpDataService gcpDataService;
    private final GcpNetworkService gcpNetworkService;
    private final CloudAccountRepository cloudAccountRepository;

    public GcpCloudmapController(GcpDataService gcpDataService,
                                 GcpNetworkService gcpNetworkService,
                                 CloudAccountRepository cloudAccountRepository) {
        this.gcpDataService = gcpDataService;
        this.gcpNetworkService = gcpNetworkService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @GetMapping("/vpcs")
    public CompletableFuture<ResponseEntity<List<GcpResourceDto>>> getVpcListForCloudmap(
            @RequestParam String accountId,
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh) {
        log.info("Fetching VPCs for accountId: {} (forceRefresh={})", accountId, forceRefresh);

        // Convert accountId to gcpProjectId
        Optional<String> gcpProjectIdOpt = getGcpProjectId(accountId);

        if (gcpProjectIdOpt.isEmpty()) {
            log.error("GCP account not found for accountId: {}", accountId);
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(Collections.emptyList())
            );
        }

        String gcpProjectId = gcpProjectIdOpt.get();
        log.info("Resolved gcpProjectId: {} for accountId: {}", gcpProjectId, accountId);

        return gcpDataService.getVpcListForCloudmap(gcpProjectId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    log.error("Error fetching VPCs for gcpProjectId: {}", gcpProjectId, ex);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
                });
    }

    @GetMapping("/graph")
    public CompletableFuture<ResponseEntity<List<Map<String, Object>>>> getGraphData(
            @RequestParam String accountId,
            @RequestParam(required = false) String vpcId) {
        log.info("Fetching graph data for accountId: {}, vpcId: {}", accountId, vpcId);

        // Convert accountId to gcpProjectId
        Optional<String> gcpProjectIdOpt = getGcpProjectId(accountId);

        if (gcpProjectIdOpt.isEmpty()) {
            log.error("GCP account not found for accountId: {}", accountId);
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(Collections.emptyList())
            );
        }

        String gcpProjectId = gcpProjectIdOpt.get();
        log.info("Resolved gcpProjectId: {} for accountId: {}", gcpProjectId, accountId);

        return gcpNetworkService.getNetworkTopologyGraph(gcpProjectId, vpcId)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    log.error("Error fetching graph data for gcpProjectId: {}", gcpProjectId, ex);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
                });
    }

    /**
     * Helper method to convert accountId to gcpProjectId
     * @param accountId The database account ID
     * @return Optional containing the GCP project ID if found
     */
    private Optional<String> getGcpProjectId(String accountId) {
        try {
            // Try to parse as Long (database ID)
            Long id = Long.parseLong(accountId);
            Optional<CloudAccount> accountOpt = cloudAccountRepository.findById(id);

            if (accountOpt.isPresent()) {
                CloudAccount account = accountOpt.get();
                if ("GCP".equalsIgnoreCase(account.getProvider()) && account.getGcpProjectId() != null) {
                    return Optional.of(account.getGcpProjectId());
                } else {
                    log.warn("Account found but is not a GCP account or missing gcpProjectId. Provider: {}",
                            account.getProvider());
                }
            }
        } catch (NumberFormatException e) {
            // If not a number, try to find by gcpProjectId directly
            log.debug("accountId is not a number, trying to find by gcpProjectId: {}", accountId);
            Optional<CloudAccount> accountOpt = cloudAccountRepository.findByGcpProjectId(accountId);
            if (accountOpt.isPresent()) {
                return Optional.of(accountOpt.get().getGcpProjectId());
            }
        }

        return Optional.empty();
    }
}