package com.xammer.cloud.service;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically checks whether observability agents are still reporting.
 *
 * Every other agent-health signal in the product is passive — it only reaches someone who
 * opens the Observability page. This is what turns "the dashboards are empty and nobody knows
 * why" into a message telling the account owner which host stopped and when.
 *
 * Runs every 30 minutes: agents are classified offline after 15 minutes of silence, so this
 * catches a genuine outage reasonably quickly without generating a mail storm over transient
 * blips. Alerts fire on transition, so a repeated scan over a still-silent host sends nothing.
 */
@Component
public class AgentSilenceAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentSilenceAlertScheduler.class);

    private final AgentSilenceAlertService agentSilenceAlertService;
    private final SchedulingEnvironmentConfig envConfig;

    public AgentSilenceAlertScheduler(AgentSilenceAlertService agentSilenceAlertService,
                                      SchedulingEnvironmentConfig envConfig) {
        this.agentSilenceAlertService = agentSilenceAlertService;
        this.envConfig = envConfig;
    }

    @Scheduled(cron = "0 5/30 * * * *")
    public void runAgentSilenceScan() {
        if (!envConfig.isSchedulingEnabled()) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            agentSilenceAlertService.runScan();
            log.debug("[AgentSilence] Scan finished in {} ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            // Never let this kill the shared single-threaded scheduler for other jobs.
            log.error("[AgentSilence] Scan failed", e);
        }
    }
}
