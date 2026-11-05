package com.xammer.cloud.xampulse.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps xp_agent_credential. Tenant key is cloudAccountId. */
@Entity
@Table(name = "xp_agent_credential")
@Getter
@Setter
@NoArgsConstructor
public class XpAgentCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "family_id")
    private UUID familyId;

    @Column(name = "refresh_hash")
    private String refreshHash;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

}
