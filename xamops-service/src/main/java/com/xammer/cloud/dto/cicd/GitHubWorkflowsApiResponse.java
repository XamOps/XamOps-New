package com.xammer.cloud.dto.cicd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wrapper class for the GitHub API response for listing workflows,
 * which returns {"total_count": ..., "workflows": [...]}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubWorkflowsApiResponse {

    @JsonProperty("workflows")
    private List<GitHubWorkflowDto> workflows;

    // Getter and Setter
    public List<GitHubWorkflowDto> getWorkflows() { return workflows; }
    public void setWorkflows(List<GitHubWorkflowDto> workflows) { this.workflows = workflows; }
}