package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProactiveCacheRefreshService {

   private static final Logger logger = LoggerFactory.getLogger(ProactiveCacheRefreshService.class);

   private final CloudAccountRepository cloudAccountRepository;
   private final DashboardDataService dashboardDataService;
   private final CacheManager cacheManager;

   @Autowired
   public ProactiveCacheRefreshService(CloudAccountRepository cloudAccountRepository, DashboardDataService dashboardDataService, CacheManager cacheManager) {
       this.cloudAccountRepository = cloudAccountRepository;
       this.dashboardDataService = dashboardDataService;
       this.cacheManager = cacheManager;
   }

   @Scheduled(fixedRateString = "${caching.proactive.fixedRate.ms:900000}")
   public void refreshDashboardCache() {
       logger.info("--- Starting proactive dashboard cache refresh job ---");
       List<CloudAccount> accounts = cloudAccountRepository.findAll();

       for (CloudAccount account : accounts) {
           try {
               String accountId = "GCP".equals(account.getProvider()) ? account.getGcpProjectId() : account.getAwsAccountId();
               if (accountId == null) continue;

               logger.info("Proactively refreshing cache for account: {}", accountId);

               // Evict the old cache entry first
               cacheManager.getCache("dashboardData").evict(accountId);

               // Re-populate the cache by calling the method.
               // The 'forceRefresh' is true to bypass any checks and fetch new data.
               // ✅ PASS NULL FOR THE USER DETAILS ARGUMENT
               dashboardDataService.getDashboardData(accountId, true, null);

           } catch (Exception e) {
               logger.error("Failed to proactively refresh cache for account: {}", account.getId(), e);
           }
       }
       logger.info("--- Proactive dashboard cache refresh job finished ---");
   }
}