package com.xammer.cloud.service.matters;

import com.xammer.cloud.dto.matters.DspmSummary;
import com.xammer.cloud.dto.matters.datastores.DatastoreRow;
import com.xammer.cloud.dto.matters.datastores.DatastoreSummarySnapshot;
import com.xammer.cloud.dto.matters.pages.DspmDashboard;
import com.xammer.cloud.dto.matters.scans.AssetRowSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Dashboard's environment cards and Top Risky rows from the summary snapshot and the collected asset
 * rows (design 5.6.1, brief 10.1).
 *
 * <p>Bars are keyed by {@code source.provider} on Cloud and On-Prem and by {@code subtype} on Endpoint (the
 * OS) and on SaaS: the console's SaaS card lists drive types (Google Drive, Gmail), brief 10.1, so the
 * design's open point 2 applies and SaaS bars open no filter.
 */
@Component
public class DashboardMapper {

    /** Top Risky rows per tab (A4). */
    static final int TOP_ROWS = 5;

    /** Bars per environment card. */
    static final int MAX_SUB_CATEGORIES = 20;

    private static final List<DatastoreTab> ENVIRONMENTS =
            List.of(DatastoreTab.CLOUD, DatastoreTab.SAAS, DatastoreTab.ENDPOINT, DatastoreTab.ON_PREM);

    /** The console's endpoint bar names (brief 10.1: "Mac"). Other codes use the subtype label. */
    private static final Map<String, String> OS_BAR_LABELS = Map.of("MAC", "Mac", "WINDOWS", "Windows");

    /** The tab's rows in collector order ({@code -severity}) with records (A6), the first 5 (A4). */
    public List<DatastoreRowMapper.MappedRow> topRows(AssetRowSnapshot snapshot) {
        List<DatastoreRowMapper.MappedRow> top = new ArrayList<>();
        if (snapshot == null || snapshot.rows() == null) {
            return top;
        }
        for (DatastoreRowMapper.MappedRow mapped : snapshot.rows()) {
            if (top.size() >= TOP_ROWS) {
                break;
            }
            if (mapped != null && mapped.row() != null && recordCount(mapped.row()) > 0) {
                top.add(mapped);
            }
        }
        return top;
    }

    /**
     * @param rows        the collector's snapshot per environment tab
     * @param labelledTop the labelled Top Risky rows per tab (all and the four environments)
     */
    public DspmDashboard toDashboard(DatastoreSummarySnapshot summary, Map<DatastoreTab, AssetRowSnapshot> rows,
                                     Map<DatastoreTab, List<DatastoreRow>> labelledTop) {
        List<DspmDashboard.Environment> environments = new ArrayList<>();
        for (DatastoreTab tab : ENVIRONMENTS) {
            DatastoreSummarySnapshot.TabSnapshot snapshot =
                    summary == null || summary.tabs() == null ? null : summary.tabs().get(tab.slug());
            long datastores = snapshot == null || snapshot.assets() == null ? 0L : snapshot.assets().value();
            long records = snapshot == null || snapshot.sensitiveRecords() == null
                    ? 0L : snapshot.sensitiveRecords().value();
            List<DspmSummary.Point> trend = snapshot == null || snapshot.sensitiveRecords() == null
                    || snapshot.sensitiveRecords().trend() == null
                    ? List.of() : snapshot.sensitiveRecords().trend();
            AssetRowSnapshot tabRows = rows == null ? null : rows.get(tab);
            environments.add(new DspmDashboard.Environment(tab.slug(), datastores, records, trend, datastores > 0,
                    filterOf(tab), subCategories(tab, tabRows), tabRows != null && tabRows.truncated()));
        }

        Map<String, List<DatastoreRow>> topRisky = new LinkedHashMap<>();
        for (DatastoreTab tab : DatastoreTab.values()) {
            List<DatastoreRow> top = labelledTop == null ? null : labelledTop.get(tab);
            topRisky.put(tab.slug(), top == null ? List.of() : top);
        }
        return new DspmDashboard(environments, topRisky);
    }

    private static String filterOf(DatastoreTab tab) {
        return switch (tab) {
            case ENDPOINT -> "os";
            case SAAS -> null;
            default -> "provider";
        };
    }

    private static List<DspmDashboard.SubCategory> subCategories(DatastoreTab tab, AssetRowSnapshot snapshot) {
        if (snapshot == null || snapshot.rows() == null) {
            return List.of();
        }
        boolean bySubtype = tab == DatastoreTab.ENDPOINT || tab == DatastoreTab.SAAS;
        Map<String, long[]> sums = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (DatastoreRowMapper.MappedRow mapped : snapshot.rows()) {
            DatastoreRow row = mapped == null ? null : mapped.row();
            if (row == null) {
                continue;
            }
            String key = bySubtype ? row.subtype() : (row.source() == null ? null : row.source().provider());
            if (key == null) {
                continue;
            }
            labels.computeIfAbsent(key, k -> label(tab, row, k));
            long[] sum = sums.computeIfAbsent(key, k -> new long[2]);
            sum[0] += recordCount(row);
            sum[1] += 1;
        }
        List<DspmDashboard.SubCategory> out = new ArrayList<>();
        sums.forEach((key, sum) -> out.add(new DspmDashboard.SubCategory(key, labels.get(key), sum[0], sum[1])));
        out.sort(Comparator.comparingLong(DspmDashboard.SubCategory::sensitiveRecords).reversed()
                .thenComparing(DspmDashboard.SubCategory::label, String.CASE_INSENSITIVE_ORDER));
        return out.size() > MAX_SUB_CATEGORIES ? new ArrayList<>(out.subList(0, MAX_SUB_CATEGORIES)) : out;
    }

    private static String label(DatastoreTab tab, DatastoreRow row, String key) {
        String label;
        if (tab == DatastoreTab.ENDPOINT) {
            label = OS_BAR_LABELS.getOrDefault(key, row.subtypeLabel());
        } else if (tab == DatastoreTab.SAAS) {
            label = row.subtypeLabel();
        } else {
            label = row.source().providerLabel();
        }
        return label == null ? key : label;
    }

    private static long recordCount(DatastoreRow row) {
        return row.sensitives() == null ? 0L : row.sensitives().recordCount();
    }
}
