package com.xammer.cloud.dto.autoscaling;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.xammer.cloud.controller.AgentProposalController;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.ApprovedAction;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.ApprovedBy;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchRequest;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.DispatchedResponse;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.OutcomeRequest;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Target;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-B12: the golden agent-contract fixtures. The Go side owns {@code proposal_batch.json},
 * {@code outcome.json} and {@code dispatched.json} and parses {@code approved_action*.json} with
 * {@code DisallowUnknownFields}; the byte-identical copies under
 * {@code src/test/resources/contract/approval/} are parsed here with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=true} for the <em>envelope</em> fields only. The embedded
 * {@code params}, {@code evidence}, {@code actions}, {@code reports} and {@code consolidation} are
 * opaque {@link JsonNode}s: an added field inside {@code fitsim.Plan} must not break this build, but a
 * wrong JSON <em>type</em> or an empty example must.
 */
class ApprovalContractTest {

    private static final ObjectMapper STRICT = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Through text, so numbers are parsed exactly as a fixture is (IntNode, not the LongNode a bean would give). */
    private static JsonNode tree(Object value) throws IOException {
        return STRICT.readTree(STRICT.writeValueAsBytes(value));
    }

    private static JsonNode fixture(String name) throws IOException {
        try (InputStream in = ApprovalContractTest.class.getResourceAsStream("/contract/approval/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return STRICT.readTree(in);
        }
    }

    @Test
    void theGoProposalBatchParsesStrictlyAndRoundTrips() throws Exception {
        JsonNode raw = fixture("proposal_batch.json");
        BatchRequest batch = STRICT.treeToValue(raw, BatchRequest.class);
        assertThat(batch.schemaVersion()).isEqualTo(1);
        assertThat(batch.observedAt()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
        assertThat(batch.items()).hasSize(3);
        assertThat(batch.rebalance().complete()).isTrue();
        assertThat(tree(batch)).isEqualTo(raw);
    }

    @Test
    void everyEmbeddedStructureIsPresentNonEmptyAndTheRightJsonType() throws Exception {
        BatchRequest batch = STRICT.treeToValue(fixture("proposal_batch.json"), BatchRequest.class);
        batch.items().forEach(item -> {
            assertThat(item.params().isObject()).isTrue();
            assertThat(item.params().size()).isPositive();
            assertThat(item.evidence().isObject()).isTrue();
            JsonNode fit = item.evidence().get("fit");
            assertThat(fit.isObject()).isTrue();
            assertThat(fit.get("pdbChecks").isArray()).isTrue();
            assertThat(fit.get("pdbChecks").size()).isPositive();
        });
        assertThat(batch.items().get(0).evidence().at("/fit/moves").size()).as("one real fit plan with a move").isPositive();
        assertThat(batch.items().get(0).dwellSeconds()).isNull();
        assertThat(batch.items().get(1).dwellSeconds()).isEqualTo(600);
    }

    @Test
    void anUnknownEnvelopeFieldFailsButAnUnknownFieldInsideEvidenceDoesNot() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode withExtraEnvelope = (com.fasterxml.jackson.databind.node.ObjectNode) fixture("proposal_batch.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) withExtraEnvelope.get("items").get(0)).put("surprise", 1);
        assertThatThrownBy(() -> STRICT.treeToValue(withExtraEnvelope, BatchRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class);

        com.fasterxml.jackson.databind.node.ObjectNode withExtraEvidence = (com.fasterxml.jackson.databind.node.ObjectNode) fixture("proposal_batch.json").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) withExtraEvidence.get("items").get(0).get("evidence").get("fit")).put("newGoField", true);
        assertThat(STRICT.treeToValue(withExtraEvidence, BatchRequest.class).items()).hasSize(3);
    }

    @Test
    void theGoOutcomeParsesStrictlyAndCarriesEveryEmbeddedStructure() throws Exception {
        JsonNode raw = fixture("outcome.json");
        OutcomeRequest o = STRICT.treeToValue(raw, OutcomeRequest.class);
        assertThat(o.status()).isEqualTo("succeeded");
        assertThat(o.operationId()).isEqualTo("proposal-" + o.proposalId());
        assertThat(o.actions().isArray()).isTrue();
        assertThat(o.actions().size()).isPositive();
        assertThat(o.reports().isArray()).isTrue();
        assertThat(o.reports().size()).isPositive();
        assertThat(o.consolidation().isObject()).isTrue();
        assertThat(o.consolidation().size()).isPositive();
        assertThat(tree(o)).isEqualTo(raw);
    }

    @Test
    void theDispatchedListParsesStrictly() throws Exception {
        JsonNode raw = fixture("dispatched.json");
        DispatchedResponse d = STRICT.treeToValue(raw, DispatchedResponse.class);
        assertThat(d.items()).singleElement().satisfies(i -> {
            assertThat(i.operationId()).isEqualTo("proposal-" + i.id());
            assertThat(i.dryRun()).isFalse();
        });
        assertThat(tree(d)).isEqualTo(raw);
    }

    @Test
    void theJavaClaimResponseMatchesTheFixturesTheGoSideParsesWithDisallowUnknownFields() throws Exception {
        ApprovedAction drain = new ApprovedAction("b3f1c2d0-7e4a-4c1b-9d2e-5a6f7b8c9d0e", 3, "NODE_DRAIN", "DRAIN",
                "node-optimizer", "NODE_DRAIN:DRAIN:node-optimizer:ip-10-0-1-5.ec2.internal",
                new Target("NODE", "ip-10-0-1-5.ec2.internal", "7c1e5f0a-2b6d-4a41-9d3e-0f8b1c2d3e4f",
                        "aws:///us-east-1a/i-0abc123def4567890", "ng-1"),
                STRICT.readTree("{\"trigger\":\"idle-drain\",\"unschedulableAtProposal\":false,"
                        + "\"providerId\":\"aws:///us-east-1a/i-0abc123def4567890\",\"nodeGroup\":\"ng-1\"}"),
                "sha256:9f2c1e5a7b3d4c6e8a0b1d2f3a4c5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f",
                Instant.parse("2026-11-01T09:20:00Z"), null, new ApprovedBy(42L, "A. Admin"),
                Instant.parse("2026-11-01T10:02:11Z"), Instant.parse("2026-11-01T10:17:11Z"), false);
        assertThat(tree(drain)).isEqualTo(fixture("approved_action.json"));

        ApprovedAction consolidate = new ApprovedAction("c4a2d3e1-8f5b-4d2c-8e3f-6b7a8c9d0e1f", 2, "NODE_CONSOLIDATE",
                "EMPTY", "consolidation", "NODE_CONSOLIDATE:EMPTY:consolidation:ip-10-0-3-7.ec2.internal",
                new Target("NODE", "ip-10-0-3-7.ec2.internal", "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
                        "aws:///us-east-1b/i-0fed987cba6543210", "ng-2"),
                STRICT.readTree("{\"trigger\":\"consolidation-empty\",\"unschedulableAtProposal\":false,"
                        + "\"moveType\":\"EMPTY\",\"providerId\":\"aws:///us-east-1b/i-0fed987cba6543210\","
                        + "\"nodeGroup\":\"ng-2\"}"),
                "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                Instant.parse("2026-11-01T09:10:00Z"), 120, new ApprovedBy(42L, "A. Admin"),
                Instant.parse("2026-11-01T10:05:00Z"), Instant.parse("2026-11-01T10:20:00Z"), true);
        assertThat(tree(consolidate)).isEqualTo(fixture("approved_action_consolidate.json"));
        assertThat(tree(drain).has("dwellSeconds")).as("sent as null, never omitted").isTrue();
        assertThat(tree(drain).get("dwellSeconds").isNull()).isTrue();
        assertThat(STRICT.treeToValue(fixture("approved_action_consolidate.json"), ApprovedAction.class).dwellSeconds())
                .isEqualTo(120);
    }

    @Test
    void theEndpointPathsTheAgentCallsAreTheOnesTheControllerServes() throws Exception {
        RequestMapping base = AgentProposalController.class.getAnnotation(RequestMapping.class);
        assertThat(base.value()).containsExactly("/api/v1/agent/proposals");
        List<String> paths = Arrays.stream(AgentProposalController.class.getDeclaredMethods())
                .flatMap(m -> {
                    PostMapping p = m.getAnnotation(PostMapping.class);
                    GetMapping g = m.getAnnotation(GetMapping.class);
                    return java.util.stream.Stream.concat(
                            p == null ? java.util.stream.Stream.<String>empty() : Arrays.stream(p.value()),
                            g == null ? java.util.stream.Stream.<String>empty() : Arrays.stream(g.value()));
                }).toList();
        assertThat(paths).containsExactlyInAnyOrder("/claim", "/dispatched", "/{id}/outcome");
    }
}
