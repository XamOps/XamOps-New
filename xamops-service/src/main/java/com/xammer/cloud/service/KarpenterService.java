package com.xammer.cloud.service;

import com.xammer.cloud.dto.k8s.KarpenterDashboard;
import com.xammer.cloud.dto.k8s.KarpenterDashboard.*;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for fetching Karpenter data from Kubernetes clusters.
 * Fetches NodePools, EC2NodeClasses, NodeClaims, and controller status.
 */
@Service
public class KarpenterService {

    private static final Logger logger = LoggerFactory.getLogger(KarpenterService.class);
    private static final String KARPENTER_NAMESPACE = "karpenter";

    /**
     * Fetch comprehensive Karpenter dashboard data from the cluster.
     * This method fetches NodePools, EC2NodeClasses, NodeClaims, and controller
     * status.
     */
    public KarpenterDashboard fetchDashboardData(KubernetesClient client) {
        KarpenterDashboard dashboard = new KarpenterDashboard();

        try {
            logger.info("🚀 Fetching Karpenter data from cluster...");

            // 1. Check if Karpenter is installed
            boolean installed = isKarpenterInstalled(client);
            dashboard.setInstalled(installed);

            if (!installed) {
                logger.info("ℹ️ Karpenter is not installed in this cluster");
                return dashboard;
            }

            // 2. Get controller status
            fetchControllerStatus(client, dashboard);

            // 3. Fetch NodePools
            List<NodePoolInfo> nodePools = fetchNodePools(client);
            dashboard.setNodePools(nodePools);
            dashboard.setTotalNodePools(nodePools.size());
            logger.info("📊 Found {} NodePools", nodePools.size());

            // 4. Fetch EC2NodeClasses
            List<EC2NodeClassInfo> ec2NodeClasses = fetchEC2NodeClasses(client, dashboard);
            dashboard.setEc2NodeClasses(ec2NodeClasses);
            dashboard.setTotalEC2NodeClasses(ec2NodeClasses.size());
            logger.info("📊 Found {} EC2NodeClasses", ec2NodeClasses.size());

            // 5. Fetch NodeClaims
            List<NodeClaimInfo> nodeClaims = fetchNodeClaims(client);
            dashboard.setNodeClaims(nodeClaims);
            dashboard.setTotalNodeClaims(nodeClaims.size());
            logger.info("📊 Found {} NodeClaims", nodeClaims.size());

            // 6. Count nodes provisioned by Karpenter
            int karpenterNodes = countKarpenterNodes(client);
            dashboard.setNodesProvisionedByKarpenter(karpenterNodes);

            logger.info("✅ Karpenter data fetch complete");

        } catch (Exception e) {
            logger.error("❌ Error fetching Karpenter data", e);
            dashboard.setInstalled(false);
        }

        return dashboard;
    }

    private boolean isKarpenterInstalled(KubernetesClient client) {
        try {
            // Check for Karpenter NodePool CRD which is a more reliable indicator than
            // deployment
            logger.info("🔍 Checking if Karpenter CRD exists...");
            var crd = client.apiextensions().v1().customResourceDefinitions()
                    .withName("nodepools.karpenter.sh").get();
            boolean installed = crd != null;
            logger.info("📋 Karpenter CRD check result: installed={}", installed);
            return installed;
        } catch (Exception e) {
            logger.warn("⚠️ Error checking Karpenter CRD (may be permission issue): {}", e.getMessage());
            logger.debug("Full exception:", e);
            return false;
        }
    }

