package com.xammer.cloud.domain;

import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.domain.type.OidShapeGuard;
import jakarta.persistence.*; // Use jakarta.persistence for Spring Boot 2.x
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;
import java.time.Instant;

@Entity
@DynamicUpdate
@Table(name = "github_integration_config") // Optional: Define table name explicitly
public class GitHubIntegrationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) // Link to the User entity
    @JoinColumn(name = "user_id", nullable = false) // Foreign key column
    private User user; // Assumes you want to link per-user

    @Column(nullable = false)
    private String githubOwner; // e.g., "my-org" or "my-username"

    @Column(nullable = false)
    private String githubRepo; // e.g., "my-cool-project"

    // No @Lob: the column is TEXT, and on PostgreSQL @Lob makes Hibernate treat it as a
    // large object -- read through a LOB stream, which fails outside a transaction with
    // "Unable to access lob stream" / "Large Objects may not be used in auto-commit mode".
    // Hibernate 5 tolerated the mismatch; Hibernate 7 does not. columnDefinition = "TEXT"
    // already gives unbounded storage. Same fix as ScriptVersion/DevOpsScript/JenkinsIntegrationConfig.
    @Type(value = LegacyLobTextType.class, parameters = @Parameter(name = LegacyLobTextType.KEY_COLUMN, value = "id"))
    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedPat; // Store the encrypted Personal Access Token

    @Column
    private Instant lastSyncedAt;

    // --- Constructors ---
    public GitHubIntegrationConfig() {}

    public GitHubIntegrationConfig(User user, String githubOwner, String githubRepo, String encryptedPat) {
        this.user = user;
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
        this.encryptedPat = encryptedPat;
    }

    // Refuses an OID-shaped inline value in both write modes (OidShapeGuard).
    @PrePersist
    @PreUpdate
    private void rejectOidShapedEncryptedPat() {
        OidShapeGuard.checkEntityWrite("github_integration_config", id, encryptedPat);
    }

    // --- Getters and Setters ---
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getGithubOwner() {
        return githubOwner;
    }

    public void setGithubOwner(String githubOwner) {
        this.githubOwner = githubOwner;
    }

    public String getGithubRepo() {
        return githubRepo;
    }

    public void setGithubRepo(String githubRepo) {
        this.githubRepo = githubRepo;
    }

    public String getEncryptedPat() {
        return encryptedPat;
    }

    public void setEncryptedPat(String encryptedPat) {
        this.encryptedPat = encryptedPat;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    // Optional: toString, equals, hashCode methods
}