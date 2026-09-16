package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ResourceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.inventory.InventoryProjectionService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /**
     * Optional: absent when the inventory plane is disabled, in which case every read below
     * keeps its previous behaviour unchanged.
     */
    @Autowired(required = false)
    private InventoryProjectionService inventoryProjection;

    public CompletableFuture<List<ResourceDto>> getVpcListForCloudmap(String accountId, boolean forceRefresh) {
        try {
            CloudAccount account = getAccount(accountId);

            // One indexed query for the VPCs instead of materialising the account to keep a
            // handful of rows. getAllResources already reads the plane, so the describes were
            // not the cost here — deserialising ~1650 resources to filter down to ~5 was.
            List<ResourceDto> planeVpcs = vpcsFromPlane(account);
            if (planeVpcs != null) {
                return CompletableFuture.completedFuture(planeVpcs);
            }

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

    /**
     * VPCs straight from the inventory plane, or null when it cannot answer.
     *
     * <p>
     * Safe to serve from the plane because this list only needs id, name, region and type —
     * all of which the plane derives from the ARN. The graph below is deliberately left on
     * {@code getAllResources}: it groups nodes by the {@code "VPC ID"} detail, which the
     * plane only carries for the types it describes.
     */
    private List<ResourceDto> vpcsFromPlane(CloudAccount account) {
        if (inventoryProjection == null || account.getId() == null) {
            return null;
        }
        try {
            if (!inventoryProjection.isFresh(account.getId())) {
                return null;
            }
            List<ResourceDto> vpcs = inventoryProjection.byType(account.getId(), "VPC");
            if (vpcs.isEmpty()) {
                // An account with no VPCs is possible but rare, and indistinguishable from a
                // plane that has not indexed them yet. Falling through costs one slow read
                // and cannot render an empty picker on a customer who has VPCs.
                return null;
            }
            logger.info("✅ VPC list served from inventory plane for account {} ({} VPCs, 0 AWS calls)",
                    account.getAwsAccountId(), vpcs.size());
            return vpcs;
        } catch (Exception e) {
            logger.warn("[Inventory] VPC plane read failed for account {} — falling back: {}",
                    account.getAwsAccountId(), e.getMessage());
            return null;
        }
    }

    public CompletableFuture<List<Map<String, Object>>> getGraphData(String accountId, String vpcId, String region,
            boolean forceRefresh) {
        try {
            CloudAccount account = getAccount(accountId);
            logger.info("Fetching graph data for account {}, VPC: {}, Region: {}", accountId, vpcId, region);

            return resourcesForGraph(account, forceRefresh)
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
                                // Use default region for "ALL" VPCs, otherwise use provided region
                                String effectiveRegion = (region != null && !region.isEmpty()) ? region : "us-east-1";
                                Ec2Client ec2Client = awsClientProvider.getEc2Client(account, effectiveRegion);
                                addVpcAndSubnetNodes(elements, ec2Client, vpcId);

                                List<ResourceDto> seeds = allResources.stream()
                                        .filter(r -> r != null && ("ALL".equals(vpcId)
                                                || vpcId.equals(getDetail(r, "VPC ID")) || vpcId.equals(r.getId())))
                                        .collect(Collectors.toList());
                                relevantResources = "ALL".equals(vpcId)
                                        ? seeds
                                        : withConnectedNeighbours(seeds, allResources);
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
                                            parent, resource.getDetails(), resource.getState(),
                                            resource.getRegion()));
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

    /**
     * Resources for the graph, from the inventory plane when it can answer.
     *
     * <p>
     * {@code CloudListService.getAllResources} is a ~1000-call describe scan. Behind a
     * cache that is tolerable once, but the Cloud Map asks for it per VPC: an account
     * with 30 VPCs produced 30 concurrent cold misses, every one of them timed out, and
     * because the {@code exceptionally} handler below turns a failure into an empty
     * list the canvas rendered "30 VPCs, 0 Subnets, 0 Resources" while looking like a
     * success. The frontend now warms the cache with a single request first, and this
     * removes the scan altogether wherever the plane is usable.
     *
     * <p>
     * The plane holds one indexed row per resource and
     * {@link InventoryProjectionService#flatList} already carries the {@code "VPC ID"},
     * {@code "Subnet ID"} and {@code "Security Groups"} details this graph groups and
     * draws edges by, so one query replaces the scan.
     *
     * <p>
     * {@code isFresh} is deliberately strict and refuses whenever region coverage is
     * incomplete — Resource Explorer does not automatically index AWS opt-in regions,
     * and serving a partial estate would silently omit whole regions. So accounts with
     * resources in an opt-in region keep taking the slow path until an aggregator index
     * exists there. That is the right trade: a slow complete answer beats a fast wrong
     * one, and the alternative is a map that quietly loses a region.
     *
     * <p>
     * {@code forceRefresh} always takes the scan, because the point of asking for a
     * refresh is to bypass anything cached.
     */
    private CompletableFuture<List<ResourceDto>> resourcesForGraph(CloudAccount account, boolean forceRefresh) {
        if (!forceRefresh && inventoryProjection != null && account.getId() != null) {
            try {
                if (inventoryProjection.isFresh(account.getId())) {
                    List<ResourceDto> fromPlane = inventoryProjection.flatList(account.getId());
                    /*
                     * The plane is only allowed to answer if it knows where things live.
                     *
                     * This graph places a resource by its "VPC ID" detail and silently drops
                     * anything without one. AwsInventoryReconciler currently persists that
                     * detail from instanceDetails(Instance) alone, so EC2 instances carry it
                     * and every other type is stored with empty details. Serving the graph
                     * from the plane therefore returned a VPC containing nothing but EC2 —
                     * no security groups, load balancers, gateways or interfaces — which the
                     * describe scan does return, because CloudListService sets "VPC ID" for
                     * all of them.
                     *
                     * Counting the detail rather than assuming it is present means this fixes
                     * itself: as the reconciler learns to persist VPC associations for more
                     * types the plane starts qualifying again, with no change here. Until
                     * then a slower scan that shows the whole topology beats an instant one
                     * that quietly omits most of it.
                     */
                    long withVpc = fromPlane.stream()
                            .filter(r -> r != null && getDetail(r, "VPC ID") != null)
                            .count();
                    if (!fromPlane.isEmpty() && withVpc > 0) {
                        logger.info("✅ Cloud Map graph served from inventory plane for account {} "
                                + "({} resources, {} with a VPC association, 0 describe calls)",
                                account.getAwsAccountId(), fromPlane.size(), withVpc);
                        return CompletableFuture.completedFuture(fromPlane);
                    }
                    logger.info("[Inventory] Plane has {} resources for account {} but {} carry a "
                            + "\"VPC ID\"; using a describe scan so non-EC2 services are not dropped",
                            fromPlane.size(), account.getAwsAccountId(), withVpc);
                }
            } catch (Exception e) {
                // A plane read failing must not cost the whole page; the scan still works.
                logger.warn("[Inventory] Cloud Map plane read failed for account {} — falling back "
                        + "to describes: {}", account.getAwsAccountId(), e.getMessage());
            }
        }
        logger.info("Cloud Map graph falling back to a describe scan for account {}",
                account.getAwsAccountId());
        return cloudListService.getAllResources(account, forceRefresh);
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

    /**
     * Widen a VPC-scoped selection to the resources it is actually wired to.
     *
     * <p>
     * Selecting purely on the {@code "VPC ID"} detail is what made the graph look
     * disconnected. {@link #createAllEdges} models thirteen relationships, but an edge
     * only survives rendering if both of its endpoints are present, and most of the
     * types on the far end of those relationships carry no {@code "VPC ID"} at all.
     * A load balancer in the VPC therefore lost the chain
     * {@code Load Balancer -> Target Group -> EC2 Instance} at the first hop, because
     * the target group was filtered out; the same happened to
     * {@code EBS Volume -> EC2 Instance} and to every {@code uses SG} edge from RDS or
     * Lambda. The relationships existed in the model and vanished from the picture.
     *
     * <p>
     * Edges are built once over the whole account and used purely as an adjacency
     * probe, then any resource sharing an edge with a seed is pulled in. Both
     * directions matter: a target group names its instances rather than the reverse,
     * so walking outward from the seeds alone would never reach it.
     *
     * <p>
     * Only a single hop is taken. Two hops would drag in most of the account through
     * shared security groups and defeat the point of scoping to a VPC. Neighbours
     * arrive without a {@code "VPC ID"}, so {@link #getParent} leaves them parentless
     * and the canvas collects them in its global scope box — which is honest: they are
     * related to this VPC without living inside it.
     */
    private List<ResourceDto> withConnectedNeighbours(List<ResourceDto> seeds,
                                                      List<ResourceDto> allResources) {
        if (seeds.isEmpty() || allResources.isEmpty()) {
            return seeds;
        }
        Set<String> seedIds = seeds.stream()
                .filter(Objects::nonNull)
                .map(ResourceDto::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Map<String, Object>> probe = new ArrayList<>();
        try {
            createAllEdges(probe, allResources);
        } catch (Exception e) {
            // The adjacency probe is an enhancement; losing it must not lose the graph.
            logger.warn("Could not probe resource adjacency, showing the VPC alone: {}", e.getMessage());
            return seeds;
        }

        Set<String> neighbourIds = new HashSet<>();
        for (Map<String, Object> edge : probe) {
            Object raw = edge.get("data");
            if (!(raw instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) raw;
            Object source = data.get("source");
            Object target = data.get("target");
            if (!(source instanceof String) || !(target instanceof String)) {
                continue;
            }
            boolean sourceIsSeed = seedIds.contains(source);
            boolean targetIsSeed = seedIds.contains(target);
            if (sourceIsSeed ^ targetIsSeed) {
                neighbourIds.add((String) (sourceIsSeed ? target : source));
            }
        }
        if (neighbourIds.isEmpty()) {
            return seeds;
        }

        List<ResourceDto> widened = new ArrayList<>(seeds);
        for (ResourceDto r : allResources) {
            if (r != null && r.getId() != null
                    && neighbourIds.contains(r.getId())
                    && !seedIds.contains(r.getId())) {
                widened.add(r);
            }
        }
        logger.info("VPC selection widened from {} to {} resources by following {} connections",
                seeds.size(), widened.size(), neighbourIds.size());
        return widened;
    }

    private void createAllEdges(List<Map<String, Object>> elements, List<ResourceDto> resources) {
        for (ResourceDto resource : resources) {
            String type = resource.getType();

            // EBS Volume -> EC2 Instance
            if ("EBS Volume".equals(type)) {
                String attachedInstance = getDetail(resource, "Attached to");
                if (attachedInstance != null && !attachedInstance.equals("N/A")) {
                    elements.add(createEdge(resource.getId(), attachedInstance, "attaches to"));
                }
            }

            // EC2 Instance -> Security Groups, IAM Role, ENI
            if ("EC2 Instance".equals(type)) {
                addSecurityGroupEdges(elements, resource, "uses SG");
                String iamRole = getDetail(resource, "IAM Role");
                if (iamRole != null && !iamRole.isBlank() && !iamRole.equals("N/A")) {
                    elements.add(createEdge(resource.getId(), iamRole, "assumes"));
                }
            }

            // Load Balancer -> Security Groups, Target Groups
            if ("Load Balancer".equals(type)) {
                addSecurityGroupEdges(elements, resource, "uses SG");
                String targetGroups = getDetail(resource, "Target Groups");
                if (targetGroups != null && !targetGroups.isBlank()) {
                    for (String tgArn : targetGroups.split("\\s*,\\s*")) {
                        if (!tgArn.trim().isEmpty()) {
                            elements.add(createEdge(resource.getId(), tgArn.trim(), "routes to"));
                        }
                    }
                }
            }

            // Target Group -> EC2 Instances
            if ("Target Group".equals(type)) {
                String targets = getDetail(resource, "Targets");
                if (targets != null && !targets.isBlank()) {
                    for (String targetId : targets.split("\\s*,\\s*")) {
                        if (!targetId.trim().isEmpty()) {
                            elements.add(createEdge(resource.getId(), targetId.trim(), "targets"));
                        }
                    }
                }
            }

            // RDS Instance -> Security Groups
            if ("RDS Instance".equals(type)) {
                addSecurityGroupEdges(elements, resource, "uses SG");
            }

            // Lambda -> Security Groups, VPC
            if ("Lambda Function".equals(type)) {
                addSecurityGroupEdges(elements, resource, "uses SG");
            }

            // NAT Gateway -> Elastic IP, Subnet
            if ("NAT Gateway".equals(type)) {
                String elasticIp = getDetail(resource, "Elastic IP");
                if (elasticIp != null && !elasticIp.isBlank() && !elasticIp.equals("N/A")) {
                    elements.add(createEdge(resource.getId(), elasticIp, "uses EIP"));
                }
            }

            // Internet Gateway -> VPC (already handled by parent relationship)

            // ECS Service -> Security Groups, Load Balancer
            if ("ECS Service".equals(type)) {
                addSecurityGroupEdges(elements, resource, "uses SG");
                String loadBalancer = getDetail(resource, "Load Balancer");
                if (loadBalancer != null && !loadBalancer.isBlank() && !loadBalancer.equals("N/A")) {
                    elements.add(createEdge(resource.getId(), loadBalancer, "behind"));
                }
            }

            // EKS Cluster -> Security Groups
            if ("EKS Cluster".equals(type)) {
                addSecurityGroupEdges(elements, resource, "uses SG");
            }

            // ElastiCache -> Security Groups
            if ("ElastiCache Cluster".equals(type)) {
                addSecurityGroupEdges(elements, resource, "uses SG");
            }

            // EFS -> Security Groups
            if ("EFS".equals(type)) {
                addSecurityGroupEdges(elements, resource, "uses SG");
            }

            // Elastic IP -> EC2 Instance
            if ("Elastic IP".equals(type)) {
                String instanceId = getDetail(resource, "Instance ID");
                if (instanceId != null && !instanceId.isBlank() && !instanceId.equals("N/A")) {
                    elements.add(createEdge(resource.getId(), instanceId, "assigned to"));
                }
            }

            // Auto Scaling Group -> EC2 Instances, Load Balancer
            if ("Auto Scaling Group".equals(type)) {
                String instances = getDetail(resource, "Instances");
                if (instances != null && !instances.isBlank()) {
                    for (String instanceId : instances.split("\\s*,\\s*")) {
                        if (!instanceId.trim().isEmpty()) {
                            elements.add(createEdge(resource.getId(), instanceId.trim(), "manages"));
                        }
                    }
                }
                String targetGroups = getDetail(resource, "Target Groups");
                if (targetGroups != null && !targetGroups.isBlank()) {
                    for (String tgArn : targetGroups.split("\\s*,\\s*")) {
                        if (!tgArn.trim().isEmpty()) {
                            elements.add(createEdge(resource.getId(), tgArn.trim(), "registers to"));
                        }
                    }
                }
            }
        }
    }

    private void addSecurityGroupEdges(List<Map<String, Object>> elements, ResourceDto resource, String label) {
        String securityGroups = getDetail(resource, "Security Groups");
        if (securityGroups != null && !securityGroups.isBlank()) {
            for (String sgId : securityGroups.split("\\s*,\\s*")) {
                if (!sgId.trim().isEmpty()) {
                    elements.add(createEdge(resource.getId(), sgId.trim(), label));
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
        return createNode(id, label, type, parent, details, null, null);
    }

    /**
     * Build one Cytoscape node.
     *
     * <p>
     * {@code state} and {@code region} are passed explicitly rather than left to
     * {@code details}: the canvas renders a status dot and a region caption per
     * resource, and {@link ResourceDto} carries both as first-class fields that
     * the details map does not duplicate. Without them every node on the map
     * rendered as "unknown" state, which is why the graph could not be read at a
     * glance.
     */
    private Map<String, Object> createNode(String id, String label, String type, String parent,
            Map<String, String> details, String state, String region) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", id);
        data.put("label", label != null && !label.isBlank() ? label : id);
        data.put("type", type);
        if (parent != null && !parent.isBlank()) {
            data.put("parent", parent);
        }
        if (state != null && !state.isBlank()) {
            data.put("state", state);
        }
        if (region != null && !region.isBlank()) {
            data.put("region", region);
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