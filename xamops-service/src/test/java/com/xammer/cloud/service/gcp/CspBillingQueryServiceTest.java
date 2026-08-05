package com.xammer.cloud.service.gcp;

import com.google.cloud.bigquery.*;
import com.xammer.cloud.domain.CspBillingConfig;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CspBillingQueryServiceTest {

    @Mock BigQuery bigQuery;
    @Mock TableResult tableResult;

    CspBillingQueryService service;
    CspBillingConfig config;

    @BeforeEach
    void setUp() {
        service = new CspBillingQueryService();
        config = new CspBillingConfig();
        config.setName("Sisl");
        config.setGcpProjectId("sisl-bq-billing");
        config.setDatasetId("Xammer_Billing_Dataset");
        config.setTableId("Xammer_Dataset");
        config.setServiceAccountKey("{\"type\":\"service_account\"}");
    }

    @Test
    void queryServiceBreakdown_returnsEmptyListOnQueryFailure() throws Exception {
        when(bigQuery.query(any(QueryJobConfiguration.class))).thenThrow(new InterruptedException("timeout"));

        List<GcpCostDto> result = service.queryServiceBreakdown(bigQuery, config, "my-project", "202604");

        assertThat(result).isEmpty();
    }

    @Test
    void queryMtdTotal_returnsZeroOnQueryFailure() throws Exception {
        when(bigQuery.query(any(QueryJobConfiguration.class))).thenThrow(new InterruptedException("timeout"));

        double result = service.queryMtdTotal(bigQuery, config, "my-project", "202604");

        assertThat(result).isEqualTo(0.0);
    }

    /**
     * History buckets by usage month now, so the query already returns "yyyy-MM" and no
     * string surgery is needed. Grouping by invoice_month used to split one calendar month
     * into two bars whenever GCP billed boundary usage to the previous invoice period.
     */
    @Test
    void queryMonthlyHistory_bucketsByUsageMonth() throws Exception {
        FieldValue monthVal = FieldValue.of(FieldValue.Attribute.PRIMITIVE, "2026-04");
        FieldValue costVal = FieldValue.of(FieldValue.Attribute.PRIMITIVE, "12.50");
        FieldValueList row = FieldValueList.of(List.of(monthVal, costVal),
            Field.of("month", LegacySQLTypeName.STRING),
            Field.of("cost", LegacySQLTypeName.NUMERIC));

        when(bigQuery.query(any(QueryJobConfiguration.class))).thenReturn(tableResult);
        when(tableResult.iterateAll()).thenReturn(List.of(row));

        List<GcpCostDto> result = service.queryMonthlyHistory(bigQuery, config, "my-project");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("2026-04");
        assertThat(result.get(0).getAmount()).isEqualTo(12.50);
    }

    @Test
    void queryMonthlyHistory_queriesUsageDateNotInvoiceMonth() throws Exception {
        when(bigQuery.query(any(QueryJobConfiguration.class))).thenReturn(tableResult);
        when(tableResult.iterateAll()).thenReturn(List.of());

        service.queryMonthlyHistory(bigQuery, config, "my-project");

        org.mockito.ArgumentCaptor<QueryJobConfiguration> captor =
            org.mockito.ArgumentCaptor.forClass(QueryJobConfiguration.class);
        verify(bigQuery).query(captor.capture());
        assertThat(captor.getValue().getQuery())
            .contains("DATE(usage_start_time)")
            .doesNotContain("invoice_month");
    }

    @Test
    void queryBreakdownByDateRange_bindsDatesAsParameters() throws Exception {
        when(bigQuery.query(any(QueryJobConfiguration.class))).thenReturn(tableResult);
        when(tableResult.iterateAll()).thenReturn(List.of());

        service.queryBreakdownByDateRange(bigQuery, config, "my-project",
            "service_description", "2026-08-01", "2026-08-05");

        org.mockito.ArgumentCaptor<QueryJobConfiguration> captor =
            org.mockito.ArgumentCaptor.forClass(QueryJobConfiguration.class);
        verify(bigQuery).query(captor.capture());
        QueryJobConfiguration sent = captor.getValue();
        assertThat(sent.getQuery()).contains("DATE(usage_start_time) >= @startDate");
        assertThat(sent.getNamedParameters()).containsKeys("projectId", "startDate", "endDate");
    }

    @Test
    void queryTotalByDateRange_returnsZeroOnQueryFailure() throws Exception {
        when(bigQuery.query(any(QueryJobConfiguration.class))).thenThrow(new InterruptedException("timeout"));

        double result = service.queryTotalByDateRange(bigQuery, config, "my-project",
            "2026-08-01", "2026-08-05");

        assertThat(result).isEqualTo(0.0);
    }
}
