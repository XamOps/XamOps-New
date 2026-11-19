package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.EndpointFilePage;
import com.xammer.cloud.dto.matters.datastores.SaasFileListPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Design 5.8 Files page flows with the real mappers (the endpoint one spied), a mocked client and an
 * in-memory cache. Values are invented; samples carry {@link #MARKER}.
 */
class FilesServiceTest {

    private static final MattersCredential CREDENTIAL =
            new MattersCredential(10L, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String MARKER = "<<SYNTHETIC-SAMPLE-4C1D>>";

    private static final String CATEGORIES = """
            {"success": true, "categories": [
              {"category": "financial", "subcategories": ["travel receipts", "invoices"]},
              {"category": "Media R&D", "subcategories": ["field notes", "invoices"]},
              {"category": "other", "subcategories": ["other"]}]}
            """;
    private static final String USERS = """
            {"success": true, "owners": [{"key": "robin@example.test", "value": "1a1b1c1d1e1f202122232425"}],
             "internal_users": ["sam@example.test"], "external_users": []}
            """;
    private static final String SAAS_PAGE = """
            {"success": true, "sensitives": [{"id": "0a0b0c0d0e0f101112131415", "file_name": "Plan Draft.pdf",
              "source": "GOOGLE_WORKSPACE", "extension": "pdf", "category": "financial"}],
             "next_cursor": "eyJuZXh0IjoyfQ"}
            """;
    private static final String ENDPOINT_PAGE = """
            {"success": true, "data": [{"id": "0000AAAA-0000-4000-8000-00000000000C", "name": "notes.txt",
              "sampleMatches": [{"samples": [{"match_text": "%1$s"}]}], "fileModifiedTime": 1789725004}],
             "total": 27}
            """.formatted(MARKER);
    private static final String ENDPOINT_OPTIONS = """
            {"success": true, "data": {"categories": ["financial"], "subcategories": [], "dataElements": [],
              "tags": [], "uids": ["UID-01"], "hostnames": ["Team-Laptop-02"], "users": ["%1$s"]}}
            """.formatted(MARKER);

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> events = new ArrayList<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private EndpointDrawerMapper endpointMapper;
    private InMemoryRedisCache cache;
    private FilesService service;

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        endpointMapper = spy(new EndpointDrawerMapper());
        cache = new InMemoryRedisCache(events);
        when(resolver.resolve(10L)).thenAnswer(inv -> {
            events.add("resolve");
            return CREDENTIAL;
        });
        when(apiClient.saasCategories(CREDENTIAL)).thenAnswer(inv -> parse(CATEGORIES));
        when(apiClient.saasSensitivityUsers(CREDENTIAL)).thenAnswer(inv -> parse(USERS));
        when(apiClient.saasSensitivities(eq(CREDENTIAL), any(SaasFileListQuery.class)))
                .thenAnswer(inv -> parse(SAAS_PAGE));
        when(apiClient.endpointFiles(eq(CREDENTIAL), any(EndpointFileListQuery.class)))
                .thenAnswer(inv -> parse(ENDPOINT_PAGE));
        when(apiClient.endpointAgentsFilterOptions(CREDENTIAL)).thenAnswer(inv -> parse(ENDPOINT_OPTIONS));
        service = new FilesService(resolver, apiClient, new FilesPageMapper(), endpointMapper, cache);
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SaasFileListQuery saasQuery(String... pairs) {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.add(pairs[i], pairs[i + 1]);
        }
        return SaasFileListQuery.parse(params);
    }

    private static List<String> documentClasses(DatastoreFilterOptions options) {
        return options.dimensions().stream().filter(d -> d.key().equals("documentClass")).findFirst().orElseThrow()
                .options().stream().map(DatastoreFilterOptions.Option::value).toList();
    }

    @Test
    @DisplayName("F2: the credential first, then the cache; both values routes on a miss; a hit makes no call")
    void saasOptionsCached() {
        DatastoreFilterOptions first = service.saasFilterOptions(10L, List.of());
        assertThat(events).startsWith("resolve", "get dspm:fsaasopts:10:all");
        DatastoreFilterOptions second = service.saasFilterOptions(10L, List.of());
        assertThat(second).isEqualTo(first);
        verify(apiClient, times(1)).saasCategories(CREDENTIAL);
        verify(apiClient, times(1)).saasSensitivityUsers(CREDENTIAL);
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::key, InMemoryRedisCache.Put::minutes)
                .containsExactly(tuple("dspm:fsaasopts:10:all", 5));
        assertThat(documentClasses(first)).containsExactly("travel receipts", "invoices", "field notes", "other");
    }

    @Test
    @DisplayName("F2 with categories: Document Class narrows to their classes in route order, de-duplicated, "
            + "from the cached entry")
    void saasOptionsNarrowed() {
        service.saasFilterOptions(10L, List.of());
        DatastoreFilterOptions narrowed = service.saasFilterOptions(10L, List.of("Media R&D", "financial"));
        assertThat(documentClasses(narrowed)).containsExactly("travel receipts", "invoices", "field notes");
        assertThat(narrowed.dimensions().stream().filter(d -> d.key().equals("documentClass")).findFirst()
                .orElseThrow().options()).extracting(DatastoreFilterOptions.Option::label)
                .containsExactly("Travel Receipts", "Invoices", "Field Notes");
        assertThat(narrowed.dimensions()).extracting(DatastoreFilterOptions.Dimension::key).containsExactly(
                "sensitives", "category", "appType", "documentClass", "owner", "internalUser", "externalUser",
                "sharing");
        assertThat(documentClasses(service.saasFilterOptions(10L, List.of("unknown")))).isEmpty();
        verify(apiClient, times(1)).saasCategories(CREDENTIAL);
        assertThat(cache.puts).hasSize(1);
    }

    @Test
    @DisplayName("F2 caches nothing when the users call fails, and the failure propagates")
    void saasOptionsUsersFail() {
        when(apiClient.saasSensitivityUsers(CREDENTIAL)).thenThrow(
                new MattersApiException(MattersApiException.Reason.UPSTREAM_UNAVAILABLE, "users"));
        assertThatThrownBy(() -> service.saasFilterOptions(10L, List.of())).isInstanceOfSatisfying(
                MattersApiException.class,
                e -> assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.UPSTREAM_UNAVAILABLE));
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("F1: cached under a hashed key for 2 minutes; another cursor is another key; a hit makes no call")
    void saasFilesCached() {
        SaasFileListPage page = service.saasFiles(10L, saasQuery("q", "plan"));
        service.saasFiles(10L, saasQuery("q", "plan"));
        service.saasFiles(10L, saasQuery("q", "plan", "cursor", "eyJuZXh0IjoyfQ"));
        verify(apiClient, times(2)).saasSensitivities(eq(CREDENTIAL), any(SaasFileListQuery.class));
        assertThat(page.rows()).extracting(SaasFileListPage.Row::sourceLabel).containsExactly("Google Drive");
        assertThat(page.nextCursor()).isEqualTo("eyJuZXh0IjoyfQ");
        assertThat(cache.puts).extracting(InMemoryRedisCache.Put::minutes).containsExactly(2, 2);
        assertThat(cache.puts).allSatisfy(put -> assertThat(put.key()).matches("^dspm:fsaas:10:[0-9a-f]{64}$"));
    }

    @Test
    @DisplayName("F3 maps through build 2's toFilePage; no sample in the cache entry; F4 caches its options")
    void endpointFilesAndOptions() {
        EndpointFilePage page = service.endpointFiles(10L,
                new EndpointFileListQuery(10, 25, null, EndpointFilters.NONE, List.of(), List.of(), List.of(), null));
        verify(endpointMapper).toFilePage(any(JsonNode.class), eq(10), eq(25));
        assertThat(page.total()).isEqualTo(27);
        assertThat(page.rows()).extracting(EndpointFilePage.File::id)
                .containsExactly("0000aaaa-0000-4000-8000-00000000000c");

        DatastoreFilterOptions options = service.endpointFilterOptions(10L);
        service.endpointFilterOptions(10L);
        verify(apiClient, times(1)).endpointAgentsFilterOptions(CREDENTIAL);
        assertThat(options.dimensions()).extracting(DatastoreFilterOptions.Dimension::key).containsExactly(
                "category", "sensitives", "documentClass", "dataElement", "tag", "uid", "hostname", "lastModified");

        assertThat(cache.puts).extracting(put -> put.key().split(":")[1], InMemoryRedisCache.Put::minutes)
                .containsExactly(tuple("fep", 2), tuple("fepopts", 5));
        assertThat(cache.puts).allSatisfy(put -> assertThat(put.json()).doesNotContain(MARKER));
    }

    @Test
    @DisplayName("a credential failure stops every method before the cache and the vendor")
    void credentialFirst() {
        doThrow(new MattersApiException(MattersApiException.Reason.NOT_ENTITLED, "not entitled"))
                .when(resolver).resolve(10L);
        List<Runnable> calls = List.of(
                () -> service.saasFilterOptions(10L, List.of()),
                () -> service.saasFiles(10L, saasQuery()),
                () -> service.endpointFiles(10L, new EndpointFileListQuery(0, 10, null, EndpointFilters.NONE,
                        List.of(), List.of(), List.of(), null)),
                () -> service.endpointFilterOptions(10L));
        calls.forEach(call -> assertThatThrownBy(call::run).isInstanceOf(MattersApiException.class));
        verifyNoInteractions(apiClient);
        assertThat(cache.gets).isEmpty();
        verify(endpointMapper, never()).toFilePage(any(), eq(0), eq(10));
    }
}
