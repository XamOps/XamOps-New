package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.scans.AssetRowSnapshot;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One tab's asset rows, read page by page from {@code /api/v1/assets} (build 6 design 4.2). The Dashboard
 * and the Scan Configurations provider list both read this snapshot, so opening one after the other costs
 * no vendor call.
 *
 * <p>Pages of 100 rows sorted by {@code -severity} are read until {@code pagination.total} rows are in, a
 * page is empty, or the page cap is reached (10 pages for an environment, 1 for All). A snapshot that
 * stopped at the cap before the vendor total is {@code truncated}. Only the snapshot is cached, never a
 * vendor body. Callers resolve the credential first.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class AssetRowCollector {

    static final int CACHE_MINUTES = 2;
    static final int PAGE_SIZE = 100;
    static final int ENVIRONMENT_PAGE_CAP = 10;
    static final int ALL_PAGE_CAP = 1;
    static final String SORT = "-severity";

    private static final TypeReference<AssetRowSnapshot> SNAPSHOT = new TypeReference<AssetRowSnapshot>() {
    };

    private final MattersApiClient apiClient;
    private final DatastoreRowMapper rowMapper;
    private final RedisCacheService cache;

    /** The {@code dsrows} entry of the tab, else the vendor pages that write it. */
    AssetRowSnapshot rows(MattersCredential credential, Long clientId, DatastoreTab tab) {
        String key = DatastoreCacheKeys.assetRows(clientId, tab);
        Optional<AssetRowSnapshot> hit = cache.get(key, SNAPSHOT);
        if (hit.isPresent()) {
            return hit.get();
        }
        AssetRowSnapshot snapshot = collect(credential, tab);
        cache.put(key, snapshot, CACHE_MINUTES);
        return snapshot;
    }

    private AssetRowSnapshot collect(MattersCredential credential, DatastoreTab tab) {
        int cap = tab == DatastoreTab.ALL ? ALL_PAGE_CAP : ENVIRONMENT_PAGE_CAP;
        List<DatastoreRowMapper.MappedRow> rows = new ArrayList<>();
        long total = 0L;
        int pages = 0;
        boolean capped = false;
        for (int page = 1; ; page++) {
            if (pages >= cap) {
                capped = true;
                break;
            }
            JsonNode body = apiClient.listAssets(credential, query(tab, page));
            pages++;
            total = DatastoreRowMapper.longOr(body.path("pagination"), "total", 0L);
            List<DatastoreRowMapper.MappedRow> pageRows = rowMapper.toMappedRows(body);
            if (pageRows.isEmpty()) {
                break;
            }
            rows.addAll(pageRows);
            if (rows.size() >= total) {
                break;
            }
        }
        boolean truncated = capped && rows.size() < total;
        if (truncated) {
            log.info("[DSPM] asset rows of tab {}: read {} of {} rows in {} pages", tab.slug(), rows.size(), total,
                    pages);
        }
        return new AssetRowSnapshot(rows, total, truncated);
    }

    /** {@code category} (absent for All), {@code sort}, {@code page}, {@code page_size}. */
    private static Map<String, List<String>> query(DatastoreTab tab, int page) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (tab.vendorCategory() != null) {
            query.put("category", List.of(tab.vendorCategory()));
        }
        query.put("sort", List.of(SORT));
        query.put("page", List.of(String.valueOf(page)));
        query.put("page_size", List.of(String.valueOf(PAGE_SIZE)));
        return query;
    }
}
