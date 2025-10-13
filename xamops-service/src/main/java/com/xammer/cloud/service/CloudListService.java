package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.dto.ServicePaginatedResponse;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.amplify.AmplifyClient;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.GetRestApisRequest;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.ListWorkGroupsRequest;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.ListProvisionedModelThroughputsRequest;
import software.amazon.awssdk.services.codebuild.CodeBuildClient;
import software.amazon.awssdk.services.codebuild.model.ListProjectsRequest;
import software.amazon.awssdk.services.codecommit.CodeCommitClient;
import software.amazon.awssdk.services.codecommit.model.ListRepositoriesRequest;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.codepipeline.model.ListPipelinesRequest;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.ListDistributionsRequest;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUserPoolsRequest;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.config.model.DescribeConfigRulesRequest;
import software.amazon.awssdk.services.controltower.ControlTowerClient;
import software.amazon.awssdk.services.datazone.DataZoneClient;
import software.amazon.awssdk.services.datazone.model.ListDomainsRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.GroupIdentifier;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.efs.EfsClient;
import software.amazon.awssdk.services.efs.model.DescribeFileSystemsRequest;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.elasticbeanstalk.ElasticBeanstalkClient;
import software.amazon.awssdk.services.elasticbeanstalk.model.DescribeEnvironmentsRequest;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.ListEventBusesRequest;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.GetDatabasesRequest;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.ListStreamsRequest;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.ListKeysRequest;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lightsail.LightsailClient;
import software.amazon.awssdk.services.pinpoint.PinpointClient;
import software.amazon.awssdk.services.pinpoint.model.GetAppsRequest;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;
import software.amazon.awssdk.services.sagemaker.model.ListNotebookInstancesRequest;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.securityhub.model.GetFindingsRequest;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.ListStateMachinesRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationRequest;
import software.amazon.awssdk.services.wafv2.Wafv2Client;
import software.amazon.awssdk.services.wafv2.model.ListWebAcLsRequest;

import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CloudListService {

    private static final Logger logger = LoggerFactory.getLogger(CloudListService.class);

    private final String configuredRegion;
    private final CloudAccountRepository cloudAccountRepository;
    public final AwsClientProvider awsClientProvider;
    private final Map<String, double[]> regionCoordinates = new HashMap<>();
    private static final Set<String> SUSTAINABLE_REGIONS = Set.of("eu-west-1", "eu-north-1", "ca-central-1", "us-west-2");
    private static final Set<String> LIGHTSAIL_REGIONS = Set.of(
            "us-east-1", "us-east-2", "us-west-2", "ap-south-1", "ap-northeast-1",
            "ap-northeast-2", "ap-southeast-1", "ap-southeast-2", "ca-central-1",
            "eu-central-1", "eu-west-1", "eu-west-2", "eu-west-3", "eu-north-1"
    );
    private static final Set<String> PINPOINT_REGIONS = Set.of(
            "us-east-1", "us-east-2", "us-west-2", "ap-south-1", "ap-northeast-1",
            "ap-northeast-2", "ap-southeast-1", "ap-southeast-2", "ca-central-1",
            "eu-central-1", "eu-west-1", "eu-west-2"
    );

    @Autowired
    private RedisCacheService redisCache;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    public CloudListService(
            CloudAccountRepository cloudAccountRepository,
            AwsClientProvider awsClientProvider
    ) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.configuredRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        loadRegionCoordinates();
    }

    CloudAccount getAccount(String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }
        return accounts.get(0);
    }

    private void loadRegionCoordinates() {
        ObjectMapper mapper = new ObjectMapper();
        try {
            URL url = new URL("https://raw.githubusercontent.com/sunshower-io/provider-lists/master/aws/output/regions.json");
            JsonNode root = mapper.readTree(url);
            JsonNode regionsNode = root.get("regions");
            if (regionsNode != null && regionsNode.isArray()) {
                for (JsonNode regionNode : regionsNode) {
                    String regionKey = regionNode.path("key").asText();
                    JsonNode coords = regionNode.path("coordinates");
                    if (!regionKey.isEmpty() && coords.isObject()) {
                        double latitude = coords.path("latitude").asDouble();
                        double longitude = coords.path("longitude").asDouble();
                        this.regionCoordinates.put(regionKey, new double[]{latitude, longitude});
                    }
                }
                logger.info("Successfully loaded {} region coordinates from external source.", this.regionCoordinates.size());
            }
        } catch (IOException e) {
            logger.error("Failed to load region coordinates from external source. Map data will be unavailable.", e);
        }
    }

    @Async("awsTaskExecutor")
    public void triggerGetResourcesAsync(List<String> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            logger.warn("triggerGetResourcesAsync called with no account IDs.");
            return;
        }
        String accountIdToUse = accountIds.get(0);
        logger.info("Triggering asynchronous resource refresh for account: {}", accountIdToUse);

        getAllResourcesGrouped(accountIdToUse, true)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        logger.error("Asynchronous resource refresh failed for account {}", accountIdToUse, ex);
                        messagingTemplate.convertAndSend("/topic/cloudlist-refresh", Map.of("status", "refresh-failed", "accountId", accountIdToUse));
                    } else {
                        logger.info("Asynchronous resource refresh completed successfully for account {}", accountIdToUse);
                        messagingTemplate.convertAndSend("/topic/cloudlist-refresh", Map.of("status", "refresh-complete", "accountId", accountIdToUse));
                    }
                });
    }



    /**
 * NEW METHOD: Get services with pagination - returns 5 services at a time with all their resources
 * Services are sorted alphabetically before pagination
 */
@Async("awsTaskExecutor")
public CompletableFuture<ServicePaginatedResponse> getAllServicesGroupedPaginated(
        String accountId,
        int page,
        int servicesPerPage,
        boolean forceRefresh) {

    logger.info("Fetching paginated services for account: {}, page: {}, servicesPerPage: {}",
                accountId, page, servicesPerPage);

    // First get all grouped services (this will use cache if available)
    return getAllResourcesGrouped(accountId, forceRefresh).thenApply(allServiceGroups -> {

        // Sort services alphabetically by service type
        List<DashboardData.ServiceGroupDto> sortedServices = allServiceGroups.stream()
                .sorted(Comparator.comparing(DashboardData.ServiceGroupDto::getServiceType))
                .collect(Collectors.toList());

        int totalServices = sortedServices.size();
        int totalPages = (int) Math.ceil((double) totalServices / servicesPerPage);

        // Validate page number
        int pageNumber = page;
        if (pageNumber < 0) {
            pageNumber = 0;
        }
        if (pageNumber >= totalPages && totalPages > 0) {
            pageNumber = totalPages - 1;
        }

        // Calculate pagination bounds
        int startIndex = pageNumber * servicesPerPage;
        int endIndex = Math.min(startIndex + servicesPerPage, totalServices);

        // Get the services for this page
        List<DashboardData.ServiceGroupDto> pageServices;
        if (startIndex >= totalServices) {
            pageServices = Collections.emptyList();
        } else {
            pageServices = sortedServices.subList(startIndex, endIndex);
        }

        // Convert DashboardData.ServiceGroupDto to ServicePaginatedResponse.ServiceGroupDto
        List<ServicePaginatedResponse.ServiceGroupDto> responseServices = pageServices.stream()
                .map(sg -> new ServicePaginatedResponse.ServiceGroupDto(
                        sg.getServiceType(),
                        sg.getResources()))
                .collect(Collectors.toList());

        // Build response
        ServicePaginatedResponse response = new ServicePaginatedResponse(
                responseServices,
                pageNumber,
                totalServices,
                totalPages,
                servicesPerPage,
                pageNumber < totalPages - 1,  // hasNext
                pageNumber > 0                 // hasPrevious
        );

        logger.info("Returning page {} of {} (services {}-{} of {})",
                    pageNumber, totalPages, startIndex + 1, endIndex, totalServices);

        return response;
    });
}


