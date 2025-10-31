package com.xammer.cloud.controller;

import com.xammer.cloud.domain.GitHubIntegrationConfig;
import com.xammer.cloud.dto.cicd.GitHubConfigRequestDto;
import com.xammer.cloud.dto.cicd.GitHubConfigResponseDto;
import com.xammer.cloud.service.CicdConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;
// Optional: Add validation import if using DTO validation
// import javax.validation.Valid;

@RestController
@RequestMapping("/api/cicd/config") // Base path for configuration endpoints
@CrossOrigin(origins = "*")
public class CicdConfigurationController {

    private static final Logger logger = LoggerFactory.getLogger(CicdConfigurationController.class);

    @Autowired
    private CicdConfigurationService configService;

    /**
     * Endpoint to save a new GitHub repository configuration for the current user.
     */
    @PostMapping("/github")
    // Use @Valid if you added validation annotations to the DTO
    public ResponseEntity<?> addGitHubConfig(/*@Valid*/ @RequestBody GitHubConfigRequestDto requestDto) {
        logger.info("Received request to add GitHub config: {}/{}", requestDto.getOwner(), requestDto.getRepo());
        GitHubIntegrationConfig savedConfig = configService.saveGitHubConfig(requestDto);

        if (savedConfig != null) {
            // Return the safe DTO (without PAT) and a 201 Created status
            return ResponseEntity.status(HttpStatus.CREATED).body(GitHubConfigResponseDto.fromEntity(savedConfig));
        } else {
            // Handle potential errors (e.g., encryption failure, user not found, database error)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to save GitHub configuration.");
        }
    }

    /**
     * Endpoint to list all GitHub repository configurations for the current user.
     */
    @GetMapping("/github")
    public ResponseEntity<List<GitHubConfigResponseDto>> listGitHubConfigs() {
        logger.debug("Received request to list GitHub configs for current user.");
        List<GitHubIntegrationConfig> configs = configService.getGitHubConfigsForCurrentUser();

        // Convert entities to safe DTOs before sending to frontend
        List<GitHubConfigResponseDto> responseDtos = configs.stream()
                .map(GitHubConfigResponseDto::fromEntity)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responseDtos);
    }

    /**
     * Endpoint to delete a specific GitHub repository configuration for the current user.
     */
    @DeleteMapping("/github/{configId}")
    public ResponseEntity<?> deleteGitHubConfig(@PathVariable Long configId) {
        logger.info("Received request to delete GitHub config with ID: {}", configId);
        boolean deleted = configService.deleteGitHubConfig(configId);

        if (deleted) {
            return ResponseEntity.noContent().build(); // Standard 204 No Content for successful deletion
        } else {
            // Could be not found, or not owned by user, or other error
            // Returning 404 is common, even if it's an ownership issue, to avoid revealing existence
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Configuration not found or user not authorized to delete.");
        }
    }
}