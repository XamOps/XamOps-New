package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.lightsail.LightsailClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.util.UUID;

@Service
public class AwsClientProvider {

    private AwsCredentialsProvider getCredentialsProvider(CloudAccount account, String region) {
        if (!"AWS".equals(account.getProvider())) {
            throw new IllegalArgumentException("Account is not an AWS account.");
        }
        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(StsClient.builder().region(Region.of(region)).build())
                .refreshRequest(AssumeRoleRequest.builder()
                        .roleArn(account.getRoleArn())
                        .roleSessionName("BillOps-Session-" + UUID.randomUUID())
                        .externalId(account.getExternalId())
                        .build())
                .build();
    }

    public CostExplorerClient getCostExplorerClient(CloudAccount account) {
        return CostExplorerClient.builder()
                .region(Region.US_EAST_1) // Cost Explorer is primarily a global service accessed via us-east-1
                .credentialsProvider(getCredentialsProvider(account, "us-east-1"))
                .build();
    }

    public Ec2Client getEc2Client(CloudAccount account, String region) {
        return Ec2Client.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider(account, region))
                .build();
    }

    public ElasticLoadBalancingV2Client getElbClient(CloudAccount account, String region) {
        return ElasticLoadBalancingV2Client.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider(account, region))
                .build();
    }

    public RdsClient getRdsClient(CloudAccount account, String region) {
        return RdsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider(account, region))
                .build();
    }

    public S3Client getS3Client(CloudAccount account) {
        // S3 is a global service, but the client needs a region for the API endpoint.
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(getCredentialsProvider(account, "us-east-1"))
                .build();
    }
    
    public LightsailClient getLightsailClient(CloudAccount account, String region) {
        return LightsailClient.builder()
                .region(Region.of(region))
                .credentialsProvider(getCredentialsProvider(account, region))
                .build();
    }
}