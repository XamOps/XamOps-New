package com.xammer.cloud.controller;

import com.xammer.cloud.service.LlmProxyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/llm-proxy")
public class LlmProxyController {

    private static final Logger logger = LoggerFactory.getLogger(LlmProxyController.class);
    private static final String TRACKING_HEADER = "X-XamOps-Key";
    private static final String AGENT_HEADER = "X-XamOps-Agent";

    private final LlmProxyService proxyService;

    public LlmProxyController(LlmProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @RequestMapping(value = "/**",
                    method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<String> proxyRequest(
            HttpServletRequest request,
            @RequestBody(required = false) String body) {
        try {
            // Extract tracking key from header
            String trackingKey = request.getHeader(TRACKING_HEADER);
            if (trackingKey == null || trackingKey.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"Missing X-XamOps-Key header. Get your tracking key from the XamOps AIOps dashboard.\"}");
            }

            // Extract sub-path after /api/llm-proxy
            String fullPath = request.getRequestURI();
            String prefix = "/api/llm-proxy";
            String subPath = fullPath.length() > prefix.length()
                    ? fullPath.substring(prefix.length() + 1)
                    : "";

            // Extract optional agent name — header takes priority, falls back to key's assigned team
            String agentName = request.getHeader(AGENT_HEADER);

            HttpMethod method = HttpMethod.valueOf(request.getMethod());

            // Collect incoming headers
            HttpHeaders headers = new HttpHeaders();
            java.util.Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                headers.set(name, request.getHeader(name));
            }

            return proxyService.proxyRequest(trackingKey, subPath, method, headers, body, agentName);

        } catch (RuntimeException e) {
            logger.error("Proxy error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Proxy request failed. Check server logs for details.\"}");
        }
    }
}
