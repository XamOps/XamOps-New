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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Dashboard's environment cards, Top Risky rows and Active Sensitive Regions from the summary snapshot
 * and the collected asset rows (design 5.6.1, brief 10.1 and 11).
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

    /** The scan status the console counts as a scanned datastore (Scanned DataStores, brief 11). */
    private static final String SCANNED_STATUS = "CLASSIFIED";

    /**
     * The provider the console's region counts leave out: checked live in build 8, its Total and Scanned
     * DataStores equal the region's rows without EBS volumes.
     */
    private static final String EXCLUDED_PROVIDER = "EBS";

    private static final Pattern AWS_REGION = Pattern.compile("[a-z]{2}(-[a-z]+)+-\\d+");
    private static final Pattern GCP_REGION = Pattern.compile("[a-z]+(-[a-z]+)+\\d+");
    private static final Set<String> GCP_MULTI_REGIONS = Set.of("us", "eu", "asia", "europe");
    private static final Pattern AZURE_REGION = Pattern.compile("[a-z]+\\d*");
    /** A 24-hex ObjectId: its leading timestamp makes string order creation order. */
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{24}");

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
        return new DspmDashboard(environments, topRisky, regions(rows == null ? null : rows.get(DatastoreTab.CLOUD)));
    }

    /**
     * "Active Sensitive Regions" (brief 11, checked against the console in build 8) from the collected cloud
     * rows: one entry per {@code source.region}, whatever its scan status or records (the console lists
     * regions with nothing classified). EBS volumes are left out, as the console's counts leave them out.
     * Ordered as the console orders them: AWS, then GCP, then Azure regions (by the code's shape), each
     * cloud's regions by their first-created datastore (the smallest 24-hex {@code source_id}), then the
     * code. Rows without a region are skipped.
     */
    private static List<DspmDashboard.Region> regions(AssetRowSnapshot snapshot) {
        if (snapshot == null || snapshot.rows() == null) {
            return List.of();
        }
        Map<String, RegionSums> sums = new LinkedHashMap<>();
        for (DatastoreRowMapper.MappedRow mapped : snapshot.rows()) {
            DatastoreRow row = mapped == null ? null : mapped.row();
            String region = row == null || row.source() == null ? null : row.source().region();
            if (region == null || region.isBlank() || EXCLUDED_PROVIDER.equals(row.source().provider())) {
                continue;
            }
            sums.computeIfAbsent(region, k -> new RegionSums()).add(row);
        }
        List<Map.Entry<String, RegionSums>> ordered = new ArrayList<>(sums.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<String, RegionSums> e) -> cloudRank(e.getKey()))
                .thenComparing(e -> e.getValue().oldestSourceId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Map.Entry::getKey));
        List<DspmDashboard.Region> out = new ArrayList<>();
        ordered.forEach(e -> out.add(e.getValue().toRegion(e.getKey())));
        return out;
    }

    /**
     * 0 for an AWS code ({@code us-east-1}), 1 for GCP ({@code us-east1}, or a multi-region such as
     * {@code us}), 2 for Azure ({@code westeurope}), 3 for anything else.
     */
    private static int cloudRank(String code) {
        if (AWS_REGION.matcher(code).matches()) {
            return 0;
        }
        if (GCP_REGION.matcher(code).matches() || GCP_MULTI_REGIONS.contains(code)) {
            return 1;
        }
        return AZURE_REGION.matcher(code).matches() ? 2 : 3;
    }

    /** One region's running sums; label counts keep first-seen order for ties. */
    private static final class RegionSums {
        private long datastores;
        private long scanned;
        private String oldestSourceId;
        private final Map<String, Integer> labels = new LinkedHashMap<>();

        void add(DatastoreRow row) {
            datastores++;
            if (row.scan() != null && SCANNED_STATUS.equals(row.scan().status())) {
                scanned++;
            }
            String sourceId = row.sourceId();
            if (sourceId != null && OBJECT_ID.matcher(sourceId).matches()
                    && (oldestSourceId == null || sourceId.compareTo(oldestSourceId) < 0)) {
                oldestSourceId = sourceId;
            }
            if (row.sensitives() != null && row.sensitives().typeLabels() != null) {
                for (String label : row.sensitives().typeLabels()) {
                    if (label != null) {
                        labels.merge(label, 1, Integer::sum);
                    }
                }
            }
        }

        DspmDashboard.Region toRegion(String region) {
            List<String> ordered = new ArrayList<>(labels.keySet());
            ordered.sort(Comparator.comparingInt((String label) -> labels.get(label)).reversed());
            return new DspmDashboard.Region(region, datastores, scanned, ordered);
        }
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
