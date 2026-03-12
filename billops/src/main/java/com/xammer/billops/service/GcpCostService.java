package com.xammer.billops.service;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableResult;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.GcpBillingDashboardDto;
import com.xammer.billops.dto.GcpResourceCostDto;
import com.xammer.billops.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut; // ADDED
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional; // ADDED

@Service
public class GcpCostService {

    private final GcpClientProvider gcpClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private static final Logger logger = LoggerFactory.getLogger(GcpCostService.class);

    @Value("${gcp.billing.export-table-name:}")
    private String billingExportTableName;

    public GcpCostService(GcpClientProvider gcpClientProvider, CloudAccountRepository cloudAccountRepository) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    // --- NEW METHOD (Cache-Only) ---
    @Transactional(readOnly = true)
    @Cacheable(value = "gcpDashboard", key = "#accountIdentifier")
    public Optional<GcpBillingDashboardDto> getCachedGcpBillingDashboardDto(String accountIdentifier) {
        logger.info("Attempting to retrieve CACHED GCP dashboard for: '{}'", accountIdentifier);
        // Spring AOP replaces this with a cache lookup.
        return Optional.empty();
    }

    // --- MODIFIED METHOD (Fetch & Cache-Write) ---
    @Transactional(readOnly = true)
    @CachePut(value = "gcpDashboard", key = "#accountIdentifier")
    public Optional<GcpBillingDashboardDto> getGcpBillingDashboardDtoAndCache(String accountIdentifier)
            throws IOException, InterruptedException {
        logger.info("🚀 [GCP DEBUG] Starting fetch for identifier: '{}'", accountIdentifier);

        Optional<CloudAccount> accountOpt = findAccount(accountIdentifier);

        if (accountOpt.isEmpty()) {
            logger.error("❌ [GCP DEBUG] Account NOT FOUND in database for identifier: {}", accountIdentifier);
            throw new IllegalArgumentException("Cloud account not found with identifier: " + accountIdentifier);
        }

        CloudAccount account = accountOpt.get();
        logger.info("✅ [GCP DEBUG] Found account in DB: ID={}, ProjectID={}, Provider={}",
                account.getId(), account.getGcpProjectId(), account.getProvider());

        if (account.getGcpServiceAccountKey() == null || account.getGcpServiceAccountKey().isBlank()) {
            logger.error("❌ [GCP DEBUG] Service Account Key is MISSING for account: {}", account.getAccountName());
            return Optional.empty();
        }

        try {
            logger.info("🔑 [GCP DEBUG] Creating BigQuery client for project: {}", account.getGcpProjectId());
            BigQuery bigquery = gcpClientProvider.createBigQueryClient(account.getGcpProjectId(),
                    account.getGcpServiceAccountKey());

            Optional<String> tableNameOpt = getBillingTableName(bigquery, account.getGcpProjectId());
            if (tableNameOpt.isEmpty()) {
                logger.error("❌ [GCP DEBUG] Table discovery FAILED for project: {}", account.getGcpProjectId());
                return Optional.empty();
            }

            String tableName = "`" + tableNameOpt.get() + "`";
            logger.info("🔍 [GCP DEBUG] Using Billing Table: {}", tableName);

            LocalDate today = LocalDate.now();
            logger.info("📅 [GCP DEBUG] Fetching data for date range up to: {}", today);

            List<GcpBillingDashboardDto.CostHistory> costHistory = getCostHistory(bigquery, tableName, today);
            logger.info("📊 [GCP DEBUG] Fetched {} history records", costHistory.size());

            List<GcpBillingDashboardDto.ServiceBreakdown> serviceBreakdown = getServiceBreakdown(bigquery, tableName,
                    today);
            logger.info("📊 [GCP DEBUG] Fetched {} service breakdown records", serviceBreakdown.size());

            BigDecimal totalCost = getMonthToDateSpend(bigquery, tableName, today);
            logger.info("💰 [GCP DEBUG] MTD Total Spend (Raw): {}", totalCost);

            BigDecimal lastMonthSpend = getLastMonthSpend(costHistory);

            GcpBillingDashboardDto dashboardDto = new GcpBillingDashboardDto(totalCost, costHistory, serviceBreakdown);
            dashboardDto.setCostLastMonth(lastMonthSpend);
            dashboardDto.setCostThisMonth(totalCost);

            logger.info("🏁 [GCP DEBUG] Successfully built DTO for {}", accountIdentifier);
            return Optional.of(dashboardDto);
        } catch (Exception e) {
            logger.error("💥 [GCP DEBUG] CRITICAL FAILURE during BigQuery fetch for {}: {}", accountIdentifier,
                    e.getMessage(), e);
            throw e;
        }
    }

