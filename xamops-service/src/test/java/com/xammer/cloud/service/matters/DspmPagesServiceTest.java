package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.pages.AssistList;
import com.xammer.cloud.dto.matters.pages.DocumentClassList;
import com.xammer.cloud.dto.matters.pages.RemediationActivity;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Build 6 design 5.8 flows of Document Classes, Assists and Remediation, with the real mappers, a mocked
 * client and an in-memory cache. Vendor bodies carry {@link #MARKER} in fields the mappers drop.
 */
class DspmPagesServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String MARKER = "<<SYNTHETIC-PAGES-7A2E>>";

    private static final String CATEGORIES_BODY = """
            {"success": true, "secret": "%1$s", "categories": [
              {"category": "People Records", "subcategories": ["Resumes", "Payslips"], "id": "%1$s"},
              {"category": "Finance Papers", "subcategories": ["Invoices"]}]}
            """.formatted(MARKER);

    private static final String ASSISTS_BODY = """
            {"success": true, "assists": [{"id": "feedfacefeedfacefeedface", "proxy_name": "Office connector",
              "client_id": "c2ludGhldGljLWNsaWVudC1pZA", "health_status": "ACTIVE", "assist_type": "DISCOVERY",
              "version": "1.2.3", "secret_expires_at": 1800000000, "account_id": "%1$s", "password": "%1$s"}]}
            """.formatted(MARKER);

    private static final String REMEDIATION_BODY = """
            {"success": true, "response": {"summary": {"total": 4, "completed": 3, "failed": 1},
              "statusCounts": {"COMPLETED": 3, "FAILED": 1}, "total": 4,
              "items": [{"id": "run-1", "action_type": "MAKE_PRIVATE", "status": "COMPLETED", "token": "%1$s"}]}}
            """.formatted(MARKER);

    private static final RemediationQuery QUERY = new RemediationQuery("plan", "COMPLETED", null, 10, 10);

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private InMemoryRedisCache cache;
    private DspmPagesService service;

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        cache = new InMemoryRedisCache(events);
        when(resolver.resolve(10L)).thenAnswer(inv -> {
            events.add("resolve");
            return CREDENTIAL;
        });
        when(apiClient.saasCategories(CREDENTIAL)).thenAnswer(inv -> parse(CATEGORIES_BODY));
        when(apiClient.fetchAssists(CREDENTIAL)).thenAnswer(inv -> parse(ASSISTS_BODY));
        when(apiClient.remediationActivity(CREDENTIAL, QUERY)).thenAnswer(inv -> parse(REMEDIATION_BODY));
        service = new DspmPagesService(resolver, apiClient, new PagesMapper(), new ScanSettingsMapper(), cache);
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Runnable> everyMethod() {
        Map<String, Runnable> calls = new LinkedHashMap<>();
        calls.put("dspm:dsdocclass:10:all", () -> service.documentClasses(10L));
        calls.put("dspm:dsassists:10:all", () -> service.assists(10L));
        calls.put(DatastoreCacheKeys.remediation(10L, QUERY), () -> service.remediation(10L, QUERY));
        return calls;
    }

    @Test
    @DisplayName("the credential is resolved before the cache read, on a miss and on a hit")
    void credentialFirst() {
        for (int round = 0; round < 2; round++) {
            everyMethod().forEach((key, call) -> {
                events.clear();
                call.run();
                assertThat(events).as(key).startsWith("resolve", "get " + key);
            });
        }
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

    @Test
    @DisplayName("each DTO is cached under its 5.8 key and TTL; a hit makes no vendor call")
    void cached() {
        for (int round = 0; round < 2; round++) {
            everyMethod().values().forEach(Runnable::run);
        }

        verify(apiClient, times(1)).saasCategories(CREDENTIAL);
        verify(apiClient, times(1)).fetchAssists(CREDENTIAL);
        verify(apiClient, times(1)).remediationActivity(CREDENTIAL, QUERY);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(tuple("dspm:dsdocclass:10:all", 10), tuple("dspm:dsassists:10:all", 5),
                        tuple(DatastoreCacheKeys.remediation(10L, QUERY), 1));
        assertThat(DatastoreCacheKeys.remediation(10L, QUERY)).matches("^dspm:dsremed:10:[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("the mapped DTOs reach the caller, and no dropped vendor field is cached")
    void contentAndNoMarker() {
        DocumentClassList classes = service.documentClasses(10L);
        AssistList assists = service.assists(10L);
        RemediationActivity activity = service.remediation(10L, QUERY);

        assertThat(classes.categories()).containsExactly("People Records", "Finance Papers");
        assertThat(classes.rows()).extracting(DocumentClassList.Row::name).containsExactly("Resumes", "Payslips", "Invoices");
        assertThat(assists.assists()).extracting(AssistList.Assist::name, AssistList.Assist::statusLabel)
                .containsExactly(tuple("Office connector", "Active"));
        assertThat(activity.total()).isEqualTo(4);
        assertThat(activity.skip()).isEqualTo(10);
        assertThat(activity.items()).extracting(RemediationActivity.Item::actionLabel).containsExactly("Make Private");
        assertThat(cache.puts).hasSize(3)
                .allSatisfy(put -> assertThat(put.json()).as(put.key()).doesNotContain(MARKER));
    }
}
