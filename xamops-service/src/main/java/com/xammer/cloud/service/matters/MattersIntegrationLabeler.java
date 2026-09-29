package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.matters.DspmFilters;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns Matters integration ids into the account labels the vendor console shows.
 *
 * <h2>The problem</h2>
 * The {@code integration_id} filter dimension — labelled "Account" in both consoles — returns Matters'
 * own Mongo ObjectIds: {@code 6aa3d15659068e284d906edb}. The vendor console shows
 * {@code 605134457560 (Xammer-prod)} for the same option. Neither half of that string is in the filter
 * response, so without this class the Account dropdown is a list of hex strings no customer can act on.
 *
 * <h2>Where each half comes from</h2>
 * <ul>
 *   <li><b>{@code 605134457560}</b> — the cloud account number, available as {@code facet.cloud_account_id}
 *       on <em>asset detail</em> only. Not on list rows, and not in the filter response. Verified live:
 *       AWS integrations report the 12-digit account id, GCP the project id ({@code xamops}), Azure the
 *       subscription GUID. All three are exactly what {@code CloudAccount.getProviderAccountId()} holds
 *       on our side, which is what makes the join below possible.</li>
 *   <li><b>{@code Xammer-prod}</b> — a friendly name Matters keeps but does not expose. {@code
 *       /api/v1/integrations} exists but answers 401 for an All-Assets key, so it is unreachable. The
 *       name is taken from XamOps' own {@link CloudAccount#getAccountName()} instead, which is arguably
 *       better: it is the name the customer chose in our product, so the dropdown matches the account
 *       picker they already use elsewhere.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 * Two upstream calls per integration — one to find any asset under it, one to read that asset's facet —
 * because there is no endpoint that maps integrations to accounts directly. That is why the result is
 * cached for {@link #CACHE_MINUTES} rather than the five minutes the filter response itself gets: an
 * integration's cloud account id is fixed for the life of the integration, so this is close to static
 * data. The live test project has four datastore integrations, so a cold build costs eight requests out
 * of a 600/minute per-key budget.
 *
 * <p>{@link #MAX_LOOKUPS} bounds the cold cost for a large estate. Beyond it the remaining options keep
 * their raw ids, which is the pre-existing behaviour rather than a regression.
 *
 * <h2>Failure behaviour</h2>
 * Every lookup is individually guarded and falls back to the raw id. Label resolution is a cosmetic
 * improvement to a filter dropdown; it must never be the reason a customer cannot see their assets.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MattersIntegrationLabeler {

    /** The dimension this class exists for. */
    public static final String DIMENSION = "integration_id";

    /**
     * 12 hours. An integration's cloud account id does not change — repointing an integration at a
     * different account means creating a new integration, which gets a new id and so a new cache entry.
     */
    private static final int CACHE_MINUTES = 12 * 60;

    /**
     * Ceiling on integrations resolved in one pass, to bound the cold-start request burst.
     *
     * Chosen well above what any observed tenant has (the live project has five across all surfaces)
     * but low enough that a misconfigured tenant with hundreds of integrations cannot turn one filter
     * request into a thousand upstream calls.
     */
    private static final int MAX_LOOKUPS = 25;

    private static final String CACHE_PREFIX = "dspm:intlabel:";

    private final MattersApiClient apiClient;
    private final CloudAccountRepository cloudAccountRepository;
    private final RedisCacheService cache;

    /**
     * Rewrites the {@code integration_id} options with readable labels.
     *
     * Returns the input unchanged when the dimension is absent, so callers do not have to check.
     * Counts, values and ordering are preserved — only {@code label} changes, because the value is what
     * gets sent back as a filter and must stay the vendor's id.
     */
    public DspmFilters withAccountLabels(MattersCredential credential, DspmFilters filters) {
        List<DspmFilters.Option> options = filters.dimensions().get(DIMENSION);
        if (options == null || options.isEmpty()) {
            return filters;
        }

        Map<String, String> labels = resolveLabels(credential, options);
        if (labels.isEmpty()) {
            return filters;
        }

        List<DspmFilters.Option> relabelled = new ArrayList<>(options.size());
        for (DspmFilters.Option option : options) {
            String label = labels.get(option.value());
            relabelled.add(label == null
                    ? option
                    : new DspmFilters.Option(option.value(), label, option.count()));
        }

        // Rebuilt rather than mutated: DspmFilters is a record over maps that are cached and may be
        // shared, and the dimension map's insertion order is the rail's display order.
        Map<String, List<DspmFilters.Option>> dimensions = new LinkedHashMap<>(filters.dimensions());
        dimensions.put(DIMENSION, relabelled);
        return new DspmFilters(dimensions, filters.hasMore());
    }

    /**
     * Integration id → label, from cache where possible.
     *
     * The cache holds the whole map for a client rather than one entry per integration, so a page load
     * costs one Redis read. Ids missing from a cached map are resolved and the map rewritten, which
     * covers an integration added since the entry was written without discarding the rest.
     */
    private Map<String, String> resolveLabels(MattersCredential credential,
                                              List<DspmFilters.Option> options) {
        String key = CACHE_PREFIX + credential.clientId();
        Map<String, String> cached = cache.get(key, new TypeReference<Map<String, String>>() {})
                .orElseGet(LinkedHashMap::new);

        List<String> missing = new ArrayList<>();
        for (DspmFilters.Option option : options) {
            String value = option.value();
            if (value != null && !value.isBlank() && !cached.containsKey(value)) {
                missing.add(value);
            }
        }
        if (missing.isEmpty()) {
            return cached;
        }

        Map<String, String> accountNames = xamOpsAccountNames(credential.clientId());
        Map<String, String> resolved = new LinkedHashMap<>(cached);

        int lookups = 0;
        for (String integrationId : missing) {
            if (lookups++ >= MAX_LOOKUPS) {
                log.info("[DSPM] stopped resolving integration labels for client {} at {} lookups",
                        credential.clientId(), MAX_LOOKUPS);
                break;
            }
            String label = lookupLabel(credential, integrationId, accountNames);
            if (label != null) {
                resolved.put(integrationId, label);
            }
        }

        if (!resolved.equals(cached)) {
            cache.put(key, resolved, CACHE_MINUTES);
        }
        return resolved;
    }

    /**
     * Resolves one integration, or null if nothing better than the raw id could be found.
     *
     * Returning null rather than the id itself is deliberate: it keeps an unresolved integration out of
     * the cache, so a transient upstream failure does not pin a hex string in place for twelve hours.
     */
    private String lookupLabel(MattersCredential credential, String integrationId,
                               Map<String, String> accountNames) {
        try {
            JsonNode list = apiClient.listAssets(credential, Map.of(
                    DIMENSION, List.of(integrationId),
                    "page_size", List.of("1")));
            JsonNode first = list.path("data").path(0);
            if (first.isMissingNode()) {
                return null;
            }

            String assetId = first.path("id").asText(null);
            String provider = first.path("source").path("provider").asText(null);
            if (assetId == null || assetId.isBlank()) {
                return null;
            }

            String cloudAccountId = apiClient.getAsset(credential, assetId)
                    .path("facet").path("cloud_account_id").asText(null);

            if (cloudAccountId == null || cloudAccountId.isBlank()) {
                // SaaS and endpoint integrations carry no cloud account — verified: a Google Workspace
                // integration's facet has no cloud_account_id at all. The provider name is the only
                // meaningful thing left, and "Google Workspace" beats an ObjectId.
                return DspmLabels.prettify(provider);
            }

            String friendly = accountNames.get(cloudAccountId);
            // Exactly the vendor's format when we hold the name, and the bare account id when we do not
            // — never a fabricated name.
            return friendly == null ? cloudAccountId : cloudAccountId + " (" + friendly + ")";

        } catch (RuntimeException e) {
            // Includes MattersApiException. Swallowed on purpose: see the class comment. The filter
            // response is still correct without a label.
            log.debug("[DSPM] could not label integration {} for client {}: {}",
                    integrationId, credential.clientId(), e.getMessage());
            return null;
        }
    }

    /**
     * Provider account id → the name the customer gave it in XamOps.
     *
     * Keyed on {@link CloudAccount#getProviderAccountId()}, which returns the AWS account id, GCP
     * project id or Azure subscription id by provider — the same three forms Matters reports in
     * {@code cloud_account_id}, so the join is direct rather than heuristic.
     *
     * Accounts the customer has not connected to XamOps simply do not match, and their options show the
     * bare account id. That is correct: a name we do not have should not be invented.
     */
    private Map<String, String> xamOpsAccountNames(Long clientId) {
        Map<String, String> names = new LinkedHashMap<>();
        try {
            for (CloudAccount account : cloudAccountRepository.findByClientId(clientId)) {
                String providerId = account.getProviderAccountId();
                String name = account.getAccountName();
                if (providerId != null && !providerId.isBlank() && name != null && !name.isBlank()) {
                    names.putIfAbsent(providerId, name);
                }
            }
        } catch (RuntimeException e) {
            log.debug("[DSPM] could not read cloud accounts for client {}: {}", clientId, e.getMessage());
        }
        return names;
    }

    /**
     * Drops the cached labels for one client.
     *
     * Called from the mapping-change eviction path. Repointing a customer at a different Matters project
     * changes which integrations exist, and a twelve-hour cache would otherwise keep labelling the new
     * project's options with the old project's account names.
     */
    public void evictClient(Long clientId) {
        cache.evict(CACHE_PREFIX + clientId);
    }
}
