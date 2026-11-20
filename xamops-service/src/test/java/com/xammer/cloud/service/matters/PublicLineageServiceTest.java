package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.EndpointFileLineage;
import com.xammer.cloud.dto.matters.datastores.PublicFileLineage;
import com.xammer.cloud.dto.matters.datastores.PublicLineageDatastorePage;
import com.xammer.cloud.dto.matters.datastores.PublicLineageDatastorePage.Datastore;
import com.xammer.cloud.dto.matters.datastores.PublicLineageFilePage;
import com.xammer.cloud.dto.matters.datastores.PublicLineageFilePage.File;
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
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Build 7 design 5.6 flows with a mocked resolver and {@link EndpointFileService}, an in-memory cache, the
 * real mappers and a real {@link MattersApiClient} on a strict, ordered {@link MockRestServiceServer}: every
 * allowed request is listed, so any other request (an exposure route among them) fails the test. Synthetic
 * values only.
 */
class PublicLineageServiceTest {

    private static final Long CLIENT = 10L;
    private static final String BASE = "https://matters.test";
    private static final String PROJECT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final MattersCredential CREDENTIAL =
            new MattersCredential(CLIENT, BASE, "synthetic-key", "k1", PROJECT_ID);

    private static final String AGENT_A = "0000000000000000000000a1";
    private static final String AGENT_B = "0000000000000000000000b2";
    private static final String AGENT_C = "0000000000000000000000c3";
    private static final String INTEGRATION = "00000000000000000000001f";
    private static final String RESOURCE_A = "Res_Synthetic-A";
    private static final String RESOURCE_B = "Res_Synthetic-B";
    private static final String DOC_ID = "Doc_Synthetic-0001";
    private static final String FILE_UUID = "0000000c-0000-4000-8000-00000000000d";

    private static final String ENDPOINTS_URL = BASE + "/api/v1/assets?category=endpoint&page=1&page_size=100";
    private static final String FILTERS_URL = BASE + "/api/v1/assets/filters?category=saas";
    private static final String SAMPLE_URL = BASE + "/api/v1/assets?integration_id=" + INTEGRATION + "&page_size=1";
    private static final String USERS_URL = BASE + "/api/v2/drive-lineage/users?integration_id=" + INTEGRATION;
    private static final String SNAPSHOT_KEY = "dspm:lnpubds:10:all";

    private static final String NO_INTEGRATIONS = "{\"integration_id\": []}";
    private static final String ONE_INTEGRATION = "{\"integration_id\": [{\"value\": \"" + INTEGRATION + "\", \"count\": 3}]}";
    private static final String GOOGLE_SAMPLE =
            "{\"data\": [{\"source\": {\"provider\": \"GOOGLE_WORKSPACE\"}}], \"pagination\": {\"total\": 3}}";
    private static final String PEOPLE = """
            {"success": true, "data": [
              {"resource_id": "%s", "email": "a@example.com", "display_name": "Person A", "file_count": 3},
              {"resource_id": "r-zero", "email": "z@example.com", "display_name": "Zero", "file_count": 0},
              {"resource_id": "%s", "email": "b@example.com", "file_count": 6}
            ]}
            """.formatted(RESOURCE_A, RESOURCE_B);

