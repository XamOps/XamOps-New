package com.xammer.cloud.domain.elasticsearch;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Elasticsearch document model for DevOps scripts.
 * Optimized for full-text search with custom analyzers for better search relevance.
 * 
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.6.1 (Performance)
 */
@Document(indexName = "devops_scripts")
@Setting(settingPath = "elasticsearch/script-index-settings.json")
public class DevOpsScriptDocument {

    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "script_analyzer")
    private String name;

    @Field(type = FieldType.Text, analyzer = "script_analyzer")
    private String description;

    @Field(type = FieldType.Keyword)
    private String category; // terraform, bash, python, ansible

    @Field(type = FieldType.Text, analyzer = "code_analyzer")
    private String code;

    @Field(type = FieldType.Text)
    private String usageInstructions;

    @Field(type = FieldType.Keyword)
    private String version;

    @Field(type = FieldType.Keyword)
    private List<String> tags;

    @Field(type = FieldType.Keyword)
    private List<String> cloudProviders; // aws, azure, gcp, multi-cloud

    @Field(type = FieldType.Keyword)
    private String difficulty; // beginner, intermediate, advanced

    @Field(type = FieldType.Text)
    private String author;

    @Field(type = FieldType.Keyword)
    private String authorId;

    @Field(type = FieldType.Integer)
    private Integer estimatedTimeMinutes;

    @Field(type = FieldType.Text)
    private String prerequisites;

    // Metrics for ranking and sorting
    @Field(type = FieldType.Integer)
    private Integer viewCount;

    @Field(type = FieldType.Integer)
    private Integer downloadCount;

    @Field(type = FieldType.Integer)
    private Integer copyCount;

    @Field(type = FieldType.Integer)
    private Integer favoriteCount;

    @Field(type = FieldType.Double)
    private Double averageRating;

    @Field(type = FieldType.Integer)
    private Integer ratingCount;

    @Field(type = FieldType.Date)
    private LocalDateTime createdAt;

    @Field(type = FieldType.Date)
    private LocalDateTime updatedAt;

    @Field(type = FieldType.Date)
    private LocalDateTime lastUsedAt;

    @Field(type = FieldType.Keyword)
    private String status; // published, draft, deprecated

    @Field(type = FieldType.Boolean)
    private Boolean isUserContributed;

    @Field(type = FieldType.Boolean)
    private Boolean isFeatured;

    // Constructors
    public DevOpsScriptDocument() {}

    public DevOpsScriptDocument(Long id, String name, String description, String category) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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

    public Integer getViewCount() { return viewCount; }
    public void setViewCount(Integer viewCount) { this.viewCount = viewCount; }

    public Integer getDownloadCount() { return downloadCount; }
    public void setDownloadCount(Integer downloadCount) { this.downloadCount = downloadCount; }

    public Integer getCopyCount() { return copyCount; }
    public void setCopyCount(Integer copyCount) { this.copyCount = copyCount; }

    public Integer getFavoriteCount() { return favoriteCount; }
    public void setFavoriteCount(Integer favoriteCount) { this.favoriteCount = favoriteCount; }

    public Double getAverageRating() { return averageRating; }
    public void setAverageRating(Double averageRating) { this.averageRating = averageRating; }

    public Integer getRatingCount() { return ratingCount; }
    public void setRatingCount(Integer ratingCount) { this.ratingCount = ratingCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Boolean getIsUserContributed() { return isUserContributed; }
    public void setIsUserContributed(Boolean isUserContributed) { this.isUserContributed = isUserContributed; }

    public Boolean getIsFeatured() { return isFeatured; }
    public void setIsFeatured(Boolean isFeatured) { this.isFeatured = isFeatured; }
}
