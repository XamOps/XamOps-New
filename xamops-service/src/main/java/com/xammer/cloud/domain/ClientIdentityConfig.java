package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores the per-tenant Authentik service account configuration.
 * One row per client. The API token is AES-256-GCM encrypted at rest;
 * it is decrypted in-memory only when making Authentik API calls.
 *
 * Authentik reference: https://docs.goauthentik.io/developer-docs/api/
 * Token scope: limited to resources prefixed by group_prefix (soft isolation).
 * Backend enforces prefix filtering on every API response (hard isolation).
 */
@Entity
@Table(name = "client_identity_config")
@Data
@NoArgsConstructor
public class ClientIdentityConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false, unique = true)
    private Client client;

    /**
     * AES-256-GCM encrypted Authentik API token.
     * Stored as Base64(iv[12] || ciphertext || tag[16]).
     * Never logged or returned to the frontend.
     */
    @Column(name = "authentik_token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String authentikTokenEncrypted;

    /**
     * Prefix applied to all Authentik groups for this tenant, e.g. "Acme-".
     * Every API response from Authentik is filtered server-side to only include
     * groups/users matching this prefix.
     */
    @Column(name = "group_prefix", nullable = false, length = 100)
    private String groupPrefix;

    /**
     * Authentik pk (integer) of the service account user created for this tenant
     * during provisioning. Stored as string to handle Authentik's pk type.
     */
    @Column(name = "service_account_pk", length = 255)
    private String serviceAccountPk;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