    private void fetchControllerStatus(KubernetesClient client, KarpenterDashboard dashboard) {
        try {
            Deployment deployment = client.apps().deployments()
                    .inNamespace(KARPENTER_NAMESPACE)
                    .withName("karpenter")
                    .get();

            if (deployment != null) {
                // Extract version from image tag
                String image = deployment.getSpec().getTemplate().getSpec()
                        .getContainers().get(0).getImage();
                String version = extractVersionFromImage(image);
                dashboard.setVersion(version);

                // Check pod status
                Integer readyReplicas = deployment.getStatus().getReadyReplicas();
                Integer desiredReplicas = deployment.getSpec().getReplicas();

                boolean healthy = readyReplicas != null && readyReplicas.equals(desiredReplicas);
                dashboard.setHealthy(healthy);
                dashboard.setPodStatus(healthy ? "Running" : "Degraded");

                // Get IAM role from ServiceAccount
                try {
                    var sa = client.serviceAccounts()
                            .inNamespace(KARPENTER_NAMESPACE)
                            .withName("karpenter")
                            .get();
                    if (sa != null && sa.getMetadata().getAnnotations() != null) {
                        String roleArn = sa.getMetadata().getAnnotations()
                                .get("eks.amazonaws.com/role-arn");
                        dashboard.setIamRoleArn(roleArn);
                    }
                } catch (Exception e) {
                    logger.debug("Could not fetch IAM role: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Error fetching controller status: {}", e.getMessage());
        }
    }

    private String extractVersionFromImage(String image) {
        if (image == null)
            return "Unknown";
        // Format: public.ecr.aws/karpenter/controller:0.37.0@sha256:...
        int colonIndex = image.lastIndexOf(':');
        int atIndex = image.indexOf('@');
        if (colonIndex > 0) {
            String versionPart = atIndex > colonIndex
                    ? image.substring(colonIndex + 1, atIndex)
                    : image.substring(colonIndex + 1);
            return versionPart;
        }
        return "Unknown";
    }

    private List<NodePoolInfo> fetchNodePools(KubernetesClient client) {
        List<NodePoolInfo> result = new ArrayList<>();

        try {
            CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                    .withGroup("karpenter.sh")
                    .withVersion("v1beta1")
                    .withPlural("nodepools")
                    .withScope("Cluster")
                    .build();

            List<GenericKubernetesResource> resources = client.genericKubernetesResources(context)
                    .list()
                    .getItems();

            for (GenericKubernetesResource resource : resources) {
                try {
                    NodePoolInfo info = parseNodePool(resource);
                    result.add(info);
                } catch (Exception e) {
                    logger.warn("Error parsing NodePool: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Error fetching NodePools: {}", e.getMessage());
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private NodePoolInfo parseNodePool(GenericKubernetesResource resource) {
        NodePoolInfo info = new NodePoolInfo();

        info.setName(resource.getMetadata().getName());
        info.setCreatedAt(resource.getMetadata().getCreationTimestamp());

        Map<String, Object> spec = (Map<String, Object>) resource.getAdditionalProperties().get("spec");
        if (spec != null) {
            // Limits
            Map<String, Object> limits = (Map<String, Object>) spec.get("limits");
            if (limits != null) {
                info.setCpuLimit(String.valueOf(limits.getOrDefault("cpu", "N/A")));
                info.setMemoryLimit(String.valueOf(limits.getOrDefault("memory", "N/A")));
            }

            // Disruption
            Map<String, Object> disruption = (Map<String, Object>) spec.get("disruption");
            if (disruption != null) {
                info.setConsolidationPolicy((String) disruption.get("consolidationPolicy"));
                info.setExpireAfter((String) disruption.get("expireAfter"));

                List<Map<String, Object>> budgets = (List<Map<String, Object>>) disruption.get("budgets");
                if (budgets != null && !budgets.isEmpty()) {
                    Object nodes = budgets.get(0).get("nodes");
                    if (nodes != null) {
                        try {
                            info.setDisruptionBudgetNodes(Integer.parseInt(nodes.toString()));
                        } catch (NumberFormatException e) {
                            info.setDisruptionBudgetNodes(1);
                        }
                    }
                }
            }

            // Template spec requirements
            Map<String, Object> template = (Map<String, Object>) spec.get("template");
            if (template != null) {
                Map<String, Object> templateSpec = (Map<String, Object>) template.get("spec");
                if (templateSpec != null) {
                    // NodeClassRef
                    Map<String, Object> nodeClassRef = (Map<String, Object>) templateSpec.get("nodeClassRef");
                    if (nodeClassRef != null) {
                        info.setNodeClassRef((String) nodeClassRef.get("name"));
                    }

                    // Requirements
                    List<Map<String, Object>> requirements = (List<Map<String, Object>>) templateSpec
                            .get("requirements");
                    if (requirements != null) {
                        for (Map<String, Object> req : requirements) {
                            String key = (String) req.get("key");
                            List<String> values = (List<String>) req.get("values");

                            if ("karpenter.sh/capacity-type".equals(key) && values != null) {
                                info.setCapacityTypes(new ArrayList<>(values));
                            } else if ("node.kubernetes.io/instance-type".equals(key) && values != null) {
                                info.setInstanceTypes(new ArrayList<>(values));
                            } else if ("kubernetes.io/arch".equals(key) && values != null) {
                                info.setArchitectures(new ArrayList<>(values));
                            }
                        }
                    }
                }
            }
        }

        return info;
    }

    private List<EC2NodeClassInfo> fetchEC2NodeClasses(KubernetesClient client, KarpenterDashboard dashboard) {
        List<EC2NodeClassInfo> result = new ArrayList<>();

        try {
            CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                    .withGroup("karpenter.k8s.aws")
                    .withVersion("v1beta1")
                    .withPlural("ec2nodeclasses")
                    .withScope("Cluster")
                    .build();

            List<GenericKubernetesResource> resources = client.genericKubernetesResources(context)
                    .list()
                    .getItems();

            for (GenericKubernetesResource resource : resources) {
                try {
                    EC2NodeClassInfo info = parseEC2NodeClass(resource, dashboard);
                    result.add(info);
                } catch (Exception e) {
                    logger.warn("Error parsing EC2NodeClass: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Error fetching EC2NodeClasses: {}", e.getMessage());
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private EC2NodeClassInfo parseEC2NodeClass(GenericKubernetesResource resource, KarpenterDashboard dashboard) {
        EC2NodeClassInfo info = new EC2NodeClassInfo();

        info.setName(resource.getMetadata().getName());
        info.setCreatedAt(resource.getMetadata().getCreationTimestamp());

        Map<String, Object> spec = (Map<String, Object>) resource.getAdditionalProperties().get("spec");
        if (spec != null) {
            info.setAmiFamily((String) spec.get("amiFamily"));
            info.setRole((String) spec.get("role"));
            info.setTags((Map<String, String>) spec.get("tags"));
        }

        Map<String, Object> status = (Map<String, Object>) resource.getAdditionalProperties().get("status");
        if (status != null) {
            // Instance Profile
            info.setInstanceProfile((String) status.get("instanceProfile"));

            // Subnets
            List<Map<String, Object>> subnets = (List<Map<String, Object>>) status.get("subnets");
            if (subnets != null) {
                List<SubnetInfo> subnetInfos = subnets.stream()
                        .map(s -> new SubnetInfo(
                                (String) s.get("id"),
                                (String) s.get("zone"),
                                (String) s.get("zoneID")))
                        .collect(Collectors.toList());
                info.setSubnets(subnetInfos);
            }

            // Security Groups
            List<Map<String, Object>> sgs = (List<Map<String, Object>>) status.get("securityGroups");
            if (sgs != null) {
                List<SecurityGroupInfo> sgInfos = sgs.stream()
                        .map(sg -> new SecurityGroupInfo(
                                (String) sg.get("id"),
                                (String) sg.get("name")))
                        .collect(Collectors.toList());
                info.setSecurityGroups(sgInfos);
            }

            // Conditions
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
            if (conditions != null) {
                for (Map<String, Object> condition : conditions) {
                    String type = (String) condition.get("type");
                    String condStatus = (String) condition.get("status");
                    String reason = (String) condition.get("reason");
                    String message = (String) condition.get("message");
                    String lastTransition = (String) condition.get("lastTransitionTime");

                    if ("Ready".equals(type)) {
                        info.setReady("True".equals(condStatus));
                        info.setReadyMessage(message);
                    }

                    // Add to dashboard conditions if not ready
                    if ("False".equals(condStatus)) {
                        StatusCondition statusCond = new StatusCondition();
                        statusCond.setType(type);
                        statusCond.setStatus(condStatus);
                        statusCond.setReason(reason);
                        statusCond.setMessage(message);
                        statusCond.setLastTransitionTime(lastTransition);
                        statusCond.setSeverity("warning");
                        dashboard.getConditions().add(statusCond);
                    }
                }
            }
        }

        return info;
    }

    private List<NodeClaimInfo> fetchNodeClaims(KubernetesClient client) {
        List<NodeClaimInfo> result = new ArrayList<>();

        try {
            CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                    .withGroup("karpenter.sh")
                    .withVersion("v1beta1")
                    .withPlural("nodeclaims")
                    .withScope("Cluster")
                    .build();

            List<GenericKubernetesResource> resources = client.genericKubernetesResources(context)
                    .list()
                    .getItems();

            for (GenericKubernetesResource resource : resources) {
                try {
                    NodeClaimInfo info = parseNodeClaim(resource);
                    result.add(info);
                } catch (Exception e) {
                    logger.warn("Error parsing NodeClaim: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Error fetching NodeClaims: {}", e.getMessage());
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private NodeClaimInfo parseNodeClaim(GenericKubernetesResource resource) {
        NodeClaimInfo info = new NodeClaimInfo();

        info.setName(resource.getMetadata().getName());
        info.setCreatedAt(resource.getMetadata().getCreationTimestamp());

        // Get nodepool from labels
        Map<String, String> labels = resource.getMetadata().getLabels();
        if (labels != null) {
            info.setNodePoolName(labels.get("karpenter.sh/nodepool"));
            info.setCapacityType(labels.get("karpenter.sh/capacity-type"));
            info.setInstanceType(labels.get("node.kubernetes.io/instance-type"));
            info.setZone(labels.get("topology.kubernetes.io/zone"));
        }

        Map<String, Object> status = (Map<String, Object>) resource.getAdditionalProperties().get("status");
        if (status != null) {
            info.setNodeName((String) status.get("nodeName"));

            List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
            if (conditions != null) {
                for (Map<String, Object> condition : conditions) {
                    if ("Ready".equals(condition.get("type"))) {
                        info.setPhase("True".equals(condition.get("status")) ? "Ready" : "NotReady");
                        break;
                    }
                }
            }
        }

        return info;
    }

    private int countKarpenterNodes(KubernetesClient client) {
        try {
            return client.nodes()
                    .withLabel("karpenter.sh/nodepool")
                    .list()
                    .getItems()
                    .size();
        } catch (Exception e) {
            logger.debug("Error counting Karpenter nodes: {}", e.getMessage());
            return 0;
        }
    }
}