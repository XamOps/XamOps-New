package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationDetail;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationList;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Build 5, plan 1.4: the per-datastore fan-out behind the Misconfigurations page. Mocked client,
 * resolver and labeler; real row and misconfiguration mappers; an in-memory cache; a real 4-thread pool.
 */
class MisconfigurationServiceTest {

    private static final Long CLIENT = 10L;
    private static final MattersCredential CREDENTIAL =
            new MattersCredential(CLIENT, "https://matters.test", "synthetic-key", "k1", "p1");
    private static final String KEY = "dspm:dsmisconf:10:all";

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, List<String>> inventory = new LinkedHashMap<>();
    private final Map<String, Long> declaredTotals = new LinkedHashMap<>();

    private MattersCredentialResolver resolver;
    private MattersApiClient apiClient;
    private DatastoreAccountLabeler labeler;
    private SynchronizedCache cache;
    private ExecutorService pool;
    private MisconfigurationService service;

    /** The in-memory cache, safe for the concurrent callers of the single-flight test. */
    private static final class SynchronizedCache extends InMemoryRedisCache {
        @Override
        public synchronized <T> Optional<T> get(String key, TypeReference<T> typeReference) {
            return super.get(key, typeReference);
        }

        @Override
        public synchronized <T> void put(String key, T value, int minutes) {
            super.put(key, value, minutes);
        }

        synchronized boolean has(String key) {
            return contains(key);
        }
    }

    @BeforeEach
    void setUp() {
        resolver = mock(MattersCredentialResolver.class);
        apiClient = mock(MattersApiClient.class);
        labeler = mock(DatastoreAccountLabeler.class);
        cache = new SynchronizedCache();
        pool = Executors.newFixedThreadPool(4);
        when(resolver.resolve(CLIENT)).thenReturn(CREDENTIAL);
        when(apiClient.listAssets(eq(CREDENTIAL), anyMap())).thenAnswer(inv -> assetsPage(inv.getArgument(1)));
        when(apiClient.inventoryIssues(eq(CREDENTIAL), anyString()))
                .thenAnswer(inv -> issuesBody(inv.getArgument(1)));
        when(labeler.label(eq(CREDENTIAL), anyList())).thenAnswer(inv -> {
            List<DatastoreRowMapper.MappedRow> rows = inv.getArgument(1);
            return rows.stream().map(m -> labelled(m.row(), "label-" + m.integrationId())).toList();
        });
        service = new MisconfigurationService(resolver, apiClient, new DatastoreRowMapper(), labeler,
                new MisconfigurationMapper(), cache, pool);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ── synthetic vendor ─────────────────────────────────────────────────────────────────────────

    private static String id(int n) {
        return String.format("%024x", n);
    }

    private void datastores(String category, int from, int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(id(from + i));
        }
        inventory.put(category, ids);
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A 100-row page of the category's datastores, with {@code pagination.total}. */
    private JsonNode assetsPage(Map<String, List<String>> query) {
        String category = query.get("category").get(0);
        int page = Integer.parseInt(query.get("page").get(0));
        int size = Integer.parseInt(query.get("page_size").get(0));
        List<String> ids = inventory.getOrDefault(category, List.of());
        List<String> rows = new ArrayList<>();
        for (int i = (page - 1) * size; i < Math.min(ids.size(), page * size); i++) {
            String sourceId = ids.get(i);
            rows.add("""
                    {"id":"asset-%1$s","source_id":"%1$s","name":"store-%1$s","category":"%2$s","subtype":"S3",
                     "risk":{"level":"HIGH","score":1.0},"sensitives":{"record_count":2,"types":["PII"]},
                     "source":{"account":"integration-1","provider":"S3","region":"eu-west-1"},
                     "scan_status":{"status":"CLASSIFIED","last_scanned_at":1700000000}}
                    """.formatted(sourceId, category));
        }
        long total = declaredTotals.getOrDefault(category, (long) ids.size());
        return parse("{\"data\":[" + String.join(",", rows) + "],\"pagination\":{\"total\":" + total + "}}");
    }

    /** Every datastore reports p-all; even-numbered ones also report p-even. */
    private JsonNode issuesBody(String sourceId) {
        boolean even = Long.parseLong(sourceId, 16) % 2 == 0;
        return parse("""
                {"success":true,"risk":[
                  {"risk":"Everywhere","policy_id":"p-all","severity":"high","source":"matters",
                   "compliance":{"name":"Framework A","clause":"1"}}%s]}
                """.formatted(even
                ? ",{\"risk\":\"Even only\",\"policy_id\":\"p-even\",\"severity\":\"medium\",\"source\":\"upwind\"}"
                : ""));
    }

    private static DatastoreRow labelled(DatastoreRow row, String label) {
        DatastoreRow.Source source = row.source();
        return new DatastoreRow(row.id(), row.sourceId(), row.name(), row.category(), row.subtype(),
                row.subtypeLabel(), row.risk(), row.sensitives(),
                new DatastoreRow.Source(source.provider(), source.providerLabel(), label, source.region()),
                row.scan(), row.agent());
    }

    private int issuesCalls() {
        return (int) mockingDetails(apiClient).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("inventoryIssues")).count();
    }

