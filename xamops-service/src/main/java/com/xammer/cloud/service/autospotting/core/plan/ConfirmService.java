package com.xammer.cloud.service.autospotting.core.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.domain.autospotting.SpotPlan;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.repository.autospotting.SpotPlanRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization;
import com.xammer.cloud.service.autospotting.core.AutoSpottingAuthorization.Decision;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupTransitions;
import com.xammer.cloud.service.autospotting.core.ops.OperationOutcomes;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Plan confirmation (design-core.md §7.1, §7.4; plan-phase-11.md task 11.2). Runs the eight checks in
 * order — each failure makes no mutating call — then flips the plan OPEN → CONFIRMED with a
 * conditional UPDATE and inserts the op QUEUED through {@code plan_id UNIQUE}, so a second confirm
 * returns the same op id (AC-SPN-01). Returns 202 {@code {operationId, status}}.
 *
 * <p>The checks (§7.4 "Confirm request"):
 * <ol>
 *   <li>switches (6.3), including the revert freeze/unreadable store for a REVERT;</li>
 *   <li>authorization (PO-14, §11) via {@link AutoSpottingAuthorization};</li>
 *   <li>kind and state (the §7.4 table) via {@link PlanKinds};</li>
 *   <li>plan OPEN and not expired (409 {@code PLAN_EXPIRED}); version matches (409
 *       {@code PLAN_VERSION_MISMATCH});</li>
 *   <li>every warning id acknowledged (422 {@code ACK_REQUIRED});</li>
 *   <li>PROGRESS kinds only: 100% Spot without opt-in, or desired ≤ 1 → 422 (never REVERT);</li>
 *   <li>busy and preemption ({@link PreemptionService});</li>
 *   <li>a live fingerprint that differs marks the plan STALE → 409 {@code PLAN_STALE}; a re-read
 *       timeout still returns 202 and op step 0 repeats the check.</li>
 * </ol>
 *
 * <p>No cloud is mutated by confirm itself — it enqueues an op. Whether that op mutates is decided by
 * the dispatcher/runner (dark in prod; dry-run in UAT via {@code autospotting.mutations.dry-run}).
 */
