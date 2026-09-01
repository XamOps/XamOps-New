package com.xammer.cloud.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity to store a SonarQube project configuration, linked to a User.
 */
@Entity
@Table(name = "sonarqube_project")
@Data
@NoArgsConstructor
public class SonarQubeProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String projectName; // User-friendly display name

    @Column(nullable = false)
    private String serverUrl; // e.g., "https://sonarcloud.io"

    @Column(nullable = false)
    private String projectKey; // e.g., "my-org_my-repo"

    // No @Lob: the column is TEXT, and on PostgreSQL @Lob makes Hibernate treat it as a
    // large object -- read through a LOB stream, which fails outside a transaction with
    // "Unable to access lob stream" / "Large Objects may not be used in auto-commit mode".
    // Hibernate 5 tolerated the mismatch; Hibernate 7 does not. columnDefinition = "TEXT"
    // already gives unbounded storage. Same fix as ScriptVersion/DevOpsScript/JenkinsIntegrationConfig.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedToken; // Encrypted SonarQube API token

    public SonarQubeProject(User user, String projectName, String serverUrl, String projectKey, String encryptedToken) {
        this.user = user;
        this.projectName = projectName;
        this.serverUrl = serverUrl;
        this.projectKey = projectKey;
        this.encryptedToken = encryptedToken;
    }
}