    // --- NEW METHOD (Cache-Only) ---
    @Transactional(readOnly = true)
    @Cacheable(value = "gcpResourceCosts", key = "{#accountIdentifier, #serviceName}")
    public Optional<List<GcpResourceCostDto>> getCachedResourceCostsForService(String accountIdentifier,
            String serviceName) {
        logger.info("Attempting to retrieve CACHED GCP resource costs for: '{}', service: '{}'", accountIdentifier,
                serviceName);
        return Optional.empty(); // Spring AOP handles this
    }

    // --- MODIFIED METHOD (Fetch & Cache-Write) ---
    @Transactional(readOnly = true)
    @CachePut(value = "gcpResourceCosts", key = "{#accountIdentifier, #serviceName}")
    public Optional<List<GcpResourceCostDto>> getResourceCostsForServiceAndCache(String accountIdentifier,
            String serviceName) throws IOException, InterruptedException {
        // Renamed from getResourceCostsForService
        logger.info("Fetching resource costs for service '{}' in account '{}'", serviceName, accountIdentifier);
        Optional<CloudAccount> accountOpt = findAccount(accountIdentifier);

        if (accountOpt.isEmpty()) {
            throw new IllegalArgumentException("Cloud account not found with identifier: " + accountIdentifier);
        }

        CloudAccount account = accountOpt.get();
        BigQuery bigquery = gcpClientProvider.createBigQueryClient(account.getGcpProjectId(),
                account.getGcpServiceAccountKey());

        Optional<String> tableNameOpt = getBillingTableName(bigquery, account.getGcpProjectId());
        if (tableNameOpt.isEmpty()) {
            throw new IllegalStateException("Billing table not found for project: " + account.getGcpProjectId());
        }
        String tableName = "`" + tableNameOpt.get() + "`";
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.withDayOfMonth(1);

        String query = "SELECT resource.name as resource_name, SUM(cost) as total_cost " +
                "FROM " + tableName + " " +
                "WHERE service.description = @serviceName AND resource.name IS NOT NULL " +
                "AND DATE(usage_start_time) >= @startDate AND DATE(usage_start_time) <= @today " +
                "GROUP BY 1 HAVING total_cost > 0.01 ORDER BY total_cost DESC LIMIT 50";

        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("serviceName", QueryParameterValue.string(serviceName))
                .addNamedParameter("startDate", QueryParameterValue.string(startDate.format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .addNamedParameter("today", QueryParameterValue.string(today.format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .build();

        // --- ADDED LOGGING ---
        logger.debug("Executing BigQuery query for resource costs:\n{}", query);
        // --- END LOGGING ---

        return Optional.of(executeResourceCostQuery(bigquery, queryConfig)); // MODIFIED: Return as Optional
    }

    @CacheEvict(value = { "gcpDashboard", "gcpResourceCosts" }, allEntries = true)
    public void evictGcpCaches() {
        logger.info("Evicting all GCP cost-related caches.");
    }

    private List<GcpResourceCostDto> executeResourceCostQuery(BigQuery bq, QueryJobConfiguration queryConfig) throws InterruptedException {
        // --- ADDED LOGGING ---
        logger.debug("BigQuery job configured. Executing query...");
        // --- END LOGGING ---

        TableResult results = bq.query(queryConfig);

        // --- ADDED LOGGING ---
        // This tells us exactly how many rows BigQuery returned, before any processing.
        // If this is 0, the query is correct but returned no data (e.g., no costs for
        // that service).
        long totalRows = results.getTotalRows();
        logger.debug("Query executed. Total rows returned: {}", totalRows);
        // --- END LOGGING ---

        List<GcpResourceCostDto> resourceCosts = new ArrayList<>();
        for (FieldValueList row : results.iterateAll()) {
            String fullResourceName = row.get("resource_name").getStringValue();
            String shortResourceName = parseShortResourceName(fullResourceName);
            BigDecimal amount = row.get("total_cost").getNumericValue();
            resourceCosts.add(new GcpResourceCostDto(shortResourceName, amount));
        }

        // --- ADDED LOGGING ---
        // This confirms how many rows were processed into the final list.
        logger.debug("Finished processing {} resource cost rows.", resourceCosts.size());
        // --- END LOGGING ---

        return resourceCosts;
    }

    private String parseShortResourceName(String fullName) {
        if (fullName == null || fullName.isEmpty()) {
            return "N/A";
        }
        if (fullName.contains("/")) {
            return fullName.substring(fullName.lastIndexOf('/') + 1);
        }
        return fullName;
    }

    private Optional<CloudAccount> findAccount(String accountIdentifier) {
        if (isNumeric(accountIdentifier)) {
            logger.info("Identifier is numeric. Searching by ID.");
            return cloudAccountRepository.findById(Long.parseLong(accountIdentifier));
        } else {
            logger.info("Identifier is not numeric. Searching by gcpProjectId.");
            Optional<CloudAccount> byGcpProjectId = cloudAccountRepository.findByGcpProjectId(accountIdentifier);
            if (byGcpProjectId.isPresent()) {
                return byGcpProjectId;
            }
            logger.warn("Account not found by gcpProjectId: '{}'. Falling back to search by awsAccountId.",
                    accountIdentifier);
            return cloudAccountRepository.findByAwsAccountId(accountIdentifier).stream().findFirst();
        }
    }

    private Optional<String> getBillingTableName(BigQuery bigquery, String gcpProjectId) {
        if (billingExportTableName != null && !billingExportTableName.isBlank()) {
            logger.info("📋 [GCP DEBUG] Using configured table name: {}", billingExportTableName);
            return Optional.of(billingExportTableName);
        }
        try {
            logger.info("🔍 [GCP DEBUG] Starting discovery for datasets in project: {}", gcpProjectId);
            for (Dataset dataset : bigquery.listDatasets(gcpProjectId).iterateAll()) {
                String datasetId = dataset.getDatasetId().getDataset();
                logger.info("📂 [GCP DEBUG] Checking dataset: {}", datasetId);
                for (Table table : bigquery.listTables(dataset.getDatasetId()).iterateAll()) {
                    String tableId = table.getTableId().getTable();
                    logger.info("   📄 [GCP DEBUG] Checking table: {}", tableId);
                    if (tableId.startsWith("gcp_billing_export_v1") ||
                            tableId.startsWith("gcp_billing_export_resource_v1")) {
                        String fullName = String.format("%s.%s.%s",
                                table.getTableId().getProject(),
                                table.getTableId().getDataset(),
                                tableId);
                        logger.info("✅ [GCP DEBUG] Found billing table: {}", fullName);
                        return Optional.of(fullName);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("❌ [GCP DEBUG] Failed to discover BigQuery billing table for project {}: {}", gcpProjectId,
                    e.getMessage(), e);
        }

        logger.warn(
                "⚠️ [GCP DEBUG] Could not find a GCP billing export table for project {}.",
                gcpProjectId);
        return Optional.empty();
    }

    private boolean isNumeric(String str) {
        if (str == null) {
            return false;
        }
        try {
            Long.parseLong(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private List<GcpBillingDashboardDto.CostHistory> getCostHistory(BigQuery bq, String tableName, LocalDate today)
            throws InterruptedException {
        String startDate = today.minusMonths(6).withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String endDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE);

        String query = "WITH MonthlyCosts AS (" +
                "  SELECT FORMAT_DATE('%Y-%m', usage_start_time) as name, SUM(cost) as total_cost " +
                "  FROM " + tableName + " " +
                "  WHERE DATE(usage_start_time) >= @startDate AND DATE(usage_start_time) <= @endDate " +
                "  GROUP BY 1" +
                "), " +
                "MonthlyCostsWithLag AS (" +
                "  SELECT name, total_cost, LAG(total_cost, 1, 0) OVER (ORDER BY name) as prev_month_cost " +
                "  FROM MonthlyCosts" +
                ") " +
                "SELECT name, total_cost, (total_cost > prev_month_cost * 1.2 AND prev_month_cost > 10) as is_anomaly "
                +
                "FROM MonthlyCostsWithLag ORDER BY name";

        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("startDate", QueryParameterValue.string(startDate))
                .addNamedParameter("endDate", QueryParameterValue.string(endDate))
                .build();
        TableResult results = bq.query(queryConfig);
        List<GcpBillingDashboardDto.CostHistory> costHistory = new ArrayList<>();
        for (FieldValueList row : results.iterateAll()) {
            String date = row.get("name").getStringValue();
            // Use getDoubleValue() for FLOAT64 columns
            BigDecimal amount = BigDecimal.valueOf(row.get("total_cost").getDoubleValue());
            boolean isAnomaly = row.get("is_anomaly").getBooleanValue();
            costHistory.add(new GcpBillingDashboardDto.CostHistory(date, amount, isAnomaly));
        }
        return costHistory;
    }

    private List<GcpBillingDashboardDto.ServiceBreakdown> getServiceBreakdown(BigQuery bq, String tableName,
            LocalDate today) throws InterruptedException {
        LocalDate startDate = today.withDayOfMonth(1);

        String query = "SELECT service.description as service_name, SUM(cost) as total_cost " +
                "FROM " + tableName + " " +
                "WHERE DATE(usage_start_time) >= @startDate AND DATE(usage_start_time) <= @today " +
                "GROUP BY 1 HAVING total_cost > 0 ORDER BY total_cost DESC";

        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("startDate", QueryParameterValue.string(startDate.format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .addNamedParameter("today", QueryParameterValue.string(today.format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .build();
        TableResult results = bq.query(queryConfig);
        List<GcpBillingDashboardDto.ServiceBreakdown> serviceBreakdown = new ArrayList<>();
        for (FieldValueList row : results.iterateAll()) {
            String serviceName = row.get("service_name").getStringValue();
            // Use getDoubleValue() for FLOAT64 columns
            BigDecimal amount = BigDecimal.valueOf(row.get("total_cost").getDoubleValue());
            serviceBreakdown.add(new GcpBillingDashboardDto.ServiceBreakdown(serviceName, amount));
        }
        return serviceBreakdown;
    }

    private BigDecimal getMonthToDateSpend(BigQuery bigquery, String tableName, LocalDate today)
            throws InterruptedException {
        LocalDate startDate = today.withDayOfMonth(1);
        String query = "SELECT SUM(cost) as total_cost FROM " + tableName + " WHERE DATE(usage_start_time) >= @startDate AND DATE(usage_start_time) <= @today";

        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .addNamedParameter("startDate", QueryParameterValue.string(startDate.format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .addNamedParameter("today", QueryParameterValue.string(today.format(DateTimeFormatter.ISO_LOCAL_DATE)))
                .build();

        TableResult results = bigquery.query(queryConfig);
        FieldValueList row = results.getValues().iterator().next();
        if (row.get("total_cost").isNull()) {
            return BigDecimal.ZERO;
        }
        // Use getDoubleValue() for FLOAT64 columns
        return BigDecimal.valueOf(row.get("total_cost").getDoubleValue());
    }

    private BigDecimal getLastMonthSpend(List<GcpBillingDashboardDto.CostHistory> costHistory) {
        String lastMonthStr = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return costHistory.stream()
                .filter(c -> c.getDate().equals(lastMonthStr))
                .map(GcpBillingDashboardDto.CostHistory::getAmount)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }
}