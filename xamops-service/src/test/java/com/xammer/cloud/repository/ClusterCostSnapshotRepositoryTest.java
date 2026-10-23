package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ClusterCostSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 slice test for the durable cluster-cost-snapshot table, following the module's existing
 * {@code @DataJpaTest} + {@link JpaSliceTestConfig} pattern (as {@link WorkloadVerticalActionRepositoryTest}
 * does). Proves the write/read-back round-trip, the prior-period window selection the Overview hero
 * reads back against, and that no history means no fabricated prior.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class ClusterCostSnapshotRepositoryTest {

    private static final Long CLUSTER = 10L;
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    @Autowired
    ClusterCostSnapshotRepository repository;

    @Test
    void roundTripsASnapshot() {
        ClusterCostSnapshot saved = repository.saveAndFlush(
                new ClusterCostSnapshot(CLUSTER, 1234.56, Instant.parse("2026-10-22T05:00:00Z")));

        assertThat(saved.getId()).isNotNull();

        Optional<ClusterCostSnapshot> found =
                repository.findTopByClusterConfigIdOrderByCapturedAtDesc(CLUSTER);

        assertThat(found).isPresent();
        assertThat(found.get().getClusterConfigId()).isEqualTo(CLUSTER);
        assertThat(found.get().getClusterMonthlyCost()).isEqualTo(1234.56);
        assertThat(found.get().getCapturedAt()).isEqualTo(Instant.parse("2026-10-22T05:00:00Z"));
    }

    @Test
    void prePersistStampsCapturedAtWhenNull() {
        Instant before = Instant.now();
        ClusterCostSnapshot saved =
                repository.saveAndFlush(new ClusterCostSnapshot(CLUSTER, 10.0, null));

        assertThat(saved.getCapturedAt())
                .as("@PrePersist stamps a captured_at when the caller left it null")
                .isNotNull()
                .isAfterOrEqualTo(before);
    }

    @Test
    void theReadBackSelectsTheNewestSnapshotAtOrBeforeTheCutoff() {
        repository.saveAndFlush(new ClusterCostSnapshot(CLUSTER, 100.0, NOW.minusSeconds(48 * 3600)));
        repository.saveAndFlush(new ClusterCostSnapshot(CLUSTER, 200.0, NOW.minusSeconds(24 * 3600)));
        repository.saveAndFlush(new ClusterCostSnapshot(CLUSTER, 300.0, NOW.minusSeconds(3600)));

        Instant cutoff = NOW.minusSeconds(24 * 3600);
        Optional<ClusterCostSnapshot> prior = repository
                .findTopByClusterConfigIdAndCapturedAtLessThanEqualOrderByCapturedAtDesc(CLUSTER, cutoff);

        assertThat(prior)
                .as("the -24h row is at the cutoff; the -1h row is newer than it and excluded")
                .isPresent();
        assertThat(prior.get().getClusterMonthlyCost()).isEqualTo(200.0);
        assertThat(prior.get().getCapturedAt()).isEqualTo(NOW.minusSeconds(24 * 3600));
    }

    @Test
    void theReadBackIsScopedByClusterConfigId() {
        repository.saveAndFlush(new ClusterCostSnapshot(CLUSTER, 200.0, NOW.minusSeconds(24 * 3600)));
        repository.saveAndFlush(new ClusterCostSnapshot(11L, 999.0, NOW.minusSeconds(24 * 3600)));

        Optional<ClusterCostSnapshot> prior = repository
                .findTopByClusterConfigIdAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
                        CLUSTER, NOW.minusSeconds(3600));

        assertThat(prior).isPresent();
        assertThat(prior.get().getClusterMonthlyCost())
                .as("another cluster's snapshot is never returned")
                .isEqualTo(200.0);
    }

    @Test
    void noHistoryAtAllReturnsEmpty() {
        assertThat(repository.findTopByClusterConfigIdAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
                CLUSTER, NOW)).isEmpty();
        assertThat(repository.findTopByClusterConfigIdOrderByCapturedAtDesc(CLUSTER)).isEmpty();
    }

    @Test
    void onlyARowNewerThanTheCutoffReturnsEmpty() {
        // The only snapshot is newer than the cutoff, so there is no prior-period baseline yet.
        repository.saveAndFlush(new ClusterCostSnapshot(CLUSTER, 300.0, NOW.minusSeconds(3600)));

        Optional<ClusterCostSnapshot> prior = repository
                .findTopByClusterConfigIdAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
                        CLUSTER, NOW.minusSeconds(24 * 3600));

        assertThat(prior)
                .as("a row inside the lookback window is not a prior period; never fabricate one")
                .isEmpty();
    }
}
