package com.xammer.cloud.service;

import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.xammer.cloud.service.AgentIngestService.CLEANUP_BATCH_SIZE;
import static com.xammer.cloud.service.AgentIngestService.CLEANUP_MAX_BATCHES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the batched retention cleanup for agent-pushed data.
 *
 * These tables were never pruned (the cleanup method existed but nothing scheduled it), so
 * the first run in production has to clear whatever has accumulated since the deployment was
 * created. The batching loop is what keeps that from becoming one enormous transaction
 * against a database whose connection pool has already failed once, so the loop's
 * termination conditions are worth pinning down.
 */
class AgentDataCleanupTest {

    private AgentIngestService service;
    private AgentDataCacheRepository dataRepo;
    private AgentEventRepository eventRepo;

    @BeforeEach
    void setUp() {
        dataRepo = mock(AgentDataCacheRepository.class);
        eventRepo = mock(AgentEventRepository.class);
        service = new AgentIngestService(
                mock(KubernetesClusterConfigRepository.class),
                dataRepo,
                eventRepo,
                mock(CloudAccountRepository.class));
    }

    @Test
    void stopsAfterAShortBatchAndSumsWhatWasDeleted() {
        // Two full batches then a partial one: the partial batch means we've caught up.
        when(dataRepo.deleteStaleDataBatch(any(), anyInt()))
                .thenReturn(CLEANUP_BATCH_SIZE, CLEANUP_BATCH_SIZE, 42);
        when(eventRepo.deleteOldEventsBatch(any(), anyInt())).thenReturn(7);

        int deleted = service.cleanupStaleData(14);

        assertThat(deleted).isEqualTo(CLEANUP_BATCH_SIZE * 2 + 42 + 7);
        verify(dataRepo, times(3)).deleteStaleDataBatch(any(), anyInt());
        verify(eventRepo, times(1)).deleteOldEventsBatch(any(), anyInt());
    }

    @Test
    void makesASingleCallPerTableWhenThereIsNothingToDelete() {
        when(dataRepo.deleteStaleDataBatch(any(), anyInt())).thenReturn(0);
        when(eventRepo.deleteOldEventsBatch(any(), anyInt())).thenReturn(0);

        assertThat(service.cleanupStaleData(14)).isZero();

        // A daily no-op job must not cost more than two statements.
        verify(dataRepo, times(1)).deleteStaleDataBatch(any(), anyInt());
        verify(eventRepo, times(1)).deleteOldEventsBatch(any(), anyInt());
    }

    @Test
    void stopsAtTheBatchCapRatherThanLoopingForever() {
        // Always-full batches would otherwise spin indefinitely against the database.
        when(dataRepo.deleteStaleDataBatch(any(), anyInt())).thenReturn(CLEANUP_BATCH_SIZE);
        when(eventRepo.deleteOldEventsBatch(any(), anyInt())).thenReturn(0);

        int deleted = service.cleanupStaleData(14);

        assertThat(deleted).isEqualTo(CLEANUP_BATCH_SIZE * CLEANUP_MAX_BATCHES);
        verify(dataRepo, times(CLEANUP_MAX_BATCHES)).deleteStaleDataBatch(any(), anyInt());
    }

    @Test
    void requestsBoundedBatchesAndACutoffInThePast() {
        when(dataRepo.deleteStaleDataBatch(any(), anyInt())).thenReturn(0);
        when(eventRepo.deleteOldEventsBatch(any(), anyInt())).thenReturn(0);

        LocalDateTime before = LocalDateTime.now().minusDays(14);
        service.cleanupStaleData(14);

        // The cutoff must be retentionDays in the past — deleting with a future cutoff would
        // wipe live data the dashboards are still reading.
        org.mockito.ArgumentCaptor<LocalDateTime> cutoff =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.ArgumentCaptor<Integer> batchSize =
                org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(dataRepo).deleteStaleDataBatch(cutoff.capture(), batchSize.capture());

        assertThat(cutoff.getValue()).isBeforeOrEqualTo(LocalDateTime.now().minusDays(14));
        assertThat(cutoff.getValue()).isAfterOrEqualTo(before.minusMinutes(1));
        assertThat(batchSize.getValue()).isEqualTo(CLEANUP_BATCH_SIZE);
    }
}
