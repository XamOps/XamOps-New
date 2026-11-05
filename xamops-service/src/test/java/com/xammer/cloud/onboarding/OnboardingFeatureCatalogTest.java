package com.xammer.cloud.onboarding;

import com.xammer.cloud.service.onboarding.OnboardingEntitlementService;
import com.xammer.cloud.service.onboarding.OnboardingFeatureCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BP-8 (design §5.5): feature availability, the phase-2 agent env flags, and the OQ-1 placeholder
 * invariants — a tier enum with safe default names and NO prices; {@code entitled()} returns true.
 */
class OnboardingFeatureCatalogTest {

    private final OnboardingFeatureCatalog catalog = new OnboardingFeatureCatalog();

    @Test
    void nodeConsolidation_needsPfComputePass() {
        assertThat(catalog.availability(OnboardingFeatureCatalog.NODE_CONSOLIDATION, true, true).available()).isTrue();
        OnboardingFeatureCatalog.Availability blocked =
                catalog.availability(OnboardingFeatureCatalog.NODE_CONSOLIDATION, true, false);
        assertThat(blocked.available()).isFalse();
        assertThat(blocked.reason()).isEqualTo("PF_COMPUTE");
    }

    @Test
    void workloadAutoscaling_needsReadWriteAccount() {
        OnboardingFeatureCatalog.Availability blocked =
                catalog.availability(OnboardingFeatureCatalog.WORKLOAD_AUTOSCALING, false, true);
        assertThat(blocked.available()).isFalse();
        assertThat(blocked.reason()).isEqualTo("READ_ONLY_CONNECTION");
        assertThat(catalog.availability(OnboardingFeatureCatalog.WORKLOAD_AUTOSCALING, true, true).available()).isTrue();
    }

    @Test
    void agentEnv_dryRunAlwaysTrue_consolidationSetsEvictorAndOptimizer_removalFromPredicate() {
        Map<String, String> env = catalog.agentEnv(
                List.of(OnboardingFeatureCatalog.NODE_CONSOLIDATION), true, "ap-south-1");
        assertThat(env).containsEntry("DRY_RUN", "true");
        assertThat(env).containsEntry("ENABLE_EVICTOR", "true");
        assertThat(env).containsEntry("ENABLE_NODE_OPTIMIZER", "true");
        assertThat(env).containsEntry("ENABLE_NODE_REMOVAL", "true");
        assertThat(env).containsEntry("AWS_REGION", "ap-south-1");

        Map<String, String> noRemoval = catalog.agentEnv(
                List.of(OnboardingFeatureCatalog.NODE_CONSOLIDATION), false, "ap-south-1");
        assertThat(noRemoval).containsEntry("ENABLE_NODE_REMOVAL", "false");
    }

    @Test
    void agentEnv_workloadAutoscaling_enablesHpaOnly_vpaStaysFalse() {
        Map<String, String> env = catalog.agentEnv(
                List.of(OnboardingFeatureCatalog.WORKLOAD_AUTOSCALING), false, "ap-south-1");
        assertThat(env).containsEntry("ENABLE_HPA_MANAGER", "true");
        assertThat(env).containsEntry("ENABLE_VPA_MANAGER", "false");
        assertThat(env).containsEntry("DRY_RUN", "true");
    }

    @Test
    void oq1_tierEnumHasSafeNames_noPrices() {
        // The catalog must not carry a price anywhere — only placeholder tier names.
        for (OnboardingFeatureCatalog.FeatureInfo info : catalog.all()) {
            assertThat(info.tier()).isIn(
                    OnboardingFeatureCatalog.Tier.FREE_READ_ONLY,
                    OnboardingFeatureCatalog.Tier.PAID_PLAN_TBD);
            assertThat(info.label()).doesNotContainPattern("\\$|USD|/mo|per month");
        }
    }

    @Test
    void entitlement_v1ReturnsTrue() {
        OnboardingEntitlementService entitlement = new OnboardingEntitlementService();
        assertThat(entitlement.entitled(1L, OnboardingFeatureCatalog.NODE_CONSOLIDATION)).isTrue();
    }
}
