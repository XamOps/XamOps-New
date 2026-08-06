package com.xammer.cloud.service.gcp;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import com.xammer.cloud.domain.CspBillingConfig;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class CspBillingQueryService {

    public BigQuery buildClient(CspBillingConfig config) {
        try {
            GoogleCredentials credentials = GoogleCredentials
                .fromStream(new ByteArrayInputStream(config.getServiceAccountKey().getBytes()))
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
            return BigQueryOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(config.getGcpProjectId())
                .build()
                .getService();
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to build BigQuery client for CSP config: " + config.getName(), e);
        }
    }

    public List<GcpCostDto> queryServiceBreakdown(BigQuery bq, CspBillingConfig config,
                                                   String projectId, String invoiceMonth) {
        String sql = String.format(
            "SELECT service_description, SUM(cost_at_list) AS cost " +
            "FROM `%s.%s.%s` " +
            "WHERE project_id = @projectId AND invoice_month = @invoiceMonth " +
            "GROUP BY service_description",
            config.getGcpProjectId(), config.getDatasetId(), config.getTableId());
        return runNamedQuery(bq, sql, projectId, invoiceMonth, "service_description");
    }

    public List<GcpCostDto> queryRegionBreakdown(BigQuery bq, CspBillingConfig config,
                                                  String projectId, String invoiceMonth) {
        String sql = String.format(
            "SELECT COALESCE(region, 'global') AS region, SUM(cost_at_list) AS cost " +
            "FROM `%s.%s.%s` " +
            "WHERE project_id = @projectId AND invoice_month = @invoiceMonth " +
            "GROUP BY region",
            config.getGcpProjectId(), config.getDatasetId(), config.getTableId());
        return runNamedQuery(bq, sql, projectId, invoiceMonth, "region");
    }

    /**
     * Last 12 months of cost, bucketed by USAGE month rather than invoice_month.
     * <p>
     * Grouping by invoice_month splits a single calendar month into two buckets whenever
     * GCP bills boundary usage to the previous invoice period — early-August usage
     * invoiced as 202607 showed up as a separate bar. That made the history chart
     * disagree with the month-to-date KPI, which filters on usage date. Output format
     * ("yyyy-MM") is unchanged, so {@code getLastMonthSpend} and the chart still match.
     */
    public List<GcpCostDto> queryMonthlyHistory(BigQuery bq, CspBillingConfig config, String projectId) {
        String sql = String.format(
            "SELECT FORMAT_DATE('%%Y-%%m', DATE(usage_start_time)) AS month, SUM(cost_at_list) AS cost " +
            "FROM `%s.%s.%s` " +
            "WHERE project_id = @projectId " +
            "GROUP BY month ORDER BY month DESC LIMIT 12",
            config.getGcpProjectId(), config.getDatasetId(), config.getTableId());
        try {
            QueryJobConfiguration query = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("projectId", QueryParameterValue.string(projectId))
                .build();
            TableResult result = bq.query(query);
            List<GcpCostDto> dtos = new ArrayList<>();
            for (FieldValueList row : result.iterateAll()) {
                double cost = row.get("cost").isNull() ? 0.0
                    : row.get("cost").getNumericValue().doubleValue();
                dtos.add(new GcpCostDto(row.get("month").getStringValue(), cost, false));
            }
            return dtos;
        } catch (Exception e) {
            log.error("CSP monthly history query failed for project {}", projectId, e);
            return List.of();
        }
    }

    public double queryMtdTotal(BigQuery bq, CspBillingConfig config,
                                String projectId, String invoiceMonth) {
        String sql = String.format(
            "SELECT SUM(cost_at_list) AS cost " +
            "FROM `%s.%s.%s` " +
            "WHERE project_id = @projectId AND invoice_month = @invoiceMonth",
            config.getGcpProjectId(), config.getDatasetId(), config.getTableId());
        try {
            QueryJobConfiguration query = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("projectId", QueryParameterValue.string(projectId))
                .addNamedParameter("invoiceMonth", QueryParameterValue.string(invoiceMonth))
                .build();
            TableResult result = bq.query(query);
            for (FieldValueList row : result.iterateAll()) {
                if (!row.get("cost").isNull()) {
                    return row.get("cost").getNumericValue().doubleValue();
                }
            }
            return 0.0;
        } catch (Exception e) {
            log.error("CSP MTD query failed for project {}", projectId, e);
            return 0.0;
        }
    }

    // =====================================================================================
    // Date-range queries
    //
    // The invoice_month-based methods above only ever see one billing period, which makes
    // them unusable for the Cost pages (arbitrary ranges, "last 90 days") and makes them
    // undercount: GCP bills usage at a month boundary to the PREVIOUS invoice_month, so a
    // '202608' filter silently drops early-August usage invoiced as '202607'. Filtering on
    // usage_start_time fixes both problems.
    // =====================================================================================

    /** Column in the flattened reseller export for each grouping dimension we support. */
    public static String dimensionColumn(String groupBy) {
        if (groupBy == null) {
            return "service_description";
        }
        switch (groupBy.toUpperCase()) {
            case "REGION":  return "region";
            case "PROJECT": return "project_name";
            default:        return "service_description";
        }
    }

    /**
     * Cost grouped by one dimension over a date range.
     *
     * @param dimension a column name from {@link #dimensionColumn(String)}
     */
    public List<GcpCostDto> queryBreakdownByDateRange(BigQuery bq, CspBillingConfig config,
                                                      String projectId, String dimension,
                                                      String startDate, String endDate) {
        String sql = String.format(
            "SELECT COALESCE(%s, 'Uncategorized') AS name, SUM(cost_at_list) AS cost " +
            "FROM `%s.%s.%s` " +
            "WHERE project_id = @projectId " +
            "  AND DATE(usage_start_time) >= @startDate AND DATE(usage_start_time) <= @endDate " +
            "GROUP BY name HAVING SUM(cost_at_list) > 0 ORDER BY cost DESC",
            dimension, config.getGcpProjectId(), config.getDatasetId(), config.getTableId());
        try {
            TableResult result = bq.query(dateRangeQuery(sql, projectId, startDate, endDate));
            List<GcpCostDto> dtos = new ArrayList<>();
            for (FieldValueList row : result.iterateAll()) {
                dtos.add(new GcpCostDto(
                    row.get("name").isNull() ? "Uncategorized" : row.get("name").getStringValue(),
                    row.get("cost").getNumericValue().doubleValue(),
                    false));
            }
            return dtos;
        } catch (Exception e) {
            log.error("CSP date-range breakdown query failed for project {} ({} to {})",
                projectId, startDate, endDate, e);
            return List.of();
        }
    }

    /** Total cost over a date range. */
    public double queryTotalByDateRange(BigQuery bq, CspBillingConfig config,
                                        String projectId, String startDate, String endDate) {
        String sql = String.format(
            "SELECT SUM(cost_at_list) AS cost FROM `%s.%s.%s` " +
            "WHERE project_id = @projectId " +
            "  AND DATE(usage_start_time) >= @startDate AND DATE(usage_start_time) <= @endDate",
            config.getGcpProjectId(), config.getDatasetId(), config.getTableId());
        try {
            TableResult result = bq.query(dateRangeQuery(sql, projectId, startDate, endDate));
            for (FieldValueList row : result.iterateAll()) {
                if (!row.get("cost").isNull()) {
                    return row.get("cost").getNumericValue().doubleValue();
                }
            }
            return 0.0;
        } catch (Exception e) {
            log.error("CSP date-range total query failed for project {} ({} to {})",
                projectId, startDate, endDate, e);
            return 0.0;
        }
    }

    /** Daily cost totals over a date range, optionally filtered to a single service. */
    public List<java.util.Map<String, Object>> queryDailyCosts(BigQuery bq, CspBillingConfig config,
                                                               String projectId, String serviceName,
                                                               String startDate, String endDate) {
        boolean filterService = serviceName != null && !serviceName.isBlank()
                && !"ALL".equalsIgnoreCase(serviceName);
        String sql = String.format(
            "SELECT FORMAT_DATE('%%Y-%%m-%%d', DATE(usage_start_time)) AS date, SUM(cost_at_list) AS cost " +
            "FROM `%s.%s.%s` " +
            "WHERE project_id = @projectId " +
            "  AND DATE(usage_start_time) >= @startDate AND DATE(usage_start_time) <= @endDate " +
            "%s" +
            "GROUP BY date ORDER BY date",
            config.getGcpProjectId(), config.getDatasetId(), config.getTableId(),
            filterService ? "  AND service_description = @serviceName " : "");
        try {
            QueryJobConfiguration.Builder builder = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("projectId", QueryParameterValue.string(projectId))
                .addNamedParameter("startDate", QueryParameterValue.date(startDate))
                .addNamedParameter("endDate", QueryParameterValue.date(endDate));
            if (filterService) {
                builder.addNamedParameter("serviceName", QueryParameterValue.string(serviceName));
            }
            TableResult result = bq.query(builder.build());
            List<java.util.Map<String, Object>> rows = new ArrayList<>();
            for (FieldValueList row : result.iterateAll()) {
                rows.add(java.util.Map.of(
                    "date", row.get("date").getStringValue(),
                    "cost", row.get("cost").isNull() ? 0.0 : row.get("cost").getNumericValue().doubleValue()));
            }
            return rows;
        } catch (Exception e) {
            log.error("CSP daily cost query failed for project {} ({} to {})",
                projectId, startDate, endDate, e);
            return List.of();
        }
    }

    private QueryJobConfiguration dateRangeQuery(String sql, String projectId,
                                                 String startDate, String endDate) {
        return QueryJobConfiguration.newBuilder(sql)
            .addNamedParameter("projectId", QueryParameterValue.string(projectId))
            .addNamedParameter("startDate", QueryParameterValue.date(startDate))
            .addNamedParameter("endDate", QueryParameterValue.date(endDate))
            .build();
    }

    private List<GcpCostDto> runNamedQuery(BigQuery bq, String sql,
                                            String projectId, String invoiceMonth, String nameField) {
        try {
            QueryJobConfiguration query = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("projectId", QueryParameterValue.string(projectId))
                .addNamedParameter("invoiceMonth", QueryParameterValue.string(invoiceMonth))
                .build();
            TableResult result = bq.query(query);
            List<GcpCostDto> dtos = new ArrayList<>();
            for (FieldValueList row : result.iterateAll()) {
                String name = row.get(nameField).isNull() ? "unknown"
                    : row.get(nameField).getStringValue();
                double cost = row.get("cost").getNumericValue().doubleValue();
                dtos.add(new GcpCostDto(name, cost, false));
            }
            return dtos;
        } catch (Exception e) {
            log.error("CSP breakdown query failed for project {}", projectId, e);
            return List.of();
        }
    }
}
