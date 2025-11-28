package com.xammer.cloud.domain;

import javax.persistence.*;

@Entity
@Table(name = "jenkins_integration_config")
public class JenkinsIntegrationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String jenkinsUrl;

    @Column(nullable = false)
    private String username;

    // FIX: Removed @Lob to prevent "Unable to access lob stream" errors
    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedApiToken;

    public JenkinsIntegrationConfig() {
    }

    public JenkinsIntegrationConfig(User user, String jenkinsUrl, String username, String encryptedApiToken) {
        this.user = user;
        this.jenkinsUrl = jenkinsUrl;
        this.username = username;
        this.encryptedApiToken = encryptedApiToken;
    }

    // Getters and Setters
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

    public String getJenkinsUrl() {
        return jenkinsUrl;
    }

    public void setJenkinsUrl(String jenkinsUrl) {
        this.jenkinsUrl = jenkinsUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEncryptedApiToken() {
        return encryptedApiToken;
    }

    public void setEncryptedApiToken(String encryptedApiToken) {
        this.encryptedApiToken = encryptedApiToken;
    }
}