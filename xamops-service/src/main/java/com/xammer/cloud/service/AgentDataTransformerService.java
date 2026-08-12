package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.dto.k8s.KarpenterDashboard;
import com.xammer.cloud.dto.k8s.KubescapeDashboard;
import com.xammer.cloud.dto.k8s.OpenCostDashboard;
import com.xammer.cloud.dto.k8s.TrivyDashboard;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Service for transforming raw agent-pushed data into dashboard DTOs.
 *
 * The agent pushes raw Kubernetes CRD data (VulnerabilityReports, ConfigurationScanSummaries, etc.)
 * This service transforms that raw data into the same DTO format used by the direct K8s query services
 * (TrivyService, KubescapeService, OpenCostService).
 */
@Service
public class AgentDataTransformerService {

    private static final Logger logger = LoggerFactory.getLogger(AgentDataTransformerService.class);

    private final AgentDataCacheRepository agentDataCacheRepository;
    private final ObjectMapper objectMapper;

    public AgentDataTransformerService(AgentDataCacheRepository agentDataCacheRepository) {
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Transform cached Trivy data into TrivyDashboard DTO.
     * Input: List of VulnerabilityReport CRD objects
     */
    public TrivyDashboard transformTrivyData(Long clusterConfigId) {
        TrivyDashboard dashboard = new TrivyDashboard();

        Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(clusterConfigId, "trivy-vulnerabilities");

        if (cacheOpt.isEmpty()) {
            logger.debug("No cached Trivy data found for cluster config: {}", clusterConfigId);
            return dashboard;
        }

        try {
            AgentDataCache cache = cacheOpt.get();
            List<Map<String, Object>> items = objectMapper.readValue(
                    cache.getDataJson(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            List<TrivyDashboard.WorkloadVulnerability> workloads = new ArrayList<>();
            Set<String> images = new HashSet<>();
            Set<String> namespaces = new HashSet<>();
            ZonedDateTime latestScan = null;

            for (Map<String, Object> item : items) {
                try {
                    TrivyDashboard.WorkloadVulnerability workload = transformVulnerabilityReport(item);
                    if (workload != null) {
                        workloads.add(workload);

                        if (workload.getImage() != null) {
                            images.add(workload.getImage() + (workload.getTag() != null ? ":" + workload.getTag() : ""));
                        }
                        if (workload.getNamespace() != null) {
                            namespaces.add(workload.getNamespace());
                        }
                        if (workload.getLastScanTime() != null) {
                            try {
                                ZonedDateTime scanTime = ZonedDateTime.parse(workload.getLastScanTime());
                                if (latestScan == null || scanTime.isAfter(latestScan)) {
                                    latestScan = scanTime;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error transforming vulnerability report: {}", e.getMessage());
                }
            }

            dashboard.setWorkloads(workloads);
            calculateTrivySummary(dashboard, images, namespaces, latestScan);

            logger.info("Transformed {} Trivy vulnerability reports from agent data", workloads.size());

        } catch (Exception e) {
            logger.error("Error transforming Trivy data: {}", e.getMessage(), e);
        }

        return dashboard;
    }

    @SuppressWarnings("unchecked")
    private TrivyDashboard.WorkloadVulnerability transformVulnerabilityReport(Map<String, Object> item) {
        TrivyDashboard.WorkloadVulnerability workload = new TrivyDashboard.WorkloadVulnerability();

        // Support TWO formats:
        // 1. K8s CRD format: metadata.name, report.summary, report.vulnerabilities
        // 2. Agent simplified format: name, namespace, summary, vulnerabilities (direct fields)

        Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
        if (metadata != null) {
            // K8s CRD format
            workload.setName((String) metadata.get("name"));
            workload.setNamespace((String) metadata.get("namespace"));

            Map<String, Object> labels = (Map<String, Object>) metadata.get("labels");
            if (labels != null) {
                workload.setKind((String) labels.get("trivy-operator.resource.kind"));
                workload.setImage((String) labels.get("trivy-operator.container.name"));
            }

            String creationTimestamp = (String) metadata.get("creationTimestamp");
            workload.setLastScanTime(creationTimestamp);
        } else {
            // Agent simplified format - direct fields
            workload.setName((String) item.get("name"));
            workload.setNamespace((String) item.get("namespace"));
            workload.setKind((String) item.get("kind"));
            workload.setImage((String) item.get("image"));
            workload.setTag((String) item.get("tag"));
        }

        // Get summary - either from report.summary (CRD) or summary (agent format)
        Map<String, Object> report = (Map<String, Object>) item.get("report");
        Map<String, Object> summary = null;
        List<Map<String, Object>> vulns = null;

        if (report != null) {
            // K8s CRD format
            summary = (Map<String, Object>) report.get("summary");
            vulns = (List<Map<String, Object>>) report.get("vulnerabilities");
        } else {
            // Agent simplified format - direct fields
            summary = (Map<String, Object>) item.get("summary");
            vulns = (List<Map<String, Object>>) item.get("vulnerabilities");
        }

        if (summary != null) {
            workload.setCritical(getIntValue(summary, "criticalCount"));
            workload.setHigh(getIntValue(summary, "highCount"));
            workload.setMedium(getIntValue(summary, "mediumCount"));
            workload.setLow(getIntValue(summary, "lowCount"));
        }

        // Transform vulnerability details
        if (vulns != null) {
            List<TrivyDashboard.VulnerabilityDetail> details = new ArrayList<>();
            for (Map<String, Object> v : vulns) {
                TrivyDashboard.VulnerabilityDetail detail = new TrivyDashboard.VulnerabilityDetail();
                detail.setVulnerabilityId((String) v.get("vulnerabilityID"));
                detail.setSeverity((String) v.get("severity"));
                detail.setPackageName((String) v.get("resource")); // agent uses "resource"
                if (detail.getPackageName() == null) {
                    detail.setPackageName((String) v.get("pkgName")); // fallback to CRD format
                }
                detail.setInstalledVersion((String) v.get("installedVersion"));
                detail.setFixedVersion((String) v.get("fixedVersion"));
                detail.setTitle((String) v.get("title"));
                detail.setDescription((String) v.get("description"));
                detail.setPrimaryLink((String) v.get("primaryLink"));

                Object scoreObj = v.get("score");
                if (scoreObj instanceof Number) {
                    detail.setScore(((Number) scoreObj).doubleValue());
                }

                details.add(detail);
            }
            workload.setVulnerabilities(details);
        }

        return workload;
    }

    private void calculateTrivySummary(TrivyDashboard dashboard, Set<String> images,
                                       Set<String> namespaces, ZonedDateTime latestScan) {
        TrivyDashboard.Summary summary = dashboard.getSummary();
        summary.setTotalVulnerabilityReports(dashboard.getWorkloads().size());

        int totalCritical = 0, totalHigh = 0, totalMedium = 0, totalLow = 0;
        for (TrivyDashboard.WorkloadVulnerability w : dashboard.getWorkloads()) {
            totalCritical += w.getCritical();
            totalHigh += w.getHigh();
            totalMedium += w.getMedium();
            totalLow += w.getLow();
        }

        summary.setTotalCritical(totalCritical);
        summary.setTotalHigh(totalHigh);
        summary.setTotalMedium(totalMedium);
        summary.setTotalLow(totalLow);
        summary.setUniqueImagesScanned(images.size());
        summary.setNamespacesCovered(namespaces.size());

        if (latestScan != null) {
            summary.setLastScanTime(latestScan.toLocalDateTime());
        }
    }

    /**
     * Transform cached Kubescape data into KubescapeDashboard DTO.
     * Input: List of ConfigurationScanSummary CRD objects
     */
    public KubescapeDashboard transformKubescapeData(Long clusterConfigId) {
        KubescapeDashboard dashboard = new KubescapeDashboard();

        Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(clusterConfigId, "kubescape-compliance");

        if (cacheOpt.isEmpty()) {
            logger.debug("No cached Kubescape data found for cluster config: {}", clusterConfigId);
            return dashboard;
        }

        try {
            AgentDataCache cache = cacheOpt.get();
            List<Map<String, Object>> items = objectMapper.readValue(
                    cache.getDataJson(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            List<KubescapeDashboard.ConfigScanSummary> configScans = new ArrayList<>();
            List<KubescapeDashboard.VulnerabilitySummary> vulnerabilities = new ArrayList<>();
            Map<String, KubescapeDashboard.NamespaceVulnerabilities> namespaceBreakdown = new HashMap<>();

            int totalCritical = 0, totalHigh = 0, totalMedium = 0, totalLow = 0;
            int passedControls = 0, failedControls = 0;
            String latestScanTime = null;

            for (Map<String, Object> item : items) {
                try {
                    Map<String, Object> result = transformConfigScanSummary(item);

                    KubescapeDashboard.ConfigScanSummary scan = (KubescapeDashboard.ConfigScanSummary) result.get("scan");
                    if (scan != null) {
                        configScans.add(scan);
                    }

                    totalCritical += (Integer) result.getOrDefault("critical", 0);
                    totalHigh += (Integer) result.getOrDefault("high", 0);
                    totalMedium += (Integer) result.getOrDefault("medium", 0);
                    totalLow += (Integer) result.getOrDefault("low", 0);
                    passedControls += (Integer) result.getOrDefault("passed", 0);
                    failedControls += (Integer) result.getOrDefault("failed", 0);

                    String scanTime = (String) result.get("scanTime");
                    if (scanTime != null) {
                        latestScanTime = scanTime;
                    }

                    // Aggregate namespace breakdown
                    String namespace = (String) result.get("namespace");
                    if (namespace != null) {
                        namespaceBreakdown.merge(namespace,
                                new KubescapeDashboard.NamespaceVulnerabilities(
                                        (Integer) result.getOrDefault("critical", 0),
                                        (Integer) result.getOrDefault("high", 0),
                                        (Integer) result.getOrDefault("medium", 0),
                                        (Integer) result.getOrDefault("low", 0)
                                ),
                                (existing, newVal) -> {
                                    existing.setCritical(existing.getCritical() + newVal.getCritical());
                                    existing.setHigh(existing.getHigh() + newVal.getHigh());
                                    existing.setMedium(existing.getMedium() + newVal.getMedium());
                                    existing.setLow(existing.getLow() + newVal.getLow());
                                    return existing;
                                }
                        );
                    }
                } catch (Exception e) {
                    logger.warn("Error transforming config scan summary: {}", e.getMessage());
                }
            }

            dashboard.setConfigScans(configScans);
            dashboard.setVulnerabilities(vulnerabilities);
            dashboard.setTotalCritical(totalCritical);
            dashboard.setTotalHigh(totalHigh);
            dashboard.setTotalMedium(totalMedium);
            dashboard.setTotalLow(totalLow);
            dashboard.setLastScanTime(latestScanTime);
            dashboard.setTotalWorkloads(items.size());
            dashboard.setNamespaceBreakdown(namespaceBreakdown);

            // Calculate compliance score
            if (passedControls + failedControls > 0) {
                double score = (double) passedControls / (passedControls + failedControls) * 100;
                dashboard.setOverallComplianceScore(Math.round(score * 100.0) / 100.0);
            }

            logger.info("Transformed {} Kubescape config scans from agent data", configScans.size());

        } catch (Exception e) {
            logger.error("Error transforming Kubescape data: {}", e.getMessage(), e);
        }

        return dashboard;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> transformConfigScanSummary(Map<String, Object> item) {
        Map<String, Object> result = new HashMap<>();

        // Support TWO formats:
        // 1. K8s CRD format: metadata.name, metadata.namespace, spec.severities, spec.controls
        // 2. Agent simplified format: name, namespace, severities, controls (direct fields)

        Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
        if (metadata != null) {
            // K8s CRD format
            result.put("namespace", metadata.get("namespace"));
            result.put("name", metadata.get("name"));
            result.put("scanTime", metadata.get("creationTimestamp"));
        } else {
            // Agent simplified format - direct fields
            result.put("namespace", item.get("namespace"));
            result.put("name", item.get("name"));
        }

        // Get severities - either from spec.severities (CRD) or severities (agent format)
        Map<String, Object> spec = (Map<String, Object>) item.get("spec");
        Map<String, Object> severities = null;
        Map<String, Object> controls = null;

        if (spec != null) {
            // K8s CRD format
            severities = (Map<String, Object>) spec.get("severities");
            controls = (Map<String, Object>) spec.get("controls");
        } else {
            // Agent simplified format - direct fields
            severities = (Map<String, Object>) item.get("severities");
            controls = (Map<String, Object>) item.get("controls");
        }

        if (severities != null) {
            result.put("critical", getIntValue(severities, "critical"));
            result.put("high", getIntValue(severities, "high"));
            result.put("medium", getIntValue(severities, "medium"));
            result.put("low", getIntValue(severities, "low"));
        }

        if (controls != null) {
            result.put("passed", getIntValue(controls, "passed"));
            result.put("failed", getIntValue(controls, "failed"));
        }

        // Create ConfigScanSummary DTO
        KubescapeDashboard.ConfigScanSummary scan = new KubescapeDashboard.ConfigScanSummary();
        scan.setNamespace((String) result.get("namespace"));

        int passed = (Integer) result.getOrDefault("passed", 0);
        int failed = (Integer) result.getOrDefault("failed", 0);
        if (passed + failed > 0) {
            scan.setComplianceScore((double) passed / (passed + failed) * 100);
        }

        result.put("scan", scan);
        return result;
    }

    /**
     * Transform cached OpenCost data into OpenCostDashboard DTO.
     * Input: OpenCost allocation API response
     */
    public OpenCostDashboard transformOpenCostData(Long clusterConfigId) {
        OpenCostDashboard dashboard = new OpenCostDashboard();

        Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(clusterConfigId, "opencost-cost");

        if (cacheOpt.isEmpty()) {
            logger.debug("No cached OpenCost data found for cluster config: {}", clusterConfigId);
            return dashboard;
        }

        try {
            AgentDataCache cache = cacheOpt.get();
            List<Map<String, Object>> items = objectMapper.readValue(
                    cache.getDataJson(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            // OpenCost data is already in a structured format
            // The agent pushes the response from OpenCost /allocation/compute endpoint
            if (!items.isEmpty()) {
                transformOpenCostAllocation(dashboard, items);
            }

            dashboard.setEnabled(true);
            logger.info("Transformed OpenCost allocation data from agent");

        } catch (Exception e) {
            logger.error("Error transforming OpenCost data: {}", e.getMessage(), e);
        }

        return dashboard;
    }

    /**
     * Splits the opencost-cost batch into namespace and controller allocations.
     *
     * <p>The agent sends both aggregation levels in a single batch. Controller rows carry a
     * non-empty {@code controller} field; namespace rows do not. Treating every row as a
     * namespace would double-count the cluster total and list workloads as if they were
     * namespaces, so the two are separated here.
     *
     * <p>Only namespace rows contribute to the daily total, since controller rows describe
     * the same spend broken down differently.
     */
    @SuppressWarnings("unchecked")
    private void transformOpenCostAllocation(OpenCostDashboard dashboard, List<Map<String, Object>> items) {
        List<OpenCostDashboard.NamespaceAllocation> namespaceAllocations = new ArrayList<>();
        List<OpenCostDashboard.ControllerAllocation> controllerAllocations = new ArrayList<>();
        double totalDailyCost = 0;

        for (Map<String, Object> item : items) {
            double cpuCost = getDoubleValue(item, "cpuCost");
            double ramCost = getDoubleValue(item, "ramCost");
            double networkCost = getDoubleValue(item, "networkCost");
            double pvCost = getDoubleValue(item, "pvCost");
            double totalCost = getDoubleValue(item, "totalCost");

            String namespace = (String) item.get("namespace");
            String controller = (String) item.get("controller");

            if (controller != null && !controller.isBlank()) {
                OpenCostDashboard.ControllerAllocation ctrl = new OpenCostDashboard.ControllerAllocation();
                ctrl.setController(controller);
                ctrl.setControllerKind((String) item.get("controllerKind"));
                ctrl.setNamespace(namespace);
                ctrl.setCpuCost(cpuCost);
                ctrl.setRamCost(ramCost);
                ctrl.setNetworkCost(networkCost);
                ctrl.setPvCost(pvCost);
                ctrl.setTotalCost(totalCost);
                ctrl.setMonthlyCost(totalCost * 30);
                controllerAllocations.add(ctrl);
                continue;
            }

            if (namespace == null) continue;

            OpenCostDashboard.NamespaceAllocation nsAlloc = new OpenCostDashboard.NamespaceAllocation();
            nsAlloc.setNamespace(namespace);
            nsAlloc.setCpuCost(cpuCost);
            nsAlloc.setRamCost(ramCost);
            nsAlloc.setNetworkCost(networkCost);
            nsAlloc.setPvCost(pvCost);
            nsAlloc.setTotalCost(totalCost);

            namespaceAllocations.add(nsAlloc);
            totalDailyCost += totalCost;
        }

        dashboard.setNamespaceAllocations(namespaceAllocations);
        dashboard.setControllerAllocations(controllerAllocations);

        OpenCostDashboard.Summary summary = new OpenCostDashboard.Summary();
        summary.setTotalDailyCost(totalDailyCost);
        summary.setTotalMonthlyCost(totalDailyCost * 30);
        dashboard.setSummary(summary);
    }

    /**
     * Transform cached Karpenter data into KarpenterDashboard DTO.
     * Input: Karpenter CRD objects (NodePools, EC2NodeClasses, NodeClaims)
     */
    public KarpenterDashboard transformKarpenterData(Long clusterConfigId) {
        KarpenterDashboard dashboard = new KarpenterDashboard();

        Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(clusterConfigId, "karpenter-data");

        if (cacheOpt.isEmpty()) {
            logger.debug("No cached Karpenter data found for cluster config: {}", clusterConfigId);
            dashboard.setInstalled(false);
            return dashboard;
        }

        try {
            AgentDataCache cache = cacheOpt.get();
            Map<String, Object> data = objectMapper.readValue(
                    cache.getDataJson(),
                    new TypeReference<Map<String, Object>>() {}
            );

            dashboard.setInstalled(true);
            dashboard.setVersion((String) data.getOrDefault("version", "unknown"));
            dashboard.setHealthy((Boolean) data.getOrDefault("healthy", false));
            dashboard.setPodStatus((String) data.getOrDefault("podStatus", "Unknown"));

            // Transform NodePools
            List<Map<String, Object>> nodePools = (List<Map<String, Object>>) data.get("nodePools");
            if (nodePools != null) {
                for (Map<String, Object> np : nodePools) {
                    KarpenterDashboard.NodePoolInfo nodePool = transformNodePool(np);
                    if (nodePool != null) {
                        dashboard.getNodePools().add(nodePool);
                    }
                }
            }

            // Transform EC2NodeClasses
            List<Map<String, Object>> ec2NodeClasses = (List<Map<String, Object>>) data.get("ec2NodeClasses");
            if (ec2NodeClasses != null) {
                for (Map<String, Object> enc : ec2NodeClasses) {
                    KarpenterDashboard.EC2NodeClassInfo ec2NodeClass = transformEC2NodeClass(enc);
                    if (ec2NodeClass != null) {
                        dashboard.getEc2NodeClasses().add(ec2NodeClass);
                    }
                }
            }

            // Transform NodeClaims
            List<Map<String, Object>> nodeClaims = (List<Map<String, Object>>) data.get("nodeClaims");
            if (nodeClaims != null) {
                int activeClaims = 0;
                int pendingClaims = 0;
                int spotNodes = 0;
                int onDemandNodes = 0;

                for (Map<String, Object> nc : nodeClaims) {
                    KarpenterDashboard.NodeClaimInfo nodeClaim = transformNodeClaim(nc);
                    if (nodeClaim != null) {
                        dashboard.getNodeClaims().add(nodeClaim);

                        if ("Ready".equals(nodeClaim.getPhase())) {
                            activeClaims++;
                        } else {
                            pendingClaims++;
                        }

                        if ("spot".equalsIgnoreCase(nodeClaim.getCapacityType())) {
                            spotNodes++;
                        } else {
                            onDemandNodes++;
                        }
                    }
                }

                dashboard.setTotalActiveClaims(activeClaims);
                dashboard.setTotalPendingClaims(pendingClaims);
                dashboard.setSpotNodesCount(spotNodes);
                dashboard.setOnDemandNodesCount(onDemandNodes);
            }

            // Set summary metrics
            dashboard.setTotalNodePools(dashboard.getNodePools().size());
            dashboard.setTotalEC2NodeClasses(dashboard.getEc2NodeClasses().size());
            dashboard.setTotalNodeClaims(dashboard.getNodeClaims().size());
            dashboard.setNodesProvisionedByKarpenter(dashboard.getTotalActiveClaims());

            logger.info("Transformed Karpenter data from agent: {} NodePools, {} EC2NodeClasses, {} NodeClaims",
                    dashboard.getTotalNodePools(), dashboard.getTotalEC2NodeClasses(), dashboard.getTotalNodeClaims());

        } catch (Exception e) {
            logger.error("Error transforming Karpenter data: {}", e.getMessage(), e);
            dashboard.setInstalled(false);
        }

        return dashboard;
    }

    @SuppressWarnings("unchecked")
    private KarpenterDashboard.NodePoolInfo transformNodePool(Map<String, Object> np) {
        KarpenterDashboard.NodePoolInfo nodePool = new KarpenterDashboard.NodePoolInfo();

        Map<String, Object> metadata = (Map<String, Object>) np.get("metadata");
        if (metadata != null) {
            nodePool.setName((String) metadata.get("name"));
            nodePool.setCreatedAt((String) metadata.get("creationTimestamp"));
        }

        Map<String, Object> spec = (Map<String, Object>) np.get("spec");
        if (spec != null) {
            Map<String, Object> limits = (Map<String, Object>) spec.get("limits");
            if (limits != null) {
                nodePool.setCpuLimit((String) limits.get("cpu"));
                nodePool.setMemoryLimit((String) limits.get("memory"));
            }

            Map<String, Object> disruption = (Map<String, Object>) spec.get("disruption");
            if (disruption != null) {
                nodePool.setConsolidationPolicy((String) disruption.get("consolidationPolicy"));
                nodePool.setExpireAfter((String) disruption.get("consolidateAfter"));
            }

            Map<String, Object> template = (Map<String, Object>) spec.get("template");
            if (template != null) {
                Map<String, Object> templateSpec = (Map<String, Object>) template.get("spec");
                if (templateSpec != null) {
                    Map<String, Object> nodeClassRef = (Map<String, Object>) templateSpec.get("nodeClassRef");
                    if (nodeClassRef != null) {
                        nodePool.setNodeClassRef((String) nodeClassRef.get("name"));
                    }

                    List<Map<String, Object>> requirements = (List<Map<String, Object>>) templateSpec.get("requirements");
                    if (requirements != null) {
                        for (Map<String, Object> req : requirements) {
                            String key = (String) req.get("key");
                            List<String> values = (List<String>) req.get("values");
                            if (values != null) {
                                if ("karpenter.sh/capacity-type".equals(key)) {
                                    nodePool.setCapacityTypes(values);
                                } else if ("node.kubernetes.io/instance-type".equals(key)) {
                                    nodePool.setInstanceTypes(values);
                                } else if ("kubernetes.io/arch".equals(key)) {
                                    nodePool.setArchitectures(values);
                                }
                            }
                        }
                    }
                }
            }
        }

        return nodePool;
    }

    @SuppressWarnings("unchecked")
    private KarpenterDashboard.EC2NodeClassInfo transformEC2NodeClass(Map<String, Object> enc) {
        KarpenterDashboard.EC2NodeClassInfo ec2NodeClass = new KarpenterDashboard.EC2NodeClassInfo();

        Map<String, Object> metadata = (Map<String, Object>) enc.get("metadata");
        if (metadata != null) {
            ec2NodeClass.setName((String) metadata.get("name"));
            ec2NodeClass.setCreatedAt((String) metadata.get("creationTimestamp"));
        }

        Map<String, Object> spec = (Map<String, Object>) enc.get("spec");
        if (spec != null) {
            ec2NodeClass.setAmiFamily((String) spec.get("amiFamily"));
            ec2NodeClass.setRole((String) spec.get("role"));
            ec2NodeClass.setInstanceProfile((String) spec.get("instanceProfile"));
        }

        Map<String, Object> status = (Map<String, Object>) enc.get("status");
        if (status != null) {
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
            if (conditions != null) {
                for (Map<String, Object> cond : conditions) {
                    if ("Ready".equals(cond.get("type"))) {
                        ec2NodeClass.setReady("True".equals(cond.get("status")));
                        ec2NodeClass.setReadyMessage((String) cond.get("message"));
                        break;
                    }
                }
            }

            List<Map<String, Object>> subnets = (List<Map<String, Object>>) status.get("subnets");
            if (subnets != null) {
                for (Map<String, Object> subnet : subnets) {
                    ec2NodeClass.getSubnets().add(new KarpenterDashboard.SubnetInfo(
                            (String) subnet.get("id"),
                            (String) subnet.get("zone"),
                            (String) subnet.get("zoneId")
                    ));
                }
            }

            List<Map<String, Object>> securityGroups = (List<Map<String, Object>>) status.get("securityGroups");
            if (securityGroups != null) {
                for (Map<String, Object> sg : securityGroups) {
                    ec2NodeClass.getSecurityGroups().add(new KarpenterDashboard.SecurityGroupInfo(
                            (String) sg.get("id"),
                            (String) sg.get("name")
                    ));
                }
            }
        }

        return ec2NodeClass;
    }

    @SuppressWarnings("unchecked")
    private KarpenterDashboard.NodeClaimInfo transformNodeClaim(Map<String, Object> nc) {
        KarpenterDashboard.NodeClaimInfo nodeClaim = new KarpenterDashboard.NodeClaimInfo();

        Map<String, Object> metadata = (Map<String, Object>) nc.get("metadata");
        if (metadata != null) {
            nodeClaim.setName((String) metadata.get("name"));
            nodeClaim.setCreatedAt((String) metadata.get("creationTimestamp"));

            Map<String, Object> labels = (Map<String, Object>) metadata.get("labels");
            if (labels != null) {
                nodeClaim.setNodePoolName((String) labels.get("karpenter.sh/nodepool"));
                nodeClaim.setCapacityType((String) labels.get("karpenter.sh/capacity-type"));
            }
        }

        Map<String, Object> status = (Map<String, Object>) nc.get("status");
        if (status != null) {
            nodeClaim.setNodeName((String) status.get("nodeName"));
            nodeClaim.setInstanceType((String) status.get("instanceType"));
            nodeClaim.setZone((String) status.get("zone"));

            List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
            if (conditions != null) {
                for (Map<String, Object> cond : conditions) {
                    if ("Ready".equals(cond.get("type"))) {
                        nodeClaim.setPhase("True".equals(cond.get("status")) ? "Ready" : "Pending");
                        break;
                    }
                }
            }
        }

        return nodeClaim;
    }

    /**
     * Transform cached cluster-metadata into a ClusterMetadataResult.
     * Input: ClusterMetadata object from agent with nodes, namespaces, podsSummary
     */
    public ClusterMetadataResult transformClusterMetadata(Long clusterConfigId) {
        ClusterMetadataResult result = new ClusterMetadataResult();

        Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(clusterConfigId, "cluster-metadata");

        if (cacheOpt.isEmpty()) {
            logger.debug("No cached cluster-metadata found for cluster config: {}", clusterConfigId);
            return result;
        }

        try {
            AgentDataCache cache = cacheOpt.get();

            // cluster-metadata is stored as an array with a single element: [{"nodes":..., "namespaces":..., "podsSummary":...}]
            List<Map<String, Object>> dataList = objectMapper.readValue(
                    cache.getDataJson(),
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            if (dataList.isEmpty()) {
                logger.warn("cluster-metadata array is empty");
                return result;
            }

            Map<String, Object> data = dataList.get(0);

            // Extract nodes
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
            if (nodes != null) {
                result.setTotalNodes(nodes.size());

                // Extract node details
                List<ClusterMetadataResult.NodeInfo> nodeInfos = new ArrayList<>();
                for (Map<String, Object> node : nodes) {
                    ClusterMetadataResult.NodeInfo nodeInfo = new ClusterMetadataResult.NodeInfo();
                    nodeInfo.setName((String) node.get("name"));
                    nodeInfo.setPhase((String) node.get("phase"));

                    Map<String, String> labels = (Map<String, String>) node.get("labels");
                    if (labels != null) {
                        nodeInfo.setLabels(labels);
                    }

                    Map<String, Object> capacity = (Map<String, Object>) node.get("capacity");
                    if (capacity != null) {
                        nodeInfo.setCpuCapacity((String) capacity.get("cpu"));
                        nodeInfo.setMemoryCapacity((String) capacity.get("memory"));
                        nodeInfo.setPodsCapacity((String) capacity.get("pods"));
                    }

                    nodeInfos.add(nodeInfo);
                }
                result.setNodes(nodeInfos);
            }

            // Extract namespaces
            List<String> namespaces = (List<String>) data.get("namespaces");
            if (namespaces != null) {
                result.setTotalNamespaces(namespaces.size());
                result.setNamespaces(namespaces);
            }

            // Extract pods summary
            Map<String, Object> podsSummary = (Map<String, Object>) data.get("podsSummary");
            if (podsSummary != null) {
                result.setTotalPods(getIntValue(podsSummary, "total"));
                result.setRunningPods(getIntValue(podsSummary, "running"));
                result.setPendingPods(getIntValue(podsSummary, "pending"));
                result.setFailedPods(getIntValue(podsSummary, "failed"));
            }

            logger.info("Transformed cluster-metadata from agent: {} nodes, {} namespaces, {} pods",
                    result.getTotalNodes(), result.getTotalNamespaces(), result.getTotalPods());

        } catch (Exception e) {
            logger.error("Error transforming cluster-metadata: {}", e.getMessage(), e);
        }

        return result;
    }

    /**
     * Result class for cluster metadata transformation.
     */
    public static class ClusterMetadataResult {
        private int totalNodes = 0;
        private int totalNamespaces = 0;
        private int totalPods = 0;
        private int runningPods = 0;
        private int pendingPods = 0;
        private int failedPods = 0;
        private List<NodeInfo> nodes = new ArrayList<>();
        private List<String> namespaces = new ArrayList<>();

        public int getTotalNodes() { return totalNodes; }
        public void setTotalNodes(int totalNodes) { this.totalNodes = totalNodes; }
        public int getTotalNamespaces() { return totalNamespaces; }
        public void setTotalNamespaces(int totalNamespaces) { this.totalNamespaces = totalNamespaces; }
        public int getTotalPods() { return totalPods; }
        public void setTotalPods(int totalPods) { this.totalPods = totalPods; }
        public int getRunningPods() { return runningPods; }
        public void setRunningPods(int runningPods) { this.runningPods = runningPods; }
        public int getPendingPods() { return pendingPods; }
        public void setPendingPods(int pendingPods) { this.pendingPods = pendingPods; }
        public int getFailedPods() { return failedPods; }
        public void setFailedPods(int failedPods) { this.failedPods = failedPods; }
        public List<NodeInfo> getNodes() { return nodes; }
        public void setNodes(List<NodeInfo> nodes) { this.nodes = nodes; }
        public List<String> getNamespaces() { return namespaces; }
        public void setNamespaces(List<String> namespaces) { this.namespaces = namespaces; }

        public static class NodeInfo {
            private String name;
            private String phase;
            private Map<String, String> labels = new HashMap<>();
            private String cpuCapacity;
            private String memoryCapacity;
            private String podsCapacity;

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }
            public String getPhase() { return phase; }
            public void setPhase(String phase) { this.phase = phase; }
            public Map<String, String> getLabels() { return labels; }
            public void setLabels(Map<String, String> labels) { this.labels = labels; }
            public String getCpuCapacity() { return cpuCapacity; }
            public void setCpuCapacity(String cpuCapacity) { this.cpuCapacity = cpuCapacity; }
            public String getMemoryCapacity() { return memoryCapacity; }
            public void setMemoryCapacity(String memoryCapacity) { this.memoryCapacity = memoryCapacity; }
            public String getPodsCapacity() { return podsCapacity; }
            public void setPodsCapacity(String podsCapacity) { this.podsCapacity = podsCapacity; }
        }
    }

    /**
     * Check if agent data is available for a cluster.
     */
    public boolean hasAgentData(Long clusterConfigId) {
        return agentDataCacheRepository.countDataTypesByCluster(clusterConfigId) > 0;
    }

    /**
     * Check if specific data type is available from agent.
     */
    public boolean hasAgentDataType(Long clusterConfigId, String dataType) {
        return agentDataCacheRepository.findByClusterConfigIdAndDataType(clusterConfigId, dataType).isPresent();
    }

    /**
     * Get the timestamp of the most recent agent data.
     */
    public LocalDateTime getLatestAgentDataTime(Long clusterConfigId) {
        return agentDataCacheRepository.getLatestCollectedAt(clusterConfigId);
    }

    private int getIntValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    private double getDoubleValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try {
                return Double.parseDouble((String) val);
            } catch (Exception e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // K8s Resources transformer (collector: "k8s-resources")
    // Replaces direct Prometheus + KubeAPI calls for node/pod/workload data.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * K8sResourcesResult holds the fields that EksDashboardService needs to populate
     * ClusterOverview, NodeDetails, PodDetails, WorkloadHealth from agent data.
     */
    public static class K8sResourcesResult {
        private int totalNodes;
        private int readyNodes;
        private int spotNodes;
        private int totalPods;
        private int runningPods;
        private int pendingPods;
        private int failedPods;
        private int totalNamespaces;
        private int totalDeployments;
        private int totalServices;
        private int totalPvcs;
        private int unscheduledPods;
        private List<Map<String, Object>> nodes = new ArrayList<>();
        private List<Map<String, Object>> pods = new ArrayList<>();
        private List<Map<String, Object>> deployments = new ArrayList<>();
        private List<Map<String, Object>> statefulSets = new ArrayList<>();
        private List<Map<String, Object>> daemonSets = new ArrayList<>();
        private List<Map<String, Object>> services = new ArrayList<>();
        private List<Map<String, Object>> ingresses = new ArrayList<>();
        private List<Map<String, Object>> hpas = new ArrayList<>();
        private List<Map<String, Object>> pvcs = new ArrayList<>();
        private List<Map<String, Object>> events = new ArrayList<>();
        private List<Map<String, Object>> nodeMetrics = new ArrayList<>();
        private List<Map<String, Object>> podMetrics = new ArrayList<>();
        private List<Map<String, Object>> namespaces = new ArrayList<>();
        private String k8sVersion;

        public int getTotalNodes() { return totalNodes; }
        public void setTotalNodes(int v) { this.totalNodes = v; }
        public int getReadyNodes() { return readyNodes; }
        public void setReadyNodes(int v) { this.readyNodes = v; }
        public int getSpotNodes() { return spotNodes; }
        public void setSpotNodes(int v) { this.spotNodes = v; }
        public int getTotalPods() { return totalPods; }
        public void setTotalPods(int v) { this.totalPods = v; }
        public int getRunningPods() { return runningPods; }
        public void setRunningPods(int v) { this.runningPods = v; }
        public int getPendingPods() { return pendingPods; }
        public void setPendingPods(int v) { this.pendingPods = v; }
        public int getFailedPods() { return failedPods; }
        public void setFailedPods(int v) { this.failedPods = v; }
        public int getTotalNamespaces() { return totalNamespaces; }
        public void setTotalNamespaces(int v) { this.totalNamespaces = v; }
        public int getTotalDeployments() { return totalDeployments; }
        public void setTotalDeployments(int v) { this.totalDeployments = v; }
        public int getTotalServices() { return totalServices; }
        public void setTotalServices(int v) { this.totalServices = v; }
        public int getTotalPvcs() { return totalPvcs; }
        public void setTotalPvcs(int v) { this.totalPvcs = v; }
        public int getUnscheduledPods() { return unscheduledPods; }
        public void setUnscheduledPods(int v) { this.unscheduledPods = v; }
        public List<Map<String, Object>> getNodes() { return nodes; }
        public void setNodes(List<Map<String, Object>> v) { this.nodes = v; }
        public List<Map<String, Object>> getPods() { return pods; }
        public void setPods(List<Map<String, Object>> v) { this.pods = v; }
        public List<Map<String, Object>> getDeployments() { return deployments; }
        public void setDeployments(List<Map<String, Object>> v) { this.deployments = v; }
        public List<Map<String, Object>> getStatefulSets() { return statefulSets; }
        public void setStatefulSets(List<Map<String, Object>> v) { this.statefulSets = v; }
        public List<Map<String, Object>> getDaemonSets() { return daemonSets; }
        public void setDaemonSets(List<Map<String, Object>> v) { this.daemonSets = v; }
        public List<Map<String, Object>> getServices() { return services; }
        public void setServices(List<Map<String, Object>> v) { this.services = v; }
        public List<Map<String, Object>> getIngresses() { return ingresses; }
        public void setIngresses(List<Map<String, Object>> v) { this.ingresses = v; }
        public List<Map<String, Object>> getHpas() { return hpas; }
        public void setHpas(List<Map<String, Object>> v) { this.hpas = v; }
        public List<Map<String, Object>> getPvcs() { return pvcs; }
        public void setPvcs(List<Map<String, Object>> v) { this.pvcs = v; }
        public List<Map<String, Object>> getEvents() { return events; }
        public void setEvents(List<Map<String, Object>> v) { this.events = v; }
        public List<Map<String, Object>> getNodeMetrics() { return nodeMetrics; }
        public void setNodeMetrics(List<Map<String, Object>> v) { this.nodeMetrics = v; }
        public List<Map<String, Object>> getPodMetrics() { return podMetrics; }
        public void setPodMetrics(List<Map<String, Object>> v) { this.podMetrics = v; }
        public List<Map<String, Object>> getNamespaces() { return namespaces; }
        public void setNamespaces(List<Map<String, Object>> v) { this.namespaces = v; }
        public String getK8sVersion() { return k8sVersion; }
        public void setK8sVersion(String v) { this.k8sVersion = v; }
    }

    /**
     * Reads the "k8s-resources" cache entry and extracts the full cluster snapshot.
     * Called from EksDashboardService in agent mode to replace Prometheus/kubeconfig data.
     */
    @SuppressWarnings("unchecked")
    public K8sResourcesResult transformK8sResources(Long clusterConfigId) {
        K8sResourcesResult result = new K8sResourcesResult();

        Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(clusterConfigId, "k8s-resources");

        if (cacheOpt.isEmpty()) {
            logger.debug("No k8s-resources cache found for cluster config: {}", clusterConfigId);
            return result;
        }

        try {
            // k8s-resources is stored as a single-object JSON (SINGLE_OBJECT_COLLECTORS)
            // Stored as [{ ...K8sResources... }] after ingest processing
            String json = cacheOpt.get().getDataJson();
            Map<String, Object> data;

            // Handle both wrapped ([{...}]) and unwrapped ({...}) storage formats
            if (json.trim().startsWith("[")) {
                List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                if (list.isEmpty()) return result;
                data = list.get(0);
            } else {
                data = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            }

            // Summary block
            Map<String, Object> summary = (Map<String, Object>) data.get("summary");
            if (summary != null) {
                result.setTotalNodes(getIntValue(summary, "totalNodes"));
                result.setReadyNodes(getIntValue(summary, "readyNodes"));
                result.setSpotNodes(getIntValue(summary, "spotNodes"));
                result.setTotalPods(getIntValue(summary, "totalPods"));
                result.setRunningPods(getIntValue(summary, "runningPods"));
                result.setPendingPods(getIntValue(summary, "pendingPods"));
                result.setFailedPods(getIntValue(summary, "failedPods"));
                result.setTotalNamespaces(getIntValue(summary, "totalNamespaces"));
                result.setTotalDeployments(getIntValue(summary, "totalDeployments"));
                result.setTotalServices(getIntValue(summary, "totalServices"));
                result.setTotalPvcs(getIntValue(summary, "totalPvcs"));
                result.setUnscheduledPods(getIntValue(summary, "unscheduledPods"));
            }

            // Resource lists — pass raw maps through; EksDashboardService maps them to DTOs
            setListField(result::setNodes, data, "nodes");
            setListField(result::setPods, data, "pods");
            setListField(result::setDeployments, data, "deployments");
            setListField(result::setStatefulSets, data, "statefulSets");
            setListField(result::setDaemonSets, data, "daemonSets");
            setListField(result::setServices, data, "services");
            setListField(result::setIngresses, data, "ingresses");
            setListField(result::setHpas, data, "hpas");
            setListField(result::setPvcs, data, "pvcs");
            setListField(result::setEvents, data, "events");
            setListField(result::setNodeMetrics, data, "nodeMetrics");
            setListField(result::setPodMetrics, data, "podMetrics");
            setListField(result::setNamespaces, data, "namespaces");

            // Extract k8sVersion if present
            Object k8sVer = data.get("k8sVersion");
            if (k8sVer instanceof String) {
                result.setK8sVersion((String) k8sVer);
            }

            logger.info("k8s-resources: nodes={} pods={} deployments={} namespaces={}",
                    result.getTotalNodes(), result.getTotalPods(),
                    result.getTotalDeployments(), result.getTotalNamespaces());

        } catch (Exception e) {
            logger.error("Error transforming k8s-resources for cluster {}: {}", clusterConfigId, e.getMessage());
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prometheus Metrics transformer (collector: "prometheus-metrics")
    // Replaces direct Prometheus HTTP calls for cost, Falco, utilization data.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * PrometheusMetricsResult holds the metrics that EksDashboardService needs
     * to populate CostMetrics, SecurityMetrics, ResourceUtilization sections.
     */
    public static class PrometheusMetricsResult {
        private double clusterCpuUsagePct;
        private double clusterMemUsagePct;
        private List<Map<String, Object>> nodeUtilization = new ArrayList<>();
        private List<Map<String, Object>> namespaceUtilization = new ArrayList<>();
        private Map<String, Object> costMetrics;
        private List<Map<String, Object>> falcoEvents = new ArrayList<>();
        private List<Map<String, Object>> podRestartRates = new ArrayList<>();
        private List<Map<String, Object>> topCpuPods = new ArrayList<>();
        private List<Map<String, Object>> topMemoryPods = new ArrayList<>();
        private List<Map<String, Object>> networkMetrics = new ArrayList<>();
        private String collectedAt;

        public double getClusterCpuUsagePct() { return clusterCpuUsagePct; }
        public void setClusterCpuUsagePct(double v) { this.clusterCpuUsagePct = v; }
        public double getClusterMemUsagePct() { return clusterMemUsagePct; }
        public void setClusterMemUsagePct(double v) { this.clusterMemUsagePct = v; }
        public List<Map<String, Object>> getNodeUtilization() { return nodeUtilization; }
        public void setNodeUtilization(List<Map<String, Object>> v) { this.nodeUtilization = v; }
        public List<Map<String, Object>> getNamespaceUtilization() { return namespaceUtilization; }
        public void setNamespaceUtilization(List<Map<String, Object>> v) { this.namespaceUtilization = v; }
        public Map<String, Object> getCostMetrics() { return costMetrics; }
        public void setCostMetrics(Map<String, Object> v) { this.costMetrics = v; }
        public List<Map<String, Object>> getFalcoEvents() { return falcoEvents; }
        public void setFalcoEvents(List<Map<String, Object>> v) { this.falcoEvents = v; }
        public List<Map<String, Object>> getPodRestartRates() { return podRestartRates; }
        public void setPodRestartRates(List<Map<String, Object>> v) { this.podRestartRates = v; }
        public List<Map<String, Object>> getTopCpuPods() { return topCpuPods; }
        public void setTopCpuPods(List<Map<String, Object>> v) { this.topCpuPods = v; }
        public List<Map<String, Object>> getTopMemoryPods() { return topMemoryPods; }
        public void setTopMemoryPods(List<Map<String, Object>> v) { this.topMemoryPods = v; }
        public List<Map<String, Object>> getNetworkMetrics() { return networkMetrics; }
        public void setNetworkMetrics(List<Map<String, Object>> v) { this.networkMetrics = v; }
        public String getCollectedAt() { return collectedAt; }
        public void setCollectedAt(String v) { this.collectedAt = v; }
    }

    /**
     * Reads the "prometheus-metrics" cache entry.
     * Called from EksDashboardService in agent mode to replace direct Prometheus queries.
     */
    @SuppressWarnings("unchecked")
    public PrometheusMetricsResult transformPrometheusMetrics(Long clusterConfigId) {
        PrometheusMetricsResult result = new PrometheusMetricsResult();

        Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(clusterConfigId, "prometheus-metrics");

        if (cacheOpt.isEmpty()) {
            logger.debug("No prometheus-metrics cache found for cluster config: {}", clusterConfigId);
            return result;
        }

        try {
            String json = cacheOpt.get().getDataJson();
            Map<String, Object> data;

            if (json.trim().startsWith("[")) {
                List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                if (list.isEmpty()) return result;
                data = list.get(0);
            } else {
                data = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            }

            result.setClusterCpuUsagePct(getDoubleValue(data, "clusterCpuUsagePct"));
            result.setClusterMemUsagePct(getDoubleValue(data, "clusterMemUsagePct"));
            result.setCollectedAt((String) data.get("collectedAt"));

            setListField(result::setNodeUtilization, data, "nodeUtilization");
            setListField(result::setNamespaceUtilization, data, "namespaceUtilization");
            setListField(result::setFalcoEvents, data, "falcoEvents");
            setListField(result::setPodRestartRates, data, "podRestartRates");
            setListField(result::setTopCpuPods, data, "topCpuPods");
            setListField(result::setTopMemoryPods, data, "topMemoryPods");
            setListField(result::setNetworkMetrics, data, "networkMetrics");

            Object costRaw = data.get("costMetrics");
            if (costRaw instanceof Map) {
                result.setCostMetrics((Map<String, Object>) costRaw);
            }

            logger.info("prometheus-metrics: cpu={}% mem={}% falcoEvents={} nodeUtilization={}",
                    result.getClusterCpuUsagePct(), result.getClusterMemUsagePct(),
                    result.getFalcoEvents().size(), result.getNodeUtilization().size());

        } catch (Exception e) {
            logger.error("Error transforming prometheus-metrics for cluster {}: {}", clusterConfigId, e.getMessage());
        }

        return result;
    }

    /**
     * Reads automation results pushed by xamops-automation.
     * Returns raw JSON map (EksDashboardService / EksAutoscalingService maps it to DTOs).
     */
    public Map<String, Object> transformAutomationData(Long clusterConfigId, String dataType) {
        Optional<AgentDataCache> cacheOpt = agentDataCacheRepository
                .findByClusterConfigIdAndDataType(clusterConfigId, dataType);
        if (cacheOpt.isEmpty()) return null;
        try {
            String json = cacheOpt.get().getDataJson();
            if (json.trim().startsWith("[")) {
                List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                return list.isEmpty() ? null : list.get(0);
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.error("Error reading automation data type={} cluster={}: {}", dataType, clusterConfigId, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void setListField(java.util.function.Consumer<List<Map<String, Object>>> setter,
                               Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (val instanceof List) {
            setter.accept((List<Map<String, Object>>) val);
        }
    }
}
