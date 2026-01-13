package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;

@Service
public class PrometheusService {

    private static final Logger logger = LoggerFactory.getLogger(PrometheusService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PrometheusService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    private String normalizeUrl(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ============================================================================================
    // COST METRICS (3 queries) - Updated to use double prefix
    // ============================================================================================
    public Double getClusterDailyCost(String prometheusUrl, String clusterName) {
        // Use max to deduplicate (same value from multiple sources)
        String query = String.format(
                "max(xamops_eks_xamops_eks_cluster_total_daily_cost{cluster_name=\"%s\"})",
                clusterName);
        return fetchScalarValue(prometheusUrl, query);
    }

    public Map<String, Double> getNodeCostPerHour(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (instance_id) (xamops_eks_xamops_eks_node_cost_per_hour{cluster_name=\"%s\"})",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "instance_id"); // ✅ CORRECT
    }

    public Map<String, Double> getNodeDailyCost(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (instance_id) (xamops_eks_xamops_eks_node_daily_cost{cluster_name=\"%s\"})",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "instance_id"); // ✅ CORRECT
    }

    // ============================================================================================
    // SECURITY METRICS - FALCO (2 queries)
    // ============================================================================================

    public Double getFalcoEventsTotal(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum(increase(xamops_eks_falcosecurity_falcosidekick_falco_events_total{cluster_name=\"%s\"}[1h]))",
                clusterName);
        return fetchScalarValue(prometheusUrl, query);
    }

    public Double getFalcoInputsTotal(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum(last_over_time(xamops_eks_falcosecurity_falcosidekick_inputs_total{cluster_name=\"%s\"}[5m]))",
                clusterName);
        return fetchScalarValue(prometheusUrl, query);
    }

    public List<Map<String, String>> getFalcoEventsByPriority(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (priority, rule, k8s_pod_name, k8s_ns_name) (increase(xamops_eks_falcosecurity_falcosidekick_falco_events_total{cluster_name=\"%s\"}[1h])) > 0",
                clusterName);
        return fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("priority", "rule", "k8s_pod_name", "k8s_ns_name"));
    }

    // ============================================================================================
    // NODE METRICS (4 queries)
    // ============================================================================================

    public List<Map<String, String>> getNodeInfo(String prometheusUrl, String clusterName) {
        String query = String.format(
                "last_over_time(xamops_eks_kube_node_info{cluster_name=\"%s\"}[24h])",
                clusterName);
        List<Map<String, String>> nodes = fetchMetricLabels(
                prometheusUrl, query,
                Arrays.asList("node", "internal_ip", "os_image", "kubelet_version", "provider_id"));

        // Fallback: try without cluster filter
        if (nodes.isEmpty()) {
            logger.warn("No nodes found with cluster_name filter, trying standard kube_node_info");
            query = "kube_node_info";
            nodes = fetchMetricLabels(prometheusUrl, query,
                    Arrays.asList("node", "internal_ip", "os_image", "kubelet_version", "provider_id"));
        }

        logger.info("Found {} nodes for cluster {}", nodes.size(), clusterName);
        return nodes;
    }

    public Map<String, Map<String, Double>> getNodeCapacity(String prometheusUrl, String clusterName) {
        logger.info("🔍 Fetching node capacity for cluster: {}", clusterName);

        String cpuQuery = String.format(
                "max by (node) (xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"cpu\"})",
                clusterName);
        Map<String, Double> cpuCapacity = fetchMetricMap(prometheusUrl, cpuQuery, "node");
        logger.info("🔍 CPU capacity returned {} nodes", cpuCapacity.size());

        String memQuery = String.format(
                "max by (node) (xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"memory\"}) / 1024 / 1024 / 1024",
                clusterName);
        Map<String, Double> memCapacity = fetchMetricMap(prometheusUrl, memQuery, "node");
        logger.info("🔍 Memory capacity returned {} nodes", memCapacity.size());

        String podsQuery = String.format(
                "max by (node) (xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"pods\"})",
                clusterName);
        Map<String, Double> podsCapacity = fetchMetricMap(prometheusUrl, podsQuery, "node");
        logger.info("🔍 Pods capacity returned {} nodes", podsCapacity.size());

        // Build result map
        Map<String, Map<String, Double>> result = new HashMap<>();
        Set<String> allNodes = new HashSet<>();
        allNodes.addAll(cpuCapacity.keySet());
        allNodes.addAll(memCapacity.keySet());
        allNodes.addAll(podsCapacity.keySet());

        logger.info("🔍 Combined unique nodes: {}", allNodes);

        for (String node : allNodes) {
            Map<String, Double> nodeCapacity = new HashMap<>();
            nodeCapacity.put("cpu", cpuCapacity.getOrDefault(node, 0.0));
            nodeCapacity.put("memory", memCapacity.getOrDefault(node, 0.0));
            nodeCapacity.put("pods", podsCapacity.getOrDefault(node, 0.0));
            result.put(node, nodeCapacity);
            logger.info("✅ Node capacity for {}: cpu={}, mem={}, pods={}",
                    node,
                    nodeCapacity.get("cpu"),
                    nodeCapacity.get("memory"),
                    nodeCapacity.get("pods"));
        }

        logger.info("✅ Node capacity data: {} nodes found", result.size());
        return result;
    }

    public Map<String, Map<String, Double>> getNodeAllocatable(String prometheusUrl, String clusterName) {
        logger.info("🔍 Fetching node allocatable for cluster: {}", clusterName);

        String cpuQuery = String.format(
                "max by (node) (xamops_eks_kube_node_status_allocatable{cluster_name=\"%s\", resource=\"cpu\"})",
                clusterName);
        Map<String, Double> cpuAllocatable = fetchMetricMap(prometheusUrl, cpuQuery, "node");
        logger.info("🔍 CPU allocatable returned {} nodes", cpuAllocatable.size());

        String memQuery = String.format(
                "max by (node) (xamops_eks_kube_node_status_allocatable{cluster_name=\"%s\", resource=\"memory\"}) / 1024 / 1024 / 1024",
                clusterName);
        Map<String, Double> memAllocatable = fetchMetricMap(prometheusUrl, memQuery, "node");
        logger.info("🔍 Memory allocatable returned {} nodes", memAllocatable.size());

        String podsQuery = String.format(
                "max by (node) (xamops_eks_kube_node_status_allocatable{cluster_name=\"%s\", resource=\"pods\"})",
                clusterName);
        Map<String, Double> podsAllocatable = fetchMetricMap(prometheusUrl, podsQuery, "node");
        logger.info("🔍 Pods allocatable returned {} nodes", podsAllocatable.size());

        Map<String, Map<String, Double>> result = new HashMap<>();
        Set<String> allNodes = new HashSet<>();
        allNodes.addAll(cpuAllocatable.keySet());
        allNodes.addAll(memAllocatable.keySet());
        allNodes.addAll(podsAllocatable.keySet());

        for (String node : allNodes) {
            Map<String, Double> nodeAllocatable = new HashMap<>();
            nodeAllocatable.put("cpu", cpuAllocatable.getOrDefault(node, 0.0));
            nodeAllocatable.put("memory", memAllocatable.getOrDefault(node, 0.0));
            nodeAllocatable.put("pods", podsAllocatable.getOrDefault(node, 0.0));
            result.put(node, nodeAllocatable);
            logger.info("✅ Node allocatable for {}: cpu={}, mem={}, pods={}",
                    node,
                    nodeAllocatable.get("cpu"),
                    nodeAllocatable.get("memory"),
                    nodeAllocatable.get("pods"));
        }

        logger.info("✅ Node allocatable data: {} nodes found", result.size());
        return result;
    }

    public Map<String, String> getNodeConditions(String prometheusUrl, String clusterName) {
        String query = String.format(
                "last_over_time(xamops_eks_kube_node_status_condition{cluster_name=\"%s\", status=\"true\"}[24h]) == 1",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(
                prometheusUrl, query, Arrays.asList("node", "condition"));

        Map<String, String> nodeConditions = new HashMap<>();
        for (Map<String, String> result : results) {
            String node = result.get("node");
            String condition = result.get("condition");
            if (node != null && condition != null) {
                nodeConditions.put(node, condition);
            }
        }
        return nodeConditions;
    }

    // ============================================================================================
    // CONTAINER METRICS (3 queries)
    // ============================================================================================

    public Map<String, Double> getContainerCpuUsage(String prometheusUrl, String clusterName) {
        // Container metrics use 'instance' label with format: namespace.pod.container
        String query = String.format(
                "sum by (instance, job) (rate(xamops_eks_container_cpu_usage{cluster_name=\"%s\"}[5m])) * 1000",
                clusterName);

        Map<String, Double> rawResult = fetchMetricMapMultiLabel(prometheusUrl, query, "instance", "job");

        // Transform instance format from "namespace.pod.container" to "pod/container"
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, Double> entry : rawResult.entrySet()) {
            String instanceJob = entry.getKey(); // Format: "namespace.pod.container/namespace/workload"
            String instance = instanceJob.split("/")[0]; // Get "namespace.pod.container"

            String[] parts = instance.split("\\.");
            if (parts.length >= 3) {
                // parts[0] = namespace, parts[1] = pod, parts[2+] = container
                String pod = parts[1];
                String container = String.join(".", Arrays.copyOfRange(parts, 2, parts.length));
                String key = pod + "/" + container;
                result.put(key, entry.getValue());
            }
        }

        return result;
    }

    public Map<String, Double> getContainerMemoryUsage(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (instance, job) (xamops_eks_container_memory_usage_bytes{cluster_name=\"%s\"}) / 1024 / 1024",
                clusterName);

        Map<String, Double> rawResult = fetchMetricMapMultiLabel(prometheusUrl, query, "instance", "job");

        // Transform instance format
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, Double> entry : rawResult.entrySet()) {
            String instanceJob = entry.getKey();
            String instance = instanceJob.split("/")[0];

            String[] parts = instance.split("\\.");
            if (parts.length >= 3) {
                String pod = parts[1];
                String container = String.join(".", Arrays.copyOfRange(parts, 2, parts.length));
                String key = pod + "/" + container;
                result.put(key, entry.getValue());
            }
        }

        return result;
    }

    public Map<String, Double> getContainerFsUsage(String prometheusUrl, String clusterName) {
        // ✅ FIX: Use the same instance/job format as CPU and Memory
        String query = String.format(
                "sum by (instance, job) (last_over_time(xamops_eks_container_filesystem_usage_bytes{cluster_name=\"%s\"}[5m])) / 1024 / 1024 / 1024",
                clusterName);

        Map<String, Double> rawResult = fetchMetricMapMultiLabel(prometheusUrl, query, "instance", "job");

        // ✅ Transform instance format from "namespace.pod.container" to "pod/container"
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, Double> entry : rawResult.entrySet()) {
            String instanceJob = entry.getKey();
            String instance = instanceJob.split("/")[0];

            String[] parts = instance.split("\\.");
            if (parts.length >= 3) {
                String pod = parts[1];
                String container = String.join(".", Arrays.copyOfRange(parts, 2, parts.length));
                String key = pod + "/" + container;
                result.put(key, entry.getValue());
            }
        }

        logger.info("✅ Container FS usage: {} entries", result.size());
        return result;
    }

    // ============================================================================================
    // POD METRICS (5 queries)
    // ============================================================================================

    public List<Map<String, String>> getPodInfo(String prometheusUrl, String clusterName) {
        List<Map<String, String>> pods = new ArrayList<>();

        // Try different metric name patterns (including double prefix
        // xamops_eks_xamops_eks_)
        String[] patterns = {
                "max by (pod, namespace, node, pod_ip) (xamops_eks_kube_pod_info{cluster_name=\"%s\"})",
                "max by (pod, namespace, node, pod_ip) (xamops_eks_xamops_eks_kube_pod_info{cluster_name=\"%s\"})",
                "kube_pod_info{cluster_name=\"%s\"}",
                "kube_pod_info"
        };

        for (String pattern : patterns) {
            String query = pattern.contains("%s") ? String.format(pattern, clusterName) : pattern;
            pods = fetchMetricLabels(prometheusUrl, query, Arrays.asList("pod", "namespace", "node", "pod_ip"));
            if (!pods.isEmpty()) {
                logger.info("✅ Found {} pods using: {}", pods.size(),
                        pattern.substring(0, Math.min(50, pattern.length())));
                break;
            }
        }

        if (pods.isEmpty()) {
            logger.warn("❌ No pods found for cluster {}", clusterName);
        }
        return pods;
    }

    public Map<String, String> getPodStatusPhase(String prometheusUrl, String clusterName) {
        String query = String.format(
                "last_over_time(xamops_eks_kube_pod_status_phase{cluster_name=\"%s\"}[24h]) == 1",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(
                prometheusUrl, query, Arrays.asList("pod", "phase"));

        Map<String, String> podPhases = new HashMap<>();
        for (Map<String, String> result : results) {
            String pod = result.get("pod");
            String phase = result.get("phase");
            if (pod != null && phase != null) {
                podPhases.put(pod, phase);
            }
        }
        return podPhases;
    }

    public Map<String, Boolean> getPodContainerStatus(String prometheusUrl, String clusterName) {
        String query = String.format(
                "last_over_time(xamops_eks_kube_pod_container_status_ready{cluster_name=\"%s\"}[5m]) == 1",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(
                prometheusUrl, query, Arrays.asList("pod", "container"));

        Map<String, Boolean> containerStatus = new HashMap<>();
        for (Map<String, String> result : results) {
            String pod = result.get("pod");
            if (pod != null) {
                containerStatus.put(pod, true);
            }
        }
        return containerStatus;
    }

    public Map<String, Double> getPodCpuUsage(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod) (rate(xamops_eks_k8s_pod_cpu_usage{cluster_name=\"%s\"}[5m])) * 1000",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "pod");
    }

    public Map<String, Double> getPodMemoryUsage(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod) (last_over_time(xamops_eks_k8s_pod_memory_usage_bytes{cluster_name=\"%s\"}[5m])) / 1024 / 1024",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "pod");
    }

    // ============================================================================================
    // DEPLOYMENT METRICS (2 queries)
    // ============================================================================================

    public Map<String, Double> getDeploymentReplicasAvailable(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (deployment) (last_over_time(xamops_eks_kube_deployment_status_replicas_available{cluster_name=\"%s\"}[24h]))",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "deployment");
    }

    public Map<String, Double> getDeploymentReplicasDesired(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (deployment) (last_over_time(xamops_eks_kube_deployment_spec_replicas{cluster_name=\"%s\"}[24h]))",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "deployment");
    }

    // ============================================================================================
    // DAEMONSET METRICS (2 queries)
    // ============================================================================================

    public Map<String, Double> getDaemonsetDesired(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (daemonset) (last_over_time(xamops_eks_kube_daemonset_status_desired_number_scheduled{cluster_name=\"%s\"}[24h]))",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "daemonset");
    }

    public Map<String, Double> getDaemonsetReady(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (daemonset) (last_over_time(xamops_eks_kube_daemonset_status_number_ready{cluster_name=\"%s\"}[24h]))",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "daemonset");
    }

    // ============================================================================================
    // NAMESPACE METRICS (2 queries)
    // ============================================================================================

    public Map<String, Double> getNamespaceCreated(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (namespace) (last_over_time(xamops_eks_kube_namespace_created{cluster_name=\"%s\"}[24h]))",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "namespace");
    }

    public Map<String, String> getNamespaceStatusPhase(String prometheusUrl, String clusterName) {
        String query = String.format(
                "last_over_time(xamops_eks_kube_namespace_status_phase{cluster_name=\"%s\"}[24h]) == 1",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(
                prometheusUrl, query, Arrays.asList("namespace", "phase"));

        Map<String, String> namespacePhases = new HashMap<>();
        for (Map<String, String> result : results) {
            String namespace = result.get("namespace");
            String phase = result.get("phase");
            if (namespace != null && phase != null) {
                namespacePhases.put(namespace, phase);
            }
        }
        return namespacePhases;
    }

    // ============================================================================================
    // EXISTING METHODS (Enhanced)
    // ============================================================================================

    public Double getClusterCpuUsage(String prometheusUrl, String clusterName) {
        // kubeletstats metric tracks cumulative CPU time across all modes
        // The rate gives CPU-seconds per second, but includes all modes (user, system,
        // idle, etc.)
        // Multiplying by 100 converts fraction to percentage
        String query = String.format(
                "(sum(rate(xamops_eks_container_cpu_usage{cluster_name=\"%s\"}[5m])) / " +
                        "sum(xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"cpu\"})) * 100",
                clusterName, clusterName);
        return fetchScalarValue(prometheusUrl, query);
    }

    public Double getClusterMemoryUsage(String prometheusUrl, String clusterName) {
        // Fix: working_set is the ACTUAL usage, not the denominator
        String query = String.format(
                "sum(xamops_eks_k8s_node_memory_working_set_bytes{cluster_name=\"%s\"}) / sum(xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"memory\"}) * 100",
                clusterName, clusterName);
        return fetchScalarValue(prometheusUrl, query);
    }

    public Integer getClusterNodeCount(String prometheusUrl, String clusterName) {
        String query = String.format(
                "count(count by (node) (xamops_eks_kube_node_info{cluster_name=\"%s\"}))",
                clusterName);
        Double val = fetchScalarValue(prometheusUrl, query);
        return val != null ? val.intValue() : 0;
    }

    public Integer getClusterPodCount(String prometheusUrl, String clusterName) {
        // Use count_values to deduplicate, then count unique pods
        String query = String.format(
                "count(max by (pod, namespace) (xamops_eks_kube_pod_status_phase{cluster_name=\"%s\",phase=\"Running\"} == 1))",
                clusterName);
        Double val = fetchScalarValue(prometheusUrl, query);
        return val != null ? val.intValue() : 0;
    }

    // ============================================================================================
    // HELPER METHODS
    // ============================================================================================

    private Map<String, Double> fetchMetricMapMultiLabel(String baseUrl, String query, String label1, String label2) {
        Map<String, Double> result = new HashMap<>();
        if (baseUrl == null || baseUrl.isEmpty()) {
            logger.warn("Prometheus URL is null or empty");
            return result;
        }

        try {
            URI url = buildUrl(baseUrl, query);
            logger.debug("Querying Prometheus: {}", query);

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (!isSuccess(root)) {
                logger.error("Prometheus query failed: {}", root.path("error").asText());
                return result;
            }

            JsonNode results = root.path("data").path("result");
            if (results.isArray()) {
                for (JsonNode item : results) {
                    String key1 = item.path("metric").path(label1).asText(null);
                    String key2 = item.path("metric").path(label2).asText(null);
                    double value = parseValue(item.path("value"));

                    if (key1 != null && key2 != null) {
                        String compositeKey = key1 + "/" + key2;
                        result.put(compositeKey, value);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching multi-label metric map: {}", e.getMessage(), e);
        }

        return result;
    }

    private List<Map<String, String>> fetchMetricLabels(String baseUrl, String query, List<String> labelsToExtract) {
        List<Map<String, String>> resultList = new ArrayList<>();
        if (baseUrl == null || baseUrl.isEmpty()) {
            logger.warn("Prometheus URL is null or empty");
            return resultList;
        }

        try {
            URI url = buildUrl(baseUrl, query);
            logger.debug("Querying Prometheus: {}", query);

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (!isSuccess(root)) {
                logger.error("Prometheus query failed: {}", root.path("error").asText());
                return resultList;
            }

            JsonNode results = root.path("data").path("result");
            if (results.isArray()) {
                for (JsonNode item : results) {
                    JsonNode metricNode = item.path("metric");
                    Map<String, String> map = new HashMap<>();

                    for (String label : labelsToExtract) {
                        if (metricNode.has(label)) {
                            map.put(label, metricNode.get(label).asText());
                        }
                    }

                    // Also add the value if present
                    JsonNode valueNode = item.path("value");
                    if (valueNode.isArray() && valueNode.size() >= 2) {
                        map.put("value", valueNode.get(1).asText());
                    }

                    if (!map.isEmpty()) {
                        resultList.add(map);
                    }
                }
            }

            logger.debug("Found {} results", resultList.size());
        } catch (Exception e) {
            logger.error("Error fetching metric labels: {}", e.getMessage(), e);
        }

        return resultList;
    }

    private Map<String, Double> fetchMetricMap(String baseUrl, String query, String keyLabel) {
        Map<String, Double> result = new HashMap<>();
        if (baseUrl == null || baseUrl.isEmpty()) {
            logger.warn("Prometheus URL is null or empty");
            return result;
        }

        try {
            URI url = buildUrl(baseUrl, query);
            logger.debug("Querying Prometheus: {}", query);

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (!isSuccess(root)) {
                logger.error("Prometheus query failed: {}", root.path("error").asText());
                return result;
            }

            JsonNode results = root.path("data").path("result");
            if (results.isArray()) {
                for (JsonNode item : results) {
                    String key = item.path("metric").path(keyLabel).asText(null);
                    double value = parseValue(item.path("value"));

                    if (key != null) {
                        result.put(key, value);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching metric map: {}", e.getMessage(), e);
        }

        return result;
    }

    private Double fetchScalarValue(String baseUrl, String query) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            logger.warn("Prometheus URL is null or empty");
            return 0.0;
        }

        try {
            URI url = buildUrl(baseUrl, query);
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (!isSuccess(root)) {
                logger.error("Prometheus query failed: {}", root.path("error").asText());
                return 0.0;
            }

            JsonNode results = root.path("data").path("result");
            if (results.isArray() && results.size() > 0) {
                return parseValue(results.get(0).path("value"));
            }
        } catch (Exception e) {
            logger.error("Error fetching scalar value: {}", e.getMessage(), e);
        }

        return 0.0;
    }

    private URI buildUrl(String baseUrl, String query) {
        String cleanUrl = normalizeUrl(baseUrl);
        return UriComponentsBuilder.fromHttpUrl(cleanUrl)
                .path("/api/v1/query")
                .queryParam("query", query)
                .build()
                .toUri();
    }

    private boolean isSuccess(JsonNode root) {
        return "success".equals(root.path("status").asText());
    }

    private double parseValue(JsonNode valueNode) {
        if (valueNode.isArray() && valueNode.size() >= 2) {
            try {
                return Double.parseDouble(valueNode.get(1).asText());
            } catch (NumberFormatException e) {
                logger.warn("Failed to parse value: {}", valueNode.get(1).asText());
                return 0.0;
            }
        }
        return 0.0;
    }

    // ============================================================================================
    // MISSING METHODS - ADD THESE
    // ============================================================================================

    public List<Map<String, String>> getClusterNodes(String prometheusUrl, String clusterName) {
        return getNodeInfo(prometheusUrl, clusterName);
    }

    public List<Map<String, String>> getClusterPods(String prometheusUrl, String clusterName) {
        return getPodInfo(prometheusUrl, clusterName);
    }

    public Map<String, String> getClusterPodStatuses(String prometheusUrl, String clusterName) {
        return getPodStatusPhase(prometheusUrl, clusterName);
    }

    public Map<String, Double> getPodRestarts(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod) (last_over_time(xamops_eks_kube_pod_container_status_restarts_total{cluster_name=\"%s\"}[24h]))",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "pod");
    }

    public Map<String, Double> getPodCreationTime(String prometheusUrl, String clusterName) {
        String query = String.format(
                "last_over_time(xamops_eks_kube_pod_created{cluster_name=\"%s\"}[24h])",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "pod");
    }

    public Map<String, Double> getNodeCpuUsage(String prometheusUrl, String clusterName) {
        String query = String.format(
                "100 - (avg by (instance) (rate(xamops_eks_node_cpu_seconds_total{cluster_name=\"%s\", mode=\"idle\"}[5m])) * 100)",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "instance");
    }

    public Map<String, Double> getNodeMemoryUsage(String prometheusUrl, String clusterName) {
        String query = String.format(
                "100 * (1 - (sum by (hostname) (xamops_eks_node_memory_MemAvailable_bytes{cluster_name=\"%s\"}) / " +
                        "sum by (hostname) (xamops_eks_node_memory_MemTotal_bytes{cluster_name=\"%s\"})))",
                clusterName, clusterName);
        return fetchMetricMap(prometheusUrl, query, "hostname");
    }

    public Map<String, Double> getNodeCreationTime(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (node) (last_over_time(xamops_eks_kube_node_created{cluster_name=\"%s\"}[24h]))",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "node");
    }

    public List<Map<String, String>> getClusterDeployments(String prometheusUrl, String clusterName) {
        String query = String.format(
                "last_over_time(xamops_eks_kube_deployment_spec_replicas{cluster_name=\"%s\"}[24h])",
                clusterName);
        return fetchMetricLabels(prometheusUrl, query, Arrays.asList("deployment", "namespace"));
    }

    public Map<String, Double> getDeploymentAvailableReplicas(String prometheusUrl, String clusterName) {
        return getDeploymentReplicasAvailable(prometheusUrl, clusterName);
    }

    public Map<String, Double> getDeploymentSpecReplicas(String prometheusUrl, String clusterName) {
        return getDeploymentReplicasDesired(prometheusUrl, clusterName);
    }

    public Map<String, Double> getDeploymentUpdatedReplicas(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (deployment) (last_over_time(xamops_eks_kube_deployment_status_replicas_updated{cluster_name=\"%s\"}[24h]))",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "deployment");
    }

    public Map<String, Double> getDeploymentCreationTime(String prometheusUrl, String clusterName) {
        String query = String.format(
                "last_over_time(xamops_eks_kube_deployment_created{cluster_name=\"%s\"}[24h])",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "deployment");
    }

    public List<Map<String, String>> getFalcoAlerts(String prometheusUrl, String clusterName) {
        String metricName = "xamops_eks_falcosecurity_falcosidekick_falco_events_total";
        String query = String.format(
                "sum by (rule, priority, k8s_pod_name, k8s_ns_name) (%s{cluster_name=\"%s\"}) > 0",
                metricName, clusterName);

        List<Map<String, String>> rawResults = fetchMetricLabels(
                prometheusUrl, query,
                Arrays.asList("rule", "priority", "k8s_pod_name", "k8s_ns_name"));

        List<Map<String, String>> finalResults = new ArrayList<>();
        for (Map<String, String> data : rawResults) {
            Map<String, String> mapped = new HashMap<>(data);
            if (mapped.containsKey("k8s_pod_name")) {
                mapped.put("pod", mapped.get("k8s_pod_name"));
            }
            if (mapped.containsKey("k8s_ns_name")) {
                mapped.put("namespace", mapped.get("k8s_ns_name"));
            }
            finalResults.add(mapped);
        }
        return finalResults;
    }

    // ============================================================================================
    // DETAILED POD/CONTAINER/NODE METRICS - Multi-tenant Safe
    // ============================================================================================

    /**
     * Get pod owner information (Deployment/StatefulSet/DaemonSet/etc.)
     * Returns map of pod -> {kind, name}
     */
    public Map<String, Map<String, String>> getPodOwner(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (pod, owner_kind, owner_name) (xamops_eks_kube_pod_owner{cluster_name=\"%s\"})",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(
                prometheusUrl, query, Arrays.asList("pod", "owner_kind", "owner_name"));

        Map<String, Map<String, String>> podOwners = new HashMap<>();
        for (Map<String, String> result : results) {
            String pod = result.get("pod");
            if (pod != null) {
                Map<String, String> owner = new HashMap<>();
                owner.put("kind", result.getOrDefault("owner_kind", "Unknown"));
                owner.put("name", result.getOrDefault("owner_name", "Unknown"));
                podOwners.put(pod, owner);
            }
        }
        logger.debug("Found {} pod owners for cluster {}", podOwners.size(), clusterName);
        return podOwners;
    }

    /**
     * Get detailed container information (name, image, image_id)
     * Returns list with pod, namespace, container, image, image_id
     */
    public List<Map<String, String>> getContainerInfo(String prometheusUrl, String clusterName) {
        // Try with cluster_name label first
        String query = String.format(
                "xamops_eks_kube_pod_container_info{cluster_name=\"%s\"}",
                clusterName);
        List<Map<String, String>> containers = fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("pod", "namespace", "container", "image", "image_id"));

        // Fallback: try without cluster filter if no results
        if (containers.isEmpty()) {
            logger.warn("No containers found with cluster_name filter, trying without filter");
            query = "kube_pod_container_info";
            containers = fetchMetricLabels(prometheusUrl, query,
                    Arrays.asList("pod", "namespace", "container", "image", "image_id"));
        }

        logger.info("Found {} containers for cluster {}", containers.size(), clusterName);
        return containers;
    }

    /**
     * Get CPU requests per pod (sum across all containers)
     * Returns map of pod -> CPU cores requested
     */
    public Map<String, Double> getPodCpuRequests(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod) (xamops_eks_kube_pod_container_resource_requests{cluster_name=\"%s\", resource=\"cpu\"})",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "pod");
    }

    /**
     * Get memory requests per pod in MB (sum across all containers)
     */
    public Map<String, Double> getPodMemoryRequests(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod) (xamops_eks_kube_pod_container_resource_requests{cluster_name=\"%s\", resource=\"memory\"}) / 1024 / 1024",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "pod");
    }

    /**
     * Get CPU limits per pod (sum across all containers)
     */
    public Map<String, Double> getPodCpuLimits(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod) (xamops_eks_kube_pod_container_resource_limits{cluster_name=\"%s\", resource=\"cpu\"})",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "pod");
    }

    /**
     * Get memory limits per pod in MB (sum across all containers)
     */
    public Map<String, Double> getPodMemoryLimits(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod) (xamops_eks_kube_pod_container_resource_limits{cluster_name=\"%s\", resource=\"memory\"}) / 1024 / 1024",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "pod");
    }

    /**
     * Get container count per pod
     */
    public Map<String, Double> getContainerCountPerPod(String prometheusUrl, String clusterName) {
        String query = String.format(
                "count by (pod) (xamops_eks_kube_pod_container_info{cluster_name=\"%s\"})",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "pod");
    }

    /**
     * Get container running state (true if running)
     * Returns map of "pod/container" -> boolean
     */
    public Map<String, Boolean> getContainerRunningState(String prometheusUrl, String clusterName) {
        String query = String.format(
                "xamops_eks_kube_pod_container_status_running{cluster_name=\"%s\"} == 1",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(
                prometheusUrl, query, Arrays.asList("pod", "container"));

        Map<String, Boolean> containerStates = new HashMap<>();
        for (Map<String, String> result : results) {
            String pod = result.get("pod");
            String container = result.get("container");
            if (pod != null && container != null) {
                containerStates.put(pod + "/" + container, true);
            }
        }
        return containerStates;
    }

    /**
     * Get container restart count
     * Returns map of "pod/container" -> restart count
     */
    public Map<String, Double> getContainerRestarts(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod, container) (xamops_eks_kube_pod_container_status_restarts_total{cluster_name=\"%s\"})",
                clusterName);
        return fetchMetricMapMultiLabel(prometheusUrl, query, "pod", "container");
    }

    /**
     * Get all node conditions (Ready, DiskPressure, MemoryPressure, PIDPressure)
     * Returns map of node -> list of active conditions
     */
    public Map<String, List<String>> getNodeAllConditions(String prometheusUrl, String clusterName) {
        String query = String.format(
                "xamops_eks_kube_node_status_condition{cluster_name=\"%s\"} == 1",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(
                prometheusUrl, query, Arrays.asList("node", "condition", "status"));

        Map<String, List<String>> nodeConditions = new HashMap<>();
        for (Map<String, String> result : results) {
            String node = result.get("node");
            String condition = result.get("condition");
            String status = result.get("status");
            if (node != null && condition != null && "true".equals(status)) {
                nodeConditions.computeIfAbsent(node, k -> new ArrayList<>()).add(condition);
            }
        }
        logger.debug("Found conditions for {} nodes in cluster {}", nodeConditions.size(), clusterName);
        return nodeConditions;
    }

    /**
     * Get pod count by phase (Running/Pending/Failed/etc.)
     */
    public Map<String, Integer> getPodCountByPhase(String prometheusUrl, String clusterName) {
        String query = String.format(
                "count by (phase) (xamops_eks_kube_pod_status_phase{cluster_name=\"%s\"} == 1)",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "phase");

        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, Double> entry : result.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().intValue());
        }
        return counts;
    }

    /**
     * Get pod CPU usage in millicores (aggregated from containers)
     * More accurate than k8s_pod_cpu_usage for multi-container pods
     */
    public Map<String, Double> getPodCpuUsageFromContainers(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod) (rate(xamops_eks_container_cpu_usage{cluster_name=\"%s\"}[5m])) * 1000",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "pod");
    }

    // ============================================================================================
    // ✅ NEW: METRICS FOR DEDICATED PODS/CONTAINERS/NODES TABS
    // These methods are ADDITIVE - they don't modify existing functionality
    // ============================================================================================

    /**
     * Get pod status phase counts (Running, Pending, Failed, Succeeded)
     */
    public Map<String, Integer> getPodStatusPhaseCounts(String prometheusUrl, String clusterName) {
        // FIX: Use max by (pod, namespace) to deduplicate pods (same logic as
        // getClusterPodCount)
        String query = String.format(
                "count by (phase) (max by (pod, namespace, phase) (xamops_eks_kube_pod_status_phase{cluster_name=\"%s\"}))",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "phase");
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, Double> entry : result.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().intValue());
        }
        logger.debug("Pod status phase counts: {}", counts);
        return counts;
    }

    /**
     * Get pod QoS class counts (Guaranteed, Burstable, BestEffort)
     */
    public Map<String, Integer> getPodQosClassCounts(String prometheusUrl, String clusterName) {
        // FIX: Use max by (pod, namespace) to deduplicate pods
        String query = String.format(
                "count by (qos_class) (max by (pod, namespace, qos_class) (xamops_eks_kube_pod_status_qos_class{cluster_name=\"%s\"}))",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "qos_class");
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, Double> entry : result.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().intValue());
        }
        logger.debug("Pod QoS class counts: {}", counts);
        return counts;
    }

    /**
     * Get container state counts (Running, Waiting, Terminated)
     */
    public Map<String, Integer> getContainerStateCounts(String prometheusUrl, String clusterName) {
        Map<String, Integer> counts = new HashMap<>();

        // Running containers
        String runningQuery = String.format(
                "count(xamops_eks_kube_pod_container_status_running{cluster_name=\"%s\"} == 1)",
                clusterName);
        Double running = fetchScalarValue(prometheusUrl, runningQuery);
        counts.put("Running", running != null ? running.intValue() : 0);

        // Waiting containers
        String waitingQuery = String.format(
                "count(xamops_eks_kube_pod_container_status_waiting{cluster_name=\"%s\"} == 1)",
                clusterName);
        Double waiting = fetchScalarValue(prometheusUrl, waitingQuery);
        counts.put("Waiting", waiting != null ? waiting.intValue() : 0);

        // Terminated containers
        String terminatedQuery = String.format(
                "count(xamops_eks_kube_pod_container_status_terminated{cluster_name=\"%s\"} == 1)",
                clusterName);
        Double terminated = fetchScalarValue(prometheusUrl, terminatedQuery);
        counts.put("Terminated", terminated != null ? terminated.intValue() : 0);

        return counts;
    }

    /**
     * Get total container restarts across the cluster
     */
    public Double getTotalContainerRestarts(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum(xamops_eks_kube_pod_container_status_restarts_total{cluster_name=\"%s\"})",
                clusterName);
        return fetchScalarValue(prometheusUrl, query);
    }

    /**
     * Get pod restart counts (for pods with restarts > 0)
     */
    public Map<String, Integer> getPodRestartCounts(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod, namespace) (xamops_eks_kube_pod_container_status_restarts_total{cluster_name=\"%s\"})",
                clusterName);

        Map<String, Integer> restarts = new HashMap<>();
        try {
            String url = normalizeUrl(prometheusUrl) + "/api/v1/query?query=" +
                    java.net.URLEncoder.encode(query, "UTF-8");
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("data").path("result");

            for (JsonNode entry : results) {
                JsonNode metric = entry.path("metric");
                String pod = metric.path("pod").asText();
                String namespace = metric.path("namespace").asText();
                String key = namespace + "/" + pod;
                int value = entry.path("value").get(1).asInt();
                if (value > 0) { // Only include pods with restarts
                    restarts.put(key, value);
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching pod restart counts: {}", e.getMessage());
        }
        return restarts;
    }

    /**
     * Get node condition counts (Ready, DiskPressure, MemoryPressure, PIDPressure)
     */
    public Map<String, Integer> getNodeConditionCounts(String prometheusUrl, String clusterName) {
        Map<String, Integer> counts = new HashMap<>();

        // Ready nodes
        String readyQuery = String.format(
                "count(xamops_eks_kube_node_status_condition{cluster_name=\"%s\", condition=\"Ready\", status=\"true\"})",
                clusterName);
        Double ready = fetchScalarValue(prometheusUrl, readyQuery);
        counts.put("Ready", ready != null ? ready.intValue() : 0);

        // DiskPressure
        String diskQuery = String.format(
                "count(xamops_eks_kube_node_status_condition{cluster_name=\"%s\", condition=\"DiskPressure\", status=\"true\"})",
                clusterName);
        Double disk = fetchScalarValue(prometheusUrl, diskQuery);
        counts.put("DiskPressure", disk != null ? disk.intValue() : 0);

        // MemoryPressure
        String memQuery = String.format(
                "count(xamops_eks_kube_node_status_condition{cluster_name=\"%s\", condition=\"MemoryPressure\", status=\"true\"})",
                clusterName);
        Double mem = fetchScalarValue(prometheusUrl, memQuery);
        counts.put("MemoryPressure", mem != null ? mem.intValue() : 0);

        // PIDPressure
        String pidQuery = String.format(
                "count(xamops_eks_kube_node_status_condition{cluster_name=\"%s\", condition=\"PIDPressure\", status=\"true\"})",
                clusterName);
        Double pid = fetchScalarValue(prometheusUrl, pidQuery);
        counts.put("PIDPressure", pid != null ? pid.intValue() : 0);

        return counts;
    }

    /**
     * Get container memory breakdown (Working Set, RSS, Available)
     */
    public Map<String, Double> getContainerMemoryBreakdown(String prometheusUrl, String clusterName) {
        Map<String, Double> breakdown = new HashMap<>();

        // Working Set
        String workingSetQuery = String.format(
                "sum(xamops_eks_container_memory_working_set_bytes{cluster_name=\"%s\"}) / 1024 / 1024",
                clusterName);
        Double workingSet = fetchScalarValue(prometheusUrl, workingSetQuery);
        breakdown.put("workingSet", workingSet != null ? workingSet : 0.0);

        // RSS
        String rssQuery = String.format(
                "sum(xamops_eks_container_memory_rss{cluster_name=\"%s\"}) / 1024 / 1024",
                clusterName);
        Double rss = fetchScalarValue(prometheusUrl, rssQuery);
        breakdown.put("rss", rss != null ? rss : 0.0);

        // Available (from node metrics)
        String availableQuery = String.format(
                "sum(xamops_eks_node_memory_MemAvailable_bytes{cluster_name=\"%s\"}) / 1024 / 1024",
                clusterName);
        Double available = fetchScalarValue(prometheusUrl, availableQuery);
        breakdown.put("available", available != null ? available : 0.0);

        return breakdown;
    }

    /**
     * Get detailed container info with metrics
     */
    public List<Map<String, Object>> getDetailedContainerMetrics(String prometheusUrl, String clusterName) {
        List<Map<String, Object>> containers = new ArrayList<>();

        // Get container info first
        List<Map<String, String>> containerInfo = getContainerInfo(prometheusUrl, clusterName);

        // Get CPU usage
        Map<String, Double> cpuUsage = getContainerCpuUsage(prometheusUrl, clusterName);

        // Get Memory usage
        Map<String, Double> memoryUsage = getContainerMemoryUsage(prometheusUrl, clusterName);

        // Get FS usage
        Map<String, Double> fsUsage = getContainerFsUsage(prometheusUrl, clusterName);

        // Get running status
        Map<String, Boolean> runningStatus = getContainerRunningStatus(prometheusUrl, clusterName);

        for (Map<String, String> info : containerInfo) {
            Map<String, Object> container = new HashMap<>();
            String pod = info.get("pod");
            String containerName = info.get("container");
            String key = pod + "/" + containerName;

            container.put("id", key);
            container.put("name", containerName);
            container.put("pod", pod);
            container.put("namespace", info.get("namespace"));
            container.put("image", info.get("image"));
            container.put("cpuUsage", cpuUsage.getOrDefault(key, 0.0));
            container.put("memoryUsage", memoryUsage.getOrDefault(key, 0.0));
            container.put("fsUsage", fsUsage.getOrDefault(key, 0.0));
            container.put("running", runningStatus.getOrDefault(key, false));

            containers.add(container);
        }

        return containers;
    }

    /**
     * Get container running status
     */
    private Map<String, Boolean> getContainerRunningStatus(String prometheusUrl, String clusterName) {
        String query = String.format(
                "xamops_eks_kube_pod_container_status_running{cluster_name=\"%s\"}",
                clusterName);

        Map<String, Boolean> status = new HashMap<>();
        try {
            String url = normalizeUrl(prometheusUrl) + "/api/v1/query?query=" +
                    java.net.URLEncoder.encode(query, "UTF-8");
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("data").path("result");

            for (JsonNode entry : results) {
                JsonNode metric = entry.path("metric");
                String pod = metric.path("pod").asText();
                String container = metric.path("container").asText();
                String key = pod + "/" + container;
                double value = entry.path("value").get(1).asDouble();
                status.put(key, value == 1.0);
            }
        } catch (Exception e) {
            logger.error("Error fetching container running status: {}", e.getMessage());
        }
        return status;
    }

}