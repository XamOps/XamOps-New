package com.xammer.cloud.service;

import com.xammer.cloud.domain.LlmProviderConfig;
import com.xammer.cloud.repository.LlmProviderConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Keeps the LiteLLM proxy config in sync with provider configs stored in the DB.
 * Called after every add / update / delete of a provider config.
 */
@Service
public class LiteLLMConfigService {

    private static final Logger logger = LoggerFactory.getLogger(LiteLLMConfigService.class);

    private final LlmProviderConfigRepository providerConfigRepo;
    private final EncryptionService encryptionService;
    private final RestTemplate restTemplate;

    @Value("${litellm.config.path:/app/litellm-config.yaml}")
    private String configPath;

    @Value("${litellm.base-url:http://localhost:4000}")
    private String litellmBaseUrl;

    @Value("${litellm.master-key:sk-xamops-master}")
    private String masterKey;

    public LiteLLMConfigService(
            LlmProviderConfigRepository providerConfigRepo,
            EncryptionService encryptionService,
            RestTemplate restTemplate) {
        this.providerConfigRepo = providerConfigRepo;
        this.encryptionService = encryptionService;
        this.restTemplate = restTemplate;
    }

    /**
     * Regenerates the full LiteLLM config from all active provider configs in DB,
     * writes it to disk, then hot-reloads LiteLLM via its /reload API.
     */
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
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(litellmBaseUrl + "/health", String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
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

    private String buildYaml(List<LlmProviderConfig> configs) {
        StringBuilder sb = new StringBuilder();
        sb.append("model_list:\n");

        for (LlmProviderConfig config : configs) {
            if (!"ACTIVE".equals(config.getStatus())) continue;

            String provider = config.getProvider().toUpperCase();
            String modelName = config.getProxyEndpointId(); // unique alias per config

            switch (provider) {
                case "OPENAI":
                    appendOpenAI(sb, config, modelName);
                    break;
                case "ANTHROPIC":
                    appendAnthropic(sb, config, modelName);
                    break;
                case "GEMINI":
                    appendGemini(sb, config, modelName);
                    break;
                case "BEDROCK":
                    appendBedrock(sb, config, modelName);
                    break;
                default:
                    logger.warn("Unknown provider '{}' — skipping in LiteLLM config", provider);
            }
        }

        sb.append("\n");
        sb.append("litellm_settings:\n");
        sb.append("  set_verbose: false\n");
        sb.append("  json_logs: true\n");
        sb.append("  drop_params: true\n");
        sb.append("\n");
        sb.append("general_settings:\n");
        sb.append("  master_key: \"").append(masterKey).append("\"\n");
        sb.append("  disable_spend_logs: false\n");

        return sb.toString();
    }

    private void appendOpenAI(StringBuilder sb, LlmProviderConfig config, String alias) {
        String apiKey = decryptKey(config.getApiKeyEncrypted());
        if (apiKey == null) return;

        sb.append("  - model_name: \"").append(alias).append("\"\n");
        sb.append("    litellm_params:\n");
        sb.append("      model: openai/gpt-4o\n");
        sb.append("      api_key: \"").append(apiKey).append("\"\n");
    }

    private void appendAnthropic(StringBuilder sb, LlmProviderConfig config, String alias) {
        String apiKey = decryptKey(config.getApiKeyEncrypted());
        if (apiKey == null) return;

        sb.append("  - model_name: \"").append(alias).append("\"\n");
        sb.append("    litellm_params:\n");
        sb.append("      model: anthropic/claude-sonnet-4-6\n");
        sb.append("      api_key: \"").append(apiKey).append("\"\n");
    }

    private void appendGemini(StringBuilder sb, LlmProviderConfig config, String alias) {
        String apiKey = decryptKey(config.getApiKeyEncrypted());
        if (apiKey == null) return;

        sb.append("  - model_name: \"").append(alias).append("\"\n");
        sb.append("    litellm_params:\n");
        sb.append("      model: gemini/gemini-2.0-flash\n");
        sb.append("      api_key: \"").append(apiKey).append("\"\n");
    }

    private void appendBedrock(StringBuilder sb, LlmProviderConfig config, String alias) {
        String accessKey = decryptKey(config.getAwsAccessKeyEncrypted());
        String secretKey = decryptKey(config.getAwsSecretKeyEncrypted());
        String region = config.getAwsRegion();
        if (accessKey == null || secretKey == null || region == null) return;

        sb.append("  - model_name: \"").append(alias).append("\"\n");
        sb.append("    litellm_params:\n");
        sb.append("      model: bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0\n");
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
            restTemplate.postForEntity(litellmBaseUrl + "/config/update", entity, String.class);
            logger.info("LiteLLM config reloaded");
        } catch (Exception e) {
            logger.warn("LiteLLM reload ping failed (will take effect on next request): {}", e.getMessage());
        }
    }
}
