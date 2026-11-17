package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Design 5.3.1 to 5.3.3: list and filter-options validation, vendor params and canonical fragments. */
class DatastoreListQueryTest {

    private static final String HEX_A = "0123456789abcdef01234567";

    private static MultiValueMap<String, String> params(String... keyValues) {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.add(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static void assertRejected(MultiValueMap<String, String> params, String parameter) {
        assertThatThrownBy(() -> DatastoreListQuery.forList(params))
                .isInstanceOfSatisfying(DspmBadRequestException.class,
                        e -> assertThat(e.parameter()).isEqualTo(parameter));
    }

    private static void assertFiltersRejected(MultiValueMap<String, String> params, String parameter) {
        assertThatThrownBy(() -> DatastoreListQuery.forFilters(params))
                .isInstanceOfSatisfying(DspmBadRequestException.class,
                        e -> assertThat(e.parameter()).isEqualTo(parameter));
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ── tab ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tab defaults to all and accepts exactly the five slugs")
    void tabEnumAndDefault() {
        assertThat(DatastoreListQuery.forList(params()).tab()).isEqualTo(DatastoreTab.ALL);
        assertThat(DatastoreListQuery.forList(params("tab", "cloud")).tab()).isEqualTo(DatastoreTab.CLOUD);
        assertThat(DatastoreListQuery.forList(params("tab", "saas")).tab()).isEqualTo(DatastoreTab.SAAS);
        assertThat(DatastoreListQuery.forList(params("tab", "endpoint")).tab()).isEqualTo(DatastoreTab.ENDPOINT);
        assertThat(DatastoreListQuery.forList(params("tab", "on_prem")).tab()).isEqualTo(DatastoreTab.ON_PREM);
        assertThat(DatastoreListQuery.forList(params("tab", "all")).tab()).isEqualTo(DatastoreTab.ALL);
        assertRejected(params("tab", "Cloud"), "tab");
        assertRejected(params("tab", "on-prem"), "tab");
        assertRejected(params("tab", ""), "tab");
    }

    @Test
    void tabSlugAndVendorCategory() {
        assertThat(DatastoreTab.ON_PREM.slug()).isEqualTo("on_prem");
        assertThat(DatastoreTab.ON_PREM.vendorCategory()).isEqualTo("on_prem");
        assertThat(DatastoreTab.ALL.vendorCategory()).isNull();
        assertThatThrownBy(() -> DatastoreTab.fromSlug(null)).isInstanceOf(DspmBadRequestException.class);
    }

    // ── paging and sort ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("page and pageSize default to 1 and 10 and reject bad formats and ranges")
    void pageAndPageSizeBounds() {
        DatastoreListQuery query = DatastoreListQuery.forList(params());
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.pageSize()).isEqualTo(10);

        DatastoreListQuery explicit = DatastoreListQuery.forList(params("page", "3", "pageSize", "100"));
        assertThat(explicit.page()).isEqualTo(3);
        assertThat(explicit.pageSize()).isEqualTo(100);

        assertRejected(params("page", "0"), "page");
        assertRejected(params("page", "-1"), "page");
        assertRejected(params("page", "abc"), "page");
        assertRejected(params("page", "1234567"), "page");
        assertRejected(params("page", ""), "page");
        assertRejected(params("pageSize", "0"), "pageSize");
        assertRejected(params("pageSize", "101"), "pageSize");
        assertRejected(params("pageSize", "1000"), "pageSize");
        assertRejected(params("pageSize", "1.5"), "pageSize");
    }

    @Test
    @DisplayName("the list offset may reach 10,000 but not pass it; the error names page")
    void offsetBound() {
        assertThat(DatastoreListQuery.forList(params("page", "1001", "pageSize", "10")).page()).isEqualTo(1001);
        assertThat(DatastoreListQuery.forList(params("page", "101", "pageSize", "100")).page()).isEqualTo(101);
        assertRejected(params("page", "1002", "pageSize", "10"), "page");
        assertRejected(params("page", "102", "pageSize", "100"), "page");
        assertRejected(params("page", "999999", "pageSize", "100"), "page");
    }

    @Test
    @DisplayName("sort accepts the six values and defaults to -severity")
    void sortSetAndDefault() {
        assertThat(DatastoreListQuery.forList(params()).sort()).isEqualTo("-severity");
        for (String sort : List.of("severity", "-severity", "name", "-name",
                "sensitive_record_count", "-sensitive_record_count")) {
            assertThat(DatastoreListQuery.forList(params("sort", sort)).sort()).isEqualTo(sort);
        }
        assertRejected(params("sort", "-risk_score"), "sort");
        assertRejected(params("sort", "Name"), "sort");
    }

    @Test
    void repeatedControlParameterIsRejected() {
        assertRejected(params("page", "1", "page", "2"), "page");
        assertRejected(params("tab", "cloud", "tab", "cloud"), "tab");
        assertRejected(params("q", "a", "q", "b"), "q");
    }

    // ── q ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("q is trimmed, blank means absent, at most 200 characters, no control characters")
    void searchRules() {
        assertThat(DatastoreListQuery.forList(params("q", "  needle  ")).search()).isEqualTo("needle");
        assertThat(DatastoreListQuery.forList(params("q", "   ")).search()).isNull();
        assertThat(DatastoreListQuery.forList(params()).search()).isNull();
        assertThat(DatastoreListQuery.forList(params("q", repeat('x', 200))).search()).hasSize(200);
        assertRejected(params("q", repeat('x', 201)), "q");
        assertRejected(params("q", "nee\u0007dle"), "q");
        assertFiltersRejected(params("q", "a\u0000b"), "q");
    }

    @Test
    @DisplayName("rejection messages never contain the rejected value")
    void messagesNeverEchoTheValue() {
        String secret = "needle-" + repeat('z', 200);
        assertThatThrownBy(() -> DatastoreListQuery.forList(params("q", secret)))
                .isInstanceOf(DspmBadRequestException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("needle"));
        assertThatThrownBy(() -> DatastoreListQuery.forList(params("scan_status", "needle")))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("needle"));
        assertThatThrownBy(() -> DatastoreListQuery.forList(params("region", "needle" + repeat('r', 130))))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("needle"));
    }

