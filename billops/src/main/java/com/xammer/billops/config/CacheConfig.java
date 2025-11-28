package com.xammer.billops.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

// Imports to enable default typing for correct deserialization
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;

// CRITICAL FIX: Import the module that teaches Jackson how to handle Spring Security classes
import org.springframework.security.jackson2.CoreJackson2Module;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * This bean creates the Cache Manager.
     * It defines its OWN ObjectMapper for Redis to solve deserialization errors.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        
        // --- Create a new ObjectMapper *just for Redis* ---
        // This is separate from the primary ObjectMapper to avoid conflicts
        ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule()) 
            // --- FIX START: Register Spring Security Module ---
            // This fixes the "Cannot construct instance of SimpleGrantedAuthority" error
            .registerModule(new CoreJackson2Module()) 
            // --- FIX END ---
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false) 
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            
        // --- THIS SOLVES THE LinkedHashMap ERROR ---
        // It embeds the Java class type into the JSON stored in Redis,
        // so Spring knows what class to deserialize it back into.
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
            .entryTtl(Duration.ofHours(1)) // Set a default TTL of 1 hour
            // Use standard String serializer for keys
            .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer())) 
            // Use our new custom JSON serializer for values
            .serializeValuesWith(SerializationPair.fromSerializer(redisSerializer)); 

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .build();
    }
}