package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.MetricDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.rds.RdsClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class MetricsService {

    private static final Logger logger = LoggerFactory.getLogger(MetricsService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final CloudListService cloudListService;

    /**
     * Resolved regions keyed by {@code accountId|serviceType|resourceId}. An empty value
     * caches a negative result so a deleted resource is not re-scanned across all regions.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> resourceRegionCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public MetricsService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider,
            @Lazy CloudListService cloudListService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.cloudListService = cloudListService;
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found in database: " + accountId));
    }

    /**
     * Peak CPU utilization over the lookback window, with the number of daily datapoints
     * the figure is based on.
     *
     * <p>The datapoint count matters: {@code Maximum} over a one-day period returns a
     * value from a single datapoint just as readily as from fourteen, so a caller that
     * treats the result as "peak over 14 days" would downsize an instance launched an hour
     * ago. Callers must check {@link CpuUtilizationSample#hasSufficientData(int)} before
     * acting.
     *
     * @param lookbackDays window length in days
     * @return the sample, or empty when the metric could not be read at all
     */
    public Optional<CpuUtilizationSample> getMaxCpuUtilizationSample(
            String accountId, String instanceId, String region, int lookbackDays) {
        try {
            CloudAccount account = getAccount(accountId);
            CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, region);

            Metric metric = Metric.builder()
                    .namespace("AWS/EC2")
                    .metricName("CPUUtilization")
                    .dimensions(Dimension.builder().name("InstanceId").value(instanceId).build())
                    .build();

            MetricStat metricStat = MetricStat.builder()
                    .metric(metric)
                    .period(86400) // Daily statistics
                    .stat("Maximum")
                    .build();

            MetricDataQuery metricDataQuery = MetricDataQuery.builder()
                    .id("maxCpu")
                    .metricStat(metricStat)
                    .returnData(true)
                    .build();

            GetMetricDataRequest request = GetMetricDataRequest.builder()
                    .startTime(Instant.now().minus(lookbackDays, ChronoUnit.DAYS))
                    .endTime(Instant.now())
                    .metricDataQueries(metricDataQuery)
                    .build();

            GetMetricDataResponse response = cwClient.getMetricData(request);
            if (!response.metricDataResults().isEmpty() && !response.metricDataResults().get(0).values().isEmpty()) {
                List<Double> values = response.metricDataResults().get(0).values();
                Optional<Double> max = values.stream().max(Double::compare);
                if (max.isPresent()) {
                    return Optional.of(new CpuUtilizationSample(max.get(), values.size(), lookbackDays));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get max CPU utilization for instance {}", instanceId, e);
        }
        return Optional.empty();
    }

    /**
     * Peak CPU utilization over the lookback window.
     *
     * @deprecated this loses the datapoint count, so callers cannot tell a 14-day peak from
     *             a one-hour peak. Use
     *             {@link #getMaxCpuUtilizationSample(String, String, String, int)}.
     */
    @Deprecated
    public Optional<Double> getMaxCpuUtilization(String accountId, String instanceId, String region, int lookbackDays) {
        return getMaxCpuUtilizationSample(accountId, instanceId, region, lookbackDays)
                .map(CpuUtilizationSample::maxCpuPercent);
    }

    /**
     * A CPU utilization reading together with its observation coverage.
     *
     * @param maxCpuPercent highest observed CPU percentage in the window
     * @param dataPoints    number of daily datapoints returned
     * @param lookbackDays  window length requested
     */
    public record CpuUtilizationSample(double maxCpuPercent, int dataPoints, int lookbackDays) {

        /**
         * True when enough of the window was observed for the peak to be meaningful.
         *
         * @param minimumDays minimum number of daily datapoints required
         */
        public boolean hasSufficientData(int minimumDays) {
            return dataPoints >= minimumDays;
        }

        /** Fraction of the requested window actually covered by data, 0.0 to 1.0. */
        public double coverage() {
            return lookbackDays <= 0 ? 0.0 : Math.min(1.0, (double) dataPoints / lookbackDays);
        }
    }


    /**
     * Note on caching: {@code forceRefresh} is honoured via {@code condition}, and the
     * previous {@code unless = "#result.get().isEmpty()"} has been removed.
     *
     * <p>That expression called blocking {@code get()} on the returned
     * {@link CompletableFuture} from the cache interceptor thread, and because
     * {@code forceRefresh} was absent from the key, passing {@code true} still returned the
     * cached future — making the parameter a no-op.
     */
    @Async("awsTaskExecutor")
    @Cacheable(value = "metrics-ec2", key = "{#accountId, #instanceId}", condition = "!#forceRefresh")
    public CompletableFuture<Map<String, List<MetricDto>>> getEc2InstanceMetrics(String accountId, String instanceId, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            CloudAccount account = getAccount(accountId);
            String instanceRegion = findInstanceRegion(account, instanceId);
            if (instanceRegion == null) {
                logger.error("Could not determine region for EC2 instance {}. Cannot fetch metrics.", instanceId);
                return Collections.emptyMap();
            }
            CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, instanceRegion);
            logger.info("Fetching CloudWatch metrics for instance: {} in region {} for account {}", instanceId, instanceRegion, accountId);
            try {
                GetMetricDataRequest cpuRequest = buildMetricDataRequest(instanceId, "CPUUtilization", "AWS/EC2", "InstanceId");
                MetricDataResult cpuResult = cwClient.getMetricData(cpuRequest).metricDataResults().get(0);
                List<MetricDto> cpuDatapoints = buildMetricDtos(cpuResult);

                GetMetricDataRequest networkInRequest = buildMetricDataRequest(instanceId, "NetworkIn", "AWS/EC2", "InstanceId");
                MetricDataResult networkInResult = cwClient.getMetricData(networkInRequest).metricDataResults().get(0);
                List<MetricDto> networkInDatapoints = buildMetricDtos(networkInResult);

                return Map.of("CPUUtilization", cpuDatapoints, "NetworkIn", networkInDatapoints);
            } catch (Exception e) {
                logger.error("Failed to fetch metrics for instance {} in account {}", instanceId, accountId, e);
                return Collections.emptyMap();
            }
        });
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "metrics-rds", key = "{#accountId, #instanceId}", condition = "!#forceRefresh")
    public CompletableFuture<Map<String, List<MetricDto>>> getRdsInstanceMetrics(String accountId, String instanceId, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            CloudAccount account = getAccount(accountId);
            String rdsRegion = findResourceRegion(account, "RDS", instanceId);
            if (rdsRegion == null) {
                logger.error("Could not determine region for RDS instance {}. Cannot fetch metrics.", instanceId);
                return Collections.emptyMap();
            }
            CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, rdsRegion);
            logger.info("Fetching CloudWatch metrics for RDS instance: {} in region {} for account {}", instanceId, rdsRegion, accountId);
            try {
                GetMetricDataRequest connectionsRequest = buildMetricDataRequest(instanceId, "DatabaseConnections", "AWS/RDS", "DBInstanceIdentifier");
                MetricDataResult connectionsResult = cwClient.getMetricData(connectionsRequest).metricDataResults().get(0);
                List<MetricDto> connectionsDatapoints = buildMetricDtos(connectionsResult);

                GetMetricDataRequest readIopsRequest = buildMetricDataRequest(instanceId, "ReadIOPS", "AWS/RDS", "DBInstanceIdentifier");
                MetricDataResult readIopsResult = cwClient.getMetricData(readIopsRequest).metricDataResults().get(0);
                List<MetricDto> readIopsDatapoints = buildMetricDtos(readIopsResult);

                GetMetricDataRequest writeIopsRequest = buildMetricDataRequest(instanceId, "WriteIOPS", "AWS/RDS", "DBInstanceIdentifier");
                MetricDataResult writeIopsResult = cwClient.getMetricData(writeIopsRequest).metricDataResults().get(0);
                List<MetricDto> writeIopsDatapoints = buildMetricDtos(writeIopsResult);

                return Map.of(
                        "DatabaseConnections", connectionsDatapoints,
                        "ReadIOPS", readIopsDatapoints,
                        "WriteIOPS", writeIopsDatapoints
                );
            } catch (Exception e) {
                logger.error("Failed to fetch metrics for RDS instance {} in account {}", instanceId, accountId, e);
                return Collections.emptyMap();
            }
        });
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "metrics-s3", key = "{#accountId, #bucketName}", condition = "!#forceRefresh")
    public CompletableFuture<Map<String, List<MetricDto>>> getS3BucketMetrics(String accountId, String bucketName, String region, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            CloudAccount account = getAccount(accountId);
            CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, region);
            logger.info("Fetching CloudWatch metrics for S3 bucket: {} in region {} for account {}", bucketName, region, accountId);
            try {
                GetMetricDataRequest sizeRequest = buildMetricDataRequest(bucketName, "BucketSizeBytes", "AWS/S3", "BucketName");
                MetricDataResult sizeResult = cwClient.getMetricData(sizeRequest).metricDataResults().get(0);
                List<MetricDto> sizeDatapoints = buildMetricDtos(sizeResult);

                GetMetricDataRequest objectsRequest = buildMetricDataRequest(bucketName, "NumberOfObjects", "AWS/S3", "BucketName");
                MetricDataResult objectsResult = cwClient.getMetricData(objectsRequest).metricDataResults().get(0);
                List<MetricDto> objectsDatapoints = buildMetricDtos(objectsResult);

                return Map.of("BucketSizeBytes", sizeDatapoints, "NumberOfObjects", objectsDatapoints);
            } catch (Exception e) {
                logger.error("Failed to fetch metrics for S3 bucket {} in account {}", bucketName, accountId, e);
                return Collections.emptyMap();
            }
        });
    }

    @Async("awsTaskExecutor")
    @Cacheable(value = "metrics-lambda", key = "{#accountId, #functionName}", condition = "!#forceRefresh")
    public CompletableFuture<Map<String, List<MetricDto>>> getLambdaFunctionMetrics(String accountId, String functionName, String region, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> {
            CloudAccount account = getAccount(accountId);
            CloudWatchClient cwClient = awsClientProvider.getCloudWatchClient(account, region);
            logger.info("Fetching CloudWatch metrics for Lambda function: {} in region {} for account {}", functionName, region, accountId);
            try {
                GetMetricDataRequest invocationsRequest = buildMetricDataRequest(functionName, "Invocations", "AWS/Lambda", "FunctionName");
                MetricDataResult invocationsResult = cwClient.getMetricData(invocationsRequest).metricDataResults().get(0);
                List<MetricDto> invocationsDatapoints = buildMetricDtos(invocationsResult);

                GetMetricDataRequest errorsRequest = buildMetricDataRequest(functionName, "Errors", "AWS/Lambda", "FunctionName");
                MetricDataResult errorsResult = cwClient.getMetricData(errorsRequest).metricDataResults().get(0);
                List<MetricDto> errorsDatapoints = buildMetricDtos(errorsResult);

                GetMetricDataRequest durationRequest = buildMetricDataRequest(functionName, "Duration", "AWS/Lambda", "FunctionName");
                MetricDataResult durationResult = cwClient.getMetricData(durationRequest).metricDataResults().get(0);
                List<MetricDto> durationDatapoints = buildMetricDtos(durationResult);

                return Map.of(
                        "Invocations", invocationsDatapoints,
                        "Errors", errorsDatapoints,
                        "Duration", durationDatapoints
                );
            } catch (Exception e) {
                logger.error("Failed to fetch metrics for Lambda function {} in account {}", functionName, accountId, e);
                return Collections.emptyMap();
            }
        });
    }

    /**
     * Resolves the region a resource lives in, memoized per account and resource.
     *
     * <p>The scan below issues a {@code DescribeInstances} against every opted-in region —
     * roughly 30 calls, each with its own client — for a single instance. It was called on
     * every metrics request, and again per instance from callers that iterate. Caching the
     * result makes the scan a once-per-resource cost instead of a per-request one.
     */
    private String findInstanceRegion(CloudAccount account, String instanceId) {
        String cacheKey = account.getAwsAccountId() + "|EC2|" + instanceId;
        String cachedRegion = resourceRegionCache.get(cacheKey);
        if (cachedRegion != null) {
            return cachedRegion.isEmpty() ? null : cachedRegion;
        }
        String resolved = locateInstanceRegion(account, instanceId);
        // Cache negative results too, so a deleted instance does not re-scan every region.
        resourceRegionCache.put(cacheKey, resolved == null ? "" : resolved);
        return resolved;
    }

    /** Performs the region scan. See {@link #findInstanceRegion} for caching. */
    private String locateInstanceRegion(CloudAccount account, String instanceId) {
        logger.debug("Attempting to find region for instance {}", instanceId);
        Ec2Client baseEc2Client = awsClientProvider.getEc2Client(account, "us-east-1");
        List<Region> allPossibleRegions = baseEc2Client.describeRegions().regions();

        for (Region region : allPossibleRegions) {
            if ("not-opted-in".equals(region.optInStatus())) continue;
            try {
                Ec2Client regionEc2Client = awsClientProvider.getEc2Client(account, region.regionName());
                DescribeInstancesRequest request = DescribeInstancesRequest.builder().instanceIds(instanceId).build();
                DescribeInstancesResponse response = regionEc2Client.describeInstances(request);
                if (response.hasReservations() && !response.reservations().isEmpty()) {
                    logger.info("Found instance {} in region {}", instanceId, region.regionName());
                    return region.regionName();
                }
            } catch (Exception e) {
                logger.trace("Instance {} not found in region {}: {}", instanceId, region.regionName(), e.getMessage());
            }
        }
        logger.warn("Could not find instance {} in any region.", instanceId);
        return null;
    }

    /** Memoized equivalent of {@link #findInstanceRegion} for non-EC2 resources. */
    private String findResourceRegion(CloudAccount account, String serviceType, String resourceId) {
        String cacheKey = account.getAwsAccountId() + "|" + serviceType + "|" + resourceId;
        String cachedRegion = resourceRegionCache.get(cacheKey);
        if (cachedRegion != null) {
            return cachedRegion.isEmpty() ? null : cachedRegion;
        }
        String resolved = locateResourceRegion(account, serviceType, resourceId);
        resourceRegionCache.put(cacheKey, resolved == null ? "" : resolved);
        return resolved;
    }

    /** Performs the region scan. See {@link #findResourceRegion} for caching. */
    private String locateResourceRegion(CloudAccount account, String serviceType, String resourceId) {
        logger.debug("Attempting to find region for {} resource {}", serviceType, resourceId);
        List<DashboardData.RegionStatus> activeRegions = cloudListService.getRegionStatusForAccount(account, false).join();

        for (DashboardData.RegionStatus region : activeRegions) {
            try {
                if ("RDS".equals(serviceType)) {
                    RdsClient rds = awsClientProvider.getRdsClient(account, region.getRegionId());
                    if (rds.describeDBInstances(r -> r.dbInstanceIdentifier(resourceId)).hasDbInstances()) {
                        logger.info("Found RDS instance {} in region {}", resourceId, region.getRegionId());
                        return region.getRegionId();
                    }
                }
            } catch (Exception e) {
                logger.trace("{} {} not found in region {}: {}", serviceType, resourceId, region.getRegionId(), e.getMessage());
            }
        }
        logger.warn("Could not find {} resource {} in any active region.", serviceType, resourceId);
        return null;
    }

    private List<MetricDto> buildMetricDtos(MetricDataResult result) {
        List<Instant> timestamps = result.timestamps();
        List<Double> values = result.values();
        if (timestamps == null || values == null || timestamps.size() != values.size()) {
            return Collections.emptyList();
        }
        return IntStream.range(0, timestamps.size()).mapToObj(i -> new MetricDto(timestamps.get(i), values.get(i))).collect(Collectors.toList());
    }

    private GetMetricDataRequest buildMetricDataRequest(String resourceId, String metricName, String namespace, String dimensionName) {
        Metric metric = Metric.builder().namespace(namespace).metricName(metricName).dimensions(Dimension.builder().name(dimensionName).value(resourceId).build()).build();
        MetricStat metricStat = MetricStat.builder().metric(metric).period(300).stat("Average").build();
        MetricDataQuery metricDataQuery = MetricDataQuery.builder().id(metricName.toLowerCase().replace(" ", "").replace("/", "")).metricStat(metricStat).returnData(true).build();
        return GetMetricDataRequest.builder().startTime(Instant.now().minus(1, ChronoUnit.DAYS)).endTime(Instant.now()).metricDataQueries(metricDataQuery).scanBy(ScanBy.TIMESTAMP_DESCENDING).build();
    }
}