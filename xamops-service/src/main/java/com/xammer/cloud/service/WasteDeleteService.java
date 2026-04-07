package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DeleteLogGroupRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.DeleteRepositoryRequest;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.efs.EfsClient;
import software.amazon.awssdk.services.efs.model.DeleteFileSystemRequest;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.DeleteCacheClusterRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteLoadBalancerRequest;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DeleteDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.DeleteDbSnapshotRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;

import java.util.List;

/**
 * Service responsible for actually deleting wasted resources from AWS.
 * Only operates on accounts with accessType = "read-write".
 */
@Service
public class WasteDeleteService {

    private static final Logger logger = LoggerFactory.getLogger(WasteDeleteService.class);

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final DashboardDataService dashboardDataService;

    public WasteDeleteService(AwsClientProvider awsClientProvider,
                               CloudAccountRepository cloudAccountRepository,
                               DashboardDataService dashboardDataService) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.dashboardDataService = dashboardDataService;
    }

    /**
     * Deletes a wasted resource from AWS.
     *
     * @param accountId    AWS account ID (12-digit)
     * @param resourceId   The resource identifier (ID, ARN, or name depending on type)
     * @param resourceType The resource type string (e.g. "EBS Volume", "ECR Repository")
     * @param region       AWS region (e.g. "ap-south-1")
     * @throws IllegalArgumentException if account not found or not read-write
     * @throws RuntimeException         if the AWS delete call fails
     */
    public void deleteResource(String accountId, String resourceId, String resourceType, String region) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }
        CloudAccount account = accounts.get(0);

        if (!"read-write".equals(account.getAccessType())) {
            throw new IllegalArgumentException(
                    "Account " + accountId + " does not have read-write access. Delete is not permitted.");
        }

        logger.info("[WasteDelete] Deleting {} '{}' in region {} for account {}",
                resourceType, resourceId, region, accountId);

        try {
            performDelete(account, resourceId, resourceType, region);
        } catch (Exception e) {
            logger.error("[WasteDelete] Failed to delete {} '{}': {}", resourceType, resourceId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete " + resourceType + " '" + resourceId + "': " + e.getMessage(), e);
        }

        // Invalidate backend waste cache so the next full reload reflects the deletion
        String cacheKey = "waste-" + accountId;
        dashboardDataService.deleteSnapshot(cacheKey);
        logger.info("[WasteDelete] Invalidated waste cache for account {}", accountId);
    }

    private void performDelete(CloudAccount account, String resourceId, String resourceType, String region) {
        switch (resourceType) {
            case "EBS Volume":
                deleteEbsVolume(account, resourceId, region);
                break;
            case "EC2 Instance":
                terminateEc2Instance(account, resourceId, region);
                break;
            case "Elastic IP":
                releaseElasticIp(account, resourceId, region);
                break;
            case "Snapshot":
                deleteSnapshot(account, resourceId, region);
                break;
            case "AMI":
                deregisterAmi(account, resourceId, region);
                break;
            case "Security Group":
                deleteSecurityGroup(account, resourceId, region);
                break;
            case "ENI":
                deleteNetworkInterface(account, resourceId, region);
                break;
            case "Load Balancer":
                deleteLoadBalancer(account, resourceId, region);
                break;
            case "ECR Repository":
                deleteEcrRepository(account, resourceId, region);
                break;
            case "Lambda":
                deleteLambdaFunction(account, resourceId, region);
                break;
            case "RDS Instance":
                deleteRdsInstance(account, resourceId, region);
                break;
            case "DB Snapshot":
                deleteDbSnapshot(account, resourceId, region);
                break;
            case "DynamoDB Table":
                deleteDynamoDbTable(account, resourceId, region);
                break;
            case "ElastiCache Cluster":
                deleteElastiCacheCluster(account, resourceId, region);
                break;
            case "S3 Bucket":
                deleteS3Bucket(account, resourceId, region);
                break;
            case "CloudWatch Log Group":
            case "Log Group":
                deleteLogGroup(account, resourceId, region);
                break;
            case "SNS Topic":
                deleteSnsTopics(account, resourceId, region);
                break;
            case "SQS Queue":
                deleteSqsQueue(account, resourceId, region);
                break;
            case "EFS File System":
                deleteEfsFileSystem(account, resourceId, region);
                break;
            case "EKS Cluster":
                deleteEksCluster(account, resourceId, region);
                break;
            case "ECS Cluster":
                deleteEcsCluster(account, resourceId, region);
                break;
            case "Certificate Manager":
                deleteAcmCertificate(account, resourceId, region);
                break;
            case "Auto Scaling Group":
                deleteAutoScalingGroup(account, resourceId, region);
                break;
            default:
                throw new IllegalArgumentException("Unsupported resource type for deletion: " + resourceType);
        }
    }

    // ─── EC2 / EBS ────────────────────────────────────────────────────────────

    private void deleteEbsVolume(CloudAccount account, String volumeId, String region) {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
        ec2.deleteVolume(DeleteVolumeRequest.builder().volumeId(volumeId).build());
        logger.info("[WasteDelete] Deleted EBS volume {}", volumeId);
    }

    private void terminateEc2Instance(CloudAccount account, String instanceId, String region) {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
        ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceId).build());
        logger.info("[WasteDelete] Terminated EC2 instance {}", instanceId);
    }

    private void releaseElasticIp(CloudAccount account, String resourceId, String region) {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
        // resourceId may be allocationId (eipalloc-...) or public IP — handle both
        if (resourceId.startsWith("eipalloc-")) {
            ec2.releaseAddress(ReleaseAddressRequest.builder().allocationId(resourceId).build());
        } else {
            // Look up allocation ID from public IP
            DescribeAddressesResponse resp = ec2.describeAddresses(
                    DescribeAddressesRequest.builder().publicIps(resourceId).build());
            if (!resp.addresses().isEmpty()) {
                String allocationId = resp.addresses().get(0).allocationId();
                ec2.releaseAddress(ReleaseAddressRequest.builder().allocationId(allocationId).build());
            }
        }
        logger.info("[WasteDelete] Released Elastic IP {}", resourceId);
    }

    private void deleteSnapshot(CloudAccount account, String snapshotId, String region) {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
        ec2.deleteSnapshot(DeleteSnapshotRequest.builder().snapshotId(snapshotId).build());
        logger.info("[WasteDelete] Deleted snapshot {}", snapshotId);
    }

    private void deregisterAmi(CloudAccount account, String imageId, String region) {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
        ec2.deregisterImage(DeregisterImageRequest.builder().imageId(imageId).build());
        logger.info("[WasteDelete] Deregistered AMI {}", imageId);
    }

    private void deleteSecurityGroup(CloudAccount account, String groupId, String region) {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
        ec2.deleteSecurityGroup(DeleteSecurityGroupRequest.builder().groupId(groupId).build());
        logger.info("[WasteDelete] Deleted security group {}", groupId);
    }

    private void deleteNetworkInterface(CloudAccount account, String eniId, String region) {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
        ec2.deleteNetworkInterface(DeleteNetworkInterfaceRequest.builder().networkInterfaceId(eniId).build());
        logger.info("[WasteDelete] Deleted ENI {}", eniId);
    }

    // ─── Load Balancer ────────────────────────────────────────────────────────

    private void deleteLoadBalancer(CloudAccount account, String lbArn, String region) {
        ElasticLoadBalancingV2Client elb = awsClientProvider.getElbv2Client(account, region);
        elb.deleteLoadBalancer(DeleteLoadBalancerRequest.builder().loadBalancerArn(lbArn).build());
        logger.info("[WasteDelete] Deleted load balancer {}", lbArn);
    }

    // ─── ECR ──────────────────────────────────────────────────────────────────

    private void deleteEcrRepository(CloudAccount account, String resourceId, String region) {
        // resourceId can be the full ARN or just the repository name
        String repoName = resourceId.contains("/") ? resourceId.substring(resourceId.lastIndexOf('/') + 1) : resourceId;
        EcrClient ecr = awsClientProvider.getEcrClient(account, region);
        ecr.deleteRepository(DeleteRepositoryRequest.builder()
                .repositoryName(repoName)
                .force(true) // force-delete even if images exist
                .build());
        logger.info("[WasteDelete] Deleted ECR repository {}", repoName);
    }

    // ─── Lambda ───────────────────────────────────────────────────────────────

    private void deleteLambdaFunction(CloudAccount account, String functionName, String region) {
        LambdaClient lambda = awsClientProvider.getLambdaClient(account, region);
        lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(functionName).build());
        logger.info("[WasteDelete] Deleted Lambda function {}", functionName);
    }

    // ─── RDS ──────────────────────────────────────────────────────────────────

    private void deleteRdsInstance(CloudAccount account, String dbInstanceId, String region) {
        RdsClient rds = awsClientProvider.getRdsClient(account, region);
        rds.deleteDBInstance(DeleteDbInstanceRequest.builder()
                .dbInstanceIdentifier(dbInstanceId)
                .skipFinalSnapshot(true)
                .deleteAutomatedBackups(true)
                .build());
        logger.info("[WasteDelete] Initiated deletion of RDS instance {}", dbInstanceId);
    }

    private void deleteDbSnapshot(CloudAccount account, String snapshotId, String region) {
        RdsClient rds = awsClientProvider.getRdsClient(account, region);
        rds.deleteDBSnapshot(DeleteDbSnapshotRequest.builder().dbSnapshotIdentifier(snapshotId).build());
        logger.info("[WasteDelete] Deleted DB snapshot {}", snapshotId);
    }

    // ─── DynamoDB ─────────────────────────────────────────────────────────────

    private void deleteDynamoDbTable(CloudAccount account, String tableName, String region) {
        DynamoDbClient dynamo = awsClientProvider.getDynamoDbClient(account, region);
        dynamo.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
        logger.info("[WasteDelete] Deleted DynamoDB table {}", tableName);
    }

    // ─── ElastiCache ──────────────────────────────────────────────────────────

    private void deleteElastiCacheCluster(CloudAccount account, String clusterId, String region) {
        ElastiCacheClient elasticache = awsClientProvider.getElastiCacheClient(account, region);
        elasticache.deleteCacheCluster(DeleteCacheClusterRequest.builder().cacheClusterId(clusterId).build());
        logger.info("[WasteDelete] Deleted ElastiCache cluster {}", clusterId);
    }

    // ─── S3 ───────────────────────────────────────────────────────────────────

    private void deleteS3Bucket(CloudAccount account, String bucketName, String region) {
        S3Client s3 = awsClientProvider.getS3Client(account, region);

        // Empty the bucket first (delete all objects)
        ListObjectsV2Request listReq = ListObjectsV2Request.builder().bucket(bucketName).build();
        ListObjectsV2Response listResp;
        do {
            listResp = s3.listObjectsV2(listReq);
            if (!listResp.contents().isEmpty()) {
                List<ObjectIdentifier> toDelete = listResp.contents().stream()
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .collect(java.util.stream.Collectors.toList());
                s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucketName)
                        .delete(Delete.builder().objects(toDelete).build())
                        .build());
            }
            listReq = listReq.toBuilder().continuationToken(listResp.nextContinuationToken()).build();
        } while (Boolean.TRUE.equals(listResp.isTruncated()));

        // Delete all object versions and delete markers
        ListObjectVersionsRequest versionReq = ListObjectVersionsRequest.builder().bucket(bucketName).build();
        ListObjectVersionsResponse versionResp;
        do {
            versionResp = s3.listObjectVersions(versionReq);
            List<ObjectIdentifier> toDelete = new java.util.ArrayList<>();
            versionResp.versions().forEach(v ->
                    toDelete.add(ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build()));
            versionResp.deleteMarkers().forEach(m ->
                    toDelete.add(ObjectIdentifier.builder().key(m.key()).versionId(m.versionId()).build()));
            if (!toDelete.isEmpty()) {
                s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucketName)
                        .delete(Delete.builder().objects(toDelete).build())
                        .build());
            }
            versionReq = versionReq.toBuilder()
                    .keyMarker(versionResp.nextKeyMarker())
                    .versionIdMarker(versionResp.nextVersionIdMarker())
                    .build();
        } while (Boolean.TRUE.equals(versionResp.isTruncated()));

        s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());
        logger.info("[WasteDelete] Deleted S3 bucket {}", bucketName);
    }

    // ─── CloudWatch Logs ──────────────────────────────────────────────────────

    private void deleteLogGroup(CloudAccount account, String logGroupName, String region) {
        CloudWatchLogsClient cwl = awsClientProvider.getCloudWatchLogsClient(account, region);
        cwl.deleteLogGroup(DeleteLogGroupRequest.builder().logGroupName(logGroupName).build());
        logger.info("[WasteDelete] Deleted log group {}", logGroupName);
    }

    // ─── SNS ──────────────────────────────────────────────────────────────────

    private void deleteSnsTopics(CloudAccount account, String topicArn, String region) {
        SnsClient sns = awsClientProvider.getSnsClient(account, region);
        sns.deleteTopic(DeleteTopicRequest.builder().topicArn(topicArn).build());
        logger.info("[WasteDelete] Deleted SNS topic {}", topicArn);
    }

    // ─── SQS ──────────────────────────────────────────────────────────────────

    private void deleteSqsQueue(CloudAccount account, String resourceId, String region) {
        SqsClient sqs = awsClientProvider.getSqsClient(account, region);
        // resourceId may be a queue URL or queue name — get URL if it's a name
        String queueUrl;
        if (resourceId.startsWith("https://")) {
            queueUrl = resourceId;
        } else {
            String queueName = resourceId.contains(":") ? resourceId.substring(resourceId.lastIndexOf(':') + 1) : resourceId;
            queueUrl = sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
        }
        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
        logger.info("[WasteDelete] Deleted SQS queue {}", resourceId);
    }

    // ─── EFS ──────────────────────────────────────────────────────────────────

    private void deleteEfsFileSystem(CloudAccount account, String fileSystemId, String region) {
        EfsClient efs = awsClientProvider.getEfsClient(account, region);
        efs.deleteFileSystem(DeleteFileSystemRequest.builder().fileSystemId(fileSystemId).build());
        logger.info("[WasteDelete] Deleted EFS file system {}", fileSystemId);
    }

    // ─── EKS ──────────────────────────────────────────────────────────────────

    private void deleteEksCluster(CloudAccount account, String clusterName, String region) {
        EksClient eks = awsClientProvider.getEksClient(account, region);
        eks.deleteCluster(software.amazon.awssdk.services.eks.model.DeleteClusterRequest.builder()
                .name(clusterName).build());
        logger.info("[WasteDelete] Initiated deletion of EKS cluster {}", clusterName);
    }

    // ─── ECS ──────────────────────────────────────────────────────────────────

    private void deleteEcsCluster(CloudAccount account, String clusterArn, String region) {
        EcsClient ecs = awsClientProvider.getEcsClient(account, region);
        ecs.deleteCluster(software.amazon.awssdk.services.ecs.model.DeleteClusterRequest.builder()
                .cluster(clusterArn).build());
        logger.info("[WasteDelete] Deleted ECS cluster {}", clusterArn);
    }

    // ─── ACM ──────────────────────────────────────────────────────────────────

    private void deleteAcmCertificate(CloudAccount account, String certificateArn, String region) {
        software.amazon.awssdk.services.acm.AcmClient acm = awsClientProvider.getAcmClient(account, region);
        acm.deleteCertificate(
                software.amazon.awssdk.services.acm.model.DeleteCertificateRequest.builder()
                        .certificateArn(certificateArn).build());
        logger.info("[WasteDelete] Deleted ACM certificate {}", certificateArn);
    }

    // ─── Auto Scaling Group ───────────────────────────────────────────────────

    private void deleteAutoScalingGroup(CloudAccount account, String asgName, String region) {
        software.amazon.awssdk.services.autoscaling.AutoScalingClient asg =
                awsClientProvider.getAutoScalingClient(account, region);
        asg.deleteAutoScalingGroup(
                software.amazon.awssdk.services.autoscaling.model.DeleteAutoScalingGroupRequest.builder()
                        .autoScalingGroupName(asgName)
                        .forceDelete(true)
                        .build());
        logger.info("[WasteDelete] Deleted Auto Scaling Group {}", asgName);
    }
}
