package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CapacityOwnership;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.flexcompute.*;
import com.xammer.cloud.repository.CapacityOwnershipRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.CapacityMonitoringService;
import com.xammer.cloud.service.CapacityNotificationEmailBuilder;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.FlexComputeService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;

import java.util.*;


@RestController
@RequestMapping("/api/flexcompute")
public class FlexComputeController {

    private static final Logger logger = LoggerFactory.getLogger(FlexComputeController.class);

    private final FlexComputeService flexComputeService;
    private final CapacityMonitoringService capacityMonitoringService;
    private final EmailService emailService;
    private final CapacityNotificationEmailBuilder emailBuilder;
    private final UserRepository userRepository;
    private final CapacityOwnershipRepository capacityOwnershipRepository;

    @Value("${cloudidr.cloudformation.template-url:}")
    private String cloudFormationTemplateUrl;

    @Value("${cloudidr.cloudformation.stack-name:XamOps-CloudiDR-Integration}")
    private String cloudFormationStackName;

    @Value("${capacity.purchase.email.enabled:true}")
    private boolean purchaseEmailEnabled;

    public FlexComputeController(FlexComputeService flexComputeService,
                                 CapacityMonitoringService capacityMonitoringService,
                                 EmailService emailService,
                                 CapacityNotificationEmailBuilder emailBuilder,
                                 UserRepository userRepository,
                                 CapacityOwnershipRepository capacityOwnershipRepository) {
        this.flexComputeService = flexComputeService;
        this.capacityMonitoringService = capacityMonitoringService;
        this.emailService = emailService;
        this.emailBuilder = emailBuilder;
        this.userRepository = userRepository;
        this.capacityOwnershipRepository = capacityOwnershipRepository;
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
     * GET /api/flexcompute/capacity - List capacities (dashboard) with pagination and filters
     */
    @GetMapping("/capacity")
    public ResponseEntity<?> describeCapacity(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(defaultValue = "desc") String sort,
            @RequestParam(required = false) List<String> statuses,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            CapacityListResponse capacities = flexComputeService.describeCapacity(userId, page, pageSize, sort, statuses);
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
     * GET /api/flexcompute/capacity/{cidrId} - Get single capacity detail with activity log
     */
    @GetMapping("/capacity/{cidrId}")
    public ResponseEntity<?> getCapacityDetail(@PathVariable String cidrId,
                                               @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            return ResponseEntity.ok(flexComputeService.getCapacityDetail(userId, cidrId));
        } catch (Exception e) {
            logger.error("FlexCompute get-capacity-detail failed: {}", e.getMessage());
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

            // Record who purchased this capacity so deployment emails only go to the buyer
            capacityOwnershipRepository.save(new CapacityOwnership(request.getCidrId(), userId));

            // Send purchase confirmation email asynchronously so the purchase response
            // is not blocked, and CloudiDR has time to propagate the new capacity to its list.
            if (purchaseEmailEnabled) {
                final Long uid = userId;
                final String cid = request.getCidrId();
                Thread emailThread = new Thread(() -> sendPurchaseConfirmationEmailAsync(uid, cid));
                emailThread.setDaemon(true);
                emailThread.start();
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("FlexCompute purchase-capacity failed: {}", e.getMessage());
            return handleError(e);
        }
    }

    /**
     * PUT /api/flexcompute/capacity/extend - Extend capacity end date
     */
    @PutMapping("/capacity/extend")
    public ResponseEntity<?> extendCapacity(@RequestBody ExtendCapacityRequest request,
                                            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            flexComputeService.extendCapacity(userId, request);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Capacity extended successfully");
            result.put("cidrId", request.getCidrId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("FlexCompute extend-capacity failed: {}", e.getMessage());
            return handleError(e);
        }
    }

    /**
     * PUT /api/flexcompute/capacity/auto-renewal - Update capacity auto-renewal settings
     */
    @PutMapping("/capacity/auto-renewal")
    public ResponseEntity<?> renewCapacity(@RequestBody RenewCapacityRequest request,
                                           @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            flexComputeService.renewCapacity(userId, request);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Auto-renewal settings updated successfully");
            result.put("cidrId", request.getCidrId());
            result.put("autoRenewalEnabled", request.getAutoRenewalEnabled());
            result.put("frequency", request.getFrequency());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("FlexCompute renew-capacity failed: {}", e.getMessage());
            return handleError(e);
        }
    }

    /**
     * DELETE /api/flexcompute/capacity/{cidrId} - Cancel capacity
     */
    @DeleteMapping("/capacity/{cidrId}")
    public ResponseEntity<?> cancelCapacity(@PathVariable String cidrId,
                                           @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            flexComputeService.cancelCapacity(userId, cidrId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Capacity cancelled successfully");
            result.put("cidrId", cidrId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("FlexCompute cancel-capacity failed: {}", e.getMessage());
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

    /**
     * POST /api/flexcompute/capacity/monitor - Manually trigger capacity monitoring for current user
     */
    @PostMapping("/capacity/monitor")
    public ResponseEntity<?> triggerCapacityMonitoring(@AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            Long userId = userDetails.getId();
            logger.info("Manual capacity monitoring triggered for user {}", userId);
            capacityMonitoringService.triggerManualMonitoring(userId);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Capacity monitoring triggered successfully");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("FlexCompute manual monitoring failed: {}", e.getMessage());
            return handleError(e);
        }
    }

    /**
     * Async helper: waits for CloudiDR to propagate the purchased capacity, then sends email.
     * Called in a daemon thread so it never blocks the purchase HTTP response.
     * Retries once after a longer delay if the capacity isn't in the list yet.
     */
    private void sendPurchaseConfirmationEmailAsync(Long userId, String cidrId) {
        // Wait 5 seconds for CloudiDR to propagate the new capacity into the list
        try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }

        if (trySendPurchaseConfirmationEmail(userId, cidrId)) return;

        // Capacity not found on first attempt — wait 20 more seconds and retry once
        logger.info("Purchase email: capacity {} not yet in list, retrying in 20s", cidrId);
        try { Thread.sleep(20000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }

        trySendPurchaseConfirmationEmail(userId, cidrId);
    }

    /**
     * Attempts to fetch capacity details and send the purchase confirmation email.
     * Returns true if the email was sent (or definitively cannot be sent), false if the
     * capacity was not found in the list yet and the caller should retry.
     */
    private boolean trySendPurchaseConfirmationEmail(Long userId, String cidrId) {
        try {
            // Get user email
            Optional<User> userOpt = userRepository.findById(userId);
            if (!userOpt.isPresent() || userOpt.get().getEmail() == null || userOpt.get().getEmail().isEmpty()) {
                logger.warn("Cannot send purchase email - user {} has no email address", userId);
                return true; // no point retrying
            }

            String userEmail = userOpt.get().getEmail();

            // Fetch capacity details from CloudiDR list (all statuses, up to 100)
            CapacityListResponse capacityList = flexComputeService.describeCapacity(userId, 1, 100, "desc", null);

            if (capacityList == null || capacityList.getValues() == null) {
                logger.warn("Purchase email: describeCapacity returned empty list for cidrId {}", cidrId);
                return false; // retry may help
            }

            // Find the purchased capacity in the list
            Optional<CapacityDescriptionResponse> purchasedCapacity = capacityList.getValues().stream()
                    .filter(c -> cidrId.equals(c.getCidrId()))
                    .findFirst();

            if (!purchasedCapacity.isPresent()) {
                logger.warn("Purchase email: capacity {} not found in list ({}  items returned)", cidrId,
                        capacityList.getValues().size());
                return false; // retry may help
            }

            CapacityDescriptionResponse capacity = purchasedCapacity.get();

            // Build and send email
            String htmlBody = emailBuilder.buildCapacityPurchaseEmail(capacity, userEmail);
            String subject = String.format("XamOps FlexCompute - Capacity Purchase Confirmed: %s", capacity.getPolicyName());

            emailService.sendHtmlEmail(userEmail, subject, htmlBody);

            logger.info("Purchase confirmation email sent to {} for capacity {}", userEmail, cidrId);
            return true;

        } catch (Exception e) {
            logger.error("Failed to send purchase confirmation email for capacity {}: {}", cidrId, e.getMessage(), e);
            return true; // non-retriable error (SMTP failure etc.)
        }
    }

    private ResponseEntity<?> handleError(Exception e) {
        // If the root cause is a CloudiDR HTTP error, forward its JSON body directly
        // so the frontend can display the exact CloudiDR error description.
        Throwable cause = e.getCause();
        if (cause instanceof HttpClientErrorException) {
            HttpClientErrorException httpEx = (HttpClientErrorException) cause;
            String body = httpEx.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                return ResponseEntity.status(httpEx.getStatusCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);
            }
        }

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
