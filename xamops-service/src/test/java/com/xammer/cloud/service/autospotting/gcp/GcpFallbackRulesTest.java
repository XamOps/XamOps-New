package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.core.FallbackProperties;
import com.xammer.cloud.service.autospotting.gcp.GcpFallbackRules.Signals;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure GCP fallback and health rules (design-gcp.md §7.1, §7.2; plan-phase-28.md 28.2; AC-HLT-02, AC-HLT-03,
 * AC-HLT-04): enter on a sustained shortfall with a capacity or quota cause, the fast path, DEGRADED, the quota
 * case, the exit signal, and preemption never starting a fallback by itself.
 */
class GcpFallbackRulesTest {
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private final GcpFallbackRules rules = new GcpFallbackRules(GcpHealthProperties.defaults(), FallbackProperties.defaults());

    private static Signals signals(int target, int shortfall, long shortMinutes, long fastMinutes, int capacity, int quota,
                                   int standardQuota, boolean spot) {
        return new Signals(NOW, target, shortfall, shortfall > 0 ? NOW.minus(Duration.ofMinutes(shortMinutes)) : null,
                fastMinutes >= 0 ? NOW.minus(Duration.ofMinutes(fastMinutes)) : null,
                shortfall == 0 ? NOW.minus(Duration.ofMinutes(shortMinutes)) : null, capacity, quota, standardQuota,
                standardQuota > 0 ? "CPUS" : null, spot, null);
    }

    private List<ReconcileAction> actions(GroupState state, Signals s) {
        return rules.evaluate(state, null, null, ConfigMode.MANAGED, NOW.minus(Duration.ofMinutes(5)), s).actions();
    }

    @Test
    void aShortfallOfFifteenMinutesWithThreeCapacityErrorsEntersFallback() {
        assertThat(actions(GroupState.ACTIVE, signals(6, 1, 15, -1, 3, 0, 0, true))).containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
        assertThat(rules.evaluate(GroupState.ACTIVE, null, null, ConfigMode.MANAGED, NOW,
                signals(6, 1, 15, -1, 3, 0, 0, true)).code()).isEqualTo("GCP-FB-1");
    }

    @Test
    void fourteenMinutesOrTwoErrorsDoNotEnter() {
        assertThat(actions(GroupState.ACTIVE, signals(6, 1, 14, -1, 3, 0, 0, true))).isEmpty();
        assertThat(actions(GroupState.ACTIVE, signals(6, 1, 15, -1, 2, 0, 0, true))).isEmpty();
    }

    @Test
    void aSinglePreemptibleQuotaErrorIsEnoughOnceTheShortfallIsSustained() {
        assertThat(actions(GroupState.ACTIVE, signals(6, 1, 15, -1, 0, 1, 0, true))).containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
    }

    @Test
    void aShortfallOfAQuarterOfTheTargetForTenMinutesWithAnyCapacityErrorEntersFast() {
        // 2 of 6 is 33%: ten minutes and one capacity error.
        assertThat(actions(GroupState.ACTIVE, signals(6, 2, 10, 10, 1, 0, 0, true))).containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
        assertThat(actions(GroupState.ACTIVE, signals(6, 2, 10, 9, 1, 0, 0, true))).isEmpty();
        assertThat(actions(GroupState.ACTIVE, signals(6, 2, 10, 10, 0, 0, 0, true))).as("no capacity cause").isEmpty();
        // 1 of 6 is below 25%: the slow path only.
        assertThat(actions(GroupState.ACTIVE, signals(6, 1, 10, 10, 1, 0, 0, true))).isEmpty();
    }

    @Test
    void aShortfallWithNoCapacityOrQuotaCauseBecomesDegradedNotFallback() {
        assertThat(actions(GroupState.ACTIVE, signals(6, 1, 15, -1, 0, 0, 0, true)))
                .containsExactly(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT);
        assertThat(actions(GroupState.ACTIVE, signals(6, 1, 14, -1, 0, 0, 0, true))).isEmpty();
    }

    @Test
    void whenStandardQuotaIsExhaustedTooFallbackCannotHelpAndTheGroupIsDegradedWithGcpQuota() {
        GcpFallbackRules.Decision d = rules.evaluate(GroupState.ACTIVE, null, null, ConfigMode.MANAGED, NOW,
                signals(6, 1, 15, -1, 3, 0, 1, true));
        assertThat(d.actions()).containsExactly(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT);
        assertThat(d.code()).isEqualTo("GCP_QUOTA");
        assertThat(d.actions()).doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
    }

    @Test
    void aGroupWithNoSpotVersionHasNothingToFallBackFrom() {
        assertThat(actions(GroupState.ACTIVE, signals(6, 1, 15, -1, 3, 0, 0, false))).doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
    }

