package com.xammer.cloud.service.matters;

import com.xammer.cloud.dto.matters.DspmSummary;
import com.xammer.cloud.dto.matters.datastores.DatastoreKpis;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.datastores.DatastoreSummarySnapshot;
import com.xammer.cloud.dto.matters.pages.DspmDashboard;
import com.xammer.cloud.dto.matters.scans.AssetRowSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 5.6.1 with brief 10.1 on synthetic rows: Cloud and On-Prem bars by provider, SaaS bars by drive
 * type (Gmail and Google Drive), Endpoint bars by OS.
 */
class DashboardMapperTest {

    private final DashboardMapper mapper = new DashboardMapper();

    private static DatastoreRowMapper.MappedRow row(String name, String category, String subtype, String provider,
                                                    long records) {
        DatastoreRow row = new DatastoreRow("id-" + name, null, name, category, subtype,
                DatastoreLabels.subtypeLabel(subtype), new DatastoreRow.Risk(null, null, null),
                new DatastoreRow.Sensitives(records, List.of()),
                new DatastoreRow.Source(provider, DatastoreLabels.providerLabel(provider), null, null),
                new DatastoreRow.Scan(null, null, null), null);
        return new DatastoreRowMapper.MappedRow(row, null);
    }

    /** A cloud row in an invented region with a scan status and category labels. */
    private static DatastoreRowMapper.MappedRow regionRow(String name, String region, String scanStatus, long records,
                                                          String... labels) {
        DatastoreRow row = new DatastoreRow("id-" + name, null, name, "cloud", "S3", "S3",
                new DatastoreRow.Risk(null, null, null), new DatastoreRow.Sensitives(records, List.of(labels)),
                new DatastoreRow.Source("S3", "S3", null, region), new DatastoreRow.Scan(scanStatus, null, null), null);
        return new DatastoreRowMapper.MappedRow(row, null);
    }

    private static AssetRowSnapshot snapshot(boolean truncated, DatastoreRowMapper.MappedRow... rows) {
        return new AssetRowSnapshot(List.of(rows), rows.length, truncated);
    }

    private static DatastoreSummarySnapshot summary(Map<String, long[]> tabs) {
        Map<String, DatastoreSummarySnapshot.TabSnapshot> snapshots = new LinkedHashMap<>();
        tabs.forEach((slug, values) -> snapshots.put(slug, new DatastoreSummarySnapshot.TabSnapshot(
                new DatastoreKpis.Metric(values[0], List.of()),
                new DatastoreKpis.Metric(values[1], List.of(new DspmSummary.Point("2026-01-01", values[1]))),
                0L)));
        return new DatastoreSummarySnapshot(Map.of(), new DatastoreKpis.Metric(0, List.of()),
                new DatastoreKpis.Metric(0, List.of()), 0L, snapshots);
    }

    private static Map<DatastoreTab, AssetRowSnapshot> rows() {
        Map<DatastoreTab, AssetRowSnapshot> rows = new LinkedHashMap<>();
        rows.put(DatastoreTab.CLOUD, snapshot(false,
                row("bucket-a", "cloud", "S3", "S3", 40),
                row("bucket-b", "cloud", "S3", "S3", 2),
                row("table-a", "cloud", "DYNAMODB", "DYNAMODB", 42),
                row("blob-a", "cloud", "AZURE_BLOB", "AZURE_BLOB", 0),
                row("unknown", "cloud", null, null, 9)));
        rows.put(DatastoreTab.SAAS, snapshot(false,
                row("mail-a", "saas", "GMAIL", "GOOGLE_WORKSPACE", 30),
                row("drive-a", "saas", "GOOGLE_WORKSPACE", "GOOGLE_WORKSPACE", 500),
                row("mail-b", "saas", "GMAIL", "GOOGLE_WORKSPACE", 5),
                row("slack-a", "saas", "SLACK", "SLACK", 0)));
        rows.put(DatastoreTab.ENDPOINT, snapshot(false,
                row("laptop-a", "endpoint", "MAC", "ENDPOINT_AGENT", 12),
                row("laptop-b", "endpoint", "WINDOWS", "ENDPOINT_AGENT", 0)));
        rows.put(DatastoreTab.ON_PREM, snapshot(true,
                row("pg-a", "on_prem", "SELF_HOSTED_POSTGRES", "SELF_HOSTED_POSTGRES", 70)));
        return rows;
    }