/**
     * **NEW PAGINATION METHOD**
     * Fetches all resources and then applies pagination to the final list.
     */
    @Async("awsTaskExecutor")
    public CompletableFuture<Page<ResourceDto>> getAllResourcesPaginated(CloudAccount account, boolean forceRefresh, int page, int size) {
        return getAllResources(account, forceRefresh).thenApply(allResources -> {
            PageRequest pageRequest = PageRequest.of(page, size);
            int start = (int) pageRequest.getOffset();
            int end = Math.min((start + pageRequest.getPageSize()), allResources.size());

            List<ResourceDto> pageContent = (start > allResources.size())
                    ? Collections.emptyList()
                    : allResources.subList(start, end);

            return (Page<ResourceDto>) new PageImpl<>(pageContent, pageRequest, allResources.size());

        }).exceptionally(ex -> {
            logger.error("Failed to fetch paginated resources for account {}", account.getAwsAccountId(), ex);
            // **THE FIX: Return a correctly typed empty Page on failure**
            return new PageImpl<ResourceDto>(Collections.emptyList(), PageRequest.of(page, size), 0);
        });
    }


    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.ServiceGroupDto>> getAllResourcesGrouped(String accountId, boolean forceRefresh) {
        String cacheKey = "groupedCloudlistResources-" + accountId;
        if (!forceRefresh) {
            Optional<List<DashboardData.ServiceGroupDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        CloudAccount account = getAccount(accountId);
        return getAllResources(account, forceRefresh).thenApply(flatList -> {
            List<DashboardData.ServiceGroupDto> groupedList = flatList.stream()
                    .filter(r -> r.getType() != null)
                    .collect(Collectors.groupingBy(ResourceDto::getType))
                    .entrySet().stream()
                    .map(e -> new DashboardData.ServiceGroupDto(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparing(DashboardData.ServiceGroupDto::getServiceType))
                    .collect(Collectors.toList());
            logger.debug("Grouped Cloudlist resources into {} service groups for account {}", groupedList.size(), accountId);
            redisCache.put(cacheKey, groupedList);
            return groupedList;
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<ResourceDto>> getAllResources(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "cloudlistResources-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<ResourceDto>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        return getRegionStatusForAccount(account, forceRefresh).thenCompose(activeRegions -> {
            if (activeRegions == null || activeRegions.isEmpty()) {
                logger.warn("No active regions found for account {}. Skipping resource fetching.", account.getAwsAccountId());
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            logger.info("Fetching all resources for Cloudlist (flat list) for account {}...", account.getAwsAccountId());

            List<CompletableFuture<List<ResourceDto>>> resourceFutures = new ArrayList<>(List.of(
                    fetchEc2InstancesForCloudlist(account, activeRegions), fetchEbsVolumesForCloudlist(account, activeRegions),
                    fetchRdsInstancesForCloudlist(account, activeRegions), fetchLambdaFunctionsForCloudlist(account, activeRegions),
                    fetchVpcsForCloudlist(account, activeRegions), fetchSecurityGroupsForCloudlist(account, activeRegions),
                    fetchS3BucketsForCloudlist(account),
                    fetchLoadBalancersForCloudlist(account, activeRegions),
                    fetchAutoScalingGroupsForCloudlist(account, activeRegions), fetchElastiCacheClustersForCloudlist(account, activeRegions),
                    fetchDynamoDbTablesForCloudlist(account, activeRegions), fetchEcrRepositoriesForCloudlist(account, activeRegions),
                    fetchRoute53HostedZonesForCloudlist(account),
                    fetchCloudTrailsForCloudlist(account, activeRegions),
                    fetchAcmCertificatesForCloudlist(account, activeRegions), fetchCloudWatchLogGroupsForCloudlist(account, activeRegions),
                    fetchSnsTopicsForCloudlist(account, activeRegions), fetchSqsQueuesForCloudlist(account, activeRegions),
                    fetchEksClustersForCloudlist(account, activeRegions),
                    fetchLightsailInstancesForCloudlist(account, activeRegions),
                    fetchAmplifyAppsForCloudlist(account, activeRegions),
                    fetchEfsFileSystemsForCloudlist(account, activeRegions),
                    fetchSsmManagedInstancesForCloudlist(account, activeRegions),
                    fetchStepFunctionsForCloudlist(account, activeRegions),
                    fetchConfigRulesForCloudlist(account, activeRegions),
                    fetchSecurityHubFindingsForCloudlist(account, activeRegions),
                    fetchGlueDatabasesForCloudlist(account, activeRegions),
                    fetchAthenaWorkgroupsForCloudlist(account, activeRegions),
                    fetchCognitoUserPoolsForCloudlist(account, activeRegions),
                    fetchCloudFrontDistributionsForCloudlist(account),
                    fetchBedrockModelsForCloudlist(account, activeRegions),
                    fetchSageMakerNotebooksForCloudlist(account, activeRegions),
                    fetchKmsKeysForCloudlist(account, activeRegions),
                    fetchPinpointAppsForCloudlist(account, activeRegions),
                    fetchInternetGatewaysForCloudlist(account, activeRegions),
                    fetchNatGatewaysForCloudlist(account, activeRegions),
                    fetchSnapshotsForCloudlist(account, activeRegions),
                    fetchEnisForCloudlist(account, activeRegions),
                    fetchElasticIpsForCloudlist(account, activeRegions),
                    fetchApiGatewaysForCloudlist(account, activeRegions),
                    fetchElasticBeanstalkEnvironmentsForCloudlist(account, activeRegions),
                    fetchCodeCommitRepositoriesForCloudlist(account, activeRegions),
                    fetchCodeBuildProjectsForCloudlist(account, activeRegions),
                    fetchCodePipelinesForCloudlist(account, activeRegions),
                    fetchKinesisStreamsForCloudlist(account, activeRegions),
                    fetchEventBridgeBusesForCloudlist(account, activeRegions)
            ));

            List<CompletableFuture<List<ResourceDto>>> safeResourceFutures = resourceFutures.stream()
                    .map(future -> future.exceptionally(ex -> {
                        logger.error("A CloudList sub-task failed for account {}: {}", account.getAwsAccountId(), ex.getMessage());
                        return Collections.emptyList();
                    }))
                    .collect(Collectors.toList());

            return CompletableFuture.allOf(safeResourceFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        List<ResourceDto> allResources = safeResourceFutures.stream()
                                .map(CompletableFuture::join)
                                .flatMap(Collection::stream)
                                .collect(Collectors.toList());

                        logger.debug("Fetched a total of {} resources for Cloudlist for account {}", allResources.size(), account.getAwsAccountId());
                        redisCache.put(cacheKey, allResources);
                        return allResources;
                    });
        });
    }

    @Async("awsTaskExecutor")
    public CompletableFuture<List<DashboardData.RegionStatus>> getRegionStatusForAccount(CloudAccount account, boolean forceRefresh) {
        String cacheKey = "regionStatus-" + account.getAwsAccountId();
        if (!forceRefresh) {
            Optional<List<DashboardData.RegionStatus>> cachedData = redisCache.get(cacheKey, new TypeReference<>() {});
            if (cachedData.isPresent()) {
                return CompletableFuture.completedFuture(cachedData.get());
            }
        }

        logger.info("Fetching status for all available and active AWS regions for account {}...", account.getAwsAccountId());
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, configuredRegion);
        try {
            Set<String> s3Regions = new HashSet<>();
            S3Client s3 = awsClientProvider.getS3Client(account, "us-east-1");
            try {
                for (Bucket bucket : s3.listBuckets().buckets()) {
                    String bucketRegion = "us-east-1";
                    try {
                        bucketRegion = s3.getBucketLocation(req -> req.bucket(bucket.name())).locationConstraintAsString();
                        if (bucketRegion == null || bucketRegion.isEmpty()) {
                            bucketRegion = "us-east-1";
                        }
                    } catch (S3Exception e) {
                        String correctRegion = e.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("x-amz-bucket-region").orElse(null);
                        if (correctRegion != null) {
                            bucketRegion = correctRegion;
                        }
                    }
                    s3Regions.add(bucketRegion);
                }
            } catch (Exception e) {
                logger.error("Could not list S3 buckets to determine active regions", e);
            }

            List<Region> allRegions = ec2.describeRegions().regions();
            logger.debug("Found {} total regions available to the account {}. Now checking for activity.", allRegions.size(), account.getAwsAccountId());

            List<DashboardData.RegionStatus> regionStatuses = allRegions.parallelStream()
                    .filter(region -> !"not-opted-in".equals(region.optInStatus()))
                    .map(region -> {
                        software.amazon.awssdk.regions.Region sdkRegion = software.amazon.awssdk.regions.Region.of(region.regionName());
                        Ec2Client regionEc2 = awsClientProvider.getEc2Client(account, sdkRegion.id());
                        RdsClient regionRds = awsClientProvider.getRdsClient(account, sdkRegion.id());
                        LambdaClient regionLambda = awsClientProvider.getLambdaClient(account, sdkRegion.id());
                        EcsClient regionEcs = awsClientProvider.getEcsClient(account, sdkRegion.id());
                        if (isRegionActive(regionEc2, regionRds, regionLambda, regionEcs, sdkRegion) || s3Regions.contains(region.regionName())) {
                            return mapRegionToStatus(region);
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            logger.debug("Successfully fetched {} active region statuses for account {}", regionStatuses.size(), account.getAwsAccountId());
            redisCache.put(cacheKey, regionStatuses);
            return CompletableFuture.completedFuture(regionStatuses);

        } catch (Exception e) {
            logger.error("Could not fetch and process AWS regions for account {}", account.getAwsAccountId(), e);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
    }

    private boolean isRegionActive(Ec2Client ec2Client, RdsClient rdsClient, LambdaClient lambdaClient, EcsClient ecsClient, software.amazon.awssdk.regions.Region region) {
        logger.debug("Performing activity check for region: {}", region.id());
        try {
            if (ec2Client.describeInstances().hasReservations() && !ec2Client.describeInstances().reservations().isEmpty()) return true;
            if (ec2Client.describeVolumes().hasVolumes() && !ec2Client.describeVolumes().volumes().isEmpty()) return true;
            if (rdsClient.describeDBInstances().hasDbInstances() && !rdsClient.describeDBInstances().dbInstances().isEmpty()) return true;
            if (lambdaClient.listFunctions().hasFunctions() && !lambdaClient.listFunctions().functions().isEmpty()) return true;
            if (ecsClient.listClusters().hasClusterArns() && !ecsClient.listClusters().clusterArns().isEmpty()) return true;
        } catch (AwsServiceException | SdkClientException e) {
            logger.warn("Could not perform active check for region {}: {}. Assuming inactive.", region.id(), e.getMessage());
            return false;
        }
        logger.debug("No activity found in region: {}", region.id());
        return false;
    }

    private DashboardData.RegionStatus mapRegionToStatus(Region region) {
        if (!this.regionCoordinates.containsKey(region.regionName())) {
            logger.warn("No coordinates found for region {}. It will not be displayed on the map.", region.regionName());
            return null;
        }
        double[] geo = this.regionCoordinates.get(region.regionName());
        double lat = geo[0];
        double lon = geo[1];
        String status = "ACTIVE";
        if (SUSTAINABLE_REGIONS.contains(region.regionName())) {
            status = "SUSTAINABLE";
        }
        return new DashboardData.RegionStatus(region.regionName(), region.regionName(), status, lat, lon);
    }

    <T> CompletableFuture<List<T>> fetchAllRegionalResources(CloudAccount account, List<DashboardData.RegionStatus> activeRegions, Function<String, List<T>> fetchFunction, String serviceName) {
        if (activeRegions == null || activeRegions.isEmpty()) {
            logger.warn("activeRegions is null or empty for service {}. Returning empty list.", serviceName);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        List<CompletableFuture<List<T>>> futures = activeRegions.stream()
                .map(regionStatus -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return fetchFunction.apply(regionStatus.getRegionId());
                    }
                    catch (AwsServiceException e) {
                        logger.warn("Cloudlist sub-task failed for account {}: {} in region {}. AWS Error: {}", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e.awsErrorDetails().errorMessage());
                        return Collections.<T>emptyList();
                    }
                    catch (Exception e) {
                        logger.error("Cloudlist sub-task failed for account {}: {} in region {}.", account.getAwsAccountId(), serviceName, regionStatus.getRegionId(), e);
                        return Collections.<T>emptyList();
                    }
                }))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<T> allResources = futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(List::stream)
                            .collect(Collectors.toList());
                    logger.debug("Fetched a total of {} {} resources across all regions for account {}", allResources.size(), serviceName, account.getAwsAccountId());
                    return allResources;
                });
    }

    private CompletableFuture<List<ResourceDto>> fetchEbsVolumesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeVolumes().volumes().stream()
                    .map(v -> new ResourceDto(v.volumeId(), getTagName(v.tags(), "N/A"), "EBS Volume", v.availabilityZone().replaceAll(".$", ""), v.stateAsString(), v.createTime(), Map.of("Size", v.size() + " GiB", "Type", v.volumeTypeAsString(), "Attached to", v.attachments().isEmpty() ? "N/A" : v.attachments().get(0).instanceId())))
                    .collect(Collectors.toList());
        }, "EBS Volumes");
    }

    private CompletableFuture<List<ResourceDto>> fetchRdsInstancesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            RdsClient rds = awsClientProvider.getRdsClient(account, regionId);
            return rds.describeDBInstances().dbInstances().stream()
                    .map(i -> new ResourceDto(i.dbInstanceIdentifier(), i.dbInstanceIdentifier(), "RDS Instance", i.availabilityZone().replaceAll(".$", ""), i.dbInstanceStatus(), i.instanceCreateTime(), Map.of("Engine", i.engine() + " " + i.engineVersion(), "Class", i.dbInstanceClass(), "Multi-AZ", i.multiAZ().toString())))
                    .collect(Collectors.toList());
        }, "RDS Instances");
    }

    private CompletableFuture<List<ResourceDto>> fetchLambdaFunctionsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            LambdaClient lambda = awsClientProvider.getLambdaClient(account, regionId);
            return lambda.listFunctions().functions().stream()
                    .map(f -> {
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
                        Instant lastModified = ZonedDateTime.parse(f.lastModified(), formatter).toInstant();
                        return new ResourceDto(f.functionName(), f.functionName(), "Lambda Function", getRegionFromArn(f.functionArn()), "Active", lastModified, Map.of("Runtime", f.runtimeAsString(), "Memory", f.memorySize() + " MB", "Timeout", f.timeout() + "s"));
                    })
                    .collect(Collectors.toList());
        }, "Lambda Functions");
    }

    CompletableFuture<List<ResourceDto>> fetchVpcsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeVpcs().vpcs().stream()
                    .map(v -> {
                        long subnetCount = ec2.describeSubnets(req -> req.filters(f -> f.name("vpc-id").values(v.vpcId())))
                                .subnets().size();

                        Map<String, String> details = new HashMap<>();
                        details.put("CIDR Block", v.cidrBlock());
                        details.put("Is Default", v.isDefault().toString());
                        details.put("Subnet Count", String.valueOf(subnetCount));

                        return new ResourceDto(
                                v.vpcId(),
                                getTagName(v.tags(), v.vpcId()),
                                "VPC",
                                regionId,
                                v.stateAsString(),
                                null,
                                details
                        );
                    })
                    .collect(Collectors.toList());
        }, "VPCs");
    }

    private CompletableFuture<List<ResourceDto>> fetchSecurityGroupsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeSecurityGroups().securityGroups().stream()
                    .map(sg -> new ResourceDto(sg.groupId(), sg.groupName(), "Security Group", regionId, "Available", null, Map.of("VPC ID", sg.vpcId(), "Inbound Rules", String.valueOf(sg.ipPermissions().size()), "Outbound Rules", String.valueOf(sg.ipPermissionsEgress().size()))))
                    .collect(Collectors.toList());
        }, "Security Groups");
    }

    private CompletableFuture<List<ResourceDto>> fetchLoadBalancersForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, regionId);
            return elbv2.describeLoadBalancers().loadBalancers().stream()
                    .map(lb -> new ResourceDto(lb.loadBalancerName(), lb.loadBalancerName(), "Load Balancer", lb.availabilityZones().isEmpty() ? regionId : lb.availabilityZones().get(0).zoneName().replaceAll(".$", ""), lb.state().codeAsString(), lb.createdTime(), Map.of("Type", lb.typeAsString(), "Scheme", lb.schemeAsString(), "VPC ID", lb.vpcId())))
                    .collect(Collectors.toList());
        }, "Load Balancers");
    }

    private CompletableFuture<List<ResourceDto>> fetchAutoScalingGroupsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            AutoScalingClient asgClient = awsClientProvider.getAutoScalingClient(account, regionId);
            return asgClient.describeAutoScalingGroups().autoScalingGroups().stream()
                    .map(asg -> new ResourceDto(asg.autoScalingGroupName(), asg.autoScalingGroupName(), "Auto Scaling Group", asg.availabilityZones().isEmpty() ? regionId : asg.availabilityZones().get(0).replaceAll(".$", ""), "Active", asg.createdTime(), Map.of("Desired", asg.desiredCapacity().toString(), "Min", asg.minSize().toString(), "Max", asg.maxSize().toString())))
                    .collect(Collectors.toList());
        }, "Auto Scaling Groups");
    }

    private CompletableFuture<List<ResourceDto>> fetchElastiCacheClustersForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ElastiCacheClient elastiCache = awsClientProvider.getElastiCacheClient(account, regionId);
            return elastiCache.describeCacheClusters().cacheClusters().stream()
                    .map(c -> new ResourceDto(c.cacheClusterId(), c.cacheClusterId(), "ElastiCache Cluster", c.preferredAvailabilityZone().replaceAll(".$", ""), c.cacheClusterStatus(), c.cacheClusterCreateTime(), Map.of("Engine", c.engine() + " " + c.engineVersion(), "NodeType", c.cacheNodeType(), "Nodes", c.numCacheNodes().toString())))
                    .collect(Collectors.toList());
        }, "ElastiCache Clusters");
    }

    private CompletableFuture<List<ResourceDto>> fetchDynamoDbTablesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            DynamoDbClient ddb = awsClientProvider.getDynamoDbClient(account, regionId);
            return ddb.listTables().tableNames().stream()
                    .map(tableName -> {
                        var tableDesc = ddb.describeTable(b -> b.tableName(tableName)).table();
                        return new ResourceDto(tableName, tableName, "DynamoDB Table", getRegionFromArn(tableDesc.tableArn()), tableDesc.tableStatusAsString(), tableDesc.creationDateTime(), Map.of("Items", tableDesc.itemCount().toString(), "Size (Bytes)", tableDesc.tableSizeBytes().toString()));
                    })
                    .collect(Collectors.toList());
        }, "DynamoDB Tables");
    }

    private CompletableFuture<List<ResourceDto>> fetchEcrRepositoriesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EcrClient ecr = awsClientProvider.getEcrClient(account, regionId);
            return ecr.describeRepositories().repositories().stream()
                    .map(r -> new ResourceDto(r.repositoryName(), r.repositoryName(), "ECR Repository", getRegionFromArn(r.repositoryArn()), "Available", r.createdAt(), Map.of("URI", r.repositoryUri())))
                    .collect(Collectors.toList());
        }, "ECR Repositories");
    }

    private CompletableFuture<List<ResourceDto>> fetchSnsTopicsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            SnsClient sns = awsClientProvider.getSnsClient(account, regionId);
            return sns.listTopics().topics().stream()
                    .map(t -> new ResourceDto(t.topicArn(), t.topicArn().substring(t.topicArn().lastIndexOf(':') + 1), "SNS Topic", getRegionFromArn(t.topicArn()), "Active", null, Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "SNS Topics");
    }

    private CompletableFuture<List<ResourceDto>> fetchSqsQueuesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            SqsClient sqs = awsClientProvider.getSqsClient(account, regionId);
            return sqs.listQueues().queueUrls().stream()
                    .map(queueUrl -> {
                        String[] arnParts = sqs.getQueueAttributes(req -> req.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN).split(":");
                        return new ResourceDto(queueUrl, arnParts[5], "SQS Queue", arnParts[3], "Active", null, Collections.emptyMap());
                    })
                    .collect(Collectors.toList());
        }, "SQS Queues");
    }

    private CompletableFuture<List<ResourceDto>> fetchCloudWatchLogGroupsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CloudWatchLogsClient cwLogs = awsClientProvider.getCloudWatchLogsClient(account, regionId);
            return cwLogs.describeLogGroups().logGroups().stream()
                    .map(lg -> new ResourceDto(lg.arn(), lg.logGroupName(), "CloudWatch Log Group", getRegionFromArn(lg.arn()), "Active", Instant.ofEpochMilli(lg.creationTime()), Map.of("Retention (Days)", lg.retentionInDays() != null ? lg.retentionInDays().toString() : "Never Expire", "Stored Bytes", String.format("%,d", lg.storedBytes()))))
                    .collect(Collectors.toList());
        }, "CloudWatch Log Groups");
    }

    private CompletableFuture<List<ResourceDto>> fetchCloudTrailsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CloudTrailClient cloudTrail = awsClientProvider.getCloudTrailClient(account, regionId);
            return cloudTrail.describeTrails().trailList().stream()
                    .map(t -> new ResourceDto(t.trailARN(), t.name(), "CloudTrail", t.homeRegion(), "Active", null, Map.of("IsMultiRegion", t.isMultiRegionTrail().toString(), "S3Bucket", t.s3BucketName())))
                    .collect(Collectors.toList());
        }, "CloudTrails");
    }

    private CompletableFuture<List<ResourceDto>> fetchS3BucketsForCloudlist(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            logger.debug("Fetching S3 buckets for account {}", account.getAwsAccountId());
            S3Client s3GlobalClient = awsClientProvider.getS3Client(account, "us-east-1");
            try {
                List<Bucket> buckets = s3GlobalClient.listBuckets().buckets();
                logger.debug("Found {} S3 buckets to process for account {}", buckets.size(), account.getAwsAccountId());
                List<ResourceDto> resources = buckets.parallelStream().map(b -> {
                            String bucketRegion = null;
                            try {
                                String locationConstraint = s3GlobalClient.getBucketLocation(req -> req.bucket(b.name())).locationConstraintAsString();
                                bucketRegion = (locationConstraint == null || locationConstraint.isEmpty()) ? "us-east-1" : locationConstraint;
                            } catch (S3Exception e) {
                                bucketRegion = e.awsErrorDetails().sdkHttpResponse()
                                        .firstMatchingHeader("x-amz-bucket-region")
                                        .orElse(null);

                                if (bucketRegion == null) {
                                    String message = e.awsErrorDetails().errorMessage();
                                    if (message != null && message.contains("expecting")) {
                                        String[] parts = message.split("'");
                                        if (parts.length >= 4) {
                                            bucketRegion = parts[3];
                                        }
                                    }
                                }

                                if (bucketRegion == null) {
                                    logger.warn("Could not determine region for bucket {}. Skipping. Error: {}", b.name(), e.getMessage());
                                    return null;
                                }
                            } catch (Exception e) {
                                logger.warn("General error getting location for bucket {}. Skipping. Error: {}", b.name(), e.getMessage());
                                return null;
                            }

                            return new ResourceDto(b.name(), b.name(), "S3 Bucket", bucketRegion, "Available", b.creationDate(), Collections.emptyMap());
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                logger.debug("Successfully processed {} S3 buckets for account {}", resources.size(), account.getAwsAccountId());
                return resources;
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed for account {}: S3 Buckets.", account.getAwsAccountId(), e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchRoute53HostedZonesForCloudlist(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Route53Client r53 = awsClientProvider.getRoute53Client(account);
                List<ResourceDto> resources = r53.listHostedZones().hostedZones().stream()
                        .map(z -> new ResourceDto(z.id(), z.name(), "Route 53 Zone", "Global", "Available", null, Map.of("Type", z.config().privateZone() ? "Private" : "Public", "Record Count", z.resourceRecordSetCount().toString())))
                        .collect(Collectors.toList());
                logger.debug("Fetched {} Route 53 Hosted Zones for account {}", resources.size(), account.getAwsAccountId());
                return resources;
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed for account {}: Route 53 Zones.", account.getAwsAccountId(), e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchAcmCertificatesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            AcmClient acm = awsClientProvider.getAcmClient(account, regionId);
            return acm.listCertificates().certificateSummaryList().stream()
                    .map(c -> new ResourceDto(c.certificateArn(), c.domainName(), "Certificate Manager", regionId, c.statusAsString(), c.createdAt(), Map.of("Type", c.typeAsString(), "InUse", c.inUse().toString())))
                    .collect(Collectors.toList());
        }, "ACM Certificates");
    }

    private CompletableFuture<List<ResourceDto>> fetchEksClustersForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EksClient eks = awsClientProvider.getEksClient(account, regionId);
            return eks.listClusters().clusters().stream()
                    .map(clusterName -> {
                        var cluster = eks.describeCluster(b -> b.name(clusterName)).cluster();
                        return new ResourceDto(cluster.name(), cluster.name(), "EKS Cluster", getRegionFromArn(cluster.arn()), cluster.statusAsString(), cluster.createdAt(), Map.of("Version", cluster.version(), "Platform Version", cluster.platformVersion()));
                    })
                    .collect(Collectors.toList());
        }, "EKS Clusters");
    }

    private CompletableFuture<List<ResourceDto>> fetchLightsailInstancesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            if (!LIGHTSAIL_REGIONS.contains(regionId)) {
                return Collections.emptyList();
            }
            LightsailClient lightsail = awsClientProvider.getLightsailClient(account, regionId);
            return lightsail.getInstances().instances().stream()
                    .map(i -> new ResourceDto(i.arn(), i.name(), "Lightsail Instance", i.location().regionName().toString(), i.state().name(), i.createdAt(), Map.of("BlueprintId", i.blueprintId(), "BundleId", i.bundleId())))
                    .collect(Collectors.toList());
        }, "Lightsail Instances");
    }

    private CompletableFuture<List<ResourceDto>> fetchAmplifyAppsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            AmplifyClient amplify = awsClientProvider.getAmplifyClient(account, regionId);
            return amplify.listApps().apps().stream()
                    .map(a -> new ResourceDto(a.appArn(), a.name(), "Amplify App", getRegionFromArn(a.appArn()), "Available", a.createTime(), Map.of("Platform", a.platform().toString(), "DefaultDomain", a.defaultDomain())))
                    .collect(Collectors.toList());
        }, "Amplify Apps");
    }

    private String getRegionFromArn(String arn) {
        if (arn == null || arn.isBlank()) return "Unknown";
        try {
            String[] parts = arn.split(":");
            if (parts.length > 3) {
                String region = parts[3];
                return region.isEmpty() ? "Global" : region;
            }
            return "Global";
        } catch (Exception e) {
            logger.warn("Could not parse region from ARN: {}", arn);
            return this.configuredRegion;
        }
    }

    public String getTagName(List<Tag> tags, String defaultName) {
        if (tags == null || tags.isEmpty()) return defaultName;
        return tags.stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst().map(Tag::value).orElse(defaultName);
    }
    private CompletableFuture<List<ResourceDto>> fetchStepFunctionsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            SfnClient client = awsClientProvider.getSfnClient(account, regionId);
            return client.listStateMachines(ListStateMachinesRequest.builder().build()).stateMachines().stream()
                    .map(sm -> new ResourceDto(sm.stateMachineArn(), sm.name(), "AWS Step Functions", regionId, null, sm.creationDate(), Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "AWS Step Functions");
    }

    private CompletableFuture<List<ResourceDto>> fetchConfigRulesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ConfigClient client = awsClientProvider.getConfigClient(account, regionId);
            return client.describeConfigRules(DescribeConfigRulesRequest.builder().build()).configRules().stream()
                    .map(rule -> new ResourceDto(rule.configRuleArn(), rule.configRuleName(), "AWS Config", regionId, rule.configRuleStateAsString(), null, Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "AWS Config");
    }

    private CompletableFuture<List<ResourceDto>> fetchSecurityHubFindingsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            SecurityHubClient client = awsClientProvider.getSecurityHubClient(account, regionId);
            return client.getFindings(GetFindingsRequest.builder().build()).findings().stream()
                    .map(finding -> new ResourceDto(finding.id(), finding.title(), "Security Hub", regionId, null, null, Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "Security Hub");
    }

    private CompletableFuture<List<ResourceDto>> fetchGlueDatabasesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            GlueClient client = awsClientProvider.getGlueClient(account, regionId);
            return client.getDatabases(GetDatabasesRequest.builder().build()).databaseList().stream()
                    .map(db -> new ResourceDto(db.name(), db.name(), "AWS Glue", regionId, null, db.createTime(), Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "AWS Glue");
    }

    private CompletableFuture<List<ResourceDto>> fetchAthenaWorkgroupsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            AthenaClient client = awsClientProvider.getAthenaClient(account, regionId);
            return client.listWorkGroups(ListWorkGroupsRequest.builder().build()).workGroups().stream()
                    .filter(wg -> !wg.name().equals("primary"))
                    .map(wg -> new ResourceDto(wg.name(), wg.name(), "Amazon Athena", regionId, wg.stateAsString(), null, Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "Amazon Athena");
    }

    private CompletableFuture<List<ResourceDto>> fetchCognitoUserPoolsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CognitoIdentityProviderClient client = awsClientProvider.getCognitoIdentityProviderClient(account, regionId);
            return client.listUserPools(ListUserPoolsRequest.builder().maxResults(10).build()).userPools().stream()
                    .map(pool -> new ResourceDto(pool.id(), pool.name(), "Amazon Cognito", regionId, null, pool.creationDate(), Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "Amazon Cognito");
    }

    private CompletableFuture<List<ResourceDto>> fetchWafWebAclsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Wafv2Client client = awsClientProvider.getWafv2Client(account, regionId);
            return client.listWebACLs(ListWebAcLsRequest.builder().scope(software.amazon.awssdk.services.wafv2.model.Scope.REGIONAL).build()).webACLs().stream()
                    .map(acl -> new ResourceDto(acl.arn(), acl.name(), "WAF", regionId, null, null, Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "WAF");
    }

    private CompletableFuture<List<ResourceDto>> fetchCloudFrontDistributionsForCloudlist(CloudAccount account) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CloudFrontClient client = awsClientProvider.getCloudFrontClient(account);
                return client.listDistributions(ListDistributionsRequest.builder().build()).distributionList().items().stream()
                        .map(dist -> new ResourceDto(dist.id(), dist.domainName(), "CloudFront", "Global", dist.status(), dist.lastModifiedTime(), Collections.emptyMap()))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                logger.error("Cloudlist sub-task failed for account {}: CloudFront Distributions.", account.getAwsAccountId(), e);
                return Collections.emptyList();
            }
        });
    }

    private CompletableFuture<List<ResourceDto>> fetchBedrockModelsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
    return fetchAllRegionalResources(account, activeRegions, regionId -> {
        try {
            BedrockClient client = awsClientProvider.getBedrockClient(account, regionId);

            // This is the correct and final implementation
            return client.listProvisionedModelThroughputs(ListProvisionedModelThroughputsRequest.builder().build())
                    .provisionedModelSummaries().stream() // The method is provisionedModelSummaries()
                    .map(model -> new ResourceDto(
                            model.provisionedModelArn(),      // Correct method: provisionedModelArn()
                            model.provisionedModelName(),     // Correct method: provisionedModelName()
                            "Bedrock Provisioned Model",
                            regionId,
                            model.statusAsString(),           // Correct method: statusAsString()
                            model.creationTime(),             // Correct method: creationTime()
                            Collections.emptyMap()
                    ))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("Could not fetch Bedrock provisioned models in region {}: {}. This may be a permissions issue or the service may not be enabled.", regionId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }, "Bedrock");
}
    private CompletableFuture<List<ResourceDto>> fetchSageMakerNotebooksForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            SageMakerClient client = awsClientProvider.getSageMakerClient(account, regionId);
            return client.listNotebookInstances(ListNotebookInstancesRequest.builder().build()).notebookInstances().stream()
                    .map(instance -> new ResourceDto(instance.notebookInstanceArn(), instance.notebookInstanceName(), "SageMaker", regionId, instance.notebookInstanceStatusAsString(), instance.creationTime(), Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "SageMaker");
    }

    private CompletableFuture<List<ResourceDto>> fetchKmsKeysForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            KmsClient client = awsClientProvider.getKmsClient(account, regionId);
            return client.listKeys(ListKeysRequest.builder().build()).keys().stream()
                    .map(key -> new ResourceDto(key.keyId(), key.keyArn(), "KMS", regionId, null, null, Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "KMS");
    }
    private CompletableFuture<List<ResourceDto>> fetchEfsFileSystemsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EfsClient client = awsClientProvider.getEfsClient(account, regionId);
            return client.describeFileSystems(DescribeFileSystemsRequest.builder().build()).fileSystems().stream()
                    .map(fs -> new ResourceDto(fs.fileSystemId(), fs.name(), "EFS File System", regionId, fs.lifeCycleState().toString(), fs.creationTime(), Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "EFS File Systems");
    }

    private CompletableFuture<List<ResourceDto>> fetchSsmManagedInstancesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            SsmClient client = awsClientProvider.getSsmClient(account, regionId);
            return client.describeInstanceInformation(DescribeInstanceInformationRequest.builder().build()).instanceInformationList().stream()
                    .map(i -> new ResourceDto(i.instanceId(), i.name(), "SSM Managed Instance", regionId, i.pingStatus().toString(), i.registrationDate(), Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "SSM Managed Instances");
    }

    private CompletableFuture<List<ResourceDto>> fetchPinpointAppsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            if (!PINPOINT_REGIONS.contains(regionId)) {
                return Collections.emptyList();
            }
            PinpointClient pinpointClient = awsClientProvider.getPinpointClient(account, regionId);
            return pinpointClient.getApps(GetAppsRequest.builder().build()).applicationsResponse().item().stream()
                    .map(app -> new ResourceDto(app.id(), app.name(), "Pinpoint Application", regionId, "Active", null, Collections.emptyMap()))
                    .collect(Collectors.toList());
        }, "Pinpoint Applications");
    }
    private CompletableFuture<List<ResourceDto>> fetchEc2InstancesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeInstances().reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .map(i -> {
                        String securityGroups = i.securityGroups().stream()
                                .map(GroupIdentifier::groupId)
                                .collect(Collectors.joining(", "));

                        Map<String, String> details = new HashMap<>();
                        details.put("Type", i.instanceTypeAsString());
                        details.put("Image ID", i.imageId());
                        details.put("VPC ID", i.vpcId());
                        details.put("Subnet ID", i.subnetId());
                        details.put("Private IP", i.privateIpAddress());
                        details.put("Security Groups", securityGroups);

                        return new ResourceDto(
                                i.instanceId(),
                                getTagName(i.tags(), i.instanceId()),
                                "EC2 Instance",
                                regionId,
                                i.state().nameAsString(),
                                i.launchTime(),
                                details
                        );
                    })
                    .collect(Collectors.toList());
        }, "EC2 Instances");
    }
    private CompletableFuture<List<ResourceDto>> fetchInternetGatewaysForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeInternetGateways().internetGateways().stream()
                    .map(igw -> {
                        String vpcId = igw.attachments().isEmpty() ? "Detached" : igw.attachments().get(0).vpcId();
                        return new ResourceDto(igw.internetGatewayId(), getTagName(igw.tags(), igw.internetGatewayId()), "Internet Gateway", regionId, "available", null, Map.of("VPC ID", vpcId));
                    })
                    .collect(Collectors.toList());
        }, "Internet Gateways");
    }

    private CompletableFuture<List<ResourceDto>> fetchNatGatewaysForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeNatGateways().natGateways().stream()
                    .map(nat -> new ResourceDto(nat.natGatewayId(), getTagName(nat.tags(), nat.natGatewayId()), "NAT Gateway", regionId, nat.stateAsString(), nat.createTime(), Map.of("VPC ID", nat.vpcId(), "Subnet ID", nat.subnetId(), "Private IP", nat.natGatewayAddresses().get(0).privateIp())))
                    .collect(Collectors.toList());
        }, "NAT Gateways");
    }
    private CompletableFuture<List<ResourceDto>> fetchSnapshotsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeSnapshots(req -> req.ownerIds(account.getAwsAccountId())).snapshots().stream()
                    .map(s -> new ResourceDto(
                            s.snapshotId(),
                            getTagName(s.tags(), s.snapshotId()),
                            "EBS Snapshot",
                            regionId,
                            s.stateAsString(),
                            s.startTime(),
                            Map.of(
                                    "Volume ID", s.volumeId() != null ? s.volumeId() : "N/A",
                                    "Volume Size", s.volumeSize() + " GiB",
                                    "Description", s.description() != null ? s.description() : "N/A"
                            )
                    ))
                    .collect(Collectors.toList());
        }, "EBS Snapshots");
    }

    private CompletableFuture<List<ResourceDto>> fetchEnisForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeNetworkInterfaces().networkInterfaces().stream()
                    .map(eni -> {
                        String location = eni.availabilityZone() != null ? eni.availabilityZone() : regionId;
                        String attachedTo = "Detached";
                        if (eni.attachment() != null && eni.attachment().instanceId() != null) {
                            attachedTo = eni.attachment().instanceId();
                        }
                        return new ResourceDto(
                                eni.networkInterfaceId(),
                                getTagName(eni.tagSet(), eni.networkInterfaceId()),
                                "Network Interface (ENI)",
                                location,
                                eni.statusAsString(),
                                null,
                                Map.of(
                                        "Subnet ID", eni.subnetId() != null ? eni.subnetId() : "N/A",
                                        "VPC ID", eni.vpcId() != null ? eni.vpcId() : "N/A",
                                        "Private IP", eni.privateIpAddress() != null ? eni.privateIpAddress() : "N/A",
                                        "Attached To", attachedTo
                                )
                        );
                    })
                    .collect(Collectors.toList());
        }, "Network Interfaces");
    }

    private CompletableFuture<List<ResourceDto>> fetchElasticIpsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, regionId);
            return ec2.describeAddresses().addresses().stream()
                    .map(eip -> new ResourceDto(
                            eip.allocationId(),
                            getTagName(eip.tags(), eip.publicIp()),
                            "Elastic IP",
                            regionId,
                            eip.associationId() != null ? "Associated" : "Unassociated",
                            null,
                            Map.of(
                                    "Public IP", eip.publicIp(),
                                    "Private IP", eip.privateIpAddress() != null ? eip.privateIpAddress() : "N/A",
                                    "Associated Instance", eip.instanceId() != null ? eip.instanceId() : "N/A"
                            )
                    ))
                    .collect(Collectors.toList());
        }, "Elastic IPs");
    }
    private CompletableFuture<List<ResourceDto>> fetchApiGatewaysForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ApiGatewayClient apiGatewayClient = awsClientProvider.getApiGatewayClient(account, regionId);
            return apiGatewayClient.getRestApis(GetRestApisRequest.builder().build()).items().stream()
                    .map(api -> new ResourceDto(
                            api.id(),
                            api.name(),
                            "API Gateway",
                            regionId,
                            "Active",
                            api.createdDate(),
                            Collections.emptyMap()
                    ))
                    .collect(Collectors.toList());
        }, "API Gateways");
    }

    private CompletableFuture<List<ResourceDto>> fetchElasticBeanstalkEnvironmentsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            ElasticBeanstalkClient ebClient = awsClientProvider.getElasticBeanstalkClient(account, regionId);
            return ebClient.describeEnvironments(DescribeEnvironmentsRequest.builder().build()).environments().stream()
                    .map(env -> new ResourceDto(
                            env.environmentId(),
                            env.environmentName(),
                            "Elastic Beanstalk Environment",
                            regionId,
                            env.status().toString(),
                            env.dateCreated(),
                            Map.of("Application Name", env.applicationName())
                    ))
                    .collect(Collectors.toList());
        }, "Elastic Beanstalk Environments");
    }

    private CompletableFuture<List<ResourceDto>> fetchCodeCommitRepositoriesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CodeCommitClient codeCommitClient = awsClientProvider.getCodeCommitClient(account, regionId);
            return codeCommitClient.listRepositories(ListRepositoriesRequest.builder().build()).repositories().stream()
                    .map(repo -> new ResourceDto(
                            repo.repositoryId(),
                            repo.repositoryName(),
                            "CodeCommit Repository",
                            regionId,
                            "Available",
                            null,
                            Collections.emptyMap()
                    ))
                    .collect(Collectors.toList());
        }, "CodeCommit Repositories");
    }

    private CompletableFuture<List<ResourceDto>> fetchCodeBuildProjectsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CodeBuildClient codeBuildClient = awsClientProvider.getCodeBuildClient(account, regionId);
            return codeBuildClient.listProjects(ListProjectsRequest.builder().build()).projects().stream()
                    .map(projectName -> new ResourceDto(
                            projectName,
                            projectName,
                            "CodeBuild Project",
                            regionId,
                            "Available",
                            null,
                            Collections.emptyMap()
                    ))
                    .collect(Collectors.toList());
        }, "CodeBuild Projects");
    }

    private CompletableFuture<List<ResourceDto>> fetchCodePipelinesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            CodePipelineClient codePipelineClient = awsClientProvider.getCodePipelineClient(account, regionId);
            return codePipelineClient.listPipelines(ListPipelinesRequest.builder().build()).pipelines().stream()
                    .map(pipeline -> new ResourceDto(
                            pipeline.name(),
                            pipeline.name(),
                            "CodePipeline",
                            regionId,
                            "Active",
                            pipeline.created(),
                            Collections.emptyMap()
                    ))
                    .collect(Collectors.toList());
        }, "CodePipelines");
    }


    //    private CompletableFuture<List<ResourceDto>> fetchControlTowerControlsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
