package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.matters.DspmPage;
import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;
import com.xammer.cloud.dto.matters.datastores.DatastoreKpis;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.datastores.DatastoreSummarySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Design 5.6.1 to 5.6.3, on synthetic bodies shaped like the vendor's. */
class DatastoreRowMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final DatastoreRowMapper mapper = new DatastoreRowMapper();

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DatastoreRow row(String rowJson) {
        List<DatastoreRowMapper.MappedRow> rows = mapper.toMappedRows(parse("{\"data\":[" + rowJson + "]}"));
        assertThat(rows).hasSize(1);
        return rows.get(0).row();
    }

    private static String scanRow(String category, String scanStatus) {
        return """
                {"id":"r-1","source_id":"aaaaaaaaaaaaaaaaaaaaaaaa","name":"demo-store","category":"%s",
                 "subtype":"S3","risk":{"level":null,"score":null},
                 "sensitives":{"record_count":0,"types":[]},
                 "source":{"account":"bbbbbbbbbbbbbbbbbbbbbbbb","provider":"S3","region":"eu-west-1"},
                 "scan_status":%s}
                """.formatted(category, scanStatus);
    }

    // ── FR-TBL-7 scan time ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Classified cloud row with only status_at shows no time")
    void classifiedWithOnlyStatusAt() {
        DatastoreRow r = row(scanRow("cloud", "{\"status\":\"CLASSIFIED\",\"last_scanned_at\":null,\"status_at\":1700000100}"));
        assertThat(r.scan().displayAt()).isNull();
        assertThat(r.scan().statusLabel()).isEqualTo("Classified");
    }

    @Test
    @DisplayName("Classified SaaS row shows last_scanned_at, ignoring status_at")
    void classifiedSaasUsesLastScanned() {
        DatastoreRow r = row(scanRow("saas", "{\"status\":\"CLASSIFIED\",\"last_scanned_at\":1700000200,\"status_at\":1700000300}"));
        assertThat(r.scan().displayAt()).isEqualTo(1700000200L);
    }

    @Test
    @DisplayName("Stopped, Failed, Not Scanned and Scanning show status_at")
    void statusAtStatuses() {
        for (String status : List.of("STOPPED", "FAILED", "NOT_SCANNED", "SCANNING")) {
            DatastoreRow r = row(scanRow("cloud", "{\"status\":\"" + status
                    + "\",\"last_scanned_at\":1700000400,\"status_at\":1700000500}"));
            assertThat(r.scan().displayAt()).as(status).isEqualTo(1700000500L);
        }
        DatastoreRow noStatusAt = row(scanRow("cloud", "{\"status\":\"FAILED\",\"last_scanned_at\":1700000400}"));
        assertThat(noStatusAt.scan().displayAt()).isNull();
    }

    @Test
    @DisplayName("an unknown or missing status falls back from last_scanned_at to status_at")
    void unknownStatusFallback() {
        assertThat(row(scanRow("cloud", "{\"status\":\"SCHEDULED\",\"last_scanned_at\":1700000600,\"status_at\":1700000700}"))
                .scan().displayAt()).isEqualTo(1700000600L);
        assertThat(row(scanRow("cloud", "{\"status\":\"SOMETHING_NEW\",\"status_at\":1700000700}"))
                .scan().displayAt()).isEqualTo(1700000700L);
        assertThat(row(scanRow("cloud", "{\"status\":null,\"status_at\":1700000800}"))
                .scan().displayAt()).isEqualTo(1700000800L);
        assertThat(DatastoreRowMapper.scanDisplayAt(null, parse("{}"))).isNull();
    }

    // ── risk, sensitives, labels ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the score is kept raw and a null level stays null")
    void riskRawAndNullLevel() {
        DatastoreRow scored = row("""
                {"id":"r-2","source_id":"cccccccccccccccccccccccc","name":"n","category":"cloud","subtype":"S3",
                 "risk":{"level":"high","score":7.46},"scan_status":{"status":"CLASSIFIED"}}
                """);
        assertThat(scored.risk().score()).isEqualTo(7.46);
        assertThat(scored.risk().level()).isEqualTo("HIGH");
        assertThat(scored.risk().levelLabel()).isEqualTo("High");

        DatastoreRow unscored = row(scanRow("cloud", "{\"status\":\"NOT_SCANNED\"}"));
        assertThat(unscored.risk().score()).isNull();
        assertThat(unscored.risk().level()).isNull();
        assertThat(unscored.risk().levelLabel()).isNull();
    }

    @Test
    @DisplayName("record count as returned and type labels in API order; integration id kept server-side")
    void sensitivesAndIntegration() {
        List<DatastoreRowMapper.MappedRow> rows = mapper.toMappedRows(parse("""
                {"data":[{"id":"r-3","source_id":"dddddddddddddddddddddddd","name":"n","category":"cloud",
                  "subtype":"S3","sensitives":{"record_count":42,"types":["SECRETS","PII"]},
                  "source":{"account":"eeeeeeeeeeeeeeeeeeeeeeee","provider":"S3","region":null}},
                  {"id":"r-4","name":"m","category":"cloud"}]}
                """));
        DatastoreRow first = rows.get(0).row();
        assertThat(first.sensitives().recordCount()).isEqualTo(42);
        assertThat(first.sensitives().typeLabels()).containsExactly("IT & Security", "Personal");
        assertThat(rows.get(0).integrationId()).isEqualTo("eeeeeeeeeeeeeeeeeeeeeeee");
        assertThat(first.source().accountLabel()).isNull();
        assertThat(first.source().region()).isNull();
        assertThat(first.id()).isEqualTo("r-3");
        assertThat(first.sourceId()).isEqualTo("dddddddddddddddddddddddd");

        DatastoreRow bare = rows.get(1).row();
        assertThat(bare.sensitives().recordCount()).isZero();
        assertThat(bare.sensitives().typeLabels()).isEmpty();
        assertThat(rows.get(1).integrationId()).isNull();
    }

    @Test
    @DisplayName("subtype and provider labels of FR-TBL-3 and FR-FLT-6")
    void subtypeAndProviderLabels() {
        assertThat(DatastoreLabels.subtypeLabel("S3")).isEqualTo("S3");
        assertThat(DatastoreLabels.subtypeLabel("GMAIL")).isEqualTo("Gmail");
        assertThat(DatastoreLabels.subtypeLabel("GOOGLE_WORKSPACE")).isEqualTo("Google Drive");
        assertThat(DatastoreLabels.subtypeLabel("SELF_HOSTED_POSTGRES")).isEqualTo("PostgreSQL");
        assertThat(DatastoreLabels.subtypeLabel("AZURE_BLOB")).isEqualTo("Azure Blob");
        assertThat(DatastoreLabels.subtypeLabel("MAC")).isEqualTo("macOS");
        assertThat(DatastoreLabels.subtypeLabel("WINDOWS")).isEqualTo("Windows");
        assertThat(DatastoreLabels.subtypeLabel("BIGQUERY")).isEqualTo("BigQuery");
        assertThat(DatastoreLabels.subtypeLabel("SOME_NEW_TYPE")).isEqualTo("Some New Type");
        assertThat(DatastoreLabels.subtypeLabel(null)).isNull();

        assertThat(DatastoreLabels.providerLabel("EBS")).isEqualTo("EBS");
        assertThat(DatastoreLabels.providerLabel("AZURE_BLOB")).isEqualTo("Azure Blob");
        assertThat(DatastoreLabels.providerLabel("DYNAMODB")).isEqualTo("DynamoDB");
        assertThat(DatastoreLabels.providerLabel("GCS")).isEqualTo("GCS");
        assertThat(DatastoreLabels.providerLabel("RDS")).isEqualTo("RDS");
        assertThat(DatastoreLabels.providerLabel("GOOGLE_WORKSPACE")).isEqualTo("Google Workspace");
        assertThat(DatastoreLabels.providerLabel("ENDPOINT_AGENT")).isEqualTo("Endpoint Agent");
        assertThat(DatastoreLabels.providerLabel("SELF_HOSTED_POSTGRES")).isEqualTo("PostgreSQL (self-hosted)");

        DatastoreRow r = row("""
                {"id":"r-5","name":"Person","category":"saas","subtype":"GOOGLE_WORKSPACE",
                 "source":{"provider":"GOOGLE_WORKSPACE"}}
                """);
        assertThat(r.subtypeLabel()).isEqualTo("Google Drive");
        assertThat(r.source().providerLabel()).isEqualTo("Google Workspace");
        assertThat(r.agent()).isNull();
    }

    @Test
    @DisplayName("endpoint rows carry the agent fields")
    void endpointAgent() {
        DatastoreRow r = row("""
                {"id":"r-6","name":"laptop-one","category":"endpoint","subtype":"MAC",
                 "serial_number":"SN-SYNTH-01","agent_status":"OFFLINE","agent_version":"9.9.1",
                 "risk":{"level":"MEDIUM"},"source":{"provider":"ENDPOINT_AGENT"},
                 "scan_status":{"status":"CLASSIFIED","last_scanned_at":1700000900}}
                """);
        assertThat(r.agent()).isEqualTo(new DatastoreRow.Agent("SN-SYNTH-01", "OFFLINE", "Offline", "9.9.1"));
        assertThat(r.subtypeLabel()).isEqualTo("macOS");
        assertThat(r.risk().score()).isNull();
        assertThat(r.risk().levelLabel()).isEqualTo("Medium");
    }

    // ── page ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("paging uses the request values; only pagination.total comes from the vendor")
    void pagingFromRequest() {
        JsonNode body = parse("{\"data\":[],\"pagination\":{\"page\":9,\"page_size\":50,\"total\":23,\"total_pages\":1}}");
        DspmPage<String> page = mapper.toPage(List.of("a", "b"), body, 2, 10);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(23);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.data()).containsExactly("a", "b");

        DspmPage<String> empty = mapper.toPage(List.of(), parse("{}"), 1, 10);
        assertThat(empty.total()).isZero();
        assertThat(empty.totalPages()).isZero();
    }

    // ── facets ───────────────────────────────────────────────────────────────────────────────────

    private static final String FACETS = """
            {
              "integration_id": [{"value": "ffffffffffffffffffffffff", "count": 4}],
              "provider": [{"value": "S3", "count": 3}, {"value": "SELF_HOSTED_POSTGRES", "count": 1}],
              "region": [{"value": "eu-west-1", "count": 2}, {"value": "", "count": 1}],
              "scan_status": [{"value": "NOT_SCANNED", "count": 2}, {"value": "CLASSIFIED", "count": 5},
                              {"value": "FAILED", "count": 1}, {"value": "STOPPED", "count": 2}],
              "severity": [{"value": "NONE", "count": 6}, {"value": "CRITICAL", "count": 1}],
              "sensitive_type": [{"value": "PII", "count": 3}],
              "data_element": [{"value": "", "count": 2}, {"value": "PERSON NAME", "count": 1}],
              "lifecycle": [{"value": "ACTIVE", "count": 9}],
              "resource_type": [{"value": "DATASTORE", "count": 9}],
              "os": [{"value": "MAC", "count": 3}],
              "agent_status": [{"value": "ONLINE", "count": 1}],
              "device_type": [{"value": "laptop", "count": 4}],
              "hostname": [{"value": "host-one"}],
              "selected": [{"value": "yes", "count": 2}],
              "not_an_array": {"value": "x"},
              "paging": {"provider": {"cursor": null, "has_more": true}, "region": {"has_more": false}}
            }
            """;

    private static DatastoreFilterOptions.Dimension dim(DatastoreFilterOptions options, String key) {
        return options.dimensions().stream().filter(d -> d.key().equals(key)).findFirst().orElse(null);
    }

    @Test
    @DisplayName("facets: paging lifted, lifecycle and resource_type dropped, region kept, vendor order")
    void facetsCloud() {
        DatastoreFilterOptions options = mapper.toFacets(parse(FACETS), DatastoreTab.CLOUD);
        assertThat(options.dimensions()).extracting(DatastoreFilterOptions.Dimension::key).containsExactly(
                "integration_id", "provider", "region", "scan_status", "severity", "sensitive_type", "data_element");
        assertThat(dim(options, "provider").hasMore()).isTrue();
        assertThat(dim(options, "region").hasMore()).isFalse();
        assertThat(dim(options, "severity").hasMore()).isFalse();
    }

    @Test
    @DisplayName("facet option labels per dimension; counts null when absent")
    void facetLabels() {
        DatastoreFilterOptions cloud = mapper.toFacets(parse(FACETS), DatastoreTab.CLOUD);
        assertThat(dim(cloud, "integration_id").options().get(0).label()).isEqualTo("ffffffffffffffffffffffff");
        assertThat(dim(cloud, "provider").options()).extracting(DatastoreFilterOptions.Option::label)
                .containsExactly("S3", "PostgreSQL (self-hosted)");
        assertThat(dim(cloud, "region").options()).extracting(DatastoreFilterOptions.Option::label)
                .containsExactly("eu-west-1", "-");
        assertThat(dim(cloud, "scan_status").options().get(0).label()).isEqualTo("Not Scanned");
        assertThat(dim(cloud, "severity").options()).extracting(DatastoreFilterOptions.Option::label)
                .containsExactly("No findings", "Critical");
        assertThat(dim(cloud, "sensitive_type").options().get(0).label()).isEqualTo("Personal");
        assertThat(dim(cloud, "data_element").options()).extracting(DatastoreFilterOptions.Option::label)
                .containsExactly("Unclassified", "PERSON NAME");
        assertThat(dim(cloud, "data_element").options().get(0).value()).isEmpty();
        assertThat(dim(cloud, "provider").options().get(0).count()).isEqualTo(3L);

        DatastoreFilterOptions endpoint = mapper.toFacets(parse(FACETS), DatastoreTab.ENDPOINT);
        assertThat(dim(endpoint, "os").options().get(0).label()).isEqualTo("macOS");
        assertThat(dim(endpoint, "agent_status").options().get(0).label()).isEqualTo("Online");
        assertThat(dim(endpoint, "device_type").options().get(0).label()).isEqualTo("Laptop");
        assertThat(dim(endpoint, "hostname").options().get(0).count()).isNull();
        assertThat(dim(endpoint, "hostname").options().get(0).label()).isEqualTo("host-one");
        assertThat(dim(endpoint, "selected")).isNull();

        DatastoreFilterOptions saas = mapper.toFacets(parse(FACETS), DatastoreTab.SAAS);
        assertThat(dim(saas, "selected").options().get(0).label()).isEqualTo("yes");
        assertThat(dim(saas, "os")).isNull();
    }

    // ── summary and KPIs ─────────────────────────────────────────────────────────────────────────

    private static final String SUMMARY = """
            {"cards": {
               "by_status": {"CLASSIFIED": 3, "FAILED": 2, "NOT_SCANNED": 7, "SCANNING": 1, "STOPPED": 1},
               "critical_high": {"value": 4, "trend": []},
               "total_assets": {"value": 14, "trend": [{"date": "2030-01-01", "value": 13}, {"date": "2030-01-02", "value": 14}]},
               "total_sensitive_records": {"value": 900, "trend": [{"date": "2030-01-02", "value": 900}]}
             },
             "tabs": {
               "all": {"value": 14, "trend": [], "total_sensitive_records": {"value": 900}, "critical_high": {"value": 4}},
               "cloud": {"value": 8, "trend": [{"date": "2030-01-02", "value": 8}],
                         "total_sensitive_records": {"value": 300, "trend": [{"date": "2030-01-01", "value": 250},
                                                                             {"date": "2030-01-02", "value": 300}]},
                         "critical_high": {"value": 2, "trend": []}},
               "saas": {"value": 3},
               "endpoint": {"value": 2, "total_sensitive_records": {"value": 9}}
             }}
            """;

    @Test
    @DisplayName("All tab KPIs come from cards; SCHEDULED absent gives 0")
    void kpisAll() {
        DatastoreSummarySnapshot summary = mapper.toSummary(parse(SUMMARY));
        DatastoreKpis kpis = mapper.toKpis(DatastoreTab.ALL, summary, null);
        assertThat(kpis.tab()).isEqualTo("all");
        assertThat(kpis.classified()).isEqualTo(3);
        assertThat(kpis.failed()).isEqualTo(2);
        assertThat(kpis.stopped()).isEqualTo(1);
        assertThat(kpis.scheduled()).isZero();
        assertThat(kpis.criticalHigh()).isEqualTo(4);
        assertThat(kpis.assetCount().value()).isEqualTo(14);
        assertThat(kpis.assetCount().trend()).hasSize(2);
        assertThat(kpis.totalSensitiveRecords().value()).isEqualTo(900);
        assertThat(kpis.tabCounts()).containsExactly(
                java.util.Map.entry("all", 14L), java.util.Map.entry("cloud", 8L), java.util.Map.entry("saas", 3L),
                java.util.Map.entry("endpoint", 2L), java.util.Map.entry("on_prem", 0L));
        assertThat(summary.byStatus()).containsEntry("NOT_SCANNED", 7L);
    }

    @Test
    @DisplayName("Cloud tab KPIs take status counts from facets and per-tab sensitive-record trends")
    void kpisCloud() {
        DatastoreSummarySnapshot summary = mapper.toSummary(parse(SUMMARY));
        DatastoreFilterOptions facets = mapper.toFacets(parse(FACETS), DatastoreTab.CLOUD);
        DatastoreKpis kpis = mapper.toKpis(DatastoreTab.CLOUD, summary, facets);
        assertThat(kpis.classified()).isEqualTo(5);
        assertThat(kpis.failed()).isEqualTo(1);
        assertThat(kpis.stopped()).isEqualTo(2);
        assertThat(kpis.scheduled()).isZero();
        assertThat(kpis.criticalHigh()).isEqualTo(2);
        assertThat(kpis.assetCount().value()).isEqualTo(8);
        assertThat(kpis.assetCount().trend()).hasSize(1);
        assertThat(kpis.totalSensitiveRecords().value()).isEqualTo(300);
        assertThat(kpis.totalSensitiveRecords().trend()).extracting(p -> p.value()).containsExactly(250L, 300L);

        DatastoreKpis endpoint = mapper.toKpis(DatastoreTab.ENDPOINT, summary,
                mapper.toFacets(parse("{}"), DatastoreTab.ENDPOINT));
        assertThat(endpoint.totalSensitiveRecords().value()).isEqualTo(9);
        assertThat(endpoint.totalSensitiveRecords().trend()).isEmpty();
        assertThat(endpoint.classified()).isZero();
        assertThat(endpoint.criticalHigh()).isZero();

        DatastoreKpis onPrem = mapper.toKpis(DatastoreTab.ON_PREM, summary, null);
        assertThat(onPrem.assetCount().value()).isZero();
        assertThat(onPrem.assetCount().trend()).isEmpty();
    }

    @Test
    void emptySummaryIsAllZero() {
        DatastoreSummarySnapshot summary = mapper.toSummary(parse("{}"));
        assertThat(summary.totalAssets().value()).isZero();
        assertThat(summary.totalAssets().trend()).isEmpty();
        assertThat(summary.byStatus()).isEmpty();
        assertThat(summary.tabs()).containsOnlyKeys("all", "cloud", "saas", "endpoint", "on_prem");
    }
}
