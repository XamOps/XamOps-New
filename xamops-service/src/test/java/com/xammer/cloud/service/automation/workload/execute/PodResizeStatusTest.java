package com.xammer.cloud.service.automation.workload.execute;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status reader, against recorded pod bodies.
 *
 * <p>Recorded rather than constructed, because the thing being tested is the <em>shape the API
 * server actually sends</em>: which field holds the applied values, where a {@code Deferred} lives,
 * and that nothing depends on {@code status.resize}, which a 1.33+ server may omit entirely. A
 * builder-constructed pod would only prove the reader agrees with itself.
 */
class PodResizeStatusTest {

    private static final ContainerApplyTarget SMALL =
            new ContainerApplyTarget("api", 25, 25, 262_144_000L, 262_144_000L);

    @Test
    @DisplayName("a completed resize is read from status.resources, with no condition left behind")
    void aCompletedResizeIsApplied() {
        PodResizeStatus status = read("resize-completed.json");

        assertThat(status.podName()).isEqualTo("overprovisioned-api-5d9f7c-abcde");
        assertThat(status.uid()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(status.ready()).isTrue();
        assertThat(status.observedGeneration()).isEqualTo(2L);
        assertThat(status.applied(SMALL))
                .as("status.containerStatuses[].resources is the ACTUAL applied value")
                .isTrue();
        assertThat(status.isDeferred()).isFalse();
        assertThat(status.isInfeasible()).isFalse();
        assertThat(status.isResizeInProgress()).isFalse();
        assertThat(status.describePending()).isNull();

        PodResizeStatus.ContainerState api = status.container("api");
        assertThat(api.restartCount()).isZero();
        assertThat(api.containerID()).startsWith("containerd://");
        assertThat(api.observed().cpuRequestMillis()).isEqualTo(25);
        assertThat(api.observed().memRequestBytes()).isEqualTo(262_144_000L);
        assertThat(api.allocated().memRequestBytes())
                .as("allocatedResources is carried for the record, and nothing is judged from it")
                .isEqualTo(262_144_000L);
        assertThat(api.declared().cpuRequestMillis())
                .as("the spec is the request, kept only for the before/after record")
                .isEqualTo(25);
    }

    @Test
    @DisplayName("an in-progress resize is not applied, even though the spec already says it is")
    void anInProgressResizeIsNotApplied() {
        PodResizeStatus status = read("resize-in-progress.json");

        assertThat(status.isResizeInProgress()).isTrue();
        assertThat(status.isResizeError()).isFalse();
        assertThat(status.applied(SMALL))
                .as("the API server accepts the patch immediately, so the spec is the request and "
                        + "only the status says whether the kubelet has done it")
                .isFalse();
        assertThat(status.container("api").declared().cpuRequestMillis()).isEqualTo(25);
        assertThat(status.container("api").observed().cpuRequestMillis()).isEqualTo(1000);
    }

    @Test
    @DisplayName("Deferred is read as Deferred, with the kubelet's own message")
    void deferredIsItsOwnState() {
        PodResizeStatus status = read("resize-deferred.json");

        assertThat(status.isDeferred()).isTrue();
        assertThat(status.isInfeasible()).isFalse();
        assertThat(status.applied(new ContainerApplyTarget("api", 1500, 1500, null, null))).isFalse();
        assertThat(status.describePending())
                .as("'waiting for node capacity' is only actionable with the kubelet's numbers")
                .isEqualTo("Deferred: Node didn't have enough capacity: cpu, requested: 1500,"
                        + " capacity: 1930");
    }

    @Test
    @DisplayName("Infeasible is distinct from Deferred: it will never succeed on this node")
    void infeasibleIsDistinctFromDeferred() {
        PodResizeStatus status = read("resize-infeasible.json");

        assertThat(status.isInfeasible()).isTrue();
        assertThat(status.isDeferred()).isFalse();
        assertThat(status.describePending()).startsWith("Infeasible: ");
    }

    @Test
    @DisplayName("the stuck memory decrease: still in progress, old value still in place")
    void aStuckMemoryDecreaseIsReadable() {
        PodResizeStatus status = read("resize-memory-decrease-stuck.json");
        ContainerApplyTarget decrease =
                new ContainerApplyTarget("worker", null, null, 262_144_000L, 262_144_000L);

        assertThat(status.isResizeInProgress()).isTrue();
        assertThat(status.isDeferred()).isFalse();
        assertThat(status.isInfeasible()).isFalse();
        assertThat(status.applied(decrease))
                .as("the kubelet will not shrink a memory limit below live usage, so this stays "
                        + "unapplied indefinitely")
                .isFalse();
        assertThat(status.container("worker").observed().memRequestBytes()).isEqualTo(1_073_741_824L);
        assertThat(status.legacyResize())
                .as("read for diagnostics; superseded from 1.33, so nothing branches on it")
                .isEqualTo("InProgress");
    }

    @Test
    @DisplayName("status.resize being absent is not a reason to wait, so it reads as null")
    void anAbsentLegacyResizeFieldIsNotRequired() {
        // The cluster this feature targets is 1.36, where status.resize is superseded by the two
        // conditions and may be omitted. A poller that required it would hang for ever.
        PodResizeStatus completed = read("resize-completed.json");

        assertThat(completed.legacyResize()).isNull();
        assertThat(completed.applied(SMALL)).isTrue();
    }

    @Test
    @DisplayName("a RestartContainer resize policy shows up as a restart in the status")
    void aRestartContainerPolicyShowsAsARestart() {
        PodResizeStatus status = read("resize-restart-container-policy.json");

        assertThat(status.container("encoder").restartCount())
                .as("the kubelet restarts the container to apply a RestartContainer resize, so "
                        + "'restart-free' must not be claimed for it")
                .isEqualTo(1);
        assertThat(status.applied(new ContainerApplyTarget("encoder", 200, 200, 262_144_000L,
                262_144_000L))).isTrue();
    }

    @Test
    @DisplayName("a null target field is satisfied by whatever is there: 'leave it alone'")
    void nullMeansLeaveItAlone() {
        PodResizeStatus status = read("resize-completed.json");

        assertThat(status.applied(new ContainerApplyTarget("api", null, null, 262_144_000L, 262_144_000L)))
                .isTrue();
        assertThat(status.appliedAll(List.of(SMALL))).isTrue();
    }

    @Test
    @DisplayName("a container with no status is 'not confirmed', never 'done'")
    void anUnknownContainerIsNotApplied() {
        PodResizeStatus status = read("resize-completed.json");

        assertThat(status.applied(new ContainerApplyTarget("sidecar", 25, null, null, null)))
                .isFalse();
        assertThat(status.container("sidecar")).isNull();
    }

    private static PodResizeStatus read(String fixture) {
        return PodResizeStatus.of(pod(fixture));
    }

    static Pod pod(String fixture) {
        try {
            String body = Files.readString(Path.of("src/test/resources/k8s/pods", fixture),
                    StandardCharsets.UTF_8);
            return Serialization.unmarshal(body, Pod.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
