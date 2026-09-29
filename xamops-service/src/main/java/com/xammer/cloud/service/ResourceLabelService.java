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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final ResourceIdResolver idResolver;

    public ResourceLabelService(ResourceLabelRepository labelRepo,
                                ResourceLabelAssignmentRepository assignmentRepo,
                                CloudAccountRepository accountRepo,
                                CostService costService,
                                ResourceIdResolver idResolver) {
        this.labelRepo = labelRepo;
        this.assignmentRepo = assignmentRepo;
        this.accountRepo = accountRepo;
        this.costService = costService;
        this.idResolver = idResolver;
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
     *
     * <p>
     * Ids are normalised through {@link ResourceIdResolver} before they are stored, so a
     * caller may pass whichever form of a resource's id it happens to hold. This is what
     * lets the Cloud List and the Cost page's per-resource view share one set of labels:
     * the first arrives with the inventory's short id, the second with the Cost and Usage
     * Report's {@code line_item_resource_id}, which is a full ARN for several services. Both
     * land on the same row.
     *
     * <p>
     * Where the inventory knows the resource, its type and region win over the caller's.
     * The CUR's idea of a type is a billing product name ("Amazon Elastic Compute Cloud -
     * Compute"); the inventory's is the type vocabulary the rest of the product filters on
     * ("EC2 Instance"). Storing whichever the caller sent would leave the view-by-label page
     * and the per-label cost fallback reading two vocabularies for one resource.
     */
    @Transactional
    public int assignLabel(Long clientId, LabelAssignRequest req, String assignedBy) {
        ResourceLabel label = labelRepo.findByIdAndClientId(req.getLabelId(), clientId)
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + req.getLabelId()));

        CloudAccount account = resolveAccount(req.getAccountId());
        Map<String, ResourceIdResolver.CanonicalResource> canonical = canonicalise(account, req);

        int created = 0;
        for (LabelAssignRequest.ResourceRef ref : req.getResources()) {
            if (ref.getResourceId() == null || ref.getResourceId().isBlank()) {
                // Resource-less charges — tax, support, subscriptions — reach the cost view as
                // rows with an empty id. They are not resources and cannot be labelled: every
                // one of them would collide on the same key.
                continue;
            }

            ResourceIdResolver.CanonicalResource known = canonical.get(ref.getResourceId());
            String resourceId = known != null ? known.resourceId() : ref.getResourceId();
            String resourceType = known != null && known.resourceType() != null
                    ? known.resourceType() : ref.getResourceType();
            if (resourceType == null || resourceType.isBlank()) resourceType = "Unknown";

            if (!assignmentRepo.existsByCloudAccountIdAndResourceIdAndLabelId(
                    account.getId(), resourceId, label.getId())) {

                ResourceLabelAssignment a = new ResourceLabelAssignment();
                a.setCloudAccount(account);
                a.setResourceId(resourceId);
                a.setResourceType(resourceType);
                a.setResourceName(known != null && known.resourceName() != null
                        ? known.resourceName() : ref.getResourceName());
                a.setRegion(known != null && known.region() != null ? known.region() : ref.getRegion());
                a.setLabel(label);
                a.setTaggedBy(assignedBy);
                assignmentRepo.save(a);
                created++;
            }
        }
        logger.info("Assigned label '{}' to {} resources (account={})", label.getName(), created, account.getId());
        return created;
    }

    /**
     * Take a label off one or many resources.
     *
     * <p>
     * Deletes under both the id as given and its canonical form. The two are usually the
     * same, but not for rows written before ids were normalised — an ARN-keyed assignment
     * from the cost view, say. Removing only the canonical key would leave those behind and
     * make the × on a badge look broken, since the next read still finds them.
     */
    @Transactional
    public void removeLabel(Long clientId, LabelAssignRequest req) {
        labelRepo.findByIdAndClientId(req.getLabelId(), clientId)
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + req.getLabelId()));

        CloudAccount account = resolveAccount(req.getAccountId());
        Map<String, ResourceIdResolver.CanonicalResource> canonical = canonicalise(account, req);

        for (LabelAssignRequest.ResourceRef ref : req.getResources()) {
            String raw = ref.getResourceId();
            if (raw == null || raw.isBlank()) continue;

            assignmentRepo.deleteByCloudAccountIdAndResourceIdAndLabelId(
                    account.getId(), raw, req.getLabelId());

            ResourceIdResolver.CanonicalResource known = canonical.get(raw);
            if (known != null && !known.resourceId().equals(raw)) {
                assignmentRepo.deleteByCloudAccountIdAndResourceIdAndLabelId(
                        account.getId(), known.resourceId(), req.getLabelId());
            }
        }
    }

    /** Resolves every id in a request in one query rather than one per resource. */
    private Map<String, ResourceIdResolver.CanonicalResource> canonicalise(
            CloudAccount account, LabelAssignRequest req) {
        if (req.getResources() == null || req.getResources().isEmpty()) return Map.of();
        return idResolver.resolve(account.getId(), req.getResources().stream()
                .map(LabelAssignRequest.ResourceRef::getResourceId)
                .filter(id -> id != null && !id.isBlank())
                .toList());
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
     *
     * <p>
     * Keyed by the ids the caller asked about, not by the ids the rows are stored under. A
     * page holding CUR ids can therefore look up its own rows directly, while the storage
     * key stays the canonical one the Cloud List uses — the indirection is the whole reason
     * both pages see one set of labels.
     *
     * <p>
     * Both keys are read and merged, so an assignment written under an ARN before ids were
     * normalised still shows up next to one written under the short id, and a resource never
     * appears untagged on one page and tagged on the other.
     */
    public Map<String, List<ResourceLabelDto>> getLabelsForResources(
            Long clientId, String accountId, List<String> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) return Map.of();

        CloudAccount account = resolveAccount(accountId);
        Map<String, ResourceIdResolver.CanonicalResource> canonical =
                idResolver.resolve(account.getId(), resourceIds);

        // One query covering both forms of every id.
        LinkedHashSet<String> lookupKeys = new LinkedHashSet<>();
        for (String id : resourceIds) {
            if (id != null && !id.isBlank()) lookupKeys.add(id);
        }
        canonical.values().forEach(c -> lookupKeys.add(c.resourceId()));
        if (lookupKeys.isEmpty()) return Map.of();

        List<ResourceLabelAssignment> assignments = assignmentRepo
                .findByCloudAccountIdAndResourceIdInForClient(
                        account.getId(), List.copyOf(lookupKeys), clientId);

        Map<String, List<ResourceLabelDto>> byStorageKey = new HashMap<>();
        for (ResourceLabelAssignment a : assignments) {
            byStorageKey.computeIfAbsent(a.getResourceId(), k -> new ArrayList<>())
                  .add(ResourceLabelDto.from(a.getLabel()));
        }

        Map<String, List<ResourceLabelDto>> result = new HashMap<>();
        for (String requested : resourceIds) {
            if (requested == null || requested.isBlank()) continue;

            // Deduplicated by label id: a resource with rows under both its ARN and its short
            // id would otherwise render the same chip twice.
            Map<Long, ResourceLabelDto> merged = new LinkedHashMap<>();
            for (ResourceLabelDto dto : byStorageKey.getOrDefault(requested, List.of())) {
                merged.putIfAbsent(dto.getId(), dto);
            }
            ResourceIdResolver.CanonicalResource known = canonical.get(requested);
            if (known != null) {
                for (ResourceLabelDto dto : byStorageKey.getOrDefault(known.resourceId(), List.of())) {
                    merged.putIfAbsent(dto.getId(), dto);
                }
            }
            if (!merged.isEmpty()) result.put(requested, new ArrayList<>(merged.values()));
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