    @Test
    @DisplayName("environments in order with the summary's counts, trend, connected and filter")
    void environments() {
        Map<String, long[]> tabs = new LinkedHashMap<>();
        tabs.put("cloud", new long[]{5, 93});
        tabs.put("saas", new long[]{4, 535});
        tabs.put("endpoint", new long[]{2, 12});
        DspmDashboard dashboard = mapper.toDashboard(summary(tabs), rows(), Map.of());

        assertThat(dashboard.environments()).extracting(DspmDashboard.Environment::id)
                .containsExactly("cloud", "saas", "endpoint", "on_prem");
        DspmDashboard.Environment cloud = dashboard.environments().get(0);
        assertThat(cloud.datastores()).isEqualTo(5);
        assertThat(cloud.sensitiveRecords()).isEqualTo(93);
        assertThat(cloud.recordsTrend()).containsExactly(new DspmSummary.Point("2026-01-01", 93));
        assertThat(cloud.connected()).isTrue();
        assertThat(cloud.filter()).isEqualTo("provider");
        assertThat(dashboard.environments().get(1).filter()).isNull();
        assertThat(dashboard.environments().get(2).filter()).isEqualTo("os");
        DspmDashboard.Environment onPrem = dashboard.environments().get(3);
        assertThat(onPrem.datastores()).isZero();
        assertThat(onPrem.connected()).isFalse();
        assertThat(onPrem.recordsTrend()).isEmpty();
        assertThat(onPrem.filter()).isEqualTo("provider");
        assertThat(onPrem.partial()).isTrue();
        assertThat(cloud.partial()).isFalse();
    }

    @Test
    @DisplayName("bars: summed records, ordered by records then label; null keys skipped; SaaS by drive type")
    void subCategories() {
        DspmDashboard dashboard = mapper.toDashboard(summary(Map.of()), rows(), Map.of());

        assertThat(dashboard.environments().get(0).subCategories()).containsExactly(
                new DspmDashboard.SubCategory("DYNAMODB", "DynamoDB", 42, 1),
                new DspmDashboard.SubCategory("S3", "S3", 42, 2),
                new DspmDashboard.SubCategory("AZURE_BLOB", "Azure Blob", 0, 1));
        assertThat(dashboard.environments().get(1).subCategories()).containsExactly(
                new DspmDashboard.SubCategory("GOOGLE_WORKSPACE", "Google Drive", 500, 1),
                new DspmDashboard.SubCategory("GMAIL", "Gmail", 35, 2),
                new DspmDashboard.SubCategory("SLACK", "Slack", 0, 1));
        assertThat(dashboard.environments().get(2).subCategories()).containsExactly(
                new DspmDashboard.SubCategory("MAC", "Mac", 12, 1),
                new DspmDashboard.SubCategory("WINDOWS", "Windows", 0, 1));
        assertThat(dashboard.environments().get(3).subCategories()).containsExactly(
                new DspmDashboard.SubCategory("SELF_HOSTED_POSTGRES", "PostgreSQL (self-hosted)", 70, 1));
    }

