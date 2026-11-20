package com.xammer.cloud.autospotting.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.gcp.GcpEventRecorder;
import com.xammer.cloud.service.autospotting.gcp.GcpMigClient;
import com.xammer.cloud.service.autospotting.gcp.GcpMigStatusReader;
import com.xammer.cloud.service.autospotting.gcp.GcpReadOnlyView;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.ledger.observe.GcpLedgerObserver;
import com.xammer.cloud.service.autospotting.ledger.observe.ObservationStore;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code ledger-observe:gcp} on real PostgreSQL with recorded-style REST fixtures and a mocked read client (plan-phase-30.md
 * 30.2; no GCP call): a preempted VM closes its interval with {@code PROVIDER_INTERRUPTED} and
 * {@code GCE_PREEMPTED_OPERATION}; a deleted VM closes with a known end and a normal charge; a VM that vanishes with no
 * operation keeps {@code first_absent_at} and is never given an invented end.
 */
@Tag("containers")
class GcpLedgerObserverIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String P = "https://www.googleapis.com/compute/v1/projects/xamops-test";

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static JsonNode fixture(String name) {
        try (InputStream in = GcpLedgerObserverIT.class.getResourceAsStream("/autospotting/gcp/" + name)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(name, e);
        }
    }

    private static ObjectNode mig(int target) {
        ObjectNode m = (ObjectNode) fixture("mig-regional-web.json").deepCopy();
        m.put("targetSize", target);
        ArrayNode versions = m.putArray("versions");
        ObjectNode std = versions.addObject();
        std.put("name", "xas-standard");
        std.put("instanceTemplate", P + "/global/instanceTemplates/std-tpl");
        std.putObject("targetSize").put("fixed", 1);
        ObjectNode spot = versions.addObject();
        spot.put("name", "xas-spot");
        spot.put("instanceTemplate", P + "/global/instanceTemplates/spot-tpl");
        return m;
    }

    private static JsonNode instance(String name, String zone, String version) {
        ObjectNode o = JSON.createObjectNode();
        o.put("instance", P + "/zones/" + zone + "/instances/" + name);
        o.put("instanceStatus", "RUNNING");
        o.put("currentAction", "NONE");
        o.putObject("version").put("name", version);
        return o;
    }

    private static JsonNode template(String type) {
        ObjectNode t = JSON.createObjectNode();
        t.putObject("properties").put("machineType", type);
        return t;
    }

    private static Map<String, Object> interval(JdbcTemplate jdbc, String name) {
        return jdbc.queryForMap("SELECT instance_name, instance_type, zone, purchase_option, capacity_weight::text w,"
                + " start_at, start_source, end_at, end_source, end_cause, end_cause_source, provider_interrupted,"
                + " closed, first_absent_at FROM spot_usage_interval WHERE provider = 'GCP' AND instance_id = ?", name);
    }

    @Test
    void preemptedDeletedAndVanishedInstancesCloseWithTheRightCause() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            var ds = Ledger17Support.dataSource(db);
            String groupId = jdbc.queryForObject("INSERT INTO spot_group (id, provider, cloud_account_id, client_id,"
                    + " group_key, state, state_changed_at, created_by, created_at, updated_at) VALUES (gen_random_uuid(),"
                    + " 'GCP', 7, 9, 'xamops-test/us-central1/web-mig', 'ACTIVE', now(), 'test', now(), now())"
                    + " RETURNING id::text", String.class);
            GcpMigClient.ReadOnly reads = mock(GcpMigClient.ReadOnly.class);
            when(reads.project()).thenReturn("xamops-test");
            when(reads.getMig(anyBoolean(), anyString(), anyString())).thenReturn(mig(3));
            when(reads.getInstanceTemplate(P + "/global/instanceTemplates/std-tpl")).thenReturn(template("n2-standard-4"));
            when(reads.getInstanceTemplate(P + "/global/instanceTemplates/spot-tpl")).thenReturn(template(
                    P + "/zones/us-central1-a/machineTypes/n2-standard-4"));
            CloudAccountRepository accounts = mock(CloudAccountRepository.class);
            CloudAccount account = new CloudAccount();
            account.setId(7L);
            when(accounts.findById(7L)).thenReturn(Optional.of(account));
            MutableClock clock = new MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
            GcpLedgerObserver observer = new GcpLedgerObserver(mock(JobRunner.class), mock(LedgerGate.class),
                    new ObservationStore(ds), accounts, a -> new GcpReadOnlyView("xamops-test", reads),
                    new GcpMigStatusReader(), jdbc, new AutoSpottingMetrics(new SimpleMeterRegistry()), clock);

            // Sweep 1: one standard and two Spot VMs are running.
            when(reads.listManagedInstances(anyBoolean(), anyString(), anyString())).thenReturn(List.of(
                    instance("web-1", "us-central1-a", "xas-standard"), instance("web-2", "us-central1-b", "xas-spot"),
                    instance("web-3", "us-central1-c", "xas-spot")));
            assertThat(observer.observeAll()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_usage_interval WHERE closed = false",
                    Integer.class)).isEqualTo(3);
            Map<String, Object> w2 = interval(jdbc, "web-2");
            assertThat(w2.get("instance_name")).isEqualTo("web-2");
            assertThat(w2.get("purchase_option")).isEqualTo("SPOT");
            assertThat(w2.get("instance_type")).isEqualTo("n2-standard-4");
            assertThat(w2.get("zone")).isEqualTo("us-central1-b");
            assertThat(w2.get("w")).isEqualTo("1.000000");
            assertThat(w2.get("start_source")).isEqualTo("SWEEP_FIRST_SEEN");
            assertThat(interval(jdbc, "web-1").get("purchase_option")).isEqualTo("ON_DEMAND");
            Map<String, Object> sample = jdbc.queryForMap("SELECT desired_units::text d, od_floor_units::text f,"
                    + " original_spot_units::text o FROM spot_group_capacity_sample");
            assertThat(sample).containsEntry("d", "3.000000").containsEntry("f", "1.000000").containsEntry("o", "0.000000");

            // Sweep 2: web-3 is gone; the Phase 28 tracker recorded the compute.instances.preempted operation.
            clock.now = clock.now.plusSeconds(300);
            Instant preempted = clock.now.minusSeconds(40);
            new GcpEventRecorder(jdbc).record(java.util.UUID.fromString(groupId), GcpEventRecorder.PREEMPTION, "op-123",
                    "web-3", "n2-standard-4", "us-central1-c", preempted, clock.now);
            when(reads.listManagedInstances(anyBoolean(), anyString(), anyString())).thenReturn(List.of(
                    instance("web-1", "us-central1-a", "xas-standard"), instance("web-2", "us-central1-b", "xas-spot")));
            observer.observeAll();
            Map<String, Object> w3 = interval(jdbc, "web-3");
            assertThat(w3.get("closed")).isEqualTo(true);
            assertThat(((java.sql.Timestamp) w3.get("end_at")).toInstant()).isEqualTo(preempted);
            assertThat(w3.get("end_source")).isEqualTo("GCE_OPERATION");
            assertThat(w3.get("end_cause")).isEqualTo("PROVIDER_INTERRUPTED");
            assertThat(w3.get("end_cause_source")).isEqualTo("GCE_PREEMPTED_OPERATION");
            assertThat(w3.get("provider_interrupted")).isEqualTo(true);
            assertThat(interval(jdbc, "web-2").get("closed")).isEqualTo(false);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_group_capacity_sample", Integer.class))
                    .as("capacity is unchanged: no new sample").isEqualTo(1);

            // Sweep 3: web-2 is gone and a delete operation names it: a known end, charged normally.
            clock.now = clock.now.plusSeconds(300);
            Instant deleted = clock.now.minusSeconds(30);
            ObjectNode op = JSON.createObjectNode();
            op.put("targetLink", P + "/zones/us-central1-b/instances/web-2");
            op.put("endTime", deleted.toString());
            op.put("insertTime", deleted.minusSeconds(5).toString());
            when(reads.listZoneOperations(eq("us-central1-b"), eq("compute.instances.delete"), any())).thenReturn(List.of(op));
            when(reads.listManagedInstances(anyBoolean(), anyString(), anyString())).thenReturn(List.of(
                    instance("web-1", "us-central1-a", "xas-standard")));
            observer.observeAll();
            Map<String, Object> closed = interval(jdbc, "web-2");
            assertThat(((java.sql.Timestamp) closed.get("end_at")).toInstant()).isEqualTo(deleted);
            assertThat(closed.get("end_cause")).isEqualTo("SCALE_IN");
            assertThat(closed.get("end_cause_source")).isEqualTo("GCE_DELETE_OPERATION");
            assertThat(closed.get("provider_interrupted")).isEqualTo(false);

            // Sweeps 4 and 5: web-1 vanishes with no operation: first_absent_at is the first absence, no end is invented.
            clock.now = clock.now.plusSeconds(300);
            Instant firstAbsent = clock.now;
            when(reads.listManagedInstances(anyBoolean(), anyString(), anyString())).thenReturn(List.of());
            observer.observeAll();
            clock.now = clock.now.plusSeconds(300);
            observer.observeAll();
            Map<String, Object> absent = interval(jdbc, "web-1");
            assertThat(absent.get("end_at")).isNull();
            assertThat(absent.get("closed")).isEqualTo(false);
            assertThat(((java.sql.Timestamp) absent.get("first_absent_at")).toInstant()).isEqualTo(firstAbsent);
            // Nothing of AWS was touched and no money was written.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_usage_interval WHERE provider <> 'GCP'",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM savings_ledger_entry", Integer.class)).isZero();
        }
    }

    @Test
    void aMissingMigWritesNothing() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            jdbc.update("INSERT INTO spot_group (id, provider, cloud_account_id, client_id, group_key, state,"
                    + " state_changed_at, created_by, created_at, updated_at) VALUES (gen_random_uuid(), 'GCP', 7, 9,"
                    + " 'xamops-test/us-central1/web-mig', 'ACTIVE', now(), 'test', now(), now())");
            GcpMigClient.ReadOnly reads = mock(GcpMigClient.ReadOnly.class);
            when(reads.getMig(anyBoolean(), anyString(), anyString())).thenThrow(new com.xammer.cloud.service.autospotting.gcp.GcpReadException(
                    "instanceGroupManagers.get", com.xammer.cloud.service.autospotting.spi.ErrorKind.NOT_FOUND));
            CloudAccountRepository accounts = mock(CloudAccountRepository.class);
            CloudAccount account = new CloudAccount();
            account.setId(7L);
            when(accounts.findById(7L)).thenReturn(Optional.of(account));
            GcpLedgerObserver observer = new GcpLedgerObserver(mock(JobRunner.class), mock(LedgerGate.class),
                    new ObservationStore(Ledger17Support.dataSource(db)), accounts,
                    a -> new GcpReadOnlyView("xamops-test", reads), new GcpMigStatusReader(), jdbc,
                    new AutoSpottingMetrics(new SimpleMeterRegistry()));
            assertThat(observer.observeAll()).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM spot_usage_interval", Integer.class)).isZero();
        }
    }
}
