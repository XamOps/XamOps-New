package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.domain.WorkloadVerticalPolicy;
import com.xammer.cloud.repository.WorkloadVerticalPolicyRepository;
import com.xammer.cloud.service.automation.workload.ApplyMethod;
import com.xammer.cloud.service.automation.workload.HysteresisBand;
import com.xammer.cloud.service.automation.workload.HpaView;
import com.xammer.cloud.service.automation.workload.NodeCapacity;
import com.xammer.cloud.service.automation.workload.PodNameRule;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.RpsSizingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadMode;
import com.xammer.cloud.service.automation.workload.WorkloadPolicy;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.access.EksClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.KubeconfigClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import com.xammer.cloud.service.automation.workload.cost.K8sCostProperties;
import com.xammer.cloud.service.automation.workload.cost.NodeRate;
import com.xammer.cloud.service.automation.workload.cost.NodeRateResolver;
import com.xammer.cloud.service.automation.workload.cost.WorkloadCostAttributionService;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.UpstreamVpaReader;
import com.xammer.cloud.service.automation.workload.policy.WorkloadPolicyService;
import com.xammer.cloud.service.automation.workload.usage.ContainerUsageAggregate;
import com.xammer.cloud.service.automation.workload.usage.NoMetricsSourceProvider;
import com.xammer.cloud.service.automation.workload.usage.PercentileSet;
import com.xammer.cloud.service.automation.workload.usage.RpsQuery;
import com.xammer.cloud.service.automation.workload.usage.UsageHistoryProvider;
import com.xammer.cloud.service.automation.workload.usage.UsageQuery;
import com.xammer.cloud.service.automation.workload.usage.UsageUnavailableException;
import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;
import com.xammer.cloud.service.automation.workload.inventory.WorkloadPods;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.Order;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read endpoint's payload: honest when gated, real when it is not, and never fabricated.
 */
class VerticalAutomationReadServiceTest {

    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-cluster", "ap-south-1");

    /** Inert account for the read signature; the cost feature is off in these fixtures. */
    private static final com.xammer.cloud.domain.CloudAccount ACCOUNT =
            new com.xammer.cloud.domain.CloudAccount("acct", "ext", "read-only", null);

    private static final long MI = 1024L * 1024L;
    private static final WorkloadRef API = new WorkloadRef("team-a", "Deployment", "overprovisioned-api",
            "dep-uid-1");

    @Test
    @DisplayName("with the master switch off: gated, empty, and NO provider is invoked")
    void theDefaultStateTouchesNothing() {
        RecordingProvider provider = new RecordingProvider();
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(false, false),
                new NoMetricsSourceProvider(), List.of(provider));

        VerticalEngineResponse response = service.read(ACCOUNT, TARGET, config(null));

        assertThat(response.engine().enabled()).isFalse();
        assertThat(response.engine().ready()).isFalse();
        assertThat(response.gateReasons())
                .contains(ReasonCode.CONTROLLER_NOT_READY.name(), ReasonCode.NO_METRICS_SOURCE.name());
        assertThat(response.workloads())
                .as("never a zero, a placeholder or an unevaluated workload")
                .isEmpty();

