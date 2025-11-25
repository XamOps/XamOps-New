package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PrometheusService {

    private static final Logger logger = LoggerFactory.getLogger(PrometheusService.class);

    private final RestTemplate restTemplate;
    private final String prometheusUrl;
    private final ObjectMapper objectMapper;

    public PrometheusService(RestTemplate restTemplate,
                             @Value("${prometheus.api.url}") String prometheusUrl) {
        this.restTemplate = restTemplate;
        // Ensure no trailing slash to avoid double slashes in URL
        this.prometheusUrl = prometheusUrl.endsWith("/") ? 
                             prometheusUrl.substring(0, prometheusUrl.length() - 1) : 
                             prometheusUrl;
        this.objectMapper = new ObjectMapper();
    }

    // ============================================================================================
    // 1. NODE METRICS
    // ============================================================================================

    public List<Map<String, String>> getClusterNodes(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("last_over_time(xamops_eks_kube_node_info{cluster_name=\"%s\"}[24h])", clusterName);
        return fetchMetricLabels(query, Arrays.asList("node", "internal_ip", "os_image", "kubelet_version", "instance_type", "topology_kubernetes_io_zone"));
    }

    public Map<String, Double> getNodeCpuUsage(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("100 - (avg by (node) (rate(xamops_eks_node_cpu_seconds_total{cluster_name=\"%s\", mode=\"idle\"}[5m])) * 100)", clusterName);
        return fetchMetricMap(query, "node"); 
    }

    public Map<String, Double> getNodeMemoryUsage(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("100 * (1 - (sum by (node) (xamops_eks_node_memory_MemAvailable_bytes{cluster_name=\"%s\"}) / sum by (node) (xamops_eks_node_memory_MemTotal_bytes{cluster_name=\"%s\"})))", clusterName, clusterName);
        return fetchMetricMap(query, "node");
    }

    // ============================================================================================
    // 2. POD METRICS
    // ============================================================================================

    public List<Map<String, String>> getClusterPods(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("last_over_time(xamops_eks_kube_pod_info{cluster_name=\"%s\"}[24h])", clusterName);
        return fetchMetricLabels(query, Arrays.asList("pod", "namespace", "node", "pod_ip"));
    }

    public Map<String, String> getClusterPodStatuses(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("last_over_time(xamops_eks_kube_pod_status_phase{cluster_name=\"%s\"}[24h]) == 1", clusterName);
        List<Map<String, String>> results = fetchMetricLabels(query, Arrays.asList("pod", "phase"));
        return results.stream()
                .filter(m -> m.containsKey("pod") && m.containsKey("phase"))
                .collect(Collectors.toMap(m -> m.get("pod"), m -> m.get("phase"), (a, b) -> a));
    }

    public Map<String, Double> getPodRestarts(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("sum by (pod) (last_over_time(xamops_eks_kube_pod_container_status_restarts_total{cluster_name=\"%s\"}[24h]))", clusterName);
        return fetchMetricMap(query, "pod");
    }

    // ============================================================================================
    // 3. DEPLOYMENT METRICS
    // ============================================================================================

    public List<Map<String, String>> getClusterDeployments(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("last_over_time(xamops_eks_kube_deployment_spec_replicas{cluster_name=\"%s\"}[24h])", clusterName);
        return fetchMetricLabels(query, Arrays.asList("deployment", "namespace"));
    }

    public Map<String, Double> getDeploymentAvailableReplicas(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("max by (deployment) (last_over_time(xamops_eks_kube_deployment_status_replicas_available{cluster_name=\"%s\"}[24h]))", clusterName);
        return fetchMetricMap(query, "deployment");
    }

    public Map<String, Double> getDeploymentSpecReplicas(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("max by (deployment) (last_over_time(xamops_eks_kube_deployment_spec_replicas{cluster_name=\"%s\"}[24h]))", clusterName);
        return fetchMetricMap(query, "deployment");
    }

    public Map<String, Double> getDeploymentUpdatedReplicas(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("max by (deployment) (last_over_time(xamops_eks_kube_deployment_status_replicas_updated{cluster_name=\"%s\"}[24h]))", clusterName);
        return fetchMetricMap(query, "deployment");
    }

    // ============================================================================================
    // INTERNAL HELPERS
    // ============================================================================================

    private List<Map<String, String>> fetchMetricLabels(String query, List<String> labelsToExtract) {
        List<Map<String, String>> resultList = new ArrayList<>();
        try {
            // Use URI to prevent RestTemplate from double-encoding
            URI url = buildUrl(query);
            logger.debug("Executing PromQL Label Fetch: {}", query);
            String response = restTemplate.getForObject(url, String.class);
            
            // Log successful response (even if empty) for debugging
            logger.info("Prometheus Response for query [{}]: {}", query, response);

            JsonNode root = objectMapper.readTree(response);
            if (!isSuccess(root)) return resultList;

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
                    if (!map.isEmpty()) {
                        resultList.add(map);
                    }
                }
            }
            logger.info("Parsed {} items from Prometheus labels.", resultList.size());
        } catch (Exception e) {
            logger.error("Error parsing Prometheus Label JSON for query: {}", query, e);
        }
        return resultList;
    }

    private Map<String, Double> fetchMetricMap(String query, String keyLabel) {
        Map<String, Double> result = new HashMap<>();
        try {
            // Use URI to prevent RestTemplate from double-encoding
            URI url = buildUrl(query);
            logger.debug("Executing PromQL Map Query: {}", query);
            String response = restTemplate.getForObject(url, String.class);
            
            JsonNode root = objectMapper.readTree(response);
            if (!isSuccess(root)) return result;

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
            logger.error("Error parsing Prometheus Vector JSON for query: {}", query, e);
        }
        return result;
    }

    // Scalar methods
    public double getClusterCpuUsage(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("100 - (avg(rate(xamops_eks_node_cpu_seconds_total{cluster_name=\"%s\", mode=\"idle\"}[5m])) * 100)", clusterName);
        return fetchSingleValue(query);
    }

    public double getClusterMemoryUsage(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("100 * (1 - (sum(xamops_eks_node_memory_MemAvailable_bytes{cluster_name=\"%s\"}) / sum(xamops_eks_node_memory_MemTotal_bytes{cluster_name=\"%s\"})))", clusterName, clusterName);
        return fetchSingleValue(query);
    }

    public int getClusterNodeCount(String clusterName) {
        // FIXED: Removed duplicate prefix
        String query = String.format("count(count by (node) (last_over_time(xamops_eks_kube_node_info{cluster_name=\"%s\"}[24h])))", clusterName);
        return (int) fetchSingleValue(query);
    }

    private double fetchSingleValue(String query) {
        try {
            URI url = buildUrl(query);
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            if (isSuccess(root)) {
                JsonNode results = root.path("data").path("result");
                if (results.isArray() && results.size() > 0) {
                    return parseValue(results.get(0).path("value"));
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing Prometheus Scalar JSON", e);
        }
        return 0.0;
    }

    private URI buildUrl(String query) {
        return UriComponentsBuilder.fromHttpUrl(prometheusUrl)
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
                return 0.0;
            }
        }
        return 0.0;
    }
}