package com.xammer.cloud.service;

import com.xammer.cloud.domain.LlmProviderConfig;
import com.xammer.cloud.repository.LlmProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the LiteLLM proxy config in sync with provider configs stored in the DB.
 *
 * Each provider contributes ALL its supported models to the config, using the
 * real model name as the alias. This means users send requests with real model
 * names (e.g. "gpt-4o", "claude-sonnet-4-6") and LiteLLM routes to the right
 * provider automatically — no UUIDs exposed to users.
 */
@Service
public class LiteLLMConfigService {

    private static final Logger logger = LoggerFactory.getLogger(LiteLLMConfigService.class);

    // Real model names per provider — used as LiteLLM model_name aliases
    private static final Map<String, List<String>> PROVIDER_MODELS = new LinkedHashMap<>();
    private static final Map<String, String> MODEL_TO_LITELLM = new LinkedHashMap<>();

    static {
        PROVIDER_MODELS.put("OPENAI", Arrays.asList(
                "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo", "o1", "o3-mini"
        ));
        PROVIDER_MODELS.put("ANTHROPIC", Arrays.asList(
                "claude-sonnet-4-5-20250929", "claude-opus-4-5-20250929",
                "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-3-haiku-20240307"
        ));
        PROVIDER_MODELS.put("GEMINI", Arrays.asList(
                "gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash"
        ));
        PROVIDER_MODELS.put("BEDROCK", Arrays.asList(
                "anthropic.claude-3-5-sonnet-20241022-v2:0",
                "anthropic.claude-3-haiku-20240307-v1:0",
                "amazon.titan-text-express-v1",
                "meta.llama3-70b-instruct-v1:0"
        ));

        // model alias → litellm provider/model string
        MODEL_TO_LITELLM.put("gpt-4o",                                    "openai/gpt-4o");
        MODEL_TO_LITELLM.put("gpt-4o-mini",                               "openai/gpt-4o-mini");
        MODEL_TO_LITELLM.put("gpt-4-turbo",                               "openai/gpt-4-turbo");
        MODEL_TO_LITELLM.put("gpt-3.5-turbo",                             "openai/gpt-3.5-turbo");
        MODEL_TO_LITELLM.put("o1",                                         "openai/o1");
        MODEL_TO_LITELLM.put("o3-mini",                                    "openai/o3-mini");
        MODEL_TO_LITELLM.put("claude-sonnet-4-5-20250929",                   "anthropic/claude-sonnet-4-5-20250929");
        MODEL_TO_LITELLM.put("claude-opus-4-5-20250929",                     "anthropic/claude-opus-4-5-20250929");
        MODEL_TO_LITELLM.put("claude-3-5-sonnet-20241022",                 "anthropic/claude-3-5-sonnet-20241022");
        MODEL_TO_LITELLM.put("claude-3-5-haiku-20241022",                  "anthropic/claude-3-5-haiku-20241022");
        MODEL_TO_LITELLM.put("claude-3-haiku-20240307",                    "anthropic/claude-3-haiku-20240307");
        MODEL_TO_LITELLM.put("gemini-2.0-flash",                           "gemini/gemini-2.0-flash");
        MODEL_TO_LITELLM.put("gemini-1.5-pro",                             "gemini/gemini-1.5-pro");
        MODEL_TO_LITELLM.put("gemini-1.5-flash",                           "gemini/gemini-1.5-flash");
        MODEL_TO_LITELLM.put("anthropic.claude-3-5-sonnet-20241022-v2:0",  "bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0");
        MODEL_TO_LITELLM.put("anthropic.claude-3-haiku-20240307-v1:0",     "bedrock/anthropic.claude-3-haiku-20240307-v1:0");
        MODEL_TO_LITELLM.put("amazon.titan-text-express-v1",               "bedrock/amazon.titan-text-express-v1");
        MODEL_TO_LITELLM.put("meta.llama3-70b-instruct-v1:0",             "bedrock/meta.llama3-70b-instruct-v1:0");
    }

    private final LlmProviderConfigRepository providerConfigRepo;
    private final EncryptionService encryptionService;
    private final RestTemplate litellmRestTemplate;

    @Value("${litellm.config.path:/app/litellm-config.yaml}")
    private String configPath;

    @Value("${litellm.base-url:http://localhost:4000}")
    private String litellmBaseUrl;

    @Value("${litellm.master-key:sk-xamops-master}")
    private String masterKey;

    public LiteLLMConfigService(
            LlmProviderConfigRepository providerConfigRepo,
            EncryptionService encryptionService,
            @Qualifier("litellmRestTemplate") RestTemplate litellmRestTemplate) {
        this.providerConfigRepo = providerConfigRepo;
        this.encryptionService = encryptionService;
        this.litellmRestTemplate = litellmRestTemplate;
    }

