package com.xammer.cloud.repository;

import com.xammer.cloud.domain.WorkloadVerticalAction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 slice test for the durable action table, following the module's existing {@code @DataJpaTest} +
 * {@link JpaSliceTestConfig} pattern (as {@link WorkloadVerticalPolicyRepositoryTest} does).
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class WorkloadVerticalActionRepositoryTest {

    @Autowired
    WorkloadVerticalActionRepository repository;

    @Test
    void roundTripsAnAction() {
        WorkloadVerticalAction saved = repository.saveAndFlush(action(1L, 10L, "team-a",
                "overprovisioned-api", "APPLIED", Instant.parse("2026-10-13T05:00:00Z")));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        Optional<WorkloadVerticalAction> found = repository
                .findTopByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadNameOrderByAppliedAtDesc(
                        10L, "team-a", "Deployment", "overprovisioned-api");

        assertThat(found).isPresent();
        assertThat(found.get().getClientId()).isEqualTo(1L);
        assertThat(found.get().getTriggerSource()).isEqualTo("MANUAL");
        assertThat(found.get().getOutcome()).isEqualTo("APPLIED");
        assertThat(found.get().getDryRun()).isFalse();
        assertThat(found.get().getRestartFree()).isTrue();
        assertThat(found.get().getBeforeJson()).isEqualTo("{\"cpuRequestMillis\":25}");
        assertThat(found.get().getAppliedAt()).isEqualTo(Instant.parse("2026-10-13T05:00:00Z"));
    }

    @Test
    void theNewestActionPerWorkloadIsReturned() {
        repository.saveAndFlush(action(1L, 10L, "team-a", "api", "APPLIED",
                Instant.parse("2026-10-13T05:00:00Z")));
        repository.saveAndFlush(action(1L, 10L, "team-a", "api", "FAILED",
                Instant.parse("2026-10-13T07:00:00Z")));

        Optional<WorkloadVerticalAction> newest = repository
                .findTopByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadNameOrderByAppliedAtDesc(
                        10L, "team-a", "Deployment", "api");

        assertThat(newest).isPresent();
        assertThat(newest.get().getOutcome())
                .as("the lastAction is the most recent one by applied_at")
                .isEqualTo("FAILED");
    }

    @Test
    void everyActionForAClusterIsReturnedNewestFirst() {
        repository.saveAndFlush(action(1L, 10L, "team-a", "api", "APPLIED",
                Instant.parse("2026-10-13T05:00:00Z")));
        repository.saveAndFlush(action(1L, 10L, "team-b", "worker", "APPLIED",
                Instant.parse("2026-10-13T06:00:00Z")));

        List<WorkloadVerticalAction> all = repository.findByClusterConfigIdOrderByAppliedAtDesc(10L);

        assertThat(all).hasSize(2);
        assertThat(all.get(0).getWorkloadName())
                .as("newest first, so the read endpoint can keep the first row per workload key")
                .isEqualTo("worker");
    }

    @Test
    void actionsAreScopedByClusterConfigId() {
        repository.saveAndFlush(action(1L, 10L, "team-a", "api", "APPLIED",
                Instant.parse("2026-10-13T05:00:00Z")));
        repository.saveAndFlush(action(1L, 11L, "team-a", "api", "APPLIED",
                Instant.parse("2026-10-13T05:00:00Z")));

        assertThat(repository.findByClusterConfigIdOrderByAppliedAtDesc(10L)).hasSize(1);
        assertThat(repository.findByClusterConfigIdOrderByAppliedAtDesc(11L)).hasSize(1);
        assertThat(repository.findByClusterConfigIdOrderByAppliedAtDesc(12L)).isEmpty();
    }

    private static WorkloadVerticalAction action(Long clientId, Long clusterConfigId, String namespace,
                                                 String name, String outcome, Instant appliedAt) {
        WorkloadVerticalAction action = new WorkloadVerticalAction();
        action.setClientId(clientId);
        action.setClusterConfigId(clusterConfigId);
        action.setNamespace(namespace);
        action.setWorkloadKind("Deployment");
        action.setWorkloadName(name);
        action.setCorrelationId("corr-1");
        action.setTriggerSource("MANUAL");
        action.setDryRun(false);
        action.setOutcome(outcome);
        action.setRestartFree(true);
        action.setBeforeJson("{\"cpuRequestMillis\":25}");
        action.setAfterJson("{\"cpuRequestMillis\":25}");
        action.setDetail(null);
        action.setAppliedAt(appliedAt);
        return action;
    }
}
