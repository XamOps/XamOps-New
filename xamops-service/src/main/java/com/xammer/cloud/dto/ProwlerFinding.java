package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProwlerFinding {

    @JsonProperty("Status")
    private String status; // e.g., "FAIL", "PASS"

    @JsonProperty("Severity")
    private String severity; // e.g., "high", "critical"

    @JsonProperty("ServiceName")
    private String serviceName;

    @JsonProperty("ResourceId")
    private String resourceId;

    @JsonProperty("Region")
    private String region;

    @JsonProperty("CheckID")
    private String checkId;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("Remediation")
    private Remediation remediation;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Remediation {
        @JsonProperty("Recommendation")
        private Recommendation recommendation;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Recommendation {
        @JsonProperty("Text")
        private String text;

        @JsonProperty("Url")
        private String url;
    }
}