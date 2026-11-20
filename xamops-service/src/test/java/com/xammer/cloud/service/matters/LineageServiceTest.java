package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.LineageCategories;
import com.xammer.cloud.dto.matters.datastores.LineageDatastorePage;
import com.xammer.cloud.dto.matters.datastores.LineageFilePage;
import com.xammer.cloud.dto.matters.datastores.LineagePolicyPage;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Build 4 design 5.8 flows with a mocked resolver and {@link SaasFileService}, an in-memory cache, the real
 * mapper and a real {@link MattersApiClient} on a strict, ordered {@link MockRestServiceServer}. Synthetic
 * values only.
 */
class LineageServiceTest {

    private static final Long CLIENT = 10L;
    private static final String BASE = "https://matters.test";
    private static final String PROJECT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final MattersCredential CREDENTIAL =
            new MattersCredential(CLIENT, BASE, "synthetic-key", "k1", PROJECT_ID);
    private static final String POLICY_UUID = "0000000a-0000-4000-8000-00000000000b";
    private static final String EXPOSURE_ID = "exposure-one";
    private static final String FILE_ID = "fedcba9876543210fedcba98";

    private static final String TYPES = "datastore_types=%5B%22ONE_DRIVE%22,%22SHARE_POINT%22,%22ENDPOINT%22%5D";
    private static final String STATUSES = "statuses=%5B%22OPEN%22%5D";

    private static final String SNAPSHOT_BODY = """
            {"success": true, "data": {"filter_options": {
              "categories": [{"id": "STALE_DATA", "name": "Stale Data"}, {"id": "DATA_RESIDENCY"},
                             {"id": "SENSITIVE_FINDINGS", "name": "Sensitive Findings"}],
              "severities": [{"id": "critical", "name": "Critical"}, {"id": "high", "name": "High"},
                             {"id": "medium", "name": "Medium"}],
              "datastore_names": [{"id": "Lab Laptop"}, {"id": "Team Drive"}],
              "labels": [{"id": "PII"}, {"id": "PHI"}]
            }}}
            """;
    private static final String POLICY_BODY = """
            {"success": true, "data": {"count": 1, "policies": [{"ref_policy_id": "%s",
              "policy_name": "Synthetic policy", "severity": "MEDIUM", "target_service": "ENDPOINT",
              "total_exposures": 3}]}}
            """.formatted(POLICY_UUID);

