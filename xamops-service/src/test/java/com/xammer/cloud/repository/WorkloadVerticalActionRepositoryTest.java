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

    // ---------------------------------------------------------------- the activity feed's page (FEAT-007)

    private static final Instant T1 = Instant.parse("2026-10-13T05:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-13T06:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-13T07:00:00Z");
    private static final Instant T4 = Instant.parse("2026-10-13T08:00:00Z");
    private static final Instant T5 = Instant.parse("2026-10-13T09:00:00Z");

    @Test
    void anActivityPageHoldsOnlyTheTenantsRowsOfThatCluster() {
        WorkloadVerticalAction own = repository.saveAndFlush(action(1L, 10L, "team-a", "api", "APPLIED", T1));
        repository.saveAndFlush(action(2L, 10L, "team-a", "api", "APPLIED", T2));
        repository.saveAndFlush(action(1L, 11L, "team-a", "api", "APPLIED", T3));

        List<WorkloadVerticalAction> page = repository.findActivityPage(1L, 10L, null, null, 50);

        assertThat(page)
                .as("another client's row under the same config id, and the client's other cluster, are never returned")
                .extracting(WorkloadVerticalAction::getId)
                .containsExactly(own.getId());
        assertThat(repository.findActivityPage(3L, 10L, null, null, 50)).isEmpty();
    }

    @Test
    void anActivityPageIsNewestFirstWithTheIdBreakingATie() {
        WorkloadVerticalAction oldest = repository.saveAndFlush(action(1L, 10L, "team-a", "a", "APPLIED", T1));
        WorkloadVerticalAction tiedFirst = repository.saveAndFlush(action(1L, 10L, "team-a", "b", "APPLIED", T2));
        WorkloadVerticalAction tiedSecond = repository.saveAndFlush(action(1L, 10L, "team-a", "c", "APPLIED", T2));

        assertThat(repository.findActivityPage(1L, 10L, null, null, 50))
                .extracting(WorkloadVerticalAction::getId)
                .containsExactly(tiedSecond.getId(), tiedFirst.getId(), oldest.getId());
        assertThat(repository.findActivityPage(1L, 10L, null, null, 2)).hasSize(2);
    }

    @Test
    void eachActivityFilterNarrowsThePage() {
        WorkloadVerticalAction applied = repository.saveAndFlush(action(1L, 10L, "team-a", "Worker-API", "APPLIED", T1));
        WorkloadVerticalAction hpa = action(1L, 10L, "team-b", "php-apache", "APPLIED", T2);
        hpa.setWorkloadKind("HorizontalPodAutoscaler");
        hpa = repository.saveAndFlush(hpa);
        WorkloadVerticalAction deferred = action(1L, 10L, "prod-sim", "throttled-encoder", "FAILED", T3);
        deferred.setAfterJson("{\"terminal\":\"DEFERRED\",\"reverted\":false,\"containers\":[],\"pods\":[]}");
        deferred = repository.saveAndFlush(deferred);
        WorkloadVerticalAction dryRun = action(1L, 10L, "team-a", "api", "TEST_RUN", T4);
        dryRun.setDryRun(true);
        dryRun.setAfterJson(null);
        dryRun = repository.saveAndFlush(dryRun);
        WorkloadVerticalAction underscore = repository.saveAndFlush(action(1L, 10L, "team-a", "a_b", "APPLIED", T5));
        repository.saveAndFlush(action(1L, 10L, "team-a", "axb", "APPLIED", T5));

        assertThat(ids(WorkloadVerticalActionRepository.appliedBetween(T2, T3)))
                .containsExactly(deferred.getId(), hpa.getId());
        assertThat(ids(WorkloadVerticalActionRepository.appliedBetween(T4, null))).hasSize(3);
        assertThat(ids(WorkloadVerticalActionRepository.appliedBetween(null, T1))).containsExactly(applied.getId());
        assertThat(ids(WorkloadVerticalActionRepository.kindIs("HorizontalPodAutoscaler")))
                .containsExactly(hpa.getId());
        assertThat(ids(WorkloadVerticalActionRepository.kindIsNot("HorizontalPodAutoscaler")))
                .hasSize(5).doesNotContain(hpa.getId());
        assertThat(ids(WorkloadVerticalActionRepository.outcomeIn(List.of("FAILED", "TEST_RUN"))))
                .containsExactly(dryRun.getId(), deferred.getId());
        assertThat(ids(WorkloadVerticalActionRepository.dryRun(true))).containsExactly(dryRun.getId());
        assertThat(ids(WorkloadVerticalActionRepository.afterJsonStartsWithAny(
                List.of("{\"terminal\":\"DEFERRED\"", "{\"terminal\":\"INFEASIBLE\""))))
                .containsExactly(deferred.getId());
        assertThat(ids(WorkloadVerticalActionRepository.afterJsonStartsWithNone(
                List.of("{\"terminal\":\"DEFERRED\""))))
                .as("a row with no after_json is not a deferral")
                .hasSize(5).contains(dryRun.getId()).doesNotContain(deferred.getId());
        assertThat(ids(WorkloadVerticalActionRepository.nameContains("api")))
                .as("case-insensitive")
                .containsExactly(dryRun.getId(), applied.getId());
        assertThat(ids(WorkloadVerticalActionRepository.nameContains("a_b")))
                .as("a LIKE wildcard in the text matches only itself")
                .containsExactly(underscore.getId());
        assertThat(ids(WorkloadVerticalActionRepository.nameContains("%"))).isEmpty();
    }

    @Test
    void theCursorContinuesExactlyWhereThePreviousPageEnded() {
        WorkloadVerticalAction r1 = repository.saveAndFlush(action(1L, 10L, "team-a", "r1", "APPLIED", T1));
        WorkloadVerticalAction r2 = repository.saveAndFlush(action(1L, 10L, "team-a", "r2", "APPLIED", T2));
        WorkloadVerticalAction r3 = repository.saveAndFlush(action(1L, 10L, "team-a", "r3", "APPLIED", T3));
        WorkloadVerticalAction r4 = repository.saveAndFlush(action(1L, 10L, "team-a", "r4", "APPLIED", T3));
        WorkloadVerticalAction r5 = repository.saveAndFlush(action(1L, 10L, "team-a", "r5", "APPLIED", T5));

        List<WorkloadVerticalAction> first = repository.findActivityPage(1L, 10L, null, null, 2);
        assertThat(first).extracting(WorkloadVerticalAction::getId).containsExactly(r5.getId(), r4.getId());

        // A row recorded while the user reads the first page is not repeated on the next one.
        repository.saveAndFlush(action(1L, 10L, "team-a", "late", "APPLIED", T5.plusSeconds(60)));

        List<WorkloadVerticalAction> second = repository.findActivityPage(1L, 10L, null,
                new WorkloadVerticalActionRepository.ActivityCursor(r4.getAppliedAt(), r4.getId()), 2);
        assertThat(second)
                .as("r3 shares r4's instant and is still on the next page")
                .extracting(WorkloadVerticalAction::getId).containsExactly(r3.getId(), r2.getId());

        List<WorkloadVerticalAction> third = repository.findActivityPage(1L, 10L, null,
                new WorkloadVerticalActionRepository.ActivityCursor(r2.getAppliedAt(), r2.getId()), 2);
        assertThat(third).extracting(WorkloadVerticalAction::getId).containsExactly(r1.getId());
    }

    // ---------------------------------------------------------------- the per-day summary's reads (FEAT-005)

    @Test
    void theSummaryScanReadsTheTenantsRowsInItsWindowNewestFirstWithoutTheJsonBodies() {
        repository.saveAndFlush(action(1L, 10L, "team-a", "before-window", "APPLIED", T1));
        WorkloadVerticalAction start = repository.saveAndFlush(action(1L, 10L, "team-a", "at-start", "APPLIED", T2));
        WorkloadVerticalAction hpa = action(1L, 10L, "team-b", "php-apache", "TEST_RUN", T3);
        hpa.setWorkloadKind("HorizontalPodAutoscaler");
        hpa.setDryRun(true);
        hpa.setAfterJson(null);
        hpa = repository.saveAndFlush(hpa);
        WorkloadVerticalAction end = action(1L, 10L, "team-a", "at-end", "NOT_CHANGED", T4);
        String proof = "{\"terminal\":\"DEFERRED\",\"reverted\":true,\"containers\":[{\"container\":\"encoder\","
                + "\"observedBefore\":{\"cpuRequestMillis\":10}}],\"pods\":[]}";
        end.setAfterJson(proof);
        end = repository.saveAndFlush(end);
        repository.saveAndFlush(action(1L, 10L, "team-a", "after-window", "APPLIED", T5));
        repository.saveAndFlush(action(2L, 10L, "team-a", "another-client", "APPLIED", T3));
        repository.saveAndFlush(action(1L, 11L, "team-a", "another-cluster", "APPLIED", T3));

        List<WorkloadVerticalActionRepository.SummaryRow> rows = repository.findSummaryRows(1L, 10L, T2, T4, 50);

        assertThat(rows)
                .as("both ends of the window are inside it; another tenant's and cluster's rows never are")
                .extracting(WorkloadVerticalActionRepository.SummaryRow::id)
                .containsExactly(end.getId(), hpa.getId(), start.getId());
        WorkloadVerticalActionRepository.SummaryRow newest = rows.get(0);
        assertThat(newest.appliedAt()).isEqualTo(T4);
        assertThat(newest.workloadKind()).isEqualTo("Deployment");
        assertThat(newest.outcome()).isEqualTo("NOT_CHANGED");
        assertThat(newest.dryRun()).isFalse();
        assertThat(newest.afterJsonStart())
                .as("only the start of after_json is read")
                .hasSize(WorkloadVerticalActionRepository.SUMMARY_AFTER_JSON_PREFIX)
                .isEqualTo(proof.substring(0, WorkloadVerticalActionRepository.SUMMARY_AFTER_JSON_PREFIX));
        assertThat(rows.get(1).workloadKind()).isEqualTo("HorizontalPodAutoscaler");
        assertThat(rows.get(1).dryRun()).isTrue();
        assertThat(rows.get(1).afterJsonStart()).isNull();
        assertThat(rows.get(2).afterJsonStart())
                .as("a short body is read whole")
                .isEqualTo("{\"cpuRequestMillis\":25}");

        assertThat(repository.findSummaryRows(1L, 10L, T2, T4, 2))
                .as("the scan is bounded: the newest rows up to the limit")
                .extracting(WorkloadVerticalActionRepository.SummaryRow::id)
                .containsExactly(end.getId(), hpa.getId());
    }

    @Test
    void theFirstAppliedAtIsTheTenantsOldestRowThatMatches() {
        repository.saveAndFlush(action(2L, 10L, "team-a", "another-client", "APPLIED", T1));
        repository.saveAndFlush(action(1L, 11L, "team-a", "another-cluster", "APPLIED", T1));
        repository.saveAndFlush(action(1L, 10L, "team-a", "api", "APPLIED", T2));
        WorkloadVerticalAction hpa = action(1L, 10L, "team-b", "php-apache", "APPLIED", T4);
        hpa.setWorkloadKind("HorizontalPodAutoscaler");
        repository.saveAndFlush(hpa);
        repository.saveAndFlush(action(1L, 10L, "team-a", "api", "APPLIED", T3));

        assertThat(repository.findFirstAppliedAt(1L, 10L, null)).contains(T2);
        assertThat(repository.findFirstAppliedAt(1L, 10L,
                WorkloadVerticalActionRepository.kindIs("HorizontalPodAutoscaler"))).contains(T4);
        assertThat(repository.findFirstAppliedAt(1L, 10L, WorkloadVerticalActionRepository.outcomeIn(
                List.of("TEST_RUN")))).isEmpty();
        assertThat(repository.findFirstAppliedAt(3L, 10L, null)).isEmpty();
    }

    private List<Long> ids(org.springframework.data.jpa.domain.Specification<WorkloadVerticalAction> filter) {
        return repository.findActivityPage(1L, 10L, filter, null, 50).stream()
                .map(WorkloadVerticalAction::getId).toList();
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
