package com.xammer.cloud.service;

import com.xammer.cloud.dto.k8s.KubescapeDashboard;
import com.xammer.cloud.dto.k8s.KubescapeDashboard.ConfigScanSummary;
import com.xammer.cloud.dto.k8s.KubescapeDashboard.VulnerabilitySummary;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class KubescapeService {

    private static final Logger logger = LoggerFactory.getLogger(KubescapeService.class);

    public KubescapeDashboard fetchDashboardData(KubernetesClient client) {
        try {
            logger.info("🛡️ Fetching Kubescape dashboard data...");

            CompletableFuture<List<Map<String, Object>>> configScansFuture = CompletableFuture
                    .supplyAsync(() -> fetchConfigScans(client));

            CompletableFuture<List<Map<String, Object>>> vulnsFuture = CompletableFuture
                    .supplyAsync(() -> fetchVulnerabilities(client));

            List<Map<String, Object>> configScans = configScansFuture.join();
            List<Map<String, Object>> vulns = vulnsFuture.join();

            logger.info("🔍 DEBUG: Fetched {} config scans and {} vulnerabilities",
                    configScans.size(), vulns.size());

            KubescapeDashboard dashboard = new KubescapeDashboard();

            dashboard.setConfigScans(transformConfigScans(configScans));
            dashboard.setVulnerabilities(transformVulnerabilities(vulns));
            dashboard.setOverallComplianceScore(calculateOverallScore(configScans));

            calculateVulnerabilityCounts(dashboard);
            dashboard.setLastScanTime(extractLastScanTime(configScans));

            logger.info("✅ Kubescape dashboard: {} config scans, {} vulnerabilities, Score: {}%",
                    dashboard.getConfigScans().size(),
                    dashboard.getVulnerabilities().size(),
                    dashboard.getOverallComplianceScore());

            return dashboard;

        } catch (Exception e) {
            logger.error("❌ Failed to fetch Kubescape data", e);
            return createEmptyDashboard();
        }
    }

    private List<Map<String, Object>> fetchConfigScans(KubernetesClient client) {
        List<Map<String, Object>> results = fetchCRD(client, "configurationscansummaries",
                "spdx.softwarecomposition.kubescape.io");

        if (!results.isEmpty()) {
            return results;
        }

        return fetchCRD(client, "configurationscansummaries", "kubescape.io");
    }

    private List<Map<String, Object>> fetchVulnerabilities(KubernetesClient client) {
        List<Map<String, Object>> results = fetchCRD(client, "vulnerabilitymanifestsummaries",
                "spdx.softwarecomposition.kubescape.io");

        if (!results.isEmpty()) {
            return results;
        }

        return fetchCRD(client, "vulnerabilitymanifestsummaries", "kubescape.io");
    }

    private List<Map<String, Object>> fetchCRD(KubernetesClient client, String plural, String group) {
        List<String> versions = List.of("v1beta1", "v1", "v1alpha1");

        for (String version : versions) {
            try {
                CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                        .withGroup(group)
                        .withVersion(version)
                        .withPlural(plural)
                        .withScope("Namespaced")
                        .build();

                try {
                    List<GenericKubernetesResource> resources = client.genericKubernetesResources(context)
                            .inAnyNamespace().list().getItems();
                    if (!resources.isEmpty()) {
                        logger.info("✅ Found {} {} (group: {}, version: {}, scope: Namespaced)",
                                resources.size(), plural, group, version);
                        return convertToMaps(resources);
                    }
                } catch (Exception e) {
                    logger.debug("Namespaced scope failed for {}/{}: {}", group, plural, e.getMessage());
                }

                context = new CustomResourceDefinitionContext.Builder()
                        .withGroup(group)
                        .withVersion(version)
                        .withPlural(plural)
                        .withScope("Cluster")
                        .build();

                List<GenericKubernetesResource> resources = client.genericKubernetesResources(context)
                        .list().getItems();

                if (!resources.isEmpty()) {
                    logger.info("✅ Found {} {} (group: {}, version: {}, scope: Cluster)",
                            resources.size(), plural, group, version);
                    return convertToMaps(resources);
                }

            } catch (Exception e) {
                logger.debug("Attempt for {}/{}/{} failed: {}", group, version, plural, e.getMessage());
            }
        }

        logger.warn("⚠️ Could not fetch Kubescape CRD '{}' in group '{}'", plural, group);
        return new ArrayList<>();
    }

    private List<Map<String, Object>> convertToMaps(List<GenericKubernetesResource> resources) {
        return resources.stream().map(r -> {
            Map<String, Object> map = new HashMap<>();

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
        }).collect(Collectors.toList());
    }

    private List<ConfigScanSummary> transformConfigScans(List<Map<String, Object>> data) {
        List<ConfigScanSummary> result = new ArrayList<>();
        if (data == null || data.isEmpty()) {
            logger.warn("⚠️ No config scan data to transform");
            return result;
        }

        logger.info("🔍 Transforming {} config scans", data.size());

        for (Map<String, Object> item : data) {
            try {
                ConfigScanSummary summary = new ConfigScanSummary();

                if (item.containsKey("metadata")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
                    summary.setNamespace((String) metadata.get("name"));
                }

                if (item.containsKey("spec")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> spec = (Map<String, Object>) item.get("spec");
                    if (spec.containsKey("severities")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> severities = (Map<String, Object>) spec.get("severities");
                        summary.setComplianceScore(calculateComplianceFromSeverities(severities));
                    }
                }

                summary.setFramework("NSA-CISA");
                result.add(summary);
            } catch (Exception e) {
                logger.warn("Failed to parse config scan: {}", e.getMessage());
            }
        }

        logger.info("✅ Transformed {} config scan summaries", result.size());
        return result;
    }

    private List<VulnerabilitySummary> transformVulnerabilities(List<Map<String, Object>> data) {
        List<VulnerabilitySummary> result = new ArrayList<>();
        if (data == null || data.isEmpty()) {
            logger.warn("⚠️ No vulnerability data to transform");
            return result;
        }

        logger.info("🔍 Transforming {} vulnerabilities", data.size());

        for (Map<String, Object> item : data) {
            try {
                logger.debug("🔍 Processing vulnerability item: {}", item.get("metadata"));

                VulnerabilitySummary vuln = new VulnerabilitySummary();

                if (item.containsKey("metadata")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
                    vuln.setWorkloadName((String) metadata.get("name"));
                    vuln.setNamespace((String) metadata.get("namespace"));
                }

                Map<String, Object> spec = null;
                if (item.containsKey("spec")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> specMap = (Map<String, Object>) item.get("spec");
                    spec = specMap;
                } else if (item.containsKey("status")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> statusMap = (Map<String, Object>) item.get("status");
                    spec = statusMap;
                }

                if (spec != null && spec.containsKey("severities")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> severities = (Map<String, Object>) spec.get("severities");

                    logger.debug("🔍 Severities for {}: {}", vuln.getWorkloadName(), severities);

                    vuln.setCriticalCount(getIntOrZero(severities, "critical"));
                    vuln.setHighCount(getIntOrZero(severities, "high"));
                    vuln.setMediumCount(getIntOrZero(severities, "medium"));
                    vuln.setLowCount(getIntOrZero(severities, "low"));

                    logger.debug("🔍 Parsed counts - Critical: {}, High: {}, Medium: {}, Low: {}",
                            vuln.getCriticalCount(), vuln.getHighCount(),
                            vuln.getMediumCount(), vuln.getLowCount());
                }

                String name = vuln.getWorkloadName();
                if (name != null) {
                    if (name.startsWith("deployment-"))
                        vuln.setWorkloadKind("Deployment");
                    else if (name.startsWith("daemonset-"))
                        vuln.setWorkloadKind("DaemonSet");
                    else
                        vuln.setWorkloadKind("Workload");
                }

                result.add(vuln);
            } catch (Exception e) {
                logger.warn("Failed to parse vulnerability: {}", e.getMessage(), e);
            }
        }

        logger.info("✅ Transformed {} vulnerability summaries", result.size());
        return result;
    }

    private Double calculateComplianceFromSeverities(Map<String, Object> severities) {
        int critical = getIntOrZero(severities, "critical");
        int high = getIntOrZero(severities, "high");
        int medium = getIntOrZero(severities, "medium");
        int low = getIntOrZero(severities, "low");

        int totalIssues = critical + high + medium + low;
        if (totalIssues == 0)
            return 100.0;

        int weightedScore = 100 - (critical * 10 + high * 5 + medium * 2 + low);
        return Math.max(0.0, Math.min(100.0, (double) weightedScore));
    }

    private Double calculateOverallScore(List<Map<String, Object>> configScans) {
        if (configScans == null || configScans.isEmpty())
            return 100.0;

        try {
            double totalScore = 0;
            int count = 0;

            for (Map<String, Object> scan : configScans) {
                try {
                    if (scan.containsKey("spec")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> spec = (Map<String, Object>) scan.get("spec");
                        if (spec.containsKey("severities")) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> severities = (Map<String, Object>) spec.get("severities");
                            totalScore += calculateComplianceFromSeverities(severities);
                            count++;
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            return count == 0 ? 100.0 : totalScore / count;
        } catch (Exception e) {
            return 100.0;
        }
    }

    private void calculateVulnerabilityCounts(KubescapeDashboard dashboard) {
        int critical = 0, high = 0, medium = 0, low = 0;

        if (dashboard.getVulnerabilities() != null) {
            for (VulnerabilitySummary vuln : dashboard.getVulnerabilities()) {
                critical += vuln.getCriticalCount() != null ? vuln.getCriticalCount() : 0;
                high += vuln.getHighCount() != null ? vuln.getHighCount() : 0;
                medium += vuln.getMediumCount() != null ? vuln.getMediumCount() : 0;
                low += vuln.getLowCount() != null ? vuln.getLowCount() : 0;
            }
        }

        dashboard.setTotalCritical(critical);
        dashboard.setTotalHigh(high);
        dashboard.setTotalMedium(medium);
        dashboard.setTotalLow(low);

        logger.info("📊 Total vulnerabilities - Critical: {}, High: {}, Medium: {}, Low: {}",
                critical, high, medium, low);
    }

    private String extractLastScanTime(List<Map<String, Object>> resources) {
        if (resources == null || resources.isEmpty())
            return "Never";
        return java.time.LocalDateTime.now().toString();
    }

    private int getIntOrZero(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key))
            return 0;

        Object val = map.get(key);

        // Handle nested structure: severities.critical.all
        if (val instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedMap = (Map<String, Object>) val;
            if (nestedMap.containsKey("all")) {
                val = nestedMap.get("all");
            }
        }

        if (val instanceof Integer)
            return (Integer) val;
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private KubescapeDashboard createEmptyDashboard() {
        KubescapeDashboard dashboard = new KubescapeDashboard();
        dashboard.setConfigScans(new ArrayList<>());
        dashboard.setVulnerabilities(new ArrayList<>());
        dashboard.setOverallComplianceScore(0.0);
        dashboard.setTotalCritical(0);
        dashboard.setTotalHigh(0);
        dashboard.setTotalMedium(0);
        dashboard.setTotalLow(0);
        dashboard.setLastScanTime("Error loading data");
        return dashboard;
    }
}