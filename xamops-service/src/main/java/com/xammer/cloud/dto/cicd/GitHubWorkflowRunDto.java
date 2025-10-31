package com.xammer.cloud.dto.cicd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * DTO representing a GitHub Actions workflow run.
 * This maps to the data returned by the GitHub API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubWorkflowRunDto {
    
    private Long id;
    
    private String name; // Workflow name
    
    @JsonProperty("html_url")
    private String htmlUrl; // Link to the run page
    
    private String status; // e.g., "completed", "in_progress"
    
    private String conclusion; // e.g., "success", "failure" (if status is "completed")
    
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
    
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;
    
    private RepositoryInfo repository; // Nested object for repo name
    
    // ✅ ADD @JsonProperty to ensure it's serialized to frontend
    @JsonProperty("workflow_id")
    private Long workflowId;
    
    // ✅ ADD run_number field from GitHub API
    @JsonProperty("run_number")
    private Integer runNumber;
    
    private String owner;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepositoryInfo {
        
        @JsonProperty("name")
        private String name;
        
        @JsonProperty("full_name")
        private String fullName;
        
        @JsonProperty("owner")
        private OwnerInfo owner;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class OwnerInfo {
            @JsonProperty("login")
            private String login;

            public String getLogin() {
                return login;
            }

            public void setLogin(String login) {
                this.login = login;
            }
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        
        public OwnerInfo getOwner() {
            return owner;
        }
        
        public void setOwner(OwnerInfo owner) {
            this.owner = owner;
        }
    }

    // --- Getters and Setters ---
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getHtmlUrl() { return htmlUrl; }
    public void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getConclusion() { return conclusion; }
    public void setConclusion(String conclusion) { this.conclusion = conclusion; }
    
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    
    public RepositoryInfo getRepository() { return repository; }
    public void setRepository(RepositoryInfo repository) { this.repository = repository; }
    
    // ✅ Ensure these are included
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }
    
    public Integer getRunNumber() { return runNumber; }
    public void setRunNumber(Integer runNumber) { this.runNumber = runNumber; }
    
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    // Display status helper
    public String getDisplayStatus() {
        if ("completed".equalsIgnoreCase(status)) {
            return conclusion != null ? conclusion.toLowerCase() : "completed";
        }
        return status != null ? status.toLowerCase() : "unknown";
    }
}
