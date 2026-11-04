package com.xammer.cloud.controller;

import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.agent.AgentPrincipal;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.ApprovedAction;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchRequest;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchResponse;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.ClaimRequest;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.DispatchedResponse;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.OutcomeRequest;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.OutcomeResponse;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.service.AgentIngestService;
import com.xammer.cloud.service.automation.proposal.ProposalException;
import com.xammer.cloud.service.automation.proposal.ProposalService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * The automation agent's side of Manual mode (design 3.4, 3.8): submit proposals, claim at most one
 * approved action, list the actions it was handed, report an outcome.
 *
 * <p><strong>Who may call it.</strong> Only a {@code CLUSTER}-scoped principal authenticated by the
 * cluster's <em>automation</em> key. {@link #resolveAutomationConfig} accepts nothing else: the legacy
 * shared key, an account-scoped key and the read-only data key are all refused (403), and the cluster
 * config is resolved <em>only</em> through {@code findByAutomationAgentApiKey}, never through the
 * query parameters or an account fallback. The resolved config is the sole cluster scope for every call;
 * a proposal id in a path is only ever looked up as {@code (id, config.getId())}, so another cluster's
 * id is a 404.
 */
@RestController
@RequestMapping("/api/v1/agent/proposals")
public class AgentProposalController {

    private static final Logger logger = LoggerFactory.getLogger(AgentProposalController.class);

    private static final long MAX_BATCH_BYTES = 2L * 1024 * 1024;
    private static final long MAX_OUTCOME_BYTES = 1024L * 1024;

    private final AgentIngestService agentIngestService;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final ProposalService proposalService;

    public AgentProposalController(AgentIngestService agentIngestService,
                                   KubernetesClusterConfigRepository clusterConfigRepository,
                                   ProposalService proposalService) {
        this.agentIngestService = agentIngestService;
        this.clusterConfigRepository = clusterConfigRepository;
        this.proposalService = proposalService;
    }

    @PostMapping
    public ResponseEntity<BatchResponse> submit(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam String clusterName,
            @RequestParam Long cloudAccountId,
            @RequestBody BatchRequest body,
            HttpServletRequest request) {
        KubernetesClusterConfig config = resolveAutomationConfig(authHeader, clusterName, cloudAccountId, request);
        if (request.getContentLengthLong() > MAX_BATCH_BYTES) {
            throw new ProposalException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "A batch is at most 2 MB.");
        }
        try {
            return ResponseEntity.ok(proposalService.acceptBatch(config, body));
        } catch (ProposalException e) {
            if (e.getStatus().value() == 400 || e.getStatus().value() == 413) {
                logger.warn("Proposal batch rejected for cluster config {}: status={} code={}", config.getId(),
                        e.getStatus().value(), e.getCode());
            }
            throw e;
        }
    }

    @PostMapping("/claim")
    public ResponseEntity<ApprovedAction> claim(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam String clusterName,
            @RequestParam Long cloudAccountId,
            @RequestBody ClaimRequest body,
            HttpServletRequest request) {
        KubernetesClusterConfig config = resolveAutomationConfig(authHeader, clusterName, cloudAccountId, request);
        Optional<ApprovedAction> claimed = proposalService.claim(config, body);
        return claimed.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/dispatched")
    public ResponseEntity<DispatchedResponse> dispatched(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam String clusterName,
            @RequestParam Long cloudAccountId,
            HttpServletRequest request) {
        KubernetesClusterConfig config = resolveAutomationConfig(authHeader, clusterName, cloudAccountId, request);
        return ResponseEntity.ok(proposalService.dispatched(config));
    }

    @PostMapping("/{id}/outcome")
    public ResponseEntity<OutcomeResponse> outcome(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam String clusterName,
            @RequestParam Long cloudAccountId,
            @PathVariable("id") String id,
            @RequestBody OutcomeRequest body,
            HttpServletRequest request) {
        KubernetesClusterConfig config = resolveAutomationConfig(authHeader, clusterName, cloudAccountId, request);
        if (request.getContentLengthLong() > MAX_OUTCOME_BYTES) {
            throw new ProposalException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "An outcome is at most 1 MB.");
        }
        return ResponseEntity.ok(proposalService.outcome(config, id, body));
    }

    // ------------------------------------------------------------------ authentication

    /**
     * The single authentication path of this controller: bearer key, automation role, ownership,
     * config by automation key only, and a cluster-name match. Everything else is 401/403.
     */
    KubernetesClusterConfig resolveAutomationConfig(String authHeader, String clusterName, Long cloudAccountId,
                                                    HttpServletRequest request) {
        String apiKey = extractApiKey(authHeader);
        AgentPrincipal principal = apiKey == null ? null : agentIngestService.authenticate(apiKey, clientIp(request));
        if (principal == null) {
            throw new ProposalException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Invalid or missing API key.");
        }
        if (principal.getScope() == AgentPrincipal.Scope.LEGACY_GLOBAL) {
            throw new ProposalException(HttpStatus.FORBIDDEN, "AGENT_KEY_LEGACY",
                    "The shared legacy key can't be used for Manual mode.");
        }
        if (principal.getScope() != AgentPrincipal.Scope.CLUSTER
                || principal.getRole() != AgentPrincipal.AgentRole.AUTOMATION) {
            throw new ProposalException(HttpStatus.FORBIDDEN, "AGENT_KEY_NOT_AUTOMATION",
                    "This endpoint needs the cluster's automation agent key.");
        }
        String denied = agentIngestService.checkOwnership(principal, String.valueOf(cloudAccountId), clusterName);
        if (denied != null) {
            throw new ProposalException(HttpStatus.FORBIDDEN, "AGENT_KEY_NOT_AUTOMATION", denied);
        }
        KubernetesClusterConfig config = clusterConfigRepository.findByAutomationAgentApiKey(apiKey)
                .orElseThrow(() -> new ProposalException(HttpStatus.FORBIDDEN, "AGENT_KEY_NOT_AUTOMATION",
                        "This endpoint needs the cluster's automation agent key."));
        if (clusterName == null || !clusterName.equals(config.getClusterName())) {
            throw new ProposalException(HttpStatus.FORBIDDEN, "AGENT_KEY_NOT_AUTOMATION",
                    "This agent key is not authorised for the requested cluster.");
        }
        return config;
    }

    private static String extractApiKey(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String key = authHeader.substring(7).trim();
        return key.isEmpty() ? null : key;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    // ------------------------------------------------------------------ errors

    @ExceptionHandler(ProposalException.class)
    public ResponseEntity<Map<String, Object>> handleProposal(ProposalException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getStatus());
        if (e.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header(HttpHeaders.RETRY_AFTER, "2");
        }
        return builder.body(Map.of("error", e.getMessage(), "code", e.getCode()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "The request body isn't valid.", "code", "INVALID_REQUEST"));
    }
}
