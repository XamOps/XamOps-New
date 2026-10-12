package com.xammer.cloud.service.automation.workload.execute;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xammer.cloud.service.automation.workload.ApplyMethod;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.WorkloadPods;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The executor, against a JDK {@link HttpServer} serving real pod bodies on loopback.
 *
 * <p>What each case is really asserting is that <strong>an unconfirmed resize is never reported as a
 * success</strong>, and that "restart-free" is only claimed where the pod's own status proves it.
 * The server is scripted per pod so a canary can fail while its siblings are watched for any request
 * at all.
 */
class DirectInPlaceResizeExecutorTest {

    private static final String NS = "team-a";
    private static final WorkloadRef REF = WorkloadRef.of(NS, "Deployment", "overprovisioned-api");
    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-1", "ap-south-1");
    private static final ResourceEnvelope GUARANTEED_1CPU_1GI =
            new ResourceEnvelope(1000, 1000, 1_073_741_824L, 1_073_741_824L);
    private static final ContainerApplyTarget SMALL =
            new ContainerApplyTarget("api", 25, 25, 262_144_000L, 262_144_000L);

    private static HttpServer server;
    private static String baseUrl;

    /** {@code podName -> body served for a GET}. */
    private static final Map<String, String> getBodies = new ConcurrentHashMap<>();
    /** {@code podName -> body served as the answer to the resize PATCH}. */
    private static final Map<String, String> patchBodies = new ConcurrentHashMap<>();
    private static final Map<String, Integer> patchStatus = new ConcurrentHashMap<>();
    private static final List<String> patchedPods = new CopyOnWriteArrayList<>();
    /**
     * {@code podName -> answers to the 2nd, 3rd … PATCH} (the first comes from {@link #patchBodies}).
     * Each answer also becomes what a GET of that pod serves from then on, the way the API server
     * would. The last one repeats.
     */
    private static final Map<String, List<Scripted>> laterPatches = new ConcurrentHashMap<>();
    /** {@code podName -> the request bodies of every PATCH it received}, in order. */
    private static final Map<String, List<String>> patchRequests = new ConcurrentHashMap<>();

    /** One scripted answer: an HTTP status and a body. */
    private record Scripted(int status, String body) {
    }

    private KubernetesClient client;
    private DirectInPlaceResizeExecutor executor;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", DirectInPlaceResizeExecutorTest::respond);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void reset() {
        getBodies.clear();
        patchBodies.clear();
        patchStatus.clear();
        patchedPods.clear();
        laterPatches.clear();
        patchRequests.clear();
        client = new KubernetesClientBuilder()
                .withConfig(new ConfigBuilder().withMasterUrl(baseUrl)
                        .withRequestRetryBackoffLimit(0).build())
                .build();
        // Short, real durations rather than a pinned clock: the poll loop advances time, so a fixed
        // Clock would make its deadline unreachable.
        executor = new DirectInPlaceResizeExecutor(new PodResizeClient(5L),
                new ClusterWorkloadInventory(), List.of(), Clock.systemUTC(),
                Duration.ofMillis(5), Duration.ofMillis(250));
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    // ---------------------------------------------------------------- happy path

    @Test
    @DisplayName("a confirmed resize proves restart-free: same uid, same containerID, same restartCount, Ready")
    void aConfirmedResizeIsProvedRestartFree() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        whenPatched("api-1", pod("api-1", "uid-api-1", "containerd://c1", 0, true, "25m", "250Mi"));

        ActionSubmission submission = executor().submit(command(false), access(), workload("api-1"));

