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

    // ✅ ADDED: Missing methods that accept dynamic URL
    public Double getClusterCpuUsage(String prometheusUrl, String clusterName) {
        String query = String.format("100 - (avg(rate(xamops_eks_node_cpu_seconds_total{mode=\"idle\", cluster_name=\"%s\"}[5m])) * 100)", clusterName);
        return fetchScalarValue(prometheusUrl, query);
    }

    public Double getClusterMemoryUsage(String prometheusUrl, String clusterName) {
        String query = String.format("100 * (1 - sum(xamops_eks_node_memory_MemAvailable_bytes{cluster_name=\"%s\"}) / sum(xamops_eks_node_memory_MemTotal_bytes{cluster_name=\"%s\"}))", clusterName, clusterName);
        return fetchScalarValue(prometheusUrl, query);
    }

    public Integer getClusterNodeCount(String prometheusUrl, String clusterName) {
        String query = String.format("count(count by (node) (xamops_eks_kube_node_info{cluster_name=\"%s\"}))", clusterName);
        Double val = fetchScalarValue(prometheusUrl, query);
        return val != null ? val.intValue() : 0;
    }

    // ... (Existing Node/Pod methods below remain unchanged) ...

    public List<Map<String, String>> getClusterNodes(String prometheusUrl, String clusterName) {
        String query = String.format("last_over_time(xamops_eks_kube_node_info{cluster_name=\"%s\"}[24h])", clusterName);
        return fetchMetricLabels(prometheusUrl, query, Arrays.asList("node", "internal_ip", "os_image", "kubelet_version", "instance_type", "topology_kubernetes_io_zone"));
    }

    public Map<String, Double> getNodeCpuUsage(String prometheusUrl, String clusterName) {
        String query = String.format("100 - (avg by (node) (rate(xamops_eks_node_cpu_seconds_total{cluster_name=\"%s\", mode=\"idle\"}[5m])) * 100)", clusterName);
        return fetchMetricMap(prometheusUrl, query, "node");
    }

    public Map<String, Double> getNodeMemoryUsage(String prometheusUrl, String clusterName) {
        String query = String.format("100 * (1 - (sum by (node) (xamops_eks_node_memory_MemAvailable_bytes{cluster_name=\"%s\"}) / sum by (node) (xamops_eks_node_memory_MemTotal_bytes{cluster_name=\"%s\"})))", clusterName, clusterName);
        return fetchMetricMap(prometheusUrl, query, "node");
    }

    public Map<String, Double> getNodeCreationTime(String prometheusUrl, String clusterName) {
        String query = String.format("max by (node) (last_over_time(xamops_eks_kube_node_created{cluster_name=\"%s\"}[24h]))", clusterName);
        return fetchMetricMap(prometheusUrl, query, "node");
    }

    public List<Map<String, String>> getClusterPods(String prometheusUrl, String clusterName) {
        String query = String.format("last_over_time(xamops_eks_kube_pod_info{cluster_name=\"%s\"}[24h])", clusterName);
        return fetchMetricLabels(prometheusUrl, query, Arrays.asList("pod", "namespace", "node", "pod_ip"));
    }

    public Map<String, String> getClusterPodStatuses(String prometheusUrl, String clusterName) {
        String query = String.format("last_over_time(xamops_eks_kube_pod_status_phase{cluster_name=\"%s\"}[24h]) == 1", clusterName);
        List<Map<String, String>> results = fetchMetricLabels(prometheusUrl, query, Arrays.asList("pod", "phase"));
        Map<String, String> resultMap = new HashMap<>();
        for (Map<String, String> m : results) {
            if (m.containsKey("pod") && m.containsKey("phase")) resultMap.put(m.get("pod"), m.get("phase"));
        }
        return resultMap;
    }

    public Map<String, Double> getPodRestarts(String prometheusUrl, String clusterName) {
        String query = String.format("sum by (pod) (last_over_time(xamops_eks_kube_pod_container_status_restarts_total{cluster_name=\"%s\"}[24h]))", clusterName);
        return fetchMetricMap(prometheusUrl, query, "pod");
    }

    public List<Map<String, String>> getClusterDeployments(String prometheusUrl, String clusterName) {
        String query = String.format("last_over_time(xamops_eks_kube_deployment_spec_replicas{cluster_name=\"%s\"}[24h])", clusterName);
        return fetchMetricLabels(prometheusUrl, query, Arrays.asList("deployment", "namespace"));
    }

    public Map<String, Double> getDeploymentAvailableReplicas(String prometheusUrl, String clusterName) {
        String query = String.format("max by (deployment) (last_over_time(xamops_eks_kube_deployment_status_replicas_available{cluster_name=\"%s\"}[24h]))", clusterName);
        return fetchMetricMap(prometheusUrl, query, "deployment");
    }

    public Map<String, Double> getDeploymentSpecReplicas(String prometheusUrl, String clusterName) {
        String query = String.format("max by (deployment) (last_over_time(xamops_eks_kube_deployment_spec_replicas{cluster_name=\"%s\"}[24h]))", clusterName);
        return fetchMetricMap(prometheusUrl, query, "deployment");
    }

    public Map<String, Double> getDeploymentUpdatedReplicas(String prometheusUrl, String clusterName) {
        String query = String.format("max by (deployment) (last_over_time(xamops_eks_kube_deployment_status_replicas_updated{cluster_name=\"%s\"}[24h]))", clusterName);
        return fetchMetricMap(prometheusUrl, query, "deployment");
    }

    public List<Map<String, String>> getFalcoAlerts(String prometheusUrl, String clusterName) {
        String metricName = "xamops_eks_falcosecurity_falcosidekick_falco_events_total";
        String query = String.format("sum by (rule, priority, k8s_pod_name, k8s_ns_name) (%s{cluster_name=\"%s\"}) > 0", metricName, clusterName);
        List<Map<String, String>> rawResults = fetchMetricLabels(prometheusUrl, query, Arrays.asList("rule", "priority", "k8s_pod_name", "k8s_ns_name"));
        List<Map<String, String>> finalResults = new ArrayList<>();
        for (Map<String, String> data : rawResults) {
            Map<String, String> mapped = new HashMap<>(data);
            if (mapped.containsKey("k8s_pod_name")) mapped.put("pod", mapped.get("k8s_pod_name"));
            if (mapped.containsKey("k8s_ns_name")) mapped.put("namespace", mapped.get("k8s_ns_name"));
            finalResults.add(mapped);
        }
        return finalResults;
    }

    // --- Helpers ---
    private List<Map<String, String>> fetchMetricLabels(String baseUrl, String query, List<String> labelsToExtract) {
        List<Map<String, String>> resultList = new ArrayList<>();
        if (baseUrl == null || baseUrl.isEmpty()) return resultList;
        try {
            URI url = buildUrl(baseUrl, query);
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            if (!isSuccess(root)) return resultList;
            JsonNode results = root.path("data").path("result");
            if (results.isArray()) {
                for (JsonNode item : results) {
                    JsonNode metricNode = item.path("metric");
                    Map<String, String> map = new HashMap<>();
                    for (String label : labelsToExtract) {
                        if (metricNode.has(label)) map.put(label, metricNode.get(label).asText());
                    }
                    if (!map.isEmpty()) resultList.add(map);
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing Prometheus Label JSON: {}", e.getMessage());
        }
        return resultList;
    }

    private Map<String, Double> fetchMetricMap(String baseUrl, String query, String keyLabel) {
        Map<String, Double> result = new HashMap<>();
        if (baseUrl == null || baseUrl.isEmpty()) return result;
        try {
            URI url = buildUrl(baseUrl, query);
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            if (!isSuccess(root)) return result;
            JsonNode results = root.path("data").path("result");
            if (results.isArray()) {
                for (JsonNode item : results) {
                    String key = item.path("metric").path(keyLabel).asText(null);
                    double value = parseValue(item.path("value"));
                    if (key != null) result.put(key, value);
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing Prometheus Map JSON: {}", e.getMessage());
        }
        return result;
    }

    private Double fetchScalarValue(String baseUrl, String query) {
        if (baseUrl == null || baseUrl.isEmpty()) return 0.0;
        try {
            URI url = buildUrl(baseUrl, query);
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            if (!isSuccess(root)) return 0.0;
            JsonNode results = root.path("data").path("result");
            if (results.isArray() && results.size() > 0) {
                return parseValue(results.get(0).path("value"));
            }
        } catch (Exception e) {
            logger.error("Error parsing Prometheus Scalar JSON: {}", e.getMessage());
        }
        return 0.0;
    }

    private URI buildUrl(String baseUrl, String query) {
        String cleanUrl = normalizeUrl(baseUrl);
        return UriComponentsBuilder.fromHttpUrl(cleanUrl).path("/api/v1/query").queryParam("query", query).build().toUri();
    }

    private boolean isSuccess(JsonNode root) {
        return "success".equals(root.path("status").asText());
    }

    private double parseValue(JsonNode valueNode) {
        if (valueNode.isArray() && valueNode.size() >= 2) {
            try { return Double.parseDouble(valueNode.get(1).asText()); } 
            catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }
}