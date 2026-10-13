package com.xammer.cloud.service.automation.workload.execute;

import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The HPA executor's submit/poll contract: DIRECT applies, AGENT enqueues without a direct write, a
 * dry run sends no patch, a replay is a duplicate with no second patch, and an expired command is
 * terminal and never applied.
 */
class HpaApplyExecutorTest {

    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-1", "ap-south-1");
    private static final WorkloadRef REF = WorkloadRef.of("team-b", "Deployment", "php-apache");
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private HpaPatchClient patchClient;
    private FakeProvider provider;
    private HpaApplyExecutor executor;
    private KubernetesClient client;

    @BeforeEach
    void setUp() {
        patchClient = mock(HpaPatchClient.class);
        client = mock(KubernetesClient.class);
        provider = new FakeProvider(client);
        executor = new HpaApplyExecutor(patchClient, List.of(provider), FIXED);
    }

    @Test
    @DisplayName("the DIRECT path patches the HPA and records APPLIED at submit time")
    void directApplies() throws Exception {
        HpaApplyOutcome outcome = executor.submit(command(1, 8, 69, false), access(), hpa());

        assertThat(outcome.terminal()).isEqualTo(HpaApplyOutcome.Terminal.APPLIED);
        assertThat(outcome.submitState()).isEqualTo(SubmitState.ACCEPTED);
        assertThat(outcome.maxReplicas()).isEqualTo(8);
        assertThat(outcome.targetUtilizationPct()).isEqualTo(69);
        verify(patchClient).patch(any(), eq("team-b"), eq("php-apache"), any(), eq(1), eq(8), eq(69));
        assertThat(executor.poll(outcome.correlationId())).contains(outcome);
    }

    @Test
    @DisplayName("a dry run sends no patch but reports what the real apply would set")
    void dryRunSendsNoPatch() {
        HpaApplyOutcome outcome = executor.submit(command(null, 8, null, true), access(), hpa());

        assertThat(outcome.terminal()).isEqualTo(HpaApplyOutcome.Terminal.APPLIED);
        assertThat(outcome.dryRun()).isTrue();
        assertThat(outcome.maxReplicas()).isEqualTo(8);
        verifyNoInteractions(patchClient);
    }

    @Test
    @DisplayName("a replayed correlationId returns DUPLICATE and sends no second patch")
    void replayIsADuplicate() throws Exception {
        HpaActionCommand command = command(1, 8, null, false);

        HpaApplyOutcome first = executor.submit(command, access(), hpa());
        HpaApplyOutcome replay = executor.submit(command, access(), hpa());

        assertThat(first.submitState()).isEqualTo(SubmitState.ACCEPTED);
        assertThat(replay.submitState()).isEqualTo(SubmitState.DUPLICATE);
        assertThat(replay.terminal()).isEqualTo(HpaApplyOutcome.Terminal.APPLIED);
        verify(patchClient, times(1)).patch(any(), anyString(), anyString(), any(),
                any(), any(), any());
    }

    @Test
    @DisplayName("a command past notValidAfter is terminal/expired and never applied")
    void expiryIsTerminal() {
        HpaActionCommand expired = new HpaActionCommand("corr-exp", TARGET, REF, "team-b",
                "php-apache", 1, 8, null, "12345", false, NOW.minusSeconds(1));

        HpaApplyOutcome outcome = executor.submit(expired, access(), hpa());

        assertThat(outcome.terminal()).isEqualTo(HpaApplyOutcome.Terminal.EXPIRED);
        assertThat(outcome.submitState()).isEqualTo(SubmitState.REFUSED);
        verifyNoInteractions(patchClient);
    }

    @Test
    @DisplayName("a missing HPA is refused, not patched")
    void missingHpaIsRefused() {
        HpaApplyOutcome outcome = executor.submit(command(1, 8, null, false), access(), null);

        assertThat(outcome.terminal()).isEqualTo(HpaApplyOutcome.Terminal.REFUSED);
        verifyNoInteractions(patchClient);
    }

    @Test
    @DisplayName("a 409 conflict is reported as REFUSED (a lost optimistic race), not FAILED")
    void conflictIsRefused() throws Exception {
        when(patchClient.patch(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new HpaPatchClient.HpaPatchRejected(409, "the object has been modified"));

        HpaApplyOutcome outcome = executor.submit(command(1, 8, null, false), access(), hpa());

        assertThat(outcome.terminal()).isEqualTo(HpaApplyOutcome.Terminal.REFUSED);
    }

    @Test
    @DisplayName("the AGENT path enqueues the command and performs no direct write")
    void agentEnqueuesWithoutWriting() {
        HpaApplyOutcome outcome = executor.enqueueForAgent(command(1, 8, null, false));

        assertThat(outcome.terminal()).isEqualTo(HpaApplyOutcome.Terminal.QUEUED);
        assertThat(outcome.submitState()).isEqualTo(SubmitState.ACCEPTED);
        verifyNoInteractions(patchClient);
    }

    @Test
    @DisplayName("the executor advertises the DIRECT path and supports a reachable target")
    void pathAndSupports() {
        assertThat(executor.path()).isEqualTo(ExecutionPath.DIRECT);
        assertThat(executor.supports(TARGET)).isTrue();
    }

    private HpaActionCommand command(Integer min, Integer max, Integer target, boolean dryRun) {
        return new HpaActionCommand("corr-1", TARGET, REF, "team-b", "php-apache", min, max, target,
                "12345", dryRun, NOW.plusSeconds(60));
    }

    private ClusterAccess access() {
        return new ClusterAccess(client, "https://api", "xamops-test-1", "ap-south-1", "fake");
    }

    private static HorizontalPodAutoscaler hpa() {
        return new HorizontalPodAutoscalerBuilder()
                .withNewMetadata().withNamespace("team-b").withName("php-apache")
                .withResourceVersion("12345").endMetadata()
                .withNewSpec().withMinReplicas(1).withMaxReplicas(5)
                .addNewMetric().withType("Resource")
                .withNewResource().withName("cpu")
                .withNewTarget().withType("Utilization").withAverageUtilization(50).endTarget()
                .endResource().endMetric()
                .endSpec().build();
    }

    /** A provider that always supports the target and opens an access over the mocked client. */
    private static final class FakeProvider implements ClusterAccessProvider {

        private final KubernetesClient client;

        private FakeProvider(KubernetesClient client) {
            this.client = client;
        }

        @Override
        public String providerName() {
            return "fake";
        }

        @Override
        public boolean supports(ClusterTarget target) {
            return true;
        }

        @Override
        public ClusterAccess open(ClusterTarget target) {
            return new ClusterAccess(client, "https://api", "xamops-test-1", "ap-south-1", "fake");
        }
    }
}
