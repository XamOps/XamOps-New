package com.xammer.billops.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule; // <-- IMPORT THIS
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Create ObjectMapper with configuration
        ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule()) // <-- ADD THIS LINE
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);


        // Create validator to allow all types
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType(Object.class)
            .build();

        // Enable default typing to preserve type information in Redis
        objectMapper.activateDefaultTyping(
            ptv,
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );

        // Create serializer with the configured ObjectMapper
        GenericJackson2JsonRedisSerializer redisSerializer =
            new GenericJackson2JsonRedisSerializer(objectMapper);

        // Configure Redis cache with the serializer
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(SerializationPair.fromSerializer(redisSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}