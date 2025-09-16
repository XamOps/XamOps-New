package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.repository.CloudAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.lightsail.LightsailClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;


import java.util.Optional;

@Service
public class AwsClientProvider {

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    private <T> T getClient(CloudAccount account, String region, Class<T> clientClass) {
        StsClient stsClient = StsClient.builder().region(Region.of("us-east-1")).build();

        AssumeRoleRequest assumeRoleRequest = AssumeRoleRequest.builder()
                .roleArn(account.getRoleArn())
                .roleSessionName("xamops-session-" + System.currentTimeMillis())
                .externalId(account.getExternalId()) // <-- THIS LINE IS THE FIX
                .build();

        AssumeRoleResponse assumeRoleResponse = stsClient.assumeRole(assumeRoleRequest);
        Credentials credentials = assumeRoleResponse.credentials();

        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsSessionCredentials.create(
                        credentials.accessKeyId(),
                        credentials.secretAccessKey(),
                        credentials.sessionToken()
                ));

        if (clientClass.equals(CostExplorerClient.class)) {
            return (T) CostExplorerClient.builder().region(Region.of(region)).credentialsProvider(credentialsProvider).build();
        }
        if (clientClass.equals(Ec2Client.class)) {
            return (T) Ec2Client.builder().region(Region.of(region)).credentialsProvider(credentialsProvider).build();
        }
        if (clientClass.equals(ElasticLoadBalancingV2Client.class)) {
            return (T) ElasticLoadBalancingV2Client.builder().region(Region.of(region)).credentialsProvider(credentialsProvider).build();
        }
        if (clientClass.equals(RdsClient.class)) {
            return (T) RdsClient.builder().region(Region.of(region)).credentialsProvider(credentialsProvider).build();
        }
        if (clientClass.equals(S3Client.class)) {
            return (T) S3Client.builder().credentialsProvider(credentialsProvider).build();
        }
        if (clientClass.equals(LightsailClient.class)) {
            return (T) LightsailClient.builder().region(Region.of(region)).credentialsProvider(credentialsProvider).build();
        }

        throw new IllegalArgumentException("Unsupported AWS client type: " + clientClass.getName());
    }

    public CostExplorerClient getCostExplorerClient(CloudAccount account) {
        return getClient(account, "us-east-1", CostExplorerClient.class);
    }

    public Ec2Client getEc2Client(CloudAccount account, String region) {
        return getClient(account, region, Ec2Client.class);
    }

    public ElasticLoadBalancingV2Client getElbClient(CloudAccount account, String region) {
        return getClient(account, region, ElasticLoadBalancingV2Client.class);
    }

    public RdsClient getRdsClient(CloudAccount account, String region) {
        return getClient(account, region, RdsClient.class);
    }

    public S3Client getS3Client(CloudAccount account) {
        return getClient(account, "us-east-1", S3Client.class);
    }

    public LightsailClient getLightsailClient(CloudAccount account, String region) {
        return getClient(account, region, LightsailClient.class);
    }
}