    // ── dimension names ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a dimension outside the tab's allow-list is rejected, and so is any unknown name")
    void unknownDimensionPerTab() {
        assertRejected(params("tab", "cloud", "selected", "yes"), "selected");
        assertThat(DatastoreListQuery.forList(params("tab", "saas", "selected", "yes")).filters())
                .containsKey("selected");
        assertRejected(params("tab", "cloud", "os", "MAC"), "os");
        assertRejected(params("tab", "all", "os", "MAC"), "os");
        assertThat(DatastoreListQuery.forList(params("tab", "endpoint", "os", "MAC")).filters())
                .containsKey("os");
        assertRejected(params("lifecycle", "ACTIVE"), "lifecycle");
        assertRejected(params("resource_type", "DATASTORE"), "resource_type");
        assertRejected(params("clientId", "99"), "clientId");
        assertRejected(params("tab", "endpoint", "selected", "yes"), "selected");
        assertFiltersRejected(params("clientId", "99"), "clientId");
    }

    @Test
    void filterOptionsRejectListOnlyControls() {
        assertFiltersRejected(params("page", "1"), "page");
        assertFiltersRejected(params("sort", "name"), "sort");
        assertFiltersRejected(params("pageSize", "10"), "pageSize");
        assertThat(DatastoreListQuery.forFilters(params("tab", "cloud", "q", "x", "provider", "S3"))
                .filters()).containsKey("provider");
    }

    @Test
    @DisplayName("endpoint dimensions are accepted on the endpoint tab")
    void endpointDimensions() {
        DatastoreListQuery query = DatastoreListQuery.forList(params("tab", "endpoint",
                "agent_status_exclude", "OFFLINE", "device_type", "laptop", "serial_number", "SN-1",
                "hostname", "host-a", "user", "a+b@example.com"));
        assertThat(query.filters().keySet())
                .containsExactly("agent_status", "device_type", "hostname", "serial_number", "user");
        assertThat(query.filters().get("agent_status").exclude()).isTrue();
    }

