package com.xammer.cloud.service.autospotting.core.audit;

import com.xammer.cloud.domain.autospotting.AutoSpottingAudit;
import com.xammer.cloud.repository.autospotting.AutoSpottingAuditRepository;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdcTaskDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes {@code autospotting_audit} rows synchronously, in the caller's transaction (design-core.md
 * §8, FR-SPN-11, plan-phase-05.md task 5.3). It departs from the codebase's {@code @Async}
 * convention ({@code B/service/matters/MattersAuditWriter.java}) on purpose: an audit record must be
 * part of the same commit as the state change or step result it records, so it can never be lost.
 *
 * <p>Every write is {@link Propagation#MANDATORY}: calling it without an active transaction throws
 * {@link org.springframework.transaction.IllegalTransactionStateException}, which keeps a caller
 * from writing an audit row outside the unit of work it is auditing. {@code @Async} is never used.
 *
 * <p>Before and after documents are scrubbed by {@link AuditRedactor} before they reach the row
 * (FR-SEC-06, AC-SEC-07). The {@code request_id} is read from MDC
 * ({@link AutoSpottingMdcTaskDecorator#REQUEST_ID}); for an impersonated (Login-As) session the
 * record carries both the impersonator and the impersonated identity (AC-SPN-10).
 */
@Service
public class AutoSpottingAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AutoSpottingAuditWriter.class);

    private final AutoSpottingAuditRepository repository;
    private final AuditRedactor redactor;

    public AutoSpottingAuditWriter(AutoSpottingAuditRepository repository, AuditRedactor redactor) {
        this.repository = repository;
        this.redactor = redactor;
    }

    /**
     * Write one audit record in the caller's transaction. {@code before}/{@code after} are JSON
     * documents (nullable) that are redacted here. The {@code request_id} is taken from MDC.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no active
     *         transaction (MANDATORY), so an audit row is never written outside its unit of work.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(AuditRecord record) {
        AutoSpottingAudit row = new AutoSpottingAudit();
        row.setOccurredAt(record.occurredAt() != null ? record.occurredAt() : Instant.now());
        row.setEventType(record.eventType());
        row.setActorType(record.actor().type().name());
        row.setActorUserId(record.actor().userId());
        row.setActorRole(record.actor().role());
        row.setImpersonatorUserId(record.actor().impersonatorUserId());
        row.setRequestId(mdcOrNull(AutoSpottingMdcTaskDecorator.REQUEST_ID));
        row.setOperationId(record.operationId());
        row.setPlanId(record.planId());
        row.setStepSeq(record.stepSeq());
        row.setProvider(record.provider());
        row.setClientId(record.clientId());
        row.setCloudAccountId(record.cloudAccountId());
        row.setGroupId(record.groupId());
        row.setGroupKey(record.groupKey());
        row.setAction(record.action());
        row.setOutcome(record.outcome());
        row.setErrorCode(record.errorCode());
        row.setCloudRequestIds(redactor.redactJson(record.cloudRequestIds()));
        row.setBeforeState(redactor.redactJson(record.beforeState()));
        row.setAfterState(redactor.redactJson(record.afterState()));
        row.setDetails(redactor.redactJson(record.details()));
        repository.save(row);
        if (log.isDebugEnabled()) {
            log.debug("audit {} {} outcome={} op={} group={}", record.eventType(), record.action(),
                    record.outcome(), record.operationId(), record.groupId());
        }
    }

    private static String mdcOrNull(String key) {
        String value = MDC.get(key);
        return (value == null || value.isEmpty()) ? null : value;
    }

    /** Who performed the audited action (design-core.md §8). */
    public enum ActorType {
        USER,
        SYSTEM,
        OPERATOR
    }

    /**
     * The actor identity. For an impersonated (Login-As) session, {@code userId} is the impersonated
     * identity and {@code impersonatorUserId} is the operator (AC-SPN-10); both are recorded.
     */
    public record Actor(ActorType type, Long userId, String role, Long impersonatorUserId) {

        public static Actor system() {
            return new Actor(ActorType.SYSTEM, null, null, null);
        }

        public static Actor user(Long userId, String role) {
            return new Actor(ActorType.USER, userId, role, null);
        }

        public static Actor operator(Long userId, String role) {
            return new Actor(ActorType.OPERATOR, userId, role, null);
        }

        /** An impersonated session: the impersonated identity acts, the operator is recorded alongside. */
        public static Actor impersonated(Long impersonatedUserId, String role, Long impersonatorUserId) {
            return new Actor(ActorType.USER, impersonatedUserId, role, impersonatorUserId);
        }
    }

    /**
     * One audit record. {@code before}/{@code after}/{@code details}/{@code cloudRequestIds} are JSON
     * documents redacted on write; the rest map to {@code autospotting_audit} columns. Built with
     * {@link #builder(String, Actor, String, String)} for the required columns.
     */
    public record AuditRecord(
            Instant occurredAt,
            String eventType,
            Actor actor,
            UUID operationId,
            UUID planId,
            Integer stepSeq,
            String provider,
            Long clientId,
            Long cloudAccountId,
            UUID groupId,
            String groupKey,
            String action,
            String outcome,
            String errorCode,
            String cloudRequestIds,
            String beforeState,
            String afterState,
            String details) {

        /** Required columns: {@code event_type}, {@code actor}, {@code action}, {@code outcome}. */
        public static Builder builder(String eventType, Actor actor, String action, String outcome) {
            return new Builder(eventType, actor, action, outcome);
        }

        public static final class Builder {
            private Instant occurredAt;
            private final String eventType;
            private final Actor actor;
            private UUID operationId;
            private UUID planId;
            private Integer stepSeq;
            private String provider;
            private Long clientId;
            private Long cloudAccountId;
            private UUID groupId;
            private String groupKey;
            private final String action;
            private final String outcome;
            private String errorCode;
            private String cloudRequestIds;
            private String beforeState;
            private String afterState;
            private String details;

            private Builder(String eventType, Actor actor, String action, String outcome) {
                this.eventType = eventType;
                this.actor = actor;
                this.action = action;
                this.outcome = outcome;
            }

            public Builder occurredAt(Instant v) { this.occurredAt = v; return this; }
            public Builder operationId(UUID v) { this.operationId = v; return this; }
            public Builder planId(UUID v) { this.planId = v; return this; }
            public Builder stepSeq(Integer v) { this.stepSeq = v; return this; }
            public Builder provider(String v) { this.provider = v; return this; }
            public Builder clientId(Long v) { this.clientId = v; return this; }
            public Builder cloudAccountId(Long v) { this.cloudAccountId = v; return this; }
            public Builder groupId(UUID v) { this.groupId = v; return this; }
            public Builder groupKey(String v) { this.groupKey = v; return this; }
            public Builder errorCode(String v) { this.errorCode = v; return this; }
            public Builder cloudRequestIds(String v) { this.cloudRequestIds = v; return this; }
            public Builder beforeState(String v) { this.beforeState = v; return this; }
            public Builder afterState(String v) { this.afterState = v; return this; }
            public Builder details(String v) { this.details = v; return this; }

            public AuditRecord build() {
                return new AuditRecord(occurredAt, eventType, actor, operationId, planId, stepSeq,
                        provider, clientId, cloudAccountId, groupId, groupKey, action, outcome,
                        errorCode, cloudRequestIds, beforeState, afterState, details);
            }
        }
    }
}
