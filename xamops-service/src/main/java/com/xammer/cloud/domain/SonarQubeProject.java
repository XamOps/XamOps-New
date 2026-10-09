package com.xammer.cloud.domain;

import com.xammer.cloud.domain.type.LegacyLobTextType;
import com.xammer.cloud.domain.type.OidShapeGuard;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.Type;

/**
 * Entity to store a SonarQube project configuration, linked to a User.
 */
@Entity
@Table(name = "sonarqube_project")
@Data
@NoArgsConstructor
@DynamicUpdate
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
    @Type(value = LegacyLobTextType.class, parameters = @Parameter(name = LegacyLobTextType.KEY_COLUMN, value = "id"))
    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedToken; // Encrypted SonarQube API token

    public SonarQubeProject(User user, String projectName, String serverUrl, String projectKey, String encryptedToken) {
        this.user = user;
        this.projectName = projectName;
        this.serverUrl = serverUrl;
        this.projectKey = projectKey;
        this.encryptedToken = encryptedToken;
    }

    // Refuses an OID-shaped inline value in both write modes (OidShapeGuard).
    @PrePersist
    @PreUpdate
    private void rejectOidShapedEncryptedToken() {
        OidShapeGuard.checkEntityWrite("sonarqube_project", id, encryptedToken);
    }
}