    @Test
    void aPausedGroupStillFallsBackUnderTheAccountSwitchOrAnOperatorButNotUnderFreezeOrDrift() {
        Signals s = signals(6, 1, 15, -1, 3, 0, 0, true);
        assertThat(rules.evaluate(GroupState.PAUSED, PauseReason.ACCOUNT_SWITCH, GroupState.ACTIVE, ConfigMode.MANAGED, NOW, s)
                .actions()).containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
        assertThat(rules.evaluate(GroupState.PAUSED, PauseReason.OPERATOR, GroupState.DEGRADED, ConfigMode.MANAGED, NOW, s)
                .actions()).containsExactly(ReconcileAction.ENQUEUE_FALLBACK);
        assertThat(rules.evaluate(GroupState.PAUSED, PauseReason.FREEZE, GroupState.ACTIVE, ConfigMode.MANAGED, NOW, s)
                .actions()).isEmpty();
        assertThat(rules.evaluate(GroupState.PAUSED, PauseReason.DRIFT, GroupState.ACTIVE, ConfigMode.MANAGED, NOW, s)
                .actions()).isEmpty();
    }

    @Test
    void aFallbackGroupStillShortTwentyMinutesIntoFallbackBecomesDegradedAndAlerts() {
        Signals s = signals(6, 1, 25, -1, 0, 0, 0, true);
        assertThat(rules.evaluate(GroupState.FALLBACK, null, null, ConfigMode.FALLBACK, NOW.minus(Duration.ofMinutes(20)), s)
                .actions()).containsExactly(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT);
        assertThat(rules.evaluate(GroupState.FALLBACK, null, null, ConfigMode.FALLBACK, NOW.minus(Duration.ofMinutes(19)), s)
                .actions()).isEmpty();
    }

    @Test
    void theExitSignalNeedsTheDwellAndThirtyQuietMinutes() {
        Signals quiet = signals(6, 0, 30, -1, 0, 0, 0, true);
        assertThat(rules.evaluate(GroupState.FALLBACK, null, null, ConfigMode.FALLBACK, NOW.minus(Duration.ofMinutes(60)), quiet)
                .actions()).containsExactly(ReconcileAction.ENQUEUE_FALLBACK_EXIT);
        assertThat(rules.evaluate(GroupState.FALLBACK, null, null, ConfigMode.FALLBACK, NOW.minus(Duration.ofMinutes(59)), quiet)
                .actions()).as("the 60 min dwell").isEmpty();
        assertThat(rules.evaluate(GroupState.FALLBACK, null, null, ConfigMode.FALLBACK, NOW.minus(Duration.ofMinutes(90)),
                signals(6, 0, 29, -1, 0, 0, 0, true)).actions()).as("shortfall 0 for 30 min").isEmpty();
    }

    @Test
    void aDegradedGroupRecoversWhenNothingIsShort_andNeverEntersFallbackFromFallbackMode() {
        assertThat(actions(GroupState.DEGRADED, signals(6, 0, 5, -1, 0, 0, 0, true))).containsExactly(ReconcileAction.MARK_RECOVERED);
        assertThat(rules.evaluate(GroupState.DEGRADED, null, null, ConfigMode.FALLBACK, NOW,
                signals(6, 1, 30, -1, 3, 0, 0, true)).actions()).doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
    }

    @Test
    void preemptionAloneNeverStartsAFallback_aThreeDayHighRateIsAWarnOnly() {
        Signals high = new Signals(NOW, 6, 0, null, null, NOW.minus(Duration.ofHours(1)), 0, 0, 0, null, true,
                NOW.minus(Duration.ofDays(3)));
        GcpFallbackRules.Decision d = rules.evaluate(GroupState.ACTIVE, null, null, ConfigMode.MANAGED, NOW, high);
        assertThat(d.actions()).containsExactly(ReconcileAction.ALERT);
        Signals recent = new Signals(NOW, 6, 0, null, null, NOW, 0, 0, 0, null, true, NOW.minus(Duration.ofDays(2)));
        assertThat(rules.evaluate(GroupState.ACTIVE, null, null, ConfigMode.MANAGED, NOW, recent).actions()).isEmpty();
    }

