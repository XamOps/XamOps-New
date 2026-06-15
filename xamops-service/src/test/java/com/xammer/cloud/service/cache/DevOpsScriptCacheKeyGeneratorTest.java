package com.xammer.cloud.service.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DevOpsScriptCacheKeyGenerator.
 * Validates cache key generation strategy for consistency and correctness.
 */
class DevOpsScriptCacheKeyGeneratorTest {

    private DevOpsScriptCacheKeyGenerator keyGenerator;

    @BeforeEach
    void setUp() {
        keyGenerator = new DevOpsScriptCacheKeyGenerator();
    }

    @Test
    void testGenerateScriptKey() {
        String key = keyGenerator.generateScriptKey(123L);
        assertEquals("devops:script:123", key);
    }

    @Test
    void testGenerateSearchKey_WithAllParameters() {
        List<String> tags = Arrays.asList("docker", "kubernetes");
        String key = keyGenerator.generateSearchKey(
            "terraform aws", "terraform", "aws", "beginner", tags, "recent"
        );
        
        assertTrue(key.startsWith("devops:search:"));
        assertTrue(key.contains("terraform"));
        assertTrue(key.contains("aws"));
        assertTrue(key.contains("beginner"));
        assertTrue(key.contains("recent"));
    }

    @Test
    void testGenerateSearchKey_WithNullParameters() {
        String key = keyGenerator.generateSearchKey(
            null, null, null, null, null, null
        );
        
        assertEquals("devops:search:all:all:all:all:notags:recent", key);
    }

    @Test
    void testGenerateSearchKey_TagsAreSorted() {
        // Tags should be sorted for cache consistency
        List<String> tags1 = Arrays.asList("docker", "kubernetes", "aws");
        List<String> tags2 = Arrays.asList("kubernetes", "aws", "docker");
        
        String key1 = keyGenerator.generateSearchKey("test", "terraform", "aws", "beginner", tags1, "recent");
        String key2 = keyGenerator.generateSearchKey("test", "terraform", "aws", "beginner", tags2, "recent");
        
        // Keys should be identical despite different input order
        assertEquals(key1, key2);
    }

    @Test
    void testGenerateSearchKey_QueryNormalization() {
        // Queries should be normalized (lowercase, trimmed)
        String key1 = keyGenerator.generateSearchKey("  Terraform AWS  ", "terraform", "aws", null, null, "recent");
        String key2 = keyGenerator.generateSearchKey("terraform aws", "terraform", "aws", null, null, "recent");
        
        assertEquals(key1, key2);
    }

    @Test
    void testGenerateListKey() {
        String key = keyGenerator.generateListKey("terraform", "aws", "beginner", 4.0, "popular");
        assertEquals("devops:list:terraform:aws:beginner:4.0:popular", key);
    }

    @Test
    void testGenerateListKey_WithNullParameters() {
        String key = keyGenerator.generateListKey(null, null, null, null, null);
        assertEquals("devops:list:all:all:all:0.0:recent", key);
    }

    @Test
    void testGenerateRecommendationKey() {
        String key = keyGenerator.generateRecommendationKey("user123", 10);
        assertEquals("devops:rec:user123:10", key);
    }

    @Test
    void testGenerateRelatedScriptsKey() {
        String key = keyGenerator.generateRelatedScriptsKey(123L, 5);
        assertEquals("devops:related:123:5", key);
    }

    @Test
    void testGenerateFavoritesKey() {
        String key = keyGenerator.generateFavoritesKey("user456");
        assertEquals("devops:favorites:user456", key);
    }

    @Test
    void testGenerateTrendingKey() {
        String key = keyGenerator.generateTrendingKey("7d", 20);
        assertEquals("devops:trending:7d:20", key);
    }

    @Test
    void testGeneratePopularKey() {
        String key = keyGenerator.generatePopularKey(15);
        assertEquals("devops:popular:15", key);
    }

    @Test
    void testGenerateScriptPattern() {
        String pattern = keyGenerator.generateScriptPattern();
        assertEquals("devops:script:*", pattern);
    }

    @Test
    void testGenerateSearchPattern() {
        String pattern = keyGenerator.generateSearchPattern();
        assertEquals("devops:search:*", pattern);
    }

    @Test
    void testGenerateListPattern() {
        String pattern = keyGenerator.generateListPattern();
        assertEquals("devops:list:*", pattern);
    }

    @Test
    void testGenerateRecommendationPattern() {
        String pattern = keyGenerator.generateRecommendationPattern();
        assertEquals("devops:rec:*", pattern);
    }

    @Test
    void testGenerateAllPattern() {
        String pattern = keyGenerator.generateAllPattern();
        assertEquals("devops:*", pattern);
    }

    @Test
    void testGenerateUserDataKey() {
        String key = keyGenerator.generateUserDataKey("user789", "presets");
        assertEquals("devops:user:user789:presets", key);
    }

    @Test
    void testGenerateAnalyticsKey() {
        String key = keyGenerator.generateAnalyticsKey(999L);
        assertEquals("devops:analytics:999", key);
    }

    @Test
    void testSearchKey_EmptyTags() {
        List<String> emptyTags = Collections.emptyList();
        String key = keyGenerator.generateSearchKey("test", "terraform", "aws", "beginner", emptyTags, "recent");
        
        assertTrue(key.contains("notags"));
    }

    @Test
    void testKeyConsistency_MultipleCallsProduceSameKey() {
        // Multiple calls with same parameters should produce identical keys
        List<String> tags = Arrays.asList("docker", "kubernetes");
        
        String key1 = keyGenerator.generateSearchKey("test", "terraform", "aws", "beginner", tags, "recent");
        String key2 = keyGenerator.generateSearchKey("test", "terraform", "aws", "beginner", tags, "recent");
        String key3 = keyGenerator.generateSearchKey("test", "terraform", "aws", "beginner", tags, "recent");
        
        assertEquals(key1, key2);
        assertEquals(key2, key3);
    }

    @Test
    void testKeyUniqueness_DifferentParametersProduceDifferentKeys() {
        // Different parameters should produce different keys
        List<String> tags = Arrays.asList("docker");
        
        String key1 = keyGenerator.generateSearchKey("test1", "terraform", "aws", "beginner", tags, "recent");
        String key2 = keyGenerator.generateSearchKey("test2", "terraform", "aws", "beginner", tags, "recent");
        String key3 = keyGenerator.generateSearchKey("test1", "bash", "aws", "beginner", tags, "recent");
        String key4 = keyGenerator.generateSearchKey("test1", "terraform", "gcp", "beginner", tags, "recent");
        
        assertNotEquals(key1, key2);
        assertNotEquals(key1, key3);
        assertNotEquals(key1, key4);
        assertNotEquals(key2, key3);
    }
}
