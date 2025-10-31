package com.xammer.cloud.domain;

import javax.persistence.*; // Use javax.persistence for Spring Boot 2.x

@Entity
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

    @Lob // Use Lob for potentially long strings like encrypted tokens
    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedPat; // Store the encrypted Personal Access Token

    // --- Constructors ---
    public GitHubIntegrationConfig() {}

    public GitHubIntegrationConfig(User user, String githubOwner, String githubRepo, String encryptedPat) {
        this.user = user;
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
        this.encryptedPat = encryptedPat;
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

    // Optional: toString, equals, hashCode methods
}