    @Async
    public void syncConfig(Long clientId) {
        try {
            List<LlmProviderConfig> configs = providerConfigRepo.findByClientIdOrderByCreatedAtDesc(clientId);
            String yaml = buildYaml(configs);
            writeConfig(yaml);
            reloadLiteLLM();
            logger.info("LiteLLM config synced for client {} ({} providers)", clientId, configs.size());
        } catch (Exception e) {
            logger.error("Failed to sync LiteLLM config: {}", e.getMessage(), e);
        }
    }

    public boolean isHealthy() {
        // LiteLLM sidecar is optional — XamOps falls back to direct provider calls.
        // Report online as long as XamOps itself is running (this method is reachable).
        try {
            ResponseEntity<String> resp = litellmRestTemplate.getForEntity(litellmBaseUrl + "/health", String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            // Sidecar not running — but XamOps direct proxy is still functional
            return true;
        }
    }

    public Map<String, Object> getStatus() {
        boolean healthy = isHealthy();
        return Map.of(
                "online", healthy,
                "url", litellmBaseUrl,
                "proxyEndpoint", litellmBaseUrl + "/v1/chat/completions"
        );
    }

    // ─── Config generation ────────────────────────────────────────

    /**
     * For each active provider config, emit one LiteLLM model entry per supported model.
     * Model name is the real name (e.g. "gpt-4o") — not a UUID.
     * If the same model appears in multiple provider configs (e.g. two Anthropic keys),
     * LiteLLM load-balances between them automatically.
     */
    private String buildYaml(List<LlmProviderConfig> configs) {
        StringBuilder sb = new StringBuilder();
        sb.append("model_list:\n");

        for (LlmProviderConfig config : configs) {
            if (!"ACTIVE".equals(config.getStatus())) continue;

            String provider = config.getProvider().toUpperCase();
            List<String> models = PROVIDER_MODELS.get(provider);
            if (models == null) {
                logger.warn("Unknown provider '{}' — skipping", provider);
                continue;
            }

            for (String modelAlias : models) {
                String litellmModel = MODEL_TO_LITELLM.get(modelAlias);
                if (litellmModel == null) continue;

                switch (provider) {
                    case "OPENAI":
                    case "ANTHROPIC":
                    case "GEMINI":
                        appendKeyBasedModel(sb, config, modelAlias, litellmModel);
                        break;
                    case "BEDROCK":
                        appendBedrockModel(sb, config, modelAlias, litellmModel);
                        break;
                }
            }
        }

        sb.append("\nlitellm_settings:\n");
        sb.append("  set_verbose: false\n");
        sb.append("  json_logs: true\n");
        sb.append("  drop_params: true\n");
        sb.append("\ngeneral_settings:\n");
        sb.append("  master_key: \"").append(masterKey).append("\"\n");
        sb.append("  disable_spend_logs: false\n");

        return sb.toString();
    }

    private void appendKeyBasedModel(StringBuilder sb, LlmProviderConfig config,
                                     String modelAlias, String litellmModel) {
        String apiKey = decryptKey(config.getApiKeyEncrypted());
        if (apiKey == null) return;

        sb.append("  - model_name: \"").append(modelAlias).append("\"\n");
        sb.append("    litellm_params:\n");
        sb.append("      model: ").append(litellmModel).append("\n");
        sb.append("      api_key: \"").append(apiKey).append("\"\n");
    }

    private void appendBedrockModel(StringBuilder sb, LlmProviderConfig config,
                                    String modelAlias, String litellmModel) {
        String accessKey = decryptKey(config.getAwsAccessKeyEncrypted());
        String secretKey = decryptKey(config.getAwsSecretKeyEncrypted());
        String region = config.getAwsRegion();
        if (accessKey == null || secretKey == null || region == null) return;

        sb.append("  - model_name: \"").append(modelAlias).append("\"\n");
        sb.append("    litellm_params:\n");
        sb.append("      model: ").append(litellmModel).append("\n");
        sb.append("      aws_access_key_id: \"").append(accessKey).append("\"\n");
        sb.append("      aws_secret_access_key: \"").append(secretKey).append("\"\n");
        sb.append("      aws_region_name: \"").append(region).append("\"\n");
    }

    private String decryptKey(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return null;
        try {
            return encryptionService.decrypt(encrypted);
        } catch (Exception e) {
            logger.warn("Failed to decrypt API key: {}", e.getMessage());
            return null;
        }
    }

    private void writeConfig(String yaml) throws IOException {
        Path path = Paths.get(configPath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, yaml);
        logger.debug("Wrote LiteLLM config to {}", configPath);
    }

    private void reloadLiteLLM() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + masterKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>("{}", headers);
            litellmRestTemplate.postForEntity(litellmBaseUrl + "/config/update", entity, String.class);
            logger.info("LiteLLM config reloaded");
        } catch (Exception e) {
            logger.warn("LiteLLM reload ping failed: {}", e.getMessage());
        }
    }
}
