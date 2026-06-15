package com.xammer.cloud.dto.devops;

import java.util.ArrayList;
import java.util.List;

/**
 * Filter criteria used when listing or searching DevOps scripts.
 *
 * <p>All list fields default to empty (meaning "no constraint"). Multiple
 * dimensions are combined with AND logic; values within a single dimension are
 * combined with OR logic.
 *
 * Requirements: REQ-2.3.2 (Advanced Filtering), REQ-2.3.3 (Sorting), REQ-2.4.2 (Favorites)
 */
public class ScriptFilters {

    /** Free-text query (matched against name, description and tags). */
    private String searchQuery;

    /** Categories to include (terraform, bash, python, ansible). */
    private List<String> categories = new ArrayList<>();

    /** Cloud providers to include (aws, azure, gcp, multi-cloud). */
    private List<String> cloudProviders = new ArrayList<>();

    /** Difficulty levels to include (beginner, intermediate, advanced). */
    private List<String> difficulties = new ArrayList<>();

    /** Tags to include. */
    private List<String> tags = new ArrayList<>();

    /** Minimum average rating a script must have to be included. */
    private Double minRating;

    /** Sort order: recent, popular, rating, name. Defaults to "recent". */
    private String sortBy = "recent";

    /** When true, restrict results to the current user's favorites. */
    private boolean showOnlyFavorites = false;

    public ScriptFilters() {}

    public String getSearchQuery() { return searchQuery; }
    public void setSearchQuery(String searchQuery) { this.searchQuery = searchQuery; }

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

    public String getSortBy() { return sortBy; }
    public void setSortBy(String sortBy) { this.sortBy = sortBy; }

    public boolean isShowOnlyFavorites() { return showOnlyFavorites; }
    public void setShowOnlyFavorites(boolean showOnlyFavorites) {
        this.showOnlyFavorites = showOnlyFavorites;
    }
}
