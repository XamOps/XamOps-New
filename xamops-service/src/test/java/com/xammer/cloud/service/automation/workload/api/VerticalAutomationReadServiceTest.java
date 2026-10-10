package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import com.xammer.cloud.service.automation.workload.usage.ContainerUsageAggregate;
import com.xammer.cloud.service.automation.workload.usage.StubUsageHistoryProvider;
import com.xammer.cloud.service.automation.workload.usage.UsageHistoryProvider;
import com.xammer.cloud.service.automation.workload.usage.UsageQuery;
import com.xammer.cloud.service.automation.workload.usage.UsageUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read endpoint's payload: honest when gated, and never fabricated.
 */
class VerticalAutomationReadServiceTest {

    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-cluster", "ap-south-1");

    @Test
    @DisplayName("with the master switch off: gated, empty, and NO provider is invoked")
    void theDefaultStateTouchesNothing() {
        RecordingProvider provider = new RecordingProvider();
        VerticalAutomationReadService service = new VerticalAutomationReadService(
                new WorkloadAutoscalingProperties(false, false),
                new StubUsageHistoryProvider(), List.of(provider));

        VerticalEngineResponse response = service.read(TARGET, config(null));

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
        VerticalAutomationReadService service = new VerticalAutomationReadService(
                new WorkloadAutoscalingProperties(false, false),
                new StubUsageHistoryProvider(), List.of(new RecordingProvider()));

        VerticalEngineResponse response = service.read(TARGET, config(null));

        // Guessing NOT_CONFIGURED would send a customer to fix an access entry that may already be
        // correct. Null means "not checked", which the UI's view model already types as nullable.
        assertThat(response.engine().clusterAccess()).isNull();
        assertThat(response.engine().executionPathDetail().direct().available()).isFalse();
        assertThat(response.engine().executionPathDetail().direct().reason()).isNull();
    }

    @Test
    @DisplayName("no cycle has ever run, and the payload says so rather than inventing a timestamp")
    void lastCycleIsEmpty() {
        VerticalAutomationReadService service = new VerticalAutomationReadService(
                new WorkloadAutoscalingProperties(false, false),
                new StubUsageHistoryProvider(), List.of());

        VerticalEngineResponse.LastCycle lastCycle = service.read(TARGET, config(null)).engine().lastCycle();

        assertThat(lastCycle.at()).isNull();
        assertThat(lastCycle.evaluated()).isNull();
        assertThat(lastCycle.error()).isNull();
    }

    @Test
    @DisplayName("a live agent makes the execution path AGENT even with no direct access")
    void aLiveAgentIsTheExecutionPath() {
        VerticalAutomationReadService service = new VerticalAutomationReadService(
                new WorkloadAutoscalingProperties(false, false),
                new StubUsageHistoryProvider(), List.of());

        VerticalEngineResponse response = service.read(TARGET, config(LocalDateTime.now().minusMinutes(2)));

        assertThat(response.engine().executionPath()).isEqualTo("AGENT");
        assertThat(response.engine().executionPathDetail().agent().available()).isTrue();
        assertThat(response.engine().executionPathDetail().agent().reason()).isNull();
        assertThat(response.engine().executionPathDetail().agent().lastSeenAt()).isNotNull();
    }

    @Test
    @DisplayName("a stale agent reuses the UI's existing AGENT_NOT_INSTALLED code")
    void aStaleAgentIsNotAnExecutionPath() {
        VerticalAutomationReadService service = new VerticalAutomationReadService(
                new WorkloadAutoscalingProperties(false, false),
                new StubUsageHistoryProvider(), List.of());

        VerticalEngineResponse response = service.read(TARGET, config(LocalDateTime.now().minusHours(3)));

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
        VerticalAutomationReadService service = new VerticalAutomationReadService(
                new WorkloadAutoscalingProperties(true, true),
                new StubUsageHistoryProvider(), List.of(failing));

        VerticalEngineResponse response = service.read(TARGET, config(null));

        assertThat(response.engine().clusterAccess()).isEqualTo("UNREACHABLE");
        assertThat(response.engine().executionPathDetail().direct().reason())
                .isEqualTo(ReasonCode.CLUSTER_UNREACHABLE.name());
        assertThat(response.gateReasons()).contains(ReasonCode.CLUSTER_UNREACHABLE.name());
        assertThat(response.gateReasons())
                .as("both flags are on, so readiness is not a gate")
                .doesNotContain(ReasonCode.CONTROLLER_NOT_READY.name());
    }

    @Test
    @DisplayName("with the switch on, CLUSTER_ACCESS_NOT_CONFIGURED maps to NOT_CONFIGURED and a gate")
    void misconfiguredAccessIsReportedAsSuch() {
        ClusterAccessProvider failing = failing(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED);
        VerticalAutomationReadService service = new VerticalAutomationReadService(
                new WorkloadAutoscalingProperties(true, true),
                new StubUsageHistoryProvider(), List.of(failing));

        VerticalEngineResponse response = service.read(TARGET, config(null));

        assertThat(response.engine().clusterAccess()).isEqualTo("NOT_CONFIGURED");
        assertThat(response.gateReasons()).contains(ReasonCode.CLUSTER_ACCESS_NOT_CONFIGURED.name());
    }

    @Test
    @DisplayName("workloads stay empty even with the switch on, because nothing can supply usage")
    void noUsageSourceMeansNoWorkloads() {
        VerticalAutomationReadService service = new VerticalAutomationReadService(
                new WorkloadAutoscalingProperties(true, true),
                new StubUsageHistoryProvider(),
                List.of(failing(ClusterAccessException.Kind.CLUSTER_UNREACHABLE)));

        VerticalEngineResponse response = service.read(TARGET, config(null));

        assertThat(response.gateReasons()).contains(ReasonCode.NO_METRICS_SOURCE.name());
        assertThat(response.workloads()).isEmpty();
        assertThat(response.engine().metricsSource().reachable()).isFalse();
        assertThat(response.engine().metricsSource().name()).isNull();
    }

    @Test
    @DisplayName("a real usage source would be named in the payload rather than hidden")
    void aNamedSourceIsReported() {
        VerticalAutomationReadService service = new VerticalAutomationReadService(
                new WorkloadAutoscalingProperties(true, true), new NamedProvider(), List.of());

        VerticalEngineResponse response = service.read(TARGET, config(null));

        assertThat(response.engine().metricsSource().reachable()).isTrue();
        assertThat(response.engine().metricsSource().name()).isEqualTo("prometheus");
        assertThat(response.gateReasons()).doesNotContain(ReasonCode.NO_METRICS_SOURCE.name());
    }

    // ---------------------------------------------------------------- fakes

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

    /** A provider with a real name, to prove the metrics-source branch is not hard-coded. */
    private static final class NamedProvider implements UsageHistoryProvider {

        @Override
        public String sourceName() {
            return "prometheus";
        }

        @Override
        public Map<String, ContainerUsageAggregate> aggregates(UsageQuery query)
                throws UsageUnavailableException {
            throw new UsageUnavailableException(UsageUnavailableException.Kind.INSUFFICIENT_HISTORY, "no");
        }
    }
}
