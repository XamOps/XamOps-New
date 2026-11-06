package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ConnectedResourceDto;
import com.xammer.cloud.dto.LabelAssignRequest;
import com.xammer.cloud.dto.LabelSplitRequest;
import com.xammer.cloud.dto.LabeledResourceDto;
import com.xammer.cloud.dto.ResourceLabelDto;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CloudAccountAccessService;
import com.xammer.cloud.service.ConnectedResourceService;
import com.xammer.cloud.service.LabelAuditService;
import com.xammer.cloud.service.ResourceLabelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/xamops/labels")
public class ResourceLabelController {

    private static final Logger logger = LoggerFactory.getLogger(ResourceLabelController.class);

    private final ResourceLabelService labelService;
    private final ConnectedResourceService connectedResourceService;
    private final LabelAuditService audit;
    private final CloudAccountAccessService accessService;

    public ResourceLabelController(ResourceLabelService labelService,
                                   ConnectedResourceService connectedResourceService,
                                   LabelAuditService audit,
                                   CloudAccountAccessService accessService) {
        this.labelService = labelService;
        this.connectedResourceService = connectedResourceService;
        this.audit = audit;
        this.accessService = accessService;
    }

    /**
     * Runs a label write, and records it in the audit trail as refused if the service rejects it.
     *
     * <p>Only {@link IllegalArgumentException} counts as a refusal: that is what the label service
     * throws for a bad request — shares over 100%, a label that is not the caller's, a duplicate
     * name. The error still reaches the caller unchanged; a failure to record it is logged and
     * dropped, so it can never replace the original error.
     */
    private <T> T auditingRejection(ClientUserDetails user, LabelAuditService.Attempt attempt, Supplier<T> write) {
        try {
            return write.get();
        } catch (IllegalArgumentException e) {
            try {
                audit.recordRejected(user.getClientId(), user.getUsername(), attempt, e.getMessage());
            } catch (RuntimeException auditFailure) {
                logger.warn("[LabelAudit] Could not record a refused {}: {}", attempt.action(), auditFailure.getMessage());
            }
            throw e;
        }
    }

    private static List<LabelAuditService.Target> targetsOf(List<LabelAssignRequest.ResourceRef> refs) {
        if (refs == null) return List.of();
        return refs.stream()
                .filter(r -> r != null && r.getResourceId() != null && !r.getResourceId().isBlank())
                .map(r -> new LabelAuditService.Target(r.getResourceId(), r.getResourceName()))
                .toList();
    }

    /** The shares a refused save asked for, in the shape stored for an accepted one. */
    private static List<LabelAuditService.ShareSnapshot> attempted(List<LabelSplitRequest.Entry> entries) {
        if (entries == null) return List.of();
        return entries.stream()
                .filter(e -> e != null)
                .map(e -> new LabelAuditService.ShareSnapshot(e.getLabelId(), null, null, e.getPercent()))
                .toList();
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
        LabelAuditService.Attempt attempt = new LabelAuditService.Attempt(
                LabelAuditService.Action.LABEL_CREATED, null, body.getSource(), null, LabelAuditService.LABEL,
                List.of(new LabelAuditService.Target(null, body.getName())),
                new LabelAuditService.LabelSnapshot(null, body.getName(), body.getColor(), body.getDescription()));
        ResourceLabelDto created = auditingRejection(user, attempt, () -> labelService.createLabel(
                user.getClientId(),
                body.getName(),
                body.getColor(),
                body.getDescription(),
                user.getUsername(),
                body.getSource()));
        return ResponseEntity.ok(created);
    }

    /** Update label name / color / description */
    @PutMapping("/{labelId}")
    public ResponseEntity<ResourceLabelDto> updateLabel(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long labelId,
            @RequestBody CreateLabelRequest body) {
        LabelAuditService.Attempt attempt = new LabelAuditService.Attempt(
                LabelAuditService.Action.LABEL_UPDATED, null, body.getSource(), labelId, LabelAuditService.LABEL,
                List.of(new LabelAuditService.Target(String.valueOf(labelId), body.getName())),
                new LabelAuditService.LabelSnapshot(labelId, body.getName(), body.getColor(), body.getDescription()));
        ResourceLabelDto updated = auditingRejection(user, attempt, () -> labelService.updateLabel(
                user.getClientId(), labelId,
                body.getName(), body.getColor(), body.getDescription(),
                user.getUsername(), body.getSource()));
        return ResponseEntity.ok(updated);
    }

    /** Delete a label (also removes all its resource assignments) */
    @DeleteMapping("/{labelId}")
    public ResponseEntity<Void> deleteLabel(
            @AuthenticationPrincipal ClientUserDetails user,
            @PathVariable Long labelId,
            @RequestParam(required = false) String source) {
        LabelAuditService.Attempt attempt = new LabelAuditService.Attempt(
                LabelAuditService.Action.LABEL_DELETED, null, source, labelId, LabelAuditService.LABEL,
                List.of(new LabelAuditService.Target(String.valueOf(labelId), null)), null);
        auditingRejection(user, attempt, () -> {
            labelService.deleteLabel(user.getClientId(), labelId, user.getUsername(), source);
            return null;
        });
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
        LabelAuditService.Attempt attempt = new LabelAuditService.Attempt(
                LabelAuditService.Action.LABEL_ASSIGNED, req.getAccountId(), req.getSource(), req.getLabelId(),
                LabelAuditService.RESOURCE, targetsOf(req.getResources()), null);
        int count = auditingRejection(user, attempt,
                () -> labelService.assignLabel(user.getClientId(), req, user.getUsername()));
        return ResponseEntity.ok(Map.of("assigned", count));
    }

