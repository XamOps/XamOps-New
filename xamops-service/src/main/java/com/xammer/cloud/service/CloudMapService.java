package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Vpc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class CloudMapService {

    private static final Logger logger = LoggerFactory.getLogger(CloudMapService.class);

    @Autowired
    private CloudListService cloudListService;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private AwsClientProvider awsClientProvider;

    public CompletableFuture<List<ResourceDto>> getVpcListForCloudmap(String accountId, boolean forceRefresh) {
        try {
            CloudAccount account = getAccount(accountId);
            logger.info("Fetching all resources for account {} to filter VPCs", accountId);

            return cloudListService.getAllResources(account, forceRefresh)
                    .thenApply(resources -> {
                        if (resources == null) {
                            logger.warn("Received null resources list for account {}", accountId);
                            return new ArrayList<ResourceDto>();
                        }

                        List<ResourceDto> vpcs = resources.stream()
                                .filter(r -> r != null && "VPC".equals(r.getType()))
                                .collect(Collectors.toList());

                        logger.info("Filtered {} VPCs from {} total resources for account {}",
                                vpcs.size(), resources.size(), accountId);
                        return vpcs;
                    })
                    .exceptionally(ex -> {
                        logger.error("Error fetching VPCs for account {}: {}", accountId, ex.getMessage(), ex);
                        return new ArrayList<ResourceDto>();
                    });
        } catch (Exception ex) {
            logger.error("Failed to initialize VPC fetch for account {}: {}", accountId, ex.getMessage(), ex);
            return CompletableFuture.completedFuture(new ArrayList<ResourceDto>());
        }
    }

    public CompletableFuture<List<Map<String, Object>>> getGraphData(String accountId, String vpcId, String region,
            boolean forceRefresh) {
        try {
            CloudAccount account = getAccount(accountId);
            logger.info("Fetching graph data for account {}, VPC: {}, Region: {}", accountId, vpcId, region);

            return cloudListService.getAllResources(account, forceRefresh)
                    .thenApply(allResources -> {
                        if (allResources == null) {
                            logger.warn("Received null resources list for account {}", accountId);
                            return new ArrayList<Map<String, Object>>();
                        }

                        logger.info("Processing {} total resources for graph", allResources.size());
                        List<Map<String, Object>> elements = new ArrayList<>();
                        List<ResourceDto> relevantResources;

                        try {
                            if (vpcId != null && !vpcId.isEmpty()) {
                                // --- VPC-specific or Multi-VPC view ---
                                logger.info("Building graph for VPC: {}", vpcId);
                                Ec2Client ec2Client = awsClientProvider.getEc2Client(account, region);
                                addVpcAndSubnetNodes(elements, ec2Client, vpcId);

                                relevantResources = allResources.stream()
                                        .filter(r -> r != null && ("ALL".equals(vpcId)
                                                || vpcId.equals(getDetail(r, "VPC ID")) || vpcId.equals(r.getId())))
                                        .collect(Collectors.toList());
                            } else {
                                // --- Global view (non-VPC resources) ---
                                logger.info("Building global resource graph");
                                relevantResources = allResources.stream()
                                        .filter(r -> r != null && getDetail(r, "VPC ID") == null
                                                && !"Subnet".equals(r.getType()))
                                        .collect(Collectors.toList());
                            }

                            logger.info("Found {} relevant resources for the graph", relevantResources.size());

                            for (ResourceDto resource : relevantResources) {
                                try {
                                    String parent = getParent(resource);
                                    elements.add(createNode(resource.getId(), resource.getName(), resource.getType(),
                                            parent, resource.getDetails()));
                                } catch (Exception e) {
                                    logger.warn("Failed to create node for resource {}: {}", resource.getId(),
                                            e.getMessage());
                                }
                            }

                            createAllEdges(elements, relevantResources);
                            logger.info("Successfully created graph with {} elements", elements.size());

                        } catch (Exception e) {
                            logger.error("Error processing resources for graph: {}", e.getMessage(), e);
                        }

                        return elements;
                    })
                    .exceptionally(ex -> {
                        logger.error("Error fetching graph data for account {}: {}", accountId, ex.getMessage(), ex);
                        return new ArrayList<Map<String, Object>>();
                    });
        } catch (Exception ex) {
            logger.error("Failed to initialize graph fetch for account {}: {}", accountId, ex.getMessage(), ex);
            return CompletableFuture.completedFuture(new ArrayList<Map<String, Object>>());
        }
    }

    private void addVpcAndSubnetNodes(List<Map<String, Object>> elements, Ec2Client ec2Client, String vpcId) {
        try {
            if ("ALL".equals(vpcId)) {
                List<Vpc> vpcs = ec2Client.describeVpcs().vpcs();
                for (Vpc vpc : vpcs) {
                    processVpcAndSubnets(elements, ec2Client, vpc);
                }
            } else {
                Vpc vpc = ec2Client.describeVpcs(req -> req.vpcIds(vpcId)).vpcs().get(0);
                processVpcAndSubnets(elements, ec2Client, vpc);
            }
        } catch (Exception e) {
            logger.error("Could not describe VPC {} or its subnets: {}", vpcId, e.getMessage());
        }
    }

    private void processVpcAndSubnets(List<Map<String, Object>> elements, Ec2Client ec2Client, Vpc vpc) {
        String vid = vpc.vpcId();
        String vpcName = vpc.tags().stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst()
                .map(t -> t.value()).orElse(vid);
        elements.add(createNode(vid, vpcName, "VPC", null, null));

        List<Subnet> subnets = ec2Client
                .describeSubnets(req -> req.filters(Filter.builder().name("vpc-id").values(vid).build())).subnets();
        for (Subnet subnet : subnets) {
            String subnetName = subnet.tags().stream().filter(t -> "Name".equalsIgnoreCase(t.key())).findFirst()
                    .map(t -> t.value()).orElse(subnet.subnetId());
            elements.add(createNode(subnet.subnetId(), subnetName, "Subnet", vid, null));
        }
    }

    private void createAllEdges(List<Map<String, Object>> elements, List<ResourceDto> resources) {
        for (ResourceDto resource : resources) {
            // EBS Volume -> EC2 Instance
            if ("EBS Volume".equals(resource.getType())) {
                String attachedInstance = getDetail(resource, "Attached to");
                if (attachedInstance != null && !attachedInstance.equals("N/A")) {
                    elements.add(createEdge(resource.getId(), attachedInstance, "attaches to"));
                }
            }
            // EC2 Instance -> Security Group
            if ("EC2 Instance".equals(resource.getType())) {
                String securityGroups = getDetail(resource, "Security Groups");
                if (securityGroups != null && !securityGroups.isBlank()) {
                    for (String sgId : securityGroups.split("\\s*,\\s*")) {
                        if (!sgId.trim().isEmpty())
                            elements.add(createEdge(resource.getId(), sgId.trim(), "uses"));
                    }
                }
            }
            // Load Balancer -> Subnets
            if ("Load Balancer".equals(resource.getType())) {
                String subnets = getDetail(resource, "Subnets"); // Note: You need to add "Subnets" to the LB's details
                                                                 // map
                if (subnets != null) {
                    for (String subnetId : subnets.split("\\s*,\\s*")) {
                        if (!subnetId.trim().isEmpty())
                            elements.add(createEdge(resource.getId(), subnetId.trim(), "in subnet"));
                    }
                }
            }
            // RDS Instance -> Security Groups
            if ("RDS Instance".equals(resource.getType())) {
                String securityGroups = getDetail(resource, "Security Groups"); // Note: You need to add "Security
                                                                                // Groups" to the RDS details map
                if (securityGroups != null) {
                    for (String sgId : securityGroups.split("\\s*,\\s*")) {
                        if (!sgId.trim().isEmpty())
                            elements.add(createEdge(resource.getId(), sgId.trim(), "uses"));
                    }
                }
            }
        }
    }

    private String getParent(ResourceDto resource) {
        String type = resource.getType();
        if ("Subnet".equals(type) || "Internet Gateway".equals(type) || "NAT Gateway".equals(type)) {
            return getDetail(resource, "VPC ID");
        }
        String subnetId = getDetail(resource, "Subnet ID");
        if (subnetId != null && !subnetId.isBlank()) {
            return subnetId;
        }
        return getDetail(resource, "VPC ID");
    }

    private String getDetail(ResourceDto resource, String key) {
        return (resource.getDetails() != null) ? resource.getDetails().get(key) : null;
    }

    private Map<String, Object> createNode(String id, String label, String type, String parent,
            Map<String, String> details) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("label", label != null && !label.isBlank() ? label : id);
        data.put("type", type);
        if (parent != null && !parent.isBlank()) {
            data.put("parent", parent);
        }

        if (details != null) {
            details.forEach((key, value) -> {
                if (!data.containsKey(key)) {
                    data.put(key, value != null ? value : "N/A");
                }
            });
        }

        Map<String, Object> node = new HashMap<>();
        node.put("data", data);
        return node;
    }

    private Map<String, Object> createEdge(String source, String target, String label) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", source + "_to_" + target + "_" + label.replaceAll("\\s", ""));
        data.put("source", source);
        data.put("target", target);
        data.put("label", label);

        Map<String, Object> edge = new HashMap<>();
        edge.put("data", data);
        return edge;
    }

    private CloudAccount getAccount(String accountId) {
        return cloudAccountRepository.findByAwsAccountId(accountId).stream().findFirst()
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
    }
}