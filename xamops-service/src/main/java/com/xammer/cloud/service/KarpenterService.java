package com.xammer.cloud.service;

import com.xammer.cloud.domain.karpenter.*;
import com.xammer.cloud.dto.karpenter.KarpenterConfigDto;
import com.xammer.cloud.dto.k8s.KarpenterDashboard;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class KarpenterService {

    private static final Logger logger = LoggerFactory.getLogger(KarpenterService.class);
    private static final String KARPENTER_NAMESPACE = "karpenter";
    private static final String DEFAULT_NODE_POOL_NAME = "spot-saver";
    private static final String DEFAULT_EC2_NODE_CLASS_NAME = "default";
    private static final List<String> POC_ALLOWED_INSTANCE_FAMILIES = Arrays.asList("t3");

    @Autowired
    private EksService eksService;

    // Stub method to satisfy CloudK8sSecurityController
    public KarpenterDashboard fetchDashboardData(KubernetesClient client) {
        logger.info("fetchDashboardData called - returning placeholder data");
        KarpenterDashboard dashboard = new KarpenterDashboard();
        dashboard.setTotalNodesCreated24h(0);
        dashboard.setTotalNodesTerminated24h(0);
        dashboard.setNodeClaims(new ArrayList<>());
        dashboard.setNodePools(new ArrayList<>());
        return dashboard;
    }

    public void installKarpenter(String accountId, String clusterName, String region, String roleArn) {
        logger.info("🚀 [KarpenterService] Installing Karpenter on cluster: {} in account: {}", clusterName, accountId);
        try {
            if (!eksService.hasOIDCProvider(accountId, clusterName, region)) {
                throw new RuntimeException("OIDC provider not configured on cluster: " + clusterName);
            }
            KubernetesClient client = eksService.getKubernetesClientForCustomerCluster(accountId, clusterName, region);

            Namespace namespace = new NamespaceBuilder()
                    .withMetadata(new ObjectMetaBuilder().withName(KARPENTER_NAMESPACE).build())
                    .build();
            try {
                client.namespaces().resource(namespace).create();
            } catch (Exception e) {
                logger.warn("⚠️ Namespace creation skipped: {}", e.getMessage());
            }

            String karpenterYaml = readKarpenterManifest();
            String finalYaml = karpenterYaml
                    .replace("{{KARPENTER_ROLE_ARN}}", roleArn)
                    .replace("{{CLUSTER_NAME}}", clusterName)
                    .replace("{{ACCOUNT_ID}}", accountId)
                    .replace("{{NAMESPACE}}", KARPENTER_NAMESPACE);

            applyYamlManifests(client, finalYaml);
            logger.info("✅ Karpenter installed successfully on cluster: {}", clusterName);
        } catch (Exception e) {
            logger.error("❌ Failed to install Karpenter", e);
            throw new RuntimeException("Failed to install Karpenter: " + e.getMessage(), e);
        }
    }

    public void configureNodePool(KarpenterConfigDto config) {
        logger.info("⚙️ [KarpenterService] Configuring NodePool for cluster: {}", config.getClusterId());
        try {
            KubernetesClient client = eksService.getKubernetesClientForCustomerCluster(
                    config.getAccountId(), config.getClusterId(), config.getRegion());

            List<String> instanceFamilies = applyPocBudgetConstraint(config.getInstanceFamilies());
            String nodePoolName = config.getNodePoolName() != null ? config.getNodePoolName() : DEFAULT_NODE_POOL_NAME;
            String ec2NodeClassName = config.getEc2NodeClassName() != null ? config.getEc2NodeClassName()
                    : DEFAULT_EC2_NODE_CLASS_NAME;

            // ✅ FIX 1: Use setter injection for EC2NodeClass (bypass Builder inheritance
            // issue)
            EC2NodeClass ec2NodeClass = createEC2NodeClass(ec2NodeClassName, config.getClusterId());
            client.resources(EC2NodeClass.class).inNamespace(KARPENTER_NAMESPACE).resource(ec2NodeClass)
                    .serverSideApply();

            // ✅ FIX 2: Use setter injection for NodePool
            NodePool nodePool = createNodePool(nodePoolName, ec2NodeClassName, config.getUseSpot(), instanceFamilies,
                    config.getTtlSecondsAfterEmpty(), config.getTtlSecondsUntilExpired(),
                    config.getConsolidationEnabled());
            client.resources(NodePool.class).inNamespace(KARPENTER_NAMESPACE).resource(nodePool).serverSideApply();

            logger.info("✅ Karpenter configuration applied successfully");
        } catch (Exception e) {
            logger.error("❌ Configuration failed", e);
            throw new RuntimeException("Failed to configure NodePool: " + e.getMessage(), e);
        }
    }

    public boolean isKarpenterInstalled(String accountId, String clusterName, String region) {
        try {
            KubernetesClient client = eksService.getKubernetesClientForCustomerCluster(accountId, clusterName, region);
            return client.apps().deployments().inNamespace(KARPENTER_NAMESPACE).withName("karpenter").get() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> applyPocBudgetConstraint(List<String> userSelectedFamilies) {
        return POC_ALLOWED_INSTANCE_FAMILIES;
    }

    private EC2NodeClass createEC2NodeClass(String name, String clusterName) {
        EC2NodeClassSpec spec = EC2NodeClassSpec.builder()
                .subnetSelector(Map.of("karpenter.sh/discovery", clusterName))
                .securityGroupSelector(Map.of("karpenter.sh/discovery", clusterName))
                .amiFamily("AL2")
                .role("KarpenterNodeRole-" + clusterName)
                .tags(Map.of("ManagedBy", "karpenter", "XamOps", "true"))
                .build();

        // ✅ Direct object creation + Setters
        EC2NodeClass nodeClass = new EC2NodeClass();
        nodeClass.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(KARPENTER_NAMESPACE)
                .build());
        nodeClass.setSpec(spec);

        return nodeClass;
    }

    private NodePool createNodePool(String name, String ec2NodeClass, Boolean useSpot, List<String> families,
            Integer ttlEmpty, Integer ttlExpired, Boolean consolidation) {
        List<NodePoolRequirement> requirements = new ArrayList<>();

        List<String> capacity = new ArrayList<>();
        if (Boolean.TRUE.equals(useSpot))
            capacity.add("spot");
        capacity.add("on-demand");

        requirements.add(NodePoolRequirement.builder().key("karpenter.sh/capacity-type").operator("In").values(capacity)
                .build());

        List<String> types = new ArrayList<>();
        for (String f : families) {
            types.add(f + ".medium");
            types.add(f + ".small");
            types.add(f + ".micro");
        }
        requirements.add(NodePoolRequirement.builder().key("karpenter.k8s.aws/instance-type").operator("In")
                .values(types).build());
        requirements.add(NodePoolRequirement.builder().key("kubernetes.io/arch").operator("In").values(List.of("amd64"))
                .build());

        NodePoolTemplate template = NodePoolTemplate.builder()
                .spec(NodePoolTemplateSpec.builder()
                        .requirements(requirements)
                        .nodeClassRef(NodeClassRef.builder().name(ec2NodeClass).build())
                        .build())
                .build();

        NodePoolDisruption disruption = NodePoolDisruption.builder()
                .consolidationPolicy(Boolean.TRUE.equals(consolidation) ? "WhenUnderutilized" : "Never")
                .expireAfter("720h")
                .build();

        NodePoolSpec spec = NodePoolSpec.builder()
                .template(template)
                .limits(Map.of("cpu", "100", "memory", "100Gi"))
                .disruption(disruption)
                .ttlSecondsAfterEmpty(ttlEmpty != null ? Long.valueOf(ttlEmpty) : 30L)
                .ttlSecondsUntilExpired(ttlExpired != null ? Long.valueOf(ttlExpired) : 2592000L)
                .build();

        // ✅ Direct object creation + Setters
        NodePool nodePool = new NodePool();
        nodePool.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(KARPENTER_NAMESPACE)
                .build());
        nodePool.setSpec(spec);

        return nodePool;
    }

    private String readKarpenterManifest() throws Exception {
        ClassPathResource resource = new ClassPathResource("karpenter.yaml");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream()))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }

    private void applyYamlManifests(KubernetesClient client, String yaml) {
        client.load(new java.io.ByteArrayInputStream(yaml.getBytes())).serverSideApply();
    }
}