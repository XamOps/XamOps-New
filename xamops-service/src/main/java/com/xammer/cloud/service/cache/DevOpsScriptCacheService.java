package com.xammer.cloud.service.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.DevOpsScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * High-level cache service for DevOps Scripts.
 * Provides type-safe caching operations with automatic key generation and TTL management.
 * 
 * TTL Strategy (per REQ-2.6.3):
 * - Script details: 10 minutes
 * - Search results: 5 minutes
 * - Recommendations: 10 minutes
 */
@Service
public class DevOpsScriptCacheService {

    private static final Logger logger = LoggerFactory.getLogger(DevOpsScriptCacheService.class);

    // TTL constants (in minutes)
    private static final long SCRIPT_DETAILS_TTL = 10;
    private static final long SEARCH_RESULTS_TTL = 5;
    private static final long RECOMMENDATIONS_TTL = 10;
    private static final long LISTS_TTL = 5;
    private static final long FAVORITES_TTL = 10;
    private static final long TRENDING_TTL = 30; // Trending changes less frequently
    private static final long ANALYTICS_TTL = 15;

    private final RedisTemplate<String, Object> redisTemplate;
    private final DevOpsScriptCacheKeyGenerator keyGenerator;
    private final ObjectMapper objectMapper;

    public DevOpsScriptCacheService(
            RedisTemplate<String, Object> devOpsScriptRedisTemplate,
            DevOpsScriptCacheKeyGenerator keyGenerator,
            ObjectMapper objectMapper) {
        this.redisTemplate = devOpsScriptRedisTemplate;
        this.keyGenerator = keyGenerator;
        this.objectMapper = objectMapper;
    }

    // ==================== SCRIPT DETAILS CACHING ====================

