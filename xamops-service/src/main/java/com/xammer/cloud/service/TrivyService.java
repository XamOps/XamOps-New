package com.xammer.cloud.service;

import com.xammer.cloud.dto.k8s.TrivyDashboard;
import com.xammer.cloud.dto.k8s.TrivyDashboard.*;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TrivyService {

    private static final Logger logger = LoggerFactory.getLogger(TrivyService.class);
    private static final String GROUP = "aquasecurity.github.io";

    public TrivyDashboard fetchDashboardData(KubernetesClient client) {
        TrivyDashboard dashboard = new TrivyDashboard();
        try {
            logger.info("🛡️ Fetching Trivy security data...");

            // Connectivity Check
            try {
                int nodeCount = client.nodes().list().getItems().size();
                logger.info("📡 Connectivity verified. Cluster has {} nodes.", nodeCount);
            } catch (Exception e) {
                logger.error("❌ Connectivity check failed!", e);
                return dashboard;
            }

            // 1. Fetch Vulnerability Reports
            // Added both plural and singular names for robustness
            List<Map<String, Object>> vulnRaw = fetchCRD(client, "vulnerabilityreports", GROUP);
            logger.info("📊 Found {} VulnerabilityReports", vulnRaw.size());
            dashboard.setWorkloads(transformVulnerabilities(vulnRaw));

            // 2. Fetch Config Audits
            List<Map<String, Object>> configRaw = fetchCRD(client, "configauditreports", GROUP);
            logger.info("📊 Found {} ConfigAuditReports", configRaw.size());
            dashboard.setConfigAudits(transformConfigAudits(configRaw));

            // 3. Fetch Exposed Secrets
            List<Map<String, Object>> secretRaw = fetchCRD(client, "exposedsecretreports", GROUP);
            logger.info("📊 Found {} ExposedSecretReports", secretRaw.size());
            dashboard.setExposedSecrets(transformExposedSecrets(secretRaw));

            // 4. Fetch RBAC Assessments
            List<Map<String, Object>> rbacRaw = fetchCRD(client, "rbacassessmentreports", GROUP);
            logger.info("📊 Found {} RbacAssessmentReports", rbacRaw.size());
            dashboard.setRbacAssessments(transformRbacAssessments(rbacRaw));

            // 5. Calculate Summary
            calculateSummary(dashboard);

            logger.info("✅ Trivy data fetch complete. Workloads: {}, Critical: {}, High: {}",
                    dashboard.getWorkloads().size(),
                    dashboard.getSummary().getTotalCritical(),
                    dashboard.getSummary().getTotalHigh());

        } catch (Exception e) {
            logger.error("❌ Error fetching Trivy data", e);
        }
        return dashboard;
    }

    private List<Map<String, Object>> fetchCRD(KubernetesClient client, String plural, String group) {
        // Prioritize v1alpha1 as per user feedback
        List<String> versions = List.of("v1alpha1", "v1", "v1beta1");

        for (String version : versions) {
            try {
                logger.debug("🔎 Attempting to fetch {}/{}/{}...", group, version, plural);

                CustomResourceDefinitionContext context = new CustomResourceDefinitionContext.Builder()
                        .withGroup(group)
                        .withVersion(version)
                        .withPlural(plural)
                        .withScope("Namespaced")
                        .build();

                List<GenericKubernetesResource> resources = client.genericKubernetesResources(context)
                        .inAnyNamespace()
                        .list()
                        .getItems();

                if (!resources.isEmpty()) {
                    logger.info("✅ Successfully fetched {} {} resources using version {}", resources.size(), plural,
                            version);
                    return convertToMaps(resources);
                } else {
                    logger.debug("⚠️ No items found for {} version {}", plural, version);
                }
            } catch (Exception e) {
                logger.warn("⚠️ Failed to fetch {} version {}: {}", plural, version, e.getMessage());
            }
        }
        logger.warn("❌ Failed to fetch {} in any version. Returning empty list.", plural);
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
                map.putAll(r.getAdditionalProperties());
            }
            return map;
        }).collect(Collectors.toList());
    }

    private List<WorkloadVulnerability> transformVulnerabilities(List<Map<String, Object>> data) {
        List<WorkloadVulnerability> results = new ArrayList<>();
        for (Map<String, Object> item : data) {
            try {
                WorkloadVulnerability vuln = new WorkloadVulnerability();
                Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
                Map<String, String> labels = (Map<String, String>) metadata.get("labels");

                // Enhanced Workload Name Logic
                String workloadName = labels != null ? labels.get("trivy-operator.resource.name") : null;
                if (workloadName == null)
                    workloadName = (String) metadata.get("name");
                vuln.setName(workloadName);

                vuln.setNamespace((String) metadata.get("namespace"));

                String workloadKind = labels != null ? labels.get("trivy-operator.resource.kind") : "Workload";
                vuln.setKind(workloadKind);

                vuln.setLastScanTime((String) metadata.get("creationTimestamp"));

                Map<String, Object> report = (Map<String, Object>) item.get("report");
                if (report != null) {
                    Map<String, Object> artifact = (Map<String, Object>) report.get("artifact");
                    if (artifact != null) {
                        vuln.setImage((String) artifact.get("repository"));
                        vuln.setTag((String) artifact.get("tag"));
                    }

                    Map<String, Object> summary = (Map<String, Object>) report.get("summary");
                    if (summary != null) {
                        vuln.setCritical(getIntValue(summary, "criticalCount"));
                        vuln.setHigh(getIntValue(summary, "highCount"));
                        vuln.setMedium(getIntValue(summary, "mediumCount"));
                        vuln.setLow(getIntValue(summary, "lowCount"));
                    }

                    // Extract detailed vulnerability information (P1 enhancement)
                    List<VulnerabilityDetail> vulnerabilityDetails = extractVulnerabilityDetails(report);
                    vuln.setVulnerabilities(vulnerabilityDetails);
                }
                results.add(vuln);
            } catch (Exception e) {
                logger.warn("Skipping invalid Trivy vuln report: {}", e.getMessage());
            }
        }
        return results;
    }

    /**
     * Extract detailed vulnerability information from Trivy report
     * This includes CVE IDs, affected packages, severity, and fix versions
     */
    @SuppressWarnings("unchecked")
    private List<VulnerabilityDetail> extractVulnerabilityDetails(Map<String, Object> report) {
        List<VulnerabilityDetail> details = new ArrayList<>();
        try {
            List<Map<String, Object>> allVulnerabilities = new ArrayList<>();

            // Try direct vulnerabilities array first (Trivy Operator v0.16+)
            List<Map<String, Object>> directVulns = (List<Map<String, Object>>) report.get("vulnerabilities");
            if (directVulns != null && !directVulns.isEmpty()) {
                allVulnerabilities.addAll(directVulns);
            }

            // Also try results array (older Trivy format: report.results[].vulnerabilities)
            List<Map<String, Object>> results = (List<Map<String, Object>>) report.get("results");
            if (results != null) {
                for (Map<String, Object> result : results) {
                    String target = getStringValue(result, "Target");
                    List<Map<String, Object>> resultVulns = (List<Map<String, Object>>) result.get("Vulnerabilities");
                    if (resultVulns != null) {
                        for (Map<String, Object> v : resultVulns) {
                            // Add target info to each vulnerability
                            v.put("target", target);
                            allVulnerabilities.add(v);
                        }
                    }
                }
            }

            if (allVulnerabilities.isEmpty()) {
                return details;
            }

            // Limit to top 50 vulnerabilities per workload to avoid response bloat
            int limit = Math.min(allVulnerabilities.size(), 50);
            for (int i = 0; i < limit; i++) {
                Map<String, Object> v = allVulnerabilities.get(i);
                VulnerabilityDetail detail = new VulnerabilityDetail();

                // Try different field name formats (camelCase and PascalCase)
                detail.setVulnerabilityId(coalesce(getStringValue(v, "vulnerabilityID"), getStringValue(v, "VulnerabilityID")));
                detail.setSeverity(coalesce(getStringValue(v, "severity"), getStringValue(v, "Severity")));
                detail.setPackageName(coalesce(getStringValue(v, "pkgName"), getStringValue(v, "PkgName")));
                detail.setInstalledVersion(coalesce(getStringValue(v, "installedVersion"), getStringValue(v, "InstalledVersion")));
                detail.setFixedVersion(coalesce(getStringValue(v, "fixedVersion"), getStringValue(v, "FixedVersion")));
                detail.setTitle(coalesce(getStringValue(v, "title"), getStringValue(v, "Title")));
                detail.setDescription(truncateDescription(coalesce(getStringValue(v, "description"), getStringValue(v, "Description")), 500));
                detail.setPrimaryLink(coalesce(getStringValue(v, "primaryURL"), getStringValue(v, "PrimaryURL")));
                detail.setTarget(coalesce(getStringValue(v, "target"), getStringValue(v, "Target")));

                // Extract CVSS score if available
                Double score = extractCvssScore(v);
                detail.setScore(score);

                // Only add if we have a valid vulnerability ID
                if (detail.getVulnerabilityId() != null && !detail.getVulnerabilityId().isEmpty()) {
                    details.add(detail);
                }
            }

            // Sort by severity (CRITICAL > HIGH > MEDIUM > LOW)
            details.sort((a, b) -> {
                int severityA = getSeverityWeight(a.getSeverity());
                int severityB = getSeverityWeight(b.getSeverity());
                return severityB - severityA;
            });

        } catch (Exception e) {
            logger.warn("Error extracting vulnerability details: {}", e.getMessage());
        }
        return details;
    }

    private String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private String truncateDescription(String description, int maxLength) {
        if (description == null) return null;
        if (description.length() <= maxLength) return description;
        return description.substring(0, maxLength) + "...";
    }

    @SuppressWarnings("unchecked")
    private Double extractCvssScore(Map<String, Object> vulnerability) {
        try {
            // Try cvss.nvd.V3Score first
            Map<String, Object> cvss = (Map<String, Object>) vulnerability.get("cvss");
            if (cvss != null) {
                Map<String, Object> nvd = (Map<String, Object>) cvss.get("nvd");
                if (nvd != null) {
                    Object v3Score = nvd.get("V3Score");
                    if (v3Score instanceof Number) {
                        return ((Number) v3Score).doubleValue();
                    }
                }
            }
            // Fallback to score field directly
            Object score = vulnerability.get("score");
            if (score instanceof Number) {
                return ((Number) score).doubleValue();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int getSeverityWeight(String severity) {
        if (severity == null) return 0;
        switch (severity.toUpperCase()) {
            case "CRITICAL": return 4;
            case "HIGH": return 3;
            case "MEDIUM": return 2;
            case "LOW": return 1;
            default: return 0;
        }
    }

    private List<ConfigAudit> transformConfigAudits(List<Map<String, Object>> data) {
        List<ConfigAudit> results = new ArrayList<>();
        for (Map<String, Object> item : data) {
            try {
                ConfigAudit audit = new ConfigAudit();
                Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
                Map<String, String> labels = (Map<String, String>) metadata.get("labels");

                String workloadName = labels != null ? labels.get("trivy-operator.resource.name")
                        : (String) metadata.get("name");
                audit.setName(workloadName);

                audit.setNamespace((String) metadata.get("namespace"));
                audit.setKind(
                        labels != null ? labels.getOrDefault("trivy-operator.resource.kind", "Workload") : "Workload");

                Map<String, Object> report = (Map<String, Object>) item.get("report");
                if (report != null) {
                    Map<String, Object> summary = (Map<String, Object>) report.get("summary");
                    if (summary != null) {
                        audit.setSuccessCount(getIntValue(summary, "passCount"));
                        audit.setDangerCount(getIntValue(summary, "failCount"));
                        audit.setWarningCount(getIntValue(summary, "warningCount"));
                    }
                }
                results.add(audit);
            } catch (Exception ignored) {
            }
        }
        return results;
    }

    private List<ExposedSecret> transformExposedSecrets(List<Map<String, Object>> data) {
        List<ExposedSecret> results = new ArrayList<>();
        for (Map<String, Object> item : data) {
            try {
                ExposedSecret secret = new ExposedSecret();
                Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
                secret.setName((String) metadata.get("name"));
                secret.setNamespace((String) metadata.get("namespace"));

                Map<String, Object> report = (Map<String, Object>) item.get("report");
                if (report != null) {
                    Map<String, Object> summary = (Map<String, Object>) report.get("summary");
                    if (summary != null) {
                        secret.setSecretCount(
                                getIntValue(summary, "criticalCount") + getIntValue(summary, "highCount"));
                    }
                }
                results.add(secret);
            } catch (Exception ignored) {
            }
        }
        return results;
    }

    private List<RbacAssessment> transformRbacAssessments(List<Map<String, Object>> data) {
        List<RbacAssessment> results = new ArrayList<>();
        for (Map<String, Object> item : data) {
            try {
                RbacAssessment rbac = new RbacAssessment();
                Map<String, Object> metadata = (Map<String, Object>) item.get("metadata");
                rbac.setName((String) metadata.get("name"));

                Map<String, Object> report = (Map<String, Object>) item.get("report");
                if (report != null) {
                    Map<String, Object> summary = (Map<String, Object>) report.get("summary");
                    if (summary != null) {
                        rbac.setDangerCount(getIntValue(summary, "failCount"));
                        rbac.setWarningCount(getIntValue(summary, "warningCount"));
                    }
                }
                results.add(rbac);
            } catch (Exception ignored) {
            }
        }
        return results;
    }

    private void calculateSummary(TrivyDashboard dashboard) {
        Summary s = dashboard.getSummary();
        Set<String> images = new HashSet<>();
        Set<String> namespaces = new HashSet<>();

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
        }

        s.setUniqueImagesScanned(images.size());
        s.setNamespacesCovered(namespaces.size());

        int secrets = 0;
        for (ExposedSecret es : dashboard.getExposedSecrets()) {
            secrets += es.getSecretCount();
        }
        s.setTotalExposedSecrets(secrets);

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
