package com.xammer.cloud.dto.cicd;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty; // <-- *** THIS IS THE MISSING IMPORT ***

/**
 * Represents a single GitHub Actions workflow (the .yml file definition).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubWorkflowDto {

    private Long id;
    private String name;
    
    @JsonProperty("path") // The path to the .yml file
    private String path;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}