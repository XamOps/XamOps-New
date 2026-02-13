package com.xammer.cloud.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Test controller to verify CloudiDR API connectivity
 */
@RestController
@RequestMapping("/api/test")
public class CloudiDRTestController {

    private static final Logger logger = LoggerFactory.getLogger(CloudiDRTestController.class);
    private final RestTemplate restTemplate;

    public CloudiDRTestController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/cloudidr-connectivity")
    public ResponseEntity<?> testCloudiDRConnectivity() {
        Map<String, Object> result = new HashMap<>();

        try {
            logger.info("Testing CloudiDR API connectivity...");
            long startTime = System.currentTimeMillis();

            // Simple HEAD request to test connectivity
            String url = "https://public-api.cloudidr.com/external/api/v1/sign-in";

            try {
                restTemplate.headForHeaders(url);
                long duration = System.currentTimeMillis() - startTime;

                result.put("success", true);
                result.put("message", "CloudiDR API is reachable");
                result.put("url", url);
                result.put("responseTimeMs", duration);
                logger.info("CloudiDR API connectivity test SUCCESS ({} ms)", duration);

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                result.put("success", false);
                result.put("message", "Failed to reach CloudiDR API");
                result.put("url", url);
                result.put("error", e.getMessage());
                result.put("errorType", e.getClass().getSimpleName());
                result.put("timeoutAfterMs", duration);
                logger.error("CloudiDR API connectivity test FAILED after {} ms: {}", duration, e.getMessage());
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Test failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }
}
