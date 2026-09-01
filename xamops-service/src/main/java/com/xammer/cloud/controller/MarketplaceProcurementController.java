package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Proxy controller for the marketplace-procurement Python microservice.
 *
 * Receives requests from the frontend, enriches the body with the customer's
 * IAM roleArn + externalId (from the DB), then forwards to the Python service
 * which uses boto3 for the new AWS Marketplace Agreements API operations.
 *
 * Frontend calls:  POST /api/marketops/procurement/{path}?cloudAccountId=...
 * Python service:  POST http://localhost:5003/procurement/{path}
 */
@RestController
@RequestMapping("/api/marketops/procurement")
@PreAuthorize("hasAuthority('ROLE_XAMOPS') or hasAuthority('ROLE_SUPER_ADMIN')")
@Slf4j
public class MarketplaceProcurementController {

    @Value("${marketplace.procurement.service.url:http://localhost:5003}")
    private String procurementServiceUrl;

    private final CloudAccountRepository cloudAccountRepository;
    private final RestTemplate restTemplate;

    public MarketplaceProcurementController(
            CloudAccountRepository cloudAccountRepository,
            @Qualifier("internalServiceRestTemplate") RestTemplate restTemplate) {
        this.cloudAccountRepository = cloudAccountRepository;
        // Injected with connect/read timeouts. A bare `new RestTemplate()` has none, so an
        // unresponsive procurement sidecar would hold request threads open indefinitely.
        this.restTemplate = restTemplate;
    }

    /**
     * Generic proxy: injects roleArn + externalId into the request body and
     * forwards the entire payload to the Python service.
     *
     * POST /api/marketops/procurement/catalog/search?cloudAccountId=xxx
     * POST /api/marketops/procurement/agreements/list?cloudAccountId=xxx
     * POST /api/marketops/procurement/agreements/describe?cloudAccountId=xxx
     * POST /api/marketops/procurement/agreements/terms?cloudAccountId=xxx
     * POST /api/marketops/procurement/agreements/invoices?cloudAccountId=xxx
     * POST /api/marketops/procurement/agreements/payments?cloudAccountId=xxx
     * POST /api/marketops/procurement/agreements/cancel?cloudAccountId=xxx
     * POST /api/marketops/procurement/agreements/cancel/get?cloudAccountId=xxx
     * POST /api/marketops/procurement/agreements/cancel/undo?cloudAccountId=xxx
     */
    @PostMapping("/**")
    public ResponseEntity<Object> proxy(
            @RequestParam String cloudAccountId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {

        CloudAccount account = cloudAccountRepository.findByProviderAccountId(cloudAccountId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No cloud account found for ID: " + cloudAccountId));

        if (account.getRoleArn() == null || account.getRoleArn().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No IAM role configured for account " + cloudAccountId));
        }

        // Build enriched body with credentials
        Map<String, Object> enrichedBody = new HashMap<>(body != null ? body : Map.of());
        enrichedBody.put("roleArn",    account.getRoleArn());
        enrichedBody.put("cloudAccountId", cloudAccountId);
        if (account.getExternalId() != null && !account.getExternalId().isBlank()) {
            enrichedBody.put("externalId", account.getExternalId());
        }

        // Extract the sub-path after /api/marketops/procurement
        String contextPath    = request.getContextPath();
        String requestUri     = request.getRequestURI();
        String prefix         = contextPath + "/api/marketops/procurement";
        String subPath        = requestUri.startsWith(prefix)
                ? requestUri.substring(prefix.length())
                : requestUri;

        String targetUrl = procurementServiceUrl + "/procurement" + subPath;
        log.info("Proxying procurement request: {} → {}", requestUri, targetUrl);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(enrichedBody, headers);

        try {
            ResponseEntity<Object> response = restTemplate.exchange(
                    targetUrl, HttpMethod.POST, entity, Object.class);
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (HttpStatusCodeException e) {
            log.warn("Procurement service returned {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Procurement service unreachable at {}: {}", targetUrl, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error",
                            "Marketplace procurement service is unavailable. " +
                            "Ensure the Python service is running on port 5003.",
                            "detail", e.getMessage()));
        }
    }
}
