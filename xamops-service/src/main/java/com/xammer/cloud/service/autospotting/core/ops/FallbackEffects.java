package com.xammer.cloud.service.autospotting.core.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.TransitionRequest;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.FallbackSteps;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * The lifecycle effects of the fallback programs (design-aws.md §6.4, §6.5; design-core.md §2.2, §5.2). A
 * provider's fallback steps only report what they saw; the group row changes here, under the group lease, in
 * the same fenced transaction as the audit row:
 *
 * <ul>
 *   <li><b>VERIFY_FALLBACK_APPLIED done</b> (FALLBACK_ENTER): the group goes to FALLBACK, or stays PAUSED with
 *       {@code paused_from_state=FALLBACK}, with {@code config_mode=FALLBACK}, {@code fallback_fingerprint}
 *       from the verifying describe, and {@code effective_at} equal to the mutation step's {@code intent_at} so
 *       billing ends at the mutation. A CONFIG_APPLY or PROFILE_CHANGE this fallback preempted is written to
 *       {@code desired_config} (so the next FALLBACK_EXIT applies it) with one
 *       {@code CONFIG_DEFERRED_BY_FALLBACK} audit row. A {@code FALLBACK_ENTERED} audit row records every
 *       fallback.</li>
 *   <li><b>WAIT_CAPACITY</b>: FALLBACK to DEGRADED once the group is still short after the still-short window,
 *       DEGRADED to FALLBACK when capacity recovers. {@code config_mode} stays FALLBACK, so recovery never goes
 *       straight to ACTIVE.</li>
 *   <li><b>FALLBACK_EXIT success</b>: ACTIVE with {@code config_mode=MANAGED} and the fingerprint cleared.</li>
 * </ul>
 */
