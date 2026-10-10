package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.ContainerReason;
import com.xammer.cloud.service.automation.workload.Finding;
import com.xammer.cloud.service.automation.workload.SkipReason;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.usage.UsageUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nine shared reason codes, asserted as <strong>literal strings</strong>.
 *
 * <p>These names are the wire contract with the frontend, so a rename or a reorder is a breaking
 * change to a workflow that is built against them. Asserting them as literals rather than against
 * {@code values()} is the point: a test that derived its expectations from the enum would pass
 * through any rename.
 *
 * <p>The CloudK8s UI design places a shared fixture at
 * {@code src/test/resources/k8s/k8s-reason-codes.json}. That file belongs to the UI branch and is
 * deliberately <strong>not</strong> created here, to avoid a merge conflict on a file that branch
 * owns; this test is the engine side of the handshake. Folding the enum into the fixture is a
 * follow-up once both branches land.
 */
class WorkloadReasonCodeContractTest {

    /** Verbatim, in order. */
    private static final List<String> SHARED_CODES = List.of(
            "READ_ONLY_CONNECTION",
            "NO_USER_PERMISSION",
            "CONTROLLER_NOT_READY",
            "DEMO",
            "CLUSTER_ACCESS_NOT_CONFIGURED",
            "CLUSTER_UNREACHABLE",
            "NO_METRICS_SOURCE",
            "INSUFFICIENT_HISTORY",
            "GITOPS_MANAGED");

    @Test
    @DisplayName("the nine shared codes are present, spelled exactly, and in order")
    void theSharedCodesAreVerbatim() {
        List<String> declared = Arrays.stream(ReasonCode.values()).map(Enum::name).toList();

        assertThat(declared).containsExactlyElementsOf(SHARED_CODES);
    }

    @Test
    @DisplayName("a refusal carries the K8S_ prefix; a payload body does not")
    void bothFormsAreAvailable() {
        assertThat(ReasonCode.CLUSTER_UNREACHABLE.name()).isEqualTo("CLUSTER_UNREACHABLE");
        assertThat(ReasonCode.CLUSTER_UNREACHABLE.refusalCode()).isEqualTo("K8S_CLUSTER_UNREACHABLE");

        for (ReasonCode code : ReasonCode.values()) {
            assertThat(code.refusalCode()).isEqualTo("K8S_" + code.name());
        }
    }

    @Test
    @DisplayName("the engine introduces no new shared gate codes")
    void noNewSharedCodesAreIntroduced() {
        // The agent channel reuses the UI's existing code rather than adding a tenth.
        assertThat(ReasonCode.AGENT_NOT_INSTALLED).isEqualTo("AGENT_NOT_INSTALLED");
        assertThat(Arrays.stream(ReasonCode.values()).map(Enum::name))
                .doesNotContain("AGENT_NOT_INSTALLED");
        assertThat(ReasonCode.values()).hasSize(9);
    }

    @Test
    @DisplayName("both typed access kinds and both usage kinds are shared codes, by name")
    void typedFailuresAreSharedCodes() {
        for (ClusterAccessException.Kind kind : ClusterAccessException.Kind.values()) {
            assertThat(SHARED_CODES)
                    .as("ClusterAccessException.Kind.%s must be a shared reason code", kind)
                    .contains(kind.name());
        }
        for (UsageUnavailableException.Kind kind : UsageUnavailableException.Kind.values()) {
            assertThat(SHARED_CODES)
                    .as("UsageUnavailableException.Kind.%s must be a shared reason code", kind)
                    .contains(kind.name());
        }
    }

    @Test
    @DisplayName("engine-internal enums are documented as free-form and are not shared gate codes")
    void engineInternalEnumsAreNotGateCodes() {
        // These appear in skipReason, findings[] and container reasons[], and the UI renders an
        // unrecognised value as { kind: 'unknown', raw }, so this list can grow without a frontend
        // change. INSUFFICIENT_HISTORY is the one deliberate overlap: a shared gate code at cluster
        // level AND a per-workload skipReason, same spelling, same meaning, two scopes.
        assertThat(Arrays.stream(SkipReason.values()).map(Enum::name))
                .containsExactly("PROTECTED_NAMESPACE", "SINGLE_REPLICA", "ROLLOUT_IN_PROGRESS",
                        "LOCKED", "HPA_VOLATILE", "INSUFFICIENT_HISTORY", "POLICY_OFF");

        assertThat(Arrays.stream(Finding.values()).map(Enum::name))
                .containsExactly("REQUESTS_UNSET", "IDLE", "THROTTLED", "OOM_RISK",
                        "HPA_COEXISTENCE", "NODE_CAPACITY_CAPPED");

        assertThat(Arrays.stream(ContainerReason.values()).map(Enum::name))
                .containsExactly("HPA_CPU_MANAGED", "HPA_MEMORY_MANAGED", "FLOOR_CLAMPED",
                        "BELOW_FLOOR_NO_RAISE", "LIMIT_MOVED", "DEMAND_CENSORED_BY_LIMIT",
                        "NODE_CAPACITY_CAPPED", "QOS_PRESERVED_NO_CHANGE", "COOLDOWN",
                        "WITHIN_HYSTERESIS", "REQUESTS_UNSET", "OOM_RISK");

        assertThat(SHARED_CODES)
                .as("the only name shared between the gate codes and the skip reasons")
                .contains("INSUFFICIENT_HISTORY");
    }
}
