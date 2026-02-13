package com.xammer.cloud.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "linked_accounts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LinkedAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "account_id", nullable = false)
    private String accountId; // Cloud provider account identifier (e.g., AWS account ID)

    @Column(name = "account_name")
    private String accountName; // User-defined name for the account

    @Column(name = "provider")
    private String provider; // Cloud provider type (e.g., AWS)

    @Column(name = "status")
    private String status; // Account status (e.g., active)

    @Column(name = "linked_at")
    private Instant linkedAt; // When the account was linked in CloudiDR

    @Column(name = "synced_at")
    private Instant syncedAt; // When we last synced this data

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        syncedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