//        return fetchAllRegionalResources(account, activeRegions, regionId -> {
//            try {
//                ControlTowerClient ctClient = awsClientProvider.getControlTowerClient(account, regionId);
//                return ctClient.listEnabledControls(ListEnabledControlsRequest.builder().build()).enabledControls().stream()
//                        .map(control -> new ResourceDto(
//                                control.controlIdentifier(),
//                                control.controlIdentifier(),
//                                "Control Tower Control",
//                                regionId,
//                                "Enabled",
//                                null,
//                                Collections.emptyMap()
//                        ))
//                        .collect(Collectors.toList());
//            } catch (software.amazon.awssdk.services.controltower.model.AccessDeniedException e) {
//                // FIX: Gracefully handle when IAM permissions are missing
//                logger.warn("Could not fetch Control Tower controls in region {}: {}", regionId, e.getMessage());
//                return Collections.emptyList();
//            }
//        }, "Control Tower Controls");
//    }

//    private CompletableFuture<List<ResourceDto>> fetchOrganizationAccountsForCloudlist(CloudAccount account) {
//        return CompletableFuture.supplyAsync(() -> {
//            try {
//                OrganizationsClient orgClient = awsClientProvider.getOrganizationsClient(account);
//                return orgClient.listAccounts(ListAccountsRequest.builder().build()).accounts().stream()
//                        .map(acc -> new ResourceDto(
//                                acc.id(),
//                                acc.name(),
//                                "Organization Account",
//                                "Global",
//                                acc.status().toString(),
//                                acc.joinedTimestamp(),
//                                Map.of("Email", acc.email())
//                        ))
//                        .collect(Collectors.toList());
//            } catch (Exception e) {
//                logger.error("Could not fetch Organization accounts for account {}. This account may not be part of an Organization.", account.getAwsAccountId(), e);
//                return Collections.emptyList();
//            }
//        });
//    }


