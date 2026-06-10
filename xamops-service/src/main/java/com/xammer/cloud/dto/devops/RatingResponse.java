package com.xammer.cloud.dto.devops;

import com.xammer.cloud.domain.ScriptRating;

import java.time.LocalDateTime;

/**
 * Response view of a {@link ScriptRating}. Flattens the rating into a safe,
 * serialization-friendly shape (no lazy JPA associations).
 *
 * Requirements: REQ-2.4.1 (Ratings &amp; Reviews)
 */
public class RatingResponse {

    private Long id;
    private Long scriptId;
    private String userId;
    private Integer rating;
    private String review;
    private Boolean verified;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public RatingResponse() {}

    /** Build a response DTO from a persisted rating entity. */
    public static RatingResponse from(ScriptRating entity) {
        RatingResponse r = new RatingResponse();
        r.id = entity.getId();
        r.scriptId = entity.getScript() != null ? entity.getScript().getId() : null;
        r.userId = entity.getUserId();
        r.rating = entity.getRating();
        r.review = entity.getReview();
        r.verified = entity.getIsVerified();
        r.createdAt = entity.getCreatedAt();
        r.updatedAt = entity.getUpdatedAt();
        return r;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public String getReview() { return review; }
    public void setReview(String review) { this.review = review; }

    public Boolean getVerified() { return verified; }
    public void setVerified(Boolean verified) { this.verified = verified; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
