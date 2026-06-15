package com.xammer.cloud.dto.devops;

import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates a full-text search request against the DevOps script index,
 * combining a free-text query with structured filters, sorting and pagination.
 *
 * Requirements: REQ-2.3.1 (Full-Text Search), REQ-2.3.2 (Advanced Filtering)
 */
public class SearchQuery {

    /** Free-text query string. May be {@code null} or blank to match everything. */
    private String text;

    private List<String> categories = new ArrayList<>();
    private List<String> cloudProviders = new ArrayList<>();
    private List<String> difficulties = new ArrayList<>();
    private List<String> tags = new ArrayList<>();

    /** Minimum average rating (0.0 - 5.0). {@code null} means no rating filter. */
    private Double minRating;

    /** Only scripts with this status are returned. Defaults to {@code published}. */
    private String status = "published";

    /** Sort key: recent, popular, rating, name. Defaults to relevance/recent. */
    private String sortBy = "recent";

    private int page = 0;
    private int size = 20;

    public SearchQuery() {}

    public SearchQuery(String text) {
        this.text = text;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public List<String> getCategories() { return categories; }
    public void setCategories(List<String> categories) {
        this.categories = categories != null ? categories : new ArrayList<>();
    }

    public List<String> getCloudProviders() { return cloudProviders; }
    public void setCloudProviders(List<String> cloudProviders) {
        this.cloudProviders = cloudProviders != null ? cloudProviders : new ArrayList<>();
    }

    public List<String> getDifficulties() { return difficulties; }
    public void setDifficulties(List<String> difficulties) {
        this.difficulties = difficulties != null ? difficulties : new ArrayList<>();
    }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public Double getMinRating() { return minRating; }
    public void setMinRating(Double minRating) { this.minRating = minRating; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSortBy() { return sortBy; }
    public void setSortBy(String sortBy) { this.sortBy = sortBy; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = Math.max(0, page); }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size > 0 ? size : 20; }

    /** @return true when no free-text query is supplied. */
    public boolean isEmptyText() {
        return text == null || text.trim().isEmpty();
    }
}
