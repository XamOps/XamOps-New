package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.DefaultAutoSpottingFlags;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.EligibilityCache;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.EvalMode;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The GCP provider's read methods: plans, snapshot, status, and the refused mutating programs. */
class GcpMigSpotProviderPlanTest {
    private static final String KEY = "xamops-test/us-central1/web-mig";
    private final CloudAccountRepository accounts = mock(CloudAccountRepository.class);
    private final List<CloudAccount> projectRows = new ArrayList<>();

    private CloudAccount account(long id) {
        CloudAccount a = new CloudAccount();
        a.setId(id);
        a.setProvider("GCP");
        a.setGcpProjectId(FakeGcp.PROJECT);
        when(accounts.findById(id)).thenReturn(Optional.of(a));
        projectRows.add(a);
        when(accounts.findByAwsAccountIdOrGcpProjectId(anyString(), anyString())).thenReturn(List.copyOf(projectRows));
        return a;
    }

    static AutoSpottingSwitchProperties switches(boolean gcpV2) {
        return new AutoSpottingSwitchProperties(true, true, true, true, false, gcpV2, false, false, false, false);
    }

    private GcpMigSpotProvider provider(FakeGcp gcp) {
        GcpViewSource views = a -> gcp.view();
        GcpMachineShapes shapes = new GcpMachineShapes();
        GcpEligibilityEvaluator evaluator = new GcpEligibilityEvaluator(views, shapes, mock(EligibilityCache.class),
                new AutoSpottingMetrics(new SimpleMeterRegistry()), false, Clock.systemUTC());
        GcpEstimatePriceService prices = new GcpEstimatePriceService(views, shapes, Clock.systemUTC());
        GcpProviderGate gate = new GcpProviderGate(switches(false), DefaultAutoSpottingFlags.load());
        return new GcpMigSpotProvider(accounts, views, evaluator, new GcpMigStatusReader(), prices, gate, false, false,
                Clock.systemUTC());
    }

    private static PlanRequest enable(CloudAccount a, String profile) {
        return new PlanRequest(new GroupRef(Provider.GCP, a.getId(), KEY, null), OperationKind.ENABLE,
                new Profile(profile, null));
    }

    @Test
    void aPlanFillsEveryBodyFieldWithTheTwoVersionFloor() throws Exception {
        FakeGcp gcp = FakeGcp.healthy();
        CloudAccount a = account(5L);
        PlanBody body = provider(gcp).createPlan(enable(a, "conservative"), gcp.view());
        assertThat(body.eligibility().eligible()).isTrue();
        assertThat(body.currentJson()).isNotNull();
        assertThat(body.diffJson()).isNotNull();
        assertThat(body.revertPathJson()).contains("RESTORE_SNAPSHOT");
        assertThat(body.snapshotToStore().exact()).isTrue();
        assertThat(body.snapshotToStore().provider()).isEqualTo(Provider.GCP);
        JsonNode target = FakeGcp.JSON.readTree(body.targetJson());
        assertThat(target.path("floorMechanism").asText()).isEqualTo("TWO_VERSION_MIG");
        assertThat(target.path("versions").get(0).path("name").asText()).isEqualTo("xas-standard");
        assertThat(target.path("versions").get(0).path("targetSize").path("fixed").asInt()).isEqualTo(3);
        assertThat(target.path("updatePolicy").path("maxUnavailable").path("fixed").asInt()).isZero();
        assertThat(target.path("updatePolicy").path("maxSurge").path("fixed").asInt()).as("Z = 3 zones").isEqualTo(3);
        assertThat(target.path("templateLabels").has("xamops-autospotting")).as("PO-20 safe default").isTrue();
        JsonNode floor = FakeGcp.JSON.readTree(body.onDemandFloorJson());
        assertThat(floor.path("floorN").asInt()).isEqualTo(3);
        assertThat(floor.path("nativeMixUsed").asBoolean()).as("PO-19: no pre-GA provisioningModelMix").isFalse();
        assertThat(floor.path("fullSpot").asBoolean()).isFalse();
        assertThat(body.rollout().surge()).isEqualTo(3);
        assertThat(body.rollout().instancesToReplace()).isEqualTo(3);
        assertThat(body.rollout().headroom()).contains("12 vCPU");
        assertThat(FakeGcp.JSON.readTree(body.rollout().quotaJson()).path("metrics")).isNotEmpty();
    }

    @Test
    void estimatesArePricedPerProfileAndLabelledEstimate() throws Exception {
        FakeGcp gcp = FakeGcp.healthy();
        CloudAccount a = account(5L);
        PlanBody body = provider(gcp).createPlan(enable(a, "conservative"), gcp.view());
        assertThat(body.estimates()).extracting(PlanBody.Estimate::profile)
                .containsExactly("conservative", "balanced", "aggressive");
        assertThat(body.estimates()).allMatch(e -> "Estimate".equals(e.label()) && e.monthlySavings() != null);
        // n2-standard-4: On-Demand 4 x 0.031611 + 16 x 0.004237, Spot 4 x 0.00758 + 16 x 0.001016, 730 h, 3 Spot VMs.
        double perVm = (4 * 0.031611 + 16 * 0.004237 - (4 * 0.00758 + 16 * 0.001016)) * 730;
        assertThat(body.estimates().get(0).monthlySavings()).isCloseTo(Math.round(perVm * 3 * 100) / 100.0,
                org.assertj.core.data.Offset.offset(0.011));
        assertThat(FakeGcp.JSON.readTree(body.estimates().get(0).priceSourcesJson()).get(0).path("source").asText())
                .isEqualTo("gcp-billing-catalog");
    }

