package com.xammer.cloud.service.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AwsCloudResource;
import com.xammer.cloud.domain.AwsInventorySyncState;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.AwsCloudResourceRepository;
import com.xammer.cloud.repository.AwsInventorySyncStateRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.resourceexplorer2.ResourceExplorer2Client;
import software.amazon.awssdk.services.resourceexplorer2.model.ConflictException;
import software.amazon.awssdk.services.resourceexplorer2.model.CreateIndexRequest;
import software.amazon.awssdk.services.resourceexplorer2.model.Index;
import software.amazon.awssdk.services.resourceexplorer2.model.ListIndexesRequest;
import software.amazon.awssdk.services.resourceexplorer2.model.ListResourcesRequest;
import software.amazon.awssdk.services.resourceexplorer2.model.ListResourcesResponse;
import software.amazon.awssdk.services.resourceexplorer2.model.Resource;
import software.amazon.awssdk.services.resourceexplorer2.model.ResourceProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Rebuilds an account's inventory from AWS Resource Explorer.
 *
 * <p>
 * This is the baseline half of the inventory plane. It replaces the ~1000 per-service,
 * per-region describe calls in {@code CloudListService.getAllResources} with a handful
 * of paginated {@code Search} calls against the account's aggregator index — which
 * costs the customer nothing, and since October 2025 needs no setup on their side.
 *
 * <p>
 * Correctness comes from mark-and-sweep rather than delete-and-reinsert. Each run takes
 * the next {@code sync_generation}, stamps every resource it observes, then tombstones
 * whatever is left behind at an older generation. That ordering matters: a truncate-first
 * approach would leave the cloud list empty for the duration of the sync, and any
 * failure halfway through would leave it empty permanently. Here a failed run simply
 * doesn't advance the generation and the previous inventory stays readable.
 *
 * <p>
 * It is also the reconciler for the event path. Events can be dropped, duplicated or
 * delivered out of order; this sweep is what makes those failure modes self-healing
 * instead of permanent drift, which is why it keeps running even once events are live.
 */
@Service
public class AwsInventoryReconciler {

    private static final Logger log = LoggerFactory.getLogger(AwsInventoryReconciler.class);

    /**
     * Page size for ListResources.
     *
     * <p>
     * Deliberately below the 1000 maximum: AWS documents that ListResources omits
     * NextToken when MaxResults is set to exactly 1000, which would silently cap us at
     * one page. 500 keeps pagination working.
     */
    private static final int PAGE_SIZE = 500;

    /**
     * Hard ceiling on pages per account, so a pathological account cannot pin a worker
     * indefinitely. At 1000 per page this is a million resources — far above any real
     * account, but a bounded loop beats an unbounded one.
     */
    private static final int MAX_PAGES = 1000;

    /**
     * Types worth one live describe per region to learn their state.
     *
     * <p>
     * Kept deliberately short. Every entry costs an API call per region containing it, so
     * the bar is "a user makes decisions from this value": running vs stopped instances,
     * unattached volumes, stopped databases. Everything else uses a static default from
     * {@link AwsResourceTypeMapper#staticStateFor}. Adding types here is the one way this
     * class can quietly drift back toward the call volume it was built to remove.
     */
    private static final Set<String> STATEFUL_TYPES = Set.of(
            // Running vs stopped, in-use vs available, available vs stopped. The reasons
            // people open the cloud list in the first place.
            "ec2:instance", "ec2:volume", "rds:db",
            // Unassociated addresses and available ENIs are billable waste, so the state is
            // the finding rather than decoration.
            "ec2:elastic-ip", "ec2:network-interface",
            // An expired certificate, an alarm in ALARM, an agent that stopped reporting,
            // and a stack sitting in ROLLBACK_COMPLETE are all actionable.
            "acm:certificate", "cloudwatch:alarm", "ssm:managed-instance",
            "cloudformation:stack",
            // Cheap, one call per region, and pending images look identical to ready ones
            // without it.
            "ec2:image", "ec2:vpc-endpoint");

    /**
     * Minimum resources a fetch must return before we trust it enough to sweep.
     *
     * <p>
     * Guards against a partially-successful fetch tombstoning a good inventory. If a page
     * fails midway we may hold only a fraction of the account; sweeping on that would mark
     * the rest deleted. Requiring the fetch to account for most of what we already have
     * makes a truncated read a no-op instead of a data-loss event.
     */
    private static final double MIN_SWEEP_COVERAGE = 0.5;

    private final AwsClientProvider awsClientProvider;
    private final AwsCloudResourceRepository resourceRepository;
    private final AwsInventorySyncStateRepository syncStateRepository;
    private final ObjectMapper objectMapper;

    /**
     * Programmatic transactions instead of {@code @Transactional}.
     *
     * <p>
     * {@code persist} is called from {@code sync} inside this same class, and Spring's
     * proxy-based transaction advice cannot intercept a self-invocation — the annotation is
     * silently ignored. The symptom was specific and misleading: the sweep is a
     * {@code @Modifying} query, which requires an active transaction, so every run failed
     * with {@code TransactionRequiredException} <em>after</em> logging that it had fetched
     * thousands of resources. Fetches looked healthy, nothing was ever written, and the
     * account quietly stayed on the describe fallback.
     */
    private final TransactionTemplate transactionTemplate;

