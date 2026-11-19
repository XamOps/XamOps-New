package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.SaasFileCopies;
import com.xammer.cloud.dto.matters.datastores.SaasFileLineage;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Design 5.8 SaaS file drawer flows and review fix R6, with the real mapper (spied, to see the file id
 * it receives), a mocked client and an in-memory cache. Dropped vendor fields carry {@link #MARKER}.
 */
class SaasFileServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String FILE_ID = "abcdef0123456789abcdef01";
    private static final String OTHER_FILE = "fedcba9876543210fedcba98";
    private static final String DATA_HASH = "0f".repeat(32);
    private static final String MARKER = "<<SYNTHETIC-SAMPLE-9B2E>>";

    private static final String OVERVIEW_BODY = """
            {"success": true, "overview": {"name": "Plan Draft.pdf", "file_extension": "PDF",
              "owner": "robin@example.test", "path": "%1$s", "labels": ["%1$s"], "file_id": "%1$s",
              "metadata": {"Subject": "Quarterly plan", "headers": [{"value": "%1$s"}], "sizeEstimate": 1024},
              "sensitives": [{"data_element": "EMAIL ADDRESS", "label": ["PII"], "count": 2, "samples": ["%1$s"]}]}}
            """.formatted(MARKER);

    private static final String ACTIVITY_BODY = """
            {"success": true, "response": [{"title": "FILE_SHARED", "status": "SUCCESS",
              "initiated_by": "robin@example.test", "created_at": 1789725004, "details": "%1$s"}]}
            """.formatted(MARKER);

    /** Two copies: the opened file in Gmail and a Drive copy. */
    private static final String COPIES_BODY = """
            {"success": true, "data": {"data_hash": "%1$s", "known_copies": 2, "truncated": false,
              "origin": {"data_object_id": "do-1", "file_id": "%2$s", "name": "%4$s"},
              "copies": [
                {"data_object_id": "do-1", "file_id": "%2$s", "source": "SAAS", "name": "Plan Draft.pdf",
                 "owner": null, "last_modified_by": "robin@example.test", "web_url": "https://%4$s.example.test",
                 "created_at": "2026-09-10T09:15:00Z",
                 "lane": {"resource_id": "res-1", "provider": "GOOGLE_WORKSPACE", "display_name": "Robin Vale",
                          "external_id": "robin@example.test", "legacy_id": "aaaaaaaaaaaaaaaaaaaaaaf8",
                          "drive_type": "GMAIL"}},
                {"data_object_id": "do-2", "file_id": "%3$s", "source": "SAAS", "name": "Plan Draft.docx",
                 "owner": "sam@example.test", "exposure": "%4$s", "created_at": "2026-09-11T08:00:00Z",
                 "lane": {"resource_id": "res-2", "provider": "GOOGLE_WORKSPACE", "display_name": "Sam Reed",
                          "external_id": "sam@example.test", "legacy_id": "bbbbbbbbbbbbbbbbbbbbbbf7",
                          "drive_type": "GOOGLE_WORKSPACE"}}],
              "summary": {"locations": 2}, "filters": {"providers": [{"value": "%4$s"}]}}}
            """.formatted(DATA_HASH, FILE_ID.toUpperCase(), OTHER_FILE, MARKER);

    private static final String TIMELINE_BODY = """
            {"success": true, "data": {"data_hash": "%1$s", "next_cursor": "%2$s",
              "lanes": [{"resource_id": "res-1", "data_object_id": "do-1"},
                        {"resource_id": "res-2", "data_object_id": "do-2"}],
              "events": [{"data_object_id": "do-2", "type": "FILE_SHARED", "actor": "sam@example.test",
                          "timestamp": "2026-09-12T10:00:00Z", "details": "%2$s"}]}}
            """.formatted(DATA_HASH, MARKER);

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private SaasDrawerMapper mapper;
    private InMemoryRedisCache cache;
    private SaasFileService service;

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        mapper = spy(new SaasDrawerMapper());
        cache = new InMemoryRedisCache(events);
        when(resolver.resolve(10L)).thenAnswer(inv -> {
            events.add("resolve");
            return CREDENTIAL;
        });
        when(apiClient.saasFileOverview(CREDENTIAL, FILE_ID)).thenAnswer(inv -> parse(OVERVIEW_BODY));
        when(apiClient.remediationLogs(CREDENTIAL, FILE_ID)).thenAnswer(inv -> parse(ACTIVITY_BODY));
        when(apiClient.hashLineageCopies(CREDENTIAL, FILE_ID)).thenAnswer(inv -> parse(COPIES_BODY));
        when(apiClient.hashLineageTimeline(CREDENTIAL, DATA_HASH, 100)).thenAnswer(inv -> parse(TIMELINE_BODY));
        service = new SaasFileService(resolver, apiClient, mapper, cache);
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(String kind) {
        return "dspm:" + kind + ":10:" + FILE_ID;
    }

    private void copiesFail(MattersApiException.Reason reason) {
        when(apiClient.hashLineageCopies(CREDENTIAL, FILE_ID)).thenThrow(new MattersApiException(reason, "copies"));
    }

    private void timelineFails(MattersApiException.Reason reason) {
        when(apiClient.hashLineageTimeline(CREDENTIAL, DATA_HASH, 100))
                .thenThrow(new MattersApiException(reason, "timeline"));
    }

    /** Every method, keyed by the cache key it reads first. */
    private Map<String, Runnable> everyMethod() {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put(key("dssfile"), () -> service.overview(10L, FILE_ID));
        calls.put(key("dssfileact"), () -> service.activity(10L, FILE_ID));
        calls.put(key("dssfilelin"), () -> service.lineage(10L, FILE_ID));
        calls.put(key("dssfilecopies"), () -> service.copies(10L, FILE_ID));
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
    }

    @Test
    @DisplayName("NOT_ENTITLED stops every method before the cache and the vendor")
    void notEntitled() {
        doThrow(new MattersApiException(MattersApiException.Reason.NOT_ENTITLED, "not entitled"))
                .when(resolver).resolve(10L);

        everyMethod().forEach((key, call) -> assertThatThrownBy(call::run).as(key)
                .isInstanceOf(MattersApiException.class));

        verifyNoInteractions(apiClient);
        assertThat(cache.gets).isEmpty();
    }

    // ── caching ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("each tab is cached under the client's key with its TTL, and a hit makes no vendor call")
    void tabsAreCached() {
        for (int round = 0; round < 2; round++) {
            everyMethod().values().forEach(Runnable::run);
        }

        verify(apiClient, times(1)).saasFileOverview(CREDENTIAL, FILE_ID);
        verify(apiClient, times(1)).remediationLogs(CREDENTIAL, FILE_ID);
        verify(apiClient, times(2)).hashLineageCopies(CREDENTIAL, FILE_ID);
        verify(apiClient, times(1)).hashLineageTimeline(CREDENTIAL, DATA_HASH, 100);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes).containsExactly(
                tuple(key("dssfile"), 2),
                tuple(key("dssfileact"), 2),
                tuple(key("dssfilelin"), 5),
                tuple(key("dssfilecopies"), 5));
    }

    // ── lineage ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("lineage: the request's file id reaches toLineage, the timeline of the copies' hash adds its events")
    void lineageUsesTheRequestFileId() {
        SaasFileLineage lineage = service.lineage(10L, FILE_ID);

        verify(mapper).toLineage(any(JsonNode.class), any(JsonNode.class), eq(FILE_ID));
        verify(apiClient).hashLineageTimeline(CREDENTIAL, DATA_HASH, 100);
        assertThat(lineage.available()).isTrue();
        assertThat(lineage.lanes()).extracting(SaasFileLineage.Lane::key, SaasFileLineage.Lane::self)
                .containsExactly(tuple("copy-0", true), tuple("copy-1", false));
        assertThat(lineage.events()).extracting(SaasFileLineage.Event::laneKey).containsExactly("copy-1");
    }

    @Test
    @DisplayName("lineage: a copies ASSET_NOT_FOUND is available false, no timeline call, and cached")
    void lineageUnavailableIsCached() {
        copiesFail(MattersApiException.Reason.ASSET_NOT_FOUND);

        SaasFileLineage first = service.lineage(10L, FILE_ID);
        SaasFileLineage second = service.lineage(10L, FILE_ID);

        assertThat(first).isEqualTo(SaasFileLineage.unavailable());
        assertThat(second).isEqualTo(SaasFileLineage.unavailable());
        verify(apiClient, times(1)).hashLineageCopies(CREDENTIAL, FILE_ID);
        verify(apiClient, never()).hashLineageTimeline(eq(CREDENTIAL), anyString(), anyInt());
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(tuple(key("dssfilelin"), 5));
    }

    @Test
    @DisplayName("lineage: no timeline call when the copies list is empty or the hash is not 64 hex")
    void timelineSkippedWithoutCopies() {
        when(apiClient.hashLineageCopies(CREDENTIAL, FILE_ID)).thenAnswer(inv -> parse("""
                {"success": true, "data": {"data_hash": "%s", "copies": []}}
                """.formatted(DATA_HASH)));
        SaasFileLineage empty = service.lineage(10L, FILE_ID);

        assertThat(empty.available()).isTrue();
        assertThat(empty.lanes()).isEmpty();
        verify(mapper).toLineage(any(JsonNode.class), isNull(), eq(FILE_ID));

        String badHash = COPIES_BODY.replace(DATA_HASH, "not-a-hash");
        when(apiClient.hashLineageCopies(CREDENTIAL, OTHER_FILE)).thenAnswer(inv -> parse(badHash));
        SaasFileLineage unhashed = service.lineage(10L, OTHER_FILE);

        assertThat(unhashed.lanes()).hasSize(2);
        assertThat(unhashed.events()).isEmpty();
        verify(apiClient, never()).hashLineageTimeline(eq(CREDENTIAL), anyString(), anyInt());
    }

    @Test
    @DisplayName("lineage: a timeline ASSET_NOT_FOUND keeps the lanes without events, and is cached")
    void timelineNotFoundKeepsTheLanes() {
        timelineFails(MattersApiException.Reason.ASSET_NOT_FOUND);

        SaasFileLineage lineage = service.lineage(10L, FILE_ID);

        assertThat(lineage.available()).isTrue();
        assertThat(lineage.lanes()).extracting(SaasFileLineage.Lane::key).containsExactly("copy-0", "copy-1");
        assertThat(lineage.events()).isEmpty();
        verify(mapper).toLineage(any(JsonNode.class), isNull(), eq(FILE_ID));
        assertThat(cache.putsFor(key("dssfilelin"))).hasSize(1);
    }

    @Test
    @DisplayName("R6: a timeline UPSTREAM_UNAVAILABLE fails the lineage and nothing is cached")
    void timelineFailurePropagates() {
        timelineFails(MattersApiException.Reason.UPSTREAM_UNAVAILABLE);

        assertThatThrownBy(() -> service.lineage(10L, FILE_ID))
                .isInstanceOfSatisfying(MattersApiException.class,
                        e -> assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.UPSTREAM_UNAVAILABLE));
        assertThatThrownBy(() -> service.lineage(10L, FILE_ID)).isInstanceOf(MattersApiException.class);

        assertThat(cache.puts).isEmpty();
        assertThat(cache.contains(key("dssfilelin"))).isFalse();
        verify(apiClient, times(2)).hashLineageCopies(CREDENTIAL, FILE_ID);
        verify(mapper, never()).toLineage(any(), any(), anyString());
    }

    @Test
    @DisplayName("a copies failure other than ASSET_NOT_FOUND fails lineage and copies, with nothing cached")
    void copiesFailurePropagates() {
        copiesFail(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE);

        assertThatThrownBy(() -> service.lineage(10L, FILE_ID)).isInstanceOfSatisfying(MattersApiException.class,
                e -> assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE));
        assertThatThrownBy(() -> service.copies(10L, FILE_ID)).isInstanceOfSatisfying(MattersApiException.class,
                e -> assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE));

        assertThat(cache.puts).isEmpty();
        verify(apiClient, never()).hashLineageTimeline(eq(CREDENTIAL), anyString(), anyInt());
    }

    // ── copies ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("copies: the request's file id reaches toCopies, the opened file is not a row, no timeline call")
    void copiesLeaveOutTheOpenedFile() {
        SaasFileCopies copies = service.copies(10L, FILE_ID);

        verify(mapper).toCopies(any(JsonNode.class), eq(FILE_ID));
        verify(apiClient, never()).hashLineageTimeline(eq(CREDENTIAL), anyString(), anyInt());
        assertThat(copies.available()).isTrue();
        assertThat(copies.copies()).extracting(SaasFileCopies.Copy::fileName).containsExactly("Plan Draft.docx");
        // Plan C20: Locations is the vendor's summary.locations (2 in COPIES_BODY).
        assertThat(copies.locations()).isEqualTo(2);
    }

    @Test
    @DisplayName("copies: an ASSET_NOT_FOUND is available false with no rows, and cached")
    void copiesUnavailableIsCached() {
        copiesFail(MattersApiException.Reason.ASSET_NOT_FOUND);

        SaasFileCopies first = service.copies(10L, FILE_ID);
        service.copies(10L, FILE_ID);

        assertThat(first).isEqualTo(SaasFileCopies.unavailable());
        assertThat(first.copies()).isEmpty();
        assertThat(first.locations()).isZero();
        verify(apiClient, times(1)).hashLineageCopies(CREDENTIAL, FILE_ID);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(tuple(key("dssfilecopies"), 5));
    }

    // ── no marker in the cache ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no cached value holds a marker from a dropped vendor field")
    void noMarkerIsCached() {
        everyMethod().values().forEach(Runnable::run);

        assertThat(cache.puts).hasSize(4)
                .allSatisfy(put -> assertThat(put.json()).as(put.key()).doesNotContain(MARKER)
                        .doesNotContain(DATA_HASH).doesNotContain("do-1").doesNotContain("res-1"));
    }
}