    private MattersCredentialResolver resolver;
    private EndpointFileService endpointFileService;
    private MattersAuditService audit;
    private InMemoryRedisCache cache;
    private MockRestServiceServer server;
    private PublicLineageService service;

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        when(resolver.resolve(CLIENT)).thenReturn(CREDENTIAL);
        endpointFileService = mock(EndpointFileService.class);
        audit = mock(MattersAuditService.class);
        cache = new InMemoryRedisCache(new ArrayList<>());
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        MattersApiClient apiClient = new MattersApiClient(restTemplate, new ObjectMapper(), audit);
        service = new PublicLineageService(resolver, apiClient, new PublicLineageMapper(), new FilesPageMapper(),
                endpointFileService, cache);
    }

    private ResponseActions expect(String url) {
        return server.expect(ExpectedCount.once(), requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist("project-id"));
    }

    private static ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private static String countUrl(String agentId) {
        return BASE + "/api/v1/endpoint-agent/agent/" + agentId + "/files?skip=0&limit=1";
    }

    private static String countBody(long total) {
        return "{\"success\": true, \"total\": " + total + ", \"data\": []}";
    }

    private static String endpoints(String... agentsAndNames) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < agentsAndNames.length; i += 2) {
            rows.add("{\"source_id\": \"" + agentsAndNames[i] + "\", \"name\": \"" + agentsAndNames[i + 1]
                    + "\", \"category\": \"endpoint\"}");
        }
        return "{\"data\": [" + String.join(",", rows) + "], \"pagination\": {\"total\": " + rows.size() + "}}";
    }

    private static Datastore endpointNode(String agentId, String name, Long files) {
        return new Datastore("ep-" + agentId, "ENDPOINT", name, "ENDPOINT", files);
    }

    private static Datastore person(String resourceId, String name, String service, long files) {
        return new Datastore("dr-" + INTEGRATION + "-" + resourceId, "DRIVE", name, service, files);
    }

    // ── P1: datastores ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P1 call order and queries: endpoints with files, 0 and 404 left out, then the drive people; cached 5 min")
    void datastoresCallOrderAndCache() {
        expect(ENDPOINTS_URL).andRespond(json(endpoints(AGENT_A, "Lab Laptop", AGENT_B, "Empty Laptop",
                AGENT_C, "Gone Laptop")));
        expect(countUrl(AGENT_A)).andRespond(json(countBody(9)));
        expect(countUrl(AGENT_B)).andRespond(json(countBody(0)));
        expect(countUrl(AGENT_C)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        expect(FILTERS_URL).andRespond(json(ONE_INTEGRATION));
        expect(SAMPLE_URL).andRespond(json(GOOGLE_SAMPLE));
        expect(USERS_URL).andRespond(json(PEOPLE));

        PublicLineageDatastorePage page = service.datastores(CLIENT, null, 0, 7);
        PublicLineageDatastorePage again = service.datastores(CLIENT, null, 0, 7);

        server.verify();
        assertThat(page.rows()).containsExactly(
                endpointNode(AGENT_A, "Lab Laptop", 9L),
                person(RESOURCE_A, "Person A", "GOOGLE_DRIVE", 3),
                person(RESOURCE_B, "b@example.com", "GOOGLE_DRIVE", 6));
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.skip()).isZero();
        assertThat(page.limit()).isEqualTo(7);
        assertThat(again).isEqualTo(page);
        assertThat(cache.events).containsExactly("get " + SNAPSHOT_KEY, "put " + SNAPSHOT_KEY, "get " + SNAPSHOT_KEY);
        assertThat(cache.putsFor(SNAPSHOT_KEY)).singleElement()
                .extracting(InMemoryRedisCache.Put::minutes).isEqualTo(5);
        verify(resolver, times(2)).resolve(CLIENT);
    }

    @Test
    @DisplayName("P1: only the first 25 endpoints are counted")
    void endpointCap() {
        String[] rows = IntStream.range(0, 30)
                .mapToObj(i -> new String[]{String.format("%024x", i + 1), "E" + i})
                .flatMap(java.util.Arrays::stream)
                .toArray(String[]::new);
        expect(ENDPOINTS_URL).andRespond(json(endpoints(rows)));
        for (int i = 0; i < 25; i++) {
            expect(countUrl(String.format("%024x", i + 1))).andRespond(json(countBody(1)));
        }
        expect(FILTERS_URL).andRespond(json(NO_INTEGRATIONS));

        PublicLineageDatastorePage page = service.datastores(CLIENT, null, 0, 50);

        server.verify();
        assertThat(page.total()).isEqualTo(25);
        assertThat(page.rows()).hasSize(25).last().extracting(Datastore::name).isEqualTo("E24");
    }

    @Test
    @DisplayName("P1: only the first 10 integrations are read")
    void integrationCap() {
        String options = IntStream.range(0, 12)
                .mapToObj(i -> "{\"value\": \"" + String.format("%024x", 0x100 + i) + "\"}")
                .collect(Collectors.joining(",", "{\"integration_id\": [", "]}"));
        expect(ENDPOINTS_URL).andRespond(json(endpoints()));
        expect(FILTERS_URL).andRespond(json(options));
        for (int i = 0; i < 10; i++) {
            String id = String.format("%024x", 0x100 + i);
            expect(BASE + "/api/v1/assets?integration_id=" + id + "&page_size=1").andRespond(json("{\"data\": []}"));
            expect(BASE + "/api/v2/drive-lineage/users?integration_id=" + id).andRespond(json("{\"success\": true, \"data\": []}"));
        }

        assertThat(service.datastores(CLIENT, null, 0, 7).rows()).isEmpty();

        server.verify();
    }

    @Test
    @DisplayName("P1: a count 500 keeps the node with files null and caches the list for 1 minute")
    void countServerErrorIsPartial() {
        expect(ENDPOINTS_URL).andRespond(json(endpoints(AGENT_A, "Lab Laptop", AGENT_B, "Desk")));
        expect(countUrl(AGENT_A)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        expect(countUrl(AGENT_B)).andRespond(json("{\"success\": false, \"message\": \"boom\"}"));
        expect(FILTERS_URL).andRespond(json(NO_INTEGRATIONS));

        PublicLineageDatastorePage page = service.datastores(CLIENT, null, 0, 7);

        server.verify();
        assertThat(page.rows()).containsExactly(endpointNode(AGENT_A, "Lab Laptop", null), endpointNode(AGENT_B, "Desk", null));
        assertThat(cache.putsFor(SNAPSHOT_KEY)).singleElement()
                .extracting(InMemoryRedisCache.Put::minutes).isEqualTo(1);
    }

    @Test
    @DisplayName("P1: a key reason on a count propagates and nothing is cached")
    void keyReasonPropagates() {
        expect(ENDPOINTS_URL).andRespond(json(endpoints(AGENT_A, "Lab Laptop")));
        expect(countUrl(AGENT_A)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\": \"Invalid API key\"}"));

        assertThatThrownBy(() -> service.datastores(CLIENT, null, 0, 7))
                .isInstanceOfSatisfying(MattersApiException.class, e ->
                        assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.INVALID_KEY));

        server.verify();
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("P1: a failing endpoint list or filters call propagates and nothing is cached")
    void listLevelFailurePropagates() {
        expect(ENDPOINTS_URL).andRespond(json(endpoints()));
        expect(FILTERS_URL).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> service.datastores(CLIENT, null, 0, 7))
                .isInstanceOfSatisfying(MattersApiException.class, e ->
                        assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.FORBIDDEN_SCOPE));

        server.verify();
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("P1: a failing sample leaves service null and the people still appear; a sample 429 propagates")
    void sampleFailure() {
        expect(ENDPOINTS_URL).andRespond(json(endpoints()));
        expect(FILTERS_URL).andRespond(json(ONE_INTEGRATION));
        expect(SAMPLE_URL).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\": \"bad\"}"));
        expect(USERS_URL).andRespond(json(PEOPLE));

        assertThat(service.datastores(CLIENT, null, 0, 7).rows()).containsExactly(
                person(RESOURCE_A, "Person A", null, 3), person(RESOURCE_B, "b@example.com", null, 6));
        server.verify();

        server.reset();
        cache = new InMemoryRedisCache(new ArrayList<>());
        setUpServiceWithCache();
        expect(ENDPOINTS_URL).andRespond(json(endpoints()));
        expect(FILTERS_URL).andRespond(json(ONE_INTEGRATION));
        expect(SAMPLE_URL).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> service.datastores(CLIENT, null, 0, 7))
                .isInstanceOfSatisfying(MattersApiException.class, e ->
                        assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.RATE_LIMITED));
        server.verify();
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("P1: people 404 gives none for the integration; people 500 leaves it out and caches the rest for 1 minute")
    void peopleFailures() {
        expect(ENDPOINTS_URL).andRespond(json(endpoints(AGENT_A, "Lab Laptop")));
        expect(countUrl(AGENT_A)).andRespond(json(countBody(2)));
        expect(FILTERS_URL).andRespond(json(ONE_INTEGRATION));
        expect(SAMPLE_URL).andRespond(json(GOOGLE_SAMPLE));
        expect(USERS_URL).andRespond(json("{\"success\": false, \"message\": \"asset not found\"}"));

        assertThat(service.datastores(CLIENT, null, 0, 7).rows()).containsExactly(endpointNode(AGENT_A, "Lab Laptop", 2L));
        server.verify();

        server.reset();
        cache = new InMemoryRedisCache(new ArrayList<>());
        setUpServiceWithCache();
        expect(ENDPOINTS_URL).andRespond(json(endpoints(AGENT_A, "Lab Laptop")));
        expect(countUrl(AGENT_A)).andRespond(json(countBody(2)));
        expect(FILTERS_URL).andRespond(json(ONE_INTEGRATION));
        expect(SAMPLE_URL).andRespond(json(GOOGLE_SAMPLE));
        server.expect(ExpectedCount.twice(), requestTo(USERS_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(service.datastores(CLIENT, null, 0, 7).rows()).containsExactly(endpointNode(AGENT_A, "Lab Laptop", 2L));
        server.verify();
        assertThat(cache.putsFor(SNAPSHOT_KEY)).singleElement()
                .extracting(InMemoryRedisCache.Put::minutes).isEqualTo(1);
    }

    @Test
    @DisplayName("P1 (R1): a non-drive integration's 400 leaves it out; the endpoints and the other integration's people stay")
    void oneFailingIntegrationKeepsTheRest() {
        String failing = "00000000000000000000002e";
        String twoIntegrations = "{\"integration_id\": [{\"value\": \"" + failing + "\"}, {\"value\": \"" + INTEGRATION
                + "\"}]}";
        expect(ENDPOINTS_URL).andRespond(json(endpoints(AGENT_A, "Lab Laptop")));
        expect(countUrl(AGENT_A)).andRespond(json(countBody(2)));
        expect(FILTERS_URL).andRespond(json(twoIntegrations));
        expect(BASE + "/api/v1/assets?integration_id=" + failing + "&page_size=1").andRespond(json(
                "{\"data\": [{\"source\": {\"provider\": \"SLACK\"}}]}"));
        expect(BASE + "/api/v2/drive-lineage/users?integration_id=" + failing).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\": \"unsupported integration\"}"));
        expect(SAMPLE_URL).andRespond(json(GOOGLE_SAMPLE));
        expect(USERS_URL).andRespond(json(PEOPLE));

        PublicLineageDatastorePage page = service.datastores(CLIENT, null, 0, 7);

        server.verify();
        assertThat(page.rows()).containsExactly(
                endpointNode(AGENT_A, "Lab Laptop", 2L),
                person(RESOURCE_A, "Person A", "GOOGLE_DRIVE", 3),
                person(RESOURCE_B, "b@example.com", "GOOGLE_DRIVE", 6));
        assertThat(page.total()).isEqualTo(3);
        assertThat(cache.putsFor(SNAPSHOT_KEY)).singleElement()
                .extracting(InMemoryRedisCache.Put::minutes).isEqualTo(1);
    }

    @Test
    @DisplayName("P1 (R1): a people 429 still fails the list, as it applies to every call; nothing is cached")
    void peopleRateLimitPropagates() {
        expect(ENDPOINTS_URL).andRespond(json(endpoints()));
        expect(FILTERS_URL).andRespond(json(ONE_INTEGRATION));
        expect(SAMPLE_URL).andRespond(json(GOOGLE_SAMPLE));
        server.expect(ExpectedCount.manyTimes(), requestTo(USERS_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> service.datastores(CLIENT, null, 0, 7))
                .isInstanceOfSatisfying(MattersApiException.class, e ->
                        assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.RATE_LIMITED));
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("P1 (R3): two concurrent cold loads share one build: one set of vendor calls, one cache write, same page")
    void concurrentColdLoadsShareOneBuild() throws Exception {
        cache = new SynchronizedCache();
        setUpServiceWithCache();
        CountDownLatch buildStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ResponseCreator slowEndpoints = request -> {
            buildStarted.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return json(endpoints(AGENT_A, "Lab Laptop")).createResponse(request);
        };
        expect(ENDPOINTS_URL).andRespond(slowEndpoints);
        expect(countUrl(AGENT_A)).andRespond(json(countBody(9)));
        expect(FILTERS_URL).andRespond(json(ONE_INTEGRATION));
        expect(SAMPLE_URL).andRespond(json(GOOGLE_SAMPLE));
        expect(USERS_URL).andRespond(json(PEOPLE));

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<PublicLineageDatastorePage> first = callers.submit(() -> service.datastores(CLIENT, null, 0, 7));
            assertThat(buildStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<PublicLineageDatastorePage> second = callers.submit(() -> service.datastores(CLIENT, "lab", 0, 7));
            Thread.sleep(150);
            assertThat(second.isDone()).isFalse();
            release.countDown();

            PublicLineageDatastorePage a = first.get(10, TimeUnit.SECONDS);
            PublicLineageDatastorePage b = second.get(10, TimeUnit.SECONDS);

            server.verify();
            assertThat(a.rows()).hasSize(3);
            assertThat(b.rows()).containsExactly(endpointNode(AGENT_A, "Lab Laptop", 9L));
            assertThat(cache.putsFor(SNAPSHOT_KEY)).hasSize(1);
        } finally {
            callers.shutdownNow();
        }
    }

    /** The in-memory cache, safe for the concurrent callers of the single-flight test. */
    private static final class SynchronizedCache extends InMemoryRedisCache {
        @Override
        public synchronized <T> java.util.Optional<T> get(String key,
                                                          com.fasterxml.jackson.core.type.TypeReference<T> type) {
            return super.get(key, type);
        }

        @Override
        public synchronized <T> void put(String key, T value, int minutes) {
            super.put(key, value, minutes);
        }
    }

    @Test
    @DisplayName("P1: a cache hit makes no request; search is a case-insensitive substring, total after search, then sliced")
    void searchAndSlice() {
        List<Datastore> rows = List.of(
                endpointNode(AGENT_A, "Lab Laptop", 9L),
                endpointNode(AGENT_B, "Desk", 1L),
                person(RESOURCE_A, "LAB person", "GOOGLE_DRIVE", 3),
                new Datastore(null, "DRIVE", null, null, 1L),
                person(RESOURCE_B, "Another lab", "GOOGLE_DRIVE", 6));
        cache.seed(SNAPSHOT_KEY, new PublicLineageService.PublicDatastoreSnapshot(rows));

        PublicLineageDatastorePage first = service.datastores(CLIENT, "lAb", 0, 2);
        PublicLineageDatastorePage second = service.datastores(CLIENT, "lab", 2, 2);
        PublicLineageDatastorePage past = service.datastores(CLIENT, "lab", 7, 7);
        PublicLineageDatastorePage all = service.datastores(CLIENT, null, 3, 7);

        server.verify();
        verifyNoInteractions(audit);
        assertThat(first.rows()).containsExactly(rows.get(0), rows.get(2));
        assertThat(first.total()).isEqualTo(3);
        assertThat(second.rows()).containsExactly(rows.get(4));
        assertThat(second.total()).isEqualTo(3);
        assertThat(past.rows()).isEmpty();
        assertThat(past.total()).isEqualTo(3);
        assertThat(all.rows()).containsExactly(rows.get(3), rows.get(4));
        assertThat(all.total()).isEqualTo(5);
        assertThat(cache.puts).isEmpty();
    }

    // ── P2: files ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P2 endpoint: one agent files page per skip and limit, cached 2 minutes per page")
    void endpointFilesCachedPerPage() {
        PublicLineageKey key = PublicLineageKey.endpoint(AGENT_A);
        String body = """
                {"success": true, "total": 9, "data": [{"id": "%s", "name": "notes.pdf", "extension": "pdf",
                  "currentUser": "user-a", "fileModifiedTime": 1775000100, "fileCreatedTime": 1775000000}]}
                """.formatted(FILE_UUID);
        expect(BASE + "/api/v1/endpoint-agent/agent/" + AGENT_A + "/files?skip=7&limit=7").andRespond(json(body));
        expect(BASE + "/api/v1/endpoint-agent/agent/" + AGENT_A + "/files?skip=0&limit=7").andRespond(json(body));

        PublicLineageFilePage page = service.files(CLIENT, key, 7, 7);
        PublicLineageFilePage again = service.files(CLIENT, key, 7, 7);
        service.files(CLIENT, key, 0, 7);

        server.verify();
        assertThat(page.rows()).containsExactly(
                new File(FILE_UUID, "notes.pdf", "pdf", "user-a", 1775000100L, 1775000000L));
        assertThat(page.total()).isEqualTo(9);
        assertThat(page.skip()).isEqualTo(7);
        assertThat(again).isEqualTo(page);
        assertThat(cache.putsFor("dspm:lnpubfiles:10:ep-" + AGENT_A + ":7-7")).singleElement()
                .extracting(InMemoryRedisCache.Put::minutes).isEqualTo(2);
        assertThat(cache.contains("dspm:lnpubfiles:10:ep-" + AGENT_A + ":0-7")).isTrue();
    }

    @Test
    @DisplayName("P2 endpoint: an unknown agent's 500 propagates and nothing is cached")
    void endpointFilesFailure() {
        expect(BASE + "/api/v1/endpoint-agent/agent/" + AGENT_A + "/files?skip=0&limit=7")
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> service.files(CLIENT, PublicLineageKey.endpoint(AGENT_A), 0, 7))
                .isInstanceOfSatisfying(MattersApiException.class, e ->
                        assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.BAD_UPSTREAM_RESPONSE));

        server.verify();
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("P2 drive: the whole list is fetched once, cached 5 minutes under a hashed key, then sliced")
    void driveFilesCachedAndSliced() {
        PublicLineageKey key = PublicLineageKey.drive(INTEGRATION, RESOURCE_A);
        String rows = IntStream.range(0, 10)
                .mapToObj(i -> "{\"doc_id\": \"Doc_Synthetic-" + i + "\", \"title\": \"file" + i + ".pdf\", "
                        + "\"doc_type\": \"PDF\", \"last_seen\": \"2026-11-01T10:00:00+00:00\"}")
                .collect(Collectors.joining(","));
        expect(BASE + "/api/v2/drive-lineage/user-files?integration_id=" + INTEGRATION + "&resource_id=" + RESOURCE_A)
                .andRespond(json("{\"success\": true, \"data\": [" + rows + "]}"));

        PublicLineageFilePage page = service.files(CLIENT, key, 7, 7);
        PublicLineageFilePage first = service.files(CLIENT, key, 0, 7);

        server.verify();
        assertThat(page.total()).isEqualTo(10);
        assertThat(page.rows()).extracting(File::id)
                .containsExactly("Doc_Synthetic-7", "Doc_Synthetic-8", "Doc_Synthetic-9");
        assertThat(page.rows().get(0)).isEqualTo(new File("Doc_Synthetic-7", "file7.pdf", "pdf", null, 1793527200L, null));
        assertThat(first.rows()).hasSize(7);
        assertThat(first.total()).isEqualTo(10);
        String cacheKey = DatastoreCacheKeys.lineagePublicDriveFiles(CLIENT, key);
        assertThat(cacheKey).doesNotContain(RESOURCE_A);
        assertThat(cache.putsFor(cacheKey)).singleElement()
                .extracting(InMemoryRedisCache.Put::minutes).isEqualTo(5);
    }

    @Test
    @DisplayName("P2 drive: a 404 is an empty page and is not cached")
    void driveFilesNotFound() {
        PublicLineageKey key = PublicLineageKey.drive(INTEGRATION, RESOURCE_A);
        expect(BASE + "/api/v2/drive-lineage/user-files?integration_id=" + INTEGRATION + "&resource_id=" + RESOURCE_A)
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        PublicLineageFilePage page = service.files(CLIENT, key, 7, 7);

        server.verify();
        assertThat(page).isEqualTo(new PublicLineageFilePage(List.of(), 0, 7, 7));
        assertThat(cache.puts).isEmpty();
    }

    // ── P3: file lineage ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("P3 endpoint: delegates to build 3's EndpointFileService and wraps its answer; no request here")
    void endpointLineageDelegates() {
        EndpointFileLineage.Asset asset = new EndpointFileLineage.Asset("notes.pdf", "Lab Laptop", "user-a",
                1775000000L, List.of(new SaasFileLineage.Event(null, "File Modified", "user-a", 1775000100L)));
        SaasFileLineage copies = new SaasFileLineage(true, null, List.of(), List.of());
        when(endpointFileService.lineage(CLIENT, FILE_UUID)).thenReturn(new EndpointFileLineage(asset, copies));

        PublicFileLineage lineage = service.fileLineage(CLIENT, PublicLineageKey.endpoint(AGENT_A), FILE_UUID);

        server.verify();
        assertThat(lineage).isEqualTo(new PublicFileLineage("ENDPOINT", asset, copies, null));
        verify(endpointFileService).lineage(CLIENT, FILE_UUID);
        assertThat(cache.puts).isEmpty();
    }

    @Test
    @DisplayName("P3 drive: the asset through build 3's mapper, the owner e-mail, unavailable copies; cached 5 minutes")
    void driveLineage() {
        PublicLineageKey key = PublicLineageKey.drive(INTEGRATION, RESOURCE_A);
        expect(BASE + "/api/v2/drive-lineage/asset?integration_id=" + INTEGRATION + "&doc_id=" + DOC_ID)
                .andRespond(json("""
                        {"success": true, "data": {
                          "asset": {"doc_id": "%s", "title": "Budget.xlsx", "doc_type": "Microsoft Excel",
                                    "first_seen": "2026-11-01T10:00:00+00:00", "owner_email": "owner@example.com"},
                          "events": [{"event_type": "SHARED", "actor_email": "owner@example.com",
                                      "timestamp": "2026-11-02T00:00:00Z"}],
                          "edges": [], "alerts": []}}
                        """.formatted(DOC_ID)));

        PublicFileLineage lineage = service.fileLineage(CLIENT, key, DOC_ID);
        PublicFileLineage again = service.fileLineage(CLIENT, key, DOC_ID);

        server.verify();
        assertThat(lineage.kind()).isEqualTo("DRIVE");
        assertThat(lineage.ownerEmail()).isEqualTo("owner@example.com");
        assertThat(lineage.copies()).isEqualTo(SaasFileLineage.unavailable());
        assertThat(lineage.asset().title()).isEqualTo("Budget.xlsx");
        assertThat(lineage.asset().device()).isNull();
        assertThat(lineage.asset().osUser()).isNull();
        assertThat(lineage.asset().firstSeenAt()).isEqualTo(1793527200L);
        assertThat(lineage.asset().events()).singleElement().satisfies(event -> {
            assertThat(event.actor()).isEqualTo("owner@example.com");
            assertThat(event.occurredAt()).isEqualTo(1793577600L);
            assertThat(event.laneKey()).isNull();
        });
        assertThat(again).isEqualTo(lineage);
        String cacheKey = DatastoreCacheKeys.lineagePublicDriveAsset(CLIENT, INTEGRATION, DOC_ID);
        assertThat(cache.putsFor(cacheKey)).singleElement()
                .extracting(InMemoryRedisCache.Put::minutes).isEqualTo(5);
        verifyNoInteractions(endpointFileService);
    }

    @Test
    @DisplayName("P3 drive: 404 'asset not found' is a null asset and is cached; a 500 propagates and nothing is cached")
    void driveLineageFailures() {
        PublicLineageKey key = PublicLineageKey.drive(INTEGRATION, RESOURCE_A);
        String url = BASE + "/api/v2/drive-lineage/asset?integration_id=" + INTEGRATION + "&doc_id=" + DOC_ID;
        expect(url).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"success\": false, \"message\": \"asset not found\"}"));

        PublicFileLineage none = service.fileLineage(CLIENT, key, DOC_ID);
        PublicFileLineage again = service.fileLineage(CLIENT, key, DOC_ID);

        server.verify();
        assertThat(none).isEqualTo(new PublicFileLineage("DRIVE", null, SaasFileLineage.unavailable(), null));
        assertThat(again).isEqualTo(none);
        assertThat(cache.puts).hasSize(1);

        server.reset();
        String other = "Doc_Synthetic-0002";
        server.expect(ExpectedCount.twice(),
                        requestTo(BASE + "/api/v2/drive-lineage/asset?integration_id=" + INTEGRATION + "&doc_id=" + other))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> service.fileLineage(CLIENT, key, other))
                .isInstanceOfSatisfying(MattersApiException.class, e ->
                        assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.UPSTREAM_UNAVAILABLE));
        server.verify();
        assertThat(cache.contains(DatastoreCacheKeys.lineagePublicDriveAsset(CLIENT, INTEGRATION, other))).isFalse();
    }

    private void setUpServiceWithCache() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        MattersApiClient apiClient = new MattersApiClient(restTemplate, new ObjectMapper(), audit);
        service = new PublicLineageService(resolver, apiClient, new PublicLineageMapper(), new FilesPageMapper(),
                endpointFileService, cache);
    }
}
