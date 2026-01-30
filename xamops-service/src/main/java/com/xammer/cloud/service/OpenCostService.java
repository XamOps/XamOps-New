package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.k8s.OpenCostDashboard;
import com.xammer.cloud.dto.k8s.OpenCostDashboard.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Service to fetch cost allocation data from OpenCost API
 * Uses Kubernetes service proxy to reach OpenCost inside the cluster
 */
@Service
public class OpenCostService {

    private static final Logger logger = LoggerFactory.getLogger(OpenCostService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // OpenCost service configuration - these are the default values
    private static final String OPENCOST_NAMESPACE = "opencost";
    private static final String OPENCOST_SERVICE = "opencost";
    private static final int OPENCOST_PORT = 9003;

    /**
     * Fetch comprehensive cost data from OpenCost API
     */
    public OpenCostDashboard fetchDashboardData(KubernetesClient client) {
        OpenCostDashboard dashboard = new OpenCostDashboard();
        dashboard.setLastUpdated(Instant.now().toString());

        try {
            logger.info("Fetching OpenCost data...");

            // Get list of currently active nodes for filtering historical data
            // Also build a map of node name -> instance type for enrichment
            Set<String> activeNodeNames = new HashSet<>();
            Map<String, String> nodeInstanceTypes = new HashMap<>();
            try {
                var nodes = client.nodes().list().getItems();
                for (var node : nodes) {
                    String nodeName = node.getMetadata().getName();
                    activeNodeNames.add(nodeName);

                    // Get instance type from node labels
                    Map<String, String> labels = node.getMetadata().getLabels();
                    if (labels != null) {
                        String instanceType = labels.get("node.kubernetes.io/instance-type");
                        if (instanceType == null) {
                            instanceType = labels.get("beta.kubernetes.io/instance-type");
                        }
                        if (instanceType != null) {
                            nodeInstanceTypes.put(nodeName, instanceType);
                        }
                    }
                }
                logger.info("Connectivity verified. Cluster has {} active nodes: {}",
                    activeNodeNames.size(), activeNodeNames);
            } catch (Exception e) {
                logger.error("Connectivity check failed!", e);
                return dashboard;
            }

            // Check if OpenCost service exists
            try {
                var service = client.services()
                        .inNamespace(OPENCOST_NAMESPACE)
                        .withName(OPENCOST_SERVICE)
                        .get();
                if (service == null) {
                    logger.warn("OpenCost service not found in namespace {}", OPENCOST_NAMESPACE);
                    return dashboard;
                }
                logger.info("OpenCost service found: {}", service.getMetadata().getName());
            } catch (Exception e) {
                logger.warn("Could not verify OpenCost service: {}", e.getMessage());
            }

            // Fetch namespace allocations (24h window for meaningful data)
            List<NamespaceAllocation> namespaceAllocations = fetchNamespaceAllocations(client, "24h");
            dashboard.setNamespaceAllocations(namespaceAllocations);
            logger.info("Fetched {} namespace allocations", namespaceAllocations.size());

            // Fetch controller allocations with namespace info using combined aggregation
            // This gives us namespace + controller + cost data in one query
            List<ControllerAllocation> controllerAllocations = fetchControllerAllocationsWithNamespace(client, "24h");
            dashboard.setControllerAllocations(controllerAllocations);
            logger.info("Fetched {} controller allocations with namespace data", controllerAllocations.size());

            // Fetch pod allocations (1h window to limit data size)
            List<PodAllocation> podAllocations = fetchPodAllocations(client, "1h");
            dashboard.setPodAllocations(podAllocations);
            logger.info("Fetched {} pod allocations", podAllocations.size());

            // Fetch node allocations and filter to only active nodes
            List<NodeAllocation> nodeAllocations = fetchNodeAllocations(client, "24h");
            // Filter out historical/terminated nodes - only keep nodes that currently exist
            // Also enrich with instance type from Kubernetes labels
            List<NodeAllocation> activeNodeAllocations = nodeAllocations.stream()
                    .filter(na -> activeNodeNames.contains(na.getNode()))
                    .map(na -> {
                        // Enrich with instance type from Kubernetes node labels
                        String instanceType = nodeInstanceTypes.get(na.getNode());
                        if (instanceType != null) {
                            na.setInstanceType(instanceType);
                        }
                        return na;
                    })
                    .collect(java.util.stream.Collectors.toList());
            dashboard.setNodeAllocations(activeNodeAllocations);
            logger.info("Fetched {} node allocations, filtered to {} active nodes",
                nodeAllocations.size(), activeNodeAllocations.size());

            // Note: Controller allocations are now derived from pod allocations above
            // No need to enrich - they already have accurate namespace/cost data

            // Calculate summary
            calculateSummary(dashboard);

            // Generate cost optimization recommendations
            generateOptimizations(dashboard);

            dashboard.setEnabled(true);
            logger.info("OpenCost data fetch complete. Total daily cost: ${}, {} optimization recommendations",
                String.format("%.2f", dashboard.getSummary().getTotalDailyCost()),
                dashboard.getOptimizations().size());

        } catch (Exception e) {
            logger.error("Error fetching OpenCost data: {}", e.getMessage(), e);
        }

        return dashboard;
    }

    /**
     * Fetch allocation data by namespace
     */
    private List<NamespaceAllocation> fetchNamespaceAllocations(KubernetesClient client, String window) {
        List<NamespaceAllocation> allocations = new ArrayList<>();
        try {
            String path = String.format("/allocation/compute?window=%s&aggregate=namespace&accumulate=true",
                    URLEncoder.encode(window, StandardCharsets.UTF_8));
            JsonNode response = proxyRequest(client, path);

            if (response != null && response.has("data") && response.get("data").isArray()) {
                JsonNode dataArray = response.get("data");
                for (JsonNode windowData : dataArray) {
                    Iterator<String> fieldNames = windowData.fieldNames();
                    while (fieldNames.hasNext()) {
                        String namespace = fieldNames.next();
                        JsonNode alloc = windowData.get(namespace);
                        NamespaceAllocation na = parseNamespaceAllocation(alloc, namespace);
                        if (na != null) {
                            allocations.add(na);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching namespace allocations: {}", e.getMessage());
        }
        return allocations;
    }

    /**
     * Fetch allocation data by controller with namespace info
     * Uses combined aggregation to get namespace + controllerKind + controller in one query
     */
    private List<ControllerAllocation> fetchControllerAllocationsWithNamespace(KubernetesClient client, String window) {
        List<ControllerAllocation> allocations = new ArrayList<>();
        try {
            // Use combined aggregation: namespace,controllerKind,controller
            // This gives us all the info we need with proper cost data
            String path = String.format("/allocation/compute?window=%s&aggregate=namespace,controllerKind,controller&accumulate=true",
                    URLEncoder.encode(window, StandardCharsets.UTF_8));
            JsonNode response = proxyRequest(client, path);

            if (response != null && response.has("data") && response.get("data").isArray()) {
                JsonNode dataArray = response.get("data");
                for (JsonNode windowData : dataArray) {
                    Iterator<String> fieldNames = windowData.fieldNames();
                    while (fieldNames.hasNext()) {
                        String key = fieldNames.next(); // Format: "namespace/controllerKind/controller"
                        JsonNode alloc = windowData.get(key);

                        // Skip idle and unallocated entries
                        if (key.contains("__idle__") || key.contains("__unallocated__")) {
                            continue;
                        }

                        ControllerAllocation ca = parseControllerAllocationFromCombinedKey(alloc, key);
                        if (ca != null && ca.getTotalCost() != null && ca.getTotalCost() > 0.001) {
                            allocations.add(ca);
                        }
                    }
                }
            }

            // Sort by total cost descending
            allocations.sort((a, b) -> Double.compare(
                nullSafe(b.getTotalCost()), nullSafe(a.getTotalCost())));

            logger.info("Fetched {} controller allocations with costs", allocations.size());

        } catch (Exception e) {
            logger.error("Error fetching controller allocations with namespace: {}", e.getMessage());
        }
        return allocations;
    }

    /**
     * Parse controller allocation from combined key format: namespace/controllerKind/controller
     */
    private ControllerAllocation parseControllerAllocationFromCombinedKey(JsonNode alloc, String combinedKey) {
        if (alloc == null || alloc.isNull()) return null;

        ControllerAllocation ca = new ControllerAllocation();

        // Parse the combined key: namespace/controllerKind/controller
        String[] parts = combinedKey.split("/");
        if (parts.length >= 3) {
            ca.setNamespace(parts[0]);
            ca.setControllerKind(parts[1]);
            ca.setController(parts[2]);
        } else if (parts.length == 2) {
            ca.setNamespace(parts[0]);
            ca.setController(parts[1]);
            ca.setControllerKind(getStringValue(alloc, "properties", "controllerKind"));
        } else {
            ca.setController(combinedKey);
            ca.setNamespace(getStringValue(alloc, "properties", "namespace"));
            ca.setControllerKind(getStringValue(alloc, "properties", "controllerKind"));
        }

        ca.setCluster(getStringValue(alloc, "properties", "cluster"));

        // Costs
        ca.setCpuCost(getDoubleValue(alloc, "cpuCost"));
        ca.setRamCost(getDoubleValue(alloc, "ramCost"));
        ca.setPvCost(getDoubleValue(alloc, "pvCost"));
        ca.setNetworkCost(getDoubleValue(alloc, "networkCost"));
        ca.setTotalCost(getDoubleValue(alloc, "totalCost"));

        // Resources
        ca.setCpuCores(getDoubleValue(alloc, "cpuCores"));
        ca.setCpuCoreRequestAverage(getDoubleValue(alloc, "cpuCoreRequestAverage"));
        ca.setCpuCoreUsageAverage(getDoubleValue(alloc, "cpuCoreUsageAverage"));
        ca.setRamBytes(getDoubleValue(alloc, "ramBytes"));
        ca.setRamByteRequestAverage(getDoubleValue(alloc, "ramByteRequestAverage"));
        ca.setRamByteUsageAverage(getDoubleValue(alloc, "ramByteUsageAverage"));

        // Efficiency
        ca.setCpuEfficiency(getDoubleValue(alloc, "cpuEfficiency"));
        ca.setRamEfficiency(getDoubleValue(alloc, "ramEfficiency"));
        ca.setTotalEfficiency(getDoubleValue(alloc, "totalEfficiency"));

        ca.setMinutes(getDoubleValue(alloc, "minutes"));

        // Calculate monthly cost
        double minutes = ca.getMinutes() != null ? ca.getMinutes() : 0;
        if (minutes > 0 && ca.getTotalCost() != null) {
            double costPerMinute = ca.getTotalCost() / minutes;
            ca.setMonthlyCost(costPerMinute * 60 * 24 * 30);
        }

        return ca;
    }

    /**
     * Fetch allocation data by controller (deployments, statefulsets, daemonsets)
     * @deprecated Use fetchControllerAllocationsWithNamespace instead
     */
    @Deprecated
    private List<ControllerAllocation> fetchControllerAllocations(KubernetesClient client, String window) {
        List<ControllerAllocation> allocations = new ArrayList<>();
        try {
            String path = String.format("/allocation/compute?window=%s&aggregate=controller&accumulate=true",
                    URLEncoder.encode(window, StandardCharsets.UTF_8));
            JsonNode response = proxyRequest(client, path);

            if (response != null && response.has("data") && response.get("data").isArray()) {
                JsonNode dataArray = response.get("data");
                for (JsonNode windowData : dataArray) {
                    Iterator<String> fieldNames = windowData.fieldNames();
                    while (fieldNames.hasNext()) {
                        String controller = fieldNames.next();
                        JsonNode alloc = windowData.get(controller);
                        ControllerAllocation ca = parseControllerAllocation(alloc, controller);
                        if (ca != null) {
                            allocations.add(ca);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching controller allocations: {}", e.getMessage());
        }
        return allocations;
    }

    /**
     * Fetch allocation data by pod
     */
    private List<PodAllocation> fetchPodAllocations(KubernetesClient client, String window) {
        List<PodAllocation> allocations = new ArrayList<>();
        try {
            String path = String.format("/allocation/compute?window=%s&aggregate=pod&accumulate=true",
                    URLEncoder.encode(window, StandardCharsets.UTF_8));
            JsonNode response = proxyRequest(client, path);

            if (response != null && response.has("data") && response.get("data").isArray()) {
                JsonNode dataArray = response.get("data");
                for (JsonNode windowData : dataArray) {
                    Iterator<String> fieldNames = windowData.fieldNames();
                    while (fieldNames.hasNext()) {
                        String pod = fieldNames.next();
                        JsonNode alloc = windowData.get(pod);
                        PodAllocation pa = parsePodAllocation(alloc, pod);
                        if (pa != null) {
                            allocations.add(pa);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching pod allocations: {}", e.getMessage());
        }
        return allocations;
    }

    /**
     * Fetch allocation data by node
     */
    private List<NodeAllocation> fetchNodeAllocations(KubernetesClient client, String window) {
        List<NodeAllocation> allocations = new ArrayList<>();
        try {
            String path = String.format("/allocation/compute?window=%s&aggregate=node&accumulate=true",
                    URLEncoder.encode(window, StandardCharsets.UTF_8));
            JsonNode response = proxyRequest(client, path);

            if (response != null && response.has("data") && response.get("data").isArray()) {
                JsonNode dataArray = response.get("data");
                for (JsonNode windowData : dataArray) {
                    Iterator<String> fieldNames = windowData.fieldNames();
                    while (fieldNames.hasNext()) {
                        String node = fieldNames.next();
                        JsonNode alloc = windowData.get(node);
                        NodeAllocation na = parseNodeAllocation(alloc, node);
                        if (na != null) {
                            allocations.add(na);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching node allocations: {}", e.getMessage());
        }
        return allocations;
    }

    /**
     * Proxy HTTP request through Kubernetes API to OpenCost service
     */
    private JsonNode proxyRequest(KubernetesClient client, String path) {
        try {
            // Build the service proxy URL
            // Format: /api/v1/namespaces/{namespace}/services/{service}:{port}/proxy{path}
            String proxyPath = String.format("/api/v1/namespaces/%s/services/%s:%d/proxy%s",
                    OPENCOST_NAMESPACE, OPENCOST_SERVICE, OPENCOST_PORT, path);

            logger.debug("Proxying request to: {}", proxyPath);

            // Use the raw() method to make HTTP request through K8s API
            // raw() returns a String in Fabric8 Kubernetes client
            String responseString = client.raw(proxyPath);

            if (responseString == null || responseString.isEmpty()) {
                logger.warn("OpenCost returned empty response for path: {}", path);
                return null;
            }

            logger.debug("OpenCost raw response (first 500 chars): {}",
                responseString.length() > 500 ? responseString.substring(0, 500) : responseString);

            JsonNode result = objectMapper.readTree(responseString);

            // OpenCost API returns {"status": "success", "data": [...]} or {"status": "error", "error": "..."}
            if (result.has("status")) {
                String status = result.get("status").asText();
                if ("success".equalsIgnoreCase(status)) {
                    logger.debug("OpenCost API returned success for path: {}", path);
                    return result;
                } else if ("error".equalsIgnoreCase(status)) {
                    String errorMsg = result.has("error") ? result.get("error").asText() : "Unknown error";
                    logger.error("OpenCost API returned error: {} for path: {}", errorMsg, path);
                    return null;
                }
            }

            // Fallback: also check for code field (backward compatibility)
            if (result.has("code") && result.get("code").asInt() == 200) {
                return result;
            }

            // If response has data, assume it's valid
            if (result.has("data")) {
                logger.debug("OpenCost response has data field, treating as success");
                return result;
            }

            logger.warn("OpenCost API returned unexpected response format: {}",
                responseString.length() > 200 ? responseString.substring(0, 200) : responseString);
            return result;

        } catch (Exception e) {
            logger.error("Failed to proxy request to OpenCost: {} - {} ({})",
                path, e.getMessage(), e.getClass().getSimpleName());
            if (logger.isDebugEnabled()) {
                logger.debug("Full exception stack trace:", e);
            }
        }
        return null;
    }

    /**
     * Parse namespace allocation from OpenCost response
     */
    private NamespaceAllocation parseNamespaceAllocation(JsonNode alloc, String namespace) {
        if (alloc == null || alloc.isNull()) return null;

        NamespaceAllocation na = new NamespaceAllocation();
        na.setNamespace(namespace);
        na.setCluster(getStringValue(alloc, "properties", "cluster"));

        // Costs
        na.setCpuCost(getDoubleValue(alloc, "cpuCost"));
        na.setRamCost(getDoubleValue(alloc, "ramCost"));
        na.setPvCost(getDoubleValue(alloc, "pvCost"));
        na.setNetworkCost(getDoubleValue(alloc, "networkCost"));
        na.setLoadBalancerCost(getDoubleValue(alloc, "loadBalancerCost"));
        na.setGpuCost(getDoubleValue(alloc, "gpuCost"));
        na.setSharedCost(getDoubleValue(alloc, "sharedCost"));
        na.setExternalCost(getDoubleValue(alloc, "externalCost"));
        na.setTotalCost(getDoubleValue(alloc, "totalCost"));

        // CPU Resources
        na.setCpuCores(getDoubleValue(alloc, "cpuCores"));
        na.setCpuCoreHours(getDoubleValue(alloc, "cpuCoreHours"));
        na.setCpuCoreRequestAverage(getDoubleValue(alloc, "cpuCoreRequestAverage"));
        na.setCpuCoreUsageAverage(getDoubleValue(alloc, "cpuCoreUsageAverage"));
        na.setCpuCoreLimitAverage(getDoubleValue(alloc, "cpuCoreLimitAverage"));

        // RAM Resources
        na.setRamBytes(getDoubleValue(alloc, "ramBytes"));
        na.setRamByteHours(getDoubleValue(alloc, "ramByteHours"));
        na.setRamByteRequestAverage(getDoubleValue(alloc, "ramByteRequestAverage"));
        na.setRamByteUsageAverage(getDoubleValue(alloc, "ramByteUsageAverage"));
        na.setRamByteLimitAverage(getDoubleValue(alloc, "ramByteLimitAverage"));

        // PV Resources
        na.setPvBytes(getDoubleValue(alloc, "pvBytes"));
        na.setPvByteHours(getDoubleValue(alloc, "pvByteHours"));

        // Efficiency
        na.setCpuEfficiency(getDoubleValue(alloc, "cpuEfficiency"));
        na.setRamEfficiency(getDoubleValue(alloc, "ramEfficiency"));
        na.setTotalEfficiency(getDoubleValue(alloc, "totalEfficiency"));

        // Idle costs
        na.setCpuIdleCost(getDoubleValue(alloc, "cpuCostIdle"));
        na.setRamIdleCost(getDoubleValue(alloc, "ramCostIdle"));

        // Time
        na.setMinutes(getDoubleValue(alloc, "minutes"));

        // Calculate monthly cost (extrapolate from available data)
        double minutes = na.getMinutes() != null ? na.getMinutes() : 0;
        if (minutes > 0 && na.getTotalCost() != null) {
            double costPerMinute = na.getTotalCost() / minutes;
            na.setMonthlyCost(costPerMinute * 60 * 24 * 30); // 30 days
        }

        return na;
    }

    /**
     * Parse controller allocation from OpenCost response
     */
    private ControllerAllocation parseControllerAllocation(JsonNode alloc, String controller) {
        if (alloc == null || alloc.isNull()) return null;

        ControllerAllocation ca = new ControllerAllocation();
        ca.setController(controller);
        ca.setNamespace(getStringValue(alloc, "properties", "namespace"));
        ca.setControllerKind(getStringValue(alloc, "properties", "controllerKind"));
        ca.setCluster(getStringValue(alloc, "properties", "cluster"));

        // Costs
        ca.setCpuCost(getDoubleValue(alloc, "cpuCost"));
        ca.setRamCost(getDoubleValue(alloc, "ramCost"));
        ca.setPvCost(getDoubleValue(alloc, "pvCost"));
        ca.setNetworkCost(getDoubleValue(alloc, "networkCost"));
        ca.setTotalCost(getDoubleValue(alloc, "totalCost"));

        // Resources
        ca.setCpuCores(getDoubleValue(alloc, "cpuCores"));
        ca.setCpuCoreRequestAverage(getDoubleValue(alloc, "cpuCoreRequestAverage"));
        ca.setCpuCoreUsageAverage(getDoubleValue(alloc, "cpuCoreUsageAverage"));
        ca.setRamBytes(getDoubleValue(alloc, "ramBytes"));
        ca.setRamByteRequestAverage(getDoubleValue(alloc, "ramByteRequestAverage"));
        ca.setRamByteUsageAverage(getDoubleValue(alloc, "ramByteUsageAverage"));

        // Efficiency
        ca.setCpuEfficiency(getDoubleValue(alloc, "cpuEfficiency"));
        ca.setRamEfficiency(getDoubleValue(alloc, "ramEfficiency"));
        ca.setTotalEfficiency(getDoubleValue(alloc, "totalEfficiency"));

        ca.setMinutes(getDoubleValue(alloc, "minutes"));

        // Calculate monthly cost
        double minutes = ca.getMinutes() != null ? ca.getMinutes() : 0;
        if (minutes > 0 && ca.getTotalCost() != null) {
            double costPerMinute = ca.getTotalCost() / minutes;
            ca.setMonthlyCost(costPerMinute * 60 * 24 * 30);
        }

        return ca;
    }

    /**
     * Parse pod allocation from OpenCost response
     */
    private PodAllocation parsePodAllocation(JsonNode alloc, String pod) {
        if (alloc == null || alloc.isNull()) return null;

        PodAllocation pa = new PodAllocation();
        pa.setPod(pod);
        pa.setNamespace(getStringValue(alloc, "properties", "namespace"));
        pa.setNode(getStringValue(alloc, "properties", "node"));
        pa.setController(getStringValue(alloc, "properties", "controller"));
        pa.setControllerKind(getStringValue(alloc, "properties", "controllerKind"));
        pa.setCluster(getStringValue(alloc, "properties", "cluster"));

        // Costs
        pa.setCpuCost(getDoubleValue(alloc, "cpuCost"));
        pa.setRamCost(getDoubleValue(alloc, "ramCost"));
        pa.setPvCost(getDoubleValue(alloc, "pvCost"));
        pa.setNetworkCost(getDoubleValue(alloc, "networkCost"));
        pa.setTotalCost(getDoubleValue(alloc, "totalCost"));

        // CPU Resources
        pa.setCpuCores(getDoubleValue(alloc, "cpuCores"));
        pa.setCpuCoreRequestAverage(getDoubleValue(alloc, "cpuCoreRequestAverage"));
        pa.setCpuCoreUsageAverage(getDoubleValue(alloc, "cpuCoreUsageAverage"));
        pa.setCpuCoreLimitAverage(getDoubleValue(alloc, "cpuCoreLimitAverage"));

        // RAM Resources
        pa.setRamBytes(getDoubleValue(alloc, "ramBytes"));
        pa.setRamByteRequestAverage(getDoubleValue(alloc, "ramByteRequestAverage"));
        pa.setRamByteUsageAverage(getDoubleValue(alloc, "ramByteUsageAverage"));
        pa.setRamByteLimitAverage(getDoubleValue(alloc, "ramByteLimitAverage"));

        // Efficiency
        pa.setCpuEfficiency(getDoubleValue(alloc, "cpuEfficiency"));
        pa.setRamEfficiency(getDoubleValue(alloc, "ramEfficiency"));
        pa.setTotalEfficiency(getDoubleValue(alloc, "totalEfficiency"));

        pa.setMinutes(getDoubleValue(alloc, "minutes"));

        return pa;
    }

    /**
     * Parse node allocation from OpenCost response
     */
    private NodeAllocation parseNodeAllocation(JsonNode alloc, String node) {
        if (alloc == null || alloc.isNull()) return null;

        NodeAllocation na = new NodeAllocation();
        na.setNode(node);
        na.setCluster(getStringValue(alloc, "properties", "cluster"));
        na.setProviderID(getStringValue(alloc, "properties", "providerID"));

        // Costs
        na.setCpuCost(getDoubleValue(alloc, "cpuCost"));
        na.setRamCost(getDoubleValue(alloc, "ramCost"));
        na.setGpuCost(getDoubleValue(alloc, "gpuCost"));
        na.setTotalCost(getDoubleValue(alloc, "totalCost"));

        // Capacity
        na.setCpuCores(getDoubleValue(alloc, "cpuCores"));
        na.setRamBytes(getDoubleValue(alloc, "ramBytes"));
        na.setGpuCount(getDoubleValue(alloc, "gpuCount"));

        // Usage
        na.setCpuCoreUsageAverage(getDoubleValue(alloc, "cpuCoreUsageAverage"));
        na.setRamByteUsageAverage(getDoubleValue(alloc, "ramByteUsageAverage"));

        // Efficiency
        na.setCpuEfficiency(getDoubleValue(alloc, "cpuEfficiency"));
        na.setRamEfficiency(getDoubleValue(alloc, "ramEfficiency"));

        na.setMinutes(getDoubleValue(alloc, "minutes"));

        return na;
    }

    /**
     * Derive controller allocations by aggregating pod allocation data
     * This is more reliable than OpenCost's direct controller aggregation which often misses namespace/cost
     */
    private List<ControllerAllocation> deriveControllerAllocationsFromPods(List<PodAllocation> podAllocations) {
        // Group pods by controller key (namespace + controller name)
        Map<String, List<PodAllocation>> podsByController = new HashMap<>();

        for (PodAllocation pod : podAllocations) {
            String controller = pod.getController();
            String namespace = pod.getNamespace();

            // Skip pods without controller (standalone pods)
            if (controller == null || controller.isEmpty() || "__unallocated__".equals(controller)) {
                continue;
            }

            // Use namespace/controller as key to handle same controller name in different namespaces
            String key = (namespace != null ? namespace : "default") + "/" + controller;
            podsByController.computeIfAbsent(key, k -> new ArrayList<>()).add(pod);
        }

        List<ControllerAllocation> controllerAllocations = new ArrayList<>();

        for (Map.Entry<String, List<PodAllocation>> entry : podsByController.entrySet()) {
            List<PodAllocation> pods = entry.getValue();
            if (pods.isEmpty()) continue;

            // Get controller info from first pod
            PodAllocation firstPod = pods.get(0);

            ControllerAllocation ca = new ControllerAllocation();
            ca.setController(firstPod.getController());
            ca.setNamespace(firstPod.getNamespace());
            ca.setControllerKind(firstPod.getControllerKind());
            ca.setCluster(firstPod.getCluster());
            ca.setPodCount(pods.size());

            // Aggregate costs from all pods
            double cpuCost = 0, ramCost = 0, pvCost = 0, networkCost = 0, totalCost = 0;
            double cpuCores = 0, cpuCoreRequest = 0, cpuCoreUsage = 0;
            double ramBytes = 0, ramByteRequest = 0, ramByteUsage = 0;
            double cpuEffSum = 0, ramEffSum = 0, totalEffSum = 0;
            double minutes = 0;
            int effCount = 0;

            for (PodAllocation pod : pods) {
                cpuCost += nullSafe(pod.getCpuCost());
                ramCost += nullSafe(pod.getRamCost());
                pvCost += nullSafe(pod.getPvCost());
                networkCost += nullSafe(pod.getNetworkCost());
                totalCost += nullSafe(pod.getTotalCost());

                cpuCores += nullSafe(pod.getCpuCores());
                cpuCoreRequest += nullSafe(pod.getCpuCoreRequestAverage());
                cpuCoreUsage += nullSafe(pod.getCpuCoreUsageAverage());

                ramBytes += nullSafe(pod.getRamBytes());
                ramByteRequest += nullSafe(pod.getRamByteRequestAverage());
                ramByteUsage += nullSafe(pod.getRamByteUsageAverage());

                minutes += nullSafe(pod.getMinutes());

                // Track efficiency (weighted by time)
                if (pod.getCpuEfficiency() != null && pod.getCpuEfficiency() > 0) {
                    cpuEffSum += pod.getCpuEfficiency();
                    effCount++;
                }
                if (pod.getRamEfficiency() != null && pod.getRamEfficiency() > 0) {
                    ramEffSum += pod.getRamEfficiency();
                }
                if (pod.getTotalEfficiency() != null && pod.getTotalEfficiency() > 0) {
                    totalEffSum += pod.getTotalEfficiency();
                }
            }

            ca.setCpuCost(cpuCost);
            ca.setRamCost(ramCost);
            ca.setPvCost(pvCost);
            ca.setNetworkCost(networkCost);
            ca.setTotalCost(totalCost);

            ca.setCpuCores(cpuCores);
            ca.setCpuCoreRequestAverage(cpuCoreRequest);
            ca.setCpuCoreUsageAverage(cpuCoreUsage);

            ca.setRamBytes(ramBytes);
            ca.setRamByteRequestAverage(ramByteRequest);
            ca.setRamByteUsageAverage(ramByteUsage);

            ca.setMinutes(minutes);

            // Calculate average efficiency
            if (effCount > 0) {
                ca.setCpuEfficiency(cpuEffSum / effCount);
                ca.setRamEfficiency(ramEffSum / effCount);
                ca.setTotalEfficiency(totalEffSum / effCount);
            }

            // Calculate monthly cost (extrapolate from available data)
            if (minutes > 0 && totalCost > 0) {
                double costPerMinute = totalCost / minutes;
                ca.setMonthlyCost(costPerMinute * 60 * 24 * 30);
            } else {
                ca.setMonthlyCost(totalCost * 30); // Fallback: assume totalCost is daily
            }

            // Calculate cost per pod
            if (ca.getPodCount() > 0 && totalCost > 0) {
                ca.setCostPerPod(totalCost / ca.getPodCount());
            }

            controllerAllocations.add(ca);
        }

        // Sort by total cost descending
        controllerAllocations.sort((a, b) ->
            Double.compare(nullSafe(b.getTotalCost()), nullSafe(a.getTotalCost())));

        logger.info("Derived {} controller allocations from {} pods",
            controllerAllocations.size(), podAllocations.size());

        return controllerAllocations;
    }

    /**
     * Enrich controller allocations with namespace and controllerKind from pod allocations
     * OpenCost's controller aggregation sometimes doesn't include namespace in properties
     * @deprecated Use deriveControllerAllocationsFromPods instead for more accurate data
     */
    @Deprecated
    private void enrichControllerAllocations(OpenCostDashboard dashboard) {
        // This method is no longer needed since we derive controller allocations from pods
        // Kept for backward compatibility
        logger.debug("enrichControllerAllocations called but no longer needed");
    }

    /**
     * Calculate summary metrics from all allocations
     */
    private void calculateSummary(OpenCostDashboard dashboard) {
        Summary summary = dashboard.getSummary();

        double totalCpuCost = 0, totalRamCost = 0, totalPvCost = 0;
        double totalNetworkCost = 0, totalLbCost = 0, totalGpuCost = 0;
        double totalSharedCost = 0, totalExternalCost = 0, totalCost = 0;
        double totalCpuCores = 0, totalRamBytes = 0, totalPvBytes = 0;
        double totalCpuIdleCost = 0, totalRamIdleCost = 0;
        double weightedCpuEfficiency = 0, weightedRamEfficiency = 0, totalMinutes = 0;

        // Aggregate from namespace allocations (most comprehensive)
        for (NamespaceAllocation na : dashboard.getNamespaceAllocations()) {
            totalCpuCost += nullSafe(na.getCpuCost());
            totalRamCost += nullSafe(na.getRamCost());
            totalPvCost += nullSafe(na.getPvCost());
            totalNetworkCost += nullSafe(na.getNetworkCost());
            totalLbCost += nullSafe(na.getLoadBalancerCost());
            totalGpuCost += nullSafe(na.getGpuCost());
            totalSharedCost += nullSafe(na.getSharedCost());
            totalExternalCost += nullSafe(na.getExternalCost());
            totalCost += nullSafe(na.getTotalCost());
            totalCpuCores += nullSafe(na.getCpuCores());
            totalRamBytes += nullSafe(na.getRamBytes());
            totalPvBytes += nullSafe(na.getPvBytes());
            totalCpuIdleCost += nullSafe(na.getCpuIdleCost());
            totalRamIdleCost += nullSafe(na.getRamIdleCost());

            double minutes = nullSafe(na.getMinutes());
            totalMinutes += minutes;
            weightedCpuEfficiency += nullSafe(na.getCpuEfficiency()) * minutes;
            weightedRamEfficiency += nullSafe(na.getRamEfficiency()) * minutes;
        }

        // Set costs
        summary.setCpuCost(totalCpuCost);
        summary.setRamCost(totalRamCost);
        summary.setPvCost(totalPvCost);
        summary.setNetworkCost(totalNetworkCost);
        summary.setLoadBalancerCost(totalLbCost);
        summary.setGpuCost(totalGpuCost);
        summary.setSharedCost(totalSharedCost);
        summary.setExternalCost(totalExternalCost);

        // Calculate daily cost (extrapolate if needed)
        // If we have 24h of data, totalCost is the daily cost
        // Otherwise extrapolate
        if (totalMinutes > 0) {
            double costPerMinute = totalCost / totalMinutes;
            summary.setTotalDailyCost(costPerMinute * 60 * 24);
            summary.setTotalMonthlyCost(costPerMinute * 60 * 24 * 30);
        } else {
            summary.setTotalDailyCost(totalCost);
            summary.setTotalMonthlyCost(totalCost * 30);
        }

        // Resources
        summary.setTotalCpuCores(totalCpuCores);
        summary.setTotalRamBytes(totalRamBytes);
        summary.setTotalPvBytes(totalPvBytes);

        // Efficiency (weighted average)
        if (totalMinutes > 0) {
            summary.setCpuEfficiency(weightedCpuEfficiency / totalMinutes);
            summary.setRamEfficiency(weightedRamEfficiency / totalMinutes);
            summary.setTotalEfficiency((weightedCpuEfficiency + weightedRamEfficiency) / (2 * totalMinutes));
        }

        // Idle costs
        summary.setCpuIdleCost(totalCpuIdleCost);
        summary.setRamIdleCost(totalRamIdleCost);
        summary.setIdleCost(totalCpuIdleCost + totalRamIdleCost);

        // Counts
        summary.setNamespaceCount(dashboard.getNamespaceAllocations().size());
        summary.setPodCount(dashboard.getPodAllocations().size());
        summary.setControllerCount(dashboard.getControllerAllocations().size());

        // Calculate cost percentages for namespaces
        if (totalCost > 0) {
            for (NamespaceAllocation na : dashboard.getNamespaceAllocations()) {
                double nsCost = nullSafe(na.getTotalCost());
                na.setCostPercentage((nsCost / totalCost) * 100);
            }
        }
    }

    /**
     * Generate cost optimization recommendations based on allocation data
     */
    private void generateOptimizations(OpenCostDashboard dashboard) {
        List<CostOptimization> optimizations = new ArrayList<>();

        // 1. Identify over-provisioned workloads (efficiency < 40%)
        for (ControllerAllocation ca : dashboard.getControllerAllocations()) {
            double efficiency = nullSafe(ca.getTotalEfficiency());
            double cost = nullSafe(ca.getTotalCost());

            // Skip if no meaningful cost or data
            if (cost < 0.01) continue;

            // Over-provisioned: efficiency < 40%
            if (efficiency > 0 && efficiency < 0.4) {
                CostOptimization opt = new CostOptimization();
                opt.setType("OVER_PROVISIONED");
                opt.setSeverity(efficiency < 0.2 ? "HIGH" : "MEDIUM");
                opt.setResourceType("WORKLOAD");
                opt.setResourceName(ca.getController());
                opt.setNamespace(ca.getNamespace());
                opt.setEfficiency(efficiency * 100);
                opt.setCurrentCost(cost);

                // Current state
                opt.setCurrentCpuRequest(nullSafe(ca.getCpuCoreRequestAverage()) * 1000); // millicores
                opt.setCurrentMemoryRequest(nullSafe(ca.getRamByteRequestAverage()) / (1024 * 1024)); // MB
                opt.setCurrentCpuUsage(nullSafe(ca.getCpuCoreUsageAverage()) * 1000);
                opt.setCurrentMemoryUsage(nullSafe(ca.getRamByteUsageAverage()) / (1024 * 1024));

                // Recommended: usage + 20% buffer
                double recCpu = opt.getCurrentCpuUsage() * 1.2;
                double recMem = opt.getCurrentMemoryUsage() * 1.2;
                opt.setRecommendedCpuRequest(recCpu);
                opt.setRecommendedMemoryRequest(recMem);

                // Calculate potential savings
                double cpuSavingsRatio = opt.getCurrentCpuRequest() > 0
                    ? (opt.getCurrentCpuRequest() - recCpu) / opt.getCurrentCpuRequest()
                    : 0;
                double memSavingsRatio = opt.getCurrentMemoryRequest() > 0
                    ? (opt.getCurrentMemoryRequest() - recMem) / opt.getCurrentMemoryRequest()
                    : 0;
                double avgSavingsRatio = (cpuSavingsRatio + memSavingsRatio) / 2;

                double monthlyCost = nullSafe(ca.getMonthlyCost());
                opt.setEstimatedMonthlySavings(monthlyCost * avgSavingsRatio);
                opt.setSavingsPercentage(avgSavingsRatio * 100);

                opt.setDescription(String.format("%s in %s is only %.0f%% efficient",
                    ca.getController(), ca.getNamespace(), efficiency * 100));
                opt.setRecommendation(String.format("Reduce CPU from %.0fm to %.0fm, Memory from %.0fMi to %.0fMi",
                    opt.getCurrentCpuRequest(), recCpu, opt.getCurrentMemoryRequest(), recMem));

                optimizations.add(opt);
            }
        }

        // 2. Identify namespaces with high idle costs
        for (NamespaceAllocation na : dashboard.getNamespaceAllocations()) {
            double idleCost = nullSafe(na.getCpuIdleCost()) + nullSafe(na.getRamIdleCost());
            double totalCost = nullSafe(na.getTotalCost());

            // Skip system namespaces and low-cost namespaces
            if (totalCost < 0.05) continue;
            if ("kube-system".equals(na.getNamespace()) ||
                "kube-public".equals(na.getNamespace()) ||
                "kube-node-lease".equals(na.getNamespace())) continue;

            // If idle cost is > 50% of total cost, flag it
            if (totalCost > 0 && idleCost / totalCost > 0.5) {
                CostOptimization opt = new CostOptimization();
                opt.setType("IDLE_RESOURCE");
                opt.setSeverity(idleCost / totalCost > 0.7 ? "HIGH" : "MEDIUM");
                opt.setResourceType("NAMESPACE");
                opt.setResourceName(na.getNamespace());
                opt.setNamespace(na.getNamespace());
                opt.setCurrentCost(totalCost);
                opt.setEfficiency(nullSafe(na.getTotalEfficiency()) * 100);

                double monthlyIdle = idleCost * 30; // Extrapolate to monthly
                opt.setEstimatedMonthlySavings(monthlyIdle);
                opt.setSavingsPercentage((idleCost / totalCost) * 100);

                opt.setDescription(String.format("Namespace %s has %.0f%% idle resources",
                    na.getNamespace(), (idleCost / totalCost) * 100));
                opt.setRecommendation("Review and right-size workloads in this namespace");

                optimizations.add(opt);
            }
        }

        // 3. Identify underutilized nodes with rightsizing recommendations
        for (NodeAllocation na : dashboard.getNodeAllocations()) {
            double cpuEff = nullSafe(na.getCpuEfficiency());
            double ramEff = nullSafe(na.getRamEfficiency());
            double avgEff = (cpuEff + ramEff) / 2;
            double cost = nullSafe(na.getTotalCost());

            if (cost < 0.01) continue;

            // Node with < 40% average efficiency - flag for rightsizing
            if (avgEff > 0 && avgEff < 0.4) {
                CostOptimization opt = new CostOptimization();
                opt.setType("NODE_RIGHTSIZING");
                opt.setSeverity(avgEff < 0.2 ? "HIGH" : "MEDIUM");
                opt.setResourceType("NODE");
                opt.setResourceName(na.getNode());
                opt.setNamespace(na.getInstanceType()); // Store instance type in namespace field for display
                opt.setCurrentCost(cost);
                opt.setEfficiency(avgEff * 100);

                // Current resource usage
                double cpuCores = nullSafe(na.getCpuCores());
                double cpuUsage = nullSafe(na.getCpuCoreUsageAverage());
                double ramGb = nullSafe(na.getRamBytes()) / (1024.0 * 1024.0 * 1024.0);
                double ramUsageGb = nullSafe(na.getRamByteUsageAverage()) / (1024.0 * 1024.0 * 1024.0);

                opt.setCurrentCpuRequest(cpuCores * 1000); // millicores capacity
                opt.setCurrentCpuUsage(cpuUsage * 1000); // millicores usage
                opt.setCurrentMemoryRequest(ramGb * 1024); // MB capacity
                opt.setCurrentMemoryUsage(ramUsageGb * 1024); // MB usage

                // Recommend smaller instance - usage + 50% buffer
                double recCpuCores = cpuUsage * 1.5;
                double recRamGb = ramUsageGb * 1.5;
                opt.setRecommendedCpuRequest(recCpuCores * 1000);
                opt.setRecommendedMemoryRequest(recRamGb * 1024);

                // Potential savings based on utilization gap
                double monthlyCost = cost * 30;
                double savingsRatio = Math.max(0, 1 - (avgEff * 2.5)); // Aggressive savings estimate
                opt.setEstimatedMonthlySavings(monthlyCost * savingsRatio);
                opt.setSavingsPercentage(savingsRatio * 100);

                String instanceType = na.getInstanceType() != null ? na.getInstanceType() : "Unknown";
                opt.setDescription(String.format("Node %s (%s) is %.0f%% utilized - CPU: %.1f/%.1f cores, RAM: %.1f/%.1f GB",
                    na.getNode(), instanceType, avgEff * 100, cpuUsage, cpuCores, ramUsageGb, ramGb));

                String recommendation = generateNodeRightsizingRecommendation(instanceType, avgEff, cpuCores, cpuUsage);
                opt.setRecommendation(recommendation);

                optimizations.add(opt);
            }
        }

        // Sort by estimated savings (highest first)
        optimizations.sort((a, b) ->
            Double.compare(nullSafe(b.getEstimatedMonthlySavings()), nullSafe(a.getEstimatedMonthlySavings())));

        // Limit to top 20 recommendations
        if (optimizations.size() > 20) {
            optimizations = new ArrayList<>(optimizations.subList(0, 20));
        }

        dashboard.setOptimizations(optimizations);

        // Log summary
        double totalPotentialSavings = optimizations.stream()
            .mapToDouble(o -> nullSafe(o.getEstimatedMonthlySavings()))
            .sum();
        logger.info("Generated {} optimization recommendations with ${} potential monthly savings",
            optimizations.size(), String.format("%.2f", totalPotentialSavings));
    }

    /**
     * Generate node rightsizing recommendation based on instance type and utilization
     */
    private String generateNodeRightsizingRecommendation(String instanceType, double avgEfficiency, double cpuCores, double cpuUsage) {
        // Map common instance families to smaller alternatives
        if (instanceType == null || instanceType.equals("Unknown")) {
            return "Consider downsizing to a smaller instance type or enabling cluster autoscaler to remove idle nodes";
        }

        // Parse instance type (e.g., m5.xlarge -> m5, xlarge)
        String[] parts = instanceType.split("\\.");
        if (parts.length < 2) {
            return String.format("Consider downsizing from %s - current utilization is only %.0f%%", instanceType, avgEfficiency * 100);
        }

        String family = parts[0];
        String size = parts[1];

        // Size ordering for recommendations
        String[] sizeOrder = {"nano", "micro", "small", "medium", "large", "xlarge", "2xlarge", "4xlarge", "8xlarge", "12xlarge", "16xlarge", "24xlarge"};
        int currentIndex = -1;
        for (int i = 0; i < sizeOrder.length; i++) {
            if (size.equals(sizeOrder[i])) {
                currentIndex = i;
                break;
            }
        }

        // Recommend smaller size based on efficiency
        String recommendedSize;
        if (avgEfficiency < 0.15 && currentIndex > 2) {
            // Very low utilization - recommend 2 sizes smaller
            recommendedSize = sizeOrder[Math.max(0, currentIndex - 2)];
        } else if (avgEfficiency < 0.3 && currentIndex > 1) {
            // Low utilization - recommend 1 size smaller
            recommendedSize = sizeOrder[Math.max(0, currentIndex - 1)];
        } else {
            // Moderate under-utilization - consider consolidation
            return String.format("Consider consolidating workloads from %s or enabling cluster autoscaler", instanceType);
        }

        String recommendedType = family + "." + recommendedSize;
        return String.format("Consider downsizing from %s to %s (estimated %.0f%% cost reduction)",
            instanceType, recommendedType, (1 - avgEfficiency * 2) * 100);
    }

    // Helper methods
    private double nullSafe(Double value) {
        return value != null ? value : 0.0;
    }

    private Double getDoubleValue(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName)) return 0.0;
        JsonNode field = node.get(fieldName);
        if (field.isNull()) return 0.0;
        return field.asDouble(0.0);
    }

    private String getStringValue(JsonNode node, String... path) {
        JsonNode current = node;
        for (String field : path) {
            if (current == null || !current.has(field)) return null;
            current = current.get(field);
        }
        if (current == null || current.isNull()) return null;
        return current.asText();
    }
}
