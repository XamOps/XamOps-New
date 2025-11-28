package com.xammer.cloud.dto.cicd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO representing a GitHub Actions workflow run or a Jenkins Build.
 * This maps to the data returned by the GitHub API and adapts Jenkins data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubWorkflowRunDto {

    private Long id;

    private String name; // Workflow name or Job name

    @JsonProperty("html_url")
    private String htmlUrl; // Link to the run page

    private String status; // e.g., "completed", "in_progress" (GitHub raw status)

    private String conclusion; // e.g., "success", "failure" (GitHub raw conclusion)

    // Added field to hold the explicit status set by JenkinsService
    private String displayStatus;

    @JsonProperty("created_at")
    private String createdAt; // Changed to String for flexibility

    @JsonProperty("updated_at")
    private String updatedAt; // Changed to String to fix compilation error

    private RepositoryInfo repository; // Nested object for repo name

    @JsonProperty("workflow_id")
    private Long workflowId;

    // Changed to Long to match the 'long' argument passed in service
    @JsonProperty("run_number")
    private Long runNumber;

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

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public OwnerInfo getOwner() {
            return owner;
        }

        public void setOwner(OwnerInfo owner) {
            this.owner = owner;
        }
    }

    // --- Getters and Setters ---

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public void setHtmlUrl(String htmlUrl) {
        this.htmlUrl = htmlUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public RepositoryInfo getRepository() {
        return repository;
    }

    public void setRepository(RepositoryInfo repository) {
        this.repository = repository;
    }

    public Long getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(Long workflowId) {
        this.workflowId = workflowId;
    }

    public Long getRunNumber() {
        return runNumber;
    }

    public void setRunNumber(Long runNumber) {
        this.runNumber = runNumber;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    // Explicit setter for Jenkins service
    public void setDisplayStatus(String displayStatus) {
        this.displayStatus = displayStatus;
    }

    // Intelligent getter: returns explicit status if set (Jenkins), otherwise
    // calculates from GitHub fields
    public String getDisplayStatus() {
        if (this.displayStatus != null) {
            return this.displayStatus;
        }
        if ("completed".equalsIgnoreCase(status)) {
            return conclusion != null ? conclusion.toLowerCase() : "completed";
        }
        return status != null ? status.toLowerCase() : "unknown";
    }
}