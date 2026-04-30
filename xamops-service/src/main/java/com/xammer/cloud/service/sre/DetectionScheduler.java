package com.xammer.cloud.service.sre;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DetectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DetectionScheduler.class);
    private final AutoDetectionService autoDetectionService;

    public DetectionScheduler(AutoDetectionService autoDetectionService) {
        this.autoDetectionService = autoDetectionService;
    }

    @Scheduled(fixedDelayString = "${sre.detection.interval-ms:60000}")
    public void runDetectionCycle() {
        log.debug("SRE detection cycle starting");
        autoDetectionService.runCycle();
    }
}
