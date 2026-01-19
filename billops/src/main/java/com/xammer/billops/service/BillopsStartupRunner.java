package com.xammer.billops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Logs application details on startup for better visibility and debugging.
 */
@Component
public class BillopsStartupRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(BillopsStartupRunner.class);

    private final Environment environment;

    public BillopsStartupRunner(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("========================================");
        logger.info("🚀 BillOps Application Startup Information:");
        logger.info("   - Profiles: {}", Arrays.toString(environment.getActiveProfiles()));
        logger.info("   - Java Version: {}", System.getProperty("java.version"));
        logger.info("   - OS: {} ({})", System.getProperty("os.name"), System.getProperty("os.arch"));
        logger.info("   - Server Port: {}", environment.getProperty("server.port", "8082"));
        logger.info("========================================");
    }
}