    @Test
    @DisplayName("bars are capped at 20 and a missing snapshot gives none")
    void capAndMissing() {
        List<DatastoreRowMapper.MappedRow> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(row("r" + i, "cloud", "P" + i, "P" + i, i));
        }
        Map<DatastoreTab, AssetRowSnapshot> rows = Map.of(DatastoreTab.CLOUD, new AssetRowSnapshot(many, 25, false));
        DspmDashboard dashboard = mapper.toDashboard(null, rows, null);
        assertThat(dashboard.environments().get(0).subCategories()).hasSize(20)
                .first().extracting(DspmDashboard.SubCategory::key).isEqualTo("P24");
        assertThat(dashboard.environments().get(1).subCategories()).isEmpty();
        assertThat(dashboard.topRisky()).containsOnlyKeys("all", "cloud", "saas", "endpoint", "on_prem");
        assertThat(dashboard.topRisky().get("all")).isEmpty();
    }

    @Test
    @DisplayName("top rows: collector order, zero-record rows skipped, at most 5")
    void topRows() {
        AssetRowSnapshot snapshot = snapshot(false,
                row("a", "cloud", "S3", "S3", 0),
                row("b", "cloud", "S3", "S3", 4),
                row("c", "cloud", "S3", "S3", 1),
                row("d", "cloud", "S3", "S3", 0),
                row("e", "cloud", "S3", "S3", 9),
                row("f", "cloud", "S3", "S3", 3),
                row("g", "cloud", "S3", "S3", 2),
                row("h", "cloud", "S3", "S3", 8));
        assertThat(mapper.topRows(snapshot)).extracting(m -> m.row().name()).containsExactly("b", "c", "e", "f", "g");
        assertThat(mapper.topRows(snapshot(false, row("a", "cloud", "S3", "S3", 1)))).hasSize(1);
        assertThat(mapper.topRows(null)).isEmpty();
    }

    @Test
    @DisplayName("labelled top rows are placed under their tab slug")
    void topRisky() {
        DatastoreRow labelled = row("b", "cloud", "S3", "S3", 4).row();
        Map<DatastoreTab, List<DatastoreRow>> top = Map.of(DatastoreTab.ALL, List.of(labelled),
                DatastoreTab.CLOUD, List.of(labelled));
        DspmDashboard dashboard = mapper.toDashboard(summary(Map.of()), rows(), top);
        assertThat(dashboard.topRisky().get("all")).containsExactly(labelled);
        assertThat(dashboard.topRisky().get("cloud")).containsExactly(labelled);
        assertThat(dashboard.topRisky().get("saas")).isEmpty();
    }

    @Test
    @DisplayName("regions: cloud rows summed per source.region, scanned = CLASSIFIED, labels by frequency, sorted")
    void regions() {
        Map<DatastoreTab, AssetRowSnapshot> rows = new LinkedHashMap<>();
        rows.put(DatastoreTab.CLOUD, snapshot(false,
                regionRow("a", "eu-west-3", "CLASSIFIED", 30, "Financial", "Personal"),
                regionRow("b", "eu-west-3", "FAILED", 0),
                regionRow("c", "eu-west-3", "CLASSIFIED", 5, "Personal"),
                regionRow("d", "sa-east-1", "CLASSIFIED", 0),
                regionRow("e", "me-south-1", "NOT_SCANNED", 0),
                regionRow("f", "southamerica-east1", "STOPPED", 50, "Health"),
                regionRow("g", null, "CLASSIFIED", 90, "Personal"),
                regionRow("h", " ", "CLASSIFIED", 90)));
        rows.put(DatastoreTab.ON_PREM, snapshot(false, regionRow("pg", "eu-west-3", "CLASSIFIED", 70)));
        DspmDashboard dashboard = mapper.toDashboard(summary(Map.of()), rows, Map.of());
        assertThat(dashboard.regions()).containsExactly(
                new DspmDashboard.Region("southamerica-east1", 1, 0, 50, List.of("Health")),
                new DspmDashboard.Region("eu-west-3", 3, 2, 35, List.of("Personal", "Financial")),
                new DspmDashboard.Region("sa-east-1", 1, 1, 0, List.of()));
    }

    @Test
    @DisplayName("regions: none without a cloud snapshot")
    void regionsMissing() {
        assertThat(mapper.toDashboard(null, null, null).regions()).isEmpty();
        assertThat(mapper.toDashboard(null, Map.of(DatastoreTab.SAAS, snapshot(false,
                regionRow("x", "eu-west-3", "CLASSIFIED", 4))), null).regions()).isEmpty();
    }
}
