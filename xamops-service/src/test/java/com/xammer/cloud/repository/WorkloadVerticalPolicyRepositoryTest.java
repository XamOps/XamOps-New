package com.xammer.cloud.repository;

import com.xammer.cloud.domain.WorkloadVerticalPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2 slice test for the per-workload policy table, following the module's existing
 * {@code @DataJpaTest} + {@link JpaSliceTestConfig} pattern.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class WorkloadVerticalPolicyRepositoryTest {

    @Autowired
    WorkloadVerticalPolicyRepository repository;

    @Test
    void roundTripsAPolicy() {
        WorkloadVerticalPolicy saved = repository.save(policy(1L, 10L, "team-a", "overprovisioned-api"));

        assertThat(saved.getId()).isNotNull();
        Optional<WorkloadVerticalPolicy> found = repository
                .findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                        10L, "team-a", "Deployment", "overprovisioned-api");

        assertThat(found).isPresent();
        assertThat(found.get().getMode()).isEqualTo("RECOMMEND");
        assertThat(found.get().getClientId()).isEqualTo(1L);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void theEntityDefaultsMatchTheEngineDefaults() {
        // A row written with nothing but its identity must behave exactly like a workload with no
        // row at all. The SQL column defaults and the Java constants are the two places defaults
        // live; WorkloadPolicyServiceTest asserts the Java side, this asserts the entity side.
        WorkloadVerticalPolicy bare = new WorkloadVerticalPolicy();
        bare.setClientId(1L);
        bare.setClusterConfigId(10L);
        bare.setNamespace("team-a");
        bare.setWorkloadKind("Deployment");
        bare.setWorkloadName("bare");

        WorkloadVerticalPolicy saved = repository.saveAndFlush(bare);

        assertThat(saved.getMode()).isEqualTo("OFF");
        assertThat(saved.getHeadroomPct()).isEqualTo(25);
        assertThat(saved.getPercentile()).isEqualTo(95);
        assertThat(saved.getWindowHours()).isEqualTo(24);
        assertThat(saved.getCpuFloorMillicores()).isEqualTo(25);
        assertThat(saved.getMemoryFloorBytes()).isEqualTo(262144000L);
        assertThat(saved.getMinIncreasePct()).isEqualTo(15);
        assertThat(saved.getMinDecreasePct()).isEqualTo(30);
        assertThat(saved.getMinIncreaseCpuMillicores()).isEqualTo(25);
        assertThat(saved.getMinDecreaseCpuMillicores()).isEqualTo(100);
        assertThat(saved.getMinIncreaseMemoryBytes()).isEqualTo(33554432L);
        assertThat(saved.getMinDecreaseMemoryBytes()).isEqualTo(134217728L);
        assertThat(saved.getApplyMethod()).isEqualTo("IN_PLACE");
        assertThat(saved.getCooldownMinutes()).isEqualTo(60);
        assertThat(saved.getMaxLimitIncreaseFactor()).isEqualTo(4.0);
        assertThat(saved.getProtectSingleReplica()).isTrue();
        assertThat(saved.getNodeCapacityFraction()).isEqualTo(0.9);
        assertThat(saved.getLastAppliedAt()).isNull();
    }

    @Test
    void oneWorkloadCannotHaveTwoPolicies() {
        repository.saveAndFlush(policy(1L, 10L, "team-a", "overprovisioned-api"));

        assertThatThrownBy(() ->
                repository.saveAndFlush(policy(1L, 10L, "team-a", "overprovisioned-api")))
                .isInstanceOf(Exception.class);
    }

    @Test
    void theSameWorkloadNameInAnotherNamespaceIsADifferentWorkload() {
        // The Go engine keyed on Deployment name with no namespace, so two workloads of the same
        // name in different namespaces were one row. The unique key includes the namespace.
        repository.saveAndFlush(policy(1L, 10L, "team-a", "api"));
        repository.saveAndFlush(policy(1L, 10L, "team-b", "api"));

        assertThat(repository.findByClusterConfigId(10L)).hasSize(2);
    }

    @Test
    void theSameWorkloadInAnotherClusterIsADifferentWorkload() {
        repository.saveAndFlush(policy(1L, 10L, "team-a", "api"));
        repository.saveAndFlush(policy(1L, 11L, "team-a", "api"));

        assertThat(repository.findByClusterConfigId(10L)).hasSize(1);
        assertThat(repository.findByClusterConfigId(11L)).hasSize(1);
    }

    @Test
    void aMismatchedTenantAndClusterPairReturnsNothing() {
        repository.saveAndFlush(policy(1L, 10L, "team-a", "api"));

        assertThat(repository.findByClientIdAndClusterConfigId(1L, 10L)).hasSize(1);
        assertThat(repository.findByClientIdAndClusterConfigId(2L, 10L)).isEmpty();
    }

    @Test
    void lastAppliedAtRoundTrips() {
        Instant applied = Instant.parse("2026-10-10T05:00:00Z");
        WorkloadVerticalPolicy row = policy(1L, 10L, "team-a", "api");
        row.setLastAppliedAt(applied);

        Long id = repository.saveAndFlush(row).getId();
        List<WorkloadVerticalPolicy> all = repository.findByClusterConfigId(10L);

        assertThat(all).hasSize(1);
        assertThat(all.get(0).getId()).isEqualTo(id);
        assertThat(all.get(0).getLastAppliedAt()).isEqualTo(applied);
    }

    private static WorkloadVerticalPolicy policy(Long clientId, Long clusterConfigId,
                                                 String namespace, String name) {
        WorkloadVerticalPolicy policy = new WorkloadVerticalPolicy();
        policy.setClientId(clientId);
        policy.setClusterConfigId(clusterConfigId);
        policy.setNamespace(namespace);
        policy.setWorkloadKind("Deployment");
        policy.setWorkloadName(name);
        policy.setMode("RECOMMEND");
        return policy;
    }
}
