package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Design 5.9: every key is evicted by its own client's glob, and no key holds customer content. */
class DatastoreCacheKeysTest {

    private static final Long CLIENT = 10L;
    private static final String SOURCE_ID = "0123456789abcdef01234567";
    private static final String UUID = "0000000a-0000-4000-8000-00000000000b";
    private static final String FILE_ID = "abcdef0123456789abcdef01";
    /** Non-numeric, so it cannot read as another client's id (build 4 plan A-1). */
    private static final String EXPOSURE_ID = "exposure-one";

    private static MultiValueMap<String, String> params(String... pairs) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.add(pairs[i], pairs[i + 1]);
        }
        return params;
    }

    /** Redis glob semantics for the only wildcard the eviction pattern uses: {@code *} matches anything. */
    private static boolean globMatches(String glob, String key) {
        String regex = Arrays.stream(glob.split("\\*", -1)).map(Pattern::quote).collect(Collectors.joining(".*"));
        return key.matches(regex);
    }

    /** One key from every factory, with vendor pages that end in the other clients' ids. */
    private static List<String> everyKey(Long clientId) {
        return List.of(
                DatastoreCacheKeys.list(clientId, DatastoreListQuery.forList(params("tab", "cloud", "q", "bucket"))),
                DatastoreCacheKeys.facets(clientId, DatastoreListQuery.forFilters(params("tab", "cloud"))),
                DatastoreCacheKeys.summary(clientId),
                DatastoreCacheKeys.accountLabels(clientId),
                DatastoreCacheKeys.overview(clientId, SOURCE_ID),
                DatastoreCacheKeys.scanProgress(clientId, SOURCE_ID),
                DatastoreCacheKeys.sensitives(clientId, SOURCE_ID),
                DatastoreCacheKeys.files(clientId, SOURCE_ID),
                DatastoreCacheKeys.risks(clientId, SOURCE_ID),
                DatastoreCacheKeys.accessGraph(clientId, SOURCE_ID),
                DatastoreCacheKeys.scanHistory(clientId, SOURCE_ID),
                DatastoreCacheKeys.dataFlowDatabases(clientId, SOURCE_ID),
                DatastoreCacheKeys.dataFlowSchemas(clientId, UUID),
                DatastoreCacheKeys.dataFlowSchemaTables(clientId, UUID, 1),
                DatastoreCacheKeys.dataFlowDatabaseTables(clientId, UUID, 99),
                DatastoreCacheKeys.dataFlowColumns(clientId, UUID, 11),
                DatastoreCacheKeys.dataFlowSchemaErd(clientId, UUID),
                DatastoreCacheKeys.dataFlowDatabaseErd(clientId, UUID),
                DatastoreCacheKeys.dataFlowTableRelations(clientId, UUID),
                DatastoreCacheKeys.saasHeader(clientId, SOURCE_ID),
                DatastoreCacheKeys.saasRecords(clientId, SOURCE_ID, DatastoreParams.VIEW_DATA_ELEMENTS),
                DatastoreCacheKeys.saasFiles(clientId, SOURCE_ID,
                        new SaasFileQuery(SaasFileQuery.Kind.DATA_ELEMENT, "EMAIL ADDRESS", "plan", 1, 11)),
                DatastoreCacheKeys.saasScanSummary(clientId, SOURCE_ID),
                DatastoreCacheKeys.saasFileOverview(clientId, FILE_ID),
                DatastoreCacheKeys.saasFileActivity(clientId, FILE_ID),
                DatastoreCacheKeys.saasFileLineage(clientId, FILE_ID),
                DatastoreCacheKeys.saasFileCopies(clientId, FILE_ID),
                DatastoreCacheKeys.endpointOverview(clientId, SOURCE_ID),
                DatastoreCacheKeys.endpointFilterOptions(clientId, SOURCE_ID),
                DatastoreCacheKeys.endpointSummary(clientId, SOURCE_ID),
                DatastoreCacheKeys.endpointMetrics(clientId, SOURCE_ID, EndpointFilters.NONE, true),
                DatastoreCacheKeys.endpointFiles(clientId, SOURCE_ID,
                        new EndpointFileQuery(EndpointFilters.NONE, null, 1, 11)),
                DatastoreCacheKeys.endpointScans(clientId, SOURCE_ID, 1, 11),
                DatastoreCacheKeys.endpointScans(clientId, SOURCE_ID, 110, 99),
                DatastoreCacheKeys.misconfigurations(clientId),
                DatastoreCacheKeys.lineageOptions(clientId),
                DatastoreCacheKeys.lineageCategories(clientId),
                DatastoreCacheKeys.lineagePolicies(clientId, ExposureCategory.SENSITIVE_FINDINGS,
                        new LineageRowQuery("plan", 1, 11, LineageFilters.NONE)),
                DatastoreCacheKeys.lineageDatastores(clientId, UUID,
                        new LineageRowQuery(null, 110, 7, LineageFilters.NONE)),
                DatastoreCacheKeys.lineageFiles(clientId, EXPOSURE_ID, 1, 11),
                DatastoreCacheKeys.lineageFiles(clientId, EXPOSURE_ID, 110, 99),
                DatastoreCacheKeys.filesSaas(clientId, SaasFileListQuery.parse(params("limit", "11", "cursor", "abc"))),
                DatastoreCacheKeys.filesSaasOptions(clientId),
                DatastoreCacheKeys.filesEndpoint(clientId, EndpointFileListQuery.parse(params("skip", "1", "limit", "11"))),
                DatastoreCacheKeys.filesEndpointOptions(clientId),
                DatastoreCacheKeys.endpointFileOverview(clientId, UUID),
                DatastoreCacheKeys.endpointFileActivity(clientId, UUID),
                DatastoreCacheKeys.endpointFileLineage(clientId, UUID));
    }

    @Test
    @DisplayName("every key matches its client's evictClient glob and no other client's")
    void everyKeyIsEvictedWithItsClient() {
        assertThat(DatastoreCacheKeys.evictionPattern(CLIENT)).isEqualTo("dspm:*:10:*");
        for (String key : everyKey(CLIENT)) {
            assertThat(globMatches(DatastoreCacheKeys.evictionPattern(CLIENT), key)).as(key).isTrue();
            for (Long other : List.of(1L, 11L, 99L, 110L)) {
                assertThat(globMatches(DatastoreCacheKeys.evictionPattern(other), key))
                        .as("%s against client %s", key, other).isFalse();
            }
        }
    }

    @Test
    @DisplayName("fixed keys have the design 5.9 shape")
    void fixedKeyShapes() {
        assertThat(DatastoreCacheKeys.summary(CLIENT)).isEqualTo("dspm:dssummary:10:all");
        assertThat(DatastoreCacheKeys.accountLabels(CLIENT)).isEqualTo("dspm:dsacct:10:labels");
        assertThat(DatastoreCacheKeys.overview(CLIENT, SOURCE_ID)).isEqualTo("dspm:dsoverview:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.scanProgress(CLIENT, SOURCE_ID)).isEqualTo("dspm:dsscan:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.sensitives(CLIENT, SOURCE_ID)).isEqualTo("dspm:dssens:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.files(CLIENT, SOURCE_ID)).isEqualTo("dspm:dsfiles:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.risks(CLIENT, SOURCE_ID)).isEqualTo("dspm:dsrisks:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.accessGraph(CLIENT, SOURCE_ID)).isEqualTo("dspm:dsaccess:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.scanHistory(CLIENT, SOURCE_ID)).isEqualTo("dspm:dshistory:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.dataFlowDatabases(CLIENT, SOURCE_ID)).isEqualTo("dspm:dsdfdb:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.dataFlowSchemas(CLIENT, UUID)).isEqualTo("dspm:dsdfschemas:10:" + UUID);
        assertThat(DatastoreCacheKeys.dataFlowSchemaTables(CLIENT, UUID, 3)).isEqualTo("dspm:dsdftables:10:s:" + UUID + ":3");
        assertThat(DatastoreCacheKeys.dataFlowDatabaseTables(CLIENT, UUID, 3))
                .isEqualTo("dspm:dsdftables:10:d:" + UUID + ":3");
        assertThat(DatastoreCacheKeys.dataFlowColumns(CLIENT, UUID, 3)).isEqualTo("dspm:dsdfcols:10:" + UUID + ":3");
    }

    @Test
    @DisplayName("list and facets keys are a readable prefix plus 64 lower-case hex characters")
    void listAndFacetsKeysAreHashed() {
        DatastoreListQuery list = DatastoreListQuery.forList(params("tab", "cloud", "provider", "S3"));
        DatastoreListQuery facets = DatastoreListQuery.forFilters(params("tab", "on_prem", "q", "orders"));

        assertThat(DatastoreCacheKeys.list(CLIENT, list)).matches("^dspm:dslist:10:cloud:[0-9a-f]{64}$");
        assertThat(DatastoreCacheKeys.facets(CLIENT, facets)).matches("^dspm:dsfacets:10:on_prem:[0-9a-f]{64}$");
        assertThat(DatastoreCacheKeys.list(CLIENT, list))
                .endsWith(DatastoreCacheKeys.sha256Hex(list.canonicalListFragment()));
        assertThat(DatastoreCacheKeys.facets(CLIENT, facets))
                .endsWith(DatastoreCacheKeys.sha256Hex(facets.canonicalFacetsFragment()));
    }

    @Test
    @DisplayName("sha256Hex is the standard digest, lower-case")
    void sha256Hex() {
        assertThat(DatastoreCacheKeys.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("neither the search term nor a filter value reaches a list or facets key")
    void noNeedleInAKey() {
        MultiValueMap<String, String> request = params("tab", "endpoint", "q", "needle-term", "hostname", "needle-host");
        String listKey = DatastoreCacheKeys.list(CLIENT, DatastoreListQuery.forList(request));
        String facetsKey = DatastoreCacheKeys.facets(CLIENT, DatastoreListQuery.forFilters(request));

        assertThat(listKey).doesNotContain("needle");
        assertThat(facetsKey).doesNotContain("needle");
    }

    @Test
    @DisplayName("the colliding pair from the review gets different keys")
    void collidingPairDiffers() {
        MultiValueMap<String, String> searchOnly = params("q", "X|-severity|1|10|data_element=FOO");
        MultiValueMap<String, String> withFilter = params("q", "X", "data_element", "FOO|-severity|1|10");

        assertThat(DatastoreCacheKeys.list(CLIENT, DatastoreListQuery.forList(searchOnly)))
                .isNotEqualTo(DatastoreCacheKeys.list(CLIENT, DatastoreListQuery.forList(withFilter)));
        assertThat(DatastoreCacheKeys.facets(CLIENT, DatastoreListQuery.forFilters(searchOnly)))
                .isNotEqualTo(DatastoreCacheKeys.facets(CLIENT, DatastoreListQuery.forFilters(withFilter)));
    }

    @Test
    @DisplayName("reordered values and parameters give the same key")
    void orderDoesNotMatter() {
        MultiValueMap<String, String> first = params("tab", "cloud", "provider", "S3,EBS", "scan_status", "FAILED");
        MultiValueMap<String, String> second = params("scan_status", "FAILED", "provider", "EBS", "provider", "S3",
                "tab", "cloud");

        assertThat(DatastoreCacheKeys.list(CLIENT, DatastoreListQuery.forList(first)))
                .isEqualTo(DatastoreCacheKeys.list(CLIENT, DatastoreListQuery.forList(second)));
        assertThat(DatastoreCacheKeys.facets(CLIENT, DatastoreListQuery.forFilters(first)))
                .isEqualTo(DatastoreCacheKeys.facets(CLIENT, DatastoreListQuery.forFilters(second)));
    }

    @Test
    @DisplayName("the KPI's unfiltered facets share the key of an unfiltered filter-options request")
    void kpiFacetsShareTheFilterOptionsKey() {
        for (DatastoreTab tab : List.of(DatastoreTab.CLOUD, DatastoreTab.SAAS, DatastoreTab.ENDPOINT, DatastoreTab.ON_PREM)) {
            assertThat(DatastoreCacheKeys.facets(CLIENT, DatastoreListQuery.unfilteredFacets(tab)))
                    .as(tab.slug())
                    .isEqualTo(DatastoreCacheKeys.facets(CLIENT, DatastoreListQuery.forFilters(params("tab", tab.slug()))));
        }
    }

    // ── build 2: SaaS, SaaS file, Endpoint and Graph View keys (design 5.8) ─────────────────────────

    @Test
    @DisplayName("build 2 fixed keys have the design 5.8 shape")
    void drawerKeyShapes() {
        assertThat(DatastoreCacheKeys.saasHeader(CLIENT, SOURCE_ID)).isEqualTo("dspm:dssaashead:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.saasRecords(CLIENT, SOURCE_ID, "document_classes"))
                .isEqualTo("dspm:dssaasrec:10:" + SOURCE_ID + ":document_classes");
        assertThat(DatastoreCacheKeys.saasScanSummary(CLIENT, SOURCE_ID)).isEqualTo("dspm:dssaassum:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.saasFileOverview(CLIENT, FILE_ID)).isEqualTo("dspm:dssfile:10:" + FILE_ID);
        assertThat(DatastoreCacheKeys.saasFileActivity(CLIENT, FILE_ID)).isEqualTo("dspm:dssfileact:10:" + FILE_ID);
        assertThat(DatastoreCacheKeys.saasFileLineage(CLIENT, FILE_ID)).isEqualTo("dspm:dssfilelin:10:" + FILE_ID);
        assertThat(DatastoreCacheKeys.saasFileCopies(CLIENT, FILE_ID)).isEqualTo("dspm:dssfilecopies:10:" + FILE_ID);
        assertThat(DatastoreCacheKeys.endpointOverview(CLIENT, SOURCE_ID)).isEqualTo("dspm:dsep:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.endpointFilterOptions(CLIENT, SOURCE_ID))
                .isEqualTo("dspm:dsepopts:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.endpointSummary(CLIENT, SOURCE_ID)).isEqualTo("dspm:dsepsum:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.endpointScans(CLIENT, SOURCE_ID, 20, 10))
                .isEqualTo("dspm:dsepscans:10:" + SOURCE_ID + ":20-10");
        assertThat(DatastoreCacheKeys.dataFlowSchemaErd(CLIENT, UUID)).isEqualTo("dspm:dsdferd:10:s:" + UUID);
        assertThat(DatastoreCacheKeys.dataFlowDatabaseErd(CLIENT, UUID)).isEqualTo("dspm:dsdferd:10:d:" + UUID);
        assertThat(DatastoreCacheKeys.dataFlowTableRelations(CLIENT, UUID)).isEqualTo("dspm:dsdfrel:10:" + UUID);
    }

    @Test
    @DisplayName("SaaS files, endpoint metrics and endpoint files keys end in the SHA-256 of their fragment")
    void drawerQueryKeysAreHashed() {
        SaasFileQuery saas = new SaasFileQuery(SaasFileQuery.Kind.DOCUMENT_CLASS, "Budget Plans", null, 0, 5);
        EndpointFilters filters = EndpointFilters.parse(params("category", "it & technical"));
        EndpointFileQuery files = new EndpointFileQuery(filters, "notes", 10, 10);

        assertThat(DatastoreCacheKeys.saasFiles(CLIENT, SOURCE_ID, saas))
                .matches("^dspm:dssaasfiles:10:" + SOURCE_ID + ":[0-9a-f]{64}$")
                .endsWith(DatastoreCacheKeys.sha256Hex(saas.canonicalFragment()));
        assertThat(DatastoreCacheKeys.endpointMetrics(CLIENT, SOURCE_ID, filters, false))
                .matches("^dspm:dsepmet:10:" + SOURCE_ID + ":[0-9a-f]{64}$")
                .endsWith(DatastoreCacheKeys.sha256Hex(filters.canonicalFragment() + "&fp=false"));
        assertThat(DatastoreCacheKeys.endpointFiles(CLIENT, SOURCE_ID, files))
                .matches("^dspm:dsepfiles:10:" + SOURCE_ID + ":[0-9a-f]{64}$")
                .endsWith(DatastoreCacheKeys.sha256Hex(files.canonicalFragment()));
    }

    @Test
    @DisplayName("no search needle, data element, class or filter value reaches a build 2 key")
    void noNeedleInADrawerKey() {
        EndpointFilters filters = EndpointFilters.parse(params("sensitives", "NEEDLE_CODE",
                "documentClass", "needle-class", "category", "needle-category", "dataElement", "needle-element"));
        List<String> keys = List.of(
                DatastoreCacheKeys.saasFiles(CLIENT, SOURCE_ID,
                        new SaasFileQuery(SaasFileQuery.Kind.DATA_ELEMENT, "needle-element", "needle-search", 0, 5)),
                DatastoreCacheKeys.saasFiles(CLIENT, SOURCE_ID,
                        new SaasFileQuery(SaasFileQuery.Kind.DOCUMENT_CLASS, "needle-class", null, 0, 5)),
                DatastoreCacheKeys.endpointMetrics(CLIENT, SOURCE_ID, filters, true),
                DatastoreCacheKeys.endpointFiles(CLIENT, SOURCE_ID, new EndpointFileQuery(filters, "needle-q", 0, 10)));

        assertThat(keys).allSatisfy(key -> assertThat(key).doesNotContainIgnoringCase("needle"));
    }

    @Test
    @DisplayName("reordered filter values and parameters give one metrics key and one files key")
    void filterOrderDoesNotMatter() {
        EndpointFilters first = EndpointFilters.parse(params("documentClass", "b,a", "category", "x", "sensitives", "PII"));
        EndpointFilters second = EndpointFilters.parse(params("sensitives", "PII", "category", "x",
                "documentClass", "a", "documentClass", "b", "documentClass", "a"));

        assertThat(DatastoreCacheKeys.endpointMetrics(CLIENT, SOURCE_ID, first, false))
                .isEqualTo(DatastoreCacheKeys.endpointMetrics(CLIENT, SOURCE_ID, second, false));
        assertThat(DatastoreCacheKeys.endpointFiles(CLIENT, SOURCE_ID, new EndpointFileQuery(first, "q", 0, 10)))
                .isEqualTo(DatastoreCacheKeys.endpointFiles(CLIENT, SOURCE_ID, new EndpointFileQuery(second, "q", 0, 10)));
    }

    @Test
    @DisplayName("the false-positive switch, the record kind and the page give distinct keys")
    void drawerKeysSeparateTheirQueries() {
        assertThat(DatastoreCacheKeys.endpointMetrics(CLIENT, SOURCE_ID, EndpointFilters.NONE, true))
                .isNotEqualTo(DatastoreCacheKeys.endpointMetrics(CLIENT, SOURCE_ID, EndpointFilters.NONE, false));
        assertThat(DatastoreCacheKeys.saasFiles(CLIENT, SOURCE_ID,
                new SaasFileQuery(SaasFileQuery.Kind.DATA_ELEMENT, "Budget", null, 0, 5)))
                .isNotEqualTo(DatastoreCacheKeys.saasFiles(CLIENT, SOURCE_ID,
                        new SaasFileQuery(SaasFileQuery.Kind.DOCUMENT_CLASS, "Budget", null, 0, 5)));
        assertThat(DatastoreCacheKeys.endpointFiles(CLIENT, SOURCE_ID,
                new EndpointFileQuery(EndpointFilters.NONE, null, 0, 10)))
                .isNotEqualTo(DatastoreCacheKeys.endpointFiles(CLIENT, SOURCE_ID,
                        new EndpointFileQuery(EndpointFilters.NONE, null, 10, 10)));
        assertThat(DatastoreCacheKeys.endpointScans(CLIENT, SOURCE_ID, 1, 10))
                .isNotEqualTo(DatastoreCacheKeys.endpointScans(CLIENT, SOURCE_ID, 11, 0));
    }

    // ── build 4: Data Lineage keys (design 5.8) ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Data Lineage keys have the design 5.8 shape, the files page joined by '-'")
    void lineageKeyShapes() {
        LineageRowQuery query = new LineageRowQuery(null, 0, 7, LineageFilters.NONE);
        assertThat(DatastoreCacheKeys.lineageOptions(CLIENT)).isEqualTo("dspm:lnopts:10:all");
        assertThat(DatastoreCacheKeys.lineageCategories(CLIENT)).isEqualTo("dspm:lncats:10:all");
        assertThat(DatastoreCacheKeys.lineagePolicies(CLIENT, ExposureCategory.STALE_DATA, query))
                .matches("^dspm:lnpol:10:STALE_DATA:[0-9a-f]{64}$")
                .endsWith(DatastoreCacheKeys.sha256Hex(query.canonicalFragment()));
        assertThat(DatastoreCacheKeys.lineageDatastores(CLIENT, UUID, query))
                .matches("^dspm:lnds:10:" + UUID + ":[0-9a-f]{64}$")
                .endsWith(DatastoreCacheKeys.sha256Hex(query.canonicalFragment()));
        assertThat(DatastoreCacheKeys.lineageFiles(CLIENT, EXPOSURE_ID, 14, 7))
                .isEqualTo("dspm:lnfiles:10:" + EXPOSURE_ID + ":14-7");
    }

    @Test
    @DisplayName("no search text or filter value reaches a Data Lineage key; filters and pages separate keys")
    void noNeedleInALineageKey() {
        LineageFilters filters = LineageFilters.parse(params("severity", "NEEDLE_SEV",
                "datastoreName_exclude", "needle-store", "label", "needle-label"), LineageFilters.POLICY_DIMENSIONS);
        LineageRowQuery query = new LineageRowQuery("needle-search", 0, 7, filters);
        List<String> keys = List.of(
                DatastoreCacheKeys.lineagePolicies(CLIENT, ExposureCategory.SENSITIVE_FINDINGS, query),
                DatastoreCacheKeys.lineageDatastores(CLIENT, UUID, query));

        assertThat(keys).allSatisfy(key -> assertThat(key).doesNotContainIgnoringCase("needle"));
        assertThat(DatastoreCacheKeys.lineagePolicies(CLIENT, ExposureCategory.SENSITIVE_FINDINGS, query))
                .isNotEqualTo(DatastoreCacheKeys.lineagePolicies(CLIENT, ExposureCategory.SENSITIVE_FINDINGS,
                        new LineageRowQuery("needle-search", 0, 7, LineageFilters.NONE)));
        assertThat(DatastoreCacheKeys.lineageDatastores(CLIENT, UUID, new LineageRowQuery(null, 0, 7, null)))
                .isNotEqualTo(DatastoreCacheKeys.lineageDatastores(CLIENT, UUID, new LineageRowQuery(null, 7, 7, null)));
        assertThat(DatastoreCacheKeys.lineageFiles(CLIENT, EXPOSURE_ID, 1, 10))
                .isNotEqualTo(DatastoreCacheKeys.lineageFiles(CLIENT, EXPOSURE_ID, 11, 0));
    }

    @Test
    @DisplayName("the Misconfigurations snapshot key is dspm:dsmisconf:<c>:all and its client's glob evicts it")
    void misconfigurationsKey() {
        assertThat(DatastoreCacheKeys.misconfigurations(CLIENT)).isEqualTo("dspm:dsmisconf:10:all");
        assertThat(globMatches(DatastoreCacheKeys.evictionPattern(CLIENT), DatastoreCacheKeys.misconfigurations(CLIENT)))
                .isTrue();
        assertThat(globMatches(DatastoreCacheKeys.evictionPattern(11L), DatastoreCacheKeys.misconfigurations(CLIENT)))
                .isFalse();
    }

    // ── build 6: Dashboard, pages, Scan Management and drawer extras (build 6 design 5.8) ───────────

    private static final RemediationQuery REMEDIATION =
            new RemediationQuery("needle-remediation", "COMPLETED", "MAKE_PRIVATE", 10, 11);
    private static final ScanListQuery SCHEDULES = ScanListQuery.from(ScanListQuery.Kind.SCHEDULES,
            params("q", "needle-schedule", "platform", "cloud", "page", "1", "pageSize", "11"));
    private static final ScanListQuery RUNS = ScanListQuery.from(ScanListQuery.Kind.RUNS,
            params("q", "needle-run", "trigger", "SCHEDULED", "page", "11", "pageSize", "99"));
    private static final ScanListQuery SCOPES = ScanListQuery.from(ScanListQuery.Kind.SCOPES,
            params("q", "needle-scope", "page", "1", "pageSize", "10"));
    private static final ScanListQuery RUN_DATASTORES = ScanListQuery.from(ScanListQuery.Kind.RUN_DATASTORES,
            params("q", "needle-ds", "account", "needle-account", "page", "1", "pageSize", "10"));
    private static final SaasUserQuery USERS =
            new SaasUserQuery("GMAIL", "needle-user", "ACTIVE", "needle-domain.example", 1, 10);
    private static final SaasChannelQuery CHANNELS =
            new SaasChannelQuery("needle-channel", "PUBLIC", "ACTIVE", "ENABLED", true, 1, 10);

    /** One key from every build 6 factory. */
    private static List<String> everyBuild6Key(Long clientId) {
        return List.of(
                DatastoreCacheKeys.dashboard(clientId),
                DatastoreCacheKeys.assetRows(clientId, DatastoreTab.ALL),
                DatastoreCacheKeys.assetRows(clientId, DatastoreTab.ON_PREM),
                DatastoreCacheKeys.documentClasses(clientId),
                DatastoreCacheKeys.assists(clientId),
                DatastoreCacheKeys.remediation(clientId, REMEDIATION),
                DatastoreCacheKeys.scanList(clientId, SCHEDULES),
                DatastoreCacheKeys.scanList(clientId, RUNS),
                DatastoreCacheKeys.scanList(clientId, SCOPES),
                DatastoreCacheKeys.scanRunDatastores(clientId, UUID, RUN_DATASTORES),
                DatastoreCacheKeys.scanSchedule(clientId, SOURCE_ID),
                DatastoreCacheKeys.scanRun(clientId, UUID),
                DatastoreCacheKeys.scanScope(clientId, UUID),
                DatastoreCacheKeys.saasIntegrations(clientId),
                DatastoreCacheKeys.saasConfiguration(clientId, FILE_ID),
                DatastoreCacheKeys.saasUsers(clientId, FILE_ID, USERS),
                DatastoreCacheKeys.saasChannels(clientId, FILE_ID, CHANNELS),
                DatastoreCacheKeys.endpointConfiguration(clientId),
                DatastoreCacheKeys.scanSettings(clientId, SOURCE_ID),
                DatastoreCacheKeys.databases(clientId, SOURCE_ID),
                DatastoreCacheKeys.syncStatus(clientId, SOURCE_ID));
    }

    @Test
    @DisplayName("build 6: every key matches its client's evictClient glob and no other client's")
    void build6KeysAreEvictedWithTheirClient() {
        for (String key : everyBuild6Key(CLIENT)) {
            assertThat(globMatches(DatastoreCacheKeys.evictionPattern(CLIENT), key)).as(key).isTrue();
            for (Long other : List.of(1L, 11L, 99L, 110L)) {
                assertThat(globMatches(DatastoreCacheKeys.evictionPattern(other), key))
                        .as("%s against client %s", key, other).isFalse();
            }
        }
    }

    @Test
    @DisplayName("build 6: keys have the design 5.8 shape; query keys end in the SHA-256 of their fragment")
    void build6KeyShapes() {
        assertThat(DatastoreCacheKeys.dashboard(CLIENT)).isEqualTo("dspm:dsdash:10:all");
        assertThat(DatastoreCacheKeys.assetRows(CLIENT, DatastoreTab.ON_PREM)).isEqualTo("dspm:dsrows:10:on_prem");
        assertThat(DatastoreCacheKeys.assetRows(CLIENT, DatastoreTab.ALL)).isEqualTo("dspm:dsrows:10:all");
        assertThat(DatastoreCacheKeys.documentClasses(CLIENT)).isEqualTo("dspm:dsdocclass:10:all");
        assertThat(DatastoreCacheKeys.assists(CLIENT)).isEqualTo("dspm:dsassists:10:all");
        assertThat(DatastoreCacheKeys.scanSchedule(CLIENT, SOURCE_ID)).isEqualTo("dspm:dssmschedone:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.scanRun(CLIENT, UUID)).isEqualTo("dspm:dssmrun:10:" + UUID);
        assertThat(DatastoreCacheKeys.scanScope(CLIENT, UUID)).isEqualTo("dspm:dssmscope:10:" + UUID);
        assertThat(DatastoreCacheKeys.saasIntegrations(CLIENT)).isEqualTo("dspm:dssaasint:10:all");
        assertThat(DatastoreCacheKeys.saasConfiguration(CLIENT, FILE_ID)).isEqualTo("dspm:dssaascfg:10:" + FILE_ID);
        assertThat(DatastoreCacheKeys.endpointConfiguration(CLIENT)).isEqualTo("dspm:dsepcfg:10:all");
        assertThat(DatastoreCacheKeys.scanSettings(CLIENT, SOURCE_ID)).isEqualTo("dspm:dsscanset:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.databases(CLIENT, SOURCE_ID)).isEqualTo("dspm:dsdbs:10:" + SOURCE_ID);
        assertThat(DatastoreCacheKeys.syncStatus(CLIENT, SOURCE_ID)).isEqualTo("dspm:dssync:10:" + SOURCE_ID);

        assertThat(DatastoreCacheKeys.remediation(CLIENT, REMEDIATION))
                .isEqualTo("dspm:dsremed:10:" + DatastoreCacheKeys.sha256Hex(REMEDIATION.canonicalFragment()));
        assertThat(DatastoreCacheKeys.scanList(CLIENT, SCHEDULES))
                .isEqualTo("dspm:dssmsched:10:" + DatastoreCacheKeys.sha256Hex(SCHEDULES.canonicalFragment()));
        assertThat(DatastoreCacheKeys.scanList(CLIENT, RUNS))
                .isEqualTo("dspm:dssmruns:10:" + DatastoreCacheKeys.sha256Hex(RUNS.canonicalFragment()));
        assertThat(DatastoreCacheKeys.scanList(CLIENT, SCOPES))
                .isEqualTo("dspm:dssmscopes:10:" + DatastoreCacheKeys.sha256Hex(SCOPES.canonicalFragment()));
        assertThat(DatastoreCacheKeys.scanRunDatastores(CLIENT, UUID, RUN_DATASTORES)).isEqualTo(
                "dspm:dssmrunds:10:" + UUID + ":" + DatastoreCacheKeys.sha256Hex(RUN_DATASTORES.canonicalFragment()));
        assertThat(DatastoreCacheKeys.saasUsers(CLIENT, FILE_ID, USERS)).isEqualTo(
                "dspm:dssaasusers:10:" + FILE_ID + ":" + DatastoreCacheKeys.sha256Hex(USERS.canonicalFragment()));
        assertThat(DatastoreCacheKeys.saasChannels(CLIENT, FILE_ID, CHANNELS)).isEqualTo(
                "dspm:dssaaschan:10:" + FILE_ID + ":" + DatastoreCacheKeys.sha256Hex(CHANNELS.canonicalFragment()));
    }

    @Test
    @DisplayName("build 6: no search term, account, domain or code reaches a key; a run's datastores need their own factory")
    void noNeedleInABuild6Key() {
        assertThat(everyBuild6Key(CLIENT)).allSatisfy(key -> assertThat(key)
                .doesNotContainIgnoringCase("needle")
                .doesNotContain("MAKE_PRIVATE", "COMPLETED", "SCHEDULED", "GMAIL", "ENABLED"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> DatastoreCacheKeys.scanList(CLIENT, RUN_DATASTORES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(DatastoreCacheKeys.scanList(CLIENT, SCHEDULES))
                .isNotEqualTo(DatastoreCacheKeys.scanList(CLIENT, ScanListQuery.from(ScanListQuery.Kind.SCHEDULES,
                        params("q", "needle-schedule", "platform", "cloud", "page", "2", "pageSize", "11"))));
    }

    @Test
    @DisplayName("the same query for two clients gives two keys")
    void keysCarryTheClientId() {
        DatastoreListQuery query = DatastoreListQuery.forList(params("tab", "cloud"));
        assertThat(DatastoreCacheKeys.list(CLIENT, query)).startsWith("dspm:dslist:10:");
        assertThat(DatastoreCacheKeys.list(11L, query)).startsWith("dspm:dslist:11:");
    }

    @Test
    @DisplayName("Files keys: fixed shapes, hashed lists, no search, filter value or cursor in clear, order-free")
    void filesKeys() {
        assertThat(DatastoreCacheKeys.filesSaasOptions(CLIENT)).isEqualTo("dspm:fsaasopts:10:all");
        assertThat(DatastoreCacheKeys.filesEndpointOptions(CLIENT)).isEqualTo("dspm:fepopts:10:all");
        assertThat(DatastoreCacheKeys.endpointFileOverview(CLIENT, UUID)).isEqualTo("dspm:fepfile:10:" + UUID);
        assertThat(DatastoreCacheKeys.endpointFileActivity(CLIENT, UUID)).isEqualTo("dspm:fepact:10:" + UUID);
        assertThat(DatastoreCacheKeys.endpointFileLineage(CLIENT, UUID)).isEqualTo("dspm:feplin:10:" + UUID);

        SaasFileListQuery saas = SaasFileListQuery.parse(params("q", "needle-search", "cursor", "needlecursor",
                "sensitives", "NEEDLE_CODE", "documentClass", "needle-class", "category", "needle-category",
                "appType", "NEEDLE_APP", "internalUser", "needle@example.test", "externalUser", "needle@example.org"));
        EndpointFileListQuery endpoint = EndpointFileListQuery.parse(params("q", "needle-q", "tag", "needle-tag",
                "uid", "needle-uid", "hostname", "needle-host", "dataElement", "needle-element"));
        assertThat(DatastoreCacheKeys.filesSaas(CLIENT, saas)).matches("^dspm:fsaas:10:[0-9a-f]{64}$")
                .endsWith(DatastoreCacheKeys.sha256Hex(saas.canonicalFragment()))
                .doesNotContainIgnoringCase("needle");
        assertThat(DatastoreCacheKeys.filesEndpoint(CLIENT, endpoint)).matches("^dspm:fep:10:[0-9a-f]{64}$")
                .doesNotContainIgnoringCase("needle");

        assertThat(DatastoreCacheKeys.filesSaas(CLIENT, SaasFileListQuery.parse(params("category", "b,a"))))
                .isEqualTo(DatastoreCacheKeys.filesSaas(CLIENT,
                        SaasFileListQuery.parse(params("category", "a", "category", "b"))));
        assertThat(DatastoreCacheKeys.filesEndpoint(CLIENT, EndpointFileListQuery.parse(params("uid", "b,a"))))
                .isEqualTo(DatastoreCacheKeys.filesEndpoint(CLIENT, EndpointFileListQuery.parse(params("uid", "a,b"))));
        assertThat(DatastoreCacheKeys.filesSaas(CLIENT, SaasFileListQuery.parse(params("cursor", "abc"))))
                .isNotEqualTo(DatastoreCacheKeys.filesSaas(CLIENT, SaasFileListQuery.parse(params())));
    }
}
