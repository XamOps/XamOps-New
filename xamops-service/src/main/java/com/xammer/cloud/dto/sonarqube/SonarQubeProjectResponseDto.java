package com.xammer.cloud.dto.sonarqube;

import com.xammer.cloud.domain.SonarQubeProject; // We will create this next
import lombok.Data;

/**
 * DTO for safely displaying a SonarQube project configuration (token-free).
 */
@Data
public class SonarQubeProjectResponseDto {
    private Long id;
    private String projectName;
    private String serverUrl;
    private String projectKey;

    /**
     * Factory method to convert an entity to this DTO.
     */
    public static SonarQubeProjectResponseDto fromEntity(SonarQubeProject entity) {
        SonarQubeProjectResponseDto dto = new SonarQubeProjectResponseDto();
        dto.setId(entity.getId());
        dto.setProjectName(entity.getProjectName());
        dto.setServerUrl(entity.getServerUrl());
        dto.setProjectKey(entity.getProjectKey());
        return dto;
    }
}