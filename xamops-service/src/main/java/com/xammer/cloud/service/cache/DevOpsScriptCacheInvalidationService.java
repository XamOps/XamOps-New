package com.xammer.cloud.service.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Cache invalidation service for DevOps Scripts.
 * Handles intelligent cache eviction based on script updates and user actions.
 * 
 * Invalidation Strategy (per REQ-2.6.3):
 * - On script update: Invalidate specific script + all search/list caches
 * - On script delete: Invalidate specific script + all search/list caches
 * - On rating update: Invalidate specific script + trending/popular caches
 * - On favorite: Invalidate user favorites cache
 * - On view/download: No cache invalidation (metrics only)
 * 
 * This ensures users always see fresh data after modifications while
 * maintaining cache performance for read operations.
 */
@Service
public class DevOpsScriptCacheInvalidationService {

    private static final Logger logger = LoggerFactory.getLogger(DevOpsScriptCacheInvalidationService.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final DevOpsScriptCacheKeyGenerator keyGenerator;

    public DevOpsScriptCacheInvalidationService(
            RedisTemplate<String, Object> devOpsScriptRedisTemplate,
            DevOpsScriptCacheKeyGenerator keyGenerator) {
        this.redisTemplate = devOpsScriptRedisTemplate;
        this.keyGenerator = keyGenerator;
    }

    /**
     * Invalidate cache for a specific script.
     * Called when script details are updated.
     * 
     * @param scriptId The script ID
     */
    public void invalidateScript(Long scriptId) {
        String key = keyGenerator.generateScriptKey(scriptId);
        Boolean deleted = redisTemplate.delete(key);
        logger.info("Invalidated cache for script {}: {}", scriptId, deleted);
    }

    /**
     * Invalidate all search-related caches.
     * Called when scripts are added, updated, or deleted.
     */
    public void invalidateAllSearchCaches() {
        String pattern = keyGenerator.generateSearchPattern();
        invalidateByPattern(pattern);
        logger.info("Invalidated all search caches with pattern: {}", pattern);
    }

    /**
     * Invalidate all list caches.
     * Called when scripts are added, updated, or deleted.
     */
    public void invalidateAllListCaches() {
        String pattern = keyGenerator.generateListPattern();
        invalidateByPattern(pattern);
        logger.info("Invalidated all list caches with pattern: {}", pattern);
    }

    /**
     * Invalidate all recommendation caches.
     * Called when user behavior changes significantly or script metadata changes.
     */
    public void invalidateAllRecommendationCaches() {
        String pattern = keyGenerator.generateRecommendationPattern();
        invalidateByPattern(pattern);
        logger.info("Invalidated all recommendation caches with pattern: {}", pattern);
    }

    /**
     * Invalidate cache for a specific script and all dependent caches.
     * This is the primary method called when a script is updated.
     * 
     * Invalidates:
     * - The specific script cache
     * - All search caches (script may appear in search results)
     * - All list caches (script may appear in filtered lists)
     * - Related scripts caches
     * - Analytics cache for the script
     * 
     * @param scriptId The script ID that was updated
     */
    public void invalidateScriptAndDependencies(Long scriptId) {
        logger.info("Starting cache invalidation for script {} and its dependencies", scriptId);
        
        // Invalidate the specific script
        invalidateScript(scriptId);
        
        // Invalidate analytics for this script
        String analyticsKey = keyGenerator.generateAnalyticsKey(scriptId);
        redisTemplate.delete(analyticsKey);
        
        // Invalidate all search and list caches (script may appear in results)
        invalidateAllSearchCaches();
        invalidateAllListCaches();
        
        // Invalidate related scripts cache (relationships may have changed)
        String relatedPattern = "devops:related:*";
        invalidateByPattern(relatedPattern);
        
        logger.info("Completed cache invalidation for script {} and dependencies", scriptId);
    }

    /**
     * Invalidate caches when a script is deleted.
     * Similar to update but also invalidates recommendations.
     * 
     * @param scriptId The script ID that was deleted
     */
    public void invalidateOnScriptDelete(Long scriptId) {
        logger.info("Invalidating caches for deleted script {}", scriptId);
        
        // Invalidate script and dependencies
        invalidateScriptAndDependencies(scriptId);
        
        // Also invalidate recommendations (script should no longer be recommended)
        invalidateAllRecommendationCaches();
        
        // Invalidate trending and popular (script should not appear)
        String trendingPattern = "devops:trending:*";
        String popularPattern = "devops:popular:*";
        invalidateByPattern(trendingPattern);
        invalidateByPattern(popularPattern);
        
        logger.info("Completed cache invalidation for deleted script {}", scriptId);
    }

    /**
     * Invalidate caches when a script rating is updated.
     * Affects trending, popular, and recommendation caches.
     * 
     * @param scriptId The script ID that received a rating
     */
    public void invalidateOnRatingUpdate(Long scriptId) {
        logger.info("Invalidating caches for script {} after rating update", scriptId);
        
        // Invalidate the specific script (average rating changed)
        invalidateScript(scriptId);
        
        // Invalidate trending and popular caches (rankings may have changed)
        String trendingPattern = "devops:trending:*";
        String popularPattern = "devops:popular:*";
        invalidateByPattern(trendingPattern);
        invalidateByPattern(popularPattern);
        
        // Invalidate search and list caches (if sorted by rating)
        invalidateAllSearchCaches();
        invalidateAllListCaches();
        
        logger.info("Completed cache invalidation for script {} after rating update", scriptId);
    }

    /**
     * Invalidate user favorites cache.
     * Called when user favorites or unfavorites a script.
     * 
     * @param userId The user ID
     */
    public void invalidateUserFavorites(String userId) {
        String key = keyGenerator.generateFavoritesKey(userId);
        Boolean deleted = redisTemplate.delete(key);
        logger.info("Invalidated favorites cache for user {}: {}", userId, deleted);
    }

    /**
     * Invalidate user-specific data cache.
     * 
     * @param userId User ID
     * @param dataType Type of data (favorites, history, presets)
     */
    public void invalidateUserData(String userId, String dataType) {
        String key = keyGenerator.generateUserDataKey(userId, dataType);
        Boolean deleted = redisTemplate.delete(key);
        logger.info("Invalidated {} cache for user {}: {}", dataType, userId, deleted);
    }

    /**
     * Invalidate recommendation caches for a specific user.
     * Called when user behavior changes significantly.
     * 
     * @param userId The user ID
     */
    public void invalidateUserRecommendations(String userId) {
        String pattern = "devops:rec:" + userId + ":*";
        invalidateByPattern(pattern);
        logger.info("Invalidated recommendation caches for user {}", userId);
    }

    /**
     * Invalidate all caches (use with extreme caution).
     * Should only be used for maintenance or major system updates.
     */
    public void invalidateAllDevOpsCaches() {
        logger.warn("INVALIDATING ALL DEVOPS CACHES - This is a heavy operation!");
        String pattern = keyGenerator.generateAllPattern();
        invalidateByPattern(pattern);
        logger.warn("All DevOps caches invalidated");
    }

    /**
     * Warm up cache for popular scripts.
     * Called on application startup or periodically.
     * This method doesn't invalidate but is included here for cache management.
     * 
     * @param scriptIds List of script IDs to warm up
     */
    public void warmUpScriptCache(Set<Long> scriptIds) {
        logger.info("Cache warm-up requested for {} scripts", scriptIds.size());
        // Implementation will be in the DevOpsScriptService
        // This is a placeholder for the cache warming strategy
    }

    /**
     * Get cache statistics for monitoring.
     * Returns information about cached items.
     * 
     * @return Cache statistics
     */
    public CacheStatistics getCacheStatistics() {
        CacheStatistics stats = new CacheStatistics();
        
        try {
            stats.setScriptCount(countKeysByPattern(keyGenerator.generateScriptPattern()));
            stats.setSearchCount(countKeysByPattern(keyGenerator.generateSearchPattern()));
            stats.setListCount(countKeysByPattern(keyGenerator.generateListPattern()));
            stats.setRecommendationCount(countKeysByPattern(keyGenerator.generateRecommendationPattern()));
            stats.setTotalCount(countKeysByPattern(keyGenerator.generateAllPattern()));
        } catch (Exception e) {
            logger.error("Error getting cache statistics", e);
        }
        
        return stats;
    }

    /**
     * Helper method to invalidate all keys matching a pattern.
     * Uses Redis SCAN for efficient pattern matching.
     * 
     * @param pattern Redis key pattern (e.g., "devops:search:*")
     */
    private void invalidateByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                Long deletedCount = redisTemplate.delete(keys);
                logger.info("Deleted {} keys matching pattern: {}", deletedCount, pattern);
            } else {
                logger.debug("No keys found matching pattern: {}", pattern);
            }
        } catch (Exception e) {
            logger.error("Error invalidating cache by pattern: {}", pattern, e);
        }
    }

    /**
     * Helper method to count keys matching a pattern.
     * 
     * @param pattern Redis key pattern
     * @return Count of matching keys
     */
    private long countKeysByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            logger.error("Error counting keys by pattern: {}", pattern, e);
            return 0;
        }
    }

    /**
     * Inner class for cache statistics.
     */
    public static class CacheStatistics {
        private long scriptCount;
        private long searchCount;
        private long listCount;
        private long recommendationCount;
        private long totalCount;

        public long getScriptCount() { return scriptCount; }
        public void setScriptCount(long scriptCount) { this.scriptCount = scriptCount; }

        public long getSearchCount() { return searchCount; }
        public void setSearchCount(long searchCount) { this.searchCount = searchCount; }

        public long getListCount() { return listCount; }
        public void setListCount(long listCount) { this.listCount = listCount; }

        public long getRecommendationCount() { return recommendationCount; }
        public void setRecommendationCount(long recommendationCount) { 
            this.recommendationCount = recommendationCount; 
        }

        public long getTotalCount() { return totalCount; }
        public void setTotalCount(long totalCount) { this.totalCount = totalCount; }

        @Override
        public String toString() {
            return String.format(
                "CacheStatistics{scripts=%d, searches=%d, lists=%d, recommendations=%d, total=%d}",
                scriptCount, searchCount, listCount, recommendationCount, totalCount
            );
        }
    }
}
