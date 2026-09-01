package com.xammer.cloud.service;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

@Service
public class DevOpsScriptService {

    @Autowired
    private DevOpsScriptRepository devOpsScriptRepository;

    @Autowired
    private CacheManager cacheManager;

    // One-time self-heal: evict any "devops_scripts" entry cached before the
    // PersistentBag/tags fix below was deployed. A stale entry has the
    // Hibernate collection type baked into its JSON and fails on every read
    // with LazyInitializationException, regardless of this fix.
    @PostConstruct
    public void evictStaleCache() {
        Cache cache = cacheManager.getCache("devops_scripts");
        if (cache != null) {
            cache.clear();
        }
    }

    // This annotation tells Spring to check the "devops_scripts" cache first.
    // If found, it returns the cached list.
    // If not found, it runs the method, fetches from DB, and stores the result in
    // Redis.
    @Cacheable(value = "devops_scripts")
    public List<DevOpsScript> getAllScripts() {
        List<DevOpsScript> scripts = devOpsScriptRepository.findAll();
        // Replace Hibernate's PersistentBag with plain ArrayLists before this
        // list is handed to the Redis @Cacheable serializer. With default
        // typing enabled, Jackson embeds the runtime collection class
        // (PersistentBag) in the cached JSON; on the next cache read it tries
        // to rehydrate a PersistentBag with no Hibernate Session attached,
        // throwing LazyInitializationException.
        for (DevOpsScript script : scripts) {
            script.setTags(new ArrayList<>(script.getTags()));
            script.setCloudProviders(new ArrayList<>(script.getCloudProviders()));
        }
        return scripts;
    }
}