    // ── values ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("integration ids must be 24 hex characters and are lower-cased")
    void integrationIdFormat() {
        assertRejected(params("integration_id", repeat('a', 23)), "integration_id");
        assertRejected(params("integration_id", repeat('a', 25)), "integration_id");
        assertRejected(params("integration_id", repeat('g', 24)), "integration_id");
        DatastoreListQuery query = DatastoreListQuery.forList(params("integration_id", HEX_A.toUpperCase()));
        assertThat(query.filters().get("integration_id").values()).containsExactly(HEX_A);
    }

    @Test
    @DisplayName("enum, code and free-text value rules")
    void valueRules() {
        assertRejected(params("scan_status", "DONE"), "scan_status");
        assertRejected(params("scan_status", "classified"), "scan_status");
        assertRejected(params("severity", "INFO"), "severity");
        assertRejected(params("provider", "s3"), "provider");
        assertRejected(params("provider", "S3-X"), "provider");
        assertRejected(params("sensitive_type", "pii"), "sensitive_type");
        assertRejected(params("region", repeat('r', 129)), "region");
        assertRejected(params("data_element", "a\u0001b"), "data_element");
        assertThat(DatastoreListQuery.forList(params("region", repeat('r', 128))).filters()).containsKey("region");
        assertThat(DatastoreListQuery.forList(params("severity", "NONE", "scan_status", "SCHEDULED"))
                .filters()).containsKeys("severity", "scan_status");
    }

    @Test
    @DisplayName("values are split, trimmed, de-duplicated and sorted; 1 to 50 of them")
    void valueListRules() {
        DatastoreListQuery merged = DatastoreListQuery.forList(params("provider", " S3 , EBS,,S3", "provider", "RDS"));
        assertThat(merged.filters().get("provider").values()).containsExactly("EBS", "RDS", "S3");

        String fifty = IntStream.range(0, 50).mapToObj(i -> "R" + i).collect(Collectors.joining(","));
        assertThat(DatastoreListQuery.forList(params("region", fifty)).filters().get("region").values()).hasSize(50);
        assertRejected(params("region", fifty + ",R50"), "region");
        assertRejected(params("provider", " , ,"), "provider");
        assertRejected(params("provider", ""), "provider");
    }

    @Test
    @DisplayName("_exclude only on excludable dimensions, and never with its include twin")
    void excludeRules() {
        assertRejected(params("tab", "endpoint", "os_exclude", "MAC"), "os_exclude");
        assertRejected(params("tab", "saas", "selected_exclude", "x"), "selected_exclude");
        assertRejected(params("provider", "S3", "provider_exclude", "EBS"), "provider_exclude");
        assertRejected(params("provider_exclude", "EBS", "provider", "S3"), "provider_exclude");
        assertRejected(params("lifecycle_exclude", "ACTIVE"), "lifecycle_exclude");
        assertThat(DatastoreListQuery.forList(params("scan_status_exclude", "FAILED"))
                .filters().get("scan_status").exclude()).isTrue();
    }

    // ── vendor params ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("include and exclude selections become one comma-joined vendor parameter")
    void vendorParams() {
        DatastoreListQuery query = DatastoreListQuery.forList(params("tab", "cloud",
                "provider", "S3,EBS", "scan_status_exclude", "FAILED", "q", "needle"));
        Map<String, List<String>> filter = query.toVendorFilterParams();
        assertThat(filter).containsEntry("category", List.of("cloud"))
                .containsEntry("q", List.of("needle"))
                .containsEntry("provider", List.of("EBS,S3"))
                .containsEntry("scan_status_exclude", List.of("FAILED"))
                .doesNotContainKeys("scan_status", "page", "sort");

        Map<String, List<String>> list = query.toVendorListParams();
        assertThat(list).containsEntry("page", List.of("1"))
                .containsEntry("page_size", List.of("10"))
                .containsEntry("sort", List.of("-severity"));

        Map<String, List<String>> listFilterTerms = new HashMap<>(list);
        listFilterTerms.keySet().removeAll(List.of("page", "page_size", "sort"));
        assertThat(listFilterTerms).isEqualTo(filter);
    }

