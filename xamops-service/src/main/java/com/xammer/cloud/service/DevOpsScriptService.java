package com.xammer.cloud.service;

import com.xammer.cloud.domain.DevOpsScript;
import com.xammer.cloud.repository.DevOpsScriptRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DevOpsScriptService {

    @Autowired
    private DevOpsScriptRepository devOpsScriptRepository;

    // This annotation tells Spring to check the "devops_scripts" cache first.
    // If found, it returns the cached list.
    // If not found, it runs the method, fetches from DB, and stores the result in
    // Redis.
    @Cacheable(value = "devops_scripts")
    public List<DevOpsScript> getAllScripts() {
        return devOpsScriptRepository.findAll();
    }
}