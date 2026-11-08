package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Design 5.8 against a mocked client and an in-memory cache. All names and ids are invented. */
class DatastoreAccountLabelerTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String LABELS_KEY = "dspm:dsacct:10:labels";
    private static final String AWS_ACCOUNT = "111122223333";

    private final ObjectMapper json = new ObjectMapper();
    private final DatastoreRowMapper rowMapper = new DatastoreRowMapper();

    private MattersApiClient apiClient;
    private CloudAccountRepository cloudAccounts;
    private InMemoryRedisCache cache;
    private DatastoreAccountLabeler labeler;

    @BeforeEach
    void setUp() {
        apiClient = mock(MattersApiClient.class);
        cloudAccounts = mock(CloudAccountRepository.class);
        cache = new InMemoryRedisCache();
        labeler = new DatastoreAccountLabeler(apiClient, cloudAccounts, cache);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String integration(int n) {
        return String.format("%024d", n);
    }

    private static String sourceId(int n) {
        return String.format("5%023d", n);
    }

    /** A list row of one integration; {@code integrationId} null gives {@code source.account: null}. */
    private static String row(int n, String category, String provider, String integrationId, String name) {
        return """
                {"id":"asset-%d","source_id":"%s","name":"%s","category":"%s","subtype":"%s",
                 "risk":{"level":"LOW","score":1.0},"sensitives":{"record_count":3,"types":["PII"]},
                 "source":{"account":%s,"provider":"%s","region":"eu-west-1"},
                 "scan_status":{"status":"CLASSIFIED","last_scanned_at":1700000000}}
                """.formatted(n, sourceId(n), name, category, provider,
                integrationId == null ? "null" : "\"" + integrationId + "\"", provider);
    }

    private List<DatastoreRowMapper.MappedRow> rows(String... rows) {
        return rowMapper.toMappedRows(parse("{\"data\":[" + String.join(",", rows) + "]}"));
    }

    private static List<String> accountLabels(List<DatastoreRow> rows) {
        return rows.stream().map(r -> r.source().accountLabel()).toList();
    }

    private JsonNode assetWithAccount(String cloudAccountId) {
        return parse(cloudAccountId == null
                ? "{\"id\":\"asset-1\",\"facet\":{}}"
                : "{\"id\":\"asset-1\",\"facet\":{\"cloud_account_id\":\"" + cloudAccountId + "\"}}");
    }

    private JsonNode saasDetail(String accountName) {
        return parse("{\"success\":true,\"detail\":{\"account_name\":\"" + accountName + "\"}}");
    }

    private static CloudAccount awsAccount(String accountId, String name) {
        CloudAccount account = new CloudAccount();
        account.setProvider("AWS");
        account.setAwsAccountId(accountId);
        account.setAccountName(name);
        return account;
    }

    private static MattersApiException failure(MattersApiException.Reason reason) {
        return new MattersApiException(reason, "synthetic failure");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> cachedLabels() {
        List<InMemoryRedisCache.Put> puts = cache.putsFor(LABELS_KEY);
        if (puts.isEmpty()) {
            return Map.of();
        }
        try {
            return json.readValue(puts.get(puts.size() - 1).json(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── rows ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cloud: the XamOps account name alone, read from the asset's cloud account id")
    void cloudUsesTheXamOpsName() {
        when(apiClient.getAsset(CREDENTIAL, "asset-1")).thenReturn(assetWithAccount(AWS_ACCOUNT));
        when(cloudAccounts.findByClientId(10L)).thenReturn(List.of(awsAccount(AWS_ACCOUNT, "Synthetic-prod")));

        List<DatastoreRow> labelled = labeler.label(CREDENTIAL, rows(row(1, "cloud", "S3", integration(1), "store-a")));

        assertThat(accountLabels(labelled)).containsExactly("Synthetic-prod");
    }

    @Test
    @DisplayName("cloud: an account XamOps does not hold shows the cloud account id")
    void cloudFallsBackToTheAccountId() {
        when(apiClient.getAsset(CREDENTIAL, "asset-1")).thenReturn(assetWithAccount(AWS_ACCOUNT));
        when(cloudAccounts.findByClientId(10L)).thenReturn(List.of(awsAccount("999988887777", "Other")));

        List<DatastoreRow> labelled = labeler.label(CREDENTIAL, rows(row(1, "cloud", "S3", integration(1), "store-a")));

        assertThat(accountLabels(labelled)).containsExactly(AWS_ACCOUNT);
    }

    @Test
    @DisplayName("SaaS: detail.account_name, one detail call per integration on the page")
    void saasUsesTheAccountName() {
        when(apiClient.saasAssetDetail(CREDENTIAL, sourceId(1))).thenReturn(saasDetail("synthetic-workspace"));

        List<DatastoreRow> labelled = labeler.label(CREDENTIAL, rows(
                row(1, "saas", "GOOGLE_WORKSPACE", integration(7), "Mailbox One"),
                row(2, "saas", "GOOGLE_WORKSPACE", integration(7), "Mailbox Two")));

        assertThat(accountLabels(labelled)).containsExactly("synthetic-workspace", "synthetic-workspace");
        verify(apiClient, times(1)).saasAssetDetail(eq(CREDENTIAL), anyString());
        verifyNoInteractions(cloudAccounts);
    }

    @Test
    @DisplayName("on-prem rows show their own name and endpoint rows none, with no lookup")
    void onPremAndEndpointNeedNoLookup() {
        List<DatastoreRow> labelled = labeler.label(CREDENTIAL, rows(
                row(1, "on_prem", "SELF_HOSTED_POSTGRES", integration(3), "orders_db"),
                row(2, "endpoint", "ENDPOINT_AGENT", null, "Laptop One")));

        assertThat(accountLabels(labelled)).containsExactly("orders_db", null);
        verifyNoInteractions(apiClient, cloudAccounts);
        assertThat(cache.gets).isEmpty();
    }

    @Test
    @DisplayName("an unknown category shows the provider label")
    void otherCategoriesShowTheProvider() {
        List<DatastoreRow> labelled = labeler.label(CREDENTIAL, rows(row(1, "warehouse", "BIGQUERY", integration(4), "x")));

        assertThat(accountLabels(labelled)).containsExactly("BigQuery");
        verifyNoInteractions(apiClient);
    }

    @Test
    @DisplayName("everything else on the row is kept")
    void keepsTheRestOfTheRow() {
        when(apiClient.saasAssetDetail(CREDENTIAL, sourceId(1))).thenReturn(saasDetail("synthetic-workspace"));
        List<DatastoreRowMapper.MappedRow> mapped = rows(row(1, "saas", "GMAIL", integration(7), "Mailbox One"));

        DatastoreRow labelled = labeler.label(CREDENTIAL, mapped).get(0);
        DatastoreRow original = mapped.get(0).row();

        assertThat(labelled).usingRecursiveComparison().ignoringFields("source.accountLabel").isEqualTo(original);
        assertThat(labelled.source().accountLabel()).isEqualTo("synthetic-workspace");
    }

    // ── caching ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("labels live in one 12-hour entry dspm:dsacct:<c>:labels and are reused")
    void labelsAreCachedForTwelveHours() {
        when(apiClient.saasAssetDetail(CREDENTIAL, sourceId(1))).thenReturn(saasDetail("synthetic-workspace"));
        String page = row(1, "saas", "GMAIL", integration(7), "Mailbox One");

        labeler.label(CREDENTIAL, rows(page));
        labeler.label(CREDENTIAL, rows(page));

        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key).containsExactly(LABELS_KEY);
        assertThat(cache.puts.get(0).minutes()).isEqualTo(12 * 60);
        assertThat(cachedLabels()).containsExactly(Map.entry(integration(7), "synthetic-workspace"));
        verify(apiClient, times(1)).saasAssetDetail(any(), anyString());
    }

    @Test
    @DisplayName("a failed lookup falls back to the provider label and caches nothing: two loads, two calls")
    void transientFailureIsNotCached() {
        when(apiClient.saasAssetDetail(CREDENTIAL, sourceId(1)))
                .thenThrow(failure(MattersApiException.Reason.UPSTREAM_UNAVAILABLE));
        String page = row(1, "saas", "GOOGLE_WORKSPACE", integration(7), "Mailbox One");

        assertThat(accountLabels(labeler.label(CREDENTIAL, rows(page)))).containsExactly("Google Workspace");
        assertThat(accountLabels(labeler.label(CREDENTIAL, rows(page)))).containsExactly("Google Workspace");

        verify(apiClient, times(2)).saasAssetDetail(CREDENTIAL, sourceId(1));
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("FORBIDDEN_SCOPE on the SaaS detail route caches the provider label: two loads, one call")
    void forbiddenScopeIsCached() {
        when(apiClient.saasAssetDetail(CREDENTIAL, sourceId(1)))
                .thenThrow(failure(MattersApiException.Reason.FORBIDDEN_SCOPE));
        String page = row(1, "saas", "GOOGLE_WORKSPACE", integration(7), "Mailbox One");

        assertThat(accountLabels(labeler.label(CREDENTIAL, rows(page)))).containsExactly("Google Workspace");
        assertThat(accountLabels(labeler.label(CREDENTIAL, rows(page)))).containsExactly("Google Workspace");

        verify(apiClient, times(1)).saasAssetDetail(CREDENTIAL, sourceId(1));
        assertThat(cachedLabels()).containsExactly(Map.entry(integration(7), "Google Workspace"));
    }

    @Test
    @DisplayName("a FORBIDDEN_SCOPE on the asset lookup of a cloud row is transient: nothing cached")
    void forbiddenScopeOnTheAssetLookupIsNotCached() {
        when(apiClient.getAsset(CREDENTIAL, "asset-1")).thenThrow(failure(MattersApiException.Reason.FORBIDDEN_SCOPE));

        List<DatastoreRow> labelled = labeler.label(CREDENTIAL, rows(row(1, "cloud", "S3", integration(1), "store-a")));

        assertThat(accountLabels(labelled)).containsExactly("S3");
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("a completed lookup is cached even when it yields the fallback")
    void completedFallbackIsCached() {
        when(apiClient.getAsset(CREDENTIAL, "asset-1")).thenReturn(assetWithAccount(null));
        String page = row(1, "cloud", "AZURE_BLOB", integration(2), "container-a");

        assertThat(accountLabels(labeler.label(CREDENTIAL, rows(page)))).containsExactly("Azure Blob");
        assertThat(accountLabels(labeler.label(CREDENTIAL, rows(page)))).containsExactly("Azure Blob");

        verify(apiClient, times(1)).getAsset(CREDENTIAL, "asset-1");
        verifyNoInteractions(cloudAccounts);
        assertThat(cachedLabels()).containsExactly(Map.entry(integration(2), "Azure Blob"));
    }

    @Test
    @DisplayName("a failed read of the XamOps accounts caches nothing for the cloud integrations of that call")
    void accountReadFailureIsNotCached() {
        when(apiClient.getAsset(eq(CREDENTIAL), anyString())).thenReturn(assetWithAccount(AWS_ACCOUNT));
        when(cloudAccounts.findByClientId(10L)).thenThrow(new IllegalStateException("database unavailable"));

        List<DatastoreRow> labelled = labeler.label(CREDENTIAL, rows(
                row(1, "cloud", "S3", integration(1), "store-a"),
                row(2, "cloud", "EBS", integration(2), "volume-b")));

        assertThat(accountLabels(labelled)).containsExactly("S3", "EBS");
        verify(cloudAccounts, times(1)).findByClientId(10L);
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("at most 25 integrations are resolved per call; the rest get the provider label")
    void lookupsAreBounded() {
        when(apiClient.saasAssetDetail(eq(CREDENTIAL), anyString()))
                .thenAnswer(inv -> saasDetail("account-" + inv.getArgument(1, String.class)));
        List<String> page = new ArrayList<>();
        IntStream.rangeClosed(1, 30).forEach(n -> page.add(row(n, "saas", "GMAIL", integration(n), "Mailbox " + n)));

        List<DatastoreRow> labelled = labeler.label(CREDENTIAL, rows(page.toArray(String[]::new)));

        verify(apiClient, times(25)).saasAssetDetail(eq(CREDENTIAL), anyString());
        assertThat(accountLabels(labelled).subList(0, 25)).allMatch(label -> label.startsWith("account-"));
        assertThat(accountLabels(labelled).subList(25, 30)).containsOnly("Gmail");
        assertThat(cachedLabels()).hasSize(25);
    }

    // ── filter options ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("options resolve through a one-row sample of each integration, keeping values and counts")
    void optionsResolveThroughASample() {
        when(apiClient.listAssets(eq(CREDENTIAL), any())).thenAnswer(inv -> {
            Map<String, List<String>> query = inv.getArgument(1);
            String integrationId = query.get("integration_id").get(0);
            assertThat(query.get("page_size")).containsExactly("1");
            if (integrationId.equals(integration(1))) {
                return parse("{\"data\":[" + row(1, "cloud", "S3", integration(1), "store-a") + "]}");
            }
            if (integrationId.equals(integration(2))) {
                return parse("{\"data\":[" + row(2, "saas", "GMAIL", integration(2), "Mailbox") + "]}");
            }
            if (integrationId.equals(integration(3))) {
                return parse("{\"data\":[" + row(3, "on_prem", "SELF_HOSTED_POSTGRES", integration(3), "orders_db") + "]}");
            }
            return parse("{\"data\":[]}");
        });
        when(apiClient.getAsset(CREDENTIAL, "asset-1")).thenReturn(assetWithAccount(AWS_ACCOUNT));
        when(cloudAccounts.findByClientId(10L)).thenReturn(List.of(awsAccount(AWS_ACCOUNT, "Synthetic-prod")));
        when(apiClient.saasAssetDetail(CREDENTIAL, sourceId(2))).thenReturn(saasDetail("synthetic-workspace"));

        DatastoreFilterOptions options = new DatastoreFilterOptions(List.of(
                new DatastoreFilterOptions.Dimension("provider", List.of(
                        new DatastoreFilterOptions.Option("S3", "S3", 4L)), false),
                new DatastoreFilterOptions.Dimension("integration_id", List.of(
                        new DatastoreFilterOptions.Option(integration(1), integration(1), 4L),
                        new DatastoreFilterOptions.Option(integration(2), integration(2), 2L),
                        new DatastoreFilterOptions.Option(integration(3), integration(3), 1L),
                        new DatastoreFilterOptions.Option(integration(9), integration(9), null)), true)));

        DatastoreFilterOptions labelled = labeler.label(CREDENTIAL, options);

        assertThat(labelled.dimensions().get(0)).isEqualTo(options.dimensions().get(0));
        DatastoreFilterOptions.Dimension integrations = labelled.dimensions().get(1);
        assertThat(integrations.hasMore()).isTrue();
        assertThat(integrations.options()).containsExactly(
                new DatastoreFilterOptions.Option(integration(1), "Synthetic-prod", 4L),
                new DatastoreFilterOptions.Option(integration(2), "synthetic-workspace", 2L),
                new DatastoreFilterOptions.Option(integration(3), "orders_db", 1L),
                // No sample found: the raw id stays, and nothing is cached for it.
                new DatastoreFilterOptions.Option(integration(9), integration(9), null));
        assertThat(cachedLabels()).containsOnlyKeys(integration(1), integration(2), integration(3));
    }

    @Test
    @DisplayName("an option whose sample lookup fails keeps its raw id and is not cached")
    void optionFailureKeepsTheRawId() {
        when(apiClient.listAssets(eq(CREDENTIAL), any())).thenThrow(failure(MattersApiException.Reason.FORBIDDEN_SCOPE));
        DatastoreFilterOptions options = new DatastoreFilterOptions(List.of(
                new DatastoreFilterOptions.Dimension("integration_id", List.of(
                        new DatastoreFilterOptions.Option(integration(1), integration(1), 4L)), false)));

        DatastoreFilterOptions labelled = labeler.label(CREDENTIAL, options);

        assertThat(labelled).isEqualTo(options);
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("options already labelled in the cache need no call; facets without the dimension are untouched")
    void cachedOptionsNeedNoCall() {
        cache.seed(LABELS_KEY, Map.of(integration(1), "Synthetic-prod"));
        DatastoreFilterOptions options = new DatastoreFilterOptions(List.of(
                new DatastoreFilterOptions.Dimension("integration_id", List.of(
                        new DatastoreFilterOptions.Option(integration(1), integration(1), 4L)), false)));
        DatastoreFilterOptions noIntegrations = new DatastoreFilterOptions(List.of(
                new DatastoreFilterOptions.Dimension("provider", List.of(
                        new DatastoreFilterOptions.Option("S3", "S3", 4L)), false)));

        assertThat(labeler.label(CREDENTIAL, options).dimensions().get(0).options())
                .containsExactly(new DatastoreFilterOptions.Option(integration(1), "Synthetic-prod", 4L));
        assertThat(labeler.label(CREDENTIAL, noIntegrations)).isSameAs(noIntegrations);
        verifyNoInteractions(apiClient);
        assertThat(cache.puts).isEmpty();
    }
}
