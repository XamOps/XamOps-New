package com.xammer.cloud.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "llm_key_group")
@Data
@NoArgsConstructor
public class LlmKeyGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(name = "group_key", nullable = false, unique = true, length = 64)
    private String groupKey;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "llm_key_group_provider",
        joinColumns = @JoinColumn(name = "group_id"),
        inverseJoinColumns = @JoinColumn(name = "provider_id")
    )
    private Set<LlmProviderConfig> providers = new HashSet<>();

    @Column(name = "load_balance_strategy", length = 30)
    private String loadBalanceStrategy = "ROUND_ROBIN";

    @OneToMany(mappedBy = "keyGroup", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<LlmProviderKey> keys = new ArrayList<>();

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
