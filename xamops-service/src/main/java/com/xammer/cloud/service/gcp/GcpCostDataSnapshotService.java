package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.*;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Snapshots historical GCP cost data into cached_data with absolute month keys
 * so that past months are served from DB without hitting BigQuery each time.
 *
 * Key scheme (permanent, no TTL):
 *   GCP_MONTHLY_TOTAL::{projectId}::{YYYY-MM}       → Double total spend
 *   GCP_MONTHLY_BILLING_SVC::{projectId}::{YYYY-MM} → List<GcpCostDto> by service  (sentinel)
 *
 * Mirrors the pattern of AWS CostDataSnapshotService.
 */
@Service
@Slf4j
public class GcpCostDataSnapshotService {

    private static final YearMonth SNAPSHOT_START = YearMonth.of(2025, 1);

    private final CloudAccountRepository cloudAccountRepository;
    private final CachedDataRepository   cachedDataRepository;
    private final GcpClientProvider      gcpClientProvider;
    private final ObjectMapper           objectMapper;

    @Value("${gcp.billing.export-table-name:}")
    private String billingExportTableName;

    public GcpCostDataSnapshotService(CloudAccountRepository cloudAccountRepository,
                                      CachedDataRepository cachedDataRepository,
                                      GcpClientProvider gcpClientProvider,
                                      ObjectMapper objectMapper) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.cachedDataRepository   = cachedDataRepository;
        this.gcpClientProvider      = gcpClientProvider;
        this.objectMapper           = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Auto-run on startup — fills any missing historical snapshots in background
    // -------------------------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void autoSnapshotOnStartup() {
        Thread t = new Thread(() -> {
            log.info("[GcpSnapshot] Auto-snapshot triggered on startup — filling missing historical months...");
            try {
                int filled = snapshotAllHistoricalMonths();
                log.info("[GcpSnapshot] Auto-snapshot complete — {} month-project slots filled", filled);
            } catch (Exception e) {
                log.error("[GcpSnapshot] Auto-snapshot startup job failed: {}", e.getMessage());
            }
        }, "gcp-cost-snapshot-startup");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Public key helpers
    // -------------------------------------------------------------------------

    public static String keyMonthlyTotal(String projectId, YearMonth ym) {
        return "GCP_MONTHLY_TOTAL::" + projectId + "::" + ym;
    }

    public static String keyMonthlySvc(String projectId, YearMonth ym) {
        return "GCP_MONTHLY_BILLING_SVC::" + projectId + "::" + ym;
    }

    /** True when yearMonth is strictly before the current calendar month — data is final. */
    public static boolean isClosedMonth(YearMonth ym) {
        return ym.isBefore(YearMonth.now());
    }

    /** True when the sentinel key (GCP_MONTHLY_BILLING_SVC) is present for this project+month. */
    public boolean isSnapshotComplete(String projectId, YearMonth ym) {
        return cachedDataRepository.existsById(keyMonthlySvc(projectId, ym));
    }

    // -------------------------------------------------------------------------
    // Read helpers — used by GcpCostService to serve closed-month data
    // -------------------------------------------------------------------------

    public Optional<Double> readMonthlyTotal(String projectId, YearMonth ym) {
        return readFromDb(keyMonthlyTotal(projectId, ym), Double.class);
    }

    public Optional<List<GcpCostDto>> readBillingSvc(String projectId, YearMonth ym) {
        return readListFromDb(keyMonthlySvc(projectId, ym), new TypeReference<List<GcpCostDto>>() {});
    }

    // -------------------------------------------------------------------------
    // Snapshot single month for a single GCP project
    // -------------------------------------------------------------------------