    private int assetCalls() {
        return (int) mockingDetails(apiClient).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("listAssets")).count();
    }

    // ── cache ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a cache hit still resolves the credential and makes no vendor call")
    void cacheHit() {
        cache.seed(KEY, new MisconfigurationSnapshot(0, false, List.of()));

        MisconfigurationList list = service.list(CLIENT);

        assertThat(list.rows()).isEmpty();
        verify(resolver).resolve(CLIENT);
        verifyNoInteractions(apiClient, labeler);
    }

    @Test
    @DisplayName("a miss pages cloud (2 pages) then on-prem, calls issues once per 24-hex datastore and caches 5 minutes")
    void missLoadsEveryDatastore() {
        datastores("cloud", 1, 150);
        datastores("on_prem", 1000, 3);
        inventory.get("on_prem").add("not-a-source-id");

        MisconfigurationList list = service.list(CLIENT);

        Map<String, List<String>> first = new LinkedHashMap<>();
        first.put("category", List.of("cloud"));
        first.put("page", List.of("1"));
        first.put("page_size", List.of("100"));
        first.put("sort", List.of("-severity"));
        verify(apiClient).listAssets(CREDENTIAL, first);
        Map<String, List<String>> second = new LinkedHashMap<>(first);
        second.put("page", List.of("2"));
        verify(apiClient).listAssets(CREDENTIAL, second);
        Map<String, List<String>> onPrem = new LinkedHashMap<>(first);
        onPrem.put("category", List.of("on_prem"));
        verify(apiClient).listAssets(CREDENTIAL, onPrem);
        assertThat(assetCalls()).isEqualTo(3);
        verify(labeler, times(1)).label(eq(CREDENTIAL), anyList());

        assertThat(issuesCalls()).isEqualTo(153);
        verify(apiClient).inventoryIssues(CREDENTIAL, id(1));
        verify(apiClient).inventoryIssues(CREDENTIAL, id(1002));
        verify(apiClient, never()).inventoryIssues(CREDENTIAL, "not-a-source-id");

        assertThat(list.datastoresRead()).isEqualTo(153);
        assertThat(list.capped()).isFalse();
        assertThat(list.totalAffectedResources()).isEqualTo(153);
        assertThat(list.rows()).extracting(MisconfigurationList.Row::policyId).containsExactly("p-all", "p-even");
        assertThat(list.rows().get(0).affectedResources()).isEqualTo(153);
        assertThat(list.rows().get(1).affectedResources()).isEqualTo(77);
        assertThat(list.rows().get(0).cloudAccounts()).containsExactly("label-integration-1");
        assertThat(cache.putsFor(KEY)).singleElement().satisfies(put -> assertThat(put.minutes()).isEqualTo(5));
    }

    @Test
    @DisplayName("the snapshot survives the cache's JSON round trip: a second call answers the same from cache")
    void snapshotRoundTrips() {
        datastores("cloud", 1, 4);

        MisconfigurationList fresh = service.list(CLIENT);
        MisconfigurationDetail freshDetail = service.detail(CLIENT, "p-even");
        MisconfigurationList cached = service.list(CLIENT);

        assertThat(cached).isEqualTo(fresh);
        assertThat(freshDetail.affected()).extracting(a -> a.datastore().sourceId()).containsExactly(id(2), id(4));
        assertThat(freshDetail.affected()).extracting(MisconfigurationDetail.AffectedDatastore::lastScannedAt)
                .containsOnly(1700000000L);
        assertThat(assetCalls()).isEqualTo(2);
        assertThat(issuesCalls()).isEqualTo(4);
    }

    @Test
    @DisplayName("detail of an unknown policy is ASSET_NOT_FOUND")
    void unknownPolicy() {
        datastores("cloud", 1, 2);

        assertThatThrownBy(() -> service.detail(CLIENT, "p-unknown"))
                .isInstanceOfSatisfying(MattersApiException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(MattersApiException.Reason.ASSET_NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Misconfiguration not found");
                });
    }

    // ── failures ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ASSET_NOT_FOUND on one datastore counts as no findings")
    void notFoundIsEmpty() {
        datastores("cloud", 1, 3);
        doThrow(new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, "Asset not found"))
                .when(apiClient).inventoryIssues(CREDENTIAL, id(2));

        MisconfigurationList list = service.list(CLIENT);

        assertThat(list.datastoresRead()).isEqualTo(3);
        assertThat(list.totalAffectedResources()).isEqualTo(2);
        assertThat(list.rows()).extracting(MisconfigurationList.Row::policyId).containsExactly("p-all");
    }

    @Test
    @DisplayName("INVALID_KEY on one datastore is thrown as is, nothing is cached, and the next call loads again")
    void otherFailureIsThrownAndNotCached() {
        datastores("cloud", 1, 12);
        MattersApiException invalid =
                new MattersApiException(MattersApiException.Reason.INVALID_KEY, "Matters rejected the key");
        doThrow(invalid).when(apiClient).inventoryIssues(CREDENTIAL, id(5));

        assertThatThrownBy(() -> service.list(CLIENT)).isSameAs(invalid);
        assertThat(cache.has(KEY)).isFalse();
        assertThat(cache.putsFor(KEY)).isEmpty();

        doAnswer(inv -> issuesBody(id(5))).when(apiClient).inventoryIssues(CREDENTIAL, id(5));
        assertThat(service.list(CLIENT).datastoresRead()).isEqualTo(12);
        assertThat(assetCalls()).isEqualTo(4);
        assertThat(cache.has(KEY)).isTrue();
    }

    @Test
    @DisplayName("501 datastores: 500 issues calls and capped")
    void capAtFiveHundred() {
        datastores("cloud", 1, 501);

        MisconfigurationList list = service.list(CLIENT);

        assertThat(issuesCalls()).isEqualTo(500);
        verify(apiClient, never()).inventoryIssues(CREDENTIAL, id(501));
        assertThat(list.datastoresRead()).isEqualTo(500);
        assertThat(list.capped()).isTrue();
    }

    @Test
    @DisplayName("a vendor total above 500 sets capped even when fewer rows came back")
    void cappedFollowsTheVendorTotal() {
        datastores("cloud", 1, 3);
        declaredTotals.put("on_prem", 600L);

        assertThat(service.list(CLIENT).capped()).isTrue();
    }

    // ── concurrency ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("at most 5 issues calls are in flight, and more than 1")
    void fanOutIsBounded() {
        datastores("cloud", 1, 40);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        doAnswer(inv -> {
            int now = active.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(15);
                return issuesBody(inv.getArgument(1));
            } finally {
                active.decrementAndGet();
            }
        }).when(apiClient).inventoryIssues(eq(CREDENTIAL), anyString());

        assertThat(service.list(CLIENT).datastoresRead()).isEqualTo(40);

        assertThat(peak.get()).isBetween(2, MisconfigurationService.FAN_OUT);
    }

    @Test
    @DisplayName("helper threads see the caller's authentication and request attributes, and are cleared after")
    void helpersCarryTheRequestContext() throws Exception {
        datastores("cloud", 1, 20);
        Authentication caller = new UsernamePasswordAuthenticationToken("operator@example.test", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(caller);
        RequestAttributes attributes = new ServletRequestAttributes(new MockHttpServletRequest());
        RequestContextHolder.setRequestAttributes(attributes);
        Thread requestThread = Thread.currentThread();
        List<Object[]> seen = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            seen.add(new Object[]{Thread.currentThread(),
                    SecurityContextHolder.getContext().getAuthentication(),
                    RequestContextHolder.getRequestAttributes()});
            Thread.sleep(10);
            return issuesBody(inv.getArgument(1));
        }).when(apiClient).inventoryIssues(eq(CREDENTIAL), anyString());

        service.list(CLIENT);

        List<Object[]> onHelpers = seen.stream().filter(s -> s[0] != requestThread).toList();
        assertThat(onHelpers).isNotEmpty();
        for (Object[] call : onHelpers) {
            assertThat(call[1]).isSameAs(caller);
            assertThat(call[2]).isSameAs(attributes);
        }
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(caller);
        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(attributes);

        CountDownLatch everyThread = new CountDownLatch(4);
        Callable<Object[]> probe = () -> {
            everyThread.countDown();
            everyThread.await(5, TimeUnit.SECONDS);
            return new Object[]{SecurityContextHolder.getContext().getAuthentication(),
                    RequestContextHolder.getRequestAttributes()};
        };
        for (Future<Object[]> result : pool.invokeAll(List.of(probe, probe, probe, probe))) {
            assertThat(result.get()).containsOnlyNulls();
        }
    }

    @Test
    @DisplayName("two concurrent list calls share one load and get the same rows")
    void singleFlight() throws Exception {
        datastores("cloud", 1, 6);
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            loadStarted.countDown();
            release.await(5, TimeUnit.SECONDS);
            return assetsPage(inv.getArgument(1));
        }).when(apiClient).listAssets(eq(CREDENTIAL), anyMap());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<MisconfigurationList> first = callers.submit(() -> service.list(CLIENT));
            assertThat(loadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            AtomicInteger waiting = new AtomicInteger();
            Future<MisconfigurationList> second = callers.submit(() -> {
                waiting.incrementAndGet();
                return service.list(CLIENT);
            });
            long until = System.currentTimeMillis() + 5_000;
            while (waiting.get() == 0 && System.currentTimeMillis() < until) {
                Thread.sleep(5);
            }
            Thread.sleep(150);
            release.countDown();

            MisconfigurationList a = first.get(10, TimeUnit.SECONDS);
            MisconfigurationList b = second.get(10, TimeUnit.SECONDS);
            assertThat(b).isEqualTo(a);
            assertThat(assetCalls()).isEqualTo(2);
            assertThat(issuesCalls()).isEqualTo(6);
            assertThat(cache.putsFor(KEY)).hasSize(1);
        } finally {
            callers.shutdownNow();
        }
    }
}
