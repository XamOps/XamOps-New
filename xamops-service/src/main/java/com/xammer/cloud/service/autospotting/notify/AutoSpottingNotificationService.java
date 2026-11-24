package com.xammer.cloud.service.autospotting.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.NotificationStore;
import com.xammer.cloud.repository.autospotting.NotificationStore.NewNotification;
import com.xammer.cloud.repository.autospotting.NotificationStore.OperationFacts;
import com.xammer.cloud.service.autospotting.core.audit.AuditRedactor;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdcTaskDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes notification outbox rows (design-ux-ops.md section 7.1, PO-24). Every write runs
 * {@link Propagation#MANDATORY}: the row joins the transaction of the state change that causes it, so a
 * rolled-back transition leaves no row, and the dedupe key makes a retried transition a no-op.
 *
 * <p>{@link #onTransition} is called by {@code GroupLifecycleService} from inside its transition transaction.
 * It maps the transition's reason code (and, for failures and reverts, the operation's kind) to a customer
 * event, so no caller of the lifecycle service has to remember to notify. A transition with no customer event
 * (most of them) writes nothing.
 *
 * <p>The payload holds ids, stable codes and short labels only. It is scrubbed by {@link AuditRedactor} before
 * it is stored and {@code AutoSpottingEmailBuilder} reads nothing else, so no raw provider text, credential or
 * stack trace can reach an email or the in-app list (FR-SEC-06).
 */
@Service
public class AutoSpottingNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingNotificationService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_REASON_LENGTH = 300;

    private final NotificationStore store;
    private final AuditRedactor redactor;

    public AutoSpottingNotificationService(NotificationStore store, AuditRedactor redactor) {
        this.store = store;
        this.redactor = redactor;
    }

    /**
     * Insert one notice in the caller's transaction.
     *
     * @return false when the dedupe key already exists (the notice was written before); nothing changes then
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean record(NotificationEvent event, long clientId, long cloudAccountId, UUID groupId,
                          UUID operationId, String dedupeKey, Map<String, Object> payload) {
        String payloadJson;
        try {
            payloadJson = redactor.redactJson(JSON.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("notification payload is not serializable", e);
        }
        boolean inserted = store.insert(new NewNotification(event.name(), "CUSTOMER", event.mandatory(), clientId,
                cloudAccountId, groupId, operationId, dedupeKey, payloadJson));
        if (log.isDebugEnabled()) {
            log.debug("notification {} key={} inserted={}", event, dedupeKey, inserted);
        }
        return inserted;
    }

    /**
     * Map a group transition that was just written to a customer event, and record it. {@code reason} is the
     * transition's reason code; {@code op} the operation id (may be null); {@code writesDesiredConfig} is true
     * for a FALLBACK_APPLIED transition that carries the config change the fallback preempted.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTransition(SpotGroup group, GroupState from, GroupState to, String reason, UUID op,
                             PauseReason pauseReason, boolean writesDesiredConfig) {
        if (group == null || group.getClientId() == null || reason == null) {
            return;
        }
        Optional<OperationFacts> facts = op == null ? Optional.empty() : store.operationFacts(op);
        String kind = facts.map(OperationFacts::kind).orElse(null);
        boolean revertOp = "REVERT".equals(kind);
        switch (reason) {
            case "FALLBACK_APPLIED" -> {
                emit(NotificationEvent.FALLBACK_ENTERED, group, op, "fb_in:" + ok(op, group), base(group, from, to));
                if (writesDesiredConfig) {
                    emit(NotificationEvent.CONFIG_DEFERRED_BY_FALLBACK, group, op, "cfg_deferred:" + ok(op, group),
                            base(group, from, to));
                }
            }
            case "OP_FALLBACK_EXIT_VERIFIED" ->
                    emit(NotificationEvent.FALLBACK_EXITED, group, op, "fb_out:" + ok(op, group), base(group, from, to));
            case "OP_ENABLE_VERIFIED", "OP_ADOPT_VERIFIED", "OP_CONFIG_APPLY_VERIFIED" -> {
                Map<String, Object> p = base(group, from, to);
                p.put("operation", kind == null ? reason.substring(3, reason.length() - 9) : kind);
                emit(NotificationEvent.PLAN_APPLIED, group, op, "applied:" + ok(op, group), p);
            }
            case "REVERT_STARTED" -> {
                Map<String, Object> p = base(group, from, to);
                addConfirmation(p, facts);
                emit(NotificationEvent.REVERT_STARTED, group, op, "revert_started:" + ok(op, group), p);
            }
            case "OP_REVERT_VERIFIED" -> {
                Map<String, Object> p = base(group, from, to);
                addConfirmation(p, facts);
                emit(NotificationEvent.REVERT_COMPLETED, group, op, "revert_completed:" + ok(op, group), p);
            }
            case "REVERT_FAILED" -> emitRevertFailed(group, from, to, op, facts, "NEEDS_REVIEW");
            case "COMPENSATION_FAILED" -> {
                if (revertOp) {
                    emitRevertFailed(group, from, to, op, facts, "NEEDS_REVIEW");
                } else if (kind != null) {
                    emitApplyFailed(group, from, to, op, facts, "NEEDS_REVIEW");
                }
            }
            case "FAILED_BEFORE_MUTATION" -> {
                if (revertOp) {
                    emitRevertFailed(group, from, to, op, facts, "NO_CHANGE");
                } else if (kind != null) {
                    emitApplyFailed(group, from, to, op, facts, "NO_CHANGE");
                }
            }
            default -> {
                if (reason.startsWith("COMPENSATED_") && kind != null && !revertOp) {
                    emitApplyFailed(group, from, to, op, facts, "RESTORED");
                } else if (to == GroupState.PAUSED && pauseReason == PauseReason.DRIFT && from != GroupState.APPLYING) {
                    Long transitionId = store.latestTransitionId(group.getId());
                    emit(NotificationEvent.DRIFT_PAUSED, group, op, "drift:" + group.getId() + ":" + transitionId,
                            base(group, from, to));
                }
            }
        }
    }

    /**
     * ADMIN_ENABLED (PO14_RECOMMENDED mode only): a platform admin or an impersonated session confirmed an
     * enable on the customer's behalf. Names the identity and the reason the admin gave.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAdminEnabled(SpotGroup group, long cloudAccountId, Long clientId, UUID planId,
                                   String confirmedBy, String role, Long impersonatorUserId, String reason) {
        Long client = group != null && group.getClientId() != null ? group.getClientId() : clientId;
        if (client == null) {
            log.warn("ADMIN_ENABLED for plan {} has no client to tell; not recorded", planId);
            return;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        if (group != null) {
            p.put("groupKey", group.getGroupKey());
            p.put("provider", group.getProvider() == null ? null : group.getProvider().name());
        }
        p.put("accountName", store.accountName(cloudAccountId).orElse(null));
        p.put("occurredAt", Instant.now().toString());
        p.put("confirmedBy", confirmedBy);
        p.put("role", role);
        if (impersonatorUserId != null) {
            p.put("impersonatorUserId", impersonatorUserId);
        }
        p.put("reason", truncate(reason));
        p.put("requestId", MDC.get(AutoSpottingMdcTaskDecorator.REQUEST_ID));
        record(NotificationEvent.ADMIN_ENABLED, client, cloudAccountId, group == null ? null : group.getId(), null,
                "admin_enable:" + planId, p);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void emitApplyFailed(SpotGroup group, GroupState from, GroupState to, UUID op,
                                 Optional<OperationFacts> facts, String outcome) {
        Map<String, Object> p = base(group, from, to);
        p.put("outcome", outcome);
        p.put("errorCode", facts.map(OperationFacts::errorCode).map(AutoSpottingNotificationService::code)
                .orElse(null));
        emit(NotificationEvent.APPLY_FAILED, group, op, "apply_failed:" + ok(op, group), p);
    }

    private void emitRevertFailed(SpotGroup group, GroupState from, GroupState to, UUID op,
                                  Optional<OperationFacts> facts, String outcome) {
        Map<String, Object> p = base(group, from, to);
        p.put("outcome", outcome);
        p.put("errorCode", facts.map(OperationFacts::errorCode).map(AutoSpottingNotificationService::code)
                .orElse(null));
        addConfirmation(p, facts);
        emit(NotificationEvent.REVERT_FAILED, group, op, "revert_failed:" + ok(op, group),
                p);
    }

    /** The op id, or the group and its newest transition when a transition has no op (a plan-driven write). */
    private String ok(UUID op, SpotGroup group) {
        return op != null ? op.toString() : group.getId() + ":" + store.latestTransitionId(group.getId());
    }

    private void addConfirmation(Map<String, Object> payload, Optional<OperationFacts> facts) {
        UUID planId = facts.map(OperationFacts::planId).orElse(null);
        if (planId == null) {
            return;
        }
        store.planConfirmation(planId).ifPresent(c -> {
            // A reason is recorded when a platform admin confirmed (PO-14): the notice then names them.
            if (c.reason() != null && !c.reason().isBlank()) {
                payload.put("confirmedBy", c.confirmedBy());
                payload.put("reason", truncate(c.reason()));
            }
        });
    }

    private void emit(NotificationEvent event, SpotGroup group, UUID op, String dedupeKey,
                      Map<String, Object> payload) {
        record(event, group.getClientId(), group.getCloudAccountId(), group.getId(), op, dedupeKey, payload);
    }

    private Map<String, Object> base(SpotGroup group, GroupState from, GroupState to) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("groupKey", group.getGroupKey());
        p.put("provider", group.getProvider() == null ? null : group.getProvider().name());
        p.put("accountName", store.accountName(group.getCloudAccountId()).orElse(null));
        p.put("fromState", from == null ? null : from.name());
        p.put("toState", to == null ? null : to.name());
        p.put("occurredAt", Instant.now().toString());
        p.put("requestId", MDC.get(AutoSpottingMdcTaskDecorator.REQUEST_ID));
        return p;
    }

    /** A stable error code only: letters, digits, underscore, dot and dash, 64 characters at most. */
    private static String code(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9_.-]", "");
        return cleaned.isEmpty() ? null : cleaned.substring(0, Math.min(64, cleaned.length()));
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        return t.length() <= MAX_REASON_LENGTH ? t : t.substring(0, MAX_REASON_LENGTH);
    }
}