    @Test
    void errorsAreDedupedByInstanceAndTimestampAndWindowed() {
        ArrayNode errors = GcpConversionFixture.JSON.createArrayNode();
        for (int i = 0; i < 2; i++) { // the same (instance, timestamp) twice is one
            error(errors, "ZONE_RESOURCE_POOL_EXHAUSTED", "web-1", NOW.minusSeconds(60).toString(), null);
        }
        error(errors, "ZONE_RESOURCE_POOL_EXHAUSTED_WITH_DETAILS", "web-2", NOW.minusSeconds(120).toString(), null);
        error(errors, "ZONE_RESOURCE_POOL_EXHAUSTED", "web-3", NOW.minus(Duration.ofMinutes(16)).toString(), null); // outside
        error(errors, "QUOTA_EXCEEDED", "web-4", NOW.minusSeconds(30).toString(), "PREEMPTIBLE_CPUS");
        error(errors, "QUOTA_EXCEEDED", "web-5", NOW.minusSeconds(30).toString(), "N2_CPUS");
        error(errors, "VM_EXTERNAL_IP_ACCESS_POLICY_CONSTRAINT", "web-6", NOW.toString(), null);
        GcpFallbackRules.ErrorCounts c = rules.count(errors, NOW);
        assertThat(c.capacity()).isEqualTo(2);
        assertThat(c.preemptibleQuota()).isEqualTo(1);
        assertThat(c.standardQuota()).isEqualTo(1);
        assertThat(c.standardQuotaMetric()).isEqualTo("N2_CPUS");
    }

    private static void error(ArrayNode errors, String code, String instance, String ts, String metric) {
        ObjectNode e = errors.addObject();
        e.put("code", code);
        e.put("instance", instance);
        e.put("timestamp", ts);
        if (metric != null) {
            e.put("quotaMetric", metric);
        }
    }

    /** AC-HLT-02: the carried since-time makes four 5-minute sweeps with a sustained stockout one enter, and not before. */
    @Test
    void fourSweepsOfASustainedStockoutYieldOneEnterAtTheFourthSweep() {
        GcpConversionFixture f = new GcpConversionFixture();
        // 5 of 6 instances run; three distinct stockout errors.
        f.gcp.managedInstances.remove(5);
        for (int i = 0; i < 3; i++) {
            f.gcp.errors.add(GcpConversionFixture.JSON.createObjectNode().<ObjectNode>set("error",
                    GcpConversionFixture.JSON.createObjectNode().put("code", "ZONE_RESOURCE_POOL_EXHAUSTED"))
                    .put("timestamp", f.clock.instant().toString()).set("instanceActionDetails",
                            GcpConversionFixture.JSON.createObjectNode().put("instance", "web-" + i)));
        }
        GcpMigSpotProvider provider = f.provider(f.clock);
        SpotGroup group = f.group();
        group.setState(GroupState.ACTIVE);
        group.setConfigMode(ConfigMode.MANAGED);
        group.setStateChangedAt(f.clock.instant().minus(Duration.ofDays(1)));
        // The MIG is a converted pair, so there is a Spot version to fall back from.
        ArrayNode versions = GcpConversionFixture.JSON.createArrayNode();
        versions.addObject().put("name", "xas-standard").put("instanceTemplate", GcpConversionFixture.ORIGINAL)
                .putObject("targetSize").put("fixed", 3);
        versions.addObject().put("name", "xas-spot").put("instanceTemplate", FakeGcp.P + "/global/instanceTemplates/spot-1");
        f.gcp.mig().set("versions", versions);
        int enters = 0;
        for (int sweep = 0; sweep < 4; sweep++) {
            GroupStatus status = provider.getStatus(group, f.gcp.view());
            List<ReconcileAction> a = provider.reconcile(group, status);
            if (a.contains(ReconcileAction.ENQUEUE_FALLBACK)) {
                enters++;
                assertThat(sweep).as("not before 15 minutes of shortfall").isEqualTo(3);
            }
            group.setHealthState(status.healthJson()); // the sweep persists it and the next sweep carries it
            f.clock.advance(Duration.ofMinutes(5));
        }
        assertThat(enters).isEqualTo(1);
    }

    @Test
    void getStatusAddsTheSinceTimesAndThePreemptionStateIsCarriedForward() throws Exception {
        GcpConversionFixture f = new GcpConversionFixture();
        GcpMigSpotProvider provider = f.provider(f.clock);
        SpotGroup group = f.group();
        group.setState(GroupState.ACTIVE);
        group.setConfigMode(ConfigMode.MANAGED);
        group.setHealthState("{\"preemption\":{\"cursor\":{\"us-central1-a\":\"2026-11-20T09:00:00Z\"}}}");
        JsonNode health = GcpConversionFixture.JSON.readTree(provider.getStatus(group, f.gcp.view()).healthJson());
        assertThat(health.path("preemption").path("cursor").path("us-central1-a").asText()).isEqualTo("2026-11-20T09:00:00Z");
        assertThat(health.path("shortfall").asInt()).isZero();
        assertThat(health.path("noShortfallSince").asText()).isNotBlank();
        assertThat(health.path("shortfallSince").isNull()).isTrue();
    }
}
