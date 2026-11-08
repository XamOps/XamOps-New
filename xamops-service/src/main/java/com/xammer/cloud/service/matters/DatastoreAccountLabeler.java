package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xammer.cloud.service.matters.DatastoreRowMapper.text;

/**
 * The Source / Account label of the Datastores page (design 5.8, FR-TBL-6).
 *
 * <p>A separate class from {@link MattersIntegrationLabeler}, which must keep producing
 * {@code <account id> (<account name>)} for the old {@code /{surface}/filters} contract. The console's rule
 * differs per category:
 * <ul>
 *   <li>{@code on_prem}: the row's own name, with no lookup (the integration name is not in the API).</li>
 *   <li>{@code endpoint}: none; the cell reads "-".</li>
 *   <li>{@code cloud}: the XamOps account name alone, else the cloud account id from the asset's
 *       {@code facet.cloud_account_id}, else the provider label. Matters' own integration names are
 *       never invented.</li>
 *   <li>{@code saas}: {@code detail.account_name} from the SaaS detail route, else the provider label.</li>
 *   <li>anything else: the provider label.</li>
 * </ul>
 *
 * <h2>Cost and caching</h2>
 * One Redis entry per client ({@link DatastoreCacheKeys#accountLabels}) maps integration id → label for
 * 12 hours. List rows resolve from the first row of each integration on their own page; filter options
 * find a sample asset per integration first. At most {@link #MAX_LOOKUPS} integrations are resolved per
 * call; the rest get the fallback. A completed lookup is cached even when it yields the provider label.
 *
 * <h2>Failures</h2>
 * The labeler never fails the caller. A {@code FORBIDDEN_SCOPE} on the SaaS detail route (a key
 * without the PLATFORM scope) does not clear on retry, so the provider label is cached as a completed
 * lookup; a key that gains the scope is picked up when {@code MattersAdminService.saveMapping} evicts
 * the client. Any other failure gives the transient fallback (the provider label for rows, the raw id
 * for options) and caches nothing, so a blip is not pinned for 12 hours. Debug lines carry the
 * integration id and the reason only.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DatastoreAccountLabeler {

    /** 12 hours, like {@link MattersIntegrationLabeler}: an integration's account does not change. */
    static final int CACHE_MINUTES = 12 * 60;

    /** Integrations resolved per call, bounding the cold-start burst against the per-key rate limit. */
    static final int MAX_LOOKUPS = 25;

    private static final String CLOUD = "cloud";
    private static final String SAAS = "saas";
    private static final String ENDPOINT = "endpoint";
    private static final String ON_PREM = "on_prem";

    private static final String INTEGRATION_ID = DatastoreFilterDimension.INTEGRATION_ID.key();

    private static final TypeReference<Map<String, String>> LABELS = new TypeReference<Map<String, String>>() {
    };

    private final MattersApiClient apiClient;
    private final CloudAccountRepository cloudAccountRepository;
    private final RedisCacheService cache;

    // ── list rows ────────────────────────────────────────────────────────────────────────────────

    /** The rows with {@code source.accountLabel} filled in, in the same order. */
    public List<DatastoreRow> label(MattersCredential credential, List<DatastoreRowMapper.MappedRow> rows) {
        Map<String, Sample> samples = new LinkedHashMap<>();
        for (DatastoreRowMapper.MappedRow mapped : rows) {
            String category = mapped.row().category();
            if ((CLOUD.equals(category) || SAAS.equals(category)) && !isBlank(mapped.integrationId())) {
                samples.putIfAbsent(mapped.integrationId(), Sample.of(mapped.row()));
            }
        }
        Map<String, String> labels = samples.isEmpty()
                ? Map.of()
                : labelsFor(credential, samples.keySet(), samples::get);

        List<DatastoreRow> labelled = new ArrayList<>(rows.size());
        for (DatastoreRowMapper.MappedRow mapped : rows) {
            labelled.add(withAccountLabel(mapped.row(), rowLabel(mapped, labels)));
        }
        return labelled;
    }

    private static String rowLabel(DatastoreRowMapper.MappedRow mapped, Map<String, String> labels) {
        DatastoreRow row = mapped.row();
        String providerLabel = row.source().providerLabel();
        String category = row.category() == null ? "" : row.category();
        return switch (category) {
            case ON_PREM -> row.name();
            case ENDPOINT -> null;
            case CLOUD, SAAS -> {
                String label = isBlank(mapped.integrationId()) ? null : labels.get(mapped.integrationId());
                yield label != null ? label : providerLabel;
            }
            default -> providerLabel;
        };
    }

    private static DatastoreRow withAccountLabel(DatastoreRow row, String accountLabel) {
        DatastoreRow.Source source = row.source();
        return new DatastoreRow(row.id(), row.sourceId(), row.name(), row.category(), row.subtype(),
                row.subtypeLabel(), row.risk(), row.sensitives(),
                new DatastoreRow.Source(source.provider(), source.providerLabel(), accountLabel, source.region()),
                row.scan(), row.agent());
    }

    // ── filter options ───────────────────────────────────────────────────────────────────────────

    /**
     * The options with readable {@code integration_id} labels. Values, counts and order are kept: the
     * value is what is sent back as a filter. An option without a label keeps its raw id.
     */
    public DatastoreFilterOptions label(MattersCredential credential, DatastoreFilterOptions options) {
        Set<String> integrationIds = new LinkedHashSet<>();
        for (DatastoreFilterOptions.Dimension dimension : options.dimensions()) {
            if (INTEGRATION_ID.equals(dimension.key())) {
                for (DatastoreFilterOptions.Option option : dimension.options()) {
                    if (!isBlank(option.value())) {
                        integrationIds.add(option.value());
                    }
                }
            }
        }
        if (integrationIds.isEmpty()) {
            return options;
        }
        Map<String, String> labels = labelsFor(credential, integrationIds,
                integrationId -> sampleOf(credential, integrationId));

        List<DatastoreFilterOptions.Dimension> dimensions = new ArrayList<>(options.dimensions().size());
        for (DatastoreFilterOptions.Dimension dimension : options.dimensions()) {
            if (!INTEGRATION_ID.equals(dimension.key())) {
                dimensions.add(dimension);
                continue;
            }
            List<DatastoreFilterOptions.Option> relabelled = new ArrayList<>(dimension.options().size());
            for (DatastoreFilterOptions.Option option : dimension.options()) {
                String label = option.value() == null ? null : labels.get(option.value());
                relabelled.add(label == null
                        ? option
                        : new DatastoreFilterOptions.Option(option.value(), label, option.count()));
            }
            dimensions.add(new DatastoreFilterOptions.Dimension(dimension.key(), relabelled, dimension.hasMore()));
        }
        return new DatastoreFilterOptions(dimensions);
    }

    /** Any asset of the integration, from a one-row list call; null when the integration has none. */
    private Sample sampleOf(MattersCredential credential, String integrationId) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put(INTEGRATION_ID, List.of(integrationId));
        query.put("page_size", List.of("1"));
        JsonNode first = apiClient.listAssets(credential, query).path("data").path(0);
        return first.isObject() ? Sample.of(first) : null;
    }

    // ── resolution ───────────────────────────────────────────────────────────────────────────────

    /**
     * Integration id → label: the cached map plus the lookups this call completes. A failed lookup has
     * no entry, so the caller applies its own fallback.
     */
    private Map<String, String> labelsFor(MattersCredential credential, Collection<String> integrationIds,
                                          SampleFinder samples) {
        String key = DatastoreCacheKeys.accountLabels(credential.clientId());
        Map<String, String> cached = cache.get(key, LABELS).orElseGet(LinkedHashMap::new);
        Map<String, String> labels = new LinkedHashMap<>(cached);

        Lookup lookup = new Lookup(credential);
        int lookups = 0;
        for (String integrationId : integrationIds) {
            if (labels.containsKey(integrationId)) {
                continue;
            }
            if (lookups++ >= MAX_LOOKUPS) {
                log.debug("[DSPM] account labels: stopped after {} lookups", MAX_LOOKUPS);
                break;
            }
            String label = resolve(lookup, integrationId, samples);
            if (label != null) {
                labels.put(integrationId, label);
            }
        }
        if (!labels.equals(cached)) {
            cache.put(key, labels, CACHE_MINUTES);
        }
        return labels;
    }

    /** One integration's label, or null when the lookup failed or found nothing (nothing is cached). */
    private String resolve(Lookup lookup, String integrationId, SampleFinder samples) {
        try {
            Sample sample = samples.find(integrationId);
            if (sample == null) {
                return null;
            }
            String category = sample.category() == null ? "" : sample.category();
            return switch (category) {
                case CLOUD -> cloudLabel(lookup, sample);
                case SAAS -> saasLabel(lookup.credential, integrationId, sample);
                case ON_PREM -> sample.name();
                default -> sample.providerLabel();
            };
        } catch (MattersApiException e) {
            log.debug("[DSPM] no account label for integration {}: {}", integrationId, e.getReason());
            return null;
        } catch (RuntimeException e) {
            log.debug("[DSPM] no account label for integration {}: {}", integrationId, e.getClass().getSimpleName());
            return null;
        }
    }

    /** The XamOps account name alone, else the cloud account id, else (no account id) the provider label. */
    private String cloudLabel(Lookup lookup, Sample sample) {
        if (isBlank(sample.id())) {
            return null;
        }
        String accountId = text(apiClient.getAsset(lookup.credential, sample.id()).path("facet"), "cloud_account_id");
        if (isBlank(accountId)) {
            return sample.providerLabel();
        }
        String name = lookup.accountNames().get(accountId);
        return name != null ? name : accountId;
    }

    /**
     * {@code detail.account_name}, else the provider label. A {@code FORBIDDEN_SCOPE} here is a completed
     * lookup that yields the provider label (design 5.8); every other failure propagates to the
     * transient fallback.
     */
    private String saasLabel(MattersCredential credential, String integrationId, Sample sample) {
        try {
            String accountName = text(apiClient.saasAssetDetail(credential, sample.sourceId()).path("detail"),
                    "account_name");
            return isBlank(accountName) ? sample.providerLabel() : accountName;
        } catch (MattersApiException e) {
            if (e.getReason() != MattersApiException.Reason.FORBIDDEN_SCOPE) {
                throw e;
            }
            log.debug("[DSPM] account label for integration {}: {}, using the provider label",
                    integrationId, e.getReason());
            return sample.providerLabel();
        }
    }

    /**
     * Provider account id → the name the customer gave the account in XamOps, keyed like
     * {@link MattersIntegrationLabeler}: {@code CloudAccount.getProviderAccountId()} holds the AWS
     * account id, GCP project id or Azure subscription id that Matters reports as {@code cloud_account_id}.
     */
    private Map<String, String> xamOpsAccountNames(Long clientId) {
        Map<String, String> names = new LinkedHashMap<>();
        for (CloudAccount account : cloudAccountRepository.findByClientId(clientId)) {
            String providerId = account.getProviderAccountId();
            String name = account.getAccountName();
            if (!isBlank(providerId) && !isBlank(name)) {
                names.putIfAbsent(providerId, name);
            }
        }
        return names;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ── per-call state ───────────────────────────────────────────────────────────────────────────

    /** Finds the sample row of one integration. */
    @FunctionalInterface
    private interface SampleFinder {
        Sample find(String integrationId);
    }

    /** What a lookup needs from one asset of the integration. */
    private record Sample(String category, String id, String sourceId, String name, String providerLabel) {

        static Sample of(DatastoreRow row) {
            return new Sample(row.category(), row.id(), row.sourceId(), row.name(), row.source().providerLabel());
        }

        /** From a raw {@code /api/v1/assets} row. */
        static Sample of(JsonNode node) {
            return new Sample(text(node, "category"), text(node, "id"), text(node, "source_id"), text(node, "name"),
                    DatastoreLabels.providerLabel(text(node.path("source"), "provider")));
        }
    }

    /**
     * One call's state. The XamOps account names are read at most once, and only when a cloud
     * integration needs resolving; a failed read fails every cloud lookup of the call, so none of them
     * caches an account id where a name may exist.
     */
    private final class Lookup {

        private final MattersCredential credential;
        private Map<String, String> accountNames;
        private RuntimeException accountNamesFailure;

        private Lookup(MattersCredential credential) {
            this.credential = credential;
        }

        private Map<String, String> accountNames() {
            if (accountNamesFailure != null) {
                throw accountNamesFailure;
            }
            if (accountNames == null) {
                try {
                    accountNames = xamOpsAccountNames(credential.clientId());
                } catch (RuntimeException e) {
                    accountNamesFailure = e;
                    throw e;
                }
            }
            return accountNames;
        }
    }
}
