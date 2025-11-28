package com.xammer.cloud.dto.cicd;

import com.xammer.cloud.domain.JenkinsIntegrationConfig;

public class JenkinsConfigResponseDto {
    private Long id;
    private String jenkinsUrl;
    private String username;

    public static JenkinsConfigResponseDto fromEntity(JenkinsIntegrationConfig config) {
        JenkinsConfigResponseDto dto = new JenkinsConfigResponseDto();
        dto.setId(config.getId());
        dto.setJenkinsUrl(config.getJenkinsUrl());
        dto.setUsername(config.getUsername());
        return dto;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
}