    private MattersCredentialResolver resolver;
    private SaasFileService saasFileService;
    private MattersAuditService audit;
    private InMemoryRedisCache cache;
    private MockRestServiceServer server;
    private MattersApiClient apiClient;

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        when(resolver.resolve(CLIENT)).thenReturn(CREDENTIAL);
        saasFileService = mock(SaasFileService.class);
        audit = mock(MattersAuditService.class);
        cache = new InMemoryRedisCache(new ArrayList<>());
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        apiClient = new MattersApiClient(restTemplate, new ObjectMapper(), audit);
    }

    private LineageService service(boolean enabled) {
        return new LineageService(resolver, apiClient, new LineageMapper(), cache, saasFileService, enabled);
    }

    private ResponseActions expect(String url) {
        return server.expect(ExpectedCount.once(), requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("project-id", PROJECT_ID));
    }

    private void expectSnapshot() {
        expect(BASE + "/api/v1/exposure/filters-options")
                .andRespond(withSuccess(SNAPSHOT_BODY, MediaType.APPLICATION_JSON));
    }

    private static String summaryUrl(String category, int skip, int limit, String extra) {
        return BASE + "/api/v1/exposure/summary?categories=%5B%22" + category + "%22%5D&" + TYPES + "&" + STATUSES
                + "&skip=" + skip + "&limit=" + limit + extra;
    }

    private static String countBody(int count) {
        return "{\"success\": true, \"data\": {\"count\": " + count + ", \"policies\": []}}";
    }

    private static LineageFilters policyFilters(String... pairs) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.add(pairs[i], pairs[i + 1]);
        }
        return LineageFilters.parse(params, LineageFilters.POLICY_DIMENSIONS);
    }

    // ── switch off (AC4) ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("switch off: unavailable answers, no credential, no cache, no request, no audit, no SaaS call")
    void switchOff() {
        LineageService service = service(false);
        LineageRowQuery query = new LineageRowQuery("plan", 7, 7, policyFilters("severity", "HIGH"));

        assertThat(service.categories(CLIENT)).isEqualTo(LineageCategories.unavailable());
        assertThat(service.categories(CLIENT).categories()).extracting(LineageCategories.Category::name)
                .containsExactly("Sensitive Findings", "Excessive Exposure", "High Volume Access", "Stale Data",
                        "Risky User", "Public Exposure");
        assertThat(service.filterOptions(CLIENT, "policy").dimensions()).isEmpty();
        assertThat(service.policies(CLIENT, ExposureCategory.SENSITIVE_FINDINGS, query))
                .isEqualTo(LineagePolicyPage.unavailable(7, 7));
        assertThat(service.datastores(CLIENT, POLICY_UUID, query)).isEqualTo(LineageDatastorePage.unavailable(7, 7));
        assertThat(service.files(CLIENT, EXPOSURE_ID, 0, 7)).isEqualTo(LineageFilePage.unavailable(0, 7));
        assertThat(service.fileLineage(CLIENT, FILE_ID)).isEqualTo(SaasFileLineage.unavailable());

        server.verify();
        verifyNoInteractions(resolver, saasFileService, audit);
        assertThat(cache.events).isEmpty();
    }

    // ── switch on ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC6: categories reads the snapshot, then one count per known category in enum order; cached")
    void categoriesCallOrderAndCache() {
        LineageService service = service(true);
        expectSnapshot();
        expect(summaryUrl("SENSITIVE_FINDINGS", 0, 1, "")).andRespond(withSuccess(countBody(1), MediaType.APPLICATION_JSON));
        expect(summaryUrl("STALE_DATA", 0, 1, "")).andRespond(withSuccess(countBody(0), MediaType.APPLICATION_JSON));

        LineageCategories first = service.categories(CLIENT);
        LineageCategories second = service.categories(CLIENT);

        server.verify();
        assertThat(first.available()).isTrue();
        assertThat(first.categories()).containsExactly(
                new LineageCategories.Category("SENSITIVE_FINDINGS", "Sensitive Findings", 1L),
                new LineageCategories.Category("STALE_DATA", "Stale Data", 0L));
        assertThat(second).isEqualTo(first);
        assertThat(cache.events).containsExactly(
                "get dspm:lncats:10:all", "get dspm:lnopts:10:all", "put dspm:lnopts:10:all", "put dspm:lncats:10:all",
                "get dspm:lncats:10:all");
        assertThat(cache.putsFor("dspm:lnopts:10:all")).singleElement()
                .extracting(InMemoryRedisCache.Put::minutes).isEqualTo(5);
        assertThat(cache.putsFor("dspm:lncats:10:all")).singleElement()
                .extracting(InMemoryRedisCache.Put::minutes).isEqualTo(2);
        verify(resolver, org.mockito.Mockito.times(2)).resolve(CLIENT);
    }

    @Test
    @DisplayName("a failing count propagates and the categories are not cached")
    void failingCountCachesNothing() {
        LineageService service = service(true);
        expectSnapshot();
        expect(summaryUrl("SENSITIVE_FINDINGS", 0, 1, "")).andRespond(withSuccess(countBody(1), MediaType.APPLICATION_JSON));
        expect(summaryUrl("STALE_DATA", 0, 1, "")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> service.categories(CLIENT))
                .isInstanceOfSatisfying(MattersApiException.class, e ->
                        assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE));

        server.verify();
        assertThat(cache.contains("dspm:lncats:10:all")).isFalse();
    }

    @Test
    @DisplayName("filter options: policy has severity, datastoreName and label; datastore has severity only")
    void filterOptions() {
        LineageService service = service(true);
        expectSnapshot();

        DatastoreFilterOptions policy = service.filterOptions(CLIENT, "policy");
        DatastoreFilterOptions datastore = service.filterOptions(CLIENT, "datastore");

        server.verify();
        assertThat(policy.dimensions()).extracting(DatastoreFilterOptions.Dimension::key)
                .containsExactly("severity", "datastoreName", "label");
        assertThat(policy.dimensions().get(0).options()).containsExactly(
                new DatastoreFilterOptions.Option("CRITICAL", "Critical", null),
                new DatastoreFilterOptions.Option("HIGH", "High", null),
                new DatastoreFilterOptions.Option("MEDIUM", "Medium", null));
        assertThat(policy.dimensions()).allSatisfy(d -> assertThat(d.hasMore()).isFalse());
        assertThat(datastore.dimensions()).extracting(DatastoreFilterOptions.Dimension::key).containsExactly("severity");
    }

    @Test
    @DisplayName("policies without a filter: no snapshot, one summary call with search and paging, then cached")
    void policiesUnfiltered() {
        LineageService service = service(true);
        expect(summaryUrl("SENSITIVE_FINDINGS", 7, 7, "&search=plan%20b"))
                .andRespond(withSuccess(POLICY_BODY, MediaType.APPLICATION_JSON));
        LineageRowQuery query = new LineageRowQuery("plan b", 7, 7, LineageFilters.NONE);

        LineagePolicyPage page = service.policies(CLIENT, ExposureCategory.SENSITIVE_FINDINGS, query);
        LineagePolicyPage again = service.policies(CLIENT, ExposureCategory.SENSITIVE_FINDINGS, query);

        server.verify();
        assertThat(page.rows()).containsExactly(new LineagePolicyPage.Policy(POLICY_UUID, "Synthetic policy",
                "MEDIUM", "Medium", "ENDPOINT", 3L));
        assertThat(page.total()).isEqualTo(1);
        assertThat(again).isEqualTo(page);
        assertThat(cache.contains("dspm:lnopts:10:all")).isFalse();
        assertThat(cache.puts).singleElement().satisfies(put -> {
            assertThat(put.key()).isEqualTo(
                    DatastoreCacheKeys.lineagePolicies(CLIENT, ExposureCategory.SENSITIVE_FINDINGS, query));
            assertThat(put.minutes()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("include filters send the chosen options' vendor ids in snapshot order")
    void includeFilters() {
        LineageService service = service(true);
        expectSnapshot();
        expect(summaryUrl("STALE_DATA", 0, 7, "&severities=%5B%22critical%22,%22medium%22%5D"
                + "&datastore_names=%5B%22Team%20Drive%22%5D&labels=%5B%22PII%22,%22PHI%22%5D"))
                .andRespond(withSuccess(POLICY_BODY, MediaType.APPLICATION_JSON));

        service.policies(CLIENT, ExposureCategory.STALE_DATA, new LineageRowQuery(null, 0, 7,
                policyFilters("severity", "MEDIUM,CRITICAL", "datastoreName", "Team Drive", "label", "PHI,PII")));

        server.verify();
    }

    @Test
    @DisplayName("an Exclude is sent as the complement, in snapshot order")
    void excludeIsTheComplement() {
        LineageService service = service(true);
        expectSnapshot();
        expect(summaryUrl("STALE_DATA", 0, 7, "&severities=%5B%22critical%22,%22medium%22%5D"
                + "&datastore_names=%5B%22Lab%20Laptop%22%5D"))
                .andRespond(withSuccess(POLICY_BODY, MediaType.APPLICATION_JSON));

        service.policies(CLIENT, ExposureCategory.STALE_DATA, new LineageRowQuery(null, 0, 7,
                policyFilters("severity_exclude", "HIGH", "datastoreName_exclude", "Team Drive")));

        server.verify();
    }

    @Test
    @DisplayName("an Exclude of every option answers an empty page with no list call and nothing cached")
    void excludeAllIsEmpty() {
        LineageService service = service(true);
        expectSnapshot();

        LineagePolicyPage page = service.policies(CLIENT, ExposureCategory.STALE_DATA, new LineageRowQuery(null, 7, 7,
                policyFilters("label_exclude", "PII,PHI")));

        server.verify();
        assertThat(page).isEqualTo(LineagePolicyPage.empty(7, 7));
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key).containsExactly("dspm:lnopts:10:all");
    }

    @Test
    @DisplayName("a value that is not an option is a 400 on the parameter as sent, before any list call")
    void membershipIsCheckedFirst() {
        LineageService service = service(true);
        expectSnapshot();

        assertThatThrownBy(() -> service.policies(CLIENT, ExposureCategory.STALE_DATA, new LineageRowQuery(null, 0, 7,
                policyFilters("severity", "HIGH", "datastoreName_exclude", "Unknown Store"))))
                .isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
                    assertThat(e.parameter()).isEqualTo("datastoreName_exclude");
                    assertThat(e.getMessage()).isEqualTo("datastoreName_exclude is not an option of this project");
                });
        assertThatThrownBy(() -> service.datastores(CLIENT, POLICY_UUID, new LineageRowQuery(null, 0, 7,
                policyFilters("severity", "LOW"))))
                .isInstanceOfSatisfying(DspmBadRequestException.class, e ->
                        assertThat(e.parameter()).isEqualTo("severity"));

        server.verify();
    }

    @Test
    @DisplayName("L4: the policy's datastores with search and the severity filter")
    void datastores() {
        LineageService service = service(true);
        expectSnapshot();
        expect(BASE + "/api/v1/exposure/policy/" + POLICY_UUID + "/exposures?skip=0&limit=7&" + STATUSES
                + "&search=lab&severities=%5B%22high%22%5D")
                .andRespond(withSuccess("""
                        {"success": true, "count": 1, "data": [{"id": "exposure-one", "datastore_name": "Lab Laptop",
                          "datastore_service": "ENDPOINT", "affected_files_count": 9}]}
                        """, MediaType.APPLICATION_JSON));
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("severity", "HIGH");
        LineageRowQuery query = new LineageRowQuery("lab", 0, 7,
                LineageFilters.parse(params, LineageFilters.DATASTORE_DIMENSIONS));

        LineageDatastorePage page = service.datastores(CLIENT, POLICY_UUID, query);

        server.verify();
        assertThat(page.rows()).containsExactly(
                new LineageDatastorePage.Datastore(EXPOSURE_ID, "Lab Laptop", "ENDPOINT", 9L));
        assertThat(cache.contains(DatastoreCacheKeys.lineageDatastores(CLIENT, POLICY_UUID, query))).isTrue();
    }

    @Test
    @DisplayName("L5: one page of files, cached under the skip-limit key")
    void files() {
        LineageService service = service(true);
        expect(BASE + "/api/v1/exposure/" + EXPOSURE_ID + "/files?skip=7&limit=7")
                .andRespond(withSuccess("""
                        {"success": true, "data": {"total_files": 9, "files": [{"id": "%s", "name": "notes.pdf",
                          "owner": "owner-a", "last_modified_time": 1775000000}]}}
                        """.formatted(FILE_ID), MediaType.APPLICATION_JSON));

        LineageFilePage page = service.files(CLIENT, EXPOSURE_ID, 7, 7);
        LineageFilePage again = service.files(CLIENT, EXPOSURE_ID, 7, 7);

        server.verify();
        assertThat(page.total()).isEqualTo(9);
        assertThat(page.rows()).containsExactly(
                new LineageFilePage.File(FILE_ID, "notes.pdf", "pdf", "owner-a", 1775000000L));
        assertThat(again).isEqualTo(page);
        assertThat(cache.contains("dspm:lnfiles:10:" + EXPOSURE_ID + ":7-7")).isTrue();
    }

    @Test
    @DisplayName("L6 delegates to build 2's SaaS file lineage")
    void fileLineageDelegates() {
        SaasFileLineage lineage = new SaasFileLineage(true, null, List.of(), List.of());
        when(saasFileService.lineage(CLIENT, FILE_ID)).thenReturn(lineage);

        assertThat(service(true).fileLineage(CLIENT, FILE_ID)).isSameAs(lineage);

        verify(saasFileService).lineage(CLIENT, FILE_ID);
        server.verify();
    }
}
