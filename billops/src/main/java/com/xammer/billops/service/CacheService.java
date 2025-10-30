package com.xammer.billops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class CacheService {

    private static final Logger logger = LoggerFactory.getLogger(CacheService.class);

    @Autowired
    private CacheManager cacheManager;

    @Scheduled(fixedRateString = "${caching.eviction.fixedRate.ms:3600000}")
    public void evictAllCachesAtIntervals() {
        logger.info("Executing scheduled cache eviction...");
        evictAllCaches();
    }

    public void evictAllCaches() {
        logger.info("Starting eviction of all application caches...");
        cacheManager.getCacheNames().stream()
            .map(cacheManager::getCache)
            .filter(Objects::nonNull)
            .forEach(cache -> {
                cache.clear();
                logger.debug("Successfully evicted cache: {}", cache.getName());
            });
        logger.info("Completed eviction of all application caches.");
    }
}