    @Value("${xamops.inventory.resource-explorer.default-region:us-east-1}")
    private String defaultRegion;

    /**
     * Whether to create Resource Explorer indexes in regions that lack one.
     *
     * <p>
     * On by default: without it, every customer using an AWS opt-in region is permanently
     * stuck on the describe fallback with no automatic remedy. Switchable off for operators
     * who would rather the platform never write to a customer account, at the cost of
     * handling those regions manually.
     */
    @Value("${xamops.inventory.auto-create-indexes:true}")
    private boolean autoCreateIndexes;

    public AwsInventoryReconciler(AwsClientProvider awsClientProvider,
            AwsCloudResourceRepository resourceRepository,
            AwsInventorySyncStateRepository syncStateRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.awsClientProvider = awsClientProvider;
        this.resourceRepository = resourceRepository;
        this.syncStateRepository = syncStateRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Outcome of one sync, so callers can report progress and support can see whether
     * an account is on the fast path without reading logs.
     */
    public static class SyncResult {
        public final boolean success;
        public final String mode;
        public final String indexType;
        public final int observed;
        public final int created;
        public final int swept;
        public final long durationMs;
        public final String error;

        SyncResult(boolean success, String mode, String indexType, int observed,
                int created, int swept, long durationMs, String error) {
            this.success = success;
            this.mode = mode;
            this.indexType = indexType;
            this.observed = observed;
            this.created = created;
            this.swept = swept;
            this.durationMs = durationMs;
            this.error = error;
        }

        /** True when Resource Explorer could not serve this account at all. */
        public boolean needsDescribeFallback() {
            return !success || AwsInventorySyncState.MODE_DESCRIBE_FALLBACK.equals(mode);
        }
    }

    /**
     * Syncs one account.
     *
     * <p>
     * Never throws: a single unreachable account must not abort a sweep across every
     * account, and the caller decides whether to fall back to describes based on
     * {@link SyncResult#needsDescribeFallback()}.
     */
    public SyncResult sync(CloudAccount account) {
        long startMs = System.currentTimeMillis();
        String awsAccountId = account.getAwsAccountId();

        if (awsAccountId == null || awsAccountId.isBlank()) {
            return new SyncResult(false, AwsInventorySyncState.MODE_UNKNOWN, null,
                    0, 0, 0, 0, "Account has no AWS account id");
        }

        try {
            IndexInfo indexInfo = resolveIndex(account);
            if (indexInfo == null) {
                String msg = "Resource Explorer reported no usable index";
                log.warn("[Inventory] {} for account {} — describe fallback required", msg, awsAccountId);
                recordState(account, null, AwsInventorySyncState.MODE_DESCRIBE_FALLBACK,
                        AwsInventorySyncState.INDEX_NONE, null, 0,
                        System.currentTimeMillis() - startMs, msg);
                return new SyncResult(false, AwsInventorySyncState.MODE_DESCRIBE_FALLBACK,
                        AwsInventorySyncState.INDEX_NONE, 0, 0, 0,
                        System.currentTimeMillis() - startMs, msg);
            }

            List<Resource> resources = fetchAll(account, indexInfo.region);

            if (resources.isEmpty()) {
                // An account with genuinely zero resources is possible but rare; far more
                // often this means the index is still building. Either way, refusing to
                // sweep is the safe choice — sweeping on an empty read would tombstone a
                // correct inventory and blank every page.
                String msg = "Resource Explorer returned 0 resources; skipping sweep to avoid "
                        + "tombstoning a good inventory (index may still be building)";
                log.warn("[Inventory] {} for account {}", msg, awsAccountId);
                recordState(account, null, AwsInventorySyncState.MODE_DESCRIBE_FALLBACK,
                        indexInfo.indexType, indexInfo.region, 0,
                        System.currentTimeMillis() - startMs, msg);
                return new SyncResult(false, AwsInventorySyncState.MODE_DESCRIBE_FALLBACK,
                        indexInfo.indexType, 0, 0, 0,
                        System.currentTimeMillis() - startMs, msg);
            }

            SyncResult result = persist(account, resources, indexInfo, startMs);
            log.info("[Inventory] Synced account {} — {} resources ({} new, {} removed) "
                    + "via {} index in {}ms",
                    awsAccountId, result.observed, result.created, result.swept,
                    indexInfo.indexType, result.durationMs);
            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startMs;
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("[Inventory] Sync failed for account {} after {}ms — falling back to describes: {}",
                    awsAccountId, duration, msg);
            try {
                recordState(account, null, AwsInventorySyncState.MODE_DESCRIBE_FALLBACK,
                        null, null, 0, duration, truncate(msg));
            } catch (Exception stateEx) {
                log.warn("[Inventory] Could not record sync failure state for {}: {}",
                        awsAccountId, stateEx.getMessage());
            }
            return new SyncResult(false, AwsInventorySyncState.MODE_DESCRIBE_FALLBACK,
                    null, 0, 0, 0, duration, msg);
        }
    }

    // ── Index resolution ────────────────────────────────────────────────────

    private static class IndexInfo {
        final String region;
        final String indexType;
        /**
         * Enabled regions Resource Explorer cannot see into. Resources here must not be
         * swept, because their absence from the search is a coverage gap rather than a
         * deletion.
         */
        final Set<String> uncoveredRegions;

        IndexInfo(String region, String indexType, Set<String> uncoveredRegions) {
            this.region = region;
            this.indexType = indexType;
            this.uncoveredRegions = uncoveredRegions == null ? Set.of() : uncoveredRegions;
        }
    }

    /**
     * Finds the region to query.
     *
     * <p>
     * Prefers the AGGREGATOR index, because a Search there covers every region in one
     * pass. Falls back to any LOCAL index, which returns only that region's resources —
     * incomplete, but better than nothing and clearly labelled as such in sync state.
     *
     * <p>
     * A {@code ListIndexes} AccessDenied is treated as "no index" rather than an error:
     * plenty of accounts will have Resource Explorer blocked by an SCP, and that is a
     * fallback condition, not a failure worth alerting on.
     */
    private IndexInfo resolveIndex(CloudAccount account) {
        try {
            ResourceExplorer2Client client = awsClientProvider
                    .getResourceExplorerClient(account, defaultRegion);
            List<Index> indexes = client.listIndexes(ListIndexesRequest.builder().build()).indexes();

            if (indexes == null || indexes.isEmpty()) {
                // No user-owned index. Search may still return partial results from
                // Resource Explorer-owned indexes, so it is worth trying the default
                // region before giving up.
                log.info("[Inventory] No user-owned index for account {} — attempting partial search",
                        account.getAwsAccountId());
                return new IndexInfo(defaultRegion, AwsInventorySyncState.INDEX_PARTIAL, null);
            }

            Set<String> indexedRegions = indexes.stream()
                    .map(this::regionOf)
                    .collect(Collectors.toSet());
            // Close the gap rather than only reporting it. Anything still missing after
            // this is either newly created and not yet replicated, or genuinely blocked.
            Set<String> uncovered = createMissingIndexes(
                    account, uncoveredRegions(account, indexedRegions));

            Optional<Index> aggregator = indexes.stream()
                    .filter(i -> "AGGREGATOR".equalsIgnoreCase(i.typeAsString()))
                    .findFirst();
            if (aggregator.isPresent()) {
                String region = regionOf(aggregator.get());
                if (uncovered.isEmpty()) {
                    return new IndexInfo(region, AwsInventorySyncState.INDEX_AGGREGATOR, uncovered);
                }
                // Almost always AWS opt-in regions, which Resource Explorer does not index
                // automatically. Flagged rather than ignored because the failure is silent:
                // the search simply omits those regions and the page looks complete.
                log.warn("[Inventory] Account {} has an aggregator index but {} enabled region(s) "
                        + "are NOT indexed: {}. Resources there are invisible to Resource Explorer "
                        + "(commonly AWS opt-in regions). Their rows will not be swept, and the "
                        + "describe fallback remains responsible for them.",
                        account.getAwsAccountId(), uncovered.size(), uncovered);
                return new IndexInfo(region, AwsInventorySyncState.INDEX_AGGREGATOR_INCOMPLETE, uncovered);
            }

            // Prefer the configured default region when it has an index, rather than
            // whichever index AWS happened to list first. The queried region decides what
            // the plane contains, so an arbitrary choice made that content arbitrary too.
            String region = indexedRegions.contains(defaultRegion)
                    ? defaultRegion
                    : regionOf(indexes.get(0));

            // Every other region is uncovered, because fetchAll() queries exactly one.
            //
            // Without this the sweep tombstones them. uncovered was empty for these accounts
            // — an index exists everywhere, nothing is missing — so persistInTransaction took
            // the unrestricted sweepStaleResources path and marked every resource outside the
            // queried region DELETED, once per sync. Observed on a real account: 9
            // CloudFormation stacks sitting in DELETED that had never been deleted.
            //
            // A LOCAL index genuinely cannot see other regions, so their absence from this
            // fetch carries no information about whether the resources still exist. That is
            // precisely what uncoveredRegions means.
            Set<String> unqueried = new LinkedHashSet<>(indexedRegions);
            unqueried.addAll(uncovered);
            unqueried.remove(region);

            log.warn("[Inventory] Account {} has only LOCAL indexes — enumeration is limited to "
                    + "region {}, and resources in the other {} region(s) are invisible to this "
                    + "sync. They are protected from the sweep, and the account stays on the "
                    + "describe fallback. Promoting an aggregator index would make this one "
                    + "cross-region query and put the account on the fast path.",
                    account.getAwsAccountId(), region, unqueried.size());
            return new IndexInfo(region, AwsInventorySyncState.INDEX_LOCAL, unqueried);

        } catch (Exception e) {
            log.info("[Inventory] ListIndexes unavailable for account {} ({}) — attempting partial search",
                    account.getAwsAccountId(), e.getMessage());
            return new IndexInfo(defaultRegion, AwsInventorySyncState.INDEX_PARTIAL, null);
        }
    }

    /**
     * Creates a Resource Explorer index in each region that lacks one.
     *
     * <p>
     * This is what stops the opt-in-region gap being a permanent, per-customer manual
     * chore. AWS does not auto-index opt-in regions, so without this any customer using
     * Hyderabad, Jakarta, Melbourne, UAE, Zurich, Spain and the rest would sit on the slow
     * describe path forever, with no signal beyond a log line.
     *
     * <p>
     * Creating an index is a write in the customer's account, so it is deliberately
     * conservative:
     * <ul>
     * <li>Only ever creates a LOCAL index. The aggregator already exists and AWS replicates
     * local indexes into it automatically; promoting a second aggregator would fail and,
     * per AWS, requires a 24-hour wait after demotion to undo.</li>
     * <li>Treats an already-exists error as success, so a race between two syncs or a
     * customer creating one by hand is harmless.</li>
     * <li>Never fails the sync. If the permission is absent — which it will be for accounts
     * onboarded before this change — we fall back to reporting the gap exactly as before.</li>
     * </ul>
     *
     * @return regions still uncovered after the attempt
     */
    private Set<String> createMissingIndexes(CloudAccount account, Set<String> missing) {
        if (!autoCreateIndexes || missing.isEmpty()) {
            return missing;
        }

        Set<String> stillMissing = new LinkedHashSet<>();
        for (String region : missing) {
            try {
                ResourceExplorer2Client client =
                        awsClientProvider.getResourceExplorerClient(account, region);
                client.createIndex(CreateIndexRequest.builder()
                        .clientToken(UUID.randomUUID().toString())
                        .build());
                // Indexing is asynchronous: the index exists now but will not contain
                // resources for minutes, and will not appear in the aggregator until it
                // finishes replicating. So this region stays "uncovered" for this run and
                // a later sync picks it up once AWS reports it.
                log.info("[Inventory] Created Resource Explorer index in {} for account {} "
                        + "(AWS does not auto-index opt-in regions). It will populate and "
                        + "replicate into the aggregator within minutes.",
                        region, account.getAwsAccountId());
                stillMissing.add(region);
            } catch (ConflictException e) {
                // Already exists — someone else created it, or two syncs raced.
                log.debug("[Inventory] Index already exists in {} for account {}",
                        region, account.getAwsAccountId());
            } catch (Exception e) {
                // Most often AccessDenied on accounts whose onboarding stack predates the
                // CreateIndex permission. Not fatal: the region simply stays uncovered and
                // the describe fallback keeps serving it.
                log.warn("[Inventory] Could not create index in {} for account {}: {}. "
                        + "That region stays on the describe fallback. If this is AccessDenied, "
                        + "the customer's onboarding stack needs updating to grant "
                        + "resource-explorer-2:CreateIndex.",
                        region, account.getAwsAccountId(), e.getMessage());
                stillMissing.add(region);
            }
        }
        return stillMissing;
    }

    /**
     * Enabled regions with no Resource Explorer index.
     *
     * <p>
     * Uses DescribeRegions rather than assuming a fixed list, because which regions an
     * account has opted into is account-specific. A failure here returns empty, which
     * conservatively treats coverage as complete — the sweep guard on overall counts still
     * applies, so the worst case is the previous behaviour rather than something new.
     */
    private Set<String> uncoveredRegions(CloudAccount account, Set<String> indexedRegions) {
        try {
            List<String> enabled = awsClientProvider.getEc2Client(account, defaultRegion)
                    .describeRegions().regions().stream()
                    .filter(r -> !"not-opted-in".equals(r.optInStatus()))
                    .map(r -> r.regionName())
                    .collect(Collectors.toList());
            return enabled.stream()
                    .filter(r -> !indexedRegions.contains(r))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            log.debug("[Inventory] Could not compare enabled vs indexed regions for {}: {}",
                    account.getAwsAccountId(), e.getMessage());
            return Set.of();
        }
    }

    private String regionOf(Index index) {
        String region = index.region();
        return (region == null || region.isBlank()) ? defaultRegion : region;
    }

    // ── Fetch ───────────────────────────────────────────────────────────────

    /**
     * Fetches the whole account inventory.
     *
     * <p>
     * Uses {@code ListResources}, not {@code Search}. This matters more than it looks:
     * {@code Search} is documented to return "all results up to the limit of 1,000
     * <em>total</em> results" — a cap on the entire result set, not on one page. Measured
     * against a real account, {@code Search} returned exactly 1000 with no continuation
     * token while {@code ListResources} returned 1911. Using {@code Search} would have
     * silently hidden 48% of that account's resources, and — far worse — mark-and-sweep
     * would then have tombstoned every resource beyond the first 1000 on each run.
     * {@code ListResources} exists precisely for full-inventory enumeration and paginates
     * properly.
     */
    private List<Resource> fetchAll(CloudAccount account, String region) {
        ResourceExplorer2Client client = awsClientProvider.getResourceExplorerClient(account, region);
        List<Resource> all = new ArrayList<>();
        String nextToken = null;
        int pages = 0;

        do {
            ListResourcesRequest.Builder req = ListResourcesRequest.builder()
                    .maxResults(PAGE_SIZE);
            if (nextToken != null) {
                req.nextToken(nextToken);
            }
            ListResourcesResponse response = client.listResources(req.build());
            if (response.resources() != null) {
                all.addAll(response.resources());
            }
            nextToken = response.nextToken();
            pages++;
            if (pages >= MAX_PAGES) {
                log.warn("[Inventory] Hit {}-page ceiling for account {} at {} resources — truncating",
                        MAX_PAGES, account.getAwsAccountId(), all.size());
                break;
            }
        } while (nextToken != null && !nextToken.isBlank());

        log.info("[Inventory] Fetched {} resources in {} page(s) for account {}",
                all.size(), pages, account.getAwsAccountId());
        return all;
    }

    // ── Persist (mark and sweep) ────────────────────────────────────────────

    /**
     * Upserts observed resources, then tombstones anything not observed.
     *
     * <p>
     * One transaction so readers never see a half-swept inventory: either the new
     * generation is fully visible or the old one still is.
     */
    protected SyncResult persist(CloudAccount account, List<Resource> resources,
            IndexInfo indexInfo, long startMs) {
        // Resolved before the transaction opens, never inside it. These are network calls
        // to AWS, and making them while holding a pooled JDBC connection had Hikari
        // reporting "apparent connection leak" — a describe taking seconds pins a
        // connection for that whole time, which under a few accounts would exhaust the
        // pool and stall unrelated requests.
        LiveFacts liveFacts = fetchLiveStates(account, resources);

        // Wrapped explicitly rather than annotated: see transactionTemplate above for why
        // @Transactional does nothing on this self-invoked method.
        return transactionTemplate.execute(status -> persistInTransaction(
                account, resources, indexInfo, startMs, liveFacts));
    }

    /**
     * Resolves state for the handful of types where it genuinely varies.
     *
     * <p>
     * Three types justify a live call: an EC2 instance can be running or stopped, an EBS
     * volume in-use or available, an RDS instance available or stopped. Those distinctions
     * are the reason people open the cloud list, and no static default can fake them —
     * showing "running" for a stopped instance is worse than showing nothing.
     *
     * <p>
     * The cost is bounded by construction. Calls are issued per region <em>that this sync
     * actually found that type in</em>, not per enabled region, and each is one unfiltered
     * bulk describe covering every resource of that type there. On a real account that is
     * about 5 calls, against the ~1000 the describe path made, so the point of the plane
     * survives. Types whose state rarely varies keep their static default rather than
     * spending a call to confirm the obvious.
     *
     * <p>
     * Failure is per region and non-fatal: a region that refuses simply contributes no
     * entries, those rows fall back to their previous value, and the sync still completes.
     * A stale state column is a far smaller problem than an aborted sync that leaves the
     * entire inventory unrefreshed.
     *
     * @return states and describe-derived attributes, both keyed by {@link #stateKey}
     */
    private LiveFacts fetchLiveStates(CloudAccount account, List<Resource> resources) {
        LiveFacts facts = new LiveFacts();

        // Which (type, region) pairs actually occur, so no call is made speculatively.
        Map<String, Set<String>> regionsByType = new HashMap<>();
        for (Resource re : resources) {
            String type = re.resourceType();
            if (!STATEFUL_TYPES.contains(type) || re.region() == null || re.region().isBlank()) {
                continue;
            }
            regionsByType.computeIfAbsent(type, t -> new LinkedHashSet<>()).add(re.region());
        }
        if (regionsByType.isEmpty()) {
            return facts;
        }

        int calls = 0;
        for (Map.Entry<String, Set<String>> entry : regionsByType.entrySet()) {
            String type = entry.getKey();
            for (String region : entry.getValue()) {
                try {
                    Map<String, Map<String, String>> detailsById = new HashMap<>();
                    fetchStates(account, type, region, detailsById)
                            .forEach((id, state) -> facts.states.put(stateKey(type, id), state));
                    detailsById.forEach((id, det) -> facts.details.put(stateKey(type, id), det));
                    calls++;
                } catch (Exception e) {
                    log.debug("[Inventory] State lookup failed for {} in {} ({}): {}",
                            type, region, account.getAwsAccountId(), e.getMessage());
                }
            }
        }
        log.debug("[Inventory] State enrichment for {}: {} call(s), {} state(s), {} detail set(s)",
                account.getAwsAccountId(), calls, facts.states.size(), facts.details.size());
        return facts;
    }

    /**
     * State plus attributes, harvested from the same bulk describe.
     *
     * <p>
     * Two maps rather than one because they are populated for different reasons and to
     * different depths: every {@link #STATEFUL_TYPES} entry yields a state, while only the
     * types whose describe response happens to carry attributes a consumer needs yield
     * details. Keeping them separate means a type can contribute one without the other.
     */
    static final class LiveFacts {
        /** Resource state by {@link #stateKey}. */
        final Map<String, String> states = new HashMap<>();
        /** Describe-derived attributes by {@link #stateKey}. */
        final Map<String, Map<String, String>> details = new HashMap<>();
    }

    /** Type-qualified, because a resource id is only unique within its type. */
    private static String stateKey(String nativeType, String resourceId) {
        return nativeType + "|" + resourceId;
    }

    /**
     * One bulk describe, returning resourceId to state.
     *
     * @param detailsOut populated with attributes for the types that carry them; the same
     *                   API response already holds these, so filling it costs nothing extra
     */
    private Map<String, String> fetchStates(CloudAccount account, String nativeType, String region,
            Map<String, Map<String, String>> detailsOut) {
        Map<String, String> states = new HashMap<>();
        switch (nativeType) {
            case "ec2:instance":
                awsClientProvider.getEc2Client(account, region).describeInstancesPaginator()
                        .reservations().stream()
                        .flatMap(r -> r.instances().stream())
                        .forEach(i -> {
                            states.put(i.instanceId(), i.state().nameAsString());
                            detailsOut.put(i.instanceId(), instanceDetails(i));
                        });
                break;
            case "ec2:volume":
                awsClientProvider.getEc2Client(account, region).describeVolumesPaginator()
                        .volumes()
                        .forEach(v -> states.put(v.volumeId(), v.stateAsString()));
                break;
            case "rds:db":
                awsClientProvider.getRdsClient(account, region).describeDBInstancesPaginator()
                        .dbInstances()
                        .forEach(d -> states.put(d.dbInstanceIdentifier(), d.dbInstanceStatus()));
                break;
            case "ec2:elastic-ip":
                // An unassociated EIP is billed hourly, so this is the value that turns a
                // row into a finding.
                awsClientProvider.getEc2Client(account, region).describeAddresses().addresses()
                        .forEach(a -> states.put(a.allocationId(),
                                a.associationId() != null ? "Associated" : "Unassociated"));
                break;
            case "ec2:network-interface":
                awsClientProvider.getEc2Client(account, region).describeNetworkInterfacesPaginator()
                        .networkInterfaces()
                        .forEach(n -> states.put(n.networkInterfaceId(), n.statusAsString()));
                break;
            case "acm:certificate":
                awsClientProvider.getAcmClient(account, region).listCertificatesPaginator()
                        .certificateSummaryList()
                        .forEach(c -> states.put(
                                AwsResourceTypeMapper.resourceIdFromArn(c.certificateArn()),
                                c.statusAsString()));
                break;
            case "cloudwatch:alarm":
                awsClientProvider.getCloudWatchClient(account, region).describeAlarmsPaginator()
                        .metricAlarms()
                        .forEach(a -> states.put(a.alarmName(), a.stateValueAsString()));
                break;
            case "ssm:managed-instance":
                awsClientProvider.getSsmClient(account, region)
                        .describeInstanceInformationPaginator().instanceInformationList()
                        .forEach(i -> states.put(i.instanceId(), i.pingStatusAsString()));
                break;
            case "cloudformation:stack":
                // Resource Explorer's id for a stack is the trailing uuid of its ARN, not
                // the stack name, so key on the same derivation the row used.
                awsClientProvider.getCloudFormationClient(account, region)
                        .describeStacksPaginator().stacks()
                        .forEach(st -> states.put(
                                AwsResourceTypeMapper.resourceIdFromArn(st.stackId()),
                                st.stackStatusAsString()));
                break;
            case "ec2:image":
                // self-owned only: without the filter this returns every public AMI.
                awsClientProvider.getEc2Client(account, region)
                        .describeImages(r -> r.owners("self")).images()
                        .forEach(i -> states.put(i.imageId(), i.stateAsString()));
                break;
            case "ec2:vpc-endpoint":
                awsClientProvider.getEc2Client(account, region).describeVpcEndpoints()
                        .vpcEndpoints()
                        .forEach(v -> states.put(v.vpcEndpointId(), v.stateAsString()));
                break;
            default:
                break;
        }
        return states;
    }

    /**
     * EC2 instance attributes, using the describe path's exact key names.
     *
     * <p>
     * The keys are copied verbatim from {@code CloudListService.fetchEc2InstancesForCloudlist}
     * for the same reason {@link AwsResourceTypeMapper} pins the display strings: consumers
     * read them by literal name. {@code XamOpsRightsizingService} reads {@code "Type"} and
     * skips any instance where it is null, and {@code CloudMapService} groups nodes by
     * {@code "VPC ID"}. Before this existed the plane wrote {@code "{}"} for every row, so
     * turning the plane on emptied the Rightsizing page and flattened the Cloud Map graph —
     * with no error anywhere, because both treat a missing attribute as "nothing to show".
     *
     * <p>
     * Nulls are dropped rather than stored as empty strings: an instance genuinely outside a
     * VPC has no VPC id, and {@code "VPC ID" -> ""} would read as membership of a VPC named
     * empty-string, which is how the global/VPC split in Cloud Map goes wrong.
     */
    // Package-private rather than private so the key names can be pinned by a test; they
    // are a contract with consumers that read them by literal string.
    static Map<String, String> instanceDetails(software.amazon.awssdk.services.ec2.model.Instance i) {
        Map<String, String> details = new HashMap<>();
        putIfPresent(details, "Type", i.instanceTypeAsString());
        putIfPresent(details, "Image ID", i.imageId());
        putIfPresent(details, "VPC ID", i.vpcId());
        putIfPresent(details, "Subnet ID", i.subnetId());
        putIfPresent(details, "Private IP", i.privateIpAddress());
        if (i.hasSecurityGroups() && !i.securityGroups().isEmpty()) {
            details.put("Security Groups", i.securityGroups().stream()
                    .map(software.amazon.awssdk.services.ec2.model.GroupIdentifier::groupId)
                    .collect(Collectors.joining(", ")));
        }
        return details;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private SyncResult persistInTransaction(CloudAccount account, List<Resource> resources,
            IndexInfo indexInfo, long startMs, LiveFacts liveFacts) {

        String awsAccountId = account.getAwsAccountId();
        Long cloudAccountId = account.getId();

        AwsInventorySyncState state = syncStateRepository.findById(cloudAccountId)
                .orElseGet(() -> newState(account));
        long generation = (state.getSyncGeneration() == null ? 0L : state.getSyncGeneration()) + 1;
        Instant now = Instant.now();

        // Load the account's existing rows once and index by ARN. One query plus in-memory
        // lookups beats a findByArn per resource, which at 5000 resources would be 5000
        // round trips.
        //
        // Scoped by cloudAccountId, matching the sweep below. It used to load by
        // awsAccountId, which is a different set whenever one AWS account has more than
        // one cloud_account row — after a disconnect/reconnect, for instance. The upsert
        // then found the *previous* connection's rows, updated those in place and left
        // this connection with nothing: a real account synced 1640 resources and wrote 10,
        // while stamping its generation counter onto rows belonging to the old connection,
        // which the sweep (correctly scoped) could never retire.
        //
        // Every status, not just ACTIVE. The unique index this upsert targets is on
        // (cloud_account_id, arn) and does not include status, so a swept row still owns
        // its ARN. Reading only ACTIVE rows made a returning resource look new, and the
        // insert that followed was rejected by that index — one collision failing the
        // whole transaction and dropping the account onto describes indefinitely. Rows
        // found here are revived below by the setStatus/setDeletedAt(null) pair, which
        // already handled this case for rows it could see.
        Map<String, AwsCloudResource> existing = new HashMap<>();
        for (AwsCloudResource row : resourceRepository.findAllByCloudAccountId(cloudAccountId)) {
            existing.put(row.getArn(), row);
        }

        List<AwsCloudResource> toSave = new ArrayList<>();
        int created = 0;
        int observed = 0;

        for (Resource re : resources) {
            String nativeType = re.resourceType();
            if (AwsResourceTypeMapper.isSuppressed(nativeType)) {
                continue;
            }
            String arn = re.arn();
            if (arn == null || arn.isBlank()) {
                continue;
            }
            observed++;

            AwsCloudResource row = existing.get(arn);
            if (row == null) {
                row = new AwsCloudResource();
                row.setCloudAccountId(cloudAccountId);
                row.setAwsAccountId(awsAccountId);
                row.setArn(arn);
                row.setFirstSeenAt(now);
                created++;
            }

            row.setResourceId(AwsResourceTypeMapper.resourceIdFromArn(arn));
            row.setService(re.service() == null ? "unknown" : re.service());
            row.setNativeType(nativeType);
            row.setDisplayType(AwsResourceTypeMapper.displayTypeFor(nativeType));
            row.setRegion(re.region());
            row.setTags(writeJson(extractTags(re)));
            row.setName(deriveName(row));
            // Resource Explorer never reports state. For most types the describe path did
            // not know it either and used a literal, so reproduce that; the types with real
            // state come from fetchLiveStates, resolved before this transaction opened.
            // Without either, every row is null and the whole cloud list reads "Unknown".
            String facts = stateKey(nativeType, row.getResourceId());
            String liveState = liveFacts.states.get(facts);
            if (liveState != null) {
                row.setState(liveState);
            } else {
                String staticState = AwsResourceTypeMapper.staticStateFor(nativeType);
                if (staticState != null) {
                    row.setState(staticState);
                }
                // Otherwise leave whatever is there: on a stateful type whose describe
                // failed this keeps the last known value rather than blanking it to
                // "Unknown" for one cycle.
            }
            row.setStatus(AwsCloudResource.STATUS_ACTIVE);
            row.setDeletedAt(null);
            row.setLastSeenAt(now);
            row.setSyncGeneration(generation);
            row.setSource(AwsCloudResource.SOURCE_RESOURCE_EXPLORER);
            // Same reasoning as state: overwrite only when this pass actually resolved
            // attributes, so a failed describe keeps the last known values instead of
            // blanking details that consumers filter on.
            Map<String, String> liveDetails = liveFacts.details.get(facts);
            if (liveDetails != null && !liveDetails.isEmpty()) {
                row.setDetails(writeJson(liveDetails));
            } else if (row.getDetails() == null) {
                row.setDetails("{}");
            }
            // Resource Explorer reports when it last saw the resource, not when AWS
            // created it. Used only as a weak ordering hint, never shown as "created".
            if (re.lastReportedAt() != null && row.getLaunchTime() == null) {
                row.setLaunchTime(re.lastReportedAt());
            }

            toSave.add(row);
        }

        resourceRepository.saveAll(toSave);

        // Sweep only when this fetch plausibly covered the account. A fetch that returned
        // far fewer resources than we already hold is much more likely to be a truncated
        // or partially-failed read than a genuine mass deletion, and sweeping on it would
        // tombstone a correct inventory. Skipping leaves the previous generation intact and
        // the next run corrects it.
        int swept = 0;
        int previouslyActive = existing.size();
        boolean coverageOk = previouslyActive == 0
                || observed >= (int) Math.floor(previouslyActive * MIN_SWEEP_COVERAGE);

        if (coverageOk) {
            if (indexInfo.uncoveredRegions.isEmpty()) {
                swept = resourceRepository.sweepStaleResources(cloudAccountId, generation, now);
            } else {
                // Spare regions Resource Explorer cannot see. Their resources are absent
                // from the search for a coverage reason, not because they were deleted.
                swept = resourceRepository.sweepStaleResourcesExcludingRegions(
                        cloudAccountId, generation, now, indexInfo.uncoveredRegions);
                log.info("[Inventory] Swept {} row(s) for account {}, protecting {} unindexed "
                        + "region(s): {}", swept, awsAccountId,
                        indexInfo.uncoveredRegions.size(), indexInfo.uncoveredRegions);
            }
        } else {
            log.warn("[Inventory] Skipping sweep for account {} — observed {} resources but "
                    + "{} were previously active ({}% coverage, need {}%). Treating as a "
                    + "truncated read rather than a mass deletion; existing rows are kept.",
                    awsAccountId, observed, previouslyActive,
                    previouslyActive == 0 ? 100 : (observed * 100 / previouslyActive),
                    (int) (MIN_SWEEP_COVERAGE * 100));
        }

        state.setSyncGeneration(generation);
        state.setLastFullSyncAt(now);
        state.setMode(AwsInventorySyncState.MODE_RESOURCE_EXPLORER);
        state.setIndexType(indexInfo.indexType);
        state.setAggregatorRegion(indexInfo.region);
        state.setResourceCount(observed);
        state.setUncoveredRegions(indexInfo.uncoveredRegions.isEmpty()
                ? null
                : String.join(",", indexInfo.uncoveredRegions));
        state.setLastError(null);
        long duration = System.currentTimeMillis() - startMs;
        state.setLastSyncDurationMs(duration);
        state.setUpdatedAt(now);
        syncStateRepository.save(state);

        return new SyncResult(true, AwsInventorySyncState.MODE_RESOURCE_EXPLORER,
                indexInfo.indexType, observed, created, swept, duration, null);
    }

    protected void recordState(CloudAccount account, Long generation, String mode,
            String indexType, String region, int count, long durationMs, String error) {
        // Same reasoning as persist(): self-invoked, so it needs an explicit transaction.
        transactionTemplate.executeWithoutResult(status -> recordStateInTransaction(
                account, generation, mode, indexType, region, count, durationMs, error));
    }

    private void recordStateInTransaction(CloudAccount account, Long generation, String mode,
            String indexType, String region, int count, long durationMs, String error) {
        AwsInventorySyncState state = syncStateRepository.findById(account.getId())
                .orElseGet(() -> newState(account));
        if (generation != null) {
            state.setSyncGeneration(generation);
        }
        state.setMode(mode);
        state.setIndexType(indexType);
        state.setAggregatorRegion(region);
        state.setResourceCount(count);
        state.setLastError(truncate(error));
        state.setLastSyncDurationMs(durationMs);
        state.setUpdatedAt(Instant.now());
        syncStateRepository.save(state);
    }

    private AwsInventorySyncState newState(CloudAccount account) {
        AwsInventorySyncState state = new AwsInventorySyncState();
        state.setCloudAccountId(account.getId());
        state.setAwsAccountId(account.getAwsAccountId());
        state.setUpdatedAt(Instant.now());
        return state;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Pulls tags out of the {@code properties} bag.
     *
     * <p>
     * Resource Explorer returns properties as a list of named JSON documents, with tags
     * under the {@code tags} name as an array of {@code {Key, Value}} objects. Parsed
     * defensively — a malformed property must not fail the whole account sync.
     */
    private Map<String, String> extractTags(Resource resource) {
        Map<String, String> tags = new HashMap<>();
        if (resource.properties() == null) {
            return tags;
        }
        for (ResourceProperty property : resource.properties()) {
            if (!"tags".equalsIgnoreCase(property.name()) || property.data() == null) {
                continue;
            }
            try {
                var node = objectMapper.readTree(property.data().toString());
                if (node.isArray()) {
                    node.forEach(entry -> {
                        String key = text(entry, "Key", "key");
                        String value = text(entry, "Value", "value");
                        if (key != null) {
                            tags.put(key, value == null ? "" : value);
                        }
                    });
                } else if (node.isObject()) {
                    node.fields().forEachRemaining(f -> tags.put(f.getKey(), f.getValue().asText("")));
                }
            } catch (Exception e) {
                log.debug("[Inventory] Could not parse tags for {}: {}", resource.arn(), e.getMessage());
            }
        }
        return tags;
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node, String... names) {
        for (String name : names) {
            if (node.hasNonNull(name)) {
                return node.get(name).asText();
            }
        }
        return null;
    }

    /**
     * Display name: the {@code Name} tag when present, else the short resource id.
     * Mirrors {@code CloudListService.getTagName(tags, fallback)} so the cloud list
     * reads the same before and after the switch.
     */
    private String deriveName(AwsCloudResource row) {
        try {
            if (row.getTags() != null) {
                var node = objectMapper.readTree(row.getTags());
                if (node.hasNonNull("Name")) {
                    String name = node.get("Name").asText();
                    if (!name.isBlank()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the resource id.
        }
        return row.getResourceId();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
