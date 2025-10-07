package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    public CompletableFuture<List<ResourceDto>> getVpcListForCloudmap(String accountId, boolean forceRefresh) {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId).stream().findFirst()
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        return cloudListService.getAllResources(account, forceRefresh)
                .thenApply(resources -> resources.stream()
                        .filter(r -> "VPC".equals(r.getType()))
                        .map(r -> new ResourceDto(r.getId(), r.getName(), "VPC", r.getRegion(), null, null, Map.of("id", r.getId(), "name", r.getName(), "region", r.getRegion())))
                        .collect(Collectors.toList()));
    }

    public CompletableFuture<List<Map<String, Object>>> getGraphData(String accountId, String vpcId, String region, boolean forceRefresh) {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId).stream().findFirst()
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        return cloudListService.getAllResources(account, forceRefresh)
                .thenApply(allResources -> {
                    List<Map<String, Object>> elements = new ArrayList<>();

                    if (vpcId != null && !vpcId.isEmpty()) {
                        // --- VPC-specific view with connections ---
                        List<ResourceDto> vpcResources = allResources.stream()
                                .filter(r -> vpcId.equals(r.getDetails().get("VPC ID")) || vpcId.equals(r.getId()))
                                .collect(Collectors.toList());

                        // Add all resource nodes
                        for (ResourceDto resource : vpcResources) {
                            String parent = getParent(resource);
                            elements.add(createNode(resource.getId(), resource.getName(), resource.getType(), parent, resource.getDetails()));
                        }

                        // Add all relationship edges (arrows)
                        for (ResourceDto resource : vpcResources) {
                            // EBS Volume -> EC2 Instance connection
                            if ("EBS Volume".equals(resource.getType())) {
                                String attachedInstance = (String) resource.getDetails().get("Attached to");
                                if (attachedInstance != null && !attachedInstance.equals("N/A")) {
                                    elements.add(createEdge(resource.getId(), attachedInstance, "attaches to"));
                                }
                            }

                            // EC2 Instance -> Security Group connection
                            if ("EC2 Instance".equals(resource.getType())) {
                                Object sgObj = resource.getDetails().get("Security Groups");
                                List<String> securityGroups = new ArrayList<>();
                                if (sgObj instanceof List<?>) {
                                    for (Object o : (List<?>) sgObj) {
                                        if (o != null) securityGroups.add(o.toString());
                                    }
                                } else if (sgObj instanceof String) {
                                    String sgString = (String) sgObj;
                                    if (sgString != null && !sgString.isBlank()) {
                                        String[] parts = sgString.split("\\s*,\\s*");
                                        for (String part : parts) {
                                            if (!part.isBlank()) securityGroups.add(part);
                                        }
                                    }
                                }
                                if (!securityGroups.isEmpty()) {
                                    for (String sgId : securityGroups) {
                                        elements.add(createEdge(resource.getId(), sgId, "uses"));
                                    }
                                }
                            }
                        }

                    } else {
                        // --- Global view: S3 Buckets ONLY ---
                        allResources.stream()
                                .filter(r -> "S3 Bucket".equals(r.getType()))
                                .forEach(resource -> {
                                    elements.add(createNode(resource.getId(), resource.getName(), resource.getType(), null, resource.getDetails()));
                                });
                    }

                    return elements;
                });
    }

    private String getParent(ResourceDto resource) {
        // Defines the hierarchy for the graph layout
        if ("Subnet".equals(resource.getType()) || "Internet Gateway".equals(resource.getType())) {
            return (String) resource.getDetails().get("VPC ID");
        }
        String parent = (String) resource.getDetails().get("Subnet ID");
        if (parent == null) {
            parent = (String) resource.getDetails().get("VPC ID");
        }
        return parent;
    }

    private Map<String, Object> createNode(String id, String label, String type, String parent, Map<String, String> details) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("label", label != null ? label : id);
        data.put("type", type);
        if (parent != null && !parent.isBlank()) {
            data.put("parent", parent);
        }

        if (details != null) {
            details.forEach((key, value) -> {
                if (!data.containsKey(key.toLowerCase())) {
                    data.put(key, value != null ? value.toString() : "N/A");
                }
            });
        }

        Map<String, Object> node = new HashMap<>();
        node.put("data", data);
        return node;
    }

    private Map<String, Object> createEdge(String source, String target, String label) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", source + "_to_" + target);
        data.put("source", source);
        data.put("target", target);
        data.put("label", label);

        Map<String, Object> edge = new HashMap<>();
        edge.put("data", data);
        return edge;
    }
}