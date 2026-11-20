package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.datastores.DatastoreSummarySnapshot;
import com.xammer.cloud.dto.matters.pages.DspmDashboard;
import com.xammer.cloud.dto.matters.scans.AssetRowSnapshot;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The XamLens Dashboard (build 6 design 4.2, 5.8): the environment cards and the Top Risky rows, from the
 * shared summary snapshot and the collected asset rows of every tab.
 *
 * <p>The credential is resolved first, then the {@code dsdash} entry is read; on a miss the summary
 * ({@code dssummary}, shared with the Datastores KPI cards), the collector's snapshots for All and the four
 * environments, and the account labels of the Top Risky rows are read, and the whitelisted
 * {@link DspmDashboard} is cached. A failure of any vendor read propagates and nothing is cached.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    static final int DASHBOARD_CACHE_MINUTES = 5;

    private static final TypeReference<DspmDashboard> DASHBOARD = new TypeReference<DspmDashboard>() {
    };

    private final MattersCredentialResolver credentialResolver;
    private final DatastoreListService listService;
    private final AssetRowCollector collector;
    private final DashboardMapper mapper;
    private final DatastoreAccountLabeler labeler;
    private final RedisCacheService cache;

    public DspmDashboard dashboard(Long clientId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return cached(DatastoreCacheKeys.dashboard(clientId), DASHBOARD, DASHBOARD_CACHE_MINUTES,
                () -> load(credential, clientId));
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private DspmDashboard load(MattersCredential credential, Long clientId) {
        DatastoreSummarySnapshot summary = listService.summary(credential, clientId);
        Map<DatastoreTab, AssetRowSnapshot> rows = new EnumMap<>(DatastoreTab.class);
        Map<DatastoreTab, List<DatastoreRow>> top = new EnumMap<>(DatastoreTab.class);
        for (DatastoreTab tab : DatastoreTab.values()) {
            AssetRowSnapshot snapshot = collector.rows(credential, clientId, tab);
            rows.put(tab, snapshot);
            List<DatastoreRowMapper.MappedRow> topRows = mapper.topRows(snapshot);
            top.put(tab, topRows.isEmpty() ? List.of() : labeler.label(credential, topRows));
        }
        return mapper.toDashboard(summary, rows, top);
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
