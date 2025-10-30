package com.xammer.billops.service;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryJobConfiguration;
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
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    @Transactional(readOnly = true)
    @Cacheable(value = "gcpDashboard", key = "#accountIdentifier")
    public GcpBillingDashboardDto getGcpBillingDashboardDto(String accountIdentifier) throws IOException, InterruptedException {
        logger.info("Attempting to find GCP account with identifier: '{}'", accountIdentifier);

        Optional<CloudAccount> accountOpt = findAccount(accountIdentifier);

        if (accountOpt.isEmpty()) {
            logger.error("GCP Cloud account could not be found with identifier: {}", accountIdentifier);
            throw new IllegalArgumentException("Cloud account not found with identifier: " + accountIdentifier);
        }

        CloudAccount account = accountOpt.get();
        logger.info("Successfully found account with ID: {}", account.getId());

        BigQuery bigquery = gcpClientProvider.createBigQueryClient(account.getGcpProjectId(), new String(account.getGcpServiceAccountKey()));

        Optional<String> tableNameOpt = getBillingTableName(bigquery, account.getGcpProjectId());
        if (tableNameOpt.isEmpty()) {
            throw new IllegalStateException("Could not find a valid BigQuery billing export table for project: " + account.getGcpProjectId());
        }
        String tableName = "`" + tableNameOpt.get() + "`";
        LocalDate today = LocalDate.now();

        List<GcpBillingDashboardDto.CostHistory> costHistory = getCostHistory(bigquery, tableName, today);
        List<GcpBillingDashboardDto.ServiceBreakdown> serviceBreakdown = getServiceBreakdown(bigquery, tableName, today);

        BigDecimal totalCost = getMonthToDateSpend(bigquery, tableName, today);
        BigDecimal lastMonthSpend = getLastMonthSpend(costHistory);

        GcpBillingDashboardDto dashboardDto = new GcpBillingDashboardDto(totalCost, costHistory, serviceBreakdown);
        dashboardDto.setCostLastMonth(lastMonthSpend);
        dashboardDto.setCostThisMonth(totalCost);

        return dashboardDto;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "gcpResourceCosts", key = "{#accountIdentifier, #serviceName}")
    public List<GcpResourceCostDto> getResourceCostsForService(String accountIdentifier, String serviceName) throws IOException, InterruptedException {
        logger.info("Fetching resource costs for service '{}' in account '{}'", serviceName, accountIdentifier);
        Optional<CloudAccount> accountOpt = findAccount(accountIdentifier);

        if (accountOpt.isEmpty()) {
            throw new IllegalArgumentException("Cloud account not found with identifier: " + accountIdentifier);
        }

        CloudAccount account = accountOpt.get();
        BigQuery bigquery = gcpClientProvider.createBigQueryClient(account.getGcpProjectId(), new String(account.getGcpServiceAccountKey()));

        Optional<String> tableNameOpt = getBillingTableName(bigquery, account.getGcpProjectId());
        if (tableNameOpt.isEmpty()) {
            throw new IllegalStateException("Billing table not found for project: " + account.getGcpProjectId());
        }
        String tableName = "`" + tableNameOpt.get() + "`";
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.withDayOfMonth(1);

        String query = String.format(
                "SELECT resource.name as resource_name, SUM(cost) as total_cost " +
                "FROM %s " +
                "WHERE service.description = '%s' AND resource.name IS NOT NULL " +
                "AND DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' " +
                "GROUP BY resource_name HAVING total_cost > 0.01 ORDER BY total_cost DESC LIMIT 50",
                tableName,
                serviceName,
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE));

        return executeResourceCostQuery(bigquery, query);
    }

    @CacheEvict(value = {"gcpDashboard", "gcpResourceCosts"}, allEntries = true)
    public void evictGcpCaches() {
        logger.info("Evicting all GCP cost-related caches.");
    }

    private List<GcpResourceCostDto> executeResourceCostQuery(BigQuery bq, String query) throws InterruptedException {
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();
        TableResult results = bq.query(queryConfig);
        List<GcpResourceCostDto> resourceCosts = new ArrayList<>();
        for (FieldValueList row : results.iterateAll()) {
            String fullResourceName = row.get("resource_name").getStringValue();
            String shortResourceName = parseShortResourceName(fullResourceName);
            BigDecimal amount = row.get("total_cost").getNumericValue();
            resourceCosts.add(new GcpResourceCostDto(shortResourceName, amount));
        }
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
            logger.warn("Account not found by gcpProjectId: '{}'. Falling back to search by awsAccountId.", accountIdentifier);
            return cloudAccountRepository.findByAwsAccountId(accountIdentifier).stream().findFirst();
        }
    }

    private Optional<String> getBillingTableName(BigQuery bigquery, String gcpProjectId) {
        if (billingExportTableName != null && !billingExportTableName.isBlank()) {
            return Optional.of(billingExportTableName);
        }
        try {
            for (Dataset dataset : bigquery.listDatasets(gcpProjectId).iterateAll()) {
                for (Table table : bigquery.listTables(dataset.getDatasetId()).iterateAll()) {
                    if (table.getTableId().getTable().startsWith("gcp_billing_export_v1")) {
                        String fullName = String.format("%s.%s.%s",
                                table.getTableId().getProject(),
                                table.getTableId().getDataset(),
                                table.getTableId().getTable());
                        logger.info("Dynamically found billing table: {}", fullName);
                        return Optional.of(fullName);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to discover BigQuery billing table for project {}: {}", gcpProjectId, e.getMessage());
        }

        logger.warn("Could not find a GCP billing export table for project {}. Please configure gcp.billing.export-table-name.", gcpProjectId);
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

    private List<GcpBillingDashboardDto.CostHistory> getCostHistory(BigQuery bq, String tableName, LocalDate today) throws InterruptedException {
        String startDate = today.minusMonths(6).withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String endDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE);

        String query = String.format(
                "WITH MonthlyCosts AS (" +
                "  SELECT FORMAT_DATE('%%%%Y-%%%%m', usage_start_time) as name, SUM(cost) as total_cost " +
                "  FROM %s " +
                "  WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' " +
                "  GROUP BY 1" +
                "), " +
                "MonthlyCostsWithLag AS (" +
                "  SELECT name, total_cost, LAG(total_cost, 1, 0) OVER (ORDER BY name) as prev_month_cost " +
                "  FROM MonthlyCosts" +
                ") " +
                "SELECT name, total_cost, (total_cost > prev_month_cost * 1.2 AND prev_month_cost > 10) as is_anomaly " +
                "FROM MonthlyCostsWithLag ORDER BY name",
                tableName, startDate, endDate);

        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();
        TableResult results = bq.query(queryConfig);
        List<GcpBillingDashboardDto.CostHistory> costHistory = new ArrayList<>();
        for (FieldValueList row : results.iterateAll()) {
            String date = row.get("name").getStringValue();
            BigDecimal amount = row.get("total_cost").getNumericValue();
            boolean isAnomaly = row.get("is_anomaly").getBooleanValue();
            costHistory.add(new GcpBillingDashboardDto.CostHistory(date, amount, isAnomaly));
        }
        return costHistory;
    }

    private List<GcpBillingDashboardDto.ServiceBreakdown> getServiceBreakdown(BigQuery bq, String tableName, LocalDate today) throws InterruptedException {
        LocalDate startDate = today.withDayOfMonth(1);

        String query = String.format(
                "SELECT service.description as service_name, SUM(cost) as total_cost " +
                "FROM %s " +
                "WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s' " +
                "GROUP BY service_name HAVING total_cost > 0 ORDER BY total_cost DESC",
                tableName,
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE));

        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query).build();
        TableResult results = bq.query(queryConfig);
        List<GcpBillingDashboardDto.ServiceBreakdown> serviceBreakdown = new ArrayList<>();
        for (FieldValueList row : results.iterateAll()) {
            String serviceName = row.get("service_name").getStringValue();
            BigDecimal amount = row.get("total_cost").getNumericValue();
            serviceBreakdown.add(new GcpBillingDashboardDto.ServiceBreakdown(serviceName, amount));
        }
        return serviceBreakdown;
    }

    private BigDecimal getMonthToDateSpend(BigQuery bigquery, String tableName, LocalDate today) throws InterruptedException {
        LocalDate startDate = today.withDayOfMonth(1);
        String query = String.format(
                "SELECT SUM(cost) as total_cost FROM %s WHERE DATE(usage_start_time) >= '%s' AND DATE(usage_start_time) <= '%s'",
                tableName,
                startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        );

        TableResult results = bigquery.query(QueryJobConfiguration.newBuilder(query).build());
        FieldValueList row = results.getValues().iterator().next();
        if (row.get("total_cost").isNull()) {
            return BigDecimal.ZERO;
        }
        return row.get("total_cost").getNumericValue();
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