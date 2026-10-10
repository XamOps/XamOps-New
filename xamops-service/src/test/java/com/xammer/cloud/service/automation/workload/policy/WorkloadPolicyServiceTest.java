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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
