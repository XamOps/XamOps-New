package com.xammer.cloud.controller;

import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.datastores.DataFlowColumnPage;
import com.xammer.cloud.dto.matters.datastores.DataFlowDatabases;
import com.xammer.cloud.dto.matters.datastores.DataFlowErd;
import com.xammer.cloud.dto.matters.datastores.DataFlowRelations;
import com.xammer.cloud.dto.matters.datastores.DataFlowSchemas;
import com.xammer.cloud.dto.matters.datastores.DataFlowTablePage;
import com.xammer.cloud.dto.matters.datastores.DatastoreAccessGraph;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilePage;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.DatastoreKpis;
import com.xammer.cloud.dto.matters.datastores.DatastoreOccurrencePage;
import com.xammer.cloud.dto.matters.datastores.DatastoreOverview;
import com.xammer.cloud.dto.matters.datastores.DatastoreRisks;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.datastores.DatastoreScanHistory;
import com.xammer.cloud.dto.matters.datastores.DatastoreScanProgress;
import com.xammer.cloud.dto.matters.datastores.DatastoreSensitives;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.XamLensTabGuard;
import com.xammer.cloud.service.matters.DatastoreDataFlowService;
import com.xammer.cloud.service.matters.DatastoreDrawerService;
import com.xammer.cloud.service.matters.DatastoreListQuery;
import com.xammer.cloud.service.matters.DatastoreListService;
import com.xammer.cloud.service.matters.DatastoreParams;
import com.xammer.cloud.service.matters.DatastoreTab;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * The XamLens Datastores page and the cloud / on-prem datastore drawer: 19 read-only GETs over the
 * Matters console routes (design 5.2; build 2 adds the two Graph View diagrams and table relations).
 *
 * <h2>Order of checks</h2>
 * Every method calls {@link XamLensTabGuard#require} first, then validates its input with
 * {@link DatastoreListQuery} or {@link DatastoreParams}, then calls a service with the principal's
 * client id, so a 403 or a 400 never reaches the vendor. Parameters are bound as a raw
 * {@link MultiValueMap} and path variables as strings, never through Spring's type conversion, so every
 * validation failure has the same {@code INVALID_REQUEST} body, and a parameter name an endpoint does
 * not list is a 400 too.
 *
 * <h2>Tenancy</h2>
 * As in {@link DspmController}, the client id comes from {@code @AuthenticationPrincipal} and nowhere
 * else. No endpoint has a {@code clientId} parameter, and sending one is a 400.
 *
 * <p>Paths start with {@code /api/dspm/datastores} or {@code /api/dspm/data-flow}, so none can match
 * {@code DspmController}'s {@code /{surface}/assets}, {@code /{surface}/filters} or
 * {@code /assets/{assetId}} (design 4.1). Failures are mapped by
 * {@link com.xammer.cloud.controller.advice.MattersExceptionHandler}.
 */
@RestController
@RequestMapping("/api/dspm")
@RequiredArgsConstructor
public class DspmDatastoreController {

    private static final Set<String> NO_PARAMS = Set.of();
    private static final Set<String> KPI_PARAMS = Set.of("tab");
    private static final Set<String> FILE_PARAMS = Set.of("page", "pageSize", "q");
    private static final Set<String> OCCURRENCE_PARAMS = Set.of("entityName", "skip", "limit", "showFalsePositives");
    private static final Set<String> TABLE_PARAMS = Set.of("page", "pageSize", "q");
    private static final Set<String> COLUMN_PARAMS = Set.of("page", "pageSize");

    private final XamLensTabGuard guard;
    private final DatastoreListService listService;
    private final DatastoreDrawerService drawerService;
    private final DatastoreDataFlowService dataFlowService;

    // ── list ─────────────────────────────────────────────────────────────────────────────────────

    /** {@code tab, page, pageSize, sort, q} and the tab's filter dimensions, each with a {@code _exclude} twin. */
    @GetMapping("/datastores")
    public ResponseEntity<DspmPage<DatastoreRow>> list(@AuthenticationPrincipal ClientUserDetails user,
                                                       @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreListQuery query = DatastoreListQuery.forList(params);
        return ResponseEntity.ok(listService.list(user.getClientId(), query));
    }

    /** {@code tab, q} and the same filter dimensions as the list, so the counts match the rows. */
    @GetMapping("/datastores/filter-options")
    public ResponseEntity<DatastoreFilterOptions> filterOptions(@AuthenticationPrincipal ClientUserDetails user,
                                                                @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreListQuery query = DatastoreListQuery.forFilters(params);
        return ResponseEntity.ok(listService.filterOptions(user.getClientId(), query));
    }

    /** {@code tab} only: the KPI cards ignore the page's filters. */
    @GetMapping("/datastores/kpis")
    public ResponseEntity<DatastoreKpis> kpis(@AuthenticationPrincipal ClientUserDetails user,
                                              @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, KPI_PARAMS);
        String rawTab = DatastoreParams.single(params, "tab");
        DatastoreTab tab = rawTab == null ? DatastoreTab.ALL : DatastoreTab.fromSlug(rawTab);
        return ResponseEntity.ok(listService.kpis(user.getClientId(), tab));
    }

    // ── drawer ───────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/datastores/{sourceId}/overview")
    public ResponseEntity<DatastoreOverview> overview(@AuthenticationPrincipal ClientUserDetails user,
                                                      @PathVariable String sourceId,
                                                      @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(drawerService.overview(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    @GetMapping("/datastores/{sourceId}/scan-status")
    public ResponseEntity<DatastoreScanProgress> scanStatus(@AuthenticationPrincipal ClientUserDetails user,
                                                            @PathVariable String sourceId,
                                                            @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(drawerService.scanProgress(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    @GetMapping("/datastores/{sourceId}/sensitives")
    public ResponseEntity<DatastoreSensitives> sensitives(@AuthenticationPrincipal ClientUserDetails user,
                                                          @PathVariable String sourceId,
                                                          @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(drawerService.sensitives(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    /** {@code page, pageSize} (offset at most 10,000) and {@code q}, a case-insensitive path search. */
    @GetMapping("/datastores/{sourceId}/sensitives/files")
    public ResponseEntity<DatastoreFilePage> files(@AuthenticationPrincipal ClientUserDetails user,
                                                   @PathVariable String sourceId,
                                                   @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, FILE_PARAMS);
        String id = DatastoreParams.sourceId(sourceId);
        DatastoreParams.Paging paging = DatastoreParams.listPaging(
                DatastoreParams.single(params, "page"), DatastoreParams.single(params, "pageSize"));
        String q = DatastoreParams.search(DatastoreParams.single(params, "q"));
        return ResponseEntity.ok(drawerService.files(user.getClientId(), id, q, paging.page(), paging.pageSize()));
    }

    /**
     * {@code entityName} (required, one of this datastore's entities), {@code skip}, {@code limit} and
     * {@code showFalsePositives}. Carries masked samples, so the response is {@code Cache-Control: no-store}.
     */
    @GetMapping("/datastores/{sourceId}/sensitives/occurrences")
    public ResponseEntity<DatastoreOccurrencePage> occurrences(@AuthenticationPrincipal ClientUserDetails user,
                                                               @PathVariable String sourceId,
                                                               @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, OCCURRENCE_PARAMS);
        String id = DatastoreParams.sourceId(sourceId);
        String entityName = DatastoreParams.entityName(DatastoreParams.single(params, "entityName"));
        int skip = DatastoreParams.skip(DatastoreParams.single(params, "skip"));
        int limit = DatastoreParams.limit(DatastoreParams.single(params, "limit"));
        boolean showFalsePositives =
                DatastoreParams.showFalsePositives(DatastoreParams.single(params, "showFalsePositives"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(drawerService.occurrences(user.getClientId(), id, entityName, skip, limit, showFalsePositives));
    }

    @GetMapping("/datastores/{sourceId}/risks")
    public ResponseEntity<DatastoreRisks> risks(@AuthenticationPrincipal ClientUserDetails user,
                                                @PathVariable String sourceId,
                                                @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(drawerService.risks(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    @GetMapping("/datastores/{sourceId}/access-graph")
    public ResponseEntity<DatastoreAccessGraph> accessGraph(@AuthenticationPrincipal ClientUserDetails user,
                                                            @PathVariable String sourceId,
                                                            @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(drawerService.accessGraph(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    @GetMapping("/datastores/{sourceId}/scan-history")
    public ResponseEntity<DatastoreScanHistory> scanHistory(@AuthenticationPrincipal ClientUserDetails user,
                                                            @PathVariable String sourceId,
                                                            @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(drawerService.scanHistory(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    // ── Data Flow ────────────────────────────────────────────────────────────────────────────────

    @GetMapping("/datastores/{sourceId}/data-flow/databases")
    public ResponseEntity<DataFlowDatabases> dataFlowDatabases(@AuthenticationPrincipal ClientUserDetails user,
                                                               @PathVariable String sourceId,
                                                               @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(dataFlowService.databases(user.getClientId(), DatastoreParams.sourceId(sourceId)));
    }

    /**
     * Data Flow ids are checked for format only, not for belonging to the datastore above them, which is
     * why these paths sit under {@code /data-flow} rather than under the datastore (design 5.2).
     */
    @GetMapping("/data-flow/databases/{databaseId}/schemas")
    public ResponseEntity<DataFlowSchemas> dataFlowSchemas(@AuthenticationPrincipal ClientUserDetails user,
                                                           @PathVariable String databaseId,
                                                           @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(dataFlowService.schemas(user.getClientId(),
                DatastoreParams.uuid("databaseId", databaseId)));
    }

    /** {@code page, pageSize} (offset below 10,000) and {@code q}; a {@code q} under 3 characters is ignored. */
    @GetMapping("/data-flow/schemas/{schemaId}/tables")
    public ResponseEntity<DataFlowTablePage> dataFlowSchemaTables(@AuthenticationPrincipal ClientUserDetails user,
                                                                  @PathVariable String schemaId,
                                                                  @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, TABLE_PARAMS);
        String id = DatastoreParams.uuid("schemaId", schemaId);
        DatastoreParams.Paging paging = DatastoreParams.dataFlowPaging(
                DatastoreParams.single(params, "page"), DatastoreParams.single(params, "pageSize"));
        String q = DatastoreParams.dataFlowSearch(DatastoreParams.single(params, "q"));
        return ResponseEntity.ok(dataFlowService.schemaTables(user.getClientId(), id, paging.page(),
                paging.pageSize(), q));
    }

    /** The schema-less variant (MySQL, MariaDB), with the same parameters. */
    @GetMapping("/data-flow/databases/{databaseId}/tables")
    public ResponseEntity<DataFlowTablePage> dataFlowDatabaseTables(@AuthenticationPrincipal ClientUserDetails user,
                                                                    @PathVariable String databaseId,
                                                                    @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, TABLE_PARAMS);
        String id = DatastoreParams.uuid("databaseId", databaseId);
        DatastoreParams.Paging paging = DatastoreParams.dataFlowPaging(
                DatastoreParams.single(params, "page"), DatastoreParams.single(params, "pageSize"));
        String q = DatastoreParams.dataFlowSearch(DatastoreParams.single(params, "q"));
        return ResponseEntity.ok(dataFlowService.databaseTables(user.getClientId(), id, paging.page(),
                paging.pageSize(), q));
    }

    /** {@code page, pageSize} (offset below 10,000). */
    @GetMapping("/data-flow/tables/{tableId}/columns")
    public ResponseEntity<DataFlowColumnPage> dataFlowColumns(@AuthenticationPrincipal ClientUserDetails user,
                                                              @PathVariable String tableId,
                                                              @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, COLUMN_PARAMS);
        String id = DatastoreParams.uuid("tableId", tableId);
        DatastoreParams.Paging paging = DatastoreParams.dataFlowPaging(
                DatastoreParams.single(params, "page"), DatastoreParams.single(params, "pageSize"));
        return ResponseEntity.ok(dataFlowService.columns(user.getClientId(), id, paging.page(), paging.pageSize()));
    }

    /** The Graph View of a schema: its tables, key columns and keys. */
    @GetMapping("/data-flow/schemas/{schemaId}/erd")
    public ResponseEntity<DataFlowErd> dataFlowSchemaErd(@AuthenticationPrincipal ClientUserDetails user,
                                                         @PathVariable String schemaId,
                                                         @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(dataFlowService.schemaErd(user.getClientId(),
                DatastoreParams.uuid("schemaId", schemaId)));
    }

    /** The schema-less variant (MySQL, MariaDB). */
    @GetMapping("/data-flow/databases/{databaseId}/erd")
    public ResponseEntity<DataFlowErd> dataFlowDatabaseErd(@AuthenticationPrincipal ClientUserDetails user,
                                                           @PathVariable String databaseId,
                                                           @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(dataFlowService.databaseErd(user.getClientId(),
                DatastoreParams.uuid("databaseId", databaseId)));
    }

    /** A table's outgoing and incoming keys, for the table panel's Relationships tab. */
    @GetMapping("/data-flow/tables/{tableId}/relations")
    public ResponseEntity<DataFlowRelations> dataFlowTableRelations(@AuthenticationPrincipal ClientUserDetails user,
                                                                    @PathVariable String tableId,
                                                                    @RequestParam MultiValueMap<String, String> params) {
        guard.require(user);
        DatastoreParams.requireAllowed(params, NO_PARAMS);
        return ResponseEntity.ok(dataFlowService.tableRelations(user.getClientId(),
                DatastoreParams.uuid("tableId", tableId)));
    }
}
