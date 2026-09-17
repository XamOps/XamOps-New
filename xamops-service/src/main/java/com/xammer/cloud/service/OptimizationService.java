package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
// Explicit import to resolve ambiguity with computeoptimizer.model.Dimension (SDK 2.34.0)
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.util.Ec2InstanceTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.codebuild.CodeBuildClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;

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
import java.util.LinkedHashMap;
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
    private final SnapshotFreshnessService snapshotFreshnessService;

    /**
     * When true, Compute Optimizer is also asked for Graviton (ARM64) options alongside
     * same-architecture ones. Off by default: an ARM64 target requires an AMI rebuild, so
     * it is a migration project rather than a one-click resize.
     */
    @Value("${optimization.ec2.include-graviton:false}")
    private boolean includeGravitonRecommendations;

    /** CloudWatch GetMetricData accepts at most 500 queries per request. */
    private static final int METRIC_QUERY_BATCH_SIZE = 500;

    /** Ceiling on GetMetricData pages followed per batch, to bound memory. */
    private static final int MAX_METRIC_PAGES = 20;

    @Autowired
    public OptimizationService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            PricingService pricingService,
            @Lazy CloudListService cloudListService,
            RedisCacheService redisCache,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper,
            SnapshotFreshnessService snapshotFreshnessService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.pricingService = pricingService;
        this.cloudListService = cloudListService;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.snapshotFreshnessService = snapshotFreshnessService;
    }

    /** Delegates to {@link Ec2InstanceTypes#architectureOf(String)}. */
    private static String architectureOf(String instanceType) {
        return Ec2InstanceTypes.architectureOf(instanceType);
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
                Optional<CachedData> dbCache = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(dbKey), dbKey);
                if (dbCache.isPresent()) {
                    List<DashboardData.OptimizationRecommendation> allRecs = objectMapper.readValue(
                            dbCache.get().getJsonData(),
                            new TypeReference<List<DashboardData.OptimizationRecommendation>>() {
                            });
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

            // Ask Compute Optimizer for CURRENT (same-architecture) options by default.
            //
            // This previously pinned AWS_ARM64, which had two effects: every recommendation
            // required an x86 -> Graviton migration (new AMI, recompiled binaries), and
            // same-architecture downsizes such as m5.4xlarge -> m5.xlarge were never
            // returned at all. Graviton options are opt-in via
            // `optimization.ec2.include-graviton=true`, and any cross-architecture option
            // is tagged so the UI and the apply path can require an explicit migration.
            RecommendationPreferences recommendationPreferences = RecommendationPreferences.builder()
                    .cpuVendorArchitectures(includeGravitonRecommendations
                            ? List.of(CpuVendorArchitecture.CURRENT, CpuVendorArchitecture.AWS_ARM64)
                            : List.of(CpuVendorArchitecture.CURRENT))
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
                            double currentCost;
                            if (recommendedCost > 0) {
                                currentCost = recommendedCost + savings;
                            } else {
                                // Graviton/ARM instances often return 0 from the Pricing API.
                                // Fall back: price the current (x86) instance and derive recommended cost.
                                currentCost = pricingService.getEc2InstanceMonthlyPrice(r.currentInstanceType(),
                                        regionId);
                                recommendedCost = Math.max(0, currentCost - savings);
                            }

                            // --- Reason derivation ---
                            // First, try to get the detailed reason codes from Compute Optimizer.
                            String reason = r.findingReasonCodes() != null
                                    ? r.findingReasonCodes().stream()
                                            .map(Object::toString)
                                            .filter(s -> s != null && !s.isBlank())
                                            .collect(Collectors.joining(", "))
                                    : "";

                            // If there are no detailed codes, fall back to the general finding,
                            // and finally to a human-readable default so the UI never shows "null".
                            if (reason == null || reason.isBlank()) {
                                if (r.finding() != null && r.finding() != Finding.UNKNOWN_TO_SDK_VERSION) {
                                    reason = r.finding().toString();
                                } else {
                                    reason = "Rightsizing opportunity";
                                }
                            }
                            // --- End reason derivation ---

                            DashboardData.OptimizationRecommendation rec = new DashboardData.OptimizationRecommendation(
                                    "EC2", r.instanceArn().split("/")[1], r.currentInstanceType(),
                                    bestOption.instanceType(),
                                    savings, reason, // Use the new 'reason' variable
                                    currentCost, recommendedCost);
                            rec.setRegion(regionId);
                            if (r.instanceName() != null && !r.instanceName().isBlank()) {
                                rec.setInstanceName(r.instanceName());
                            }

                            // Flag cross-architecture targets so the UI can label them and the
                            // apply path can refuse to stop/modify/start into an incompatible AMI.
                            String currentArch = architectureOf(r.currentInstanceType());
                            String targetArch = architectureOf(bestOption.instanceType());
                            rec.setCurrentArchitecture(currentArch);
                            rec.setRecommendedArchitecture(targetArch);
                            rec.setRequiresArchitectureMigration(!currentArch.equals(targetArch));

                            return rec;
                        })
                        // Drop non-actionable recommendations: AWS sometimes returns the current
                        // instance type as the "best" ARM64 option with zero savings. These show up
                        // as "t4g.small -> t4g.small / $0.00" noise rows, so filter them out.
                        .filter(rec -> rec.getRecommendedType() != null
                                && !rec.getRecommendedType().equalsIgnoreCase(rec.getCurrentType())
                                && rec.getEstimatedMonthlySavings() > 0)
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
                findIdleSnsTopics(account, activeRegions), findIdleSqsQueues(account, activeRegions),
                findIdleAutoScalingGroups(account, activeRegions),
                findIdleNatGateways(account, activeRegions),
                findS3BucketsWithNoReads(account),
                // Was written but never added to this list, so the capability existed in the
                // codebase and never ran.
                findOversizedEc2Instances(account, activeRegions),
                findLogGroupsWithNoRetentionPolicy(account, activeRegions),
                findAttachedButIdleEbsVolumes(account, activeRegions),
                findCloudFrontDistributionsWithNoTraffic(account),
                findUnusedRoute53HostedZones(account),
                findGlueJobsNeverRan(account, activeRegions),
                findIdleStepFunctionsMachines(account, activeRegions),
                findApiGatewayApisWithNoTraffic(account, activeRegions),
                findCodeBuildProjectsWithNoBuilds(account, activeRegions),
                findIdleKinesisStreams(account, activeRegions));

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
                        String name = getTagName(volume.tags(), volume.volumeId());
                        return PricingService.isPriceAvailable(monthlyCost)
                                ? new DashboardData.WastedResource(volume.volumeId(), name, "EBS Volume",
                                        regionId, monthlyCost, "Unattached Volume")
                                : DashboardData.WastedResource.withUnknownSavings(volume.volumeId(), name,
                                        "EBS Volume", regionId,
                                        "Unattached Volume — monthly cost could not be determined");
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
                    // An address held deliberately for an allowlist, or provisioned by
                    // CloudFormation/Terraform, is not waste: releasing it loses the address, and
                    // the stack recreates it on the next apply with a different one.
                    .filter(address -> !isManagedOrRetained(address.tags()))
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

            // Snapshots that back a registered AMI cannot be deleted — DeleteSnapshot returns
            // InvalidSnapshot.InUse — so recommending it produced advice that always fails.
            java.util.Set<String> amiBackedSnapshotIds = snapshotIdsBackingImages(ec2, regionId);
            double snapshotRate = pricingService.getEbsSnapshotGbMonthPrice(regionId);

            return ec2.describeSnapshots(r -> r.ownerIds("self")).snapshots().stream()
                    .filter(s -> s.startTime().isBefore(ninetyDaysAgo))
                    // A snapshot kept by a lifecycle policy, AWS Backup or an explicit retention
                    // tag is deliberately old — age alone is not evidence it is unwanted.
                    .filter(s -> !isManagedOrRetained(s.tags()))
                    .filter(s -> !amiBackedSnapshotIds.contains(s.snapshotId()))
                    .map(snapshot -> wasteWithRate(snapshot.snapshotId(),
                            getTagName(snapshot.tags(), snapshot.snapshotId()), "Snapshot", regionId,
                            snapshotRate, calculateSnapshotMonthlyCost(snapshot, regionId),
                            "Older than 90 days"))
                    .collect(Collectors.toList());
        }, "Old Snapshots");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findDeregisteredAmis(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            double snapshotGbMonthPrice = pricingService.getEbsSnapshotGbMonthPrice(regionId);
            DescribeImagesRequest imagesRequest = DescribeImagesRequest.builder().owners("self").build();
            return ec2.describeImages(imagesRequest).images().stream()
                    // Only terminal failure states. Filtering on "not AVAILABLE" also caught
                    // PENDING — an AMI mid-build — and reported it as "Deregistered or Failed",
                    // so an in-flight image bake appeared in the waste report.
                    .filter(image -> image.state() == ImageState.FAILED
                            || image.state() == ImageState.ERROR
                            || image.state() == ImageState.INVALID
                            || image.state() == ImageState.DEREGISTERED)
                    .map(image -> {
                        double monthlyCost = image.blockDeviceMappings().stream()
                                .filter(bdm -> bdm.ebs() != null && bdm.ebs().volumeSize() != null)
                                .mapToDouble(bdm -> bdm.ebs().volumeSize() * snapshotGbMonthPrice)
                                .sum();
                        return wasteWithRate(image.imageId(), image.name(), "AMI", regionId,
                                snapshotGbMonthPrice, monthlyCost, "Deregistered or Failed State");
                    })
                    .collect(Collectors.toList());
        }, "Deregistered AMIs");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleRdsInstances(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            RdsClient rds = awsClientProvider.getRdsClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<software.amazon.awssdk.services.rds.model.DBInstance> instances = rds.describeDBInstances()
                    .dbInstances().stream()
                    .filter(db -> "available".equalsIgnoreCase(db.dbInstanceStatus()))
                    .collect(Collectors.toList());
            if (instances.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < instances.size(); i++) {
                queries.add(MetricDataQuery.builder().id("rds" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/RDS").metricName("DatabaseConnections")
                                        .dimensions(Dimension.builder().name("DBInstanceIdentifier")
                                                .value(instances.get(i).dbInstanceIdentifier()).build())
                                        .build())
                                .period(86400).stat("Maximum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < instances.size(); i++) {
                List<Double> values = results.getOrDefault("rds" + i, List.of());
                if (!values.isEmpty() && values.stream().allMatch(v -> v < 1)) {
                    var db = instances.get(i);
                    double rdsMonthly = pricingService.getRdsInstanceMonthlyPrice(db, regionId);
                    findings.add(wasteWithRate(db.dbInstanceIdentifier(),
                            db.dbInstanceIdentifier(), "RDS Instance", regionId,
                            rdsMonthly, rdsMonthly,
                            "Idle RDS Instance (no connections)"));
                }
            }
            return findings;
        }, "Idle RDS Instances");
    }

    /**
     * Whether a tag key marks its resource as owned by a controller that will recreate it.
     *
     * <p>The AWS Load Balancer Controller and the in-tree service controller both tag what they
     * create — {@code elbv2.k8s.aws/cluster}, {@code kubernetes.io/cluster/<name>},
     * {@code kubernetes.io/service-name}. CloudFormation and Elastic Beanstalk do the same.
     * Deleting one of these is undone by its owner within minutes, and in the meantime the
     * service it fronts is unreachable.
     */
    static boolean isControllerOwnedTagKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalised = key.toLowerCase();
        return normalised.startsWith("kubernetes.io/")
                || normalised.startsWith("elbv2.k8s.aws/")
                || normalised.startsWith("ingress.k8s.aws/")
                || normalised.startsWith("service.k8s.aws/")
                || normalised.startsWith("aws:cloudformation:")
                || normalised.startsWith("aws:elasticbeanstalk:");
    }

    /**
     * ARNs of the load balancers in {@code loadBalancers} that a controller owns.
     *
     * <p>Tags are resolved in batches of 20 — the {@code DescribeTags} limit — rather than one
     * call per load balancer.
     */
    private java.util.Set<String> kubernetesManagedLoadBalancerArns(ElasticLoadBalancingV2Client elbv2,
            List<software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer> loadBalancers) {

        java.util.Set<String> owned = new java.util.HashSet<>();
        List<String> arns = loadBalancers.stream()
                .map(software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer::loadBalancerArn)
                .collect(Collectors.toList());

        // DescribeTags accepts at most 20 resource ARNs per call.
        final int batchSize = 20;
        for (int i = 0; i < arns.size(); i += batchSize) {
            List<String> batch = arns.subList(i, Math.min(i + batchSize, arns.size()));
            try {
                for (var description : elbv2.describeTags(req -> req.resourceArns(batch)).tagDescriptions()) {
                    boolean managed = description.tags().stream()
                            .anyMatch(tag -> isControllerOwnedTagKey(tag.key()));
                    if (managed) {
                        owned.add(description.resourceArn());
                    }
                }
            } catch (Exception e) {
                // Fail open: an unreadable tag set should not hide a genuinely idle load balancer.
                logger.debug("[Waste] Could not read load balancer tags for a batch of {}: {}",
                        batch.size(), e.getMessage());
            }
        }
        return owned;
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleLoadBalancers(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, regionId);
            var loadBalancers = elbv2.describeLoadBalancers().loadBalancers();
            if (loadBalancers.isEmpty()) {
                return List.of();
            }
            // Resolved in batches of 20 (the DescribeTags limit) rather than one call per load
            // balancer, so a large account does not pay a round trip each.
            java.util.Set<String> clusterOwnedArns = kubernetesManagedLoadBalancerArns(elbv2, loadBalancers);

            return loadBalancers.stream()
                    // A load balancer provisioned by the AWS Load Balancer Controller or by an
                    // Ingress belongs to a cluster, which recreates it. Its target groups are
                    // legitimately empty whenever the backing deployment is scaled to zero.
                    .filter(lb -> !clusterOwnedArns.contains(lb.loadBalancerArn()))
                    .filter(lb -> {
                        var targetGroups = elbv2
                                .describeTargetGroups(req -> req.loadBalancerArn(lb.loadBalancerArn()))
                                .targetGroups();
                        // allMatch on an empty list is true, so a load balancer created moments ago
                        // — before its target groups exist — was reported as idle mid-deployment.
                        if (targetGroups.isEmpty()) {
                            return false;
                        }
                        return targetGroups.stream().allMatch(
                                tg -> elbv2.describeTargetHealth(req -> req.targetGroupArn(tg.targetGroupArn()))
                                        .targetHealthDescriptions().isEmpty());
                    })
                    .map(lb -> {
                        double elbHourly = pricingService.getElbHourlyPrice(regionId, lb.typeAsString());
                        return wasteWithRate(lb.loadBalancerArn(), lb.loadBalancerName(),
                                "Load Balancer", regionId, elbHourly, elbHourly * 730,
                                "Idle Load Balancer (no targets)");
                    })
                    .collect(Collectors.toList());
        }, "Idle Load Balancers");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleEksClusters(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EksClient eks = awsClientProvider.getEksClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<String> clusters = eks.listClusters().clusters();
            if (clusters.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < clusters.size(); i++) {
                queries.add(MetricDataQuery.builder().id("eks" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("ContainerInsights")
                                        .metricName("node_cpu_utilization")
                                        .dimensions(
                                                Dimension.builder().name("ClusterName").value(clusters.get(i)).build())
                                        .build())
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
                        // Control-plane charge for the cluster itself, at the region's real rate
                        // rather than a flat us-east-1 $73/month.
                        double eksHourly = pricingService.getEksClusterHourlyPrice(regionId);
                        findings.add(wasteWithRate(clusters.get(i), clusters.get(i), "EKS Cluster",
                                regionId, eksHourly, eksHourly * 730, "Idle Cluster (low CPU)"));
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
                    // An ECS cluster with no tasks or services has no charge of its own — any
                    // cost sat in the (already terminated) container instances.
                    .map(cluster -> DashboardData.WastedResource.withNoCostImpact(cluster.clusterArn(),
                            cluster.clusterName(), "ECS Cluster", regionId, "Idle Cluster (0 tasks/services)"))
                    .collect(Collectors.toList());
        }, "Idle ECS Clusters");
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnderutilizedLambdaFunctions(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            LambdaClient lambda = awsClientProvider.getLambdaClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<software.amazon.awssdk.services.lambda.model.FunctionConfiguration> functions = lambda.listFunctions()
                    .functions();
            if (functions.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < functions.size(); i++) {
                queries.add(MetricDataQuery.builder().id("fn" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/Lambda").metricName("Invocations")
                                        .dimensions(Dimension.builder().name("FunctionName")
                                                .value(functions.get(i).functionName()).build())
                                        .build())
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
                    // Lambda bills per request and per GB-second, so a function invoked fewer
                    // than ten times a month accrues essentially nothing — well inside the free
                    // tier. The previous flat $0.50 was invented and inflated the waste total by
                    // $0.50 for every idle function found. Kept as a hygiene finding.
                    findings.add(DashboardData.WastedResource.withNoCostImpact(
                            func.functionArn(), func.functionName(), "Lambda", regionId,
                            "Low Invocations (<10 in 30d)"));
                }
            }
            return findings;
        }, "Underutilized Lambda Functions");
    }

    /**
     * Manual RDS snapshots always retained per source instance, regardless of age, so the last
     * surviving copy of a database is never proposed for deletion.
     */
    static final int MIN_RETAINED_DB_SNAPSHOTS = 1;

    /**
     * The grouping key for a manual snapshot: its source instance, or the snapshot itself when
     * the source is gone.
     *
     * <p>An orphaned snapshot gets a key of its own so it forms a group of one and is therefore
     * always retained. That is the case where deletion is least recoverable: RDS keeps no
     * automated backups past an instance's lifetime, so a manual snapshot of a deleted instance
     * is usually the only surviving copy of the data.
     */
    private static String dbSnapshotGroupKey(software.amazon.awssdk.services.rds.model.DBSnapshot snap) {
        return snap.dbInstanceIdentifier() == null || snap.dbInstanceIdentifier().isBlank()
                ? "(orphaned)" + snap.dbSnapshotIdentifier()
                : snap.dbInstanceIdentifier();
    }

    /**
     * The manual snapshots that are both older than {@code cutoff} and safe to delete.
     *
     * <p>Within each source instance the newest {@link #MIN_RETAINED_DB_SNAPSHOTS} are retained
     * whatever their age, and only the snapshots beyond that are judged on age. Retaining
     * unconditionally rather than only among the aged ones matters in both directions: a lone
     * old snapshot is never proposed for deletion even though it is old, and an old snapshot
     * sitting behind two recent ones is proposed even though its own group contains something
     * ancient.
     *
     * <p>A snapshot with no creation time is never returned — an unknown age is not evidence of
     * an old one.
     */
    static List<software.amazon.awssdk.services.rds.model.DBSnapshot> deletableManualDbSnapshots(
            List<software.amazon.awssdk.services.rds.model.DBSnapshot> manual, Instant cutoff) {

        Map<String, List<software.amazon.awssdk.services.rds.model.DBSnapshot>> byInstance =
                new LinkedHashMap<>();
        for (var snap : manual) {
            byInstance.computeIfAbsent(dbSnapshotGroupKey(snap), k -> new ArrayList<>()).add(snap);
        }

        List<software.amazon.awssdk.services.rds.model.DBSnapshot> deletable = new ArrayList<>();
        for (var snapshots : byInstance.values()) {
            snapshots.stream()
                    // Newest first, so the retained ones are the most recent. A snapshot of
                    // unknown age sorts last and is therefore never one of the retained copies.
                    .sorted(Comparator.comparing(
                            software.amazon.awssdk.services.rds.model.DBSnapshot::snapshotCreateTime,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .skip(MIN_RETAINED_DB_SNAPSHOTS)
                    .filter(snap -> snap.snapshotCreateTime() != null
                            && snap.snapshotCreateTime().isBefore(cutoff))
                    .forEach(deletable::add);
        }
        return deletable;
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findOldDbSnapshots(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            RdsClient rds = awsClientProvider.getRdsClient(account, regionId);
            Instant ninetyDaysAgo = Instant.now().minus(90, ChronoUnit.DAYS);

            List<software.amazon.awssdk.services.rds.model.DBSnapshot> manual =
                    rds.describeDBSnapshots(r -> r.snapshotType("manual")).dbSnapshots();

            return deletableManualDbSnapshots(manual, ninetyDaysAgo).stream()
                    .map(snap -> {
                        double snapRate = pricingService.getRdsSnapshotGbMonthPrice(regionId, snap.engine());
                        return wasteWithRate(
                                snap.dbSnapshotIdentifier(),
                                snap.dbSnapshotIdentifier(), "DB Snapshot", regionId,
                                snapRate, snap.allocatedStorage() * snapRate,
                                String.format("Older than 90 days. The %d most recent manual snapshot(s) of "
                                        + "the same instance are retained.", MIN_RETAINED_DB_SNAPSHOTS));
                    })
                    .collect(Collectors.toList());
        }, "Old DB Snapshots");
    }

    /**
     * Snapshot ids referenced by the block device mappings of any registered AMI in the region.
     *
     * <p>{@code DeleteSnapshot} on one of these fails with {@code InvalidSnapshot.InUse}, so a
     * recommendation to delete it can never be carried out. Returns an empty set on failure,
     * which keeps the caller's previous behaviour rather than hiding every snapshot.
     */
    private java.util.Set<String> snapshotIdsBackingImages(Ec2Client ec2, String regionId) {
        try {
            java.util.Set<String> ids = new java.util.HashSet<>();
            ec2.describeImages(DescribeImagesRequest.builder().owners("self").build())
                    .images()
                    .forEach(image -> image.blockDeviceMappings().stream()
                            .filter(bdm -> bdm.ebs() != null && bdm.ebs().snapshotId() != null)
                            .forEach(bdm -> ids.add(bdm.ebs().snapshotId())));
            return ids;
        } catch (Exception e) {
            logger.warn("[Waste] Could not determine AMI-backed snapshots in {}: {}. Snapshots "
                    + "backing an AMI may be reported as deletable.", regionId, e.getMessage());
            return java.util.Set.of();
        }
    }

    /**
     * Whether a resource is provisioned by infrastructure-as-code or explicitly retained.
     *
     * <p>Deleting a stack-managed resource is at best undone by the next apply and at worst
     * leaves the stack in a drifted state that fails to update. A retention tag is an explicit
     * instruction from the owner that the resource is deliberately idle — a warm standby, a
     * reserved allowlist entry, a compliance copy.
     */
    private boolean isManagedOrRetained(List<software.amazon.awssdk.services.ec2.model.Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        for (software.amazon.awssdk.services.ec2.model.Tag tag : tags) {
            String key = tag.key() == null ? "" : tag.key().toLowerCase();
            String value = tag.value() == null ? "" : tag.value().toLowerCase();

            // CloudFormation, CDK, Terraform, Elastic Beanstalk and Service Catalog markers.
            if (key.startsWith("aws:cloudformation:")
                    || key.startsWith("aws:elasticbeanstalk:")
                    || key.startsWith("aws:servicecatalog:")
                    || key.equals("aws:cdk:path")
                    || key.startsWith("terraform")
                    || key.equals("managedby") || key.equals("managed-by")
                    || key.equals("provisioner")) {
                return true;
            }
            if (value.equals("terraform") || value.equals("cloudformation") || value.equals("pulumi")) {
                return true;
            }
            // Explicit retention instructions.
            if (key.contains("donotdelete") || key.contains("do-not-delete")
                    || key.contains("retain") || key.contains("preserve")
                    || key.contains("allowlist") || key.contains("whitelist")) {
                return true;
            }
        }
        return false;
    }

    /** CloudWatch Logs GB-month rate for a region, never negative. */
    private double logsStorageGbMonthPrice(String regionId) {
        try {
            return Math.max(0.0, pricingService.getCloudWatchLogsStorageGbMonthPrice(regionId));
        } catch (Exception e) {
            logger.warn("CloudWatch Logs storage price unavailable for {}: {}", regionId, e.getMessage());
            return 0.0;
        }
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findUnusedCloudWatchLogGroups(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CloudWatchLogsClient logs = awsClientProvider.getCloudWatchLogsClient(account, regionId);
            long sixtyDaysAgoMillis = Instant.now().minus(60, ChronoUnit.DAYS).toEpochMilli();
            List<DashboardData.WastedResource> findings = new ArrayList<>();
            List<LogGroup> logGroups = logs.describeLogGroups().logGroups();
            // Resolved once per region rather than per log group; the price call is cached but
            // an account can have thousands of groups.
            final double logsGbMonthPrice = logsStorageGbMonthPrice(regionId);

            for (LogGroup lg : logGroups) {
                // storedBytes() == 0 means there is nothing to bill for; CloudWatch Logs
                // charges on ingestion and stored volume, not on the group existing.
                if (lg.creationTime() < sixtyDaysAgoMillis && lg.storedBytes() == 0) {
                    findings.add(DashboardData.WastedResource.withNoCostImpact(lg.logGroupName(), lg.logGroupName(),
                            "Log Group", regionId, "Empty and created over 60 days ago"));
                    continue;
                }
                try {
                    DescribeLogStreamsResponse response = logs.describeLogStreams(req -> req
                            .logGroupName(lg.logGroupName()).orderBy("LastEventTime").descending(true).limit(1));
                    if (response.logStreams().isEmpty() || response.logStreams().get(0).lastEventTimestamp() == null
                            || response.logStreams().get(0).lastEventTimestamp() < sixtyDaysAgoMillis) {
                        // This group still holds data, so it carries a real storage charge.
                        // Reporting 0.0 made an abandoned multi-gigabyte group look free to keep.
                        double storedGb = lg.storedBytes() == null ? 0.0
                                : lg.storedBytes() / (1024.0 * 1024 * 1024);
                        double monthlyCost = storedGb * logsGbMonthPrice;
                        findings.add(storedGb > 0
                                ? new DashboardData.WastedResource(lg.logGroupName(), lg.logGroupName(), "Log Group",
                                        regionId, monthlyCost,
                                        String.format("No new events in 60+ days (%.2f GB retained)", storedGb))
                                : DashboardData.WastedResource.withNoCostImpact(lg.logGroupName(), lg.logGroupName(),
                                        "Log Group", regionId, "No new events in 60+ days"));
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
                    // A VPC's default group cannot be deleted, so reporting it is pure noise.
                    .filter(sg -> sg.vpcId() != null && !"default".equals(sg.groupName()))
                    .filter(sg -> isSecurityGroupUnused(account, sg.groupId(), regionId))
                    // Security groups are free; this is a blast-radius/hygiene finding only.
                    .map(sg -> DashboardData.WastedResource.withNoCostImpact(sg.groupId(), sg.groupName(),
                            "Security Group", regionId, "Unused Security Group"))
                    .collect(Collectors.toList());
        }, "Unused Security Groups");
    }

    /**
     * Whether a security group is genuinely unreferenced.
     *
     * <p>An ENI check alone is not sufficient. A group can be referenced by the ingress or
     * egress rules of another group, in which case {@code DeleteSecurityGroup} fails with
     * {@code DependencyViolation}; and a VPC's {@code default} group cannot be deleted at all.
     * Reporting either as removable produced advice that could not be carried out.
     */
    private boolean isSecurityGroupUnused(CloudAccount account, String groupId, String regionId) {
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            DescribeNetworkInterfacesRequest request = DescribeNetworkInterfacesRequest.builder()
                    .filters(software.amazon.awssdk.services.ec2.model.Filter.builder().name("group-id").values(groupId)
                            .build())
                    .build();
            if (!ec2.describeNetworkInterfaces(request).networkInterfaces().isEmpty()) {
                return false;
            }

            // Referenced by another group's rules — deletion would be rejected.
            boolean referencedByAnotherGroup = ec2.describeSecurityGroups().securityGroups().stream()
                    .filter(other -> !groupId.equals(other.groupId()))
                    .anyMatch(other -> referencesGroup(other, groupId));
            if (referencedByAnotherGroup) {
                logger.debug("[Waste] Security group {} is referenced by another group's rules — "
                        + "not reporting it as unused", groupId);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error("Could not check usage for security group {} in account {}: {}", groupId,
                    account.getAwsAccountId(), e.getMessage());
        }
        return false;
    }

    /** Whether any ingress or egress rule of {@code group} references {@code groupId}. */
    private boolean referencesGroup(software.amazon.awssdk.services.ec2.model.SecurityGroup group,
                                    String groupId) {
        java.util.stream.Stream<software.amazon.awssdk.services.ec2.model.IpPermission> rules =
                java.util.stream.Stream.concat(
                        group.ipPermissions() == null ? java.util.stream.Stream.empty()
                                : group.ipPermissions().stream(),
                        group.ipPermissionsEgress() == null ? java.util.stream.Stream.empty()
                                : group.ipPermissionsEgress().stream());
        return rules.anyMatch(rule -> rule.userIdGroupPairs() != null
                && rule.userIdGroupPairs().stream()
                        .anyMatch(pair -> groupId.equals(pair.groupId())));
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findIdleEc2Instances(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);
            List<Instance> instances = ec2
                    .describeInstances(r -> r.filters(f -> f.name("instance-state-name").values("running")))
                    .reservations().stream().flatMap(r -> r.instances().stream()).collect(Collectors.toList());
            if (instances.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(60, ChronoUnit.DAYS);
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
                double networkIn = ninVals.isEmpty() ? Double.MAX_VALUE
                        : ninVals.stream().mapToDouble(Double::doubleValue).sum();
                double networkOut = nouVals.isEmpty() ? Double.MAX_VALUE
                        : nouVals.stream().mapToDouble(Double::doubleValue).sum();
                if (maxCpu < maxCpuThreshold && networkIn < networkThreshold && networkOut < networkThreshold) {
                    Instance inst = instances.get(i);
                    String name = getTagName(inst.tags(), inst.instanceId());
                    double monthlyPrice = pricingService
                            .getEc2InstanceMonthlyPrice(inst.instanceTypeAsString(), regionId);
                    // A pricing lookup failure returns 0.0. Reporting that as the cost of an
                    // idle instance understates waste, so flag it as unpriced instead.
                    findings.add(PricingService.isPriceAvailable(monthlyPrice)
                            ? new DashboardData.WastedResource(inst.instanceId(), name, "EC2 Instance",
                                    regionId, monthlyPrice, "Idle EC2 Instance (low CPU)")
                            : DashboardData.WastedResource.withUnknownSavings(inst.instanceId(), name,
                                    "EC2 Instance", regionId,
                                    "Idle EC2 Instance (low CPU) — monthly cost could not be determined"));
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
            List<Instance> stoppedInstances = ec2
                    .describeInstances(r -> r.filters(f -> f.name("instance-state-name").values("stopped")))
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
                    .collect(Collectors.toList());

            if (stoppedInstances.isEmpty()) {
                return List.of();
            }

            // Stopped instances still incur costs for their attached EBS volumes.
            List<String> volumeIds = stoppedInstances.stream()
                    .flatMap(i -> i.blockDeviceMappings().stream())
                    .filter(bdm -> bdm.ebs() != null && bdm.ebs().volumeId() != null)
                    .map(bdm -> bdm.ebs().volumeId())
                    .collect(Collectors.toList());

            Map<String, Volume> volumesById = volumeIds.isEmpty() ? Collections.emptyMap()
                    : ec2.describeVolumes(r -> r.volumeIds(volumeIds)).volumes().stream()
                            .collect(Collectors.toMap(Volume::volumeId, v -> v));

            return stoppedInstances.stream()
                    .map(instance -> {
                        double monthlyCost = instance.blockDeviceMappings().stream()
                                .filter(bdm -> bdm.ebs() != null)
                                .map(bdm -> volumesById.get(bdm.ebs().volumeId()))
                                .filter(java.util.Objects::nonNull)
                                .mapToDouble(v -> calculateEbsMonthlyCost(v, regionId))
                                .sum();
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
                    .filter(eni -> !isManagedOrRetained(eni.tagSet()))
                    // An available ENI is free. Kept as a hygiene finding, but flagged so it
                    // does not inflate a count shown next to a reclaimable-spend figure.
                    .map(eni -> DashboardData.WastedResource.withNoCostImpact(eni.networkInterfaceId(),
                            getTagName(eni.tagSet(), eni.networkInterfaceId()), "ENI", regionId, "Unattached ENI"))
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
     * Executes CloudWatch GetMetricData in batches of 500 queries (API limit) and
     * returns
     * a map of query ID → list of metric values. Replaces individual
     * GetMetricStatistics calls.
     */
    /**
     * Whether CloudWatch returned no datapoints at all for these series.
     *
     * <p>An empty series is not evidence of zero activity. It means one of: the metric is an
     * opt-in that the customer has not enabled (S3 request metrics, API Gateway detailed
     * metrics), the resource is newer than the window, the resource is not publishing, or the
     * call was throttled. Summing an empty list gives {@code 0.0}, and several detectors read
     * that as "idle" and recommended deleting a resource in active use.
     *
     * <p>Use this only for metrics that a healthy resource publishes continuously. Counter
     * metrics such as {@code Invocations}, {@code NumberOfMessagesPublished} and
     * {@code NumberOfMessagesSent} genuinely do not publish when the count is zero, so absence
     * there really does mean zero and those detectors are left as they are.
     */
    private static boolean noDatapoints(List<Double> series) {
        return series == null || series.isEmpty();
    }

    /** True when neither series carried any datapoint. See {@link #noDatapoints(List)}. */
    private static boolean noDatapoints(List<Double> first, List<Double> second) {
        return noDatapoints(first) && noDatapoints(second);
    }

    private Map<String, List<Double>> batchGetMetrics(CloudWatchClient cw, List<MetricDataQuery> queries,
            Instant start, Instant end) {
        Map<String, List<Double>> results = new HashMap<>();
        for (int i = 0; i < queries.size(); i += METRIC_QUERY_BATCH_SIZE) {
            List<MetricDataQuery> chunk = queries.subList(i,
                    Math.min(i + METRIC_QUERY_BATCH_SIZE, queries.size()));

            // Follow nextToken. GetMetricData caps a single response at 100,800 datapoints,
            // which 500 queries over a long window can exceed. Reading only the first page
            // silently truncated the tail, so some resources appeared to have no data and
            // were then treated as idle.
            String nextToken = null;
            int pages = 0;
            do {
                GetMetricDataRequest req = GetMetricDataRequest.builder()
                        .startTime(start).endTime(end)
                        .metricDataQueries(chunk)
                        .nextToken(nextToken)
                        .build();
                GetMetricDataResponse response = cw.getMetricData(req);

                // A query's datapoints can span pages, so append rather than overwrite.
                response.metricDataResults().forEach(r ->
                        results.computeIfAbsent(r.id(), k -> new ArrayList<>()).addAll(r.values()));

                nextToken = response.nextToken();
            } while (nextToken != null && !nextToken.isBlank() && ++pages < MAX_METRIC_PAGES);

            if (pages >= MAX_METRIC_PAGES) {
                logger.warn("GetMetricData hit the {}-page ceiling; metric results may be truncated",
                        MAX_METRIC_PAGES);
            }
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

    private double calculateSnapshotMonthlyCost(Snapshot snapshot, String region) {
        if (snapshot.volumeSize() != null)
            return snapshot.volumeSize() * pricingService.getEbsSnapshotGbMonthPrice(region);
        return 0.0;
    }

    /** Standard explanation appended to a finding whose cost could not be priced. */
    private static final String UNPRICED_SUFFIX =
            " — monthly cost could not be priced from the AWS Price List, so this resource is "
            + "excluded from savings totals";

    /**
     * Builds a waste finding, reporting the cost as unknown when the rate behind it is unavailable.
     *
     * <p>Gates on {@code rate} rather than on {@code monthlyCost} deliberately. A rate of zero means
     * the Price List lookup failed; a <em>cost</em> of zero can be perfectly legitimate — an empty
     * ECR repository or a DynamoDB table with no data multiplies a real rate by a real zero size.
     * Checking the product would mislabel those as unpriced.
     *
     * <p>Before {@link PricingService} stopped substituting constants for failed lookups, an
     * unavailable rate arrived here as a plausible us-east-1 number and was reported as measured
     * spend. It now arrives as {@link PricingService#PRICE_UNAVAILABLE}, and this is where that
     * becomes an honest "unknown" rather than a confident "$0".
     */
    private static DashboardData.WastedResource wasteWithRate(String resourceId, String resourceName,
            String resourceType, String region, double rate, double monthlyCost, String reason) {
        return PricingService.isPriceAvailable(rate)
                ? new DashboardData.WastedResource(resourceId, resourceName, resourceType, region,
                        monthlyCost, reason)
                : DashboardData.WastedResource.withUnknownSavings(resourceId, resourceName, resourceType,
                        region, reason + UNPRICED_SUFFIX);
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findS3StorageClassWaste(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            List<DashboardData.WastedResource> output = new ArrayList<>();
            try {
                S3Client s3 = awsClientProvider.getS3Client(account, "us-east-1");
                List<software.amazon.awssdk.services.s3.model.Bucket> buckets = s3.listBuckets().buckets();
                if (buckets.isEmpty())
                    return output;

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
                                                            Dimension.builder().name("BucketName")
                                                                    .value(regionBuckets.get(i)).build(),
                                                            Dimension.builder().name("StorageType")
                                                                    .value("StandardStorage").build())
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
                                double standardPrice = pricingService.getS3StorageClassGbMonthPrice(region, "Standard");
                                double iaPrice = pricingService.getS3StorageClassGbMonthPrice(region, "Standard-IA");
                                // The saving is a difference, so both rates have to be real. Gating on
                                // the smaller of the two means either one missing marks the finding
                                // unpriced rather than yielding a nonsense delta.
                                double rateGate = Math.min(standardPrice, iaPrice);
                                output.add(wasteWithRate(name, name, "S3 Bucket", region,
                                        rateGate, gbInStandard * (standardPrice - iaPrice),
                                        String.format(
                                                "%.1f GB in Standard storage - consider Intelligent-Tiering or Standard-IA",
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
            List<software.amazon.awssdk.services.efs.model.FileSystemDescription> fileSystems = efs
                    .describeFileSystems(r -> {
                    }).fileSystems();
            if (fileSystems.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(90, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < fileSystems.size(); i++) {
                queries.add(MetricDataQuery.builder().id("efs" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/EFS").metricName("MeteredIOBytes")
                                        .dimensions(Dimension.builder().name("FileSystemId")
                                                .value(fileSystems.get(i).fileSystemId()).build())
                                        .build())
                                .period(86400).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < fileSystems.size(); i++) {
                List<Double> ioSeries = results.getOrDefault("efs" + i, List.of());
                // A healthy file system publishes TotalIOBytes continuously, so no datapoints
                // means the metric is unavailable rather than that the file system is idle.
                if (noDatapoints(ioSeries)) {
                    logger.debug("[Waste] Skipping EFS {} — no TotalIOBytes datapoints in the window",
                            fileSystems.get(i).fileSystemId());
                    continue;
                }
                double totalIo = ioSeries.stream().mapToDouble(Double::doubleValue).sum();
                if (totalIo == 0.0) {
                    var fs = fileSystems.get(i);
                    double sizeGb = fs.sizeInBytes().value() / (1024.0 * 1024 * 1024);
                    String name = fs.name() != null && !fs.name().isEmpty() ? fs.name() : fs.fileSystemId();
                    double efsRate = pricingService.getEfsGbMonthPrice(regionId);
                    findings.add(wasteWithRate(fs.fileSystemId(), name, "EFS File System",
                            regionId, efsRate, sizeGb * efsRate,
                            "No I/O in 90 days - likely idle file system"));
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
            List<software.amazon.awssdk.services.elasticache.model.CacheCluster> clusters = ec
                    .describeCacheClusters(r -> r.showCacheNodeInfo(true)).cacheClusters().stream()
                    .filter(cluster -> "available".equals(cluster.cacheClusterStatus()))
                    .collect(Collectors.toList());
            if (clusters.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(90, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < clusters.size(); i++) {
                queries.add(MetricDataQuery.builder().id("ecache" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/ElastiCache").metricName("CurrConnections")
                                        .dimensions(Dimension.builder().name("CacheClusterId")
                                                .value(clusters.get(i).cacheClusterId()).build())
                                        .build())
                                .period(86400).stat("Maximum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < clusters.size(); i++) {
                List<Double> connSeries = results.getOrDefault("ecache" + i, List.of());
                // CurrConnections is a gauge that an existing cluster always publishes, so an
                // empty series means the cluster is not reporting, not that nobody is connected.
                if (noDatapoints(connSeries)) {
                    logger.debug("[Waste] Skipping ElastiCache cluster {} — no CurrConnections datapoints",
                            clusters.get(i).cacheClusterId());
                    continue;
                }
                double maxConnections = connSeries.stream()
                        .mapToDouble(Double::doubleValue).max().orElse(0.0);
                if (maxConnections == 0.0) {
                    var cluster = clusters.get(i);
                    int nodes = cluster.numCacheNodes() != null ? cluster.numCacheNodes() : 1;
                    double hourlyPrice = pricingService.getElastiCacheNodeHourlyPrice(regionId, cluster.cacheNodeType());
                    findings.add(wasteWithRate(cluster.cacheClusterId(),
                            cluster.cacheClusterId(), "ElastiCache Cluster", regionId,
                            hourlyPrice, nodes * hourlyPrice * 730,
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
            if (tableNames.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(90, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < tableNames.size(); i++) {
                String tbl = tableNames.get(i);
                queries.add(MetricDataQuery.builder().id("ddbr" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/DynamoDB")
                                        .metricName("ConsumedReadCapacityUnits")
                                        .dimensions(Dimension.builder().name("TableName").value(tbl).build()).build())
                                .period(86400).stat("Sum").build())
                        .returnData(true).build());
                queries.add(MetricDataQuery.builder().id("ddbw" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/DynamoDB")
                                        .metricName("ConsumedWriteCapacityUnits")
                                        .dimensions(Dimension.builder().name("TableName").value(tbl).build()).build())
                                .period(86400).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < tableNames.size(); i++) {
                List<Double> readSeries = results.getOrDefault("ddbr" + i, List.of());
                List<Double> writeSeries = results.getOrDefault("ddbw" + i, List.of());
                // A provisioned table publishes consumed-capacity continuously; an on-demand
                // table may not. Either way an empty series is not proof the table is unused.
                if (noDatapoints(readSeries, writeSeries)) {
                    logger.debug("[Waste] Skipping DynamoDB table {} — no consumed-capacity datapoints",
                            tableNames.get(i));
                    continue;
                }
                double reads = readSeries.stream().mapToDouble(Double::doubleValue).sum();
                double writes = writeSeries.stream().mapToDouble(Double::doubleValue).sum();
                if (reads == 0.0 && writes == 0.0) {
                    String tableName = tableNames.get(i);
                    double sizeGb;
                    try {
                        sizeGb = ddb.describeTable(r -> r.tableName(tableName)).table().tableSizeBytes()
                                / (1024.0 * 1024 * 1024);
                    } catch (Exception e) {
                        sizeGb = 0.0;
                    }
                    double ddbRate = pricingService.getDynamoDbGbMonthPrice(regionId);
                    findings.add(wasteWithRate(tableName, tableName, "DynamoDB Table",
                            regionId, ddbRate, sizeGb * ddbRate, "No read/write activity in 90 days"));
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
                            var images = ecr.describeImages(r -> r.repositoryName(repo.repositoryName()))
                                    .imageDetails();
                            if (images.isEmpty())
                                return true;
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
                        double ecrRate = pricingService.getEcrGbMonthPrice(regionId);
                        return wasteWithRate(repo.repositoryArn(), repo.repositoryName(),
                                "ECR Repository", regionId, ecrRate, sizeGb * ecrRate,
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
                    // ACM public certificates are free; private-CA certificates are billed on
                    // the CA, not the certificate. Either way deleting one frees no spend.
                    .map(cert -> DashboardData.WastedResource.withNoCostImpact(cert.certificateArn(),
                            cert.domainName() != null ? cert.domainName() : cert.certificateArn(),
                            "Certificate Manager", regionId,
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
            if (topics.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(90, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < topics.size(); i++) {
                String topicName = topics.get(i).topicArn().substring(topics.get(i).topicArn().lastIndexOf(':') + 1);
                queries.add(MetricDataQuery.builder().id("sns" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/SNS").metricName("NumberOfMessagesPublished")
                                        .dimensions(Dimension.builder().name("TopicName").value(topicName).build())
                                        .build())
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
                    // SNS bills per published message and per delivery. A topic with no traffic
                    // costs nothing to keep, so the previous flat $0.10 was invented.
                    findings.add(DashboardData.WastedResource.withNoCostImpact(topicArn, topicName,
                            "SNS Topic", regionId, "No messages published in 90 days"));
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
            if (queueUrls.isEmpty())
                return List.of();

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
                                        .dimensions(Dimension.builder().name("QueueName").value(queueName).build())
                                        .build())
                                .period(86400).stat("Sum").build())
                        .returnData(true).build());
                queries.add(MetricDataQuery.builder().id("sqsv" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/SQS")
                                        .metricName("ApproximateNumberOfMessagesVisible")
                                        .dimensions(Dimension.builder().name("QueueName").value(queueName).build())
                                        .build())
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
                double sent = sentResults.getOrDefault("sqss" + i, List.of()).stream().mapToDouble(Double::doubleValue)
                        .sum();
                double visible = visibleResults.getOrDefault("sqsv" + i, List.of()).stream()
                        .mapToDouble(Double::doubleValue).max().orElse(0.0);
                if (sent == 0.0 && visible == 0.0) {
                    String queueUrl = queueUrls.get(i);
                    String queueName = queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
                    // SQS bills per request. An empty, unused queue costs nothing to keep, so
                    // the previous flat $0.05 was invented.
                    findings.add(DashboardData.WastedResource.withNoCostImpact(queueUrl, queueName,
                            "SQS Queue", regionId, "No messages sent in 90 days and queue is empty"));
                }
            }
            return findings;
        }, "Idle SQS Queues");
    }

    // EC2 instances that are technically running but oversized — CPU between 3%
    // and 10% avg over 90 days on an instance type of xlarge or larger, where
    // idle capacity carries real cost. The >3% floor excludes truly idle
    // instances, which are already reported by findIdleEc2Instances.
    private CompletableFuture<List<DashboardData.WastedResource>> findOversizedEc2Instances(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);

            List<software.amazon.awssdk.services.ec2.model.Instance> instances = ec2.describeInstances(
                    r -> r.filters(software.amazon.awssdk.services.ec2.model.Filter.builder()
                            .name("instance-state-name").values("running").build()))
                    .reservations().stream()
                    .flatMap(res -> res.instances().stream())
                    .filter(i -> {
                        String t = i.instanceTypeAsString();
                        return t.contains("xlarge") || t.endsWith("2xlarge") || t.endsWith("4xlarge")
                                || t.endsWith("8xlarge") || t.endsWith("12xlarge") || t.endsWith("16xlarge")
                                || t.endsWith("24xlarge") || t.endsWith("32xlarge") || t.endsWith("48xlarge");
                    })
                    .collect(Collectors.toList());

            if (instances.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(90, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < instances.size(); i++) {
                queries.add(MetricDataQuery.builder().id("ec2oc" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/EC2").metricName("CPUUtilization")
                                        .dimensions(Dimension.builder().name("InstanceId")
                                                .value(instances.get(i).instanceId()).build())
                                        .build())
                                .period(86400).stat("Average").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < instances.size(); i++) {
                List<Double> values = results.getOrDefault("ec2oc" + i, List.of());
                double avgCpu = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                if (!values.isEmpty() && values.stream().allMatch(v -> v < 10.0) && avgCpu > 3.0) {
                    var inst = instances.get(i);
                    String name = getTagName(inst.tags(), inst.instanceId());
                    double fullCost = pricingService.getEc2InstanceMonthlyPrice(
                            inst.instanceTypeAsString(), regionId);
                    // The action here is a downsize, not a deletion, so the saving is the
                    // difference between the current and target instance price — not the whole
                    // bill. This detector does not choose a target size, so the figure is reported
                    // as unpriced and the quantified recommendation is left to the rightsizing
                    // service, which does. Booking the full price would have overstated it by
                    // roughly the cost of the smaller instance.
                    findings.add(DashboardData.WastedResource.withUnknownSavings(
                            inst.instanceId(), name, "EC2 Instance", regionId,
                            String.format("Oversized: CPU averaged %.1f%% and never exceeded 10%% over "
                                    + "90 days (%s). Full compute cost is about $%.2f/month; the saving "
                                    + "depends on the target size.",
                                    avgCpu, inst.instanceTypeAsString(), fullCost)));
                }
            }
            return findings;
        }, "Oversized EC2 Instances");
    }

    // Auto Scaling Groups that have never scaled in/out in 30 days — capacity is
    // provisioned but static.
    private CompletableFuture<List<DashboardData.WastedResource>> findIdleAutoScalingGroups(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            AutoScalingClient asg = awsClientProvider.getAutoScalingClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);

            List<software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup> groups = asg
                    .describeAutoScalingGroups().autoScalingGroups().stream()
                    .filter(g -> g.minSize() > 0)
                    .collect(Collectors.toList());

            if (groups.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            List<MetricDataQuery> scaleOutQueries = new ArrayList<>();
            List<MetricDataQuery> scaleInQueries = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                String asgName = groups.get(i).autoScalingGroupName();
                scaleOutQueries.add(MetricDataQuery.builder().id("asgout" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/AutoScaling")
                                        .metricName("GroupTotalInstances")
                                        .dimensions(Dimension.builder().name("AutoScalingGroupName")
                                                .value(asgName).build())
                                        .build())
                                .period(86400).stat("Maximum").build())
                        .returnData(true).build());
                scaleInQueries.add(MetricDataQuery.builder().id("asgin" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/AutoScaling")
                                        .metricName("GroupTotalInstances")
                                        .dimensions(Dimension.builder().name("AutoScalingGroupName")
                                                .value(asgName).build())
                                        .build())
                                .period(86400).stat("Minimum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> maxResults = batchGetMetrics(cw, scaleOutQueries, start, end);
            Map<String, List<Double>> minResults = batchGetMetrics(cw, scaleInQueries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) {
                List<Double> maxVals = maxResults.getOrDefault("asgout" + i, List.of());
                List<Double> minVals = minResults.getOrDefault("asgin" + i, List.of());
                if (maxVals.isEmpty() || minVals.isEmpty())
                    continue;
                double overallMax = maxVals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
                double overallMin = minVals.stream().mapToDouble(Double::doubleValue).min().orElse(0);
                // No scaling activity: max and min stayed equal across all days
                if (overallMax == overallMin) {
                    var g = groups.get(i);
                    // Auto Scaling itself is free; any cost sits in the instances it launches,
                    // which the EC2 detectors already account for.
                    findings.add(DashboardData.WastedResource.withNoCostImpact(
                            g.autoScalingGroupARN(), g.autoScalingGroupName(),
                            "Auto Scaling Group", regionId,
                            "No scale-in/scale-out activity in 30 days"));
                }
            }
            return findings;
        }, "Idle Auto Scaling Groups");
    }

    // NAT Gateways with very low processed bytes — fixed cost ~$32/mo even when
    // unused.
    private CompletableFuture<List<DashboardData.WastedResource>> findIdleNatGateways(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);

            List<software.amazon.awssdk.services.ec2.model.NatGateway> gateways = ec2.describeNatGateways(r -> r.filter(
                    software.amazon.awssdk.services.ec2.model.Filter.builder()
                            .name("state").values("available").build()))
                    .natGateways();

            if (gateways.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < gateways.size(); i++) {
                queries.add(MetricDataQuery.builder().id("nat" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/NATGateway")
                                        .metricName("BytesOutToDestination")
                                        .dimensions(Dimension.builder().name("NatGatewayId")
                                                .value(gateways.get(i).natGatewayId()).build())
                                        .build())
                                .period(2592000).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < gateways.size(); i++) {
                List<Double> natSeries = results.getOrDefault("nat" + i, List.of());
                // An active NAT gateway publishes byte counters continuously. No datapoints means
                // the metric is unavailable, and deleting a NAT gateway blackholes every route
                // that points at it — too destructive to act on an absent metric.
                if (noDatapoints(natSeries)) {
                    logger.debug("[Waste] Skipping NAT gateway {} — no traffic datapoints in the window",
                            gateways.get(i).natGatewayId());
                    continue;
                }
                double totalBytes = natSeries.stream().mapToDouble(Double::doubleValue).sum();
                // Flag if less than 1 MB of outbound traffic in 30 days
                if (totalBytes < 1_048_576) {
                    var gw = gateways.get(i);
                    String name = gw.tags().stream().filter(t -> "Name".equals(t.key()))
                            .map(software.amazon.awssdk.services.ec2.model.Tag::value)
                            .findFirst().orElse(gw.natGatewayId());
                    double natHourly = pricingService.getNatGatewayHourlyPrice(regionId);
                    findings.add(wasteWithRate(gw.natGatewayId(), name,
                            "NAT Gateway", regionId, natHourly, natHourly * 730,
                            "Near-zero traffic in 30 days (<1 MB outbound)"));
                }
            }
            return findings;
        }, "Idle NAT Gateways");
    }

    /**
     * The region a bucket lives in, so its CloudWatch metrics are queried in the right place.
     *
     * @return the region, or null when it cannot be determined
     */
    private String resolveBucketRegion(S3Client s3, String bucketName) {
        try {
            String loc = s3.getBucketLocation(req -> req.bucket(bucketName)).locationConstraintAsString();
            // An empty location constraint means us-east-1.
            return (loc == null || loc.isEmpty()) ? "us-east-1" : loc;
        } catch (Exception e) {
            logger.debug("[Waste] Could not resolve region for bucket {}: {}", bucketName, e.getMessage());
            return null;
        }
    }

    // S3 buckets with no GET requests in 90 days — data sitting cold without
    // Glacier pricing.
    private CompletableFuture<List<DashboardData.WastedResource>> findS3BucketsWithNoReads(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                S3Client s3 = awsClientProvider.getS3Client(account, "us-east-1");
                List<software.amazon.awssdk.services.s3.model.Bucket> buckets = s3.listBuckets().buckets();
                if (buckets.isEmpty())
                    return List.of();

                // A bucket's metrics live in the bucket's own region. This previously queried
                // us-east-1 CloudWatch for every bucket in the account, so buckets outside
                // us-east-1 returned nothing and were all reported as never read. The sibling
                // findS3StorageClassWaste already groups by region; this now does the same.
                Map<String, List<String>> bucketsByRegion = new HashMap<>();
                for (var bucket : buckets) {
                    String bucketRegion = resolveBucketRegion(s3, bucket.name());
                    if (bucketRegion == null) {
                        continue;
                    }
                    bucketsByRegion.computeIfAbsent(bucketRegion, k -> new ArrayList<>()).add(bucket.name());
                }

                Instant end = Instant.now();
                Instant start = end.minus(90, ChronoUnit.DAYS);
                List<DashboardData.WastedResource> findings = new ArrayList<>();

                for (Map.Entry<String, List<String>> entry : bucketsByRegion.entrySet()) {
                    String bucketRegion = entry.getKey();
                    List<String> names = entry.getValue();
                    try {
                        CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, bucketRegion);
                        List<MetricDataQuery> queries = new ArrayList<>();
                        for (int i = 0; i < names.size(); i++) {
                            queries.add(MetricDataQuery.builder().id("s3r" + i)
                                    .metricStat(MetricStat.builder()
                                            .metric(Metric.builder().namespace("AWS/S3")
                                                    .metricName("GetRequests")
                                                    .dimensions(
                                                            Dimension.builder().name("BucketName")
                                                                    .value(names.get(i)).build(),
                                                            Dimension.builder().name("FilterId")
                                                                    .value("EntireBucket").build())
                                                    .build())
                                            .period(2592000).stat("Sum").build())
                                    .returnData(true).build());
                        }
                        Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

                        for (int i = 0; i < names.size(); i++) {
                            List<Double> getSeries = results.getOrDefault("s3r" + i, List.of());
                            // AWS/S3 GetRequests belongs to request metrics, an opt-in paid feature
                            // configured per bucket. Where they are not enabled the series is empty
                            // for EVERY bucket, so this used to report the whole account as unread.
                            if (noDatapoints(getSeries)) {
                                logger.debug("[Waste] Skipping S3 bucket {} — no GetRequests datapoints; "
                                        + "request metrics are likely not enabled on this bucket", names.get(i));
                                continue;
                            }
                            double gets = getSeries.stream().mapToDouble(Double::doubleValue).sum();
                            if (gets == 0.0) {
                                // The storage cost is real but is not priced here, so report it as
                                // unpriced rather than as a zero that reads like "free to keep".
                                findings.add(DashboardData.WastedResource.withUnknownSavings(
                                        names.get(i), names.get(i), "S3 Bucket", bucketRegion,
                                        "No GET requests in 90 days — consider Glacier or deletion"));
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("S3 no-read scan failed for region {}: {}", bucketRegion, e.getMessage());
                    }
                }
                return findings;
            } catch (Exception e) {
                logger.warn("S3 no-read scan failed: {}", e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    // CloudWatch Log Groups with no retention policy — logs accumulate forever at
    // $0.03/GB/month.
    /**
     * Whether a log group has received an event since {@code sinceMillis}.
     *
     * <p>Used so the no-retention detector does not also report groups that
     * {@code findUnusedCloudWatchLogGroups} already covers. Returns true on failure, which keeps
     * the group in this detector rather than dropping it from both.
     */
    private boolean hasRecentLogEvents(CloudWatchLogsClient logs, String logGroupName, long sinceMillis) {
        try {
            DescribeLogStreamsResponse response = logs.describeLogStreams(req -> req
                    .logGroupName(logGroupName).orderBy("LastEventTime").descending(true).limit(1));
            if (response.logStreams().isEmpty()) {
                return false;
            }
            Long lastEvent = response.logStreams().get(0).lastEventTimestamp();
            return lastEvent == null || lastEvent >= sinceMillis;
        } catch (Exception e) {
            logger.debug("[Waste] Could not read log streams for {}: {}", logGroupName, e.getMessage());
            return true;
        }
    }

    private CompletableFuture<List<DashboardData.WastedResource>> findLogGroupsWithNoRetentionPolicy(
            CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CloudWatchLogsClient logs = awsClientProvider.getCloudWatchLogsClient(account, regionId);
            long sixtyDaysAgoMillis = Instant.now().minus(60, ChronoUnit.DAYS).toEpochMilli();
            // Resolved once per region; the price call is cached but an account can have
            // thousands of log groups.
            final double logsGbMonthPrice = logsStorageGbMonthPrice(regionId);

            return logs.describeLogGroups().logGroups().stream()
                    .filter(lg -> lg.retentionInDays() == null && lg.storedBytes() != null && lg.storedBytes() > 0)
                    // findUnusedCloudWatchLogGroups already reports groups with no recent events,
                    // and priced the same stored gigabytes at a different rate. Reporting a group
                    // in both places double-counted its cost and showed the customer two
                    // contradictory figures for one resource. This detector is about the absence
                    // of a retention policy, so it defers on anything already flagged as stale.
                    .filter(lg -> lg.creationTime() == null || lg.creationTime() >= sixtyDaysAgoMillis
                            || hasRecentLogEvents(logs, lg.logGroupName(), sixtyDaysAgoMillis))
                    .map(lg -> {
                        // Was hardcoded at $0.03/GB while the sibling detector used the live
                        // regional rate, so the two disagreed outside us-east-1.
                        double monthlyCost = (lg.storedBytes() / 1_073_741_824.0) * logsGbMonthPrice;
                        return new DashboardData.WastedResource(lg.logGroupName(), lg.logGroupName(),
                                "CloudWatch Log Group", regionId, monthlyCost,
                                "No retention policy — logs accumulate indefinitely");
                    })
                    .collect(Collectors.toList());
        }, "Log Groups With No Retention");
    }

    // EBS volumes that are attached to an instance but have had zero read/write ops
    // in 14 days.
    private CompletableFuture<List<DashboardData.WastedResource>> findAttachedButIdleEbsVolumes(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);

            List<Volume> volumes = ec2.describeVolumes(r -> r.filters(
                    software.amazon.awssdk.services.ec2.model.Filter.builder()
                            .name("status").values("in-use").build()))
                    .volumes();

            if (volumes.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            List<MetricDataQuery> readQueries = new ArrayList<>();
            List<MetricDataQuery> writeQueries = new ArrayList<>();
            for (int i = 0; i < volumes.size(); i++) {
                String vid = volumes.get(i).volumeId();
                readQueries.add(MetricDataQuery.builder().id("ebsr" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/EBS").metricName("VolumeReadOps")
                                        .dimensions(Dimension.builder().name("VolumeId").value(vid).build()).build())
                                .period(1209600).stat("Sum").build())
                        .returnData(true).build());
                writeQueries.add(MetricDataQuery.builder().id("ebsw" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/EBS").metricName("VolumeWriteOps")
                                        .dimensions(Dimension.builder().name("VolumeId").value(vid).build()).build())
                                .period(1209600).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> readResults = batchGetMetrics(cw, readQueries, start, end);
            Map<String, List<Double>> writeResults = batchGetMetrics(cw, writeQueries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < volumes.size(); i++) {
                List<Double> readSeries = readResults.getOrDefault("ebsr" + i, List.of());
                List<Double> writeSeries = writeResults.getOrDefault("ebsw" + i, List.of());

                // No datapoints is not the same as no I/O. CloudWatch returns an empty series
                // when a volume is attached to a stopped instance, when the volume is newer than
                // the window, or when the metric simply did not publish — and this used to sum
                // that empty list to 0.0 and recommend deleting an attached, in-use volume.
                // findIdleEc2Instances already guards this way; this detector did not.
                if (readSeries.isEmpty() && writeSeries.isEmpty()) {
                    logger.debug("[Waste] Skipping volume {} — no EBS I/O datapoints in the window, "
                            + "which is not evidence of idleness", volumes.get(i).volumeId());
                    continue;
                }

                double reads = readSeries.stream().mapToDouble(Double::doubleValue).sum();
                double writes = writeSeries.stream().mapToDouble(Double::doubleValue).sum();
                if (reads == 0.0 && writes == 0.0) {
                    Volume v = volumes.get(i);
                    double cost = calculateEbsMonthlyCost(v, regionId);
                    findings.add(new DashboardData.WastedResource(v.volumeId(),
                            getTagName(v.tags(), v.volumeId()), "EBS Volume", regionId, cost,
                            "Attached but 0 read/write ops in 30 days"));
                }
            }
            return findings;
        }, "Attached But Idle EBS Volumes");
    }

    // CloudFront distributions with zero requests in 30 days — still incur origin
    // transfer costs.
    private CompletableFuture<List<DashboardData.WastedResource>> findCloudFrontDistributionsWithNoTraffic(
            CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CloudFrontClient cf = awsClientProvider.getCloudFrontClient(account);
                CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, "us-east-1");

                List<software.amazon.awssdk.services.cloudfront.model.DistributionSummary> distributions = cf
                        .listDistributions().distributionList().items();
                if (distributions.isEmpty())
                    return List.of();

                Instant end = Instant.now();
                Instant start = end.minus(30, ChronoUnit.DAYS);
                List<MetricDataQuery> queries = new ArrayList<>();
                for (int i = 0; i < distributions.size(); i++) {
                    queries.add(MetricDataQuery.builder().id("cf" + i)
                            .metricStat(MetricStat.builder()
                                    .metric(Metric.builder().namespace("AWS/CloudFront")
                                            .metricName("Requests")
                                            .dimensions(
                                                    Dimension.builder().name("DistributionId")
                                                            .value(distributions.get(i).id()).build(),
                                                    Dimension.builder().name("Region").value("Global").build())
                                            .build())
                                    .period(2592000).stat("Sum").build())
                            .returnData(true).build());
                }
                Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

                List<DashboardData.WastedResource> findings = new ArrayList<>();
                for (int i = 0; i < distributions.size(); i++) {
                    double requests = results.getOrDefault("cf" + i, List.of())
                            .stream().mapToDouble(Double::doubleValue).sum();
                    if (requests == 0.0) {
                        var dist = distributions.get(i);
                        findings.add(new DashboardData.WastedResource(dist.id(), dist.domainName(),
                                "CloudFront Distribution", "global", 0.0,
                                "Zero requests in 30 days"));
                    }
                }
                return findings;
            } catch (Exception e) {
                logger.warn("CloudFront idle scan failed: {}", e.getMessage());
                return Collections.<DashboardData.WastedResource>emptyList();
            }
        });
    }

    // Route 53 hosted zones with no DNS queries — $0.50/zone/month accumulates
    // across abandoned projects.
    private CompletableFuture<List<DashboardData.WastedResource>> findUnusedRoute53HostedZones(
            CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Route53Client r53 = awsClientProvider.getRoute53Client(account);
                CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, "us-east-1");

                List<software.amazon.awssdk.services.route53.model.HostedZone> zones = r53.listHostedZones()
                        .hostedZones().stream()
                        .filter(z -> !z.config().privateZone())
                        .collect(Collectors.toList());
                if (zones.isEmpty())
                    return List.of();

                Instant end = Instant.now();
                Instant start = end.minus(30, ChronoUnit.DAYS);
                List<MetricDataQuery> queries = new ArrayList<>();
                for (int i = 0; i < zones.size(); i++) {
                    String zoneId = zones.get(i).id().replace("/hostedzone/", "");
                    queries.add(MetricDataQuery.builder().id("r53" + i)
                            .metricStat(MetricStat.builder()
                                    .metric(Metric.builder().namespace("AWS/Route53")
                                            .metricName("DNSQueries")
                                            .dimensions(Dimension.builder().name("HostedZoneId")
                                                    .value(zoneId).build())
                                            .build())
                                    .period(2592000).stat("Sum").build())
                            .returnData(true).build());
                }
                Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

                List<DashboardData.WastedResource> findings = new ArrayList<>();
                for (int i = 0; i < zones.size(); i++) {
                    double queries2 = results.getOrDefault("r53" + i, List.of())
                            .stream().mapToDouble(Double::doubleValue).sum();
                    if (queries2 == 0.0) {
                        var zone = zones.get(i);
                        findings.add(new DashboardData.WastedResource(zone.id(), zone.name(),
                                "Route53 Hosted Zone", "global", 0.50,
                                "Zero DNS queries in 30 days"));
                    }
                }
                return findings;
            } catch (Exception e) {
                logger.warn("Route53 idle scan failed: {}", e.getMessage());
                return Collections.<DashboardData.WastedResource>emptyList();
            }
        });
    }

    // Glue jobs that were created but have never been triggered — ETL pipelines for
    // dead projects.
    private CompletableFuture<List<DashboardData.WastedResource>> findGlueJobsNeverRan(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            GlueClient glue = awsClientProvider.getGlueClient(account, regionId);
            List<DashboardData.WastedResource> findings = new ArrayList<>();
            try {
                var jobs = glue.getJobs(r -> {}).jobs();
                for (var job : jobs) {
                    try {
                        var runs = glue.getJobRuns(r -> r.jobName(job.name()).maxResults(1)).jobRuns();
                        if (runs.isEmpty()) {
                            // Glue bills per DPU-hour of execution, so a job that never ran has
                            // cost nothing.
                            findings.add(DashboardData.WastedResource.withNoCostImpact(
                                    job.name(), job.name(),
                                    "Glue Job", regionId, "Job created but never executed"));
                        }
                    } catch (Exception e) {
                        logger.warn("Could not get runs for Glue job {}: {}", job.name(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.warn("Glue job scan failed in {}: {}", regionId, e.getMessage());
            }
            return findings;
        }, "Glue Jobs Never Ran");
    }

    // Step Functions state machines with zero executions in 30 days — forgotten
    // automations.
    private CompletableFuture<List<DashboardData.WastedResource>> findIdleStepFunctionsMachines(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            SfnClient sfn = awsClientProvider.getSfnClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);

            List<software.amazon.awssdk.services.sfn.model.StateMachineListItem> machines = sfn.listStateMachines()
                    .stateMachines();
            if (machines.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < machines.size(); i++) {
                String machineName = machines.get(i).name();
                queries.add(MetricDataQuery.builder().id("sfn" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/States")
                                        .metricName("ExecutionsStarted")
                                        .dimensions(Dimension.builder().name("StateMachineArn")
                                                .value(machines.get(i).stateMachineArn()).build())
                                        .build())
                                .period(2592000).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < machines.size(); i++) {
                double executions = results.getOrDefault("sfn" + i, List.of())
                        .stream().mapToDouble(Double::doubleValue).sum();
                if (executions == 0.0) {
                    var m = machines.get(i);
                    // Step Functions bills per state transition, so an unexecuted state machine
                    // is free to keep.
                    findings.add(DashboardData.WastedResource.withNoCostImpact(
                            m.stateMachineArn(), m.name(),
                            "Step Functions State Machine", regionId,
                            "Zero executions in 30 days"));
                }
            }
            return findings;
        }, "Idle Step Functions State Machines");
    }

    // API Gateway REST APIs with zero calls in 30 days — live endpoints nobody is
    // hitting.
    private CompletableFuture<List<DashboardData.WastedResource>> findApiGatewayApisWithNoTraffic(
            CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ApiGatewayClient apigw = awsClientProvider.getApiGatewayClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);

            List<software.amazon.awssdk.services.apigateway.model.RestApi> apis = apigw.getRestApis().items();
            if (apis.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < apis.size(); i++) {
                queries.add(MetricDataQuery.builder().id("apigw" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/ApiGateway")
                                        .metricName("Count")
                                        .dimensions(Dimension.builder().name("ApiName")
                                                .value(apis.get(i).name()).build())
                                        .build())
                                .period(2592000).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < apis.size(); i++) {
                List<Double> countSeries = results.getOrDefault("apigw" + i, List.of());
                // AWS/ApiGateway Count is published per stage only when detailed CloudWatch
                // metrics are enabled on that stage. With them off the series is empty for EVERY
                // API, so this detector reported every REST API in the account as receiving no
                // traffic.
                if (noDatapoints(countSeries)) {
                    logger.debug("[Waste] Skipping API Gateway {} — no Count datapoints; detailed "
                            + "CloudWatch metrics are likely not enabled on its stages",
                            apis.get(i).name());
                    continue;
                }
                double count = countSeries.stream().mapToDouble(Double::doubleValue).sum();
                if (count == 0.0) {
                    var api = apis.get(i);
                    // API Gateway bills per request; an API with no traffic accrues nothing.
                    findings.add(DashboardData.WastedResource.withNoCostImpact(api.id(), api.name(),
                            "API Gateway REST API", regionId,
                            "Zero API calls in 30 days"));
                }
            }
            return findings;
        }, "Idle API Gateway APIs");
    }

    // CodeBuild projects with no builds in 60 days — CI pipelines for dead or
    // migrated projects.
    private CompletableFuture<List<DashboardData.WastedResource>> findCodeBuildProjectsWithNoBuilds(
            CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CodeBuildClient cb = awsClientProvider.getCodeBuildClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);

            List<String> projectNames = cb.listProjects().projects();
            if (projectNames.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(60, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < projectNames.size(); i++) {
                queries.add(MetricDataQuery.builder().id("cb" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/CodeBuild")
                                        .metricName("Builds")
                                        .dimensions(Dimension.builder().name("ProjectName")
                                                .value(projectNames.get(i)).build())
                                        .build())
                                .period(5184000).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < projectNames.size(); i++) {
                double builds = results.getOrDefault("cb" + i, List.of())
                        .stream().mapToDouble(Double::doubleValue).sum();
                if (builds == 0.0) {
                    String name = projectNames.get(i);
                    // CodeBuild bills per build minute, so a project with no builds is free.
                    findings.add(DashboardData.WastedResource.withNoCostImpact(name, name,
                            "CodeBuild Project", regionId,
                            "No builds in 60 days"));
                }
            }
            return findings;
        }, "Idle CodeBuild Projects");
    }

    // Kinesis streams with provisioned shards but no data — each shard costs
    // ~$15/month idle.
    private CompletableFuture<List<DashboardData.WastedResource>> findIdleKinesisStreams(CloudAccount account,
            List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            KinesisClient kinesis = awsClientProvider.getKinesisClient(account, regionId);
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, regionId);

            List<String> streamNames = kinesis.listStreams().streamNames();
            if (streamNames.isEmpty())
                return List.of();

            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            List<MetricDataQuery> queries = new ArrayList<>();
            for (int i = 0; i < streamNames.size(); i++) {
                queries.add(MetricDataQuery.builder().id("kin" + i)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder().namespace("AWS/Kinesis")
                                        .metricName("PutRecords.Records")
                                        .dimensions(Dimension.builder().name("StreamName")
                                                .value(streamNames.get(i)).build())
                                        .build())
                                .period(2592000).stat("Sum").build())
                        .returnData(true).build());
            }
            Map<String, List<Double>> results = batchGetMetrics(cw, queries, start, end);

            List<DashboardData.WastedResource> findings = new ArrayList<>();
            for (int i = 0; i < streamNames.size(); i++) {
                double records = results.getOrDefault("kin" + i, List.of())
                        .stream().mapToDouble(Double::doubleValue).sum();
                if (records == 0.0) {
                    try {
                        final String streamName = streamNames.get(i);
                        var desc = kinesis.describeStreamSummary(r -> r.streamName(streamName))
                                .streamDescriptionSummary();
                        int shardCount = desc.openShardCount();
                        double monthlyCost = shardCount * 15.12;
                        findings.add(new DashboardData.WastedResource(
                                desc.streamARN(), streamName,
                                "Kinesis Stream", regionId, monthlyCost,
                                "No records in 30 days (" + shardCount + " provisioned shard"
                                        + (shardCount != 1 ? "s" : "") + ")"));
                    } catch (Exception e) {
                        logger.warn("Could not describe Kinesis stream {}: {}", streamNames.get(i), e.getMessage());
                    }
                }
            }
            return findings;
        }, "Idle Kinesis Streams");
    }
}