package com.xammer.cloud.service;

import com.xammer.cloud.dto.k8s.KarpenterDashboard;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class KarpenterService {

    private static final Logger logger = LoggerFactory.getLogger(KarpenterService.class);

    public KarpenterDashboard fetchDashboardData(KubernetesClient client) {
        KarpenterDashboard dashboard = new KarpenterDashboard();
        try {
            logger.info("🚀 Fetching Karpenter dashboard data...");

            CompletableFuture<List<Map<String, Object>>> claimsFuture = CompletableFuture
                    .supplyAsync(() -> fetchKarpenterCRD(client, "nodeclaims"));

            CompletableFuture<List<Map<String, Object>>> poolsFuture = CompletableFuture
                    .supplyAsync(() -> fetchKarpenterCRD(client, "nodepools"));

            CompletableFuture<Map<String, Object>> metricsFuture = CompletableFuture
                    .supplyAsync(() -> fetchKarpenterMetrics(client));

            // Transform to DTOs
            dashboard.setNodeClaims(transformNodeClaims(claimsFuture.join()));
            dashboard.setNodePools(transformNodePools(poolsFuture.join()));
            dashboard.setMetrics(metricsFuture.join());

            logger.info("✅ Karpenter dashboard data fetched successfully.");

        } catch (Exception e) {
            logger.error("❌ Failed to fetch Karpenter data", e);
        }
        return dashboard;
    }

    private List<Map<String, Object>> fetchKarpenterCRD(KubernetesClient client, String plural) {
        List<String> versionsToTry = List.of("v1beta1", "v1", "v1alpha5");

        for (String version : versionsToTry) {
            try {
                CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                        .withGroup("karpenter.sh")
                        .withVersion(version)
                        .withPlural(plural)
                        .withScope("Cluster")
                        .build();

                List<GenericKubernetesResource> resources = client.genericKubernetesResources(context).list()
                        .getItems();

                if (!resources.isEmpty()) {
                    return resources.stream()
                            .map(r -> {
                                Map<String, Object> map = new HashMap<>();

                                // FIX: Convert ObjectMeta properly
                                if (r.getMetadata() != null) {
                                    Map<String, Object> metadata = new HashMap<>();
                                    metadata.put("name", r.getMetadata().getName());
                                    metadata.put("namespace", r.getMetadata().getNamespace());
                                    metadata.put("creationTimestamp", r.getMetadata().getCreationTimestamp());
                                    map.put("metadata", metadata);
                                }

                                if (r.getAdditionalProperties() != null) {
                                    map.put("spec", r.getAdditionalProperties().get("spec"));
                                    map.put("status", r.getAdditionalProperties().get("status"));
                                }
                                return map;
                            })
                            .collect(Collectors.toList());
                }
            } catch (Exception e) {
                logger.debug("Attempt for {} with version {} failed: {}", plural, version, e.getMessage());
            }
        }
        return new ArrayList<>();
    }

    // ADD THESE METHODS:
    private List<KarpenterDashboard.NodeClaim> transformNodeClaims(List<Map<String, Object>> resources) {
        List<KarpenterDashboard.NodeClaim> result = new ArrayList<>();

        for (Map<String, Object> resource : resources) {
            try {
                KarpenterDashboard.NodeClaim claim = new KarpenterDashboard.NodeClaim();

                if (resource.containsKey("metadata")) {
                    Map<String, Object> metadata = (Map<String, Object>) resource.get("metadata");
                    claim.setNodeName((String) metadata.get("name"));
                    claim.setAge(calculateAge((String) metadata.get("creationTimestamp")));
                }

                if (resource.containsKey("spec")) {
                    Map<String, Object> spec = (Map<String, Object>) resource.get("spec");

                    if (spec.containsKey("nodeClassRef")) {
                        Map<String, Object> nodeClassRef = (Map<String, Object>) spec.get("nodeClassRef");
                        claim.setNodePoolName((String) nodeClassRef.get("name"));
                    }

                    if (spec.containsKey("requirements")) {
                        List<Map<String, Object>> requirements = (List<Map<String, Object>>) spec.get("requirements");
                        for (Map<String, Object> req : requirements) {
                            String key = (String) req.get("key");
                            if ("node.kubernetes.io/instance-type".equals(key) && req.containsKey("values")) {
                                List<String> values = (List<String>) req.get("values");
                                if (!values.isEmpty())
                                    claim.setInstanceType(values.get(0));
                            }
                            if ("topology.kubernetes.io/zone".equals(key) && req.containsKey("values")) {
                                List<String> values = (List<String>) req.get("values");
                                if (!values.isEmpty())
                                    claim.setZone(values.get(0));
                            }
                        }
                    }
                }

                if (resource.containsKey("status")) {
                    Map<String, Object> status = (Map<String, Object>) resource.get("status");
                    if (status.containsKey("conditions")) {
                        List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
                        for (Map<String, Object> condition : conditions) {
                            if ("Ready".equals(condition.get("type"))) {
                                String conditionStatus = (String) condition.get("status");
                                claim.setPhase("True".equals(conditionStatus) ? "Ready" : "Provisioning");
                            }
                        }
                    }
                }

                result.add(claim);
            } catch (Exception e) {
                logger.warn("Failed to parse NodeClaim: {}", e.getMessage());
            }
        }

        return result;
    }

    private List<KarpenterDashboard.NodePool> transformNodePools(List<Map<String, Object>> resources) {
        List<KarpenterDashboard.NodePool> result = new ArrayList<>();

        for (Map<String, Object> resource : resources) {
            try {
                KarpenterDashboard.NodePool pool = new KarpenterDashboard.NodePool();

                if (resource.containsKey("metadata")) {
                    Map<String, Object> metadata = (Map<String, Object>) resource.get("metadata");
                    pool.setPoolName((String) metadata.get("name"));
                }

                if (resource.containsKey("spec")) {
                    Map<String, Object> spec = (Map<String, Object>) resource.get("spec");

                    if (spec.containsKey("limits")) {
                        Map<String, Object> limits = (Map<String, Object>) spec.get("limits");
                        if (limits.containsKey("cpu")) {
                            pool.setCpuLimit(parseCpuValue((String) limits.get("cpu")));
                        }
                        if (limits.containsKey("memory")) {
                            pool.setMemoryLimitGb(parseMemoryValue((String) limits.get("memory")));
                        }
                    }

                    if (spec.containsKey("disruption")) {
                        Map<String, Object> disruption = (Map<String, Object>) spec.get("disruption");
                        if (disruption.containsKey("consolidationPolicy")) {
                            String policy = (String) disruption.get("consolidationPolicy");
                            pool.setConsolidationEnabled(!"Never".equals(policy));
                        }
                    }

                    List<String> instanceTypes = new ArrayList<>();
                    if (spec.containsKey("template")) {
                        Map<String, Object> template = (Map<String, Object>) spec.get("template");
                        if (template.containsKey("spec")) {
                            Map<String, Object> templateSpec = (Map<String, Object>) template.get("spec");
                            if (templateSpec.containsKey("requirements")) {
                                List<Map<String, Object>> requirements = (List<Map<String, Object>>) templateSpec
                                        .get("requirements");
                                for (Map<String, Object> req : requirements) {
                                    if ("node.kubernetes.io/instance-type".equals(req.get("key"))
                                            && req.containsKey("values")) {
                                        instanceTypes.addAll((List<String>) req.get("values"));
                                    }
                                }
                            }
                        }
                    }
                    pool.setInstanceTypes(instanceTypes);
                }

                result.add(pool);
            } catch (Exception e) {
                logger.warn("Failed to parse NodePool: {}", e.getMessage());
            }
        }

        return result;
    }

    private Map<String, Object> fetchKarpenterMetrics(KubernetesClient client) {
        Map<String, Object> metrics = new HashMap<>();
        try {
            List<Pod> pods = client.pods().inAnyNamespace().withLabel("app.kubernetes.io/name", "karpenter").list()
                    .getItems();

            if (pods.isEmpty()) {
                return metrics;
            }

            Pod pod = pods.get(0);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            client.pods().inNamespace(pod.getMetadata().getNamespace()).withName(pod.getMetadata().getName())
                    .writingOutput(out)
                    .writingError(err)
                    .exec("sh", "-c", "curl -s localhost:8000/metrics || wget -qO- localhost:8000/metrics");

            String output = out.toString();
            if (!output.isEmpty()) {
                metrics.put("raw", output);
                metrics.put("nodes_created", parseMetric(output, "karpenter_nodeclaims_created"));
                metrics.put("nodes_terminated", parseMetric(output, "karpenter_nodeclaims_terminated"));
            }

        } catch (Exception e) {
            logger.warn(
                    "⚠️ Could not fetch Karpenter metrics (likely 403 Forbidden). This is expected for read-only users.");
            metrics.put("error", "Metrics unavailable (Permissions)");
        }
        return metrics;
    }

    private int parseMetric(String data, String metricName) {
        try {
            for (String line : data.split("\n")) {
                if (line.startsWith(metricName)) {
                    String[] parts = line.split(" ");
                    if (parts.length > 1) {
                        return (int) Double.parseDouble(parts[1]);
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    private String calculateAge(String creationTimestamp) {
        try {
            java.time.Instant created = java.time.Instant.parse(creationTimestamp);
            java.time.Duration age = java.time.Duration.between(created, java.time.Instant.now());

            long days = age.toDays();
            long hours = age.toHours() % 24;
            long minutes = age.toMinutes() % 60;

            if (days > 0)
                return days + "d";
            if (hours > 0)
                return hours + "h";
            return minutes + "m";
        } catch (Exception e) {
            return "N/A";
        }
    }

    private Integer parseCpuValue(String cpu) {
        try {
            if (cpu == null)
                return 0;
            if (cpu.endsWith("m")) {
                return Integer.parseInt(cpu.replace("m", "")) / 1000;
            }
            return Integer.parseInt(cpu);
        } catch (Exception e) {
            return 0;
        }
    }

    private Integer parseMemoryValue(String memory) {
        try {
            if (memory == null)
                return 0;
            if (memory.endsWith("Gi")) {
                return Integer.parseInt(memory.replace("Gi", ""));
            } else if (memory.endsWith("Mi")) {
                return Integer.parseInt(memory.replace("Mi", "")) / 1024;
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }
}