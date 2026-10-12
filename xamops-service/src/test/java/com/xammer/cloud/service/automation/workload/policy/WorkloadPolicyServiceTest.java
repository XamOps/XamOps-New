package com.xammer.cloud.service.automation.workload.policy;

import com.xammer.cloud.domain.WorkloadVerticalPolicy;
import com.xammer.cloud.repository.WorkloadVerticalPolicyRepository;
import com.xammer.cloud.service.automation.workload.ApplyMethod;
import com.xammer.cloud.service.automation.workload.WorkloadMode;
import com.xammer.cloud.service.automation.workload.WorkloadPolicy;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Policy resolution: the row when there is one, the defaults when there is not, and the two must
 * agree on every value.
 */
@ExtendWith(MockitoExtension.class)
class WorkloadPolicyServiceTest {

    private static final Long CLUSTER_CONFIG_ID = 10L;
    private static final WorkloadRef REF = WorkloadRef.of("team-a", "Deployment", "overprovisioned-api");

    @Mock
    WorkloadVerticalPolicyRepository repository;

    @Test
    @DisplayName("no row means the engine defaults, with the source recorded as DEFAULTS")
    void absentRowMeansDefaults() {
        when(repository.findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                CLUSTER_CONFIG_ID, "team-a", "Deployment", "overprovisioned-api"))
                .thenReturn(Optional.empty());

        WorkloadPolicy policy = service().resolve(CLUSTER_CONFIG_ID, REF);

