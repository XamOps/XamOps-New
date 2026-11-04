package com.xammer.cloud.dto.autoscaling;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Wire records of the user-facing proposal API (design 3.8). Absent values are sent as JSON
 * {@code null} (never omitted) so the TypeScript types can be exact. {@code params} and
 * {@code evidence} are opaque {@link JsonNode}s; the UI reads only what it renders from them.
 */
public final class ProposalDtos {

    private ProposalDtos() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProposalDto(
            String id, long version,
            String actionType,            // WORKLOAD_RESIZE|NODE_DRAIN|NODE_CONSOLIDATE|REBALANCE
            String operation,             // DRAIN|CORDON_ONLY|EMPTY|IN_PLACE|null
            String originController,      // vertical-engine|node-optimizer|evictor|consolidation|rebalancer
            String targetKind,            // WORKLOAD|NODE
            String targetRef,
            String status,
            String statusReason,
            String verdict,               // ALLOWED|REFUSED (at proposal time)
            List<String> refusalCodes,
            String refusalReason,
            String refusalStage,          // PROPOSAL|EXECUTION|null
            String planFingerprint,
            boolean dryRun,
            Instant proposedAt, Instant firstSeenAt, Instant lastSeenAt,
            Instant expiresAt,
            Instant approvableAfter,      // backend-owned; null unless NODE_CONSOLIDATE
            Instant approvedUntil,
            String decidedByName, Instant decidedAt, String decisionNote,
            String outcomeStatus,
            Boolean outcomeVerified,
            JsonNode params,
            JsonNode evidence) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProposalEventDto(Instant at, String actorType, String actorName, String event, String detail) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProposalDetailDto(ProposalDto proposal, List<ProposalEventDto> events) {
    }

    /** The list-scoping {@code manual} object (NOT the capabilities one): why this page's list may be empty. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ManualListAvailability(boolean available, String reason, boolean workloadProposals) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Counts(int pending, int approved, int dispatched, int refused) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProposalListDto(
            String state,                 // OK | UNREADABLE (list-level only)
            String approvalMode, String effective,
            ManualListAvailability manual,
            Counts counts,
            List<ProposalDto> items, String nextCursor,
            Instant generatedAt, Instant lastAgentCheckIn) {
    }

    /** Body of {@code POST /proposals/{id}/approve}. */
    public record ApproveRequest(Long expectedVersion, String planFingerprint, String note) {
    }

    /** Body of {@code POST /proposals/{id}/reject}. */
    public record RejectRequest(Long expectedVersion, String note) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DecisionResponse(String id, String status, boolean alreadyDecided) {
    }

    /** Body of {@code PUT /approval-mode}. */
    public record ApprovalModeRequest(String mode) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ApprovalModeResponse(String mode, String effective, Instant changedAt) {
    }

    /**
     * The per-cluster {@code manual} object of {@code /api/xamops/k8s/capabilities} (the
     * capabilities shape, distinct from {@link ManualListAvailability}).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ManualCapabilityDto(String mode, String effective, int pending, boolean available,
                                      String reason, boolean workloadProposals) {
    }
}
