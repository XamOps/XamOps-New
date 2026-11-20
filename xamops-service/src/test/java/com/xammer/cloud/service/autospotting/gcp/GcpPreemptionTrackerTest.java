package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GCP preemption tracking through the reconciler (design-gcp.md §8; plan-phase-28.md 28.3; AC-HLT-06, AC-EVT-06):
 * the same preemption seen twice is one row, a deleted instance is still matched, only this group's instances count,
 * the cursor moves per zone and survives in the health state, and a failed read loses nothing.
 */
class GcpPreemptionTrackerTest {
    /** An in-memory {@code spot_group_event} with the same unique key as {@code ux_sge_dedupe}. */
    static final class MemoryEvents extends GcpEventRecorder {
        final Map<String, Instant> rows = new LinkedHashMap<>();
        final Map<String, String> zones = new LinkedHashMap<>();
        MemoryEvents() {
            super(null);
        }
        @Override
        public boolean record(UUID groupId, String eventType, String eventKey, String instance, String instanceType,
                              String zone, Instant occurredAt, Instant now) {
            String key = groupId + "|" + eventType + "|" + eventKey;
            if (rows.containsKey(key)) {
                return false;
            }
            rows.put(key, occurredAt);
            zones.put(key, zone);
            return true;
        }
        @Override
        public int countSince(UUID groupId, String eventType, Instant since) {
            return (int) rows.entrySet().stream().filter(e -> e.getKey().startsWith(groupId + "|" + eventType + "|"))
                    .filter(e -> !e.getValue().isBefore(since)).count();
        }
        @Override
        public Map<String, Integer> countByZoneSince(UUID groupId, Instant since) {
            Map<String, Integer> out = new java.util.TreeMap<>();
            rows.forEach((k, v) -> {
                if (k.startsWith(groupId + "|PREEMPTION|") && !v.isBefore(since)) {
                    out.merge(zones.get(k), 1, Integer::sum);
                }
            });
            return out;
        }
        long count(String type) {
            return rows.keySet().stream().filter(k -> k.contains("|" + type + "|")).count();
        }
    }

    private final GcpConversionFixture f = new GcpConversionFixture();
    private final MemoryEvents events = new MemoryEvents();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GcpPreemptionTracker tracker = new GcpPreemptionTracker(f.accounts, a -> f.gcp.view(), events,
            new AutoSpottingMetrics(registry), GcpHealthProperties.defaults(), f.clock);
    private final GcpMigSpotProvider provider = f.provider(f.clock);
    private SpotGroup group;

    GcpPreemptionTrackerTest() {
        f.gcp.mig().put("baseInstanceName", "web");
        group = f.group();
        group.setState(GroupState.ACTIVE);
        group.setConfigMode(ConfigMode.MANAGED);
        ((ObjectNode) f.gcp.mig()).putArray("versions").addObject().put("name", "xas-spot")
                .put("instanceTemplate", GcpConversionFixture.ORIGINAL);
    }

    private void preemption(String id, String instance, String zone, Instant at) {
        f.gcp.zoneOperations.computeIfAbsent(zone, z -> new java.util.ArrayList<>()).add(GcpConversionFixture.JSON.createObjectNode()
                .put("id", id).put("operationType", "compute.instances.preempted").put("insertTime", at.toString())
                .put("targetLink", FakeGcp.P + "/zones/" + zone + "/instances/" + instance));
    }

    /** One sweep: getStatus, then the tracker, then the runtime column the reconciler persists. */
    private JsonNode sweep() throws Exception {
        GroupStatus status = tracker.track(group, provider.getStatus(group, f.gcp.view()));
        group.setHealthState(status.healthJson());
        return GcpConversionFixture.JSON.readTree(status.healthJson());
    }

    @Test
    void theSamePreemptionSeenTwiceIsOneRowAndOneMetric() throws Exception {
        Instant at = f.clock.instant().minusSeconds(120);
        preemption("op-1", "web-1", "us-central1-b", at);
        sweep();
        // The state is lost (a restart, or a failed write): the same operation is read again.
        group.setHealthState(null);
        sweep();
        assertThat(events.count("PREEMPTION")).isEqualTo(1);
        assertThat(registry.counter("autospotting.interruptions", "provider", "gcp", "region", "us-central1",
                "source", "gcp_preemption").count()).isEqualTo(1.0);
        assertThat(events.rows.keySet().iterator().next()).endsWith("|op-1");
    }

    @Test
    void aDeletedInstanceIsMatchedByTheNamesOfTheLastTwoSweeps_notOnlyByThePrefix() throws Exception {
        f.gcp.mig().put("baseInstanceName", "something-else");
        sweep(); // web-0..5 are seen
        f.gcp.managedInstances.removeIf(m -> m.path("instance").asText().endsWith("/web-2"));
        preemption("op-2", "web-2", "us-central1-c", f.clock.instant().minusSeconds(30));
        f.clock.advance(Duration.ofMinutes(5));
        sweep();
        assertThat(events.count("PREEMPTION")).isEqualTo(1);
    }

