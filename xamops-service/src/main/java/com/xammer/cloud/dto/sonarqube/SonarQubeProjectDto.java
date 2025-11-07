package com.xammer.cloud.dto.sonarqube;

import lombok.Data;

/**
 * DTO for creating or updating a SonarQube project configuration.
 * Includes the plaintext token from the user.
 */
@Data
public class SonarQubeProjectDto {
    private Long id; // Used for updates, null for creation
    private String projectName; // User-friendly name, e.g., "XamOps Backend"
    private String serverUrl;   // e.g., "https://sonarcloud.io"
    private String projectKey;  // The project's unique key in SonarQube
    private String token;       // The plaintext SonarQube API token
}