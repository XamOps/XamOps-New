package com.xammer.cloud.dto.k8s;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TrivyDashboard {
    private Summary summary = new Summary();
    private List<WorkloadVulnerability> workloads = new ArrayList<>();
    private List<ConfigAudit> configAudits = new ArrayList<>();
    private List<ExposedSecret> exposedSecrets = new ArrayList<>();
    private List<RbacAssessment> rbacAssessments = new ArrayList<>();

    public static class Summary {
        private int totalCritical;
        private int totalHigh;
        private int totalMedium;
        private int totalLow;
        private int totalVulnerabilityReports;
        private int uniqueImagesScanned;
        private int namespacesCovered;
        private int totalExposedSecrets;
        private int totalConfigAuditFailures;
        private LocalDateTime lastScanTime;

        // Getters and Setters
        public int getTotalCritical() {
            return totalCritical;
        }

        public void setTotalCritical(int totalCritical) {
            this.totalCritical = totalCritical;
        }

        public int getTotalHigh() {
            return totalHigh;
        }

        public void setTotalHigh(int totalHigh) {
            this.totalHigh = totalHigh;
        }

        public int getTotalMedium() {
            return totalMedium;
        }

        public void setTotalMedium(int totalMedium) {
            this.totalMedium = totalMedium;
        }

        public int getTotalLow() {
            return totalLow;
        }

        public void setTotalLow(int totalLow) {
            this.totalLow = totalLow;
        }

        public int getTotalVulnerabilityReports() {
            return totalVulnerabilityReports;
        }

        public void setTotalVulnerabilityReports(int totalVulnerabilityReports) {
            this.totalVulnerabilityReports = totalVulnerabilityReports;
        }

        public int getUniqueImagesScanned() {
            return uniqueImagesScanned;
        }

        public void setUniqueImagesScanned(int uniqueImagesScanned) {
            this.uniqueImagesScanned = uniqueImagesScanned;
        }

        public int getNamespacesCovered() {
            return namespacesCovered;
        }

        public void setNamespacesCovered(int namespacesCovered) {
            this.namespacesCovered = namespacesCovered;
        }

        public int getTotalExposedSecrets() {
            return totalExposedSecrets;
        }

        public void setTotalExposedSecrets(int totalExposedSecrets) {
            this.totalExposedSecrets = totalExposedSecrets;
        }

        public int getTotalConfigAuditFailures() {
            return totalConfigAuditFailures;
        }

        public void setTotalConfigAuditFailures(int totalConfigAuditFailures) {
            this.totalConfigAuditFailures = totalConfigAuditFailures;
        }

        public LocalDateTime getLastScanTime() {
            return lastScanTime;
        }

        public void setLastScanTime(LocalDateTime lastScanTime) {
            this.lastScanTime = lastScanTime;
        }
    }

    public static class WorkloadVulnerability {
        private String name;
        private String namespace;
        private String kind;
        private String image;
        private String tag;
        private int critical;
        private int high;
        private int medium;
        private int low;
        private String lastScanTime;
        private List<VulnerabilityDetail> vulnerabilities = new ArrayList<>();

        // Getters and Setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }

        public int getCritical() {
            return critical;
        }

        public void setCritical(int critical) {
            this.critical = critical;
        }

        public int getHigh() {
            return high;
        }

        public void setHigh(int high) {
            this.high = high;
        }

        public int getMedium() {
            return medium;
        }

        public void setMedium(int medium) {
            this.medium = medium;
        }

        public int getLow() {
            return low;
        }

        public void setLow(int low) {
            this.low = low;
        }

        public String getLastScanTime() {
            return lastScanTime;
        }

        public void setLastScanTime(String lastScanTime) {
            this.lastScanTime = lastScanTime;
        }

        public List<VulnerabilityDetail> getVulnerabilities() {
            return vulnerabilities;
        }

        public void setVulnerabilities(List<VulnerabilityDetail> vulnerabilities) {
            this.vulnerabilities = vulnerabilities;
        }
    }

    // Detailed vulnerability information including CVE, package, etc.
    public static class VulnerabilityDetail {
        private String vulnerabilityId; // CVE ID (e.g., CVE-2023-12345)
        private String severity; // CRITICAL, HIGH, MEDIUM, LOW
        private String packageName; // Affected package name
        private String installedVersion; // Currently installed version
        private String fixedVersion; // Version where vulnerability is fixed
        private String title; // Brief title/description
        private String description; // Full description
        private Double score; // CVSS score
        private String target; // Target file/component
        private String primaryLink; // Link to CVE details

        public String getVulnerabilityId() {
            return vulnerabilityId;
        }

        public void setVulnerabilityId(String vulnerabilityId) {
            this.vulnerabilityId = vulnerabilityId;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public String getInstalledVersion() {
            return installedVersion;
        }

        public void setInstalledVersion(String installedVersion) {
            this.installedVersion = installedVersion;
        }

        public String getFixedVersion() {
            return fixedVersion;
        }

        public void setFixedVersion(String fixedVersion) {
            this.fixedVersion = fixedVersion;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Double getScore() {
            return score;
        }

        public void setScore(Double score) {
            this.score = score;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getPrimaryLink() {
            return primaryLink;
        }

        public void setPrimaryLink(String primaryLink) {
            this.primaryLink = primaryLink;
        }
    }

    public static class ConfigAudit {
        private String name;
        private String namespace;
        private String kind;
        private int successCount;
        private int dangerCount;
        private int warningCount;

        // Getters and Setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public void setSuccessCount(int successCount) {
            this.successCount = successCount;
        }

        public int getDangerCount() {
            return dangerCount;
        }

        public void setDangerCount(int dangerCount) {
            this.dangerCount = dangerCount;
        }

        public int getWarningCount() {
            return warningCount;
        }

        public void setWarningCount(int warningCount) {
            this.warningCount = warningCount;
        }
    }

    public static class ExposedSecret {
        private String name;
        private String namespace;
        private int secretCount;

        // Getters and Setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public int getSecretCount() {
            return secretCount;
        }

        public void setSecretCount(int secretCount) {
            this.secretCount = secretCount;
        }
    }

    public static class RbacAssessment {
        private String name;
        private int dangerCount;
        private int warningCount;

        // Getters and Setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getDangerCount() {
            return dangerCount;
        }

        public void setDangerCount(int dangerCount) {
            this.dangerCount = dangerCount;
        }

        public int getWarningCount() {
            return warningCount;
        }

        public void setWarningCount(int warningCount) {
            this.warningCount = warningCount;
        }
    }

    // Main Getters and Setters
    public Summary getSummary() {
        return summary;
    }

    public void setSummary(Summary summary) {
        this.summary = summary;
    }

    public List<WorkloadVulnerability> getWorkloads() {
        return workloads;
    }

    public void setWorkloads(List<WorkloadVulnerability> workloads) {
        this.workloads = workloads;
    }

    public List<ConfigAudit> getConfigAudits() {
        return configAudits;
    }

    public void setConfigAudits(List<ConfigAudit> configAudits) {
        this.configAudits = configAudits;
    }

    public List<ExposedSecret> getExposedSecrets() {
        return exposedSecrets;
    }

    public void setExposedSecrets(List<ExposedSecret> exposedSecrets) {
        this.exposedSecrets = exposedSecrets;
    }

    public List<RbacAssessment> getRbacAssessments() {
        return rbacAssessments;
    }

    public void setRbacAssessments(List<RbacAssessment> rbacAssessments) {
        this.rbacAssessments = rbacAssessments;
    }
}
