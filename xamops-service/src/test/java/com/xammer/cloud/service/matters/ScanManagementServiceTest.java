package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.scans.ScanRun;
import com.xammer.cloud.dto.matters.scans.ScanSchedule;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Build 6 design 5.8 Scan Management flows (S1 to S7) with the real mapper, a mocked client and an
 * in-memory cache. Vendor bodies carry {@link #MARKER} in fields the mapper drops.
 */
class ScanManagementServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String MARKER = "<<SYNTHETIC-SCANS-41B9>>";
    private static final String SCHEDULE_ID = "abcdef0123456789abcdef01";
    private static final String RUN_ID = "0000000a-0000-4000-8000-00000000000b";
    private static final String SCOPE_ID = "0123456789abcdef01234567";

    private static final String LIST_BODY = """
            {"data": [{"id": "%2$s", "name": "Nightly", "schedule_name": "Nightly", "platform": "cloud",
              "status": "SUCCESS", "created_by": {"email": "%1$s"}, "pg_id": "%1$s"}],
             "pagination": {"total": 21, "total_pages": 3}}
            """;
    private static final String DETAIL_BODY = """
            {"id": "%2$s", "name": "Nightly", "platform": "cloud", "created_by": {"email": "%1$s"},
             "scopes": [{"name": "Prod"}], "selected_assets": [{"id": "a1", "name": "bucket-a", "source_id": "%1$s"}]}
            """;

    private static final ScanListQuery SCHEDULES = query(ScanListQuery.Kind.SCHEDULES, "platform", "cloud");
    private static final ScanListQuery RUNS = query(ScanListQuery.Kind.RUNS, "trigger", "SCHEDULED");
    private static final ScanListQuery RUN_DATASTORES = query(ScanListQuery.Kind.RUN_DATASTORES, "status", "FAILED");
    private static final ScanListQuery SCOPES = query(ScanListQuery.Kind.SCOPES, "platform", "on_prem");

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private InMemoryRedisCache cache;
    private ScanManagementService service;

    private static ScanListQuery query(ScanListQuery.Kind kind, String filter, String value) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("q", "nightly");
        params.add(filter, value);
        params.add("page", "2");
        params.add("pageSize", "10");
        return ScanListQuery.from(kind, params);
    }

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        cache = new InMemoryRedisCache(events);
        when(resolver.resolve(10L)).thenAnswer(inv -> {
            events.add("resolve");
            return CREDENTIAL;
        });
        when(apiClient.scanSchedules(CREDENTIAL, SCHEDULES)).thenAnswer(inv -> list(SCHEDULE_ID));
        when(apiClient.scanSchedule(CREDENTIAL, SCHEDULE_ID)).thenAnswer(inv -> detail(SCHEDULE_ID));
        when(apiClient.scanRuns(CREDENTIAL, RUNS)).thenAnswer(inv -> list(RUN_ID));
        when(apiClient.scanRun(CREDENTIAL, RUN_ID)).thenAnswer(inv -> detail(RUN_ID));
        when(apiClient.scanRunDatastores(CREDENTIAL, RUN_ID, RUN_DATASTORES)).thenAnswer(inv -> list("d1"));
        when(apiClient.scanScopes(CREDENTIAL, SCOPES)).thenAnswer(inv -> list(SCOPE_ID));
        when(apiClient.scanScope(CREDENTIAL, SCOPE_ID)).thenAnswer(inv -> detail(SCOPE_ID));
        service = new ScanManagementService(resolver, apiClient, new ScanManagementMapper(), cache);
    }

    private JsonNode list(String id) throws Exception {
        return json.readTree(LIST_BODY.formatted(MARKER, id));
    }

    private JsonNode detail(String id) throws Exception {
        return json.readTree(DETAIL_BODY.formatted(MARKER, id));
    }

    /** Every method, keyed by the cache key it reads first, with the TTL it writes. */
    private Map<String, Map.Entry<Integer, Runnable>> everyMethod() {
        Map<String, Map.Entry<Integer, Runnable>> calls = new LinkedHashMap<>();
        calls.put(DatastoreCacheKeys.scanList(10L, SCHEDULES), Map.entry(1, () -> service.schedules(10L, SCHEDULES)));
        calls.put("dspm:dssmschedone:10:" + SCHEDULE_ID, Map.entry(2, () -> service.schedule(10L, SCHEDULE_ID)));
        calls.put(DatastoreCacheKeys.scanList(10L, RUNS), Map.entry(1, () -> service.runs(10L, RUNS)));
        calls.put("dspm:dssmrun:10:" + RUN_ID, Map.entry(1, () -> service.run(10L, RUN_ID)));
        calls.put(DatastoreCacheKeys.scanRunDatastores(10L, RUN_ID, RUN_DATASTORES),
                Map.entry(1, () -> service.runDatastores(10L, RUN_ID, RUN_DATASTORES)));
        calls.put(DatastoreCacheKeys.scanList(10L, SCOPES), Map.entry(2, () -> service.scopes(10L, SCOPES)));
        calls.put("dspm:dssmscope:10:" + SCOPE_ID, Map.entry(2, () -> service.scope(10L, SCOPE_ID)));
        return calls;
    }

    @Test
    @DisplayName("the credential is resolved before the cache read, on a miss and on a hit")
    void credentialFirst() {
        for (int round = 0; round < 2; round++) {
            everyMethod().forEach((key, call) -> {
                events.clear();
                call.getValue().run();
                assertThat(events).as(key).startsWith("resolve", "get " + key);
            });
        }
    }

    @Test
    @DisplayName("NOT_ENTITLED stops every method before the cache and the vendor")
    void notEntitled() {
        doThrow(new MattersApiException(MattersApiException.Reason.NOT_ENTITLED, "not entitled"))
                .when(resolver).resolve(10L);

        everyMethod().forEach((key, call) -> assertThatThrownBy(call.getValue()::run).as(key)
                .isInstanceOf(MattersApiException.class));
        verifyNoInteractions(apiClient);
        assertThat(cache.gets).isEmpty();
    }

    @Test
    @DisplayName("each DTO is cached under its 5.8 key and TTL; a hit makes no vendor call")
    void cached() {
        for (int round = 0; round < 2; round++) {
            everyMethod().values().forEach(call -> call.getValue().run());
        }

        verify(apiClient, times(1)).scanSchedules(CREDENTIAL, SCHEDULES);
        verify(apiClient, times(1)).scanSchedule(CREDENTIAL, SCHEDULE_ID);
        verify(apiClient, times(1)).scanRuns(CREDENTIAL, RUNS);
        verify(apiClient, times(1)).scanRun(CREDENTIAL, RUN_ID);
        verify(apiClient, times(1)).scanRunDatastores(CREDENTIAL, RUN_ID, RUN_DATASTORES);
        verify(apiClient, times(1)).scanScopes(CREDENTIAL, SCOPES);
        verify(apiClient, times(1)).scanScope(CREDENTIAL, SCOPE_ID);
        List<Object[]> expected = new ArrayList<>();
        everyMethod().forEach((key, call) -> expected.add(new Object[]{key, call.getKey()}));
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(expected.stream().map(e -> tuple(e[0], e[1])).toArray(org.assertj.core.groups.Tuple[]::new));
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("pages carry the request's page and the vendor total; no dropped vendor field is cached")
    void contentAndNoMarker() {
        DspmPage<ScanSchedule> schedules = service.schedules(10L, SCHEDULES);
        DspmPage<ScanRun> runs = service.runs(10L, RUNS);
        everyMethod().values().forEach(call -> call.getValue().run());

        assertThat(schedules.data()).extracting(ScanSchedule::id).containsExactly(SCHEDULE_ID);
        assertThat(schedules.page()).isEqualTo(2);
        assertThat(schedules.pageSize()).isEqualTo(10);
        assertThat(schedules.total()).isEqualTo(21);
        assertThat(runs.data()).extracting(ScanRun::name).containsExactly("Nightly");
        assertThat(service.scope(10L, SCOPE_ID).targets()).extracting(t -> t.name()).containsExactly("bucket-a");
        assertThat(cache.puts).hasSize(7)
                .allSatisfy(put -> assertThat(put.json()).as(put.key()).doesNotContain(MARKER));
    }
}
