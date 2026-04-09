package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DTO for Prowler v5 OCSF (Open Cybersecurity Schema Framework) output.
 * Maps the DetectionFinding schema from json-ocsf output format.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProwlerFinding {

    @JsonProperty("status_code")
    private String statusCode; // "FAIL", "PASS", "MANUAL"

    @JsonProperty("severity")
    private String severity; // "Critical", "High", "Medium", "Low", "Informational"

    @JsonProperty("severity_id")
    private Integer severityId;

    @JsonProperty("message")
    private String message; // status_extended detail

    @JsonProperty("finding_info")
    private FindingInfo findingInfo;

    @JsonProperty("resources")
    private List<Resource> resources;

    @JsonProperty("metadata")
    private FindingMetadata metadata;

    @JsonProperty("cloud")
    private CloudInfo cloud;

    @JsonProperty("remediation")
    private RemediationInfo remediation;

    @JsonProperty("unmapped")
    private Unmapped unmapped;

    // --- Convenience Accessors (hidden from Jackson to avoid serialization conflicts) ---

    @JsonIgnore
    public String getStatus() {
        return statusCode;
    }

    @JsonIgnore
    public String getResourceId() {
        if (resources != null && !resources.isEmpty()) {
            return resources.get(0).getUid();
        }
        return null;
    }

    @JsonIgnore
    public String getRegion() {
        if (resources != null && !resources.isEmpty() && resources.get(0).getRegion() != null) {
            return resources.get(0).getRegion();
        }
        if (cloud != null) {
            return cloud.getRegion();
        }
        return null;
    }

    @JsonIgnore
    public String getServiceName() {
        if (resources != null && !resources.isEmpty() && resources.get(0).getGroup() != null) {
            return resources.get(0).getGroup().getName();
        }
        return null;
    }

    @JsonIgnore
    public String getCheckId() {
        if (metadata != null) {
            return metadata.getEventCode();
        }
        return null;
    }

    @JsonIgnore
    public String getCheckTitle() {
        if (findingInfo != null) {
            return findingInfo.getTitle();
        }
        return null;
    }

    @JsonIgnore
    public String getDescription() {
        if (findingInfo != null) {
            return findingInfo.getDesc();
        }
        return message;
    }

    @JsonIgnore
    public Map<String, List<String>> getCompliance() {
        if (unmapped != null) {
            return unmapped.getCompliance();
        }
        return null;
    }

    // --- Nested DTOs ---

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FindingInfo {
        @JsonProperty("title")
        private String title;

        @JsonProperty("desc")
        private String desc;

        @JsonProperty("uid")
        private String uid;

        @JsonProperty("name")
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Resource {
        @JsonProperty("uid")
        private String uid;

        @JsonProperty("name")
        private String name;

        @JsonProperty("region")
        private String region;

        @JsonProperty("type")
        private String type;

        @JsonProperty("group")
        private ResourceGroup group;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceGroup {
        @JsonProperty("name")
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FindingMetadata {
        @JsonProperty("event_code")
        private String eventCode; // CheckID

        @JsonProperty("product")
        private Product product;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Product {
        @JsonProperty("name")
        private String name;

        @JsonProperty("version")
        private String version;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CloudInfo {
        @JsonProperty("region")
        private String region;

        @JsonProperty("provider")
        private String provider;

        @JsonProperty("account")
        private CloudAccount account;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CloudAccount {
        @JsonProperty("uid")
        private String uid;

        @JsonProperty("name")
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RemediationInfo {
        @JsonProperty("desc")
        private String desc;

        @JsonProperty("references")
        private List<String> references;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Unmapped {
        @JsonProperty("compliance")
        private Map<String, List<String>> compliance;

        @JsonProperty("categories")
        private List<String> categories;
    }
}
