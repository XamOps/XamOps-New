package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.MattersProjectMapping;
import com.xammer.cloud.dto.matters.DspmAssetDetail;
import com.xammer.cloud.dto.matters.DspmAssetRow;
import com.xammer.cloud.dto.matters.DspmFilters;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.DspmStatus;
import com.xammer.cloud.dto.matters.DspmSummary;
import com.xammer.cloud.repository.MattersProjectMappingRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read side of the Matters DSPM add-on.
 *
 * <h2>Live proxy, not a local mirror</h2>
 * Every method reads through to Matters and caches the result briefly in Redis. There is no sync
 * scheduler and no ingest table, unlike the Upwind integration which mirrors the whole estate into
 * Postgres.
 *
 * The reason is that the upstream is already a UI-shaped API: {@code /assets} is filterable, sortable
 * and paginated, {@code /assets/filters} returns disjunctive facet counts, and {@code /assets/summary}
 * returns per-category counts with 14-day trends. Mirroring it locally would mean reimplementing
 * disjunctive faceting against our own tables for no gain. And the rate limit is 600 requests/minute
 * <em>per key</em> with a key per customer, so budgets never contend between customers and a page load
 * costs a handful of requests out of that customer's own allowance.
 *
 * The accepted cost: when Matters is down, the page errors rather than showing stale data. That is
 * reported honestly through {@link DspmStatus} rather than being disguised as an empty result, because
 * an empty inventory and an unreachable vendor mean opposite things on a data-security screen.
 *
 * <h2>Tenancy</h2>
 * Every method takes a {@code clientId} that must come from the authenticated principal, and resolves it
 * through {@link MattersCredentialResolver} — the only place a client id becomes a credential. Because
 * the vendor key is itself scoped to one project, selecting the right row <em>is</em> the isolation; there
 * is no post-fetch filter, and no parameter a request could use to widen its own scope.
 *
 * <h2>Cache keys</h2>
 * Always prefixed with the client id, so one customer's cached inventory can never be served to another.
 * {@link DspmAssetQuery#cacheFragment()} supplies the rest from normalised values, so equivalent requests
 * share an entry.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MattersDspmService {

    /**
     * Short TTLs. This is a live view of a security posture, and the vendor's own scans complete on
     * their own schedule — a long cache would leave a customer looking at a resolved finding. Long
     * enough to absorb tab switching and pagination within one session.
     */
    private static final int LIST_CACHE_MINUTES = 2;
    private static final int FILTERS_CACHE_MINUTES = 5;
    private static final int DETAIL_CACHE_MINUTES = 2;
    private static final int SUMMARY_CACHE_MINUTES = 5;

    private static final String KEY_PREFIX = "dspm:";

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final MattersAssetMapper mapper;
    private final MattersProjectMappingRepository mappingRepository;
    private final MattersIntegrationLabeler integrationLabeler;
    private final RedisCacheService cache;

    // ── Cloud surface ────────────────────────────────────────────────────────────────────────────

    /**
     * An asset list for one surface — datastores, SaaS accounts or managed endpoints.
     *
     * The surface is carried by the query's {@code resource_type} scope, so this method is the same for
     * all three. The cache key derives from that scope, so one surface can never serve another's page.
     */
    public DspmPage<DspmAssetRow> getAssets(Long clientId, DspmAssetQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        String key = KEY_PREFIX + "assets:" + clientId + ":" + query.cacheFragment();

        Optional<DspmPage<DspmAssetRow>> cached =
                cache.get(key, new TypeReference<DspmPage<DspmAssetRow>>() {});
        if (cached.isPresent()) {
            return cached.get();
        }

        JsonNode body = apiClient.listAssets(credential, query.toListParams());
        DspmPage<DspmAssetRow> page = mapper.toPage(body);
        // SaaS for its email, drive type, file count and size; Endpoint for its deduplicated record count
        // (the agent lists some files twice, and the list's figure counts both).
        if (DspmAssetQuery.Surface.SAAS.resourceType().equals(query.resourceTypeScope())
                || DspmAssetQuery.Surface.ENDPOINT.resourceType().equals(query.resourceTypeScope())) {
            page = withProfiles(credential, clientId, page);
        }
        cache.put(key, page, LIST_CACHE_MINUTES);
        return page;
    }

    // ── row profiles ─────────────────────────────────────────────────────────────────────────────

    /**
     * Longer than the page cache: an account's email and drive type never change, and its file count
     * and size move only as scans progress. This is what keeps the per-row detail calls rare.
     */
    private static final int PROFILE_CACHE_MINUTES = 30;

    /** Detail calls in flight at once for one page. Bounds the burst against the 600/min per-key limit. */
    private static final int PROFILE_CONCURRENCY = 6;

    private static final long PROFILE_TIMEOUT_SECONDS = 20;

    private final java.util.concurrent.ExecutorService profilePool =
            java.util.concurrent.Executors.newFixedThreadPool(PROFILE_CONCURRENCY, runnable -> {
                Thread thread = new Thread(runnable, "dspm-profile");
                thread.setDaemon(true);
                return thread;
            });

    @jakarta.annotation.PreDestroy
    void shutdownProfilePool() {
        profilePool.shutdownNow();
    }

    /**
     * Attaches detail-only fields to each row on the page.
     *
     * The SaaS table's Assets, Total Files and Total Size columns come from asset detail, not the list
     * (see {@link DspmAssetRow.Profile}), so each row costs one detail call when its profile is not
     * cached. Calls run in parallel on a small pool, carrying the caller's security and request context
     * so the audit trail still records who read the data and from where.
     *
     * Best-effort per row: a failed or slow lookup leaves that row without a profile, and its cells
     * render as a dash. A table missing two sizes is better than a table that fails to load.
     */
    private DspmPage<DspmAssetRow> withProfiles(MattersCredential credential, Long clientId,
                                                DspmPage<DspmAssetRow> page) {
        org.springframework.security.core.context.SecurityContext security =
                org.springframework.security.core.context.SecurityContextHolder.getContext();
        org.springframework.web.context.request.RequestAttributes request =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();

        List<java.util.concurrent.CompletableFuture<DspmAssetRow.Profile>> lookups = page.data().stream()
                .map(row -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    org.springframework.security.core.context.SecurityContextHolder.setContext(security);
                    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(request);
                    try {
                        return profileOf(credential, clientId, row.id());
                    } finally {
                        // Pool threads are reused: clear both so no later task inherits this caller.
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
                    }
                }, profilePool))
                .toList();

        List<DspmAssetRow> rows = new java.util.ArrayList<>(page.data().size());
        for (int i = 0; i < page.data().size(); i++) {
            DspmAssetRow row = page.data().get(i);
            DspmAssetRow.Profile profile = null;
            try {
                profile = lookups.get(i).get(PROFILE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                log.debug("[DSPM] no profile for asset {}: {}", row.id(), e.getMessage());
            }
            rows.add(profile == null ? row : row.withProfile(profile));
        }
        return new DspmPage<>(rows, page.page(), page.pageSize(), page.total(), page.totalPages());
    }

    /** One asset's profile, from cache or from its detail. Null when the lookup fails. */
    private DspmAssetRow.Profile profileOf(MattersCredential credential, Long clientId, String assetId) {
        String key = KEY_PREFIX + "profile:" + clientId + ":" + assetId;
        Optional<DspmAssetRow.Profile> cached = cache.get(key, new TypeReference<DspmAssetRow.Profile>() {});
        if (cached.isPresent()) {
            return cached.get();
        }
        try {
            DspmAssetRow.Profile profile = mapper.toProfile(apiClient.getAsset(credential, assetId));
            cache.put(key, profile, PROFILE_CACHE_MINUTES);
            return profile;
        } catch (RuntimeException e) {
            log.debug("[DSPM] could not load profile for asset {}: {}", assetId, e.getMessage());
            return null;
        }
    }

    /**
     * Filter options with faceted counts.
     *
     * Called with the same filters as {@link #getAssets}, which is what preserves the upstream's
     * disjunctive counting — a dimension the user has already filtered on keeps showing all of its
     * options rather than collapsing to the selected one.
     *
     * <p>The {@code integration_id} options are relabelled on the way out. The vendor returns its own
     * ObjectIds there while its console shows {@code 605134457560 (Xammer-prod)}; see
     * {@link MattersIntegrationLabeler} for where each half of that comes from. Done before caching so
     * the extra lookups are paid once per five-minute window rather than per request.
     */
    public DspmFilters getFilters(Long clientId, DspmAssetQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        String key = KEY_PREFIX + "filters:" + clientId + ":" + query.filterCacheFragment();

        Optional<DspmFilters> cached = cache.get(key, new TypeReference<DspmFilters>() {});
        if (cached.isPresent()) {
            return cached.get();
        }

        JsonNode body = apiClient.listFilters(credential, query.toFilterParams());
        DspmFilters filters = integrationLabeler.withAccountLabels(credential, mapper.toFilters(body));
        cache.put(key, filters, FILTERS_CACHE_MINUTES);
        return filters;
    }

    /**
     * One asset in full.
     *
     * An id from another project, an unknown id and a non-UUID string are all indistinguishable here —
     * the vendor answers 404 for each, which surfaces as {@link MattersApiException.Reason#ASSET_NOT_FOUND}.
     * That is the correct behaviour: telling a caller that an id exists but belongs to someone else would
     * itself be a disclosure.
     */
    public DspmAssetDetail getAsset(Long clientId, String assetId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        String key = KEY_PREFIX + "asset:" + clientId + ":" + assetId;

        Optional<DspmAssetDetail> cached = cache.get(key, new TypeReference<DspmAssetDetail>() {});
        if (cached.isPresent()) {
            return cached.get();
        }

        JsonNode body = apiClient.getAsset(credential, assetId);
        DspmAssetDetail detail = mapper.toDetail(body);
        // Only paid when the 50-object cap may have hidden sensitive objects. Once the returned list
        // reaches clean objects, every entity is already in hand.
        if (detail.breakdownPartial()) {
            detail = mapper.withDetectedEntities(detail, detectedElements(credential, detail));
        }
        cache.put(key, detail, DETAIL_CACHE_MINUTES);
        return detail;
    }

    /**
     * Every detector that fired anywhere in one asset, from a filter call scoped to just that asset.
     *
     * <p>There is no asset-id filter, so the scope is the asset's own attributes: name, resource type,
     * integration, provider, scan status and severity. That isolated the asset in all 17 truncated assets
     * in the test project, but it is checked on every call rather than assumed: the {@code resource_type}
     * facet counts the matching assets, and anything other than exactly one means the facet would mix in
     * another asset's detectors, so nothing is returned.
     *
     * <p>Best-effort. A failure returns an empty list, which leaves the sampled entities as they were.
     */
    private List<String> detectedElements(MattersCredential credential, DspmAssetDetail detail) {
        if (detail.resourceType() == null || detail.name() == null) {
            return List.of();
        }
        Map<String, List<String>> scope = new LinkedHashMap<>();
        scope.put("resource_type", List.of(detail.resourceType()));
        scope.put("q", List.of(detail.name()));
        if (detail.source().account() != null) {
            scope.put("integration_id", List.of(detail.source().account()));
        }
        if (detail.source().provider() != null) {
            scope.put("provider", List.of(detail.source().provider()));
        }
        if (detail.scan().status() != null) {
            scope.put("scan_status", List.of(detail.scan().status()));
        }
        // Already folded to NONE when the vendor sent null, which is the value this filter accepts.
        scope.put("severity", List.of(detail.risk().level()));

        try {
            DspmFilters facets = mapper.toFilters(apiClient.listFilters(credential, scope));
            long matching = facets.dimensions().getOrDefault("resource_type", List.of()).stream()
                    .filter(option -> detail.resourceType().equals(option.value()))
                    .mapToLong(DspmFilters.Option::count)
                    .sum();
            if (matching != 1) {
                log.debug("[DSPM] entity scope for asset {} matched {} assets; using the sample only",
                        detail.id(), matching);
                return List.of();
            }
            return facets.dimensions().getOrDefault("data_element", List.of()).stream()
                    .map(DspmFilters.Option::value)
                    .toList();
        } catch (RuntimeException e) {
            // Includes MattersApiException. The detail is still correct without the extra entities.
            log.debug("[DSPM] could not read detected entities for asset {}: {}", detail.id(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Headline counts and trends.
     *
     * @param category null or blank for the whole project; otherwise a single tab. Not
     *                 datastore-scoped by default, because the summary endpoint takes a single tab
     *                 rather than a list and {@code all} is the useful default for the scan-coverage
     *                 figures. Note this is the one place a category value is legitimate: it is
     *                 single-valued here.
     */
    public DspmSummary getSummary(Long clientId, String category) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        String scope = (category == null || category.isBlank()) ? "all" : category;
        String key = KEY_PREFIX + "summary:" + clientId + ":" + scope;

        Optional<DspmSummary> cached = cache.get(key, new TypeReference<DspmSummary>() {});
        if (cached.isPresent()) {
            return cached.get();
        }

        JsonNode body = apiClient.summary(credential, "all".equals(scope) ? null : scope);
        DspmSummary summary = mapper.toSummary(body);
        cache.put(key, summary, SUMMARY_CACHE_MINUTES);
        return summary;
    }

    // ── status ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Connection state, as a value rather than as an exception.
     *
     * Never throws. The customer-facing surface needs to render "not entitled", "being set up", "key
     * expired" and "vendor unreachable" as four different screens, so it wants the reason as data. The
     * read methods above throw for the same conditions because they cannot return a partial answer.
     *
     * Deliberately not cached: it is a single local lookup in the common case, and a customer refreshing
     * after an operator has just fixed their mapping should see the change immediately rather than wait
     * out a TTL.
     */
    public DspmStatus getStatus(Long clientId) {
        boolean entitled = credentialResolver.isEntitled(clientId);
        if (!entitled) {
            return new DspmStatus(false, false, false,
                    MattersApiException.Reason.NOT_ENTITLED.name(),
                    "Data Security posture management is not enabled for your organisation.",
                    null, null);
        }

        Optional<MattersProjectMapping> mapping = mappingRepository.findByClientId(clientId);
        if (mapping.isEmpty()) {
            return new DspmStatus(false, true, false,
                    MattersApiException.Reason.NOT_CONFIGURED.name(),
                    "Your data security workspace is being set up. This usually completes within one "
                            + "business day.",
                    null, null);
        }

        MattersProjectMapping row = mapping.get();
        Optional<MattersApiException.Reason> problem = credentialResolver.describeProblem(clientId);

        if (problem.isEmpty()) {
            return DspmStatus.ready(row.getMattersProjectName(), row.getLastVerifiedAt());
        }

        MattersApiException.Reason reason = problem.get();
        return new DspmStatus(false, true, row.isCredentialStored(), reason.name(),
                customerMessage(reason), row.getMattersProjectName(), row.getLastVerifiedAt());
    }

    /**
     * Customer-safe wording per failure reason.
     *
     * The internal detail stays in the audit trail and the logs. A customer does not need to know that
     * an encryption key is mismatched — they need to know whether to wait, retry, or contact someone,
     * and every message here answers exactly that.
     */
    private String customerMessage(MattersApiException.Reason reason) {
        return switch (reason) {
            case NOT_ENTITLED ->
                    "Data Security posture management is not enabled for your organisation.";
            case NOT_CONFIGURED ->
                    "Your data security workspace is being set up. This usually completes within one "
                            + "business day.";
            case DISABLED ->
                    "Your data security connection is paused. Contact support to re-enable it.";
            // Three distinct operator faults, one customer message: in each case the customer can do
            // nothing, and naming the internal cause would be noise at best.
            case KEY_EXPIRED, INVALID_KEY, WRONG_ENVIRONMENT, FORBIDDEN_SCOPE, DECRYPT_FAILED,
                 MALFORMED_KEY ->
                    "Your data security connection needs attention. Our team has been notified.";
            case RATE_LIMITED ->
                    "Too many requests in a short period. Please wait a moment and try again.";
            case UPSTREAM_UNAVAILABLE, BAD_UPSTREAM_RESPONSE ->
                    "The data security service is temporarily unavailable. Please try again shortly.";
            case ASSET_NOT_FOUND -> "That asset could not be found.";
        };
    }

    // ── cache maintenance ────────────────────────────────────────────────────────────────────────

    /**
     * Drops every cached DSPM response for one client.
     *
     * Called after an operator changes a mapping. Without it, repointing a customer to a different
     * project would leave the previous project's assets on screen until each TTL lapsed — which on a
     * data-security page means showing one customer another customer's inventory. The short TTLs limit
     * the window; this closes it.
     */
    public void evictClient(Long clientId) {
        long removed = cache.evictByPattern(KEY_PREFIX + "*:" + clientId + ":*");
        // Held under a different key shape (one entry per client, not per query) and with a twelve-hour
        // TTL, so the pattern sweep above does not reach it and it would outlive a repoint by half a day.
        integrationLabeler.evictClient(clientId);
        log.info("[DSPM] evicted {} cached entries for client {}", removed, clientId);
    }
}
