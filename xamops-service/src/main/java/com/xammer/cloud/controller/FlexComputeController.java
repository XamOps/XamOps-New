package com.xammer.cloud.controller;

import com.xammer.cloud.dto.flexcompute.*;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.FlexComputeService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/flexcompute")
public class FlexComputeController {

    private static final Logger logger = LoggerFactory.getLogger(FlexComputeController.class);

    private final FlexComputeService flexComputeService;

    @Value("${cloudidr.cloudformation.template-url:}")
    private String cloudFormationTemplateUrl;

    @Value("${cloudidr.cloudformation.stack-name:XamOps-CloudiDR-Integration}")
    private String cloudFormationStackName;

    public FlexComputeController(FlexComputeService flexComputeService) {
        this.flexComputeService = flexComputeService;
    }

    /**
     * POST /api/flexcompute/auth - Authenticate with CloudiDR
     */
    @PostMapping("/auth")
    public ResponseEntity<?> authenticate(@RequestBody CloudiDRSignInRequest request,
                                          @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            logger.info("FlexCompute auth request for user {}", userId);

            CloudiDRSignInResponse response = flexComputeService.authenticateUser(
                    userId, request.getEmail(), request.getPassword());

            Map<String, Object> result = new HashMap<>();
            result.put("authenticated", true);
            result.put("email", request.getEmail());
            result.put("message", "Successfully authenticated with CloudiDR");

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("FlexCompute auth failed: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("authenticated", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
    }

    /**
     * GET /api/flexcompute/auth/status - Check CloudiDR auth status
     */
    @GetMapping("/auth/status")
    public ResponseEntity<?> getAuthStatus(@AuthenticationPrincipal ClientUserDetails userDetails) {
        Long userId = userDetails.getId();
        boolean isAuthenticated = flexComputeService.isAuthenticated(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("authenticated", isAuthenticated);
        if (isAuthenticated) {
            result.put("email", flexComputeService.getAuthEmail(userId));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * DELETE /api/flexcompute/auth - Disconnect CloudiDR
     */
    @DeleteMapping("/auth")
    public ResponseEntity<?> disconnect(@AuthenticationPrincipal ClientUserDetails userDetails) {
        Long userId = userDetails.getId();
        flexComputeService.disconnect(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("disconnected", true);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/flexcompute/linked-accounts - Get linked cloud accounts (from database)
     */
    @GetMapping("/linked-accounts")
    public ResponseEntity<?> getLinkedAccounts(@AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            List<LinkedAccountResponse> accounts = flexComputeService.getLinkedAccounts(userId);
            return ResponseEntity.ok(accounts);
        } catch (Exception e) {
            logger.error("FlexCompute get-linked-accounts failed: {}", e.getMessage());
            return handleError(e);
        }
    }

    /**
     * POST /api/flexcompute/linked-accounts/refresh - Refresh linked accounts from CloudiDR
     */
    @PostMapping("/linked-accounts/refresh")
    public ResponseEntity<?> refreshLinkedAccounts(@AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            List<LinkedAccountResponse> accounts = flexComputeService.refreshLinkedAccounts(userId);
            return ResponseEntity.ok(accounts);
        } catch (Exception e) {
            logger.error("FlexCompute refresh-linked-accounts failed: {}", e.getMessage());
            return handleError(e);
        }
    }

    /**
     * GET /api/flexcompute/capacity - List all capacities (dashboard)
     */
    @GetMapping("/capacity")
    public ResponseEntity<?> describeCapacity(@AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            CapacityListResponse capacities = flexComputeService.describeCapacity(userId);
            return ResponseEntity.ok(capacities);
        } catch (Exception e) {
            logger.error("FlexCompute describe-capacity failed: {}", e.getMessage());
            return handleError(e);
        }
    }

    /**
     * POST /api/flexcompute/capacity/search - Search available capacity
     */
    @PostMapping("/capacity/search")
    public ResponseEntity<?> searchCapacity(@RequestBody SearchCapacityRequest request,
                                            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            SearchCapacityResponse response = flexComputeService.searchCapacity(userId, request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("FlexCompute search-capacity failed: {}", e.getMessage());
            return handleError(e);
        }
    }

    /**
     * POST /api/flexcompute/capacity/reserve - Reserve capacity
     * Creates a temporary 15-minute reservation with cidrId and pricing
     */
    @PostMapping("/capacity/reserve")
    public ResponseEntity<?> reserveCapacity(@RequestBody ReserveCapacityRequest request,
                                             @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            ReserveCapacityResponse response = flexComputeService.reserveCapacity(userId, request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("FlexCompute reserve-capacity failed: {}", e.getMessage());
            return handleError(e);
        }
    }

    /**
     * PUT /api/flexcompute/capacity/configure - Configure capacity
     */
    @PutMapping("/capacity/configure")
    public ResponseEntity<?> configureCapacity(@RequestBody ConfigureCapacityRequest request,
                                               @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            SearchCapacityResponse response = flexComputeService.configureCapacity(userId, request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("FlexCompute configure-capacity failed: {}", e.getMessage());
            return handleError(e);
        }
    }

    /**
     * POST /api/flexcompute/capacity/purchase - Purchase capacity
     */
    @PostMapping("/capacity/purchase")
    public ResponseEntity<?> purchaseCapacity(@RequestBody PurchaseCapacityRequest request,
                                              @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            SearchCapacityResponse response = flexComputeService.purchaseCapacity(userId, request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("FlexCompute purchase-capacity failed: {}", e.getMessage());
            return handleError(e);
        }
    }

    /**
     * GET /api/flexcompute/cloudformation/deploy-url - Generate CF Quick Create URL
     */
    @GetMapping("/cloudformation/deploy-url")
    public ResponseEntity<?> getCloudFormationDeployUrl(@RequestParam(defaultValue = "us-east-1") String region) {
        if (cloudFormationTemplateUrl == null || cloudFormationTemplateUrl.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "CloudFormation template URL not configured"));
        }

        String quickCreateUrl = String.format(
                "https://%s.console.aws.amazon.com/cloudformation/home?region=%s#/stacks/quickcreate?stackName=%s&templateURL=%s",
                region, region, cloudFormationStackName, cloudFormationTemplateUrl);

        Map<String, String> result = new HashMap<>();
        result.put("deployUrl", quickCreateUrl);
        result.put("stackName", cloudFormationStackName);
        result.put("region", region);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<?> handleError(Exception e) {
        String message = e.getMessage();
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;

        if (message != null && message.contains("not configured")) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (message != null && message.contains("expired")) {
            status = HttpStatus.UNAUTHORIZED;
        }

        Map<String, String> error = new HashMap<>();
        error.put("error", message);
        return ResponseEntity.status(status).body(error);
    }
}