    /** Remove a label from one or many resources */
    @PostMapping("/remove")
    public ResponseEntity<Void> removeLabel(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestBody LabelAssignRequest req) {
        LabelAuditService.Attempt attempt = new LabelAuditService.Attempt(
                LabelAuditService.Action.LABEL_REMOVED, req.getAccountId(), req.getSource(), req.getLabelId(),
                LabelAuditService.RESOURCE, targetsOf(req.getResources()), null);
        auditingRejection(user, attempt, () -> {
            labelService.removeLabel(user.getClientId(), req, user.getUsername());
            return null;
        });
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Label shares — set every label on one resource or service at once
    // -----------------------------------------------------------------------

    /**
     * Replaces a resource's labels with exactly the ones given, each with an optional percent.
     *
     * PUT body: { "accountId": "...", "resource": { "resourceId": "...", ... },
     *             "labels": [ { "labelId": 3, "percent": 40 }, { "labelId": 5, "percent": 40 } ] }
     *
     * The shares may add up to less than 100 — the rest is unallocated — but never more.
     */
    @PutMapping("/resource-split")
    public ResponseEntity<List<ResourceLabelDto>> setResourceLabels(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestBody LabelSplitRequest req) {
        // The cost page's label controls are AWS-only, so the account must be one the caller holds.
        accessService.requireAwsAccess(user, req.getAccountId());
        LabelAssignRequest.ResourceRef ref = req.getResource();
        LabelAuditService.Attempt attempt = new LabelAuditService.Attempt(
                LabelAuditService.Action.LABELS_SET, req.getAccountId(), req.getSource(), null,
                LabelAuditService.RESOURCE,
                ref == null ? List.of() : targetsOf(List.of(ref)), attempted(req.getLabels()));
        return ResponseEntity.ok(auditingRejection(user, attempt,
                () -> labelService.setResourceLabels(user.getClientId(), req, user.getUsername())));
    }

    /** Every service label on one account: { "Amazon Elastic Compute Cloud": [label, ...], ... }. */
    @GetMapping("/service-map")
    public ResponseEntity<Map<String, List<ResourceLabelDto>>> getServiceLabels(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestParam String accountId) {
        accessService.requireAwsAccess(user, accountId);
        return ResponseEntity.ok(labelService.getServiceLabels(user.getClientId(), accountId));
    }

    /** As {@code /resource-split}, for a whole billing service. Body carries "service" instead of "resource". */
    @PutMapping("/service-split")
    public ResponseEntity<List<ResourceLabelDto>> setServiceLabels(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestBody LabelSplitRequest req) {
        accessService.requireAwsAccess(user, req.getAccountId());
        String service = req.getService() == null ? null : req.getService().trim();
        LabelAuditService.Attempt attempt = new LabelAuditService.Attempt(
                LabelAuditService.Action.LABELS_SET, req.getAccountId(), req.getSource(), null,
                LabelAuditService.SERVICE,
                service == null || service.isEmpty() ? List.of() : List.of(new LabelAuditService.Target(service, service)),
                attempted(req.getLabels()));
        return ResponseEntity.ok(auditingRejection(user, attempt,
                () -> labelService.setServiceLabels(user.getClientId(), req, user.getUsername())));
    }

    // -----------------------------------------------------------------------
    // Audit trail
    // -----------------------------------------------------------------------

    /**
     * Who changed which labels and shares, newest first.
     *
     * <p>Without {@code targetType}: everything on the account, plus the client's label edits.
     * With {@code targetType=RESOURCE|SERVICE} and {@code targetId}: one resource or service. Page
     * back with {@code beforeId}, the id of the oldest event already shown.
     *
     * Example: GET /api/xamops/labels/history?accountId=123456789012&targetType=SERVICE&targetId=Kiro
     */
    @GetMapping("/history")
    public ResponseEntity<LabelAuditService.HistoryPage> history(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestParam String accountId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "30") int size) {
        CloudAccount account = accessService.requireAwsAccess(user, accountId);
        if (targetType == null || targetType.isBlank()) {
            return ResponseEntity.ok(audit.accountHistory(user.getClientId(), account, beforeId, size));
        }
        return ResponseEntity.ok(audit.targetHistory(user.getClientId(), account, targetType, targetId, beforeId, size));
    }

    /** 404, not 403, so these endpoints cannot be used to learn which account ids exist. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found."));
    }

    // -----------------------------------------------------------------------
    // Cloud List enrichment — called once per page load
    // -----------------------------------------------------------------------

    /**
     * Returns a map of { resourceId → [label, label, ...] } for the given resource IDs.
     * The Cloud List page and the Cost page's per-resource view both call this after
     * fetching resources so they can show label chips on each row without an N+1 pattern.
     *
     * <p>Resource ids may be in whichever form the caller holds — the inventory's short id
     * or a full ARN from the Cost and Usage Report. The response is keyed by the ids as
     * asked for, so a caller can look up its own rows directly.
     *
     * <p>Scoped to the caller's client. It was not: the account is reachable by its provider
     * account id, which is not a secret, so the unscoped version let any signed-in user read
     * another tenant's labels for an account id they could name.
     *
     * POST body: { "accountId": "123456789012", "resourceIds": ["i-abc", "vol-xyz", ...] }
     */
    @PostMapping("/resource-map")
    public ResponseEntity<Map<String, List<ResourceLabelDto>>> getLabelsForResources(
            @AuthenticationPrincipal ClientUserDetails user,
            @RequestBody ResourceMapRequest req) {
        Map<String, List<ResourceLabelDto>> map = labelService.getLabelsForResources(
                user.getClientId(), req.getAccountId(), req.getResourceIds());
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

        /** Which screen made the change, for the audit trail. Optional. */
        private String source;

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
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
