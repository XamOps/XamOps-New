package com.xammer.cloud.config;

import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.giffing.bucket4j.spring.boot.starter.context.properties.Bucket4JBootProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.spi.CachingProvider;

/**
 * Supplies the JSR-107 {@link CacheManager} that bucket4j's synchronous cache resolver
 * needs for the dashboard rate limiter, backed by Caffeine and therefore in-process.
 *
 * <p>Why this class has to exist: bucket4j 0.14 removed the Caffeine resolver that the
 * 0.8.1 setup relied on, so the limiter now has to be pointed at a named cache. The
 * starter's Lettuce and Redisson resolvers are {@code @ConditionalOnAsynchronousPropertyCondition}
 * and only activate for {@code WEBFLUX}/{@code GATEWAY} filters — in this servlet
 * application they never match, and the starter aborts startup with "No Bucket4j cache
 * configuration found" rather than degrading. JCache is the sync option that needs no
 * additional Redis client.
 *
 * <p>Scope is deliberately narrow. This is a {@code javax.cache.CacheManager}, not a
 * {@code org.springframework.cache.CacheManager}, so it does not interact with the
 * application's own {@code @Cacheable} setup — that stays on the explicit
 * {@code RedisCacheManager} in {@link CacheConfig}.
 *
 * <p>Note this keeps the limit per-instance, matching the previous behaviour. Making it
 * hold across instances is a deliberate change, not an upgrade side effect; the Redis-backed
 * {@code RateLimitInterceptor} is the precedent to follow if that is wanted.
 */
@Configuration
public class Bucket4jCacheConfig {

    @Bean(destroyMethod = "close")
    public CacheManager bucket4jCacheManager(Bucket4JBootProperties properties) {
        // Name the provider explicitly: Caching.getCachingProvider() throws when more than
        // one JSR-107 provider is on the classpath, and that is a startup failure waiting
        // for whoever next adds a caching library.
        CachingProvider provider = Caching.getCachingProvider(CaffeineCachingProvider.class.getName());
        CacheManager cacheManager = provider.getCacheManager();

        // bucket4j only ever calls the untyped CacheManager.getCache(String) and never
        // creates a cache, so every configured cache has to exist up front or the resolver
        // hands back null at first request.
        properties.getFilters().stream()
                .map(filter -> filter.getCacheName())
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .forEach(name -> createIfAbsent(cacheManager, name));

        if (properties.isFilterConfigCachingEnabled()) {
            createIfAbsent(cacheManager, properties.getFilterConfigCacheName());
        }

        return cacheManager;
    }

    private static void createIfAbsent(CacheManager cacheManager, String name) {
        if (cacheManager.getCache(name) == null) {
            // Untyped on purpose — getCache(String) rejects a cache that was created with
            // declared key/value types.
            cacheManager.createCache(name, new MutableConfiguration<>());
        }
    }
}
