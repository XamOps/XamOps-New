package com.xammer.cloud.service;

import com.xammer.cloud.repository.ClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On startup, syncs all existing provider configs to LiteLLM so the sidecar
 * has a valid config even after a service restart.
 */
@Component
public class LiteLLMStartupInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(LiteLLMStartupInitializer.class);

    private final ClientRepository clientRepository;
    private final LiteLLMConfigService liteLLMConfigService;

    public LiteLLMStartupInitializer(ClientRepository clientRepository,
                                     LiteLLMConfigService liteLLMConfigService) {
        this.clientRepository = clientRepository;
        this.liteLLMConfigService = liteLLMConfigService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            clientRepository.findAll().forEach(client ->
                    liteLLMConfigService.syncConfig(client.getId())
            );
            logger.info("LiteLLM config initialized for all clients");
        } catch (Exception e) {
            logger.warn("LiteLLM startup sync failed (non-fatal): {}", e.getMessage());
        }
    }
}
