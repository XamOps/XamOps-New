package com.xammer.cloud.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.SonarQubeProject;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.AlertsResponseDto;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    // GCP billing in India is denominated in INR; the dashboard displays USD.
    //
    // The conversion goes through CurrencyConversionService so there is a single
    // configurable rate for the whole platform. This was previously a local
    // INR_TO_USD_RATE = 0.012 constant (an implied 83.33), which disagreed with the
    // 83.0 used by the GCP optimization service and the 84.0 used by both disk
    // services — so the same resource cost a different amount depending on which
    // code path a user hit.

    // Limit concurrent account processing to prevent multiplicative thread
    // explosion.
    // 8 accounts × 15 regions × 10 services = 1,200 awsTaskExecutor tasks max (fits
    // in queue=2000)
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
    private final CloudsitterService cloudsitterService;
    private final com.xammer.cloud.service.gcp.GcpAutoSpottingService gcpAutoSpottingService;
    private final com.xammer.cloud.service.gcp.GcpDiskRightsizingService gcpDiskRightsizingService;
    private final com.xammer.cloud.service.azure.AzureAutoSpottingService azureAutoSpottingService;
    private final com.xammer.cloud.service.azure.AzureDiskRightsizingService azureDiskRightsizingService;
    private final com.xammer.cloud.service.azure.AzureWasteService azureWasteService;
    private final CurrencyConversionService currencyConversionService;

    // Phase 2: Master Data Access for Tenant Iteration
    private final JdbcTemplate masterJdbcTemplate;

    private final JobExecutionHistoryRepository jobExecutionHistoryRepository;

    // ADDED: Environment-aware configuration
    private final com.xammer.cloud.config.SchedulingEnvironmentConfig envConfig;

    /**
     * Optional so the dashboard keeps working unchanged where the inventory plane is
     * disabled or Resource Explorer is blocked.
     */
    @Autowired(required = false)
    private com.xammer.cloud.service.inventory.InventoryProjectionService inventoryProjection;

    /**
     * Scores IAM identities for excess permission, feeding the Security Center's Identity tab.
     *
     * <p>
     * Field-injected rather than added to the constructor above, which already takes 28
     * arguments — following the {@code inventoryProjection} precedent. A 29th parameter would
     * touch every construction site for no behavioural gain.
     */
    @Autowired
    private com.xammer.cloud.service.security.IamPrivilegeAnalyzer iamPrivilegeAnalyzer;

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
            @Lazy CloudsitterService cloudsitterService,
            @Lazy com.xammer.cloud.service.gcp.GcpAutoSpottingService gcpAutoSpottingService,
            @Lazy com.xammer.cloud.service.gcp.GcpDiskRightsizingService gcpDiskRightsizingService,
            @Lazy com.xammer.cloud.service.azure.AzureAutoSpottingService azureAutoSpottingService,
            @Lazy com.xammer.cloud.service.azure.AzureDiskRightsizingService azureDiskRightsizingService,
            @Lazy com.xammer.cloud.service.azure.AzureWasteService azureWasteService,
            CurrencyConversionService currencyConversionService,
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
        this.cloudsitterService = cloudsitterService;
        this.gcpAutoSpottingService = gcpAutoSpottingService;
        this.gcpDiskRightsizingService = gcpDiskRightsizingService;
        this.azureAutoSpottingService = azureAutoSpottingService;
        this.azureDiskRightsizingService = azureDiskRightsizingService;
        this.azureWasteService = azureWasteService;
        this.currencyConversionService = currencyConversionService;
        this.backgroundTaskExecutor = backgroundTaskExecutor;
    }

    // ============================================================================================
    // PHASE 2: ASYNC AGGREGATOR (BACKGROUND JOBS)
    // ============================================================================================

    // runBackgroundAggregation() removed — it called getGranularKpi() with
    // forceRefresh=true
    // at 1:00 AM (live CE API calls), then
    // ProactiveCacheRefreshService.refreshAwsDashboardData()
    // did the same at 3:30 AM. Replaced by buildUnifiedAwsSnapshot() at 3:55 AM
    // which reads
    // already-written DASHBOARD_KPI snapshots from DB — zero API calls.
    @Deprecated
    public void runBackgroundAggregation() {
        // Removed — replaced by buildUnifiedSnapshots() scheduler at 5:10 AM Wed/Sun
        // which reads existing per-account DB snapshots with zero API calls.
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
            if ("local".equals(activeProfile) || "uat".equals(activeProfile) || "preprod".equals(activeProfile)
                    || "prod".equals(activeProfile)) {
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
        return getSecuritySnapshot(key, typeRef, 0);
    }

    /**
     * A security snapshot, provided it is younger than {@code maxAgeHours}.
     *
     * <p>
     * The age check exists because this row has no expiry and, for security findings, is rewritten
     * only by the twice-weekly cron. Read unconditionally it could serve findings a customer fixed
     * days earlier, and no amount of refreshing would clear them — the reported symptom on the GCP
     * pages, present here by a different route. Returning null instead lets the caller fall through
     * to a live scan.
     *
     * @param maxAgeHours 0 or less to accept any age, preserving the original behaviour for the
     *     callers where a snapshot of record is wanted rather than a current one
     */
    public Object getSecuritySnapshot(String key, TypeReference<?> typeRef, int maxAgeHours) {
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            if (maxAgeHours > 0) {
                LocalDateTime stamped = cached.get().getLastUpdated();
                // An unstamped row is of unknown age; on a security surface that has to count as
                // too old rather than be assumed current.
                if (stamped == null || stamped.isBefore(LocalDateTime.now().minusHours(maxAgeHours))) {
                    logger.info("Security snapshot {} is older than {}h — ignoring it so the caller scans live",
                            key, maxAgeHours);
                    return null;
                }
            }
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
        return getAlertsSnapshot(key, typeRef, 0);
    }

    /**
     * An alerts snapshot, provided it is younger than {@code maxAgeMinutes}.
     *
     * <p>
     * The AWS Alerts page read this row with no age check, and the job that rewrites it ran twice
     * a week — so opening the page could show quota and cost alerts four days old, with nothing on
     * screen to say so. Returning null past the cutoff lets the caller rebuild live instead, the
     * same rule the GCP alerts snapshot already follows.
     *
     * @param maxAgeMinutes 0 or less to accept any age, for callers that want the snapshot of
     *     record rather than a current one
     */
    public Object getAlertsSnapshot(String key, TypeReference<?> typeRef, int maxAgeMinutes) {
        Optional<CachedData> cached = cachedDataRepository.findById(key);
        if (cached.isPresent()) {
            if (maxAgeMinutes > 0) {
                LocalDateTime stamped = cached.get().getLastUpdated();
                // Unstamped means unknown age, which on an alerts page has to count as too old.
                if (stamped == null || stamped.isBefore(LocalDateTime.now().minusMinutes(maxAgeMinutes))) {
                    logger.info("Alerts snapshot {} is older than {} min — ignoring it so the caller rebuilds live",
                            key, maxAgeMinutes);
                    return null;
                }
            }
            try {
                return objectMapper.readValue(cached.get().getJsonData(), typeRef);
            } catch (JsonProcessingException e) {
                logger.warn("Stale/corrupted Alerts snapshot for key '{}' — deleting so warm-up can rebuild. Cause: {}",
                        key, e.getMessage());
                cachedDataRepository.deleteById(key);
            }
        }
        return null;
    }

    // DB-first, Redis-fallback read for alerts — used by unified snapshot builder
    private AlertsResponseDto getAlertsFromCache(String accountId) {
        String key = "alerts-" + accountId;
        AlertsResponseDto fromDb = (AlertsResponseDto) getAlertsSnapshot(key,
                new com.fasterxml.jackson.core.type.TypeReference<AlertsResponseDto>() {
                });
        if (fromDb != null) {
            logger.info("[AlertsCache] DB hit for {}", key);
            return fromDb;
        }
        Optional<AlertsResponseDto> fromRedis = redisCache.get(key, AlertsResponseDto.class);
        if (fromRedis.isPresent()) {
            logger.info("[AlertsCache] Redis hit for {} — {} alerts", key,
                    fromRedis.get().getAlerts() != null ? fromRedis.get().getAlerts().size() : 0);
            return fromRedis.get();
        }
        logger.warn("[AlertsCache] No alerts found in DB or Redis for {}", key);
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
                return objectMapper.readValue(cached.get().getJsonData(), new TypeReference<List<CostDto>>() {
                });
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

    /**
     * Reads already-written DASHBOARD_KPI::{accountId} snapshots from DB for all
     * AWS accounts,
     * aggregates them, and saves UNIFIED_DASHBOARD_AWS::{tenant}::system.
     * Called by the 3:55 AM scheduler — zero API calls, pure DB read + math.
     */
    public void buildAndSaveUnifiedAwsSnapshot() {
        String tenantId = TenantContext.getCurrentTenant();

        List<CloudAccount> allAccounts = cloudAccountRepository.findAll();
        List<CloudAccount> awsAccounts = allAccounts.stream()
                .filter(a -> "AWS".equalsIgnoreCase(a.getProvider()) && a.getAwsAccountId() != null)
                .collect(Collectors.toList());

        if (awsAccounts.isEmpty()) {
            logger.info("[UnifiedSnapshot] No AWS accounts found for tenant {}", tenantId);
            return;
        }

        // Build one snapshot per client so each user sees only their own accounts
        Map<Long, List<CloudAccount>> byClient = awsAccounts.stream()
                .filter(a -> a.getClient() != null)
                .collect(Collectors.groupingBy(a -> a.getClient().getId()));

        for (Map.Entry<Long, List<CloudAccount>> entry : byClient.entrySet()) {
            Long clientId = entry.getKey();
            List<CloudAccount> clientAccounts = entry.getValue();
            String scopedKey = SNAPSHOT_KEY_AWS + "::" + tenantId + "::system::" + clientId;

            List<DashboardData> perAccountData = new ArrayList<>();
            for (CloudAccount account : clientAccounts) {
                DashboardData kpi = getSnapshot("DASHBOARD_KPI::" + account.getAwsAccountId());
                if (kpi != null) {
                    DashboardData opt = getSnapshot("DASHBOARD_OPT::" + account.getAwsAccountId());
                    if (opt != null && opt.getSelectedAccount() != null) {
                        kpi.getSelectedAccount().setWastedResources(opt.getSelectedAccount().getWastedResources());
                        if (opt.getSelectedAccount().getSavingsSummary() != null) {
                            kpi.getSelectedAccount().setSavingsSummary(opt.getSelectedAccount().getSavingsSummary());
                        }
                    }
                    DashboardData inv = getSnapshot("DASHBOARD_INVENTORY::" + account.getAwsAccountId());
                    if (inv != null && inv.getSelectedAccount() != null
                            && inv.getSelectedAccount().getResourceInventory() != null) {
                        kpi.getSelectedAccount().setResourceInventory(inv.getSelectedAccount().getResourceInventory());
                    }
                    AlertsResponseDto alertsSnap = getAlertsFromCache(account.getAwsAccountId());
                    if (alertsSnap != null) {
                        int alertCount = alertsSnap.getAlerts() != null ? alertsSnap.getAlerts().size()
                                : (alertsSnap.getMetadata() != null ? alertsSnap.getMetadata().getTotalAlerts() : 0);
                        kpi.getSelectedAccount().setActiveAlertCount(alertCount);
                    }
                    logger.debug("[UnifiedSnapshot] Account {} securityScore={}", account.getAwsAccountId(),
                            kpi.getSelectedAccount().getSecurityScore());
                    perAccountData.add(kpi);
                } else {
                    logger.warn("[UnifiedSnapshot] No KPI snapshot found for account {} — skipping",
                            account.getAwsAccountId());
                }
            }

            if (perAccountData.isEmpty()) {
                logger.warn("[UnifiedSnapshot] No per-account KPI snapshots for client {} tenant {} — skipping",
                        clientId, tenantId);
                continue;
            }

            List<String> accountNames = clientAccounts.stream().map(CloudAccount::getAccountName)
                    .collect(Collectors.toList());
            DashboardData aggregated = aggregateDashboardData(perAccountData, clientAccounts, accountNames,
                    Collections.emptyList());
            aggregated.setLastUpdated(
                    LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
            saveSnapshot(scopedKey, aggregated);
            logger.info("[UnifiedSnapshot] Built UNIFIED_DASHBOARD_AWS for tenant {} client {} — {} accounts", tenantId,
                    clientId, perAccountData.size());
        }
    }


    public void buildAndSaveUnifiedAwsSnapshotForClient(Long clientId) {
        String tenantId = TenantContext.getCurrentTenant();
        String scopedKey = SNAPSHOT_KEY_AWS + "::" + tenantId + "::system::" + clientId;

        List<CloudAccount> clientAccounts = cloudAccountRepository.findByClientId(clientId).stream()
                .filter(a -> "AWS".equalsIgnoreCase(a.getProvider()) && a.getAwsAccountId() != null)
                .collect(Collectors.toList());

        if (clientAccounts.isEmpty()) {
            logger.info("[UnifiedSnapshot] No AWS accounts for client {} tenant {}", clientId, tenantId);
            return;
        }

        List<DashboardData> perAccountData = new ArrayList<>();
        for (CloudAccount account : clientAccounts) {
            DashboardData kpi = getSnapshot("DASHBOARD_KPI::" + account.getAwsAccountId());
            if (kpi != null) {
                DashboardData opt = getSnapshot("DASHBOARD_OPT::" + account.getAwsAccountId());
                if (opt != null && opt.getSelectedAccount() != null) {
                    kpi.getSelectedAccount().setWastedResources(opt.getSelectedAccount().getWastedResources());
                    if (opt.getSelectedAccount().getSavingsSummary() != null) {
                        kpi.getSelectedAccount().setSavingsSummary(opt.getSelectedAccount().getSavingsSummary());
                    }
                }
                DashboardData inv = getSnapshot("DASHBOARD_INVENTORY::" + account.getAwsAccountId());
                if (inv != null && inv.getSelectedAccount() != null
                        && inv.getSelectedAccount().getResourceInventory() != null) {
                    kpi.getSelectedAccount().setResourceInventory(inv.getSelectedAccount().getResourceInventory());
                }
                AlertsResponseDto alertsSnap = getAlertsFromCache(account.getAwsAccountId());
                if (alertsSnap != null) {
                    int alertCount = alertsSnap.getAlerts() != null ? alertsSnap.getAlerts().size()
                            : (alertsSnap.getMetadata() != null ? alertsSnap.getMetadata().getTotalAlerts() : 0);
                    kpi.getSelectedAccount().setActiveAlertCount(alertCount);
                }
                logger.debug("[UnifiedSnapshot] Account {} securityScore={}", account.getAwsAccountId(),
                        kpi.getSelectedAccount().getSecurityScore());
                perAccountData.add(kpi);
            }
        }

        if (perAccountData.isEmpty())
            return;

        List<String> accountNames = clientAccounts.stream().map(CloudAccount::getAccountName)
                .collect(Collectors.toList());
        DashboardData aggregated = aggregateDashboardData(perAccountData, clientAccounts, accountNames,
                Collections.emptyList());
        aggregated.setLastUpdated(
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
        saveSnapshot(scopedKey, aggregated);
        logger.info("[UnifiedSnapshot] Force-rebuilt UNIFIED_DASHBOARD_AWS for tenant {} client {} — {} accounts",
                tenantId, clientId, perAccountData.size());
    }

    /**
     * Reads already-written GCP_DASHBOARD::{gcpProjectId} snapshots from DB for all
     * GCP accounts,
     * aggregates them, and saves UNIFIED_DASHBOARD_GCP::{tenant}::system.
     * Zero API calls — pure DB read + math.
     */
    public void buildAndSaveUnifiedGcpSnapshot() {
        String tenantId = TenantContext.getCurrentTenant();

        List<CloudAccount> allAccounts = cloudAccountRepository.findAll();
        List<CloudAccount> gcpAccounts = allAccounts.stream()
                .filter(a -> "GCP".equalsIgnoreCase(a.getProvider()) && a.getGcpProjectId() != null)
                .collect(Collectors.toList());

        if (gcpAccounts.isEmpty()) {
            logger.info("[UnifiedSnapshot] No GCP accounts found for tenant {}", tenantId);
            return;
        }

        Map<Long, List<CloudAccount>> byClient = gcpAccounts.stream()
                .filter(a -> a.getClient() != null)
                .collect(Collectors.groupingBy(a -> a.getClient().getId()));

        for (Map.Entry<Long, List<CloudAccount>> entry : byClient.entrySet()) {
            Long clientId = entry.getKey();
            List<CloudAccount> clientAccounts = entry.getValue();
            String scopedKey = SNAPSHOT_KEY_GCP + "::" + tenantId + "::system::" + clientId;

            List<com.xammer.cloud.dto.gcp.GcpDashboardData> perAccountData = new ArrayList<>();
            List<CloudAccount> found = new ArrayList<>();
            for (CloudAccount account : clientAccounts) {
                com.xammer.cloud.dto.gcp.GcpDashboardData snap = gcpDataService
                        .getGcpDashboardSnapshot("GCP_DASHBOARD::" + account.getGcpProjectId());
                if (snap != null) {
                    perAccountData.add(snap);
                    found.add(account);
                } else {
                    logger.warn("[UnifiedSnapshot] No GCP snapshot found for project {} — skipping",
                            account.getGcpProjectId());
                }
            }

            if (perAccountData.isEmpty()) {
                logger.warn("[UnifiedSnapshot] No GCP snapshots for client {} tenant {} — skipping", clientId,
                        tenantId);
                continue;
            }

            List<String> accountNames = found.stream().map(CloudAccount::getAccountName).collect(Collectors.toList());
            DashboardData aggregated = aggregateGcpDashboardData(perAccountData, found, accountNames,
                    Collections.emptyList());
            aggregated.setLastUpdated(
                    LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
            saveSnapshot(scopedKey, aggregated);
            logger.info("[UnifiedSnapshot] Built UNIFIED_DASHBOARD_GCP for tenant {} client {} — {} projects", tenantId,
                    clientId, perAccountData.size());
        }
    }

    public void buildAndSaveUnifiedGcpSnapshotForClient(Long clientId) {
        String tenantId = TenantContext.getCurrentTenant();
        String scopedKey = SNAPSHOT_KEY_GCP + "::" + tenantId + "::system::" + clientId;

        List<CloudAccount> clientAccounts = cloudAccountRepository.findByClientId(clientId).stream()
                .filter(a -> "GCP".equalsIgnoreCase(a.getProvider()) && a.getGcpProjectId() != null)
                .collect(Collectors.toList());

        if (clientAccounts.isEmpty())
            return;

        List<com.xammer.cloud.dto.gcp.GcpDashboardData> perAccountData = new ArrayList<>();
        List<CloudAccount> found = new ArrayList<>();
        for (CloudAccount account : clientAccounts) {
            com.xammer.cloud.dto.gcp.GcpDashboardData snap = gcpDataService
                    .getGcpDashboardSnapshot("GCP_DASHBOARD::" + account.getGcpProjectId());
            if (snap != null) {
                perAccountData.add(snap);
                found.add(account);
            }
        }

        if (perAccountData.isEmpty())
            return;

        List<String> accountNames = found.stream().map(CloudAccount::getAccountName).collect(Collectors.toList());
        DashboardData aggregated = aggregateGcpDashboardData(perAccountData, found, accountNames,
                Collections.emptyList());
        aggregated.setLastUpdated(
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
        saveSnapshot(scopedKey, aggregated);
        logger.info("[UnifiedSnapshot] Force-rebuilt UNIFIED_DASHBOARD_GCP for tenant {} client {} — {} projects",
                tenantId, clientId, perAccountData.size());
    }

    /**
     * Reads already-written AZURE_DASHBOARD::{subscriptionId} snapshots from DB for
     * all Azure accounts,
     * aggregates them, and saves UNIFIED_DASHBOARD_AZURE::{tenant}::system.
     * Zero API calls — pure DB read + math.
     */
    public void buildAndSaveUnifiedAzureSnapshot() {
        String tenantId = TenantContext.getCurrentTenant();

        List<CloudAccount> allAccounts = cloudAccountRepository.findAll();
        List<CloudAccount> azureAccounts = allAccounts.stream()
                .filter(a -> "Azure".equalsIgnoreCase(a.getProvider()) && a.getAzureSubscriptionId() != null)
                .collect(Collectors.toList());

        if (azureAccounts.isEmpty()) {
            logger.info("[UnifiedSnapshot] No Azure accounts found for tenant {}", tenantId);
            return;
        }

        Map<Long, List<CloudAccount>> byClient = azureAccounts.stream()
                .filter(a -> a.getClient() != null)
                .collect(Collectors.groupingBy(a -> a.getClient().getId()));

        for (Map.Entry<Long, List<CloudAccount>> entry : byClient.entrySet()) {
            Long clientId = entry.getKey();
            List<CloudAccount> clientAccounts = entry.getValue();
            String scopedKey = SNAPSHOT_KEY_AZURE + "::" + tenantId + "::system::" + clientId;

            List<DashboardData> perAccountData = new ArrayList<>();
            for (CloudAccount account : clientAccounts) {
                com.xammer.cloud.dto.azure.AzureDashboardData snap = azureDashboardService
                        .getDashboardFromDatabase("AZURE_DASHBOARD::" + account.getAzureSubscriptionId());
                if (snap != null) {
                    DashboardData mapped = mapAzureDataToDashboardData(snap, account);
                    if (mapped != null)
                        perAccountData.add(mapped);
                } else {
                    logger.warn("[UnifiedSnapshot] No Azure snapshot found for subscription {} — skipping",
                            account.getAzureSubscriptionId());
                }
            }

            if (perAccountData.isEmpty()) {
                logger.warn("[UnifiedSnapshot] No Azure snapshots for client {} tenant {} — skipping", clientId,
                        tenantId);
                continue;
            }

            DashboardData aggregated = aggregateGenericDashboardData(perAccountData, "Azure");
            aggregated.setLastUpdated(
                    LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
            saveSnapshot(scopedKey, aggregated);
            logger.info("[UnifiedSnapshot] Built UNIFIED_DASHBOARD_AZURE for tenant {} client {} — {} subscriptions",
                    tenantId, clientId, perAccountData.size());
        }
    }

    public void buildAndSaveUnifiedAzureSnapshotForClient(Long clientId) {
        String tenantId = TenantContext.getCurrentTenant();
        String scopedKey = SNAPSHOT_KEY_AZURE + "::" + tenantId + "::system::" + clientId;

        List<CloudAccount> clientAccounts = cloudAccountRepository.findByClientId(clientId).stream()
                .filter(a -> "Azure".equalsIgnoreCase(a.getProvider()) && a.getAzureSubscriptionId() != null)
                .collect(Collectors.toList());

        if (clientAccounts.isEmpty())
            return;

        List<DashboardData> perAccountData = new ArrayList<>();
        for (CloudAccount account : clientAccounts) {
            com.xammer.cloud.dto.azure.AzureDashboardData snap = azureDashboardService
                    .getDashboardFromDatabase("AZURE_DASHBOARD::" + account.getAzureSubscriptionId());
            if (snap != null) {
                DashboardData mapped = mapAzureDataToDashboardData(snap, account);
                if (mapped != null)
                    perAccountData.add(mapped);
            }
        }

        if (perAccountData.isEmpty())
            return;

        DashboardData aggregated = aggregateGenericDashboardData(perAccountData, "Azure");
        aggregated.setLastUpdated(
                LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
        saveSnapshot(scopedKey, aggregated);
        logger.info(
                "[UnifiedSnapshot] Force-rebuilt UNIFIED_DASHBOARD_AZURE for tenant {} client {} — {} subscriptions",
                tenantId, clientId, perAccountData.size());
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

        String clientId = (userDetails != null && userDetails.getClientId() != null)
                ? userDetails.getClientId().toString()
                : null;
        String prebuiltKey = SNAPSHOT_KEY_AWS + "::" + tenantId + "::system::"
                + (clientId != null ? clientId : "system");

        // 3. Prefer the pre-built snapshot the scheduler writes; fall back to the user-scoped copy.
        //
        // This order used to be reversed, and that pinned the unified dashboard to stale data
        // permanently. Only prebuiltKey is ever written by buildAndSaveUnifiedAwsSnapshot;
        // scopedKey is populated once, here, as a copy. Because saveSnapshot writes a cached_data
        // row with no expiry and getSnapshot re-warms Redis from that row on every miss, the copy
        // never aged out and nothing invalidated it — so once it existed, the scheduler could
        // refresh prebuiltKey nightly for months and this method would keep returning the first
        // copy it ever made. Observed as a unified dashboard showing a "Synced Jul 10" timestamp
        // in mid-September, beside FinOps and Cost pages reading live Redis and showing today's
        // figures. Only the Refresh button escaped it, because forceRefresh rewrites scopedKey.
        //
        // Reading prebuiltKey first makes the scheduler's output authoritative and reduces the copy
        // to what it was presumably meant to be: a fallback for the clientId == null case, which
        // the builder never writes a prebuilt key for.
        if (!forceRefresh) {
            DashboardData prebuilt = getSnapshot(prebuiltKey);
            if (prebuilt != null) {
                if (!scopedKey.equals(prebuiltKey))
                    saveSnapshot(scopedKey, prebuilt);
                logger.info("Returning pre-built AWS Snapshot for Unified View (Tenant: {} | User: {})", tenantId,
                        isAdmin ? "Global/Admin" : userDetails.getUsername());
                populateAvailableAccounts(prebuilt, userDetails);
                return prebuilt;
            }
            DashboardData cached = getSnapshot(scopedKey);
            if (cached != null) {
                logger.info("Returning Cached AWS Snapshot for Unified View (Tenant: {} | User: {})", tenantId,
                        isAdmin ? "Global/Admin" : userDetails.getUsername());
                populateAvailableAccounts(cached, userDetails);
                return cached;
            }
        }

        // On force-refresh or full cache miss: rebuild only this client's accounts —
        // zero API calls.
        if (clientId != null) {
            buildAndSaveUnifiedAwsSnapshotForClient(userDetails.getClientId());
        } else {
            buildAndSaveUnifiedAwsSnapshot();
        }
        DashboardData built = getSnapshot(prebuiltKey);
        if (built == null)
            return createEmptyDashboardData("AWS");
        if (!scopedKey.equals(prebuiltKey))
            saveSnapshot(scopedKey, built);
        populateAvailableAccounts(built, userDetails);
        return built;
    }

    public DashboardData getUnifiedGcpData(ClientUserDetails userDetails, boolean forceRefresh) {

        String tenantId = TenantContext.getCurrentTenant();

        // Determine Cache Key (User + Tenant Scoped)
        boolean isAdmin = isUserAdmin(userDetails);
        String clientScope = (userDetails != null && userDetails.getClientId() != null)
                ? userDetails.getClientId().toString()
                : "system";
        String scopedKey = SNAPSHOT_KEY_GCP + "::" + tenantId + "::" + clientScope;

        String clientId = (userDetails != null && userDetails.getClientId() != null)
                ? userDetails.getClientId().toString()
                : null;
        String prebuiltKey = SNAPSHOT_KEY_GCP + "::" + tenantId + "::system::"
                + (clientId != null ? clientId : "system");

        // Pre-built first, user-scoped copy second. See getUnifiedAwsData for why this order
        // matters: the reverse pinned the view to the first copy it ever made, forever.
        if (!forceRefresh) {
            DashboardData prebuilt = getSnapshot(prebuiltKey);
            if (prebuilt != null) {
                if (!scopedKey.equals(prebuiltKey))
                    saveSnapshot(scopedKey, prebuilt);
                logger.info("Returning pre-built GCP Snapshot for Unified View (Tenant: {} | User: {})", tenantId,
                        isAdmin ? "Global/Admin" : userDetails.getUsername());
                populateAvailableAccounts(prebuilt, userDetails);
                return prebuilt;
            }
            DashboardData cached = getSnapshot(scopedKey);
            if (cached != null) {
                logger.info("Returning Cached GCP Snapshot for Unified View (Tenant: {} | User: {})", tenantId,
                        isAdmin ? "Global/Admin" : userDetails.getUsername());
                populateAvailableAccounts(cached, userDetails);
                return cached;
            }
        }

        if (clientId != null) {
            buildAndSaveUnifiedGcpSnapshotForClient(userDetails.getClientId());
        } else {
            buildAndSaveUnifiedGcpSnapshot();
        }
        DashboardData built = getSnapshot(prebuiltKey);
        if (built == null)
            return createEmptyDashboardData("GCP");
        if (!scopedKey.equals(prebuiltKey))
            saveSnapshot(scopedKey, built);
        populateAvailableAccounts(built, userDetails);
        return built;
    }

    public DashboardData getUnifiedAzureData(ClientUserDetails userDetails, boolean forceRefresh) {

        String tenantId = TenantContext.getCurrentTenant();

        // Determine Cache Key (User + Tenant Scoped)
        boolean isAdmin = isUserAdmin(userDetails);
        String clientScope = (userDetails != null && userDetails.getClientId() != null)
                ? userDetails.getClientId().toString()
                : "system";
        String scopedKey = SNAPSHOT_KEY_AZURE + "::" + tenantId + "::" + clientScope;

        String clientId = (userDetails != null && userDetails.getClientId() != null)
                ? userDetails.getClientId().toString()
                : null;
        String prebuiltKey = SNAPSHOT_KEY_AZURE + "::" + tenantId + "::system::"
                + (clientId != null ? clientId : "system");

        // Pre-built first, user-scoped copy second. See getUnifiedAwsData for why this order
        // matters: the reverse pinned the view to the first copy it ever made, forever. This is the
        // path the reported "Synced Jul 10" Azure dashboard came through.
        if (!forceRefresh) {
            DashboardData prebuilt = getSnapshot(prebuiltKey);
            if (prebuilt != null) {
                if (!scopedKey.equals(prebuiltKey))
                    saveSnapshot(scopedKey, prebuilt);
                logger.info("Returning pre-built Azure Snapshot for Unified View (Tenant: {} | User: {})", tenantId,
                        isAdmin ? "Global/Admin" : userDetails.getUsername());
                populateAvailableAccounts(prebuilt, userDetails);
                return prebuilt;
            }
            DashboardData cached = getSnapshot(scopedKey);
            if (cached != null) {
                logger.info("Returning Cached Azure Snapshot for Unified View (Tenant: {} | User: {})", tenantId,
                        isAdmin ? "Global/Admin" : userDetails.getUsername());
                populateAvailableAccounts(cached, userDetails);
                return cached;
            }
        }

        if (clientId != null) {
            buildAndSaveUnifiedAzureSnapshotForClient(userDetails.getClientId());
        } else {
            buildAndSaveUnifiedAzureSnapshot();
        }
        DashboardData built = getSnapshot(prebuiltKey);
        if (built == null)
            return createEmptyDashboardData("Azure");
        if (!scopedKey.equals(prebuiltKey))
            saveSnapshot(scopedKey, built);
        populateAvailableAccounts(built, userDetails);
        return built;
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
                subAcc.setWastedResourceCount(acc.getWastedResources() != null ? acc.getWastedResources().size() : 0);
                // Use real alert count from alerts-{accountId} snapshot if pre-set, else fall
                // back to optimizationSummary
                subAcc.setActiveAlertCount(acc.getActiveAlertCount() > 0 ? acc.getActiveAlertCount()
                        : (acc.getOptimizationSummary() != null ? (int) acc.getOptimizationSummary().getCriticalAlerts()
                                : 0));
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
        target.setEcs(target.getEcs() + source.getEcs());
        target.setImages(target.getImages() + source.getImages());
        target.setSnapshots(target.getSnapshots() + source.getSnapshots());
        target.setRoute53Zones(target.getRoute53Zones() + source.getRoute53Zones());
        target.setLoadBalancers(target.getLoadBalancers() + source.getLoadBalancers());
        target.setLightsail(target.getLightsail() + source.getLightsail());
        target.setAmplify(target.getAmplify() + source.getAmplify());

        // Keep the frontend-compatibility *Count fields in sync so the dashboard KPI
        // cards (Total Resources, EC2 Instances, S3 Buckets, ...) reflect the
        // aggregated multi-account totals rather than showing 0.
        target.setEc2Count(target.getEc2());
        target.setS3Count(target.getS3Buckets());
        target.setRdsCount(target.getRdsInstances());
        target.setVpcCount(target.getVpc());
        target.setLambdaCount(target.getLambdas());
        target.setEbsCount(target.getEbsVolumes());
        target.setRoute53Count(target.getRoute53Zones());
        target.setLoadBalancerCount(target.getLoadBalancers());
        target.setEcsCount(target.getEcs());
        target.setEksCount(target.getKubernetes());
        target.setLightsailCount(target.getLightsail());
        target.setAmplifyCount(target.getAmplify());
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
            double mtdUsd = currencyConversionService.inrToUsd(data.getMonthToDateSpend());
            double forecastUsd = currencyConversionService.inrToUsd(data.getForecastedSpend());
            double lastMonthUsd = currencyConversionService.inrToUsd(data.getLastMonthSpend());

            totalMTD += mtdUsd;
            totalForecast += forecastUsd;
            totalLastMonth += lastMonthUsd;

            DashboardData.Account subAcc = new DashboardData.Account(acc.getGcpProjectId(), acc.getAccountName(),
                    mtdUsd, forecastUsd);
            subAcc.setResourceInventory(data.getResourceInventory());
            double gcpExtra = computeAdditionalSavings(acc);
            DashboardData.OptimizationSummary gcpOptSum = data.getOptimizationSummary();
            if (gcpExtra > 0) {
                double base = gcpOptSum != null ? gcpOptSum.getTotalPotentialSavings() : 0;
                long alerts = gcpOptSum != null ? gcpOptSum.getCriticalAlerts() : 0;
                gcpOptSum = new DashboardData.OptimizationSummary(base + gcpExtra, alerts);
            }
            subAcc.setOptimizationSummary(gcpOptSum);
            subAcc.setSecurityScore(data.getSecurityScore());
            subAcc.setWastedResourceCount(data.getWastedResources() != null ? data.getWastedResources().size() : 0);
            subAcc.setActiveAlertCount(gcpOptSum != null ? (int) gcpOptSum.getCriticalAlerts() : 0);
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

            if (gcpOptSum != null) {
                totalOptimizationSavings += gcpOptSum.getTotalPotentialSavings();
                totalCriticalAlerts += gcpOptSum.getCriticalAlerts();
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
                    double costUsd = currencyConversionService.inrToUsd(costValue);
                    costHistoryMap.merge(labels.get(j), costUsd, Double::sum);
                }
            }

            if (data.getBillingSummary() != null) {
                for (GcpCostDto bill : data.getBillingSummary()) {
                    // Convert billing summary from INR to USD
                    double billUsd = currencyConversionService.inrToUsd(bill.getAmount());
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

    /**
     * Build a fully-populated single-account snapshot for the multi-account
     * aggregation by combining all granular dashboard pieces (KPI, inventory,
     * regions, cost/billing and optimization).
     *
     * Each granular call is individually cached, so this stays fast while giving
     * the aggregator everything it needs: resource inventory, region status,
     * billing summary and the optimization recommendation lists. Reusing the
     * granular endpoints also keeps MTD spend consistent with the Account
     * Dashboard, Cost and FinOps pages (all read from the same caches).
     */
    private DashboardData getCompleteAccountData(CloudAccount account, boolean forceRefresh,
            ClientUserDetails userDetails) {
        String accountId = account.getAwsAccountId();

        DashboardData kpiData = getGranularKpi(accountId, forceRefresh, userDetails);
        DashboardData invData = getGranularInventory(accountId, forceRefresh, userDetails);
        DashboardData mapData = getGranularMap(accountId, forceRefresh, userDetails);
        DashboardData costData = getGranularCost(accountId, forceRefresh, userDetails);
        DashboardData optData = getGranularOptimization(accountId, forceRefresh, userDetails);

        DashboardData.Account kpiAcc = kpiData != null ? kpiData.getSelectedAccount() : null;
        DashboardData.Account invAcc = invData != null ? invData.getSelectedAccount() : null;
        DashboardData.Account mapAcc = mapData != null ? mapData.getSelectedAccount() : null;
        DashboardData.Account costAcc = costData != null ? costData.getSelectedAccount() : null;
        DashboardData.Account optAcc = optData != null ? optData.getSelectedAccount() : null;

        DashboardData.Account merged = new DashboardData.Account();
        merged.setId(accountId);
        merged.setName(account.getAccountName());

        // Spend, cost history and KPI-level optimization summary come from the KPI
        // endpoint (same source used by the single-account dashboard).
        if (kpiAcc != null) {
            merged.setMonthToDateSpend(kpiAcc.getMonthToDateSpend());
            merged.setForecastedSpend(kpiAcc.getForecastedSpend());
            merged.setLastMonthSpend(kpiAcc.getLastMonthSpend());
            merged.setCostHistory(kpiAcc.getCostHistory());
            merged.setOptimizationSummary(kpiAcc.getOptimizationSummary());
            merged.setActiveAlertCount(kpiAcc.getActiveAlertCount());
        }

        // Resource inventory + CloudWatch status.
        if (invAcc != null) {
            merged.setResourceInventory(invAcc.getResourceInventory());
            merged.setCloudWatchStatus(invAcc.getCloudWatchStatus());
        }

        // Region status (for Cost by Region + map).
        if (mapAcc != null) {
            merged.setRegionStatus(mapAcc.getRegionStatus());
        }

        // Billing summary (Top Services / Billing Summary widgets); prefer the cost
        // endpoint's cost history if the KPI one was empty.
        if (costAcc != null) {
            merged.setBillingSummary(costAcc.getBillingSummary());
            if (merged.getCostHistory() == null && costAcc.getCostHistory() != null) {
                merged.setCostHistory(costAcc.getCostHistory());
            }
        }

        // Optimization detail: recommendation lists, waste, anomalies and savings.
        if (optAcc != null) {
            merged.setEc2Recommendations(optAcc.getEc2Recommendations());
            merged.setEbsRecommendations(optAcc.getEbsRecommendations());
            merged.setLambdaRecommendations(optAcc.getLambdaRecommendations());
            merged.setWastedResources(optAcc.getWastedResources());
            merged.setCostAnomalies(optAcc.getCostAnomalies());
            merged.setSavingsSummary(optAcc.getSavingsSummary());
            if (optAcc.getOptimizationSummary() != null) {
                merged.setOptimizationSummary(optAcc.getOptimizationSummary());
            }
        }

        DashboardData result = new DashboardData();
        result.setSelectedProvider("AWS");
        result.setSelectedAccount(merged);
        return result;
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
                        // This prevents executor thread starvation — threads only run when a permit is
                        // secured.
                        ACCOUNT_SEMAPHORE.acquireUninterruptibly();
                        return CompletableFuture.supplyAsync(() -> {
                            try {
                                // Fetch the COMPLETE account snapshot (KPI + inventory + regions +
                                // billing + optimization) so the aggregated multi-account view shows
                                // all widgets, not just spend. Granular pieces are individually cached
                                // and MTD stays consistent with the Account Dashboard / Cost / FinOps
                                // pages because KPI spend is read from the same AWS_COST_BREAKDOWN cache.
                                return getCompleteAccountData(account, forceRefresh, userDetails);
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
                        logger.warn(
                                "[MultiAccount] Timed out waiting for all accounts after 5 min — returning partial results: {}",
                                ex.getMessage());
                        return null;
                    })
                    .join();

            failedAccounts.addAll(failedAccountsConcurrent);

            List<DashboardData> accountDataList = futures.stream()
                    .map(f -> {
                        try {
                            return f.getNow(null);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (accountDataList.isEmpty()) {
                throw new RuntimeException("Failed to fetch data from all selected AWS accounts");
            }

            //
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

        // Combined optimization detail across all selected accounts — drives the KPI
        // recommendation counts (EC2 Rightsizing, EBS Waste, Total Recommendations,
        // Cost Anomalies) in the aggregated view.
        List<DashboardData.OptimizationRecommendation> allEc2Recommendations = new ArrayList<>();
        List<DashboardData.OptimizationRecommendation> allEbsRecommendations = new ArrayList<>();
        List<DashboardData.OptimizationRecommendation> allLambdaRecommendations = new ArrayList<>();
        List<DashboardData.WastedResource> allWastedResources = new ArrayList<>();
        List<DashboardData.CostAnomaly> allCostAnomalies = new ArrayList<>();

        for (DashboardData data : accountDataList) {
            DashboardData.Account account = data.getSelectedAccount();
            if (account == null)
                continue;

            if (account.getEc2Recommendations() != null) {
                allEc2Recommendations.addAll(account.getEc2Recommendations());
            }
            if (account.getEbsRecommendations() != null) {
                allEbsRecommendations.addAll(account.getEbsRecommendations());
            }
            if (account.getLambdaRecommendations() != null) {
                allLambdaRecommendations.addAll(account.getLambdaRecommendations());
            }
            if (account.getWastedResources() != null) {
                allWastedResources.addAll(account.getWastedResources());
            }
            if (account.getCostAnomalies() != null) {
                allCostAnomalies.addAll(account.getCostAnomalies());
            }

            DashboardData.Account subAcc = new DashboardData.Account(account.getId(), account.getName(),
                    account.getMonthToDateSpend(),
                    account.getForecastedSpend());
            subAcc.setResourceInventory(account.getResourceInventory());
            subAcc.setOptimizationSummary(account.getOptimizationSummary());
            subAcc.setSecurityScore(account.getSecurityScore());
            subAcc.setWastedResourceCount(
                    account.getWastedResources() != null ? account.getWastedResources().size() : 0);
            // Prefer real alert count set from alerts cache; fall back to
            // optimizationSummary
            subAcc.setActiveAlertCount(account.getActiveAlertCount() > 0 ? account.getActiveAlertCount()
                    : (account.getOptimizationSummary() != null
                            ? (int) account.getOptimizationSummary().getCriticalAlerts()
                            : 0));
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
            }
            // Use real alert count (from alerts cache) over
            // optimizationSummary.criticalAlerts
            totalCriticalAlerts += account.getActiveAlertCount() > 0
                    ? account.getActiveAlertCount()
                    : (account.getOptimizationSummary() != null
                            ? (long) account.getOptimizationSummary().getCriticalAlerts()
                            : 0);
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
                .filter(d -> d.getSelectedAccount() != null && d.getSelectedAccount().getSecurityScore() > 0)
                .mapToInt(d -> d.getSelectedAccount().getSecurityScore())
                .average()
                .orElse(100.0);
        aggregatedAccount.setSecurityScore(avgSecurityScore);

        aggregatedAccount.setEc2Recommendations(allEc2Recommendations);
        aggregatedAccount.setEbsRecommendations(allEbsRecommendations);
        aggregatedAccount.setLambdaRecommendations(allLambdaRecommendations);
        aggregatedAccount.setCostAnomalies(allCostAnomalies);
        aggregatedAccount.setWastedResources(allWastedResources);
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
            //

            //
        } catch (InterruptedException ie) {
            // Restore the interrupt flag so the per-account loop's isInterrupted() check
            // fires
            // and breaks out of the loop. Without this, swallowing InterruptedException
            // clears
            // the flag and the loop continues running even after tenantFuture.cancel(true).
            Thread.currentThread().interrupt();
            long duration = System.currentTimeMillis() - startTime;
            logger.warn(
                    "❌ [Single-Account-Fetch] Interrupted | Tenant: {} | Account: {} | Time: {}ms — propagating interrupt",
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
                        double extraSavingsFull = computeAdditionalSavings(account);
                        if (extraSavingsFull > 0) {
                            optimizationSummary = new DashboardData.OptimizationSummary(
                                    optimizationSummary.getTotalPotentialSavings() + extraSavingsFull,
                                    optimizationSummary.getCriticalAlerts());
                        }

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
            Map<String, Integer> counts = groupedResources.stream()
                    .collect(Collectors.toMap(
                            DashboardData.ServiceGroupDto::getServiceType,
                            group -> group.getResources().size(),
                            (existing, replacement) -> existing)); // Handle duplicates if any
            return buildResourceInventory(counts);
        });
    }

    /**
     * The Resource Inventory widget straight from the plane, or null when it cannot answer.
     *
     * <p>
     * Null rather than an empty payload, for the same reason the cloud list does it: empty
     * is a valid answer meaning "this account owns nothing", and conflating the two would
     * let a cold plane blank the dashboard instead of falling through to the describe path.
     *
     * <p>
     * CloudWatch status is deliberately carried over from the previous snapshot instead of
     * being recomputed. It is not part of the plane and costs a per-region fan-out, so
     * recomputing it on every dashboard load to keep the counts live would trade the problem
     * being fixed for a slower one. Counts change constantly; alarm status does not, and the
     * describe path below refreshes it on its normal schedule.
     */
    private DashboardData inventoryFromPlane(String accountId, String cacheKey) {
        if (inventoryProjection == null) {
            return null;
        }
        try {
            CloudAccount account = getAccount(accountId);
            if (!"AWS".equalsIgnoreCase(account.getProvider())
                    || !inventoryProjection.isFresh(account.getId())) {
                return null;
            }
            Map<String, Long> planeCounts = inventoryProjection.countsByType(account.getId());
            if (planeCounts.isEmpty()) {
                return null;
            }
            Map<String, Integer> counts = new HashMap<>();
            planeCounts.forEach((type, count) -> counts.put(type, count.intValue()));

            DashboardData data = new DashboardData();
            data.setSelectedProvider(account.getProvider());

            DashboardData.Account mainAccount = new DashboardData.Account();
            mainAccount.setId(accountId);
            mainAccount.setName(account.getAccountName());

            DashboardData.ResourceInventory inventory = buildResourceInventory(counts);
            mainAccount.setResourceInventory(inventory);
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

            DashboardData previous = getSnapshot(cacheKey);
            if (previous != null && previous.getSelectedAccount() != null) {
                mainAccount.setCloudWatchStatus(previous.getSelectedAccount().getCloudWatchStatus());
            }

            data.setSelectedAccount(mainAccount);
            data.setLastUpdated(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));

            logger.info("✅ [Inventory] Resource inventory served from plane | Account: {} "
                    + "({} types, 0 AWS calls)", accountId, counts.size());
            return data;
        } catch (Exception e) {
            // Never let the new path break the old one.
            logger.warn("[Inventory] Plane read failed for dashboard inventory {} — "
                    + "falling back: {}", accountId, e.getMessage());
            return null;
        }
    }

    /**
     * Maps display-type counts onto the inventory widget's fields.
     *
     * <p>
     * Delegates to {@link com.xammer.cloud.service.inventory.ResourceInventoryMapper} so the
     * describe path, the inventory plane and the WebSocket push cannot disagree.
     */
    private DashboardData.ResourceInventory buildResourceInventory(Map<String, Integer> counts) {
        return com.xammer.cloud.service.inventory.ResourceInventoryMapper.fromCounts(counts);
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

    /**
     * Runs a single AWS lookup, falling back to {@code fallback} if it fails.
     *
     * <p>
     * Exists so one missing IAM permission degrades one field instead of discarding a whole
     * identity. AccessDenied is logged at debug: on an account whose role lacks a permission it
     * fires once per user per refresh, and at warn level it buries everything else in the log.
     */
    private <T> T quietly(java.util.function.Supplier<T> call, T fallback, String what) {
        try {
            return call.get();
        } catch (software.amazon.awssdk.awscore.exception.AwsServiceException e) {
            if (e.statusCode() == 403) {
                logger.debug("Skipped {} — access denied by the account's role.", what);
            } else {
                logger.warn("Skipped {} — {}", what, e.getMessage());
            }
            return fallback;
        } catch (Exception e) {
            logger.warn("Skipped {} — {}", what, e.getMessage());
            return fallback;
        }
    }

    /**
     * How long a request will wait for the privilege audit before answering without it.
     *
     * <p>
     * Chosen so the common case is unchanged and the pathological case stops failing. The audit
     * is two IAM calls per distinct managed policy, run serially against a low-TPS global
     * endpoint; a typical account finishes inside a couple of seconds and still returns fully
     * scored. An account with a few hundred distinct policies needs 20-40s on its own, which is
     * past every timeout between here and the browser — so past this deadline the inventory is
     * returned and the audit finishes in the background, rewriting the cache entry for the next
     * load. Comfortably under the ~60s gateway budget, leaving room for the inventory phase.
     */
    private static final int AUDIT_WAIT_SECONDS = 12;

    /** Redis lifetime for an IAM scan, in minutes. */
    private static final int IAM_CACHE_MINUTES = 60;

    /**
     * How old the durable snapshot may be before a read rebuilds it behind the request.
     *
     * <p>
     * This is a refresh trigger, not an expiry: past this age the snapshot is still served, and
     * the rescan happens in the background. Nothing here ever makes a user wait on IAM for data
     * we already hold, because the failure this whole tier exists to remove is the request that
     * waits and then times out anyway.
     *
     * <p>
     * Six hours rather than the Redis TTL's sixty minutes. The two windows answer different
     * questions — Redis decides how long to avoid a database read, this decides how long an
     * access review may go unverified — and tying them together is what made the tab re-scan
     * roughly every visit: Redis is also the session store, so it is restarted and evicted far
     * more often than IAM actually changes. Real IAM changes do not wait for this window at all;
     * they arrive as events. See {@code InventoryEventListener}.
     */
    private static final int IAM_SNAPSHOT_REFRESH_MINUTES = 6 * 60;

    /**
     * Shortest gap between two change-triggered rescans of the same account.
     *
     * <p>
     * Terraform applying an IAM module emits dozens of {@code CreateRole} and
     * {@code AttachRolePolicy} calls in under a minute. Rescanning per event would turn one
     * deploy into dozens of full IAM scans against a low-TPS global endpoint, which is how a
     * freshness feature becomes a throttling incident. One scan a few minutes after the burst
     * sees the same end state.
     */
    private static final long IAM_CHANGE_RESCAN_COOLDOWN_MS = 3 * 60 * 1000L;

    /** Earliest time a change event may trigger another rescan, per account. */
    private final Map<String, Long> iamChangeRescanFloor = new ConcurrentHashMap<>();

    /**
     * Scans in flight, so a retry joins the running scan instead of starting a second one.
     *
     * <p>
     * Without this, the Reload button was a way to make the problem worse: each press queued
     * another full scan onto the same thread pool and the same IAM throttle budget, so the more
     * a user retried the slower every attempt became. In-process only — it collapses the retry
     * storm from one browser, which is the case that actually happens, rather than coordinating
     * across replicas.
     */
    private final Map<String, CompletableFuture<DashboardData.IamDetail>> iamScansInFlight =
            new ConcurrentHashMap<>();

    /**
     * IAM identities for an account, with risk scoring when it fits in the time available.
     *
     * <p>
     * Two phases with very different costs. The inventory — list users, list roles, and the
     * policy/group/MFA lookups per identity — is a few hundred calls on a parallel stream and
     * lands in seconds. The privilege audit is {@code GetPolicy} plus {@code GetPolicyVersion}
     * per distinct policy, strictly serial inside {@link IamPrivilegeAnalyzer}, and on a large
     * estate takes longer than any timeout between this method and the browser.
     *
     * <p>
     * Previously both ran before anything was cached, and the single {@code redisCache.put} was
     * the last statement in the method. A scan killed at the gateway therefore cached nothing,
     * so every retry paid the full cost again and the feature could never converge on a warm
     * cache — the Identity tab was permanently broken for exactly the accounts that most need an
     * access review. Now the inventory is cached the moment it exists, and the audit is a
     * separate pass that rewrites the entry when it finishes. The worst case degrades from "no
     * identities at all" to "identities without risk badges, scored shortly".
     *
     * <p>
     * Read order is Redis, then the durable {@code cached_data} snapshot, then a live scan — and
     * only the last of those makes anyone wait. Redis alone was not a cache for this feature so
     * much as a sixty-minute grace period: it is the same instance that stores HTTP sessions, so
     * it is restarted on every deploy and evicted under pressure, and with nothing behind it the
     * next visitor after any of that paid a full IAM scan and frequently hit the request deadline
     * instead of seeing identities. An account that has been scanned once now answers from disk
     * in milliseconds for as long as the connection exists, and goes stale on its own schedule
     * rather than the session store's.
     *
     * <p>
     * Not {@code @Async} any more, deliberately. The body no longer blocks — Redis read, at most
     * one indexed row read, then {@code supplyAsync} for the scan itself — so the hop bought
     * nothing, and it cost correctness: {@code backgroundTaskExecutor} is the one executor in
     * {@code AwsConfig} without {@code tenantTaskDecorator}, and {@code TenantContext} is an
     * {@code InheritableThreadLocal}, so a pooled thread carries whichever tenant happened to
     * create it. Reading the snapshot there would have queried an arbitrary tenant's database.
     * Running on the caller's thread means the tenant is the caller's, which is the only correct
     * answer. The scan is still handed to the executor, and the self-invocation from
     * {@link #getDashboardData} never went through the proxy anyway, so nothing became
     * synchronous that was not already.
     */
    public CompletableFuture<DashboardData.IamDetail> getIamDetails(CloudAccount account, boolean forceRefresh) {
        String cacheKey = iamCacheKey(account.getAwsAccountId());

        if (!forceRefresh) {
            Optional<DashboardData.IamDetail> cachedData = redisCache.get(cacheKey, DashboardData.IamDetail.class);
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(withRefreshFlag(cachedData.get(), account));
            }

            // Redis is cold. That is the common case rather than the exceptional one — a deploy,
            // an eviction, or simply an hour passing — and before the durable tier existed it
            // meant a full IAM scan with the user watching.
            IamSnapshot snapshot = readIamSnapshot(cacheKey);
            if (snapshot != null) {
                // Re-warmed before the flags are set, so the copy in Redis stays a clean record
                // of the scan and does not bake in one request's "a rescan is running" state.
                redisCache.put(cacheKey, snapshot.detail(), IAM_CACHE_MINUTES);
                if (snapshot.olderThanMinutes(IAM_SNAPSHOT_REFRESH_MINUTES)) {
                    logger.info("[IAM] Snapshot for account {} is from {} — serving it and "
                            + "rescanning in the background", account.getAwsAccountId(),
                            snapshot.writtenAt());
                    startIamScan(account, cacheKey, "stale snapshot");
                }
                return CompletableFuture.completedFuture(withRefreshFlag(snapshot.detail(), account));
            }
        }

        return startIamScan(account, cacheKey, forceRefresh ? "forced refresh" : "no snapshot yet");
    }

    /**
     * The cache key for one account's identity snapshot, shared by Redis and {@code cached_data}.
     *
     * <p>
     * Version suffix bumped when permission-risk fields were added. Entries written by the
     * previous build carry no risk data, and the frontend's staleness guard only checks that
     * iamDetails exists — so without a new key an old entry would render every identity as
     * unflagged, which is precisely the false reassurance this feature exists to remove.
     */
    private static String iamCacheKey(String awsAccountId) {
        return "iamDetailsV2-" + awsAccountId;
    }

    /**
     * Starts a scan, or hands back the one already running for this account.
     *
     * <p>
     * computeIfAbsent rather than get-then-put: two requests arriving together both saw an empty
     * map under the naive version and both started a full scan, which is the exact pile-up this
     * is here to prevent. The mapping function only allocates — the scan itself runs on the
     * executor — so nothing blocks while the bin lock is held.
     */
    private CompletableFuture<DashboardData.IamDetail> startIamScan(
            CloudAccount account, String cacheKey, String reason) {
        return iamScansInFlight.computeIfAbsent(account.getAwsAccountId(), key -> {
            logger.info("[IAM] Starting scan for account {} ({})", key, reason);
            // backgroundTaskExecutor rather than the common pool. supplyAsync with no executor put
            // this whole scan on ForkJoinPool.commonPool, where the nested parallelStream calls
            // compete for the same few workers, every blocking IAM call occupies a carrier thread
            // with no compensation, and one account's scan slowed every other parallel stream in
            // the process.
            CompletableFuture<DashboardData.IamDetail> started = runIamScan(account, cacheKey);
            // Two-arg remove so a scan that has already been replaced by a newer one cannot evict
            // its successor on completion.
            started.whenComplete((r, e) -> iamScansInFlight.remove(key, started));
            return started;
        });
    }

    /**
     * Rescans an account's identities because IAM changed, not because time passed.
     *
     * <p>
     * The reason the tab is allowed a six-hour refresh window at all. A window that long would
     * be indefensible on its own — an access review showing a role that was deleted this morning
     * is worse than no review — but IAM mutations reach us as events within seconds, so the
     * window only ever governs what we might have missed, never what we were told about.
     *
     * <p>
     * Force-scans rather than evicting. Eviction would leave the next visitor to pay for the
     * change, which is the behaviour being removed; this way the fresh result is already in both
     * tiers before anyone asks. Cooldown-guarded because IAM changes arrive in bursts — see
     * {@link #IAM_CHANGE_RESCAN_COOLDOWN_MS}.
     *
     * @return true when a rescan was started, false when one is already covered by the cooldown
     */
    public boolean refreshIamDetailsAfterChange(CloudAccount account) {
        String accountId = account == null ? null : account.getAwsAccountId();
        if (accountId == null || accountId.isBlank()) {
            return false;
        }

        long now = System.currentTimeMillis();
        // compute rather than get-then-put. ConcurrentHashMap runs the remapping function under
        // the bin lock, so exactly one caller can claim the window even when two event batches for
        // the same account are processed at once — with get-then-put both would read the same
        // expired floor and both would scan.
        boolean[] claimed = { false };
        iamChangeRescanFloor.compute(accountId, (key, floor) -> {
            if (floor != null && floor > now) {
                return floor;
            }
            claimed[0] = true;
            return now + IAM_CHANGE_RESCAN_COOLDOWN_MS;
        });

        if (!claimed[0]) {
            logger.debug("[IAM] Change event for account {} is inside the rescan cooldown, skipping",
                    accountId);
            return false;
        }

        startIamScan(account, iamCacheKey(accountId), "IAM change event");
        return true;
    }

    /**
     * Drops an account's identity snapshot from both tiers.
     *
     * <p>
     * For disconnection rather than for freshness: while a connection exists a stale snapshot is
     * strictly better than none, so nothing on the read path calls this. Removing the durable row
     * matters because it has no expiry — left behind, it would still answer for an account whose
     * credentials were revoked.
     */
    public void evictIamDetails(String awsAccountId) {
        if (awsAccountId == null || awsAccountId.isBlank()) {
            return;
        }
        String cacheKey = iamCacheKey(awsAccountId);
        try {
            redisCache.evict(cacheKey);
        } catch (Exception e) {
            logger.debug("[IAM] Could not evict {} from Redis: {}", cacheKey, e.getMessage());
        }
        try {
            cachedDataRepository.deleteById(cacheKey);
        } catch (Exception e) {
            logger.debug("[IAM] Could not delete the snapshot row {}: {}", cacheKey, e.getMessage());
        }
        iamChangeRescanFloor.remove(awsAccountId);
    }

    /**
     * Tells the caller whether a newer answer is already on its way.
     *
     * <p>
     * Set on the response rather than inferred by the client, because only this side knows a scan
     * is running. Without it a cached payload and a cached-but-superseded payload look identical,
     * so the UI would settle on the old one until someone pressed Reload — and the whole point of
     * serving stale is that the fresh result arrives without being asked for.
     */
    private DashboardData.IamDetail withRefreshFlag(DashboardData.IamDetail detail, CloudAccount account) {
        if (detail != null) {
            detail.setRefreshing(iamScansInFlight.containsKey(account.getAwsAccountId()));
        }
        return detail;
    }

    /**
     * A durable snapshot, with the age of the row it came from.
     *
     * <p>
     * The row's {@code lastUpdated} column is the authority on age rather than the DTO field,
     * because the column is written by the code that saves and cannot be wrong about when that
     * happened. The DTO field exists to be shown to a person.
     */
    private record IamSnapshot(DashboardData.IamDetail detail, LocalDateTime writtenAt) {
        boolean olderThanMinutes(long minutes) {
            // An unstamped row is of unknown age. On an access-review surface that counts as
            // due for a rescan rather than as current — the snapshot is still served either way,
            // so treating it as old costs one background scan and nothing else.
            return writtenAt == null || writtenAt.isBefore(LocalDateTime.now().minusMinutes(minutes));
        }
    }

    /**
     * Reads the durable snapshot, or null when there is nothing usable.
     *
     * <p>
     * Every failure here is non-fatal by design: the caller falls through to a live scan, which
     * is exactly what happened on every request before this tier existed.
     */
    private IamSnapshot readIamSnapshot(String cacheKey) {
        try {
            Optional<CachedData> row = cachedDataRepository.findById(cacheKey);
            if (row.isEmpty() || !StringUtils.hasText(row.get().getJsonData())) {
                return null;
            }
            DashboardData.IamDetail detail = objectMapper.readValue(
                    row.get().getJsonData(), DashboardData.IamDetail.class);
            return new IamSnapshot(detail, row.get().getLastUpdated());
        } catch (Exception e) {
            // Most likely a shape change between builds. Deleted rather than left to fail on
            // every subsequent read, since the scan that replaces it will write the new shape.
            logger.warn("[IAM] Unreadable snapshot {} — discarding it and scanning live: {}",
                    cacheKey, e.getMessage());
            try {
                cachedDataRepository.deleteById(cacheKey);
            } catch (Exception ignored) {
                // Nothing to do: the live scan overwrites this row anyway.
            }
            return null;
        }
    }

    /**
     * Writes a scan result to Redis and to {@code cached_data}.
     *
     * @param tenantId the tenant captured on the thread that asked for the scan; see
     *     {@link #runIamScan} for why it cannot be read from {@code TenantContext} here
     */
    private void writeIamCache(String cacheKey, DashboardData.IamDetail detail, String tenantId) {
        detail.setLastUpdated(Instant.now().toString());
        redisCache.put(cacheKey, detail, IAM_CACHE_MINUTES);

        String previous = TenantContext.getCurrentTenant();
        try {
            if (tenantId != null) {
                TenantContext.setCurrentTenant(tenantId);
            }
            CachedData row = cachedDataRepository.findById(cacheKey).orElseGet(CachedData::new);
            row.setCacheKey(cacheKey);
            row.setJsonData(objectMapper.writeValueAsString(detail));
            row.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(row);
            logger.info("[IAM] Snapshot persisted for {}", cacheKey);
        } catch (Exception e) {
            // Redis already holds the result, so this request and the next hour are unaffected.
            // All that is lost is the survive-a-restart property, which is worth a warning and
            // not worth failing a scan over.
            logger.warn("[IAM] Could not persist the snapshot for {}: {}", cacheKey, e.getMessage());
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentTenant(previous);
            }
        }
    }

    /**
     * Inventory, then as much of the audit as fits in {@link #AUDIT_WAIT_SECONDS}.
     *
     * <p>
     * Composed rather than blocking on {@code get(timeout)}. Waiting synchronously would park a
     * pool thread for the whole deadline, and the audit it is waiting for runs on that same pool —
     * so under load the waiters could occupy every thread while the work they need sits in the
     * queue behind them. {@code completeOnTimeout} hands back the inventory without anyone
     * waiting, and the audit continues to its own cache write regardless.
     */
    private CompletableFuture<DashboardData.IamDetail> runIamScan(CloudAccount account, String cacheKey) {
        String accountId = account.getAwsAccountId();
        IamClient iam = awsClientProvider.getIamClient(account);

        // Captured here, on the thread that asked for the scan, and carried into both cache
        // writes below. It cannot be read inside them: they run on backgroundTaskExecutor, which
        // is the one executor in AwsConfig without tenantTaskDecorator, and TenantContext is an
        // InheritableThreadLocal — a pooled thread holds whichever tenant happened to be set when
        // that thread was created, which after the pool warms up is effectively arbitrary. Saving
        // the snapshot under it would write one customer's identity inventory into another
        // customer's database.
        String tenantId = TenantContext.getCurrentTenant();

        return CompletableFuture.supplyAsync(() -> {
            long startedAt = System.currentTimeMillis();
            DashboardData.IamDetail inventory = scanIamInventory(iam, account);
            logger.info("[IAM] Inventory for account {} took {} ms ({} users, {} roles)",
                    accountId, System.currentTimeMillis() - startedAt,
                    inventory.getUsers().size(), inventory.getRoles().size());

            // Cached before the audit runs, which is the whole point: from here on, a timeout
            // costs the risk badges rather than the entire page.
            inventory.setPermissionAnalysisPending(true);
            inventory.setPermissionAnalysisNote(
                    "Identities are listed. The access assessment is still running and will appear here "
                            + "shortly.");
            writeIamCache(cacheKey, inventory, tenantId);
            return inventory;
        }, backgroundTaskExecutor).thenCompose(inventory -> {
            // The analyzer mutates the DTOs it is given, so it gets its own copy. Handing it the
            // objects that may be serialized into a response would mean writing risk fields on one
            // thread while Jackson reads them on another.
            DashboardData.IamDetail auditTarget = copyForAudit(inventory);
            if (auditTarget == null) {
                return CompletableFuture.completedFuture(inventory);
            }

            CompletableFuture<DashboardData.IamDetail> audit = CompletableFuture.supplyAsync(() -> {
                long auditStart = System.currentTimeMillis();
                try {
                    var outcome = iamPrivilegeAnalyzer.analyze(iam, auditTarget.getUsers(), auditTarget.getRoles());
                    auditTarget.setPermissionAnalysisDegraded(outcome.degraded());
                    auditTarget.setPermissionAnalysisNote(outcome.note());
                } catch (Exception e) {
                    logger.error("[IAM] Privilege analysis failed for account {}; serving inventory without "
                            + "risk scoring.", accountId, e);
                    auditTarget.setPermissionAnalysisDegraded(true);
                    auditTarget.setPermissionAnalysisNote(
                            "Permission analysis did not complete for this account, so identities are shown "
                                    + "without a risk assessment.");
                }
                auditTarget.setPermissionAnalysisPending(false);
                // Overwrites the inventory-only entry written above in both tiers, so the next
                // load is fully scored whether or not the request that started this is still
                // listening — and whether or not Redis survives until then.
                writeIamCache(cacheKey, auditTarget, tenantId);
                logger.info("[IAM] Privilege audit for account {} took {} ms", accountId,
                        System.currentTimeMillis() - auditStart);
                return auditTarget;
            }, backgroundTaskExecutor);

            return audit.completeOnTimeout(inventory, AUDIT_WAIT_SECONDS, TimeUnit.SECONDS);
        });
    }

    /**
     * Deep copy via the cache's own serializer.
     *
     * <p>
     * Round-tripping JSON rather than writing a copy constructor per DTO: these types already
     * have to survive that round trip to be cacheable, so a copy that fails here would be a
     * caching bug worth failing loudly on. Returns null when it cannot copy, and the caller
     * skips the audit rather than risk handing the analyzer the live objects.
     */
    private DashboardData.IamDetail copyForAudit(DashboardData.IamDetail source) {
        try {
            return objectMapper.readValue(
                    objectMapper.writeValueAsString(source), DashboardData.IamDetail.class);
        } catch (Exception e) {
            logger.error("[IAM] Could not copy inventory for the privilege audit; skipping scoring.", e);
            return null;
        }
    }

    /**
     * The inventory half: who exists, what is attached to them, and whether they carry MFA.
     *
     * <p>
     * No policy documents are read here — that is the audit's job, and keeping the two apart is
     * what lets the cheap half be cached on its own.
     */
    private DashboardData.IamDetail scanIamInventory(IamClient iam, CloudAccount account) {
        // Group policies, resolved once per group and shared across its members.
        //
        // Groups are the normal place administrator access is granted, so without these the
        // permission audit would clear the account's actual administrators. Cached because
        // membership overlaps heavily — an account with 60 users typically has a handful of
        // groups, and resolving per user would repeat the same two calls dozens of times.
        // ConcurrentHashMap because the user mapping below runs on a parallel stream.
        java.util.Map<String, List<DashboardData.IamPolicyDetail>> groupPolicyCache =
                new java.util.concurrent.ConcurrentHashMap<>();
        java.util.function.Function<String, List<DashboardData.IamPolicyDetail>> policiesForGroup =
                groupName -> groupPolicyCache.computeIfAbsent(groupName, g -> {
                    List<DashboardData.IamPolicyDetail> inherited = new ArrayList<>();
                    inherited.addAll(quietly(
                            () -> iam.listAttachedGroupPolicies(r -> r.groupName(g)).attachedPolicies()
                                    .stream()
                                    .map(p -> new DashboardData.IamPolicyDetail(
                                            p.policyName(), p.policyArn(), "Managed", g))
                                    .collect(Collectors.toList()),
                            new ArrayList<>(), "attached policies for group " + g));
                    inherited.addAll(quietly(
                            () -> iam.listGroupPolicies(r -> r.groupName(g)).policyNames().stream()
                                    .map(p -> new DashboardData.IamPolicyDetail(p, null, "Inline", g))
                                    .collect(Collectors.toList()),
                            new ArrayList<>(), "inline policies for group " + g));
                    return inherited;
                });

        // Each sub-call is guarded separately rather than the identity as a whole. Previously a
        // single failure — typically AccessDenied, because the read-only onboarding role did
        // not grant iam:ListAttachedUserPolicies — discarded the user entirely, so an account
        // missing one permission rendered an empty user list instead of a partial one. Dropping
        // an identity from a security view is the worst possible failure mode: it reports
        // "nobody here" rather than "could not read this".
        // Paginated. iam.listUsers() returns one page — 100 users by default — and IAM does
        // not warn about the rest, so on any account past that boundary the Identity tab was
        // silently reporting a subset of the estate as if it were the whole thing. On a
        // security view that is worse than an error: the administrator who was never listed
        // reads as an administrator who does not exist.
        List<software.amazon.awssdk.services.iam.model.User> allUsers = new ArrayList<>();
        iam.listUsersPaginator().users().forEach(allUsers::add);

        List<DashboardData.IamUserDetail> users = allUsers.parallelStream().map(user -> {
            List<String> groups = quietly(
                    () -> iam.listGroupsForUser(r -> r.userName(user.userName()))
                            .groups().stream().map(g -> g.groupName()).collect(Collectors.toList()),
                    new ArrayList<>(), "groups for user " + user.userName());

            List<DashboardData.IamPolicyDetail> policies = new ArrayList<>();
            policies.addAll(quietly(
                    () -> iam.listAttachedUserPolicies(r -> r.userName(user.userName())).attachedPolicies()
                            .stream()
                            .map(p -> new DashboardData.IamPolicyDetail(p.policyName(), p.policyArn(), "Managed"))
                            .collect(Collectors.toList()),
                    new ArrayList<>(), "attached policies for user " + user.userName()));
            policies.addAll(quietly(
                    () -> iam.listUserPolicies(r -> r.userName(user.userName())).policyNames().stream()
                            .map(p -> new DashboardData.IamPolicyDetail(p, null, "Inline"))
                            .collect(Collectors.toList()),
                    new ArrayList<>(), "inline policies for user " + user.userName()));

            // Merged into the same list so the analyzer scores effective permission rather
            // than directly-attached permission. Each carries inheritedFrom, so the card can
            // still say the grant lives on the group. Copied per user, because the analyzer
            // writes its verdict onto these objects and sharing one instance across every
            // member of a group would have them racing on the same fields.
            for (String group : groups) {
                for (DashboardData.IamPolicyDetail inherited : policiesForGroup.apply(group)) {
                    policies.add(new DashboardData.IamPolicyDetail(
                            inherited.getPolicyName(), inherited.getPolicyArn(),
                            inherited.getType(), inherited.getInheritedFrom()));
                }
            }

            boolean mfaEnabled = quietly(
                    () -> !iam.listMFADevices(r -> r.userName(user.userName())).mfaDevices().isEmpty(),
                    false, "MFA devices for user " + user.userName());

            return new DashboardData.IamUserDetail(
                    user.userName(), user.userId(), user.arn(),
                    user.createDate().toString(),
                    user.passwordLastUsed() != null ? user.passwordLastUsed().toString() : "Never",
                    groups, policies, mfaEnabled);
        }).collect(Collectors.toList());

        // Paginated for the same reason as users above. Roles hit the 100 boundary sooner than
        // users on most accounts, because every service integration leaves one behind.
        List<software.amazon.awssdk.services.iam.model.Role> allRoles = new ArrayList<>();
        iam.listRolesPaginator().roles().forEach(allRoles::add);

        List<DashboardData.IamRoleDetail> roles = allRoles.parallelStream()
                .filter(r -> !r.path().startsWith("/aws-service-role/"))
                .map(role -> {
                    List<DashboardData.IamPolicyDetail> policies = new ArrayList<>();
                    policies.addAll(quietly(
                            () -> iam.listAttachedRolePolicies(r -> r.roleName(role.roleName()))
                                    .attachedPolicies().stream()
                                    .map(p -> new DashboardData.IamPolicyDetail(p.policyName(), p.policyArn(),
                                            "Managed"))
                                    .collect(Collectors.toList()),
                            new ArrayList<>(), "attached policies for role " + role.roleName()));
                    policies.addAll(quietly(
                            () -> iam.listRolePolicies(r -> r.roleName(role.roleName())).policyNames().stream()
                                    .map(p -> new DashboardData.IamPolicyDetail(p, null, "Inline"))
                                    .collect(Collectors.toList()),
                            new ArrayList<>(), "inline policies for role " + role.roleName()));

                    return new DashboardData.IamRoleDetail(
                            role.roleName(), role.roleId(), role.arn(),
                            role.createDate().toString(), role.description(), policies);
                }).collect(Collectors.toList());

        return new DashboardData.IamDetail(users, roles);
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
                    List<DashboardData.WastedResource> wasted = wastedFuture.join();
                    List<DashboardData.OptimizationRecommendation> allRecs = Stream
                            .of(ec2RecsFuture.join(), ebsRecsFuture.join(), lambdaRecsFuture.join())
                            .flatMap(List::stream)
                            .collect(Collectors.toList());

                    // Skip resources whose cost could not be priced, so an unpriced finding is
                    // never counted as if the resource were free.
                    double wasteSavings = wasted.stream()
                            .filter(DashboardData.WastedResource::isBillableWaste)
                            .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                            .sum();

                    // Exclude rightsizing savings for resources already booked at full cost in
                    // the waste list. An idle EC2 instance previously contributed its entire
                    // monthly price as waste AND a downsize delta as rightsizing; the same
                    // applied to unattached EBS volumes, which also appear in ebsDiskSavings
                    // below. Deleting and resizing are mutually exclusive, so the full-cost
                    // waste figure wins.
                    Set<String> wastedResourceIds = wasted.stream()
                            .map(DashboardData.WastedResource::getResourceId)
                            .filter(id -> id != null && !id.isBlank())
                            .collect(Collectors.toSet());

                    double rightsizingSavings = allRecs.stream()
                            .filter(r -> r.getResourceId() == null
                                    || !wastedResourceIds.contains(r.getResourceId()))
                            .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
                            .sum();

                    // AutoSpotting potential savings — served from Redis cache (fast)
                    double spotPotentialSavings = 0.0;
                    if (cloudAccountId != null) {
                        try {
                            com.xammer.cloud.dto.autospotting.CostResponse spotCost = autoSpottingService
                                    .getCostData(cloudAccountId);
                            if (spotCost != null && spotCost.getSummary() != null) {
                                double hourly = spotCost.getSummary().getTotalPotentialSavings();
                                spotPotentialSavings = hourly * 24 * 30; // convert hourly → monthly
                            }
                        } catch (Exception e) {
                            logger.warn("Could not fetch AutoSpotting potential savings for dashboard: {}",
                                    e.getMessage());
                        }
                    }

                    // EBS Disk Rightsizing savings — cache-only, no live scan.
                    // Unattached volumes are excluded because findUnattachedEbsVolumes already
                    // counts them in wasteSavings above.
                    double ebsDiskSavings = 0.0;
                    if (cloudAccountId != null) {
                        try {
                            String awsAccId = cloudAccountRepository.findById(cloudAccountId)
                                    .map(com.xammer.cloud.domain.CloudAccount::getAwsAccountId)
                                    .orElse(null);
                            ebsDiskSavings = ebsDiskRightsizingService
                                    .getTotalPotentialSavingsExcluding(awsAccId, wastedResourceIds);
                        } catch (Exception e) {
                            logger.warn("Could not fetch EBS disk rightsizing savings for dashboard: {}",
                                    e.getMessage());
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

                    double totalPotential = rightsizingSavings + wasteSavings + spotPotentialSavings + ebsDiskSavings;
                    return new DashboardData.SavingsSummary(0.0, totalPotential, suggestions);
                });
    }

    /**
     * Sums only resize savings from a disk-rightsizing scan.
     *
     * <p>Excludes {@code DELETE_UNATTACHED}, which the waste report already books at full monthly
     * cost, and {@code EXPAND}, which raises cost rather than saving. Also skips rows the scanner
     * marked un-actionable — a GCP or Azure shrink that needs a migration is not realizable by
     * the action the UI offers.
     */
    private double sumResizeOnlyDiskSavings(java.util.List<java.util.Map<String, Object>> disks) {
        if (disks == null) {
            return 0.0;
        }
        return disks.stream()
                .filter(d -> {
                    Object rec = d.get("recommendation");
                    return rec != null
                            && !"NONE".equals(rec)
                            && !"DELETE_UNATTACHED".equals(rec)
                            && !"EXPAND".equals(rec);
                })
                .filter(d -> !Boolean.FALSE.equals(d.get("actionable")))
                .mapToDouble(d -> {
                    Object s = d.get("estimatedSavings");
                    double value = s instanceof Number ? ((Number) s).doubleValue() : 0.0;
                    // A negative figure denotes a cost increase; never let it flip the sign.
                    return Math.max(0.0, value);
                })
                .sum();
    }

    private double computeAdditionalSavings(com.xammer.cloud.domain.CloudAccount account) {
        double total = 0.0;
        String provider = account.getProvider();

        if ("AWS".equalsIgnoreCase(provider)) {

            // AutoSpotting potential savings
            try {
                com.xammer.cloud.dto.autospotting.CostResponse spot = autoSpottingService.getCostData(account.getId());
                if (spot != null && spot.getSummary() != null && spot.getSummary().getTotalPotentialSavings() != null) {
                    total += spot.getSummary().getTotalPotentialSavings() * 24 * 30;
                }
            } catch (Exception e) {
                logger.warn("[Dashboard] AutoSpotting savings unavailable for {}: {}", account.getAwsAccountId(),
                        e.getMessage());
            }
            // EBS Disk Rightsizing savings. Unattached volumes must be excluded: the waste
            // detector already books them at their full monthly price, and the disk-rightsizing
            // scanner books the same volumes again under DELETE_UNATTACHED. Counting both
            // double-charged every unattached volume into the dashboard KPI, which is why the
            // headline figure could exceed the reconciled FinOps total.
            try {
                total += ebsDiskRightsizingService.getResizeOnlyPotentialSavings(account.getAwsAccountId());
            } catch (Exception e) {

                logger.warn("[Dashboard] EBS disk savings unavailable for {}: {}", account.getAwsAccountId(),
                        e.getMessage());
            }
            // CloudSitter scheduling savings
            try {
                total += cloudsitterService.getTotalActiveSavings(account.getAwsAccountId());
            } catch (Exception e) {
                logger.warn("[Dashboard] CloudSitter savings unavailable for {}: {}", account.getAwsAccountId(),
                        e.getMessage());
            }

        } else if ("GCP".equalsIgnoreCase(provider)) {
            // GCP Spot Automation (MIGs) — hourly → monthly
            try {
                java.util.List<com.xammer.cloud.service.gcp.GcpAutoSpottingService.GcpAutoSpottingGroupDto> migs = gcpAutoSpottingService
                        .listMigsAllRegionsWithCosts(account.getId().toString());
                total += migs.stream().mapToDouble(m -> m.potentialHourlySavings).sum() * 24 * 30;
            } catch (Exception e) {
                logger.warn("[Dashboard] GCP Spot savings unavailable for {}: {}", account.getGcpProjectId(),
                        e.getMessage());
            }
            // GCP Disk Rightsizing — resize savings only. DELETE_UNATTACHED is already booked at
            // full cost by the waste report's findUnattachedDisks, and EXPAND raises cost rather
            // than saving. Counting either here inflated the KPI above the reconciled total, the
            // same defect getResizeOnlyPotentialSavings fixes on the AWS side.
            try {
                java.util.List<java.util.Map<String, Object>> disks = gcpDiskRightsizingService
                        .getDisks(account.getId().toString(), false);
                total += sumResizeOnlyDiskSavings(disks);
            } catch (Exception e) {
                logger.warn("[Dashboard] GCP disk savings unavailable for {}: {}", account.getGcpProjectId(),
                        e.getMessage());
            }

        } else if ("Azure".equalsIgnoreCase(provider)) {
            // Azure Spot Automation (VMSS) — hourly → monthly
            try {
                com.xammer.cloud.dto.autospotting.CostResponse spot =

                        azureAutoSpottingService.getCostData(account.getAzureSubscriptionId());
                if (spot != null && spot.getSummary() != null && spot.getSummary().getTotalPotentialSavings() != null) {
                    total += spot.getSummary().getTotalPotentialSavings() * 24 * 30;
                }
            } catch (Exception e) {
                logger.warn("[Dashboard] Azure Spot savings unavailable for {}: {}", account.getAzureSubscriptionId(),
                        e.getMessage());
            }
            // Azure Disk Rightsizing
            try {
                // Internal caller: the account is already resolved and trusted here, so its
                // own client id satisfies the service's tenant-ownership check.
                Long ownerClientId = account.getClient() != null ? account.getClient().getId() : null;
                java.util.List<java.util.Map<String, Object>> disks = azureDiskRightsizingService
                        .getDisks(account.getId().toString(), ownerClientId, false);
                // Resize savings only — see the GCP branch above for why.
                total += sumResizeOnlyDiskSavings(disks);
            } catch (Exception e) {
                logger.warn("[Dashboard] Azure disk savings unavailable for {}: {}", account.getAzureSubscriptionId(),
                        e.getMessage());
            }
        }

        return total;
    }

    private DashboardData.OptimizationSummary getOptimizationSummary(List<DashboardData.WastedResource> wastedResources,
            List<DashboardData.OptimizationRecommendation> ec2Recs,
            List<DashboardData.OptimizationRecommendation> ebsRecs,
            List<DashboardData.OptimizationRecommendation> lambdaRecs,
            List<DashboardData.CostAnomaly> anomalies) {
        double rightsizingSavings = Stream.of(ec2Recs, ebsRecs, lambdaRecs)
                .flatMap(List::stream)
                .mapToDouble(DashboardData.OptimizationRecommendation::getEstimatedMonthlySavings)
                .sum();

        // Unpriced and genuinely-free findings must not contribute: their monthlySavings is a
        // placeholder zero, and including them only inflated the accompanying resource count.
        double wasteSavings = wastedResources.stream()
                .filter(DashboardData.WastedResource::isBillableWaste)
                .mapToDouble(DashboardData.WastedResource::getMonthlySavings)
                .sum();

        double totalSavings = rightsizingSavings + wasteSavings;
        int criticalAlerts = (anomalies != null ? anomalies.size() : 0) + ec2Recs.size() + ebsRecs.size()
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

        // USD when Azure gave us a USD figure, native currency otherwise.
        //
        // This mapping feeds the unified multi-provider dashboard, which totals AWS, GCP and Azure
        // into one number and therefore needs one currency. It used to copy the native figure
        // through and leave the frontend to convert with a hardcoded 0.012 (an implied 83.33
        // INR/USD). Azure's own CostUSD for the same rows implies 95.65 — the rate it actually
        // billed at — so the local conversion was around 15% out on every Azure figure in that
        // view, and completely wrong for a subscription that bills in anything but INR.
        //
        // Falls back to the native figure when no USD is available, which is also the correct
        // no-op for a subscription already billed in USD.
        Double azureMtdUsd = azureData.getMonthToDateSpendUsd();
        mainAccount.setMonthToDateSpend(azureMtdUsd != null ? azureMtdUsd : azureData.getMonthToDateSpend());
        mainAccount.setForecastedSpend(azureData.getForecastedSpend());
        if (azureMtdUsd == null && azureData.getCurrency() != null
                && !"USD".equalsIgnoreCase(azureData.getCurrency())) {
            logger.warn("[Unified] Azure subscription {} reports {} but no USD conversion — its spend is "
                    + "being totalled in {} alongside USD figures from other providers.",
                    account.getAzureSubscriptionId(), azureData.getCurrency(), azureData.getCurrency());
        }

        if (azureData.getCostHistory() != null) {
            mainAccount.setCostHistory(new DashboardData.CostHistory(
                    azureData.getCostHistory().getLabels(),
                    azureData.getCostHistory().getCosts(),
                    azureData.getCostHistory().getForecast()));
        }

        if (azureData.getRegionStatus() != null) {
            List<DashboardData.RegionStatus> regions = azureData.getRegionStatus().stream()
                    .map(r -> new DashboardData.RegionStatus(r.getName(), r.getStatus(), r.getStatus(), r.getLatitude(),
                            r.getLongitude()))
                    .collect(Collectors.toList());
            mainAccount.setRegionStatus(regions);
        }

        // Previously hardcoded to 100 with no insights, so every Azure subscription rendered a
        // perfect security score regardless of posture — a fabricated all-clear. The real Defender
        // secure score is now collected by AzureDashboardService; zero means "not known" and the
        // dashboard renders that as N/A rather than as a number.
        mainAccount.setSecurityScore(azureData.getSecurityScore());
        mainAccount.setSecurityInsights(azureData.getSecurityInsights() != null
                ? azureData.getSecurityInsights()
                : new ArrayList<>());
        if (azureData.getSecurityScore() == 0 && azureData.getSecurityScoreUnavailableReason() != null) {
            logger.info("[AzureMap] No security score for {}: {}", account.getAzureSubscriptionId(),
                    azureData.getSecurityScoreUnavailableReason());
        }
        mainAccount.setSavingsSummary(new DashboardData.SavingsSummary(0.0, new ArrayList<>()));
        double azureExtra = computeAdditionalSavings(account);
        mainAccount.setOptimizationSummary(new DashboardData.OptimizationSummary(azureExtra, 0));
        mainAccount.setEc2Recommendations(new ArrayList<>());
        // Read Azure waste from cache (populated by Wed/Sun scheduler) — zero API calls
        try {
            List<com.xammer.cloud.dto.azure.AzureWasteItem> azureWaste = azureWasteService
                    .getWasteItems(account.getAzureSubscriptionId(), false);
            if (azureWaste != null && !azureWaste.isEmpty()) {
                // AzureWasteItem amounts are already USD. They were previously multiplied by
                // INR_TO_USD_RATE here while the optimizationSummary in the same response was
                // left unconverted, so two adjacent fields differed by ~83x.
                List<DashboardData.WastedResource> mapped = azureWaste.stream()
                        .map(w -> new DashboardData.WastedResource(
                                w.getResourceId(), w.getResourceName(), w.getResourceType(),
                                w.getRegion(), w.getMonthlySavings(), w.getReason()))
                        .collect(Collectors.toList());
                mainAccount.setWastedResources(mapped);
            } else {
                mainAccount.setWastedResources(new ArrayList<>());
            }
        } catch (Exception e) {
            logger.warn("[AzureMap] Failed to load waste items for {}: {}", account.getAzureSubscriptionId(),
                    e.getMessage());
            mainAccount.setWastedResources(new ArrayList<>());
        }
        mainAccount.setCloudWatchStatus(new DashboardData.CloudWatchStatus(0, 0, 0));
        // Carried through from the Azure payload rather than blanked. This was an empty list even
        // once anomalies existed, so the unified dashboard would still have shown Azure as having
        // none.
        mainAccount.setCostAnomalies(azureData.getCostAnomalies() != null
                ? azureData.getCostAnomalies()
                : new ArrayList<>());
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
                CompletableFuture<FinOpsReportDto> finOpsFuture = finOpsService.getFinOpsReport(idToUse, forceRefresh,
                        null, null);
                // Billing summary is the single source of truth for MTD spend — shared with
                // the Cost page and FinOps page via the getCostBreakdown("SERVICE") cache key.
                CompletableFuture<List<DashboardData.BillingSummary>> billingSummaryFuture = finOpsService
                        .getBillingSummary(account, forceRefresh, null, null);
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
                                finOpsFuture, billingSummaryFuture)
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
                double extraSavingsKpi = computeAdditionalSavings(account);
                if (extraSavingsKpi > 0) {
                    optSummary = new DashboardData.OptimizationSummary(
                            optSummary.getTotalPotentialSavings() + extraSavingsKpi,
                            optSummary.getCriticalAlerts());
                }
                mainAccount.setOptimizationSummary(optSummary);

                // MTD spend: sum the billing-summary service amounts — same source and same
                // cache key (AWS_COST_BREAKDOWN) as the Cost page and FinOps page.
                List<DashboardData.BillingSummary> billingSummary = billingSummaryFuture.join();
                Double mtd = billingSummary.stream()
                        .mapToDouble(DashboardData.BillingSummary::getAmount).sum();
                // Last-month spend: use second-to-last entry from the monthly cost history.
                Double lastMonth = costHistory.getCosts().size() > 1
                        ? costHistory.getCosts().get(costHistory.getCosts().size() - 2)
                        : 0.0;

                int dayOfMonth = LocalDate.now().getDayOfMonth();
                int daysInMonth = LocalDate.now().lengthOfMonth();
                Double forecast = (dayOfMonth > 0) ? (mtd / dayOfMonth) * daysInMonth : 0.0;

                mainAccount.setMonthToDateSpend(mtd);
                mainAccount.setLastMonthSpend(lastMonth);
                //
                mainAccount.setForecastedSpend(forecast);
                //

                // Merge EC2+EBS+Lambda into combined rightsizing list — serves the Rightsizing
                // page.
                // This is what refreshOptimization() used to do exclusively via
                // getAllOptimizationRecommendations().
                List<DashboardData.OptimizationRecommendation> allRecs = Stream
                        .of(ec2Future.join(), ebsFuture.join(), lambdaFuture.join())
                        .flatMap(List::stream).collect(Collectors.toList());

                redisCache.put("allRecommendations-" + idToUse, allRecs, 60);
                try {
                    CachedData rightsizingSnap = cachedDataRepository.findById("rightsizing-" + idToUse)
                            .orElse(new CachedData());
                    rightsizingSnap.setCacheKey("rightsizing-" + idToUse);
                    rightsizingSnap.setJsonData(objectMapper.writeValueAsString(allRecs));
                    rightsizingSnap.setLastUpdated(java.time.LocalDateTime.now());
                    cachedDataRepository.save(rightsizingSnap);
                } catch (Exception e) {
                    logger.warn("Failed to save rightsizing snapshot for {}: {}", idToUse, e.getMessage());
                }

                // Save waste snapshot to DB — serves the Waste Management page.
                saveWasteSnapshot("waste-" + idToUse, wasteFuture.join());

                // The dashboard reads cost and savings from the DASHBOARD_COST / DASHBOARD_OPT
                // snapshots, not from this KPI payload. Write them here from the data already
                // in hand so the nightly refresh actually moves those widgets — zero extra
                // Cost Explorer calls. Kept as separate snapshots (rather than widening this
                // one) so the KPI payload stays small on every dashboard load.
                try {
                    DashboardData.SavingsSummary savingsSummary = getSavingsSummary(
                            wasteFuture, ec2Future, ebsFuture, lambdaFuture,
                            account.getAwsAccountId() != null ? Long.parseLong(account.getAwsAccountId()) : null)
                            .exceptionally(ex -> {
                                logger.warn("Savings summary calculation failed for {}: {}", idToUse, ex.getMessage());
                                return new DashboardData.SavingsSummary(0.0, 0.0);
                            }).join();

                    saveDashboardCostSnapshot(idToUse, account.getAccountName(), account.getProvider(),
                            costHistory, billingSummary);
                    saveDashboardOptSnapshot(idToUse, account.getAccountName(), account.getProvider(),
                            ec2Future.join(), ebsFuture.join(), lambdaFuture.join(), wasteFuture.join(),
                            anomaliesFuture.join(), optSummary, savingsSummary);
                } catch (Exception e) {
                    // Never let a sibling-snapshot write break the KPI response itself.
                    logger.warn("Failed to sync cost/optimization snapshots for {}: {}", idToUse, e.getMessage());
                }

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

            // Tier 0 — counts straight from the inventory plane, ahead of the snapshot read
            // on purpose.
            //
            // The snapshot is written to Redis *and* to cached_data, and cached_data has no
            // expiry, so "invalidate it" means deleting a database row. Any eviction we
            // forget leaves the customer looking at a permanently wrong number — which is
            // exactly what happened here: the cloud list moved to the plane and updated
            // within seconds while this widget kept serving counts from a snapshot nothing
            // ever invalidated, showing 20 S3 buckets against a real 23. Since the plane
            // answers with one indexed GROUP BY, there is nothing worth caching in front of
            // it, and reading it first removes the whole class of staleness rather than
            // adding one more key to an eviction list.
            DashboardData live = inventoryFromPlane(idToUse, cacheKey);
            if (live != null) {
                return live;
            }

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

                // Calculate summaries
                DashboardData.OptimizationSummary optSum = getOptimizationSummary(waste.join(), ec2.join(), ebs.join(),
                        lambda.join(), anomalies.join());
                double extraSavings = computeAdditionalSavings(account);
                if (extraSavings > 0) {
                    optSum = new DashboardData.OptimizationSummary(
                            optSum.getTotalPotentialSavings() + extraSavings,
                            optSum.getCriticalAlerts());
                }
                mainAccount.setOptimizationSummary(optSum);

                // Savings Summary
                DashboardData.SavingsSummary savings = getSavingsSummary(waste, ec2, ebs, lambda,
                        account.getAwsAccountId() != null ? Long.parseLong(account.getAwsAccountId()) : null).join();
                mainAccount.setSavingsSummary(savings);

                data.setSelectedAccount(mainAccount);
            } else {
                DashboardData providerData = getDashboardData(idToUse, forceRefresh, userDetails);
                if (providerData.getSelectedAccount() != null) {

                    double extra = computeAdditionalSavings(account);
                    if (extra > 0) {
                        DashboardData.OptimizationSummary existing = providerData.getSelectedAccount()
                                .getOptimizationSummary();
                        double base = existing != null ? existing.getTotalPotentialSavings() : 0;
                        long alerts = existing != null ? existing.getCriticalAlerts() : 0;
                        providerData.getSelectedAccount().setOptimizationSummary(
                                new DashboardData.OptimizationSummary(base + extra, alerts));
                    }
                }
                return providerData;
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

    /**
     * Write the DASHBOARD_COST:: snapshot the dashboard's cost widgets read.
     * Called from getGranularKpi() with cost data it has already fetched, so the
     * nightly refresh keeps this key in sync without re-hitting Cost Explorer.
     */
    private void saveDashboardCostSnapshot(String accountId, String accountName, String provider,
            DashboardData.CostHistory costHistory, List<DashboardData.BillingSummary> billingSummary) {
        try {
            DashboardData costData = new DashboardData();
            costData.setSelectedProvider(provider);

            DashboardData.Account costAcc = new DashboardData.Account();
            costAcc.setId(accountId);
            costAcc.setName(accountName);
            costAcc.setCostHistory(costHistory);
            costAcc.setBillingSummary(billingSummary);

            costData.setSelectedAccount(costAcc);
            costData.setLastUpdated(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
            saveSnapshot("DASHBOARD_COST::" + accountId, costData);
        } catch (Exception e) {
            logger.warn("Failed to save DASHBOARD_COST snapshot for {}: {}", accountId, e.getMessage());
        }
    }

    /**
     * Write the DASHBOARD_OPT:: snapshot the dashboard's savings widgets read, and
     * which buildAndSaveUnifiedAwsSnapshot() merges into the all-accounts view.
     * Mirrors the payload getGranularOptimization() produces.
     */
    private void saveDashboardOptSnapshot(String accountId, String accountName, String provider,
            List<DashboardData.OptimizationRecommendation> ec2Recs,
            List<DashboardData.OptimizationRecommendation> ebsRecs,
            List<DashboardData.OptimizationRecommendation> lambdaRecs,
            List<DashboardData.WastedResource> wastedResources,
            List<DashboardData.CostAnomaly> anomalies,
            DashboardData.OptimizationSummary optSummary,
            DashboardData.SavingsSummary savingsSummary) {
        try {
            DashboardData optData = new DashboardData();
            optData.setSelectedProvider(provider);

            DashboardData.Account optAcc = new DashboardData.Account();
            optAcc.setId(accountId);
            optAcc.setName(accountName);
            optAcc.setEc2Recommendations(ec2Recs);
            optAcc.setEbsRecommendations(ebsRecs);
            optAcc.setLambdaRecommendations(lambdaRecs);
            optAcc.setWastedResources(wastedResources);
            optAcc.setCostAnomalies(anomalies);
            optAcc.setOptimizationSummary(optSummary);
            optAcc.setSavingsSummary(savingsSummary);

            optData.setSelectedAccount(optAcc);
            optData.setLastUpdated(java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm:ss")));
            saveSnapshot("DASHBOARD_OPT::" + accountId, optData);
        } catch (Exception e) {
            logger.warn("Failed to save DASHBOARD_OPT snapshot for {}: {}", accountId, e.getMessage());
        }
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
                kpiAcc.setSecurityScore(data.getSelectedAccount().getSecurityScore());

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
