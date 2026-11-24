package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationDetail;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationList;
import com.xammer.cloud.dto.matters.misconfigurations.MisconfigurationSnapshot;
import com.xammer.cloud.service.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The XamLens Misconfigurations page (build 5, plan 1.4). The console's own risk routes refuse project
 * keys, so the page is aggregated from the public per-datastore {@code issues/overview}, called once per
 * cloud and on-prem datastore of {@code /api/v1/assets}.
 *
 * <h2>Cost</h2>
 * One load is up to {@value #MAX_DATASTORES} issues calls plus the asset pages, so it runs
 * {@value #FAN_OUT} calls at a time (the request thread and up to four helpers on
 * {@code dspmFanOutExecutor}), is cached for {@value #CACHE_MINUTES} minutes per client, and concurrent
 * loads of one client share a single flight. A failure other than not-found fails the whole load and is
 * never cached: partial numbers would be wrong numbers. The datastore that failed is read first on the
 * client's next load, so a datastore that keeps failing costs a Retry about {@value #FAN_OUT} issues calls
 * instead of the whole fan-out; the order of the results does not change.
 *
 * <h2>Time budget</h2>
 * The fan-out stops starting calls at a deadline that scales with the datastore count:
 * {@value #CALL_ALLOWANCE_MS} ms per call per worker (about twice the 161 ms measured sequentially), at
 * least {@value #MIN_FAN_OUT_MS} ms, and never later than {@value #LOAD_BUDGET_MS} ms after the load
 * started. 500 datastores get 30 s; 88 get the 15 s floor. The deadline is checked before each call
 * starts, so a call already in flight runs to its end: normally well under a second, which keeps the
 * request inside the browser's 45 s deadline. The worst case is a call that hangs: {@link MattersApiClient}
 * waits up to its 20 s read timeout and retries once, so the request can end about 40 s after the
 * deadline. The browser then reports a timeout, and the load still completes and is cached if it
 * succeeds.
 *
 * <h2>Request state on helpers</h2>
 * Every vendor call goes through {@link MattersApiClient}, which writes the audit rows. Helpers carry the
 * caller's authentication and request attributes, so those rows keep their operator, impersonation, IP
 * and user agent; {@code tenantTaskDecorator} carries the tenant and MDC. The request thread joins every
 * helper before it returns, so no helper touches request state afterwards.
 */
@Service
@Slf4j
public class MisconfigurationService {

    static final int CACHE_MINUTES = 5;
    static final int FAN_OUT = 5;
    static final int MAX_DATASTORES = 500;
    static final int ASSET_PAGE_SIZE = 100;
    static final long MIN_FAN_OUT_MS = 15_000;
    static final long CALL_ALLOWANCE_MS = 300;
    static final long LOAD_BUDGET_MS = 35_000;

    static final String NOT_FOUND_MESSAGE = "Misconfiguration not found";
    static final String TOO_SLOW_MESSAGE = "Matters took too long to list misconfigurations";

    private static final List<DatastoreTab> TABS = List.of(DatastoreTab.CLOUD, DatastoreTab.ON_PREM);
    private static final Pattern SOURCE_ID = Pattern.compile("^[0-9a-fA-F]{24}$");
    private static final TypeReference<MisconfigurationSnapshot> SNAPSHOT =
            new TypeReference<MisconfigurationSnapshot>() {
            };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final DatastoreRowMapper rowMapper;
    private final DatastoreAccountLabeler labeler;
    private final MisconfigurationMapper mapper;
    private final RedisCacheService cache;
    private final Executor executor;

    /** The load in progress per client; the leader removes its own entry when it ends. */
    private final ConcurrentHashMap<Long, CompletableFuture<MisconfigurationSnapshot>> inFlight =
            new ConcurrentHashMap<>();

    /** The datastore whose issues call failed the client's last load; read first on the next one. */
    private final ConcurrentHashMap<Long, String> lastFailedSource = new ConcurrentHashMap<>();

    public MisconfigurationService(MattersCredentialResolver credentialResolver,
                                   MattersApiClient apiClient,
                                   DatastoreRowMapper rowMapper,
                                   DatastoreAccountLabeler labeler,
                                   MisconfigurationMapper mapper,
                                   RedisCacheService cache,
                                   @Qualifier("dspmFanOutExecutor") Executor executor) {
        this.credentialResolver = credentialResolver;
        this.apiClient = apiClient;
        this.rowMapper = rowMapper;
        this.labeler = labeler;
        this.mapper = mapper;
        this.cache = cache;
        this.executor = executor;
    }

    /** The cards and one row per policy. */
    public MisconfigurationList list(Long clientId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return mapper.toList(snapshot(credential, clientId));
    }

    /**
     * One policy with its affected datastores.
     *
     * @throws MattersApiException ASSET_NOT_FOUND when no datastore of the project reports the policy.
     */
    public MisconfigurationDetail detail(Long clientId, String policyId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        for (MisconfigurationDetail policy : snapshot(credential, clientId).policies()) {
            if (policy.policyId().equals(policyId)) {
                return policy;
            }
        }
        throw new MattersApiException(MattersApiException.Reason.ASSET_NOT_FOUND, NOT_FOUND_MESSAGE);
    }

    /** The cached snapshot, else one load shared by every concurrent caller of the client. */
    MisconfigurationSnapshot snapshot(MattersCredential credential, Long clientId) {
        String key = DatastoreCacheKeys.misconfigurations(clientId);
        Optional<MisconfigurationSnapshot> cached = cache.get(key, SNAPSHOT);
        if (cached.isPresent()) {
            return cached.get();
        }
        CompletableFuture<MisconfigurationSnapshot> mine = new CompletableFuture<>();
        CompletableFuture<MisconfigurationSnapshot> leader = inFlight.putIfAbsent(clientId, mine);
        if (leader != null) {
            return await(leader);
        }
        try {
            MisconfigurationSnapshot snapshot = load(credential, clientId);
            cache.put(key, snapshot, CACHE_MINUTES);
            mine.complete(snapshot);
            return snapshot;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(clientId, mine);
        }
    }

    private static MisconfigurationSnapshot await(CompletableFuture<MisconfigurationSnapshot> leader) {
        try {
            return leader.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    /** Reads every cloud and on-prem datastore (at most {@value #MAX_DATASTORES}) and their issues. */
    MisconfigurationSnapshot load(MattersCredential credential, Long clientId) {
        long startedAt = System.currentTimeMillis();
        List<DatastoreRowMapper.MappedRow> mapped = new ArrayList<>();
        List<Long> lastScanned = new ArrayList<>();
        long vendorTotal = 0L;
        for (DatastoreTab tab : TABS) {
            int tabRows = 0;
            Long tabTotal = null;
            int page = 1;
            do {
                JsonNode body = apiClient.listAssets(credential, assetQuery(tab, page));
                JsonNode total = body.path("pagination").path("total");
                tabTotal = total.isNumber() ? total.asLong() : tabTotal;
                List<DatastoreRowMapper.MappedRow> rows = rowMapper.toMappedRows(body);
                if (rows.isEmpty()) {
                    break;
                }
                JsonNode data = body.path("data");
                for (int i = 0; i < rows.size() && mapped.size() < MAX_DATASTORES; i++) {
                    mapped.add(rows.get(i));
                    lastScanned.add(MisconfigurationMapper.lastScannedAt(data.path(i)));
                }
                tabRows += rows.size();
                if (rows.size() < ASSET_PAGE_SIZE || (tabTotal != null && tabRows >= tabTotal)) {
                    break;
                }
                page++;
            } while (mapped.size() < MAX_DATASTORES);
            vendorTotal += tabTotal == null ? tabRows : tabTotal;
        }
        boolean capped = vendorTotal > MAX_DATASTORES;

        List<DatastoreRow> labelled = labeler.label(credential, mapped);
        List<DatastoreRow> rows = new ArrayList<>();
        List<Long> scanned = new ArrayList<>();
        List<String> sourceIds = new ArrayList<>();
        for (int i = 0; i < labelled.size(); i++) {
            DatastoreRow row = labelled.get(i);
            if (row.sourceId() != null && SOURCE_ID.matcher(row.sourceId()).matches()) {
                rows.add(row);
                scanned.add(lastScanned.get(i));
                sourceIds.add(row.sourceId());
            }
        }

        long deadline = Math.min(startedAt + LOAD_BUDGET_MS,
                System.currentTimeMillis() + fanOutBudgetMs(sourceIds.size()));
        List<List<MisconfigurationMapper.Issue>> issues = fetchIssues(credential, sourceIds, deadline);
        List<MisconfigurationMapper.Source> sources = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            sources.add(new MisconfigurationMapper.Source(rows.get(i), scanned.get(i), issues.get(i)));
        }
        MisconfigurationSnapshot snapshot = mapper.toSnapshot(sources, rows.size(), capped);
        log.info("Misconfigurations loaded for client {}: {} datastores read in {} ms",
                clientId, rows.size(), System.currentTimeMillis() - startedAt);
        return snapshot;
    }

    /**
     * How long the fan-out may keep starting calls for {@code datastores} datastores:
     * {@value #CALL_ALLOWANCE_MS} ms per call per worker, at least {@value #MIN_FAN_OUT_MS} ms.
     */
    static long fanOutBudgetMs(int datastores) {
        long callsPerWorker = (datastores + FAN_OUT - 1) / FAN_OUT;
        return Math.max(MIN_FAN_OUT_MS, callsPerWorker * CALL_ALLOWANCE_MS);
    }

    /**
     * The issues of every datastore, in the same order, {@value #FAN_OUT} calls at a time. A datastore
     * answering ASSET_NOT_FOUND (removed since the asset list) has none. The first other failure, or a
     * call due to start after {@code deadline} (epoch ms), stops every worker and is thrown once all of
     * them have ended. The datastore that failed the client's last load, if listed, is read first.
     */
    List<List<MisconfigurationMapper.Issue>> fetchIssues(MattersCredential credential, List<String> sourceIds,
                                                         long deadline) {
        int n = sourceIds.size();
        List<List<MisconfigurationMapper.Issue>> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            results.add(null);
        }
        Long clientId = credential.clientId();
        int[] order = readOrder(sourceIds, lastFailedSource.get(clientId));
        AtomicInteger next = new AtomicInteger();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();

        Runnable worker = () -> {
            while (failure.get() == null) {
                int position = next.getAndIncrement();
                if (position >= n) {
                    return;
                }
                int i = order[position];
                if (System.currentTimeMillis() > deadline) {
                    failure.compareAndSet(null, new MattersApiException(
                            MattersApiException.Reason.UPSTREAM_UNAVAILABLE, TOO_SLOW_MESSAGE));
                    return;
                }
                try {
                    List<MisconfigurationMapper.Issue> found =
                            mapper.toIssues(apiClient.inventoryIssues(credential, sourceIds.get(i)));
                    synchronized (results) {
                        results.set(i, found);
                    }
                } catch (MattersApiException e) {
                    if (e.getReason() != MattersApiException.Reason.ASSET_NOT_FOUND) {
                        if (failure.compareAndSet(null, e)) {
                            lastFailedSource.put(clientId, sourceIds.get(i));
                        }
                        return;
                    }
                    synchronized (results) {
                        results.set(i, List.of());
                    }
                } catch (RuntimeException e) {
                    if (failure.compareAndSet(null, e)) {
                        lastFailedSource.put(clientId, sourceIds.get(i));
                    }
                    return;
                }
            }
        };

        List<CompletableFuture<Void>> helpers = new ArrayList<>();
        for (int h = 0; h < Math.min(FAN_OUT, n) - 1; h++) {
            try {
                helpers.add(CompletableFuture.runAsync(withRequestContext(worker), executor));
            } catch (RejectedExecutionException e) {
                // The pool is busy: fewer helpers, and the remaining work stays on the shared index.
                break;
            }
        }
        worker.run();
        for (CompletableFuture<Void> helper : helpers) {
            try {
                helper.join();
            } catch (CompletionException e) {
                failure.compareAndSet(null, e.getCause() instanceof RuntimeException cause ? cause : e);
            }
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        lastFailedSource.remove(clientId);
        synchronized (results) {
            return new ArrayList<>(results);
        }
    }

    /** Indexes into {@code sourceIds} in reading order: {@code first}, if listed, then the rest in order. */
    private static int[] readOrder(List<String> sourceIds, String first) {
        int n = sourceIds.size();
        int[] order = new int[n];
        int lead = first == null ? -1 : sourceIds.indexOf(first);
        int position = 0;
        if (lead >= 0) {
            order[position++] = lead;
        }
        for (int i = 0; i < n; i++) {
            if (i != lead) {
                order[position++] = i;
            }
        }
        return order;
    }

    /**
     * The task with the caller's authentication and request attributes set on the helper thread, and
     * both cleared from it afterwards.
     */
    static Runnable withRequestContext(Runnable task) {
        SecurityContext callerContext = SecurityContextHolder.getContext();
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return () -> {
            try {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(callerContext.getAuthentication());
                SecurityContextHolder.setContext(context);
                RequestContextHolder.setRequestAttributes(attributes);
                task.run();
            } finally {
                SecurityContextHolder.clearContext();
                RequestContextHolder.resetRequestAttributes();
            }
        };
    }

    private static Map<String, List<String>> assetQuery(DatastoreTab tab, int page) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("category", List.of(tab.vendorCategory()));
        query.put("page", List.of(String.valueOf(page)));
        query.put("page_size", List.of(String.valueOf(ASSET_PAGE_SIZE)));
        query.put("sort", List.of("-severity"));
        return query;
    }
}
