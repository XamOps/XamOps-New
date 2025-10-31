package com.xammer.cloud.dto.cicd;

// Add validation annotations if needed (e.g., @NotBlank)
// import javax.validation.constraints.NotBlank;

public class GitHubConfigRequestDto {

    // @NotBlank
    private String owner; // GitHub owner/org

    // @NotBlank
    private String repo; // GitHub repository name

    // @NotBlank
    private String pat; // Personal Access Token (sent plaintext from frontend)

    // Getters and Setters
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getRepo() { return repo; }
    public void setRepo(String repo) { this.repo = repo; }
    public String getPat() { return pat; }
    public void setPat(String pat) { this.pat = pat; }
}