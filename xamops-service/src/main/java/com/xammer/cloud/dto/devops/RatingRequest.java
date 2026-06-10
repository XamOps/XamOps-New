package com.xammer.cloud.dto.devops;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * Request payload for rating a script. The rating must be an integer between
 * 1 and 5 (inclusive); the optional review is capped at 1000 characters.
 *
 * Requirements: REQ-2.4.1 (Ratings &amp; Reviews), REQ-2.8.2 (Input Validation)
 */
public class RatingRequest {

    @NotNull(message = "rating is required")
    @Min(value = 1, message = "rating must be at least 1")
    @Max(value = 5, message = "rating must be at most 5")
    private Integer rating;

    @Size(max = 1000, message = "review must not exceed 1000 characters")
    private String review;

    public RatingRequest() {}

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public String getReview() { return review; }
    public void setReview(String review) { this.review = review; }
}
