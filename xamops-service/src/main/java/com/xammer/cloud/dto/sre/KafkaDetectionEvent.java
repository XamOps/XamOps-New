package com.xammer.cloud.dto.sre;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KafkaDetectionEvent {

    @JsonProperty("incident_id")
    private String incidentId;

    @JsonProperty("version")
    private int version = 1;

    @JsonProperty("status")
    private String status;

    @JsonProperty("updated_at")
    private Long updatedAt;

    @JsonProperty("client_id")
    private Long clientId;

    @JsonProperty("entity")
    private String entity;

    @JsonProperty("resource_type")
    private String resourceType;

    @JsonProperty("signal")
    private String signal;

    @JsonProperty("score")
    private double score;

    @JsonProperty("metric_summary")
    private Map<String, Object> metricSummary;

    @JsonProperty("evidence_log_lines")
    private List<String> evidenceLogLines;

    @JsonProperty("github_repo")
    private String githubRepo;

    @JsonProperty("gitlab_project")
    private String gitlabProject;

    @JsonProperty("investigation_id")
    private Long investigationId;
}
