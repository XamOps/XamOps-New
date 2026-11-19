package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.scans.ScanRun;
import com.xammer.cloud.dto.matters.scans.ScanRunDatastore;
import com.xammer.cloud.dto.matters.scans.ScanRunDetail;
import com.xammer.cloud.dto.matters.scans.ScanSchedule;
import com.xammer.cloud.dto.matters.scans.ScanScheduleDetail;
import com.xammer.cloud.dto.matters.scans.ScanScope;
import com.xammer.cloud.dto.matters.scans.ScanScopeDetail;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Scan Management's schedules, scan history and scopes (build 6 design 5.8, endpoints S1 to S7), view
 * only. Ids are validated 24-hex ids or UUIDs, lower-cased.
 *
 * <p>Every method resolves the credential first, then reads the cache, then makes one vendor call and
 * caches the whitelisted DTO.
 */
@Service
@RequiredArgsConstructor
public class ScanManagementService {

    static final int SCHEDULES_CACHE_MINUTES = 1;
    static final int SCHEDULE_CACHE_MINUTES = 2;
    static final int RUNS_CACHE_MINUTES = 1;
    static final int RUN_CACHE_MINUTES = 1;
    static final int RUN_DATASTORES_CACHE_MINUTES = 1;
    static final int SCOPES_CACHE_MINUTES = 2;
    static final int SCOPE_CACHE_MINUTES = 2;

    private static final TypeReference<DspmPage<ScanSchedule>> SCHEDULES =
            new TypeReference<DspmPage<ScanSchedule>>() {
            };
    private static final TypeReference<ScanScheduleDetail> SCHEDULE = new TypeReference<ScanScheduleDetail>() {
    };
    private static final TypeReference<DspmPage<ScanRun>> RUNS = new TypeReference<DspmPage<ScanRun>>() {
    };
    private static final TypeReference<ScanRunDetail> RUN = new TypeReference<ScanRunDetail>() {
    };
    private static final TypeReference<DspmPage<ScanRunDatastore>> RUN_DATASTORES =
            new TypeReference<DspmPage<ScanRunDatastore>>() {
            };
    private static final TypeReference<DspmPage<ScanScope>> SCOPES = new TypeReference<DspmPage<ScanScope>>() {
    };
    private static final TypeReference<ScanScopeDetail> SCOPE = new TypeReference<ScanScopeDetail>() {
    };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final ScanManagementMapper mapper;
    private final RedisCacheService cache;

    /** @param query a {@link ScanListQuery.Kind#SCHEDULES} query */
    public DspmPage<ScanSchedule> schedules(Long clientId, ScanListQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.scanList(clientId, query), SCHEDULES, SCHEDULES_CACHE_MINUTES,
                () -> mapper.toSchedules(apiClient.scanSchedules(credential, query), query.page(), query.pageSize()));
    }

    public ScanScheduleDetail schedule(Long clientId, String scheduleId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.scanSchedule(clientId, scheduleId), SCHEDULE, SCHEDULE_CACHE_MINUTES,
                () -> mapper.toSchedule(apiClient.scanSchedule(credential, scheduleId)));
    }

    /** @param query a {@link ScanListQuery.Kind#RUNS} query */
    public DspmPage<ScanRun> runs(Long clientId, ScanListQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.scanList(clientId, query), RUNS, RUNS_CACHE_MINUTES,
                () -> mapper.toRuns(apiClient.scanRuns(credential, query), query.page(), query.pageSize()));
    }

    public ScanRunDetail run(Long clientId, String runId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.scanRun(clientId, runId), RUN, RUN_CACHE_MINUTES,
                () -> mapper.toRun(apiClient.scanRun(credential, runId)));
    }

    /** @param query a {@link ScanListQuery.Kind#RUN_DATASTORES} query */
    public DspmPage<ScanRunDatastore> runDatastores(Long clientId, String runId, ScanListQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.scanRunDatastores(clientId, runId, query), RUN_DATASTORES,
                RUN_DATASTORES_CACHE_MINUTES,
                () -> mapper.toRunDatastores(apiClient.scanRunDatastores(credential, runId, query), query.page(),
                        query.pageSize()));
    }

    /** @param query a {@link ScanListQuery.Kind#SCOPES} query */
    public DspmPage<ScanScope> scopes(Long clientId, ScanListQuery query) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.scanList(clientId, query), SCOPES, SCOPES_CACHE_MINUTES,
                () -> mapper.toScopes(apiClient.scanScopes(credential, query), query.page(), query.pageSize()));
    }

    public ScanScopeDetail scope(Long clientId, String scopeId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.scanScope(clientId, scopeId), SCOPE, SCOPE_CACHE_MINUTES,
                () -> mapper.toScope(apiClient.scanScope(credential, scopeId)));
    }

    private <T> T cached(String key, TypeReference<T> type, int minutes, Supplier<T> load) {
        Optional<T> hit = cache.get(key, type);
        if (hit.isPresent()) {
            return hit.get();
        }
        T value = load.get();
        cache.put(key, value, minutes);
        return value;
    }
}
