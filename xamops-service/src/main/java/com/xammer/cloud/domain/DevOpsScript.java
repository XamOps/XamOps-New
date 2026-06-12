package com.xammer.cloud.domain;

import javax.persistence.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "devops_script")
@EntityListeners(AuditingEntityListener.class)
public class DevOpsScript {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 50)
    private String category; // terraform, bash, python, ansible

    // NOTE: Do NOT use @Lob here. On PostgreSQL, @Lob on a String maps the
    // column to a large-object OID and reads it via getClob()/getLong(),
    // which fails with "Bad value for type long" against a plain TEXT column.
    // columnDefinition = "TEXT" already provides unbounded text storage.
    @Column(columnDefinition = "TEXT", nullable = false)
    private String code;

    @Column(columnDefinition = "TEXT")
    @JsonProperty("usage")
    private String usageInstructions;

    @Column(nullable = false, length = 20)
    private String version;

    // Enhanced fields for improvements
    @ElementCollection
    @CollectionTable(name = "script_tags", joinColumns = @JoinColumn(name = "script_id"))
    @Column(name = "tag", length = 50)
    private List<String> tags = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "script_cloud_providers", joinColumns = @JoinColumn(name = "script_id"))
    @Column(name = "provider", length = 20)
    private List<String> cloudProviders = new ArrayList<>();

    @Column(length = 50)
    private String difficulty; // beginner, intermediate, advanced

    @Column(length = 100)
    private String author;

    @Column(name = "author_id")
    private String authorId;

    @Column(name = "estimated_time_minutes")
    private Integer estimatedTimeMinutes;

    @Column(columnDefinition = "TEXT")
    private String prerequisites;

    @Column(name = "related_documentation_url", columnDefinition = "TEXT")
    private String relatedDocumentationUrl;

    // Metrics
    @Column(name = "view_count")
    private Integer viewCount = 0;

    @Column(name = "download_count")
    private Integer downloadCount = 0;

    @Column(name = "copy_count")
    private Integer copyCount = 0;

    @Column(name = "favorite_count")
    private Integer favoriteCount = 0;

    @Column(name = "average_rating")
    private Double averageRating = 0.0;

    @Column(name = "rating_count")
    private Integer ratingCount = 0;

    // Timestamps
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    // Status
    @Column(length = 20)
    private String status = "published"; // published, draft, deprecated

    @Column(name = "is_user_contributed")
    private Boolean isUserContributed = false;

    @Column(name = "is_featured")
    private Boolean isFeatured = false;

    // Legacy field for backward compatibility
    @Column(name = "last_updated")
    private String lastUpdated;

    @Column(name = "downloads")
    private Integer downloads = 0;

    // Constructors
    public DevOpsScript() {}

    // Legacy constructor for backward compatibility
    public DevOpsScript(String name, String description, String category, String code, String usageInstructions, String version, int downloads, String lastUpdated) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.code = code;
        this.usageInstructions = usageInstructions;
        this.version = version;
        this.downloads = downloads;
        this.downloadCount = downloads;
        this.lastUpdated = lastUpdated;
        this.status = "published";
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

    public String getRelatedDocumentationUrl() { return relatedDocumentationUrl; }
    public void setRelatedDocumentationUrl(String relatedDocumentationUrl) { this.relatedDocumentationUrl = relatedDocumentationUrl; }

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

    // Legacy getters/setters for backward compatibility
    public String getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }

    public int getDownloads() { return downloads != null ? downloads : 0; }
    public void setDownloads(int downloads) {
        this.downloads = downloads;
        this.downloadCount = downloads;
    }
}