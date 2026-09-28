package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.DspmAssetDetail;
import com.xammer.cloud.dto.matters.DspmAssetRow;
import com.xammer.cloud.dto.matters.DspmFilters;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.DspmSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper tests built from responses captured from the live tenant on 2026-09-25.
 *
 * The fixtures keep the real field names, the real null placement and the real undocumented fields,
 * because that is the whole point: the published documentation disagrees with the service on several
 * counts, and a fixture written from the documentation would pass while the integration failed.
 */
class MattersAssetMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final MattersAssetMapper mapper = new MattersAssetMapper();

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── the "zero is not no data" rule ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a NOT_SCANNED asset reporting zero records is not treated as clean")
    void unscannedZeroIsNotKnown() {
        // Shape taken verbatim from a live row: NOT_SCANNED is absent from the documented enum, and it
        // covered 59 of 100 sampled rows.
        DspmAssetRow row = mapper.toRow(parse("""
                {
                  "id": "cc6a1b30-ae25-5d45-9ce8-50c410199d78",
                  "name": "costexports",
                  "subtype": "AZURE_BLOB",
                  "category": "cloud",
                  "resource_type": "DATASTORE",
                  "source_id": "costexports",
                  "lifecycle": {"status": "NOT_APPLICABLE"},
                  "risk": {"level": null, "policy_violation_count": 35, "score": null},
                  "sensitives": {"level": null, "object_count": 0, "record_count": 0, "types": []},
                  "source": {"account": "6aa78fec1a8b789f14de12dc", "provider": "AZURE_BLOB", "region": "westeurope"},
                  "scan_status": {"last_scanned_at": null, "stage": null, "status": "NOT_SCANNED"}
                }
                """));

        assertThat(row.sensitives().recordCount()).isZero();
        // The whole point: the count is zero but unknowable, so the UI must not render "0 sensitive
        // records" as a clean bill of health.
        assertThat(row.sensitives().known()).isFalse();
        assertThat(row.scan().statusLabel()).isEqualTo("Not scanned");
    }

    @Test
    @DisplayName("only CLASSIFIED yields a believable record count")
    void classifiedZeroIsKnown() {
        DspmAssetRow row = mapper.toRow(parse("""
                {
                  "id": "a1", "name": "xops.xammer.in", "subtype": "S3", "category": "cloud",
                  "resource_type": "DATASTORE",
                  "risk": {"level": "LOW", "policy_violation_count": null, "score": 1.0},
                  "sensitives": {"level": "NOT_SENSITIVE", "object_count": 1, "record_count": 0, "types": []},
                  "source": {"account": "x", "provider": "S3", "region": "ap-south-1"},
                  "scan_status": {"last_scanned_at": 1789129812, "stage": "SCANNING_COMPLETED", "status": "CLASSIFIED"}
                }
                """));

        assertThat(row.sensitives().known()).isTrue();
        assertThat(row.scan().statusLabel()).isEqualTo("Scanned");
        assertThat(row.scan().lastScannedAt()).isEqualTo(1789129812L);
    }

    @Test
    @DisplayName("FAILED and STOPPED scans are also not clean")
    void failedAndStoppedAreNotKnown() {
        for (String status : new String[]{"FAILED", "STOPPED", "SCANNING"}) {
            DspmAssetRow row = mapper.toRow(parse("""
                    {"id":"a","name":"b","risk":{},"sensitives":{"record_count":0},
                     "source":{},"scan_status":{"status":"%s"}}
                    """.formatted(status)));
            assertThat(row.sensitives().known())
                    .as("status %s must not imply a trustworthy zero", status)
                    .isFalse();
        }
    }

    // ── severity NONE <-> null ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a null risk level is folded to the NONE sentinel the filter dimension uses")
    void nullSeverityBecomesNone() {
        DspmAssetRow row = mapper.toRow(parse("""
                {"id":"a","name":"b","risk":{"level":null,"score":null},"sensitives":{},
                 "source":{},"scan_status":{"status":"NOT_SCANNED"}}
                """));

        // /assets/filters reports "NONE" for these rows and ?severity=NONE selects them, so the row has
        // to carry the same token or the filter rail and the table disagree.
        assertThat(row.risk().level()).isEqualTo("NONE");
        assertThat(row.risk().hasFinding()).isFalse();
    }

    @Test
    @DisplayName("a real severity is preserved and flagged as a finding")
    void realSeverityPreserved() {
        DspmAssetRow row = mapper.toRow(parse("""
                {"id":"a","name":"b","risk":{"level":"CRITICAL","score":8.44,"policy_violation_count":3},
                 "sensitives":{},"source":{},"scan_status":{"status":"STOPPED"}}
                """));

        assertThat(row.risk().level()).isEqualTo("CRITICAL");
        assertThat(row.risk().hasFinding()).isTrue();
        assertThat(row.risk().score()).isEqualTo(8.44);
        assertThat(row.risk().policyViolationCount()).isEqualTo(3);
    }

    // ── sensitive category labels ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("category codes are labelled as the vendor console labels them")
    void sensitiveTypeLabels() {
        DspmAssetRow row = mapper.toRow(parse("""
                {"id":"a","name":"b","risk":{},
                 "sensitives":{"record_count":909,"object_count":4272,"types":["PII","SECRETS"],
                               "level":"MEDIUM_SENSITIVITY"},
                 "source":{},"scan_status":{"status":"CLASSIFIED"}}
                """));

        assertThat(row.sensitives().types()).containsExactly("PII", "SECRETS");
        // Customers see the vendor console alongside XamOps; raw codes would make the same asset look
        // like it had different findings in each product.
        assertThat(row.sensitives().typeLabels()).containsExactly("Personal", "IT & Security");
        assertThat(row.sensitives().objectCount()).isEqualTo(4272L);
    }

    // ── filters, including the undocumented dimensions ───────────────────────────────────────────

    @Test
    @DisplayName("the paging object is lifted out of the dimension map, not rendered as a dimension")
    void filtersLiftPagingOut() {
        DspmFilters filters = mapper.toFilters(parse("""
                {
                  "data_element": [
                    {"value": "PERSON NAME", "count": 17},
                    {"value": "", "count": 6}
                  ],
                  "lifecycle": [{"value": "ACTIVE", "count": 64}],
                  "severity": [{"value": "NONE", "count": 109}, {"value": "CRITICAL", "count": 8}],
                  "provider": [{"value": "SELF_HOSTED_POSTGRES", "count": 1}],
                  "scan_status": [{"value": "NOT_SCANNED", "count": 74}],
                  "sensitive_type": [{"value": "PII", "count": 21}],
                  "paging": {
                    "data_element": {"cursor": null, "has_more": false},
                    "provider": {"cursor": "abc", "has_more": true}
                  }
                }
                """));

        // 'paging' sits alongside the real dimensions but is an object, so anything iterating naively
        // would render it as an empty pseudo-dimension in the rail.
        assertThat(filters.dimensions()).doesNotContainKey("paging");
        assertThat(filters.dimensions()).containsKeys(
                "data_element", "lifecycle", "severity", "provider", "scan_status", "sensitive_type");

        // Truncation is surfaced rather than hidden: there is no cursor parameter on the list endpoint,
        // so admitting the option list is incomplete is the only honest option.
        assertThat(filters.hasMore()).containsEntry("provider", true);
        assertThat(filters.hasMore()).containsEntry("data_element", false);
    }

    @Test
    @DisplayName("filter option labels are humanised, and the empty data_element value is named")
    void filterOptionLabels() {
        DspmFilters filters = mapper.toFilters(parse("""
                {
                  "data_element": [{"value": "ACCESS KEY AWS", "count": 7}, {"value": "", "count": 6}],
                  "severity": [{"value": "NONE", "count": 109}],
                  "provider": [{"value": "SELF_HOSTED_POSTGRES", "count": 1}],
                  "scan_status": [{"value": "NOT_SCANNED", "count": 74}],
                  "sensitive_type": [{"value": "SECRETS", "count": 13}]
                }
                """));

        // data_element values arrive human-readable already; prettifying would turn this into
        // "Access Key Aws".
        assertThat(filters.dimensions().get("data_element").get(0).label()).isEqualTo("ACCESS KEY AWS");
        // A blank option would otherwise be an unclickable empty row with a count next to it.
        assertThat(filters.dimensions().get("data_element").get(1).label()).isEqualTo("Unclassified");
        assertThat(filters.dimensions().get("severity").get(0).label()).isEqualTo("No findings");
        assertThat(filters.dimensions().get("provider").get(0).label()).isEqualTo("Self Hosted Postgres");
        assertThat(filters.dimensions().get("scan_status").get(0).label()).isEqualTo("Not scanned");
        assertThat(filters.dimensions().get("sensitive_type").get(0).label()).isEqualTo("IT & Security");
    }

    // ── detail: facet passthrough, truncation, entity aggregation ────────────────────────────────

    @Test
    @DisplayName("object truncation is detected and the entity breakdown is marked partial")
    void detailTruncation() {
        // Live on-prem datastore: object_total 328, objects capped at 50 by the vendor. Two objects here
        // stand in for the 50.
        DspmAssetDetail detail = mapper.toDetail(parse("""
                {
                  "id": "d094b17c-4209-560a-a990-badc21ea7c30",
                  "name": "xamops_local_db",
                  "subtype": "SELF_HOSTED_POSTGRES",
                  "category": "on_prem",
                  "resource_type": "DATASTORE",
                  "external_id": "xamops_local_db",
                  "risk": {"level": "HIGH", "policy_violation_count": null, "score": 6.0},
                  "sensitives": {"level":"HIGH_SENSITIVITY","object_count":328,"record_count":17268,
                                 "types":["PII","SECRETS"]},
                  "source": {"account":"6aa3fb283c84ccaebfe9e263","provider":"SELF_HOSTED_POSTGRES"},
                  "scan_status": {"last_scanned_at":null,"stage":"SCANNING_OUTPUT_PROCESSED","status":"CLASSIFIED"},
                  "facet": {"able_to_scan": true, "region": "self-hosted"},
                  "object_total": 328,
                  "objects": [
                    {"id":"o1","name":"raw_payload","object_type":"COLUMN","source":"DATASTORE_COLUMN",
                     "path":"p1","extension":null,"size_bytes":null,"is_sensitive":true,
                     "sensitive_count":3810,
                     "findings":[{"category":"SECRETS","count":3810,"entity_type":"SHA KEY"}]},
                    {"id":"o2","name":"export.csv","object_type":"FILE","source":"CLOUD_SCAN",
                     "path":"p2","extension":"csv","size_bytes":4192048,"is_sensitive":true,
                     "sensitive_count":7510,
                     "findings":[{"category":"SECRETS","count":7470,"entity_type":"SHA KEY"},
                                 {"category":"PII","count":40,"entity_type":"PERSON NAME"}]}
                  ]
                }
                """));

        assertThat(detail.objectTotal()).isEqualTo(328L);
        assertThat(detail.objects()).hasSize(2);
        assertThat(detail.objectsTruncated()).isTrue();
        // The breakdown can only be as complete as the capped object array, so it is a lower bound and
        // the UI has to say so rather than printing a confident total.
        assertThat(detail.breakdownPartial()).isTrue();

        // Aggregated across objects, highest first: SHA KEY 3810 + 7470 = 11280, then PERSON NAME 40.
        assertThat(detail.entityBreakdown()).hasSize(2);
        assertThat(detail.entityBreakdown().get(0).entityType()).isEqualTo("SHA KEY");
        assertThat(detail.entityBreakdown().get(0).count()).isEqualTo(11280L);
        assertThat(detail.entityBreakdown().get(0).categoryLabel()).isEqualTo("IT & Security");
        assertThat(detail.entityBreakdown().get(1).entityType()).isEqualTo("PERSON NAME");
        assertThat(detail.entityBreakdown().get(1).count()).isEqualTo(40L);
    }

    @Test
    @DisplayName("an untruncated asset is not marked partial")
    void detailNotTruncated() {
        DspmAssetDetail detail = mapper.toDetail(parse("""
                {"id":"a","name":"b","risk":{},"sensitives":{},"source":{},
                 "scan_status":{"status":"CLASSIFIED"},"facet":{},
                 "object_total":1,
                 "objects":[{"id":"o","name":"n","object_type":"FILE","source":"SAAS","path":"p",
                             "is_sensitive":false,"sensitive_count":0,"findings":[]}]}
                """));

        assertThat(detail.objectsTruncated()).isFalse();
        assertThat(detail.breakdownPartial()).isFalse();
        assertThat(detail.entityBreakdown()).isEmpty();
    }

    @Test
    @DisplayName("facet keys pass through untyped, preserving arrays and omitting nothing present")
    void facetPassthrough() {
        // The endpoint facet is the widest shape and includes device_users, an array the docs omit.
        DspmAssetDetail detail = mapper.toDetail(parse("""
                {"id":"a","name":"apple's MacBook Pro","risk":{},"sensitives":{},"source":{},
                 "scan_status":{"status":"CLASSIFIED"},"object_total":0,"objects":[],
                 "facet":{"os":"MAC","os_version":"15.0.1","cpu_usage":0.008009882577138189,
                          "used_disk_space_bytes":217376661504,
                          "device_users":["apple","shared"],"last_ping_time":1790059466}}
                """));

        assertThat(detail.facet()).containsEntry("os", "MAC");
        assertThat(detail.facet()).containsEntry("used_disk_space_bytes", 217376661504L);
        assertThat(detail.facet()).containsEntry("cpu_usage", 0.008009882577138189);
        assertThat(detail.facet().get("device_users")).isInstanceOf(java.util.List.class);
        assertThat(detail.facet()).containsEntry("last_ping_time", 1790059466L);
    }

    // ── pagination and summary ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pagination is passed through in the vendor's 1-based terms")
    void paginationPassthrough() {
        DspmPage<DspmAssetRow> page = mapper.toPage(parse("""
                {"data":[],"pagination":{"page":1,"page_size":100,"total":132,"total_pages":2}}
                """));

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(100);
        assertThat(page.total()).isEqualTo(132L);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("summary exposes per-tab metrics and the undocumented NOT_SCANNED status")
    void summaryMapping() {
        DspmSummary summary = mapper.toSummary(parse("""
                {
                  "cards": {
                    "by_status": {"CLASSIFIED":36,"FAILED":17,"NOT_SCANNED":74,"SCANNING":1,"STOPPED":4},
                    "total_assets": {"value":132,"trend":[{"date":"2026-09-24","value":132}]},
                    "critical_high": {"value":13,"trend":[]},
                    "total_sensitive_records": {"value":905309,"trend":[{"date":"2026-09-25","value":905309}]}
                  },
                  "tabs": {
                    "all": {"value":132,"trend":[],"critical_high":{"value":13},"total_sensitive_records":{"value":905309}},
                    "cloud": {"value":67,"trend":[],"critical_high":{"value":12},"total_sensitive_records":{"value":20000}},
                    "on_prem": {"value":1,"trend":[],"critical_high":{"value":1},"total_sensitive_records":{"value":17268}},
                    "saas": {"value":62,"trend":[],"critical_high":{"value":0},"total_sensitive_records":{"value":868041}},
                    "endpoint": {"value":2,"trend":[],"critical_high":{"value":0},"total_sensitive_records":{"value":6}}
                  }
                }
                """));

        assertThat(summary.totalAssets().value()).isEqualTo(132L);
        assertThat(summary.criticalHigh().value()).isEqualTo(13L);
        assertThat(summary.totalSensitiveRecords().value()).isEqualTo(905309L);

        // NOT_SCANNED is undocumented and is the largest bucket; scan coverage is the headline fact.
        assertThat(summary.byStatus()).containsEntry("NOT_SCANNED", 74L);
        assertThat(summary.byStatus()).containsEntry("CLASSIFIED", 36L);

        // Cloud + on-prem is what the vendor's own Cloud screen totals to (68).
        assertThat(summary.tabs().get("cloud").value() + summary.tabs().get("on_prem").value())
                .isEqualTo(68L);
        // Per-tab metrics come from the same call rather than one request per category.
        assertThat(summary.tabs().get("on_prem").sensitiveRecords()).isEqualTo(17268L);

        // An empty trend means "no history", not "a history of zeroes".
        assertThat(summary.criticalHigh().trend()).isEmpty();
        assertThat(summary.totalAssets().trend()).hasSize(1);
    }
}
