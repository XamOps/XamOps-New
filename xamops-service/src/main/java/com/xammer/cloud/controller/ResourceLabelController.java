package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ConnectedResourceDto;
import com.xammer.cloud.dto.LabelAssignRequest;
import com.xammer.cloud.dto.LabeledResourceDto;
import com.xammer.cloud.dto.ResourceLabelDto;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.ConnectedResourceService;
import com.xammer.cloud.service.ResourceLabelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/labels")
public class ResourceLabelController {

    private static final Logger logger = LoggerFactory.getLogger(ResourceLabelController.class);

    private final ResourceLabelService labelService;
    private final ConnectedResourceService connectedResourceService;

    public ResourceLabelController(ResourceLabelService labelService,
                                   ConnectedResourceService connectedResourceService) {
        this.labelService = labelService;
        this.connectedResourceService = connectedResourceService;
    }

    // -----------------------------------------------------------------------
    // Label CRUD
    // -----------------------------------------------------------------------

    /** List all labels for the current client (includes resourceCount per label) */
    @GetMapping
    public ResponseEntity<List<ResourceLabelDto>> listLabels(
            @AuthenticationPrincipal ClientUserDetails user) {
        return ResponseEntity.ok(labelService.getLabelsForClient(user.getClientId()));
    }

    /** Create a new label */
    @PostMapping
    public ResponseEntity<ResourceLabelDto> createLabel(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestBody CreateLabelRequest body) {
        ResourceLabelDto created = labelService.createLabel(
                user.getClientId(),
                body.getName(),
                body.getColor(),
                body.getDescription(),
                user.getUsername());
        return ResponseEntity.ok(created);
    }

    /** Update label name / color / description */
    @PutMapping("/{labelId}")
    public ResponseEntity<ResourceLabelDto> updateLabel(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long labelId,
            @RequestBody CreateLabelRequest body) {
        ResourceLabelDto updated = labelService.updateLabel(
                user.getClientId(), labelId,
                body.getName(), body.getColor(), body.getDescription());
        return ResponseEntity.ok(updated);
    }

    /** Delete a label (also removes all its resource assignments) */
    @DeleteMapping("/{labelId}")
    public ResponseEntity<Void> deleteLabel(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long labelId) {
        labelService.deleteLabel(user.getClientId(), labelId);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Assign / remove labels from resources
    // -----------------------------------------------------------------------

    /** Assign a label to one or many resources (idempotent) */
    @PostMapping("/assign")
    public ResponseEntity<Map<String, Object>> assignLabel(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestBody LabelAssignRequest req) {
        int count = labelService.assignLabel(user.getClientId(), req, user.getUsername());
        return ResponseEntity.ok(Map.of("assigned", count));
    }

    /** Remove a label from one or many resources */
    @PostMapping("/remove")
    public ResponseEntity<Void> removeLabel(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestBody LabelAssignRequest req) {
        labelService.removeLabel(user.getClientId(), req);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Cloud List enrichment — called once per page load
    // -----------------------------------------------------------------------

    /**
     * Returns a map of { resourceId → [label, label, ...] } for the given resource IDs.
     * The Cloud List page calls this after fetching resources so it can show label chips
     * on each row without an N+1 pattern.
     *
     * POST body: { "cloudAccountId": 42, "resourceIds": ["i-abc", "vol-xyz", ...] }
     */
    @PostMapping("/resource-map")
    public ResponseEntity<Map<String, List<ResourceLabelDto>>> getLabelsForResources(
            @RequestBody ResourceMapRequest req) {
        Map<String, List<ResourceLabelDto>> map =
                labelService.getLabelsForResources(req.getAccountId(), req.getResourceIds());
        return ResponseEntity.ok(map);
    }

    // -----------------------------------------------------------------------
    // "View by label" page
    // -----------------------------------------------------------------------

    /** Paginated list of resources assigned to a specific label */
    @GetMapping("/{labelId}/resources")
    public ResponseEntity<Page<LabeledResourceDto>> getResourcesByLabel(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long labelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Page<LabeledResourceDto> result = labelService.getResourcesByLabel(
                user.getClientId(), labelId,
                PageRequest.of(page, size, Sort.by("taggedAt").descending()));
        return ResponseEntity.ok(result);
    }

    // -----------------------------------------------------------------------
    // Per-label cost breakdown
    // -----------------------------------------------------------------------

    /**
     * Returns cost data for all resources assigned to a label.
     *
     * Example: GET /api/xamops/labels/3/cost?accountId=123456789012&startDate=2026-03-01&endDate=2026-03-24
     *
     * Response: { "costs": [...], "resourceLevel": true|false, "message": "..." }
     */
    @GetMapping("/{labelId}/cost")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> getLabelCost(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long labelId,
            @RequestParam String accountId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        String start = startDate != null ? startDate : LocalDate.now().withDayOfMonth(1).toString();
        String end = endDate != null ? endDate : LocalDate.now().toString();

        logger.info("💰 Label cost request - labelId={}, accountId={}, {}/{}", labelId, accountId, start, end);

        return labelService.getLabelCostBreakdown(user.getClientId(), labelId, accountId, start, end)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("❌ Error fetching label cost for labelId={}: {}", labelId, ex.getMessage());
                    return ResponseEntity.<Map<String, Object>>status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    // -----------------------------------------------------------------------
    // Connected Resources — infrastructure related to a given resource
    // -----------------------------------------------------------------------

    /**
     * Returns the infrastructure resources connected to a given resource.
     * Currently supports EC2 Instance (→ EBS volumes, Elastic IPs, Load Balancers)
     * and Load Balancer (→ EC2 instances behind it).
     *
     * Example: GET /api/xamops/labels/connected-resources?accountId=123456789012&resourceId=i-09abc&resourceType=EC2+Instance&region=ap-south-1
     */
    @GetMapping("/connected-resources")
    public ResponseEntity<List<ConnectedResourceDto>> getConnectedResources(
            @RequestParam String accountId,
            @RequestParam String resourceId,
            @RequestParam String resourceType,
            @RequestParam(required = false, defaultValue = "us-east-1") String region) {

        logger.info("🔗 Connected resources: type={} id={} account={} region={}",
                resourceType, resourceId, accountId, region);
        List<ConnectedResourceDto> resources =
                connectedResourceService.getConnectedResources(accountId, resourceId, resourceType, region);
        return ResponseEntity.ok(resources);
    }

    // -----------------------------------------------------------------------
    // Inner request bodies
    // -----------------------------------------------------------------------

    public static class CreateLabelRequest {
        private String name;
        private String color;
        private String description;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class ResourceMapRequest {
        private String accountId;   // provider account ID string (same as all other XamOps APIs)
        private List<String> resourceIds;

        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }

        public List<String> getResourceIds() { return resourceIds; }
        public void setResourceIds(List<String> resourceIds) { this.resourceIds = resourceIds; }
    }
}
