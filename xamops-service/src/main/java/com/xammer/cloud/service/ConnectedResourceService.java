package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.ResourceLabelAssignment;
import com.xammer.cloud.dto.ConnectedResourceDto;
import com.xammer.cloud.dto.ResourceLabelDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.ResourceLabelAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ListServicesRequest;
import software.amazon.awssdk.services.ecs.model.ListServicesResponse;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.Nodegroup;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetFunctionConfigurationResponse;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Discovers infrastructure resources that are connected/related to a given resource.
 */
@Service
public class ConnectedResourceService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectedResourceService.class);

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final ResourceLabelAssignmentRepository assignmentRepo;

    public ConnectedResourceService(AwsClientProvider awsClientProvider,
                                     CloudAccountRepository cloudAccountRepository,
                                     ResourceLabelAssignmentRepository assignmentRepo) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.assignmentRepo = assignmentRepo;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public List<ConnectedResourceDto> getConnectedResources(String accountId,
                                                             String resourceId,
                                                             String resourceType,
                                                             String region) {
        CloudAccount account = resolveAccount(accountId);
        logger.info("🔗 Connected resources: type={} id={} region={}", resourceType, resourceId, region);
        switch (resourceType) {
            case "EC2 Instance":       return findEc2Connected(account, region, resourceId);
            case "Load Balancer":      return findElbConnected(account, region, resourceId);
            case "EBS Volume":         return findEbsConnected(account, region, resourceId);
            case "RDS Instance":       return findRdsConnected(account, region, resourceId);
            case "Lambda Function":    return findLambdaConnected(account, region, resourceId);
            case "Auto Scaling Group": return findAsgConnected(account, region, resourceId);
            case "EKS Cluster":        return findEksConnected(account, region, resourceId);
            case "ECS Cluster":        return findEcsConnected(account, region, resourceId);
            case "NAT Gateway":        return findNatGatewayConnected(account, region, resourceId);
            default:                   return Collections.emptyList();
        }
    }

    // -----------------------------------------------------------------------
    // EC2 Instance → EBS volumes + Elastic IPs + Load Balancers
    // -----------------------------------------------------------------------

    private List<ConnectedResourceDto> findEc2Connected(CloudAccount account, String region, String instanceId) {
        List<ConnectedResourceDto> result = new ArrayList<>();
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);

        Instance instance;
        try {
            DescribeInstancesResponse resp = ec2.describeInstances(
                    DescribeInstancesRequest.builder().instanceIds(instanceId).build());
            if (resp.reservations().isEmpty() || resp.reservations().get(0).instances().isEmpty()) {
                logger.warn("Instance {} not found in {}", instanceId, region);
                return result;
            }
            instance = resp.reservations().get(0).instances().get(0);
        } catch (Exception e) {
            logger.error("Failed to describe instance {}: {}", instanceId, e.getMessage());
            return result;
        }

        // EBS Volumes
        for (InstanceBlockDeviceMapping bdm : instance.blockDeviceMappings()) {
            String volumeId = bdm.ebs() != null ? bdm.ebs().volumeId() : null;
            if (volumeId == null) continue;
            try {
                DescribeVolumesResponse volResp = ec2.describeVolumes(
                        DescribeVolumesRequest.builder().volumeIds(volumeId).build());
                for (Volume vol : volResp.volumes()) {
                    String volName = tagValue(vol.tags(), "Name");
                    Map<String, String> details = new LinkedHashMap<>();
                    details.put("size", vol.size() + " GiB");
                    details.put("type", vol.volumeTypeAsString());
                    details.put("state", vol.stateAsString());
                    details.put("device", bdm.deviceName());
                    result.add(new ConnectedResourceDto(volumeId, volName != null ? volName : volumeId,
                            "EBS Volume", region, details, labelsFor(account, volumeId)));
                }
            } catch (Exception e) {
                logger.warn("Could not describe volume {}: {}", volumeId, e.getMessage());
            }
        }

        // Elastic IPs
        try {
            DescribeAddressesResponse addrResp = ec2.describeAddresses(
                    DescribeAddressesRequest.builder()
                            .filters(Filter.builder().name("instance-id").values(instanceId).build())
                            .build());
            for (Address addr : addrResp.addresses()) {
                String allocationId = addr.allocationId();
                Map<String, String> details = new LinkedHashMap<>();
                details.put("publicIp", addr.publicIp() != null ? addr.publicIp() : "");
                details.put("domain", addr.domainAsString());
                result.add(new ConnectedResourceDto(
                        allocationId != null ? allocationId : addr.publicIp(),
                        addr.publicIp(), "Elastic IP", region, details,
                        allocationId != null ? labelsFor(account, allocationId) : Collections.emptyList()));
            }
        } catch (Exception e) {
            logger.warn("Could not describe EIPs for {}: {}", instanceId, e.getMessage());
        }

        // Load Balancers in same VPC
        String vpcId = instance.vpcId();
        if (vpcId != null && !vpcId.isEmpty()) {
            try {
                ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, region);
                elbv2.describeLoadBalancers().loadBalancers().stream()
                        .filter(lb -> vpcId.equals(lb.vpcId()))
                        .forEach(lb -> {
                            if (lbTargetsInstance(elbv2, lb.loadBalancerArn(), instanceId)) {
                                Map<String, String> details = new LinkedHashMap<>();
                                details.put("dns", lb.dnsName());
                                details.put("scheme", lb.schemeAsString());
                                details.put("type", lb.typeAsString());
                                result.add(new ConnectedResourceDto(lb.loadBalancerArn(),
                                        lb.loadBalancerName(), "Load Balancer", region,
                                        details, labelsFor(account, lb.loadBalancerArn())));
                            }
                        });
            } catch (Exception e) {
                logger.warn("Could not look up LBs for {}: {}", instanceId, e.getMessage());
            }
        }

        // Security Groups
        try {
            for (GroupIdentifier sg : instance.securityGroups()) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("name", sg.groupName());
                result.add(new ConnectedResourceDto(sg.groupId(), sg.groupName(),
                        "Security Group", region, details, labelsFor(account, sg.groupId())));
            }
        } catch (Exception e) {
            logger.warn("Could not get security groups for {}: {}", instanceId, e.getMessage());
        }

        logger.info("🔗 Found {} connected for EC2 {}", result.size(), instanceId);
        return result;
    }

    // -----------------------------------------------------------------------
    // EBS Volume → attached EC2 instance(s)
    // -----------------------------------------------------------------------

    private List<ConnectedResourceDto> findEbsConnected(CloudAccount account, String region, String volumeId) {
        List<ConnectedResourceDto> result = new ArrayList<>();
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
            DescribeVolumesResponse resp = ec2.describeVolumes(
                    DescribeVolumesRequest.builder().volumeIds(volumeId).build());
            for (Volume vol : resp.volumes()) {
                for (VolumeAttachment att : vol.attachments()) {
                    String instanceId = att.instanceId();
                    if (instanceId == null) continue;
                    try {
                        ec2.describeInstances(DescribeInstancesRequest.builder()
                                .instanceIds(instanceId).build())
                                .reservations().forEach(r -> r.instances().forEach(inst -> {
                                    String name = tagValue(inst.tags(), "Name");
                                    Map<String, String> details = new LinkedHashMap<>();
                                    details.put("instanceType", inst.instanceTypeAsString());
                                    details.put("state", inst.state().nameAsString());
                                    details.put("device", att.device());
                                    details.put("privateIp", inst.privateIpAddress() != null ? inst.privateIpAddress() : "");
                                    result.add(new ConnectedResourceDto(instanceId,
                                            name != null ? name : instanceId, "EC2 Instance",
                                            region, details, labelsFor(account, instanceId)));
                                }));
                    } catch (Exception e) {
                        logger.warn("Could not describe instance {}: {}", instanceId, e.getMessage());
                        result.add(new ConnectedResourceDto(instanceId, instanceId, "EC2 Instance",
                                region, Map.of("device", att.device()), labelsFor(account, instanceId)));
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to find connected for EBS {}: {}", volumeId, e.getMessage());
        }
        logger.info("🔗 Found {} connected for EBS {}", result.size(), volumeId);
        return result;
    }

    // -----------------------------------------------------------------------
    // Load Balancer → EC2 Instances behind it
    // -----------------------------------------------------------------------

    private List<ConnectedResourceDto> findElbConnected(CloudAccount account, String region, String lbIdentifier) {
        List<ConnectedResourceDto> result = new ArrayList<>();
        try {
            ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, region);
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);

            List<LoadBalancer> candidates = elbv2.describeLoadBalancers().loadBalancers().stream()
                    .filter(lb -> lbIdentifier.equals(lb.loadBalancerArn())
                            || lbIdentifier.equals(lb.loadBalancerName()))
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) return result;
            LoadBalancer lb = candidates.get(0);

            DescribeTargetGroupsResponse tgResp = elbv2.describeTargetGroups(
                    req -> req.loadBalancerArn(lb.loadBalancerArn()));
            Set<String> instanceIds = new LinkedHashSet<>();
            for (TargetGroup tg : tgResp.targetGroups()) {
                try {
                    elbv2.describeTargetHealth(req -> req.targetGroupArn(tg.targetGroupArn()))
                            .targetHealthDescriptions().forEach(th -> {
                                String id = th.target().id();
                                if (id != null && id.startsWith("i-")) instanceIds.add(id);
                            });
                } catch (Exception e) {
                    logger.warn("Could not describe TG health {}: {}", tg.targetGroupArn(), e.getMessage());
                }
            }

            if (!instanceIds.isEmpty()) {
                try {
                    ec2.describeInstances(DescribeInstancesRequest.builder()
                            .instanceIds(new ArrayList<>(instanceIds)).build())
                            .reservations().forEach(r -> r.instances().forEach(inst -> {
                                String name = tagValue(inst.tags(), "Name");
                                Map<String, String> details = new LinkedHashMap<>();
                                details.put("instanceType", inst.instanceTypeAsString());
                                details.put("state", inst.state().nameAsString());
                                details.put("privateIp", inst.privateIpAddress() != null ? inst.privateIpAddress() : "");
                                result.add(new ConnectedResourceDto(inst.instanceId(),
                                        name != null ? name : inst.instanceId(), "EC2 Instance",
                                        region, details, labelsFor(account, inst.instanceId())));
                            }));
                } catch (Exception e) {
                    logger.warn("Could not describe LB target instances: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to find connected for LB {}: {}", lbIdentifier, e.getMessage());
        }
        logger.info("🔗 Found {} connected for LB {}", result.size(), lbIdentifier);
        return result;
    }

    // -----------------------------------------------------------------------
    // RDS Instance → security groups + read replicas
    // -----------------------------------------------------------------------

    private List<ConnectedResourceDto> findRdsConnected(CloudAccount account, String region, String dbId) {
        List<ConnectedResourceDto> result = new ArrayList<>();
        try {
            RdsClient rds = awsClientProvider.getRdsClient(account, region);
            List<DBInstance> instances = rds.describeDBInstances(
                    req -> req.dbInstanceIdentifier(dbId)).dbInstances();
            if (instances.isEmpty()) return result;
            DBInstance db = instances.get(0);

            // VPC Security Groups
            db.vpcSecurityGroups().forEach(sg -> {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("status", sg.status());
                result.add(new ConnectedResourceDto(sg.vpcSecurityGroupId(), sg.vpcSecurityGroupId(),
                        "Security Group", region, details, labelsFor(account, sg.vpcSecurityGroupId())));
            });

            // Read replicas
            db.readReplicaDBInstanceIdentifiers().forEach(replicaId ->
                result.add(new ConnectedResourceDto(replicaId, replicaId, "RDS Instance", region,
                        Map.of("role", "Read Replica"), labelsFor(account, replicaId))));

            // Replication source (if this is a replica)
            if (db.readReplicaSourceDBInstanceIdentifier() != null
                    && !db.readReplicaSourceDBInstanceIdentifier().isBlank()) {
                String sourceId = db.readReplicaSourceDBInstanceIdentifier();
                result.add(new ConnectedResourceDto(sourceId, sourceId, "RDS Instance", region,
                        Map.of("role", "Primary (Source)"), labelsFor(account, sourceId)));
            }

            // Subnet group info (just descriptive — no labelable sub-resource)
            if (db.dbSubnetGroup() != null) {
                String subnetGroupId = db.dbSubnetGroup().dbSubnetGroupName();
                Map<String, String> details = new LinkedHashMap<>();
                details.put("vpcId", db.dbSubnetGroup().vpcId() != null ? db.dbSubnetGroup().vpcId() : "");
                result.add(new ConnectedResourceDto(subnetGroupId, subnetGroupId,
                        "RDS Subnet Group", region, details, labelsFor(account, subnetGroupId)));
            }
        } catch (Exception e) {
            logger.error("Failed to find connected for RDS {}: {}", dbId, e.getMessage());
        }
        logger.info("🔗 Found {} connected for RDS {}", result.size(), dbId);
        return result;
    }

    // -----------------------------------------------------------------------
    // Lambda Function → VPC security groups + event source triggers
    // -----------------------------------------------------------------------

    private List<ConnectedResourceDto> findLambdaConnected(CloudAccount account, String region, String functionName) {
        List<ConnectedResourceDto> result = new ArrayList<>();
        try {
            LambdaClient lambda = awsClientProvider.getLambdaClient(account, region);
            GetFunctionConfigurationResponse config = lambda.getFunctionConfiguration(
                    req -> req.functionName(functionName));

            // VPC Security Groups (if Lambda is VPC-attached)
            if (config.vpcConfig() != null && !config.vpcConfig().securityGroupIds().isEmpty()) {
                Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
                for (String sgId : config.vpcConfig().securityGroupIds()) {
                    try {
                        ec2.describeSecurityGroups(DescribeSecurityGroupsRequest.builder()
                                .groupIds(sgId).build())
                                .securityGroups().forEach(sg -> {
                                    Map<String, String> details = new LinkedHashMap<>();
                                    details.put("name", sg.groupName());
                                    details.put("vpc", sg.vpcId() != null ? sg.vpcId() : "");
                                    result.add(new ConnectedResourceDto(sgId, sg.groupName(),
                                            "Security Group", region, details, labelsFor(account, sgId)));
                                });
                    } catch (Exception e) {
                        result.add(new ConnectedResourceDto(sgId, sgId, "Security Group", region,
                                Collections.emptyMap(), labelsFor(account, sgId)));
                    }
                }
            }

            // Event Source Mappings (triggers — SQS, DynamoDB, Kinesis, etc.)
            try {
                lambda.listEventSourceMappings(req -> req.functionName(functionName))
                        .eventSourceMappings().forEach(esm -> {
                            String sourceArn = esm.eventSourceArn();
                            if (sourceArn == null) return;
                            String name = sourceArn.contains(":")
                                    ? sourceArn.substring(sourceArn.lastIndexOf(':') + 1) : sourceArn;
                            String type = sourceArn.contains(":dynamodb:") ? "DynamoDB Table"
                                    : sourceArn.contains(":kinesis:") ? "Kinesis Stream"
                                    : sourceArn.contains(":sqs:") ? "SQS Queue"
                                    : sourceArn.contains(":kafka:") || sourceArn.contains(":msk:") ? "MSK Cluster"
                                    : "Event Source";
                            Map<String, String> details = new LinkedHashMap<>();
                            details.put("state", esm.state() != null ? esm.state() : "");
                            details.put("batchSize", String.valueOf(esm.batchSize()));
                            result.add(new ConnectedResourceDto(sourceArn, name, type, region,
                                    details, labelsFor(account, sourceArn)));
                        });
            } catch (Exception e) {
                logger.warn("Could not list event sources for {}: {}", functionName, e.getMessage());
            }
        } catch (Exception e) {
            logger.error("Failed to find connected for Lambda {}: {}", functionName, e.getMessage());
        }
        logger.info("🔗 Found {} connected for Lambda {}", result.size(), functionName);
        return result;
    }

    // -----------------------------------------------------------------------
    // Auto Scaling Group → EC2 instances + Load Balancers
    // -----------------------------------------------------------------------

    private List<ConnectedResourceDto> findAsgConnected(CloudAccount account, String region, String asgName) {
        List<ConnectedResourceDto> result = new ArrayList<>();
        try {
            AutoScalingClient asc = awsClientProvider.getAutoScalingClient(account, region);
            List<AutoScalingGroup> groups = asc.describeAutoScalingGroups(
                    DescribeAutoScalingGroupsRequest.builder()
                            .autoScalingGroupNames(asgName).build()).autoScalingGroups();
            if (groups.isEmpty()) return result;
            AutoScalingGroup asg = groups.get(0);
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);

            // EC2 instances managed by the ASG
            List<String> instanceIds = asg.instances().stream()
                    .map(i -> i.instanceId())
                    .collect(Collectors.toList());
            if (!instanceIds.isEmpty()) {
                try {
                    ec2.describeInstances(DescribeInstancesRequest.builder()
                            .instanceIds(instanceIds).build())
                            .reservations().forEach(r -> r.instances().forEach(inst -> {
                                String name = tagValue(inst.tags(), "Name");
                                Map<String, String> details = new LinkedHashMap<>();
                                details.put("instanceType", inst.instanceTypeAsString());
                                details.put("state", inst.state().nameAsString());
                                details.put("privateIp", inst.privateIpAddress() != null ? inst.privateIpAddress() : "");
                                result.add(new ConnectedResourceDto(inst.instanceId(),
                                        name != null ? name : inst.instanceId(), "EC2 Instance",
                                        region, details, labelsFor(account, inst.instanceId())));
                            }));
                } catch (Exception e) {
                    logger.warn("Could not describe ASG instances: {}", e.getMessage());
                }
            }

            // Load Balancers via Target Groups
            ElasticLoadBalancingV2Client elbv2 = awsClientProvider.getElbv2Client(account, region);
            for (String tgArn : asg.targetGroupARNs()) {
                try {
                    elbv2.describeTargetGroups(req -> req.targetGroupArns(tgArn))
                            .targetGroups().forEach(tg -> {
                                if (tg.loadBalancerArns().isEmpty()) return;
                                String lbArn = tg.loadBalancerArns().get(0);
                                try {
                                    elbv2.describeLoadBalancers(req -> req.loadBalancerArns(lbArn))
                                            .loadBalancers().forEach(lb -> {
                                                Map<String, String> details = new LinkedHashMap<>();
                                                details.put("dns", lb.dnsName());
                                                details.put("type", lb.typeAsString());
                                                result.add(new ConnectedResourceDto(lbArn,
                                                        lb.loadBalancerName(), "Load Balancer",
                                                        region, details, labelsFor(account, lbArn)));
                                            });
                                } catch (Exception e) {
                                    logger.warn("Could not describe LB {}: {}", lbArn, e.getMessage());
                                }
                            });
                } catch (Exception e) {
                    logger.warn("Could not describe TG {}: {}", tgArn, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to find connected for ASG {}: {}", asgName, e.getMessage());
        }
        logger.info("🔗 Found {} connected for ASG {}", result.size(), asgName);
        return result;
    }

    // -----------------------------------------------------------------------
    // EKS Cluster → Node Groups
    // -----------------------------------------------------------------------

    private List<ConnectedResourceDto> findEksConnected(CloudAccount account, String region, String clusterName) {
        List<ConnectedResourceDto> result = new ArrayList<>();
        try {
            EksClient eks = awsClientProvider.getEksClient(account, region);
            eks.listNodegroups(req -> req.clusterName(clusterName))
                    .nodegroups().forEach(ngName -> {
                        try {
                            Nodegroup ng = eks.describeNodegroup(
                                    req -> req.clusterName(clusterName).nodegroupName(ngName)).nodegroup();
                            Map<String, String> details = new LinkedHashMap<>();
                            details.put("status", ng.statusAsString());
                            details.put("instanceType", ng.instanceTypes().isEmpty() ? "" : ng.instanceTypes().get(0));
                            if (ng.scalingConfig() != null) {
                                details.put("desired", String.valueOf(ng.scalingConfig().desiredSize()));
                                details.put("min", String.valueOf(ng.scalingConfig().minSize()));
                                details.put("max", String.valueOf(ng.scalingConfig().maxSize()));
                            }
                            result.add(new ConnectedResourceDto(ng.nodegroupArn(), ngName,
                                    "EKS Node Group", region, details, labelsFor(account, ng.nodegroupArn())));
                        } catch (Exception e) {
                            logger.warn("Could not describe EKS node group {}: {}", ngName, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            logger.error("Failed to find connected for EKS {}: {}", clusterName, e.getMessage());
        }
        logger.info("🔗 Found {} connected for EKS {}", result.size(), clusterName);
        return result;
    }

    // -----------------------------------------------------------------------
    // ECS Cluster → Services
    // -----------------------------------------------------------------------

    private List<ConnectedResourceDto> findEcsConnected(CloudAccount account, String region, String clusterArn) {
        List<ConnectedResourceDto> result = new ArrayList<>();
        try {
            EcsClient ecs = awsClientProvider.getEcsClient(account, region);
            ListServicesResponse servResp = ecs.listServices(
                    ListServicesRequest.builder().cluster(clusterArn).maxResults(20).build());
            if (!servResp.serviceArns().isEmpty()) {
                ecs.describeServices(req -> req.cluster(clusterArn).services(servResp.serviceArns()))
                        .services().forEach(svc -> {
                            Map<String, String> details = new LinkedHashMap<>();
                            details.put("status", svc.status() != null ? svc.status() : "");
                            details.put("running", String.valueOf(svc.runningCount()));
                            details.put("desired", String.valueOf(svc.desiredCount()));
                            details.put("launchType", svc.launchTypeAsString() != null ? svc.launchTypeAsString() : "");
                            result.add(new ConnectedResourceDto(svc.serviceArn(), svc.serviceName(),
                                    "ECS Service", region, details, labelsFor(account, svc.serviceArn())));
                        });
            }
        } catch (Exception e) {
            logger.error("Failed to find connected for ECS cluster {}: {}", clusterArn, e.getMessage());
        }
        logger.info("🔗 Found {} connected for ECS cluster {}", result.size(), clusterArn);
        return result;
    }

    // -----------------------------------------------------------------------
    // NAT Gateway → Elastic IPs
    // -----------------------------------------------------------------------

    private List<ConnectedResourceDto> findNatGatewayConnected(CloudAccount account, String region, String natGwId) {
        List<ConnectedResourceDto> result = new ArrayList<>();
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
            ec2.describeNatGateways(DescribeNatGatewaysRequest.builder()
                    .natGatewayIds(natGwId).build())
                    .natGateways().forEach(natGw -> {
                        natGw.natGatewayAddresses().forEach(addr -> {
                            if (addr.allocationId() == null) return;
                            Map<String, String> details = new LinkedHashMap<>();
                            details.put("publicIp", addr.publicIp() != null ? addr.publicIp() : "");
                            details.put("privateIp", addr.privateIp() != null ? addr.privateIp() : "");
                            result.add(new ConnectedResourceDto(addr.allocationId(), addr.publicIp(),
                                    "Elastic IP", region, details, labelsFor(account, addr.allocationId())));
                        });
                    });
        } catch (Exception e) {
            logger.error("Failed to find connected for NAT GW {}: {}", natGwId, e.getMessage());
        }
        logger.info("🔗 Found {} connected for NAT GW {}", result.size(), natGwId);
        return result;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private boolean lbTargetsInstance(ElasticLoadBalancingV2Client elbv2, String lbArn, String instanceId) {
        try {
            for (TargetGroup tg : elbv2.describeTargetGroups(req -> req.loadBalancerArn(lbArn)).targetGroups()) {
                boolean found = elbv2.describeTargetHealth(req -> req.targetGroupArn(tg.targetGroupArn()))
                        .targetHealthDescriptions().stream()
                        .anyMatch(th -> instanceId.equals(th.target().id()));
                if (found) return true;
            }
        } catch (Exception e) {
            logger.warn("Error checking LB {} for {}: {}", lbArn, instanceId, e.getMessage());
        }
        return false;
    }

    private List<ResourceLabelDto> labelsFor(CloudAccount account, String resourceId) {
        List<ResourceLabelAssignment> assignments =
                assignmentRepo.findByCloudAccountIdAndResourceId(account.getId(), resourceId);
        return assignments.stream()
                .map(a -> ResourceLabelDto.from(a.getLabel()))
                .collect(Collectors.toList());
    }

    private String tagValue(List<Tag> tags, String key) {
        return tags == null ? null : tags.stream()
                .filter(t -> key.equals(t.key()))
                .map(Tag::value)
                .findFirst().orElse(null);
    }

    private CloudAccount resolveAccount(String accountId) {
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseGet(() -> {
                    List<CloudAccount> list = cloudAccountRepository.findByAwsAccountIdOrGcpProjectId(accountId, accountId);
                    if (list.isEmpty()) throw new IllegalArgumentException("Account not found: " + accountId);
                    return list.get(0);
                });
    }
}