//    private CompletableFuture<List<ResourceDto>> fetchShieldProtectionsForCloudlist(CloudAccount account) {
//        return CompletableFuture.supplyAsync(() -> {
//            try {
//                ShieldClient shieldClient = awsClientProvider.getShieldClient(account);
//                return shieldClient.listProtections(ListProtectionsRequest.builder().build()).protections().stream()
//                        .map(protection -> new ResourceDto(
//                                protection.id(),
//                                protection.name(),
//                                "AWS Shield Protection",
//                                "Global",
//                                "Enabled",
//                                null,
//                                Map.of("Resource ARN", protection.resourceArn())
//                        ))
//                        .collect(Collectors.toList());
//            } catch (software.amazon.awssdk.services.shield.model.ResourceNotFoundException e) {
//                // FIX: Gracefully handle when AWS Shield Advanced is not subscribed
//                logger.warn("Could not fetch Shield protections for account {}: {}", account.getAwsAccountId(), e.getMessage());
//                return Collections.emptyList();
//            } catch (Exception e) {
//                logger.error("Could not fetch Shield protections for account {}.", account.getAwsAccountId(), e);
//                return Collections.emptyList();
//            }
//        });
//    }

    private CompletableFuture<List<ResourceDto>> fetchKinesisStreamsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            KinesisClient kinesisClient = awsClientProvider.getKinesisClient(account, regionId);
            return kinesisClient.listStreams(ListStreamsRequest.builder().build()).streamNames().stream()
                    .map(streamName -> new ResourceDto(
                            streamName,
                            streamName,
                            "Kinesis Stream",
                            regionId,
                            "Active",
                            null,
                            Collections.emptyMap()
                    ))
                    .collect(Collectors.toList());
        }, "Kinesis Streams");
    }



    private CompletableFuture<List<ResourceDto>> fetchEventBridgeBusesForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            EventBridgeClient eventBridgeClient = awsClientProvider.getEventBridgeClient(account, regionId);
            return eventBridgeClient.listEventBuses(ListEventBusesRequest.builder().build()).eventBuses().stream()
                    .map(bus -> new ResourceDto(
                            bus.arn(),
                            bus.name(),
                            "EventBridge Bus",
                            getRegionFromArn(bus.arn()),
                            "Active",
                            null,
                            Collections.emptyMap()
                    ))
                    .collect(Collectors.toList());
        }, "EventBridge Buses");
    }
    private CompletableFuture<List<ResourceDto>> fetchDataZoneDomainsForCloudlist(CloudAccount account, List<DashboardData.RegionStatus> activeRegions) {
        return fetchAllRegionalResources(account, activeRegions, regionId -> {
            DataZoneClient dataZoneClient = awsClientProvider.getDataZoneClient(account, regionId);
            return dataZoneClient.listDomains(ListDomainsRequest.builder().build()).items().stream()
                    .map(domain -> new ResourceDto(
                            domain.id(),
                            domain.name(),
                            "DataZone Domain",
                            regionId,
                            domain.status().toString(),
                            domain.createdAt(),
                            Collections.emptyMap()
                    ))
                    .collect(Collectors.toList());
        }, "DataZone Domains");
    }
}