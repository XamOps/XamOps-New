package com.xammer.cloud.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
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

                // --- Type Info Configuration (Fixes LinkedHashMap Error) ---
                //
                // Default typing is genuinely needed here: Redis holds heterogeneous cached
                // values and session objects, and without embedded type information they
                // deserialize back as LinkedHashMap. What is not needed is accepting *any*
                // class named in the JSON.
                //
                // This previously used LaissezFaireSubTypeValidator, which performs no
                // validation at all — it is Jackson's explicit "trust the document" option.
                // Combined with default typing that is the standard deserialization-gadget
                // RCE setup: an attacker who can write a value into Redis names a class with
                // a dangerous constructor or setter (the JNDI-fetching datasource classes
                // are the usual choice) and gets code execution inside the service on read.
                //
                // Redis is not internet-exposed, so this is a second-stage vulnerability
                // rather than a front door. It is worth closing anyway: it converts "the
                // attacker reached Redis" from full application compromise into cache
                // tampering, and it is the difference between one bug and a chain.
                //
                // The allowlist is by package rather than by class so ordinary DTO changes
                // do not require touching this config. Anything deserialized here belongs to
                // the application, the JDK's own containers, or Spring Security's session
                // types — nothing else has a legitimate reason to appear.
                BasicPolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                                .allowIfSubType("com.xammer.")
                                .allowIfSubType("java.util.")
                                .allowIfSubType("java.time.")
                                .allowIfSubType("java.lang.")
                                .allowIfSubType("java.math.")
                                .allowIfSubType("org.springframework.security.")
                                .build();

                objectMapper.activateDefaultTyping(
                                typeValidator,
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
                                .serializeValuesWith(
                                                SerializationPair.fromSerializer(springSessionDefaultRedisSerializer));

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