    @Test
    void anotherGroupsInstancesAreNeverCounted() throws Exception {
        sweep();
        preemption("op-3", "batch-1", "us-central1-a", f.clock.instant().minusSeconds(30));
        f.clock.advance(Duration.ofMinutes(5));
        sweep();
        assertThat(events.count("PREEMPTION")).isZero();
    }

    @Test
    void theCursorMovesPerZoneAndTheNextSweepAsksOnlyForWhatIsNew() throws Exception {
        Instant t1 = f.clock.instant().minusSeconds(200);
        preemption("op-4", "web-1", "us-central1-b", t1);
        JsonNode health = sweep();
        assertThat(health.path("preemption").path("cursor").path("us-central1-b").asText()).isEqualTo(t1.toString());
        f.gcp.calls.clear();
        f.clock.advance(Duration.ofMinutes(5));
        Instant t2 = f.clock.instant().minusSeconds(10);
        preemption("op-5", "web-4", "us-central1-b", t2);
        health = sweep();
        assertThat(events.count("PREEMPTION")).isEqualTo(2);
        assertThat(health.path("preemption").path("cursor").path("us-central1-b").asText()).isEqualTo(t2.toString());
        assertThat(f.gcp.count("zoneOperations.list")).as("one list per zone of the MIG").isEqualTo(3);
    }

    @Test
    void ratesAndZoneCountsAreDerivedAndAHighRateStartsItsClock() throws Exception {
        for (int i = 0; i < 7; i++) {
            preemption("op-" + (10 + i), "web-" + i, "us-central1-a", f.clock.instant().minusSeconds(300 + i));
        }
        f.gcp.managedInstances.forEach(m -> ((ObjectNode) m.path("version")).put("name", "xas-spot"));
        ((ObjectNode) f.gcp.mig()).putArray("versions").addObject().put("name", "xas-spot").put("instanceTemplate", GcpConversionFixture.ORIGINAL);
        JsonNode p = sweep().path("preemption");
        assertThat(p.path("preemptions1h").asInt()).isEqualTo(7);
        assertThat(p.path("preemptions24h").asInt()).isEqualTo(7);
        assertThat(p.path("byZone").path("us-central1-a").asInt()).isEqualTo(7);
        // 7 preemptions across 6 Spot VMs in a day: more than one each.
        assertThat(p.path("rate24h").asDouble()).isGreaterThan(1.0);
        assertThat(p.path("highRateSince").asText()).isEqualTo(f.clock.instant().toString());
        f.clock.advance(Duration.ofMinutes(5));
        assertThat(sweep().path("preemption").path("highRateSince").asText())
                .as("the clock keeps its start").isEqualTo(f.clock.instant().minus(Duration.ofMinutes(5)).toString());
    }

    @Test
    void anInstanceThatVanishedWhileOthersAreRecreatingIsASuspectedPreemptionAndNeverCounted() throws Exception {
        sweep();
        f.gcp.managedInstances.removeIf(m -> m.path("instance").asText().endsWith("/web-3"));
        ((ObjectNode) f.gcp.managedInstances.get(0)).put("currentAction", "CREATING");
        f.clock.advance(Duration.ofMinutes(5));
        sweep();
        assertThat(events.count("SUSPECTED_PREEMPTION")).isEqualTo(1);
        assertThat(events.count("PREEMPTION")).isZero();
        assertThat(events.countSince(group.getId(), "PREEMPTION", Instant.EPOCH)).isZero();
    }

    @Test
    void aFailedReadLosesNothing_theCursorStaysAndTheStatusIsReturnedAsRead() throws Exception {
        Instant at = f.clock.instant().minusSeconds(60);
        preemption("op-20", "web-1", "us-central1-b", at);
        GroupStatus base = provider.getStatus(group, f.gcp.view());
        f.gcp.fail("zoneOperations.list", ErrorKind.TRANSIENT);
        GroupStatus out = tracker.track(group, base);
        assertThat(out.healthJson()).isEqualTo(base.healthJson());
        assertThat(events.rows).isEmpty();
        f.gcp.faults.clear();
        sweep();
        assertThat(events.count("PREEMPTION")).as("the next sweep reads the same window").isEqualTo(1);
    }

    @Test
    void theRawInstanceListIsConsumedAndNeverPersisted() throws Exception {
        JsonNode health = sweep();
        assertThat(health.has("instances")).isFalse();
        assertThat(health.has("baseInstanceName")).isFalse();
        assertThat(health.path("preemption").path("seen").size()).isEqualTo(6);
    }
}
