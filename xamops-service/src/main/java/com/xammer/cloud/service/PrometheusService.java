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
        Double val = fetchScalarValue(prometheusUrl, query);
        // Standard falcosidekick fallback (GKE / vanilla install, no prefix)
        if (val == null || val == 0.0) {
            val = fetchScalarValue(prometheusUrl,
                    "sum(increase(falcosecurity_falcosidekick_falco_events_total[1h]))");
        }
        return val;
    }

    public Double getFalcoInputsTotal(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum(last_over_time(xamops_eks_falcosecurity_falcosidekick_inputs_total{cluster_name=\"%s\"}[5m]))",
                clusterName);
        return fetchScalarValue(prometheusUrl, query);
    }

    /** Returns true if Falco pods are running in the cluster (detected via kube-state-metrics). */
    public boolean isFalcoRunning(String prometheusUrl) {
        Double count = fetchScalarValue(prometheusUrl,
                "count(kube_pod_info{namespace=\"falco\"})");
        return count != null && count > 0;
    }

    // ---- Kubescape ----

    /** Returns true if Kubescape Operator pods are running (detected via kube-state-metrics). */
    public boolean isKubescapeRunning(String prometheusUrl) {
        Double count = fetchScalarValue(prometheusUrl,
                "count(kube_pod_info{namespace=\"kubescape\"})");
        return count != null && count > 0;
    }

    /** Returns map with "failed" and "passed" resource counts from Kubescape. */
    public Map<String, Double> getKubescapePassFail(String prometheusUrl) {
        Map<String, Double> result = new HashMap<>();
        Double failed = fetchScalarValue(prometheusUrl, "sum(kubescape_resources_failed_total)");
        Double total  = fetchScalarValue(prometheusUrl, "sum(kubescape_resources_all_total)");
        result.put("failed", failed != null ? failed : 0.0);
        result.put("passed", total != null && failed != null ? total - failed : 0.0);
        return result;
    }

    /** Returns per-control failure list from Kubescape Operator metrics. */
    public List<Map<String, String>> getKubescapeControlSummary(String prometheusUrl) {
        return fetchMetricLabels(prometheusUrl,
                "kubescape_resources_failed_total > 0",
                Arrays.asList("control_name", "severity"));
    }

    // ---- Trivy Operator ----

    /** Returns true if Trivy Operator pods are running (checks trivy-system or trivy namespace). */
    public boolean isTrivyRunning(String prometheusUrl) {
        Double count = fetchScalarValue(prometheusUrl,
                "count(kube_pod_info{namespace=\"trivy-system\"})");
        if (count == null || count == 0) {
            count = fetchScalarValue(prometheusUrl,
                    "count(kube_pod_info{namespace=\"trivy\"})");
        }
        return count != null && count > 0;
    }

    /** Returns total vulnerability count from Trivy Operator. */
    public Double getTrivyTotalVulnerabilities(String prometheusUrl) {
        return fetchScalarValue(prometheusUrl, "sum(trivy_image_vulnerabilities)");
    }

    /** Returns per-image, per-severity vulnerability breakdown from Trivy Operator. */
    public List<Map<String, String>> getTrivyVulnerabilitiesBySeverity(String prometheusUrl) {
        return fetchMetricLabels(prometheusUrl,
                "sum by (severity, image_repository, image_tag, namespace) (trivy_image_vulnerabilities) > 0",
                Arrays.asList("severity", "image_repository", "image_tag", "namespace"));
    }

    public List<Map<String, String>> getFalcoEventsByPriority(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (priority, rule, k8s_pod_name, k8s_ns_name) (increase(xamops_eks_falcosecurity_falcosidekick_falco_events_total{cluster_name=\"%s\"}[1h])) > 0",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("priority", "rule", "k8s_pod_name", "k8s_ns_name"));
        // Standard falcosidekick fallback (GKE / vanilla install, no prefix)
        if (results.isEmpty()) {
            results = fetchMetricLabels(prometheusUrl,
                    "sum by (priority, rule, k8s_pod_name, k8s_ns_name) " +
                    "(increase(falcosecurity_falcosidekick_falco_events_total[1h])) > 0",
                    Arrays.asList("priority", "rule", "k8s_pod_name", "k8s_ns_name"));
        }
        return results;
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

        // Filter by job="opencost" as confirmed by user working queries
        String cpuQuery = String.format(
                "xamops_eks_kube_node_status_capacity_cpu_cores{cluster_name=\"%s\", job=\"opencost\"}",
                clusterName);
        Map<String, Double> cpuCapacity = fetchMetricMap(prometheusUrl, cpuQuery, "node");

        // Fallback: try standard kube-state-metrics if opencost not available
        if (cpuCapacity.isEmpty()) {
            cpuQuery = String.format(
                    "max by (node) (xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"cpu\", job=\"kube-state-metrics\"})",
                    clusterName);
            cpuCapacity = fetchMetricMap(prometheusUrl, cpuQuery, "node");
        }
        logger.info("🔍 CPU capacity returned {} nodes", cpuCapacity.size());

        String memQuery = String.format(
                "xamops_eks_kube_node_status_capacity_memory_bytes{cluster_name=\"%s\", job=\"opencost\"} / 1024 / 1024 / 1024",
                clusterName);
        Map<String, Double> memCapacity = fetchMetricMap(prometheusUrl, memQuery, "node");

        // Fallback: try standard kube-state-metrics
        if (memCapacity.isEmpty()) {
            memQuery = String.format(
                    "max by (node) (xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"memory\", job=\"kube-state-metrics\"}) / 1024 / 1024 / 1024",
                    clusterName);
            memCapacity = fetchMetricMap(prometheusUrl, memQuery, "node");
        }
        logger.info("🔍 Memory capacity returned {} nodes", memCapacity.size());

        String podsQuery = String.format(
                "max by (node) (xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"pods\", job=\"kube-state-metrics\"})",
                clusterName);
        Map<String, Double> podsCapacity = fetchMetricMap(prometheusUrl, podsQuery, "node");
        if (podsCapacity.isEmpty()) {
            podsQuery = String.format(
                    "max by (node) (xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"pods\"})",
                    clusterName);
            podsCapacity = fetchMetricMap(prometheusUrl, podsQuery, "node");
        }
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

        // Filter by job="kube-state-metrics" to avoid duplicates from opencost
        String cpuQuery = String.format(
                "max by (node) (xamops_eks_kube_node_status_allocatable{cluster_name=\"%s\", resource=\"cpu\", job=\"kube-state-metrics\"})",
                clusterName);
        Map<String, Double> cpuAllocatable = fetchMetricMap(prometheusUrl, cpuQuery, "node");
        if (cpuAllocatable.isEmpty()) {
            cpuQuery = String.format(
                    "max by (node) (xamops_eks_kube_node_status_allocatable{cluster_name=\"%s\", resource=\"cpu\"})",
                    clusterName);
            cpuAllocatable = fetchMetricMap(prometheusUrl, cpuQuery, "node");
        }
        logger.info("🔍 CPU allocatable returned {} nodes", cpuAllocatable.size());

        String memQuery = String.format(
                "max by (node) (xamops_eks_kube_node_status_allocatable{cluster_name=\"%s\", resource=\"memory\", job=\"kube-state-metrics\"}) / 1024 / 1024 / 1024",
                clusterName);
        Map<String, Double> memAllocatable = fetchMetricMap(prometheusUrl, memQuery, "node");
        if (memAllocatable.isEmpty()) {
            memQuery = String.format(
                    "max by (node) (xamops_eks_kube_node_status_allocatable{cluster_name=\"%s\", resource=\"memory\"}) / 1024 / 1024 / 1024",
                    clusterName);
            memAllocatable = fetchMetricMap(prometheusUrl, memQuery, "node");
        }
        logger.info("🔍 Memory allocatable returned {} nodes", memAllocatable.size());

        String podsQuery = String.format(
                "max by (node) (xamops_eks_kube_node_status_allocatable{cluster_name=\"%s\", resource=\"pods\", job=\"kube-state-metrics\"})",
                clusterName);
        Map<String, Double> podsAllocatable = fetchMetricMap(prometheusUrl, podsQuery, "node");
        if (podsAllocatable.isEmpty()) {
            podsQuery = String.format(
                    "max by (node) (xamops_eks_kube_node_status_allocatable{cluster_name=\"%s\", resource=\"pods\"})",
                    clusterName);
            podsAllocatable = fetchMetricMap(prometheusUrl, podsQuery, "node");
        }
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

        // Standard kube-state-metrics fallback (no cluster_name label required)
        if (results.isEmpty()) {
            query = "kube_node_status_condition{status=\"true\"} == 1";
            results = fetchMetricLabels(prometheusUrl, query, Arrays.asList("node", "condition"));
        }

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

    /**
     * Get node groups from EKS labels
     */
    public Map<String, String> getNodeGroups(String prometheusUrl, String clusterName) {
        Map<String, String> nodeGroups = new HashMap<>();

        // Query eks.amazonaws.com/nodegroup label
        String query = String.format(
                "xamops_eks_kube_node_labels{cluster_name=\"%s\", label_eks_amazonaws_com_nodegroup!=\"\"}",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(
                prometheusUrl, query, Arrays.asList("node", "label_eks_amazonaws_com_nodegroup"));

        for (Map<String, String> result : results) {
            String node = result.get("node");
            String nodeGroup = result.get("label_eks_amazonaws_com_nodegroup");
            if (node != null && nodeGroup != null) {
                nodeGroups.put(node, nodeGroup);
            }
        }

        logger.info("✅ Node groups: {} nodes found", nodeGroups.size());
        return nodeGroups;
    }

    /**
     * Get PID pressure status per node
     */
    public Map<String, Boolean> getNodePIDPressure(String prometheusUrl, String clusterName) {
        Map<String, Boolean> pidPressure = new HashMap<>();

        String query = String.format(
                "xamops_eks_kube_node_status_condition{cluster_name=\"%s\", condition=\"PIDPressure\", status=\"true\"}",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(
                prometheusUrl, query, Arrays.asList("node"));

        for (Map<String, String> result : results) {
            String node = result.get("node");
            if (node != null) {
                pidPressure.put(node, true);
            }
        }

        logger.info("✅ PID pressure: {} nodes with pressure", pidPressure.size());
        return pidPressure;
    }

    /**
     * Get aggregated pod resource metrics for requests, limits, and usage
     */
    public Map<String, Double> getPodResourceAggregates(String prometheusUrl, String clusterName) {
        Map<String, Double> aggregates = new HashMap<>();

        // Total CPU Requests
        String cpuRequestsQuery = String.format(
                "sum(xamops_eks_kube_pod_container_resource_requests{cluster_name=\"%s\", resource=\"cpu\", unit=\"core\"})",
                clusterName);
        Double cpuRequests = fetchScalarValue(prometheusUrl, cpuRequestsQuery);
        aggregates.put("totalCpuRequests", cpuRequests != null ? cpuRequests * 1000 : 0.0); // Convert to millicores

        // Total CPU Limits
        String cpuLimitsQuery = String.format(
                "sum(xamops_eks_kube_pod_container_resource_limits{cluster_name=\"%s\", resource=\"cpu\", unit=\"core\"})",
                clusterName);
        Double cpuLimits = fetchScalarValue(prometheusUrl, cpuLimitsQuery);
        aggregates.put("totalCpuLimits", cpuLimits != null ? cpuLimits * 1000 : 0.0); // Convert to millicores

        // Total Memory Requests (bytes to MB)
        String memRequestsQuery = String.format(
                "sum(xamops_eks_kube_pod_container_resource_requests{cluster_name=\"%s\", resource=\"memory\", unit=\"byte\"}) / 1024 / 1024",
                clusterName);
        Double memRequests = fetchScalarValue(prometheusUrl, memRequestsQuery);
        aggregates.put("totalMemoryRequests", memRequests != null ? memRequests : 0.0);

        // Total Memory Limits (bytes to MB)
        String memLimitsQuery = String.format(
                "sum(xamops_eks_kube_pod_container_resource_limits{cluster_name=\"%s\", resource=\"memory\", unit=\"byte\"}) / 1024 / 1024",
                clusterName);
        Double memLimits = fetchScalarValue(prometheusUrl, memLimitsQuery);
        aggregates.put("totalMemoryLimits", memLimits != null ? memLimits : 0.0);

        // Total CPU Usage (current)
        String cpuUsageQuery = String.format(
                "sum(rate(xamops_eks_container_cpu_usage_seconds_total{cluster_name=\"%s\"}[5m])) * 1000",
                clusterName);
        Double cpuUsage = fetchScalarValue(prometheusUrl, cpuUsageQuery);
        aggregates.put("totalCpuUsage", cpuUsage != null ? cpuUsage : 0.0);

        // Total Memory Usage (current, bytes to MB)
        String memUsageQuery = String.format(
                "sum(xamops_eks_container_memory_usage_bytes{cluster_name=\"%s\"}) / 1024 / 1024",
                clusterName);
        Double memUsage = fetchScalarValue(prometheusUrl, memUsageQuery);
        aggregates.put("totalMemoryUsage", memUsage != null ? memUsage : 0.0);

        // Count pods without requests
        String noRequestsQuery = String.format(
                "count(count by (pod, namespace) (xamops_eks_kube_pod_container_resource_requests{cluster_name=\"%s\"} == 0))",
                clusterName);
        Double noRequests = fetchScalarValue(prometheusUrl, noRequestsQuery);
        aggregates.put("podsWithoutRequests", noRequests != null ? noRequests : 0.0);

        // Count pods without limits
        String noLimitsQuery = String.format(
                "count(count by (pod, namespace) (xamops_eks_kube_pod_container_resource_limits{cluster_name=\"%s\"} == 0))",
                clusterName);
        Double noLimits = fetchScalarValue(prometheusUrl, noLimitsQuery);
        aggregates.put("podsWithoutLimits", noLimits != null ? noLimits : 0.0);

        logger.info("✅ Pod resource aggregates computed");
        return aggregates;
    }

    // ============================================================================================
    // CONTAINER METRICS (3 queries)
    // ============================================================================================

    public Map<String, Double> getContainerCpuUsage(String prometheusUrl, String clusterName) {
        // Use cAdvisor container_cpu_usage_seconds_total metric with pod and container
        // labels
        String query = String.format(
                "rate(xamops_eks_container_cpu_usage_seconds_total{cluster_name=\"%s\", container!=\"\", container!=\"POD\"}[5m]) * 1000",
                clusterName);

        // First try with pod and container labels (standard cAdvisor)
        List<Map<String, String>> results = fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("pod", "container", "value"));

        Map<String, Double> result = new HashMap<>();
        for (Map<String, String> entry : results) {
            String pod = entry.get("pod");
            String container = entry.get("container");
            String valueStr = entry.get("value");

            if (pod != null && container != null && valueStr != null) {
                try {
                    double cpuValue = Double.parseDouble(valueStr);
                    String key = pod + "/" + container;
                    result.put(key, cpuValue);
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse container CPU value: {}", valueStr);
                }
            }
        }

        // Fallback: Try with instance/job format if pod/container labels not available
        if (result.isEmpty()) {
            query = String.format(
                    "sum by (instance, job) (rate(xamops_eks_container_cpu_usage{cluster_name=\"%s\"}[5m])) * 1000",
                    clusterName);

            Map<String, Double> rawResult = fetchMetricMapMultiLabel(prometheusUrl, query, "instance", "job");

            // Transform instance format from "namespace.pod.container" to "pod/container"
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
        }

        logger.info("🔍 Container CPU usage: {} containers found", result.size());
        return result;
    }

    public Map<String, Double> getContainerMemoryUsage(String prometheusUrl, String clusterName) {
        // Use cAdvisor container_memory_working_set_bytes metric
        String query = String.format(
                "xamops_eks_container_memory_working_set_bytes{cluster_name=\"%s\", container!=\"\", container!=\"POD\"} / 1024 / 1024",
                clusterName);

        List<Map<String, String>> results = fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("pod", "container", "value"));

        Map<String, Double> result = new HashMap<>();
        for (Map<String, String> entry : results) {
            String pod = entry.get("pod");
            String container = entry.get("container");
            String valueStr = entry.get("value");

            if (pod != null && container != null && valueStr != null) {
                try {
                    double memValue = Double.parseDouble(valueStr);
                    String key = pod + "/" + container;
                    result.put(key, memValue);
                } catch (NumberFormatException e) {
                    logger.warn("Failed to parse container memory value: {}", valueStr);
                }
            }
        }

        // Fallback: Try with instance/job format
        if (result.isEmpty()) {
            query = String.format(
                    "sum by (instance, job) (xamops_eks_container_memory_usage_bytes{cluster_name=\"%s\"}) / 1024 / 1024",
                    clusterName);

            Map<String, Double> rawResult = fetchMetricMapMultiLabel(prometheusUrl, query, "instance", "job");

            // Transform instance format
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
        }

        logger.info("🔍 Container memory usage: {} containers found", result.size());
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

        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (results.isEmpty()) {
            results = fetchMetricLabels(prometheusUrl,
                    "kube_pod_status_phase == 1", Arrays.asList("pod", "phase"));
        }

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
        // Use container CPU aggregated by pod for accuracy
        String query = String.format(
                "sum by (pod) (rate(xamops_eks_container_cpu_usage_seconds_total{cluster_name=\"%s\", container!=\"\", container!=\"POD\"}[5m])) * 1000",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "pod");

        // Fallback 1: Try with k8s_pod_cpu_usage metric
        if (result.isEmpty()) {
            query = String.format(
                    "sum by (pod) (rate(xamops_eks_k8s_pod_cpu_usage{cluster_name=\"%s\"}[5m])) * 1000",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "pod");
        }

        // Fallback 2: Try with plain container_cpu_usage
        if (result.isEmpty()) {
            query = String.format(
                    "sum by (pod) (rate(xamops_eks_container_cpu_usage{cluster_name=\"%s\", container!=\"\", container!=\"POD\"}[5m])) * 1000",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "pod");
        }

        // Standard fallback (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            query = "sum by (pod) (rate(container_cpu_usage_seconds_total{container!=\"\", container!=\"POD\"}[5m])) * 1000";
            result = fetchMetricMap(prometheusUrl, query, "pod");
        }

        return result;
    }

    public Map<String, Double> getPodMemoryUsage(String prometheusUrl, String clusterName) {
        // Use container memory usage aggregated by pod, filter out POD container
        String query = String.format(
                "sum by (pod) (xamops_eks_container_memory_working_set_bytes{cluster_name=\"%s\", container!=\"\", container!=\"POD\"}) / 1024 / 1024",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "pod");

        // Fallback: Try with plain memory_usage_bytes
        if (result.isEmpty()) {
            query = String.format(
                    "sum by (pod) (xamops_eks_container_memory_usage_bytes{cluster_name=\"%s\", container!=\"\", container!=\"POD\"}) / 1024 / 1024",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "pod");
        }

        // Standard fallback (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            query = "sum by (pod) (container_memory_working_set_bytes{container!=\"\", container!=\"POD\"}) / 1024 / 1024";
            result = fetchMetricMap(prometheusUrl, query, "pod");
        }

        return result;
    }

    // ============================================================================================
    // DEPLOYMENT METRICS (2 queries)
    // ============================================================================================

    public Map<String, Double> getDeploymentReplicasAvailable(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (deployment) (last_over_time(xamops_eks_kube_deployment_status_replicas_available{cluster_name=\"%s\"}[24h]))",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "deployment");
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl, "kube_deployment_status_replicas_available", "deployment");
        }
        return result;
    }

    public Map<String, Double> getDeploymentReplicasDesired(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (deployment) (last_over_time(xamops_eks_kube_deployment_spec_replicas{cluster_name=\"%s\"}[24h]))",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "deployment");
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl, "kube_deployment_spec_replicas", "deployment");
        }
        return result;
    }

    // ============================================================================================
    // DAEMONSET METRICS (2 queries)
    // ============================================================================================

    public Map<String, Double> getDaemonsetDesired(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (daemonset) (last_over_time(xamops_eks_kube_daemonset_status_desired_number_scheduled{cluster_name=\"%s\"}[24h]))",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "daemonset");
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl, "kube_daemonset_status_desired_number_scheduled", "daemonset");
        }
        return result;
    }

    public Map<String, Double> getDaemonsetReady(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (daemonset) (last_over_time(xamops_eks_kube_daemonset_status_number_ready{cluster_name=\"%s\"}[24h]))",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "daemonset");
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl, "kube_daemonset_status_number_ready", "daemonset");
        }
        return result;
    }

    public List<Map<String, String>> getClusterDaemonsets(String prometheusUrl, String clusterName) {
        String query = String.format(
                "last_over_time(xamops_eks_kube_daemonset_status_desired_number_scheduled{cluster_name=\"%s\"}[24h])",
                clusterName);
        List<Map<String, String>> result = fetchMetricLabels(prometheusUrl, query, Arrays.asList("daemonset", "namespace"));
        if (result.isEmpty()) {
            result = fetchMetricLabels(prometheusUrl, "kube_daemonset_status_desired_number_scheduled", Arrays.asList("daemonset", "namespace"));
        }
        return result;
    }

    // ============================================================================================
    // STATEFULSET METRICS (3 queries)
    // ============================================================================================

    public Map<String, Double> getStatefulsetReplicasDesired(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (statefulset) (last_over_time(xamops_eks_kube_statefulset_replicas{cluster_name=\"%s\"}[24h]))",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "statefulset");
    }

    public Map<String, Double> getStatefulsetReplicasReady(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (statefulset) (last_over_time(xamops_eks_kube_statefulset_status_replicas_ready{cluster_name=\"%s\"}[24h]))",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "statefulset");
    }

    public List<Map<String, String>> getClusterStatefulsets(String prometheusUrl, String clusterName) {
        String query = String.format(
                "last_over_time(xamops_eks_kube_statefulset_labels{cluster_name=\"%s\"}[24h])",
                clusterName);
        List<Map<String, String>> result = fetchMetricLabels(prometheusUrl, query, Arrays.asList("statefulset", "namespace"));
        if (result.isEmpty()) {
            result = fetchMetricLabels(prometheusUrl, "kube_statefulset_replicas", Arrays.asList("statefulset", "namespace"));
        }
        return result;
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

        // Standard kube-state-metrics fallback
        if (results.isEmpty()) {
            results = fetchMetricLabels(prometheusUrl, "kube_namespace_status_phase == 1",
                    Arrays.asList("namespace", "phase"));
        }

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
    // P2: COST ALLOCATION METRICS - Namespace & Workload Level
    // ============================================================================================

    /**
     * Get CPU usage aggregated by namespace (in millicores)
     */
    public Map<String, Double> getNamespaceCpuUsage(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (namespace) (rate(xamops_eks_container_cpu_usage{cluster_name=\"%s\"}[5m])) * 1000",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "namespace");
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl,
                    "sum by (namespace) (rate(container_cpu_usage_seconds_total{container!=\"\", container!=\"POD\"}[5m])) * 1000",
                    "namespace");
        }
        return result;
    }

    /**
     * Get memory usage aggregated by namespace (in MB)
     */
    public Map<String, Double> getNamespaceMemoryUsage(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (namespace) (xamops_eks_container_memory_usage_bytes{cluster_name=\"%s\"}) / 1024 / 1024",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "namespace");
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl,
                    "sum by (namespace) (container_memory_working_set_bytes{container!=\"\", container!=\"POD\"}) / 1024 / 1024",
                    "namespace");
        }
        return result;
    }

    /**
     * Get pod count by namespace
     */
    public Map<String, Double> getNamespacePodCount(String prometheusUrl, String clusterName) {
        String query = String.format(
                "count by (namespace) (xamops_eks_kube_pod_info{cluster_name=\"%s\"})",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "namespace");
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl, "count by (namespace) (kube_pod_info)", "namespace");
        }
        return result;
    }

    /**
     * Get node labels including capacity type (spot/on-demand) and node group
     * Uses kube_node_labels metric which contains all node labels
     */
    public List<Map<String, String>> getNodeLabels(String prometheusUrl, String clusterName) {
        // Try to get node labels that include capacity type
        String query = String.format(
                "last_over_time(xamops_eks_kube_node_labels{cluster_name=\"%s\"}[24h])",
                clusterName);
        List<Map<String, String>> labels = fetchMetricLabels(
                prometheusUrl, query,
                Arrays.asList("node", "label_node_kubernetes_io_instance_type",
                        "label_eks_amazonaws_com_capacityType",
                        "label_eks_amazonaws_com_nodegroup",
                        "label_karpenter_sh_capacity_type",
                        "label_karpenter_sh_nodepool",
                        "label_topology_kubernetes_io_zone"));

        // If no results, try fallback without cluster filter
        if (labels.isEmpty()) {
            logger.warn("No node labels found with cluster filter, trying without filter");
            query = "kube_node_labels";
            labels = fetchMetricLabels(prometheusUrl, query,
                    Arrays.asList("node", "label_node_kubernetes_io_instance_type",
                            "label_eks_amazonaws_com_capacityType",
                            "label_eks_amazonaws_com_nodegroup",
                            "label_karpenter_sh_capacity_type",
                            "label_karpenter_sh_nodepool"));
        }

        logger.info("Found node labels for {} nodes", labels.size());
        return labels;
    }

    /**
     * Get CPU usage by deployment (aggregated from pods)
     * Note: created_by_name often contains ReplicaSet name (e.g.,
     * metrics-server-5b774b674d)
     * We store both the full name and stripped name for flexible matching
     */
    public Map<String, Map<String, Double>> getDeploymentCpuUsage(String prometheusUrl, String clusterName) {
        // This query groups pod CPU by their owner deployment
        String query = String.format(
                "sum by (namespace, created_by_name) (rate(xamops_eks_container_cpu_usage{cluster_name=\"%s\"}[5m])) * 1000",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("namespace", "created_by_name", "value"));

        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        // Uses kube_pod_owner join to group container CPU by workload owner
        if (results.isEmpty()) {
            query = "sum by (namespace, owner_name) (" +
                    "  rate(container_cpu_usage_seconds_total{container!=\"\", container!=\"POD\"}[5m]) * 1000" +
                    "  * on(pod, namespace) group_left(owner_name)" +
                    "  kube_pod_owner{owner_kind=~\"ReplicaSet|DaemonSet|StatefulSet\"}" +
                    ")";
            results = fetchMetricLabels(prometheusUrl, query,
                    Arrays.asList("namespace", "owner_name", "value"));
        }

        Map<String, Map<String, Double>> deploymentCpu = new HashMap<>();
        for (Map<String, String> result : results) {
            String namespace = result.get("namespace");
            // Support both created_by_name (OpenCost) and owner_name (standard kube-state-metrics)
            String createdByName = result.get("created_by_name");
            if (createdByName == null) createdByName = result.get("owner_name");
            String valueStr = result.get("value");
            if (namespace != null && createdByName != null && valueStr != null) {
                double cpuValue = 0.0;
                try {
                    cpuValue = Double.parseDouble(valueStr);
                } catch (NumberFormatException e) {
                    cpuValue = 0.0;
                }

                // Store with full ReplicaSet name
                String fullKey = namespace + "/" + createdByName;
                Map<String, Double> metrics = new HashMap<>();
                metrics.put("cpu", cpuValue);
                deploymentCpu.put(fullKey, metrics);

                // Also store with stripped deployment name (remove ReplicaSet suffix like
                // -5b774b674d)
                String deploymentName = stripReplicaSetSuffix(createdByName);
                if (!deploymentName.equals(createdByName)) {
                    String strippedKey = namespace + "/" + deploymentName;
                    // Accumulate if multiple ReplicaSets for same deployment
                    Map<String, Double> existingMetrics = deploymentCpu.getOrDefault(strippedKey, new HashMap<>());
                    double existingCpu = existingMetrics.getOrDefault("cpu", 0.0);
                    existingMetrics.put("cpu", existingCpu + cpuValue);
                    deploymentCpu.put(strippedKey, existingMetrics);
                }
            }
        }
        return deploymentCpu;
    }

    /**
     * Strip ReplicaSet suffix from pod owner name (e.g.,
     * "metrics-server-5b774b674d" -> "metrics-server")
     */
    private String stripReplicaSetSuffix(String name) {
        if (name == null)
            return name;
        // ReplicaSet suffix pattern: -[a-z0-9]{8,10} at the end
        // e.g., metrics-server-5b774b674d -> metrics-server
        return name.replaceAll("-[a-z0-9]{8,10}$", "");
    }

    /**
     * Get memory usage by deployment (aggregated from pods)
     * Note: created_by_name often contains ReplicaSet name (e.g.,
     * metrics-server-5b774b674d)
     * We store both the full name and stripped name for flexible matching
     */
    public Map<String, Map<String, Double>> getDeploymentMemoryUsage(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (namespace, created_by_name) (xamops_eks_container_memory_usage_bytes{cluster_name=\"%s\"}) / 1024 / 1024",
                clusterName);
        List<Map<String, String>> results = fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("namespace", "created_by_name", "value"));

        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        // Uses kube_pod_owner join to group container memory by workload owner
        if (results.isEmpty()) {
            query = "sum by (namespace, owner_name) (" +
                    "  container_memory_working_set_bytes{container!=\"\", container!=\"POD\"} / 1024 / 1024" +
                    "  * on(pod, namespace) group_left(owner_name)" +
                    "  kube_pod_owner{owner_kind=~\"ReplicaSet|DaemonSet|StatefulSet\"}" +
                    ")";
            results = fetchMetricLabels(prometheusUrl, query,
                    Arrays.asList("namespace", "owner_name", "value"));
        }

        Map<String, Map<String, Double>> deploymentMem = new HashMap<>();
        for (Map<String, String> result : results) {
            String namespace = result.get("namespace");
            // Support both created_by_name (OpenCost) and owner_name (standard kube-state-metrics)
            String createdByName = result.get("created_by_name");
            if (createdByName == null) createdByName = result.get("owner_name");
            String valueStr = result.get("value");
            if (namespace != null && createdByName != null && valueStr != null) {
                double memValue = 0.0;
                try {
                    memValue = Double.parseDouble(valueStr);
                } catch (NumberFormatException e) {
                    memValue = 0.0;
                }

                // Store with full ReplicaSet name
                String fullKey = namespace + "/" + createdByName;
                Map<String, Double> metrics = new HashMap<>();
                metrics.put("memory", memValue);
                deploymentMem.put(fullKey, metrics);

                // Also store with stripped deployment name (remove ReplicaSet suffix like
                // -5b774b674d)
                String deploymentName = stripReplicaSetSuffix(createdByName);
                if (!deploymentName.equals(createdByName)) {
                    String strippedKey = namespace + "/" + deploymentName;
                    // Accumulate if multiple ReplicaSets for same deployment
                    Map<String, Double> existingMetrics = deploymentMem.getOrDefault(strippedKey, new HashMap<>());
                    double existingMem = existingMetrics.getOrDefault("memory", 0.0);
                    existingMetrics.put("memory", existingMem + memValue);
                    deploymentMem.put(strippedKey, existingMetrics);
                }
            }
        }
        return deploymentMem;
    }

    /**
     * Get restart count per pod/container
     */
    public Map<String, Double> getPodRestartCount(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod) (xamops_eks_kube_pod_container_status_restarts_total{cluster_name=\"%s\"})",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "pod");
        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl,
                    "sum by (pod) (kube_pod_container_status_restarts_total)", "pod");
        }
        return result;
    }

    /**
     * Get node CPU usage per node (percentage)
     */
    public Map<String, Double> getNodeCpuUsage(String prometheusUrl, String clusterName) {
        // Pattern 1: Confirmed working query with exported_instance label
        String query = String.format(
                "(sum(rate(container_cpu_usage_seconds_total{cluster_name=\"%s\", container!=\"\"}[5m])) by (exported_instance) / "
                        +
                        "on(exported_instance) group_left() xamops_eks_kube_node_status_capacity_cpu_cores{cluster_name=\"%s\", job=\"opencost\"}) * 100",
                clusterName, clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "exported_instance");

        // Pattern 2: fallback with node label if exported_instance fails
        if (result.isEmpty()) {
            query = String.format(
                    "(sum by (node) (rate(xamops_eks_container_cpu_usage_seconds_total{cluster_name=\"%s\", container!=\"\", container!=\"POD\"}[5m])) / "
                            +
                            "on(node) group_left() max by (node) (xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"cpu\"})) * 100",
                    clusterName, clusterName);
            result = fetchMetricMap(prometheusUrl, query, "node");
        }

        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            query = "(sum by (node) (rate(container_cpu_usage_seconds_total{container!=\"\", container!=\"POD\"}[5m])) / " +
                    "on(node) group_left() max by (node) (kube_node_status_capacity{resource=\"cpu\"})) * 100";
            result = fetchMetricMap(prometheusUrl, query, "node");
        }

        return result;
    }

    public Map<String, Double> getNodeCpuRequests(String prometheusUrl, String clusterName) {
        // Sum CPU requests (in cores) for all pods on each node, convert to millicores
        String query = String.format(
                "sum by (node) (xamops_eks_kube_pod_container_resource_requests{cluster_name=\"%s\", resource=\"cpu\", unit=\"core\"}) * 1000",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "node");

        // Fallback: standard kube-state-metrics
        if (result.isEmpty()) {
            query = "sum by (node) (kube_pod_container_resource_requests{resource=\"cpu\"}) * 1000";
            result = fetchMetricMap(prometheusUrl, query, "node");
        }

        return result;
    }

    public Map<String, Double> getNodeMemoryRequests(String prometheusUrl, String clusterName) {
        // Sum memory requests (in bytes) for all pods on each node
        String query = String.format(
                "sum by (node) (xamops_eks_kube_pod_container_resource_requests{cluster_name=\"%s\", resource=\"memory\", unit=\"byte\"})",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "node");

        // Fallback without unit filter
        if (result.isEmpty()) {
            query = String.format(
                    "sum by (node) (xamops_eks_kube_pod_container_resource_requests{cluster_name=\"%s\", resource=\"memory\"})",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "node");
        }

        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl,
                    "sum by (node) (kube_pod_container_resource_requests{resource=\"memory\"})", "node");
        }

        return result;
    }

    // kubeletstats metric tracks cumulative CPU time across all modes
    // The rate gives CPU-seconds per second, but includes all modes (user, system,
    // idle, etc.)
    // Multiplying by 100 converts fraction to percentage
    // Note: Filter by job="kube-state-metrics" to avoid duplicate series from
    // opencost
    public Double getClusterCpuUsage(String prometheusUrl, String clusterName) {
        String query = String.format(
                "(sum(rate(container_cpu_usage_seconds_total{cluster_name=\"%s\", container!=\"\"}[5m])) / " +
                        "sum(xamops_eks_kube_node_status_capacity_cpu_cores{cluster_name=\"%s\", job=\"opencost\"})) * 100",
                clusterName, clusterName);
        Double val = fetchScalarValue(prometheusUrl, query);

        if (val == null || val == 0.0) {
            query = String.format(
                    "(sum(rate(xamops_eks_container_cpu_usage{cluster_name=\"%s\"}[5m])) / " +
                            "sum(xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"cpu\", job=\"kube-state-metrics\"})) * 100",
                    clusterName, clusterName);
            val = fetchScalarValue(prometheusUrl, query);
        }
        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (val == null || val == 0.0) {
            val = fetchScalarValue(prometheusUrl,
                    "(sum(rate(container_cpu_usage_seconds_total{container!=\"\", container!=\"POD\"}[5m])) / " +
                    "sum(kube_node_status_capacity{resource=\"cpu\"})) * 100");
        }
        return val;
    }

    public Double getClusterCpuUsageMillicores(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum(rate(xamops_eks_container_cpu_usage{cluster_name=\"%s\"}[5m])) * 1000",
                clusterName);
        Double val = fetchScalarValue(prometheusUrl, query);
        if (val == null || val == 0.0) {
            query = "sum(rate(container_cpu_usage_seconds_total{container!=\"\", container!=\"POD\"}[5m])) * 1000";
            val = fetchScalarValue(prometheusUrl, query);
        }
        return val;
    }

    public Double getClusterCpuCapacityMillicores(String prometheusUrl, String clusterName) {
        // Filter by job="kube-state-metrics" to avoid duplicate series from opencost
        String query = String.format(
                "sum(xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"cpu\", job=\"kube-state-metrics\"}) * 1000",
                clusterName);
        Double val = fetchScalarValue(prometheusUrl, query);
        // Fallback without job filter
        if (val == null || val == 0.0) {
            query = String.format(
                    "sum(xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"cpu\"}) * 1000",
                    clusterName);
            val = fetchScalarValue(prometheusUrl, query);
        }
        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (val == null || val == 0.0) {
            val = fetchScalarValue(prometheusUrl, "sum(kube_node_status_capacity{resource=\"cpu\"}) * 1000");
        }
        return val;
    }

    public Double getClusterMemoryUsage(String prometheusUrl, String clusterName) {
        String query = String.format(
                "(sum(container_memory_working_set_bytes{cluster_name=\"%s\", container!=\"\"}) / " +
                        "sum(xamops_eks_kube_node_status_capacity_memory_bytes{cluster_name=\"%s\", job=\"opencost\"})) * 100",
                clusterName, clusterName);
        Double val = fetchScalarValue(prometheusUrl, query);

        if (val == null || val == 0.0) {
            query = String.format(
                    "sum(xamops_eks_k8s_node_memory_working_set_bytes{cluster_name=\"%s\"}) / sum(xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"memory\", job=\"kube-state-metrics\"}) * 100",
                    clusterName, clusterName);
            val = fetchScalarValue(prometheusUrl, query);
        }
        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (val == null || val == 0.0) {
            val = fetchScalarValue(prometheusUrl,
                    "(sum(container_memory_working_set_bytes{container!=\"\", container!=\"POD\"}) / " +
                    "sum(kube_node_status_capacity{resource=\"memory\"})) * 100");
        }
        return val;
    }

    public Double getClusterMemoryUsageBytes(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum(xamops_eks_k8s_node_memory_working_set_bytes{cluster_name=\"%s\"})",
                clusterName);
        Double val = fetchScalarValue(prometheusUrl, query);
        if (val == null || val == 0.0) {
            query = "sum(node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes)";
            val = fetchScalarValue(prometheusUrl, query);
        }
        return val;
    }

    public Double getClusterMemoryCapacityBytes(String prometheusUrl, String clusterName) {
        // Filter by job="kube-state-metrics" to avoid duplicate series from opencost
        String query = String.format(
                "sum(xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"memory\", job=\"kube-state-metrics\"})",
                clusterName);
        Double val = fetchScalarValue(prometheusUrl, query);
        // Fallback without job filter
        if (val == null || val == 0.0) {
            query = String.format(
                    "sum(xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"memory\"})",
                    clusterName);
            val = fetchScalarValue(prometheusUrl, query);
        }
        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (val == null || val == 0.0) {
            val = fetchScalarValue(prometheusUrl, "sum(kube_node_status_capacity{resource=\"memory\"})");
        }
        return val;
    }

    public Integer getClusterNodeCount(String prometheusUrl, String clusterName) {
        // Filter by job="kube-state-metrics" to avoid duplicates from opencost
        String query = String.format(
                "count(count by (node) (xamops_eks_kube_node_info{cluster_name=\"%s\", job=\"kube-state-metrics\"}))",
                clusterName);
        Double val = fetchScalarValue(prometheusUrl, query);

        // Fallback without job filter
        if (val == null || val.intValue() == 0) {
            query = String.format(
                    "count(count by (node) (xamops_eks_kube_node_info{cluster_name=\"%s\"}))",
                    clusterName);
            val = fetchScalarValue(prometheusUrl, query);
        }
        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (val == null || val.intValue() == 0) {
            val = fetchScalarValue(prometheusUrl, "count(kube_node_info)");
        }
        return val != null ? val.intValue() : 0;
    }

    public Integer getClusterPodCount(String prometheusUrl, String clusterName) {
        // Use max by (pod, namespace) to deduplicate pods from multiple scrapers
        // (kube-state-metrics + opencost)
        // Filter by job="kube-state-metrics" to avoid counting duplicates
        String query = String.format(
                "count(max by (pod, namespace) (xamops_eks_kube_pod_status_phase{cluster_name=\"%s\", job=\"kube-state-metrics\", phase=\"Running\"} == 1))",
                clusterName);
        Double val = fetchScalarValue(prometheusUrl, query);

        // Fallback: if no results with job filter, try without (for backward
        // compatibility)
        if (val == null || val.intValue() == 0) {
            query = String.format(
                    "count(max by (pod, namespace) (xamops_eks_kube_pod_status_phase{cluster_name=\"%s\", phase=\"Running\"} == 1))",
                    clusterName);
            val = fetchScalarValue(prometheusUrl, query);
        }
        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (val == null || val.intValue() == 0) {
            val = fetchScalarValue(prometheusUrl,
                    "count(kube_pod_status_phase{phase=\"Running\"} == 1)");
        }
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
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "pod");
        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl, "kube_pod_created", "pod");
        }
        return result;
    }

    public Map<String, Double> getNodeCpuUsageMillicores(String prometheusUrl, String clusterName) {
        // Pattern 1: Confirmed working query with exported_instance label
        String query = String.format(
                "sum(rate(container_cpu_usage_seconds_total{cluster_name=\"%s\", container!=\"\"}[5m])) by (exported_instance) * 1000",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "exported_instance");

        // Pattern 2: try with xamops_eks_ prefix if needed
        if (result.isEmpty()) {
            query = String.format(
                    "sum(rate(xamops_eks_container_cpu_usage_seconds_total{cluster_name=\"%s\", container!=\"\"}[5m])) by (exported_instance) * 1000",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "exported_instance");
        }

        // Pattern 3 fallback: Aggregate container CPU usage by node label
        if (result.isEmpty()) {
            query = String.format(
                    "sum by (node) (rate(xamops_eks_container_cpu_usage_seconds_total{cluster_name=\"%s\", container!=\"\", container!=\"POD\"}[5m])) * 1000",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "node");
        }

        // Pattern 4 fallback: Use node_exporter metrics
        if (result.isEmpty()) {
            query = String.format(
                    "sum by (instance) (rate(xamops_eks_node_cpu_seconds_total{cluster_name=\"%s\", mode!=\"idle\"}[5m])) * 1000",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "instance");
        }

        // Pattern 5 fallback: standard node-exporter (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            query = "sum by (node) (rate(container_cpu_usage_seconds_total{container!=\"\", container!=\"POD\"}[5m])) * 1000";
            result = fetchMetricMap(prometheusUrl, query, "node");
        }

        return result;
    }

    public Map<String, Double> getNodeMemoryUsageBytes(String prometheusUrl, String clusterName) {
        // Working query with exported_instance label
        String query = String.format(
                "sum(container_memory_working_set_bytes{cluster_name=\"%s\", container!=\"\"}) by (exported_instance)",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "exported_instance");

        // Fallback with prefix
        if (result.isEmpty()) {
            query = String.format(
                    "sum(xamops_eks_container_memory_working_set_bytes{cluster_name=\"%s\", container!=\"\"}) by (exported_instance)",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "exported_instance");
        }

        // Final fallback using node label
        if (result.isEmpty()) {
            query = String.format(
                    "sum by (node) (xamops_eks_container_memory_working_set_bytes{cluster_name=\"%s\", container!=\"\", container!=\"POD\"})",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "node");
        }

        // Standard fallback (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            query = "sum by (node) (container_memory_working_set_bytes{container!=\"\", container!=\"POD\"})";
            result = fetchMetricMap(prometheusUrl, query, "node");
        }

        return result;
    }

    public Map<String, Double> getNodeMemoryUsage(String prometheusUrl, String clusterName) {
        // Pattern 1: Working set bytes divided by capacity bytes
        String query = String.format(
                "(sum(container_memory_working_set_bytes{cluster_name=\"%s\", container!=\"\"}) by (exported_instance) / "
                        +
                        "on(exported_instance) group_left() xamops_eks_kube_node_status_capacity_memory_bytes{cluster_name=\"%s\", job=\"opencost\"}) * 100",
                clusterName, clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "exported_instance");

        // Pattern 2 fallback: Try with node label
        if (result.isEmpty()) {
            query = String.format(
                    "(sum by (node) (xamops_eks_container_memory_working_set_bytes{cluster_name=\"%s\", container!=\"\", container!=\"POD\"}) / "
                            +
                            "on(node) group_left() xamops_eks_kube_node_status_capacity{cluster_name=\"%s\", resource=\"memory\"}) * 100",
                    clusterName, clusterName);
            result = fetchMetricMap(prometheusUrl, query, "node");
        }

        // Standard fallback (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            query = "(sum by (node) (container_memory_working_set_bytes{container!=\"\", container!=\"POD\"}) / " +
                    "on(node) group_left() max by (node) (kube_node_status_capacity{resource=\"memory\"})) * 100";
            result = fetchMetricMap(prometheusUrl, query, "node");
        }

        return result;
    }

    public Map<String, Double> getNodeCreationTime(String prometheusUrl, String clusterName) {
        String query = String.format(
                "max by (node) (last_over_time(xamops_eks_kube_node_created{cluster_name=\"%s\"}[24h]))",
                clusterName);
        return fetchMetricMap(prometheusUrl, query, "node");
    }

    public Map<String, String> getNodeCapacityTypes(String prometheusUrl, String clusterName) {
        String query = String.format(
                "xamops_eks_kube_node_labels{cluster_name=\"%s\"}",
                clusterName);
        List<Map<String, String>> labels = fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("node", "label_eks_amazonaws_com_capacityType", "label_karpenter_sh_capacity_type",
                        "label_karpenter_sh_nodepool"));

        Map<String, String> capacityTypes = new HashMap<>();
        for (Map<String, String> labelMap : labels) {
            String node = labelMap.get("node");
            if (node == null)
                continue;

            String type = labelMap.get("label_eks_amazonaws_com_capacityType");
            if (type == null)
                type = labelMap.get("label_karpenter_sh_capacity_type");

            if (type != null) {
                capacityTypes.put(node, type);
            }
        }
        return capacityTypes;
    }

    public List<Map<String, String>> getClusterDeployments(String prometheusUrl, String clusterName) {
        String query = String.format(
                "last_over_time(xamops_eks_kube_deployment_spec_replicas{cluster_name=\"%s\"}[24h])",
                clusterName);
        List<Map<String, String>> result = fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("deployment", "namespace"));
        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            result = fetchMetricLabels(prometheusUrl, "kube_deployment_spec_replicas",
                    Arrays.asList("deployment", "namespace"));
        }
        return result;
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
        // Standard falcosidekick fallback (GKE / vanilla install, no prefix)
        if (rawResults.isEmpty()) {
            rawResults = fetchMetricLabels(prometheusUrl,
                    "sum by (rule, priority, k8s_pod_name, k8s_ns_name) " +
                    "(falcosecurity_falcosidekick_falco_events_total) > 0",
                    Arrays.asList("rule", "priority", "k8s_pod_name", "k8s_ns_name"));
        }

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
        // Filter by job="kube-state-metrics" to avoid duplicates from otel-gateway
        String query = String.format(
                "max by (pod, owner_kind, owner_name) (xamops_eks_kube_pod_owner{cluster_name=\"%s\", job=\"kube-state-metrics\"})",
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
     * Get CPU usage grouped by owner (Deployment, DaemonSet, etc.)
     * Uses kube_pod_owner join for accurate mapping
     */
    public List<Map<String, String>> getWorkloadCpuUsage(String prometheusUrl, String clusterName) {
        // Filter by job="kube-state-metrics" to avoid duplicates from otel-gateway
        String query = String.format(
                "sum by (owner_name, owner_kind, namespace) (" +
                        "  rate(xamops_eks_container_cpu_usage_seconds_total{cluster_name=\"%s\", container!=\"\", container!=\"POD\"}[5m]) * 1000 "
                        +
                        "  * on(pod, namespace) group_left(owner_name, owner_kind) " +
                        "  xamops_eks_kube_pod_owner{cluster_name=\"%s\", job=\"kube-state-metrics\"}" +
                        ")",
                clusterName, clusterName);

        return fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("owner_name", "owner_kind", "namespace", "value"));
    }

    /**
     * Get Memory usage grouped by owner (Deployment, DaemonSet, etc.)
     */
    public List<Map<String, String>> getWorkloadMemoryUsage(String prometheusUrl, String clusterName) {
        // Filter by job="kube-state-metrics" to avoid duplicates from otel-gateway
        String query = String.format(
                "sum by (owner_name, owner_kind, namespace) (" +
                        "  xamops_eks_container_memory_working_set_bytes{cluster_name=\"%s\", container!=\"\", container!=\"POD\"} / 1024 / 1024 "
                        +
                        "  * on(pod, namespace) group_left(owner_name, owner_kind) " +
                        "  xamops_eks_kube_pod_owner{cluster_name=\"%s\", job=\"kube-state-metrics\"}" +
                        ")",
                clusterName, clusterName);

        return fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("owner_name", "owner_kind", "namespace", "value"));
    }

    /**
     * Get detailed container information (name, image, image_id)
     * Returns list with pod, namespace, container, image, image_id
     */
    public List<Map<String, String>> getContainerInfo(String prometheusUrl, String clusterName) {
        // Filter by job="kube-state-metrics" to avoid duplicates from opencost
        String query = String.format(
                "xamops_eks_kube_pod_container_info{cluster_name=\"%s\", job=\"kube-state-metrics\"}",
                clusterName);
        List<Map<String, String>> containers = fetchMetricLabels(prometheusUrl, query,
                Arrays.asList("pod", "namespace", "container", "image", "image_id"));

        // Fallback 1: try without job filter if no results
        if (containers.isEmpty()) {
            query = String.format(
                    "xamops_eks_kube_pod_container_info{cluster_name=\"%s\"}",
                    clusterName);
            containers = fetchMetricLabels(prometheusUrl, query,
                    Arrays.asList("pod", "namespace", "container", "image", "image_id"));
        }

        // Fallback 2: try without cluster filter if still no results
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
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "pod");
        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl,
                    "sum by (pod) (kube_pod_container_resource_requests{resource=\"cpu\"})", "pod");
        }
        return result;
    }

    /**
     * Get memory requests per pod in MB (sum across all containers)
     */
    public Map<String, Double> getPodMemoryRequests(String prometheusUrl, String clusterName) {
        String query = String.format(
                "sum by (pod) (xamops_eks_kube_pod_container_resource_requests{cluster_name=\"%s\", resource=\"memory\"}) / 1024 / 1024",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "pod");
        // Standard kube-state-metrics fallback (GKE / vanilla kube-prometheus-stack)
        if (result.isEmpty()) {
            result = fetchMetricMap(prometheusUrl,
                    "sum by (pod) (kube_pod_container_resource_requests{resource=\"memory\"}) / 1024 / 1024", "pod");
        }
        return result;
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
        // Use max by to deduplicate, filter by job="kube-state-metrics"
        String query = String.format(
                "count by (phase) (max by (pod, namespace, phase) (xamops_eks_kube_pod_status_phase{cluster_name=\"%s\", job=\"kube-state-metrics\"} == 1))",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "phase");

        // Fallback without job filter
        if (result.isEmpty()) {
            query = String.format(
                    "count by (phase) (max by (pod, namespace, phase) (xamops_eks_kube_pod_status_phase{cluster_name=\"%s\"} == 1))",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "phase");
        }

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
        // Use cAdvisor metrics, filter out POD container and empty containers
        String query = String.format(
                "sum by (pod) (rate(xamops_eks_container_cpu_usage_seconds_total{cluster_name=\"%s\", container!=\"\", container!=\"POD\"}[5m])) * 1000",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "pod");

        // Fallback 1
        if (result.isEmpty()) {
            query = String.format(
                    "sum by (pod) (rate(xamops_eks_container_cpu_usage{cluster_name=\"%s\", container!=\"\", container!=\"POD\"}[5m])) * 1000",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "pod");
        }

        logger.info("🔍 Pod CPU usage from containers: {} pods found", result.size());
        return result;
    }

    // ============================================================================================
    // ✅ NEW: METRICS FOR DEDICATED PODS/CONTAINERS/NODES TABS
    // These methods are ADDITIVE - they don't modify existing functionality
    // ============================================================================================

    /**
     * Get pod status phase counts (Running, Pending, Failed, Succeeded)
     */
    public Map<String, Integer> getPodStatusPhaseCounts(String prometheusUrl, String clusterName) {
        // Use max by (pod, namespace, phase) to deduplicate pods from multiple scrapers
        // Filter by job="kube-state-metrics" to avoid duplicates from opencost
        String query = String.format(
                "count by (phase) (max by (pod, namespace, phase) (xamops_eks_kube_pod_status_phase{cluster_name=\"%s\", job=\"kube-state-metrics\"}))",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "phase");

        // Fallback without job filter if no results
        if (result.isEmpty()) {
            query = String.format(
                    "count by (phase) (max by (pod, namespace, phase) (xamops_eks_kube_pod_status_phase{cluster_name=\"%s\"}))",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "phase");
        }

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
        // Use max by to deduplicate, filter by job="kube-state-metrics"
        String query = String.format(
                "count by (qos_class) (max by (pod, namespace, qos_class) (xamops_eks_kube_pod_status_qos_class{cluster_name=\"%s\", job=\"kube-state-metrics\"}))",
                clusterName);
        Map<String, Double> result = fetchMetricMap(prometheusUrl, query, "qos_class");

        // Fallback without job filter
        if (result.isEmpty()) {
            query = String.format(
                    "count by (qos_class) (max by (pod, namespace, qos_class) (xamops_eks_kube_pod_status_qos_class{cluster_name=\"%s\"}))",
                    clusterName);
            result = fetchMetricMap(prometheusUrl, query, "qos_class");
        }

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

        // Running containers - use max by to deduplicate from multiple scrapers
        String runningQuery = String.format(
                "count(max by (pod, namespace, container) (xamops_eks_kube_pod_container_status_running{cluster_name=\"%s\", job=\"kube-state-metrics\"} == 1))",
                clusterName);
        Double running = fetchScalarValue(prometheusUrl, runningQuery);
        counts.put("Running", running != null ? running.intValue() : 0);

        // Waiting containers - use max by to deduplicate
        String waitingQuery = String.format(
                "count(max by (pod, namespace, container) (xamops_eks_kube_pod_container_status_waiting{cluster_name=\"%s\", job=\"kube-state-metrics\"} == 1))",
                clusterName);
        Double waiting = fetchScalarValue(prometheusUrl, waitingQuery);
        counts.put("Waiting", waiting != null ? waiting.intValue() : 0);

        // Terminated containers - use max by to deduplicate
        String terminatedQuery = String.format(
                "count(max by (pod, namespace, container) (xamops_eks_kube_pod_container_status_terminated{cluster_name=\"%s\", job=\"kube-state-metrics\"} == 1))",
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
     * Filter by job="kube-state-metrics" to avoid duplicates from opencost
     */
    private Map<String, Boolean> getContainerRunningStatus(String prometheusUrl, String clusterName) {
        // First try with job filter for deduplication
        String query = String.format(
                "xamops_eks_kube_pod_container_status_running{cluster_name=\"%s\", job=\"kube-state-metrics\"}",
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

            // Fallback: if no results, try without job filter
            if (status.isEmpty()) {
                query = String.format(
                        "xamops_eks_kube_pod_container_status_running{cluster_name=\"%s\"}",
                        clusterName);
                url = normalizeUrl(prometheusUrl) + "/api/v1/query?query=" +
                        java.net.URLEncoder.encode(query, "UTF-8");
                response = restTemplate.getForObject(url, String.class);
                root = objectMapper.readTree(response);
                results = root.path("data").path("result");

                for (JsonNode entry : results) {
                    JsonNode metric = entry.path("metric");
                    String pod = metric.path("pod").asText();
                    String container = metric.path("container").asText();
                    String key = pod + "/" + container;
                    double value = entry.path("value").get(1).asDouble();
                    status.put(key, value == 1.0);
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching container running status: {}", e.getMessage());
        }
        return status;
    }

}