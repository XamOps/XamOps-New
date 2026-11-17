package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.matters.datastores.DataFlowColumnPage;
import com.xammer.cloud.dto.matters.datastores.DataFlowDatabases;
import com.xammer.cloud.dto.matters.datastores.DataFlowSchemas;
import com.xammer.cloud.dto.matters.datastores.DataFlowTablePage;
import com.xammer.cloud.dto.matters.datastores.DataFlowVendorPage;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static com.xammer.cloud.service.matters.DatastoreRowMapper.totalPages;

/**
 * The drawer's Data Flow tab: the database → schema tree, a schema's (or a schema-less database's)
 * tables, and a table's columns (design 4.7 and 5.6.10). Ids are validated, lower-cased UUIDs, except
 * the datastore's 24-hex {@code sourceId}.
 *
 * <h2>Vendor pages of 100</h2>
 * Tables and columns are always requested in vendor pages of 100 and sliced here, so normal paging and
 * search share cache entries (one per vendor page), page clicks inside a vendor page cost nothing, and
 * UI pages above 100 stay reachable. The vendor serves at most 100 pages, which is why the controller
 * keeps {@code (page - 1) x pageSize} below 10,000.
 *
 * <h2>Search</h2>
 * The tables route ignores every search parameter (verified), so a search of 3+ characters loads up to
 * 100 vendor pages, reusing cached ones, and filters by name here. Every call runs on the request
 * thread; the frontend keeps at most one such search in flight per drawer.
 */
@Service
@RequiredArgsConstructor
public class DatastoreDataFlowService {

    static final int VENDOR_PAGE_SIZE = MattersApiClient.MAX_PAGE_SIZE;
    /** Pages the database and schema trees read before reporting {@code truncated}. */
    static final int MAX_TREE_PAGES = 10;
    /** The vendor's page ceiling, and the search cap (A21). */
    static final int MAX_VENDOR_PAGES = 100;
    static final int CACHE_MINUTES = 5;

    private static final TypeReference<DataFlowDatabases> DATABASES = new TypeReference<DataFlowDatabases>() {
    };
    private static final TypeReference<DataFlowSchemas> SCHEMAS = new TypeReference<DataFlowSchemas>() {
    };
    private static final TypeReference<DataFlowVendorPage<DataFlowTablePage.Table>> TABLES =
            new TypeReference<DataFlowVendorPage<DataFlowTablePage.Table>>() {
            };
    private static final TypeReference<DataFlowVendorPage<DataFlowColumnPage.Column>> COLUMNS =
            new TypeReference<DataFlowVendorPage<DataFlowColumnPage.Column>>() {
            };

    private final MattersCredentialResolver credentialResolver;
    private final MattersApiClient apiClient;
    private final DataFlowMapper mapper;
    private final RedisCacheService cache;

    // ── tree ─────────────────────────────────────────────────────────────────────────────────────

    /** The datastore's databases, at most 10 vendor pages. */
    public DataFlowDatabases databases(Long clientId, String sourceId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        String key = DatastoreCacheKeys.dataFlowDatabases(clientId, sourceId);
        Optional<DataFlowDatabases> cached = cache.get(key, DATABASES);
        if (cached.isPresent()) {
            return cached.get();
        }
        List<DataFlowDatabases.Database> items = new ArrayList<>();
        boolean truncated = readTree(page -> apiClient.schemaDatabases(credential, sourceId, page, VENDOR_PAGE_SIZE),
                body -> items.addAll(mapper.toDatabases(body)));
        DataFlowDatabases databases = new DataFlowDatabases(items, truncated);
        cache.put(key, databases, CACHE_MINUTES);
        return databases;
    }

