package com.xammer.cloud.autospotting.list;

import com.xammer.cloud.dto.autospotting.v2.GroupListDto;
import com.xammer.cloud.service.autospotting.core.list.GroupListCache;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationStepRepository;
import com.xammer.cloud.service.autospotting.core.audit.AuditRedactor;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

/** design-ux-ops.md §4.6, §9.8 (AC-CMP-06, AC-OBS-05): the list cache stores complete lists only and can be evicted. */
class AutoSpottingListCacheTest {

    private static GroupListDto list(boolean partial) {
        return new GroupListDto(List.of(), partial,
                partial ? List.of(new GroupListDto.FailedLocation("eu-west-1", "TIMEOUT")) : List.of(),
                Instant.now(), Map.of(), new GroupListDto.Kpis(0, 0, 0, null, 0));
    }

    @Test
    void aCompleteListIsServedFromTheCache() {
        GroupListCache cache = new GroupListCache(Duration.ofSeconds(60));
        GroupListDto l = list(false);
        cache.put(7L, l);
        assertThat(cache.get(7L)).containsSame(l);
        assertThat(cache.get(8L)).isEmpty();
    }

    @Test
    void aPartialListIsNeverCachedSoItIsNeverServedAsComplete() {
        GroupListCache cache = new GroupListCache(Duration.ofSeconds(60));
        cache.put(7L, list(true));
        assertThat(cache.get(7L)).isEmpty();
    }

    @Test
    void evictDropsTheAccountsList() {
        GroupListCache cache = new GroupListCache(Duration.ofSeconds(60));
        cache.put(7L, list(false));
        cache.put(8L, list(false));
        cache.evict(7L);
        assertThat(cache.get(7L)).isEmpty();
        assertThat(cache.get(8L)).isPresent();
        cache.evict(null); // no-op
    }

    @Test
    void anEntryExpiresAfterTheTtl() throws InterruptedException {
        GroupListCache cache = new GroupListCache(Duration.ofMillis(50));
        cache.put(7L, list(false));
        Thread.sleep(150);
        assertThat(cache.get(7L)).isEmpty();
    }

    @Test
    void terminalOperationEvictsList() {
        SpotOperationRepository ops = mock(SpotOperationRepository.class);
        OperationStore store = new OperationStore(ops, mock(SpotOperationStepRepository.class), mock(FencedTx.class),
                mock(AuditRedactor.class));
        GroupListCache cache = new GroupListCache(Duration.ofSeconds(60));
        store.setGroupListCache(cache);
        UUID opId = UUID.randomUUID();
        SpotOperation op = new SpotOperation();
        op.setId(opId);
        op.setCloudAccountId(7L);
        when(ops.findById(opId)).thenReturn(Optional.of(op));

        for (Runnable terminal : List.<Runnable>of(() -> store.finishSucceeded(opId),
                () -> store.finishFailed(opId, "CHECK_FAILED", "m"), () -> store.finishCancelled(opId, "USER"))) {
            cache.put(7L, list(false));
            cache.put(8L, list(false));
            terminal.run();
            assertThat(cache.get(7L)).as("the account's list is evicted").isEmpty();
            assertThat(cache.get(8L)).as("another account's list is untouched").isPresent();
        }
    }

    @Test
    void anOperationStoreWithoutACacheStillFinishes() {
        SpotOperationRepository ops = mock(SpotOperationRepository.class);
        OperationStore store = new OperationStore(ops, mock(SpotOperationStepRepository.class), mock(FencedTx.class),
                mock(AuditRedactor.class));
        store.finishSucceeded(UUID.randomUUID()); // no list cache wired: no exception
    }
}
