package com.xammer.cloud.autospotting.obs;

import com.xammer.cloud.domain.autospotting.DriftStatus;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-OBS-01: a {@link SimpleMeterRegistry} exercises every typed method on {@link AutoSpottingMetrics}
 * and asserts the meter names, the tag keys, and — critically — that no meter carries a tag key
 * matching {@code instance|host|account} (design-ux-ops.md §6.1, plan-phase-10.md task 10.1). The
 * forbidden-tag assertion is what stops a billed-per-account custom metric or a per-instance series
 * that would not sum.
 */
class AutoSpottingMetricsTest {

    /** Any tag key matching this pattern is forbidden on every AutoSpotting meter (§6.1, AC-OBS-01). */
    private static final Pattern FORBIDDEN_TAG = Pattern.compile("instance|host|account",
            Pattern.CASE_INSENSITIVE);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AutoSpottingMetrics metrics = new AutoSpottingMetrics(registry);

    @Test
    void everyTypedMethodRegistersAMeterWithOnlyBoundedTagKeys() {
        // ── counters ──
        metrics.conversionStarted(Provider.AWS, "ENABLE");
        metrics.conversionCompleted(Provider.AWS, "ENABLE", "succeeded");
        metrics.revert(Provider.GCP, "succeeded");
        metrics.fallback(Provider.AWS, "enter", "succeeded");
        metrics.interruption(Provider.AWS, "us-east-1", "aws_itn");
        metrics.event(Provider.GCP, "received");
        metrics.leaseFailure("store_error");
        metrics.jobSkipped("reconciler", "lease_held");
        metrics.reconcileRun(Provider.AWS, "ok");
        metrics.reconcileAction(Provider.AWS, "mark_drift");
        metrics.reconcileDrift(Provider.AWS, "DR-1", "MATERIAL");
        metrics.cloudError(Provider.AWS, "DescribeAutoScalingGroups", "THROTTLED");
        metrics.ledgerEntries(Provider.AWS, "BILL_VERIFIED", 3);
        metrics.ledgerUnknown(Provider.GCP);
        metrics.dryRunOperation(Provider.AWS, "ENABLE", "succeeded");
        metrics.opPreempted(Provider.AWS, "CONFIG_APPLY", "revert");
        metrics.reconcileGroup("ACTIVE", "NONE");
        metrics.reconcileAlert("DEGRADED");
        metrics.dispatch("poll");

        // ── timers ──
        metrics.recordOperationDuration(Provider.AWS, "ENABLE", "succeeded", Duration.ofSeconds(1));
        metrics.recordReconcileDuration(Provider.AWS, Duration.ofMillis(50));
        metrics.recordCloudCall(Provider.AWS, "UpdateAutoScalingGroup", "ok", Duration.ofMillis(10));
        metrics.recordPlanDuration(Provider.AWS, Duration.ofMillis(5));

        // ── gauges bound through the typed method (reconciler per-sweep gauges) ──
        AtomicLong staleness = new AtomicLong(7);
        metrics.bindReconcilerGauge(AutoSpottingMetrics.RECONCILE_STALENESS_SECONDS, Provider.AWS, staleness::get);
        metrics.bindReconcilerGauge(AutoSpottingMetrics.OPS_STUCK, Provider.AWS, () -> 0);

        // Every AutoSpotting meter is registered under a dotted name and carries no forbidden tag key.
        List<Meter> spotMeters = registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("autospotting."))
                .toList();
        assertThat(spotMeters).isNotEmpty();
        for (Meter m : spotMeters) {
            for (Tag tag : m.getId().getTags()) {
                assertThat(FORBIDDEN_TAG.matcher(tag.getKey()).find())
                        .as("meter %s must not carry a forbidden tag key %s", m.getId().getName(), tag.getKey())
                        .isFalse();
            }
        }
    }

    @Test
    void counterMetersHaveTheExpectedNamesAndTagKeys() {
        metrics.conversionStarted(Provider.AWS, "ENABLE");
        assertTagKeys(AutoSpottingMetrics.CONVERSIONS_STARTED, "provider", "kind");

        metrics.conversionCompleted(Provider.AWS, "ENABLE", "succeeded");
        assertTagKeys(AutoSpottingMetrics.CONVERSIONS_COMPLETED, "provider", "kind", "outcome");

        metrics.fallback(Provider.AWS, "enter", "succeeded");
        assertTagKeys(AutoSpottingMetrics.FALLBACKS, "provider", "direction", "outcome");

        metrics.interruption(Provider.AWS, "us-east-1", "aws_itn");
        assertTagKeys(AutoSpottingMetrics.INTERRUPTIONS, "provider", "region", "source");

        metrics.leaseFailure("store_error");
        assertTagKeys(AutoSpottingMetrics.LEASE_FAILURES, "reason");

        metrics.jobSkipped("reconciler", "lease_held");
        assertTagKeys(AutoSpottingMetrics.JOB_SKIPPED, "job", "reason");

        metrics.reconcileDrift(Provider.AWS, "DR-1", "MATERIAL");
        assertTagKeys(AutoSpottingMetrics.RECONCILE_DRIFT, "provider", "code", "severity");

        metrics.cloudError(Provider.AWS, "DescribeAutoScalingGroups", "THROTTLED");
        assertTagKeys(AutoSpottingMetrics.CLOUD_ERRORS, "provider", "api", "kind");

        metrics.ledgerEntries(Provider.AWS, "BILL_VERIFIED", 1);
        assertTagKeys(AutoSpottingMetrics.LEDGER_ENTRIES, "provider", "tier");

        metrics.opPreempted(Provider.AWS, "CONFIG_APPLY", "revert");
        assertTagKeys(AutoSpottingMetrics.OPS_PREEMPTED, "provider", "kind", "by");

        metrics.dispatch("poll");
        assertTagKeys(AutoSpottingMetrics.DISPATCH, "event");
    }

    @Test
    void groupGaugesCarryOnlyProviderAndStateOrSeverityAndNeverAnAccountTag() {
        // The group gauges are bound by the core GroupGaugeBinder through these typed methods; here we
        // bind them with plain suppliers to assert names, tag keys and the value they report.
        AtomicLong activeCount = new AtomicLong(2);
        AtomicLong materialDrift = new AtomicLong(1);
        metrics.bindGroupStateGauge(Provider.AWS, GroupState.ACTIVE, activeCount::get);
        metrics.bindGroupDriftGauge(Provider.AWS, DriftStatus.MATERIAL, materialDrift::get);

        assertThat(gaugeValue(AutoSpottingMetrics.GROUPS, "provider", "aws", "state", "ACTIVE")).isEqualTo(2.0);
        assertThat(gaugeValue(AutoSpottingMetrics.GROUPS_DRIFT, "provider", "aws", "severity", "MATERIAL"))
                .isEqualTo(1.0);

        // Neither gauge carries an account/instance/host tag; tag keys are the bounded ones only.
        registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(AutoSpottingMetrics.GROUPS)
                        || m.getId().getName().equals(AutoSpottingMetrics.GROUPS_DRIFT))
                .forEach(m -> m.getId().getTags().forEach(t ->
                        assertThat(FORBIDDEN_TAG.matcher(t.getKey()).find()).isFalse()));
    }

    @Test
    void everyBoundGaugeStillReportsAfterGarbageCollection() throws Exception {
        // The sources are lambdas nobody else references; a weakly held gauge reads NaN once they are collected.
        AtomicLong n = new AtomicLong(5);
        metrics.bindReconcilerGauge(AutoSpottingMetrics.OPS_STUCK, Provider.AWS, () -> n.get());
        metrics.bindLedgerLastSuccessAge("ledger", () -> n.get());
        metrics.bindGroupStateGauge(Provider.AWS, GroupState.ACTIVE, () -> n.get());
        metrics.bindGroupDriftGauge(Provider.AWS, DriftStatus.MATERIAL, () -> n.get());
        // System.gc() is only a hint: wait until a canary object is really collected, so a collection did happen.
        java.lang.ref.WeakReference<Object> canary = new java.lang.ref.WeakReference<>(new Object());
        for (int i = 0; i < 100 && canary.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertThat(canary.get()).as("a garbage collection ran").isNull();
        assertThat(gaugeValue(AutoSpottingMetrics.OPS_STUCK, "provider", "aws")).isEqualTo(5.0);
        assertThat(gaugeValue(AutoSpottingMetrics.LEDGER_LAST_SUCCESS_AGE, "job", "ledger")).isEqualTo(5.0);
        assertThat(gaugeValue(AutoSpottingMetrics.GROUPS, "provider", "aws", "state", "ACTIVE")).isEqualTo(5.0);
        assertThat(gaugeValue(AutoSpottingMetrics.GROUPS_DRIFT, "provider", "aws", "severity", "MATERIAL"))
                .isEqualTo(5.0);
    }

    private void assertTagKeys(String meterName, String... expectedKeys) {
        Meter meter = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(meterName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no meter registered with name " + meterName));
        Set<String> keys = meter.getId().getTags().stream().map(Tag::getKey)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(keys).containsExactlyInAnyOrder(expectedKeys);
    }

    private double gaugeValue(String name, String... tagKv) {
        var search = registry.find(name);
        for (int i = 0; i < tagKv.length; i += 2) {
            search = search.tag(tagKv[i], tagKv[i + 1]);
        }
        var gauge = search.gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }
}
