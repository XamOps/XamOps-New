package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Append-only audit trail for the Matters DSPM add-on: every use of a customer's API key, and every
 * operator change to a mapping or entitlement.
 *
 * <h2>Why this table exists</h2>
 * It is the deliberate replacement for something given up by choice. Holding each customer's Matters
 * key in AWS Secrets Manager would have produced a CloudTrail record of every {@code GetSecretValue}
 * for free. Encrypting in-application instead means credential reads happen entirely inside this
 * process and leave no trace anywhere else — so the trail has to be written here or it does not
 * exist.
 *
 * <p>The question it has to be able to answer is "who caused customer X's sensitive-data inventory to
 * be read, and when". For a DSPM product that is not bookkeeping: the data behind these calls is a
 * catalogue of where a customer's PII and secrets live, and an operator with impersonation rights can
 * reach it. Without a record, that access is invisible.
 *
 * <h2>What is never written here</h2>
 * The API key itself, in any form. {@link #apiKeyId} is the non-secret key-id segment of
 * {@code mts_<env>_<key_id>_<secret>}, which identifies <em>which</em> key was used — enough to tie a
 * call to a credential during a rotation or an incident, useless to anyone who obtains this table.
 * An audit log that records the thing it is auditing access to would be a second copy of the secret
 * in a table designed to be widely readable and long-retained.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "matters_access_audit")
public class MattersAccessAudit {

    // ── Credential use ───────────────────────────────────────────────────────────────────────────
    /** A customer's key was decrypted and used against Matters. The high-volume case. */
    public static final String ACTION_CREDENTIAL_USED = "CREDENTIAL_USED";

    // ── Operator mutations ───────────────────────────────────────────────────────────────────────
    public static final String ACTION_MAPPING_CREATED = "MAPPING_CREATED";
    public static final String ACTION_MAPPING_UPDATED = "MAPPING_UPDATED";
    public static final String ACTION_MAPPING_DELETED = "MAPPING_DELETED";
    public static final String ACTION_MAPPING_VERIFIED = "MAPPING_VERIFIED";
    /** The stored key was replaced. Separate from a plain update — it is the security-relevant one. */
    public static final String ACTION_KEY_ROTATED = "KEY_ROTATED";
    /**
     * A live mapping was pointed at a different Matters project, which swaps the customer's entire
     * dataset. Its own action because no other update changes what a customer sees so completely,
     * and because it is the shape of a misconfiguration that leaks one customer's data to another.
     */
    public static final String ACTION_PROJECT_REPOINTED = "PROJECT_REPOINTED";
    public static final String ACTION_ENTITLEMENT_GRANTED = "ENTITLEMENT_GRANTED";
    public static final String ACTION_ENTITLEMENT_WITHDRAWN = "ENTITLEMENT_WITHDRAWN";

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_FAILURE = "FAILURE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The client whose data was accessed or whose configuration changed.
     *
     * A plain {@code Long}, not a {@code @ManyToOne} to {@link Client}. Two reasons: the audit row
     * must survive its client being deleted — "this customer's inventory was read, then the customer
     * was removed" is exactly the sequence an audit log exists to preserve — and a lazy association
     * here would issue a query per row when the admin viewer lists a page of them.
     */
    @Column(name = "client_id")
    private Long clientId;

    /** Who acted. Null for scheduled or system-initiated work, which {@link #actorType} records. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_username", length = 256)
    private String actorUsername;

    /**
     * The acting principal's role, captured at the time of the call.
     *
     * Stored rather than resolved later because roles change: an operator who was
     * {@code ROLE_SUPER_ADMIN} when they read a customer's inventory may not be one when the log is
     * reviewed, and the question is always what they were permitted to do at the time.
     */
    @Column(name = "actor_role", length = 64)
    private String actorRole;

    /**
     * {@code CUSTOMER}, {@code OPERATOR}, or {@code SYSTEM}.
     *
     * The distinction that matters most on review. A customer reading their own inventory is
     * routine; an operator reading it is the event worth looking at. Derived at write time from the
     * authenticated principal and whether impersonation was active.
     */
    @Column(name = "actor_type", length = 32)
    private String actorType;

    /** True when the call was made through super-admin impersonation. */
    @Column(name = "impersonated", nullable = false)
    private boolean impersonated = false;

    /** One of the {@code ACTION_*} constants. */
    @Column(name = "action", length = 64, nullable = false)
    private String action;

    /**
     * Key-id segment of the credential used — never the secret. See the class javadoc.
     * Null for actions that use no credential, such as an entitlement change.
     */
    @Column(name = "api_key_id", length = 128)
    private String apiKeyId;

    /** The Matters project the action concerned, so a repoint is legible after the fact. */
    @Column(name = "matters_project_id", length = 128)
    private String mattersProjectId;

    /**
     * Which Matters path was called, e.g. {@code /api/v1/assets}. Path only — query strings can carry
     * a customer's search terms, and those do not belong in a long-retained table.
     */
    @Column(name = "endpoint", length = 256)
    private String endpoint;

    @Column(name = "outcome", length = 32, nullable = false)
    private String outcome = OUTCOME_SUCCESS;

    /**
     * {@code MattersApiException.Reason} name when {@link #outcome} is FAILURE.
     * The machine-readable code, so failures can be counted by cause without parsing prose.
     */
    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    /**
     * Free-text context: which filters were applied, what an operator changed, why a verify failed.
     * Never credentials, and never raw customer data.
     */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    /** Caller IP, honouring X-Forwarded-For since the services sit behind an ALB and nginx. */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** Upstream latency in milliseconds, for credential-use rows. Null for mutations. */
    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Factory for the high-volume credential-use row. */
    public static MattersAccessAudit credentialUse(Long clientId, String apiKeyId,
                                                   String projectId, String endpoint) {
        MattersAccessAudit a = new MattersAccessAudit();
        a.setClientId(clientId);
        a.setAction(ACTION_CREDENTIAL_USED);
        a.setApiKeyId(apiKeyId);
        a.setMattersProjectId(projectId);
        a.setEndpoint(endpoint);
        return a;
    }

    /** Factory for an operator mutation. */
    public static MattersAccessAudit mutation(Long clientId, String action, String projectId) {
        MattersAccessAudit a = new MattersAccessAudit();
        a.setClientId(clientId);
        a.setAction(action);
        a.setMattersProjectId(projectId);
        a.setActorType("OPERATOR");
        return a;
    }

    public MattersAccessAudit failed(String reason, String detail) {
        this.outcome = OUTCOME_FAILURE;
        this.failureReason = reason;
        this.detail = detail;
        return this;
    }
}