    /**
     * Get cached script details by ID.
     * 
     * @param scriptId The script ID
     * @return Optional containing cached script or empty if not found
     */
    public Optional<DevOpsScript> getScript(Long scriptId) {
        String key = keyGenerator.generateScriptKey(scriptId);
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("Cache HIT for script: {}", scriptId);
                DevOpsScript script = objectMapper.convertValue(value, DevOpsScript.class);
                return Optional.of(script);
            }
            logger.debug("Cache MISS for script: {}", scriptId);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error retrieving script {} from cache", scriptId, e);
            return Optional.empty();
        }
    }

    /**
     * Cache script details.
     * 
     * @param script The script to cache
     */
    public void cacheScript(DevOpsScript script) {
        if (script == null || script.getId() == null) {
            logger.warn("Attempted to cache null script or script without ID");
            return;
        }
        
        String key = keyGenerator.generateScriptKey(script.getId());
        try {
            redisTemplate.opsForValue().set(key, script, SCRIPT_DETAILS_TTL, TimeUnit.MINUTES);
            logger.debug("Cached script {} with TTL {} minutes", script.getId(), SCRIPT_DETAILS_TTL);
        } catch (Exception e) {
            logger.error("Error caching script {}", script.getId(), e);
        }
    }

    // ==================== SEARCH RESULTS CACHING ====================

    /**
     * Get cached search results.
     * 
     * @param query Search query
     * @param category Category filter
     * @param cloudProvider Cloud provider filter
     * @param difficulty Difficulty filter
     * @param tags Tag filters
     * @param sortBy Sort order
     * @return Optional containing cached results or empty if not found
     */
    public Optional<List<DevOpsScript>> getSearchResults(
            String query, String category, String cloudProvider,
            String difficulty, List<String> tags, String sortBy) {
        
        String key = keyGenerator.generateSearchKey(
            query, category, cloudProvider, difficulty, tags, sortBy);
        
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("Cache HIT for search: {}", key);
                List<DevOpsScript> results = objectMapper.convertValue(
                    value, new TypeReference<List<DevOpsScript>>() {});
                return Optional.of(results);
            }
            logger.debug("Cache MISS for search: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error retrieving search results from cache", e);
            return Optional.empty();
        }
    }

    /**
     * Cache search results.
     * 
     * @param query Search query
     * @param category Category filter
     * @param cloudProvider Cloud provider filter
     * @param difficulty Difficulty filter
     * @param tags Tag filters
     * @param sortBy Sort order
     * @param results Search results to cache
     */
    public void cacheSearchResults(
            String query, String category, String cloudProvider,
            String difficulty, List<String> tags, String sortBy,
            List<DevOpsScript> results) {
        
        String key = keyGenerator.generateSearchKey(
            query, category, cloudProvider, difficulty, tags, sortBy);
        
        try {
            redisTemplate.opsForValue().set(key, results, SEARCH_RESULTS_TTL, TimeUnit.MINUTES);
            logger.debug("Cached search results with key {} and TTL {} minutes", 
                key, SEARCH_RESULTS_TTL);
        } catch (Exception e) {
            logger.error("Error caching search results", e);
        }
    }

    // ==================== LIST CACHING ====================

    /**
     * Get cached script list.
     * 
     * @param category Category filter
     * @param cloudProvider Cloud provider filter
     * @param difficulty Difficulty filter
     * @param minRating Minimum rating filter
     * @param sortBy Sort order
     * @return Optional containing cached list or empty if not found
     */
    public Optional<List<DevOpsScript>> getList(
            String category, String cloudProvider, String difficulty,
            Double minRating, String sortBy) {
        
        String key = keyGenerator.generateListKey(
            category, cloudProvider, difficulty, minRating, sortBy);
        
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("Cache HIT for list: {}", key);
                List<DevOpsScript> results = objectMapper.convertValue(
                    value, new TypeReference<List<DevOpsScript>>() {});
                return Optional.of(results);
            }
            logger.debug("Cache MISS for list: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error retrieving list from cache", e);
            return Optional.empty();
        }
    }

    /**
     * Cache script list.
     * 
     * @param category Category filter
     * @param cloudProvider Cloud provider filter
     * @param difficulty Difficulty filter
     * @param minRating Minimum rating filter
     * @param sortBy Sort order
     * @param scripts Script list to cache
     */
    public void cacheList(
            String category, String cloudProvider, String difficulty,
            Double minRating, String sortBy, List<DevOpsScript> scripts) {
        
        String key = keyGenerator.generateListKey(
            category, cloudProvider, difficulty, minRating, sortBy);
        
        try {
            redisTemplate.opsForValue().set(key, scripts, LISTS_TTL, TimeUnit.MINUTES);
            logger.debug("Cached list with key {} and TTL {} minutes", key, LISTS_TTL);
        } catch (Exception e) {
            logger.error("Error caching list", e);
        }
    }

    // ==================== RECOMMENDATIONS CACHING ====================

    /**
     * Get cached recommendations for a user.
     * 
     * @param userId User ID
     * @param limit Number of recommendations
     * @return Optional containing cached recommendations or empty if not found
     */
    public Optional<List<DevOpsScript>> getRecommendations(String userId, int limit) {
        String key = keyGenerator.generateRecommendationKey(userId, limit);
        
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("Cache HIT for recommendations: {}", key);
                List<DevOpsScript> results = objectMapper.convertValue(
                    value, new TypeReference<List<DevOpsScript>>() {});
                return Optional.of(results);
            }
            logger.debug("Cache MISS for recommendations: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error retrieving recommendations from cache", e);
            return Optional.empty();
        }
    }

    /**
     * Cache recommendations.
     * 
     * @param userId User ID
     * @param limit Number of recommendations
     * @param recommendations Recommendations to cache
     */
    public void cacheRecommendations(String userId, int limit, List<DevOpsScript> recommendations) {
        String key = keyGenerator.generateRecommendationKey(userId, limit);
        
        try {
            redisTemplate.opsForValue().set(
                key, recommendations, RECOMMENDATIONS_TTL, TimeUnit.MINUTES);
            logger.debug("Cached recommendations with key {} and TTL {} minutes", 
                key, RECOMMENDATIONS_TTL);
        } catch (Exception e) {
            logger.error("Error caching recommendations", e);
        }
    }

    // ==================== RELATED SCRIPTS CACHING ====================

    /**
     * Get cached related scripts.
     * 
     * @param scriptId Script ID
     * @param limit Number of related scripts
     * @return Optional containing cached related scripts or empty if not found
     */
    public Optional<List<DevOpsScript>> getRelatedScripts(Long scriptId, int limit) {
        String key = keyGenerator.generateRelatedScriptsKey(scriptId, limit);
        
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("Cache HIT for related scripts: {}", key);
                List<DevOpsScript> results = objectMapper.convertValue(
                    value, new TypeReference<List<DevOpsScript>>() {});
                return Optional.of(results);
            }
            logger.debug("Cache MISS for related scripts: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error retrieving related scripts from cache", e);
            return Optional.empty();
        }
    }

    /**
     * Cache related scripts.
     * 
     * @param scriptId Script ID
     * @param limit Number of related scripts
     * @param relatedScripts Related scripts to cache
     */
    public void cacheRelatedScripts(Long scriptId, int limit, List<DevOpsScript> relatedScripts) {
        String key = keyGenerator.generateRelatedScriptsKey(scriptId, limit);
        
        try {
            redisTemplate.opsForValue().set(
                key, relatedScripts, RECOMMENDATIONS_TTL, TimeUnit.MINUTES);
            logger.debug("Cached related scripts with key {} and TTL {} minutes", 
                key, RECOMMENDATIONS_TTL);
        } catch (Exception e) {
            logger.error("Error caching related scripts", e);
        }
    }

    // ==================== FAVORITES CACHING ====================

    /**
     * Get cached user favorites.
     * 
     * @param userId User ID
     * @return Optional containing cached favorites or empty if not found
     */
    public Optional<List<DevOpsScript>> getFavorites(String userId) {
        String key = keyGenerator.generateFavoritesKey(userId);
        
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("Cache HIT for favorites: {}", key);
                List<DevOpsScript> results = objectMapper.convertValue(
                    value, new TypeReference<List<DevOpsScript>>() {});
                return Optional.of(results);
            }
            logger.debug("Cache MISS for favorites: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error retrieving favorites from cache", e);
            return Optional.empty();
        }
    }

    /**
     * Cache user favorites.
     * 
     * @param userId User ID
     * @param favorites Favorites to cache
     */
    public void cacheFavorites(String userId, List<DevOpsScript> favorites) {
        String key = keyGenerator.generateFavoritesKey(userId);
        
        try {
            redisTemplate.opsForValue().set(key, favorites, FAVORITES_TTL, TimeUnit.MINUTES);
            logger.debug("Cached favorites with key {} and TTL {} minutes", 
                key, FAVORITES_TTL);
        } catch (Exception e) {
            logger.error("Error caching favorites", e);
        }
    }

    // ==================== TRENDING/POPULAR CACHING ====================

    /**
     * Get cached trending scripts.
     * 
     * @param timeRange Time range (e.g., "7d", "30d")
     * @param limit Number of trending scripts
     * @return Optional containing cached trending scripts or empty if not found
     */
    public Optional<List<DevOpsScript>> getTrending(String timeRange, int limit) {
        String key = keyGenerator.generateTrendingKey(timeRange, limit);
        
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("Cache HIT for trending: {}", key);
                List<DevOpsScript> results = objectMapper.convertValue(
                    value, new TypeReference<List<DevOpsScript>>() {});
                return Optional.of(results);
            }
            logger.debug("Cache MISS for trending: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error retrieving trending from cache", e);
            return Optional.empty();
        }
    }

    /**
     * Cache trending scripts.
     * 
     * @param timeRange Time range
     * @param limit Number of trending scripts
     * @param trending Trending scripts to cache
     */
    public void cacheTrending(String timeRange, int limit, List<DevOpsScript> trending) {
        String key = keyGenerator.generateTrendingKey(timeRange, limit);
        
        try {
            redisTemplate.opsForValue().set(key, trending, TRENDING_TTL, TimeUnit.MINUTES);
            logger.debug("Cached trending with key {} and TTL {} minutes", 
                key, TRENDING_TTL);
        } catch (Exception e) {
            logger.error("Error caching trending", e);
        }
    }

    /**
     * Get cached popular scripts.
     * 
     * @param limit Number of popular scripts
     * @return Optional containing cached popular scripts or empty if not found
     */
    public Optional<List<DevOpsScript>> getPopular(int limit) {
        String key = keyGenerator.generatePopularKey(limit);
        
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                logger.debug("Cache HIT for popular: {}", key);
                List<DevOpsScript> results = objectMapper.convertValue(
                    value, new TypeReference<List<DevOpsScript>>() {});
                return Optional.of(results);
            }
            logger.debug("Cache MISS for popular: {}", key);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Error retrieving popular from cache", e);
            return Optional.empty();
        }
    }

    /**
     * Cache popular scripts.
     * 
     * @param limit Number of popular scripts
     * @param popular Popular scripts to cache
     */
    public void cachePopular(int limit, List<DevOpsScript> popular) {
        String key = keyGenerator.generatePopularKey(limit);
        
        try {
            redisTemplate.opsForValue().set(key, popular, TRENDING_TTL, TimeUnit.MINUTES);
            logger.debug("Cached popular with key {} and TTL {} minutes", 
                key, TRENDING_TTL);
        } catch (Exception e) {
            logger.error("Error caching popular", e);
        }
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Check if a key exists in cache.
     * 
     * @param key Cache key
     * @return true if key exists, false otherwise
     */
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            logger.error("Error checking key existence: {}", key, e);
            return false;
        }
    }

    /**
     * Get remaining TTL for a key.
     * 
     * @param key Cache key
     * @return Remaining TTL in seconds, -1 if key doesn't exist, -2 if no expiry
     */
    public long getTTL(String key) {
        try {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            return ttl != null ? ttl : -1;
        } catch (Exception e) {
            logger.error("Error getting TTL for key: {}", key, e);
            return -1;
        }
    }
}