        assertThat(submission.state()).isEqualTo(SubmitState.ACCEPTED);
        ResizeProof proof = proof();
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.APPLIED);
        assertThat(proof.restartFree()).isTrue();
        assertThat(proof.publishedTerminal()).isEqualTo(ActionOutcome.Terminal.APPLIED);
        assertThat(patchedPods).containsExactly("api-1");

        ResizeProof.PodProof pod = proof.pods().get(0);
        assertThat(pod.samePod()).isTrue();
        assertThat(pod.ready()).isTrue();
        ResizeProof.ContainerProof container = pod.containers().get(0);
        assertThat(container.containerIdBefore()).isEqualTo("containerd://c1");
        assertThat(container.containerIdAfter()).isEqualTo("containerd://c1");
        assertThat(container.restartCountBefore()).isZero();
        assertThat(container.restartCountAfter()).isZero();
        assertThat(container.observedBefore().cpuRequestMillis()).isEqualTo(1000);
        assertThat(container.observedAfter().cpuRequestMillis())
                .as("read from status.containerStatuses[].resources, which is what is in place")
                .isEqualTo(25);
    }

    @Test
    @DisplayName("the canary is resized and confirmed before any sibling is touched")
    void everyPodIsResizedAfterTheCanaryIsConfirmed() {
        for (String name : List.of("api-1", "api-2", "api-3")) {
            givenPod(name, "uid-" + name, "containerd://" + name, 0, true, "1", "1Gi");
            whenPatched(name, pod(name, "uid-" + name, "containerd://" + name, 0, true, "25m", "250Mi"));
        }

        executor().submit(command(false), access(), workload("api-1", "api-2", "api-3"));

        ResizeProof proof = proof();
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.APPLIED);
        assertThat(proof.restartFree()).isTrue();
        assertThat(proof.pods()).hasSize(3);
        assertThat(patchedPods).hasSize(3).contains("api-1");
        assertThat(patchedPods.get(0))
                .as("the canary goes first, alone")
                .isEqualTo("api-1");
    }

    // ---------------------------------------------------------------- honest failures

    @Test
    @DisplayName("Deferred is reported as Deferred and is never APPLIED")
    void deferredIsNotApplied() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        whenPatched("api-1", withCondition(
                pod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi"),
                condition("PodResizePending", "True", "Deferred",
                        "Node didn't have enough capacity: cpu")));

        executor().submit(command(false), access(), workload("api-1"));

        ResizeProof proof = proof();
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.DEFERRED);
        assertThat(proof.restartFree()).isFalse();
        assertThat(proof.detail()).contains("deferred").contains("capacity");
        assertThat(proof.containers()).singleElement()
                .extracting(ResizeProof.ContainerResult::state)
                .isEqualTo(ResizeProof.ContainerState.DEFERRED);
        assertThat(proof.publishedTerminal())
                .as("the published vocabulary has no DEFERRED, and APPLIED would be a lie")
                .isEqualTo(ActionOutcome.Terminal.FAILED);
    }

    @Test
    @DisplayName("Infeasible is reported as Infeasible and is never APPLIED")
    void infeasibleIsNotApplied() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        whenPatched("api-1", withCondition(
                pod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi"),
                condition("PodResizePending", "True", "Infeasible",
                        "Node didn't have enough capacity: memory")));

        executor().submit(command(false), access(), workload("api-1"));

        assertThat(proof().outcome()).isEqualTo(ResizeProof.Outcome.INFEASIBLE);
        assertThat(proof().detail()).contains("never fit on this node");
        assertThat(proof().detail())
                .as("a patch was sent, so 'nothing was changed' would be false (review-1 S7)")
                .doesNotContain("Nothing was changed");
        assertThat(proof().containers()).singleElement()
                .extracting(ResizeProof.ContainerResult::applied)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("a restartCount increment is a failure that says 'restart detected'")
    void aRestartIsDetectedAndReported() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        whenPatched("api-1", pod("api-1", "uid-api-1", "containerd://c2", 1, true, "25m", "250Mi"));

        executor().submit(command(false), access(), workload("api-1"));

        ResizeProof proof = proof();
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.RESTART_DETECTED);
        assertThat(proof.detail())
                .as("the whole promise of this feature is that the pod does not restart")
                .contains("restart detected");
        assertThat(proof.restartFree()).isFalse();
    }

    @Test
    @DisplayName("a resize still in progress at the deadline is reported, not waited out for ever")
    void aStuckResizeIsReportedAsStuck() {
        // The memory-decrease case: the kubelet will not shrink a limit below live usage, so
        // PodResizeInProgress stays True and status.resources never changes.
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        String stuck = withCondition(pod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi"),
                condition("PodResizeInProgress", "True", null, null));
        whenPatched("api-1", stuck);
        getBodies.put("api-1", stuck);

        executor().submit(command(false), access(), workload("api-1"));

        assertThat(proof().outcome()).isEqualTo(ResizeProof.Outcome.STUCK_IN_PROGRESS);
        assertThat(proof().detail()).contains("still in progress").contains("not confirmed");
    }

    @Test
    @DisplayName("a pod that applied the resize but is no longer Ready is not a success")
    void aPodThatLosesReadinessIsNotASuccess() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        whenPatched("api-1", pod("api-1", "uid-api-1", "containerd://c1", 0, false, "25m", "250Mi"));

        executor().submit(command(false), access(), workload("api-1"));

        assertThat(proof().outcome()).isEqualTo(ResizeProof.Outcome.NOT_READY);
        assertThat(proof().restartFree()).isFalse();
    }

    @Test
    @DisplayName("a canary failure leaves every remaining pod untouched")
    void aCanaryFailureStopsTheRollout() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        whenPatched("api-1", withCondition(
                pod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi"),
                condition("PodResizePending", "True", "Infeasible", "no capacity")));
        for (String name : List.of("api-2", "api-3")) {
            givenPod(name, "uid-" + name, "containerd://" + name, 0, true, "1", "1Gi");
            whenPatched(name, pod(name, "uid-" + name, "containerd://" + name, 0, true, "25m", "250Mi"));
        }

        executor().submit(command(false), access(), workload("api-1", "api-2", "api-3"));

        assertThat(proof().outcome()).isEqualTo(ResizeProof.Outcome.INFEASIBLE);
        assertThat(patchedPods)
                .as("one pod is the whole blast radius of a bad number: the resize, then putting it back")
                .containsExactly("api-1", "api-1");
    }

    // ---------------------------------------------------------------- putting a declined pod back (S7)

    /**
     * UAT, 2026-10-12: prod-sim/throttled-encoder's canary came back {@code Deferred} ("OutOfcpu …
     * requested: 63, used: 1900, capacity: 1930"). The rollout stopped, but the pod kept 63m in its
     * spec with {@code PodResizePending=True}, unrecorded, for the kubelet to apply whenever the node
     * freed up. It had to be reverted by hand.
     */
    @Test
    @DisplayName("a Deferred canary is put back to its old size and proved back (fixture resize-deferred.json)")
    void aDeferredCanaryIsPutBack() {
        Pod deferred = PodResizeStatusTest.pod("resize-deferred.json");
        String name = deferred.getMetadata().getName();
        String asBefore = asItWas(deferred);
        getBodies.put(name, asBefore);
        whenPatched(name, Serialization.asJson(deferred));
        laterPatches.put(name, List.of(new Scripted(200, asBefore)));

        ActionCommand raise = commandFor(new ContainerApplyTarget("api", 1500, 1500, 2L * GI, 2L * GI));
        executor().submit(raise, access(), fixtureWorkload(deferred));

        ResizeProof proof = proofOf(raise.correlationId());
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.DEFERRED);
        assertThat(patchedPods).as("the resize, then the revert").containsExactly(name, name);
        assertThat(patchRequests.get(name).get(1))
                .as("the revert sends the pod's own size from before the apply, request and limit together")
                .isEqualTo("{\"spec\":{\"containers\":[{\"name\":\"api\",\"resources\":{\"requests\":"
                        + "{\"cpu\":\"1000m\",\"memory\":\"1Gi\"},\"limits\":{\"cpu\":\"1000m\",\"memory\":\"1Gi\"}}}]}}");
        ResizeProof.PodProof pod = proof.pods().get(0);
        assertThat(pod.revert().state()).isEqualTo(ResizeProof.RevertState.REVERTED);
        assertThat(pod.insufficientResource()).isEqualTo("cpu");
        assertThat(pod.changed()).as("back where it was").isFalse();
        assertThat(proof.reverted()).isTrue();
        assertThat(proof.anyPodChanged()).as("so no cooldown starts, and the user can retry").isFalse();
        assertThat(proof.insufficientResource()).isEqualTo("cpu");
        assertThat(proof.detail()).contains("deferred").contains("back to its previous size")
                .doesNotContain("Nothing was changed");
        assertThat(proof.restartFree()).isFalse();
    }

    @Test
    @DisplayName("an Infeasible canary is put back to its old size and proved back (fixture resize-infeasible.json)")
    void anInfeasibleCanaryIsPutBack() {
        Pod infeasible = PodResizeStatusTest.pod("resize-infeasible.json");
        String name = infeasible.getMetadata().getName();
        String asBefore = asItWas(infeasible);
        getBodies.put(name, asBefore);
        whenPatched(name, Serialization.asJson(infeasible));
        laterPatches.put(name, List.of(new Scripted(200, asBefore)));

        ActionCommand raise = commandFor(new ContainerApplyTarget("api", 8000, 8000, 16L * GI, 16L * GI));
        executor().submit(raise, access(), fixtureWorkload(infeasible));

        ResizeProof proof = proofOf(raise.correlationId());
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.INFEASIBLE);
        assertThat(patchedPods).containsExactly(name, name);
        assertThat(proof.pods().get(0).revert().state()).isEqualTo(ResizeProof.RevertState.REVERTED);
        assertThat(proof.insufficientResource()).isEqualTo("memory");
        assertThat(proof.reverted()).isTrue();
        assertThat(proof.anyPodChanged()).isFalse();
        assertThat(proof.detail()).contains("never fit on this node").contains("back to its previous size")
                .doesNotContain("Nothing was changed");
    }

    @Test
    @DisplayName("a revert the pod never confirms is reported as not confirmed, and counts as a change")
    void aRevertThatIsNotConfirmedSaysSo() {
        Pod deferred = PodResizeStatusTest.pod("resize-deferred.json");
        String name = deferred.getMetadata().getName();
        getBodies.put(name, asItWas(deferred));
        whenPatched(name, Serialization.asJson(deferred));
        // The API server accepts the revert, and the pod keeps reporting the pending resize.
        laterPatches.put(name, List.of(new Scripted(200, Serialization.asJson(deferred))));

        ActionCommand raise = commandFor(new ContainerApplyTarget("api", 1500, 1500, 2L * GI, 2L * GI));
        executor().submit(raise, access(), fixtureWorkload(deferred));

        ResizeProof proof = proofOf(raise.correlationId());
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.DEFERRED);
        assertThat(proof.pods().get(0).revert().state()).isEqualTo(ResizeProof.RevertState.NOT_CONFIRMED);
        assertThat(proof.reverted()).isFalse();
        assertThat(proof.anyPodChanged())
                .as("the new size may still be applied later, so the cooldown must start")
                .isTrue();
        assertThat(proof.detail()).contains("did not confirm it").contains("may still be applied later");
    }

    @Test
    @DisplayName("a revert the API server refuses is reported as failed, never as put back")
    void aRefusedRevertSaysSo() {
        Pod deferred = PodResizeStatusTest.pod("resize-deferred.json");
        String name = deferred.getMetadata().getName();
        getBodies.put(name, asItWas(deferred));
        whenPatched(name, Serialization.asJson(deferred));
        laterPatches.put(name, List.of(new Scripted(500,
                "{\"kind\":\"Status\",\"code\":500,\"message\":\"etcdserver: request timed out\"}")));

        ActionCommand raise = commandFor(new ContainerApplyTarget("api", 1500, 1500, 2L * GI, 2L * GI));
        executor().submit(raise, access(), fixtureWorkload(deferred));

        ResizeProof proof = proofOf(raise.correlationId());
        assertThat(proof.pods().get(0).revert().state()).isEqualTo(ResizeProof.RevertState.FAILED);
        assertThat(proof.reverted()).isFalse();
        assertThat(proof.anyPodChanged()).isTrue();
        assertThat(proof.detail()).contains("could not").contains("may still be applied later");
    }

    @Test
    @DisplayName("a sibling declined after a confirmed canary is put back; the canary keeps its new size")
    void aDeclinedSiblingIsPutBackAndTheCanaryIsKept() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        whenPatched("api-1", pod("api-1", "uid-api-1", "containerd://c1", 0, true, "25m", "250Mi"));
        givenPod("api-2", "uid-api-2", "containerd://c2", 0, true, "1", "1Gi");
        whenPatched("api-2", withCondition(pod("api-2", "uid-api-2", "containerd://c2", 0, true, "1", "1Gi"),
                condition("PodResizePending", "True", "Deferred", "Node didn't have enough resource: memory")));
        laterPatches.put("api-2", List.of(new Scripted(200,
                pod("api-2", "uid-api-2", "containerd://c2", 0, true, "1", "1Gi"))));

        executor().submit(command(false), access(), workload("api-1", "api-2"));

        ResizeProof proof = proof();
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.PARTIALLY_APPLIED);
        assertThat(patchedPods).containsExactly("api-1", "api-2", "api-2");
        assertThat(proof.pods()).extracting(ResizeProof.PodProof::podName).containsExactly("api-1", "api-2");
        assertThat(proof.pods().get(0).changed()).isTrue();
        assertThat(proof.pods().get(0).revert()).isNull();
        assertThat(proof.pods().get(1).revert().state()).isEqualTo(ResizeProof.RevertState.REVERTED);
        assertThat(proof.anyPodChanged()).as("the canary runs the new size").isTrue();
    }

    @Test
    @DisplayName("a Ready pod is the canary, ahead of one that is not Ready (review-2 item 14)")
    void aReadyPodIsTheCanary() {
        for (String name : List.of("api-1", "api-2")) {
            givenPod(name, "uid-" + name, "containerd://" + name, 0, true, "1", "1Gi");
            whenPatched(name, pod(name, "uid-" + name, "containerd://" + name, 0, true, "25m", "250Mi"));
        }
        ClusterWorkloadInventory.Workload notReadyFirst = new ClusterWorkloadInventory.Workload(REF, 2,
                false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", GUARANTEED_1CPU_1GI, Map.of())),
                new WorkloadPods(REF, List.of(new WorkloadPods.Pod("api-1", "uid-api-1", false),
                        new WorkloadPods.Pod("api-2", "uid-api-2", true))));

        executor().submit(command(false), access(), notReadyFirst);

        assertThat(patchedPods.get(0))
                .as("a canary that was never Ready would fail as 'stopped being ready'")
                .isEqualTo("api-2");
        assertThat(DirectInPlaceResizeExecutor.canaryFirst(notReadyFirst.pods().pods()))
                .extracting(WorkloadPods.Pod::name)
                .containsExactly("api-2", "api-1");
    }

    @Test
    @DisplayName("a dry run on a workload with no live pods is refused, as the real apply is (review-2 item 14)")
    void aDryRunWithNoPodsIsRefused() {
        ClusterWorkloadInventory.Workload noPods = new ClusterWorkloadInventory.Workload(REF, 1,
                false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", GUARANTEED_1CPU_1GI, Map.of())),
                new WorkloadPods(REF, List.of()));

        ActionSubmission submission = executor().submit(command(true), access(), noPods);

        assertThat(submission.state()).isEqualTo(SubmitState.REFUSED);
        assertThat(proof().outcome())
                .as("a rehearsal that passed would promise an apply the real one refuses")
                .isEqualTo(ResizeProof.Outcome.REFUSED);
        assertThat(proof().detail()).contains("no pods");
        assertThat(patchedPods).isEmpty();
    }

    @Test
    @DisplayName("the proof names the pods that were left out, so 'every pod' is never claimed (review-2 SF3)")
    void skippedPodsAreInTheProof() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        whenPatched("api-1", pod("api-1", "uid-api-1", "containerd://c1", 0, true, "25m", "250Mi"));
        ClusterWorkloadInventory.Workload withAPendingPod = new ClusterWorkloadInventory.Workload(REF, 2,
                false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", GUARANTEED_1CPU_1GI, Map.of())),
                new WorkloadPods(REF, List.of(new WorkloadPods.Pod("api-1", "uid-api-1")), List.of(),
                        List.of(new WorkloadPods.Skipped("api-2", "Pending", WorkloadPods.SKIPPED_NOT_RUNNING))));

        executor().submit(command(false), access(), withAPendingPod);

        ResizeProof proof = proof();
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.APPLIED);
        assertThat(proof.pods()).hasSize(1);
        assertThat(proof.skippedPods()).containsExactly(
                new ResizeProof.SkippedPod("api-2", "Pending", WorkloadPods.SKIPPED_NOT_RUNNING));
        assertThat(patchedPods).containsExactly("api-1");
    }

    @Test
    @DisplayName("the pending reason names the resource the node lacked, in either kubelet wording")
    void theInsufficientResourceIsReadFromTheReason() {
        assertThat(DirectInPlaceResizeExecutor.insufficientResource(PodResizeStatus.of(
                PodResizeStatusTest.pod("resize-deferred.json")))).isEqualTo("cpu");
        assertThat(DirectInPlaceResizeExecutor.insufficientResource(PodResizeStatus.of(
                PodResizeStatusTest.pod("resize-infeasible.json")))).isEqualTo("memory");
        Pod uat = Serialization.unmarshal(withCondition(pod("p", "u", "c", 0, true, "10m", "64Mi"),
                condition("PodResizePending", "True", "Deferred",
                        "OutOfcpu: Node didn't have enough resource: cpu, requested: 63, used: 1900, capacity: 1930")),
                Pod.class);
        assertThat(DirectInPlaceResizeExecutor.insufficientResource(PodResizeStatus.of(uat))).isEqualTo("cpu");
        assertThat(DirectInPlaceResizeExecutor.insufficientResource(PodResizeStatus.of(
                Serialization.unmarshal(pod("p", "u", "c", 0, true, "10m", "64Mi"), Pod.class)))).isNull();
    }

    @Test
    @DisplayName("a sibling failing after a confirmed canary is PARTIALLY_APPLIED, and the rest stop")
    void aSiblingFailureIsPartiallyApplied() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        whenPatched("api-1", pod("api-1", "uid-api-1", "containerd://c1", 0, true, "25m", "250Mi"));
        givenPod("api-2", "uid-api-2", "containerd://c2", 0, true, "1", "1Gi");
        whenPatched("api-2", withCondition(pod("api-2", "uid-api-2", "containerd://c2", 0, true, "1", "1Gi"),
                condition("PodResizePending", "True", "Infeasible", "no capacity")));

        executor().submit(command(false), access(), workload("api-1", "api-2"));

        ResizeProof proof = proof();
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.PARTIALLY_APPLIED);
        assertThat(proof.restartFree()).isFalse();
        assertThat(proof.detail()).contains("api-2").contains("rollout stopped");
        assertThat(proof.publishedTerminal()).isEqualTo(ActionOutcome.Terminal.PARTIALLY_APPLIED);
    }

    // ---------------------------------------------------------------- never patched at all

    @Test
    @DisplayName("an expired command is EXPIRED with no PATCH sent")
    void anExpiredCommandSendsNoPatch() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        ActionCommand expired = new ActionCommand("corr-expired", TARGET, REF, List.of(SMALL),
                ApplyMethod.IN_PLACE, false, null, Instant.now().minusSeconds(1));

        ActionSubmission submission = executor().submit(expired, access(), workload("api-1"));

        assertThat(submission.state()).isEqualTo(SubmitState.REFUSED);
        assertThat(patchedPods).isEmpty();
        ResizeProof proof = proofOf("corr-expired");
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.EXPIRED);
        assertThat(proof.publishedTerminal()).isEqualTo(ActionOutcome.Terminal.EXPIRED);
        assertThat(proof.detail()).contains("no longer current");
    }

    @Test
    @DisplayName("a dry run sends no PATCH and returns the would-be diff")
    void aDryRunSendsNoPatch() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");

        ActionSubmission submission = executor().submit(command(true), access(), workload("api-1"));

        assertThat(submission.state()).isEqualTo(SubmitState.ACCEPTED);
        assertThat(patchedPods).isEmpty();
        ResizeProof proof = proof();
        assertThat(proof.outcome()).isEqualTo(ResizeProof.Outcome.DRY_RUN);
        assertThat(proof.restartFree()).isFalse();
        assertThat(proof.publishedTerminal())
                .as("a rehearsal that published APPLIED is the measured reference failure")
                .isEqualTo(ActionOutcome.Terminal.REFUSED);
        ResizeProof.ContainerResult container = proof.containers().get(0);
        assertThat(container.state()).isEqualTo(ResizeProof.ContainerState.DRY_RUN);
        assertThat(container.requested().cpuRequestMillis()).isEqualTo(25);
        assertThat(container.requested().memRequestBytes()).isEqualTo(262_144_000L);
    }

    @Test
    @DisplayName("a replayed correlationId answers DUPLICATE without a second patch")
    void aReplayIsADuplicate() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        whenPatched("api-1", pod("api-1", "uid-api-1", "containerd://c1", 0, true, "25m", "250Mi"));
        DirectInPlaceResizeExecutor executor = executor();

        ActionSubmission first = executor.submit(command(false), access(), workload("api-1"));
        ActionSubmission replay = executor.submit(command(false), access(), workload("api-1"));

        assertThat(first.state()).isEqualTo(SubmitState.ACCEPTED);
        assertThat(replay.state()).isEqualTo(SubmitState.DUPLICATE);
        assertThat(patchedPods)
                .as("at-least-once delivery must not become at-least-twice patching")
                .containsExactly("api-1");
        assertThat(executor.poll("corr-1")).isPresent();
    }

    @Test
    @DisplayName("a BestEffort container is refused before any patch, with the rule named")
    void aBestEffortContainerIsRefused() {
        // dev-sim/no-requests-sidecar-app is exactly this: no resources block at all.
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        ClusterWorkloadInventory.Workload bestEffort = new ClusterWorkloadInventory.Workload(REF, 1,
                false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", ResourceEnvelope.UNSET, Map.of())),
                new WorkloadPods(REF, List.of(new WorkloadPods.Pod("api-1", "uid-api-1"))));

        ActionSubmission submission = executor().submit(command(false), access(), bestEffort);

        assertThat(submission.state()).isEqualTo(SubmitState.REFUSED);
        assertThat(patchedPods).isEmpty();
        assertThat(proof().outcome()).isEqualTo(ResizeProof.Outcome.REFUSED);
        assertThat(proof().detail())
                .contains("BestEffort")
                .contains("cannot add");
    }

    @Test
    @DisplayName("a change that would alter the QoS class is refused before any patch")
    void aQosChangeIsRefused() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        // Guaranteed-shaped today; moving only the request would make it Burstable.
        ActionCommand requestOnly = new ActionCommand("corr-qos", TARGET, REF,
                List.of(new ContainerApplyTarget("api", 25, null, null, null)), ApplyMethod.IN_PLACE,
                false, null, Instant.now().plusSeconds(300));

        ActionSubmission submission = executor().submit(requestOnly, access(), workload("api-1"));

        assertThat(submission.state()).isEqualTo(SubmitState.REFUSED);
        assertThat(patchedPods).isEmpty();
        assertThat(proofOf("corr-qos").detail()).contains("QoS");
    }

    @Test
    @DisplayName("a workload with no pods is refused rather than reported as applied")
    void aWorkloadWithNoPodsIsRefused() {
        ClusterWorkloadInventory.Workload noPods = new ClusterWorkloadInventory.Workload(REF, 0,
                false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", GUARANTEED_1CPU_1GI, Map.of())),
                new WorkloadPods(REF, List.of()));

        ActionSubmission submission = executor().submit(command(false), access(), noPods);

        assertThat(submission.state()).isEqualTo(SubmitState.REFUSED);
        assertThat(proof().detail()).contains("no pods");
    }

    @Test
    @DisplayName("a RestartContainer resize policy applies, but restart-free is not claimed")
    void aRestartContainerPolicyAppliesWithoutClaimingRestartFree() {
        givenPod("api-1", "uid-api-1", "containerd://c1", 0, true, "1", "1Gi");
        whenPatched("api-1", pod("api-1", "uid-api-1", "containerd://c2", 1, true, "25m", "250Mi"));
        ClusterWorkloadInventory.Workload declaresRestart = new ClusterWorkloadInventory.Workload(REF,
                1, false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", GUARANTEED_1CPU_1GI,
                        Map.of("memory", "RestartContainer"))),
                new WorkloadPods(REF, List.of(new WorkloadPods.Pod("api-1", "uid-api-1"))));

        executor().submit(command(false), access(), declaresRestart);

        ResizeProof proof = proof();
        assertThat(proof.outcome())
                .as("the kubelet restarting the container is correct behaviour here, not a failure")
                .isEqualTo(ResizeProof.Outcome.APPLIED);
        assertThat(proof.restartFree())
                .as("correct behaviour or not, the pod did restart, so the claim is withheld")
                .isFalse();
        assertThat(proof.containers()).singleElement()
                .extracting(ResizeProof.ContainerResult::restartExpected)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("a pod replaced between the read and the apply is not resized")
    void aReplacedPodIsNotResized() {
        givenPod("api-1", "uid-new", "containerd://c9", 0, true, "1", "1Gi");

        executor().submit(command(false), access(), workload("api-1"));

        assertThat(patchedPods).isEmpty();
        assertThat(proof().detail()).contains("was replaced between the read and the apply");
    }

    // ---------------------------------------------------------------- shape

    @Test
    @DisplayName("the path is DIRECT and cancellation is honestly a no-op")
    void theExecutorDescribesItself() {
        DirectInPlaceResizeExecutor executor = executor();

        assertThat(executor.path()).isEqualTo(ExecutionPath.DIRECT);
        assertThat(executor.supports(TARGET))
                .as("no access provider is registered in this test, so nothing is supported")
                .isFalse();
        assertThat(executor.poll("never-submitted")).isEmpty();
        executor.cancel("never-submitted");
    }

    @Test
    @DisplayName("this executor cannot address a Deployment at all")
    void nothingHereCanPatchATemplate() throws IOException {
        // Structural, not behavioural: a template patch restarts every pod, which is the opposite
        // of what this feature promises, so the capability must not be present in the source.
        for (String file : List.of("DirectInPlaceResizeExecutor.java", "PodResizeClient.java",
                "PodResizeStatus.java", "ResizeProof.java")) {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/xammer/cloud/service/automation/workload/execute", file),
                    StandardCharsets.UTF_8);
            assertThat(source)
                    .as("%s must contain no reference to a workload template API", file)
                    .doesNotContain("deployments()")
                    .doesNotContain("statefulSets()")
                    .doesNotContain("replicaSets()");
        }
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * One executor per test — JUnit builds a fresh instance per method, so the recorded-outcome map
     * starts empty and the replay case is the only one that sees a second submission.
     */
    private DirectInPlaceResizeExecutor executor() {
        return executor;
    }

    private ResizeProof proof() {
        return proofOf("corr-1");
    }

    private ResizeProof proofOf(String correlationId) {
        return executor.proof(correlationId).orElseThrow(
                () -> new AssertionError("no proof was recorded for " + correlationId));
    }

    private ActionCommand command(boolean dryRun) {
        return new ActionCommand("corr-1", TARGET, REF, List.of(SMALL), ApplyMethod.IN_PLACE, dryRun,
                null, Instant.now().plusSeconds(300));
    }

    private static final long GI = 1_073_741_824L;

    private static ActionCommand commandFor(ContainerApplyTarget target) {
        return new ActionCommand("corr-fixture", TARGET, REF, List.of(target), ApplyMethod.IN_PLACE, false,
                null, Instant.now().plusSeconds(300));
    }

    /** The one-pod workload a recorded fixture belongs to: 1 CPU / 1 GiB, Guaranteed, today. */
    private static ClusterWorkloadInventory.Workload fixtureWorkload(Pod fixture) {
        return new ClusterWorkloadInventory.Workload(REF, 1, false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", GUARANTEED_1CPU_1GI, Map.of())),
                new WorkloadPods(REF, List.of(new WorkloadPods.Pod(fixture.getMetadata().getName(),
                        fixture.getMetadata().getUid()))));
    }

    /**
     * The recorded pod as it was before the patch: its spec back at what its status reports, and no
     * resize pending. Also exactly what it looks like once it has been put back.
     */
    private static String asItWas(Pod recorded) {
        Pod pod = Serialization.unmarshal(Serialization.asJson(recorded), Pod.class);
        pod.getStatus().setConditions(new ArrayList<>(pod.getStatus().getConditions().stream()
                .filter(c -> !PodResizeStatus.PENDING_CONDITION.equals(c.getType()))
                .toList()));
        for (Container container : pod.getSpec().getContainers()) {
            pod.getStatus().getContainerStatuses().stream()
                    .filter(status -> status.getName().equals(container.getName()))
                    .findFirst()
                    .ifPresent(status -> container.setResources(status.getResources()));
        }
        return Serialization.asJson(pod);
    }

    private ClusterAccess access() {
        return new ClusterAccess(client, baseUrl, "xamops-test-1", "ap-south-1", "test");
    }

    private static ClusterWorkloadInventory.Workload workload(String... podNames) {
        List<WorkloadPods.Pod> pods = new ArrayList<>();
        for (String name : podNames) {
            pods.add(new WorkloadPods.Pod(name, "uid-" + name));
        }
        return new ClusterWorkloadInventory.Workload(REF, pods.size(), false, false, null, null,
                List.of(new ClusterWorkloadInventory.ContainerSpec("api", GUARANTEED_1CPU_1GI, Map.of())),
                new WorkloadPods(REF, pods));
    }

    private static void givenPod(String name, String uid, String containerId, int restartCount,
                                 boolean ready, String cpu, String memory) {
        getBodies.put(name, pod(name, uid, containerId, restartCount, ready, cpu, memory));
    }

    private static void whenPatched(String name, String body) {
        patchBodies.put(name, body);
        patchStatus.put(name, 200);
    }

    /** One pod, with {@code spec} and {@code status} both carrying {@code cpu}/{@code memory}. */
    private static String pod(String name, String uid, String containerId, int restartCount,
                              boolean ready, String cpu, String memory) {
        Pod pod = new Pod();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name);
        metadata.setNamespace(NS);
        metadata.setUid(uid);
        pod.setMetadata(metadata);

        Container container = new Container();
        container.setName("api");
        container.setResources(resources(cpu, memory));
        PodSpec spec = new PodSpec();
        spec.setContainers(List.of(container));
        pod.setSpec(spec);

        ContainerStatus status = new ContainerStatus();
        status.setName("api");
        status.setContainerID(containerId);
        status.setRestartCount(restartCount);
        status.setReady(true);
        status.setResources(resources(cpu, memory));

        PodStatus podStatus = new PodStatus();
        podStatus.setPhase("Running");
        podStatus.setContainerStatuses(List.of(status));
        podStatus.setConditions(new ArrayList<>(
                List.of(condition("Ready", ready ? "True" : "False", null, null))));
        pod.setStatus(podStatus);
        return Serialization.asJson(pod);
    }

    private static String withCondition(String podJson, PodCondition extra) {
        Pod pod = Serialization.unmarshal(podJson, Pod.class);
        List<PodCondition> conditions = new ArrayList<>(pod.getStatus().getConditions());
        conditions.add(extra);
        pod.getStatus().setConditions(conditions);
        return Serialization.asJson(pod);
    }

    private static PodCondition condition(String type, String status, String reason, String message) {
        PodCondition condition = new PodCondition();
        condition.setType(type);
        condition.setStatus(status);
        condition.setReason(reason);
        condition.setMessage(message);
        return condition;
    }

    private static ResourceRequirements resources(String cpu, String memory) {
        ResourceRequirements requirements = new ResourceRequirements();
        requirements.setRequests(Map.of("cpu", new Quantity(cpu), "memory", new Quantity(memory)));
        requirements.setLimits(Map.of("cpu", new Quantity(cpu), "memory", new Quantity(memory)));
        return requirements;
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        String body;
        int status = 200;
        if ("PATCH".equals(method) && path.endsWith("/resize")) {
            String podName = podNameOf(path.substring(0, path.length() - "/resize".length()));
            patchedPods.add(podName);
            List<String> received = patchRequests.computeIfAbsent(podName, name -> new CopyOnWriteArrayList<>());
            received.add(request);
            List<Scripted> later = laterPatches.get(podName);
            if (received.size() > 1 && later != null && !later.isEmpty()) {
                Scripted answer = later.get(Math.min(received.size() - 2, later.size() - 1));
                body = answer.body();
                status = answer.status();
                if (status == 200) {
                    getBodies.put(podName, answer.body());
                }
            } else {
                body = patchBodies.getOrDefault(podName, "");
                status = patchStatus.getOrDefault(podName, 404);
            }
        } else if ("GET".equals(method) && path.contains("/pods/")) {
            String podName = podNameOf(path);
            body = getBodies.getOrDefault(podName, "");
            status = body.isEmpty() ? 404 : 200;
        } else {
            body = "{\"kind\":\"Status\",\"code\":404}";
            status = 404;
        }
        if (body.isEmpty()) {
            body = "{\"kind\":\"Status\",\"code\":" + status + ",\"message\":\"not scripted\"}";
        }
        byte[] answer = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, answer.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(answer);
        }
    }

    private static String podNameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
