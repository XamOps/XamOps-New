package com.xammer.cloud.service.gcp;

import com.google.cloud.bigquery.*;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class GcpCostServiceTest {

    @Mock
    private GcpClientProvider gcpClientProvider;

    @Mock
    private RedisCacheService redisCacheService;

    @Mock
    private BigQuery bigQuery;

    @Mock
    private TableResult tableResult;

    private GcpCostService gcpCostService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        gcpCostService = new GcpCostService(gcpClientProvider, redisCacheService);
    }

    @Test
    public void testGetCostByTagSync_UsesParameterizedQuery() throws InterruptedException {
        // Arrange
        String projectId = "test-project";
        String tagKey = "env";

        doReturn(Optional.empty()).when(redisCacheService).get(anyString(), any(Class.class));
        when(gcpClientProvider.getBigQueryClient(projectId)).thenReturn(Optional.of(bigQuery));

        // Mock table discovery
        Dataset dataset = mock(Dataset.class);
        DatasetId datasetId = DatasetId.of(projectId, "dataset");
        when(dataset.getDatasetId()).thenReturn(datasetId);

        Table table = mock(Table.class);
        TableId tableId = TableId.of(projectId, "dataset", "gcp_billing_export_v1_xyz");
        when(table.getTableId()).thenReturn(tableId);

        doReturn(mockPage(dataset)).when(bigQuery).listDatasets(projectId);
        doReturn(mockPage(table)).when(bigQuery).listTables(datasetId);

        when(bigQuery.query(any(QueryJobConfiguration.class))).thenReturn(tableResult);
        when(tableResult.iterateAll()).thenReturn(List.of());

        // Act
        gcpCostService.getCostByTagSync(projectId, tagKey);

        // Assert
        ArgumentCaptor<QueryJobConfiguration> captor = ArgumentCaptor.forClass(QueryJobConfiguration.class);
        verify(bigQuery).query(captor.capture());

        QueryJobConfiguration capturedConfig = captor.getValue();
        String query = capturedConfig.getQuery();

        assertTrue(query.contains("l.key = @tagKey"), "Query should use @tagKey parameter");
        assertTrue(query.contains("DATE(usage_start_time) >= @startDate"), "Query should use @startDate parameter");
        assertTrue(query.contains("DATE(usage_start_time) <= @endDate"), "Query should use @endDate parameter");

        assertEquals(tagKey, capturedConfig.getNamedParameters().get("tagKey").getValue());
        assertEquals(LocalDate.now().withDayOfMonth(1).toString(), capturedConfig.getNamedParameters().get("startDate").getValue());
        assertEquals(LocalDate.now().toString(), capturedConfig.getNamedParameters().get("endDate").getValue());
    }

    private <T> com.google.api.gax.paging.Page<T> mockPage(T item) {
        com.google.api.gax.paging.Page<T> page = mock(com.google.api.gax.paging.Page.class);
        doReturn(List.of(item)).when(page).iterateAll();
        return page;
    }
}
