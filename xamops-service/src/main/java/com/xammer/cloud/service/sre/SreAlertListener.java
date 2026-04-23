package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.dto.sre.TriggerInvestigationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SreAlertListener {

    private static final Logger logger = LoggerFactory.getLogger(SreAlertListener.class);
    private static final Set<String> AUTO_TRIGGER_SEVERITIES = Set.of("CRITICAL", "HIGH", "critical", "high");

    private final SreInvestigationService investigationService;

    public SreAlertListener(SreInvestigationService investigationService) {
        this.investigationService = investigationService;
    }

    @EventListener
    @Async
    public void onAlertFired(SreAlertEvent event) {
        if (!AUTO_TRIGGER_SEVERITIES.contains(event.getSeverity())) {
            return;
        }

        logger.info("Auto-triggering SRE investigation for resource {} (severity: {})",
            event.getResourceId(), event.getSeverity());

        TriggerInvestigationRequest req = new TriggerInvestigationRequest();
        req.setCloudAccountId(event.getCloudAccountId());
        req.setResourceType(event.getResourceType());
        req.setResourceId(event.getResourceId());
        req.setResourceName(event.getResourceName());
        req.setAlertPayload(event.getAlertPayload());

        try {
            investigationService.createInvestigation(event.getClientId(), req, SreInvestigation.TriggerType.AUTO);
        } catch (Exception e) {
            logger.error("Failed to auto-trigger SRE investigation for resource {}: {}",
                event.getResourceId(), e.getMessage());
        }
    }
}
