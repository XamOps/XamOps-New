package com.xammer.cloud.service.inventory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AwsCloudResource;
import com.xammer.cloud.domain.AwsInventorySyncState;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import com.xammer.cloud.repository.AwsInventorySyncStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Serves the inventory plane to existing page consumers in the shapes they already
 * expect.
 *
 * <p>
 * The deliberate constraint here is that nothing downstream changes. {@link ResourceDto}
 * and {@link DashboardData.ServiceGroupDto} are produced exactly as
 * {@code CloudListService} produces them today, so the frontend, {@code CloudMapService},
 * {@code XamOpsRightsizingService} and the rest keep working while the source underneath
 * them swaps from a 1000-call scan to a table read. A migration that also changed the
 * contract would be impossible to roll back safely.
 *
 * <p>
 * {@link #isFresh} is what makes the swap safe: callers ask whether the plane has usable
 * data for an account before trusting it, and fall back to the old path when it does not.
 *
 * <p>
 * Every method here takes a {@code cloudAccountId} — the {@code cloud_account} primary
 * key, meaning one tenant's connection to one AWS account — and never a 12-digit AWS
 * account id. They are not interchangeable: an AWS account can be connected more than
 * once, so an AWS account id can select several rows. Keying on it made this service
 * throw {@code NonUniqueResultException} the first time a customer reconnected, and
 * because callers treat any exception as "plane unavailable", the whole feature turned
 * itself off while reporting a healthy sync.
 */
@Service
public class InventoryProjectionService {

    private static final Logger log = LoggerFactory.getLogger(InventoryProjectionService.class);

    private final AwsCloudResourceRepository resourceRepository;
    private final AwsInventorySyncStateRepository syncStateRepository;
    private final ObjectMapper objectMapper;

    /**
     * How old a full sync may be before the plane declares itself unusable and callers
     * revert to describes. Generous relative to the sync interval: a few missed cycles
     * is a stale page, whereas flipping to a 1000-call scan under load is an outage.
     */
    @Value("${xamops.inventory.staleness-threshold-minutes:180}")
    private long stalenessThresholdMinutes;

    @Value("${xamops.inventory.enabled:false}")
    private boolean inventoryEnabled;

    public InventoryProjectionService(AwsCloudResourceRepository resourceRepository,
            AwsInventorySyncStateRepository syncStateRepository,
            ObjectMapper objectMapper) {
        this.resourceRepository = resourceRepository;
        this.syncStateRepository = syncStateRepository;
        this.objectMapper = objectMapper;
    }

    // ── Readiness ───────────────────────────────────────────────────────────

    /**
     * Whether the plane can be trusted to answer for this account.
     *
     * <p>
     * Requires the feature flag on, a successful Resource Explorer sync, a recent one,
     * and at least one row. The row check is not redundant: a sync that legitimately
     * observed zero resources would otherwise let the plane answer "this account is
     * empty", which is indistinguishable from a broken integration to a user.
     */
    public boolean isFresh(Long cloudAccountId) {
        if (!inventoryEnabled || cloudAccountId == null) {
            return false;
        }
        // findById, not a lookup by AWS account id: cloudAccountId is this row's primary
        // key, so it cannot match twice. See AwsInventorySyncStateRepository for the
        // failure that motivated removing the by-AWS-account lookup entirely.
        Optional<AwsInventorySyncState> stateOpt = syncStateRepository.findById(cloudAccountId);
        if (stateOpt.isEmpty()) {
            return false;
        }
        AwsInventorySyncState state = stateOpt.get();

        if (!AwsInventorySyncState.MODE_RESOURCE_EXPLORER.equals(state.getMode())) {
            return false;
        }

        // Incomplete region coverage means whole regions are missing from the plane —
        // typically AWS opt-in regions, which Resource Explorer does not index
        // automatically. Serving that would silently omit every resource in those regions:
        // measured on a real account, 7 running EC2 instances vanished from a list of 22.
        // A slow-but-complete describe scan is strictly better than a fast wrong answer,
        // so the whole account falls back until the gap is closed by creating an index in
        // the missing region.
        // Only an aggregator index sees the whole account in one query, so only an
        // aggregator index may be served.
        //
        // AGGREGATOR_INCOMPLETE was already excluded here for exactly this reason, but LOCAL
        // and PARTIAL fell through and were served as though complete. They are not:
        // AwsInventoryReconciler calls fetchAll() with a single region, so a LOCAL-only
        // account is enumerated from one arbitrarily-chosen region's index and every other
        // region is absent. Measured on a real account with LOCAL indexes in 18 regions: the
        // plane held EKS, EC2 and EventBridge resources from ap-northeast-1 and not one of
        // the S3 buckets that exist in ap-south-1, while the cloud list reported it as the
        // complete estate.
        //
        // The same principle the AGGREGATOR_INCOMPLETE branch was written on: a slow but
        // complete describe scan beats a fast wrong answer. Promoting one LOCAL index to
        // AGGREGATOR restores the fast path for these accounts.
        if (!AwsInventorySyncState.INDEX_AGGREGATOR.equals(state.getIndexType())) {
            log.warn("[Inventory] Account {} has index type {} (uncovered: [{}]) — falling back "
                    + "to describes because only an AGGREGATOR index covers every region in one "
                    + "query. Promote a Resource Explorer aggregator index to use the fast path.",
                    state.getAwsAccountId(), state.getIndexType(), state.getUncoveredRegions());
            return false;
        }
        if (state.getLastFullSyncAt() == null) {
            return false;
        }
        long ageMinutes = ChronoUnit.MINUTES.between(state.getLastFullSyncAt(), Instant.now());
        if (ageMinutes > stalenessThresholdMinutes) {
            log.info("[Inventory] Plane stale for account {} ({} min old, threshold {}) — "
                    + "caller should fall back", state.getAwsAccountId(), ageMinutes,
                    stalenessThresholdMinutes);
            return false;
        }
        return resourceRepository.countByCloudAccountIdAndStatus(
                cloudAccountId, AwsCloudResource.STATUS_ACTIVE) > 0;
    }

    /** Whether the plane holds only partial results, for honest UI labelling. */
    public boolean isPartial(Long cloudAccountId) {
        if (cloudAccountId == null) {
            return false;
        }
        return syncStateRepository.findById(cloudAccountId)
                .map(s -> AwsInventorySyncState.INDEX_PARTIAL.equals(s.getIndexType())
                        || AwsInventorySyncState.INDEX_LOCAL.equals(s.getIndexType())
                        || AwsInventorySyncState.INDEX_AGGREGATOR_INCOMPLETE.equals(s.getIndexType()))
                .orElse(false);
    }

    public Optional<AwsInventorySyncState> syncState(Long cloudAccountId) {
        return cloudAccountId == null ? Optional.empty()
                : syncStateRepository.findById(cloudAccountId);
    }

    // ── Projections ─────────────────────────────────────────────────────────

    /** Flat list, replacing {@code CloudListService.getAllResources}. */
    @Transactional(readOnly = true)
    public List<ResourceDto> flatList(Long cloudAccountId) {
        return resourceRepository.findActiveByCloudAccountId(cloudAccountId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Grouped list, replacing {@code CloudListService.getAllResourcesGrouped}.
     * Sorted by service type to match the existing ordering the frontend paginates over.
     */
    @Transactional(readOnly = true)
    public List<DashboardData.ServiceGroupDto> groupedList(Long cloudAccountId) {
        return resourceRepository.findActiveByCloudAccountId(cloudAccountId).stream()
                .map(this::toDto)
                .filter(r -> r.getType() != null)
                .collect(Collectors.groupingBy(ResourceDto::getType))
                .entrySet().stream()
                .map(e -> new DashboardData.ServiceGroupDto(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(DashboardData.ServiceGroupDto::getServiceType))
                .collect(Collectors.toList());
    }

    /**
     * Paged flat list. Unlike the current implementation this pages in SQL, so page 50
     * costs the same as page 1 instead of deserialising the whole account each time.
     */
    @Transactional(readOnly = true)
    public Page<ResourceDto> pagedList(Long cloudAccountId, int page, int size) {
        Page<AwsCloudResource> rows = resourceRepository.findActiveByCloudAccountId(
                cloudAccountId, PageRequest.of(page, size));
        List<ResourceDto> content = rows.getContent().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return new PageImpl<>(content, PageRequest.of(page, size), rows.getTotalElements());
    }

    /** Single type slice, for consumers that want only EC2 instances or only VPCs. */
    @Transactional(readOnly = true)
    public List<ResourceDto> byType(Long cloudAccountId, String displayType) {
        return resourceRepository
                .findActiveByCloudAccountIdAndDisplayType(cloudAccountId, displayType).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * The dashboard's Resource Inventory widget, straight from SQL.
     *
     * <p>
     * One indexed {@code GROUP BY} rather than materialising the account's resources and
     * counting them in the JVM, which is what made this widget expensive enough to cache —
     * and being cached is what made it show stale numbers.
     */
    @Transactional(readOnly = true)
    public DashboardData.ResourceInventory resourceInventory(Long cloudAccountId) {
        return ResourceInventoryMapper.fromLongCounts(countsByType(cloudAccountId));
    }

    /** Per-type counts without materialising the resources. */
    @Transactional(readOnly = true)
    public Map<String, Long> countsByType(Long cloudAccountId) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : resourceRepository.countActiveByDisplayType(cloudAccountId)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * Regions that actually contain resources.
     *
     * <p>
     * This is the replacement for {@code getRegionStatusForAccount}'s discovery pass,
     * which calls {@code describeRegions} then probes five services in every region —
     * and, because of a {@code hasX() && !x().isEmpty()} pattern, issues three of those
     * probes twice. Here it is one indexed {@code DISTINCT}.
     */
    @Transactional(readOnly = true)
    public List<String> activeRegions(Long cloudAccountId) {
        return resourceRepository.findActiveRegions(cloudAccountId).stream()
                .filter(r -> r != null && !r.isBlank())
                .sorted()
                .collect(Collectors.toList());
    }

    /** Resources first observed within the window — backs the "new" badge. */
    @Transactional(readOnly = true)
    public List<ResourceDto> recentlyAdded(Long cloudAccountId, int withinMinutes) {
        Instant since = Instant.now().minus(withinMinutes, ChronoUnit.MINUTES);
        return resourceRepository.findRecentlyAdded(cloudAccountId, since).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    // ── Mapping ─────────────────────────────────────────────────────────────

    /**
     * Row to {@link ResourceDto}.
     *
     * <p>
     * Tags are merged into {@code details} because that is where the existing cloud list
     * UI reads them from, and the ARN is added so the detail drill-down can resolve a
     * resource without re-deriving it from id plus type.
     */
    private ResourceDto toDto(AwsCloudResource row) {
        Map<String, String> details = readMap(row.getDetails());
        Map<String, String> tags = readMap(row.getTags());
        if (!tags.isEmpty()) {
            tags.forEach((k, v) -> details.putIfAbsent(k, v));
        }
        details.putIfAbsent("ARN", row.getArn());

        return new ResourceDto(
                row.getResourceId(),
                row.getName(),
                row.getDisplayType(),
                row.getRegion(),
                row.getState(),
                row.getLaunchTime(),
                details);
    }

    private Map<String, String> readMap(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            Map<String, String> out = new HashMap<>();
            raw.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
            return out;
        } catch (Exception e) {
            log.debug("[Inventory] Could not parse JSON column: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Region list in the shape the dashboard map and every region-scoped page expects.
     *
     * <p>
     * Coordinates are intentionally left to the caller: {@code CloudListService} already
     * owns the region-to-latlong table, and duplicating it here would let the two drift.
     */
    public List<String> activeRegionIds(Long cloudAccountId) {
        return new ArrayList<>(activeRegions(cloudAccountId));
    }
}
