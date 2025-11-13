package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.SonarQubeProject;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.sonarqube.SonarQubeMetricsDto;
import com.xammer.cloud.dto.sonarqube.SonarQubeProjectDto;
import com.xammer.cloud.repository.SonarQubeProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;

@Service
public class SonarQubeService {

    private static final Logger logger = LoggerFactory.getLogger(SonarQubeService.class);

    private final SonarQubeProjectRepository projectRepository;
    private final EncryptionService encryptionService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public SonarQubeService(SonarQubeProjectRepository projectRepository,
                            EncryptionService encryptionService,
                            RestTemplate restTemplate,
                            ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.encryptionService = encryptionService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<SonarQubeProject> getProjectsForUser(User user) {
        return projectRepository.findByUserId(user.getId());
    }

    @Transactional(readOnly = true)
    public Optional<SonarQubeProject> getProjectById(Long projectId, User user) {
        return projectRepository.findById(projectId)
                .filter(project -> project.getUser().getId().equals(user.getId()));
    }

    @Transactional
    public SonarQubeProject addProject(SonarQubeProjectDto dto, User user) {
        String encryptedToken = encryptionService.encrypt(dto.getToken());
        if (encryptedToken == null) {
            logger.error("Failed to encrypt SonarQube token for user {}", user.getUsername());
            throw new RuntimeException("Encryption failed. Cannot save project.");
        }

        SonarQubeProject project = new SonarQubeProject(
                user,
                dto.getProjectName(),
                dto.getServerUrl(),
                dto.getProjectKey(),
                encryptedToken
        );
        return projectRepository.save(project);
    }

    @Transactional
    public boolean deleteProject(Long projectId, User user) {
        Optional<SonarQubeProject> projectOpt = getProjectById(projectId, user);
        if (projectOpt.isPresent()) {
            projectRepository.delete(projectOpt.get());
            logger.info("Deleted SonarQube project ID {} for user {}", projectId, user.getUsername());
            return true;
        }
        logger.warn("User {} failed to delete SonarQube project ID {}: Not found or no permission", user.getUsername(), projectId);
        return false;
    }

    /**
     * Fetches live metrics from the SonarQube API for a given project.
     */
    public SonarQubeMetricsDto getProjectMetrics(SonarQubeProject project) {
        String token = encryptionService.decrypt(project.getEncryptedToken());
        if (token == null) {
            logger.error("Failed to decrypt SonarQube token for project ID: {}", project.getId());
            return null;
        }

        // --- START OF MODIFICATION ---
        // We are adding all the new metric keys from your screenshot
        String metricKeys = "alert_status,bugs,vulnerabilities,code_smells,coverage,ncloc," +
                            "reliability_rating,security_rating,security_hotspots,security_review_rating," +
                            "sqale_rating,sqale_index,lines_to_cover,duplicated_lines,duplicated_lines_density";

        String url = UriComponentsBuilder.fromHttpUrl(project.getServerUrl())
                .path("/api/measures/component")
                .queryParam("component", project.getProjectKey())
                .queryParam("metricKeys", metricKeys) // Use the new, expanded list
                .toUriString();
        // --- END OF MODIFICATION ---

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(token, "");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            logger.info("Fetching SonarQube metrics for project: {}", project.getProjectKey());
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            return parseSonarQubeResponse(response.getBody());

        } catch (Exception e) {
            logger.error("Failed to fetch SonarQube data for project {}: {}", project.getProjectKey(), e.getMessage());
            return null;
        }
    }

    /**
     * This helper method parses the complex JSON response from SonarQube.
     */
    private SonarQubeMetricsDto parseSonarQubeResponse(String jsonResponse) {
        try {
            SonarQubeMetricsDto metricsDto = new SonarQubeMetricsDto();
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode component = root.path("component");
            JsonNode measures = component.path("measures");

            for (JsonNode measure : measures) {
                String metric = measure.path("metric").asText();
                String value = measure.path("value").asText();
                // Some ratings are in a "period" object, check there too.
                if (value.isEmpty()) {
                    value = measure.path("period").path("value").asText();
                }
                
                switch (metric) {
                    case "alert_status":
                        metricsDto.setQualityGateStatus(value);
                        break;
                    case "bugs":
                        metricsDto.setBugs(Integer.parseInt(value));
                        break;
                    case "vulnerabilities":
                        metricsDto.setVulnerabilities(Integer.parseInt(value));
                        break;
                    case "code_smells":
                        metricsDto.setCodeSmells(Integer.parseInt(value));
                        break;
                    case "coverage":
                        metricsDto.setCoverage(Double.parseDouble(value));
                        break;
                    case "ncloc":
                        metricsDto.setLinesOfCode(Integer.parseInt(value));
                        break;
                    
                    // --- START OF NEW METRICS ---
                    case "reliability_rating":
                        metricsDto.setReliabilityRating(value);
                        break;
                    case "security_rating":
                        metricsDto.setSecurityRating(value);
                        break;
                    case "security_hotspots":
                        metricsDto.setSecurityHotspots(Integer.parseInt(value));
                        break;
                    case "security_review_rating":
                        metricsDto.setSecurityReviewRating(value);
                        break;
                    case "sqale_rating": // Maintainability Rating
                        metricsDto.setMaintainabilityRating(value);
                        break;
                    case "sqale_index": // Technical Debt (in minutes)
                        metricsDto.setTechDebt(Integer.parseInt(value));
                        break;
                    case "lines_to_cover":
                        metricsDto.setLinesToCover(Integer.parseInt(value));
                        break;
                    case "duplicated_lines":
                        metricsDto.setDuplicatedLines(Integer.parseInt(value));
                        break;
                    case "duplicated_lines_density":
                        metricsDto.setDuplicationDensity(Double.parseDouble(value));
                        break;
                    // --- END OF NEW METRICS ---
                }
            }
            return metricsDto;
        } catch (Exception e) {
            logger.error("Failed to parse SonarQube JSON response", e);
            return null;
        }
    }
}