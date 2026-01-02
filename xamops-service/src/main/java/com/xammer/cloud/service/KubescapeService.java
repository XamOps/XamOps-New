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

    public KubescapeDashboard fetchDashboardData(KubernetesClient client, String clusterName, String accountId) {
        try {
            logger.info("🛡️ Fetching Kubescape data for cluster: {}", clusterName);

            CompletableFuture<List<Map<String, Object>>> configScansFuture = CompletableFuture
                    .supplyAsync(() -> fetchConfigScans(client));

            CompletableFuture<List<Map<String, Object>>> vulnsFuture = CompletableFuture
                    .supplyAsync(() -> fetchVulnerabilities(client));

            List<Map<String, Object>> configScans = configScansFuture.join();
            List<Map<String, Object>> vulns = vulnsFuture.join();

            KubescapeDashboard dashboard = new KubescapeDashboard();
            dashboard.setConfigScans(transformConfigScans(configScans));
            dashboard.setVulnerabilities(transformVulnerabilities(vulns));
            dashboard.setOverallComplianceScore(calculateOverallScore(configScans));

            calculateVulnerabilityCounts(dashboard);
            dashboard.setLastScanTime(extractLastScanTime(configScans));

            return dashboard;

        } catch (Exception e) {
            logger.error("❌ Failed to fetch Kubescape data", e);
            return createEmptyDashboard();
        }
    }

    private List<Map<String, Object>> fetchConfigScans(KubernetesClient client) {
        List<Map<String, Object>> results = fetchCRD(client, "configurationscansummaries",
                "spdx.softwarecomposition.kubescape.io");
        return !results.isEmpty() ? results : fetchCRD(client, "configurationscansummaries", "kubescape.io");
    }

    private List<Map<String, Object>> fetchVulnerabilities(KubernetesClient client) {
        List<Map<String, Object>> results = fetchCRD(client, "vulnerabilitymanifestsummaries",
                "spdx.softwarecomposition.kubescape.io");
        return !results.isEmpty() ? results : fetchCRD(client, "vulnerabilitymanifestsummaries", "kubescape.io");
    }

    private List<Map<String, Object>> fetchCRD(KubernetesClient client, String plural, String group) {
        List<String> versions = List.of("v1beta1", "v1", "v1alpha1");
        for (String version : versions) {
            try {
                CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                        .withGroup(group).withVersion(version).withPlural(plural).withScope("Cluster").build();
                List<GenericKubernetesResource> resources = client.genericKubernetesResources(context).list()
                        .getItems();
                if (!resources.isEmpty())
                    return convertToMaps(resources);
            } catch (Exception ignored) {
            }
        }
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
                metadata.put("labels", r.getMetadata().getLabels());
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
        for (Map<String, Object> item : data) {
            try {
                ConfigScanSummary summary = new ConfigScanSummary();
                Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
                summary.setNamespace((String) metadata.get("name"));
                summary.setFramework("NSA-CISA");

                Map<String, Object> severities = extractSeverities(item);
                if (severities != null) {
                    summary.setComplianceScore(calculateScore(severities));
                }
                result.add(summary);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private List<VulnerabilitySummary> transformVulnerabilities(List<Map<String, Object>> data) {
        List<VulnerabilitySummary> result = new ArrayList<>();
        for (Map<String, Object> item : data) {
            try {
                VulnerabilitySummary vuln = new VulnerabilitySummary();
                Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
                vuln.setWorkloadName((String) metadata.get("name"));
                vuln.setNamespace((String) metadata.get("namespace"));
                vuln.setWorkloadKind("Workload");

                Map<String, Object> severities = extractSeverities(item);
                if (severities != null) {
                    vuln.setCriticalCount(getIntValue(severities, "critical"));
                    vuln.setHighCount(getIntValue(severities, "high"));
                    vuln.setMediumCount(getIntValue(severities, "medium"));
                    vuln.setLowCount(getIntValue(severities, "low"));
                }
                result.add(vuln);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private Map<String, Object> extractSeverities(Map<String, Object> item) {
        Map<String, Object> spec = (Map<String, Object>) item.get("spec");
        if (spec != null && spec.get("severities") != null)
            return (Map<String, Object>) spec.get("severities");
        Map<String, Object> status = (Map<String, Object>) item.get("status");
        if (status != null && status.get("severities") != null)
            return (Map<String, Object>) status.get("severities");
        return null;
    }

    private int getIntValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Map)
            val = ((Map) val).get("all");
        if (val instanceof Number)
            return ((Number) val).intValue();
        return 0;
    }

    private Double calculateScore(Map<String, Object> severities) {
        int c = getIntValue(severities, "critical"), h = getIntValue(severities, "high"),
                m = getIntValue(severities, "medium"), l = getIntValue(severities, "low");
        int total = c + h + m + l;
        return total == 0 ? 100.0 : Math.max(0.0, 100.0 - (c * 10 + h * 5 + m * 2 + l));
    }

    private Double calculateOverallScore(List<Map<String, Object>> scans) {
        if (scans.isEmpty())
            return 100.0;
        double sum = 0;
        for (Map<String, Object> s : scans) {
            Map<String, Object> sev = extractSeverities(s);
            if (sev != null)
                sum += calculateScore(sev);
        }
        return sum / scans.size();
    }

    private void calculateVulnerabilityCounts(KubescapeDashboard d) {
        int c = 0, h = 0, m = 0, l = 0;
        for (VulnerabilitySummary v : d.getVulnerabilities()) {
            c += v.getCriticalCount();
            h += v.getHighCount();
            m += v.getMediumCount();
            l += v.getLowCount();
        }
        d.setTotalCritical(c);
        d.setTotalHigh(h);
        d.setTotalMedium(m);
        d.setTotalLow(l);
    }

    private String extractLastScanTime(List<Map<String, Object>> data) {
        return data.isEmpty() ? "Never" : (String) ((Map) data.get(0).get("metadata")).get("creationTimestamp");
    }

    private KubescapeDashboard createEmptyDashboard() {
        KubescapeDashboard d = new KubescapeDashboard();
        d.setConfigScans(new ArrayList<>());
        d.setVulnerabilities(new ArrayList<>());
        d.setOverallComplianceScore(0.0);
        d.setLastScanTime("Error");
        return d;
    }
}