        assertThat(provider.opened)
                .as("the default state must make no cluster call at all")
                .isZero();
        assertThat(provider.supportsAsked)
                .as("not even a supports() probe, since no claim about access is made")
                .isZero();
    }

    @Test
    @DisplayName("with the switch off, the engine makes no claim about cluster access it has not checked")
    void unverifiedAccessIsReportedAsUnknownNotAsMisconfigured() {
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(false, false),
                new NoMetricsSourceProvider(), List.of(new RecordingProvider()));

        VerticalEngineResponse response = service.read(ACCOUNT, TARGET, config(null));

        // Guessing NOT_CONFIGURED would send a customer to fix an access entry that may already be
        // correct. Null means "not checked", which the UI's view model already types as nullable.
        assertThat(response.engine().clusterAccess()).isNull();
        assertThat(response.engine().executionPathDetail().direct().available()).isFalse();
        assertThat(response.engine().executionPathDetail().direct().reason()).isNull();
    }

    @Test
    @DisplayName("while gated, no cycle has run and the payload says so rather than inventing a timestamp")
    void lastCycleIsEmptyWhileGated() {
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(false, false),
                new NoMetricsSourceProvider(), List.of());

        VerticalEngineResponse.LastCycle lastCycle = service.read(ACCOUNT, TARGET, config(null)).engine().lastCycle();

        assertThat(lastCycle.at()).isNull();
        assertThat(lastCycle.evaluated()).isNull();
        assertThat(lastCycle.error()).isNull();
    }

    @Test
    @DisplayName("a live agent makes the execution path AGENT even with no direct access")
    void aLiveAgentIsTheExecutionPath() {
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(false, false),
                new NoMetricsSourceProvider(), List.of());

        VerticalEngineResponse response = service.read(ACCOUNT, TARGET, config(LocalDateTime.now().minusMinutes(2)));

        assertThat(response.engine().executionPath()).isEqualTo("AGENT");
        assertThat(response.engine().executionPathDetail().agent().available()).isTrue();
        assertThat(response.engine().executionPathDetail().agent().reason()).isNull();
        assertThat(response.engine().executionPathDetail().agent().lastSeenAt()).isNotNull();
    }

    @Test
    @DisplayName("a stale agent reuses the UI's existing AGENT_NOT_INSTALLED code")
    void aStaleAgentIsNotAnExecutionPath() {
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(false, false),
                new NoMetricsSourceProvider(), List.of());

        VerticalEngineResponse response = service.read(ACCOUNT, TARGET, config(LocalDateTime.now().minusHours(3)));

        assertThat(response.engine().executionPath()).isEqualTo("NONE");
        assertThat(response.engine().executionPathDetail().agent().available()).isFalse();
        assertThat(response.engine().executionPathDetail().agent().reason())
                .as("no new shared gate code is introduced for the agent channel")
                .isEqualTo("AGENT_NOT_INSTALLED");
    }

    @Test
    @DisplayName("with the switch on, a CLUSTER_UNREACHABLE provider maps to UNREACHABLE and a gate")
    void unreachableIsReportedAsSuch() {
        ClusterAccessProvider failing = failing(ClusterAccessException.Kind.CLUSTER_UNREACHABLE);
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(true, true),
                new NoMetricsSourceProvider(), List.of(failing));

        VerticalEngineResponse response = service.read(ACCOUNT, TARGET, config(null));

        assertThat(response.engine().clusterAccess()).isEqualTo("UNREACHABLE");
        assertThat(response.engine().executionPathDetail().direct().reason())
                .isEqualTo(ReasonCode.CLUSTER_UNREACHABLE.name());
        assertThat(response.gateReasons()).contains(ReasonCode.CLUSTER_UNREACHABLE.name());
        assertThat(response.gateReasons())
                .as("both flags are on, so readiness is not a gate")
                .doesNotContain(ReasonCode.CONTROLLER_NOT_READY.name());
    }

    @Test
    @DisplayName("provider selection follows declared precedence, not the order they were injected")
    void providerSelectionIsDecidedNotIncidental() {
        // Both predicates are true for a registered EKS cluster, so without a declared precedence
        // which provider served a cluster would depend on component-scan ordering.
        PrecedenceProvider eksPath = new EksPrecedenceProvider();
        PrecedenceProvider kubeconfigPath = new KubeconfigPrecedenceProvider();

        // Injected in the wrong order on purpose.
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(true, true),
                new NoMetricsSourceProvider(), List.of(kubeconfigPath, eksPath));

        service.read(ACCOUNT, TARGET, config(null));

        assertThat(eksPath.opened)
                .as("for a cluster reached through a connected AWS account, the customer's own role "
                        + "is the credential of record")
                .isEqualTo(1);
        assertThat(kubeconfigPath.opened).as("the kubeconfig path is the fallback").isZero();
    }

    @Test
    @DisplayName("the precedence lives on the production providers, not only in this test")
    void theProductionProvidersDeclareTheirPrecedence() {
        assertThat(EksClusterAccessProvider.PRECEDENCE)
                .isLessThan(KubeconfigClusterAccessProvider.PRECEDENCE);

        assertThat(EksClusterAccessProvider.class.getAnnotation(Order.class))
                .as("without @Order, Spring's injected list order is incidental again")
                .isNotNull()
                .extracting(Order::value)
                .isEqualTo(EksClusterAccessProvider.PRECEDENCE);
        assertThat(KubeconfigClusterAccessProvider.class.getAnnotation(Order.class))
                .isNotNull()
                .extracting(Order::value)
                .isEqualTo(KubeconfigClusterAccessProvider.PRECEDENCE);
    }

    @Test
    @DisplayName("with the switch on, CLUSTER_ACCESS_NOT_CONFIGURED maps to NOT_CONFIGURED and a gate")
    void misconfiguredAccessIsReportedAsSuch() {
        ClusterAccessProvider failing = failing(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(true, true),
                new NoMetricsSourceProvider(), List.of(failing));

        VerticalEngineResponse response = service.read(ACCOUNT, TARGET, config(null));

        assertThat(response.engine().clusterAccess()).isEqualTo("NOT_CONFIGURED");
        assertThat(response.gateReasons()).contains(ReasonCode.CLUSTER_ACCESS_NOT_CONFIGURED.name());
    }

    @Test
    @DisplayName("no reachable usage source means no workloads, whatever the switches say")
    void noUsageSourceMeansNoWorkloads() {
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(true, true),
                new NoMetricsSourceProvider(), List.of(opening()));

        VerticalEngineResponse response = service.read(ACCOUNT, TARGET, config(null));

        assertThat(response.gateReasons()).contains(ReasonCode.NO_METRICS_SOURCE.name());
        assertThat(response.workloads()).isEmpty();
        assertThat(response.engine().metricsSource().reachable()).isFalse();
        assertThat(response.engine().metricsSource().name()).isNull();
    }

    @Test
    @DisplayName("the direct-path probe is cached for a BROKEN cluster: a poll loop does not re-dial it")
    void aBrokenClusterIsNotReDialled() {
        // Opening the EKS provider means describeCluster, a token mint, a TLS handshake and a
        // namespaces GET. This endpoint is a UI poll target, so re-dialling an unroutable cluster
        // every poll would put an AWS call on the latency path of every refresh.
        RecordingProvider provider = new RecordingProvider();
        long[] now = {0L};
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(true, true),
                new NoMetricsSourceProvider(), List.of(provider), () -> now[0]);

        VerticalEngineResponse first = service.read(ACCOUNT, TARGET, config(null));
        now[0] += VerticalAutomationReadService.PROBE_TTL.toNanos() / 2;
        VerticalEngineResponse second = service.read(ACCOUNT, TARGET, config(null));

        assertThat(provider.opened).isEqualTo(1);
        assertThat(second.engine().clusterAccess())
                .as("the cached answer is the same answer, not a weaker one")
                .isEqualTo(first.engine().clusterAccess())
                .isEqualTo("UNREACHABLE");
        assertThat(second.gateReasons()).isEqualTo(first.gateReasons());
    }

    @Test
    @DisplayName("once the TTL has elapsed the cluster is probed again")
    void theCachedProbeExpires() {
        // A customer who fixes an access entry must see it on their next refresh, not on a restart.
        RecordingProvider provider = new RecordingProvider();
        long[] now = {0L};
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(true, true),
                new NoMetricsSourceProvider(), List.of(provider), () -> now[0]);

        service.read(ACCOUNT, TARGET, config(null));
        now[0] += VerticalAutomationReadService.PROBE_TTL.toNanos();
        service.read(ACCOUNT, TARGET, config(null));

        assertThat(provider.opened).isEqualTo(2);
    }

    @Test
    @DisplayName("two clusters do not share one cached probe")
    void theCacheIsPerCluster() {
        RecordingProvider provider = new RecordingProvider();
        long[] now = {0L};
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(true, true),
                new NoMetricsSourceProvider(), List.of(provider), () -> now[0]);

        service.read(ACCOUNT, TARGET, config(null));
        service.read(ACCOUNT, new ClusterTarget(7L, 43L, "102227938259", "another-cluster", "ap-south-1"),
                config(null));

        assertThat(provider.opened)
                .as("the key is client.id + cluster_config_id, so one cluster's result is never "
                        + "served for another")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the metrics-source probe is cached on the same TTL, not re-run on every poll")
    void theMetricsProbeIsCached() {
        CountingProvider usage = new CountingProvider();
        long[] now = {0L};
        VerticalAutomationReadService service = service(usage, emptyInventory(), VerticalReadServiceFixtures
                .noStoredPolicies(), "RECOMMEND", 24, () -> now[0]);

        service.read(ACCOUNT, TARGET, config(null));
        now[0] += VerticalAutomationReadService.PROBE_TTL.toNanos() / 2;
        service.read(ACCOUNT, TARGET, config(null));

        assertThat(usage.reachabilityProbes)
                .as("the probe is a real Prometheus query, so it is cached like the access probe")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- the real read path

    @Test
    @DisplayName("a reachable source is named, and the cycle that ran is reported as the real one")
    void aNamedSourceAndARealCycleAreReported() {
        VerticalAutomationReadService service = service(new FakeUsage(), inventory(overprovisionedApi()),
                VerticalReadServiceFixtures.noStoredPolicies(), "RECOMMEND", 24);

        VerticalEngineResponse response = service.read(ACCOUNT, TARGET, config(null));

        assertThat(response.engine().metricsSource().reachable()).isTrue();
        assertThat(response.engine().metricsSource().name()).isEqualTo("fake-prometheus");
        assertThat(response.gateReasons()).doesNotContain(ReasonCode.NO_METRICS_SOURCE.name());
        assertThat(response.engine().lastCycle().at())
                .as("a real cycle just ran, so lastCycle stops being all-null")
                .isEqualTo(VerticalReadServiceFixtures.CYCLE_AT);
        assertThat(response.engine().lastCycle().evaluated()).isEqualTo(1);
        assertThat(response.engine().lastCycle().error()).isNull();
    }

    @Test
    @DisplayName("a real workload maps to a real DTO, and no recommended field is ever 0")
    void aRealWorkloadIsEvaluated() {
        VerticalAutomationReadService service = service(new FakeUsage(), inventory(overprovisionedApi()),
                VerticalReadServiceFixtures.noStoredPolicies(), "RECOMMEND", 24);

        VerticalWorkloadDto dto = service.read(ACCOUNT, TARGET, config(null)).workloads().get(0);

        assertThat(dto.ref().namespace()).isEqualTo("team-a");
        assertThat(dto.ref().name()).isEqualTo("overprovisioned-api");
        assertThat(dto.replicas()).isEqualTo(2);
        assertThat(dto.skipReason()).isNull();
        assertThat(dto.provenance().usageSource()).isEqualTo("fake-prometheus");
        assertThat(dto.provenance().sampleCoverage()).isEqualTo(1.0);

        VerticalWorkloadDto.ContainerDto container = dto.containers().get(0);
        assertThat(container.name()).isEqualTo("idler");
        assertThat(container.current().cpuRequestM()).isEqualTo(200);
        assertThat(container.recommended()).isNotNull();
        assertThat(container.recommended().cpuRequestM())
                .as("the floor clamps the decrease; a 0m or 1m request is the measured failure this "
                        + "engine exists to prevent")
                .isEqualTo(WorkloadPolicyDefaults.CPU_FLOOR_MILLIS);
        assertThat(List.of(container.recommended().cpuRequestM(), container.recommended().memRequestMi()))
                .allSatisfy(value -> assertThat(value.longValue()).isPositive());
    }

    @Test
    @DisplayName("a DEFAULTS policy picks up the configured window and mode; a stored row does not")
    void theConfiguredDefaultsApplyOnlyToTheNoRowCase() {
        FakeUsage usage = new FakeUsage();
        VerticalAutomationReadService withDefaults = service(usage, inventory(overprovisionedApi()),
                VerticalReadServiceFixtures.noStoredPolicies(), "RECOMMEND", 72);

        VerticalWorkloadDto fromDefaults = withDefaults.read(ACCOUNT, TARGET, config(null)).workloads().get(0);

        assertThat(fromDefaults.provenance().policySource()).isEqualTo("DEFAULTS");
        assertThat(fromDefaults.provenance().windowHours()).isEqualTo(72);
        assertThat(fromDefaults.policy().window()).isEqualTo("72h");
        assertThat(fromDefaults.policy().mode()).isEqualTo("RECOMMEND");
        assertThat(usage.lastQuery.windowHours())
                .as("the window the provider was asked for is the window that was reported")
                .isEqualTo(72);

        FakeUsage storedUsage = new FakeUsage();
        WorkloadPolicy row = storedRow(WorkloadMode.RECOMMEND, 24);
        VerticalAutomationReadService withRow = service(storedUsage, inventory(overprovisionedApi()),
                VerticalReadServiceFixtures.storedPolicies(
                        Map.of(WorkloadPolicyService.key(API), row)), "RECOMMEND", 72);

        VerticalWorkloadDto fromRow = withRow.read(ACCOUNT, TARGET, config(null)).workloads().get(0);

        assertThat(fromRow.provenance().policySource())
                .as("a stored row is never overridden, or the reported provenance would be a lie")
                .isEqualTo("WORKLOAD_ROW");
        assertThat(fromRow.provenance().windowHours()).isEqualTo(24);
        assertThat(storedUsage.lastQuery.windowHours()).isEqualTo(24);
    }

    @Test
    @DisplayName("after one Apply under RECOMMEND, endpoint 1 still reports RECOMMEND and keeps publishing (report 4c)")
    void anApplyDoesNotTurnTheWorkloadOff() {
        // Real policy service over a mocked table, so the row is the one recordApplied writes and
        // the read path maps it back -- the round trip that turned a workload OFF on the live run.
        WorkloadVerticalPolicyRepository table = mock(WorkloadVerticalPolicyRepository.class);
        WorkloadPolicyService policies = new WorkloadPolicyService(table);
        VerticalMetricsProperties uat = VerticalReadServiceFixtures.metricsProperties("RECOMMEND", 72);
        VerticalWorkloadDto before = service(new FakeUsage(), inventory(overprovisionedApi()),
                VerticalReadServiceFixtures.noStoredPolicies(), "RECOMMEND", 72)
                .read(ACCOUNT, TARGET, config(null)).workloads().get(0);

        // Applied two hours before this cycle, so the 60-minute cooldown has elapsed.
        policies.recordApplied(7L, 42L, API, uat.defaultsPolicy(),
                VerticalReadServiceFixtures.CYCLE_AT.minus(Duration.ofHours(2)));
        ArgumentCaptor<WorkloadVerticalPolicy> written = ArgumentCaptor.forClass(WorkloadVerticalPolicy.class);
        verify(table).save(written.capture());
        when(table.findByClusterConfigId(42L)).thenReturn(List.of(written.getValue()));

        VerticalWorkloadDto after = service(new FakeUsage(), inventory(overprovisionedApi()),
                policies, "RECOMMEND", 72).read(ACCOUNT, TARGET, config(null)).workloads().get(0);

        assertThat(after.policy()).as("the same mode and policy values as before the Apply")
                .isEqualTo(before.policy());
        assertThat(after.policy().mode()).isEqualTo("RECOMMEND");
        assertThat(after.provenance().windowHours()).isEqualTo(72);
        assertThat(after.skipReason()).as("never POLICY_OFF").isNull();
        assertThat(after.containers()).hasSize(1);
        assertThat(after.containers().get(0).recommended())
                .as("recommendations keep being published once the cooldown has passed")
                .isEqualTo(before.containers().get(0).recommended())
                .isNotNull();
    }

    @Test
    @DisplayName("the usage query carries the owner-resolved pods, never a name prefix")
    void theUsageQueryIsOwnerScoped() {
        FakeUsage usage = new FakeUsage();
        service(usage, inventory(overprovisionedApi()), VerticalReadServiceFixtures.noStoredPolicies(),
                "RECOMMEND", 24).read(ACCOUNT, TARGET, config(null));

        UsageQuery query = usage.lastQuery;
        assertThat(query.ref().namespace()).isEqualTo("team-a");
        assertThat(query.podNames())
                .containsExactly("overprovisioned-api-aaa-1", "overprovisioned-api-aaa-2");
        assertThat(query.containerNames()).containsExactly("idler");
        assertThat(query.percentile()).isEqualTo(WorkloadPolicyDefaults.PERCENTILE);
    }

    @Test
    @DisplayName("the usage query also carries the pod-name shapes, so history survives a rollout")
    void theUsageQueryCarriesTheHistoricalPodShapes() {
        // Orchestrator addendum A: with the current pod names alone, a rollout, eviction or drain
        // resets a workload's observed history to zero, and at MIN_SAMPLE_COVERAGE 0.5 over a 72h
        // window that means 36h of uninterrupted pod life before anything can be recommended.
        FakeUsage usage = new FakeUsage();
        service(usage, inventory(overprovisionedApi()), VerticalReadServiceFixtures.noStoredPolicies(),
                "RECOMMEND", 24).read(ACCOUNT, TARGET, config(null));

        assertThat(usage.lastQuery.podNameRules())
                .containsExactly(PodNameRule.replicaSetPods("overprovisioned-api-aaa"),
                        PodNameRule.replicaSetPods("overprovisioned-api-old"));
    }

    @Test
    @DisplayName("INSUFFICIENT_HISTORY publishes the workload with a skip reason and NO numbers")
    void insufficientHistoryIsAWaitingState() {
        VerticalAutomationReadService service = service(
                new FailingUsage(UsageUnavailableException.Kind.INSUFFICIENT_HISTORY),
                inventory(overprovisionedApi()), VerticalReadServiceFixtures.noStoredPolicies(),
                "RECOMMEND", 24);

        VerticalEngineResponse response = service.read(ACCOUNT, TARGET, config(null));

        assertThat(response.workloads()).hasSize(1);
        VerticalWorkloadDto dto = response.workloads().get(0);
        assertThat(dto.skipReason()).isEqualTo(ReasonCode.INSUFFICIENT_HISTORY.name());
        assertThat(dto.containers())
                .as("a waiting workload carries no numbers at all, not zeros and not blanks")
                .isEmpty();
        assertThat(dto.autoApplyEligible()).isFalse();
        assertThat(dto.provenance().windowStart()).isNull();
        assertThat(dto.provenance().windowEnd()).isNull();
        assertThat(dto.provenance().sampleCoverage()).isNull();
        assertThat(dto.provenance().windowHours())
                .as("the policy's window is a fact about configuration, not a measurement")
                .isEqualTo(24);
        assertThat(response.gateReasons())
                .as("one workload's history is not a cluster-wide gate")
                .doesNotContain(ReasonCode.INSUFFICIENT_HISTORY.name());
    }

    @Test
    @DisplayName("a cluster-wide usage failure leaves workloads empty and names the gate")
    void aClusterWideUsageFailureIsAGate() {
        VerticalAutomationReadService service = service(
                new FailingUsage(UsageUnavailableException.Kind.NO_METRICS_SOURCE),
                inventory(overprovisionedApi()), VerticalReadServiceFixtures.noStoredPolicies(),
                "RECOMMEND", 24);

        VerticalEngineResponse response = service.read(ACCOUNT, TARGET, config(null));

        assertThat(response.workloads())
                .as("a partial list the reader cannot tell is partial is worse than none")
                .isEmpty();
        assertThat(response.gateReasons()).contains(ReasonCode.NO_METRICS_SOURCE.name());
        assertThat(response.engine().lastCycle().at()).isEqualTo(VerticalReadServiceFixtures.CYCLE_AT);
        assertThat(response.engine().lastCycle().error()).isNotNull();
    }

    @Test
    @DisplayName("an unreadable node capacity is a cluster-wide failure, not a capacity of zero")
    void withoutNodeCapacityNothingIsPublished() {
        ClusterWorkloadInventory inventory = mock(ClusterWorkloadInventory.class);
        when(inventory.read(any())).thenReturn(
                new ClusterWorkloadInventory.Inventory(List.of(overprovisionedApi()), null));
        VerticalAutomationReadService service = service(new FakeUsage(), inventory,
                VerticalReadServiceFixtures.noStoredPolicies(), "RECOMMEND", 24);

        VerticalEngineResponse response = service.read(ACCOUNT, TARGET, config(null));

        assertThat(response.workloads()).isEmpty();
        assertThat(response.engine().lastCycle().error()).contains("allocatable");
    }

    @Test
    @DisplayName("a workload with no pods is a waiting state, not a measurement of nothing")
    void aWorkloadWithNoPodsIsNotMeasured() {
        ClusterWorkloadInventory.Workload noPods = new ClusterWorkloadInventory.Workload(API, 2, false,
                false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("idler", burstable(), Map.of())),
                new WorkloadPods(API, List.of()));
        VerticalAutomationReadService service = service(new FakeUsage(), inventory(noPods),
                VerticalReadServiceFixtures.noStoredPolicies(), "RECOMMEND", 24);

        VerticalWorkloadDto dto = service.read(ACCOUNT, TARGET, config(null)).workloads().get(0);

        assertThat(dto.skipReason()).isEqualTo(ReasonCode.INSUFFICIENT_HISTORY.name());
        assertThat(dto.containers()).isEmpty();
    }

    @Test
    @DisplayName("an unreadable upstream VPA is a finding, never a fabricated comparison")
    void anUnreadableVpaIsAFinding() {
        UpstreamVpaReader vpaReader = mock(UpstreamVpaReader.class);
        when(vpaReader.read(any())).thenReturn(UpstreamVpaReader.View.notReadable());
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(true, true),
                VerticalReadServiceFixtures.metricsProperties("RECOMMEND", 24),
                new FakeUsage(), List.of(opening()), inventory(overprovisionedApi()), vpaReader,
                VerticalReadServiceFixtures.noStoredPolicies(), () -> 0L);

        VerticalWorkloadDto dto = service.read(ACCOUNT, TARGET, config(null)).workloads().get(0);

        assertThat(dto.findings()).contains(UpstreamVpaReader.FINDING_NOT_READABLE);
        assertThat(dto.containers().get(0).upstreamVpa()).isNull();
    }

    @Test
    @DisplayName("a readable upstream VPA target is published beside ours, with its own source")
    void aReadableVpaTargetIsPublished() {
        UpstreamVpaReader vpaReader = mock(UpstreamVpaReader.class);
        when(vpaReader.read(any())).thenReturn(new UpstreamVpaReader.View(
                Map.of(UpstreamVpaReader.key(API),
                        Map.of("idler", new UpstreamVpaReader.ContainerTarget(26, 262144000L, null))),
                Map.of(), true));
        VerticalAutomationReadService service = VerticalReadServiceFixtures.service(
                new WorkloadAutoscalingProperties(true, true),
                VerticalReadServiceFixtures.metricsProperties("RECOMMEND", 24),
                new FakeUsage(), List.of(opening()), inventory(overprovisionedApi()), vpaReader,
                VerticalReadServiceFixtures.noStoredPolicies(), () -> 0L);

        VerticalWorkloadDto dto = service.read(ACCOUNT, TARGET, config(null)).workloads().get(0);
        VerticalWorkloadDto.ContainerDto container = dto.containers().get(0);

        assertThat(container.upstreamVpa()).isNotNull();
        assertThat(container.upstreamVpa().cpuRequestM()).isEqualTo(26);
        assertThat(container.upstreamVpa().memRequestMi()).isEqualTo(250);
        assertThat(container.upstreamVpa().source()).isEqualTo("vpa");
        assertThat(dto.findings()).doesNotContain(UpstreamVpaReader.FINDING_NOT_READABLE);
    }

    // ---------------------------------------------------------------- F3: RPS replica sizing

    @Test
    @DisplayName("feature off (the default): every workload is NO_RPS_SOURCE and nothing auto-applies")
    void rpsFeatureOffIsNoRpsSource() {
        FakeUsage usage = new FakeUsage();
        usage.observedRps = java.util.OptionalDouble.of(1200.0);   // even with RPS, off => no source
        VerticalAutomationReadService service = service(usage, inventory(overprovisionedApi()),
                VerticalReadServiceFixtures.noStoredPolicies(), "RECOMMEND", 24,
                VerticalReadServiceFixtures.rpsOff());

        VerticalWorkloadDto.HorizontalDto horizontal =
                service.read(ACCOUNT, TARGET, config(null)).workloads().get(0).horizontal();

        assertThat(horizontal.rpsReplicas()).isNull();
        assertThat(horizontal.rpsReason()).isEqualTo("NO_RPS_SOURCE");
        assertThat(horizontal.rpsFormula()).isNull();
        assertThat(horizontal.autoApplyEligible()).isFalse();
    }

    @Test
    @DisplayName("RPS present but no configured capacity is NO_CAPACITY_BASIS, never a self-derived number")
    void rpsPresentWithoutCapacityIsNoCapacityBasis() {
        FakeUsage usage = new FakeUsage();
        usage.observedRps = java.util.OptionalDouble.of(1200.0);
        VerticalAutomationReadService service = service(usage, inventory(overprovisionedApi()),
                VerticalReadServiceFixtures.noStoredPolicies(), "RECOMMEND", 24,
                RpsSizingProperties.of(true, "http_requests_total", 0.70, null));

        VerticalWorkloadDto.HorizontalDto horizontal =
                service.read(ACCOUNT, TARGET, config(null)).workloads().get(0).horizontal();

        assertThat(horizontal.rpsReplicas()).isNull();
        assertThat(horizontal.rpsReason()).isEqualTo("NO_CAPACITY_BASIS");
        assertThat(horizontal.rpsFormula()).isNull();
    }

    @Test
    @DisplayName("RPS + a real capacity is a sized count, unbounded with a note when there is no HPA range")
    void rpsWithCapacityButNoHpaIsSizedUnbounded() {
        FakeUsage usage = new FakeUsage();
        usage.observedRps = java.util.OptionalDouble.of(1200.0);   // no HPA on overprovisionedApi()
        VerticalAutomationReadService service = service(usage, inventory(overprovisionedApi()),
                VerticalReadServiceFixtures.noStoredPolicies(), "RECOMMEND", 24,
                RpsSizingProperties.of(true, "http_requests_total", 0.70, 50.0));

        VerticalWorkloadDto.HorizontalDto horizontal =
                service.read(ACCOUNT, TARGET, config(null)).workloads().get(0).horizontal();

        // ceil(1200 / 50 / 0.70) = ceil(34.28...) = 35, no HPA range so unbounded.
        assertThat(horizontal.rpsReplicas()).isEqualTo(35);
        assertThat(horizontal.rpsReason()).isEqualTo("RPS_SIZED");
        assertThat(horizontal.rpsFormula()).contains("(no HPA range)");
        assertThat(horizontal.autoApplyEligible()).isFalse();
    }

    @Test
    @DisplayName("RPS + capacity + an HPA range clamps the sized count to [min,max]")
    void rpsWithCapacityAndHpaIsClamped() {
        FakeUsage usage = new FakeUsage();
        usage.observedRps = java.util.OptionalDouble.of(1200.0);
        VerticalAutomationReadService service = service(usage, inventory(hpaBackedApi(2, 10)),
                VerticalReadServiceFixtures.noStoredPolicies(), "RECOMMEND", 24,
                RpsSizingProperties.of(true, "http_requests_total", 0.70, 50.0));

        VerticalWorkloadDto.HorizontalDto horizontal =
                service.read(ACCOUNT, TARGET, config(null)).workloads().get(0).horizontal();

        // raw 35 clamped to the HPA's [2,10] -> 10.
        assertThat(horizontal.rpsReplicas()).isEqualTo(10);
        assertThat(horizontal.rpsReason()).isEqualTo("RPS_SIZED");
        assertThat(horizontal.rpsFormula()).contains("clamped to HPA [2,10]");
    }

    @Test
    @DisplayName("the RPS read is scoped to the workload's own pods, window and offset")
    void theRpsQueryIsOwnerScoped() {
        FakeUsage usage = new FakeUsage();
        usage.observedRps = java.util.OptionalDouble.of(1200.0);
        service(usage, inventory(overprovisionedApi()), VerticalReadServiceFixtures.noStoredPolicies(),
                "RECOMMEND", 24, RpsSizingProperties.of(true, "http_requests_total", 0.70, 50.0))
                .read(ACCOUNT, TARGET, config(null));

        RpsQuery query = usage.lastRpsQuery;
        assertThat(query).isNotNull();
        assertThat(query.ref().namespace()).isEqualTo("team-a");
        assertThat(query.podNames())
                .containsExactly("overprovisioned-api-aaa-1", "overprovisioned-api-aaa-2");
        assertThat(query.containerNames()).containsExactly("idler");
        assertThat(query.windowHours()).isEqualTo(24);
        assertThat(query.offsetSeconds()).isEqualTo(120);
        assertThat(query.metricExpr()).isEqualTo("http_requests_total");
    }

    // ---------------------------------------------------------------- FEAT-001: cost roll-ups

    @Test
    @DisplayName("flag ON: nodeCosts is the FULL fleet — one entry per node, including an idle node")
    void nodeCostsCoverTheWholeFleetIncludingAnIdleNode() {
        // Two workloads on PRICED_NODE in team-a; IDLE_NODE hosts no evaluated pod but is in the fleet.
        StubResolver resolver = new StubResolver();
        VerticalAutomationReadService service = costService(resolver, costInventory(), K8sCostProperties.enabledDefaults());

        var cost = service.read(ACCOUNT, TARGET, config(null)).cost();

        assertThat(cost).isNotNull();
        assertThat(cost.nodeCosts()).as("the roll-up is the full fleet, not the pod-hosting subset")
                .extracting(c -> c.nodeName())
                .containsExactlyInAnyOrder(PRICED_NODE, IDLE_NODE, FARGATE_NODE);

        var priced = cost.nodeCosts().stream().filter(c -> c.nodeName().equals(PRICED_NODE)).findFirst().orElseThrow();
        // $0.096/hr × 730 = 70.08 (the KNOWN rate).
        assertThat(priced.monthlyCost()).isCloseTo(70.08, org.assertj.core.data.Offset.offset(0.001));
        assertThat(priced.source()).isEqualTo("PRICE_LIST");
        assertThat(priced.reason()).isNull();

        var idle = cost.nodeCosts().stream().filter(c -> c.nodeName().equals(IDLE_NODE)).findFirst().orElseThrow();
        // The idle node hosts no evaluated pod, so it was never in the attribution map; it is still
        // priced for the fleet at the same KNOWN rate.
        assertThat(idle.monthlyCost()).isCloseTo(70.08, org.assertj.core.data.Offset.offset(0.001));

        var fargate = cost.nodeCosts().stream().filter(c -> c.nodeName().equals(FARGATE_NODE)).findFirst().orElseThrow();
        assertThat(fargate.monthlyCost()).as("an unpriceable node is a dash + reason, never 0").isNull();
        assertThat(fargate.reason()).isEqualTo("FARGATE_UNPRICEABLE");

        // Resolver calls: PRICED_NODE once (during attribution, since it hosts an evaluated pod) and
        // IDLE_NODE once (the only genuinely-missing priceable node the fleet step fills). The fleet
        // step REUSES the already-resolved PRICED_NODE rather than resolving it again, and the Fargate
        // node is short-circuited without any resolve. So exactly 2, never 3+.
        assertThat(resolver.calls)
                .as("the fleet step reuses already-resolved rates and never resolves Fargate")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("flag ON: namespaceCosts group by ref().namespace(), summing only priceable workloads")
    void namespaceCostsGroupByRefNamespace() {
        StubResolver resolver = new StubResolver();
        VerticalAutomationReadService service = costService(resolver, costInventory(), K8sCostProperties.enabledDefaults());

        var cost = service.read(ACCOUNT, TARGET, config(null)).cost();

        var teamA = cost.namespaceCosts().stream().filter(n -> n.namespace().equals("team-a")).findFirst().orElseThrow();
        // team-a: one workload on PRICED_NODE (priceable) + one on FARGATE_NODE (unpriceable).
        assertThat(teamA.pricedWorkloads()).isEqualTo(1);
        assertThat(teamA.unpricedWorkloads()).isEqualTo(1);
        assertThat(teamA.monthlyCost()).as("sums only the priceable workload").isNotNull().isGreaterThan(0.0);

        var teamB = cost.namespaceCosts().stream().filter(n -> n.namespace().equals("team-b")).findFirst().orElseThrow();
        // team-b: its one workload runs on FARGATE_NODE => all-unpriceable namespace.
        assertThat(teamB.pricedWorkloads()).isZero();
        assertThat(teamB.monthlyCost()).as("all-unpriceable namespace is null, never 0").isNull();
    }

    @Test
    @DisplayName("flag ON, all nodes unpriceable: clusterMonthlyCost == null WITH a full all-dash fleet")
    void allUnpriceableHasNullClusterCostButFullFleet() {
        // Every workload runs on FARGATE_NODE; the fleet is all three nodes, each unpriceable.
        StubResolver resolver = new StubResolver();
        VerticalAutomationReadService service = costService(resolver, allUnpriceableInventory(),
                K8sCostProperties.enabledDefaults());

        var cost = service.read(ACCOUNT, TARGET, config(null)).cost();

        assertThat(cost).as("flag ON => cost is not null even when nothing is priceable").isNotNull();
        assertThat(cost.clusterMonthlyCost()).as("nothing priceable => null, never 0").isNull();
        assertThat(cost.monthlySavingsUnavailableReason()).isNotNull();
        assertThat(cost.nodeCosts()).as("the fleet list is non-null and full even when all-dash")
                .hasSize(1);
        assertThat(cost.nodeCosts()).allSatisfy(c -> {
            assertThat(c.monthlyCost()).isNull();
            assertThat(c.reason()).isEqualTo("FARGATE_UNPRICEABLE");
        });
    }

    @Test
    @DisplayName("flag OFF: cost == null and the resolver is never called")
    void flagOffMakesNoResolverCallAndNullCost() {
        StubResolver resolver = new StubResolver();
        VerticalAutomationReadService service = costService(resolver, costInventory(), K8sCostProperties.disabled());

        var cost = service.read(ACCOUNT, TARGET, config(null)).cost();

        assertThat(cost).as("flag OFF => cost is null; nodeCosts/namespaceCosts never built").isNull();
        assertThat(resolver.calls).as("flag OFF => no AWS/resolver call at all").isZero();
    }

    @Test
    @DisplayName("withRollups identity: the attached lists ride a summary whose Σ fields are preserved")
    void withRollupsPreservesTheSummaryFields() {
        StubResolver resolver = new StubResolver();
        VerticalAutomationReadService service = costService(resolver, costInventory(), K8sCostProperties.enabledDefaults());

        var cost = service.read(ACCOUNT, TARGET, config(null)).cost();

        // What summary(...) would have computed from the same per-workload costs:
        var summaryOnly = new WorkloadCostAttributionService(K8sCostProperties.enabledDefaults())
                .summary(service.read(ACCOUNT, TARGET, config(null)).workloads().stream()
                        .map(VerticalWorkloadDto::cost).toList());

        assertThat(cost.currency()).isEqualTo(summaryOnly.currency());
        assertThat(cost.hoursPerMonth()).isEqualTo(summaryOnly.hoursPerMonth());
        assertThat(cost.clusterMonthlyCost()).isEqualTo(summaryOnly.clusterMonthlyCost());
        assertThat(cost.pricedWorkloads()).isEqualTo(summaryOnly.pricedWorkloads());
        assertThat(cost.unpricedWorkloads()).isEqualTo(summaryOnly.unpricedWorkloads());
        assertThat(cost.nodeCosts()).as("the fleet roll-up is attached by withRollups").isNotNull();
        assertThat(cost.namespaceCosts()).as("the namespace roll-up is attached by withRollups").isNotNull();
    }

    @Test
    @DisplayName("a null monthlyCost serializes to JSON null, never 0")
    void nullMonthlyCostSerializesAsNull() throws Exception {
        StubResolver resolver = new StubResolver();
        VerticalAutomationReadService service = costService(resolver, costInventory(), K8sCostProperties.enabledDefaults());

        var cost = service.read(ACCOUNT, TARGET, config(null)).cost();
        var fargate = cost.nodeCosts().stream().filter(c -> c.nodeName().equals(FARGATE_NODE)).findFirst().orElseThrow();

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(fargate);
        assertThat(json).contains("\"monthlyCost\":null");
        assertThat(json).doesNotContain("\"monthlyCost\":0");
    }

    // ---------------------------------------------------------------- fixtures

    private VerticalAutomationReadService service(UsageHistoryProvider usage,
                                                  ClusterWorkloadInventory inventory,
                                                  WorkloadPolicyService policies,
                                                  String defaultMode, int defaultWindowHours) {
        return service(usage, inventory, policies, defaultMode, defaultWindowHours, () -> 0L);
    }

    private VerticalAutomationReadService service(UsageHistoryProvider usage,
                                                  ClusterWorkloadInventory inventory,
                                                  WorkloadPolicyService policies,
                                                  String defaultMode, int defaultWindowHours,
                                                  RpsSizingProperties rps) {
        UpstreamVpaReader vpaReader = mock(UpstreamVpaReader.class);
        when(vpaReader.read(any())).thenReturn(UpstreamVpaReader.View.none());
        return VerticalReadServiceFixtures.service(new WorkloadAutoscalingProperties(true, true),
                VerticalReadServiceFixtures.metricsProperties(defaultMode, defaultWindowHours),
                usage, List.of(opening()), inventory, vpaReader, policies, rps, () -> 0L);
    }

    /** The reference workload with a CPU HPA targeting it, for the RPS clamp fixtures. */
    private static ClusterWorkloadInventory.Workload hpaBackedApi(int minReplicas, int maxReplicas) {
        HpaView hpa = HpaView.cpuHpa("overprovisioned-api", API, 50, minReplicas, maxReplicas);
        return new ClusterWorkloadInventory.Workload(API, 2, false, false, hpa, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("idler", burstable(), Map.of())),
                new WorkloadPods(API, List.of(
                        new WorkloadPods.Pod("overprovisioned-api-aaa-1", "pod-uid-1"),
                        new WorkloadPods.Pod("overprovisioned-api-aaa-2", "pod-uid-2")),
                        List.of(PodNameRule.replicaSetPods("overprovisioned-api-aaa"),
                                PodNameRule.replicaSetPods("overprovisioned-api-old"))));
    }

    private VerticalAutomationReadService service(UsageHistoryProvider usage,
                                                  ClusterWorkloadInventory inventory,
                                                  WorkloadPolicyService policies,
                                                  String defaultMode, int defaultWindowHours,
                                                  java.util.function.LongSupplier nanoClock) {
        UpstreamVpaReader vpaReader = mock(UpstreamVpaReader.class);
        when(vpaReader.read(any())).thenReturn(UpstreamVpaReader.View.none());
        return VerticalReadServiceFixtures.service(new WorkloadAutoscalingProperties(true, true),
                VerticalReadServiceFixtures.metricsProperties(defaultMode, defaultWindowHours),
                usage, List.of(opening()), inventory, vpaReader, policies, nanoClock);
    }

    private static ClusterWorkloadInventory inventory(ClusterWorkloadInventory.Workload... workloads) {
        ClusterWorkloadInventory inventory = mock(ClusterWorkloadInventory.class);
        when(inventory.read(any())).thenReturn(new ClusterWorkloadInventory.Inventory(
                List.of(workloads), NodeCapacity.t3Medium()));
        return inventory;
    }

    private static ClusterWorkloadInventory emptyInventory() {
        return inventory();
    }

    /** The measured reference workload: 200m/256Mi requested, almost nothing used. */
    private static ClusterWorkloadInventory.Workload overprovisionedApi() {
        return new ClusterWorkloadInventory.Workload(API, 2, false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("idler", burstable(), Map.of())),
                new WorkloadPods(API, List.of(
                        new WorkloadPods.Pod("overprovisioned-api-aaa-1", "pod-uid-1"),
                        new WorkloadPods.Pod("overprovisioned-api-aaa-2", "pod-uid-2")),
                        List.of(PodNameRule.replicaSetPods("overprovisioned-api-aaa"),
                                PodNameRule.replicaSetPods("overprovisioned-api-old"))));
    }

    private static ResourceEnvelope burstable() {
        return new ResourceEnvelope(200, 500, 256 * MI, 512 * MI);
    }

    // ---- FEAT-001 cost fixtures ------------------------------------------------------------

    private static final long GI = 1024L * 1024 * 1024;
    private static final String PRICED_NODE = "ip-priced";
    private static final String IDLE_NODE = "ip-idle";
    private static final String FARGATE_NODE = "fargate-xyz";
    /** The KNOWN on-demand rate the stub resolver returns, so per-node/cluster $ are assertable. */
    private static final double KNOWN_USD_PER_HOUR = 0.096;

    /** A real read service with the cost feature on, a real attribution service, and a stub resolver. */
    private VerticalAutomationReadService costService(StubResolver resolver,
                                                      ClusterWorkloadInventory inventory,
                                                      K8sCostProperties cost) {
        UpstreamVpaReader vpaReader = mock(UpstreamVpaReader.class);
        when(vpaReader.read(any())).thenReturn(UpstreamVpaReader.View.none());
        return VerticalReadServiceFixtures.costService(new WorkloadAutoscalingProperties(true, true),
                VerticalReadServiceFixtures.metricsProperties("RECOMMEND", 24), new FakeUsage(),
                List.of(opening()), inventory, vpaReader, VerticalReadServiceFixtures.noStoredPolicies(),
                resolver.resolver, cost, () -> 0L);
    }

    /** m5.large node facts: 2 vCPU / 8 GiB, 2000m / 8Gi allocatable — priceable when the rate is. */
    private static ClusterWorkloadInventory.NodeFacts m5Large() {
        return new ClusterWorkloadInventory.NodeFacts("m5.large", "on-demand", false, "ap-south-1a",
                false, 2.0, 8.0, 2000, 8 * GI);
    }

    /** A Fargate node: unpriceable per instance type (fargate == true). */
    private static ClusterWorkloadInventory.NodeFacts fargateFacts() {
        return new ClusterWorkloadInventory.NodeFacts(null, null, true, "ap-south-1a", true, null, null,
                null, null);
    }

    private static ClusterWorkloadInventory.Workload workloadOn(WorkloadRef ref, String nodeName) {
        // Two replicas, so the engine does not gate it as SINGLE_REPLICA and a real decision (with
        // containers) is produced for the cost attribution to price.
        return new ClusterWorkloadInventory.Workload(ref, 2, false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("app",
                        new ResourceEnvelope(1000, 1000, 4L * GI, 4L * GI), Map.of())),
                new WorkloadPods(ref, List.of(
                        new WorkloadPods.Pod(ref.name() + "-0", "uid-" + ref.name() + "-0", true, nodeName),
                        new WorkloadPods.Pod(ref.name() + "-1", "uid-" + ref.name() + "-1", true, nodeName))));
    }

    /**
     * A fleet of three nodes: PRICED_NODE and IDLE_NODE are m5.large (priceable), FARGATE_NODE is
     * Fargate. team-a has one workload on PRICED_NODE (priceable) and one on FARGATE_NODE
     * (unpriceable); team-b has one workload on FARGATE_NODE. IDLE_NODE hosts no evaluated pod.
     */
    private static ClusterWorkloadInventory costInventory() {
        ClusterWorkloadInventory.Workload a1 = workloadOn(
                new WorkloadRef("team-a", "Deployment", "priced-api", "uid-a1"), PRICED_NODE);
        ClusterWorkloadInventory.Workload a2 = workloadOn(
                new WorkloadRef("team-a", "Deployment", "fargate-api", "uid-a2"), FARGATE_NODE);
        ClusterWorkloadInventory.Workload b1 = workloadOn(
                new WorkloadRef("team-b", "Deployment", "batch", "uid-b1"), FARGATE_NODE);
        Map<String, ClusterWorkloadInventory.NodeFacts> nodes = new java.util.LinkedHashMap<>();
        nodes.put(PRICED_NODE, m5Large());
        nodes.put(IDLE_NODE, m5Large());
        nodes.put(FARGATE_NODE, fargateFacts());
        return costInventoryOf(List.of(a1, a2, b1), nodes);
    }

    /** Every workload on the Fargate node, and a single-node Fargate fleet. */
    private static ClusterWorkloadInventory allUnpriceableInventory() {
        ClusterWorkloadInventory.Workload a1 = workloadOn(
                new WorkloadRef("team-a", "Deployment", "fargate-api", "uid-a1"), FARGATE_NODE);
        Map<String, ClusterWorkloadInventory.NodeFacts> nodes =
                Map.of(FARGATE_NODE, fargateFacts());
        return costInventoryOf(List.of(a1), nodes);
    }

    private static ClusterWorkloadInventory costInventoryOf(
            List<ClusterWorkloadInventory.Workload> workloads,
            Map<String, ClusterWorkloadInventory.NodeFacts> nodesByName) {
        ClusterWorkloadInventory inventory = mock(ClusterWorkloadInventory.class);
        when(inventory.read(any())).thenReturn(new ClusterWorkloadInventory.Inventory(
                workloads, NodeCapacity.t3Medium(), nodesByName));
        return inventory;
    }

    /**
     * A {@link NodeRateResolver} that returns the KNOWN on-demand rate for whatever instance type it
     * is asked for, counting the calls so the "resolve at most once per distinct missing node shape"
     * contract is assertable. A Mockito stub rather than a subclass, since the resolver's
     * convenience constructor is package-private to {@code ...cost}.
     */
    private static final class StubResolver {
        private int calls;
        private final NodeRateResolver resolver;

        private StubResolver() {
            resolver = mock(NodeRateResolver.class);
            when(resolver.resolve(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                calls++;
                String instanceType = invocation.getArgument(1);
                String capacityType = invocation.getArgument(2);
                String zone = invocation.getArgument(3);
                String region = invocation.getArgument(4);
                return NodeRate.priceable(KNOWN_USD_PER_HOUR, NodeRate.Source.PRICE_LIST, "known",
                        instanceType, capacityType, zone, region);
            });
        }
    }

    private static WorkloadPolicy storedRow(WorkloadMode mode, int windowHours) {
        return new WorkloadPolicy(mode, WorkloadPolicyDefaults.HEADROOM_PCT,
                WorkloadPolicyDefaults.PERCENTILE, windowHours, WorkloadPolicyDefaults.CPU_FLOOR_MILLIS,
                WorkloadPolicyDefaults.MEM_FLOOR_BYTES,
                new HysteresisBand(15, 25), new HysteresisBand(30, 100),
                new HysteresisBand(15, 32 * MI), new HysteresisBand(30, 128 * MI),
                ApplyMethod.IN_PLACE, WorkloadPolicyDefaults.COOLDOWN_MINUTES,
                WorkloadPolicyDefaults.MAX_LIMIT_INCREASE_FACTOR, true,
                WorkloadPolicyDefaults.NODE_CAPACITY_FRACTION, null,
                WorkloadPolicy.Source.WORKLOAD_ROW);
    }

    private static KubernetesClusterConfig config(LocalDateTime lastCheckin) {
        KubernetesClusterConfig config = new KubernetesClusterConfig();
        config.setId(42L);
        config.setClusterName("xamops-test-cluster");
        config.setRegion("ap-south-1");
        config.setLastAgentCheckin(lastCheckin);
        return config;
    }

    private static ClusterAccessProvider failing(ClusterAccessException.Kind kind) {
        return new ClusterAccessProvider() {
            @Override
            public String providerName() {
                return "fake";
            }

            @Override
            public boolean supports(ClusterTarget target) {
                return true;
            }

            @Override
            public ClusterAccess open(ClusterTarget target) throws ClusterAccessException {
                throw new ClusterAccessException(kind, "fake failure");
            }
        };
    }

    /** A provider that opens successfully, handing out a client nothing in the test actually calls. */
    private static ClusterAccessProvider opening() {
        return new ClusterAccessProvider() {
            @Override
            public String providerName() {
                return "fake-open";
            }

            @Override
            public boolean supports(ClusterTarget target) {
                return true;
            }

            @Override
            public ClusterAccess open(ClusterTarget target) {
                return new ClusterAccess(mock(KubernetesClient.class), "https://127.0.0.1",
                        target.clusterName(), target.region(), "fake-open");
            }
        };
    }

    /** Records whether it was touched at all, which is the assertion that matters when gated. */
    private static final class RecordingProvider implements ClusterAccessProvider {

        private int supportsAsked;
        private int opened;

        @Override
        public String providerName() {
            return "recording";
        }

        @Override
        public boolean supports(ClusterTarget target) {
            supportsAsked++;
            return true;
        }

        @Override
        public ClusterAccess open(ClusterTarget target) throws ClusterAccessException {
            opened++;
            throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_UNREACHABLE, "never");
        }
    }

    /** Supports everything, records whether it was the one chosen, and never performs I/O. */
    private abstract static class PrecedenceProvider implements ClusterAccessProvider {

        private int opened;

        @Override
        public boolean supports(ClusterTarget target) {
            return true;
        }

        @Override
        public ClusterAccess open(ClusterTarget target) throws ClusterAccessException {
            opened++;
            throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_UNREACHABLE, "not opened");
        }
    }

    @Order(EksClusterAccessProvider.PRECEDENCE)
    private static final class EksPrecedenceProvider extends PrecedenceProvider {

        @Override
        public String providerName() {
            return "eks-direct";
        }
    }

    @Order(KubeconfigClusterAccessProvider.PRECEDENCE)
    private static final class KubeconfigPrecedenceProvider extends PrecedenceProvider {

        @Override
        public String providerName() {
            return "kubeconfig";
        }
    }

    /** Measures whatever it is asked for, with full coverage over the policy window. */
    private static final class FakeUsage implements UsageHistoryProvider {

        private UsageQuery lastQuery;
        private RpsQuery lastRpsQuery;
        /** When present, {@link #observedRps} returns it; empty by default (NO_RPS_SOURCE). */
        private java.util.OptionalDouble observedRps = java.util.OptionalDouble.empty();

        @Override
        public String sourceName() {
            return "fake-prometheus";
        }

        @Override
        public boolean reachable(ClusterAccess access) {
            return true;
        }

        @Override
        public Map<String, ContainerUsageAggregate> aggregates(ClusterAccess access, UsageQuery query) {
            lastQuery = query;
            Map<String, ContainerUsageAggregate> byContainer = new java.util.LinkedHashMap<>();
            for (String container : query.containerNames()) {
                byContainer.put(container, new ContainerUsageAggregate(container,
                        PercentileSet.single(query.percentile(), 10),
                        PercentileSet.single(query.percentile(), 20 * MI),
                        0.0, 0,
                        VerticalReadServiceFixtures.CYCLE_AT.minus(Duration.ofHours(24)),
                        VerticalReadServiceFixtures.CYCLE_AT, 1.0, sourceName()));
            }
            return byContainer;
        }

        @Override
        public java.util.OptionalDouble observedRps(ClusterAccess access, RpsQuery query) {
            lastRpsQuery = query;
            return observedRps;
        }
    }

    /** Reachable, but every measurement fails with one typed kind. */
    private static final class FailingUsage implements UsageHistoryProvider {

        private final UsageUnavailableException.Kind kind;

        private FailingUsage(UsageUnavailableException.Kind kind) {
            this.kind = kind;
        }

        @Override
        public String sourceName() {
            return "fake-prometheus";
        }

        @Override
        public boolean reachable(ClusterAccess access) {
            return true;
        }

        @Override
        public Map<String, ContainerUsageAggregate> aggregates(ClusterAccess access, UsageQuery query)
                throws UsageUnavailableException {
            throw new UsageUnavailableException(kind, "fake " + kind);
        }

        @Override
        public java.util.OptionalDouble observedRps(ClusterAccess access, RpsQuery query) {
            return java.util.OptionalDouble.empty();
        }
    }

    /** Counts reachability probes, to prove they are cached. */
    private static final class CountingProvider implements UsageHistoryProvider {

        private int reachabilityProbes;

        @Override
        public String sourceName() {
            return "counting";
        }

        @Override
        public boolean reachable(ClusterAccess access) {
            reachabilityProbes++;
            return true;
        }

        @Override
        public Map<String, ContainerUsageAggregate> aggregates(ClusterAccess access, UsageQuery query) {
            return Map.of();
        }

        @Override
        public java.util.OptionalDouble observedRps(ClusterAccess access, RpsQuery query) {
            return java.util.OptionalDouble.empty();
        }
    }
}
