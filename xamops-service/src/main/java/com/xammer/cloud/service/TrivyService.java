package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.k8s.TrivyDashboard;
import com.xammer.cloud.dto.k8s.TrivyDashboard.*;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;

@Service
public class TrivyService {

    private static final Logger logger = LoggerFactory.getLogger(TrivyService.class);
    private static final String GROUP = "aquasecurity.github.io";
    private static final String VERSION = "v1alpha1";

    // Contexts for Trivy CRDs
    private static final CustomResourceDefinitionContext VULN_REPORT_CTX = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP)
            .withVersion(VERSION)
            .withScope("Namespaced")
            .withPlural("vulnerabilityreports")
            .build();

    private static final CustomResourceDefinitionContext CLUSTER_VULN_REPORT_CTX = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP)
            .withVersion(VERSION)
            .withScope("Cluster")
            .withPlural("clustervulnerabilityreports")
            .build();

    private static final CustomResourceDefinitionContext CONFIG_AUDIT_CTX = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP)
            .withVersion(VERSION)
            .withScope("Namespaced")
            .withPlural("configauditreports")
            .build();

    private static final CustomResourceDefinitionContext CLUSTER_CONFIG_AUDIT_CTX = new CustomResourceDefinitionContext.Builder()
            .withGroup(GROUP)
            .withVersion(VERSION)
            .withScope("Cluster")
            .withPlural("clusterconfigauditreports")
            .build();

    public TrivyDashboard fetchDashboardData(KubernetesClient client) {
        TrivyDashboard dashboard = new TrivyDashboard();

        try {
            // 1. Fetch Vulnerability Reports (Namespaced)
            GenericKubernetesResourceList vulnReports = client.genericKubernetesResources(VULN_REPORT_CTX).list();
            dashboard.getWorkloads().addAll(transformVulnerabilities(vulnReports.getItems()));

            // 2. Fetch Cluster Vulnerability Reports
            try {
                GenericKubernetesResourceList clusterVulnReports = client
                        .genericKubernetesResources(CLUSTER_VULN_REPORT_CTX).list();
                dashboard.getWorkloads().addAll(transformVulnerabilities(clusterVulnReports.getItems()));
            } catch (Exception e) {
                logger.debug("ClusterVulnerabilityReports not found or accessible: {}", e.getMessage());
            }

            // 3. Fetch Config Audit Reports
            try {
                GenericKubernetesResourceList configAudits = client.genericKubernetesResources(CONFIG_AUDIT_CTX).list();
                dashboard.getConfigAudits().addAll(transformConfigAudits(configAudits.getItems()));
            } catch (Exception e) {
                logger.debug("ConfigAuditReports not found or accessible: {}", e.getMessage());
            }

            // 4. Fetch Cluster Config Audit Reports
            try {
                GenericKubernetesResourceList clusterConfigAudits = client
                        .genericKubernetesResources(CLUSTER_CONFIG_AUDIT_CTX).list();
                dashboard.getConfigAudits().addAll(transformConfigAudits(clusterConfigAudits.getItems()));
            } catch (Exception e) {
                logger.debug("ClusterConfigAuditReports not found or accessible: {}", e.getMessage());
            }

            // 5. Calculate Summary
            calculateSummary(dashboard);

        } catch (Exception e) {
            logger.error("Error fetching Trivy dashboard data", e);
        }

        return dashboard;
    }

    @SuppressWarnings("unchecked")
    private List<WorkloadVulnerability> transformVulnerabilities(List<GenericKubernetesResource> resources) {
        List<WorkloadVulnerability> list = new ArrayList<>();

        for (GenericKubernetesResource resource : resources) {
            try {
                WorkloadVulnerability w = new WorkloadVulnerability();
                Map<String, Object> metadata = resource.getMetadata().getAdditionalProperties();
                Map<String, Object> labels = (Map<String, Object>) metadata.get("labels");

                w.setName(resource.getMetadata().getName());
                w.setNamespace(resource.getMetadata().getNamespace());

                if (labels != null) {
                    w.setKind((String) labels.get("trivy-operator.resource.kind"));
                    w.setImage((String) labels.get("trivy-operator.container.name")); // Often the container name is
                                                                                      // more useful here
                }

                Map<String, Object> report = (Map<String, Object>) resource.getAdditionalProperties().get("report");
                if (report != null) {
                    Map<String, Object> summary = (Map<String, Object>) report.get("summary");
                    if (summary != null) {
                        w.setCritical(getIntValue(summary, "criticalCount"));
                        w.setHigh(getIntValue(summary, "highCount"));
                        w.setMedium(getIntValue(summary, "mediumCount"));
                        w.setLow(getIntValue(summary, "lowCount"));
                    }

                    // Extract last scan time from metadata or report
                    String creationTimestamp = (String) metadata.get("creationTimestamp");
                    w.setLastScanTime(creationTimestamp);

                    // Detailed List
                    List<Map<String, Object>> vulns = (List<Map<String, Object>>) report.get("vulnerabilities");
                    if (vulns != null) {
                        List<VulnerabilityDetail> details = new ArrayList<>();
                        for (Map<String, Object> v : vulns) {
                            VulnerabilityDetail d = new VulnerabilityDetail();
                            d.setVulnerabilityId((String) v.get("vulnerabilityID"));
                            d.setSeverity((String) v.get("severity"));
                            d.setPackageName((String) v.get("pkgName"));
                            d.setInstalledVersion((String) v.get("installedVersion"));
                            d.setFixedVersion((String) v.get("fixedVersion"));
                            d.setTitle((String) v.get("title"));
                            d.setDescription((String) v.get("description"));
                            d.setPrimaryLink((String) v.get("primaryLink"));

                            // Try to get score
                            Object scoreObj = v.get("score");
                            if (scoreObj instanceof Number) {
                                d.setScore(((Number) scoreObj).doubleValue());
                            } else if (scoreObj instanceof String) {
                                try {
                                    d.setScore(Double.parseDouble((String) scoreObj));
                                } catch (Exception ignored) {
                                }
                            }

                            details.add(d);
                        }
                        w.setVulnerabilities(details);
                    }
                }

                list.add(w);
            } catch (Exception e) {
                logger.warn("Error processing vulnerability report: {}", e.getMessage());
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private List<ConfigAudit> transformConfigAudits(List<GenericKubernetesResource> resources) {
        List<ConfigAudit> list = new ArrayList<>();
        for (GenericKubernetesResource resource : resources) {
            try {
                ConfigAudit c = new ConfigAudit();
                Map<String, Object> metadata = resource.getMetadata().getAdditionalProperties();
                Map<String, Object> labels = (Map<String, Object>) metadata.get("labels");

                c.setName(resource.getMetadata().getName());
                c.setNamespace(resource.getMetadata().getNamespace());

                if (labels != null) {
                    c.setKind((String) labels.get("trivy-operator.resource.kind"));
                }

                Map<String, Object> report = (Map<String, Object>) resource.getAdditionalProperties().get("report");
                if (report != null) {
                    Map<String, Object> summary = (Map<String, Object>) report.get("summary");
                    if (summary != null) {
                        c.setDangerCount(getIntValue(summary, "dangerCount")); // mapping "critical" or "high" to danger
                        c.setWarningCount(getIntValue(summary, "warningCount")); // mapping "medium" or "low" to warning
                        c.setSuccessCount(getIntValue(summary, "passCount"));
                    }
                }
                list.add(c);
            } catch (Exception e) {
                logger.warn("Error processing config audit: {}", e.getMessage());
            }
        }
        return list;
    }

    private void calculateSummary(TrivyDashboard dashboard) {
        Summary s = dashboard.getSummary();
        Set<String> images = new HashSet<>();
        Set<String> namespaces = new HashSet<>();
        ZonedDateTime latestScan = null;

        s.setTotalVulnerabilityReports(dashboard.getWorkloads().size());

        for (WorkloadVulnerability v : dashboard.getWorkloads()) {
            s.setTotalCritical(s.getTotalCritical() + v.getCritical());
            s.setTotalHigh(s.getTotalHigh() + v.getHigh());
            s.setTotalMedium(s.getTotalMedium() + v.getMedium());
            s.setTotalLow(s.getTotalLow() + v.getLow());
            // Concatenate image + tag for uniqueness
            if (v.getImage() != null) {
                images.add(v.getImage() + (v.getTag() != null ? ":" + v.getTag() : ""));
            }
            if (v.getNamespace() != null)
                namespaces.add(v.getNamespace());

            // Track latest scan time
            if (v.getLastScanTime() != null) {
                try {
                    ZonedDateTime scanTime = ZonedDateTime.parse(v.getLastScanTime());
                    if (latestScan == null || scanTime.isAfter(latestScan)) {
                        latestScan = scanTime;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        s.setUniqueImagesScanned(images.size());
        s.setNamespacesCovered(namespaces.size());

        if (latestScan != null) {
            s.setLastScanTime(latestScan.toLocalDateTime());
        }

        int failures = 0;
        for (ConfigAudit ca : dashboard.getConfigAudits()) {
            failures += ca.getDangerCount();
        }
        s.setTotalConfigAuditFailures(failures);
    }

    private int getIntValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number)
            return ((Number) val).intValue();
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }
}
