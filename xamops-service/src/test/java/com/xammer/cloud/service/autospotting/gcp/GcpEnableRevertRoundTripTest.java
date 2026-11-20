package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G6 proof (AutoSpotting Phase B): ENABLE then REVERT of a GCP MIG, regional and zonal, over a stubbed Compute.
 * The MIG ends on its exact original template and update policy, no PATCH ever carries a target size, and the
 * running instance count never falls below the target at any step, including through the surge-first replacement of
 * every instance. The cloud is simulated by this test (one instance added before one is removed). No GCP call.
 */
class GcpEnableRevertRoundTripTest {
    private static final UUID REVERT_OP = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

    private final List<String> trace = new ArrayList<>();
    private int target;
    private long minRunning = Long.MAX_VALUE;

    private long running(GcpConversionFixture f) {
        return f.gcp.managedInstances.stream().filter(i -> "RUNNING".equals(i.path("instanceStatus").asText())).count();
    }

    private String versions(GcpConversionFixture f) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode v : f.gcp.mig().path("versions")) {
            String t = v.path("instanceTemplate").asText();
            sb.append(sb.length() == 0 ? "" : ",").append(v.path("name").asText("-")).append(':')
                    .append(t.substring(t.lastIndexOf('/') + 1));
        }
        return sb.toString();
    }

    /** One trace line plus the invariants: target size untouched, running count at or above it. */
    private void check(GcpConversionFixture f, String label) {
        long run = running(f);
        int ts = f.gcp.mig().path("targetSize").asInt();
        minRunning = Math.min(minRunning, run);
        trace.add(label + " | targetSize=" + ts + " running=" + run + " versions=[" + versions(f) + "] policy="
                + f.gcp.mig().path("updatePolicy").path("type").asText());
        assertThat(ts).as(label + " targetSize").isEqualTo(target);
        assertThat(run).as(label + " running instances").isGreaterThanOrEqualTo(target);
    }

    private void step(GcpConversionFixture f, String phase, StepProgram p, int i) {
        StepOutcome o = f.run(p.at(i), i);
        assertThat(o).as(phase + " " + p.at(i).name() + " " + o).isInstanceOf(StepOutcome.Done.class);
        check(f, phase + " " + i + ":" + p.at(i).name());
    }

    /** The cloud replaces every instance not on {@code template}: surge one, then remove one, never below target. */
    private void rollTo(GcpConversionFixture f, String phase, int keepOnOriginal, String spotLink, String zone) {
        int n = f.gcp.managedInstances.size();
        for (int i = 0; i < n; i++) {
            boolean std = i < keepOnOriginal;
            String want = std ? GcpConversionFixture.ORIGINAL : (spotLink == null ? GcpConversionFixture.ORIGINAL : spotLink);
            String version = spotLink == null ? null : (std ? GcpMigFacts.STANDARD_VERSION : GcpMigFacts.SPOT_VERSION);
            String name = "web-" + i;
            JsonNode old = f.gcp.managedInstances.stream().filter(m -> m.path("instance").asText().endsWith("/" + name))
                    .findFirst().orElseThrow();
            if (old.path("version").path("instanceTemplate").asText().equals(want)) {
                // Same template: the instance stays, the MIG only labels it with its version name.
                ObjectNode v = (ObjectNode) old.path("version");
                if (version == null) {
                    v.remove("name");
                } else {
                    v.put("name", version);
                }
                continue;
            }
            ObjectNode fresh = GcpConversionFixture.instance(i, version, want, "NONE");
            fresh.put("instance", FakeGcp.P + "/zones/" + zone + "/instances/" + name + "-new");
            f.gcp.managedInstances.add(fresh);
            check(f, phase + " cloud: surge instance " + name + "-new up");
            f.gcp.managedInstances.remove(old);
            check(f, phase + " cloud: " + name + " removed");
            // Keep the instance name stable for the next pass: the replacement takes the slot.
            fresh.put("instance", FakeGcp.P + "/zones/" + zone + "/instances/" + name);
        }
        ObjectNode status = (ObjectNode) f.gcp.mig().path("status");
        status.put("isStable", true);
        ((ObjectNode) status.path("versionTarget")).put("isReached", true);
    }

    private void roundTrip(GcpConversionFixture f, String kind, int floor, String zone) {
        JsonNode originalVersions = f.gcp.mig().path("versions").deepCopy();
        JsonNode originalPolicy = f.gcp.mig().path("updatePolicy").deepCopy();
        String originalTemplate = f.gcp.mig().path("instanceTemplate").asText();
        target = f.gcp.mig().path("targetSize").asInt();
        check(f, kind + " ENABLE start");

        f.planAgainstLive();
        StepProgram enable = f.library.program(f.context(OperationKind.ENABLE, f.target(floor).toString(), null, OperationPhase.FORWARD));
        for (int i = 0; i <= 5; i++) {
            step(f, kind + " ENABLE", enable, i);
        }
        assertThat(f.gcp.mig().path("versions")).as("converted to the standard + Spot pair").hasSize(2);
        String spot = f.registry.findByOp(f.opId).orElseThrow().selfLink();
        rollTo(f, kind + " ENABLE", floor, spot, zone);
        assertThat(f.pollDone(enable.at(6), 6)).isInstanceOf(StepOutcome.Done.class);
        check(f, kind + " ENABLE 6:" + enable.at(6).name());
        for (int i = 7; i <= 9; i++) {
            step(f, kind + " ENABLE", enable, i);
        }
        trace.add(kind + " ENABLE end | spot instances=" + f.gcp.managedInstances.stream()
                .filter(m -> m.path("version").path("instanceTemplate").asText().equals(spot)).count());

        f.planAgainstLive();
        StepProgram revert = f.restoreProgram.program(new OperationContext(REVERT_OP, OperationKind.REVERT, f.group(), null, null,
                OperationPhase.FORWARD, f.planId, null));
        for (int i = 0; i <= 4; i++) {
            step(f, kind + " REVERT", revert, i);
        }
        rollTo(f, kind + " REVERT", 0, null, zone);
        assertThat(f.pollDone(revert.at(5), 5)).isInstanceOf(StepOutcome.Done.class);
        check(f, kind + " REVERT 5:" + revert.at(5).name());
        for (int i = 6; i <= 8; i++) {
            step(f, kind + " REVERT", revert, i);
        }

        // Exact original template and update policy, same size, no deletion, no template created by the revert.
        // One normalisation: targetSize.calculated is output-only (GCP computes it for a single version), never written.
        originalVersions.forEach(v -> ((ObjectNode) v).remove("targetSize"));
        assertThat(f.gcp.mig().path("versions")).isEqualTo(originalVersions);
        assertThat(f.gcp.mig().path("instanceTemplate").asText()).isEqualTo(originalTemplate);
        assertThat(GcpTargets.policyHas(f.gcp.mig().path("updatePolicy"), originalPolicy)).isTrue();
        assertThat(f.gcp.mig().path("updatePolicy").path("type").asText()).isEqualTo(originalPolicy.path("type").asText());
        assertThat(f.gcp.mig().path("targetSize").asInt()).isEqualTo(target);
        assertThat(f.gcp.managedInstances).allSatisfy(m ->
                assertThat(m.path("version").path("instanceTemplate").asText()).isEqualTo(GcpConversionFixture.ORIGINAL));
        assertThat(f.mutating.patches).allSatisfy(b -> assertThat(b.has("targetSize")).as("no PATCH carries a target size").isFalse());
        assertThat(f.mutating.deleted).as("nothing deleted inline").isEmpty();
        assertThat(f.mutating.inserted).as("one Spot copy at enable, none at an exact revert").hasSize(1);
        assertThat(f.registry.findByOp(f.opId).orElseThrow().status()).isEqualTo("RETIRED");
        assertThat(minRunning).isGreaterThanOrEqualTo(target);
        trace.add(kind + " REVERT end | exact original template=" + originalTemplate.substring(originalTemplate.lastIndexOf('/') + 1)
                + " updatePolicy.type=" + f.gcp.mig().path("updatePolicy").path("type").asText() + " targetSize=" + target
                + " minRunning=" + minRunning + " patches=" + f.mutating.calls);
        trace.forEach(l -> System.out.println("G6-TRACE " + l));
    }

    @Test
    void regionalEnableThenRevertRestoresTheExactOriginalAndNeverDropsBelowTarget() {
        GcpConversionFixture f = new GcpConversionFixture();
        assertThat(f.gcp.mig().has("region")).isTrue();
        roundTrip(f, "REGIONAL", 3, "us-central1-a");
    }

    @Test
    void zonalEnableThenRevertRestoresTheExactOriginalAndNeverDropsBelowTarget() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.useZonalMig();
        assertThat(f.gcp.mig().has("zone")).isTrue();
        roundTrip(f, "ZONAL", 2, "us-central1-a");
    }
}
