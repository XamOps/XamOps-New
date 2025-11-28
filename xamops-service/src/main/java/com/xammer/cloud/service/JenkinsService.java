package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.JenkinsIntegrationConfig;
import com.xammer.cloud.dto.cicd.GitHubWorkflowRunDto;
import com.xammer.cloud.dto.cicd.JenkinsJobDto;
import com.xammer.cloud.dto.cicd.PipelineStageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class JenkinsService {

    private static final Logger logger = LoggerFactory.getLogger(JenkinsService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public JenkinsService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    // ... existing getJobs method ...
    public List<JenkinsJobDto> getJobs(JenkinsIntegrationConfig config, String decryptedToken) {
        // Fetch jobs with relevant fields: name, url, color (status), and lastBuild
        // info
        String apiUrl = config.getJenkinsUrl()
                + "/api/json?tree=jobs[name,url,color,lastBuild[number,timestamp,result,url]]";

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(config.getUsername(), decryptedToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    apiUrl, HttpMethod.GET, entity, JsonNode.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode jobsNode = response.getBody().get("jobs");
                if (jobsNode != null && jobsNode.isArray()) {
                    List<JenkinsJobDto> jobs = new ArrayList<>();
                    for (JsonNode node : jobsNode) {
                        jobs.add(objectMapper.treeToValue(node, JenkinsJobDto.class));
                    }
                    return jobs;
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching Jenkins jobs for user {}: {}", config.getUsername(), e.getMessage());
        }
        return Collections.emptyList();
    }

    // --- NEW METHOD: Fetch Build History for a Job ---
    public List<GitHubWorkflowRunDto> getJobHistory(JenkinsIntegrationConfig config, String jobName,
            String decryptedToken) {
        // Fetch the last 15 builds
        String apiUrl = String.format("%s/job/%s/api/json?tree=builds[number,url,timestamp,result,duration]{0,15}",
                config.getJenkinsUrl(), jobName);

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(config.getUsername(), decryptedToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, JsonNode.class);
            List<GitHubWorkflowRunDto> runs = new ArrayList<>();

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode buildsNode = response.getBody().get("builds");
                if (buildsNode != null && buildsNode.isArray()) {
                    for (JsonNode build : buildsNode) {
                        GitHubWorkflowRunDto run = new GitHubWorkflowRunDto();

                        // Map Jenkins fields to Generic DTO fields
                        run.setId(build.path("number").asLong()); // Use build number as ID
                        run.setRunNumber(build.path("number").asLong());
                        run.setName(jobName); // Job Name
                        run.setHtmlUrl(build.path("url").asText());

                        // Status mapping
                        String result = build.path("result").asText("IN_PROGRESS");
                        if (result.equals("null"))
                            result = "IN_PROGRESS";
                        run.setDisplayStatus(result); // SUCCESS, FAILURE, etc.

                        // Timestamp mapping
                        long timestamp = build.path("timestamp").asLong();
                        run.setUpdatedAt(Instant.ofEpochMilli(timestamp).toString());

                        runs.add(run);
                    }
                }
            }
            return runs;
        } catch (Exception e) {
            logger.error("Error fetching Jenkins history for job {}: {}", jobName, e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<PipelineStageDto> getJenkinsBuildStages(JenkinsIntegrationConfig config, String jobName,
            int buildNumber, String decryptedToken) {
        // Use the Pipeline Stage View Plugin API
        String apiUrl = String.format("%s/job/%s/%d/wfapi/describe", config.getJenkinsUrl(), jobName, buildNumber);

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(config.getUsername(), decryptedToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, JsonNode.class);
            List<PipelineStageDto> stages = new ArrayList<>();

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode stagesNode = response.getBody().get("stages");
                if (stagesNode != null && stagesNode.isArray()) {
                    for (JsonNode stageNode : stagesNode) {
                        PipelineStageDto stage = new PipelineStageDto();
                        stage.setName(stageNode.path("name").asText());

                        String status = stageNode.path("status").asText();
                        // Jenkins statuses: SUCCESS, FAILED, IN_PROGRESS, PAUSED_PENDING_INPUT, ABORTED
                        if ("SUCCESS".equalsIgnoreCase(status))
                            stage.setStatus("success");
                        else if ("FAILED".equalsIgnoreCase(status))
                            stage.setStatus("failure");
                        else if ("IN_PROGRESS".equalsIgnoreCase(status))
                            stage.setStatus("running");
                        else if ("ABORTED".equalsIgnoreCase(status))
                            stage.setStatus("skipped");
                        else
                            stage.setStatus("pending");

                        long durationMillis = stageNode.path("durationMillis").asLong(0);
                        stage.setDuration((durationMillis / 1000) + "s");

                        stages.add(stage);
                    }
                }
            }
            return stages;
        } catch (Exception e) {
            logger.warn(
                    "Could not fetch Jenkins pipeline stages (wfapi). This is expected for non-pipeline jobs. Error: {}",
                    e.getMessage());
            return Collections.emptyList();
        }
    }
}