@Service
public class ConfirmService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmService.class);

    /** The live-fingerprint re-read budget (§7.4 step 8): 1.5 s; on timeout, op step 0 re-checks. */
    static final Duration FINGERPRINT_TIMEOUT = Duration.ofMillis(1500);

    private final SpotPlanRepository plans;
    private final SpotGroupRepository groups;
    private final SpotOperationRepository operations;
    private final OperationStore operationStore;
    private final PreemptionService preemption;
    private final AutoSpottingAuthorization authorization;
    private final RevertPreconditions revertPreconditions;
    private final SwitchService switchService;
    private final AutoSpottingSwitchProperties switchProperties;
    private final ProviderRegistry providers;
    private final AutoSpottingAuditWriter auditWriter;
    private final AutoSpottingNotificationService notifications;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConfirmService(SpotPlanRepository plans, SpotGroupRepository groups,
                          SpotOperationRepository operations, OperationStore operationStore,
                          PreemptionService preemption, AutoSpottingAuthorization authorization,
                          RevertPreconditions revertPreconditions, SwitchService switchService,
                          AutoSpottingSwitchProperties switchProperties, ProviderRegistry providers,
                          AutoSpottingAuditWriter auditWriter, AutoSpottingNotificationService notifications) {
        this.plans = plans;
        this.groups = groups;
        this.operations = operations;
        this.operationStore = operationStore;
        this.preemption = preemption;
        this.authorization = authorization;
        this.revertPreconditions = revertPreconditions;
        this.switchService = switchService;
        this.switchProperties = switchProperties;
        this.providers = providers;
        this.auditWriter = auditWriter;
        this.notifications = notifications;
    }

    /** The confirm command (controller-resolved; the account is already tenancy-authorized). */
    /** The marker stored in {@code confirm_acks} when the customer confirmed a 100% Spot plan (FR-PLN-04). */
    public static final String FULL_SPOT_OPTIN = "FULL_SPOT_OPTIN";
    /** GR-03: the 409 code of a GCP revert plan that needs a permission XamOps does not have. */
    public static final String REVERT_NEEDS_PERMISSION = "REVERT_NEEDS_PERMISSION";

    public record ConfirmCommand(int planVersion, List<String> acknowledgedWarningIds, boolean fullSpotOptIn,
                                 String reason, boolean dryRunRequested, String idempotencyKey,
                                 String requestSha256) {
    }

    /** The 202 result: the operation id and its status. */
    public record ConfirmResult(UUID operationId, String status) {
    }

    /**
     * Confirm {@code planId} for {@code account}. Runs in a new transaction: the conditional plan
     * UPDATE and the op insert commit together. A second confirm (any key, any instance) returns the
     * existing op id (§7.4 idempotent confirm, AC-SPN-01).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmResult confirm(CloudAccount account, ClientUserDetails user, UUID planId, ConfirmCommand cmd) {
        SpotPlan plan = plans.findById(planId)
                .filter(p -> account.getId().equals(p.getCloudAccountId()))
                .orElseThrow(() -> new AutoSpottingApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Plan not found."));

        // If this plan was already confirmed, return its op id (idempotent confirm, §7.4).
        SpotOperation existing = operations.findByPlanId(plan.getId()).orElse(null);
        if (existing != null) {
            return new ConfirmResult(existing.getId(), existing.getStatus().name());
        }

        Provider provider = providerOf(account);
        SpotGroup group = groups.findById(plan.getGroupId()).orElse(null);
        GroupState state = group == null ? null : group.getState();
        boolean revert = plan.getKind() == OperationKind.REVERT;

        // 1. Switches (6.3), including the revert freeze / unreadable store.
        SwitchState switches = switchService.readUncached();
        if (revert) {
            revertPreconditions.checkSwitches(switches);
        }

        // 2. Authorization (PO-14, §11).
        Decision decision = authorization.authorizeConfirm(user, account, plan.getKind(), cmd.reason());
        if (!decision.allowed()) {
            throw new AutoSpottingApiException(decision.denyStatus(), decision.denyCode(), decision.denyDetail());
        }

        // 3. Kind and state (the §7.4 table), repeated under the group's current state. GR-02: a REVERTED
        // group is revertible only after a drain-only revert that left XamOps' configuration on it (PASSROLE_DENIED
        // or CHECK_FAILED; RevertHistory.revertAllowedAgain).
        boolean regrant = revert && group != null && state == GroupState.REVERTED
                && RevertHistory.revertAllowedAgain(operations, plans, group);
        if (!PlanKinds.isAllowed(plan.getKind(), state, regrant)) {
            throw AutoSpottingApiException.planKindNotAllowed(PlanKinds.notAllowedDetail(plan.getKind(), state));
        }

        // 4. Plan OPEN and not expired; version matches.
        if (plan.getStatus() != SpotPlan.PlanStatus.OPEN) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "PLAN_EXPIRED",
                    "This plan is no longer open. Review a fresh plan.");
        }
        if (plan.getExpiresAt() != null && !plan.getExpiresAt().isAfter(Instant.now())) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "PLAN_EXPIRED",
                    "This plan expired. Review a fresh plan.");
        }
        if (plan.getVersion() != cmd.planVersion()) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "PLAN_VERSION_MISMATCH",
                    "The plan changed since you read it. Reload and try again.");
        }

        // GR-03: a GCP revert has no partial safe target that needs fewer permissions than the PATCH itself, so a plan
        // that carries REVERT_NEEDS_PERMISSION is refused here (retryable: re-plan after the grant). Nothing was
        // enqueued, so the group keeps its state and fallback and billing carry on.
        if (revert && provider == Provider.GCP && planNeedsPermission(plan)) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, REVERT_NEEDS_PERMISSION,
                    "XamOps is missing a permission it needs to revert this group. Grant it, then plan the revert again.",
                    true, null, null);
        }
        // 5. Every warning id acknowledged.
        Set<String> required = warningIds(plan);
        Set<String> acked = new HashSet<>(cmd.acknowledgedWarningIds() == null ? List.of() : cmd.acknowledgedWarningIds());
        if (!acked.containsAll(required)) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACK_REQUIRED",
                    "Acknowledge every warning before confirming.");
        }

        // 5b. A REVERT plan that carries any blocker can never run (it would fail in step 0): refuse it with the
        // plan's own code and text before an operation, alert or audit row exists (revert matrix, review High #2).
        // An unreadable plan body fails closed.
        if (revert) {
            JsonNode blocker = revertBlocker(plan);
            if (blocker != null) {
                throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        blocker.path("code").asText("CHECK_FAILED"),
                        blocker.path("text").asText("This revert can't run yet. Review a fresh revert plan."));
            }
        }
        // 6. PROGRESS kinds only (never REVERT): 100% Spot without opt-in, or desired ≤ 1.
        if (!revert && CompensationTargets.actionClass(plan.getKind())
                == com.xammer.cloud.domain.autospotting.ActionClass.PROGRESS) {
            if (plan.isRequiresFullSpotOptin() && !cmd.fullSpotOptIn()) {
                throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED",
                        "This plan is 100% Spot; confirm the full-Spot opt-in to proceed.");
            }
            JsonNode blocker = firstBlocker(plan);
            if (blocker != null) {
                // A blocked plan is never confirmed: a Launch Configuration group, desired <= 1, a
                // terminate-first maintenance policy and the other hard blockers end here with 422 and
                // the plan's own guidance text, before an operation exists (AC-AWS-01).
                throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        blocker.path("code").asText("CHECK_FAILED"), blocker.path("text").asText(
                                "This group can't be converted yet."));
            }
            if (desiredAtMostOne(plan)) {
                throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHECK_FAILED",
                        "This group's desired capacity is 1 or less; it is too small to convert.");
            }
        }

        // An ENABLE that will mutate runs only for a group its plan moved to PLANNED: the op's lifecycle
        // transitions (PLANNED -> APPLYING -> ACTIVE) are guarded on that state, so from any other state they all
        // miss and the cloud would change while XamOps keeps the group UNMANAGED (phase 23 defects D-23-07/08).
        // A dry-run ENABLE plan never moves the group to PLANNED (§7.2), so it is exempt.
        // The global dry-run switch is deliberately not part of this test: it only makes the op dry-run (below),
        // and a non-PLANNED ENABLE plan is refused whatever the switch says.
        boolean dryRunConfirm = plan.isDryRun() || cmd.dryRunRequested();
        if (plan.getKind() == OperationKind.ENABLE && !dryRunConfirm && state != GroupState.PLANNED) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_GROUP_NOT_PLANNED",
                    "This group isn't waiting for this enable any more. Review a fresh plan.");
        }
        // The op's first mutation moves the group to its start state (APPLYING / REVERTING). When the group's state
        // has no edge there, every such transition would miss, so refuse now instead of accepting a doomed op.
        if (!dryRunConfirm && plan.getKind() != OperationKind.ENABLE && group != null) {
            GroupState start = OperationOutcomes.startState(plan.getKind(), OperationPhase.FORWARD);
            if (start != null && state != start && !GroupTransitions.isAllowed(state, start)) {
                throw AutoSpottingApiException.planKindNotAllowed(PlanKinds.notAllowedDetail(plan.getKind(), state));
            }
        }

        // 8 (before enqueue): live fingerprint re-read (1.5 s). A mismatch marks the plan STALE; a
        // timeout still returns 202 and op step 0 repeats the check. Performed before preemption so a
        // stale plan never preempts a running op.
        FingerprintOutcome fp = reReadFingerprint(provider, group, plan);
        if (fp == FingerprintOutcome.MISMATCH) {
            plans.markStaleIfOpen(plan.getId());
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "PLAN_STALE",
                    "The group changed after the plan was created. Review a fresh plan.");
        }

        // 7. Busy and preemption (§4.7). The new op id is minted now so a preemption records it.
        UUID opId = UUID.randomUUID();
        PreemptionService.Preemption verdict = group == null
                ? PreemptionService.Preemption.clearForRowlessPlan()
                : preemption.preemptFor(group.getId(), plan.getKind(), opId);
        if (verdict.verdict() == PreemptionService.Verdict.BUSY) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_GROUP_BUSY",
                    "AutoSpotting is changing this group right now. Try again when it finishes.");
        }

        // Flip the plan OPEN -> CONFIRMED (conditional on status/version), then insert the op. The op
        // is dry-run when the plan was created dry-run, or the environment flag is on, or this confirm
        // asks for it (§7.2, plan-phase-11.md 11.3).
        boolean dryRun = plan.isDryRun() || cmd.dryRunRequested() || switchProperties.mutationsDryRun();
        int flipped = plans.confirmIfOpen(plan.getId(), plan.getVersion(), actorName(user), Instant.now(),
                acksJson(acksWithOptIn(cmd)), cmd.reason());
        if (flipped != 1) {
            // Another confirm won the race between our read and the UPDATE: return its op id.
            SpotOperation winner = operations.findByPlanId(plan.getId()).orElse(null);
            if (winner != null) {
                return new ConfirmResult(winner.getId(), winner.getStatus().name());
            }
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "PLAN_EXPIRED",
                    "This plan is no longer open. Review a fresh plan.");
        }

        UUID insertedOpId = insertOp(account, plan, group, state, opId, dryRun, cmd);
        auditConfirm(account, plan, group, decision);
        if (decision.adminEnabled()) {
            // PO14_RECOMMENDED only: tell the customer an admin enabled this on their behalf (ux §7.2). The
            // outbox row joins this transaction, so it exists exactly when the confirm does.
            notifications.recordAdminEnabled(group, account.getId(), null, plan.getId(), actorName(user),
                    decision.role() == null ? null : decision.role().name(),
                    decision.actor() == null ? null : decision.actor().impersonatorUserId(), cmd.reason());
        }

        if (log.isDebugEnabled()) {
            log.debug("plan {} CONFIRMED -> op {} kind={} dryRun={}", plan.getId(), insertedOpId,
                    plan.getKind(), dryRun);
        }
        return new ConfirmResult(insertedOpId, "QUEUED");
    }

    // ── op insert ────────────────────────────────────────────────────────────

    private UUID insertOp(CloudAccount account, SpotPlan plan, SpotGroup group, GroupState state, UUID opId,
                          boolean dryRun, ConfirmCommand cmd) {
        GroupState priorState = group != null ? group.getState()
                : (state != null ? state : GroupState.UNMANAGED);
        try {
            operationStore.insertQueued(OperationRecord.builder(opId, plan.getGroupId(), account.getId(), plan.getKind())
                    .planId(plan.getId())
                    .priorState(priorState)
                    .compensationTarget(CompensationTargets.forKind(plan.getKind()))
                    .targetConfigJson(targetConfigJson(plan))
                    .compensationConfigJson(compensationConfigJson(plan))
                    .idempotencyKey(cmd.idempotencyKey())
                    .requestSha256(cmd.requestSha256())
                    .requestedBy(plan.getCreatedBy())
                    .dryRun(dryRun)
                    .build());
            return opId;
        } catch (RuntimeException e) {
            // plan_id UNIQUE / ux_spot_op_running races: a second confirm inserted the op first.
            SpotOperation winner = operations.findByPlanId(plan.getId()).orElse(null);
            if (winner != null) {
                return winner.getId();
            }
            throw e;
        }
    }

    // ── checks / helpers ───────────────────────────────────────────────────────

    private enum FingerprintOutcome { MATCH, MISMATCH, TIMEOUT }

    private FingerprintOutcome reReadFingerprint(Provider provider, SpotGroup group, SpotPlan plan) {
        SpotProvider spotProvider = providers.find(provider).orElse(null);
        if (spotProvider == null || group == null) {
            // No provider (dark) or no row: op step 0 performs the fingerprint check instead (§7.4).
            return FingerprintOutcome.TIMEOUT;
        }
        try {
            ReadOnlyCloudView readOnly = new ReadOnlyCloudView() {
            };
            // Ownership comes from the plan's target (a first enable's row has no desired_config yet).
            String live = spotProvider.liveFingerprintFor(group, targetConfigJson(plan), readOnly);
            if (live == null) {
                live = spotProvider.getStatus(group, readOnly).liveFingerprint();
            }
            if (live == null || plan.getLiveFingerprint() == null) {
                return FingerprintOutcome.TIMEOUT;
            }
            return live.equals(plan.getLiveFingerprint()) ? FingerprintOutcome.MATCH : FingerprintOutcome.MISMATCH;
        } catch (RuntimeException timeoutOrError) {
            // A re-read timeout/error still returns 202; op step 0 repeats the fingerprint check (§7.4).
            return FingerprintOutcome.TIMEOUT;
        }
    }

    private Set<String> warningIds(SpotPlan plan) {
        Set<String> ids = new HashSet<>();
        try {
            JsonNode node = objectMapper.readTree(plan.getWarningIds() == null ? "[]" : plan.getWarningIds());
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                ids.add(it.next().asText());
            }
        } catch (Exception ignored) {
            // A malformed warning list fails closed to "no acks satisfy it" only if it had ids; an
            // empty/invalid list is treated as no warnings.
        }
        return ids;
    }

    /** True when the plan's eligibility carries a {@code REVERT_NEEDS_PERMISSION} blocker (GCP, GR-03). */
    private boolean planNeedsPermission(SpotPlan plan) {
        try {
            JsonNode body = objectMapper.readTree(plan.getBody() == null ? "{}" : plan.getBody());
            for (JsonNode blocker : body.path("eligibility").path("blockers")) {
                if (REVERT_NEEDS_PERMISSION.equals(blocker.path("code").asText())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // an unreadable body carries no such blocker; step 0 of the revert checks the permissions again
        }
        return false;
    }
    /** A REVERT plan's first blocker, a synthetic CHECK_FAILED when the body can't be read, or null. */
    private JsonNode revertBlocker(SpotPlan plan) {
        try {
            JsonNode blockers = objectMapper.readTree(plan.getBody() == null ? "{}" : plan.getBody())
                    .path("eligibility").path("blockers");
            return blockers.isArray() && !blockers.isEmpty() ? blockers.get(0) : null;
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("code", "CHECK_FAILED")
                    .put("text", "This revert plan could not be read. Review a fresh revert plan.");
        }
    }

    /** The first definitive blocker the plan recorded, or null when it has none. */
    private JsonNode firstBlocker(SpotPlan plan) {
        try {
            JsonNode body = objectMapper.readTree(plan.getBody() == null ? "{}" : plan.getBody());
            JsonNode blockers = body.path("eligibility").path("blockers");
            return blockers.isArray() && !blockers.isEmpty() ? blockers.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean desiredAtMostOne(SpotPlan plan) {
        // The plan body carries the rollout; a group with desired <= 1 is too small for a PROGRESS
        // conversion (§7.4 step 6, AC-PLN-08). The fake/provider encodes desired in the rollout's
        // instancesToReplace; absent that, do not block (safe default: not too small).
        try {
            JsonNode body = objectMapper.readTree(plan.getBody() == null ? "{}" : plan.getBody());
            JsonNode rollout = body.get("rollout");
            if (rollout != null && rollout.has("desired")) {
                return rollout.get("desired").asInt(2) <= 1;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return false;
    }

    /**
     * The configuration a PRIOR_CONFIG compensation restores (design-core.md §2.2.1): the plan body's
     * {@code current}, the owned fields as read when the plan was made. Null for the other targets, and
     * when the body carries no JSON object there.
     */
    private String compensationConfigJson(SpotPlan plan) {
        if (CompensationTargets.forKind(plan.getKind())
                != com.xammer.cloud.domain.autospotting.CompensationTarget.PRIOR_CONFIG) {
            return null;
        }
        try {
            JsonNode body = objectMapper.readTree(plan.getBody() == null ? "{}" : plan.getBody());
            JsonNode current = body.get("current");
            return current != null && current.isObject() ? current.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String targetConfigJson(SpotPlan plan) {
        try {
            JsonNode body = objectMapper.readTree(plan.getBody() == null ? "{}" : plan.getBody());
            JsonNode target = body.get("target");
            return target == null || target.isNull() ? null : target.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void auditConfirm(CloudAccount account, SpotPlan plan, SpotGroup group, Decision decision) {
        auditWriter.write(AuditRecord.builder("PLAN_CONFIRMED", decision.actor(), plan.getKind().name(), "OK")
                .planId(plan.getId())
                .provider(account.getProvider())
                .cloudAccountId(account.getId())
                .clientId(group != null ? group.getClientId() : null)
                .groupId(plan.getGroupId())
                .groupKey(group != null ? group.getGroupKey() : null)
                .details(decision.adminEnabled() ? "{\"adminEnabled\":true}" : null)
                .build());
    }

    private Provider providerOf(CloudAccount account) {
        return "GCP".equalsIgnoreCase(account.getProvider()) ? Provider.GCP : Provider.AWS;
    }

    /** The acknowledged warning ids, plus {@link #FULL_SPOT_OPTIN} when the customer ticked the opt-in. */
    private static List<String> acksWithOptIn(ConfirmCommand cmd) {
        List<String> acks = new java.util.ArrayList<>(cmd.acknowledgedWarningIds() == null
                ? List.of() : cmd.acknowledgedWarningIds());
        if (cmd.fullSpotOptIn()) {
            acks.add(FULL_SPOT_OPTIN);
        }
        return acks;
    }

    private static String acksJson(List<String> acks) {
        if (acks == null || acks.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < acks.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(acks.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private static String actorName(ClientUserDetails user) {
        if (user == null) {
            return "system";
        }
        return user.getUsername() != null ? user.getUsername() : String.valueOf(user.getId());
    }
}
