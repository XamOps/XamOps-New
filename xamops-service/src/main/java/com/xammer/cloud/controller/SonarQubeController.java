package com.xammer.cloud.controller;

import com.xammer.cloud.domain.SonarQubeProject;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.sonarqube.SonarQubeMetricsDto;
import com.xammer.cloud.dto.sonarqube.SonarQubeProjectDto;
import com.xammer.cloud.dto.sonarqube.SonarQubeProjectResponseDto;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.SonarQubeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/xamops/sonarqube")
@PreAuthorize("isAuthenticated()") // ✅ ADDED: Protect all endpoints in this controller
public class SonarQubeController {

    private static final Logger logger = LoggerFactory.getLogger(SonarQubeController.class);

    @Autowired
    private SonarQubeService sonarQubeService;

    @Autowired
    private UserRepository userRepository;

    /**
     * Helper to get the authenticated User entity.
     */
    private User getCurrentUser(Principal principal) {
        if (principal == null) {
            throw new SecurityException("User is not authenticated");
        }
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + principal.getName()));
    }

    /**
     * Add a new SonarQube project configuration.
     */
    @PostMapping("/projects")
    public ResponseEntity<SonarQubeProjectResponseDto> addProject(
            @RequestBody SonarQubeProjectDto projectDto,
            Principal principal) {
        try {
            User user = getCurrentUser(principal);
            logger.info("User {} is adding a new SonarQube project: {}", user.getUsername(), projectDto.getProjectName());
            
            SonarQubeProject project = sonarQubeService.addProject(projectDto, user);
            return ResponseEntity.status(HttpStatus.CREATED).body(SonarQubeProjectResponseDto.fromEntity(project));
        } catch (Exception e) {
            logger.error("Error adding SonarQube project: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * List all SonarQube projects for the current user.
     */
    @GetMapping("/projects")
    public ResponseEntity<List<SonarQubeProjectResponseDto>> getProjects(Principal principal) {
        try {
            User user = getCurrentUser(principal);
            logger.info("Fetching SonarQube projects for user: {}", user.getUsername());
            
            List<SonarQubeProject> projects = sonarQubeService.getProjectsForUser(user);
            List<SonarQubeProjectResponseDto> dtos = projects.stream()
                    .map(SonarQubeProjectResponseDto::fromEntity)
                    .collect(Collectors.toList());
            
            logger.info("Found {} SonarQube projects for user: {}", dtos.size(), user.getUsername());
            return ResponseEntity.ok(dtos);
        } catch (UsernameNotFoundException e) {
            logger.error("User not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            logger.error("Error fetching SonarQube projects: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Delete a SonarQube project configuration.
     */
    @DeleteMapping("/projects/{projectId}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable Long projectId, 
            Principal principal) {
        try {
            User user = getCurrentUser(principal);
            logger.info("User {} is deleting SonarQube project ID: {}", user.getUsername(), projectId);
            
            boolean deleted = sonarQubeService.deleteProject(projectId, user);
            if (deleted) {
                logger.info("Successfully deleted SonarQube project ID: {}", projectId);
                return ResponseEntity.noContent().build();
            } else {
                logger.warn("SonarQube project ID {} not found or unauthorized for user {}", projectId, user.getUsername());
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            logger.error("Error deleting SonarQube project: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get live metrics for a specific SonarQube project.
     */
    @GetMapping("/projects/{projectId}/metrics")
    public ResponseEntity<SonarQubeMetricsDto> getProjectMetrics(
            @PathVariable Long projectId,
            Principal principal) {
        
        try {
            User user = getCurrentUser(principal);
            logger.info("Fetching metrics for SonarQube project ID {} for user: {}", projectId, user.getUsername());
            
            Optional<SonarQubeProject> projectOpt = sonarQubeService.getProjectById(projectId, user);

            if (projectOpt.isEmpty()) {
                logger.warn("User {} tried to access non-existent or unauthorized SonarQube project ID {}", user.getUsername(), projectId);
                return ResponseEntity.notFound().build();
            }

            SonarQubeMetricsDto metrics = sonarQubeService.getProjectMetrics(projectOpt.get());
            if (metrics == null) {
                logger.error("Could not retrieve metrics from SonarQube for project ID: {}", projectId);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }

            logger.info("Successfully retrieved metrics for project ID: {}", projectId);
            return ResponseEntity.ok(metrics);
        } catch (UsernameNotFoundException e) {
            logger.error("User not found: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            logger.error("Error fetching metrics for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
