package com.xammer.billops.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.billops.config.ClientUserDetailsJacksonModule; // Ensure this import is correct
import org.springframework.boot.CommandLineRunner;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.security.jackson2.SecurityJackson2Modules;

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
    private ObjectMapper createRedisObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Register Modules
        objectMapper.registerModules(SecurityJackson2Modules.getModules(getClass().getClassLoader()));
        objectMapper.registerModule(new ClientUserDetailsJacksonModule());

        // Activate Default Typing (Crucial for Redis, bad for REST)
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return objectMapper;
    }

    @Bean(name = "springSessionDefaultRedisSerializer")
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        // Use the private creator method
        return new GenericJackson2JsonRedisSerializer(createRedisObjectMapper());
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        // --- Create a new ObjectMapper just for Redis ---
        // This is separate from the primary ObjectMapper to avoid conflicts
        // Reuse the dedicated Redis ObjectMapper and create a RedisSerializer from it
        ObjectMapper objectMapper = createRedisObjectMapper();
        RedisSerializer<Object> redisSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(SerializationPair.fromSerializer(redisSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    // Keep your flush bean if needed, or remove it if you're done debugging
    @Bean
    public CommandLineRunner clearRedisCache(RedisConnectionFactory connectionFactory) {
        return args -> {
            try {
                connectionFactory.getConnection().serverCommands().flushAll();
                System.out.println("✅ REDIS CACHE FLUSHED (BillOps)");
            } catch (Exception e) {
                // ignore
            }
        };
    }
}