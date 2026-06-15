/**
 * Redis caching infrastructure for DevOps Scripts feature.
 * 
 * This package provides comprehensive caching support including:
 * - Redis configuration with appropriate TTL settings
 * - Cache key generation strategy
 * - Cache invalidation service
 * - Type-safe cache operations
 * 
 * Implements requirements:
 * - REQ-2.6.1: Search Performance (< 300ms p95)
 * - REQ-2.6.3: Multi-layer caching with TTL management
 * 
 * Cache TTL Strategy:
 * - Script details: 10 minutes
 * - Search results: 5 minutes
 * - Recommendations: 10 minutes
 * 
 * @see com.xammer.cloud.config.DevOpsScriptRedisCacheConfig
 * @see com.xammer.cloud.service.cache.DevOpsScriptCacheKeyGenerator
 * @see com.xammer.cloud.service.cache.DevOpsScriptCacheService
 * @see com.xammer.cloud.service.cache.DevOpsScriptCacheInvalidationService
 * 
 * @author XamOps Team
 * @version 1.0
 * @since 2024
 */
package com.xammer.cloud.service.cache;
