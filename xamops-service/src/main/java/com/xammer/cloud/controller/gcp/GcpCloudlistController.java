package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpResourceDto;
import com.xammer.cloud.dto.gcp.GcpServicePaginatedResponse;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/cloudlist")
public class GcpCloudlistController {

    private final GcpDataService gcpDataService;

    public GcpCloudlistController(GcpDataService gcpDataService) {
        this.gcpDataService = gcpDataService;
    }

    /**
     * Fetches all discoverable resources for a given GCP project.
     * @param accountId The GCP Project ID.
     * @return A CompletableFuture with a list of all discovered resources.
     */
    @GetMapping("/resources")
    public CompletableFuture<ResponseEntity<List<GcpResourceDto>>> getAllResources(@RequestParam String accountId,
                                                                                  @RequestParam(name = "forceRefresh", required = false, defaultValue = "false") boolean forceRefresh) {
        return gcpDataService.getAllResources(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * Fetches GCP resources grouped by service type, sorted by usage (most frequently used first).
     * Returns the top 50 most used services with pagination support.
     *
     * @param accountId The GCP Project ID.
     * @param page Page number (0-indexed, default: 0)
     * @param servicesPerPage Number of services per page (default: 10)
     * @param forceRefresh Whether to bypass cache (default: false)
     * @return Paginated response with services sorted by resource count (descending)
     */
    @GetMapping("/services-paginated")
    public CompletableFuture<ResponseEntity<GcpServicePaginatedResponse>> getServicesGroupedPaginated(
            @RequestParam String accountId,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "servicesPerPage", required = false, defaultValue = "10") int servicesPerPage,
            @RequestParam(name = "forceRefresh", required = false, defaultValue = "false") boolean forceRefresh) {

        return gcpDataService.getServicesGroupedPaginated(accountId, page, servicesPerPage, forceRefresh)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * Fetches all GCP resources grouped by service type (non-paginated).
     * Returns top 50 services sorted by usage (most used first).
     *
     * @param accountId The GCP Project ID.
     * @param forceRefresh Whether to bypass cache (default: false)
     * @return List of service groups sorted by resource count
     */
    @GetMapping("/resources-grouped")
    public CompletableFuture<ResponseEntity<List<GcpServicePaginatedResponse.GcpServiceGroupDto>>> getAllResourcesGrouped(
            @RequestParam String accountId,
            @RequestParam(name = "forceRefresh", required = false, defaultValue = "false") boolean forceRefresh) {

        return gcpDataService.getAllResourcesGrouped(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok);
    }
}