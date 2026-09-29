package com.xammer.cloud.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.xammer.cloud.domain.converter.EncryptedStringConverter;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Maps one XamOps client (customer organisation) to one Matters.ai project.
 *
 * <h2>Why this table exists at all</h2>
 * The XamSecure/Upwind integration needs nothing like it: Xammer holds a single Upwind
 * organisation, one shared credential reads the whole estate, and tenancy is re-established
 * downstream by filtering findings to the cloud accounts a client owns
 * ({@code XamSecureService.resolveAccountIds}).
 *
 * Matters.ai cannot work that way. Its API key is issued against exactly one account <em>and</em>
 * one project, and the docs are explicit that tenancy is derived from the key — there is no header
 * or query parameter that names an account or project, so a key "cannot be pointed at data outside
 * the account and project it was issued for". Every response is pre-filtered to that scope.
 *
 * Two consequences follow, and they drive this whole design:
 * <ol>
 *   <li><b>One key per customer, not one per platform.</b> Xammer owns a single Matters account and
 *       creates a project per customer inside it, so the credential is necessarily per-client. It
 *       therefore has to be stored, which is why this row exists and why {@link #apiKey} is
 *       encrypted at rest.</li>
 *   <li><b>The key IS the tenancy boundary.</b> Picking the row by {@code client_id} and using its
 *       key is the entire isolation mechanism — there is no second filter to apply afterwards, and
 *       no way for a request to widen its own scope. That makes {@link #mattersProjectId} unique:
 *       pointing two clients at one project is the only way to leak data here, so the database
 *       refuses it rather than trusting every future call site to check.</li>
 * </ol>
 *
 * <h2>Lifecycle</h2>
 * Created by an internal operator from BillOps admin → Accounts after the matching project has been
 * created in Matters, which is the manual step the integration is built around: a new customer on
 * Matters is not visible in XamOps until someone records the mapping here. {@link #enabled} and
 * {@code Client.mattersDspmEnabled} are separate on purpose — the first is "this connection works",
 * the second is "this customer has bought the add-on".
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "matters_project_mapping",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_mpm_client", columnNames = "client_id"),
           @UniqueConstraint(name = "uk_mpm_project", columnNames = "matters_project_id")
       })
public class MattersProjectMapping {

    /** Connection states, as last observed by a verification call. */
    public static final String STATUS_UNVERIFIED = "UNVERIFIED";
    public static final String STATUS_CONNECTED = "CONNECTED";
    public static final String STATUS_INVALID_KEY = "INVALID_KEY";
    public static final String STATUS_FORBIDDEN = "FORBIDDEN";
    public static final String STATUS_UNREACHABLE = "UNREACHABLE";

    /**
     * The stored key could not be decrypted — an XamOps-side fault, not a Matters-side one.
     *
     * Its own status because the remedy is the opposite of every other failure here: nobody should
     * rotate the Matters key, because the key is probably fine. What is wrong is
     * {@code app.encryption.secret-key} not matching the value the row was written with.
     */
    public static final String STATUS_DECRYPT_FAILED = "DECRYPT_FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The XamOps customer org this mapping belongs to.
     *
     * Client-scoped rather than user-scoped, following {@code ObservabilityConfig}. Most
     * integration configs in this codebase hang off {@code user_id} ({@code JenkinsIntegrationConfig}
     * and friends), which means the connection dies with whoever created it. A DSPM subscription
     * belongs to the customer, not to an individual, and an operator — not a customer user —
     * creates it, so there is often no sensible user to attach it to in the first place.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    @JsonIgnore
    private Client client;

    /**
     * The Matters account the project lives under.
     *
     * The same value for every row today, since Xammer has one account. Recorded per row anyway
     * so a second account (a reseller arrangement, a separate region's tenant) does not require a
     * schema change, and so the account a key was issued against is visible without decrypting it.
     */
    @Column(name = "matters_account_id", length = 128)
    private String mattersAccountId;

    /**
     * The Matters project id this client's data comes from — the mapping this whole table exists
     * to hold.
     *
     * Unique. Not for tidiness: see the class javadoc. The API key already scopes reads to one
     * project, so two clients sharing a project id means two clients reading the same data.
     *
     * Informational for reads (the key does the scoping) but load-bearing for operators: it is how
     * a mapping is reconciled against the Matters console, and how a mis-pasted key is caught by
     * {@code verify}, which compares this against the project the key actually resolves to.
     */
    @Column(name = "matters_project_id", length = 128, nullable = false)
    private String mattersProjectId;

    /** Human-readable project name, shown in the admin list so operators are not matching ids by eye. */
    @Column(name = "matters_project_name", length = 256)
    private String mattersProjectName;

    /**
     * Tenant base URL, e.g. {@code https://app.ap-south-1.matters.ai}.
     *
     * Per-row rather than a single global property because Matters is regional and the key is bound
     * to one environment: a key issued for one region is rejected against another with an explicit
     * "API key is for a different environment". Storing the host next to the key it belongs with
     * means a customer provisioned in a different region is a data change, not a deploy.
     */
    @Column(name = "base_url", length = 256, nullable = false)
    private String baseUrl;

    /**
     * The {@code X-API-Key} secret, in Matters' {@code mts_<env>_<key_id>_<secret>} form.
     *
     * Encrypted at rest by {@link EncryptedStringConverter} (AES-256/GCM, {@code enc:v1:} prefix).
     * {@code TEXT} rather than {@code @Lob} deliberately — {@code @Lob} on Postgres produces the
     * "Unable to access lob stream" failures this codebase has already had to unpick elsewhere.
     *
     * {@code @JsonIgnore} so it cannot escape through an admin DTO that happens to serialise the
     * entity; the admin API exposes {@link #apiKeyLastFour} instead. Matters shows a key once at
     * creation and stores only a hash, so a lost key is rotated, never recovered — this column is
     * the only copy Xammer has.
     */
    @Column(name = "api_key", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    @JsonIgnore
    private String apiKey;

    /**
     * Environment segment parsed out of the key at save time.
     *
     * Kept separate so the admin UI can show which environment a mapping points at, and so a
     * key/base-URL mismatch can be flagged before it becomes a 401, without decrypting the secret.
     */
    @Column(name = "api_key_env", length = 32)
    private String apiKeyEnv;

    /** Key id segment. Identifies the key in Matters' own console for rotation and revocation. */
    @Column(name = "api_key_id", length = 128)
    private String apiKeyId;

    /**
     * Last four characters of the secret, for "is this the key I think it is" without decryption.
     *
     * <p>It also does a second, less obvious job: it is the <b>presence sentinel</b> for the
     * encrypted column. {@code EncryptionService.decrypt} returns null on failure rather than
     * throwing, and {@link EncryptedStringConverter} passes that null straight through, so a
     * key written under a different {@code app.encryption.secret-key} arrives here as
     * {@code apiKey == null} — indistinguishable from a mapping where no key was ever stored.
     *
     * <p>Those two cases need opposite handling. "No key yet" is the normal gap between granting
     * the add-on and provisioning the project, and the customer should see "being set up". "Key
     * will not decrypt" is a deployment fault that needs an operator, and showing it as "being set
     * up" would hide it indefinitely.
     *
     * <p>This column is stored in the clear, so it survives a key mismatch: set means a credential
     * was written at some point, and null means one never was. See {@link #isDecryptionFailed()}.
     */
    @Column(name = "api_key_last_four", length = 8)
    private String apiKeyLastFour;

    /**
     * When the key stops working, if it was issued with an expiry.
     *
     * Matters caps key lifetime at 180 days, so every key here is on a clock and an unattended
     * mapping will eventually 401. Recorded so the add-on can warn an operator ahead of time
     * instead of surfacing as a customer-reported outage. Null means no expiry was set.
     */
    @Column(name = "api_key_expires_at")
    private Instant apiKeyExpiresAt;

    /**
     * Whether this mapping should be used.
     *
     * Distinct from {@code Client.mattersDspmEnabled}: that is the commercial grant, this is the
     * operational switch. Disabling here lets an operator park a broken or rotating connection
     * without touching the customer's entitlement.
     */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Result of the last verification call — one of the {@code STATUS_*} constants. */
    @Column(name = "connection_status", length = 32, nullable = false)
    private String connectionStatus = STATUS_UNVERIFIED;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    /**
     * Why the last call failed, kept so an operator can tell an expired key from an unreachable
     * tenant from a revoked scope without reading server logs.
     */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    /** Username of the operator who last wrote this row. Audit trail for a credential change. */
    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** True when this mapping can actually be used for a read. */
    @Transient
    public boolean isUsable() {
        return enabled && apiKey != null && !apiKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * True when a credential was stored at some point, whether or not it can be read back now.
     *
     * Derived from {@link #apiKeyLastFour} rather than {@link #apiKey}, because the latter reads as
     * null both when nothing was stored and when what was stored will not decrypt.
     */
    @Transient
    public boolean isCredentialStored() {
        return apiKeyLastFour != null && !apiKeyLastFour.isBlank();
    }

    /**
     * True when a credential is on record but came back empty — i.e. decryption failed.
     *
     * This is the case that must never be reported to a customer as "not set up yet": the mapping is
     * complete, and the fault is on the XamOps side.
     */
    @Transient
    public boolean isDecryptionFailed() {
        return isCredentialStored() && (apiKey == null || apiKey.isBlank());
    }

    /**
     * True when the key has a recorded expiry that has already passed.
     *
     * Checked before calling out so an expired key reports as expired rather than as a generic
     * 401, which is the difference between "rotate the key" and "something is broken".
     */
    @Transient
    public boolean isKeyExpired() {
        return apiKeyExpiresAt != null && Instant.now().isAfter(apiKeyExpiresAt);
    }
}
