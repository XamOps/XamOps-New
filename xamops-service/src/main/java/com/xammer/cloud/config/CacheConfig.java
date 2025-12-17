package com.xammer.cloud.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 1. Define the ObjectMapper as a Bean.
     * This allows us to configure the JSON serialization rules in one place.
     */
    @Bean
    public ObjectMapper redisObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // --- Standard Configuration ---
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // ✅ FIX 1: Register Standard Security Modules
        // Teaches Jackson how to handle 'UsernamePasswordAuthenticationToken' and
        // 'SimpleGrantedAuthority'
        objectMapper.registerModules(SecurityJackson2Modules.getModules(getClass().getClassLoader()));

        // ✅ FIX 2: Register Custom ClientUserDetails Module
        // Teaches Jackson how to handle your custom 'ClientUserDetails' object
        objectMapper.registerModule(new ClientUserDetailsJacksonModule());

        // ✅ FIX 3: Register Mixin for JDBCConnectionException
        // Fixes "Cannot construct instance of JDBCConnectionException" error
        objectMapper.addMixIn(org.hibernate.exception.JDBCConnectionException.class,
                com.xammer.cloud.config.mixin.JDBCConnectionExceptionMixin.class);

        // --- Type Info Configuration (Fixes LinkedHashMap Error) ---
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        return objectMapper;
    }

    /**
     * 2. Define the Serializer as a Bean with the SPECIFIC NAME
     * "springSessionDefaultRedisSerializer".
     * This satisfies the "bean NOT FOUND" error and forces Spring Session to use
     * our JSON config.
     */
    @Bean(name = "springSessionDefaultRedisSerializer")
    public RedisSerializer<Object> springSessionDefaultRedisSerializer(ObjectMapper redisObjectMapper) {
        return new GenericJackson2JsonRedisSerializer(redisObjectMapper);
    }

    /**
     * 3. Inject the serializer into the CacheManager.
     * This ensures application caching (like @Cacheable) uses the same format as
     * Sessions.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
            RedisSerializer<Object> springSessionDefaultRedisSerializer) {

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(60)) // Cache expires after 60 minutes
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(SerializationPair.fromSerializer(springSessionDefaultRedisSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    /**
     * ⚠️ AUTO-FLUSH REDIS ON STARTUP
     * Since we changed the serialization format, old data in Redis will cause
     * crashes.
     * This automatically wipes Redis when the app starts to ensure a clean slate.
     */
    // @Bean
    // public CommandLineRunner clearRedisCache(RedisConnectionFactory
    // connectionFactory) {
    // return args -> {
    // try {
    // connectionFactory.getConnection().serverCommands().flushAll();
    // System.out.println("===========================================");
    // System.out.println("✅ REDIS CACHE FLUSHED SUCCESSFULLY");
    // System.out.println("===========================================");
    // } catch (Exception e) {
    // System.err.println("❌ FAILED TO FLUSH REDIS: " + e.getMessage());
    // }
    // };
    // }
}