        assertThat(policy.source()).isEqualTo(WorkloadPolicy.Source.DEFAULTS);
        assertThat(policy.mode())
                .as("a new automation feature must do nothing until somebody asks for it")
                .isEqualTo(WorkloadMode.OFF);
        assertThat(policy.percentile()).isEqualTo(95);
        assertThat(policy.windowHours()).isEqualTo(24);
        assertThat(policy.headroomPct()).isEqualTo(25);
    }

    @Test
    @DisplayName("a bare row resolves to exactly the same values as no row at all")
    void aBareRowMatchesTheDefaults() {
        // The two places defaults live -- the SQL column defaults mirrored on the entity, and the
        // Java constants -- must agree, or a row written by any path would behave differently from
        // a workload with no row.
        when(repository.findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                CLUSTER_CONFIG_ID, "team-a", "Deployment", "overprovisioned-api"))
                .thenReturn(Optional.of(bareRow()));

        WorkloadPolicy fromRow = service().resolve(CLUSTER_CONFIG_ID, REF);
        WorkloadPolicy fromDefaults = WorkloadPolicyDefaults.policy();

        assertThat(fromRow.mode()).isEqualTo(fromDefaults.mode());
        assertThat(fromRow.headroomPct()).isEqualTo(fromDefaults.headroomPct());
        assertThat(fromRow.percentile()).isEqualTo(fromDefaults.percentile());
        assertThat(fromRow.windowHours()).isEqualTo(fromDefaults.windowHours());
        assertThat(fromRow.cpuFloorMillis()).isEqualTo(fromDefaults.cpuFloorMillis());
        assertThat(fromRow.memFloorBytes()).isEqualTo(fromDefaults.memFloorBytes());
        assertThat(fromRow.cpuIncrease()).isEqualTo(fromDefaults.cpuIncrease());
        assertThat(fromRow.cpuDecrease()).isEqualTo(fromDefaults.cpuDecrease());
        assertThat(fromRow.memIncrease()).isEqualTo(fromDefaults.memIncrease());
        assertThat(fromRow.memDecrease()).isEqualTo(fromDefaults.memDecrease());
        assertThat(fromRow.applyMethod()).isEqualTo(fromDefaults.applyMethod());
        assertThat(fromRow.cooldownMinutes()).isEqualTo(fromDefaults.cooldownMinutes());
        assertThat(fromRow.maxLimitIncreaseFactor()).isEqualTo(fromDefaults.maxLimitIncreaseFactor());
        assertThat(fromRow.protectSingleReplica()).isEqualTo(fromDefaults.protectSingleReplica());
        assertThat(fromRow.nodeCapacityFraction()).isEqualTo(fromDefaults.nodeCapacityFraction());

        // The one value that must differ: a row says somebody chose these numbers.
        assertThat(fromRow.source()).isEqualTo(WorkloadPolicy.Source.WORKLOAD_ROW);
    }

    @Test
    @DisplayName("a stored row's values are honoured, including lastAppliedAt")
    void storedValuesAreHonoured() {
        WorkloadVerticalPolicy row = bareRow();
        row.setMode("AUTO");
        row.setHeadroomPct(40);
        row.setPercentile(99);
        row.setWindowHours(168);
        row.setApplyMethod("IN_PLACE_AND_PERSIST");
        row.setCooldownMinutes(15);
        row.setLastAppliedAt(Instant.parse("2026-10-10T05:00:00Z"));
        when(repository.findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                CLUSTER_CONFIG_ID, "team-a", "Deployment", "overprovisioned-api"))
                .thenReturn(Optional.of(row));

        WorkloadPolicy policy = service().resolve(CLUSTER_CONFIG_ID, REF);

        assertThat(policy.mode()).isEqualTo(WorkloadMode.AUTO);
        assertThat(policy.headroomPct()).isEqualTo(40);
        assertThat(policy.percentile()).isEqualTo(99);
        assertThat(policy.windowHours()).isEqualTo(168);
        assertThat(policy.windowLabel()).isEqualTo("168h");
        assertThat(policy.applyMethod()).isEqualTo(ApplyMethod.IN_PLACE_AND_PERSIST);
        assertThat(policy.cooldownMinutes()).isEqualTo(15);
        assertThat(policy.lastAppliedAt()).isEqualTo(Instant.parse("2026-10-10T05:00:00Z"));
    }

    @Test
    @DisplayName("an unrecognised mode or apply method falls back to the SAFE value, not an exception")
    void unknownEnumValuesFallBackSafely() {
        // A row written by a future version of the product must not be able to take the read
        // endpoint down, and the safe value is the one that does nothing.
        WorkloadVerticalPolicy row = bareRow();
        row.setMode("SOMETHING_NEW");
        row.setApplyMethod("CANARY");
        when(repository.findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                CLUSTER_CONFIG_ID, "team-a", "Deployment", "overprovisioned-api"))
                .thenReturn(Optional.of(row));

        WorkloadPolicy policy = service().resolve(CLUSTER_CONFIG_ID, REF);

        assertThat(policy.mode()).isEqualTo(WorkloadMode.OFF);
        assertThat(policy.applyMethod()).isEqualTo(ApplyMethod.IN_PLACE);
    }

    @Test
    @DisplayName("resolveAll keys policies by namespace/kind/name")
    void resolveAllKeysByFullIdentity() {
        WorkloadVerticalPolicy a = bareRow();
        a.setMode("RECOMMEND");
        WorkloadVerticalPolicy b = bareRow();
        b.setNamespace("team-b");
        b.setWorkloadName("php-apache");
        when(repository.findByClusterConfigId(CLUSTER_CONFIG_ID)).thenReturn(List.of(a, b));

        Map<String, WorkloadPolicy> all = service().resolveAll(CLUSTER_CONFIG_ID);

        assertThat(all).containsOnlyKeys(
                "team-a/Deployment/overprovisioned-api", "team-b/Deployment/php-apache");
        assertThat(all.get(WorkloadPolicyService.key(REF)).mode()).isEqualTo(WorkloadMode.RECOMMEND);
    }

    // ---------------------------------------------------------------- recording an apply

    @Test
    @DisplayName("an apply against an existing row stamps last_applied_at and changes nothing else")
    void recordingAnApplyUpdatesTheExistingRow() {
        WorkloadVerticalPolicy row = bareRow();
        row.setMode("RECOMMEND");
        row.setHeadroomPct(40);
        when(repository.findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                CLUSTER_CONFIG_ID, "team-a", "Deployment", "overprovisioned-api"))
                .thenReturn(Optional.of(row));

        service().recordApplied(5L, CLUSTER_CONFIG_ID, REF, WorkloadPolicyDefaults.policy(),
                Instant.parse("2026-10-11T12:00:00Z"));

        ArgumentCaptor<WorkloadVerticalPolicy> saved =
                ArgumentCaptor.forClass(WorkloadVerticalPolicy.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getLastAppliedAt()).isEqualTo(Instant.parse("2026-10-11T12:00:00Z"));
        assertThat(saved.getValue().getMode())
                .as("recording an apply must not rewrite the policy somebody chose")
                .isEqualTo("RECOMMEND");
        assertThat(saved.getValue().getHeadroomPct()).isEqualTo(40);
    }

    @Test
    @DisplayName("an apply against a workload with no row creates one, with the mode actually in force")
    void recordingAnApplyCreatesARowWhenThereIsNone() {
        // Policy is absent by default, so most workloads have no row at all -- and an apply against
        // one of them still has to be remembered, or the engine's cooldown guard has nothing to read.
        when(repository.findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                CLUSTER_CONFIG_ID, "team-a", "Deployment", "overprovisioned-api"))
                .thenReturn(Optional.empty());

        service().recordApplied(5L, CLUSTER_CONFIG_ID, REF,
                WorkloadPolicyDefaults.policy(WorkloadMode.RECOMMEND),
                Instant.parse("2026-10-11T12:00:00Z"));

        ArgumentCaptor<WorkloadVerticalPolicy> saved =
                ArgumentCaptor.forClass(WorkloadVerticalPolicy.class);
        verify(repository).save(saved.capture());
        WorkloadVerticalPolicy row = saved.getValue();
        assertThat(row.getClientId()).as("tenant scoping is denormalised onto the row").isEqualTo(5L);
        assertThat(row.getClusterConfigId()).isEqualTo(CLUSTER_CONFIG_ID);
        assertThat(row.getNamespace()).isEqualTo("team-a");
        assertThat(row.getWorkloadKind()).isEqualTo("Deployment");
        assertThat(row.getWorkloadName()).isEqualTo("overprovisioned-api");
        assertThat(row.getMode())
                .as("the mode that was in force, never AUTO: pressing Apply once must not give a "
                        + "workload a policy nobody chose")
                .isEqualTo("RECOMMEND");
        assertThat(row.getLastAppliedAt()).isEqualTo(Instant.parse("2026-10-11T12:00:00Z"));
    }

    @Test
    @DisplayName("a row created by an apply reads back as exactly the policy that was in force (report 4c)")
    void aCreatedRowReadsBackAsThePolicyInForce() {
        // UAT's no-row policy: RECOMMEND over 72h. The row used to copy the mode alone, so the window
        // fell back to the column's 24h, and the apply path passed the bare defaults, so mode was OFF.
        WorkloadPolicy inForce = WorkloadPolicyDefaults.policy(WorkloadMode.RECOMMEND).withWindowHours(72);
        when(repository.findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                CLUSTER_CONFIG_ID, "team-a", "Deployment", "overprovisioned-api"))
                .thenReturn(Optional.empty());
        Instant appliedAt = Instant.parse("2026-10-11T12:00:00Z");

        service().recordApplied(5L, CLUSTER_CONFIG_ID, REF, inForce, appliedAt);

        ArgumentCaptor<WorkloadVerticalPolicy> saved =
                ArgumentCaptor.forClass(WorkloadVerticalPolicy.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getMode()).isEqualTo("RECOMMEND");
        assertThat(saved.getValue().getWindowHours()).isEqualTo(72);
        when(repository.findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                CLUSTER_CONFIG_ID, "team-a", "Deployment", "overprovisioned-api"))
                .thenReturn(Optional.of(saved.getValue()));
        WorkloadPolicy readBack = service().resolve(CLUSTER_CONFIG_ID, REF);
        assertThat(readBack)
                .as("every policy value survives the round trip; only the source and the apply "
                        + "stamp differ")
                .isEqualTo(new WorkloadPolicy(inForce.mode(), inForce.headroomPct(),
                        inForce.percentile(), inForce.windowHours(), inForce.cpuFloorMillis(),
                        inForce.memFloorBytes(), inForce.cpuIncrease(), inForce.cpuDecrease(),
                        inForce.memIncrease(), inForce.memDecrease(), inForce.applyMethod(),
                        inForce.cooldownMinutes(), inForce.maxLimitIncreaseFactor(),
                        inForce.protectSingleReplica(), inForce.nodeCapacityFraction(), appliedAt,
                        WorkloadPolicy.Source.WORKLOAD_ROW));
    }

    @Test
    @DisplayName("an apply against an existing row leaves every policy value as it was")
    void anApplyNeverRewritesAnExistingRowsPolicy() {
        WorkloadVerticalPolicy row = bareRow();
        row.setMode("AUTO");
        row.setPercentile(90);
        row.setWindowHours(48);
        row.setHeadroomPct(40);
        row.setCooldownMinutes(30);
        when(repository.findByClusterConfigIdAndNamespaceAndWorkloadKindAndWorkloadName(
                CLUSTER_CONFIG_ID, "team-a", "Deployment", "overprovisioned-api"))
                .thenReturn(Optional.of(row));
        WorkloadPolicy beforeApply = service().resolve(CLUSTER_CONFIG_ID, REF);
        Instant appliedAt = Instant.parse("2026-10-11T12:00:00Z");

        service().recordApplied(5L, CLUSTER_CONFIG_ID, REF,
                WorkloadPolicyDefaults.policy(WorkloadMode.RECOMMEND).withWindowHours(72), appliedAt);

        assertThat(service().resolve(CLUSTER_CONFIG_ID, REF))
                .as("the in-force policy passed to recordApplied is ignored when a row exists")
                .isEqualTo(beforeApply.withLastAppliedAt(appliedAt));
    }

    @Test
    @DisplayName("recording an apply with no cluster, workload or timestamp is refused")
    void recordingAnApplyNeedsItsIdentifiers() {
        WorkloadPolicyService service = new WorkloadPolicyService(repository);
        WorkloadPolicy defaults = WorkloadPolicyDefaults.policy();
        Instant now = Instant.parse("2026-10-11T12:00:00Z");

        assertThatThrownBy(() -> service.recordApplied(5L, null, REF, defaults, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordApplied(5L, CLUSTER_CONFIG_ID, null, defaults, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordApplied(5L, CLUSTER_CONFIG_ID, REF, defaults, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the protected-namespace set is a code constant, not a policy field")
    void protectedNamespacesAreNotPolicy() {
        assertThat(WorkloadPolicyDefaults.PROTECTED_NAMESPACES)
                .containsExactlyInAnyOrder("kube-system", "monitoring", "amazon-cloudwatch",
                        "vpa", "xamops-system");
    }

    private WorkloadPolicyService service() {
        return new WorkloadPolicyService(repository);
    }

    private static WorkloadVerticalPolicy bareRow() {
        WorkloadVerticalPolicy row = new WorkloadVerticalPolicy();
        row.setId(1L);
        row.setClientId(1L);
        row.setClusterConfigId(CLUSTER_CONFIG_ID);
        row.setNamespace("team-a");
        row.setWorkloadKind("Deployment");
        row.setWorkloadName("overprovisioned-api");
        return row;
    }
}
