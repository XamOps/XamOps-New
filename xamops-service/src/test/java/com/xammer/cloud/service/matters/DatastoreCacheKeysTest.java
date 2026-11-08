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
                DatastoreCacheKeys.dataFlowColumns(clientId, UUID, 11));
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

    @Test
    @DisplayName("the same query for two clients gives two keys")
    void keysCarryTheClientId() {
        DatastoreListQuery query = DatastoreListQuery.forList(params("tab", "cloud"));
        assertThat(DatastoreCacheKeys.list(CLIENT, query)).startsWith("dspm:dslist:10:");
        assertThat(DatastoreCacheKeys.list(11L, query)).startsWith("dspm:dslist:11:");
    }
}
