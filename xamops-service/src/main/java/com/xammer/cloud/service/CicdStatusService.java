package com.xammer.cloud.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xammer.cloud.dto.cicd.GitHubWorkflowDto;
import com.xammer.cloud.dto.cicd.GitHubWorkflowRunDto;
import com.xammer.cloud.dto.cicd.GitHubWorkflowsApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.cicd.PipelineStageDto;
import java.time.Duration;
import java.time.Instant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class CicdStatusService {

    private static final Logger logger = LoggerFactory.getLogger(CicdStatusService.class);

    private final RestTemplate restTemplate;

    @Value("${github.api.baseurl}")
    private String githubApiBaseUrl;

    @Autowired
    public CicdStatusService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .build();
    }

    /**
     * Fetches the latest workflow run for EACH workflow in a repository.
     * This is the new primary method.
     */
    public List<GitHubWorkflowRunDto> getLatestRunPerWorkflow(String owner, String repo, String decryptedPat) {
        logger.info("Fetching latest run per workflow for {}/{}", owner, repo);

        // 1. Get all workflows in the repo
        List<GitHubWorkflowDto> workflows = fetchWorkflowsForRepo(owner, repo, decryptedPat);
        if (workflows.isEmpty()) {
            logger.warn("No workflows found for {}/{}, or an error occurred.", owner, repo);
            return Collections.emptyList();
        }

        logger.debug("Found {} workflows for {}/{}, fetching latest run for each...", workflows.size(), owner, repo);
        List<GitHubWorkflowRunDto> allLatestRuns = new ArrayList<>();

        // 2. For each workflow, get its latest run
        for (GitHubWorkflowDto workflow : workflows) {
            GitHubWorkflowRunDto latestRun = fetchLatestRunForWorkflow(owner, repo, workflow.getId(), decryptedPat);
            if (latestRun != null) {

                // --- FIX ---
                // The 'latestRun' object now correctly contains the repository info
                // (including the nested owner object) thanks to Jackson and your updated DTO.
                // We no longer need to manually create and set the repoInfo.

                // We just need to set the Workflow Name and ID from the *workflow* object
                latestRun.setName(workflow.getName());
                latestRun.setWorkflowId(workflow.getId());
                allLatestRuns.add(latestRun);
            }
        }
        return allLatestRuns;
    }

    /**
     * Helper method to fetch all defined workflows (.yml files) in a repo.
     */
    private List<GitHubWorkflowDto> fetchWorkflowsForRepo(String owner, String repo, String decryptedPat) {
        String url = UriComponentsBuilder.fromHttpUrl(githubApiBaseUrl)
                .pathSegment("repos", owner, repo, "actions", "workflows")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(decryptedPat);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<GitHubWorkflowsApiResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, GitHubWorkflowsApiResponse.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody().getWorkflows();
            }
            return Collections.emptyList();
        } catch (HttpClientErrorException e) {
            logger.error("HTTP error fetching workflows for {}/{}: {} - {}", owner, repo, e.getStatusCode(),
                    e.getResponseBodyAsString());
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error fetching workflows for {}/{}: {}", owner, repo, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Helper method to fetch the single latest run for a SPECIFIC workflow ID.
     */
    private GitHubWorkflowRunDto fetchLatestRunForWorkflow(String owner, String repo, Long workflowId,
            String decryptedPat) {
        String url = UriComponentsBuilder.fromHttpUrl(githubApiBaseUrl)
                .pathSegment("repos", owner, repo, "actions", "workflows", workflowId.toString(), "runs")
                .queryParam("per_page", 1) // We only want the most recent one
                .queryParam("page", 1)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(decryptedPat);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            // Note: This response already contains the full 'repository' object,
            // which Jackson will parse using your updated DTO.
            ResponseEntity<GitHubWorkflowRunsApiResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, GitHubWorkflowRunsApiResponse.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null
                    && !response.getBody().getWorkflowRuns().isEmpty()) {
                // Return just the first (and only) run
                return response.getBody().getWorkflowRuns().get(0);
            }
            return null; // No runs found for this workflow
        } catch (Exception e) {
            // Log the parsing error you saw
            logger.error("Error fetching latest run for workflow ID {}: {}", workflowId, e.getMessage());
            return null;
        }
    }

    /*
     * This is the old method. We keep it in case it's needed,
     * but the controller will now use getLatestRunPerWorkflow.
     */
    public List<GitHubWorkflowRunDto> getLatestGitHubWorkflowRuns(String owner, String repo, String decryptedPat,
            int count) {
        String url = UriComponentsBuilder.fromHttpUrl(githubApiBaseUrl)
                .pathSegment("repos", owner, repo, "actions", "runs")
                .queryParam("per_page", count)
                .queryParam("page", 1)
                .toUriString();

        logger.info("Fetching GitHub Actions runs from: {}", url);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(decryptedPat);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<GitHubWorkflowRunsApiResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, GitHubWorkflowRunsApiResponse.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null
                    && response.getBody().getWorkflowRuns() != null) {
                List<GitHubWorkflowRunDto> runs = response.getBody().getWorkflowRuns();
                logger.info("Successfully fetched {} workflow runs for {}/{}", runs.size(), owner, repo);
                runs.forEach(run -> {
                    if (run.getRepository() == null) {
                        GitHubWorkflowRunDto.RepositoryInfo repoInfo = new GitHubWorkflowRunDto.RepositoryInfo();
                        repoInfo.setName(repo);
                        repoInfo.setFullName(owner + "/" + repo);

                        // We must also set the nested owner object if we create this manually
                        GitHubWorkflowRunDto.RepositoryInfo.OwnerInfo ownerInfo = new GitHubWorkflowRunDto.RepositoryInfo.OwnerInfo();
                        ownerInfo.setLogin(owner);
                        repoInfo.setOwner(ownerInfo);

                        run.setRepository(repoInfo);
                    }
                });
                return runs;
            } else {
                return Collections.emptyList();
            }
        } catch (HttpClientErrorException e) {
            logger.error("HTTP error fetching GitHub Actions runs for {}/{}: {} - {}", owner, repo, e.getStatusCode(),
                    e.getResponseBodyAsString());
            return Collections.emptyList();
        } catch (Exception e) {
            logger.error("Error fetching GitHub Actions runs for {}/{}: {}", owner, repo, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Inner wrapper class to match the GitHub API response structure
     * which returns {"total_count": ..., "workflow_runs": [...]}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubWorkflowRunsApiResponse {
        @JsonProperty("workflow_runs")
        private List<GitHubWorkflowRunDto> workflowRuns;

        public List<GitHubWorkflowRunDto> getWorkflowRuns() {
            if (workflowRuns == null) {
                workflowRuns = new ArrayList<>();
            }
            return workflowRuns;
        }

        public void setWorkflowRuns(List<GitHubWorkflowRunDto> workflowRuns) {
            this.workflowRuns = workflowRuns;
        }
    }

    // This is the method used by the modal (getWorkflowRunHistory)
    public List<GitHubWorkflowRunDto> getRunsForWorkflow(String owner, String repo, Long workflowId,
            String decryptedPat, int count) {
        String url = UriComponentsBuilder.fromHttpUrl(githubApiBaseUrl)
                .pathSegment("repos", owner, repo, "actions", "workflows", workflowId.toString(), "runs")
                .queryParam("per_page", count) // Use the count parameter
                .queryParam("page", 1)
                .toUriString();

        logger.info("Fetching history for workflow ID {} from: {}", workflowId, url);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(decryptedPat);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<GitHubWorkflowRunsApiResponse> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, GitHubWorkflowRunsApiResponse.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<GitHubWorkflowRunDto> runs = response.getBody().getWorkflowRuns();
                // Set repository info for each run
                runs.forEach(run -> {
                    // This data *should* be in the response, but we set it just in case
                    if (run.getRepository() == null) {
                        GitHubWorkflowRunDto.RepositoryInfo repoInfo = new GitHubWorkflowRunDto.RepositoryInfo();
                        repoInfo.setName(repo);
                        repoInfo.setFullName(owner + "/" + repo);
                        GitHubWorkflowRunDto.RepositoryInfo.OwnerInfo ownerInfo = new GitHubWorkflowRunDto.RepositoryInfo.OwnerInfo();
                        ownerInfo.setLogin(owner);
                        repoInfo.setOwner(ownerInfo);
                        run.setRepository(repoInfo);
                    }
                    run.setWorkflowId(workflowId); // Also add workflowId to each historical run
                });
                return runs;
            }
            return Collections.emptyList();
        } catch (HttpClientErrorException e) {
            logger.error("HTTP error fetching runs for workflow {}: {} - {}", workflowId, e.getStatusCode(),
                    e.getResponseBodyAsString());
            return Collections.emptyList();
        }
    }

    // This method is no longer used, but we can leave it.
    public GitHubWorkflowRunsApiResponse getWorkflowRunsForWorkflow(String owner, String repo, String workflowId,
            String token) {

        String url = UriComponentsBuilder.fromHttpUrl(githubApiBaseUrl)
                .pathSegment("repos", owner, repo, "actions", "workflows", workflowId, "runs")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.set("Accept", "application/vnd.github.v3+json");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<GitHubWorkflowRunsApiResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    GitHubWorkflowRunsApiResponse.class);

            if (response.getBody() == null) {
                logger.warn("Received null body when fetching workflow runs for workflow ID {}", workflowId);
                return new GitHubWorkflowRunsApiResponse();
            }
            return response.getBody();

        } catch (HttpClientErrorException e) {
            logger.error("Error fetching workflow runs: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return new GitHubWorkflowRunsApiResponse(); // Return an empty response
        } catch (Exception e) {
            logger.error("Generic error fetching workflow runs for workflow ID {}: {}", workflowId, e.getMessage());
            return new GitHubWorkflowRunsApiResponse();
        }
    }

    public List<PipelineStageDto> getGitHubRunJobs(String owner, String repo, Long runId, String decryptedPat) {
        String url = UriComponentsBuilder.fromHttpUrl(githubApiBaseUrl)
                .pathSegment("repos", owner, repo, "actions", "runs", runId.toString(), "jobs")
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(decryptedPat);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            List<PipelineStageDto> stages = new ArrayList<>();

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode jobsNode = response.getBody().get("jobs");
                if (jobsNode != null && jobsNode.isArray()) {
                    for (JsonNode job : jobsNode) {
                        PipelineStageDto stage = new PipelineStageDto();
                        stage.setName(job.path("name").asText());

                        String conclusion = job.path("conclusion").asText("null");
                        String status = job.path("status").asText();

                        // Map GitHub status to our generic status
                        if ("success".equalsIgnoreCase(conclusion))
                            stage.setStatus("success");
                        else if ("failure".equalsIgnoreCase(conclusion))
                            stage.setStatus("failure");
                        else if ("skipped".equalsIgnoreCase(conclusion))
                            stage.setStatus("skipped");
                        else if ("in_progress".equalsIgnoreCase(status) || "queued".equalsIgnoreCase(status))
                            stage.setStatus("running");
                        else
                            stage.setStatus("pending");

                        // Calculate Duration
                        String startedAt = job.path("started_at").asText(null);
                        String completedAt = job.path("completed_at").asText(null);
                        if (startedAt != null && completedAt != null) {
                            long diff = Duration.between(Instant.parse(startedAt), Instant.parse(completedAt))
                                    .getSeconds();
                            stage.setDuration(diff + "s");
                        } else {
                            stage.setDuration("-");
                        }

                        stage.setUrl(job.path("html_url").asText());
                        stages.add(stage);
                    }
                }
            }
            return stages;
        } catch (Exception e) {
            logger.error("Error fetching GitHub jobs for run {}: {}", runId, e.getMessage());
            return Collections.emptyList();
        }
    }
}