    @Test
    void categoryOmittedForAll() {
        assertThat(DatastoreListQuery.forList(params()).toVendorFilterParams()).doesNotContainKey("category");
        assertThat(DatastoreListQuery.forList(params()).toVendorListParams())
                .doesNotContainKeys("category", "q")
                .containsEntry("sort", List.of("-severity"));
        assertThat(DatastoreListQuery.forFilters(params("tab", "on_prem")).toVendorFilterParams())
                .containsEntry("category", List.of("on_prem"));
    }

    @Test
    void listAndFilterQueriesCarryIdenticalFilterTerms() {
        MultiValueMap<String, String> shared = params("tab", "endpoint", "q", "needle",
                "os", "MAC", "agent_status_exclude", "OFFLINE");
        MultiValueMap<String, String> withList = new LinkedMultiValueMap<>(shared);
        withList.add("page", "2");
        withList.add("sort", "name");
        Map<String, List<String>> listParams = new HashMap<>(DatastoreListQuery.forList(withList).toVendorListParams());
        listParams.keySet().removeAll(List.of("page", "page_size", "sort"));
        assertThat(listParams).isEqualTo(DatastoreListQuery.forFilters(shared).toVendorFilterParams());
    }

    // ── canonical fragments ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the canonical list fragment has the documented shape")
    void canonicalListShape() {
        DatastoreListQuery query = DatastoreListQuery.forList(params(
                "scan_status_exclude", "FAILED", "q", "bucket exports", "provider", "S3,EBS"));
        assertThat(query.canonicalListFragment())
                .isEqualTo("q=bucket+exports&sort=-severity&page=1&pageSize=10&provider=EBS,S3&scan_status_exclude=FAILED");
        assertThat(query.canonicalFacetsFragment())
                .isEqualTo("q=bucket+exports&provider=EBS,S3&scan_status_exclude=FAILED");
    }

    @Test
    @DisplayName("value and parameter order do not change the fragments")
    void orderIndependence() {
        DatastoreListQuery a = DatastoreListQuery.forList(params("provider", "S3,EBS", "region", "b,a", "q", "x"));
        DatastoreListQuery b = DatastoreListQuery.forList(params("q", "x", "region", "a", "region", "b",
                "provider", "EBS", "provider", "S3"));
        assertThat(a.canonicalListFragment()).isEqualTo(b.canonicalListFragment());
        assertThat(a.canonicalFacetsFragment()).isEqualTo(b.canonicalFacetsFragment());
    }

    @Test
    @DisplayName("q and values are URL-encoded, so separators inside them cannot forge structure")
    void fragmentsEncode() {
        DatastoreListQuery query = DatastoreListQuery.forList(params("q", "a&b=c|d", "region", "eu west=1|x"));
        assertThat(query.canonicalFacetsFragment()).isEqualTo("q=a%26b%3Dc%7Cd&region=eu+west%3D1%7Cx");
    }

    @Test
    void includeAndExcludeDiffer() {
        String include = DatastoreListQuery.forFilters(params("provider", "EBS")).canonicalFacetsFragment();
        String exclude = DatastoreListQuery.forFilters(params("provider_exclude", "EBS")).canonicalFacetsFragment();
        assertThat(include).isEqualTo("q=&provider=EBS");
        assertThat(exclude).isEqualTo("q=&provider_exclude=EBS");
    }

    @Test
    @DisplayName("an absent q is written q=, and the KPI's unfiltered facets match an unfiltered request")
    void absentSearchAndUnfilteredFacets() {
        assertThat(DatastoreListQuery.forList(params()).canonicalListFragment())
                .isEqualTo("q=&sort=-severity&page=1&pageSize=10");
        DatastoreListQuery kpi = DatastoreListQuery.unfilteredFacets(DatastoreTab.CLOUD);
        DatastoreListQuery request = DatastoreListQuery.forFilters(params("tab", "cloud", "q", "  "));
        assertThat(kpi.canonicalFacetsFragment()).isEqualTo("q=").isEqualTo(request.canonicalFacetsFragment());
        assertThat(kpi.toVendorFilterParams()).isEqualTo(request.toVendorFilterParams());
        assertThat(kpi.tab()).isEqualTo(DatastoreTab.CLOUD);
    }
}
