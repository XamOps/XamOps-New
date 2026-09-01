package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * An Upwind API-security endpoint, synced locally so the APIs tab reads from the
 * database instead of walking every Upwind page on request.
 *
 * Endpoint payloads contain no cloud account id — it comes from the cloud-account-id
 * request parameter — so {@link #cloudAccountId} is stamped by the sync.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "upwind_api_endpoint")
public class UpwindApiEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upwind_id", nullable = false, unique = true)
    private String upwindId;

    @Column(name = "cloud_account_id", nullable = false)
    private String cloudAccountId;

    private String method;

    @Column(columnDefinition = "TEXT")
    private String uri;

    @Column(name = "resource_id")
    private String resourceId;

    /** True when Upwind has observed internet ingress for the endpoint. */
    @Column(name = "internet_exposed", nullable = false)
    private boolean internetExposed;

    /** AUTHENTICATED / UNAUTHENTICATED, or null when Upwind hasn't classified it. */
    @Column(name = "auth_state")
    private String authState;

    /** Comma-separated domains; the read path splits this back into a list. */
    @Column(columnDefinition = "TEXT")
    private String domains;

    @Column(name = "first_seen_time")
    private Instant firstSeenTime;

    @Column(name = "last_seen_time")
    private Instant lastSeenTime;

    /**
     * risk_overview.sensitive_data[] flattened to "CATEGORY:Type" entries (e.g. "PII:Email"),
     * comma-separated. Only the v2 /api-security/endpoints/search payload carries this; the
     * v1 endpoint list did not, which is why the APIs tab had no data-classification panel.
     */
    @Column(name = "sensitive_data", length = 1024)
    private String sensitiveData;

    @Column(name = "has_sensitive_data")
    private Boolean hasSensitiveData;

    /** Observed HTTP status codes, comma-separated (e.g. "200,401,502"). */
    @Column(name = "status_codes", length = 256)
    private String statusCodes;

    /** ACTIVE / RESOLVED (absent from latest complete sync) */
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;
}
