package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilePage;
import com.xammer.cloud.dto.matters.datastores.DatastoreOccurrencePage;
import com.xammer.cloud.dto.matters.datastores.DatastoreSensitives;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Design 5.9 drawer flows, with the real mappers, a mocked client and an in-memory cache. Every vendor
 * body carries {@link #MARKER} wherever the real routes carry raw samples or config.
 */
class DatastoreDrawerServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String SOURCE_ID = "0123456789abcdef01234567";
    private static final String MARKER = "<<SYNTHETIC-SAMPLE-7F3A>>";

    private static final String OVERVIEW_BODY = """
            {"success":true,"inventories_details":{
              "scanning_status":"SCAN_STOPPED","canDiscoverSchema":false,
              "asset_details":{"url":"https://console.example.test/store-a","entitySamples":[{"value":"%1$s"}],
                               "owner":"%1$s"},
              "file_types":{"pdf_files":2,"xlsx_files":0},"total_files":2,
              "sensitive_records":{"sensitive_records_count":5,
                                   "label_wise_sensitive_records_count":{"PII":[{"entity_name":"PERSON NAME","count":5}]}},
              "properties":{"region":"eu-west-1","datastore_size":1024,"owner":"%1$s"},
              "security_configurations":{"default_encryption":"ENABLED"}}}
            """.formatted(MARKER);

    private static final String SENSITIVES_BODY = """
            {"success":true,
             "entity_level_response":[{"entity_name":"PERSON NAME","count":5,"labels":["PII"]},
                                      {"entity_name":"","count":0,"labels":[]}],
             "inventories_sensitives":[{"path":"s3a://store-a/reports/a.csv","sampled_data":[{"value":"%1$s"}]}],
             "file_level_sensitives_count":{
               "s3a://store-a/reports/a.csv":{"PERSON NAME":{"entityCount":5,"labels":["PII"],"columnName":"name",
                                              "reason":"%1$s","sampled_data":[{"value":"%1$s"}]}},
               "s3a://store-a/reports/b.csv":{"":{"entityCount":0,"labels":["PII"],"sampled_data":[]}}}}
            """.formatted(MARKER);

    private static final String OCCURRENCES_BODY = """
            {"success":true,"entity_level_response":{"entity_name":"PERSON NAME","total_sensitive_occurrences":5,
              "labels":["PII"],
              "data":[{"url":"s3a://store-a/reports/a.csv","is_false_positive":false,
                       "sampled_data":[{"value":"%1$s"},{"value":"<<Second Sample>>"}]}],
              "pagination":{"total":1,"skip":0,"limit":80}}}
            """.formatted(MARKER);

    private static final String RISKS_BODY = """
            {"success":true,"risk":[{"risk":"Bucket versioning is disabled","source":"upwind","severity":"medium",
              "description":"## Why","remediation":"Enable it","remediation_recommendation_code_cli":"aws s3api x",
              "policy_id":"p-1","learn_more":"https://example.test"}]}
            """;

    private static final String ACCESS_GRAPH_BODY = """
            {"success":true,"access_graph":{"type":"S3","name":"store-a","children":{
              "roles":[{"name":"role-a","read":true,"list":true}],"users":[],"groups":[]}}}
            """;

    private static final String SCAN_STATUS_BODY = "{\"success\":true,\"scan_progress\":42.5}";

    private static final String SCAN_HISTORY_BODY = """
            {"success":true,"scan_history":[{"id":"run-1","execution_number":1,"start_time":1700000000,
              "end_time":1700000100,"count_of_data_at_risk":5,"sensitivity_level":"CRITICAL_SENSITIVITY",
              "triggered_by":"user@example.com","status":"SCAN_STOPPED"}]}
            """;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private InMemoryRedisCache cache;
    private DatastoreDrawerService service;

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        cache = new InMemoryRedisCache(events);
        when(resolver.resolve(10L)).thenAnswer(inv -> {
            events.add("resolve");
            return CREDENTIAL;
        });
        when(apiClient.inventoryOverview(CREDENTIAL, SOURCE_ID)).thenAnswer(inv -> parse(OVERVIEW_BODY));
        when(apiClient.inventorySensitives(CREDENTIAL, SOURCE_ID)).thenAnswer(inv -> parse(SENSITIVES_BODY));
        when(apiClient.inventorySensitiveEntity(eq(CREDENTIAL), eq(SOURCE_ID), anyString(), anyInt(), anyInt(),
                anyBoolean())).thenAnswer(inv -> parse(OCCURRENCES_BODY));
        when(apiClient.inventoryIssues(CREDENTIAL, SOURCE_ID)).thenAnswer(inv -> parse(RISKS_BODY));
        when(apiClient.inventoryAccessGraph(CREDENTIAL, SOURCE_ID)).thenAnswer(inv -> parse(ACCESS_GRAPH_BODY));
        when(apiClient.scanStatus(CREDENTIAL, SOURCE_ID)).thenAnswer(inv -> parse(SCAN_STATUS_BODY));
        when(apiClient.scanHistory(CREDENTIAL, SOURCE_ID)).thenAnswer(inv -> parse(SCAN_HISTORY_BODY));
        service = new DatastoreDrawerService(resolver, apiClient, new DatastoreDrawerMapper(),
                new AccessGraphGrouper(), cache);
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every drawer method, keyed by the cache key it reads first. */
    private Map<String, Runnable> everyMethod() {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("dspm:dsoverview:10:" + SOURCE_ID, () -> service.overview(10L, SOURCE_ID));
        calls.put("dspm:dsscan:10:" + SOURCE_ID, () -> service.scanProgress(10L, SOURCE_ID));
        calls.put("dspm:dssens:10:" + SOURCE_ID, () -> service.sensitives(10L, SOURCE_ID));
        calls.put("dspm:dsfiles:10:" + SOURCE_ID, () -> service.files(10L, SOURCE_ID, null, 1, 10));
        calls.put("dspm:dsrisks:10:" + SOURCE_ID, () -> service.risks(10L, SOURCE_ID));
        calls.put("dspm:dsaccess:10:" + SOURCE_ID, () -> service.accessGraph(10L, SOURCE_ID));
        calls.put("dspm:dshistory:10:" + SOURCE_ID, () -> service.scanHistory(10L, SOURCE_ID));
        return calls;
    }

    // ── order of checks ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the credential is resolved before any cache read, on every method")
    void credentialFirst() {
        everyMethod().forEach((key, call) -> {
            events.clear();
            call.run();
            assertThat(events).as(key).startsWith("resolve", "get " + key);
        });

        events.clear();
        service.occurrences(10L, SOURCE_ID, "PERSON NAME", 0, 10, false);
        assertThat(events).startsWith("resolve", "get dspm:dssens:10:" + SOURCE_ID);
    }

    @Test
    @DisplayName("NOT_ENTITLED stops every method before the cache and the vendor")
    void notEntitled() {
        doThrow(new MattersApiException(MattersApiException.Reason.NOT_ENTITLED, "not entitled"))
                .when(resolver).resolve(10L);

        everyMethod().forEach((key, call) -> assertThatThrownBy(call::run).as(key)
                .isInstanceOf(MattersApiException.class));
        assertThatThrownBy(() -> service.occurrences(10L, SOURCE_ID, "PERSON NAME", 0, 10, false))
                .isInstanceOf(MattersApiException.class);

        verifyNoInteractions(apiClient);
        assertThat(cache.gets).isEmpty();
    }

    // ── caching ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("each tab is cached under the client's key with its TTL, and a hit makes no vendor call")
    void tabsAreCached() {
        for (int round = 0; round < 2; round++) {
            service.overview(10L, SOURCE_ID);
            service.scanProgress(10L, SOURCE_ID);
            service.risks(10L, SOURCE_ID);
            service.accessGraph(10L, SOURCE_ID);
            service.scanHistory(10L, SOURCE_ID);
        }

        verify(apiClient, times(1)).inventoryOverview(CREDENTIAL, SOURCE_ID);
        verify(apiClient, times(1)).scanStatus(CREDENTIAL, SOURCE_ID);
        verify(apiClient, times(1)).inventoryIssues(CREDENTIAL, SOURCE_ID);
        verify(apiClient, times(1)).inventoryAccessGraph(CREDENTIAL, SOURCE_ID);
        verify(apiClient, times(1)).scanHistory(CREDENTIAL, SOURCE_ID);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes).containsExactly(
                tuple("dspm:dsoverview:10:" + SOURCE_ID, 2),
                tuple("dspm:dsscan:10:" + SOURCE_ID, 1),
                tuple("dspm:dsrisks:10:" + SOURCE_ID, 5),
                tuple("dspm:dsaccess:10:" + SOURCE_ID, 5),
                tuple("dspm:dshistory:10:" + SOURCE_ID, 2));
    }

    @Test
    @DisplayName("one sensitives call writes the snapshot and the File View rows, which files() then reads")
    void sensitivesWriteBothEntries() {
        DatastoreSensitives sensitives = service.sensitives(10L, SOURCE_ID);
        DatastoreFilePage files = service.files(10L, SOURCE_ID, null, 1, 10);

        verify(apiClient, times(1)).inventorySensitives(CREDENTIAL, SOURCE_ID);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes).containsExactly(
                tuple("dspm:dssens:10:" + SOURCE_ID, 5),
                tuple("dspm:dsfiles:10:" + SOURCE_ID, 5));
        assertThat(sensitives.entities()).extracting(DatastoreSensitives.Entity::name).containsExactly("PERSON NAME", "");
        assertThat(files.rows()).extracting(DatastoreFilePage.FileRow::name).containsExactly("a.csv", "b.csv");
        assertThat(files.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("the File View loads both entries on a miss, then pages and searches the cached rows")
    void filesLoadOnAMiss() {
        DatastoreFilePage searched = service.files(10L, SOURCE_ID, "B.CSV", 1, 10);
        service.sensitives(10L, SOURCE_ID);

        verify(apiClient, times(1)).inventorySensitives(CREDENTIAL, SOURCE_ID);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key)
                .containsExactly("dspm:dssens:10:" + SOURCE_ID, "dspm:dsfiles:10:" + SOURCE_ID);
        assertThat(searched.rows()).extracting(DatastoreFilePage.FileRow::path)
                .containsExactly("s3a://store-a/reports/b.csv");
        assertThat(searched.total()).isEqualTo(1);
    }

    // ── occurrences ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("occurrences never read or write a cache entry of their own: only the snapshot is read")
    void occurrencesAreNeverCached() {
        service.sensitives(10L, SOURCE_ID);
        cache.gets.clear();
        cache.puts.clear();

        service.occurrences(10L, SOURCE_ID, "PERSON NAME", 0, 10, false);
        service.occurrences(10L, SOURCE_ID, "PERSON NAME", 0, 10, false);

        verify(apiClient, times(2)).inventorySensitiveEntity(CREDENTIAL, SOURCE_ID, "PERSON NAME", 0, 10, true);
        assertThat(cache.puts).isEmpty();
        assertThat(cache.gets).containsOnly("dspm:dssens:10:" + SOURCE_ID);
    }

    @Test
    @DisplayName("on a snapshot miss, occurrences fetch the sensitives route and write only its two entries")
    void occurrencesFetchTheSnapshotOnAMiss() {
        DatastoreOccurrencePage page = service.occurrences(10L, SOURCE_ID, "PERSON NAME", 20, 10, false);

        verify(apiClient, times(1)).inventorySensitives(CREDENTIAL, SOURCE_ID);
        verify(apiClient, times(1)).inventorySensitiveEntity(CREDENTIAL, SOURCE_ID, "PERSON NAME", 20, 10, true);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key)
                .containsExactly("dspm:dssens:10:" + SOURCE_ID, "dspm:dsfiles:10:" + SOURCE_ID);
        assertThat(page.skip()).isEqualTo(20);
        assertThat(page.limit()).isEqualTo(10);
    }

    @Test
    @DisplayName("an unknown entity is a 400 before any occurrences call, with the snapshot cached or fetched")
    void unknownEntityIsRejected() {
        assertThatThrownBy(() -> service.occurrences(10L, SOURCE_ID, "EMAIL ADDRESS", 0, 10, false))
                .isInstanceOfSatisfying(DspmBadRequestException.class,
                        e -> assertThat(e.parameter()).isEqualTo("entityName"));
        verify(apiClient, times(1)).inventorySensitives(CREDENTIAL, SOURCE_ID);

        assertThatThrownBy(() -> service.occurrences(10L, SOURCE_ID, "EMAIL ADDRESS", 0, 10, false))
                .isInstanceOf(DspmBadRequestException.class);
        verify(apiClient, times(1)).inventorySensitives(CREDENTIAL, SOURCE_ID);

        verify(apiClient, never()).inventorySensitiveEntity(eq(CREDENTIAL), anyString(), anyString(), anyInt(),
                anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("isFalsePositive=false is sent only while false positives are hidden")
    void falsePositivesHiddenUnlessShown() {
        service.occurrences(10L, SOURCE_ID, "PERSON NAME", 0, 10, false);
        service.occurrences(10L, SOURCE_ID, "PERSON NAME", 0, 10, true);

        verify(apiClient).inventorySensitiveEntity(CREDENTIAL, SOURCE_ID, "PERSON NAME", 0, 10, true);
        verify(apiClient).inventorySensitiveEntity(CREDENTIAL, SOURCE_ID, "PERSON NAME", 0, 10, false);
    }

    // ── no sample in the cache ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no cached value holds a sample, while the occurrence response carries it raw")
    void noSampleIsEverCached() {
        everyMethod().values().forEach(Runnable::run);
        DatastoreOccurrencePage occurrences = service.occurrences(10L, SOURCE_ID, "PERSON NAME", 0, 10, true);

        assertThat(cache.puts).isNotEmpty()
                .allSatisfy(put -> assertThat(put.json()).as(put.key()).doesNotContain(MARKER));
        assertThat(occurrences.rows().get(0).samples()).containsExactly(MARKER, "<<Second Sample>>");
    }
}
