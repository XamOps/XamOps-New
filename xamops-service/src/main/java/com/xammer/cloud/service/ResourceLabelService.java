package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.ResourceLabel;
import com.xammer.cloud.domain.ResourceLabelAssignment;
import com.xammer.cloud.domain.ServiceLabelAssignment;
import com.xammer.cloud.dto.LabelAssignRequest;
import com.xammer.cloud.dto.LabelSplitRequest;
import com.xammer.cloud.dto.LabeledResourceDto;
import com.xammer.cloud.dto.ResourceLabelDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ResourceLabelAssignmentRepository;
import com.xammer.cloud.repository.ResourceLabelRepository;
import com.xammer.cloud.repository.ServiceLabelAssignmentRepository;
import com.xammer.cloud.service.LabelAuditService.Action;
import com.xammer.cloud.service.LabelAuditService.Change;
import com.xammer.cloud.service.LabelAuditService.LabelSnapshot;
import com.xammer.cloud.service.LabelAuditService.ShareSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ServiceLabelAssignmentRepository serviceAssignmentRepo;
    /**
     * Every write below records what it changed here, in the same transaction. The entry holds the
     * target's full set of labels and shares before and after, so it is read before the write.
     */
    private final LabelAuditService audit;

    public ResourceLabelService(ResourceLabelRepository labelRepo,
                                ResourceLabelAssignmentRepository assignmentRepo,
                                CloudAccountRepository accountRepo,
                                CostService costService,
                                ResourceIdResolver idResolver,
                                ServiceLabelAssignmentRepository serviceAssignmentRepo,
                                LabelAuditService audit) {
        this.labelRepo = labelRepo;
        this.assignmentRepo = assignmentRepo;
        this.accountRepo = accountRepo;
        this.costService = costService;
        this.idResolver = idResolver;
        this.serviceAssignmentRepo = serviceAssignmentRepo;
        this.audit = audit;
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

    /** @param source which screen made the change, for the audit trail; may be null */
    @Transactional
    public ResourceLabelDto createLabel(Long clientId, String name, String color, String description,
                                        String createdBy, String source) {
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
        ResourceLabel saved = labelRepo.save(label);
        audit.record(clientId, createdBy, source, Action.LABEL_CREATED, null, null, saved,
                List.of(new Change(null, LabelAuditService.LABEL, String.valueOf(saved.getId()), saved.getName(),
                        null, LabelSnapshot.of(saved))));
        return ResourceLabelDto.from(saved);
    }

    @Transactional
    public ResourceLabelDto updateLabel(Long clientId, Long labelId, String name, String color, String description,
                                        String actor, String source) {
        ResourceLabel label = labelRepo.findByIdAndClientId(labelId, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + labelId));
        LabelSnapshot before = LabelSnapshot.of(label);

        if (name != null && !name.equals(label.getName())) {
            if (labelRepo.existsByClientIdAndName(clientId, name)) {
                throw new IllegalArgumentException("A label named '" + name + "' already exists.");
            }
            label.setName(name.trim());
        }
        if (color != null) label.setColor(color);
        if (description != null) label.setDescription(description);

        ResourceLabel saved = labelRepo.save(label);
        LabelSnapshot after = LabelSnapshot.of(saved);
        if (!before.equals(after)) {
            audit.record(clientId, actor, source, Action.LABEL_UPDATED, null, null, saved,
                    List.of(new Change(null, LabelAuditService.LABEL, String.valueOf(labelId), saved.getName(),
                            before, after)));
        }
        return ResourceLabelDto.from(saved);
    }

    /**
     * Deletes a label and every assignment of it.
     *
     * <p>What the label was on is read first and written to the audit trail — one change per
     * resource and service, with its full set of labels either side — because once the rows are
     * gone that cannot be reconstructed.
     */
    @Transactional
    public void deleteLabel(Long clientId, Long labelId, String actor, String source) {
        ResourceLabel label = labelRepo.findByIdAndClientId(labelId, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + labelId));

        List<Change> changed = new ArrayList<>();
        changed.add(new Change(null, LabelAuditService.LABEL, String.valueOf(labelId), label.getName(),
                LabelSnapshot.of(label), null));

        Map<Long, Map<String, TargetRef>> resourcesByAccount = new LinkedHashMap<>();
        for (ResourceLabelAssignment a : assignmentRepo.findAllAssignmentsByLabelId(labelId)) {
            String id = a.getResourceId();
            resourcesByAccount.computeIfAbsent(a.getCloudAccount().getId(), k -> new LinkedHashMap<>())
                    .putIfAbsent(id, new TargetRef(id, new LinkedHashSet<>(List.of(id)), a.getResourceName()));
        }
        resourcesByAccount.forEach((accountId, targets) -> {
            Map<String, List<ShareSnapshot>> sets = labelSetsOf(accountId, clientId, targets.values());
            for (TargetRef t : targets.values()) {
                List<ShareSnapshot> was = sets.getOrDefault(t.storeId(), List.of());
                changed.add(new Change(accountId, LabelAuditService.RESOURCE, t.storeId(), t.name(), was,
                        without(was, labelId)));
            }
        });

        Map<Long, Set<String>> servicesByAccount = new LinkedHashMap<>();
        for (ServiceLabelAssignment a : serviceAssignmentRepo.findAllForLabel(labelId)) {
            servicesByAccount.computeIfAbsent(a.getCloudAccount().getId(), k -> new LinkedHashSet<>())
                    .add(a.getServiceName());
        }
        servicesByAccount.forEach((accountId, services) -> {
            Map<String, List<ShareSnapshot>> sets = serviceLabelSetsOf(accountId, clientId);
            for (String s : services) {
                List<ShareSnapshot> was = sets.getOrDefault(s, List.of());
                changed.add(new Change(accountId, LabelAuditService.SERVICE, s, s, was, without(was, labelId)));
            }
        });

        audit.record(clientId, actor, source, Action.LABEL_DELETED, null, null, label, changed);

        // Service labels are removed explicitly: where Hibernate created that table, its foreign
        // key has no ON DELETE CASCADE and the delete below would be refused.
        serviceAssignmentRepo.deleteByLabelId(labelId);
        // Cascade deletes all resource assignments via DB constraint
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
        // What each resource carried before, for the audit entry.
        Map<String, List<ShareSnapshot>> before =
                labelSetsOf(account.getId(), clientId, targetsOf(req.getResources(), canonical).values());
        List<Change> changed = new ArrayList<>();

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

                List<ShareSnapshot> was = before.getOrDefault(resourceId, List.of());
                // A label the reader already sees, through a row under an older id form, is not
                // a change worth an entry.
                if (!holds(was, label.getId())) {
                    changed.add(new Change(account.getId(), LabelAuditService.RESOURCE, resourceId,
                            a.getResourceName(), was, with(was, ShareSnapshot.of(label, null))));
                }
            }
        }
        audit.record(clientId, assignedBy, req.getSource(), Action.LABEL_ASSIGNED, account, req.getAccountId(),
                label, changed);
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
    public void removeLabel(Long clientId, LabelAssignRequest req, String actor) {
        ResourceLabel label = labelRepo.findByIdAndClientId(req.getLabelId(), clientId)
                .orElseThrow(() -> new IllegalArgumentException("Label not found: " + req.getLabelId()));

        CloudAccount account = resolveAccount(req.getAccountId());
        Map<String, ResourceIdResolver.CanonicalResource> canonical = canonicalise(account, req);
        // What each resource carried before, for the audit entry. Read with the same two id forms
        // the deletes below use, so the entry describes exactly the rows that go.
        Map<String, TargetRef> targets = targetsOf(req.getResources(), canonical);
        Map<String, List<ShareSnapshot>> before = labelSetsOf(account.getId(), clientId, targets.values());

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

        List<Change> changed = new ArrayList<>();
        for (TargetRef t : targets.values()) {
            List<ShareSnapshot> was = before.getOrDefault(t.storeId(), List.of());
            if (holds(was, label.getId())) {
                changed.add(new Change(account.getId(), LabelAuditService.RESOURCE, t.storeId(), t.name(), was,
                        without(was, label.getId())));
            }
        }
        audit.record(clientId, actor, req.getSource(), Action.LABEL_REMOVED, account, req.getAccountId(), label,
                changed);
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

    // -----------------------------------------------------------------------
    // Label shares — the complete set of labels on one resource or service
    // -----------------------------------------------------------------------

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MAX_LABELS_PER_TARGET = 20;

    /**
     * Replaces the labels on one resource with exactly these, each with its share.
     *
     * <p>Labels not in the request are taken off. Existing rows are found under both the id as
     * given and its canonical form, the same way {@link #removeLabel} does, so an older row keyed
     * by an ARN is updated or removed rather than left behind beside a new one.
     *
     * @return the resource's labels as stored, in request order, each carrying its percent
     */
    @Transactional
    public List<ResourceLabelDto> setResourceLabels(Long clientId, LabelSplitRequest req, String actor) {
        LabelAssignRequest.ResourceRef ref = req.getResource();
        if (ref == null || ref.getResourceId() == null || ref.getResourceId().isBlank()) {
            throw new IllegalArgumentException("A resource id is required.");
        }
        Map<Long, BigDecimal> shares = validateShares(req.getLabels());
        Map<Long, ResourceLabel> labels = loadLabels(clientId, shares.keySet());

        CloudAccount account = resolveAccount(req.getAccountId());
        String raw = ref.getResourceId();
        ResourceIdResolver.CanonicalResource known =
                idResolver.resolve(account.getId(), List.of(raw)).get(raw);
        String storeId = known != null ? known.resourceId() : raw;

        LinkedHashSet<String> keys = new LinkedHashSet<>(List.of(raw, storeId));
        List<ResourceLabelAssignment> existing = assignmentRepo
                .findByCloudAccountIdAndResourceIdInForClient(account.getId(), List.copyOf(keys), clientId);

        // Before, as the resource map shows it: rows under the stored id first, then older forms.
        Map<Long, ShareSnapshot> was = new LinkedHashMap<>();
        existing.stream()
                .sorted(Comparator.comparing((ResourceLabelAssignment a) -> !storeId.equals(a.getResourceId())))
                .forEach(a -> was.putIfAbsent(a.getLabel().getId(), ShareSnapshot.of(a.getLabel(), a.getSplitPercent())));
        List<ShareSnapshot> before = List.copyOf(was.values());

        Set<Long> kept = new HashSet<>();
        for (ResourceLabelAssignment a : existing) {
            Long labelId = a.getLabel().getId();
            // A label stored under both id forms keeps one row; the duplicate goes.
            if (!shares.containsKey(labelId) || !kept.add(labelId)) {
                assignmentRepo.delete(a);
                continue;
            }
            a.setSplitPercent(shares.get(labelId));
            assignmentRepo.save(a);
        }

        String resourceType = known != null && known.resourceType() != null
                ? known.resourceType() : ref.getResourceType();
        if (resourceType == null || resourceType.isBlank()) resourceType = "Unknown";
        for (Map.Entry<Long, BigDecimal> e : shares.entrySet()) {
            if (kept.contains(e.getKey())) continue;
            ResourceLabelAssignment a = new ResourceLabelAssignment();
            a.setCloudAccount(account);
            a.setResourceId(storeId);
            a.setResourceType(resourceType);
            a.setResourceName(known != null && known.resourceName() != null
                    ? known.resourceName() : ref.getResourceName());
            a.setRegion(known != null && known.region() != null ? known.region() : ref.getRegion());
            a.setLabel(labels.get(e.getKey()));
            a.setSplitPercent(e.getValue());
            a.setTaggedBy(actor);
            assignmentRepo.save(a);
        }

        List<ShareSnapshot> after = snapshots(shares, labels);
        if (!sameShares(before, after)) {
            String name = known != null && known.resourceName() != null ? known.resourceName() : ref.getResourceName();
            audit.record(clientId, actor, req.getSource(), Action.LABELS_SET, account, req.getAccountId(), null,
                    List.of(new Change(account.getId(), LabelAuditService.RESOURCE, storeId, name, before, after)));
        }

        logger.info("Set {} label share(s) on resource {} (account={})", shares.size(), storeId, account.getId());
        return asDtos(shares, labels);
    }

    /** Every service label the client has put on one account: service name → labels with shares. */
    public Map<String, List<ResourceLabelDto>> getServiceLabels(Long clientId, String accountId) {
        CloudAccount account = resolveAccount(accountId);
        Map<String, List<ResourceLabelDto>> out = new LinkedHashMap<>();
        for (ServiceLabelAssignment a : serviceAssignmentRepo.findByCloudAccountIdForClient(account.getId(), clientId)) {
            out.computeIfAbsent(a.getServiceName(), k -> new ArrayList<>())
                    .add(ResourceLabelDto.from(a.getLabel(), a.getSplitPercent()));
        }
        return out;
    }

    /**
     * Replaces the labels on one billing service with exactly these, each with its share.
     *
     * @return the service's labels as stored, in request order, each carrying its percent
     */
    @Transactional
    public List<ResourceLabelDto> setServiceLabels(Long clientId, LabelSplitRequest req, String actor) {
        String service = req.getService() == null ? null : req.getService().trim();
        if (service == null || service.isEmpty()) {
            throw new IllegalArgumentException("A service name is required.");
        }
        if (service.length() > 255) {
            throw new IllegalArgumentException("A service name is at most 255 characters.");
        }
        Map<Long, BigDecimal> shares = validateShares(req.getLabels());
        Map<Long, ResourceLabel> labels = loadLabels(clientId, shares.keySet());
        CloudAccount account = resolveAccount(req.getAccountId());

        List<ServiceLabelAssignment> existing = serviceAssignmentRepo.findForService(account.getId(), service, clientId);
        Map<Long, ShareSnapshot> was = new LinkedHashMap<>();
        existing.forEach(a -> was.putIfAbsent(a.getLabel().getId(), ShareSnapshot.of(a.getLabel(), a.getSplitPercent())));
        List<ShareSnapshot> before = List.copyOf(was.values());

        Set<Long> kept = new HashSet<>();
        for (ServiceLabelAssignment a : existing) {
            Long labelId = a.getLabel().getId();
            if (!shares.containsKey(labelId) || !kept.add(labelId)) {
                serviceAssignmentRepo.delete(a);
                continue;
            }
            a.setSplitPercent(shares.get(labelId));
            serviceAssignmentRepo.save(a);
        }
        for (Map.Entry<Long, BigDecimal> e : shares.entrySet()) {
            if (kept.contains(e.getKey())) continue;
            ServiceLabelAssignment a = new ServiceLabelAssignment();
            a.setCloudAccount(account);
            a.setServiceName(service);
            a.setLabel(labels.get(e.getKey()));
            a.setSplitPercent(e.getValue());
            a.setTaggedBy(actor);
            serviceAssignmentRepo.save(a);
        }

        List<ShareSnapshot> after = snapshots(shares, labels);
        if (!sameShares(before, after)) {
            audit.record(clientId, actor, req.getSource(), Action.LABELS_SET, account, req.getAccountId(), null,
                    List.of(new Change(account.getId(), LabelAuditService.SERVICE, service, service, before, after)));
        }

        logger.info("Set {} label share(s) on service '{}' (account={})", shares.size(), service, account.getId());
        return asDtos(shares, labels);
    }

    /**
     * Checks a set of label shares and returns label id → percent, in request order.
     *
     * <p>Each share is above 0 and at most 100, with at most two decimals, or null for the whole
     * cost. Together they may not exceed 100 — a null counting as 100 — because one cost cannot be
     * given out more than once. Under 100 is allowed: the remainder is unallocated.
     */
    static Map<Long, BigDecimal> validateShares(List<LabelSplitRequest.Entry> entries) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        if (entries == null) return out;
        if (entries.size() > MAX_LABELS_PER_TARGET) {
            throw new IllegalArgumentException("At most " + MAX_LABELS_PER_TARGET + " labels on one item.");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (LabelSplitRequest.Entry e : entries) {
            if (e == null || e.getLabelId() == null) {
                throw new IllegalArgumentException("Every share needs a label.");
            }
            if (out.containsKey(e.getLabelId())) {
                throw new IllegalArgumentException("A label is listed twice.");
            }
            BigDecimal p = e.getPercent();
            if (p != null) {
                if (p.signum() <= 0 || p.compareTo(HUNDRED) > 0) {
                    throw new IllegalArgumentException("Each share must be above 0% and at most 100%.");
                }
                if (p.stripTrailingZeros().scale() > 2) {
                    throw new IllegalArgumentException("Shares take at most two decimal places.");
                }
                p = p.setScale(2);
            }
            sum = sum.add(p == null ? HUNDRED : p);
            out.put(e.getLabelId(), p);
        }
        if (sum.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("The shares add up to " + sum.stripTrailingZeros().toPlainString()
                    + "%. Together they can be at most 100%.");
        }
        return out;
    }

    /** The client's labels with these ids, refusing any that belong to someone else or do not exist. */
    private Map<Long, ResourceLabel> loadLabels(Long clientId, Set<Long> ids) {
        Map<Long, ResourceLabel> out = new HashMap<>();
        for (Long id : ids) {
            out.put(id, labelRepo.findByIdAndClientId(id, clientId)
                    .orElseThrow(() -> new IllegalArgumentException("Label not found: " + id)));
        }
        return out;
    }

    private static List<ResourceLabelDto> asDtos(Map<Long, BigDecimal> shares, Map<Long, ResourceLabel> labels) {
        List<ResourceLabelDto> out = new ArrayList<>(shares.size());
        shares.forEach((id, percent) -> out.add(ResourceLabelDto.from(labels.get(id), percent)));
        return out;
    }

    // -----------------------------------------------------------------------
    // Audit snapshots — the labels on a target, read before a write changes them
    // -----------------------------------------------------------------------

    /** Ids per lookup query, so a label on thousands of resources does not build one huge IN list. */
    private static final int LOOKUP_CHUNK = 500;

    /** A resource as an audit target: the id it is stored under, every form to read it by, its name. */
    private record TargetRef(String storeId, Set<String> forms, String name) {
    }

    /** The resources in a request, keyed by the id each is stored under. Blank ids are not resources. */
    private static Map<String, TargetRef> targetsOf(List<LabelAssignRequest.ResourceRef> refs,
                                                    Map<String, ResourceIdResolver.CanonicalResource> canonical) {
        Map<String, TargetRef> out = new LinkedHashMap<>();
        if (refs == null) return out;
        for (LabelAssignRequest.ResourceRef ref : refs) {
            String raw = ref.getResourceId();
            if (raw == null || raw.isBlank()) continue;
            ResourceIdResolver.CanonicalResource known = canonical.get(raw);
            String store = known != null ? known.resourceId() : raw;
            String name = known != null && known.resourceName() != null ? known.resourceName() : ref.getResourceName();
            out.computeIfAbsent(store, k -> new TargetRef(store, new LinkedHashSet<>(List.of(store)), name))
                    .forms().add(raw);
        }
        return out;
    }

    /**
     * Each target's labels as a reader sees them — merged across every id form it is stored under,
     * the way the resource map reads them — keyed by stored id.
     */
    private Map<String, List<ShareSnapshot>> labelSetsOf(Long accountId, Long clientId, Collection<TargetRef> targets) {
        if (targets.isEmpty()) return Map.of();
        List<String> forms = targets.stream().flatMap(t -> t.forms().stream()).distinct().toList();
        Map<String, List<ResourceLabelAssignment>> byStored = new HashMap<>();
        for (int i = 0; i < forms.size(); i += LOOKUP_CHUNK) {
            List<String> chunk = forms.subList(i, Math.min(forms.size(), i + LOOKUP_CHUNK));
            for (ResourceLabelAssignment a : assignmentRepo.findByCloudAccountIdAndResourceIdInForClient(
                    accountId, chunk, clientId)) {
                byStored.computeIfAbsent(a.getResourceId(), k -> new ArrayList<>()).add(a);
            }
        }
        Map<String, List<ShareSnapshot>> out = new HashMap<>();
        for (TargetRef t : targets) {
            Map<Long, ShareSnapshot> merged = new LinkedHashMap<>();
            for (String form : t.forms()) {
                for (ResourceLabelAssignment a : byStored.getOrDefault(form, List.of())) {
                    merged.putIfAbsent(a.getLabel().getId(), ShareSnapshot.of(a.getLabel(), a.getSplitPercent()));
                }
            }
            out.put(t.storeId(), List.copyOf(merged.values()));
        }
        return out;
    }

    /** Every service's labels in one account, keyed by service name. */
    private Map<String, List<ShareSnapshot>> serviceLabelSetsOf(Long accountId, Long clientId) {
        Map<String, Map<Long, ShareSnapshot>> byService = new HashMap<>();
        for (ServiceLabelAssignment a : serviceAssignmentRepo.findByCloudAccountIdForClient(accountId, clientId)) {
            byService.computeIfAbsent(a.getServiceName(), k -> new LinkedHashMap<>())
                    .putIfAbsent(a.getLabel().getId(), ShareSnapshot.of(a.getLabel(), a.getSplitPercent()));
        }
        Map<String, List<ShareSnapshot>> out = new HashMap<>();
        byService.forEach((service, shares) -> out.put(service, List.copyOf(shares.values())));
        return out;
    }

    private static List<ShareSnapshot> snapshots(Map<Long, BigDecimal> shares, Map<Long, ResourceLabel> labels) {
        List<ShareSnapshot> out = new ArrayList<>(shares.size());
        shares.forEach((id, percent) -> out.add(ShareSnapshot.of(labels.get(id), percent)));
        return out;
    }

    private static boolean holds(List<ShareSnapshot> set, Long labelId) {
        return set.stream().anyMatch(s -> s.labelId().equals(labelId));
    }

    private static List<ShareSnapshot> with(List<ShareSnapshot> set, ShareSnapshot added) {
        List<ShareSnapshot> out = new ArrayList<>(set);
        out.add(added);
        return out;
    }

    private static List<ShareSnapshot> without(List<ShareSnapshot> set, Long labelId) {
        return set.stream().filter(s -> !s.labelId().equals(labelId)).toList();
    }

    /** The same labels with the same shares, in any order. A null share is the whole cost. */
    private static boolean sameShares(List<ShareSnapshot> a, List<ShareSnapshot> b) {
        if (a.size() != b.size()) return false;
        Map<Long, BigDecimal> byLabel = new HashMap<>();
        for (ShareSnapshot s : a) byLabel.put(s.labelId(), s.percent());
        for (ShareSnapshot s : b) {
            if (!byLabel.containsKey(s.labelId())) return false;
            BigDecimal x = byLabel.get(s.labelId());
            BigDecimal y = s.percent();
            if (x == null ? y != null : y == null || x.compareTo(y) != 0) return false;
        }
        return true;
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
                  .add(ResourceLabelDto.from(a.getLabel(), a.getSplitPercent()));
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
