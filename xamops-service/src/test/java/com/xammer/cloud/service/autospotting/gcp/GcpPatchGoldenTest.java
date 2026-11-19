package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The conversion PATCH bodies, as golden JSON (AC-GCP-02): surge-first, zones from the live MIG, no capacity fields. */
class GcpPatchGoldenTest {
    private static final String P = FakeGcp.P + "/global/instanceTemplates/";

    private JsonNode patchOf(GcpConversionFixture f, int n) throws Exception {
        f.planAgainstLive();
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, f.target(n).toString(), null, OperationPhase.FORWARD));
        for (int i = 0; i <= 4; i++) {
            StepOutcome o = f.run(p.at(i), i);
            assertThat(o).as(p.at(i).name() + " " + o).isInstanceOf(StepOutcome.Done.class);
        }
        assertThat(f.mutating.patches).hasSize(1);
        return f.mutating.patches.get(0);
    }

    @Test
    void regionalPatchCarriesMaxSurgeEqualToTheZoneCountAndMaxUnavailableZero() throws Exception {
        GcpConversionFixture f = new GcpConversionFixture();
        JsonNode expected = GcpConversionFixture.JSON.readTree("""
                {"versions":[
                  {"name":"xas-standard","instanceTemplate":"%sweb-tpl-1","targetSize":{"fixed":3}},
                  {"name":"xas-spot","instanceTemplate":"%sweb-mig-xas-aaaaaaaa"}],
                 "updatePolicy":{"type":"PROACTIVE","minimalAction":"REPLACE","mostDisruptiveAllowedAction":"REPLACE",
                   "replacementMethod":"SUBSTITUTE","maxSurge":{"fixed":3},"maxUnavailable":{"fixed":0}}}
                """.formatted(P, P));
        assertThat(patchOf(f, 3)).isEqualTo(expected);
        assertThat(f.mutating.patchFingerprints.get(0)).as("the raw MIG fingerprint is the precondition").isEqualTo("Zm9vYmFy");
    }

    @Test
    void zonalPatchSurgesOneAndNeverSendsCapacityOrMinReadyFields() throws Exception {
        GcpConversionFixture f = new GcpConversionFixture();
        f.useZonalMig();
        JsonNode body = patchOf(f, 2);
        assertThat(body.path("updatePolicy").path("maxSurge").path("fixed").asInt()).isEqualTo(1);
        assertThat(body.path("updatePolicy").path("maxUnavailable").path("fixed").asInt()).isZero();
        assertThat(body.has("targetSize")).isFalse();
        assertThat(body.path("updatePolicy").has("minReadySeconds")).isFalse();
        assertThat(body.path("updatePolicy").has("minReadySec")).isFalse();
        assertThat(body.path("versions").path(0).path("targetSize").path("fixed").asInt()).isEqualTo(2);
    }

    @Test
    void fullSpotIsOneSpotVersion() throws Exception {
        GcpConversionFixture f = new GcpConversionFixture();
        JsonNode body = patchOf(f, 0);
        assertThat(body.path("versions")).hasSize(1);
        assertThat(body.path("versions").path(0).path("name").asText()).isEqualTo("xas-spot");
        assertThat(body.path("versions").path(0).has("targetSize")).isFalse();
    }

    @Test
    void theNativeMixFieldIsNeverUsed() throws Exception {
        GcpConversionFixture f = new GcpConversionFixture();
        assertThat(patchOf(f, 3).toString()).doesNotContain("provisioningModelMix").doesNotContain("instanceFlexibilityPolicy");
    }
}
