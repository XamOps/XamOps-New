package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.LlmBudget;
import com.xammer.cloud.domain.LlmProviderConfig;
import com.xammer.cloud.domain.LlmUsageLog;
import com.xammer.cloud.repository.LlmBudgetRepository;
import com.xammer.cloud.repository.LlmProviderConfigRepository;
import com.xammer.cloud.repository.LlmUsageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LlmProxyService {

    private static final Logger logger = LoggerFactory.getLogger(LlmProxyService.class);

    private final LlmProviderConfigRepository configRepo;
    private final LlmUsageLogRepository usageLogRepo;
    private final LlmBudgetRepository budgetRepo;
    private final com.xammer.cloud.repository.LlmMasterKeyRepository masterKeyRepo;
    private final com.xammer.cloud.repository.LlmKeyGroupRepository keyGroupRepo;
    private final EncryptionService encryptionService;
    private final KeyPoolService keyPoolService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${litellm.base-url:http://localhost:4000}")
    private String litellmBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${litellm.master-key:sk-xamops-master}")
    private String litellmMasterKey;

    // Budget spend cache: key = "clientId:providerConfigId", value = [spend, timestampMs]
    private final ConcurrentHashMap<String, double[]> spendCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 60_000;

    // Model pricing per million tokens (input, output) in USD
    private static final Map<String, double[]> MODEL_PRICING = new HashMap<>();

    static {
        // OpenAI models
        MODEL_PRICING.put("gpt-4o", new double[]{2.50, 10.00});
        MODEL_PRICING.put("gpt-4o-mini", new double[]{0.15, 0.60});
        MODEL_PRICING.put("gpt-4-turbo", new double[]{10.00, 30.00});
        MODEL_PRICING.put("gpt-4", new double[]{30.00, 60.00});
        MODEL_PRICING.put("gpt-3.5-turbo", new double[]{0.50, 1.50});
        MODEL_PRICING.put("o1", new double[]{15.00, 60.00});
        MODEL_PRICING.put("o1-mini", new double[]{3.00, 12.00});
        MODEL_PRICING.put("o3-mini", new double[]{1.10, 4.40});

        // Anthropic models
        MODEL_PRICING.put("claude-opus-4-5-20250929", new double[]{15.00, 75.00});
        MODEL_PRICING.put("claude-sonnet-4-5-20250929", new double[]{3.00, 15.00});
        MODEL_PRICING.put("claude-3-5-sonnet-20241022", new double[]{3.00, 15.00});
        MODEL_PRICING.put("claude-3-5-haiku-20241022", new double[]{0.80, 4.00});
        MODEL_PRICING.put("claude-3-haiku-20240307", new double[]{0.25, 1.25});

        // Google Gemini models
        MODEL_PRICING.put("gemini-1.5-pro", new double[]{3.50, 10.50});
        MODEL_PRICING.put("gemini-1.5-flash", new double[]{0.075, 0.30});
        MODEL_PRICING.put("gemini-2.0-flash", new double[]{0.10, 0.40});
        MODEL_PRICING.put("gemini-pro", new double[]{0.50, 1.50});

        // AWS Bedrock — Claude models via Bedrock
        MODEL_PRICING.put("anthropic.claude-3-haiku-20240307-v1:0", new double[]{0.25, 1.25});
        MODEL_PRICING.put("anthropic.claude-3-5-haiku-20241022-v1:0", new double[]{0.80, 4.00});
        MODEL_PRICING.put("anthropic.claude-3-5-sonnet-20241022-v2:0", new double[]{3.00, 15.00});
        MODEL_PRICING.put("anthropic.claude-3-sonnet-20240229-v1:0", new double[]{3.00, 15.00});
        MODEL_PRICING.put("anthropic.claude-3-opus-20240229-v1:0", new double[]{15.00, 75.00});
        MODEL_PRICING.put("amazon.titan-text-express-v1", new double[]{0.20, 0.60});
        MODEL_PRICING.put("amazon.titan-text-lite-v1", new double[]{0.15, 0.20});
        MODEL_PRICING.put("meta.llama3-8b-instruct-v1:0", new double[]{0.30, 0.60});
        MODEL_PRICING.put("meta.llama3-70b-instruct-v1:0", new double[]{2.65, 3.50});
        MODEL_PRICING.put("mistral.mistral-7b-instruct-v0:2", new double[]{0.15, 0.20});
        MODEL_PRICING.put("mistral.mixtral-8x7b-instruct-v0:1", new double[]{0.45, 0.70});
    }

    public LlmProxyService(
            LlmProviderConfigRepository configRepo,
            LlmUsageLogRepository usageLogRepo,
            LlmBudgetRepository budgetRepo,
            com.xammer.cloud.repository.LlmMasterKeyRepository masterKeyRepo,
            com.xammer.cloud.repository.LlmKeyGroupRepository keyGroupRepo,
            EncryptionService encryptionService,
            KeyPoolService keyPoolService,
            @org.springframework.beans.factory.annotation.Qualifier("llmRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.configRepo = configRepo;
        this.usageLogRepo = usageLogRepo;
        this.budgetRepo = budgetRepo;
        this.masterKeyRepo = masterKeyRepo;
        this.keyGroupRepo = keyGroupRepo;
        this.encryptionService = encryptionService;
        this.keyPoolService = keyPoolService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    // ─── Main proxy entry point ───────────────────────────────────

    public ResponseEntity<String> proxyRequest(
            String xamopsKey,
            String subPath,
            HttpMethod method,
            HttpHeaders incomingHeaders,
            String body,
            String agentName) {

        // Master key path — one key routes to any provider based on model name in body
        com.xammer.cloud.domain.LlmMasterKey masterKey = masterKeyRepo.findByMasterKey(xamopsKey).orElse(null);
        if (masterKey != null) {
            return handleMasterKeyRequest(masterKey, subPath, method, incomingHeaders, body, agentName);
        }

        // Key group path — routes to providers in the group based on model name in body
        com.xammer.cloud.domain.LlmKeyGroup keyGroup = keyGroupRepo.findByGroupKey(xamopsKey).orElse(null);
        if (keyGroup != null) {
            return handleKeyGroupRequest(keyGroup, subPath, method, incomingHeaders, body, agentName);
        }

        // Per-provider key path — routes to a specific provider config
        LlmProviderConfig config = configRepo.findByProxyEndpointId(xamopsKey)
                .orElseThrow(() -> new RuntimeException(
                        "Invalid X-XamOps-Key. Get your key from XamOps AIOps → AI Gateway tab."));

        if (!"ACTIVE".equals(config.getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("{\"error\":\"Provider configuration is inactive\"}");
        }

        if ((agentName == null || agentName.isBlank()) && config.getAssignedTeam() != null) {
            agentName = config.getAssignedTeam();
        }

        ResponseEntity<String> budgetBlock = checkBudget(config);
        if (budgetBlock != null) return budgetBlock;

        return forwardViaLiteLLM(config, subPath, method, incomingHeaders, body, agentName, true);
    }

    // ─── Master key: model name in body determines provider ───────

    private ResponseEntity<String> handleMasterKeyRequest(
            com.xammer.cloud.domain.LlmMasterKey masterKeyEntity,
            String subPath,
            HttpMethod method,
            HttpHeaders incomingHeaders,
            String body,
            String agentName) {

        Long clientId = masterKeyEntity.getClient().getId();

        // Extract model name from request body to find matching provider
        String modelName = extractModelFromBody(body);
        if (modelName == null || modelName.isBlank()) {
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"Request body must include a 'model' field (e.g. 'gpt-4o', 'claude-sonnet-4-5-20250929').\"}");
        }

        // Find active provider that supports this model
        String providerForModel = detectProvider(modelName);
        if (providerForModel == null) {
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"Unknown model: '" + modelName + "'. Supported models: gpt-4o, claude-sonnet-4-5-20250929, gemini-2.0-flash, etc.\"}");
        }

        List<LlmProviderConfig> configs = configRepo.findByClientIdOrderByCreatedAtDesc(clientId);
        LlmProviderConfig config = configs.stream()
                .filter(c -> "ACTIVE".equals(c.getStatus())
                        && c.getProvider().equalsIgnoreCase(providerForModel))
                .findFirst()
                .orElse(null);

        if (config == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("{\"error\":\"No active " + providerForModel + " provider connected. Add one in AIOps → API Keys tab.\"}");
        }

        ResponseEntity<String> budgetBlock = checkBudget(config);
        if (budgetBlock != null) return budgetBlock;

        // Route directly to the provider — same as the group key path.
        // LiteLLM sidecar is optional and may not have the correct model config in sync.
        if ("BEDROCK".equals(providerForModel)) {
            return forwardBedrockRequest(config, subPath, method, body, agentName);
        }
        String apiKey = null;
        if (config.getApiKeyEncrypted() != null && !config.getApiKeyEncrypted().isBlank()) {
            try { apiKey = encryptionService.decrypt(config.getApiKeyEncrypted()); }
            catch (Exception ex) { logger.warn("Failed to decrypt master key for {}: {}", providerForModel, ex.getMessage()); }
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = extractBearerToken(incomingHeaders);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"No API key available for " + providerForModel + ". Add or rotate the key in AIOps → AI Gateway.\"}");
        }
        String targetUrl = buildTargetUrl(providerForModel, subPath);
        String translatedBody = translateBodyForProvider(providerForModel, body);
        HttpHeaders directHeaders = buildProviderHeaders(providerForModel, apiKey, incomingHeaders);
        long startTime = System.currentTimeMillis();
        try {
            HttpEntity<String> entity = new HttpEntity<>(translatedBody, directHeaders);
            ResponseEntity<String> resp = restTemplate.exchange(targetUrl, method, entity, String.class);
            long latency = System.currentTimeMillis() - startTime;
            logUsageAsync(config, subPath, body, resp.getBody(), resp.getStatusCodeValue(), latency, false, null, agentName);
            return ResponseEntity.status(resp.getStatusCode())
                    .headers(filterResponseHeaders(resp.getHeaders()))
                    .body(resp.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException ex) {
            long latency = System.currentTimeMillis() - startTime;
            logUsageAsync(config, subPath, body, ex.getResponseBodyAsString(), ex.getStatusCode().value(), latency, true, ex.getResponseBodyAsString(), agentName);
            return ResponseEntity.status(ex.getStatusCode())
                    .headers(filterResponseHeaders(ex.getResponseHeaders()))
                    .body(ex.getResponseBodyAsString());
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - startTime;
            logUsageAsync(config, subPath, body, null, 502, latency, true, ex.getMessage(), agentName);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"Failed to reach " + providerForModel + ": " + ex.getMessage() + "\"}");
        }
    }

    // ─── Key group: routes only within the group's providers ─────

    private ResponseEntity<String> handleKeyGroupRequest(
            com.xammer.cloud.domain.LlmKeyGroup keyGroup,
            String subPath,
            HttpMethod method,
            HttpHeaders incomingHeaders,
            String body,
            String agentName) {

        String modelName = extractModelFromBody(body);
        if (modelName == null || modelName.isBlank()) {
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"Request body must include a 'model' field.\"}");
        }

        String providerForModel = detectProvider(modelName);
        if (providerForModel == null) {
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"Unknown model: '" + modelName + "'.\"}");
        }

        // Only route to providers that are in this group
        String finalProvider = providerForModel;
        LlmProviderConfig config = keyGroup.getProviders().stream()
                .filter(p -> "ACTIVE".equals(p.getStatus())
                        && p.getProvider().equalsIgnoreCase(finalProvider))
                .findFirst()
                .orElse(null);

        if (config == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("{\"error\":\"No active " + providerForModel + " provider in this key group. "
                            + "Add it to the group in AIOps → AI Gateway → Key Groups.\"}");
        }

        ResponseEntity<String> budgetBlock = checkBudget(config);
        if (budgetBlock != null) return budgetBlock;

        // Try group key pool first (load balancing path) — route directly to provider
        KeyPoolService.SelectedKey selectedKey = keyPoolService.getNextKey(keyGroup, providerForModel);
        if (selectedKey != null) {
            if ("BEDROCK".equals(providerForModel)) {
                return forwardBedrockRequest(config, subPath, method, body, agentName);
            }
            long startTime = System.currentTimeMillis();
            String targetUrl = buildTargetUrl(providerForModel, subPath);
            String translatedBody = translateBodyForProvider(providerForModel, body);
            HttpHeaders directHeaders = buildProviderHeaders(providerForModel, selectedKey.decryptedKey(), incomingHeaders);
            try {
                HttpEntity<String> entity = new HttpEntity<>(translatedBody, directHeaders);
                ResponseEntity<String> resp = restTemplate.exchange(targetUrl, method, entity, String.class);
                long latency = System.currentTimeMillis() - startTime;
                keyPoolService.markSuccess(selectedKey.keyId());
                logUsageAsync(config, subPath, body, resp.getBody(), resp.getStatusCodeValue(), latency, false, null, agentName);
                return ResponseEntity.status(resp.getStatusCode())
                        .headers(filterResponseHeaders(resp.getHeaders()))
                        .body(resp.getBody());
            } catch (HttpStatusCodeException ex) {
                long latency = System.currentTimeMillis() - startTime;
                if (ex.getStatusCode().value() == 429) keyPoolService.markRateLimited(selectedKey.keyId(), 60);
                logUsageAsync(config, subPath, body, ex.getResponseBodyAsString(), ex.getStatusCode().value(), latency, true, ex.getResponseBodyAsString(), agentName);
                return ResponseEntity.status(ex.getStatusCode())
                        .headers(filterResponseHeaders(ex.getResponseHeaders()))
                        .body(ex.getResponseBodyAsString());
            } catch (Exception ex) {
                long latency = System.currentTimeMillis() - startTime;
                logUsageAsync(config, subPath, body, null, 502, latency, true, ex.getMessage(), agentName);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body("{\"error\":\"Failed to reach " + providerForModel + ": " + ex.getMessage() + "\"}");
            }
        }

        // No pool key — resolve the API key, then route directly (bypass LiteLLM entirely)
        String resolvedKey = null;

        // 1. Try the encrypted key stored on the provider config (added via Rotate Key)
        if (config.getApiKeyEncrypted() != null && !config.getApiKeyEncrypted().isBlank()) {
            try {
                resolvedKey = encryptionService.decrypt(config.getApiKeyEncrypted());
            } catch (Exception decEx) {
                logger.warn("Failed to decrypt provider API key for group route ({}): {}", providerForModel, decEx.getMessage());
            }
        }

        // 2. Fall back to caller's Bearer token
        if (resolvedKey == null || resolvedKey.isBlank()) {
            resolvedKey = extractBearerToken(incomingHeaders);
        }

        if (resolvedKey == null || resolvedKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"No API key available for this group. Rotate a key for the provider in AIOps → AI Gateway → API Keys tab.\"}");
        }

        // Route directly to the provider — skip LiteLLM
        if ("BEDROCK".equals(providerForModel)) {
            return forwardBedrockRequest(config, subPath, method, body, agentName);
        }

        long startTime = System.currentTimeMillis();
        String targetUrl = buildTargetUrl(providerForModel, subPath);
        String translatedBody = translateBodyForProvider(providerForModel, body);
        HttpHeaders directHeaders = buildProviderHeaders(providerForModel, resolvedKey, incomingHeaders);
        try {
            HttpEntity<String> entity = new HttpEntity<>(translatedBody, directHeaders);
            ResponseEntity<String> resp = restTemplate.exchange(targetUrl, method, entity, String.class);
            long latency = System.currentTimeMillis() - startTime;
            logUsageAsync(config, subPath, body, resp.getBody(), resp.getStatusCodeValue(), latency, false, null, agentName);
            return ResponseEntity.status(resp.getStatusCode())
                    .headers(filterResponseHeaders(resp.getHeaders()))
                    .body(resp.getBody());
        } catch (HttpStatusCodeException ex) {
            long latency = System.currentTimeMillis() - startTime;
            logUsageAsync(config, subPath, body, ex.getResponseBodyAsString(), ex.getStatusCode().value(), latency, true, ex.getResponseBodyAsString(), agentName);
            return ResponseEntity.status(ex.getStatusCode())
                    .headers(filterResponseHeaders(ex.getResponseHeaders()))
                    .body(ex.getResponseBodyAsString());
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - startTime;
            logUsageAsync(config, subPath, body, null, 502, latency, true, ex.getMessage(), agentName);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"Failed to reach " + providerForModel + ": " + ex.getMessage() + "\"}");
        }
    }

    private String detectProvider(String modelName) {
        String m = modelName.toLowerCase();
        if (m.startsWith("gpt-") || m.startsWith("o1") || m.startsWith("o3")) return "OPENAI";
        if (m.startsWith("claude-")) return "ANTHROPIC";
        if (m.startsWith("gemini-")) return "GEMINI";
        if (m.startsWith("anthropic.") || m.startsWith("amazon.") || m.startsWith("meta.")) return "BEDROCK";
        return null;
    }

    private String extractModelFromBody(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            return node.has("model") ? node.get("model").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ─── LiteLLM forwarding ───────────────────────────────────────

    private ResponseEntity<String> forwardViaLiteLLM(
            LlmProviderConfig config,
            String subPath,
            HttpMethod method,
            HttpHeaders incomingHeaders,
            String body,
            String agentName,
            boolean rewriteModel) {

        // For per-provider key: rewrite model to first real model for that provider
        // For master key: body already has the real model name LiteLLM recognises
        String outBody = body;
        if (rewriteModel) {
            String firstModel = getFirstModelForProvider(config.getProvider().toUpperCase());
            if (firstModel != null) outBody = rewriteModelInBody(body, firstModel);
        }

        // LiteLLM exposes an OpenAI-compatible endpoint
        String targetUrl = litellmBaseUrl + "/v1/" + (subPath != null && !subPath.isBlank() ? subPath : "chat/completions");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(litellmMasterKey);
        if (agentName != null && !agentName.isBlank()) {
            headers.set("X-XamOps-Agent", agentName);
        }

        long startTime = System.currentTimeMillis();

        try {
            HttpEntity<String> entity = new HttpEntity<>(outBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(targetUrl, method, entity, String.class);
            long latency = System.currentTimeMillis() - startTime;

            logUsageAsync(config, subPath, body, response.getBody(),
                    response.getStatusCodeValue(), latency, false, null, agentName);

            return ResponseEntity.status(response.getStatusCode())
                    .headers(filterResponseHeaders(response.getHeaders()))
                    .body(response.getBody());

        } catch (HttpStatusCodeException e) {
            long latency = System.currentTimeMillis() - startTime;
            String errorBody = e.getResponseBodyAsString();
            logUsageAsync(config, subPath, body, errorBody,
                    e.getStatusCode().value(), latency, true, errorBody, agentName);

            return ResponseEntity.status(e.getStatusCode())
                    .headers(filterResponseHeaders(e.getResponseHeaders()))
                    .body(errorBody);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            logger.warn("LiteLLM unreachable ({}), falling back to direct proxy", e.getMessage());

            // Fallback: route directly if LiteLLM is down
            String provider = config.getProvider().toUpperCase();
            if ("BEDROCK".equals(provider)) {
                return forwardBedrockRequest(config, subPath, method, body, agentName);
            }

            String apiKey;
            Long usedKeyId = null;

            // 1. Try the encrypted key stored on the provider config (added via Rotate Key)
            if (config.getApiKeyEncrypted() != null && !config.getApiKeyEncrypted().isBlank()) {
                try {
                    apiKey = encryptionService.decrypt(config.getApiKeyEncrypted());
                } catch (Exception decEx) {
                    logger.warn("Failed to decrypt provider API key for {}: {}", provider, decEx.getMessage());
                    apiKey = null;
                }
            } else {
                apiKey = null;
            }

            // 2. Fall back to bearer token passed by caller
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = extractBearerToken(incomingHeaders);
            }

            if (apiKey == null || apiKey.isBlank()) {
                logUsageAsync(config, subPath, body, null, 502, latency, true, e.getMessage(), agentName);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body("{\"error\":\"No API key available. Add keys via AIOps → AI Gateway → API Keys tab, or pass Authorization: Bearer YOUR_KEY\"}");
            }

            String targetUrl2 = buildTargetUrl(provider, subPath);
            HttpHeaders fallbackHeaders = buildProviderHeaders(provider, apiKey, incomingHeaders);
            // Translate OpenAI-format body to provider-native format if needed
            String fallbackBody = translateBodyForProvider(provider, body);
            try {
                HttpEntity<String> fallbackEntity = new HttpEntity<>(fallbackBody, fallbackHeaders);
                ResponseEntity<String> fallbackResp = restTemplate.exchange(targetUrl2, method, fallbackEntity, String.class);
                long lat2 = System.currentTimeMillis() - startTime;
                if (usedKeyId != null) keyPoolService.markSuccess(usedKeyId);
                logUsageAsync(config, subPath, body, fallbackResp.getBody(),
                        fallbackResp.getStatusCodeValue(), lat2, false, null, agentName);
                return ResponseEntity.status(fallbackResp.getStatusCode())
                        .headers(filterResponseHeaders(fallbackResp.getHeaders()))
                        .body(fallbackResp.getBody());
            } catch (HttpStatusCodeException ex) {
                long lat2 = System.currentTimeMillis() - startTime;
                if (usedKeyId != null && ex.getStatusCode().value() == 429) {
                    keyPoolService.markRateLimited(usedKeyId, 60);
                }
                logUsageAsync(config, subPath, body, ex.getResponseBodyAsString(),
                        ex.getStatusCode().value(), lat2, true, ex.getResponseBodyAsString(), agentName);
                return ResponseEntity.status(ex.getStatusCode())
                        .headers(filterResponseHeaders(ex.getResponseHeaders()))
                        .body(ex.getResponseBodyAsString());
            } catch (Exception ex) {
                long lat2 = System.currentTimeMillis() - startTime;
                logUsageAsync(config, subPath, body, null, 502, lat2, true, ex.getMessage(), agentName);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body("{\"error\":\"Failed to reach AI provider: " + ex.getMessage() + "\"}");
            }
        }
    }

    private String rewriteModelInBody(String body, String modelAlias) {
        if (body == null || body.isBlank()) return body;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(body);
            node.put("model", modelAlias);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return body;
        }
    }

    private String translateBodyForProvider(String provider, String body) {
        if (body == null || body.isBlank()) return body;
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(body);
            if ("ANTHROPIC".equals(provider)) {
                // Anthropic requires max_tokens; add default if missing
                if (!node.has("max_tokens")) {
                    node.put("max_tokens", 1024);
                }
                // Remove fields Anthropic doesn't accept
                node.remove("stream");
            } else if ("GEMINI".equals(provider)) {
                // Gemini uses a completely different body format
                String content = node.has("messages")
                        ? node.get("messages").get(node.get("messages").size() - 1).get("content").asText()
                        : "Hello";
                return "{\"contents\":[{\"parts\":[{\"text\":\"" + content.replace("\"", "\\\"") + "\"}]}]}";
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return body;
        }
    }

    private static final Map<String, String> PROVIDER_DEFAULT_MODEL = new HashMap<>();
    static {
        PROVIDER_DEFAULT_MODEL.put("OPENAI",    "gpt-4o");
        PROVIDER_DEFAULT_MODEL.put("ANTHROPIC", "claude-sonnet-4-5-20250929");
        PROVIDER_DEFAULT_MODEL.put("GEMINI",    "gemini-2.0-flash");
        PROVIDER_DEFAULT_MODEL.put("BEDROCK",   "anthropic.claude-3-5-sonnet-20241022-v2:0");
    }

    private String getFirstModelForProvider(String provider) {
        return PROVIDER_DEFAULT_MODEL.get(provider);
    }

    @Async
    public void logUsageAsync(LlmProviderConfig config, String path, String requestBody,
                              String responseBody, int statusCode, long latencyMs,
                              boolean isError, String errorMessage, String agentName) {
        try {
            String model = extractModel(config.getProvider(), requestBody, responseBody);
            int inputTokens = 0;
            int outputTokens = 0;

            if (responseBody != null && !isError) {
                int[] tokens = extractTokens(config.getProvider(), responseBody);
                inputTokens = tokens[0];
                outputTokens = tokens[1];
            }

            double cost = calculateCost(model, inputTokens, outputTokens);

            LlmUsageLog log = new LlmUsageLog();
            log.setClientId(config.getClient().getId());
            log.setProviderConfigId(config.getId());
            log.setProvider(config.getProvider());
            log.setModel(model != null ? model : "unknown");
            log.setInputTokens(inputTokens);
            log.setOutputTokens(outputTokens);
            log.setTotalTokens(inputTokens + outputTokens);
            log.setEstimatedCost(cost);
            log.setLatencyMs(latencyMs);
            log.setStatusCode(statusCode);
            log.setIsError(isError);
            log.setErrorMessage(errorMessage);
            log.setRequestPath(path);
            log.setAgentName(agentName);
            log.setTimestamp(Instant.now());

            usageLogRepo.save(log);

            // Invalidate budget spend cache after logging
            String cacheKey = config.getClient().getId() + ":" + config.getId();
            spendCache.remove(cacheKey);
            spendCache.remove(config.getClient().getId() + ":global");
        } catch (Exception e) {
            logger.error("Failed to log LLM usage: {}", e.getMessage());
        }
    }

    // ─── Budget enforcement ─────────────────────────────────────

    private ResponseEntity<String> checkBudget(LlmProviderConfig config) {
        Long clientId = config.getClient().getId();
        Long providerConfigId = config.getId();

        List<LlmBudget> budgets = budgetRepo.findActiveBudgetsForProvider(clientId, providerConfigId);
        if (budgets.isEmpty()) return null;

        LlmBudget budget = budgets.get(0);
        if (!Boolean.TRUE.equals(budget.getAutoDisable())) return null;

        String cacheKey = clientId + ":" +
                (budget.getProviderConfigId() != null ? budget.getProviderConfigId() : "global");
        double[] cached = spendCache.get(cacheKey);
        double currentSpend;

        if (cached != null && (System.currentTimeMillis() - cached[1]) < CACHE_TTL_MS) {
            currentSpend = cached[0];
        } else {
            Instant monthStart = LocalDate.now().withDayOfMonth(1)
                    .atStartOfDay().toInstant(ZoneOffset.UTC);

            if (budget.getProviderConfigId() == null) {
                currentSpend = usageLogRepo.sumCostSince(clientId, monthStart);
            } else {
                currentSpend = usageLogRepo.sumCostByProviderConfigSince(
                        clientId, budget.getProviderConfigId(), monthStart);
            }
            spendCache.put(cacheKey, new double[]{currentSpend, System.currentTimeMillis()});
        }

        if (currentSpend >= budget.getMonthlyLimitUsd()) {
            logger.warn("Budget exceeded for client {} provider {}: ${} >= ${}",
                    clientId, providerConfigId, currentSpend, budget.getMonthlyLimitUsd());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("{\"error\":\"Monthly budget limit exceeded. Current spend: $"
                            + String.format("%.2f", currentSpend)
                            + ", Budget: $" + String.format("%.2f", budget.getMonthlyLimitUsd())
                            + ". Contact your administrator or increase the budget in XamOps AIOps.\"}");
        }

        return null;
    }

    // ─── Extract API key from unified Authorization: Bearer header ──

    private String extractBearerToken(HttpHeaders headers) {
        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.toLowerCase().startsWith("bearer ")) {
            return auth.substring(7).trim();
        }
        return null;
    }

    // ─── Bedrock: SigV4-signed forwarding ────────────────────────

    private ResponseEntity<String> forwardBedrockRequest(
            LlmProviderConfig config, String subPath,
            HttpMethod method, String body, String agentName) {

        String region = config.getAwsRegion();
        if (region == null || region.isBlank()) {
            return ResponseEntity.badRequest()
                    .body("{\"error\":\"Bedrock provider is missing AWS region configuration.\"}");
        }

        String accessKey = null;
        String secretKey = null;
        try {
            if (config.getAwsAccessKeyEncrypted() != null && !config.getAwsAccessKeyEncrypted().isBlank()) {
                accessKey = encryptionService.decrypt(config.getAwsAccessKeyEncrypted());
            }
            if (config.getAwsSecretKeyEncrypted() != null && !config.getAwsSecretKeyEncrypted().isBlank()) {
                secretKey = encryptionService.decrypt(config.getAwsSecretKeyEncrypted());
            }
        } catch (Exception e) {
            logger.error("Failed to decrypt Bedrock credentials: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"Failed to decrypt Bedrock credentials.\"}");
        }

        if (accessKey == null || secretKey == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("{\"error\":\"Bedrock provider credentials not configured. Add your AWS Access Key and Secret Key via API Keys → Rotate Key.\"}");
        }

        String targetUrl = buildBedrockUrl(region, subPath);
        long startTime = System.currentTimeMillis();

        try {
            // Build the unsigned SDK request
            byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            SdkHttpFullRequest.Builder reqBuilder = SdkHttpFullRequest.builder()
                    .method(SdkHttpMethod.fromValue(method.name()))
                    .uri(URI.create(targetUrl))
                    .putHeader("Content-Type", "application/json")
                    .putHeader("Accept", "application/json")
                    .contentStreamProvider(() -> new ByteArrayInputStream(bodyBytes));

            SdkHttpFullRequest unsignedRequest = reqBuilder.build();

            // Sign with SigV4
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
            Aws4Signer signer = Aws4Signer.create();
            Aws4SignerParams signerParams = Aws4SignerParams.builder()
                    .signingRegion(Region.of(region))
                    .signingName("bedrock")
                    .awsCredentials(credentials)
                    .build();

            SdkHttpFullRequest signedRequest = signer.sign(unsignedRequest, signerParams);

            // Copy signed headers to Spring HttpHeaders
            HttpHeaders signedHeaders = new HttpHeaders();
            signedRequest.headers().forEach((k, v) -> v.forEach(val -> signedHeaders.add(k, val)));
            signedHeaders.setContentType(MediaType.APPLICATION_JSON);
            signedHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            HttpEntity<String> entity = new HttpEntity<>(body, signedHeaders);
            ResponseEntity<String> response = restTemplate.exchange(targetUrl, method, entity, String.class);

            long latency = System.currentTimeMillis() - startTime;
            logUsageAsync(config, subPath, body, response.getBody(),
                    response.getStatusCodeValue(), latency, false, null, agentName);

            return ResponseEntity.status(response.getStatusCode())
                    .headers(filterResponseHeaders(response.getHeaders()))
                    .body(response.getBody());

        } catch (HttpStatusCodeException e) {
            long latency = System.currentTimeMillis() - startTime;
            logUsageAsync(config, subPath, body, e.getResponseBodyAsString(),
                    e.getStatusCode().value(), latency, true, e.getResponseBodyAsString(), agentName);
            return ResponseEntity.status(e.getStatusCode())
                    .headers(filterResponseHeaders(e.getResponseHeaders()))
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            logger.error("Bedrock proxy request failed: {}", e.getMessage());
            logUsageAsync(config, subPath, body, null, 500, latency, true, e.getMessage(), agentName);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"Failed to reach Bedrock: " + e.getMessage() + "\"}");
        }
    }

    // ─── Provider-specific URL building ──────────────────────────

    private String buildTargetUrl(String provider, String subPath) {
        // Always use the provider's native endpoint — ignore subPath since each
        // provider has a different API structure (OpenAI vs Anthropic vs Gemini).
        switch (provider) {
            case "OPENAI":
                return "https://api.openai.com/v1/chat/completions";
            case "ANTHROPIC":
                return "https://api.anthropic.com/v1/messages";
            case "GEMINI":
                return "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";
            default:
                throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
    }

    private String buildBedrockUrl(String region, String subPath) {
        String path = (subPath != null && !subPath.isEmpty()) ? "/" + subPath : "";
        return "https://bedrock-runtime." + region + ".amazonaws.com" + path;
    }

    private HttpHeaders buildProviderHeaders(String provider, String apiKey, HttpHeaders incoming) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        switch (provider) {
            case "OPENAI":
                headers.setBearerAuth(apiKey);
                break;
            case "ANTHROPIC":
                headers.set("x-api-key", apiKey);
                // Forward anthropic-version from user's request, default to 2023-06-01
                String anthropicVersion = incoming.getFirst("anthropic-version");
                headers.set("anthropic-version", anthropicVersion != null ? anthropicVersion : "2023-06-01");
                // Forward anthropic-beta if present
                String anthropicBeta = incoming.getFirst("anthropic-beta");
                if (anthropicBeta != null) {
                    headers.set("anthropic-beta", anthropicBeta);
                }
                break;
            case "GEMINI":
                headers.set("x-goog-api-key", apiKey);
                break;
        }

        // Forward Accept header if present
        if (incoming.containsKey(HttpHeaders.ACCEPT)) {
            headers.setAccept(incoming.getAccept());
        }

        return headers;
    }

    private HttpHeaders filterResponseHeaders(HttpHeaders responseHeaders) {
        HttpHeaders filtered = new HttpHeaders();
        if (responseHeaders == null) return filtered;

        // Forward content type and relevant headers
        if (responseHeaders.getContentType() != null) {
            filtered.setContentType(responseHeaders.getContentType());
        }
        return filtered;
    }

    // ─── Token extraction (provider-specific) ────────────────────

    private String extractModel(String provider, String requestBody, String responseBody) {
        try {
            // Try from response first
            if (responseBody != null) {
                JsonNode resp = objectMapper.readTree(responseBody);
                if (resp.has("model")) return resp.get("model").asText();
            }
            // Fall back to request body
            if (requestBody != null) {
                JsonNode req = objectMapper.readTree(requestBody);
                if (req.has("model")) return req.get("model").asText();
            }
        } catch (Exception e) {
            logger.debug("Could not extract model name: {}", e.getMessage());
        }
        return null;
    }

    private int[] extractTokens(String provider, String responseBody) {
        try {
            JsonNode resp = objectMapper.readTree(responseBody);

            switch (provider.toUpperCase()) {
                case "OPENAI": {
                    JsonNode usage = resp.get("usage");
                    if (usage != null) {
                        int input = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0;
                        int output = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0;
                        return new int[]{input, output};
                    }
                    break;
                }
                case "ANTHROPIC": {
                    JsonNode usage = resp.get("usage");
                    if (usage != null) {
                        int input = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                        int output = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                        return new int[]{input, output};
                    }
                    break;
                }
                case "GEMINI": {
                    JsonNode meta = resp.get("usageMetadata");
                    if (meta != null) {
                        int input = meta.has("promptTokenCount") ? meta.get("promptTokenCount").asInt() : 0;
                        int output = meta.has("candidatesTokenCount") ? meta.get("candidatesTokenCount").asInt() : 0;
                        return new int[]{input, output};
                    }
                    break;
                }
                case "BEDROCK": {
                    // Claude on Bedrock uses same usage format as Anthropic
                    JsonNode usage = resp.get("usage");
                    if (usage != null) {
                        int input = usage.has("input_tokens") ? usage.get("input_tokens").asInt() : 0;
                        int output = usage.has("output_tokens") ? usage.get("output_tokens").asInt() : 0;
                        return new int[]{input, output};
                    }
                    // Amazon Titan / other Bedrock models
                    JsonNode inputTokenCount = resp.get("inputTextTokenCount");
                    JsonNode results = resp.get("results");
                    if (inputTokenCount != null && results != null && results.isArray() && results.size() > 0) {
                        int input = inputTokenCount.asInt();
                        int output = results.get(0).has("tokenCount") ? results.get(0).get("tokenCount").asInt() : 0;
                        return new int[]{input, output};
                    }
                    break;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract token counts: {}", e.getMessage());
        }
        return new int[]{0, 0};
    }

    private double calculateCost(String model, int inputTokens, int outputTokens) {
        if (model == null) return 0.0;

        // Try exact match first, then prefix match
        double[] pricing = MODEL_PRICING.get(model);
        if (pricing == null) {
            // Try prefix match for versioned model names
            for (Map.Entry<String, double[]> entry : MODEL_PRICING.entrySet()) {
                if (model.startsWith(entry.getKey())) {
                    pricing = entry.getValue();
                    break;
                }
            }
        }

        if (pricing == null) {
            logger.debug("No pricing found for model: {}", model);
            return 0.0;
        }

        // Pricing is per million tokens
        double inputCost = (inputTokens / 1_000_000.0) * pricing[0];
        double outputCost = (outputTokens / 1_000_000.0) * pricing[1];
        return Math.round((inputCost + outputCost) * 1_000_000.0) / 1_000_000.0;
    }
}
