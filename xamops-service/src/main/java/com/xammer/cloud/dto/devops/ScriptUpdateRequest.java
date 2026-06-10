package com.xammer.cloud.dto.devops;

import javax.validation.constraints.Size;
import java.util.List;

/**
 * Request payload for updating an existing DevOps script. All fields are
 * optional; only non-null values are applied to the target script, allowing
 * partial updates.
 *
 * Requirements: REQ-2.1.1 (Content Volume), REQ-2.1.3 (Categorization),
 *               REQ-2.7.1 (Versioning), REQ-2.7.2 (Script Status)
 */
public class ScriptUpdateRequest {

    @Size(max = 200)
    private String name;

    private String description;

    @Size(max = 50)
    private String category;

    private String code;

    private String usageInstructions;

    private String version;

    private List<String> tags;

    private List<String> cloudProviders;

    private String difficulty;

    private String author;

    private String authorId;

    private Integer estimatedTimeMinutes;

    private String prerequisites;

    private String relatedDocumentationUrl;

    private String status;

    private Boolean isFeatured;

    public ScriptUpdateRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getUsageInstructions() { return usageInstructions; }
    public void setUsageInstructions(String usageInstructions) { this.usageInstructions = usageInstructions; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public List<String> getCloudProviders() { return cloudProviders; }
    public void setCloudProviders(List<String> cloudProviders) { this.cloudProviders = cloudProviders; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }

    public Integer getEstimatedTimeMinutes() { return estimatedTimeMinutes; }
    public void setEstimatedTimeMinutes(Integer estimatedTimeMinutes) { this.estimatedTimeMinutes = estimatedTimeMinutes; }

    public String getPrerequisites() { return prerequisites; }
    public void setPrerequisites(String prerequisites) { this.prerequisites = prerequisites; }

    public String getRelatedDocumentationUrl() { return relatedDocumentationUrl; }
    public void setRelatedDocumentationUrl(String relatedDocumentationUrl) { this.relatedDocumentationUrl = relatedDocumentationUrl; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Boolean getIsFeatured() { return isFeatured; }
    public void setIsFeatured(Boolean isFeatured) { this.isFeatured = isFeatured; }
}
