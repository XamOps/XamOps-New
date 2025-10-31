package com.xammer.cloud.dto.cicd;

import com.xammer.cloud.domain.GitHubIntegrationConfig;

public class GitHubConfigResponseDto {
    private Long id;
    private String owner;
    private String repo;

    // Static factory method for conversion
    public static GitHubConfigResponseDto fromEntity(GitHubIntegrationConfig entity) {
        GitHubConfigResponseDto dto = new GitHubConfigResponseDto();
        dto.setId(entity.getId());
        dto.setOwner(entity.getGithubOwner());
        dto.setRepo(entity.getGithubRepo());
        return dto;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }
}