    /** One database's schemas, at most 10 vendor pages. */
    public DataFlowSchemas schemas(Long clientId, String databaseId) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        String key = DatastoreCacheKeys.dataFlowSchemas(clientId, databaseId);
        Optional<DataFlowSchemas> cached = cache.get(key, SCHEMAS);
        if (cached.isPresent()) {
            return cached.get();
        }
        List<DataFlowSchemas.Schema> items = new ArrayList<>();
        boolean truncated = readTree(page -> apiClient.schemaSchemas(credential, databaseId, page, VENDOR_PAGE_SIZE),
                body -> items.addAll(mapper.toSchemas(body)));
        DataFlowSchemas schemas = new DataFlowSchemas(items, truncated);
        cache.put(key, schemas, CACHE_MINUTES);
        return schemas;
    }

    /**
     * Vendor pages 1, 2, … until {@code pagination.total_pages} or {@link #MAX_TREE_PAGES}.
     *
     * @return true when pages remained after the last one read.
     */
    private boolean readTree(IntFunction<JsonNode> fetch, Consumer<JsonNode> collect) {
        for (int page = 1; ; page++) {
            JsonNode body = fetch.apply(page);
            collect.accept(body);
            if (page >= mapper.vendorTotalPages(body)) {
                return false;
            }
            if (page >= MAX_TREE_PAGES) {
                return true;
            }
        }
    }

    // ── tables and columns ───────────────────────────────────────────────────────────────────────

    /**
     * One page of a schema's tables.
     *
     * @param q a validated search of 3+ characters, or null.
     */
    public DataFlowTablePage schemaTables(Long clientId, String schemaId, int page, int pageSize, String q) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return tables(number -> cachedVendorPage(DatastoreCacheKeys.dataFlowSchemaTables(clientId, schemaId, number),
                        TABLES, () -> mapper.toTablePage(
                                apiClient.schemaTables(credential, schemaId, number, VENDOR_PAGE_SIZE))),
                page, pageSize, q);
    }

    /** One page of a schema-less database's tables (MySQL, MariaDB). */
    public DataFlowTablePage databaseTables(Long clientId, String databaseId, int page, int pageSize, String q) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        return tables(number -> cachedVendorPage(
                        DatastoreCacheKeys.dataFlowDatabaseTables(clientId, databaseId, number), TABLES,
                        () -> mapper.toTablePage(
                                apiClient.schemaDatabaseTables(credential, databaseId, number, VENDOR_PAGE_SIZE))),
                page, pageSize, q);
    }

    /** One page of a table's columns, with the same vendor-page slicing as tables. */
    public DataFlowColumnPage columns(Long clientId, String tableId, int page, int pageSize) {
        MattersCredential credential = credentialResolver.resolve(clientId);
        Slice<DataFlowColumnPage.Column> slice = slice(number -> cachedVendorPage(
                DatastoreCacheKeys.dataFlowColumns(clientId, tableId, number), COLUMNS,
                () -> mapper.toColumnPage(apiClient.schemaColumns(credential, tableId, number, VENDOR_PAGE_SIZE))),
                page, pageSize);
        return new DataFlowColumnPage(slice.rows(), page, pageSize, slice.total(), totalPages(slice.total(), pageSize));
    }

    private DataFlowTablePage tables(IntFunction<DataFlowVendorPage<DataFlowTablePage.Table>> vendorPages,
                                     int page, int pageSize, String q) {
        if (q == null) {
            Slice<DataFlowTablePage.Table> slice = slice(vendorPages, page, pageSize);
            return new DataFlowTablePage(slice.rows(), page, pageSize, slice.total(),
                    totalPages(slice.total(), pageSize), false);
        }

        DataFlowVendorPage<DataFlowTablePage.Table> first = vendorPages.apply(1);
        List<DataFlowTablePage.Table> all = new ArrayList<>(first.rows());
        int last = Math.min(first.totalPages(), MAX_VENDOR_PAGES);
        for (int vendorPage = 2; vendorPage <= last; vendorPage++) {
            all.addAll(vendorPages.apply(vendorPage).rows());
        }
        String needle = q.toLowerCase(Locale.ROOT);
        List<DataFlowTablePage.Table> matched = all.stream()
                .filter(table -> table.name() != null && table.name().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        int total = matched.size();
        int from = (int) Math.min((long) (page - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        return new DataFlowTablePage(List.copyOf(matched.subList(from, to)), page, pageSize, total,
                totalPages(total, pageSize), first.totalPages() > MAX_VENDOR_PAGES);
    }

    /**
     * One UI page cut from 100-row vendor pages. Offset o = (page − 1) × pageSize lies on vendor page
     * v = o / 100 + 1 at index o mod 100. When the page runs past the end of vendor page v, page v + 1
     * is fetched for the rest only when v < min(V, 100), V being {@code total_pages}; otherwise the
     * partial slice is returned. So the last page and the vendor's page ceiling are never passed.
     */
    private static <T> Slice<T> slice(IntFunction<DataFlowVendorPage<T>> vendorPages, int page, int pageSize) {
        long offset = (long) (page - 1) * pageSize;
        int vendorPage = (int) (offset / VENDOR_PAGE_SIZE) + 1;
        int start = (int) (offset % VENDOR_PAGE_SIZE);

        DataFlowVendorPage<T> current = vendorPages.apply(vendorPage);
        List<T> rows = new ArrayList<>(take(current.rows(), start, pageSize));
        int rest = start + pageSize - VENDOR_PAGE_SIZE;
        if (rest > 0 && vendorPage < Math.min(current.totalPages(), MAX_VENDOR_PAGES)) {
            rows.addAll(take(vendorPages.apply(vendorPage + 1).rows(), 0, rest));
        }
        return new Slice<>(List.copyOf(rows), current.total());
    }

    private static <T> List<T> take(List<T> rows, int from, int count) {
        if (from >= rows.size()) {
            return List.of();
        }
        return rows.subList(from, Math.min(from + count, rows.size()));
    }

    /** One cached vendor page, else the vendor call. */
    private <T> DataFlowVendorPage<T> cachedVendorPage(String key, TypeReference<DataFlowVendorPage<T>> type,
                                                       Supplier<DataFlowVendorPage<T>> load) {
        Optional<DataFlowVendorPage<T>> cached = cache.get(key, type);
        if (cached.isPresent()) {
            return cached.get();
        }
        DataFlowVendorPage<T> loaded = load.get();
        cache.put(key, loaded, CACHE_MINUTES);
        return loaded;
    }

    private record Slice<T>(List<T> rows, long total) {
    }
}