    @Test
    void anUnpricedGroupIsNotPricedNeverZero() {
        FakeGcp gcp = FakeGcp.healthy();
        gcp.skus = List.of();
        CloudAccount a = account(5L);
        PlanBody body = provider(gcp).createPlan(enable(a, "conservative"), gcp.view());
        assertThat(body.estimates()).isNotEmpty().allMatch(e -> e.monthlySavings() == null);
    }

    @Test
    void zeroMutatingCallsDuringPlanSnapshotAndStatus() {
        FakeGcp gcp = FakeGcp.healthy();
        CloudAccount a = account(5L);
        GcpMigSpotProvider p = provider(gcp);
        p.checkEligibility(new GroupRef(Provider.GCP, a.getId(), KEY, null), gcp.view(), null, EvalMode.PLAN);
        p.createPlan(enable(a, "balanced"), gcp.view());
        p.takeSnapshot(new GroupRef(Provider.GCP, a.getId(), KEY, null), gcp.view());
        SpotGroup row = new SpotGroup();
        row.setCloudAccountId(a.getId());
        row.setGroupKey(KEY);
        assertThat(p.getStatus(row, gcp.view()).found()).isTrue();
        assertThat(gcp.calls).isNotEmpty().allMatch(c -> c.endsWith(".get") || c.endsWith(".aggregatedList")
                || c.contains(".list") || c.endsWith("testIamPermissions"));
        assertThat(p.reconcile(row, p.getStatus(row, gcp.view()))).isEmpty();
    }

    @Test
    void anAutoscaledMigWithAFloorIsBlockedButFullSpotIsNot() {
        FakeGcp gcp = FakeGcp.healthy();
        String link = FakeGcp.P + "/regions/us-central1/autoscalers/web-as";
        ((ObjectNode) gcp.mig().path("status")).put("autoscaler", link);
        gcp.autoscalers.add(FakeGcp.JSON.createObjectNode().put("selfLink", link)
                .set("autoscalingPolicy", FakeGcp.JSON.createObjectNode().put("minNumReplicas", 2).put("maxNumReplicas", 20)));
        CloudAccount a = account(5L);
        PlanBody conservative = provider(gcp).createPlan(enable(a, "conservative"), gcp.view());
        assertThat(conservative.eligibility().blockers()).extracting(c -> c.code()).contains("GCP_FLOOR_SHAPE_UNVERIFIED");
        assertThat(conservative.onDemandFloorJson()).contains("\"atAutoscalerMax\":{\"spot\":17,\"standard\":3}");
        PlanBody fullSpot = provider(gcp).createPlan(enable(a, "full_spot"), gcp.view());
        assertThat(fullSpot.eligibility().blockers()).extracting(c -> c.code()).doesNotContain("GCP_FLOOR_SHAPE_UNVERIFIED");
        assertThat(fullSpot.onDemandFloorJson()).contains("\"fullSpot\":true");
    }

    @Test
    void aSecondCloudAccountOnTheSameProjectIs409DuplicateProviderAccount() {
        FakeGcp gcp = FakeGcp.healthy();
        CloudAccount first = account(5L);
        CloudAccount second = account(9L);
        assertThat(provider(gcp).createPlan(enable(first, "conservative"), gcp.view()).eligibility().eligible()).isTrue();
        assertThatThrownBy(() -> provider(gcp).createPlan(enable(second, "conservative"), gcp.view()))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("DUPLICATE_PROVIDER_ACCOUNT");
                });
    }

    @Test
    void mutatingProgramsAreRefusedWhileTheFlagsAreFalse() {
        FakeGcp gcp = FakeGcp.healthy();
        GcpMigSpotProvider p = provider(gcp);
        OperationContext op = new OperationContext(UUID.randomUUID(), OperationKind.ENABLE, new SpotGroup(), "{}", null);
        assertThatThrownBy(() -> p.executeConversion(op)).isInstanceOfSatisfying(AutoSpottingApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
        assertThatThrownBy(() -> p.restore(op, null)).isInstanceOfSatisfying(AutoSpottingApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
        assertThat(new GcpProviderGate(switches(false), DefaultAutoSpottingFlags.load()).open()).isFalse();
        assertThat(DefaultAutoSpottingFlags.load().isGcpConversionsEnabled()).isFalse();
        assertThat(gcp.calls).isEmpty();
    }

    @Test
    void aRevertPlanIsNeverBlockedByConversionChecks() {
        FakeGcp gcp = FakeGcp.healthy();
        gcp.mig().put("targetSize", 1);
        CloudAccount a = account(5L);
        PlanBody revert = provider(gcp).createPlan(new PlanRequest(new GroupRef(Provider.GCP, a.getId(), KEY, null),
                OperationKind.REVERT, null), gcp.view());
        assertThat(revert.eligibility().blockers()).isEmpty();
        assertThat(revert.revertPathJson()).contains("REVERT");
    }
}
