package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Vpc;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.lightsail.LightsailClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ResourceService {

    private final AwsClientProvider awsClientProvider;
    private static final Logger logger = LoggerFactory.getLogger(ResourceService.class);

    public ResourceService(AwsClientProvider awsClientProvider) {
        this.awsClientProvider = awsClientProvider;
    }

    public List<Map<String, Object>> getResourcesInRegion(CloudAccount account, String region, String serviceName) {
        logger.info("Attempting to fetch resources in region '{}' for service name: '{}'", region, serviceName);
        String lowerServiceName = serviceName.toLowerCase();

        // Router to call the correct live data method based on the service name
        if (lowerServiceName.contains("elastic compute cloud") || lowerServiceName.contains("ec2")) {
            logger.info("Matched service '{}' to EC2 instance lookup.", serviceName);
            return getEc2Instances(account, region);
        }
        if (lowerServiceName.contains("virtual private cloud")) {
            logger.info("Matched service '{}' to VPC lookup.", serviceName);
            return getVpcResources(account, region);
        }
        if (lowerServiceName.contains("elastic load balancing")) {
            logger.info("Matched service '{}' to ELB lookup.", serviceName);
            return getElbResources(account, region);
        }
        if (lowerServiceName.contains("rds") || lowerServiceName.contains("relational database service")) {
            logger.info("Matched service '{}' to RDS instance lookup.", serviceName);
            return getRdsInstances(account, region);
        }
        if (lowerServiceName.contains("s3")) {
            logger.info("Matched service '{}' to S3 bucket lookup.", serviceName);
            // S3 is global, so we don't need the region parameter for the API call itself
            return getS3Buckets(account);
        }
        if (lowerServiceName.contains("lightsail")) {
            logger.info("Matched service '{}' to Lightsail instance lookup.", serviceName);
            return getLightsailInstances(account, region);
        }

        logger.warn("No resource listing implementation found for service: '{}'. Returning empty list.", serviceName);
        // Return an empty list for services without a specific resource listing implementation yet
        return Collections.emptyList();
    }

    private List<Map<String, Object>> getEc2Instances(CloudAccount account, String region) {
        List<Map<String, Object>> resources = new ArrayList<>();
        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);
            for (Reservation reservation : ec2Client.describeInstances().reservations()) {
                for (Instance instance : reservation.instances()) {
                    resources.add(createResourceMap(
                        getTagValue(instance.tags(), "Name", instance.instanceId()),
                        instance.instanceId(),
                        15.50 // Placeholder cost
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching EC2 instances for region {}: {}", region, e.getMessage());
        }
        return resources;
    }

    private List<Map<String, Object>> getVpcResources(CloudAccount account, String region) {
        List<Map<String, Object>> resources = new ArrayList<>();
        try {
            Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);
            for (Vpc vpc : ec2Client.describeVpcs().vpcs()) {
                resources.add(createResourceMap(
                    getTagValue(vpc.tags(), "Name", vpc.vpcId()),
                    vpc.vpcId(),
                    5.25 // Placeholder cost
                ));
            }
        } catch (Exception e) {
            logger.error("Error fetching VPCs for region {}: {}", region, e.getMessage());
        }
        return resources;
    }

    private List<Map<String, Object>> getElbResources(CloudAccount account, String region) {
        List<Map<String, Object>> resources = new ArrayList<>();
        try {
            ElasticLoadBalancingV2Client elbClient = awsClientProvider.getElbClient(account, region);
            for (LoadBalancer lb : elbClient.describeLoadBalancers().loadBalancers()) {
                resources.add(createResourceMap(lb.loadBalancerName(), lb.dnsName(), 18.00)); // Placeholder
            }
        } catch (Exception e) {
            logger.error("Error fetching ELBs for region {}: {}", region, e.getMessage());
        }
        return resources;
    }

    private List<Map<String, Object>> getRdsInstances(CloudAccount account, String region) {
        List<Map<String, Object>> resources = new ArrayList<>();
        try {
            RdsClient rdsClient = awsClientProvider.getRdsClient(account, region);
            for (DBInstance db : rdsClient.describeDBInstances().dbInstances()) {
                resources.add(createResourceMap(db.dbInstanceIdentifier(), db.endpoint().address(), 45.00)); // Placeholder
            }
        } catch (Exception e) {
            logger.error("Error fetching RDS instances for region {}: {}", region, e.getMessage());
        }
        return resources;
    }

    private List<Map<String, Object>> getS3Buckets(CloudAccount account) {
        List<Map<String, Object>> resources = new ArrayList<>();
        try {
            S3Client s3Client = awsClientProvider.getS3Client(account);
            for (Bucket bucket : s3Client.listBuckets().buckets()) {
                resources.add(createResourceMap(bucket.name(), "S3 Bucket", 3.50)); // Placeholder
            }
        } catch (Exception e) {
            logger.error("Error fetching S3 buckets: {}", e.getMessage());
        }
        return resources;
    }
    
    private List<Map<String, Object>> getLightsailInstances(CloudAccount account, String region) {
        List<Map<String, Object>> resources = new ArrayList<>();
        try {
            LightsailClient lightsailClient = awsClientProvider.getLightsailClient(account, region);
            for (software.amazon.awssdk.services.lightsail.model.Instance instance : lightsailClient.getInstances().instances()) {
                resources.add(createResourceMap(instance.name(), instance.supportCode(), 10.00)); // Placeholder
            }
        } catch (Exception e) {
            logger.error("Error fetching Lightsail instances for region {}: {}", region, e.getMessage());
        }
        return resources;
    }

    private String getTagValue(List<Tag> tags, String key, String defaultValue) {
        return tags.stream()
                .filter(t -> key.equals(t.key()))
                .findFirst()
                .map(Tag::value)
                .orElse(defaultValue);
    }

    private Map<String, Object> createResourceMap(String name, String id, double cost) {
        Map<String, Object> resource = new HashMap<>();
        resource.put("name", name);
        resource.put("id", id);
        resource.put("cost", cost); // Note: This cost is still a placeholder
        return resource;
    }
}