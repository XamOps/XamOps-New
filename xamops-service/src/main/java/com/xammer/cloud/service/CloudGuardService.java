package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.dto.*;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.inventory.InventoryProjectionService;
import com.xammer.cloud.service.security.SecurityFindingProjectionService;
import com.xammer.cloud.service.security.SecuritySeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.ec2.model.NatGatewayState;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.servicequotas.model.GetServiceQuotaRequest;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasRequest;
import software.amazon.awssdk.services.servicequotas.model.ListServiceQuotasResponse;
import software.amazon.awssdk.services.servicequotas.model.ServiceQuota;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class CloudGuardService {

    private static final Logger logger = LoggerFactory.getLogger(CloudGuardService.class);
    private static final double ALERT_THRESHOLD_PERCENTAGE = 75.0;

    /**
     * How long a saved alerts snapshot may be served before a page load rebuilds it live. Matches
     * the Redis lifetime and the GCP page, so all three tiers agree on what "current" means.
     */
    public static final int ALERTS_SNAPSHOT_MAX_AGE_MINUTES = 60;

    // Network Quota Codes (AWS Service Quotas)
    private static final String QUOTA_VPC = "L-F678F1CE"; // VPCs per Region
    private static final String QUOTA_INTERNET_GATEWAY = "L-A4707A72"; // Internet Gateways per Region
    private static final String QUOTA_NAT_GATEWAY = "L-FE5A380F"; // NAT Gateways per AZ
    private static final String QUOTA_ELASTIC_IP = "L-0263D0A3"; // Elastic IPs per Region

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final RedisCacheService redisCache;
    private final CloudListService cloudListService;
    private final FinOpsService finOpsService;
    private final EmailService emailService;
    private final DashboardDataService dashboardDataService;
    private final AwsSecurityHubService securityHubService;
    private final AlertEmailBuilder alertEmailBuilder;
    private final NotificationRecipientResolver recipientResolver;
    private final AlertNotificationDeduplicator deduplicator;
    private final AlertDigestSeverityGate severityGate;

    /**
     * Optional: absent when the inventory plane is disabled, in which case the quota
     * counts below keep using their per-region describes unchanged.
     */
    @Autowired(required = false)
    private InventoryProjectionService inventoryProjection;

    /**
     * Live event-driven findings. Field-injected to keep the constructor from growing a
     * twelfth parameter; the projection is a no-op when the feature flag is off.
     */
    @Autowired
    private SecurityFindingProjectionService liveFindings;

    @Autowired
    public CloudGuardService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            RedisCacheService redisCache,
            @Lazy CloudListService cloudListService,
            FinOpsService finOpsService,
            EmailService emailService,
            @Lazy DashboardDataService dashboardDataService,
            AwsSecurityHubService securityHubService,
            AlertEmailBuilder alertEmailBuilder,
            NotificationRecipientResolver recipientResolver,
            AlertNotificationDeduplicator deduplicator,
            AlertDigestSeverityGate severityGate) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.redisCache = redisCache;
        this.cloudListService = cloudListService;
        this.finOpsService = finOpsService;
        this.emailService = emailService;
        this.dashboardDataService = dashboardDataService;
        this.securityHubService = securityHubService;
        this.alertEmailBuilder = alertEmailBuilder;
        this.recipientResolver = recipientResolver;
        this.deduplicator = deduplicator;
        this.severityGate = severityGate;
    }

    private CloudAccount getAccount(String accountId) {
        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByProviderAccountId(accountId);
        if (accountOpt.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }

        CloudAccount account = accountOpt.get();

        // Force initialization of the Client proxy
        if (account.getClient() != null) {
            try {
                account.getClient().getEmail();
            } catch (Exception e) {
                logger.warn("Could not initialize client for account {}", accountId);
            }
        }

        return account;
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getVpcQuotaAlerts(String accountId,
            boolean forceRefresh) {
        String cacheKey = "vpcQuotaAlerts-" + accountId;

        if (!forceRefresh) {
            Optional<List<DashboardData.ServiceQuotaInfo>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                logger.info("--- LOADING FROM REDIS CACHE (TypeReference): {} ---", cacheKey);
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(accountId);
        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService
                .getRegionStatusForAccountOrFail(account, forceRefresh);

        return activeRegionsFuture.thenCompose(activeRegions -> {
            if (activeRegions == null || activeRegions.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            // Resource counts come from the plane when it can answer; the quota limits below
            // still come from Service Quotas, which is not inventory and has no event source.
            Map<String, Long> planeVpcCounts = regionCountsFromPlane(account, "VPC");
            CompletableFuture<Map<String, Long>> vpcCountsFuture = planeVpcCounts != null
                    ? CompletableFuture.completedFuture(planeVpcCounts)
                    : cloudListService.fetchVpcsForCloudlist(account, activeRegions)
                            .thenApply(CloudGuardService::countByRegion);

            // Use thenApply (not Async) to avoid creating extra thread
            return vpcCountsFuture.thenApply(vpcCountsByRegion -> {
                // Process regions sequentially (we're already in async context)
                // This prevents thread exhaustion from CompletableFuture.supplyAsync() creating
                // unbounded threads
                List<DashboardData.ServiceQuotaInfo> allQuotaInfo = new ArrayList<>();
                RegionFailures failures = new RegionFailures();

                for (DashboardData.RegionStatus region : activeRegions) {
                    try {
                        logger.debug("Fetching VPC quota for account {} in region {}", accountId,
                                region.getRegionId());
                        QuotaLimit vpcLimit = quotaLimits(account, region.getRegionId(), "vpc",
                                VPC_SERVICE_QUOTA_CODES, forceRefresh).get(QUOTA_VPC);

                        if (vpcLimit != null) {
                            double usage = vpcCountsByRegion.getOrDefault(region.getRegionId(), 0L)
                                    .doubleValue();
                            addIfOverThreshold(vpcLimit, "VPC", vpcLimit.name(), usage,
                                    region.getRegionId(), allQuotaInfo);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to get VPC quota for region {}: {}", region.getRegionId(),
                                e.getMessage());
                        failures.record(e);
                    }
                }

                // Every region failing is a check that did not run — typically a missing Service
                // Quotas permission. Returned as an empty list, as it used to be, that read as "no
                // VPC limit is close", which is exactly the alert the page exists to raise.
                failures.throwIfAllFailed(activeRegions.size(), "VPC quotas");

                logger.info("Successfully fetched {} VPC quota alerts for account {}.", allQuotaInfo.size(),
                        accountId);
                redisCache.put(cacheKey, allQuotaInfo, 60);
                return allQuotaInfo;
            });
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.ServiceQuotaInfo>> getNetworkQuotaAlerts(String accountId,
            boolean forceRefresh) {
        String cacheKey = "networkQuotaAlerts-" + accountId;

        if (!forceRefresh) {
            Optional<List<DashboardData.ServiceQuotaInfo>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                logger.info("--- LOADING NETWORK QUOTA ALERTS FROM REDIS CACHE: {} ---", cacheKey);
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(accountId);
        CompletableFuture<List<DashboardData.RegionStatus>> activeRegionsFuture = cloudListService
                .getRegionStatusForAccountOrFail(account, forceRefresh);

        // Use thenApply (not Async) to avoid creating extra thread
        return activeRegionsFuture.thenApply(activeRegions -> {
            if (activeRegions == null || activeRegions.isEmpty()) {
                return Collections.<DashboardData.ServiceQuotaInfo>emptyList();
            }

            List<DashboardData.ServiceQuotaInfo> allQuotaInfo = new ArrayList<>();
            RegionFailures failures = new RegionFailures();

            // Resolved once for the whole account rather than per region: one indexed query,
            // replacing a describe per region. Only internet gateways come from the plane: NAT
            // gateways' limit is per Availability Zone, which the plane does not record, and the
            // plane cannot tell service-managed Elastic IPs from the ones that count (see
            // countElasticIps).
            Map<String, Long> planeIgw = regionCountsFromPlane(account, "Internet Gateway");

            for (DashboardData.RegionStatus region : activeRegions) {
                try {
                    String regionId = region.getRegionId();
                    // The Elastic IP quota belongs to "ec2", not "vpc". It used to be looked up in
                    // the "vpc" listing, where it was never found, so that alert could not fire.
                    Map<String, QuotaLimit> vpcLimits = quotaLimits(account, regionId, "vpc",
                            VPC_SERVICE_QUOTA_CODES, forceRefresh);
                    QuotaLimit eipLimit = quotaLimits(account, regionId, "ec2",
                            Set.of(QUOTA_ELASTIC_IP), forceRefresh).get(QUOTA_ELASTIC_IP);

                    // Usage is counted only where there is a limit to compare it with.
                    QuotaLimit igwLimit = vpcLimits.get(QUOTA_INTERNET_GATEWAY);
                    if (igwLimit != null) {
                        long igwCount = planeIgw != null ? planeIgw.getOrDefault(regionId, 0L)
                                : countInternetGateways(account, regionId);
                        addIfOverThreshold(igwLimit, "Internet Gateway",
                                "Internet gateways per Region", igwCount, regionId, allQuotaInfo);
                    }
                    if (eipLimit != null) {
                        addIfOverThreshold(eipLimit, "Elastic IP",
                                "EC2-VPC Elastic IPs", countElasticIps(account, regionId), regionId, allQuotaInfo);
                    }

                    // Compared per zone, which is what the limit is. Comparing the region's total
                    // against a per-zone limit raised false warnings on any multi-AZ setup: three
                    // zones with two gateways each read as 6 of 5.
                    QuotaLimit natQuota = vpcLimits.get(QUOTA_NAT_GATEWAY);
                    if (natQuota != null) {
                        countNatGatewaysByAz(account, regionId).forEach((az, count) ->
                                addIfOverThreshold(natQuota, "NAT Gateway",
                                        "NAT gateways per Availability Zone (" + az + ")", count, regionId,
                                        allQuotaInfo));
                    }
                } catch (Exception e) {
                    logger.warn("Failed to check network quotas in region {}: {}", region.getRegionId(),
                            e.getMessage());
                    failures.record(e);
                }
            }

            failures.throwIfAllFailed(activeRegions.size(), "Network quotas");

            logger.info("Successfully fetched {} network quota alerts for account {}.", allQuotaInfo.size(), accountId);
            redisCache.put(cacheKey, allQuotaInfo, 60);
            return allQuotaInfo;
        });
    }

    /** The "vpc" quotas the alerts use, read together so both quota checks share one lookup. */
    private static final Set<String> VPC_SERVICE_QUOTA_CODES =
            Set.of(QUOTA_VPC, QUOTA_INTERNET_GATEWAY, QUOTA_NAT_GATEWAY);

    /**
     * How long a region's quota limits are reused. Limits move only when an increase is
     * approved, while usage — always counted live — is what changes; re-reading every limit in
     * every region on every scan is what pushed the scan past the page's timeout.
     */
    private static final int QUOTA_LIMIT_CACHE_MINUTES = 6 * 60;

    /** ListServiceQuotas pages at 100. */
    private static final int MAX_QUOTA_PAGES = 10;

    /** What an alert needs of a quota, in a form the cache can hold. */
    record QuotaLimit(String name, double value) {
    }

    /**
     * Applied limits for some of a service's quotas in one region, keyed by quota code. Codes
     * Service Quotas does not return are absent.
     *
     * <p>Reused from the cache for {@link #QUOTA_LIMIT_CACHE_MINUTES} unless {@code forceRefresh}.
     * A single code is fetched on its own rather than by listing the service: listing all of EC2,
     * for the one Elastic IP quota, took several calls a region and more than doubled the scan.
     */
    private Map<String, QuotaLimit> quotaLimits(CloudAccount account, String regionId, String serviceCode,
            Set<String> quotaCodes, boolean forceRefresh) {
        String cacheKey = "quotaLimits-" + account.getAwsAccountId() + "-" + regionId + "-" + serviceCode
                + "-" + String.join(",", new TreeSet<>(quotaCodes));
        if (!forceRefresh) {
            Optional<Map<String, QuotaLimit>> cached = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        ServiceQuotasClient sqClient = awsClientProvider.getServiceQuotasClient(account, regionId);
        Map<String, QuotaLimit> limits = null;
        if (quotaCodes.size() == 1) {
            String code = quotaCodes.iterator().next();
            try {
                ServiceQuota q = sqClient.getServiceQuota(GetServiceQuotaRequest.builder()
                        .serviceCode(serviceCode).quotaCode(code).build()).quota();
                limits = q != null && q.value() != null
                        ? Map.of(code, new QuotaLimit(q.quotaName(), q.value()))
                        : Map.of();
            } catch (Exception e) {
                // Roles granted only ListServiceQuotas end up here, as do quotas with no applied
                // value. Listing the service is slower but answers both.
                logger.debug("GetServiceQuota {}/{} in {} failed, listing instead: {}", serviceCode, code,
                        regionId, e.getMessage());
            }
        }
        if (limits == null) {
            limits = listQuotaLimits(sqClient, serviceCode, quotaCodes);
        }
        redisCache.put(cacheKey, limits, QUOTA_LIMIT_CACHE_MINUTES);
        return limits;
    }

    /** Lists a service's quotas, following every page, and keeps the codes asked for. */
    private Map<String, QuotaLimit> listQuotaLimits(ServiceQuotasClient sqClient, String serviceCode,
            Set<String> quotaCodes) {
        Map<String, QuotaLimit> limits = new HashMap<>();
        String nextToken = null;
        int pages = 0;
        do {
            ListServiceQuotasResponse page = sqClient.listServiceQuotas(ListServiceQuotasRequest.builder()
                    .serviceCode(serviceCode)
                    .maxResults(100)
                    .nextToken(nextToken)
                    .build());
            page.quotas().stream()
                    .filter(q -> quotaCodes.contains(q.quotaCode()) && q.value() != null)
                    .forEach(q -> limits.put(q.quotaCode(), new QuotaLimit(q.quotaName(), q.value())));
            nextToken = page.nextToken();
            pages++;
        } while (limits.size() < quotaCodes.size()
                && nextToken != null && !nextToken.isBlank() && pages < MAX_QUOTA_PAGES);
        return limits;
    }

    /**
     * Per-region failures of one quota check, so a check that failed everywhere is reported as a
     * failure rather than as an empty — and therefore reassuring — result.
     */
    private static final class RegionFailures {
        private int count;
        private String firstMessage;

        void record(Exception e) {
            count++;
            if (firstMessage == null) {
                firstMessage = e.getMessage();
            }
        }

        void throwIfAllFailed(int regions, String check) {
            if (regions > 0 && count == regions) {
                throw new IllegalStateException(check + " could not be read in any region: " + firstMessage);
            }
        }
    }

    /**
     * Per-region counts of one resource type from the inventory plane, or null when the
     * plane cannot answer and the caller should fall back to describes.
     *
     * <p>
     * An empty result is treated as "cannot answer" rather than "none exist", which is the
     * conservative choice for a quota alert specifically. Reading zero when the true count is
     * non-zero does not surface as an error — it suppresses the alert, so the customer hears
     * nothing right up to the point they hit the limit. Confirming a genuine zero costs one
     * describe per region and is the cheaper mistake.
     *
     * <p>
     * Deleted resources are excluded by construction: the plane tombstones them and every
     * projection read filters on ACTIVE, which is also what the old
     * {@code countNatGateways} achieved by filtering out {@code NatGatewayState.DELETED}.
     */
    private Map<String, Long> regionCountsFromPlane(CloudAccount account, String displayType) {
        if (inventoryProjection == null || account.getId() == null) {
            return null;
        }
        try {
            if (!inventoryProjection.isFresh(account.getId())) {
                return null;
            }
            Map<String, Long> counts = countByRegion(inventoryProjection.byType(account.getId(), displayType));
            if (counts.isEmpty()) {
                return null;
            }
            logger.debug("[Inventory] {} counts for account {} served from plane ({} regions, 0 AWS calls)",
                    displayType, account.getAwsAccountId(), counts.size());
            return counts;
        } catch (Exception e) {
            logger.warn("[Inventory] {} plane count failed for account {} — falling back: {}",
                    displayType, account.getAwsAccountId(), e.getMessage());
            return null;
        }
    }

    /** Groups resources by region, skipping the region-less ones so the key is never null. */
    private static Map<String, Long> countByRegion(List<ResourceDto> resources) {
        return resources.stream()
                .filter(r -> r != null && r.getRegion() != null && !r.getRegion().isBlank())
                .collect(Collectors.groupingBy(ResourceDto::getRegion, Collectors.counting()));
    }

    // The counters below throw rather than return 0. A zero on failure does not look like an
    // error — it suppresses the alert, so the customer hears nothing right up to the limit.

    /**
     * Count Internet Gateways in a region.
     */
    private long countInternetGateways(CloudAccount account, String regionId) {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
        return ec2.describeInternetGatewaysPaginator().internetGateways().stream().count();
    }

    /**
     * NAT gateways per Availability Zone in a region, counting the states AWS counts against the
     * quota (pending, available, deleting) and not deleted or failed ones.
     *
     * <p>A NAT gateway reports its subnet, not its zone, so the zone comes from the subnet.
     */
    private Map<String, Long> countNatGatewaysByAz(CloudAccount account, String regionId) {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
        List<NatGateway> gateways = ec2.describeNatGatewaysPaginator().natGateways().stream()
                .filter(ng -> ng.state() != NatGatewayState.DELETED && ng.state() != NatGatewayState.FAILED)
                .collect(Collectors.toList());
        if (gateways.isEmpty()) {
            return Map.of();
        }

        Set<String> subnetIds = gateways.stream().map(NatGateway::subnetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> azBySubnet = ec2.describeSubnets(r -> r.subnetIds(subnetIds)).subnets().stream()
                .collect(Collectors.toMap(Subnet::subnetId, Subnet::availabilityZone, (a, b) -> a));

        return natCountsByAz(gateways, azBySubnet);
    }

    /** Groups gateways by the zone of their subnet. Package-private for testing. */
    static Map<String, Long> natCountsByAz(List<NatGateway> gateways, Map<String, String> azBySubnet) {
        return gateways.stream().collect(Collectors.groupingBy(
                ng -> azBySubnet.getOrDefault(ng.subnetId(), "unknown zone"),
                TreeMap::new, Collectors.counting()));
    }

    /**
     * Count the Elastic IPs in a region that use up the account's Elastic IP quota.
     *
     * <p>Service-managed addresses are left out: those that Application Load Balancers and other
     * services allocate and manage themselves, which DescribeAddresses returns with
     * {@code ServiceManaged} set. On the xammer-local account ap-south-1 had 13 addresses, 8 of
     * them ALB-managed, against a limit of 5 — counting all 13 reported the quota at 260%, a
     * reading AWS cannot produce, when the true figure was 5 of 5.
     */
    private long countElasticIps(CloudAccount account, String regionId) {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
        return countedAgainstQuota(ec2.describeAddresses().addresses());
    }

    /** Package-private for testing. */
    static long countedAgainstQuota(List<Address> addresses) {
        return addresses.stream()
                .filter(a -> a.serviceManaged() == null)
                .count();
    }

    /**
     * Adds the quota to the list when usage is at or above the alert threshold. A null quota —
     * one Service Quotas did not return for this region — adds nothing.
     */
    private void addIfOverThreshold(QuotaLimit quota, String serviceName, String quotaName, double usage,
            String regionId, List<DashboardData.ServiceQuotaInfo> quotaList) {
        if (quota == null) {
            return;
        }
        double limit = quota.value();
        double utilization = (limit > 0) ? (usage / limit) * 100.0 : 0.0;
        if (utilization >= ALERT_THRESHOLD_PERCENTAGE) {
            quotaList.add(new DashboardData.ServiceQuotaInfo(
                    serviceName, quotaName, limit, usage, "Active", regionId));
        }
    }

    @Async("awsTaskExecutor")
    @Transactional(readOnly = true)
    /**
     * Emails the account's users about alerts that are new since the last notification.
     *
     * <p>Separate from {@link #getAlerts} on purpose: that method backs the Alerts page, so
     * sending from it meant a page load — and every restart, via the cache warm-up — mailed
     * everyone. Only the scheduler calls this.
     *
     * <p>Sends nothing when the only change is that alerts were resolved. The stored set is
     * still updated, so a finding that clears and later returns is reported as new.
     */
    public void notifyNewAlerts(String accountId) {
        AlertsResponseDto response = getAlerts(accountId, true).join();
        notifyNewAlerts(accountId, response != null && response.getAlerts() != null
                ? response.getAlerts()
                : java.util.Collections.emptyList());
    }

    /**
     * Overload for a caller that has already fetched the alerts.
     *
     * <p>The nightly refresh needs the alert list anyway to write its snapshot; re-fetching
     * inside the notifier would double every Service Quotas and Cost Explorer call it makes.
     */
    public void notifyNewAlerts(String accountId, List<AlertDto> alerts) {
        try {
            AlertNotificationDeduplicator.AlertDelta delta =
                    deduplicator.detectNewAlerts("aws", accountId, alerts);
            deduplicator.markSeen("aws", accountId, alerts);

            if (!delta.worthSending()) {
                logger.info("[AwsAlerts] No new alerts for account {} ({} open) — no email",
                        accountId, delta.totalOpen());
                return;
            }

            CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                    .stream().findFirst().orElse(null);
            if (account == null) {
                logger.warn("[AwsAlerts] Account {} not found — cannot notify", accountId);
                return;
            }
            String accountName = account.getAccountName() != null ? account.getAccountName() : accountId;

            // Threshold applied to the new alerts only; the seen-set above already recorded
            // everything, so lowering the threshold later cannot resurface old findings as new.
            List<AlertDto> notifiable = severityGate.notifiable(account, delta.newAlerts());
            if (notifiable.isEmpty()) {
                logger.info("[AwsAlerts] {} new alert(s) for account {} all below the digest "
                        + "severity threshold — no email", delta.newAlerts().size(), accountId);
                return;
            }

            // Through the shared resolver, so a user who switched security email off in BillOps
            // admin is excluded from this digest too. An empty list is not an error — it can simply
            // mean nobody on the tenant wants it.
            List<String> recipients = recipientResolver.emailsForAccount(account);
            if (recipients.isEmpty()) {
                logger.warn("[AwsAlerts] {} new alert(s) for account {} but no recipients configured",
                        notifiable.size(), accountId);
                return;
            }

            String html = alertEmailBuilder.buildNewAlertsDigestEmail(
                    notifiable, delta.totalOpen(), delta.coldStart(), accountName, accountId);
            String subject = delta.coldStart()
                    ? String.format("[XamOps] Alert Digest — %d alert%s for %s",
                            delta.totalOpen(), delta.totalOpen() == 1 ? "" : "s", accountName)
                    : String.format("[XamOps] %d new alert%s for %s (%d open)",
                            notifiable.size(), notifiable.size() == 1 ? "" : "s",
                            accountName, delta.totalOpen());

            for (String email : recipients) {
                try {
                    emailService.sendHtmlEmail(email, subject, html);
                    logger.info("[AwsAlerts] Sent digest to {} ({} new, {} open)",
                            email, notifiable.size(), delta.totalOpen());
                } catch (Exception e) {
                    logger.error("[AwsAlerts] Failed to send digest to {}: {}", email, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("[AwsAlerts] Notification run failed for account {}: {}", accountId, e.getMessage(), e);
        }
    }

    /**
     * How recently an anomaly must have been running to still be an alert. Matches the GCP page.
     *
     * <p>Cost Explorer is asked for 60 days of anomalies, which is right for the FinOps history
     * but not for an alert: every one of them used to be listed as CRITICAL, including spikes that
     * ended weeks ago.
     */
    static final int ANOMALY_ALERT_MAX_AGE_DAYS = 14;

    /**
     * Smallest overspend that is an alert, in USD. Cost Explorer reports anomalies of a few cents —
     * CloudWatch Events at $0.01 over expected — and listed on the Alerts page each one counted as
     * a warning. They remain visible on the FinOps anomaly view.
     */
    static final double ANOMALY_ALERT_MIN_IMPACT_USD = 1.0;

    /**
     * Below this overspend, in USD, a spike is at most MEDIUM however large its multiple: tripling
     * a $0.50/day line item is worth a look, not an emergency.
     */
    static final double ANOMALY_SEVERE_MIN_IMPACT_USD = 10.0;

    /**
     * Alert rows for the anomalies still recent enough to act on, graded by size.
     *
     * <p>Package-private and static so the filtering and grading can be tested without AWS.
     */
    static List<AlertDto> anomalyAlerts(List<DashboardData.CostAnomaly> anomalies, LocalDate today) {
        if (anomalies == null) {
            return List.of();
        }
        LocalDate cutoff = today.minusDays(ANOMALY_ALERT_MAX_AGE_DAYS);
        List<AlertDto> result = new ArrayList<>();
        for (DashboardData.CostAnomaly a : anomalies) {
            // The end date, not the start: an anomaly that began three weeks ago and is still
            // running is current.
            LocalDate lastSeen = a.getEndDate() != null ? a.getEndDate() : a.getStartDate();
            if (lastSeen != null && lastSeen.isBefore(cutoff)) {
                continue;
            }
            if (a.getUnexpectedSpend() < ANOMALY_ALERT_MIN_IMPACT_USD) {
                continue;
            }

            String service = a.getService() != null && !a.getService().isBlank()
                    ? a.getService()
                    : "Unidentified service";
            String severity = anomalySeverity(a);
            String id = a.getAnomalyId() != null && !a.getAnomalyId().isBlank()
                    ? a.getAnomalyId()
                    : "anomaly-" + service.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase() + "-" + a.getStartDate();

            AlertDto alert = new AlertDto(
                    id,
                    service,
                    // The start date is in the name so two spikes on one service stay two alerts.
                    // The digest de-duplicates on type, name, service, region and status, and the
                    // old constant name "Cost Anomaly Detected" made a second spike on a service
                    // look already seen, so it was never emailed.
                    "Cost spike — " + service + (a.getStartDate() != null ? " (" + a.getStartDate() + ")" : ""),
                    describeAwsAnomaly(a),
                    SecuritySeverity.CRITICAL.equals(severity) ? "CRITICAL" : "WARNING",
                    a.getUnexpectedSpend(),
                    0,
                    "ANOMALY",
                    "Global");
            alert.setSeverity(severity);
            // Cost Explorer reports impact in the payer account's billing currency, which this
            // class does not fetch. USD is the existing assumption — stated here rather than
            // buried in a format string, so it is visible when an account bills in something else.
            alert.setCurrencySymbol("$");
            result.add(alert);
        }
        return result;
    }

    /**
     * Severity from how far spend moved from what Cost Explorer expected, with the thresholds
     * the GCP page uses. These were all CRITICAL before, because the expected spend was not being
     * read; Cost Explorer reports it, so a 20% overshoot and a tenfold one no longer look alike.
     *
     * <p>The multiple alone overstates small line items — $3 against an expected $0.50 is sixfold —
     * so CRITICAL and HIGH also need the overspend to reach {@link #ANOMALY_SEVERE_MIN_IMPACT_USD}.
     */
    static String anomalySeverity(DashboardData.CostAnomaly a) {
        double ratio = a.getChangeRatio();
        boolean severeAmount = a.getUnexpectedSpend() >= ANOMALY_SEVERE_MIN_IMPACT_USD;
        if (ratio >= 5.0 && severeAmount) {
            return SecuritySeverity.CRITICAL;
        }
        if (ratio >= 2.5 && severeAmount) {
            return SecuritySeverity.HIGH;
        }
        // A modest overshoot, or no expected spend reported. MEDIUM rather than a guess either
        // way: Cost Explorer flagged it, so something happened.
        return SecuritySeverity.MEDIUM;
    }

    /**
     * Names the service, the dates, the excess and what it was measured against.
     *
     * <p>The original wording was "Unexpected spend of $1234.00" and nothing else — no service,
     * no date. On an account with several anomalies every row read identically apart from the
     * number, so there was no way to tell which one to look at first.
     */
    private static String describeAwsAnomaly(DashboardData.CostAnomaly a) {
        String service = a.getService() != null && !a.getService().isBlank()
                ? a.getService()
                : "An unidentified service";
        String excess = String.format("$%,.2f", a.getUnexpectedSpend());
        String when;
        if (a.getStartDate() == null) {
            when = "";
        } else if (a.getEndDate() != null && !a.getEndDate().equals(a.getStartDate())) {
            when = String.format(" between %s and %s", a.getStartDate(), a.getEndDate());
        } else {
            when = String.format(" on %s", a.getStartDate());
        }
        double ratio = a.getChangeRatio();
        if (ratio <= 0) {
            return String.format("%s: unexpected spend of %s%s.", service, excess, when);
        }
        return String.format("%s: %s more than the expected $%,.2f%s — %.1f× normal.",
                service, excess, a.getBaselineSpend(), when, ratio);
    }

    /**
     * An error row naming which source failed and, where it can tell, why.
     *
     * <p>Permission failures are called out because they are the common case and the only one
     * the customer can fix themselves; "FETCH_ERROR" and a raw SDK message did not say that.
     */
    static AlertErrorDto sourceError(String source, Throwable e) {
        Throwable root = e;
        while ((root instanceof java.util.concurrent.CompletionException
                || root instanceof java.util.concurrent.ExecutionException) && root.getCause() != null) {
            root = root.getCause();
        }
        String detail = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        String lower = detail.toLowerCase(Locale.ROOT);

        String type;
        String message;
        if (lower.contains("security token included in the request is expired")
                || lower.contains("expiredtoken") || lower.contains("token has expired")) {
            // Checked before access-denied: the STS error for this reads like a permission
            // problem, but nothing is wrong with the customer's role — the credentials XamOps
            // signs in with have lapsed, and every check fails the same way until they renew.
            type = "CREDENTIALS_EXPIRED";
            message = "The AWS credentials XamOps uses to reach this account have expired, so it could not sign in. "
                    + detail;
        } else if (lower.contains("accessdenied") || lower.contains("access denied")
                || lower.contains("not authorized") || lower.contains("unauthorizedoperation")) {
            type = "PERMISSION_DENIED";
            message = "XamOps' role in this AWS account is not allowed to read this. " + detail;
        } else if (lower.contains("throttl") || lower.contains("rate exceeded")) {
            type = "RATE_LIMIT";
            message = "AWS is rate-limiting requests right now. Retry in a few minutes. " + detail;
        } else {
            type = "API_ERROR";
            message = detail;
        }
        return AlertErrorDto.builder()
                .service(source)
                .errorType(type)
                .message(message)
                .timestamp(Instant.now().toString())
                .build();
    }

    public CompletableFuture<AlertsResponseDto> getAlerts(String accountId, boolean forceRefresh) {
        long startTime = System.currentTimeMillis();
        logger.info("🔍 Fetching alerts for account: {}", accountId);

        final String dbKey = "alerts-" + accountId;

        if (!forceRefresh) {
            // 1. Redis first — fast, no DB connection consumed
            Optional<AlertsResponseDto> redisResponse = redisCache.get(dbKey, AlertsResponseDto.class);
            if (redisResponse.isPresent() && redisResponse.get().getAlerts() != null
                    && !redisResponse.get().getAlerts().isEmpty()) {
                logger.info("✅ Serving {} alerts from Redis cache for account {}",
                        redisResponse.get().getAlerts().size(), accountId);
                return CompletableFuture.completedFuture(redisResponse.get());
            }

            // 2. DB fallback — only when Redis is cold (after restart/deployment), and only while
            // the snapshot is young enough to still describe the account.
            AlertsResponseDto cachedResponse = (AlertsResponseDto) dashboardDataService.getAlertsSnapshot(
                    dbKey, new TypeReference<AlertsResponseDto>() {
                    }, ALERTS_SNAPSHOT_MAX_AGE_MINUTES);

            if (cachedResponse != null && cachedResponse.getAlerts() != null && !cachedResponse.getAlerts().isEmpty()) {
                logger.info("✅ Serving {} alerts from DB — repopulating Redis for account {}",
                        cachedResponse.getAlerts().size(), accountId);

                if (cachedResponse.getMetadata() == null) {
                    cachedResponse.setMetadata(AlertsMetadataDto.builder()
                            .totalAlerts(cachedResponse.getAlerts().size())
                            .timestamp(Instant.now().toString())
                            .fromCache(true)
                            .build());
                }
                redisCache.put(dbKey, cachedResponse, 60); // re-warm Redis so next request skips DB
                return CompletableFuture.completedFuture(cachedResponse);
            }
        }

        // 2. Fetch fresh data from AWS API — or join the scan already doing so.
        //
        // A scan takes tens of seconds, longer than the page waits. Pressing Retry while one
        // was still running started a second full scan beside it, doubling the AWS calls and
        // making both slower, so the retry could never catch up. Keyed by tenant as well: two
        // tenants connecting one AWS account read it through different roles.
        String flightKey = TenantContext.getCurrentTenant() + "::" + accountId;
        CompletableFuture<AlertsResponseDto> running = scansInFlight.get(flightKey);
        if (running == null) {
            CompletableFuture<AlertsResponseDto> mine = new CompletableFuture<>();
            running = scansInFlight.putIfAbsent(flightKey, mine);
            if (running == null) {
                try {
                    scanAlerts(accountId, forceRefresh, startTime).whenComplete((result, ex) -> {
                        scansInFlight.remove(flightKey, mine);
                        if (ex != null) {
                            mine.completeExceptionally(ex);
                        } else {
                            mine.complete(result);
                        }
                    });
                } catch (RuntimeException e) {
                    scansInFlight.remove(flightKey, mine);
                    throw e;
                }
                return mine;
            }
        }
        logger.info("Joining the alerts scan already running for account {}", accountId);
        return running;
    }

    /** Scans in progress, by tenant and account. See getAlerts. */
    private final Map<String, CompletableFuture<AlertsResponseDto>> scansInFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

    private CompletableFuture<AlertsResponseDto> scanAlerts(String accountId, boolean forceRefresh, long startTime) {
        final String dbKey = "alerts-" + accountId;
        logger.info("Fetching fresh alerts from AWS API for account {}", accountId);
        CloudAccount account = getAccount(accountId);

        // CloudGuard is AWS-only — return empty response for GCP/Azure accounts
        if (account.getAwsAccountId() == null || account.getAwsAccountId().isEmpty()) {
            logger.info("CloudGuard: Skipping alerts for non-AWS account {}", accountId);
            return CompletableFuture.completedFuture(AlertsResponseDto.builder()
                    .alerts(Collections.emptyList())
                    .errors(Collections.emptyList())
                    .metadata(AlertsMetadataDto.builder()
                            .totalAlerts(0)
                            .timestamp(Instant.now().toString())
                            .fromCache(false)
                            .build())
                    .build());
        }

        // Recipients are deliberately NOT resolved here any more. This method backs
        // GET /cloudguard/alerts, so the digest it used to send meant a page load — and every
        // restart, via the cache warm-up — mailed everyone on the account. The send now lives in
        // notifyNewAlerts, which the nightly scheduler is the only caller of, and which resolves
        // recipients through the same NotificationRecipientResolver so per-user opt-out still
        // applies.

        // Fetch all alert types in parallel
        CompletableFuture<List<DashboardData.ServiceQuotaInfo>> vpcQuotasFuture = getVpcQuotaAlerts(accountId,
                forceRefresh);
        CompletableFuture<List<DashboardData.ServiceQuotaInfo>> networkQuotasFuture = getNetworkQuotaAlerts(accountId,
                forceRefresh);
        // Anomalies follow forceRefresh like every other source. They used to be read cache-first
        // on the assumption that a FinOps job refreshed them nightly, but nothing forces that
        // fetch on a schedule — so the Refresh button re-scanned everything except cost, and the
        // cached list could be as old as the last time someone force-refreshed FinOps.
        CompletableFuture<List<DashboardData.CostAnomaly>> anomaliesFuture =
                finOpsService.getCostAnomaliesForAlerts(account, forceRefresh);
        CompletableFuture<List<AlertDto>> securityFuture = securityHubService.getSecurityAlerts(accountId,
                forceRefresh);

        // handle, not thenApply. allOf completes exceptionally when any one source does, and
        // thenApply would then skip the whole block — discarding every source that succeeded and
        // never reaching the per-source catches below. allOf still waits for all four, so by the
        // time handle runs each join() returns or throws immediately.
        return CompletableFuture.allOf(vpcQuotasFuture, networkQuotasFuture, anomaliesFuture, securityFuture)
                .handle((v, anySourceFailed) -> {
                    List<AlertDto> alerts = new ArrayList<>();
                    List<AlertErrorDto> errors = new ArrayList<>();

                    int vpcCount = 0;
                    int networkCount = 0;
                    int anomalyCount = 0;
                    int securityCount = 0;

                    // Process VPC quota alerts
                    try {
                        List<DashboardData.ServiceQuotaInfo> vpcQuotas = vpcQuotasFuture.join();
                        if (vpcQuotas != null && !vpcQuotas.isEmpty()) {
                            List<AlertDto> quotaAlerts = vpcQuotas.stream()
                                    .map(q -> createQuotaAlert(q, "VPC"))
                                    .collect(Collectors.toList());
                            alerts.addAll(quotaAlerts);
                            vpcCount = quotaAlerts.size();
                            logger.info("Added {} VPC quota alerts", vpcCount);
                        }
                    } catch (Exception e) {
                        logger.error("Error joining VPC quotas for {}: {}", accountId, e.getMessage());
                        errors.add(sourceError("VPC quotas", e));
                    }

                    // Process network quota alerts (IGW, NAT, EIP)
                    try {
                        List<DashboardData.ServiceQuotaInfo> networkQuotas = networkQuotasFuture.join();
                        if (networkQuotas != null && !networkQuotas.isEmpty()) {
                            List<AlertDto> networkAlerts = networkQuotas.stream()
                                    .map(q -> createQuotaAlert(q, q.getServiceName()))
                                    .collect(Collectors.toList());
                            alerts.addAll(networkAlerts);
                            networkCount = networkAlerts.size();
                            logger.info("Added {} network quota alerts", networkCount);
                        }
                    } catch (Exception e) {
                        logger.error("Error joining network quotas for {}: {}", accountId, e.getMessage());
                        errors.add(sourceError("Network quotas", e));
                    }

                    // Process anomaly alerts
                    try {
                        List<DashboardData.CostAnomaly> anomalies = anomaliesFuture.join();
                        List<AlertDto> anomalyAlerts = anomalyAlerts(anomalies, LocalDate.now());
                        alerts.addAll(anomalyAlerts);
                        anomalyCount = anomalyAlerts.size();
                        logger.info("Added {} anomaly alerts ({} detected in the last 60 days)", anomalyCount,
                                anomalies != null ? anomalies.size() : 0);
                    } catch (Exception e) {
                        logger.error("Error joining cost anomalies for {}: {}", accountId, e.getMessage());
                        errors.add(sourceError("Cost anomalies", e));
                    }

                    // Process security alerts
                    boolean securityHubRead = false;
                    try {
                        List<AlertDto> securityAlerts = securityFuture.join();
                        securityHubRead = true;
                        if (securityAlerts != null && !securityAlerts.isEmpty()) {
                            alerts.addAll(securityAlerts);
                            securityCount = securityAlerts.size();
                            logger.info("Added {} security alerts", securityCount);
                        }
                    } catch (Exception e) {
                        logger.error("Error joining security hub findings for {}: {}", accountId, e.getMessage());
                        errors.add(sourceError("Security Hub", e));
                    }

                    // Process live event-driven findings
                    //
                    // Read straight from the table rather than joined off a future, because
                    // unlike every source above this one costs no AWS call — it is one indexed
                    // query. Added last so it sorts after the quota and anomaly rows the page
                    // was built around, and filtered to CRITICAL/HIGH inside the projection so
                    // low-severity config drift does not bury them.
                    //
                    // Security Hub findings also arrive here through EventBridge. When Security
                    // Hub was just read directly, that read is the complete current list, so its
                    // live copies are left out — otherwise every one was listed twice. When the
                    // direct read failed, the live copies are all there is, so they stay.
                    int liveFindingCount = 0;
                    try {
                        List<AlertDto> findingAlerts = liveFindings.asAlerts(account.getId(),
                                securityHubRead
                                        ? Set.of(CloudSecurityFinding.SOURCE_SECURITY_HUB)
                                        : Set.of());
                        if (!findingAlerts.isEmpty()) {
                            alerts.addAll(findingAlerts);
                            liveFindingCount = findingAlerts.size();
                            logger.info("Added {} live security finding alerts for account {}",
                                    liveFindingCount, accountId);
                        }
                    } catch (Exception e) {
                        // Never let a new source break the four that already worked.
                        logger.error("Error reading live security findings for {}: {}",
                                accountId, e.getMessage());
                        errors.add(sourceError("Live security findings", e));
                    }
                    securityCount += liveFindingCount;

                    // No email from here. This method backs GET /cloudguard/alerts, so sending from
                    // it meant a page load mailed every user on the account, as did each restart
                    // via the cache warm-up. Notification lives in notifyNewAlerts, reachable only
                    // from the scheduler.
                    long duration = System.currentTimeMillis() - startTime;
                    AlertsMetadataDto metadata = AlertsMetadataDto.builder()
                            .totalAlerts(alerts.size())
                            .vpcQuotaCount(vpcCount)
                            .networkQuotaCount(networkCount)
                            .costAnomalyCount(anomalyCount)
                            .securityAlertCount(securityCount)
                            .fetchDurationMs(duration)
                            .timestamp(Instant.now().toString())
                            .fromCache(false)
                            .build();

                    logger.info("Returning {} total alerts for account {} in {}ms", alerts.size(), accountId, duration);
                    AlertsResponseDto response = AlertsResponseDto.builder()
                            .alerts(alerts)
                            .errors(errors)
                            .metadata(metadata)
                            .build();
                    redisCache.put(dbKey, response, 60); // save to Redis after fresh AWS fetch
                    return response;
                }).exceptionally(ex -> {
                    logger.error("Fatal error fetching alerts for account {}: {}", accountId, ex.getMessage(), ex);
                    return AlertsResponseDto.builder()
                            .alerts(Collections.emptyList())
                            .errors(List.of(AlertErrorDto.builder()
                                    .service("SYSTEM")
                                    .errorType("FATAL_ERROR")
                                    .message(ex.getMessage())
                                    .timestamp(Instant.now().toString())
                                    .build()))
                            .metadata(AlertsMetadataDto.builder()
                                    .timestamp(Instant.now().toString())
                                    .build())
                            .build();
                });
    }

    /**
     * Create an AlertDto from a ServiceQuotaInfo.
     */
    private AlertDto createQuotaAlert(DashboardData.ServiceQuotaInfo q, String serviceName) {
        // Quota name and region identify the row; a running counter used to be appended, which
        // gave the same quota a different id whenever the list around it changed.
        String uniqueId = String.format("quota-%s-%s",
                q.getQuotaName().replaceAll("[^a-zA-Z0-9]", "-").toLowerCase(),
                q.getRegionId());

        double utilization = q.getLimit() > 0 ? (q.getUsage() / q.getLimit() * 100) : 0;

        return new AlertDto(
                uniqueId,
                serviceName,
                q.getQuotaName(),
                String.format("Current usage: %.0f / %.0f (%.1f%%)", q.getUsage(), q.getLimit(), utilization),
                determineQuotaStatus(q.getUsage(), q.getLimit()),
                q.getUsage(),
                q.getLimit(),
                "QUOTA",
                q.getRegionId());
    }

    /**
     * Determine quota status based on usage percentage
     */
    private String determineQuotaStatus(double usage, double limit) {
        if (limit == 0)
            return "UNKNOWN";
        double percentage = (usage / limit) * 100;
        if (percentage >= 90)
            return "CRITICAL";
        if (percentage >= 75)
            return "WARNING";
        return "OK";
    }
}
