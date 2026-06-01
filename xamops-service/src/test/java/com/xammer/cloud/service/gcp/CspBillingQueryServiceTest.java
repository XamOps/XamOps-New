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

    @Test
    void queryMonthlyHistory_convertsInvoiceMonthFormat() throws Exception {
        FieldValue monthVal = FieldValue.of(FieldValue.Attribute.PRIMITIVE, "202604");
        FieldValue costVal = FieldValue.of(FieldValue.Attribute.PRIMITIVE, "12.50");
        FieldValueList row = FieldValueList.of(List.of(monthVal, costVal),
            Field.of("invoice_month", LegacySQLTypeName.STRING),
            Field.of("cost", LegacySQLTypeName.NUMERIC));

        when(bigQuery.query(any(QueryJobConfiguration.class))).thenReturn(tableResult);
        when(tableResult.iterateAll()).thenReturn(List.of(row));

        List<GcpCostDto> result = service.queryMonthlyHistory(bigQuery, config, "my-project");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("2026-04");
        assertThat(result.get(0).getAmount()).isEqualTo(12.50);
    }
}
