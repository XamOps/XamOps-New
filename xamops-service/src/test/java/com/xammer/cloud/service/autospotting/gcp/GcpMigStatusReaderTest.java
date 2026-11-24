package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GcpMigStatusReaderTest {
    private final GcpMigStatusReader reader = new GcpMigStatusReader();
    private static final String KEY = "xamops-test/us-central1/web-mig";

    @Test
    void statusReadsHealthCapacityAndBothFingerprints() throws Exception {
        FakeGcp gcp = FakeGcp.healthy();
        GroupStatus status = reader.read(gcp.view(), KEY);
        assertThat(status.found()).isTrue();
        assertThat(status.foreignActivity()).isFalse();
        GcpMigFacts g = GcpMigFacts.from(FakeGcp.PROJECT, gcp.mig());
        assertThat(status.liveFingerprint()).isEqualTo(GcpFingerprint.planHash(g));
        JsonNode health = FakeGcp.JSON.readTree(status.healthJson());
        assertThat(health.path("versionTargetReached").asBoolean()).isTrue();
        assertThat(health.path("running").asInt()).isEqualTo(3);
        assertThat(health.path("healthyInService").asInt()).as("one VM is UNHEALTHY").isEqualTo(2);
        JsonNode capacity = FakeGcp.JSON.readTree(status.capacityJson());
        assertThat(capacity.path("desired").asInt()).isEqualTo(6);
        assertThat(capacity.path("standard").asInt()).isEqualTo(1);
        assertThat(capacity.path("spot").asInt()).isEqualTo(2);
        assertThat(capacity.path("ownedFingerprint").asText()).isEqualTo(GcpFingerprint.of(g));
    }

    @Test
    void aConfirmed404IsNotFoundButAPermissionFailureIsRethrown() {
        FakeGcp gone = FakeGcp.healthy();
        gone.migs.clear();
        assertThat(reader.read(gone.view(), KEY).found()).isFalse();
        FakeGcp denied = FakeGcp.healthy().fail("instanceGroupManagers.get", ErrorKind.PERMISSION);
        assertThatThrownBy(() -> reader.read(denied.view(), KEY)).isInstanceOfSatisfying(GcpReadException.class,
                e -> assertThat(e.kind()).isEqualTo(ErrorKind.PERMISSION));
    }

    @Test
    void aFailingListErrorsKeepsTheBaseStatus() {
        FakeGcp gcp = FakeGcp.healthy().fail("instanceGroupManagers.listErrors", ErrorKind.TRANSIENT);
        assertThat(reader.read(gcp.view(), KEY).found()).isTrue();
    }

    @Test
    void anUpdateInFlightOnAForeignGroupIsForeignActivity() {
        FakeGcp gcp = FakeGcp.healthy();
        gcp.mig().putObject("status").put("isStable", false).putObject("versionTarget").put("isReached", false);
        assertThat(reader.read(gcp.view(), KEY).foreignActivity()).isTrue();
    }

    @Test
    void theSnapshotRecordsScopeZonesTemplatesAndTheRestorableFields() throws Exception {
        FakeGcp gcp = FakeGcp.healthy();
        GcpMigStatusReader.Read read = reader.readForSnapshot(gcp.view(), KEY);
        SnapshotPayload snap = GcpSnapshotCodec.encode(read.group(), read.autoscaler(), read.templates(), false, List.of());
        assertThat(snap.provider()).isEqualTo(Provider.GCP);
        assertThat(snap.schemaVersion()).isEqualTo(1);
        assertThat(snap.exact()).isTrue();
        JsonNode p = FakeGcp.JSON.readTree(snap.payloadJson());
        assertThat(p.path("locationType").asText()).isEqualTo("REGIONAL");
        assertThat(p.path("region").asText()).isEqualTo("us-central1");
        assertThat(p.has("zone")).isFalse();
        assertThat(p.path("distributionZones")).hasSize(3);
        assertThat(p.path("instanceTemplate").asText()).isEqualTo(FakeGcp.TEMPLATE);
        assertThat(p.path("updatePolicy").path("type").asText()).isEqualTo("OPPORTUNISTIC");
        assertThat(p.path("templates")).hasSize(1);
        assertThat(p.path("templates").get(0).path("sha256").asText()).hasSize(64);
        assertThat(p.path("planHash").asText()).isEqualTo(GcpFingerprint.planHash(read.group()));
        assertThat(snap.payloadSha256()).hasSize(64);
    }

    @Test
    void aTemplateThatWasNotReadMakesTheSnapshotInexact() {
        FakeGcp gcp = FakeGcp.healthy();
        GcpMigFacts g = GcpMigFacts.from(FakeGcp.PROJECT, gcp.mig());
        SnapshotPayload snap = GcpSnapshotCodec.encode(g, null,
                List.of(new GcpSnapshotCodec.TemplateRead(FakeGcp.TEMPLATE, null)), false, List.of());
        assertThat(snap.exact()).isFalse();
        assertThat(snap.inexactReasons()).anyMatch(r -> r.startsWith("TEMPLATE_NOT_READ"));
    }

    @Test
    void aZonalMigIsZonalAndANamelessScopeIsUnknown() {
        GcpMigFacts zonal = GcpMigFacts.from(FakeGcp.PROJECT, FakeGcp.obj("mig-zonal-batch.json"));
        assertThat(zonal.locationType()).isEqualTo("ZONAL");
        assertThat(zonal.region()).isEqualTo("us-central1");
        assertThat(zonal.zoneCount()).isEqualTo(1);
        com.fasterxml.jackson.databind.node.ObjectNode noScope = FakeGcp.obj("mig-zonal-batch.json");
        noScope.remove("zone");
        GcpMigFacts unknown = GcpMigFacts.from(FakeGcp.PROJECT, noScope);
        assertThat(unknown.locationKnown()).isFalse();
        assertThat(unknown.locationType()).isEqualTo("UNKNOWN");
    }
}
