// In com.xammer.cloud.config.CacheConfig.java
// ⬇️ USE THIS UPDATED VERSION ⬇️

package com.xammer.cloud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature; // ✅ ADD
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule; // ✅ ADD
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer; // ✅ ADD

// ✅ ADD THESE IMPORTS
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        
        // --- Create a new ObjectMapper *just for Redis* ---
        ObjectMapper objectMapper = new ObjectMapper()
            
            // FIX 1: Handle dates like LocalDateTime
            .registerModule(new JavaTimeModule()) 
            
            // FIX 2: Write dates as "2025-11-10T..." not timestamps
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false) 
            
            // FIX 3: Don't crash on new properties
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            
        // --- FIX 4: THIS SOLVES THE LinkedHashMap ERROR ---
        objectMapper.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance, 
            ObjectMapper.DefaultTyping.NON_FINAL, 
            JsonTypeInfo.As.PROPERTY
        );
        // ---------------------------------------------------

        // --- Create a serializer with the custom Redis ObjectMapper ---
        GenericJackson2JsonRedisSerializer redisSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        // --- Use the new serializer in your Redis cache configuration ---
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            
            // Use String serializer for keys
            .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer())) 
            
            // Use our new JSON serializer for values
            .serializeValuesWith(SerializationPair.fromSerializer(redisSerializer)); 

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .build();
    }
}