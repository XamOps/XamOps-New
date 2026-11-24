package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.EndpointOccurrencePage;
import com.xammer.cloud.dto.matters.datastores.EndpointSummary;
import com.xammer.cloud.dto.matters.datastores.EndpointSummarySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
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
 * Design 5.8 Endpoint drawer flows and review fix R5, with the real mapper, a mocked client and an
 * in-memory cache. Every vendor body carries {@link #MARKER} wherever the real routes carry raw samples.
 */
class EndpointDrawerServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String AGENT_ID = "0123456789abcdef01234567";
    private static final String MARKER = "<<SYNTHETIC-SAMPLE-3E8C>>";
    private static final String SUMMARY_KEY = "dspm:dsepsum:10:" + AGENT_ID;

    private static final String OVERVIEW_BODY = """
            {"success": true, "data": {"device_serial_number": "SER-0001", "agent_status": "ACTIVE",
              "hostname": "Robin Laptop", "users": ["Robin"], "local_ip": "%1$s", "email_id": "%1$s"}}
            """.formatted(MARKER);

    private static final String FILTER_OPTIONS_BODY = """
            {"success": true, "data": {"sensitives": ["PII"], "subcategories": ["system documentation"],
              "categories": ["it & technical"], "dataElements": ["EMAIL ADDRESS"]}}
            """;

    private static final String SENSITIVE_FILES_BODY = """
            {"success": true, "data": {"total_files": 12, "extension_counts": {"pdf": 2, "md": 1},
              "label_wise_sensitive_entities": {"PII": [{"entity_name": "EMAIL ADDRESS", "count": 4}]},
              "category_subcategory_counts": {"it & technical": {"system documentation": 3}},
              "total_sensitives_count": 6,
              "sampleMatches": [{"name": "EMAIL ADDRESS", "samples": [{"match_text": "%1$s"}]}],
              "sampled_data": ["%1$s"],
              "unified_metrics": [
                {"label": "PII", "entity_name": "EMAIL ADDRESS", "category": "", "subcategory": "", "count": 4},
                {"label": "PII", "entity_name": "PERSON NAME", "category": "", "subcategory": "", "count": 2},
                {"label": "", "entity_name": "", "category": "it & technical", "subcategory": "system documentation",
                 "count": 3}]}}
            """.formatted(MARKER);

    private static final String ENTITY_BODY = """
            {"success": true, "data": [{"name": "notes.txt", "path": "/Users/synthetic/notes.txt", "tags": [],
              "isFalsePositive": false,
              "sampleMatches": [{"name": "EMAIL ADDRESS", "samples": [{"match_text": "%1$s"}]}]}],
             "total": 1}
            """.formatted(MARKER);

    private static final String FILES_BODY = """
            {"success": true, "data": [{"name": "plan.md", "extension": "md", "path": "/Users/synthetic/plan.md",
              "sensitiveEntitiesCount": 1, "labels": ["PII"], "category": "it & technical",
              "sampleMatches": [{"name": "EMAIL ADDRESS", "samples": [{"match_text": "%1$s"}]}],
              "sensitiveEntities": [{"name": "%1$s"}]}],
             "total": 1}
            """.formatted(MARKER);

    private static final String SCANS_BODY = """
            {"success": true, "data": [{"status": "completed", "start_time": 1789725004, "end_time": 1789725300,
              "sensitive_files": 3, "total_files": 10, "extension_counts": {"%1$s": 1}}], "count": 1}
            """.formatted(MARKER);

    private static final EndpointFilters FILTERS = EndpointFilters.parse(params("documentClass", "system documentation"));
    private static final EndpointFileQuery FILE_QUERY = new EndpointFileQuery(FILTERS, "plan", 0, 10);

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private EndpointDrawerMapper mapper;
    private InMemoryRedisCache cache;
    private EndpointDrawerService service;

    private static MultiValueMap<String, String> params(String... pairs) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.add(pairs[i], pairs[i + 1]);
        }
        return params;
    }

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        mapper = new EndpointDrawerMapper();
        cache = new InMemoryRedisCache(events);
        when(resolver.resolve(10L)).thenAnswer(inv -> {
            events.add("resolve");
            return CREDENTIAL;
        });
        when(apiClient.agentOverview(CREDENTIAL, AGENT_ID)).thenAnswer(inv -> parse(OVERVIEW_BODY));
        when(apiClient.agentFilterOptions(CREDENTIAL, AGENT_ID)).thenAnswer(inv -> parse(FILTER_OPTIONS_BODY));
        when(apiClient.agentSensitiveFiles(eq(CREDENTIAL), eq(AGENT_ID), any(EndpointFilters.class), anyBoolean()))
                .thenAnswer(inv -> parse(SENSITIVE_FILES_BODY));
        when(apiClient.agentEntity(eq(CREDENTIAL), eq(AGENT_ID), anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenAnswer(inv -> parse(ENTITY_BODY));
        when(apiClient.agentFiles(eq(CREDENTIAL), eq(AGENT_ID), any(EndpointFileQuery.class)))
                .thenAnswer(inv -> parse(FILES_BODY));
        when(apiClient.agentScans(eq(CREDENTIAL), eq(AGENT_ID), anyInt(), anyInt()))
                .thenAnswer(inv -> parse(SCANS_BODY));
        service = new EndpointDrawerService(resolver, apiClient, mapper, cache);
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every cached method, keyed by the cache key it reads first. */
    private Map<String, Runnable> everyMethod() {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("dspm:dsep:10:" + AGENT_ID, () -> service.overview(10L, AGENT_ID));
        calls.put("dspm:dsepopts:10:" + AGENT_ID, () -> service.filterOptions(10L, AGENT_ID));
        calls.put(SUMMARY_KEY, () -> service.summary(10L, AGENT_ID));
        calls.put(DatastoreCacheKeys.endpointMetrics(10L, AGENT_ID, FILTERS, false),
                () -> service.sensitives(10L, AGENT_ID, FILTERS, false));
        calls.put(DatastoreCacheKeys.endpointFiles(10L, AGENT_ID, FILE_QUERY),
                () -> service.files(10L, AGENT_ID, FILE_QUERY));
        calls.put(DatastoreCacheKeys.endpointScans(10L, AGENT_ID, 0, 10), () -> service.scans(10L, AGENT_ID, 0, 10));
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
        service.occurrences(10L, AGENT_ID, "EMAIL ADDRESS", 0, 10, false);
        assertThat(events).startsWith("resolve", "get " + SUMMARY_KEY);
    }

    @Test
    @DisplayName("NOT_ENTITLED stops every method before the cache and the vendor")
    void notEntitled() {
        doThrow(new MattersApiException(MattersApiException.Reason.NOT_ENTITLED, "not entitled"))
                .when(resolver).resolve(10L);

        everyMethod().forEach((key, call) -> assertThatThrownBy(call::run).as(key)
                .isInstanceOf(MattersApiException.class));
        assertThatThrownBy(() -> service.occurrences(10L, AGENT_ID, "EMAIL ADDRESS", 0, 10, false))
                .isInstanceOf(MattersApiException.class);

        verifyNoInteractions(apiClient);
        assertThat(cache.gets).isEmpty();
    }

    // ── caching ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("each view is cached under the client's key with its TTL, and a hit makes no vendor call")
    void viewsAreCached() {
        for (int round = 0; round < 2; round++) {
            everyMethod().values().forEach(Runnable::run);
        }

        verify(apiClient, times(1)).agentOverview(CREDENTIAL, AGENT_ID);
        verify(apiClient, times(1)).agentFilterOptions(CREDENTIAL, AGENT_ID);
        verify(apiClient, times(1)).agentSensitiveFiles(CREDENTIAL, AGENT_ID, EndpointFilters.NONE, false);
        verify(apiClient, times(1)).agentSensitiveFiles(CREDENTIAL, AGENT_ID, FILTERS, true);
        verify(apiClient, times(1)).agentFiles(CREDENTIAL, AGENT_ID, FILE_QUERY);
        verify(apiClient, times(1)).agentScans(CREDENTIAL, AGENT_ID, 0, 10);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes).containsExactly(
                tuple("dspm:dsep:10:" + AGENT_ID, 2),
                tuple("dspm:dsepopts:10:" + AGENT_ID, 5),
                tuple(SUMMARY_KEY, 5),
                tuple(DatastoreCacheKeys.endpointMetrics(10L, AGENT_ID, FILTERS, false), 5),
                tuple(DatastoreCacheKeys.endpointFiles(10L, AGENT_ID, FILE_QUERY), 2),
                tuple(DatastoreCacheKeys.endpointScans(10L, AGENT_ID, 0, 10), 2));
    }

    @Test
    @DisplayName("summary caches an EndpointSummarySnapshot from the unfiltered call and returns its summary()")
    void summaryCachesTheSnapshot() {
        EndpointSummary summary = service.summary(10L, AGENT_ID);

        verify(apiClient).agentSensitiveFiles(CREDENTIAL, AGENT_ID, EndpointFilters.NONE, false);
        EndpointSummarySnapshot expected = mapper.toSummarySnapshot(parse(SENSITIVE_FILES_BODY));
        assertThat(summary).isEqualTo(expected.summary());
        assertThat(cache.putsFor(SUMMARY_KEY)).singleElement()
                .satisfies(put -> assertThat(put.json()).contains("\"entityNames\":[\"EMAIL ADDRESS\",\"PERSON NAME\"]"));
        assertThat(cache.get(SUMMARY_KEY, new TypeReference<EndpointSummarySnapshot>() {
        })).contains(expected);
    }

    // ── occurrences (review fix R5) ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R5: on an empty cache, the only write during occurrences is the dsepsum snapshot, without a sample")
    void occurrencesWriteOnlyTheSnapshot() {
        EndpointOccurrencePage page = service.occurrences(10L, AGENT_ID, "EMAIL ADDRESS", 20, 10, false);

        assertThat(cache.puts).singleElement().satisfies(put -> {
            assertThat(put.key()).startsWith("dspm:dsepsum:").isEqualTo(SUMMARY_KEY);
            assertThat(put.json()).doesNotContain(MARKER);
        });
        verify(apiClient, times(1)).agentSensitiveFiles(CREDENTIAL, AGENT_ID, EndpointFilters.NONE, false);
        verify(apiClient, times(1)).agentEntity(CREDENTIAL, AGENT_ID, "EMAIL ADDRESS", 20, 10, true);
        assertThat(page.skip()).isEqualTo(20);
        assertThat(page.rows().get(0).samples()).containsExactly("<<SYNT•••••-SAM•••-3•••>>");
    }

    @Test
    @DisplayName("R5: with a dsepsum hit, occurrences write nothing and read only the snapshot")
    void occurrencesWithASnapshotHitWriteNothing() {
        service.summary(10L, AGENT_ID);
        cache.gets.clear();
        cache.puts.clear();

        service.occurrences(10L, AGENT_ID, "EMAIL ADDRESS", 0, 10, false);
        service.occurrences(10L, AGENT_ID, "EMAIL ADDRESS", 0, 10, false);

        assertThat(cache.puts).isEmpty();
        assertThat(cache.gets).containsOnly(SUMMARY_KEY);
        verify(apiClient, times(1)).agentSensitiveFiles(CREDENTIAL, AGENT_ID, EndpointFilters.NONE, false);
        verify(apiClient, times(2)).agentEntity(CREDENTIAL, AGENT_ID, "EMAIL ADDRESS", 0, 10, true);
    }

    @Test
    @DisplayName("an entity outside the snapshot is a 400 before any entity call, the snapshot fetched once on a miss")
    void unknownEntityIsRejected() {
        assertThatThrownBy(() -> service.occurrences(10L, AGENT_ID, "BANK ACCOUNT", 0, 10, false))
                .isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
                    assertThat(e.parameter()).isEqualTo("entityName");
                    assertThat(e.getMessage()).isEqualTo("entityName is not an entity of this agent");
                });
        assertThatThrownBy(() -> service.occurrences(10L, AGENT_ID, "system documentation", 0, 10, false))
                .isInstanceOf(DspmBadRequestException.class);

        verify(apiClient, times(1)).agentSensitiveFiles(CREDENTIAL, AGENT_ID, EndpointFilters.NONE, false);
        verify(apiClient, never()).agentEntity(eq(CREDENTIAL), anyString(), anyString(), anyInt(), anyInt(),
                anyBoolean());
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key).containsExactly(SUMMARY_KEY);
    }

    @Test
    @DisplayName("with the snapshot cached, the entityName check makes no sensitive-files call")
    void membershipReadsTheCachedSnapshot() {
        cache.seed(SUMMARY_KEY, new EndpointSummarySnapshot(
                new EndpointSummary(1L, List.of(), 1L, List.of(), List.of()), List.of("API KEY")));

        service.occurrences(10L, AGENT_ID, "API KEY", 0, 10, true);
        assertThatThrownBy(() -> service.occurrences(10L, AGENT_ID, "EMAIL ADDRESS", 0, 10, true))
                .isInstanceOf(DspmBadRequestException.class);

        verify(apiClient, never()).agentSensitiveFiles(any(), anyString(), any(), anyBoolean());
        verify(apiClient, times(1)).agentEntity(CREDENTIAL, AGENT_ID, "API KEY", 0, 10, false);
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("isFalsePositive=false is sent only while false positives are hidden, each switch with its own entry")
    void falsePositivesHiddenUnlessShown() {
        service.occurrences(10L, AGENT_ID, "EMAIL ADDRESS", 0, 10, false);
        service.occurrences(10L, AGENT_ID, "EMAIL ADDRESS", 0, 10, true);
        service.sensitives(10L, AGENT_ID, FILTERS, false);
        service.sensitives(10L, AGENT_ID, FILTERS, true);

        verify(apiClient).agentEntity(CREDENTIAL, AGENT_ID, "EMAIL ADDRESS", 0, 10, true);
        verify(apiClient).agentEntity(CREDENTIAL, AGENT_ID, "EMAIL ADDRESS", 0, 10, false);
        verify(apiClient).agentSensitiveFiles(CREDENTIAL, AGENT_ID, FILTERS, true);
        verify(apiClient).agentSensitiveFiles(CREDENTIAL, AGENT_ID, FILTERS, false);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key).containsExactly(SUMMARY_KEY,
                DatastoreCacheKeys.endpointMetrics(10L, AGENT_ID, FILTERS, false),
                DatastoreCacheKeys.endpointMetrics(10L, AGENT_ID, FILTERS, true));
    }

    // ── no sample in the cache ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no cached value holds a sample, while the occurrence response carries it masked")
    void noSampleIsEverCached() {
        everyMethod().values().forEach(Runnable::run);
        EndpointOccurrencePage occurrences = service.occurrences(10L, AGENT_ID, "EMAIL ADDRESS", 0, 10, true);

        assertThat(cache.puts).hasSize(6)
                .allSatisfy(put -> assertThat(put.json()).as(put.key()).doesNotContain(MARKER));
        assertThat(occurrences.rows().get(0).samples()).containsExactly("<<SYNT•••••-SAM•••-3•••>>");
    }
}
