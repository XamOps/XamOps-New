package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.SaasFilePage;
import com.xammer.cloud.dto.matters.datastores.SaasRecords;
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
 * Design 5.8 SaaS drawer flows, with the real mapper, a mocked client and an in-memory cache. Every
 * vendor body carries {@link #MARKER} in fields the mapper drops.
 */
class SaasDrawerServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String SOURCE_ID = "0123456789abcdef01234567";
    private static final String MARKER = "<<SYNTHETIC-SAMPLE-5C1D>>";

    private static final String DETAIL_BODY = """
            {"success": true, "detail": {"drive_type": "gmail", "account_name": "ops-mailbox", "owner": "%1$s",
              "name": "%1$s", "scan_state": "COMPLETED", "realtime_scan_status": "ENABLED",
              "scanning": {"failure_reason": "%1$s"}}}
            """.formatted(MARKER);

    private static final String ELEMENT_RECORDS_BODY = """
            {"success": true, "view": "data_elements", "records": [
              {"sensitive_record": "EMAIL ADDRESS", "label": ["PII"], "count": 40, "sampled_data": ["%1$s"]},
              {"sensitive_record": "BANK ACCOUNT", "label": ["PCI"], "count": 12}]}
            """.formatted(MARKER);

    private static final String CLASS_RECORDS_BODY = """
            {"success": true, "view": "document_classes", "records": [
              {"category_label": "Financial", "subcategory_label": "Budget Plans", "count": 8, "sample": "%1$s"}]}
            """.formatted(MARKER);

    private static final String FILES_BODY = """
            {"success": true, "files": [
              {"id": "ABCDEF0123456789ABCDEF01", "file": "Plan Draft.pdf", "records": 4, "sampled_data": "%1$s"}],
             "count": 12}
            """.formatted(MARKER);

    private static final String SCAN_SUMMARY_BODY = """
            {"success": true, "scan_summary": {"supported": {"count": 10, "size": 2048}, "unsupported": {"count": 1},
              "file_type_breakdown": {"pdf": 3}, "properties": {"owner": "Robin Vale", "token": "%1$s"}}}
            """.formatted(MARKER);

    private static final SaasFileQuery ELEMENT_QUERY =
            new SaasFileQuery(SaasFileQuery.Kind.DATA_ELEMENT, "EMAIL ADDRESS", "plan", 0, 5);
    private static final SaasFileQuery CLASS_QUERY =
            new SaasFileQuery(SaasFileQuery.Kind.DOCUMENT_CLASS, "Budget Plans", null, 5, 5);

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private InMemoryRedisCache cache;
    private SaasDrawerService service;

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        cache = new InMemoryRedisCache(events);
        when(resolver.resolve(10L)).thenAnswer(inv -> {
            events.add("resolve");
            return CREDENTIAL;
        });
        when(apiClient.saasAssetDetail(CREDENTIAL, SOURCE_ID)).thenAnswer(inv -> parse(DETAIL_BODY));
        when(apiClient.saasRecords(CREDENTIAL, SOURCE_ID, "data_elements"))
                .thenAnswer(inv -> parse(ELEMENT_RECORDS_BODY));
        when(apiClient.saasRecords(CREDENTIAL, SOURCE_ID, "document_classes"))
                .thenAnswer(inv -> parse(CLASS_RECORDS_BODY));
        when(apiClient.saasFiles(eq(CREDENTIAL), eq(SOURCE_ID), any(SaasFileQuery.class)))
                .thenAnswer(inv -> parse(FILES_BODY));
        when(apiClient.saasScanSummary(CREDENTIAL, SOURCE_ID)).thenAnswer(inv -> parse(SCAN_SUMMARY_BODY));
        service = new SaasDrawerService(resolver, apiClient, new SaasDrawerMapper(), cache);
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String key(String kind, String rest) {
        return "dspm:" + kind + ":10:" + rest;
    }

    /** Every method, keyed by the cache key it reads first. */
    private Map<String, Runnable> everyMethod() {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put(key("dssaashead", SOURCE_ID), () -> service.header(10L, SOURCE_ID));
        calls.put(key("dssaasrec", SOURCE_ID + ":document_classes"),
                () -> service.records(10L, SOURCE_ID, "document_classes"));
        calls.put(key("dssaasrec", SOURCE_ID + ":data_elements"), () -> service.files(10L, SOURCE_ID, ELEMENT_QUERY));
        calls.put(key("dssaassum", SOURCE_ID), () -> service.scanSummary(10L, SOURCE_ID));
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
    @DisplayName("each view is cached under the client's key with its TTL, and a hit makes no vendor call")
    void viewsAreCached() {
        for (int round = 0; round < 2; round++) {
            service.header(10L, SOURCE_ID);
            service.records(10L, SOURCE_ID, "data_elements");
            service.files(10L, SOURCE_ID, ELEMENT_QUERY);
            service.scanSummary(10L, SOURCE_ID);
        }

        verify(apiClient, times(1)).saasAssetDetail(CREDENTIAL, SOURCE_ID);
        verify(apiClient, times(1)).saasRecords(CREDENTIAL, SOURCE_ID, "data_elements");
        verify(apiClient, times(1)).saasFiles(CREDENTIAL, SOURCE_ID, ELEMENT_QUERY);
        verify(apiClient, times(1)).saasScanSummary(CREDENTIAL, SOURCE_ID);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes).containsExactly(
                tuple(key("dssaashead", SOURCE_ID), 5),
                tuple(key("dssaasrec", SOURCE_ID + ":data_elements"), 5),
                tuple(DatastoreCacheKeys.saasFiles(10L, SOURCE_ID, ELEMENT_QUERY), 2),
                tuple(key("dssaassum", SOURCE_ID), 5));
    }

    @Test
    @DisplayName("a file page reads the records view of its kind, then pages the files route")
    void filesReadTheMatchingView() {
        SaasFilePage elementPage = service.files(10L, SOURCE_ID, ELEMENT_QUERY);
        SaasFilePage classPage = service.files(10L, SOURCE_ID, CLASS_QUERY);

        verify(apiClient, times(1)).saasRecords(CREDENTIAL, SOURCE_ID, "data_elements");
        verify(apiClient, times(1)).saasRecords(CREDENTIAL, SOURCE_ID, "document_classes");
        verify(apiClient).saasFiles(CREDENTIAL, SOURCE_ID, ELEMENT_QUERY);
        verify(apiClient).saasFiles(CREDENTIAL, SOURCE_ID, CLASS_QUERY);
        assertThat(elementPage.rows()).extracting(SaasFilePage.File::id).containsExactly("abcdef0123456789abcdef01");
        assertThat(elementPage.total()).isEqualTo(12);
        assertThat(classPage.skip()).isEqualTo(5);
        assertThat(classPage.limit()).isEqualTo(5);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key).containsExactly(
                key("dssaasrec", SOURCE_ID + ":data_elements"),
                DatastoreCacheKeys.saasFiles(10L, SOURCE_ID, ELEMENT_QUERY),
                key("dssaasrec", SOURCE_ID + ":document_classes"),
                DatastoreCacheKeys.saasFiles(10L, SOURCE_ID, CLASS_QUERY));
    }

    // ── membership ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a value outside the records is a 400 before any files call; the snapshot is fetched once, then read")
    void unknownValueIsRejected() {
        SaasFileQuery unknownElement = new SaasFileQuery(SaasFileQuery.Kind.DATA_ELEMENT, "PERSON NAME", null, 0, 5);
        SaasFileQuery unknownClass = new SaasFileQuery(SaasFileQuery.Kind.DOCUMENT_CLASS, "EMAIL ADDRESS", null, 0, 5);

        assertThatThrownBy(() -> service.files(10L, SOURCE_ID, unknownElement))
                .isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
                    assertThat(e.parameter()).isEqualTo("dataElement");
                    assertThat(e.getMessage()).isEqualTo("dataElement is not a row of this datastore");
                });
        assertThatThrownBy(() -> service.files(10L, SOURCE_ID, unknownElement))
                .isInstanceOf(DspmBadRequestException.class);
        assertThatThrownBy(() -> service.files(10L, SOURCE_ID, unknownClass))
                .isInstanceOfSatisfying(DspmBadRequestException.class,
                        e -> assertThat(e.parameter()).isEqualTo("documentClass"));

        verify(apiClient, times(1)).saasRecords(CREDENTIAL, SOURCE_ID, "data_elements");
        verify(apiClient, times(1)).saasRecords(CREDENTIAL, SOURCE_ID, "document_classes");
        verify(apiClient, never()).saasFiles(eq(CREDENTIAL), anyString(), any(SaasFileQuery.class));
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key).containsExactly(
                key("dssaasrec", SOURCE_ID + ":data_elements"), key("dssaasrec", SOURCE_ID + ":document_classes"));
    }

    @Test
    @DisplayName("with the records snapshot cached, membership makes no records call")
    void membershipReadsTheCachedSnapshot() {
        cache.seed(key("dssaasrec", SOURCE_ID + ":data_elements"), new SaasRecords("data_elements",
                List.of(new SaasRecords.Row("PERSON NAME", "Personal", 3))));

        SaasFileQuery cachedName = new SaasFileQuery(SaasFileQuery.Kind.DATA_ELEMENT, "PERSON NAME", null, 0, 5);
        service.files(10L, SOURCE_ID, cachedName);
        assertThatThrownBy(() -> service.files(10L, SOURCE_ID, ELEMENT_QUERY))
                .isInstanceOf(DspmBadRequestException.class);

        verify(apiClient, never()).saasRecords(eq(CREDENTIAL), anyString(), anyString());
        verify(apiClient, times(1)).saasFiles(CREDENTIAL, SOURCE_ID, cachedName);
        verify(apiClient, never()).saasFiles(CREDENTIAL, SOURCE_ID, ELEMENT_QUERY);
    }

    // ── no sample in the cache ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no cached value holds a marker from a dropped vendor field")
    void noMarkerIsCached() {
        everyMethod().values().forEach(Runnable::run);
        service.files(10L, SOURCE_ID, CLASS_QUERY);

        assertThat(cache.puts).hasSize(6)
                .allSatisfy(put -> assertThat(put.json()).as(put.key()).doesNotContain(MARKER));
    }
}