    public void snapshotMonthForProject(String projectId, YearMonth ym, boolean overwrite) {
        if (projectId == null || projectId.isBlank()) return;

        // Skip if already snapshotted (sentinel key present) and not overwriting
        if (!overwrite && isSnapshotComplete(projectId, ym)) {
            log.debug("[GcpSnapshot] Skipping already-snapshotted {}/{}", projectId, ym);
            return;
        }

        log.info("[GcpSnapshot] Snapshotting {} for GCP project {}", ym, projectId);

        Optional<BigQuery> bqOpt = gcpClientProvider.getBigQueryClient(projectId);
        if (bqOpt.isEmpty()) {
            log.warn("[GcpSnapshot] No BigQuery client for project {} — skipping", projectId);
            return;
        }

        BigQuery bq = bqOpt.get();
        Optional<String> tableOpt = getBillingTableName(bq, projectId);
        if (tableOpt.isEmpty()) {
            log.warn("[GcpSnapshot] No billing export table found for project {} — skipping", projectId);
            return;
        }

        String table     = tableOpt.get();
        String start     = ym.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String end       = ym.atEndOfMonth().format(DateTimeFormatter.ISO_LOCAL_DATE);

        try {
            // 1. Monthly total
            double total = fetchMonthlyTotal(bq, table, projectId, start, end);
            persist(keyMonthlyTotal(projectId, ym), total);

            // 2. Billing by service (sentinel — saved last)
            List<GcpCostDto> svcBreakdown = fetchBillingSvc(bq, table, projectId, start, end);
            persist(keyMonthlySvc(projectId, ym), svcBreakdown);

            log.info("[GcpSnapshot] ✅ Done {}/{} — total=${}", projectId, ym, String.format("%.2f", total));
        } catch (Exception e) {
            log.error("[GcpSnapshot] ❌ Failed {}/{}: {}", projectId, ym, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Bulk snapshot — all GCP projects × all closed months
    // -------------------------------------------------------------------------

    public int snapshotAllHistoricalMonths() {
        List<CloudAccount> accounts = cloudAccountRepository.findAll().stream()
                .filter(a -> "GCP".equalsIgnoreCase(a.getProvider())
                        && a.getGcpProjectId() != null
                        && !a.getGcpProjectId().isBlank())
                .collect(Collectors.toList());

        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth ym = SNAPSHOT_START; !ym.isAfter(lastMonth); ym = ym.plusMonths(1)) {
            months.add(ym);
        }

        log.info("[GcpSnapshot] Bulk snapshot: {} projects × {} months", accounts.size(), months.size());

        int filled = 0;
        for (CloudAccount account : accounts) {
            String projectId = account.getGcpProjectId();
            for (YearMonth ym : months) {
                if (isSnapshotComplete(projectId, ym)) continue;
                try {
                    snapshotMonthForProject(projectId, ym, false);
                    filled++;
                } catch (Exception e) {
                    log.warn("[GcpSnapshot] Skipping {}/{} after error: {}", projectId, ym, e.getMessage());
                }
            }
        }
        log.info("[GcpSnapshot] Bulk snapshot complete — {} slots filled", filled);
        return filled;
    }

    /**
     * Snapshots the CURRENT month's final numbers.
     * Called by the end-of-month scheduler (11 PM last day of month).
     */
    public void snapshotCurrentMonthFinal() {
        YearMonth current = YearMonth.now();
        List<CloudAccount> accounts = cloudAccountRepository.findAll().stream()
                .filter(a -> "GCP".equalsIgnoreCase(a.getProvider())
                        && a.getGcpProjectId() != null
                        && !a.getGcpProjectId().isBlank())
                .collect(Collectors.toList());

        log.info("[GcpSnapshot] End-of-month final snapshot for {} — {} GCP projects", current, accounts.size());

        for (CloudAccount account : accounts) {
            try {
                snapshotMonthForProject(account.getGcpProjectId(), current, true);
            } catch (Exception e) {
                log.error("[GcpSnapshot] End-of-month failed for {}: {}", account.getGcpProjectId(), e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // BigQuery fetch helpers
    // -------------------------------------------------------------------------

    private double fetchMonthlyTotal(BigQuery bq, String table, String projectId,
                                     String start, String end) throws InterruptedException {
        String query = String.format(
                "SELECT SUM(cost) as total_cost FROM `%s` " +
                "WHERE project.id = '%s' " +
                "AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s'",
                table, projectId, start, end);

        TableResult result = bq.query(QueryJobConfiguration.newBuilder(query).build());
        FieldValue value = result.getValues().iterator().next().get("total_cost");
        return value.isNull() ? 0.0 : value.getDoubleValue();
    }

    private List<GcpCostDto> fetchBillingSvc(BigQuery bq, String table, String projectId,
                                              String start, String end) throws InterruptedException {
        String query = String.format(
                "SELECT service.description as name, SUM(cost) as amount " +
                "FROM `%s` " +
                "WHERE project.id = '%s' " +
                "AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' " +
                "GROUP BY 1 ORDER BY 2 DESC",
                table, projectId, start, end);

        TableResult result = bq.query(QueryJobConfiguration.newBuilder(query).build());
        List<GcpCostDto> list = new ArrayList<>();
        for (FieldValueList row : result.iterateAll()) {
            String name   = row.get("name").isNull()   ? "Unknown" : row.get("name").getStringValue();
            double amount = row.get("amount").isNull() ? 0.0       : row.get("amount").getDoubleValue();
            if (amount > 0.001) list.add(new GcpCostDto(name, amount, false));
        }
        return list;
    }

    private Optional<String> getBillingTableName(BigQuery bq, String projectId) {
        if (billingExportTableName != null && !billingExportTableName.isBlank()) {
            return Optional.of(billingExportTableName);
        }
        // Auto-discover billing export dataset
        try {
            for (Dataset dataset : bq.listDatasets(projectId).iterateAll()) {
                String id = dataset.getDatasetId().getDataset().toLowerCase();
                if (id.contains("billing") || id.contains("gcp_billing") || id.contains("cloud_billing")) {
                    for (com.google.cloud.bigquery.Table t : bq.listTables(dataset.getDatasetId()).iterateAll()) {
                        String tName = t.getTableId().getTable().toLowerCase();
                        if (tName.startsWith("gcp_billing") || tName.startsWith("cloud_billing_export")) {
                            return Optional.of(dataset.getDatasetId().getProject() + "."
                                    + dataset.getDatasetId().getDataset() + "."
                                    + t.getTableId().getTable());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[GcpSnapshot] Auto-discover billing table failed for {}: {}", projectId, e.getMessage());
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // DB persistence helpers
    // -------------------------------------------------------------------------

    private void persist(String key, Object value) {
        try {
            CachedData row = cachedDataRepository.findById(key).orElse(new CachedData());
            row.setCacheKey(key);
            row.setJsonData(objectMapper.writeValueAsString(value));
            row.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(row);
        } catch (Exception e) {
            log.warn("[GcpSnapshot] Failed to persist {}: {}", key, e.getMessage());
        }
    }

    private <T> Optional<T> readFromDb(String key, Class<T> type) {
        try {
            return cachedDataRepository.findById(key).map(row -> {
                try { return objectMapper.readValue(row.getJsonData(), type); }
                catch (Exception e) { log.warn("[GcpSnapshot] Deserialize failed {}: {}", key, e.getMessage()); return null; }
            });
        } catch (Exception e) {
            log.warn("[GcpSnapshot] DB read failed {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private <T> Optional<T> readListFromDb(String key, TypeReference<T> typeRef) {
        try {
            return cachedDataRepository.findById(key).map(row -> {
                try { return objectMapper.<T>readValue(row.getJsonData(), typeRef); }
                catch (Exception e) { log.warn("[GcpSnapshot] Deserialize failed {}: {}", key, e.getMessage()); return null; }
            });
        } catch (Exception e) {
            log.warn("[GcpSnapshot] DB read failed {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }
}
