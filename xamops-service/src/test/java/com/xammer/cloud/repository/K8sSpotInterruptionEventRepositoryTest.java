package com.xammer.cloud.repository;

import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2 slice test for the spot interruption history table ({@link ClusterCostSnapshotRepositoryTest}
 * pattern): every read is scoped by cluster, one row per node lifecycle, the batched purge keeps
 * newer rows (AC10), and the tracking query is not bounded by age.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class K8sSpotInterruptionEventRepositoryTest {

    private static final Long CLUSTER = 10L;
    private static final Long OTHER = 11L;
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-24T06:00:00Z");

    @Autowired
    K8sSpotInterruptionEventRepository repository;

    private K8sSpotInterruptionEvent row(Long cluster, String node, Instant created, Instant noticedAt, String status) {
        K8sSpotInterruptionEvent e = new K8sSpotInterruptionEvent();
        e.setClusterConfigId(cluster);
        e.setNodeName(node);
        e.setNodeCreatedAt(created);
        e.setNoticeKind("interruption");
        e.setSources("nth-taint");
        e.setNoticedAt(noticedAt);
        e.setRecoveryStatus(status);
        return e;
    }

    @Test
    void roundTripsARowAndStampsTimestamps() {
        K8sSpotInterruptionEvent saved = repository.saveAndFlush(row(CLUSTER, "n1", CREATED, NOW, "no-snapshot"));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(repository.findByClusterConfigIdAndNodeNameAndNodeCreatedAt(CLUSTER, "n1", CREATED)).isPresent();
        assertThat(repository.findFirstByClusterConfigIdAndNodeNameAndNodeCreatedAt(CLUSTER, "n1", CREATED)).isPresent();
        assertThat(repository.findFirstByClusterConfigIdAndNodeNameAndNodeCreatedAt(CLUSTER, "n1", CREATED.plusSeconds(1)))
                .as("a reused node name with another creation time is another lifecycle")
                .isEmpty();
    }

    @Test
    void everyReadIsScopedByCluster() {
        repository.saveAndFlush(row(CLUSTER, "n1", CREATED, NOW, "tracking"));
        repository.saveAndFlush(row(OTHER, "n1", CREATED, NOW, "tracking"));

        assertThat(repository.findByClusterConfigIdAndNodeNameAndNodeCreatedAt(CLUSTER, "n1", CREATED).get()
                .getClusterConfigId()).isEqualTo(CLUSTER);
        assertThat(repository.findByClusterConfigIdAndRecoveryStatus(CLUSTER, "tracking"))
                .extracting(K8sSpotInterruptionEvent::getClusterConfigId).containsExactly(CLUSTER);
        assertThat(repository.findByClusterConfigIdAndNodeNameAndNoticedAtBetween(CLUSTER, "n1",
                NOW.minusSeconds(60), NOW.plusSeconds(60)))
                .extracting(K8sSpotInterruptionEvent::getClusterConfigId).containsExactly(CLUSTER);
        assertThat(repository.findByClusterConfigIdAndNoticedAtAfterOrderByNoticedAtDesc(CLUSTER,
                NOW.minusSeconds(60), PageRequest.of(0, 10)))
                .extracting(K8sSpotInterruptionEvent::getClusterConfigId).containsExactly(CLUSTER);
    }

    @Test
    void theSameNodeLifecycleTwiceIsRefused() {
        repository.saveAndFlush(row(CLUSTER, "n1", CREATED, NOW, "tracking"));
        assertThatThrownBy(() -> repository.saveAndFlush(row(CLUSTER, "n1", CREATED, NOW.plusSeconds(5), "tracking")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theReadQueryIsNewestFirstAndPaged() {
        repository.saveAndFlush(row(CLUSTER, "a", CREATED, NOW.minusSeconds(300), "tracking"));
        repository.saveAndFlush(row(CLUSTER, "b", CREATED, NOW.minusSeconds(100), "tracking"));
        repository.saveAndFlush(row(CLUSTER, "c", CREATED, NOW.minusSeconds(200), "tracking"));
        repository.saveAndFlush(row(CLUSTER, "old", CREATED, NOW.minus(Duration.ofDays(40)), "recovered"));
        List<K8sSpotInterruptionEvent> page = repository.findByClusterConfigIdAndNoticedAtAfterOrderByNoticedAtDesc(
                CLUSTER, NOW.minus(Duration.ofDays(30)), PageRequest.of(0, 2));
        assertThat(page).extracting(K8sSpotInterruptionEvent::getNodeName).containsExactly("b", "c");
    }

    @Test
    void theTrackingQueryReturnsOldRows() {
        repository.saveAndFlush(row(CLUSTER, "old", CREATED, NOW.minus(Duration.ofDays(200)), "tracking"));
        repository.saveAndFlush(row(CLUSTER, "done", CREATED, NOW, "recovered"));
        assertThat(repository.findByClusterConfigIdAndRecoveryStatus(CLUSTER, "tracking"))
                .extracting(K8sSpotInterruptionEvent::getNodeName).containsExactly("old");
    }

    @Test
    void thePurgeDeletesOnlyRowsOlderThanTheCutoff_inBatches_AC10() {
        Instant cutoff = NOW.minus(Duration.ofDays(365));
        for (int i = 0; i < 3; i++) {
            repository.saveAndFlush(row(CLUSTER, "old-" + i, CREATED, cutoff.minus(Duration.ofDays(1 + i)), "recovered"));
        }
        repository.saveAndFlush(row(OTHER, "old-other", CREATED, cutoff.minusSeconds(1), "recovered"));
        repository.saveAndFlush(row(CLUSTER, "new", CREATED, cutoff.plusSeconds(1), "recovered"));

        assertThat(repository.deleteOlderThanBatch(cutoff, 2)).as("one batch is bounded").isEqualTo(2);
        assertThat(repository.deleteOlderThanBatch(cutoff, 2)).isEqualTo(2);
        assertThat(repository.deleteOlderThanBatch(cutoff, 2)).isZero();
        assertThat(repository.findAll()).extracting(K8sSpotInterruptionEvent::getNodeName).containsExactly("new");
    }
}
