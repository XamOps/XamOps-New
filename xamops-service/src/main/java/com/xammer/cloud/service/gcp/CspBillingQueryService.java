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

    public List<GcpCostDto> queryMonthlyHistory(BigQuery bq, CspBillingConfig config, String projectId) {
        String sql = String.format(
            "SELECT invoice_month, SUM(cost_at_list) AS cost " +
            "FROM `%s.%s.%s` " +
            "WHERE project_id = @projectId " +
            "GROUP BY invoice_month ORDER BY invoice_month DESC LIMIT 12",
            config.getGcpProjectId(), config.getDatasetId(), config.getTableId());
        try {
            QueryJobConfiguration query = QueryJobConfiguration.newBuilder(sql)
                .addNamedParameter("projectId", QueryParameterValue.string(projectId))
                .build();
            TableResult result = bq.query(query);
            List<GcpCostDto> dtos = new ArrayList<>();
            for (FieldValueList row : result.iterateAll()) {
                String raw = row.get("invoice_month").getStringValue();
                String displayMonth = raw.substring(0, 4) + "-" + raw.substring(4, 6);
                double cost = row.get("cost").getNumericValue().doubleValue();
                dtos.add(new GcpCostDto(displayMonth, cost, false));
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
