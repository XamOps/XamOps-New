package com.xammer.billops.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
// ❌ REMOVE @Primary
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

// ✅ ADD THESE IMPORTS
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    // ❌ REMOVE THE @Primary ObjectMapper BEAN FROM THIS FILE
    // It is now in BillopsApplication.java
    
    // ❌ REMOVE the cacheConfiguration() bean. We will combine it.

    /**
     * This bean creates the Cache Manager.
     * It now defines its OWN ObjectMapper for Redis to solve
     * the LinkedHashMap error.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        
        // --- Create a new ObjectMapper *just for Redis* ---
        ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule()) 
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false) 
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            
        // --- ✅ THIS SOLVES THE LinkedHashMap ERROR ---
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
            .entryTtl(Duration.ofHours(1))
            .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer())) 
            .serializeValuesWith(SerializationPair.fromSerializer(redisSerializer)); 

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(config)
            .build();
    }
}