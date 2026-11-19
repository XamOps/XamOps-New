package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-GCP-01: the Spot copy is a deep copy that changes ONLY the documented Spot fields (plan-phase-27.md 27.2).
 * The golden source carries GPUs, Shielded and Confidential VM, IP forwarding, a reservation, sole-tenancy node
 * affinity, placement ({@code locationHint}, resource policies), two NICs with alias ranges, local SSD,
 * {@code minCpuPlatform}, advanced machine features, a field XamOps has never heard of, and labels named like
 * output-only fields.
 */
class GcpTemplateClonerGoldenTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID GROUP = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OP = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000");

    static JsonNode golden(String name) {
        try (InputStream in = GcpTemplateClonerGoldenTest.class.getResourceAsStream("/autospotting/gcp/golden/" + name)) {
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(name, e);
        }
    }

    @Test
    void everySourceFieldSurvivesExceptTheDocumentedSpotFields() {
        JsonNode source = golden("template-rich-source.json");
        GcpTemplateCloner.Clone clone = GcpTemplateCloner.clone(source, "rich-tpl-1", GROUP, "web", OP);
        assertThat(clone.body()).isEqualTo(golden("template-rich-expected.json"));
    }

    @Test
    void preservesGpusShieldedConfidentialIpForwardingReservationPlacementAndSoleTenancy() {
        JsonNode source = golden("template-rich-source.json");
        JsonNode copy = GcpTemplateCloner.clone(source, "rich-tpl-1", GROUP, "web", OP).body().path("properties");
        JsonNode in = source.path("properties");
        for (String f : new String[] {"guestAccelerators", "shieldedInstanceConfig", "confidentialInstanceConfig", "canIpForward",
                "reservationAffinity", "resourcePolicies", "advancedMachineFeatures", "networkPerformanceConfig",
                "minCpuPlatform", "machineType", "serviceAccounts", "someFutureField", "displayDevice"}) {
            assertThat(copy.path(f)).as(f).isEqualTo(in.path(f));
        }
        JsonNode sched = copy.path("scheduling");
        assertThat(sched.path("nodeAffinities")).isEqualTo(in.path("scheduling").path("nodeAffinities"));
        assertThat(sched.path("locationHint").asText()).isEqualTo("us-central1-a");
        assertThat(sched.path("maxRunDuration")).isEqualTo(in.path("scheduling").path("maxRunDuration"));
        assertThat(copy.path("disks")).hasSize(2);
        assertThat(copy.path("networkInterfaces").path(0).path("aliasIpRanges")).isNotEmpty();
    }

    @Test
    void schedulingBecomesSpotWithDeleteAndNoLegacyPreemptibleFlag() {
        JsonNode sched = GcpTemplateCloner.clone(golden("template-rich-source.json"), "rich-tpl-1", GROUP, "web", OP)
                .body().path("properties").path("scheduling");
        assertThat(sched.path("provisioningModel").asText()).isEqualTo("SPOT");
        assertThat(sched.path("instanceTerminationAction").asText()).isEqualTo("DELETE");
        assertThat(sched.path("automaticRestart").asBoolean(true)).isFalse();
        assertThat(sched.path("onHostMaintenance").asText()).isEqualTo("TERMINATE");
        assertThat(sched.has("preemptible")).isFalse();
    }

    @Test
    void theOnlyAddedLabelIsTheAttributionLabelAndCustomerLabelsNamedLikeOutputFieldsSurvive() {
        JsonNode labels = GcpTemplateCloner.clone(golden("template-rich-source.json"), "rich-tpl-1", GROUP, "web", OP)
                .body().path("properties").path("labels");
        assertThat(labels.path("xamops-autospotting").asText()).isEqualTo(GROUP.toString());
        assertThat(labels.path("kind").asText()).isEqualTo("web");
        assertThat(labels.path("id").asText()).isEqualTo("7");
        assertThat(labels.size()).isEqualTo(4);
    }

    @Test
    void outputOnlyFieldsAreDropped() {
        JsonNode props = GcpTemplateCloner.clone(golden("template-rich-source.json"), "rich-tpl-1", GROUP, "web", OP)
                .body().path("properties");
        assertThat(props.path("tags").has("fingerprint")).isFalse();
        assertThat(props.path("metadata").has("fingerprint")).isFalse();
        assertThat(props.path("metadata").has("kind")).isFalse();
        assertThat(props.path("networkInterfaces").path(0).has("fingerprint")).isFalse();
        assertThat(props.path("networkInterfaces").path(0).has("kind")).isFalse();
    }

    @Test
    void nameIsDeterministicSixtyThreeCharactersMaxAndTheDescriptionNamesSourceAndGroup() {
        GcpTemplateCloner.Clone a = GcpTemplateCloner.clone(golden("template-rich-source.json"), "rich-tpl-1", GROUP, "web", OP);
        GcpTemplateCloner.Clone b = GcpTemplateCloner.clone(golden("template-rich-source.json"), "rich-tpl-1", GROUP, "web", OP);
        assertThat(a.name()).isEqualTo("web-xas-aaaaaaaa").isEqualTo(b.name());
        assertThat(a.sha256()).isEqualTo(b.sha256()).hasSize(64);
        assertThat(a.body().path("description").asText()).isEqualTo("XamOps AutoSpotting copy of rich-tpl-1 for group " + GROUP);
        String longMig = "a".repeat(63);
        assertThat(GcpTemplateCloner.nameFor(longMig, OP)).hasSize(63).endsWith("-xas-aaaaaaaa");
    }

    @Test
    void theSourceIsNeverModified() {
        JsonNode source = golden("template-rich-source.json");
        JsonNode before = source.deepCopy();
        GcpTemplateCloner.clone(source, "rich-tpl-1", GROUP, "web", OP);
        assertThat(source).isEqualTo(before);
    }

    @Test
    void aMigNameEndingInDashesIsTrimmedSoTheNameStaysRfc1035() {
        assertThat(GcpTemplateCloner.nameFor("a".repeat(52) + "-", OP)).matches("^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$");
    }
}
