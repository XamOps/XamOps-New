package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.computeoptimizer.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.ImageState;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeNetworkInterfacesRequest;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.efs.EfsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class OptimizationService {

    private static final Logger logger = LoggerFactory.getLogger(OptimizationService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final PricingService pricingService;
    private final CloudListService cloudListService;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public OptimizationService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            PricingService pricingService,
            @Lazy CloudListService cloudListService,
            RedisCacheService redisCache,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.pricingService = pricingService;
        this.cloudListService = cloudListService;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    private CloudAccount getAccount(String accountId) {
        // MODIFIED: Handle list of accounts to prevent crash
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }
        return accounts.get(0); // Return the first one found
    }

    /**
     * Helper method to archive data to PostgreSQL for Superset analytics.
     */
    private void archiveData(String prefix, String accountId, Object data) {
        try {
            // Key format: TYPE::ACCOUNT_ID::DATE (e.g., WASTE::123456789::2023-10-27)
            String dbKey = prefix + "::" + accountId + "::" + LocalDate.now();
            String json = objectMapper.writeValueAsString(data);

            CachedData archivalData = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            archivalData.setCacheKey(dbKey);
            archivalData.setJsonData(json);
            archivalData.setLastUpdated(LocalDateTime.now());

            cachedDataRepository.save(archivalData);
            logger.info("✅ Archived {} data to DB for account {}", prefix, accountId);
        } catch (Exception e) {
            logger.error("❌ Failed to archive {} data for account {}", prefix, accountId, e);
        }
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getAllOptimizationRecommendations(
            String accountId, boolean forceRefresh) {
        String cacheKey = "allRecommendations-" + accountId;
        if (!forceRefresh) {
            // 1. Check Redis first (fast, no DB connection consumed)
            Optional<List<DashboardData.OptimizationRecommendation>> cachedData = redisCache.get(cacheKey,
                    new TypeReference<>() {
                    });
            if (cachedData.isPresent()) {
                logger.info("✅ Redis cache hit for rightsizing recommendations for account {}", accountId);
                return CompletableFuture.completedFuture(cachedData.get());
            }

            // 2. DB fallback — only used when Redis is cold (after restart/deployment)
            try {
                String dbKey = "rightsizing-" + accountId;
                Optional<CachedData> dbCache = cachedDataRepository.findById(dbKey);
                if (dbCache.isPresent()) {
                    List<DashboardData.OptimizationRecommendation> allRecs = objectMapper.readValue(
                        dbCache.get().getJsonData(),
                        new TypeReference<List<DashboardData.OptimizationRecommendation>>() {});
                    if (!allRecs.isEmpty()) {
                        logger.info("✅ Serving {} rightsizing recs from DB — repopulating Redis for account {}",
                            allRecs.size(), accountId);
                        redisCache.put(cacheKey, allRecs, 60); // re-warm Redis so next request skips DB
                        return CompletableFuture.completedFuture(allRecs);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to read rightsizing data from database cache: {}", e.getMessage());
            }
        }

        // 3. Fetch fresh data from AWS API
        logger.info("Fetching fresh rightsizing recommendations from AWS API for account {}", accountId);
        CloudAccount account = getAccount(accountId);
        return cloudListService.getRegionStatusForAccount(account, forceRefresh).thenCompose(activeRegions -> {
            if (activeRegions == null || activeRegions.isEmpty()) {
                logger.warn("No active regions found for account {}. Skipping optimization recommendations.",
                        account.getAwsAccountId());
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            logger.info("Fetching all optimization recommendations for account {}...", account.getAwsAccountId());
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ec2 = getEc2InstanceRecommendations(
                    account, activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> ebs = getEbsVolumeRecommendations(account,
                    activeRegions, forceRefresh);
            CompletableFuture<List<DashboardData.OptimizationRecommendation>> lambda = getLambdaFunctionRecommendations(
                    account, activeRegions, forceRefresh);
            return CompletableFuture.allOf(ec2, ebs, lambda).thenApply(v -> {
                List<DashboardData.OptimizationRecommendation> allRecs = Stream
                        .of(ec2.join(), ebs.join(), lambda.join()).flatMap(List::stream).collect(Collectors.toList());
                logger.debug("Fetched a total of {} optimization recommendations for account {}", allRecs.size(),
                        accountId);
                redisCache.put(cacheKey, allRecs, 60);
                // Save merged results under a stable date-independent key so the DB cache
                // survives across midnight without a daily miss.
                try {
                    String dbKey = "rightsizing-" + accountId;
                    CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                    snapshot.setCacheKey(dbKey);
                    snapshot.setJsonData(objectMapper.writeValueAsString(allRecs));
                    snapshot.setLastUpdated(LocalDateTime.now());
                    cachedDataRepository.save(snapshot);
                    logger.info("✅ Saved rightsizing snapshot to DB for account {}", accountId);
                } catch (Exception e) {
                    logger.warn("Failed to save rightsizing snapshot to DB: {}", e.getMessage());
                }
                return allRecs;
            });
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getEc2InstanceRecommendations(
            CloudAccount account, List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "ec2Recs-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.OptimizationRecommendation>> cachedData = redisCache.get(cacheKey,
                    new TypeReference<>() {
                    });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ComputeOptimizerClient co = awsClientProvider.getComputeOptimizerClient(account, regionId);
            List<DashboardData.OptimizationRecommendation> recommendations = new ArrayList<>();
            String nextToken = null;

            // **FIXED LOGIC**: Use raw strings instead of enum constants to avoid
            // resolution issues.
            RecommendationPreferences recommendationPreferences = RecommendationPreferences.builder()
                    .cpuVendorArchitectures(List.of(CpuVendorArchitecture.AWS_ARM64))
                    .build();

            do {
                GetEc2InstanceRecommendationsRequest.Builder requestBuilder = GetEc2InstanceRecommendationsRequest
                        .builder()
                        .recommendationPreferences(recommendationPreferences); // Apply the preference

                if (nextToken != null) {
                    requestBuilder.nextToken(nextToken);
                }
                GetEc2InstanceRecommendationsResponse response = co
                        .getEC2InstanceRecommendations(requestBuilder.build());

                response.instanceRecommendations().stream()
                        .filter(r -> r.finding() != null && r.finding() != Finding.OPTIMIZED
                                && r.recommendationOptions() != null && !r.recommendationOptions().isEmpty())
                        .map(r -> {
                            InstanceRecommendationOption bestOption = r.recommendationOptions().stream()
                                    .max(Comparator.comparingDouble(opt -> opt.savingsOpportunity() != null
                                            && opt.savingsOpportunity().estimatedMonthlySavings() != null
                                            && opt.savingsOpportunity().estimatedMonthlySavings().value() != null
                                                    ? opt.savingsOpportunity().estimatedMonthlySavings().value()
                                                    : 0.0))
                                    .orElse(r.recommendationOptions().get(0));
                            double savings = bestOption.savingsOpportunity() != null
                                    && bestOption.savingsOpportunity().estimatedMonthlySavings() != null
                                    && bestOption.savingsOpportunity().estimatedMonthlySavings().value() != null
                                            ? bestOption.savingsOpportunity().estimatedMonthlySavings().value()
                                            : 0.0;

                            double recommendedCost = pricingService
                                    .getEc2InstanceMonthlyPrice(bestOption.instanceType(), regionId);
                            double currentCost = recommendedCost + savings;

                            // --- Start of Fix ---
                            // First, try to get the detailed reason codes.
                            String reason = r.findingReasonCodes().stream().map(Object::toString)
                                    .collect(Collectors.joining(", "));

                            // If there are no detailed codes, fall back to the general finding.
                            if (reason.isEmpty() && r.finding() != null) {
                                reason = r.finding().toString();
                            }
                            // --- End of Fix ---

                            return new DashboardData.OptimizationRecommendation(
                                    "EC2", r.instanceArn().split("/")[1], r.currentInstanceType(),
                                    bestOption.instanceType(),
                                    savings, reason, // Use the new 'reason' variable
                                    currentCost, recommendedCost);
                        })
                        .forEach(recommendations::add);

                nextToken = response.nextToken();
            } while (nextToken != null);

            return recommendations;
        }, "EC2 Recommendations").thenApply(result -> {
            redisCache.put(cacheKey, result, 60);

            // ✅ Archive Data for Superset
            archiveData("RIGHTSIZING_EC2", account.getAwsAccountId(), result);

            return result;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getEbsVolumeRecommendations(
            CloudAccount account, List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "ebsRecs-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.OptimizationRecommendation>> cachedData = redisCache.get(cacheKey,
                    new TypeReference<>() {
                    });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ComputeOptimizerClient co = awsClientProvider.getComputeOptimizerClient(account, regionId);
            List<DashboardData.OptimizationRecommendation> recommendations = new ArrayList<>();
            String nextToken = null;

            do {
                GetEbsVolumeRecommendationsRequest.Builder requestBuilder = GetEbsVolumeRecommendationsRequest
                        .builder();
                if (nextToken != null) {
                    requestBuilder.nextToken(nextToken);
                }
                GetEbsVolumeRecommendationsResponse response = co.getEBSVolumeRecommendations(requestBuilder.build());

                response.volumeRecommendations().stream()
                        .filter(r -> r.finding() != null && !r.finding().toString().equals("OPTIMIZED")
                                && r.volumeRecommendationOptions() != null
                                && !r.volumeRecommendationOptions().isEmpty())
                        .map(r -> {
                            VolumeRecommendationOption bestOption = r.volumeRecommendationOptions().stream()
                                    .max(Comparator.comparingDouble(opt -> opt.savingsOpportunity() != null
                                            && opt.savingsOpportunity().estimatedMonthlySavings() != null
                                                    ? opt.savingsOpportunity().estimatedMonthlySavings().value()
                                                    : 0.0))
                                    .orElse(r.volumeRecommendationOptions().get(0));

                            double savings = bestOption.savingsOpportunity() != null
                                    && bestOption.savingsOpportunity().estimatedMonthlySavings() != null
                                            ? bestOption.savingsOpportunity().estimatedMonthlySavings().value()
                                            : 0.0;

                            double recommendedCost = pricingService.getEbsGbMonthPrice(regionId,
                                    bestOption.configuration().volumeType()) * bestOption.configuration().volumeSize();
                            double currentCost = savings + recommendedCost;

                            return new DashboardData.OptimizationRecommendation(
                                    "EBS", r.volumeArn().split("/")[1],
                                    r.currentConfiguration().volumeType() + " - "
                                            + r.currentConfiguration().volumeSize() + "GiB",
                                    bestOption.configuration().volumeType() + " - "
                                            + bestOption.configuration().volumeSize() + "GiB",
                                    savings, r.finding().toString(), currentCost, recommendedCost);
                        })
                        .forEach(recommendations::add);

                nextToken = response.nextToken();
            } while (nextToken != null);

            return recommendations;
        }, "EBS Recommendations").thenApply(result -> {
            redisCache.put(cacheKey, result, 60);

            // ✅ Archive Data for Superset
            archiveData("RIGHTSIZING_EBS", account.getAwsAccountId(), result);

            return result;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.OptimizationRecommendation>> getLambdaFunctionRecommendations(
            CloudAccount account, List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "lambdaRecs-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.OptimizationRecommendation>> cachedData = redisCache.get(cacheKey,
                    new TypeReference<>() {
                    });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ComputeOptimizerClient co = awsClientProvider.getComputeOptimizerClient(account, regionId);
            List<DashboardData.OptimizationRecommendation> recommendations = new ArrayList<>();
            String nextToken = null;

            try {
                do {
                    GetLambdaFunctionRecommendationsRequest.Builder requestBuilder = GetLambdaFunctionRecommendationsRequest
                            .builder();
                    if (nextToken != null) {
                        requestBuilder.nextToken(nextToken);
                    }
                    GetLambdaFunctionRecommendationsResponse response = co
                            .getLambdaFunctionRecommendations(requestBuilder.build());

                    response.lambdaFunctionRecommendations().stream()
                            .filter(r -> r.finding() != null
                                    && r.finding() != LambdaFunctionRecommendationFinding.OPTIMIZED
                                    && r.memorySizeRecommendationOptions() != null
                                    && !r.memorySizeRecommendationOptions().isEmpty())
                            .map(r -> {
                                LambdaFunctionMemoryRecommendationOption bestOption = r
                                        .memorySizeRecommendationOptions().stream()
                                        .max(Comparator.comparingDouble(opt -> opt.savingsOpportunity() != null
                                                && opt.savingsOpportunity().estimatedMonthlySavings() != null
                                                        ? opt.savingsOpportunity().estimatedMonthlySavings().value()
                                                        : 0.0))
                                        .orElse(r.memorySizeRecommendationOptions().get(0));

                                double savings = bestOption.savingsOpportunity() != null
                                        && bestOption.savingsOpportunity().estimatedMonthlySavings() != null
                                                ? bestOption.savingsOpportunity().estimatedMonthlySavings().value()
                                                : 0.0;
                                double recommendedCost = savings > 0 ? savings * 1.5 : 0; // Simplified cost
                                double currentCost = recommendedCost + savings;

                                return new DashboardData.OptimizationRecommendation(
                                        "Lambda", r.functionArn().substring(r.functionArn().lastIndexOf(':') + 1),
                                        r.currentMemorySize() + " MB", bestOption.memorySize() + " MB",
                                        savings,
                                        r.findingReasonCodes().stream().map(Object::toString)
                                                .collect(Collectors.joining(", ")),
                                        currentCost, recommendedCost);
                            })
                            .forEach(recommendations::add);

                    nextToken = response.nextToken();
                } while (nextToken != null);
            } catch (Exception e) {
                logger.error(
                        "Could not fetch Lambda Recommendations for account {} in region {}. This might be a permissions issue or Compute Optimizer is not enabled.",
                        account.getAwsAccountId(), regionId, e);
            }
            return recommendations;
        }, "Lambda Recommendations").thenApply(result -> {
            redisCache.put(cacheKey, result, 60);

            // ✅ Archive Data for Superset
            archiveData("RIGHTSIZING_LAMBDA", account.getAwsAccountId(), result);

            return result;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.WastedResource>> getWastedResources(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions, boolean forceRefresh) {
        String cacheKey = "wastedResources-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.WastedResource>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {
            });
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }
        if (activeRegions == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        logger.info("Fetching wasted resources for account {}...", account.getAwsAccountId());
        List<CompletableFuture<List<DashboardData.WastedResource>>> futures = List.of(
                findUnattachedEbsVolumes(account, activeRegions), findUnusedElasticIps(account, activeRegions),
                findOldSnapshots(account, activeRegions), findDeregisteredAmis(account, activeRegions),
                findIdleRdsInstances(account, activeRegions), findIdleLoadBalancers(account, activeRegions),
                findUnusedSecurityGroups(account, activeRegions), findIdleEc2Instances(account, activeRegions),
                findStoppedEc2InstancesForTooLong(account, activeRegions),
                findUnattachedEnis(account, activeRegions), findIdleEksClusters(account, activeRegions),
                findIdleEcsClusters(account, activeRegions), findUnderutilizedLambdaFunctions(account, activeRegions),
                findOldDbSnapshots(account, activeRegions), findUnusedCloudWatchLogGroups(account, activeRegions),
                findS3StorageClassWaste(account), findIdleEfsFileSystems(account, activeRegions),
                findIdleElastiCacheClusters(account, activeRegions), findUnusedDynamoDbTables(account, activeRegions),
                findStaleEcrRepositories(account, activeRegions), findExpiredAcmCertificates(account, activeRegions),
                findIdleSnsTopics(account, activeRegions), findIdleSqsQueues(account, activeRegions));

        List<CompletableFuture<List<DashboardData.WastedResource>>> safeFutures = futures.stream()
                .map(f -> f.exceptionally(e -> {
                    logger.warn("Waste detection method failed, skipping: {}", e.getMessage());
                    return Collections.emptyList();
                }))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(safeFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<DashboardData.WastedResource> allWasted = safeFutures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .collect(Collectors.toList());
                    logger.debug("... found {} total wasted resources for account {}.", allWasted.size(),
                            account.getAwsAccountId());
                    redisCache.put(cacheKey, allWasted, 60);

                    // ✅ Archive Data for Superset
                    archiveData("WASTE", account.getAwsAccountId(), allWasted);

                    return allWasted;
                });
    }

    // ... [ The rest of the waste-finding methods (findUnattachedEbsVolumes, etc.)
    // remain unchanged ] ...
    private CompletableFuture<List<DashboardData.WastedResource>> findUnattachedEbsVolumes(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2
                    .describeVolumes(req -> req.filters(List.of(software.amazon.awssdk.services.ec2.model.Filter
                            .builder().name("status").values("available").build())))
                    .volumes().stream()
                    .map(volume -> {
                        double monthlyCost = calculateEbsMonthlyCost(volume, regionId);
                        return new DashboardData.WastedResource(volume.volumeId(),
                                getTagName(volume.tags(), volume.volumeId()), "EBS Volume", regionId, monthlyCost,
                                "Unattached Volume");
                    })
                    .collect(Collectors.toList());
        }, "Unattached EBS Volumes");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnusedElasticIps(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            double monthlyCost = pricingService.getElasticIpMonthlyPrice(regionId);
            return ec2.describeAddresses().addresses().stream()
                    .filter(address -> address.associationId() == null)
                    .map(address -> new DashboardData.WastedResource(address.allocationId(), address.publicIp(),
                            "Elastic IP", regionId, monthlyCost, "Unassociated EIP"))
                    .collect(Collectors.toList());
        }, "Unused Elastic IPs");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findOldSnapshots(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
            return ec2.describeSnapshots(r -> r.ownerIds("self")).snapshots().stream()
                    .filter(s -> s.startTime().isBefore(ninetyDaysAgo))
                    .map(snapshot -> new DashboardData.WastedResource(snapshot.snapshotId(),
                            getTagName(snapshot.tags(), snapshot.snapshotId()), "Snapshot", regionId,
                            calculateSnapshotMonthlyCost(snapshot), "Older than 90 days"))
                    .collect(Collectors.toList());
        }, "Old Snapshots");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findDeregisteredAmis(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            DescribeImagesRequest imagesRequest = DescribeImagesRequest.builder().owners("self").build();
            return ec2.describeImages(imagesRequest).images().stream()
                    .filter(image -> image.state() != ImageState.AVAILABLE)
                    .map(image -> new DashboardData.WastedResource(image.imageId(), image.name(), "AMI", regionId, 0.5,
                            "Deregistered or Failed State"))
                    .collect(Collectors.toList());
        }, "Deregistered AMIs");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleRdsInstances(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            RdsClient rds = awsClientProvider.getRdsClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<software.amazon.awssdk.services.rds.model.DBInstance> instances =
                    rds.describeDBInstances().dbInstances().stream()
                            .filter(db -> "available".equalsIgnoreCase(db.dbInstanceStatus()))
                            .collect(Collectors.toList());
            if (instances.isEmpty()) return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(7, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < instances.size(); i++) {
                queries.add(MetricDataQuery.builder().id("rds" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/RDS").metricName("DatabaseConnections")
                                        .dimensions(Dimension.builder().name("DBInstanceIdentifier")
                                                .value(instances.get(i).dbInstanceIdentifier()).build()).build())
                                .period(86400).stat("Maximum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < instances.size(); i++) {
                List<Double> values = results.getOrDefault("rds" + i, List.of());
                if (!values.isEmpty() && values.stream().allMatch(v -> v < 1)) {
                    var db = instances.get(i);
                    findings.add(new DashboardData.WastedResource(db.dbInstanceIdentifier(),
                            db.dbInstanceIdentifier(), "RDS Instance", regionId,
                            pricingService.getRdsInstanceMonthlyPrice(db, regionId),
                            "Idle RDS Instance (no connections)"));
                }
            }
            return findings;
        }, "Idle RDS Instances");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleLoadBalancers(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, regionId);
            return elbv2.describeLoadBalancers().loadBalancers().stream()
                    .filter(lb -> {
                        boolean isIdle = elbv2.describeTargetGroups(req -> req.loadBalancerArn(lb.loadBalancerArn()))
                                .targetGroups().stream()
                                .allMatch(
                                        tg -> elbv2.describeTargetHealth(req -> req.targetGroupArn(tg.targetGroupArn()))
                                                .targetHealthDescriptions().isEmpty());
                        return isIdle;
                    })
                    .map(lb -> new DashboardData.WastedResource(lb.loadBalancerArn(), lb.loadBalancerName(),
                            "Load Balancer", regionId, 17.0, "Idle Load Balancer (no targets)"))
                    .collect(Collectors.toList());
        }, "Idle Load Balancers");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleEksClusters(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EksClient eks = awsClientProvider.getEksClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<String> clusters = eks.listClusters().clusters();
            if (clusters.isEmpty()) return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(14, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < clusters.size(); i++) {
                queries.add(MetricDataQuery.builder().id("eks" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("ContainerInsights").metricName("node_cpu_utilization")
                                        .dimensions(Dimension.builder().name("ClusterName").value(clusters.get(i)).build()).build())
                                .period(86400).stat("Average").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < clusters.size(); i++) {
                List<Double> values = results.getOrDefault("eks" + i, List.of());
                if (!values.isEmpty()) {
                    double avgCpu = values.stream().mapToDouble(Double::doubleValue).average().orElse(100.0);
                    if (avgCpu < 5.0) {
                        findings.add(new DashboardData.WastedResource(clusters.get(i), clusters.get(i), "EKS Cluster",
                                regionId, 73.0, "Idle Cluster (low CPU)"));
                    }
                }
            }

            return findings;
        }, "Idle EKS Clusters");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleEcsClusters(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EcsClient ecs = awsClientProvider.getEcsClient(account, regionId);
            return ecs.listClusters().clusterArns().stream()
                    .map(clusterArn -> ecs.describeClusters(r -> r.clusters(clusterArn)).clusters().get(0))
                    .filter(cluster -> cluster.runningTasksCount() == 0 && cluster.activeServicesCount() == 0)
                    .map(cluster -> new DashboardData.WastedResource(cluster.clusterArn(), cluster.clusterName(),
                            "ECS Cluster", regionId, 0.0, "Idle Cluster (0 tasks/services)"))
                    .collect(Collectors.toList());
        }, "Idle ECS Clusters");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnderutilizedLambdaFunctions(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            LambdaClient lambda = awsClientProvider.getLambdaClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<software.amazon.awssdk.services.lambda.model.FunctionConfiguration> functions =
                    lambda.listFunctions().functions();
            if (functions.isEmpty()) return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < functions.size(); i++) {
                queries.add(MetricDataQuery.builder().id("fn" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/Lambda").metricName("Invocations")
                                        .dimensions(Dimension.builder().name("FunctionName")
                                                .value(functions.get(i).functionName()).build()).build())
                                .period(86400).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < functions.size(); i++) {
                double total = results.getOrDefault("fn" + i, List.of()).stream()
                        .mapToDouble(Double::doubleValue).sum();
                if (total < 10) {
                    var func = functions.get(i);
                    findings.add(new DashboardData.WastedResource(func.functionArn(), func.functionName(), "Lambda",
                            regionId, 0.50, "Low Invocations (<10 in 30d)"));
                }
            }
            return findings;
        }, "Underutilized Lambda Functions");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findOldDbSnapshots(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            RdsClient rds = awsClientProvider.getRdsClient(account, regionId);
            Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
            return rds.describeDBSnapshots(r -> r.snapshotType("manual")).dbSnapshots().stream()
                    .filter(snap -> snap.snapshotCreateTime().isBefore(ninetyDaysAgo))
                    .map(snap -> new DashboardData.WastedResource(snap.dbSnapshotIdentifier(),
                            snap.dbSnapshotIdentifier(), "DB Snapshot", regionId, snap.allocatedStorage() * 0.095,
                            "Older than 90 days"))
                    .collect(Collectors.toList());
        }, "Old DB Snapshots");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnusedCloudWatchLogGroups(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CloudWatchLogsClient logs = awsClientProvider.getCloudWatchLogsClient(account, regionId);
            long sixtyDaysAgoMillis = Instant.now().minus(60, ChronoUnit.DAYS).toEpochMilli();
            List<DashboardData.WastedResource> findings = new ArrayList<>();
            List<LogGroup> logGroups = logs.describeLogGroups().logGroups();

            for (LogGroup lg : logGroups) {
                if (lg.creationTime() < sixtyDaysAgoMillis && lg.storedBytes() == 0) {
                    findings.add(new DashboardData.WastedResource(lg.logGroupName(), lg.logGroupName(), "Log Group",
                            regionId, 0.0, "Empty and created over 60 days ago"));
                    continue;
                }
                try {
                    DescribeLogStreamsResponse response = logs.describeLogStreams(req -> req
                            .logGroupName(lg.logGroupName()).orderBy("LastEventTime").descending(true).limit(1));
                    if (response.logStreams().isEmpty() || response.logStreams().get(0).lastEventTimestamp() == null
                            || response.logStreams().get(0).lastEventTimestamp() < sixtyDaysAgoMillis) {
                        findings.add(new DashboardData.WastedResource(lg.logGroupName(), lg.logGroupName(), "Log Group",
                                regionId, 0.0, "No new events in 60+ days"));
                    }
                } catch (Exception e) {
                    logger.warn("Could not describe log streams for {}: {}", lg.logGroupName(), e.getMessage());
                }
            }
            return findings;
        }, "Unused Log Groups");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnusedSecurityGroups(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeSecurityGroups().securityGroups().stream()
                    .filter(sg -> sg.vpcId() != null && isSecurityGroupUnused(account, sg.groupId(), regionId))
                    .map(sg -> new DashboardData.WastedResource(sg.groupId(), sg.groupName(), "Security Group",
                            regionId, 0.0, "Unused Security Group"))
                    .collect(Collectors.toList());
        }, "Unused Security Groups");
    }

    private boolean isSecurityGroupUnused(CloudAccount account, String groupId, String regionId) {
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            DescribeNetworkInterfacesRequest request = DescribeNetworkInterfacesRequest.builder()
                    .filters(software.amazon.awssdk.services.ec2.model.Filter.builder().name("group-id").values(groupId)
                            .build())
                    .build();
            return ec2.describeNetworkInterfaces(request).networkInterfaces().isEmpty();
        } catch (Exception e) {
            logger.error("Could not check usage for security group {} in account {}: {}", groupId,
                    account.getAwsAccountId(), e.getMessage());
        }
        return false;
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleEc2Instances(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<Instance> instances = ec2
                    .describeInstances(r -> r.filters(f -> f.name("instance-state-name").values("running")))
                    .reservations().stream().flatMap(r -> r.instances().stream()).collect(Collectors.toList());
            if (instances.isEmpty()) return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            List<MetricDataQuery> cpuQueries = new ArrayList<>();
            List<MetricDataQuery> netQueries = new ArrayList<>();
            for (int i = 0; i < instances.size(); i++) {
                String iid = instances.get(i).instanceId();
                cpuQueries.add(MetricDataQuery.builder().id("cpu" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/EC2").metricName("CPUUtilization")
                                        .dimensions(Dimension.builder().name("InstanceId").value(iid).build()).build())
                                .period(86400).stat("Maximum").build())
                        .returnData(true).build());
                netQueries.add(MetricDataQuery.builder().id("nin" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/EC2").metricName("NetworkIn")
                                        .dimensions(Dimension.builder().name("InstanceId").value(iid).build()).build())
                                .period(86400 * 30).stat("Sum").build())
                        .returnData(true).build());
                netQueries.add(MetricDataQuery.builder().id("nou" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/EC2").metricName("NetworkOut")
                                        .dimensions(Dimension.builder().name("InstanceId").value(iid).build()).build())
                                .period(86400 * 30).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> cpuResults = batchGetMetrics(cw, cpuQueries, start, end);
            Map<String, List<Double>> netResults = batchGetMetrics(cw, netQueries, start, end);

            double maxCpuThreshold = 3.0;
            double networkThreshold = 100.0 * 1024 * 1024;
            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < instances.size(); i++) {
                double maxCpu = cpuResults.getOrDefault("cpu" + i, List.of()).stream()
                        .mapToDouble(Double::doubleValue).max().orElse(100.0);
                List<Double> ninVals = netResults.getOrDefault("nin" + i, List.of());
                List<Double> nouVals = netResults.getOrDefault("nou" + i, List.of());
                double networkIn = ninVals.isEmpty() ? Double.MAX_VALUE : ninVals.stream().mapToDouble(Double::doubleValue).sum();
                double networkOut = nouVals.isEmpty() ? Double.MAX_VALUE : nouVals.stream().mapToDouble(Double::doubleValue).sum();
                if (maxCpu < maxCpuThreshold && networkIn < networkThreshold && networkOut < networkThreshold) {
                    Instance inst = instances.get(i);
                    findings.add(new DashboardData.WastedResource(inst.instanceId(),
                            getTagName(inst.tags(), inst.instanceId()), "EC2 Instance", regionId,
                            pricingService.getEc2InstanceMonthlyPrice(inst.instanceTypeAsString(), regionId),
                            "Idle EC2 Instance (low CPU)"));
                }
            }
            return findings;
        }, "Idle EC2 Instances");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findStoppedEc2InstancesForTooLong(
            CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
            return ec2.describeInstances(r -> r.filters(f -> f.name("instance-state-name").values("stopped")))
                    .reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .filter(instance -> {
                        String stateTransitionReason = instance.stateTransitionReason();
                        if (stateTransitionReason != null && stateTransitionReason.contains("User initiated")) {
                            // Extract and parse the timestamp
                            try {
                                String timestampStr = stateTransitionReason.substring(
                                        stateTransitionReason.indexOf('(') + 1, stateTransitionReason.indexOf(')'));
                                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z",
                                        Locale.ENGLISH);
                                ZonedDateTime zonedDateTime = ZonedDateTime.parse(timestampStr, formatter);
                                return zonedDateTime.toInstant().isBefore(thirtyDaysAgo);
                            } catch (Exception e) {
                                logger.warn("Could not parse stop time for instance {}", instance.instanceId(), e);
                                return false;
                            }
                        }
                        return false;
                    })
                    .map(instance -> {
                        // Stopped instances still incur costs for their EBS volumes.
                        // This is a simplified calculation. A more accurate one would describe the
                        // volumes.
                        double monthlyCost = instance.blockDeviceMappings().size() * 5.0; // Assume ~$5/month per volume
                        return new DashboardData.WastedResource(instance.instanceId(),
                                getTagName(instance.tags(), instance.instanceId()), "EC2 Instance", regionId,
                                monthlyCost, "Stopped for >30 days");
                    })
                    .collect(Collectors.toList());
        }, "Stopped EC2 Instances");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnattachedEnis(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2
                    .describeNetworkInterfaces(
                            req -> req.filters(List.of(software.amazon.awssdk.services.ec2.model.Filter.builder()
                                    .name("status").values("available").build())))
                    .networkInterfaces().stream()
                    .map(eni -> new DashboardData.WastedResource(eni.networkInterfaceId(),
                            getTagName(eni.tagSet(), eni.networkInterfaceId()), "ENI", regionId, 0.0, "Unattached ENI"))
                    .collect(Collectors.toList());
        }, "Unattached ENIs");
    }

    private <T> CompletableFuture<List<T>> fetchAllRegionalResources(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions, Function<String, List<T>> fetchFunction,
            String serviceName) {
        if (activeRegions == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        List<CompletableFuture<List<T>>> futures = activeRegions.stream()
                .map(regionStatus -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return fetchFunction.apply(regionStatus.getRegionId());
                    } catch (AwsServiceException e) {
                        logger.warn("Optimization check failed for account {}: {} in region {}. AWS Error: {}",
                                account.getAwsAccountId(), serviceName, regionStatus.getRegionId(),
                                e.awsErrorDetails().errorMessage());
                        return Collections.<T>emptyList();
                    } catch (Exception e) {
                        logger.error("Optimization check failed for account {}: {} in region {}.",
                                account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e);
                        return Collections.<T>emptyList();
                    }
                }))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(List::stream)
                        .collect(Collectors.toList()));
    }

    /**
     * Executes CloudWatch GetMetricData in batches of 500 queries (API limit) and returns
     * a map of query ID → list of metric values. Replaces individual GetMetricStatistics calls.
     */
    private Map<String, List<Double>> batchGetMetrics(CloudWatchClient cw, List<MetricDataQuery> queries,
            Instant start, Instant end) {
        Map<String, List<Double>> results = new HashMap<>();
        for (int i = 0; i < queries.size(); i += 500) {
            List<MetricDataQuery> chunk = queries.subList(i, Math.min(i + 500, queries.size()));
            GetMetricDataRequest req = GetMetricDataRequest.builder()
                    .startTime(start).endTime(end).metricDataQueries(chunk).build();
            cw.getMetricData(req).metricDataResults()
                    .forEach(r -> results.put(r.id(), r.values()));
        }
        return results;
    }

    public String getTagName(List<Tag> tags, String defaultName) {
        if (tags == null || tags.isEmpty())
            return defaultName;
        return tags.stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value)
                .orElse(defaultName);
    }

    private double calculateEbsMonthlyCost(Volume volume, String region) {
        double gbMonthPrice = pricingService.getEbsGbMonthPrice(region, volume.volumeTypeAsString());
        return volume.size() * gbMonthPrice;
    }

    private double calculateSnapshotMonthlyCost(Snapshot snapshot) {
        if (snapshot.volumeSize() != null)
            return snapshot.volumeSize() * 0.05;
        return 0.0;
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findS3StorageClassWaste(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            List<DashboardData.WastedResource> output = new ArrayList<>();
            try {
                S3Client s3 = awsClientProvider.getS3Client(account, "us-east-1");
                List<software.amazon.awssdk.services.s3.model.Bucket> buckets = s3.listBuckets().buckets();
                if (buckets.isEmpty()) return output;

                // Group buckets by region to batch one GetMetricData call per region
                Map<String, List<String>> bucketsByRegion = new HashMap<>();
                for (var bucket : buckets) {
                    try {
                        String loc = s3.getBucketLocation(req -> req.bucket(bucket.name()))
                                .locationConstraintAsString();
                        String region = (loc == null || loc.isEmpty()) ? "us-east-1" : loc;
                        bucketsByRegion.computeIfAbsent(region, k -> new ArrayList<>()).add(bucket.name());
                    } catch (Exception e) {
                        bucketsByRegion.computeIfAbsent("us-east-1", k -> new ArrayList<>()).add(bucket.name());
                    }
                }

                Instant end = Instant.now();
                Instant start = end.minus(2, ChronoUnit.DAYS);
                for (Map.Entry<String, List<String>> entry : bucketsByRegion.entrySet()) {
                    String region = entry.getKey();
                    List<String> regionBuckets = entry.getValue();
                    try {
                        CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, region);
                        List<MetricDataQuery> queries = new ArrayList<>();
                        for (int i = 0; i < regionBuckets.size(); i++) {
                            queries.add(MetricDataQuery.builder().id("s3b" + i)
                                    .metricStat(MetricStat.builder()
                                            .metric(Metric.builder().namespace("AWS/S3").metricName("BucketSizeBytes")
                                                    .dimensions(
                                                            Dimension.builder().name("BucketName").value(regionBuckets.get(i)).build(),
                                                            Dimension.builder().name("StorageType").value("StandardStorage").build())
                                                    .build())
                                            .period(86400).stat("Average").build())
                                    .returnData(true).build());
                        }
                        Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);
                        for (int i = 0; i < regionBuckets.size(); i++) {
                            double bytesInStandard = results.getOrDefault("s3b" + i, List.of()).stream()
                                    .mapToDouble(Double::doubleValue).max().orElse(0.0);
                            double gbInStandard = bytesInStandard / (1024.0 * 1024 * 1024);
                            if (gbInStandard >= 1.0) {
                                String name = regionBuckets.get(i);
                                output.add(new DashboardData.WastedResource(name, name, "S3 Bucket", region,
                                        gbInStandard * (0.023 - 0.0125),
                                        String.format("%.1f GB in Standard storage - consider Intelligent-Tiering or Standard-IA",
                                                gbInStandard)));
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Skipping S3 metrics for region {}: {}", region, e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to check S3 storage class waste for account {}", account.getAwsAccountId(), e);
            }
            return output;
        });
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleEfsFileSystems(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EfsClient efs = awsClientProvider.getEfsClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<software.amazon.awssdk.services.efs.model.FileSystemDescription> fileSystems =
                    efs.describeFileSystems(r -> {}).fileSystems();
            if (fileSystems.isEmpty()) return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(90, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < fileSystems.size(); i++) {
                queries.add(MetricDataQuery.builder().id("efs" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/EFS").metricName("MeteredIOBytes")
                                        .dimensions(Dimension.builder().name("FileSystemId")
                                                .value(fileSystems.get(i).fileSystemId()).build()).build())
                                .period(86400).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < fileSystems.size(); i++) {
                double totalIo = results.getOrDefault("efs" + i, List.of()).stream()
                        .mapToDouble(Double::doubleValue).sum();
                if (totalIo == 0.0) {
                    var fs = fileSystems.get(i);
                    double sizeGb = fs.sizeInBytes().value() / (1024.0 * 1024 * 1024);
                    String name = fs.name() != null && !fs.name().isEmpty() ? fs.name() : fs.fileSystemId();
                    findings.add(new DashboardData.WastedResource(fs.fileSystemId(), name, "EFS File System",
                            regionId, sizeGb * 0.30, "No I/O in 90 days - likely idle file system"));
                }
            }
            return findings;
        }, "Idle EFS File Systems");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleElastiCacheClusters(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ElastiCacheClient ec = awsClientProvider.getElastiCacheClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<software.amazon.awssdk.services.elasticache.model.CacheCluster> clusters =
                    ec.describeCacheClusters(r -> r.showCacheNodeInfo(true)).cacheClusters().stream()
                            .filter(cluster -> "available".equals(cluster.cacheClusterStatus()))
                            .collect(Collectors.toList());
            if (clusters.isEmpty()) return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(90, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < clusters.size(); i++) {
                queries.add(MetricDataQuery.builder().id("ecache" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/ElastiCache").metricName("CurrConnections")
                                        .dimensions(Dimension.builder().name("CacheClusterId")
                                                .value(clusters.get(i).cacheClusterId()).build()).build())
                                .period(86400).stat("Maximum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < clusters.size(); i++) {
                double maxConnections = results.getOrDefault("ecache" + i, List.of()).stream()
                        .mapToDouble(Double::doubleValue).max().orElse(0.0);
                if (maxConnections == 0.0) {
                    var cluster = clusters.get(i);
                    int nodes = cluster.numCacheNodes() != null ? cluster.numCacheNodes() : 1;
                    findings.add(new DashboardData.WastedResource(cluster.cacheClusterId(),
                            cluster.cacheClusterId(), "ElastiCache Cluster", regionId, nodes * 0.034 * 730,
                            "No connections in 90 days - likely idle cluster"));
                }
            }
            return findings;
        }, "Idle ElastiCache Clusters");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnusedDynamoDbTables(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            DynamoDbClient ddb = awsClientProvider.getDynamoDbClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<String> tableNames = ddb.listTables().tableNames();
            if (tableNames.isEmpty()) return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(90, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < tableNames.size(); i++) {
                String tbl = tableNames.get(i);
                queries.add(MetricDataQuery.builder().id("ddbr" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/DynamoDB").metricName("ConsumedReadCapacityUnits")
                                        .dimensions(Dimension.builder().name("TableName").value(tbl).build()).build())
                                .period(86400).stat("Sum").build())
                        .returnData(true).build());
                queries.add(MetricDataQuery.builder().id("ddbw" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/DynamoDB").metricName("ConsumedWriteCapacityUnits")
                                        .dimensions(Dimension.builder().name("TableName").value(tbl).build()).build())
                                .period(86400).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < tableNames.size(); i++) {
                double reads = results.getOrDefault("ddbr" + i, List.of()).stream().mapToDouble(Double::doubleValue).sum();
                double writes = results.getOrDefault("ddbw" + i, List.of()).stream().mapToDouble(Double::doubleValue).sum();
                if (reads == 0.0 && writes == 0.0) {
                    String tableName = tableNames.get(i);
                    double sizeGb;
                    try {
                        sizeGb = ddb.describeTable(r -> r.tableName(tableName)).table().tableSizeBytes()
                                / (1024.0 * 1024 * 1024);
                    } catch (Exception e) {
                        sizeGb = 0.0;
                    }
                    findings.add(new DashboardData.WastedResource(tableName, tableName, "DynamoDB Table",
                            regionId, sizeGb * 0.25, "No read/write activity in 90 days"));
                }
            }
            return findings;
        }, "Unused DynamoDB Tables");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findStaleEcrRepositories(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EcrClient ecr = awsClientProvider.getEcrClient(account, regionId);
            Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);
            return ecr.describeRepositories().repositories().stream()
                    .filter(repo -> {
                        try {
                            var images = ecr.describeImages(r -> r.repositoryName(repo.repositoryName())).imageDetails();
                            if (images.isEmpty()) return true;
                            return images.stream().allMatch(img -> img.imagePushedAt() != null
                                    && img.imagePushedAt().isBefore(ninetyDaysAgo));
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .map(repo -> {
                        double sizeGb;
                        try {
                            sizeGb = ecr.describeImages(r -> r.repositoryName(repo.repositoryName()))
                                    .imageDetails().stream()
                                    .mapToLong(img -> img.imageSizeInBytes() != null ? img.imageSizeInBytes() : 0L)
                                    .sum() / (1024.0 * 1024 * 1024);
                        } catch (Exception e) {
                            sizeGb = 0.0;
                        }
                        double monthlyCost = sizeGb * 0.10;
                        return new DashboardData.WastedResource(repo.repositoryArn(), repo.repositoryName(),
                                "ECR Repository", regionId, monthlyCost,
                                "No image push or pull in 90 days");
                    })
                    .collect(Collectors.toList());
        }, "Stale ECR Repositories");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findExpiredAcmCertificates(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            AcmClient acm = awsClientProvider.getAcmClient(account, regionId);
            return acm.listCertificates(r -> r.certificateStatuses(
                    software.amazon.awssdk.services.acm.model.CertificateStatus.EXPIRED,
                    software.amazon.awssdk.services.acm.model.CertificateStatus.INACTIVE))
                    .certificateSummaryList().stream()
                    .map(cert -> new DashboardData.WastedResource(cert.certificateArn(),
                            cert.domainName() != null ? cert.domainName() : cert.certificateArn(),
                            "Certificate Manager", regionId, 0.0,
                            "Certificate is " + cert.statusAsString() + " - review and delete if unused"))
                    .collect(Collectors.toList());
        }, "Expired ACM Certificates");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleSnsTopics(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            SnsClient sns = awsClientProvider.getSnsClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<software.amazon.awssdk.services.sns.model.Topic> topics = sns.listTopics().topics();
            if (topics.isEmpty()) return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(90, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < topics.size(); i++) {
                String topicName = topics.get(i).topicArn().substring(topics.get(i).topicArn().lastIndexOf(':') + 1);
                queries.add(MetricDataQuery.builder().id("sns" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/SNS").metricName("NumberOfMessagesPublished")
                                        .dimensions(Dimension.builder().name("TopicName").value(topicName).build()).build())
                                .period(86400).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < topics.size(); i++) {
                double total = results.getOrDefault("sns" + i, List.of()).stream()
                        .mapToDouble(Double::doubleValue).sum();
                if (total == 0.0) {
                    String topicArn = topics.get(i).topicArn();
                    String topicName = topicArn.substring(topicArn.lastIndexOf(':') + 1);
                    findings.add(new DashboardData.WastedResource(topicArn, topicName,
                            "SNS Topic", regionId, 0.10, "No messages published in 90 days"));
                }
            }
            return findings;
        }, "Idle SNS Topics");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleSqsQueues(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            SqsClient sqs = awsClientProvider.getSqsClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<String> queueUrls = sqs.listQueues().queueUrls();
            if (queueUrls.isEmpty()) return List.of();

            Instant end = Instant.now();
            Instant sentStart = end.minus(90, ChronoUnit.DAYS);
            Instant visibleStart = end.minus(1, ChronoUnit.DAYS);
            // Use a single time range covering both windows; sentStart is the wider window
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < queueUrls.size(); i++) {
                String queueName = queueUrls.get(i).substring(queueUrls.get(i).lastIndexOf('/') + 1);
                queries.add(MetricDataQuery.builder().id("sqss" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/SQS").metricName("NumberOfMessagesSent")
                                        .dimensions(Dimension.builder().name("QueueName").value(queueName).build()).build())
                                .period(86400).stat("Sum").build())
                        .returnData(true).build());
                queries.add(MetricDataQuery.builder().id("sqsv" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/SQS").metricName("ApproximateNumberOfMessagesVisible")
                                        .dimensions(Dimension.builder().name("QueueName").value(queueName).build()).build())
                                .period(86400).stat("Maximum").build())
                        .returnData(true).build());
            }
            // Sent uses 90-day window; visible uses last 1 day — run two batched calls
            Map<String, List<Double>> sentResults = batchGetMetrics(cw, queries.stream()
                    .filter(q -> q.id().startsWith("sqss")).collect(Collectors.toList()), sentStart, end);
            Map<String, List<Double>> visibleResults = batchGetMetrics(cw, queries.stream()
                    .filter(q -> q.id().startsWith("sqsv")).collect(Collectors.toList()), visibleStart, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < queueUrls.size(); i++) {
                double sent = sentResults.getOrDefault("sqss" + i, List.of()).stream().mapToDouble(Double::doubleValue).sum();
                double visible = visibleResults.getOrDefault("sqsv" + i, List.of()).stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
                if (sent == 0.0 && visible == 0.0) {
                    String queueUrl = queueUrls.get(i);
                    String queueName = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
                    findings.add(new DashboardData.WastedResource(queueUrl, queueName,
                            "SQS Queue", regionId, 0.05, "No messages sent in 90 days and queue is empty"));
                }
            }
            return findings;
        }, "Idle SQS Queues");
    }

    private GetMetricDataRequest buildMetricDataRequest(String resourceId, String metricName, String namespace,
            String dimensionName) {
        Metric metric = Metric.builder().namespace(namespace).metricName(metricName)
                .dimensions(Dimension.builder().name(dimensionName).value(resourceId).build()).build();
        MetricStat metricStat = MetricStat.builder().metric(metric).period(300).stat("Average").build();
        MetricDataQuery metricDataQuery = MetricDataQuery.builder()
                .id(metricName.toLowerCase().replace(" ", "").replace("/", "")).metricStat(metricStat).returnData(true)
                .build();
        return GetMetricDataRequest.builder().startTime(Instant.now().minus(1, ChronoUnit.DAYS)).endTime(Instant.now())
                .metricDataQueries(metricDataQuery).scanBy(ScanBy.TIMESTAMP_DESCENDING).build();
    }
}