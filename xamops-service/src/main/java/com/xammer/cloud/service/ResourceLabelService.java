package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.ResourceLabel;
import com.xammer.cloud.domain.ResourceLabelAssignment;
import com.xammer.cloud.dto.LabelAssignRequest;
import com.xammer.cloud.dto.LabeledResourceDto;
import com.xammer.cloud.dto.ResourceLabelDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ResourceLabelAssignmentRepository;
import com.xammer.cloud.repository.ResourceLabelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class ResourceLabelService {

    private static final Logger logger = LoggerFactory.getLogger(ResourceLabelService.class);

    private final ResourceLabelRepository labelRepo;
    private final ResourceLabelAssignmentRepository assignmentRepo;
    private final CloudAccountRepository accountRepo;
    private final CostService costService;

    public ResourceLabelService(ResourceLabelRepository labelRepo,
                                ResourceLabelAssignmentRepository assignmentRepo,
                                CloudAccountRepository accountRepo,
                                CostService costService) {
        this.labelRepo = labelRepo;
        this.assignmentRepo = assignmentRepo;
        this.accountRepo = accountRepo;
        this.costService = costService;
    }

    // -----------------------------------------------------------------------
    // Label CRUD
    // -----------------------------------------------------------------------

    public List<ResourceLabelDto> getLabelsForClient(Long clientId) {
        return labelRepo.findByClientIdOrderByNameAsc(clientId)
                .stream()
                .map(label -> {
                    ResourceLabelDto dto = ResourceLabelDto.from(label);
                    dto.setResourceCount(assignmentRepo.countByLabelId(label.getId()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public ResourceLabelDto createLabel(Long clientId, String name, String color, String description, String createdBy) {
        if (labelRepo.existsByClientIdAndName(clientId, name)) {
            throw new IllegalArgumentException("A label named '" + name + "' already exists.");
        }
        ResourceLabel label = new ResourceLabel();
        Client client = new Client();
        client.setId(clientId);
        label.setClient(client);
        label.setName(name.trim());
        label.setColor(color != null ? color : "#6366f1");
        label.setDescription(description);
        label.setCreatedBy(createdBy);
        return ResourceLabelDto.from(labelRepo.save(label));
    }

    @Transactional
    public ResourceLabelDto updateLabel(Long clientId, Long labelId, String name, String color, String description) {
        ResourceLabel label = labelRepo.findByIdAndClientId(labelId, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + labelId));

        if (name != null && !name.equals(label.getName())) {
            if (labelRepo.existsByClientIdAndName(clientId, name)) {
                throw new IllegalArgumentException("A label named '" + name + "' already exists.");
            }
            label.setName(name.trim());
        }
        if (color != null) label.setColor(color);
        if (description != null) label.setDescription(description);

        return ResourceLabelDto.from(labelRepo.save(label));
    }

    @Transactional
    public void deleteLabel(Long clientId, Long labelId) {
        ResourceLabel label = labelRepo.findByIdAndClientId(labelId, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + labelId));
        // Cascade deletes all assignments via DB constraint
        labelRepo.delete(label);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Mirrors the exact account lookup used by CloudlistController */
    private CloudAccount resolveAccount(String accountId) {
        return accountRepo.findByProviderAccountId(accountId)
                .orElseGet(() -> {
                    List<CloudAccount> list = accountRepo.findByAwsAccountIdOrGcpProjectId(accountId, accountId);
                    if (list.isEmpty()) throw new IllegalArgumentException("Account not found: " + accountId);
                    return list.get(0);
                });
    }

    // -----------------------------------------------------------------------
    // Resource → Label assignments
    // -----------------------------------------------------------------------

    /**
     * Assign a label to one or many resources (idempotent — safe to call twice).
     * accountId is the provider account ID string (AWS account ID, GCP project ID, etc.)
     */
    @Transactional
    public int assignLabel(Long clientId, LabelAssignRequest req, String assignedBy) {
        ResourceLabel label = labelRepo.findByIdAndClientId(req.getLabelId(), clientId)
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + req.getLabelId()));

        CloudAccount account = resolveAccount(req.getAccountId());

        int created = 0;
        for (LabelAssignRequest.ResourceRef ref : req.getResources()) {
            if (!assignmentRepo.existsByCloudAccountIdAndResourceIdAndLabelId(
                    account.getId(), ref.getResourceId(), label.getId())) {

                ResourceLabelAssignment a = new ResourceLabelAssignment();
                a.setCloudAccount(account);
                a.setResourceId(ref.getResourceId());
                a.setResourceType(ref.getResourceType());
                a.setResourceName(ref.getResourceName());
                a.setRegion(ref.getRegion());
                a.setLabel(label);
                a.setTaggedBy(assignedBy);
                assignmentRepo.save(a);
                created++;
            }
        }
        logger.info("Assigned label '{}' to {} resources (account={})", label.getName(), created, account.getId());
        return created;
    }

    @Transactional
    public void removeLabel(Long clientId, LabelAssignRequest req) {
        labelRepo.findByIdAndClientId(req.getLabelId(), clientId)
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + req.getLabelId()));

        CloudAccount account = resolveAccount(req.getAccountId());
        for (LabelAssignRequest.ResourceRef ref : req.getResources()) {
            assignmentRepo.deleteByCloudAccountIdAndResourceIdAndLabelId(
                    account.getId(), ref.getResourceId(), req.getLabelId());
        }
    }

    @Transactional
    public void clearResourceLabels(String accountId, String resourceId) {
        CloudAccount account = resolveAccount(accountId);
        assignmentRepo.deleteByCloudAccountIdAndResourceId(account.getId(), resourceId);
    }

    // -----------------------------------------------------------------------
    // Cloud List enrichment — one query for a full page of resources
    // -----------------------------------------------------------------------

    /**
     * Returns resourceId → [labels] for all given resource IDs in one query (no N+1).
     */
    public Map<String, List<ResourceLabelDto>> getLabelsForResources(String accountId, List<String> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) return Map.of();

        CloudAccount account = resolveAccount(accountId);
        List<ResourceLabelAssignment> assignments =
                assignmentRepo.findByCloudAccountIdAndResourceIdIn(account.getId(), resourceIds);

        Map<String, List<ResourceLabelDto>> result = new HashMap<>();
        for (ResourceLabelAssignment a : assignments) {
            result.computeIfAbsent(a.getResourceId(), k -> new ArrayList<>())
                  .add(ResourceLabelDto.from(a.getLabel()));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // "View by label" page — paginated list of resources for one label
    // -----------------------------------------------------------------------

    public Page<LabeledResourceDto> getResourcesByLabel(Long clientId, Long labelId, Pageable pageable) {
        labelRepo.findByIdAndClientId(labelId, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + labelId));

        return assignmentRepo.findByLabelId(labelId, pageable)
                .map(LabeledResourceDto::from);
    }

    // -----------------------------------------------------------------------
    // Per-label cost breakdown
    // -----------------------------------------------------------------------

    /**
     * Returns cost data for all resources assigned to a label.
     * Delegates to CostService which tries RESOURCE_ID filtering first,
     * then falls back to SERVICE-level filtering.
     */
    public CompletableFuture<Map<String, Object>> getLabelCostBreakdown(
            Long clientId, Long labelId, String accountId, String startDate, String endDate) {

        labelRepo.findByIdAndClientId(labelId, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + labelId));

        List<ResourceLabelAssignment> assignments = assignmentRepo.findAllAssignmentsByLabelIdAndAwsAccountId(labelId, accountId);
        List<String> resourceIds = assignments.stream()
                .map(ResourceLabelAssignment::getResourceId)
                .collect(Collectors.toList());
        List<String> resourceTypes = assignments.stream()
                .map(ResourceLabelAssignment::getResourceType)
                .distinct()
                .collect(Collectors.toList());

        // Build a human-readable list of tracked resources to include in the response
        List<Map<String, String>> trackedResources = assignments.stream()
                .map(a -> {
                    Map<String, String> info = new HashMap<>();
                    info.put("id", a.getResourceId());
                    info.put("name", a.getResourceName() != null ? a.getResourceName() : a.getResourceId());
                    info.put("type", a.getResourceType());
                    info.put("region", a.getRegion() != null ? a.getRegion() : "");
                    return info;
                })
                .collect(Collectors.toList());

        return costService.getCostForLabelResources(accountId, resourceIds, resourceTypes, trackedResources, startDate, endDate)
                .thenApply(result -> {
                    result.put("trackedResources", trackedResources);
                    return result;
                });
    }
}
