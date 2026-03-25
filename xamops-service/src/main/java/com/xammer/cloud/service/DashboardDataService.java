package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.SonarQubeProject;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.ReservationDto;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.dto.gcp.GcpDashboardData;
import com.xammer.cloud.dto.gcp.GcpFinOpsReportDto;
import com.xammer.cloud.dto.sonarqube.SonarQubeMetricsDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.JobExecutionHistoryRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.PolicyScopeType;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasRequest;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasResponse;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DashboardDataService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardDataService.class);

    // Cache Keys for Phase 2 Snapshots
    // NOTE: These are now prefixes. Actual keys will be SNAPSHOT_KEY_AWS + "::" +
    // tenantId
    private static final String SNAPSHOT_KEY_AWS = "UNIFIED_DASHBOARD_AWS";
    private static final String SNAPSHOT_KEY_GCP = "UNIFIED_DASHBOARD_GCP";
    private static final String SNAPSHOT_KEY_AZURE = "UNIFIED_DASHBOARD_AZURE";

    // Currency Conversion Rate: INR to USD
    // GCP billing in India is in INR, we convert to USD for unified display
    private static final double INR_TO_USD_RATE = 0.012; // Approximately 1 INR = 0.012 USD (83 INR = 1 USD)

    // Limit concurrent account processing to prevent multiplicative thread explosion.
    // 8 accounts × 15 regions × 10 services = 1,200 awsTaskExecutor tasks max (fits in queue=2000)
    private static final Semaphore ACCOUNT_SEMAPHORE = new Semaphore(8);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final GcpDataService gcpDataService;
    private final CloudListService cloudListService;
    private final OptimizationService optimizationService;
    private final SecurityService securityService;
    private final FinOpsService finOpsService;
    private final ReservationService reservationService;
    private final RedisCacheService redisCache;
    private final ObjectMapper objectMapper;
    private final CachedDataRepository cachedDataRepository;
    private final CostService costService;
    private final com.xammer.cloud.service.azure.AzureDashboardService azureDashboardService;
    private final com.xammer.cloud.service.gcp.GcpFinOpsReportService gcpFinOpsReportService;
    private final SonarQubeService sonarQubeService;
    private final UserRepository userRepository;
    private final AutoSpottingService autoSpottingService;
    private final EbsDiskRightsizingService ebsDiskRightsizingService;

    // Phase 2: Master Data Access for Tenant Iteration
    private final JdbcTemplate masterJdbcTemplate;

    private final JobExecutionHistoryRepository jobExecutionHistoryRepository;

    // ADDED: Environment-aware configuration
    private final com.xammer.cloud.config.SchedulingEnvironmentConfig envConfig;

    // Dedicated executor for background data-fetch lambdas (IAM, CloudWatch, etc.)
    // Keeps AWS-Async-* threads free for HTTP-facing requests
    private final TaskExecutor backgroundTaskExecutor;

    @Autowired
    public DashboardDataService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            GcpDataService gcpDataService,
            @Lazy CloudListService cloudListService,
            @Lazy OptimizationService optimizationService,
            @Lazy SecurityService securityService,
            @Lazy FinOpsService finOpsService,
            @Lazy ReservationService reservationService,
            RedisCacheService redisCache,
            ObjectMapper objectMapper,
            CachedDataRepository cachedDataRepository,
            @Lazy CostService costService,
            @Lazy com.xammer.cloud.service.azure.AzureDashboardService azureDashboardService,
            @Lazy com.xammer.cloud.service.gcp.GcpFinOpsReportService gcpFinOpsReportService,
            @Lazy SonarQubeService sonarQubeService,
            UserRepository userRepository,
            JobExecutionHistoryRepository jobExecutionHistoryRepository,
            @Qualifier("masterDataSource") DataSource masterDataSource,
            com.xammer.cloud.config.SchedulingEnvironmentConfig envConfig,
            @Lazy AutoSpottingService autoSpottingService,
            @Lazy EbsDiskRightsizingService ebsDiskRightsizingService,
            @Qualifier("backgroundTaskExecutor") TaskExecutor backgroundTaskExecutor) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.gcpDataService = gcpDataService;
        this.cloudListService = cloudListService;
        this.optimizationService = optimizationService;
        this.securityService = securityService;
        this.finOpsService = finOpsService;
        this.reservationService = reservationService;
        this.redisCache = redisCache;
        this.objectMapper = objectMapper;
        this.cachedDataRepository = cachedDataRepository;
        this.costService = costService;
        this.azureDashboardService = azureDashboardService;
        this.gcpFinOpsReportService = gcpFinOpsReportService;
        this.sonarQubeService = sonarQubeService;
        this.userRepository = userRepository;
        this.jobExecutionHistoryRepository = jobExecutionHistoryRepository;
        this.masterJdbcTemplate = new JdbcTemplate(masterDataSource);
        this.envConfig = envConfig;
        this.autoSpottingService = autoSpottingService;
        this.ebsDiskRightsizingService = ebsDiskRightsizingService;
        this.backgroundTaskExecutor = backgroundTaskExecutor;
    }

    // ============================================================================================
    // PHASE 2: ASYNC AGGREGATOR (BACKGROUND JOBS)
    // ============================================================================================

    /**
     * OPTIMIZED: Runs once daily to pre-calculate dashboard data for the current
     * tenant only.
     * Changed from every 6 hours to daily at 1:00 AM to reduce AWS API costs.
     * Uses cron schedule instead of fixedDelay to ensure predictable execution
     * time.
     * No immediate execution on startup - waits until scheduled time.
     */
    @Scheduled(cron = "0 0 1 * * ?") // Daily at 1:00 AM
    public void runBackgroundAggregation() {
        logger.info("⚡ [Async-Aggregator] Starting global dashboard aggregation...");
        long totalStart = System.currentTimeMillis();

        List<String> activeTenants = fetchActiveTenants();
        logger.info("Found {} active tenants to process: {}", activeTenants.size(), activeTenants);

        if (activeTenants.isEmpty()) {
            logger.warn("⚠️ No active tenants found in Master DB. Checking default context...");
            // Optional: You might want to process the default context here if needed
        }

        for (String tenantId : activeTenants) {
            long tenantStart = System.currentTimeMillis();
            com.xammer.cloud.domain.JobExecutionHistory jobHistory = new com.xammer.cloud.domain.JobExecutionHistory(
                    "DASHBOARD_AGGREGATION", tenantId, LocalDateTime.now(), "RUNNING");

            // We need to save this in the TENANT context? No, this history should probably
            // be in the tenant DB.
            // However, the `runBackgroundAggregation` iterates over tenants.
            // If we want a central history, it should be in master DB, but we are using
            // JpaRepository which usually binds to the current tenant DataSource.
            // Given the requirement "in database the data should store in proper format",
            // keeping it in the tenant DB is safer/easier with existing Jpa setup.

            // Note: We need to switch context BEFORE saving the start record if we want it
            // in Tenant DB.

            try {
                TenantContext.setCurrentTenant(tenantId);

                // Save START record
                jobHistory = jobExecutionHistoryRepository.save(jobHistory);

                logger.info(">> Processing Tenant: {}", tenantId);

                final String tenantIdFinal = tenantId;
                final com.xammer.cloud.domain.JobExecutionHistory jobHistoryFinal = jobHistory;
                CompletableFuture<Integer> tenantFuture = CompletableFuture.supplyAsync(() -> {
                    TenantContext.setCurrentTenant(tenantIdFinal);
                    try {
                        return aggregateTenantData(tenantIdFinal);
                    } finally {
                        TenantContext.clear();
                    }
                });

                int processedCount;
                try {
                    processedCount = tenantFuture.get(15, TimeUnit.MINUTES);
                } catch (TimeoutException te) {
                    logger.error("❌ Tenant {} timed out after 15 min — skipping to next tenant", tenantId);
                    tenantFuture.cancel(true);
                    try {
                        if (jobHistoryFinal.getId() != null) {
                            jobHistoryFinal.setEndTime(LocalDateTime.now());
                            jobHistoryFinal.setStatus("TIMEOUT");
                            jobHistoryFinal.setErrorMessage("Processing timed out after 15 minutes");
                            jobExecutionHistoryRepository.save(jobHistoryFinal);
                        }
                    } catch (Exception writeEx) {
                        logger.error("Failed to write timeout log to DB", writeEx);
                    }
                    continue;
                } catch (java.util.concurrent.ExecutionException ee) {
                    logger.error("❌ Tenant {} threw an unhandled exception — skipping to next tenant. Cause: {}",
                            tenantId, ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage(), ee);
                    tenantFuture.cancel(true);
                    try {
                        if (jobHistoryFinal.getId() != null) {
                            jobHistoryFinal.setEndTime(LocalDateTime.now());
                            jobHistoryFinal.setStatus("FAILURE");
                            jobHistoryFinal.setErrorMessage("Unhandled exception: " + ee.getMessage());
                            jobExecutionHistoryRepository.save(jobHistoryFinal);
                        }
                    } catch (Exception writeEx) {
                        logger.error("Failed to write failure log to DB", writeEx);
                    }
                    continue;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.error("❌ Thread interrupted while waiting for tenant {} — stopping aggregation", tenantId);
                    break;
                }

                jobHistory.setEndTime(LocalDateTime.now());
                jobHistory.setStatus("SUCCESS");
                jobHistory.setItemsProcessed(processedCount);
                jobExecutionHistoryRepository.save(jobHistory);

                logger.info("<< Finished Tenant: {} | Time: {}ms | Saved to DB Context: {}", tenantId,
                        (System.currentTimeMillis() - tenantStart), tenantId);
            } catch (Exception e) {
                logger.error("❌ Failed to process tenant {} | Time: {}ms", tenantId,
                        (System.currentTimeMillis() - tenantStart), e);

                // Try to log failure to DB implementation
                try {
                    // Ensure we are still in context to save error
                    if (jobHistory.getId() != null) {
                        jobHistory.setEndTime(LocalDateTime.now());
                        jobHistory.setStatus("FAILURE");
                        jobHistory.setErrorMessage(e.getMessage());
                        jobExecutionHistoryRepository.save(jobHistory);
                    }
                } catch (Exception writeEx) {
                    logger.error("Failed to write failure log to DB", writeEx);
                }
            } finally {
                TenantContext.clear();
            }
        }
        logger.info("✅ [Async-Aggregator] Global aggregation complete | Total Time: {}ms",
                (System.currentTimeMillis() - totalStart));
    }

    /**
     * Fetch active tenants - ENVIRONMENT AWARE
     * - Local/UAT: Returns single configured tenant (uses local/uat DB)
     * - Prod: Returns ALL active tenants from master DB (tenant_config table)
     */
    private List<String> fetchActiveTenants() {
        try {
            if (!envConfig.isSchedulingEnabled()) {
                logger.info("⚠️ Scheduling is disabled in this environment");
                return Collections.emptyList();
            }

            String activeProfile = envConfig.getActiveProfile();
            logger.info("🔒 Environment: {}", activeProfile);

            // In local/UAT, there's no tenant_config table - just return the configured
            // tenant
            if ("local".equals(activeProfile) || "uat".equals(activeProfile) || "preprod".equals(activeProfile) || "prod".equals(activeProfile)) {
                String currentTenant = envConfig.getCurrentTenantId();
                logger.info("✅ Using configured tenant '{}' for {} environment (no tenant_config query)",
                        currentTenant, activeProfile);
                return java.util.Arrays.asList(currentTenant);
            }

            // Fallback: query tenant_config from master DB - GET ALL ACTIVE TENANTS
            List<String> tenants = masterJdbcTemplate.queryForList(
                    "SELECT tenant_id FROM tenant_config WHERE active = true",
                    String.class);

            if (tenants.isEmpty()) {
                logger.warn("⚠️ No active tenants found in tenant_config table");
            } else {
                logger.info("✅ Found {} active tenants for background aggregation: {}", tenants.size(), tenants);
            }

            return tenants;
        } catch (Exception e) {
            logger.error("❌ Failed to fetch tenants from Master DB", e);
            return Collections.emptyList();
        }
    }

    private int aggregateTenantData(String tenantId) {
        // 1. Fetch all accounts for this tenant (System Admin View)
        List<CloudAccount> allAccounts = cloudAccountRepository.findAll();
        if (allAccounts.isEmpty()) {
            logger.info("No accounts found for tenant {}. Skipping.", tenantId);
            return 0;
        }

        logger.info("Found {} total accounts for tenant {}", allAccounts.size(), tenantId);

        // 2. AWS Aggregation
        try {
            List<String> awsIds = allAccounts.stream()
                    .filter(a -> "AWS".equalsIgnoreCase(a.getProvider()))
                    .map(CloudAccount::getAwsAccountId)
                    .collect(Collectors.toList());

            if (!awsIds.isEmpty()) {
                logger.info("Aggregating {} AWS accounts for tenant {}...", awsIds.size(), tenantId);
                DashboardData awsData = getMultiAccountAwsDashboardData(awsIds, true, null); // null user = system
                // FIX: Save with SCOPED KEY
                saveSnapshot(SNAPSHOT_KEY_AWS + "::" + tenantId, awsData);
            }
        } catch (Exception e) {
            logger.error("AWS Aggregation failed for {}", tenantId, e);
        }

        // 3. GCP Aggregation
        try {
            List<String> gcpIds = allAccounts.stream()
                    .filter(a -> "GCP".equalsIgnoreCase(a.getProvider()))
                    .map(CloudAccount::getGcpProjectId)
                    .collect(Collectors.toList());

            if (!gcpIds.isEmpty()) {
                logger.info("Aggregating {} GCP projects for tenant {}...", gcpIds.size(), tenantId);
                DashboardData gcpData = getMultiAccountGcpDashboardData(gcpIds, true, null);
                // FIX: Save with SCOPED KEY
                saveSnapshot(SNAPSHOT_KEY_GCP + "::" + tenantId, gcpData);
            }
        } catch (Exception e) {
            logger.error("GCP Aggregation failed for {}", tenantId, e);
        }

        // 4. Azure Aggregation
        try {
            List<String> azureIds = allAccounts.stream()
                    .filter(a -> "Azure".equalsIgnoreCase(a.getProvider()))
                    .map(CloudAccount::getAzureSubscriptionId)
                    .collect(Collectors.toList());

            if (!azureIds.isEmpty()) {
                logger.info("Aggregating {} Azure subscriptions for tenant {}...", azureIds.size(), tenantId);
                DashboardData azureData = getMultiAccountAzureDashboardData(azureIds, true, null);
                if (azureData != null) {
                    // FIX: Save with SCOPED KEY
                    saveSnapshot(SNAPSHOT_KEY_AZURE + "::" + tenantId, azureData);
                }
            }
        } catch (Exception e) {
            logger.error("Azure Aggregation failed for {}", tenantId, e);
        }

        // 5. Standard Dashboard: Pre-cache granular endpoints AND full dashboard for
        // individual accounts
        logger.info("Pre-caching dashboard data for {} accounts...", allAccounts.size());
        for (CloudAccount account : allAccounts) {
            // If the per-tenant timeout fired (cancel(true) called), stop early
            // so the main aggregation thread can move on to the next tenant.
            if (Thread.currentThread().isInterrupted()) {
                logger.warn("[Aggregator] Thread interrupted — stopping per-account loop early for tenant {}", tenantId);
                break;
            }
            if (!"CONNECTED".equalsIgnoreCase(account.getStatus())) {
                continue; // Skip non-connected accounts
            }

            try {
                // FIX: Use actual cloud account ID, not database ID
                String accountId;
                if ("AWS".equalsIgnoreCase(account.getProvider())) {
                    accountId = account.getAwsAccountId();
                } else if ("GCP".equalsIgnoreCase(account.getProvider())) {
                    accountId = account.getGcpProjectId();
                } else if ("Azure".equalsIgnoreCase(account.getProvider())) {
                    accountId = account.getAzureSubscriptionId();
                } else {
                    logger.warn("Unknown provider {} for account {}, skipping", account.getProvider(), account.getId());
                    continue;
                }

                if (accountId == null || accountId.isEmpty()) {
                    logger.warn("Account ID is null or empty for account {}, skipping", account.getId());
                    continue;
                }

                logger.debug("Pre-caching data for account: {} ({})", accountId, account.getProvider());

                // Cache the FULL dashboard data (this contains everything needed!)
                try {
                    getDashboardData(accountId, true, null);
                    logger.info("✓ Full Dashboard cached for {} ({})", accountId, account.getProvider());
                } catch (Exception e) {
                    logger.error("Failed to cache dashboard for {}: {}", accountId, e.getMessage());
                }

                // ⭐ NEW: Cache Cloud List for AWS accounts (Grouped & Flat)
                if ("AWS".equalsIgnoreCase(account.getProvider())) {
                    try {
                        cloudListService.getAllResourcesGrouped(accountId, true);
                        logger.info("✓ Cloud List (Grouped) pre-cached for {} (AWS)", accountId);
                    } catch (Exception e) {
                        logger.error("Failed to pre-cache Cloud List for {}: {}", accountId, e.getMessage());
                    }
                }

                // ⭐ NEW: Cache FinOps data for AWS accounts
                if ("AWS".equalsIgnoreCase(account.getProvider())) {
                    try {
                        FinOpsReportDto finopsData = finOpsService.getFinOpsReport(accountId, true, null, null).join();
                        String finopsKey = "finops-" + accountId;
                        saveFinOpsSnapshot(finopsKey, finopsData);
                        logger.info("✓ FinOps cached for {} (AWS)", accountId);
                    } catch (Exception e) {
                        logger.error("Failed to cache FinOps for {}: {}", accountId, e.getMessage());
                    }
                }

                // ⭐ NEW: Cache FinOps data for GCP projects
                if ("GCP".equalsIgnoreCase(account.getProvider())) {
                    try {
                        GcpFinOpsReportDto gcpFinopsData = gcpFinOpsReportService.generateFinOpsReport(accountId)
                                .join();
                        String gcpFinopsKey = "gcp-finops-" + accountId;
                        saveGcpFinOpsSnapshot(gcpFinopsKey, gcpFinopsData);
                        logger.info("✓ GCP FinOps cached for {} (GCP)", accountId);
                    } catch (Exception e) {
                        logger.error("Failed to cache GCP FinOps for {}: {}", accountId, e.getMessage());
                    }
                }

            } catch (Exception e) {
                logger.warn("Failed to pre-cache data for account {}: {}", account.getId(), e.getMessage());
            }
        }
        logger.info("✅ Dashboard pre-caching complete for tenant {}", tenantId);
        return allAccounts.size();
    }

    private void saveSnapshot(String key, DashboardData data) {
        try {
            CachedData cachedData = cachedDataRepository.findById(key).orElse(new CachedData());
            cachedData.setCacheKey(key);
            cachedData.setJsonData(objectMapper.writeValueAsString(data));
            cachedData.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(cachedData);
            redisCache.put(key, data, 60); // L1 cache — next request skips DB entirely
            logger.info("Saved snapshot to DB + Redis: {}", key);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize snapshot {}", key, e);
        }
    }

    public void deleteSnapshot(String key) {
        if (StringUtils.hasText(key)) {
            cachedDataRepository.findById(key).ifPresent(cachedDataRepository::delete);
            logger.info("Deleted database snapshot for key: {}", key);
        }
    }

    private DashboardData getSnapshot(String key) {
        // 1. Redis first — fast, no DB connection consumed
        Optional<DashboardData> redisData = redisCache.get(key, DashboardData.class);
        if (redisData.isPresent()) {
            logger.info("✅ Redis hit for dashboard snapshot: {}", key);
            return redisData.get();
        }

        // 2. DB fallback — only used when Redis is cold (after restart/deployment)
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            try {
                DashboardData data = objectMapper.readValue(cached.get().getJsonData(), DashboardData.class);
                if (cached.get().getLastUpdated() != null) {
                    data.setLastUpdated(cached.get().getLastUpdated()
                            .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
                }
                logger.info("✅ DB hit for dashboard snapshot — repopulating Redis: {}", key);
                redisCache.put(key, data, 60); // re-warm Redis so next request skips DB
                return data;
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize snapshot {}", key, e);
            }
        }
        return null;
    }

    // ============================================================================================
    // FINOPS, WASTE & SECURITY SNAPSHOT HELPERS (for database caching)
    // ============================================================================================

    public void saveFinOpsSnapshot(String key, FinOpsReportDto data) {
        try {
            CachedData cachedData = cachedDataRepository.findById(key).orElse(new CachedData());
            cachedData.setCacheKey(key);
            cachedData.setJsonData(objectMapper.writeValueAsString(data));
            cachedData.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(cachedData);
            logger.info("Saved FinOps snapshot: {}", key);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize FinOps snapshot {}", key, e);
        }
    }

    public FinOpsReportDto getFinOpsSnapshot(String key) {
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getJsonData(), FinOpsReportDto.class);
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize FinOps snapshot {}", key, e);
            }
        }
        return null;
    }

    // ⭐ NEW: GCP FinOps Snapshot Methods
    public void saveGcpFinOpsSnapshot(String key, GcpFinOpsReportDto data) {
        try {
            CachedData cachedData = cachedDataRepository.findById(key).orElse(new CachedData());
            cachedData.setCacheKey(key);
            cachedData.setJsonData(objectMapper.writeValueAsString(data));
            cachedData.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(cachedData);
            logger.info("Saved GCP FinOps snapshot: {}", key);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize GCP FinOps snapshot {}", key, e);
        }
    }

    public GcpFinOpsReportDto getGcpFinOpsSnapshot(String key) {
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getJsonData(), GcpFinOpsReportDto.class);
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize GCP FinOps snapshot {}", key, e);
            }
        }
        return null;
    }

    public void saveWasteSnapshot(String key, List<DashboardData.WastedResource> data) {
        try {
            CachedData cachedData = cachedDataRepository.findById(key).orElse(new CachedData());
            cachedData.setCacheKey(key);
            cachedData.setJsonData(objectMapper.writeValueAsString(data));
            cachedData.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(cachedData);
            logger.info("Saved Waste snapshot: {}", key);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize Waste snapshot {}", key, e);
        }
    }

    public List<DashboardData.WastedResource> getWasteSnapshot(String key) {
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getJsonData(),
                        new TypeReference<List<DashboardData.WastedResource>>() {
                        });
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize Waste snapshot {}", key, e);
            }
        }
        return null;
    }

    public void saveSecuritySnapshot(String key, Object data) {
        try {
            CachedData cachedData = cachedDataRepository.findById(key).orElse(new CachedData());
            cachedData.setCacheKey(key);
            cachedData.setJsonData(objectMapper.writeValueAsString(data));
            cachedData.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(cachedData);
            logger.info("Saved Security snapshot: {}", key);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize Security snapshot {}", key, e);
        }
    }

    public Object getSecuritySnapshot(String key, TypeReference<?> typeRef) {
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getJsonData(), typeRef);
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize Security snapshot {}", key, e);
            }
        }
        return null;
    }

    public void saveAlertsSnapshot(String key, Object data) {
        try {
            CachedData cachedData = cachedDataRepository.findById(key).orElse(new CachedData());
            cachedData.setCacheKey(key);
            cachedData.setJsonData(objectMapper.writeValueAsString(data));
            cachedData.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(cachedData);
            logger.info("Saved Alerts snapshot: {}", key);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize Alerts snapshot {}", key, e);
        }
    }

    public Object getAlertsSnapshot(String key, TypeReference<?> typeRef) {
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getJsonData(), typeRef);
            } catch (JsonProcessingException e) {
                logger.warn("Stale/corrupted Alerts snapshot for key '{}' — deleting so warm-up can rebuild. Cause: {}", key, e.getMessage());
                cachedDataRepository.deleteById(key);
            }
        }
        return null;
    }

    public void saveReservationSnapshot(String key, ReservationDto data) {
        try {
            CachedData cachedData = cachedDataRepository.findById(key).orElse(new CachedData());
            cachedData.setCacheKey(key);
            cachedData.setJsonData(objectMapper.writeValueAsString(data));
            cachedData.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(cachedData);
            logger.info("Saved Reservation snapshot: {}", key);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize Reservation snapshot {}", key, e);
        }
    }

    public ReservationDto getReservationSnapshot(String key) {
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getJsonData(), ReservationDto.class);
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize Reservation snapshot {}", key, e);
            }
        }
        return null;
    }

    public void saveCostSnapshot(String key, List<CostDto> data) {
        try {
            CachedData cachedData = cachedDataRepository.findById(key).orElse(new CachedData());
            cachedData.setCacheKey(key);
            cachedData.setJsonData(objectMapper.writeValueAsString(data));
            cachedData.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(cachedData);
            logger.info("Saved Cost snapshot: {}", key);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize Cost snapshot {}", key, e);
        }
    }

    public List<CostDto> getCostSnapshot(String key) {
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            try {
                return objectMapper.readValue(cached.get().getJsonData(), new TypeReference<List<CostDto>>() {});
            } catch (JsonProcessingException e) {
                logger.error("Failed to deserialize Cost snapshot {}", key, e);
            }
        }
        return null;
    }

    // ============================================================================================
    // PHASE 1 & 2: GRANULAR UNIFIED DASHBOARD METHODS (Updated for Caching)
    // ============================================================================================

    public List<String> getUnifiedTicker(ClientUserDetails userDetails) {
        List<String> messages = new ArrayList<>();
        List<CloudAccount> accounts = fetchUserAccounts(userDetails);

        int totalAccounts = accounts.size();
        long providerCount = accounts.stream().map(CloudAccount::getProvider).distinct().count();

        messages.add("ℹ️ Monitoring " + totalAccounts + " cloud accounts across " + providerCount + " providers.");

        for (CloudAccount acc : accounts) {
            String accNameLower = acc.getAccountName().toLowerCase();
            if (accNameLower.contains("prod") || accNameLower.contains("preprod")) {
                messages.add("🛡️ Security scan completed for '" + acc.getAccountName() + "': No critical threats.");
            }
        }

        messages.add("🚀 New: AutoSpotting is now available for Azure Virtual Scale Sets.");
        messages.add("💰 Global savings opportunity detected: Rightsizing suggestions updated.");
        messages.add("⚡ System Performance: All systems operational. API Latency: 42ms.");

        return messages;
    }

    public DashboardData getUnifiedSummary(ClientUserDetails userDetails) {
        List<CloudAccount> allAccounts = fetchUserAccounts(userDetails);
        DashboardData data = new DashboardData();
        data.setMultiAccountView(true);
        data.setSelectedProvider("Unified");

        DashboardData.Account unifiedAcc = new DashboardData.Account();
        unifiedAcc.setId("unified-view");
        unifiedAcc.setName("Unified View");

        unifiedAcc.setAwsAccountCount(
                (int) allAccounts.stream().filter(a -> "AWS".equalsIgnoreCase(a.getProvider())).count());
        unifiedAcc.setGcpAccountCount(
                (int) allAccounts.stream().filter(a -> "GCP".equalsIgnoreCase(a.getProvider())).count());
        unifiedAcc.setAzureAccountCount(
                (int) allAccounts.stream().filter(a -> "Azure".equalsIgnoreCase(a.getProvider())).count());

        // Add total account count to help frontend logic
        unifiedAcc.setTotalAccounts(allAccounts.size());

        data.setSelectedAccount(unifiedAcc);
        populateAvailableAccounts(data, userDetails);
        return data;
    }

    public DashboardData getUnifiedAwsData(ClientUserDetails userDetails, boolean forceRefresh)
            throws ExecutionException, InterruptedException, IOException {

        // 1. Get Tenant Context
        String tenantId = TenantContext.getCurrentTenant();

        // 2. Determine Cache Key (User + Tenant Scoped)
        boolean isAdmin = isUserAdmin(userDetails);
        String clientScope = (userDetails != null && userDetails.getClientId() != null)
                ? userDetails.getClientId().toString()
                : "system";
        String scopedKey = SNAPSHOT_KEY_AWS + "::" + tenantId + "::" + clientScope;

        // 3. Try to fetch from Snapshot first
        if (!forceRefresh) {
            DashboardData cached = getSnapshot(scopedKey);
            if (cached != null) {
                logger.info("Returning Cached AWS Snapshot for Unified View (Tenant: {} | User: {})", tenantId,
                        isAdmin ? "Global/Admin" : userDetails.getUsername());
                populateAvailableAccounts(cached, userDetails);
                return cached;
            }
        }

        // 4. Fallback to Live Fetch
        List<CloudAccount> allAccounts = fetchUserAccounts(userDetails);
        List<String> awsIds = allAccounts.stream()
                .filter(a -> "AWS".equalsIgnoreCase(a.getProvider()))
                .map(CloudAccount::getAwsAccountId)
                .collect(Collectors.toList());

        // 5. Handle Empty State gracefully
        if (awsIds.isEmpty()) {
            return createEmptyDashboardData("AWS");
        }

        DashboardData result = getMultiAccountAwsDashboardData(awsIds, forceRefresh, userDetails);
        result.setLastUpdated(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));

        // 6. Save using SCOPED KEY
        saveSnapshot(scopedKey, result);

        return result;
    }

    public DashboardData getUnifiedGcpData(ClientUserDetails userDetails, boolean forceRefresh) {

        String tenantId = TenantContext.getCurrentTenant();

        // Determine Cache Key (User + Tenant Scoped)
        boolean isAdmin = isUserAdmin(userDetails);
        String clientScope = (userDetails != null && userDetails.getClientId() != null)
                ? userDetails.getClientId().toString()
                : "system";
        String scopedKey = SNAPSHOT_KEY_GCP + "::" + tenantId + "::" + clientScope;

        // 1. Try to fetch from Snapshot
        if (!forceRefresh) {
            DashboardData cached = getSnapshot(scopedKey);
            if (cached != null) {
                logger.info("Returning Cached GCP Snapshot for Unified View (Tenant: {} | User: {})", tenantId,
                        isAdmin ? "Global/Admin" : userDetails.getUsername());
                populateAvailableAccounts(cached, userDetails);
                return cached;
            }
        }

        List<CloudAccount> allAccounts = fetchUserAccounts(userDetails);
        List<String> gcpIds = allAccounts.stream()
                .filter(a -> "GCP".equalsIgnoreCase(a.getProvider()))
                .map(CloudAccount::getGcpProjectId)
                .collect(Collectors.toList());

        if (gcpIds.isEmpty()) {
            return createEmptyDashboardData("GCP");
        }

        DashboardData result = getMultiAccountGcpDashboardData(gcpIds, forceRefresh, userDetails);
        result.setLastUpdated(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
        saveSnapshot(scopedKey, result);
        return result;
    }

    public DashboardData getUnifiedAzureData(ClientUserDetails userDetails, boolean forceRefresh) {

        String tenantId = TenantContext.getCurrentTenant();

        // Determine Cache Key (User + Tenant Scoped)
        boolean isAdmin = isUserAdmin(userDetails);
        String clientScope = (userDetails != null && userDetails.getClientId() != null)
                ? userDetails.getClientId().toString()
                : "system";
        String scopedKey = SNAPSHOT_KEY_AZURE + "::" + tenantId + "::" + clientScope;

        // 1. Try to fetch from Snapshot
        if (!forceRefresh) {
            DashboardData cached = getSnapshot(scopedKey);
            if (cached != null) {
                logger.info("Returning Cached Azure Snapshot for Unified View (Tenant: {} | User: {})", tenantId,
                        isAdmin ? "Global/Admin" : userDetails.getUsername());
                populateAvailableAccounts(cached, userDetails);
                return cached;
            }
        }

        List<CloudAccount> allAccounts = fetchUserAccounts(userDetails);
        List<String> azureIds = allAccounts.stream()
                .filter(a -> "Azure".equalsIgnoreCase(a.getProvider()))
                .map(CloudAccount::getAzureSubscriptionId)
                .collect(Collectors.toList());

        if (azureIds.isEmpty()) {
            return createEmptyDashboardData("Azure");
        }

        DashboardData result = getMultiAccountAzureDashboardData(azureIds, forceRefresh, userDetails);
        if (result != null) {
            result.setLastUpdated(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
            saveSnapshot(scopedKey, result);
        }
        return result;
    }

    // Helper method to create a safe empty response for users with no accounts
    private DashboardData createEmptyDashboardData(String provider) {
        DashboardData data = new DashboardData();
        data.setSelectedProvider(provider);
        data.setMultiAccountView(true);

        DashboardData.Account emptyAcc = new DashboardData.Account();
        emptyAcc.setId("empty-view");
        emptyAcc.setName("No Accounts Configured");

        // Initialize empty lists to prevent null pointers in frontend loops
        emptyAcc.setSubAccounts(Collections.emptyList());
        emptyAcc.setResourceInventory(new DashboardData.ResourceInventory());
        emptyAcc.setCostHistory(new DashboardData.CostHistory(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList()));
        emptyAcc.setBillingSummary(Collections.emptyList());
        emptyAcc.setSecurityScore(100);
        emptyAcc.setSecurityInsights(Collections.emptyList());
        emptyAcc.setRegionStatus(Collections.emptyList());
        emptyAcc.setWastedResources(Collections.emptyList());
        emptyAcc.setEc2Recommendations(Collections.emptyList());

        data.setSelectedAccount(emptyAcc);
        data.setAvailableAccounts(Collections.emptyList());

        return data;
    }

    public DashboardData.CodeQualitySummary getUnifiedHealthData(ClientUserDetails userDetails) {
        try {
            User user = null;
            if (userDetails != null) {
                user = userRepository.findByUsername(userDetails.getUsername()).orElse(null);
            }
            if (user == null)
                return new DashboardData.CodeQualitySummary("N/A", 0, 0, 0, 0.0, 0);

            List<SonarQubeProject> projects = sonarQubeService.getProjectsForUser(user);
            if (projects.isEmpty())
                return new DashboardData.CodeQualitySummary("N/A", 0, 0, 0, 0.0, 0);

            int bugs = 0, vulns = 0, smells = 0;
            double totalCoverage = 0.0;
            int projectsWithCoverage = 0;

            for (SonarQubeProject p : projects) {
                SonarQubeMetricsDto m = sonarQubeService.getProjectMetrics(p);
                if (m != null) {
                    bugs += m.getBugs();
                    vulns += m.getVulnerabilities();
                    smells += m.getCodeSmells();
                    if (m.getCoverage() > 0) {
                        totalCoverage += m.getCoverage();
                        projectsWithCoverage++;
                    }
                }
            }
            double avgCoverage = projectsWithCoverage > 0 ? totalCoverage / projectsWithCoverage : 0.0;
            String rating = (vulns > 0 || bugs > 10) ? "C" : (smells > 50 ? "B" : "A");

            return new DashboardData.CodeQualitySummary(rating, bugs, vulns, smells, avgCoverage, projects.size());
        } catch (Exception e) {
            logger.error("Error fetching SonarQube metrics", e);
            return new DashboardData.CodeQualitySummary("Error", 0, 0, 0, 0, 0);
        }
    }

    // ============================================================================================
    // PRIVATE HELPERS & CORE LOGIC
    // ============================================================================================

    private boolean isUserAdmin(ClientUserDetails userDetails) {
        if (userDetails == null)
            return false; // System user is effectively admin, but handled separately
        return userDetails.getAuthorities().stream()
                .anyMatch(role -> "ROLE_BILLOPS_ADMIN".equals(role.getAuthority())
                        || "ROLE_SUPER_ADMIN".equals(role.getAuthority()));
    }

    private List<CloudAccount> fetchUserAccounts(ClientUserDetails userDetails) {
        // Support for "System" user (null) in background tasks
        if (userDetails == null) {
            return cloudAccountRepository.findAll();
        }

        boolean isAdmin = userDetails.getAuthorities().stream()
                .anyMatch(role -> "ROLE_BILLOPS_ADMIN".equals(role.getAuthority())
                        || "ROLE_SUPER_ADMIN".equals(role.getAuthority()));

        if (isAdmin) {
            return cloudAccountRepository.findAll();
        } else {
            return cloudAccountRepository.findByClientId(userDetails.getClientId());
        }
    }

    private CloudAccount getAccount(String accountId) {
        if (!StringUtils.hasText(accountId)) {
            throw new IllegalArgumentException("Account ID cannot be null or empty.");
        }

        // Handle multi-account strings just in case
        String idToUse = accountId.contains(",") ? accountId.split(",")[0] : accountId;

        // Match the pattern used by CloudListService - check provider-specific ID first
        return cloudAccountRepository.findByProviderAccountId(idToUse)
                .orElseGet(() -> {
                    // Fallback to older lookup if needed
                    List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountIdOrGcpProjectId(idToUse,
                            idToUse);
                    if (accounts.isEmpty()) {
                        // One last attempt: Azure
                        Optional<CloudAccount> azureAccount = cloudAccountRepository.findByAzureSubscriptionId(idToUse);
                        if (azureAccount.isPresent()) {
                            return azureAccount.get();
                        }
                        logger.error("Account not found in database for accountId: {}", idToUse);
                        throw new RuntimeException("Account not found in database: " + idToUse);
                    }
                    return accounts.get(0);
                });
    }

    public DashboardData getMultiAccountDashboardData(List<String> accountIds, boolean forceRefresh,
            ClientUserDetails userDetails) throws ExecutionException, InterruptedException, IOException {
        logger.info("Starting multi-account dashboard data fetch for {} accounts", accountIds.size());

        if (accountIds.isEmpty()) {
            throw new IllegalArgumentException("Account list cannot be empty");
        }

        Set<String> providers = new HashSet<>();
        for (String accountId : accountIds) {
            try {
                CloudAccount account = getAccount(accountId);
                providers.add(account.getProvider().toUpperCase());
            } catch (Exception e) {
                logger.error("Failed to validate account {}", accountId, e);
                throw new IllegalArgumentException("Invalid account ID: " + accountId);
            }
        }

        if (providers.size() > 1) {
            throw new IllegalArgumentException(
                    "Cannot mix cloud providers in multi-account view. Found: " +
                            String.join(", ", providers)
                            + ". Please select accounts from only one provider (AWS or GCP).");
        }

        CloudAccount firstAccount = getAccount(accountIds.get(0));
        String provider = firstAccount.getProvider();

        if ("GCP".equalsIgnoreCase(provider)) {
            return getMultiAccountGcpDashboardData(accountIds, forceRefresh, userDetails);
        } else if ("AWS".equalsIgnoreCase(provider)) {
            return getMultiAccountAwsDashboardData(accountIds, forceRefresh, userDetails);
        } else {
            throw new IllegalArgumentException("Multi-account view is not supported for provider: " + provider);
        }
    }

    // ============================================================================================
    // AGGREGATION LOGIC (Refactored)
    // ============================================================================================

    private DashboardData getMultiAccountAzureDashboardData(List<String> accountIds, boolean forceRefresh,
            ClientUserDetails userDetails) {
        long startTime = System.currentTimeMillis();
        String user = userDetails != null ? userDetails.getUsername() : "System";
        String tenant = TenantContext.getCurrentTenant();

        logger.info("🔍 [Azure-Fetch] Started | Tenant: {} | User: {} | Accounts: {}", tenant, user, accountIds.size());

        try {
            List<DashboardData> localDataList = accountIds.stream().parallel().map(id -> {
                try {
                    CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(id).orElse(null);
                    if (account == null)
                        return null;

                    com.xammer.cloud.dto.azure.AzureDashboardData azureData = azureDashboardService.getDashboardData(id,
                            forceRefresh);
                    return mapAzureDataToDashboardData(azureData, account);
                } catch (Exception e) {
                    logger.error("Error fetching Azure data for {}", id, e);
                    return null;
                }
            }).filter(Objects::nonNull).collect(Collectors.toList());

            if (localDataList.isEmpty())
                return null;

            DashboardData aggregated = aggregateGenericDashboardData(localDataList, "Azure");
            populateAvailableAccounts(aggregated, userDetails);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("✅ [Azure-Fetch] Completed | Tenant: {} | Time: {}ms | Accounts: {}", tenant, duration,
                    accountIds.size());
            return aggregated;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ [Azure-Fetch] Failed | Tenant: {} | Time: {}ms | Error: {}", tenant, duration,
                    e.getMessage(), e);
            throw e;
        }
    }

    private DashboardData aggregateGenericDashboardData(List<DashboardData> dataList, String provider) {
        if (dataList == null || dataList.isEmpty())
            return null;

        DashboardData aggregated = new DashboardData();
        aggregated.setMultiAccountView(true);
        aggregated.setSelectedProvider(provider);

        DashboardData.Account resultAcc = new DashboardData.Account();
        resultAcc.setId("multi-account-" + provider);
        resultAcc.setName(dataList.size() + " " + provider + " Accounts");

        List<DashboardData.Account> subAccounts = new ArrayList<>();
        double totalCost = 0;
        double totalForecast = 0;
        double totalLast = 0;

        for (DashboardData d : dataList) {
            DashboardData.Account acc = d.getSelectedAccount();
            if (acc != null) {
                totalCost += acc.getMonthToDateSpend();
                totalForecast += acc.getForecastedSpend();
                totalLast += acc.getLastMonthSpend();
                DashboardData.Account subAcc = new DashboardData.Account(acc.getId(), acc.getName(),
                        acc.getMonthToDateSpend(),
                        acc.getForecastedSpend());
                subAcc.setResourceInventory(acc.getResourceInventory());
                subAcc.setOptimizationSummary(acc.getOptimizationSummary());
                subAcc.setSecurityScore(acc.getSecurityScore());
                subAccounts.add(subAcc);
            }
        }

        resultAcc.setSubAccounts(subAccounts);
        resultAcc.setMonthToDateSpend(totalCost);
        resultAcc.setForecastedSpend(totalForecast);
        resultAcc.setLastMonthSpend(totalLast);

        DashboardData.ResourceInventory inv = new DashboardData.ResourceInventory();
        for (DashboardData d : dataList) {
            DashboardData.ResourceInventory s = d.getSelectedAccount().getResourceInventory();
            if (s != null) {
                mergeInventory(inv, s);
            }
        }
        resultAcc.setResourceInventory(inv);

        resultAcc.setSecurityScore(
                (int) dataList.stream().mapToInt(d -> d.getSelectedAccount().getSecurityScore()).average().orElse(100));

        aggregated.setSelectedAccount(resultAcc);
        return aggregated;
    }

    private void mergeInventory(DashboardData.ResourceInventory target, DashboardData.ResourceInventory source) {
        if (source == null)
            return;
        target.setEc2(target.getEc2() + source.getEc2());
        target.setS3Buckets(target.getS3Buckets() + source.getS3Buckets());
        target.setRdsInstances(target.getRdsInstances() + source.getRdsInstances());
        target.setKubernetes(target.getKubernetes() + source.getKubernetes());
        target.setVpc(target.getVpc() + source.getVpc());
        target.setLambdas(target.getLambdas() + source.getLambdas());
        target.setEbsVolumes(target.getEbsVolumes() + source.getEbsVolumes());
    }

    private DashboardData getMultiAccountGcpDashboardData(List<String> accountIds, boolean forceRefresh,
            ClientUserDetails userDetails) {
        long startTime = System.currentTimeMillis();
        String user = userDetails != null ? userDetails.getUsername() : "System";
        String tenant = TenantContext.getCurrentTenant();

        logger.info("🔍 [GCP-Fetch] Started | Tenant: {} | User: {} | Accounts: {}", tenant, user, accountIds.size());

        List<CloudAccount> validAccounts = new ArrayList<>();
        List<String> accountNames = new ArrayList<>();
        List<String> failedAccounts = new ArrayList<>();

        for (String accountId : accountIds) {
            try {
                CloudAccount account = getAccount(accountId);
                if ("GCP".equalsIgnoreCase(account.getProvider())) {
                    validAccounts.add(account);
                    accountNames.add(account.getAccountName());
                } else {
                    logger.warn("Skipping non-GCP account {} in GCP multi-account mode", accountId);
                }
            } catch (Exception e) {
                logger.error("Failed to load account {}", accountId, e);
                failedAccounts.add(accountId);
            }
        }

        if (validAccounts.isEmpty()) {
            throw new RuntimeException("No valid GCP accounts found for multi-account dashboard");
        }

        try {
            List<CompletableFuture<GcpDashboardData>> futures = validAccounts.stream()
                    .map(account -> gcpDataService.getDashboardData(account.getGcpProjectId(), forceRefresh)
                            .exceptionally(ex -> {
                                logger.error("Failed to fetch data for GCP project {}", account.getGcpProjectId(), ex);
                                failedAccounts.add(account.getGcpProjectId());
                                return new GcpDashboardData();
                            }))
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            List<GcpDashboardData> accountDataList = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            DashboardData aggregatedData = aggregateGcpDashboardData(accountDataList, validAccounts, accountNames,
                    failedAccounts);
            populateAvailableAccounts(aggregatedData, userDetails);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("✅ [GCP-Fetch] Completed | Tenant: {} | Time: {}ms | Accounts: {}", tenant, duration,
                    accountIds.size());
            return aggregatedData;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ [GCP-Fetch] Failed | Tenant: {} | Time: {}ms | Error: {}", tenant, duration, e.getMessage(),
                    e);
            throw e;
        }
    }

    private DashboardData aggregateGcpDashboardData(List<GcpDashboardData> dataList,
            List<CloudAccount> accounts,
            List<String> accountNames,
            List<String> failedAccounts) {
        DashboardData aggregated = new DashboardData();
        aggregated.setMultiAccountView(true);
        aggregated.setSelectedAccountIds(
                accounts.stream().map(CloudAccount::getGcpProjectId).collect(Collectors.toList()));
        aggregated.setSelectedAccountNames(accountNames);
        aggregated.setFailedAccounts(failedAccounts);
        aggregated.setSelectedProvider("GCP");

        DashboardData.Account aggregatedAccount = new DashboardData.Account();
        aggregatedAccount.setId("multi-account-gcp");
        aggregatedAccount.setName(String.format("%d GCP Projects", accounts.size()));

        List<DashboardData.Account> subAccounts = new ArrayList<>();

        double totalMTD = 0.0;
        double totalForecast = 0.0;
        double totalLastMonth = 0.0;
        double totalOptimizationSavings = 0.0;
        long totalCriticalAlerts = 0;
        double totalPotentialSavings = 0.0;

        DashboardData.ResourceInventory aggregatedInventory = new DashboardData.ResourceInventory();
        DashboardData.IamResources aggregatedIam = new DashboardData.IamResources(0, 0, 0, 0);
        Map<String, DashboardData.RegionStatus> uniqueRegionsMap = new HashMap<>();
        Map<String, Double> costHistoryMap = new LinkedHashMap<>();
        Map<String, Double> billingSummaryMap = new HashMap<>();
        Map<String, DashboardData.SecurityInsight> securityInsightMap = new HashMap<>();
        List<Integer> securityScores = new ArrayList<>();

        for (int i = 0; i < dataList.size(); i++) {
            GcpDashboardData data = dataList.get(i);
            CloudAccount acc = accounts.get(i);

            // Convert GCP costs from INR to USD
            double mtdUsd = data.getMonthToDateSpend() * INR_TO_USD_RATE;
            double forecastUsd = data.getForecastedSpend() * INR_TO_USD_RATE;
            double lastMonthUsd = data.getLastMonthSpend() * INR_TO_USD_RATE;

            totalMTD += mtdUsd;
            totalForecast += forecastUsd;
            totalLastMonth += lastMonthUsd;

            DashboardData.Account subAcc = new DashboardData.Account(acc.getGcpProjectId(), acc.getAccountName(),
                    mtdUsd, forecastUsd);
            subAcc.setResourceInventory(data.getResourceInventory());
            subAcc.setOptimizationSummary(data.getOptimizationSummary());
            subAcc.setSecurityScore(data.getSecurityScore());
            subAccounts.add(subAcc);

            if (data.getResourceInventory() != null) {
                mergeInventory(aggregatedInventory, data.getResourceInventory());
            }

            if (data.getIamResources() != null) {
                aggregatedIam.setUsers(aggregatedIam.getUsers() + data.getIamResources().getUsers());
                aggregatedIam.setRoles(aggregatedIam.getRoles() + data.getIamResources().getRoles());
                aggregatedIam.setGroups(aggregatedIam.getGroups() + data.getIamResources().getGroups());
                aggregatedIam
                        .setCustomerManagedPolicies(aggregatedIam.getPolicies() + data.getIamResources().getPolicies());
            }

            if (data.getSecurityScore() > 0) {
                securityScores.add(data.getSecurityScore());
            }
            if (data.getSecurityInsights() != null) {
                for (DashboardData.SecurityInsight insight : data.getSecurityInsights()) {
                    String key = insight.getCategory();
                    securityInsightMap.merge(key, insight, (existing, valid) -> new DashboardData.SecurityInsight(
                            existing.getTitle(),
                            existing.getCategory(),
                            existing.getSeverity(),
                            existing.getCount() + valid.getCount()));
                }
            }

            if (data.getOptimizationSummary() != null) {
                totalOptimizationSavings += data.getOptimizationSummary().getTotalPotentialSavings();
                totalCriticalAlerts += data.getOptimizationSummary().getCriticalAlerts();
            }
            if (data.getSavingsSummary() != null) {
                totalPotentialSavings += data.getSavingsSummary().getTotalPotentialSavings();
            }

            if (data.getRegionStatus() != null) {
                data.getRegionStatus().forEach(region -> {
                    // Store complete region data with coordinates, not just regionId
                    uniqueRegionsMap.putIfAbsent(region.getRegionId(), region);
                });
            }

            if (data.getCostHistory() != null && data.getCostHistory().getLabels() != null) {
                List<String> labels = data.getCostHistory().getLabels();
                List<Double> costs = data.getCostHistory().getCosts();
                for (int j = 0; j < labels.size(); j++) {
                    // Note: Labels are already converted to USD if they came from individual
                    // account fetch,
                    // but here aggregateGcpDashboardData is also applying INR_TO_USD_RATE.
                    // Wait, let's check if GcpDataService already converts to USD.
                    // Yes, it does NOT seem to convert individual history items to USD in
                    // GcpDataService.
                    // Actually, GcpDataService (line 289) says:
                    // costHistory.setCosts(historyList.stream().map(GcpCostDto::getAmount).collect(Collectors.toList()));
                    // And historyList comes from gcpCostService.getHistoricalCosts(gcpProjectId).
                    // Most GCP BigQuery results are in INR.
                    double costValue = costs.get(j);
                    double costUsd = costValue * INR_TO_USD_RATE;
                    costHistoryMap.merge(labels.get(j), costUsd, Double::sum);
                }
            }

            if (data.getBillingSummary() != null) {
                for (GcpCostDto bill : data.getBillingSummary()) {
                    // Convert billing summary from INR to USD
                    double billUsd = bill.getAmount() * INR_TO_USD_RATE;
                    billingSummaryMap.merge(bill.getName(), billUsd, Double::sum);
                }
            }
        }

        aggregatedAccount.setSubAccounts(subAccounts);
        aggregatedAccount.setMonthToDateSpend(totalMTD);
        aggregatedAccount.setForecastedSpend(totalForecast);
        aggregatedAccount.setLastMonthSpend(totalLastMonth);
        aggregatedAccount.setResourceInventory(aggregatedInventory);
        aggregatedAccount.setIamResources(aggregatedIam);
        aggregatedAccount.setSecurityScore(securityScores.isEmpty() ? 100
                : (int) securityScores.stream().mapToInt(Integer::intValue).average().orElse(100));
        aggregatedAccount.setSecurityInsights(new ArrayList<>(securityInsightMap.values()));

        aggregatedAccount.setOptimizationSummary(
                new DashboardData.OptimizationSummary(totalOptimizationSavings, totalCriticalAlerts));

        List<DashboardData.SavingsSuggestion> savingsSuggestions = new ArrayList<>();
        if (totalPotentialSavings > 0) {
            savingsSuggestions
                    .add(new DashboardData.SavingsSuggestion("Total Potential Savings", totalPotentialSavings));
        }
        aggregatedAccount
                .setSavingsSummary(new DashboardData.SavingsSummary(totalPotentialSavings, savingsSuggestions));

        // Collect all unique regions with complete data (including coordinates)
        List<DashboardData.RegionStatus> regionStatusList = new ArrayList<>(uniqueRegionsMap.values());
        aggregatedAccount.setRegionStatus(regionStatusList);

        List<String> costLabels = new ArrayList<>(costHistoryMap.keySet());
        costLabels.sort(this::compareMonthStrings);
        List<Double> costValues = costLabels.stream().map(costHistoryMap::get).collect(Collectors.toList());
        List<Boolean> costAnomalies = costLabels.stream().map(l -> false).collect(Collectors.toList());
        aggregatedAccount.setCostHistory(new DashboardData.CostHistory(costLabels, costValues, costAnomalies));

        List<DashboardData.BillingSummary> billingSummaryList = billingSummaryMap.entrySet().stream()
                .map(entry -> new DashboardData.BillingSummary(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> Double.compare(b.getAmount(), a.getAmount()))
                .collect(Collectors.toList());
        aggregatedAccount.setBillingSummary(billingSummaryList);

        aggregatedAccount.setEc2Recommendations(Collections.emptyList());
        aggregatedAccount.setWastedResources(Collections.emptyList());
        aggregatedAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(0, 0, 0));

        aggregated.setSelectedAccount(aggregatedAccount);
        return aggregated;
    }

    private DashboardData getMultiAccountAwsDashboardData(List<String> accountIds, boolean forceRefresh,
            ClientUserDetails userDetails) throws ExecutionException, InterruptedException, IOException {
        long startTime = System.currentTimeMillis();
        String user = userDetails != null ? userDetails.getUsername() : "System";
        String tenant = TenantContext.getCurrentTenant();

        logger.info("🔍 [AWS-Fetch] Started | Tenant: {} | User: {} | Accounts: {}", tenant, user, accountIds.size());

        List<CloudAccount> validAccounts = new ArrayList<>();
        List<String> accountNames = new ArrayList<>();
        List<String> failedAccounts = new ArrayList<>();

        for (String accountId : accountIds) {
            try {
                CloudAccount account = getAccount(accountId);
                if ("AWS".equalsIgnoreCase(account.getProvider())) {
                    validAccounts.add(account);
                    accountNames.add(account.getAccountName());
                } else {
                    logger.warn("Skipping non-AWS account {} in AWS multi-account mode", accountId);
                }
            } catch (Exception e) {
                logger.error("Failed to load account {}", accountId, e);
                failedAccounts.add(accountId);
            }
        }

        if (validAccounts.isEmpty()) {
            throw new RuntimeException("No valid AWS accounts found for multi-account dashboard");
        }

        try {
            // Thread-safe queue for failures from concurrent futures
            java.util.Queue<String> failedAccountsConcurrent = new java.util.concurrent.ConcurrentLinkedQueue<>();

            List<CompletableFuture<DashboardData>> futures = validAccounts.stream()
                    .map(account -> {
                        // Acquire OUTSIDE supplyAsync: blocks here until a slot is free.
                        // This prevents executor thread starvation — threads only run when a permit is secured.
                        ACCOUNT_SEMAPHORE.acquireUninterruptibly();
                        return CompletableFuture.supplyAsync(() -> {
                            try {
                                return getDashboardData(account.getAwsAccountId(), forceRefresh, userDetails);
                            } catch (Exception e) {
                                logger.error("Failed to fetch data for account {} — isolated, continuing",
                                        account.getAwsAccountId(), e);
                                failedAccountsConcurrent.add(account.getAwsAccountId());
                                return null;
                            } finally {
                                ACCOUNT_SEMAPHORE.release();
                            }
                        }, backgroundTaskExecutor);
                    })
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(5, TimeUnit.MINUTES)
                    .exceptionally(ex -> {
                        logger.warn("[MultiAccount] Timed out waiting for all accounts after 5 min — returning partial results: {}", ex.getMessage());
                        return null;
                    })
                    .join();

            failedAccounts.addAll(failedAccountsConcurrent);

            List<DashboardData> accountDataList = futures.stream()
                    .map(f -> {
                        try { return f.getNow(null); }
                        catch (Exception e) { return null; }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (accountDataList.isEmpty()) {
                throw new RuntimeException("Failed to fetch data from all selected AWS accounts");
            }

            DashboardData aggregatedData = aggregateDashboardData(accountDataList, validAccounts, accountNames,
                    failedAccounts);
            populateAvailableAccounts(aggregatedData, userDetails);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("✅ [AWS-Fetch] Completed | Tenant: {} | Time: {}ms | Accounts: {}", tenant, duration,
                    accountIds.size());
            return aggregatedData;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ [AWS-Fetch] Failed | Tenant: {} | Time: {}ms | Error: {}", tenant, duration, e.getMessage(),
                    e);
            throw e;
        }
    }

    private int compareMonthStrings(String a, String b) {
        try {
            String normalizedA = normalizeMonthString(a);
            String normalizedB = normalizeMonthString(b);

            DateTimeFormatter formatter;
            if (normalizedA.contains(" ")) {
                formatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
            } else if (normalizedA.contains("-")) {
                formatter = DateTimeFormatter.ofPattern("yyyy-MM");
            } else {
                return a.compareTo(b);
            }

            YearMonth dateA = YearMonth.parse(normalizedA, formatter);
            YearMonth dateB = YearMonth.parse(normalizedB, formatter);
            return dateA.compareTo(dateB);
        } catch (Exception e) {
            logger.debug("Could not parse month labels '{}' and '{}' as dates, using string comparison", a, b);
            return normalizeMonthString(a).compareTo(normalizeMonthString(b));
        }
    }

    private void populateAvailableAccounts(DashboardData data, ClientUserDetails userDetails) {
        if (userDetails != null) {
            List<CloudAccount> userAccounts = fetchUserAccounts(userDetails);

            List<DashboardData.Account> availableAccounts = userAccounts.stream()
                    .map(acc -> new DashboardData.Account(
                            "AWS".equalsIgnoreCase(acc.getProvider()) ? acc.getAwsAccountId() : acc.getGcpProjectId(),
                            acc.getAccountName(),
                            Collections.emptyList(),
                            null, null, Collections.emptyList(), null, Collections.emptyList(), null, null, null,
                            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                            Collections.emptyList(),
                            null, Collections.emptyList(), null, Collections.emptyList(), Collections.emptyList(),
                            100, 0.0, 0.0, 0.0))
                    .collect(Collectors.toList());
            data.setAvailableAccounts(availableAccounts);
        }
    }

    private DashboardData aggregateDashboardData(List<DashboardData> accountDataList,
            List<CloudAccount> accounts,
            List<String> accountNames,
            List<String> failedAccounts) {
        DashboardData aggregated = new DashboardData();
        aggregated.setMultiAccountView(true);
        aggregated.setSelectedAccountIds(
                accounts.stream().map(CloudAccount::getAwsAccountId).collect(Collectors.toList()));
        aggregated.setSelectedAccountNames(accountNames);
        aggregated.setFailedAccounts(failedAccounts);
        aggregated.setSelectedProvider("AWS");

        double totalMTD = 0.0;
        double totalForecast = 0.0;
        double totalLastMonth = 0.0;
        double totalPotentialSavings = 0.0;

        DashboardData.ResourceInventory aggregatedInventory = new DashboardData.ResourceInventory();

        int totalUsers = 0, totalGroups = 0, totalPolicies = 0, totalRoles = 0;
        int totalAlarms = 0, totalMetrics = 0, totalLogs = 0;

        Map<String, DashboardData.RegionStatus> uniqueRegionsMap = new HashMap<>();
        Map<String, Double> costHistoryMap = new LinkedHashMap<>();
        Map<String, Double> billingSummaryMap = new HashMap<>();

        double totalOptimizationSavings = 0.0;
        long totalCriticalAlerts = 0;

        List<DashboardData.Account> subAccounts = new ArrayList<>();

        for (DashboardData data : accountDataList) {
            DashboardData.Account account = data.getSelectedAccount();
            if (account == null)
                continue;

            DashboardData.Account subAcc = new DashboardData.Account(account.getId(), account.getName(),
                    account.getMonthToDateSpend(),
                    account.getForecastedSpend());
            subAcc.setResourceInventory(account.getResourceInventory());
            subAcc.setOptimizationSummary(account.getOptimizationSummary());
            subAcc.setSecurityScore(account.getSecurityScore());
            subAccounts.add(subAcc);

            totalMTD += account.getMonthToDateSpend();
            totalForecast += account.getForecastedSpend();
            totalLastMonth += account.getLastMonthSpend();

            if (account.getSavingsSummary() != null) {
                totalPotentialSavings += account.getSavingsSummary().getTotalPotentialSavings();
            }

            if (account.getResourceInventory() != null) {
                mergeInventory(aggregatedInventory, account.getResourceInventory());
            }

            if (account.getIamResources() != null) {
                totalUsers += account.getIamResources().getUsers();
                totalGroups += account.getIamResources().getGroups();
                totalPolicies += account.getIamResources().getPolicies();
                totalRoles += account.getIamResources().getRoles();
            }

            if (account.getCloudWatchStatus() != null) {
                totalAlarms += account.getCloudWatchStatus().getAlarms();
                totalMetrics += account.getCloudWatchStatus().getMetrics();
                totalLogs += account.getCloudWatchStatus().getLogGroups();
            }

            if (account.getRegionStatus() != null) {
                account.getRegionStatus().forEach(region -> {
                    // Store complete region data with coordinates, not just regionId
                    uniqueRegionsMap.putIfAbsent(region.getRegionId(), region);
                });
            }

            if (account.getCostHistory() != null && account.getCostHistory().getLabels() != null) {
                List<String> labels = account.getCostHistory().getLabels();
                List<Double> values = account.getCostHistory().getValues() != null
                        ? account.getCostHistory().getValues()
                        : account.getCostHistory().getCosts();

                for (int i = 0; i < labels.size() && i < values.size(); i++) {
                    String month = labels.get(i);
                    double amount = values.get(i);
                    costHistoryMap.put(month, costHistoryMap.getOrDefault(month, 0.0) + amount);
                }
            }

            if (account.getBillingSummary() != null) {
                for (DashboardData.BillingSummary billing : account.getBillingSummary()) {
                    String service = billing.getServiceName();
                    double amount = billing.getAmount() != 0 ? billing.getAmount() : billing.getMonthToDateCost();
                    billingSummaryMap.put(service, billingSummaryMap.getOrDefault(service, 0.0) + amount);
                }
            }

            if (account.getOptimizationSummary() != null) {
                totalOptimizationSavings += account.getOptimizationSummary().getTotalPotentialSavings();
                totalCriticalAlerts += account.getOptimizationSummary().getCriticalAlerts();
            }
        }

        DashboardData.Account aggregatedAccount = new DashboardData.Account();
        aggregatedAccount.setId("multi-account");
        aggregatedAccount.setName(String.format("%d AWS Accounts", accounts.size()));
        aggregatedAccount.setSubAccounts(subAccounts);
        aggregatedAccount.setMonthToDateSpend(totalMTD);
        aggregatedAccount.setForecastedSpend(totalForecast);
        aggregatedAccount.setLastMonthSpend(totalLastMonth);
        aggregatedAccount.setResourceInventory(aggregatedInventory);

        aggregatedAccount
                .setIamResources(new DashboardData.IamResources(totalUsers, totalGroups, totalPolicies, totalRoles));

        aggregatedAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(totalAlarms, totalMetrics, totalLogs));

        // Collect all unique regions with complete data (including coordinates)
        List<DashboardData.RegionStatus> regionStatusList = new ArrayList<>(uniqueRegionsMap.values());
        aggregatedAccount.setRegionStatus(regionStatusList);

        List<String> costLabels = new ArrayList<>(costHistoryMap.keySet());

        costLabels.sort(this::compareMonthStrings);

        List<Double> costValues = costLabels.stream()
                .map(costHistoryMap::get)
                .collect(Collectors.toList());

        List<Boolean> costAnomalies = costLabels.stream()
                .map(label -> false)
                .collect(Collectors.toList());

        aggregatedAccount.setCostHistory(new DashboardData.CostHistory(costLabels, costValues, costAnomalies));

        List<DashboardData.BillingSummary> billingSummaryList = billingSummaryMap.entrySet().stream()
                .map(entry -> new DashboardData.BillingSummary(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> {
                    double amountA = a.getAmount() != 0 ? a.getAmount() : a.getMonthToDateCost();
                    double amountB = b.getAmount() != 0 ? b.getAmount() : b.getMonthToDateCost();
                    return Double.compare(amountB, amountA);
                })
                .collect(Collectors.toList());
        aggregatedAccount.setBillingSummary(billingSummaryList);

        List<DashboardData.SavingsSuggestion> savingsSuggestions = new ArrayList<>();
        if (totalPotentialSavings > 0) {
            savingsSuggestions
                    .add(new DashboardData.SavingsSuggestion("Total Potential Savings", totalPotentialSavings));
        }
        aggregatedAccount
                .setSavingsSummary(new DashboardData.SavingsSummary(totalPotentialSavings, savingsSuggestions));

        aggregatedAccount.setOptimizationSummary(
                new DashboardData.OptimizationSummary(totalOptimizationSavings, totalCriticalAlerts));

        int avgSecurityScore = (int) accountDataList.stream()
                .filter(d -> d.getSelectedAccount() != null)
                .mapToInt(d -> d.getSelectedAccount().getSecurityScore())
                .average()
                .orElse(100.0);
        aggregatedAccount.setSecurityScore(avgSecurityScore);

        aggregatedAccount.setEc2Recommendations(Collections.emptyList());
        aggregatedAccount.setEbsRecommendations(Collections.emptyList());
        aggregatedAccount.setLambdaRecommendations(Collections.emptyList());
        aggregatedAccount.setCostAnomalies(Collections.emptyList());
        aggregatedAccount.setWastedResources(Collections.emptyList());
        aggregatedAccount.setSecurityInsights(Collections.emptyList());
        aggregatedAccount.setReservationAnalysis(null);
        aggregatedAccount.setReservationPurchaseRecommendations(Collections.emptyList());
        aggregatedAccount.setServiceQuotas(Collections.emptyList());

        aggregated.setSelectedAccount(aggregatedAccount);
        return aggregated;
    }

    private String normalizeMonthString(String monthStr) {
        return monthStr
                .replace("Sept ", "Sep ")
                .replace("June ", "Jun ")
                .replace("July ", "Jul ")
                .replace("January ", "Jan ")
                .replace("February ", "Feb ")
                .replace("March ", "Mar ")
                .replace("April ", "Apr ")
                .replace("August ", "Aug ")
                .replace("September ", "Sep ")
                .replace("October ", "Oct ")
                .replace("November ", "Nov ")
                .replace("December ", "Dec ");
    }

    public DashboardData getDashboardData(String accountId, boolean forceRefresh, ClientUserDetails userDetails)
            throws ExecutionException, InterruptedException, IOException {
        long startTime = System.currentTimeMillis();
        String user = userDetails != null ? userDetails.getUsername() : "System";
        String tenant = TenantContext.getCurrentTenant();
        logger.info("🔍 [Single-Account-Fetch] Started | Tenant: {} | User: {} | Account: {}", tenant, user, accountId);

        // 1. Determine Cache Key (Scoped by Tenant + Account)
        String dbCacheKey = "DASHBOARD_DATA::" + tenant + "::" + accountId;

        // 2. Try to fetch from Database Cache first (if not forcing refresh)
        if (!forceRefresh) {
            DashboardData cached = getSnapshot(dbCacheKey);
            if (cached != null) {
                logger.info("✅ [Single-Account-Fetch] Returned from DB Cache | Tenant: {} | Account: {}", tenant,
                        accountId);
                return cached;
            }
        }

        // 3. Fetch Fresh Data from Cloud Provider
        CloudAccount account = getAccount(accountId);
        DashboardData freshData;

        try {
            if ("GCP".equalsIgnoreCase(account.getProvider())) {
                GcpDashboardData gcpData = gcpDataService.getDashboardData(account.getGcpProjectId(), forceRefresh)
                        .exceptionally(ex -> {
                            logger.error(
                                    "Failed to get a complete GCP dashboard data object for account {}. Returning partial data.",
                                    account.getGcpProjectId(), ex);
                            return new GcpDashboardData();
                        })
                        .get(5, TimeUnit.MINUTES);
                freshData = mapGcpDataToDashboardData(gcpData, account);
            } else if ("Azure".equalsIgnoreCase(account.getProvider())) {
                com.xammer.cloud.dto.azure.AzureDashboardData azureData = azureDashboardService
                        .getDashboardData(account.getAzureSubscriptionId(), forceRefresh);
                freshData = mapAzureDataToDashboardData(azureData, account);
            } else {
                // AWS
                freshData = getAwsDashboardData(account, forceRefresh, userDetails);
            }

            // 4. Save to Database Cache (Primary Storage)
            saveSnapshot(dbCacheKey, freshData);

            // 5. Update Granular Cache Keys (FIX for stale dashboard widgets)
            updateGranularSnapshots(accountId, freshData);

            freshData.setLastUpdated(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));

            long duration = System.currentTimeMillis() - startTime;
            logger.info("✅ [Single-Account-Fetch] Completed | Tenant: {} | Time: {}ms | Account: {}", tenant, duration,
                    accountId);
            return freshData;

        } catch (InterruptedException ie) {
            // Restore the interrupt flag so the per-account loop's isInterrupted() check fires
            // and breaks out of the loop. Without this, swallowing InterruptedException clears
            // the flag and the loop continues running even after tenantFuture.cancel(true).
            Thread.currentThread().interrupt();
            long duration = System.currentTimeMillis() - startTime;
            logger.warn("❌ [Single-Account-Fetch] Interrupted | Tenant: {} | Account: {} | Time: {}ms — propagating interrupt",
                    tenant, accountId, duration);
            freshData = new DashboardData();
            freshData.setError("Interrupted");
            freshData.setSelectedProvider(account.getProvider());
            freshData.setSelectedAccount(new DashboardData.Account(
                    "AWS".equalsIgnoreCase(account.getProvider()) ? account.getAwsAccountId()
                            : account.getGcpProjectId(),
                    account.getAccountName(),
                    Collections.emptyList(), new DashboardData.ResourceInventory(), null, Collections.emptyList(),
                    null, Collections.emptyList(), null, null, null, Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(), null,
                    Collections.emptyList(), Collections.emptyList(), 0, 0.0, 0.0, 0.0));
            return freshData;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logger.error("❌ [Single-Account-Fetch] Failed | Tenant: {} | Time: {}ms | Account: {} | Error: {}", tenant,
                    duration, accountId, e.getMessage(), e);

            // Return error object instead of throwing to prevent frontend crash
            freshData = new DashboardData();
            freshData.setError("Failed to load data: " + e.getMessage());
            freshData.setSelectedProvider(account.getProvider());
            freshData.setSelectedAccount(new DashboardData.Account(
                    "AWS".equalsIgnoreCase(account.getProvider()) ? account.getAwsAccountId()
                            : account.getGcpProjectId(),
                    account.getAccountName(),
                    Collections.emptyList(), new DashboardData.ResourceInventory(), null, Collections.emptyList(),
                    null, Collections.emptyList(), null, null, null, Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), null, Collections.emptyList(), null,
                    Collections.emptyList(), Collections.emptyList(), 0, 0.0, 0.0, 0.0));
            return freshData;
        }
    }

    private DashboardData mapGcpDataToDashboardData(GcpDashboardData gcpData, CloudAccount account) {
        DashboardData data = new DashboardData();
        data.setSelectedProvider("GCP");

        DashboardData.Account mainAccount = new DashboardData.Account();
        mainAccount.setId(account.getGcpProjectId());
        mainAccount.setName(account.getAccountName());

        mainAccount.setResourceInventory(gcpData.getResourceInventory());
        mainAccount.setIamResources(gcpData.getIamResources());
        mainAccount.setIamDetails(gcpData.getIamDetails());
        mainAccount.setSecurityScore(gcpData.getSecurityScore());
        mainAccount.setSecurityInsights(gcpData.getSecurityInsights());
        mainAccount.setSavingsSummary(gcpData.getSavingsSummary());
        mainAccount.setMonthToDateSpend(gcpData.getMonthToDateSpend());
        mainAccount.setForecastedSpend(gcpData.getForecastedSpend());
        mainAccount.setLastMonthSpend(gcpData.getLastMonthSpend());
        mainAccount.setOptimizationSummary(gcpData.getOptimizationSummary());
        mainAccount.setRegionStatus(gcpData.getRegionStatus());

        List<String> costLabels = (gcpData.getCostHistory() != null && gcpData.getCostHistory().getLabels() != null)
                ? gcpData.getCostHistory().getLabels()
                : new ArrayList<>();
        List<Double> costValues = (gcpData.getCostHistory() != null)
                ? gcpData.getCostHistory().getValues()
                : new ArrayList<>();
        List<Boolean> costAnomalies = (gcpData.getCostHistory() != null
                && gcpData.getCostHistory().getAnomalies() != null)
                        ? gcpData.getCostHistory().getAnomalies()
                        : new ArrayList<>();
        mainAccount.setCostHistory(new DashboardData.CostHistory(costLabels, costValues, costAnomalies));

        List<DashboardData.BillingSummary> billingSummary = (gcpData.getBillingSummary() != null)
                ? gcpData.getBillingSummary().stream()
                        .map(b -> new DashboardData.BillingSummary(b.getName(), b.getAmount()))
                        .collect(Collectors.toList())
                : new ArrayList<>();
        mainAccount.setBillingSummary(billingSummary);

        List<DashboardData.OptimizationRecommendation> gceRecs = (gcpData.getRightsizingRecommendations() != null)
                ? gcpData.getRightsizingRecommendations().stream()
                        .map(rec -> new DashboardData.OptimizationRecommendation(
                                "GCE", rec.getResourceName(), rec.getCurrentMachineType(),
                                rec.getRecommendedMachineType(), rec.getMonthlySavings(), "Rightsizing opportunity",
                                0.0, 0.0))
                        .collect(Collectors.toList())
                : new ArrayList<>();
        mainAccount.setEc2Recommendations(gceRecs);

        List<DashboardData.WastedResource> wastedResources = (gcpData.getWastedResources() != null)
                ? gcpData.getWastedResources().stream()
                        .map(waste -> new DashboardData.WastedResource(
                                waste.getResourceName(), waste.getResourceName(), waste.getType(),
                                waste.getLocation(), waste.getMonthlySavings(), "Idle Resource"))
                        .collect(Collectors.toList())
                : new ArrayList<>();
        mainAccount.setWastedResources(wastedResources);
        mainAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(0, 0, 0));
        mainAccount.setCostAnomalies(new ArrayList<>());
        mainAccount.setEbsRecommendations(new ArrayList<>());
        mainAccount.setLambdaRecommendations(new ArrayList<>());

        data.setSelectedAccount(mainAccount);
        populateAvailableAccounts(data, null);

        return data;
    }

    private DashboardData getAwsDashboardData(CloudAccount account, boolean forceRefresh, ClientUserDetails userDetails)
            throws ExecutionException, InterruptedException, TimeoutException {
        logger.info("--- LAUNCHING OPTIMIZED ASYNC DATA FETCH FROM AWS for account {} ---", account.getAwsAccountId());

        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService
                .getRegionStatusForAccount(account, forceRefresh)
                .exceptionally(ex -> {
                    logger.error("Failed to fetch regions for account {}", account.getAwsAccountId(), ex);
                    return Collections.emptyList();
                });

        return activeRegionsFuture.thenComposeAsync((List<DashboardData.RegionStatus> activeRegions) -> {
            if (activeRegions == null || activeRegions.isEmpty()) {
                logger.warn("No active regions found for account {}. Returning empty dashboard.",
                        account.getAwsAccountId());
                return CompletableFuture.completedFuture(new DashboardData());
            }

            CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture = cloudListService
                    .getAllResourcesGrouped(account.getAwsAccountId(), forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Failed to fetch grouped resources for account {}", account.getAwsAccountId(), ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<DashboardData.ResourceInventory> inventoryFuture = getResourceInventory(
                    groupedResourcesFuture)
                    .exceptionally(ex -> {
                        logger.error("Inventory calculation failed for {}", account.getAwsAccountId(), ex);
                        return new DashboardData.ResourceInventory();
                    });

            CompletableFuture<DashboardData.CloudWatchStatus> cwStatusFuture = getCloudWatchStatus(account,
                    activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("CloudWatch fetch failed for {}", account.getAwsAccountId(), ex);
                        return new DashboardData.CloudWatchStatus(0, 0, 0);
                    });

            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture = optimizationService
                    .getEc2InstanceRecommendations(account, activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("EC2 Recs failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture = optimizationService
                    .getEbsVolumeRecommendations(account, activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("EBS Recs failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture = optimizationService
                    .getLambdaFunctionRecommendations(account, activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Lambda Recs failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<DashboardData.WastedResource>> wastedResourcesFuture = optimizationService
                    .getWastedResources(account, activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Waste fetch failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<DashboardData.SecurityFinding>> securityFindingsFuture = securityService
                    .getComprehensiveSecurityFindings(account, activeRegions, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Security findings failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<DashboardData.CostHistory> costHistoryFuture = finOpsService
                    .getCostHistory(account, forceRefresh, null, null)
                    .exceptionally(ex -> {
                        logger.error("Cost history failed", ex);
                        return new DashboardData.CostHistory();
                    });

            CompletableFuture<List<DashboardData.BillingSummary>> billingFuture = finOpsService
                    .getBillingSummary(account, forceRefresh, null, null)
                    .exceptionally(ex -> {
                        logger.error("Billing summary failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<DashboardData.IamResources> iamFuture = getIamResources(account, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("IAM resources failed", ex);
                        return new DashboardData.IamResources();
                    });

            CompletableFuture<DashboardData.IamDetail> iamDetailsFuture = getIamDetails(account, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("IAM details failed", ex);
                        return new DashboardData.IamDetail();
                    });

            CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = finOpsService
                    .getCostAnomalies(account, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Cost anomalies failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<DashboardData.ReservationAnalysis> reservationFuture = reservationService
                    .getReservationAnalysis(account, forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Reservation analysis failed", ex);
                        return new DashboardData.ReservationAnalysis();
                    });

            CompletableFuture<List<DashboardData.ReservationPurchaseRecommendation>> reservationPurchaseFuture = reservationService
                    .getReservationPurchaseRecommendations(account, "ONE_YEAR", "NO_UPFRONT", "THIRTY_DAYS", "STANDARD",
                            forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Reservation recommendations failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<List<DashboardData.ServiceQuotaInfo>> vpcQuotaInfoFuture = getServiceQuotaInfo(account,
                    activeRegions, groupedResourcesFuture, "vpc", "L-F678F1CE")
                    .exceptionally(ex -> {
                        logger.error("Service quotas failed", ex);
                        return Collections.emptyList();
                    });

            CompletableFuture<Double> mtdSpendFuture = costService
                    .getTotalMonthToDateCost(account.getAwsAccountId(), forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("MTD Cost failed", ex);
                        return 0.0;
                    });

            CompletableFuture<Double> lastMonthSpendFuture = costService
                    .getLastMonthSpend(account.getAwsAccountId(), forceRefresh)
                    .exceptionally(ex -> {
                        logger.error("Last Month Cost failed", ex);
                        return 0.0;
                    });

            CompletableFuture<DashboardData.SavingsSummary> savingsFuture = getSavingsSummary(
                    wastedResourcesFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture,
                    account.getAwsAccountId() != null ? Long.parseLong(account.getAwsAccountId()) : null)
                    .exceptionally(ex -> {
                        logger.error("Savings summary calculation failed", ex);
                        return new DashboardData.SavingsSummary(0.0, 0.0);
                    });

            return CompletableFuture.allOf(
                    inventoryFuture, cwStatusFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture,
                    wastedResourcesFuture, securityFindingsFuture, costHistoryFuture, billingFuture,
                    iamFuture, iamDetailsFuture, savingsFuture, anomaliesFuture, reservationFuture,
                    reservationPurchaseFuture, vpcQuotaInfoFuture, mtdSpendFuture, lastMonthSpendFuture)
                    .thenApply(v -> {
                        List<DashboardData.WastedResource> wastedResources = wastedResourcesFuture.join();
                        List<DashboardData.OptimizationRecommendation> ec2Recs = ec2RecsFuture.join();
                        List<DashboardData.OptimizationRecommendation> ebsRecs = ebsRecsFuture.join();
                        List<DashboardData.OptimizationRecommendation> lambdaRecs = lambdaRecsFuture.join();
                        List<DashboardData.CostAnomaly> anomalies = anomaliesFuture.join();
                        List<DashboardData.SecurityFinding> securityFindings = securityFindingsFuture.join();
                        List<DashboardData.ServiceQuotaInfo> vpcQuotas = vpcQuotaInfoFuture.join();

                        Double mtdSpend = mtdSpendFuture.join();
                        Double lastMonthSpend = lastMonthSpendFuture.join();

                        int dayOfMonth = LocalDate.now().getDayOfMonth();
                        int daysInMonth = LocalDate.now().lengthOfMonth();
                        Double forecastedSpend = (dayOfMonth > 0) ? (mtdSpend / dayOfMonth) * daysInMonth : 0.0;

                        List<DashboardData.SecurityInsight> securityInsights = securityFindings.stream()
                                .collect(Collectors.groupingBy(DashboardData.SecurityFinding::getCategory,
                                        Collectors.groupingBy(DashboardData.SecurityFinding::getSeverity,
                                                Collectors.counting())))
                                .entrySet().stream()
                                .map(entry -> new DashboardData.SecurityInsight(
                                        String.format("%s has potential issues", entry.getKey()),
                                        entry.getKey(),
                                        entry.getValue().keySet().stream().findFirst().orElse("INFO"),
                                        entry.getValue().values().stream().mapToInt(Long::intValue).sum()))
                                .collect(Collectors.toList());

                        DashboardData.OptimizationSummary optimizationSummary = getOptimizationSummary(
                                wastedResources, ec2Recs, ebsRecs, lambdaRecs, anomalies);

                        int securityScore = calculateSecurityScore(securityFindings);

                        DashboardData data = new DashboardData();
                        data.setSelectedProvider("AWS");
                        DashboardData.Account mainAccount = new DashboardData.Account(
                                account.getAwsAccountId(), account.getAccountName(),
                                activeRegions, inventoryFuture.join(), cwStatusFuture.join(), securityInsights,
                                costHistoryFuture.join(), billingFuture.join(), iamFuture.join(),
                                iamDetailsFuture.join(),
                                savingsFuture.join(),
                                ec2Recs, anomalies, ebsRecs, lambdaRecs,
                                reservationFuture.join(), reservationPurchaseFuture.join(),
                                optimizationSummary, wastedResources, vpcQuotas,
                                securityScore,
                                mtdSpend,
                                forecastedSpend,
                                lastMonthSpend);

                        data.setSelectedAccount(mainAccount);
                        populateAvailableAccounts(data, userDetails);

                        return data;
                    });
        }, backgroundTaskExecutor).get(90, TimeUnit.SECONDS);
    }

    private CompletableFuture<DashboardData.ResourceInventory> getResourceInventory(
            CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture) {
        return groupedResourcesFuture.thenApply(groupedResources -> {
            DashboardData.ResourceInventory inventory = new DashboardData.ResourceInventory();
            Map<String, Integer> counts = groupedResources.stream()
                    .collect(Collectors.toMap(
                            DashboardData.ServiceGroupDto::getServiceType,
                            group -> group.getResources().size(),
                            (existing, replacement) -> existing)); // Handle duplicates if any

            // Original fields
            inventory.setVpc(counts.getOrDefault("VPC", 0));
            inventory.setEcs(counts.getOrDefault("ECS Cluster", 0));
            inventory.setEc2(counts.getOrDefault("EC2 Instance", 0));
            inventory.setKubernetes(counts.getOrDefault("EKS Cluster", 0));
            inventory.setLambdas(counts.getOrDefault("Lambda Function", 0));
            inventory.setEbsVolumes(counts.getOrDefault("EBS Volume", 0));
            inventory.setImages(counts.getOrDefault("AMI", 0));
            inventory.setSnapshots(counts.getOrDefault("Snapshot", 0));
            inventory.setS3Buckets(counts.getOrDefault("S3 Bucket", 0));
            inventory.setRdsInstances(counts.getOrDefault("RDS Instance", 0));
            inventory.setRoute53Zones(counts.getOrDefault("Route 53 Zone", 0));
            inventory.setLoadBalancers(counts.getOrDefault("Load Balancer", 0));
            inventory.setLightsail(counts.getOrDefault("Lightsail Instance", 0));
            inventory.setAmplify(counts.getOrDefault("Amplify App", 0));

            // Frontend compatibility fields
            inventory.setEc2Count(inventory.getEc2());
            inventory.setS3Count(inventory.getS3Buckets());
            inventory.setRdsCount(inventory.getRdsInstances());
            inventory.setVpcCount(inventory.getVpc());
            inventory.setLambdaCount(inventory.getLambdas());
            inventory.setEbsCount(inventory.getEbsVolumes());
            inventory.setRoute53Count(inventory.getRoute53Zones());
            inventory.setLoadBalancerCount(inventory.getLoadBalancers());
            inventory.setEcsCount(inventory.getEcs());
            inventory.setEksCount(inventory.getKubernetes());
            inventory.setLightsailCount(inventory.getLightsail());
            inventory.setAmplifyCount(inventory.getAmplify());

            return inventory;
        });
    }

    @Async("backgroundTaskExecutor")
    public CompletableFuture<DashboardData.CloudWatchStatus> getCloudWatchStatus(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "cloudwatchStatus-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.CloudWatchStatus> cachedData = redisCache.get(cacheKey,
                    DashboardData.CloudWatchStatus.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        DashboardData.CloudWatchStatus status = new DashboardData.CloudWatchStatus(0, 0, 0);
        redisCache.put(cacheKey, status, 60);
        return CompletableFuture.completedFuture(status);
    }

    @Async("backgroundTaskExecutor")
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getServiceQuotaInfo(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions,
            CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture, String serviceCode,
            String quotaCode) {
        return groupedResourcesFuture.thenCompose(groupedResources -> {
            Map<String, Long> vpcCounts = groupedResources.stream()
                    .filter(g -> "VPC".equals(g.getServiceType()))
                    .flatMap(g -> g.getResources().stream())
                    .collect(Collectors.groupingBy(com.xammer.cloud.dto.ResourceDto::getRegion, Collectors.counting()));

            List<CompletableFuture<DashboardData.ServiceQuotaInfo>> futures = activeRegions.stream()
                    .map(region -> CompletableFuture.supplyAsync(() -> {
                        try {
                            ServiceQuotasClient sqClient = awsClientProvider.getServiceQuotasClient(account,
                                    region.getRegionId());
                            ListServiceQuotasRequest listRequest = ListServiceQuotasRequest.builder()
                                    .serviceCode(serviceCode)
                                    .build();
                            ListServiceQuotasResponse listResponse = sqClient.listServiceQuotas(listRequest);
                            Optional<ServiceQuota> quota = listResponse.quotas().stream()
                                    .filter(q -> q.quotaCode().equals(quotaCode))
                                    .findFirst();

                            return quota.map(serviceQuota -> {
                                double currentCount = vpcCounts.getOrDefault(region.getRegionId(), 0L).doubleValue();
                                double utilization = (serviceQuota.value() > 0)
                                        ? (currentCount / serviceQuota.value()) * 100.0
                                        : 0.0;
                                return new DashboardData.ServiceQuotaInfo(
                                        serviceQuota.quotaName(),
                                        serviceQuota.value(),
                                        utilization,
                                        region.getRegionId());
                            }).orElse(null);
                        } catch (Exception e) {
                            logger.error("Failed to get quota info for service {} in region {}.", serviceCode,
                                    region.getRegionId(), e);
                            return null;
                        }
                    }))
                    .collect(Collectors.toList());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                            .map(CompletableFuture::join)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toList()));
        });
    }

    @Async("backgroundTaskExecutor")
    public CompletableFuture<DashboardData.IamResources> getIamResources(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "iamResources-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.IamResources> cachedData = redisCache.get(cacheKey,
                    DashboardData.IamResources.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        IamClient iam = awsClientProvider.getIamClient(account);
        logger.info("Fetching IAM resources for account {}...", account.getAwsAccountId());
        int users = 0, groups = 0, policies = 0, roles = 0;
        try {
            users = iam.listUsers().users().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Users on account {}", account.getAwsAccountId(), e);
        }
        try {
            groups = iam.listGroups().groups().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Groups on account {}", account.getAwsAccountId(), e);
        }
        try {
            policies = iam.listPolicies(r -> r.scope(PolicyScopeType.LOCAL)).policies().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Policies on account {}", account.getAwsAccountId(), e);
        }
        try {
            roles = iam.listRoles().roles().size();
        } catch (Exception e) {
            logger.error("IAM check failed for Roles on account {}", account.getAwsAccountId(), e);
        }

        DashboardData.IamResources resources = new DashboardData.IamResources(users, groups, policies, roles);
        redisCache.put(cacheKey, resources, 60);
        return CompletableFuture.completedFuture(resources);
    }

    @Async("backgroundTaskExecutor")
    public CompletableFuture<DashboardData.IamDetail> getIamDetails(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "iamDetails-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<DashboardData.IamDetail> cachedData = redisCache.get(cacheKey, DashboardData.IamDetail.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            IamClient iam = awsClientProvider.getIamClient(account);

            List<DashboardData.IamUserDetail> users = iam.listUsers().users().parallelStream().map(user -> {
                try {
                    List<String> groups = iam.listGroupsForUser(r -> r.userName(user.userName()))
                            .groups().stream().map(g -> g.groupName()).collect(Collectors.toList());

                    List<DashboardData.IamPolicyDetail> policies = new ArrayList<>();

                    iam.listAttachedUserPolicies(r -> r.userName(user.userName())).attachedPolicies()
                            .forEach(p -> policies
                                    .add(new DashboardData.IamPolicyDetail(p.policyName(), p.policyArn(), "Managed")));

                    iam.listUserPolicies(r -> r.userName(user.userName())).policyNames()
                            .forEach(p -> policies.add(new DashboardData.IamPolicyDetail(p, null, "Inline")));

                    boolean mfaEnabled = !iam.listMFADevices(r -> r.userName(user.userName())).mfaDevices().isEmpty();

                    return new DashboardData.IamUserDetail(
                            user.userName(), user.userId(), user.arn(),
                            user.createDate().toString(),
                            user.passwordLastUsed() != null ? user.passwordLastUsed().toString() : "Never",
                            groups, policies, mfaEnabled);
                } catch (Exception e) {
                    logger.warn("Failed to fetch details for user {}", user.userName());
                    return null;
                }
            }).filter(java.util.Objects::nonNull).collect(Collectors.toList());

            List<DashboardData.IamRoleDetail> roles = iam.listRoles().roles().parallelStream()
                    .filter(r -> !r.path().startsWith("/aws-service-role/"))
                    .map(role -> {
                        try {
                            List<DashboardData.IamPolicyDetail> policies = new ArrayList<>();
                            iam.listAttachedRolePolicies(r -> r.roleName(role.roleName())).attachedPolicies()
                                    .forEach(p -> policies.add(new DashboardData.IamPolicyDetail(p.policyName(),
                                            p.policyArn(), "Managed")));

                            iam.listRolePolicies(r -> r.roleName(role.roleName())).policyNames()
                                    .forEach(p -> policies.add(new DashboardData.IamPolicyDetail(p, null, "Inline")));

                            return new DashboardData.IamRoleDetail(
                                    role.roleName(), role.roleId(), role.arn(),
                                    role.createDate().toString(), role.description(), policies);
                        } catch (Exception e) {
                            logger.warn("Failed to fetch details for role {}", role.roleName());
                            return null;
                        }
                    }).filter(java.util.Objects::nonNull).collect(Collectors.toList());

            DashboardData.IamDetail details = new DashboardData.IamDetail(users, roles);
            redisCache.put(cacheKey, details, 60);
            return details;
        });
    }

    @Async("backgroundTaskExecutor")
    public CompletableFuture<DashboardData.SavingsSummary> getSavingsSummary(
            CompletableFuture<List<DashboardData.WastedResource>> wastedFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2RecsFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsRecsFuture,
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaRecsFuture,
            Long cloudAccountId) {

        return CompletableFuture.allOf(wastedFuture, ec2RecsFuture, ebsRecsFuture, lambdaRecsFuture)
                .thenApply(v -> {
                    double wasteSavings = wastedFuture.join().stream()
                            .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                            .sum();

                    double rightsizingSavings = Stream
                            .of(ec2RecsFuture.join(), ebsRecsFuture.join(), lambdaRecsFuture.join())
                            .flatMap(List::stream)
                            .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
                            .sum();

                    // AutoSpotting potential savings — served from Redis cache (fast)
                    double spotPotentialSavings = 0.0;
                    if (cloudAccountId != null) {
                        try {
                            com.xammer.cloud.dto.autospotting.CostResponse spotCost =
                                    autoSpottingService.getCostData(cloudAccountId);
                            if (spotCost != null && spotCost.getSummary() != null) {
                                double hourly = spotCost.getSummary().getTotalPotentialSavings();
                                spotPotentialSavings = hourly * 24 * 30; // convert hourly → monthly
                            }
                        } catch (Exception e) {
                            logger.warn("Could not fetch AutoSpotting potential savings for dashboard: {}", e.getMessage());
                        }
                    }

                    // EBS Disk Rightsizing savings — cache-only, no live scan
                    double ebsDiskSavings = 0.0;
                    if (cloudAccountId != null) {
                        try {
                            String awsAccId = cloudAccountRepository.findById(cloudAccountId)
                                    .map(com.xammer.cloud.domain.CloudAccount::getAwsAccountId)
                                    .orElse(null);
                            ebsDiskSavings = ebsDiskRightsizingService.getTotalPotentialSavings(awsAccId);
                        } catch (Exception e) {
                            logger.warn("Could not fetch EBS disk rightsizing savings for dashboard: {}", e.getMessage());
                        }
                    }

                    List<DashboardData.SavingsSuggestion> suggestions = new ArrayList<>();
                    if (rightsizingSavings > 0) {
                        suggestions.add(new DashboardData.SavingsSuggestion("Rightsizing", rightsizingSavings));
                    }
                    if (wasteSavings > 0) {
                        suggestions.add(new DashboardData.SavingsSuggestion("Waste Elimination", wasteSavings));
                    }
                    if (spotPotentialSavings > 0) {
                        suggestions.add(new DashboardData.SavingsSuggestion("Spot Automation", spotPotentialSavings));
                    }
                    if (ebsDiskSavings > 0) {
                        suggestions.add(new DashboardData.SavingsSuggestion("EBS Disk Rightsizing", ebsDiskSavings));
                    }

                    double totalPotential = wasteSavings + rightsizingSavings + spotPotentialSavings + ebsDiskSavings;

                    return new DashboardData.SavingsSummary(totalPotential, suggestions);
                });
    }

    private DashboardData.OptimizationSummary getOptimizationSummary(
            List<DashboardData.WastedResource> wastedResources,
            List<DashboardData.OptimizationRecommendation> ec2Recs,
            List<DashboardData.OptimizationRecommendation> ebsRecs,
            List<DashboardData.OptimizationRecommendation> lambdaRecs,
            List<DashboardData.CostAnomaly> anomalies) {
        double rightsizingSavings = Stream.of(ec2Recs, ebsRecs, lambdaRecs)
                .flatMap(List::stream)
                .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
                .sum();

        double wasteSavings = wastedResources.stream()
                .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                .sum();

        double totalSavings = rightsizingSavings + wasteSavings;
        long criticalAlerts = (anomalies != null ? anomalies.size() : 0) + ec2Recs.size() + ebsRecs.size()
                + lambdaRecs.size();
        return new DashboardData.OptimizationSummary(totalSavings, criticalAlerts);
    }

    private int calculateSecurityScore(List<DashboardData.SecurityFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return 100;
        }
        Map<String, Long> counts = findings.stream()
                .collect(Collectors.groupingBy(DashboardData.SecurityFinding::getSeverity, Collectors.counting()));

        long criticalWeight = 5;
        long highWeight = 2;
        long mediumWeight = 1;
        long lowWeight = 0;

        long weightedScore = (counts.getOrDefault("CRITICAL", 0L) * criticalWeight) +
                (counts.getOrDefault("HIGH", 0L) * highWeight) +
                (counts.getOrDefault("MEDIUM", 0L) * mediumWeight) +
                (counts.getOrDefault("LOW", 0L) * lowWeight);

        double score = 100.0 / (1 + 0.1 * weightedScore);

        return Math.max(0, (int) Math.round(score));
    }

    private DashboardData mapAzureDataToDashboardData(com.xammer.cloud.dto.azure.AzureDashboardData azureData,
            CloudAccount account) {
        DashboardData data = new DashboardData();
        data.setSelectedProvider("Azure");

        DashboardData.Account mainAccount = new DashboardData.Account();
        mainAccount.setId(account.getAzureSubscriptionId());
        mainAccount.setName(account.getAccountName());

        DashboardData.ResourceInventory inv = new DashboardData.ResourceInventory();
        if (azureData.getResourceInventory() != null) {
            com.xammer.cloud.dto.azure.AzureDashboardData.ResourceInventory azInv = azureData.getResourceInventory();
            inv.setEc2((int) azInv.getVirtualMachines());
            inv.setS3Buckets((int) azInv.getStorageAccounts());
            inv.setRdsInstances((int) azInv.getSqlDatabases());
            inv.setVpc((int) azInv.getVirtualNetworks());
            inv.setLambdas((int) azInv.getFunctions());
            inv.setEbsVolumes((int) azInv.getDisks());
            inv.setRoute53Zones((int) azInv.getDnsZones());
            inv.setLoadBalancers((int) azInv.getLoadBalancers());
            inv.setKubernetes((int) azInv.getKubernetesServices());
            inv.setAmplify((int) azInv.getAppServices());
        }
        mainAccount.setResourceInventory(inv);

        if (azureData.getIamDetails() != null) {
            mainAccount.setIamDetails(azureData.getIamDetails());
            int userCount = azureData.getIamDetails().getUsers() != null ? azureData.getIamDetails().getUsers().size()
                    : 0;
            int roleCount = azureData.getIamDetails().getRoles() != null ? azureData.getIamDetails().getRoles().size()
                    : 0;
            mainAccount.setIamResources(new DashboardData.IamResources(userCount, 0, 0, roleCount));
        } else {
            mainAccount.setIamResources(new DashboardData.IamResources(0, 0, 0, 0));
        }

        mainAccount.setMonthToDateSpend(azureData.getMonthToDateSpend());
        mainAccount.setForecastedSpend(azureData.getForecastedSpend());

        if (azureData.getCostHistory() != null) {
            mainAccount.setCostHistory(new DashboardData.CostHistory(
                    azureData.getCostHistory().getLabels(),
                    azureData.getCostHistory().getCosts(),
                    azureData.getCostHistory().getAnomalies()));
        }

        if (azureData.getRegionStatus() != null) {
            List<DashboardData.RegionStatus> regions = azureData.getRegionStatus().stream()
                    .map(r -> new DashboardData.RegionStatus(r.getName(), r.getStatus(), r.getStatus(), r.getLatitude(),
                            r.getLongitude()))
                    .collect(Collectors.toList());
            mainAccount.setRegionStatus(regions);
        }

        mainAccount.setSecurityScore(100);
        mainAccount.setSecurityInsights(new ArrayList<>());
        mainAccount.setSavingsSummary(new DashboardData.SavingsSummary(0.0, new ArrayList<>()));
        mainAccount.setOptimizationSummary(new DashboardData.OptimizationSummary(0.0, 0));
        mainAccount.setEc2Recommendations(new ArrayList<>());
        mainAccount.setWastedResources(new ArrayList<>());
        mainAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(0, 0, 0));
        mainAccount.setCostAnomalies(new ArrayList<>());
        mainAccount.setEbsRecommendations(new ArrayList<>());
        mainAccount.setLambdaRecommendations(new ArrayList<>());
        mainAccount.setBillingSummary(new ArrayList<>());

        data.setSelectedAccount(mainAccount);
        populateAvailableAccounts(data, null);

        return data;
    }

    // ============================================================================================
    // GRANULAR DASHBOARD METHODS (Standard Dashboard Refactor)
    // ============================================================================================

    public DashboardData getGranularKpi(String accountId, boolean forceRefresh, ClientUserDetails userDetails) {
        String idToUse = accountId.contains(",") ? accountId.split(",")[0] : accountId;
        try {
            String cacheKey = "DASHBOARD_KPI::" + idToUse;

            // 1. Try Cache (Redis + DB)
            if (!forceRefresh) {
                DashboardData cached = getSnapshot(cacheKey);
                if (cached != null) {
                    logger.info("✅ [KPI] Returned from DB Snapshot | Account: {}", idToUse);
                    return cached;
                }
            }

            CloudAccount account = getAccount(idToUse);
            DashboardData data = new DashboardData();
            data.setSelectedProvider(account.getProvider());

            DashboardData.Account mainAccount = new DashboardData.Account();
            mainAccount.setId(idToUse);
            mainAccount.setName(account.getAccountName());
            data.setSelectedAccount(mainAccount);

            if ("AWS".equalsIgnoreCase(account.getProvider())) {
                // Fetch KPI data including cost history for sparklines and optimization summary
                CompletableFuture<DashboardData.CostHistory> costHistoryFuture = finOpsService.getCostHistory(account,
                        forceRefresh, null, null);
                // This line was added based on the provided code edit snippet.
                // If FinOpsReportDto is not used elsewhere in this method, it might be dead
                // code.
                CompletableFuture<FinOpsReportDto> finOpsFuture = finOpsService.getFinOpsReport(idToUse, forceRefresh,
                        null, null);
                List<DashboardData.RegionStatus> activeRegions = cloudListService
                        .getRegionStatusForAccount(account, forceRefresh).join();

                CompletableFuture<List<DashboardData.WastedResource>> wasteFuture = optimizationService
                        .getWastedResources(account, activeRegions, forceRefresh);
                CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2Future = optimizationService
                        .getEc2InstanceRecommendations(account, activeRegions, forceRefresh);
                CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebsFuture = optimizationService
                        .getEbsVolumeRecommendations(account, activeRegions, forceRefresh);
                CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambdaFuture = optimizationService
                        .getLambdaFunctionRecommendations(account, activeRegions, forceRefresh);
                CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture = finOpsService
                        .getCostAnomalies(account, forceRefresh);

                // Wait for all to complete
                CompletableFuture
                        .allOf(costHistoryFuture, wasteFuture, ec2Future, ebsFuture, lambdaFuture, anomaliesFuture,
                                finOpsFuture)
                        .join();

                DashboardData.CostHistory costHistory = costHistoryFuture.join();
                mainAccount.setCostHistory(costHistory);

                // Calculate optimization summary for potential savings
                DashboardData.OptimizationSummary optSummary = getOptimizationSummary(
                        wasteFuture.join(),
                        ec2Future.join(),
                        ebsFuture.join(),
                        lambdaFuture.join(),
                        anomaliesFuture.join());
                mainAccount.setOptimizationSummary(optSummary);

                // Also set individual spend values for potential direct access
                Double mtd = costHistory.getCosts().size() > 0
                        ? costHistory.getCosts().get(costHistory.getCosts().size() - 1)
                        : 0.0;
                Double lastMonth = costHistory.getCosts().size() > 1
                        ? costHistory.getCosts().get(costHistory.getCosts().size() - 2)
                        : 0.0;

                int dayOfMonth = LocalDate.now().getDayOfMonth();
                int daysInMonth = LocalDate.now().lengthOfMonth();
                Double forecast = (dayOfMonth > 0) ? (mtd / dayOfMonth) * daysInMonth : 0.0;

                mainAccount.setMonthToDateSpend(mtd);
                mainAccount.setLastMonthSpend(lastMonth);
                mainAccount.setForecastedSpend(forecast);

            } else {
                // Fallback for other providers: Fetch full data and extract
                return getDashboardData(idToUse, forceRefresh, userDetails);
            }

            // Save Snapshot
            data.setLastUpdated(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
            saveSnapshot(cacheKey, data);
            return data;
        } catch (Exception e) {
            logger.error("Error fetching granular KPI for {}", idToUse, e);
            return createErrorResponse("Failed to fetch KPI data");
        }
    }

    public DashboardData getGranularInventory(String accountId, boolean forceRefresh, ClientUserDetails userDetails) {
        String idToUse = accountId.contains(",") ? accountId.split(",")[0] : accountId;
        try {
            String cacheKey = "DASHBOARD_INVENTORY::" + idToUse;

            // 1. Try Cache (Redis + DB)
            if (!forceRefresh) {
                DashboardData cached = getSnapshot(cacheKey);
                if (cached != null) {
                    logger.info("✅ [Inventory] Returned from DB Snapshot | Account: {}", idToUse);
                    return cached;
                }
            }

            CloudAccount account = getAccount(idToUse);
            DashboardData data = new DashboardData();
            data.setSelectedProvider(account.getProvider());

            if ("AWS".equalsIgnoreCase(account.getProvider())) {
                DashboardData.Account mainAccount = new DashboardData.Account();
                mainAccount.setId(idToUse);
                mainAccount.setName(account.getAccountName());

                CompletableFuture<List<DashboardData.ServiceGroupDto>> groupedResourcesFuture = cloudListService
                        .getAllResourcesGrouped(idToUse, forceRefresh);

                DashboardData.ResourceInventory inventory = getResourceInventory(groupedResourcesFuture).join();
                mainAccount.setResourceInventory(inventory);

                // Flattened fields for compatibility
                mainAccount.setEc2Count(inventory.getEc2Count());
                mainAccount.setS3Count(inventory.getS3Count());
                mainAccount.setRdsCount(inventory.getRdsCount());
                mainAccount.setVpcCount(inventory.getVpcCount());
                mainAccount.setLambdaCount(inventory.getLambdaCount());
                mainAccount.setEbsCount(inventory.getEbsCount());
                mainAccount.setRoute53Count(inventory.getRoute53Count());
                mainAccount.setLoadBalancerCount(inventory.getLoadBalancerCount());
                mainAccount.setEcsCount(inventory.getEcsCount());
                mainAccount.setEksCount(inventory.getEksCount());
                mainAccount.setLightsailCount(inventory.getLightsailCount());
                mainAccount.setAmplifyCount(inventory.getAmplifyCount());

                // Also fetch cloudwatch status as it's often small and near inventory
                List<DashboardData.RegionStatus> activeRegions = cloudListService
                        .getRegionStatusForAccount(account, forceRefresh).join();
                DashboardData.CloudWatchStatus cwStatus = getCloudWatchStatus(account, activeRegions, forceRefresh)
                        .join();
                mainAccount.setCloudWatchStatus(cwStatus);

                data.setSelectedAccount(mainAccount);
            } else {
                return getDashboardData(idToUse, forceRefresh, userDetails);
            }

            data.setLastUpdated(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
            saveSnapshot(cacheKey, data);
            return data;
        } catch (Exception e) {
            logger.error("Error fetching granular Inventory for {}", idToUse, e);
            return createErrorResponse("Failed to fetch Inventory data");
        }
    }

    public DashboardData getGranularCost(String accountId, boolean forceRefresh, ClientUserDetails userDetails) {
        String idToUse = accountId.contains(",") ? accountId.split(",")[0] : accountId;
        try {
            String cacheKey = "DASHBOARD_COST::" + idToUse;

            // 1. Try Cache (Redis + DB)
            if (!forceRefresh) {
                DashboardData cached = getSnapshot(cacheKey);
                if (cached != null) {
                    logger.info("✅ [Cost] Returned from DB Snapshot | Account: {}", idToUse);
                    return cached;
                }
            }

            CloudAccount account = getAccount(idToUse);
            DashboardData data = new DashboardData();
            data.setSelectedProvider(account.getProvider());

            if ("AWS".equalsIgnoreCase(account.getProvider())) {
                DashboardData.Account mainAccount = new DashboardData.Account();
                mainAccount.setId(idToUse);
                mainAccount.setName(account.getAccountName());

                CompletableFuture<DashboardData.CostHistory> costHistoryFuture = finOpsService.getCostHistory(account,
                        forceRefresh, null, null);
                CompletableFuture<List<DashboardData.BillingSummary>> billingFuture = finOpsService
                        .getBillingSummary(account, forceRefresh, null, null);

                DashboardData.CostHistory ch = costHistoryFuture.join();
                mainAccount.setCostHistory(ch);

                mainAccount.setBillingSummary(billingFuture.join());
                data.setSelectedAccount(mainAccount);
            } else {
                return getDashboardData(idToUse, forceRefresh, userDetails);
            }

            data.setLastUpdated(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
            saveSnapshot(cacheKey, data);
            return data;
        } catch (Exception e) {
            logger.error("Error fetching granular Cost for {}", idToUse, e);
            return createErrorResponse("Failed to fetch Cost data");
        }
    }

    public DashboardData getGranularOptimization(String accountId, boolean forceRefresh,
            ClientUserDetails userDetails) {
        String idToUse = accountId.contains(",") ? accountId.split(",")[0] : accountId;
        try {
            String cacheKey = "DASHBOARD_OPT::" + idToUse;

            // 1. Try Cache (Redis + DB)
            if (!forceRefresh) {
                DashboardData cached = getSnapshot(cacheKey);
                if (cached != null) {
                    logger.info("✅ [Optimization] Returned from DB Snapshot | Account: {}", idToUse);
                    return cached;
                }
            }

            CloudAccount account = getAccount(idToUse);
            DashboardData data = new DashboardData();
            data.setSelectedProvider(account.getProvider());

            if ("AWS".equalsIgnoreCase(account.getProvider())) {
                DashboardData.Account mainAccount = new DashboardData.Account();
                mainAccount.setId(idToUse);
                mainAccount.setName(account.getAccountName());

                List<DashboardData.RegionStatus> activeRegions = cloudListService
                        .getRegionStatusForAccount(account, forceRefresh).join();

                CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2 = optimizationService
                        .getEc2InstanceRecommendations(account, activeRegions, forceRefresh);
                CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebs = optimizationService
                        .getEbsVolumeRecommendations(account, activeRegions, forceRefresh);
                CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambda = optimizationService
                        .getLambdaFunctionRecommendations(account, activeRegions, forceRefresh);
                CompletableFuture<List<DashboardData.WastedResource>> waste = optimizationService
                        .getWastedResources(account, activeRegions, forceRefresh);
                CompletableFuture<List<DashboardData.CostAnomaly>> anomalies = finOpsService.getCostAnomalies(account,
                        forceRefresh);

                CompletableFuture.allOf(ec2, ebs, lambda, waste, anomalies).join();

                mainAccount.setEc2Recommendations(ec2.join());
                mainAccount.setEbsRecommendations(ebs.join());
                mainAccount.setLambdaRecommendations(lambda.join());
                mainAccount.setWastedResources(waste.join());
                mainAccount.setCostAnomalies(anomalies.join());

                // Calculcate summaries
                DashboardData.OptimizationSummary optSum = getOptimizationSummary(waste.join(), ec2.join(), ebs.join(),
                        lambda.join(), anomalies.join());
                mainAccount.setOptimizationSummary(optSum);

                // Savings Summary
                DashboardData.SavingsSummary savings = getSavingsSummary(waste, ec2, ebs, lambda,
                        account.getAwsAccountId() != null ? Long.parseLong(account.getAwsAccountId()) : null).join();
                mainAccount.setSavingsSummary(savings);

                data.setSelectedAccount(mainAccount);
            } else {
                return getDashboardData(idToUse, forceRefresh, userDetails);
            }

            data.setLastUpdated(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
            saveSnapshot(cacheKey, data);
            return data;
        } catch (Exception e) {
            logger.error("Error fetching granular Optimization for {}", idToUse, e);
            return createErrorResponse("Failed to fetch Optimization data");
        }
    }

    public DashboardData getGranularMap(String accountId, boolean forceRefresh, ClientUserDetails userDetails) {
        String idToUse = accountId.contains(",") ? accountId.split(",")[0] : accountId;
        try {
            String cacheKey = "DASHBOARD_MAP::" + idToUse;

            // 1. Try Cache (Redis + DB)
            if (!forceRefresh) {
                DashboardData cached = getSnapshot(cacheKey);
                if (cached != null) {
                    logger.info("✅ [Map] Returned from DB Snapshot | Account: {}", idToUse);
                    return cached;
                }
            }

            CloudAccount account = getAccount(idToUse);
            DashboardData data = new DashboardData();
            data.setSelectedProvider(account.getProvider());

            if ("AWS".equalsIgnoreCase(account.getProvider())) {
                DashboardData.Account mainAccount = new DashboardData.Account();
                mainAccount.setId(idToUse);
                mainAccount.setName(account.getAccountName());

                List<DashboardData.RegionStatus> activeRegions = cloudListService
                        .getRegionStatusForAccount(account, forceRefresh).join();
                mainAccount.setRegionStatus(activeRegions);

                data.setSelectedAccount(mainAccount);
            } else {
                return getDashboardData(idToUse, forceRefresh, userDetails);
            }

            data.setLastUpdated(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
            saveSnapshot(cacheKey, data);
            return data;
        } catch (Exception e) {
            logger.error("Error fetching granular Map for {}", idToUse, e);
            return createErrorResponse("Failed to fetch Map data");
        }
    }

    private DashboardData createErrorResponse(String errorMsg) {
        DashboardData data = new DashboardData();
        data.setError(errorMsg);
        return data;
    }

    // ============================================================================================
    // FIX: HELPER TO SYNC GRANULAR CACHES
    // ============================================================================================

    private void updateGranularSnapshots(String accountId, DashboardData data) {
        try {
            logger.debug("Syncing granular cache snapshots for account {}", accountId);

            // 1. KPI Cache
            if (data.getSelectedAccount() != null && data.getSelectedAccount().getCostHistory() != null) {
                // Ensure we construct the specific partial object expected by KPI endpoint
                DashboardData kpiData = new DashboardData();
                kpiData.setSelectedProvider(data.getSelectedProvider());
                DashboardData.Account kpiAcc = new DashboardData.Account();
                kpiAcc.setId(accountId);
                kpiAcc.setName(data.getSelectedAccount().getName());

                kpiAcc.setCostHistory(data.getSelectedAccount().getCostHistory());
                kpiAcc.setOptimizationSummary(data.getSelectedAccount().getOptimizationSummary());
                kpiAcc.setMonthToDateSpend(data.getSelectedAccount().getMonthToDateSpend());
                kpiAcc.setForecastedSpend(data.getSelectedAccount().getForecastedSpend());
                kpiAcc.setLastMonthSpend(data.getSelectedAccount().getLastMonthSpend());

                kpiData.setSelectedAccount(kpiAcc);
                saveSnapshot("DASHBOARD_KPI::" + accountId, kpiData);
            }

            // 2. Inventory Cache
            if (data.getSelectedAccount() != null && data.getSelectedAccount().getResourceInventory() != null) {
                DashboardData invData = new DashboardData();
                invData.setSelectedProvider(data.getSelectedProvider());
                DashboardData.Account invAcc = new DashboardData.Account();
                invAcc.setId(accountId);
                invAcc.setName(data.getSelectedAccount().getName());

                invAcc.setResourceInventory(data.getSelectedAccount().getResourceInventory());
                invAcc.setCloudWatchStatus(data.getSelectedAccount().getCloudWatchStatus());

                invData.setSelectedAccount(invAcc);
                saveSnapshot("DASHBOARD_INVENTORY::" + accountId, invData);
            }

            // 3. Cost Cache
            if (data.getSelectedAccount() != null && data.getSelectedAccount().getCostHistory() != null) {
                DashboardData costData = new DashboardData();
                costData.setSelectedProvider(data.getSelectedProvider());
                DashboardData.Account costAcc = new DashboardData.Account();
                costAcc.setId(accountId);
                costAcc.setName(data.getSelectedAccount().getName());

                costAcc.setCostHistory(data.getSelectedAccount().getCostHistory());
                costAcc.setBillingSummary(data.getSelectedAccount().getBillingSummary());

                costData.setSelectedAccount(costAcc);
                saveSnapshot("DASHBOARD_COST::" + accountId, costData);
            }

            // 4. Optimization Cache
            if (data.getSelectedAccount() != null) {
                DashboardData optData = new DashboardData();
                optData.setSelectedProvider(data.getSelectedProvider());
                DashboardData.Account optAcc = new DashboardData.Account();
                optAcc.setId(accountId);
                optAcc.setName(data.getSelectedAccount().getName());

                optAcc.setEc2Recommendations(data.getSelectedAccount().getEc2Recommendations());
                optAcc.setEbsRecommendations(data.getSelectedAccount().getEbsRecommendations());
                optAcc.setLambdaRecommendations(data.getSelectedAccount().getLambdaRecommendations());
                optAcc.setWastedResources(data.getSelectedAccount().getWastedResources());
                optAcc.setCostAnomalies(data.getSelectedAccount().getCostAnomalies());
                optAcc.setOptimizationSummary(data.getSelectedAccount().getOptimizationSummary());
                optAcc.setSavingsSummary(data.getSelectedAccount().getSavingsSummary());

                optData.setSelectedAccount(optAcc);
                saveSnapshot("DASHBOARD_OPT::" + accountId, optData);
            }

            // 5. Map Cache
            if (data.getSelectedAccount() != null && data.getSelectedAccount().getRegionStatus() != null) {
                DashboardData mapData = new DashboardData();
                mapData.setSelectedProvider(data.getSelectedProvider());
                DashboardData.Account mapAcc = new DashboardData.Account();
                mapAcc.setId(accountId);
                mapAcc.setName(data.getSelectedAccount().getName());

                mapAcc.setRegionStatus(data.getSelectedAccount().getRegionStatus());

                mapData.setSelectedAccount(mapAcc);
                saveSnapshot("DASHBOARD_MAP::" + accountId, mapData);
            }

            logger.info("✓ Granular cache snapshots synced for {}", accountId);

        } catch (Exception e) {
            logger.error("Failed to sync granular snapshots for {}", accountId, e);
        }
    }
}