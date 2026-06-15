package com.xammer.cloud.service.cache;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Cache key generation strategy for DevOps Scripts.
 * Provides consistent, hierarchical key naming for different cache types.
 * 
 * Key Format Strategy:
 * - Scripts: devops:script:{scriptId}
 * - Search: devops:search:{query}:{filters_hash}
 * - Lists: devops:list:{filters_hash}
 * - Recommendations: devops:rec:{userId}:{context}
 * - User favorites: devops:favorites:{userId}
 * - Trending: devops:trending:{timeRange}
 * 
 * This enables efficient cache invalidation and organized Redis namespace.
 */
@Component
public class DevOpsScriptCacheKeyGenerator {

    private static final String PREFIX = "devops";
    private static final String SEPARATOR = ":";

    /**
     * Generate cache key for individual script details.
     * Format: devops:script:{scriptId}
     * 
     * @param scriptId The script ID
     * @return Cache key for script details
     */
    public String generateScriptKey(Long scriptId) {
        return String.join(SEPARATOR, PREFIX, "script", String.valueOf(scriptId));
    }

    /**
     * Generate cache key for script search results.
     * Format: devops:search:{query}:{category}:{cloudProvider}:{difficulty}:{tags}:{sortBy}
     * 
     * @param query Search query string
     * @param category Script category (terraform, bash, python, ansible)
     * @param cloudProvider Cloud provider filter (aws, azure, gcp, multi-cloud)
     * @param difficulty Difficulty level (beginner, intermediate, advanced)
     * @param tags List of tags
     * @param sortBy Sort order (recent, popular, rating, name)
     * @return Cache key for search results
     */
    public String generateSearchKey(String query, String category, String cloudProvider, 
                                   String difficulty, List<String> tags, String sortBy) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(PREFIX).append(SEPARATOR).append("search").append(SEPARATOR);
        
        // Normalize query (trim, lowercase)
        String normalizedQuery = query != null ? query.trim().toLowerCase() : "all";
        keyBuilder.append(normalizedQuery).append(SEPARATOR);
        
        // Add filter components
        keyBuilder.append(category != null ? category : "all").append(SEPARATOR);
        keyBuilder.append(cloudProvider != null ? cloudProvider : "all").append(SEPARATOR);
        keyBuilder.append(difficulty != null ? difficulty : "all").append(SEPARATOR);
        
        // Add tags (sorted for consistency)
        if (tags != null && !tags.isEmpty()) {
            String tagString = tags.stream()
                .sorted()
                .collect(Collectors.joining(","));
            keyBuilder.append(tagString);
        } else {
            keyBuilder.append("notags");
        }
        keyBuilder.append(SEPARATOR);
        
        // Add sort order
        keyBuilder.append(sortBy != null ? sortBy : "recent");
        
        return keyBuilder.toString();
    }

    /**
     * Generate cache key for script list (filtered, no search query).
     * Format: devops:list:{category}:{cloudProvider}:{difficulty}:{minRating}:{sortBy}
     * 
     * @param category Script category
     * @param cloudProvider Cloud provider filter
     * @param difficulty Difficulty level
     * @param minRating Minimum rating filter
     * @param sortBy Sort order
     * @return Cache key for script list
     */
    public String generateListKey(String category, String cloudProvider, String difficulty, 
                                  Double minRating, String sortBy) {
        return String.join(SEPARATOR,
            PREFIX,
            "list",
            category != null ? category : "all",
            cloudProvider != null ? cloudProvider : "all",
            difficulty != null ? difficulty : "all",
            minRating != null ? String.valueOf(minRating) : "0.0",
            sortBy != null ? sortBy : "recent"
        );
    }

    /**
     * Generate cache key for user recommendations.
     * Format: devops:rec:{userId}:{limit}
     * 
     * @param userId User ID
     * @param limit Number of recommendations
     * @return Cache key for recommendations
     */
    public String generateRecommendationKey(String userId, int limit) {
        return String.join(SEPARATOR, PREFIX, "rec", userId, String.valueOf(limit));
    }

    /**
     * Generate cache key for related scripts.
     * Format: devops:related:{scriptId}:{limit}
     * 
     * @param scriptId The script ID
     * @param limit Number of related scripts
     * @return Cache key for related scripts
     */
    public String generateRelatedScriptsKey(Long scriptId, int limit) {
        return String.join(SEPARATOR, PREFIX, "related", String.valueOf(scriptId), String.valueOf(limit));
    }

    /**
     * Generate cache key for user favorites.
     * Format: devops:favorites:{userId}
     * 
     * @param userId User ID
     * @return Cache key for user favorites
     */
    public String generateFavoritesKey(String userId) {
        return String.join(SEPARATOR, PREFIX, "favorites", userId);
    }

    /**
     * Generate cache key for trending scripts.
     * Format: devops:trending:{timeRange}:{limit}
     * 
     * @param timeRange Time range (7d, 30d, etc.)
     * @param limit Number of trending scripts
     * @return Cache key for trending scripts
     */
    public String generateTrendingKey(String timeRange, int limit) {
        return String.join(SEPARATOR, PREFIX, "trending", timeRange, String.valueOf(limit));
    }

    /**
     * Generate cache key for popular scripts.
     * Format: devops:popular:{limit}
     * 
     * @param limit Number of popular scripts
     * @return Cache key for popular scripts
     */
    public String generatePopularKey(int limit) {
        return String.join(SEPARATOR, PREFIX, "popular", String.valueOf(limit));
    }

    /**
     * Generate wildcard pattern for invalidating all script-related caches.
     * Format: devops:script:*
     * 
     * @return Pattern for all script keys
     */
    public String generateScriptPattern() {
        return String.join(SEPARATOR, PREFIX, "script", "*");
    }

    /**
     * Generate wildcard pattern for invalidating all search caches.
     * Format: devops:search:*
     * 
     * @return Pattern for all search keys
     */
    public String generateSearchPattern() {
        return String.join(SEPARATOR, PREFIX, "search", "*");
    }

    /**
     * Generate wildcard pattern for invalidating all list caches.
     * Format: devops:list:*
     * 
     * @return Pattern for all list keys
     */
    public String generateListPattern() {
        return String.join(SEPARATOR, PREFIX, "list", "*");
    }

    /**
     * Generate wildcard pattern for invalidating all recommendation caches.
     * Format: devops:rec:*
     * 
     * @return Pattern for all recommendation keys
     */
    public String generateRecommendationPattern() {
        return String.join(SEPARATOR, PREFIX, "rec", "*");
    }

    /**
     * Generate wildcard pattern for invalidating all caches (use with caution).
     * Format: devops:*
     * 
     * @return Pattern for all DevOps cache keys
     */
    public String generateAllPattern() {
        return PREFIX + SEPARATOR + "*";
    }

    /**
     * Generate cache key for user-specific data.
     * Format: devops:user:{userId}:{dataType}
     * 
     * @param userId User ID
     * @param dataType Type of user data (favorites, history, presets)
     * @return Cache key for user data
     */
    public String generateUserDataKey(String userId, String dataType) {
        return String.join(SEPARATOR, PREFIX, "user", userId, dataType);
    }

    /**
     * Generate cache key for script analytics.
     * Format: devops:analytics:{scriptId}
     * 
     * @param scriptId The script ID
     * @return Cache key for script analytics
     */
    public String generateAnalyticsKey(Long scriptId) {
        return String.join(SEPARATOR, PREFIX, "analytics", String.valueOf(scriptId));
    }
}
