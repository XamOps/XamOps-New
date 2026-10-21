package com.xammer.cloud.service.automation.workload.policy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.WorkloadVerticalAction;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository;
import com.xammer.cloud.service.automation.workload.ActionTrigger;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.execute.ContainerApplyTarget;
import com.xammer.cloud.service.automation.workload.execute.ResizeProof;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The activity log's writes (FEAT-007): the proof object every new row stores as {@code after_json},
 * the three new row kinds, and the rule that an audit write never fails the apply it records.
 */
class WorkloadActionRecorderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-1", "ap-south-1");
    private static final ClusterTarget OTHER_CLUSTER =
            new ClusterTarget(7L, 43L, "102227938259", "xamops-test-2", "ap-south-1");
    private static final WorkloadRef REF = WorkloadRef.of("prod-sim", "Deployment", "throttled-encoder");
    private static final Instant AT = Instant.parse("2026-10-14T06:30:00Z");
    private static final ResourceEnvelope BEFORE = new ResourceEnvelope(10, 100, 67_108_864L, 134_217_728L);
    private static final ResourceEnvelope ASKED = new ResourceEnvelope(63, 100, 67_108_864L, 134_217_728L);
    private static final List<ContainerApplyTarget> SUBMITTED =
            List.of(new ContainerApplyTarget("encoder", 63, null, null, null));

    private WorkloadVerticalActionRepository repository;
    private MutableClock clock;
    private WorkloadActionRecorder recorder;
    private Logger logger;
    private ListAppender<ILoggingEvent> log;

    @BeforeEach
    void setUp() {
        repository = mock(WorkloadVerticalActionRepository.class);
        clock = new MutableClock(AT);
        recorder = new WorkloadActionRecorder(repository, clock);
        logger = (Logger) LoggerFactory.getLogger(WorkloadActionRecorder.class);
        log = new ListAppender<>();
        log.start();
        logger.addAppender(log);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(log);
    }

    @Test
    @DisplayName("record() keeps its contract and now writes the proof object as after_json")
    void recordWritesTheProofObject() throws Exception {
        recorder.record(7L, TARGET, REF, ActionTrigger.MANUAL, false, SUBMITTED, applied(), AT);

        WorkloadVerticalAction row = saved();
        assertThat(row.getOutcome()).isEqualTo("APPLIED");
        assertThat(row.getRestartFree()).isTrue();
        assertThat(row.getBeforeJson())
                .as("before_json keeps the submitted targets, as it always did")
                .isEqualTo("[{\"container\":\"encoder\",\"cpuRequestMillis\":63}]");
        assertThat(row.getAfterJson()).startsWith("{\"terminal\":\"APPLIED\"");

        JsonNode after = JSON.readTree(row.getAfterJson());
        assertThat(after.get("reverted").isNull()).as("no pod needed putting back").isTrue();
        assertThat(after.at("/containers/0/container").asText()).isEqualTo("encoder");
        assertThat(after.at("/containers/0/observedBefore/cpuRequestMillis").asInt()).isEqualTo(10);
        assertThat(after.at("/containers/0/observedAfter/cpuRequestMillis").asInt()).isEqualTo(63);
        assertThat(after.at("/containers/0/observedAfter/memLimitBytes").asLong()).isEqualTo(134_217_728L);
        JsonNode pod = after.at("/pods/0");
        assertThat(pod.get("pod").asText()).isEqualTo("throttled-encoder-mcw8q");
        assertThat(pod.get("node").isNull()).as("the proof holds no node, so none is guessed").isTrue();
        assertThat(pod.get("changed").asBoolean()).as("the pod runs the new size").isTrue();
        assertThat(pod.get("uidChanged").asBoolean()).isFalse();
        assertThat(pod.get("restartsBefore").asInt()).isZero();
        assertThat(pod.get("restartsAfter").asInt()).isZero();
        assertThat(pod.get("ready").asBoolean()).isTrue();
        assertThat(pod.get("revert").isNull()).isTrue();
    }

    @Test
    @DisplayName("each container keeps the older rows' flat sizes, the observed-after ones, for readers of that shape")
    void eachContainerKeepsTheLegacyFlatSizes() throws Exception {
        recorder.record(7L, TARGET, REF, ActionTrigger.MANUAL, false, SUBMITTED, applied(), AT);

        String afterJson = saved().getAfterJson();
        assertThat(afterJson).as("terminal stays the first key").startsWith("{\"terminal\":\"APPLIED\"");
        JsonNode container = JSON.readTree(afterJson).at("/containers/0");
        assertThat(container.get("cpuRequestMillis").asInt()).isEqualTo(ASKED.cpuRequestMillis());
        assertThat(container.get("cpuLimitMillis").asInt()).isEqualTo(ASKED.cpuLimitMillis());
        assertThat(container.get("memRequestBytes").asLong()).isEqualTo(ASKED.memRequestBytes());
        assertThat(container.get("memLimitBytes").asLong()).isEqualTo(ASKED.memLimitBytes());
        assertThat(container.get("observedAfter").get("cpuRequestMillis").asInt())
                .as("the flat fields are the observed-after size")
                .isEqualTo(container.get("cpuRequestMillis").asInt());
        assertThat(legacyCpuRequestMillis(afterJson))
                .as("read the way a reader of the older array shape reads it (origin/main RcaAnalyzer.cpuRequestMillis)")
                .isEqualTo(63L);
    }

    @Test
    @DisplayName("a container observed with no size afterwards gets no flat sizes, as the older rows wrote it")
    void aContainerWithoutAnObservedSizeGetsNoFlatSizes() throws Exception {
        ResizeProof unobserved = new ResizeProof(ResizeProof.Outcome.APPLIED, null, false,
                List.of(new ResizeProof.ContainerResult("encoder", ResizeProof.ContainerState.APPLIED, null,
                        ASKED, false)),
                List.of(new ResizeProof.PodProof("throttled-encoder-mcw8q", "uid-1", "uid-1", true,
                        List.of(new ResizeProof.ContainerProof("encoder", "containerd://c1", "containerd://c1",
                                0, 0, BEFORE, null)))));

        recorder.record(7L, TARGET, REF, ActionTrigger.MANUAL, false, SUBMITTED, unobserved, AT);

        JsonNode container = JSON.readTree(saved().getAfterJson()).at("/containers/0");
        assertThat(container.has("cpuRequestMillis")).isFalse();
        assertThat(container.has("memLimitBytes")).isFalse();
        assertThat(container.get("observedAfter").isNull()).isTrue();
        assertThat(container.get("observedBefore").get("cpuRequestMillis").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("a partial apply says per pod which one runs the new size and which one was put back")
    void eachPodSaysWhetherItChanged() throws Exception {
        recorder.record(7L, TARGET, REF, ActionTrigger.MANUAL, false, SUBMITTED, partlyApplied(), AT);

        WorkloadVerticalAction row = saved();
        assertThat(row.getOutcome()).isEqualTo("PARTIALLY_APPLIED");
        JsonNode pods = JSON.readTree(row.getAfterJson()).get("pods");
        assertThat(pods).hasSize(2);
        assertThat(pods.get(0).get("changed").asBoolean()).isTrue();
        assertThat(pods.get(0).get("revert").isNull()).isTrue();
        assertThat(pods.get(1).get("changed").asBoolean()).as("put back: it runs its old size").isFalse();
        assertThat(pods.get(1).at("/revert/state").asText()).isEqualTo("REVERTED");
    }

    @Test
    @DisplayName("record() still refuses a row without its mandatory inputs")
    void recordKeepsItsContract() {
        assertThatThrownBy(() -> recorder.record(7L, TARGET, REF, ActionTrigger.MANUAL, false, SUBMITTED,
                null, AT)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a dry run is a TEST_RUN row with dry_run=true, the submitted targets and the DRY_RUN proof")
    void aDryRunIsATestRun() throws Exception {
        ResizeProof dryRun = new ResizeProof(ResizeProof.Outcome.DRY_RUN,
                "Dry run: no patch was sent to the cluster.", false, List.of(), List.of());

        recorder.recordAttempt(7L, TARGET, REF, ActionTrigger.MANUAL, true, SUBMITTED, dryRun, AT);

        WorkloadVerticalAction row = saved();
        assertThat(row.getOutcome()).isEqualTo(WorkloadActionRecorder.OUTCOME_TEST_RUN);
        assertThat(row.getDryRun()).isTrue();
        assertThat(row.getRestartFree()).isFalse();
        assertThat(row.getTriggerSource()).isEqualTo("MANUAL");
        assertThat(row.getBeforeJson()).isEqualTo("[{\"container\":\"encoder\",\"cpuRequestMillis\":63}]");
        assertThat(JSON.readTree(row.getAfterJson()).toString())
                .isEqualTo("{\"terminal\":\"DRY_RUN\",\"reverted\":null,\"containers\":[],\"pods\":[]}");
        assertThat(row.getDetail()).isEqualTo("Dry run: no patch was sent to the cluster.");
        assertThat(row.getAppliedAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("a deferral put back is NOT_CHANGED, with the terminal, the revert and its state in the detail")
    void aPutBackDeferralIsNotChanged() throws Exception {
        recorder.recordAttempt(7L, TARGET, REF, ActionTrigger.AUTO, false, SUBMITTED, deferredPutBack(), AT);

        WorkloadVerticalAction row = saved();
        assertThat(row.getOutcome()).isEqualTo(WorkloadActionRecorder.OUTCOME_NOT_CHANGED);
        assertThat(row.getDryRun()).isFalse();
        assertThat(row.getTriggerSource()).isEqualTo("AUTO");
        assertThat(row.getAfterJson()).startsWith("{\"terminal\":\"DEFERRED\",\"reverted\":true");
        JsonNode after = JSON.readTree(row.getAfterJson());
        assertThat(after.at("/containers/0/observedBefore/cpuRequestMillis").asInt()).isEqualTo(10);
        assertThat(after.at("/containers/0/observedAfter/cpuRequestMillis").asInt())
                .as("after a put-back the observed size is the old one")
                .isEqualTo(10);
        assertThat(after.at("/pods/0/revert/state").asText()).isEqualTo("REVERTED");
        assertThat(after.at("/pods/0/revert/detail").asText()).startsWith("XamOps put pod");
        assertThat(row.getDetail())
                .startsWith("The kubelet deferred the resize")
                .endsWith("Revert: REVERTED.");
    }

    @Test
    @DisplayName("a refusal is a REFUSED row whose detail is the code and the message")
    void aRefusalIsRecorded() {
        recorder.recordRefusal(7L, TARGET, REF, ActionTrigger.MANUAL, true, "K8S_INVALID_POLICY",
                "COOLDOWN: a change was applied recently.", AT);

        WorkloadVerticalAction row = saved();
        assertThat(row.getOutcome()).isEqualTo(WorkloadActionRecorder.OUTCOME_REFUSED);
        assertThat(row.getDetail()).isEqualTo("K8S_INVALID_POLICY: COOLDOWN: a change was applied recently.");
        assertThat(row.getDryRun()).isTrue();
        assertThat(row.getRestartFree()).isFalse();
        assertThat(row.getBeforeJson()).isNull();
        assertThat(row.getAfterJson()).isNull();
        assertThat(row.getClientId()).isEqualTo(7L);
        assertThat(row.getClusterConfigId()).isEqualTo(42L);
        assertThat(row.getWorkloadName()).isEqualTo("throttled-encoder");
    }

    @Test
    @DisplayName("an HPA apply is recorded under the HPA kind and name, with its bounds before and after")
    void anHpaApplyIsRecorded() {
        recorder.recordHpa(7L, TARGET, "team-b", "php-apache", ActionTrigger.MANUAL, false,
                new WorkloadActionRecorder.HpaBounds(1, 5, 50), new WorkloadActionRecorder.HpaBounds(1, 8, 50),
                "APPLIED", null, "corr-1", AT);

        WorkloadVerticalAction row = saved();
        assertThat(row.getWorkloadKind()).isEqualTo(WorkloadActionRecorder.KIND_HPA);
        assertThat(row.getWorkloadName()).isEqualTo("php-apache");
        assertThat(row.getNamespace()).isEqualTo("team-b");
        assertThat(row.getOutcome()).isEqualTo("APPLIED");
        assertThat(row.getCorrelationId()).isEqualTo("corr-1");
        assertThat(row.getRestartFree()).as("an HPA patch touches no pod").isFalse();
        assertThat(row.getBeforeJson()).isEqualTo("{\"minReplicas\":1,\"maxReplicas\":5,\"targetUtilizationPct\":50}");
        assertThat(row.getAfterJson()).isEqualTo("{\"minReplicas\":1,\"maxReplicas\":8,\"targetUtilizationPct\":50}");
        assertThat(WorkloadActionRecorder.KIND_HPA.length())
                .as("fits workload_kind VARCHAR(32), so no migration is needed")
                .isLessThanOrEqualTo(32);
    }

    @Test
    @DisplayName("every new write is fail-soft: a failing store or a missing input never reaches the apply")
    void everyNewWriteIsFailSoft() {
        when(repository.save(any())).thenThrow(new IllegalStateException("database is down"));

        assertThatCode(() -> {
            recorder.recordAttempt(7L, TARGET, REF, ActionTrigger.MANUAL, false, SUBMITTED, deferredPutBack(), AT);
            recorder.recordRefusal(7L, TARGET, REF, ActionTrigger.MANUAL, false, "K8S_INVALID_POLICY", "No.", AT);
            recorder.recordHpa(7L, TARGET, "team-b", "php-apache", ActionTrigger.MANUAL, false, null, null,
                    "APPLIED", null, "corr-1", AT);
            recorder.recordAttempt(7L, null, REF, ActionTrigger.MANUAL, false, SUBMITTED, deferredPutBack(), AT);
            recorder.recordAttempt(7L, TARGET, REF, ActionTrigger.MANUAL, false, SUBMITTED, null, AT);
            recorder.recordRefusal(7L, TARGET, null, ActionTrigger.MANUAL, false, "K8S_DEMO", "No.", AT);
            recorder.recordHpa(7L, TARGET, " ", "php-apache", ActionTrigger.MANUAL, false, null, null,
                    "APPLIED", null, "corr-1", AT);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a failing store is logged at WARN once per cluster per ten minutes")
    void failuresAreRateLimitedPerCluster() {
        when(repository.save(any())).thenThrow(new IllegalStateException("database is down"));

        recorder.recordRefusal(7L, TARGET, REF, ActionTrigger.MANUAL, false, "K8S_INVALID_POLICY", "No.", AT);
        recorder.recordRefusal(7L, TARGET, REF, ActionTrigger.MANUAL, false, "K8S_INVALID_POLICY", "No.", AT);
        assertThat(warnings()).hasSize(1);

        recorder.recordRefusal(7L, OTHER_CLUSTER, REF, ActionTrigger.MANUAL, false, "K8S_INVALID_POLICY", "No.", AT);
        assertThat(warnings()).as("another cluster has its own allowance").hasSize(2);

        clock.advance(WorkloadActionRecorder.WARNING_INTERVAL.minusSeconds(1));
        recorder.recordRefusal(7L, TARGET, REF, ActionTrigger.MANUAL, false, "K8S_INVALID_POLICY", "No.", AT);
        assertThat(warnings()).hasSize(2);

        clock.advance(Duration.ofSeconds(1));
        recorder.recordRefusal(7L, TARGET, REF, ActionTrigger.MANUAL, false, "K8S_INVALID_POLICY", "No.", AT);
        assertThat(warnings()).hasSize(3);
        assertThat(warnings().get(0).getFormattedMessage())
                .contains("cluster config 42")
                .contains("the apply itself is unaffected")
                .doesNotContain("102227938259");
    }

    @Test
    @DisplayName("each fail-soft write runs in a transaction of its own, so a failed insert never reaches the caller's")
    void eachFailSoftWriteHasItsOwnTransaction() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(transactions.getTransaction(any())).thenReturn(status);
        WorkloadActionRecorder owned = new WorkloadActionRecorder(repository,
                WorkloadActionRecorder.requiresNew(transactions), clock);

        owned.recordRefusal(7L, TARGET, REF, ActionTrigger.MANUAL, false, "K8S_INVALID_POLICY", "No.", AT);
        verify(transactions).commit(status);

        // An insert can fail inside its own transaction, as one in a read-only transaction does on
        // PostgreSQL; the failure is rolled back there and never reaches the caller.
        when(repository.save(any())).thenThrow(new IllegalStateException("cannot execute INSERT in a read-only transaction"));
        assertThatCode(() -> {
            owned.recordAttempt(7L, TARGET, REF, ActionTrigger.AUTO, false, SUBMITTED, deferredPutBack(), AT);
            owned.recordHpa(7L, TARGET, "team-b", "php-apache", ActionTrigger.MANUAL, false, null, null,
                    "APPLIED", null, "corr-1", AT);
        }).doesNotThrowAnyException();

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(3)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues())
                .as("a new transaction, suspending the caller's, every time")
                .allSatisfy(definition -> assertThat(definition.getPropagationBehavior())
                        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(transactions, times(2)).rollback(status);
        verify(transactions, times(1)).commit(status);
    }

    // ---------------------------------------------------------------- fixtures

    private WorkloadVerticalAction saved() {
        ArgumentCaptor<WorkloadVerticalAction> row = ArgumentCaptor.forClass(WorkloadVerticalAction.class);
        verify(repository).save(row.capture());
        return row.getValue();
    }

    private List<ILoggingEvent> warnings() {
        return log.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    private static ResizeProof applied() {
        return new ResizeProof(ResizeProof.Outcome.APPLIED, null, true,
                List.of(new ResizeProof.ContainerResult("encoder", ResizeProof.ContainerState.APPLIED, null,
                        ASKED, false)),
                List.of(new ResizeProof.PodProof("throttled-encoder-mcw8q", "uid-1", "uid-1", true,
                        List.of(new ResizeProof.ContainerProof("encoder", "containerd://c1", "containerd://c1",
                                0, 0, BEFORE, ASKED)))));
    }

    /** The canary took the new size; the second pod was declined for room and put back. */
    private static ResizeProof partlyApplied() {
        return new ResizeProof(ResizeProof.Outcome.PARTIALLY_APPLIED,
                "1 of 2 pods resized; the other was put back.", false,
                List.of(new ResizeProof.ContainerResult("encoder", ResizeProof.ContainerState.APPLIED, null,
                        ASKED, false)),
                List.of(new ResizeProof.PodProof("throttled-encoder-mcw8q", "uid-1", "uid-1", true,
                                List.of(new ResizeProof.ContainerProof("encoder", "containerd://c1",
                                        "containerd://c1", 0, 0, BEFORE, ASKED))),
                        new ResizeProof.PodProof("throttled-encoder-x7k2p", "uid-2", "uid-2", true,
                                List.of(new ResizeProof.ContainerProof("encoder", "containerd://c2",
                                        "containerd://c2", 0, 0, BEFORE, BEFORE)),
                                new ResizeProof.Revert(ResizeProof.RevertState.REVERTED,
                                        "XamOps put pod prod-sim/throttled-encoder-x7k2p back to its previous size."),
                                "cpu")));
    }

    /**
     * {@code containers[].cpuRequestMillis} read the way origin/main's {@code RcaAnalyzer.cpuRequestMillis}
     * reads {@code after_json}: an array, or an object's {@code containers}, then the first container
     * with a numeric value.
     */
    private static Long legacyCpuRequestMillis(String afterJson) throws Exception {
        JsonNode node = JSON.readTree(afterJson);
        JsonNode containers = node.isArray() ? node : node.get("containers");
        for (JsonNode container : containers) {
            JsonNode value = container.get("cpuRequestMillis");
            if (value != null && value.isNumber()) {
                return value.asLong();
            }
        }
        return null;
    }

    private static ResizeProof deferredPutBack() {
        return new ResizeProof(ResizeProof.Outcome.DEFERRED,
                "The kubelet deferred the resize of pod prod-sim/throttled-encoder-mcw8q because its node has"
                        + " no room for it right now (cpu).", false,
                List.of(new ResizeProof.ContainerResult("encoder", ResizeProof.ContainerState.DEFERRED, null,
                        ASKED, false)),
                List.of(new ResizeProof.PodProof("throttled-encoder-mcw8q", "uid-1", "uid-1", true,
                        List.of(new ResizeProof.ContainerProof("encoder", "containerd://c1", "containerd://c1",
                                0, 0, BEFORE, BEFORE)),
                        new ResizeProof.Revert(ResizeProof.RevertState.REVERTED,
                                "XamOps put pod prod-sim/throttled-encoder-mcw8q back to its previous size."),
                        "cpu")));
    }

    /** A clock the rate-limit test can move forward. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
