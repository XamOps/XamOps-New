package com.xammer.billops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class GlobalUserSyncService {

    private static final Logger log = LoggerFactory.getLogger(GlobalUserSyncService.class);

    @Scheduled(fixedDelay = 86400000)
    public void syncAllUsersToGlobalDirectory() {
        // global_users sync disabled — single DB mode, no global_users table
        log.debug("Skipping Global User Sync - single DB mode");
    }
}
