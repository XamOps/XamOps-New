package com.xammer.cloud.dto.autoscaling;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * The agent wire contract (design 3.4, 3.8), frozen by the golden fixtures under
 * {@code src/test/resources/contract/approval/} and their byte-identical Go copies.
 *
 * <p>Strictness: the <em>envelope</em> fields declared here are checked with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=true} in the contract test. {@code params}, {@code evidence},
 * {@code actions}, {@code reports} and {@code consolidation} are opaque {@link JsonNode}s and excluded
 * from field-level strictness (a Go change inside {@code fitsim.Plan} must not break this build); the
 * contract test asserts their JSON <em>types</em> instead. Production mappers stay lenient.
 */
public final class AgentProposalDtos {

    private AgentProposalDtos() {
    }

    public static final int SCHEMA_VERSION = 1;

    // ------------------------------------------------------------------ agent -> backend

    public record BatchRequest(Integer schemaVersion, String cycleId, Instant observedAt, List<Item> items,
                               Rebalance rebalance) {
    }

    public record Item(String dedupeKey, String actionType, String operation, String originController,
                       Target target, JsonNode params, String verdict, Refusal refusal, Gate gate,
                       String planFingerprint, Integer dwellSeconds, JsonNode evidence) {
    }

    public record Target(String kind, String name, String uid, String providerId, String nodeGroup) {
    }

    public record Refusal(List<String> codes, String reason) {
    }

    public record Gate(String mode, String reason, Boolean dryRun) {
    }

    /** Only the agent's final chunk carries this; {@code complete} consumes the stored rebalance request. */
    public record Rebalance(Boolean complete, Integer candidateCount) {
    }

    /** {@code POST /claim} body: nothing but the cycle id is read. */
    public record ClaimRequest(String cycleId) {
    }

    /** {@code POST /{id}/outcome}. */
    public record OutcomeRequest(Integer schemaVersion, String proposalId, String status, Boolean verified,
                                 Boolean dryRun, String stage, String reason, String operationId,
                                 JsonNode actions, JsonNode reports, JsonNode consolidation) {
    }

    // ------------------------------------------------------------------ backend -> agent

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BatchResponse(int accepted, List<Suppressed> suppressed, List<Rejected> rejected) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Suppressed(String dedupeKey, String reason) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Rejected(String dedupeKey, String code) {
    }

    /** The claim response: exactly one approved action. {@code dwellSeconds} is non-null only for NODE_CONSOLIDATE. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ApprovedAction(String proposalId, long version, String actionType, String operation,
                                 String originController, String dedupeKey, Target target, JsonNode params,
                                 String planFingerprint, Instant firstSeenAt, Integer dwellSeconds,
                                 ApprovedBy approvedBy, Instant approvedAt, Instant approvedUntil,
                                 boolean dryRunAtProposal) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ApprovedBy(Long id, String name) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DispatchedResponse(List<DispatchedItem> items) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DispatchedItem(String id, String targetRef, String targetIdentity, String operationId,
                                 String actionType, String operation, Instant dispatchedAt, boolean dryRun) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OutcomeResponse(String status) {
    }
}