final class FallbackEffects {
    private static final Logger log = LoggerFactory.getLogger(FallbackEffects.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SpotOperationRepository operations;
    private final SpotGroupRepository groups;
    private final OperationStore store;
    private final GroupLifecycleService lifecycle;
    private final FencedTx fencedTx;
    private final AutoSpottingAuditWriter auditWriter;
    private final AutoSpottingMetrics metrics;

    FallbackEffects(SpotOperationRepository operations, SpotGroupRepository groups, OperationStore store,
                    GroupLifecycleService lifecycle, FencedTx fencedTx, AutoSpottingAuditWriter auditWriter,
                    AutoSpottingMetrics metrics) {
        this.operations = operations;
        this.groups = groups;
        this.store = store;
        this.lifecycle = lifecycle;
        this.fencedTx = fencedTx;
        this.auditWriter = auditWriter;
        this.metrics = metrics;
    }

    /** Apply the effect of {@code step}'s outcome, when it has one. A failure here never fails the op. */
    void afterStep(LeaseHandle handle, SpotOperation op, SpotGroup group, Step step, StepOutcome outcome) {
        if (op.getKind() != OperationKind.FALLBACK_ENTER || op.getPhase() != OperationPhase.FORWARD) {
            return;
        }
        try {
            if (FallbackSteps.VERIFY_FALLBACK_APPLIED.equals(step.name()) && outcome instanceof StepOutcome.Done d) {
                applied(handle, op, group, d.outputJson());
            } else if (FallbackSteps.RESTORE_UPDATE_POLICY.equals(step.name()) && outcome instanceof StepOutcome.Done d) {
                String fingerprint = text(d.outputJson(), FallbackSteps.FINGERPRINT);
                SpotGroup fresh = groups.findById(group.getId()).orElse(group);
                if (fingerprint != null && fresh.getConfigMode() == ConfigMode.FALLBACK) {
                    lifecycle.refreshFallbackFingerprint(handle, fresh, fingerprint);
                }
            } else if (FallbackSteps.WAIT_CAPACITY.equals(step.name())) {
                String json = outcome instanceof StepOutcome.Done d ? d.outputJson()
                        : outcome instanceof StepOutcome.InProgress p ? p.progressJson() : null;
                capacity(handle, op, group, json);
            }
        } catch (IllegalArgumentException forbidden) {
            log.warn("op {} fallback effect not allowed: {}", op.getId(), forbidden.getMessage());
        }
    }

    private void applied(LeaseHandle handle, SpotOperation op, SpotGroup group, String outputJson) {
        SpotGroup fresh = groups.findById(group.getId()).orElse(group);
        if (fresh.getConfigMode() == ConfigMode.FALLBACK && (fresh.getState() == GroupState.FALLBACK
                || fresh.getState() == GroupState.PAUSED || fresh.getState() == GroupState.DEGRADED)) {
            return; // a resume after the transition already landed (idempotent)
        }
        String fingerprint = text(outputJson, FallbackSteps.FINGERPRINT);
        SpotOperation freshOp = operations.findById(op.getId()).orElse(op);
        Instant intentAt = freshOp.getFirstMutationSeq() == null ? null
                : store.stepIntentAt(op.getId(), freshOp.getFirstMutationSeq()).orElse(null);
        if (intentAt == null) {
            intentAt = Instant.now();
        }
        boolean paused = fresh.getState() == GroupState.PAUSED;
        GroupState to = paused ? GroupState.PAUSED : GroupState.FALLBACK;
        TransitionRequest request = TransitionRequest.of(to, "FALLBACK_APPLIED", Actor.system(), op.getId(), intentAt)
                .withConfigMode(ConfigMode.FALLBACK);
        if (paused) {
            request = request.withPause(fresh.getPauseReason(), GroupState.FALLBACK);
        }
        String deferred = deferredTarget(op);
        if (deferred != null) {
            request = request.writingDesiredConfig(deferred, fresh.getDesiredFingerprint());
        }
        GroupLifecycleService.Result result =
                lifecycle.transitionWithFallbackFingerprint(handle, fresh, request, fingerprint);
        if (result == GroupLifecycleService.Result.STALE) {
            log.warn("op {} FALLBACK_APPLIED transition missed its guard; the sweep re-reads", op.getId());
            return;
        }
        final String intentJson = "{\"intentAt\":\"" + intentAt + "\",\"to\":\"" + to + "\"}";
        fencedTx.run(handle, () -> {
            audit(op, fresh, "FALLBACK_ENTERED", "FALLBACK", "SUCCESS", intentJson);
            if (deferred != null) {
                audit(op, fresh, "CONFIG_DEFERRED_BY_FALLBACK", "FALLBACK", "SUCCESS",
                        "{\"applyAt\":\"FALLBACK_EXIT\"}");
            }
        });
        metrics.fallback(fresh.getProvider(), "enter", "applied");
    }

    private String deferredTarget(SpotOperation fallbackOp) {
        List<SpotOperation> preempted = operations.findConfigOpsPreemptedBy(fallbackOp.getId());
        for (SpotOperation p : preempted) {
            if (p.getTargetConfig() != null && !p.getTargetConfig().isBlank()) {
                return p.getTargetConfig();
            }
        }
        return null;
    }

    private void capacity(LeaseHandle handle, SpotOperation op, SpotGroup group, String json) {
        if (json == null) {
            return;
        }
        String capacity = text(json, FallbackSteps.CAPACITY);
        boolean degrade = bool(json, "degrade");
        SpotGroup fresh = groups.findById(group.getId()).orElse(group);
        if (fresh.getConfigMode() != ConfigMode.FALLBACK) {
            return;
        }
        if (FallbackSteps.SHORT.equals(capacity) && degrade && fresh.getState() == GroupState.FALLBACK) {
            lifecycle.transition(handle, fresh, TransitionRequest.of(GroupState.DEGRADED, "ON_DEMAND_SHORT",
                    Actor.system(), op.getId(), Instant.now()));
            metrics.reconcileAlert(GroupState.FALLBACK.name());
            fencedTx.run(handle, () -> audit(op, fresh, "FALLBACK_CAPACITY_SHORT", "FALLBACK", "DEGRADED", null));
        } else if (FallbackSteps.OK.equals(capacity) && fresh.getState() == GroupState.DEGRADED) {
            lifecycle.transition(handle, fresh, TransitionRequest.of(GroupState.FALLBACK, "CAPACITY_RECOVERED",
                    Actor.system(), op.getId(), Instant.now()));
        }
    }

    /** FALLBACK_EXIT success: ACTIVE, MANAGED, fingerprint cleared, desired config rewritten to the live result. */
    void exitSucceeded(LeaseHandle handle, SpotOperation op, SpotGroup group, String[] desiredConfigWrite) {
        SpotGroup fresh = groups.findById(group.getId()).orElse(group);
        TransitionRequest request = TransitionRequest.of(GroupState.ACTIVE, "OP_FALLBACK_EXIT_VERIFIED",
                Actor.system(), op.getId(), Instant.now()).withConfigMode(ConfigMode.MANAGED);
        if (desiredConfigWrite != null) {
            request = request.writingDesiredConfig(desiredConfigWrite[0], desiredConfigWrite[1]);
        }
        lifecycle.transitionWithFallbackFingerprint(handle, fresh, request, null);
        fencedTx.run(handle, () -> audit(op, fresh, "FALLBACK_EXITED", "FALLBACK", "SUCCESS", null));
        metrics.fallback(fresh.getProvider(), "exit", "applied");
    }

    private void audit(SpotOperation op, SpotGroup g, String event, String action, String outcome, String details) {
        auditWriter.write(AuditRecord.builder(event, Actor.system(), action, outcome)
                .operationId(op.getId())
                .provider(g.getProvider() == null ? null : g.getProvider().name())
                .clientId(g.getClientId())
                .cloudAccountId(g.getCloudAccountId())
                .groupId(g.getId())
                .groupKey(g.getGroupKey())
                .details(details)
                .build());
    }

    private static String text(String json, String field) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(json).path(field);
            return n.isMissingNode() || n.isNull() ? null : n.asText();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static boolean bool(String json, String field) {
        try {
            return JSON.readTree(json).path(field).asBoolean(false);
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
