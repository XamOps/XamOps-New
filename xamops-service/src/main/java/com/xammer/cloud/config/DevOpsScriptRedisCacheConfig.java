package com.xammer.cloud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis cache configuration specifically for DevOps Scripts feature.
 * Configures RedisTemplate for script caching with proper serialization.
 * 
 * Cache TTL Strategy (per REQ-2.6.3):
 * - Script details: 10 minutes
 * - Search results: 5 minutes
 * - Script lists: 5 minutes
 * - Recommendations: 10 minutes
 */
@Configuration
public class DevOpsScriptRedisCacheConfig {

    /**
     * RedisTemplate configured for DevOps script caching.
     * Uses String keys and JSON values for flexibility.
     */
    @Bean(name = "devOpsScriptRedisTemplate")
    public RedisTemplate<String, Object> devOpsScriptRedisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper redisObjectMapper) {
        
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Use String serializer for keys (e.g., "devops:script:123")
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        
        // Use JSON serializer for values (DevOpsScript objects, lists, etc.)
        GenericJackson2JsonRedisSerializer valueSerializer = 
            new GenericJackson2JsonRedisSerializer(redisObjectMapper);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Cache configuration for script details.
     * TTL: 10 minutes (per REQ-2.6.3)
     */
    @Bean(name = "scriptDetailsCacheConfig")
    public RedisCacheConfiguration scriptDetailsCacheConfig(ObjectMapper redisObjectMapper) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                    new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer(redisObjectMapper)))
                .disableCachingNullValues();
    }

    /**
     * Cache configuration for search results and script lists.
     * TTL: 5 minutes (per REQ-2.6.3)
     */
    @Bean(name = "searchResultsCacheConfig")
    public RedisCacheConfiguration searchResultsCacheConfig(ObjectMapper redisObjectMapper) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                    new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer(redisObjectMapper)))
                .disableCachingNullValues();
    }

    /**
     * Cache configuration for recommendations.
     * TTL: 10 minutes
     */
    @Bean(name = "recommendationsCacheConfig")
    public RedisCacheConfiguration recommendationsCacheConfig(ObjectMapper redisObjectMapper) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
                    new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer(redisObjectMapper)))
                .disableCachingNullValues();
    }
}
