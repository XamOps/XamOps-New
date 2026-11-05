package com.xammer.cloud.domain.envallocation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Who did what to a client's environment allocation, and when. Append-only. */
@Entity
@Table(name = "env_allocation_audit",
        indexes = @Index(name = "idx_env_allocation_audit_client", columnList = "client_id, created_at"))
@Getter
@Setter
@NoArgsConstructor
public class EnvAllocationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "actor")
    private String actor;

    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "entity_type", length = 40)
    private String entityType;

    @Column(name = "entity_ref", length = 100)
    private String entityRef;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
