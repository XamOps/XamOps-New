package com.xammer.cloud.dto.k8s; // ⚠️ MUST have this package declaration

import java.time.LocalDateTime;
import java.util.List;

public class KubescapeDashboard {
    private Double overallComplianceScore;
    private List<ConfigScanSummary> configScans;
    private List<VulnerabilitySummary> vulnerabilities;
    private Integer totalCritical;
    private Integer totalHigh;
    private Integer totalMedium;
    private Integer totalLow;
    private String lastScanTime;

    // Constructors
    public KubescapeDashboard() {
    }

    // Getters and Setters
    public Double getOverallComplianceScore() {
        return overallComplianceScore;
    }

    public void setOverallComplianceScore(Double overallComplianceScore) {
        this.overallComplianceScore = overallComplianceScore;
    }

    public List<ConfigScanSummary> getConfigScans() {
        return configScans;
    }

    public void setConfigScans(List<ConfigScanSummary> configScans) {
        this.configScans = configScans;
    }

    public List<VulnerabilitySummary> getVulnerabilities() {
        return vulnerabilities;
    }

    public void setVulnerabilities(List<VulnerabilitySummary> vulnerabilities) {
        this.vulnerabilities = vulnerabilities;
    }

    public Integer getTotalCritical() {
        return totalCritical;
    }

    public void setTotalCritical(Integer totalCritical) {
        this.totalCritical = totalCritical;
    }

    public Integer getTotalHigh() {
        return totalHigh;
    }

    public void setTotalHigh(Integer totalHigh) {
        this.totalHigh = totalHigh;
    }

    public Integer getTotalMedium() {
        return totalMedium;
    }

    public void setTotalMedium(Integer totalMedium) {
        this.totalMedium = totalMedium;
    }

    public Integer getTotalLow() {
        return totalLow;
    }

    public void setTotalLow(Integer totalLow) {
        this.totalLow = totalLow;
    }

    public String getLastScanTime() {
        return lastScanTime;
    }

    public void setLastScanTime(String lastScanTime) {
        this.lastScanTime = lastScanTime;
    }

    // Inner classes
    public static class ConfigScanSummary {
        private String namespace;
        private Double complianceScore;
        private String framework;

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public Double getComplianceScore() {
            return complianceScore;
        }

        public void setComplianceScore(Double complianceScore) {
            this.complianceScore = complianceScore;
        }

        public String getFramework() {
            return framework;
        }

        public void setFramework(String framework) {
            this.framework = framework;
        }
    }

    public static class VulnerabilitySummary {
        private String workloadName;
        private String namespace;
        private String workloadKind;
        private Integer criticalCount;
        private Integer highCount;
        private Integer mediumCount;
        private Integer lowCount;

        public String getWorkloadName() {
            return workloadName;
        }

        public void setWorkloadName(String workloadName) {
            this.workloadName = workloadName;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getWorkloadKind() {
            return workloadKind;
        }

        public void setWorkloadKind(String workloadKind) {
            this.workloadKind = workloadKind;
        }

        public Integer getCriticalCount() {
            return criticalCount;
        }

        public void setCriticalCount(Integer criticalCount) {
            this.criticalCount = criticalCount;
        }

        public Integer getHighCount() {
            return highCount;
        }

        public void setHighCount(Integer highCount) {
            this.highCount = highCount;
        }

        public Integer getMediumCount() {
            return mediumCount;
        }

        public void setMediumCount(Integer mediumCount) {
            this.mediumCount = mediumCount;
        }

        public Integer getLowCount() {
            return lowCount;
        }

        public void setLowCount(Integer lowCount) {
            this.lowCount = lowCount;
        }
    }
}